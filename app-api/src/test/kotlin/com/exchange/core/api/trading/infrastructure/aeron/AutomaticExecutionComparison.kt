package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode

/** 확정 실행 위치 이전에 끝난 승인만 pending에서 뺀다. 금융 정답은 기존 독립 fixture를 그대로 쓴다. */
internal fun pendingStateAtPosition(
    expected: Map<String, Any?>,
    events: List<JsonNode>,
    position: Long,
): Map<String, Any?> {
    val completed =
        events
            .filter {
                it.path("kind").asString() == "APPLIED" && it.path("inputKind").asString() == "EXECUTION" &&
                    (observedApplicationPosition(it) ?: Long.MAX_VALUE) <= position
            }.map { it.path("executionSequence").asLong() }
            .toSet()
    val pending =
        (expected["pendingOrders"] as? List<*>).orEmpty().filter {
            ((it as Map<*, *>)["sequence"] as Number).toLong() !in completed
        }
    return expected + mapOf("pendingOrders" to pending, "pendingOrderCount" to pending.size)
}

/** 외부 checkpoint만으로 완료를 추측하지 않고 각 승인에 대응하는 실제 내부 확정 위치를 모두 대조한다. */
internal fun compareAutomaticExecutionCoverage(
    runId: String,
    eventsByMember: Map<Int, List<JsonNode>>,
    checkpoints: List<Pair<OrderReplicationStep, Long?>>,
): List<FundingVerificationIssue> {
    val issues = mutableListOf<FundingVerificationIssue>()
    val accepted =
        checkpoints
            .filter { it.first.expectedResult["outcome"] == "ACCEPTED" }
            .distinctBy { (it.first.expectedResult["sequence"] as Number).toLong() }
    val expectedSequences = accepted.map { (it.first.expectedResult["sequence"] as Number).toLong() }.toSet()
    val executions =
        eventsByMember.mapValues { (_, events) ->
            events.filter {
                it.path("kind").asString() == "APPLIED" && it.path("runId").asString() == runId &&
                    it.path("inputKind").asString() == "EXECUTION"
            }
        }
    for ((member, events) in executions) {
        if (member !in 0..2 && events.isNotEmpty()) issues.add(FundingVerificationIssue("MISMATCH", "계획 밖 실행 사본 node$member"))
        for (event in events) {
            if (event.path("executionSequence").asLong() !in expectedSequences) {
                issues.add(FundingVerificationIssue("MISMATCH", "node$member 계획 밖 내부 실행: $event"))
            }
        }
    }
    for ((step, admissionPosition) in accepted) {
        val sequence = (step.expectedResult.getValue("sequence") as Number).toLong()
        val byMember =
            (0..2).associateWith { member ->
                executions[member].orEmpty().filter {
                    it.path("executionSequence").asLong() ==
                        sequence
                }
            }
        val positions =
            byMember.values
                .flatten()
                .mapNotNull(::observedApplicationPosition)
                .distinct()
        val position = positions.singleOrNull()
        if (position == null || admissionPosition == null || position <= admissionPosition || byMember.values.any { it.size != 1 }) {
            issues.add(
                FundingVerificationIssue(
                    "UNVERIFIABLE",
                    "승인 $sequence 내부 실행 세 사본·단일 위치 미확인: admission=$admissionPosition, executions=$positions, counts=${byMember.mapValues {
                        it.value.size
                    }}",
                ),
            )
            continue
        }
        val reference = byMember.getValue(0).single()
        for ((member, values) in byMember) {
            val event = values.single()
            val prefix = "execute$sequence.node$member"
            if (event.path("state") != reference.path("state") || event.path("trading") != reference.path("trading")) {
                issues.add(FundingVerificationIssue("MISMATCH", "$prefix 같은 위치 $position 전체 업무 상태가 node0과 다릅니다"))
            }
            val prior = checkpoints.filter { it.second != null && it.second!! < position }.maxByOrNull { it.second!! }?.first
            val expectedState = step.executionState ?: prior?.expectedState ?: step.expectedState
            compareOrderFields(
                prefix + ".state",
                event.path("state"),
                pendingStateAtPosition(expectedState, executions[member].orEmpty(), position),
                issues,
            )
            compareOrderFields(
                prefix + ".trading",
                event.path("trading"),
                step.executionTrading ?: restingOnlyExpectedTrading(expectedState, executions[member].orEmpty(), position),
                issues,
            )
            val completion = event.at("/trading/completions").filter { it.path("admissionSequence").asLong() == sequence }
            if (completion.size != 1 || completion.single().path("executionId").asString() != "EXECUTE:v1:$sequence") {
                issues.add(FundingVerificationIssue("MISMATCH", "$prefix 전체 완료 누락 또는 중복: $completion"))
            }
            if (event.at("/state/pendingOrders").any { it.path("sequence").asLong() == sequence }) {
                issues.add(FundingVerificationIssue("MISMATCH", "$prefix 완료한 승인이 처리 대기에 남았습니다"))
            }
            val timestamp = event.at("/trading/callbackTimestamp")
            if (!timestamp.isIntegralNumber || !timestamp.canConvertToLong()) {
                issues.add(FundingVerificationIssue("UNVERIFIABLE", "$prefix 실행 callback 시각 누락: $timestamp"))
            } else {
                val executionTime = java.time.Instant.ofEpochMilli(timestamp.longValue())
                val completedAt = completion.singleOrNull()?.path("occurredAt")?.asString()
                if (completedAt !=
                    executionTime.toString()
                ) {
                    issues.add(FundingVerificationIssue("MISMATCH", "$prefix 완료 시각 expected=$executionTime, actual=$completedAt"))
                }
                completion.singleOrNull()?.path("settlements")?.forEach { settlement ->
                    compareOrderFields(
                        "$prefix.settlement.execution.time",
                        settlement,
                        mapOf("occurredAt" to executionTime.toString()),
                        issues,
                    )
                }
                val transactions = event.at("/state/transactions").filter { it.path("kind").asString() == "SETTLEMENT" }
                val ownIds: Set<String> =
                    completion
                        .singleOrNull()
                        ?.path("settlements")
                        ?.toList()
                        ?.map { it.path("ledgerTransactionId").asString() }
                        ?.toSet()
                        ?: emptySet()
                transactions.filter { it.path("transactionId").asString() in ownIds }.forEach { transaction ->
                    compareOrderFields(
                        prefix + ".settlement.time",
                        transaction,
                        mapOf(
                            "occurredAt" to executionTime.toString(),
                            "occurredAtEpochSecond" to executionTime.epochSecond,
                            "occurredAtNano" to executionTime.nano,
                        ),
                        issues,
                    )
                }
            }
            if (event.hasNonNull("failure")) issues.add(FundingVerificationIssue("UNVERIFIABLE", "$prefix 실패: ${event.path("failure")}"))
        }
    }
    return issues.distinct()
}

/** 기존 회귀 사례는 상대 없는 같은 방향 주문이다. 주문장의 수량·순서·완료도 입력 fixture에서 정한다. */
internal fun restingOnlyExpectedTrading(
    expectedState: Map<String, Any?>,
    executions: List<JsonNode>,
    position: Long,
): Map<String, Any?> {
    val completed =
        executions
            .filter { (observedApplicationPosition(it) ?: Long.MAX_VALUE) <= position }
            .map { it.path("executionSequence").asLong() }
            .toSet()

    @Suppress("UNCHECKED_CAST")
    val records = (expectedState["pendingOrders"] as? List<Map<String, Any?>>).orEmpty()
    val ordered = records.filter { (it.getValue("sequence") as Number).toLong() in completed }

    fun candidate(record: Map<String, Any?>): Map<*, *> = record.getValue("candidate") as Map<*, *>

    fun order(record: Map<String, Any?>): Map<*, *> = candidate(record)["order"]!! as Map<*, *>
    val byMarket = ordered.groupBy { order(it)["marketId"]!! }
    val requestedMarkets =
        (expectedState["requests"] as? List<*>)
            .orEmpty()
            .map {
                ((it as Map<*, *>)["order"] as Map<*, *>)["marketId"]!!
            }.distinct()
            .sortedBy { it.toString() }
    val books =
        requestedMarkets.map { market ->
            val entries = byMarket[market].orEmpty()
            mapOf(
                "marketId" to market,
                "lastEngineSequence" to entries.size,
                "orders" to
                    entries.map { record ->
                        val source = order(record)
                        mapOf(
                            "orderId" to source["orderId"],
                            "userId" to source["userId"],
                            "side" to source["side"],
                            "price" to source["price"],
                            "originalQuantity" to source["quantity"],
                            "remainingQuantity" to source["quantity"],
                        )
                    },
            )
        }
    return mapOf(
        "currentReservations" to records.map { candidate(it)["reservation"]!! },
        "books" to books,
        "completions" to
            ordered.map { record ->
                val sequence = (record.getValue("sequence") as Number).toLong()
                val source = order(record)
                val sameMarket = ordered.filter { order(it)["marketId"] == source["marketId"] }
                val eventSequence = sameMarket.indexOf(record) + 1
                mapOf(
                    "admissionSequence" to sequence,
                    "executionId" to "EXECUTE:v1:$sequence",
                    "marketId" to source["marketId"],
                    "orderId" to source["orderId"],
                    "expectedEventCount" to 1,
                    "firstEngineSequence" to eventSequence,
                    "lastEngineSequence" to eventSequence,
                    "events" to
                        listOf(
                            mapOf(
                                "kind" to "ORDER_ENTERED_BOOK",
                                "marketId" to source["marketId"],
                                "engineSequence" to eventSequence,
                                "orderId" to source["orderId"],
                                "userId" to source["userId"],
                                "side" to source["side"],
                                "price" to source["price"],
                                "remainingQuantity" to source["quantity"],
                            ),
                        ),
                    "settlements" to emptyList<Any>(),
                    "balanceChanges" to emptyList<Any>(),
                    "reservationChanges" to emptyList<Any>(),
                )
            },
    )
}

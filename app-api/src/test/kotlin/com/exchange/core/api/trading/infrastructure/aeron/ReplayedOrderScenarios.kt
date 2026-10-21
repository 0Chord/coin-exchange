package com.exchange.core.api.trading.infrastructure.aeron

/** 같은 R1의 여러 전송을 구분한다. 정답은 엔진이나 실제 관찰에서 만들지 않는다. */
internal fun acceptedReplaySteps(): List<OrderReplicationStep> {
    val accepted = replayRecord("L-RESERVE-1", 1)
    val firstRequest = replayRequest("R1", "L-RESERVE-1", 1, replayStoredAcceptance(accepted))
    val approvedState = replayState(funded = true, accepted, listOf(firstRequest))
    val duplicate = replayRequest("R2", "L-DUPLICATE-2", 5, replayStoredRejection("DUPLICATE_ORDER"))
    val duplicateState = replayState(funded = true, accepted, listOf(firstRequest, duplicate))
    return listOf(
        replayOpening("A-0"),
        replayOrder("A-1", "R1", "L-RESERVE-1", 1, replayAccepted("R1"), approvedState),
        replayOrder("A-2", "R1", "L-RESERVE-1", 1, replayAccepted("R1"), approvedState),
        replayOrder("A-3", "R1", "L-RETRY-3", 3, replayAccepted("R1"), approvedState, policyVersion = 2),
        replayOrder(
            "A-4",
            "R1",
            "L-CONFLICT-4",
            4,
            replayRejected("R1", "REQUEST_CONTENT_CONFLICT"),
            approvedState,
            price = 101_000_000,
            policyVersion = 2,
        ),
        replayOrder("A-5", "R2", "L-DUPLICATE-2", 5, replayRejected("R2", "DUPLICATE_ORDER"), duplicateState),
        replayOrder("A-6", "R2", "L-RETRY-6", 6, replayRejected("R2", "DUPLICATE_ORDER"), duplicateState, policyVersion = 2),
    )
}

/** 첫 부족 거절은 입금 후에도, 같은 주문이 다른 요청으로 승인된 후에도 그대로다. */
internal fun rejectedReplaySteps(): List<OrderReplicationStep> {
    val rejection = replayRequest("R1", "L-REJECT-1", 1, replayStoredRejection("INSUFFICIENT_BALANCE"))
    val emptyRejected = replayState(funded = false, requests = listOf(rejection))
    val fundedRejected = replayState(funded = true, requests = listOf(rejection))
    val accepted = replayRecord("L-RESERVE-2", 3)
    val acceptance = replayRequest("R2", "L-RESERVE-2", 3, replayStoredAcceptance(accepted))
    val admitted = replayState(funded = true, accepted, listOf(rejection, acceptance))
    return listOf(
        replayOrder("B-0", "R1", "L-REJECT-1", 1, replayRejected("R1", "INSUFFICIENT_BALANCE"), emptyRejected),
        replayOpening("B-1", fundedRejected),
        replayOrder(
            "B-2",
            "R1",
            "L-RETRY-2",
            2,
            replayRejected("R1", "INSUFFICIENT_BALANCE"),
            fundedRejected,
            policyVersion = 2,
        ),
        replayOrder("B-3", "R2", "L-RESERVE-2", 3, replayAccepted("R2"), admitted),
        replayOrder("B-4", "R1", "L-REJECT-1", 1, replayRejected("R1", "INSUFFICIENT_BALANCE"), admitted),
    )
}

/** 새 요청의 정책 불일치는 최초 결과로 저장하지 않아 같은 R1을 바로잡을 수 있다. */
internal fun contextCorrectionSteps(): List<OrderReplicationStep> {
    val accepted = replayRecord("L-RESERVE-1", 2)
    val request = replayRequest("R1", "L-RESERVE-1", 2, replayStoredAcceptance(accepted))
    return listOf(
        replayOpening("C-0"),
        replayOrder(
            "C-1",
            "R1",
            "L-CONTEXT-1",
            1,
            replayRejected("R1", "EXECUTION_CONTEXT_MISMATCH"),
            replayState(funded = true),
            policyVersion = 2,
        ),
        replayOrder("C-2", "R1", "L-RESERVE-1", 2, replayAccepted("R1"), replayState(funded = true, accepted, listOf(request))),
    )
}

private fun replayOpening(
    name: String,
    state: Map<String, Any?> = replayState(funded = true),
) = OrderReplicationStep(
    name,
    "FUNDING",
    listOf("P1", "U1", "USDT", "1000000000", "L-OPEN-1", replayTime(0)),
    replayFundingResult(),
    state,
)

private fun replayOrder(
    name: String,
    requestId: String,
    ledgerId: String,
    second: Int,
    result: Map<String, Any?>,
    state: Map<String, Any?>,
    price: Long = 100_000_000,
    policyVersion: Long? = null,
) = OrderReplicationStep(
    name,
    "ORDER",
    listOf(requestId, "U1", "O1", "BUY", price.toString(), "300000000", ledgerId, replayTime(second)) +
        if (policyVersion == null) emptyList() else listOf(policyVersion.toString()),
    result,
    state,
)

private fun replayState(
    funded: Boolean,
    accepted: Map<String, Any?>? = null,
    requests: List<Map<String, Any?>> = emptyList(),
): Map<String, Any?> {
    val reserve = accepted?.get("candidate") as? Map<*, *>
    return mapOf(
        "balances" to
            when {
                accepted != null -> listOf(replayBalance(700_000_000, 300_000_000))
                funded -> listOf(replayBalance(1_000_000_000, 0))
                else -> emptyList()
            },
        "preparationCount" to if (funded) 1 else 0,
        "pendingOrderCount" to if (accepted == null) 0 else 1,
        "requestRecordCount" to requests.size,
        "lastSequence" to if (accepted == null) 0 else 1,
        "preparations" to if (funded) listOf(replayFundingResult()) else emptyList(),
        "pendingOrders" to listOfNotNull(accepted),
        "requests" to requests,
        "transactions" to
            (if (funded) listOf(replayOpeningTransaction()) else emptyList()) + listOfNotNull(reserve?.get("reserveTransaction")),
    )
}

private fun replayFundingResult(): Map<String, Any?> =
    mapOf(
        "preparationId" to "P1",
        "userId" to "U1",
        "assetId" to "USDT",
        "amount" to 1_000_000_000L,
        "ledgerTransactionId" to "L-OPEN-1",
        "occurredAt" to replayTime(0),
        "alreadyPrepared" to false,
    )

private fun replayRecord(
    ledgerId: String,
    second: Int,
): Map<String, Any?> =
    mapOf(
        "sequence" to 1,
        "candidate" to
            mapOf(
                "order" to replayOrderFields(),
                "market" to replayMarket(),
                "feePolicy" to replayPolicy(),
                "previousBalance" to replayBalance(1_000_000_000, 0),
                "nextBalance" to replayBalance(700_000_000, 300_000_000),
                "reservation" to
                    mapOf(
                        "marketId" to "BTC-USDT",
                        "orderId" to "O1",
                        "userId" to "U1",
                        "side" to "BUY",
                        "assetId" to "USDT",
                        "limitPrice" to 100_000_000L,
                        "initialQuantity" to 300_000_000L,
                        "remainingQuantity" to 300_000_000L,
                        "reservedAmount" to 300_000_000L,
                        "remainingAmount" to 300_000_000L,
                        "feePolicy" to replayPolicy(),
                        "initialFeeReserveAmount" to 0L,
                        "remainingFeeReserveAmount" to 0L,
                        "status" to "ACTIVE",
                        "feeRemainder" to 0L,
                        "releasedAmount" to null,
                    ),
                "reserveTransaction" to
                    replayTransaction(
                        ledgerId,
                        "RESERVE:v1:dd2c94ecf984651e09ce084d6492c1b1b5c51256a807608ef55966c9bc56e47e",
                        "RESERVE",
                        second,
                        300_000_000,
                        "USER:U1:USDT:AVAILABLE",
                        "USER:U1:USDT:HOLD",
                    ),
            ),
    )

private fun replayRequest(
    requestId: String,
    ledgerId: String,
    second: Int,
    result: Map<String, Any?>,
): Map<String, Any?> =
    mapOf(
        "requestId" to requestId,
        "order" to replayOrderFields(),
        "market" to replayMarket(),
        "feePolicy" to replayPolicy(),
        "ledgerTransactionId" to ledgerId,
        "result" to result,
    ) + replayInstant(second)

private fun replayStoredAcceptance(record: Map<String, Any?>): Map<String, Any?> = mapOf("outcome" to "ACCEPTED", "record" to record)

private fun replayStoredRejection(reason: String): Map<String, Any?> =
    mapOf(
        "outcome" to "REJECTED",
        "reason" to reason,
        "insufficientBalance" to if (reason == "INSUFFICIENT_BALANCE") replayShortage() else null,
    )

private fun replayAccepted(requestId: String): Map<String, Any?> =
    replayIdentifiers(requestId) + mapOf("outcome" to "ACCEPTED", "sequence" to 1)

private fun replayRejected(
    requestId: String,
    reason: String,
): Map<String, Any?> = replayIdentifiers(requestId) + replayStoredRejection(reason)

private fun replayIdentifiers(requestId: String): Map<String, Any?> =
    mapOf("userId" to "U1", "requestId" to requestId, "marketId" to "BTC-USDT", "orderId" to "O1")

private fun replayShortage(): Map<String, Any?> =
    mapOf("assetId" to "USDT", "available" to 0L, "required" to 300_000_000L, "shortfall" to 300_000_000L)

private fun replayBalance(
    available: Long,
    hold: Long,
): Map<String, Any?> = mapOf("userId" to "U1", "assetId" to "USDT", "available" to available, "hold" to hold)

private fun replayOrderFields(): Map<String, Any?> =
    mapOf(
        "marketId" to "BTC-USDT",
        "orderId" to "O1",
        "userId" to "U1",
        "side" to "BUY",
        "orderType" to "LIMIT",
        "timeInForce" to "GTC",
        "price" to 100_000_000L,
        "quantity" to 300_000_000L,
    )

private fun replayMarket(): Map<String, Any?> =
    mapOf("marketId" to "BTC-USDT", "baseAssetId" to "BTC", "quoteAssetId" to "USDT", "baseQuantityScale" to 8)

private fun replayPolicy(): Map<String, Any?> =
    mapOf("productType" to "SPOT", "feeTier" to "NORMAL", "scheduleVersion" to 1, "makerPartsPerMillion" to 0, "takerPartsPerMillion" to 0)

private fun replayOpeningTransaction(): Map<String, Any?> =
    replayTransaction("L-OPEN-1", "OPENING:P1", "OPENING", 0, 1_000_000_000, "SYSTEM:USDT:DEVELOPMENT_FUNDING", "USER:U1:USDT:AVAILABLE")

private fun replayTransaction(
    id: String,
    source: String,
    kind: String,
    second: Int,
    amount: Long,
    debit: String,
    credit: String,
): Map<String, Any?> =
    mapOf(
        "transactionId" to id,
        "sourceEventId" to source,
        "kind" to kind,
        "postings" to
            listOf(
                mapOf("accountId" to debit, "assetId" to "USDT", "side" to "DEBIT", "amount" to amount),
                mapOf("accountId" to credit, "assetId" to "USDT", "side" to "CREDIT", "amount" to amount),
            ),
    ) + replayInstant(second)

private fun replayInstant(second: Int): Map<String, Any?> =
    mapOf("occurredAt" to replayTime(second), "occurredAtEpochSecond" to 1_792_540_800L + second, "occurredAtNano" to 123_456_789)

private fun replayTime(second: Int) = "2026-10-21T00:00:${second.toString().padStart(2, '0')}.123456789Z"

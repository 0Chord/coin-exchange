package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

private const val RECOVERY_FUNDS_BYTE_LIMIT = 1_048_576L
private const val RECOVERY_MATCHING_BYTE_LIMIT = 2_097_152L

/** #134의 다섯 입력에 대한 수기 기대값이다. 기본값은 첫 BUY3의 결과 확인 뒤 상태다. */
internal data class RecoveryExpected(
    val inputs: Long = 1,
    val fundsThrough: Long = 1,
    val matchingAck: Long = 1,
    val settled: Long = 1,
    val buyerQ: Long = 697,
    val buyerHold: Long = 303,
    val buyerX: Long = 0,
    val buyerXHold: Long = 0,
    val sellerQ: Long = 100,
    val sellerX: Long = 3,
    val sellerHold: Long = 0,
    val live: Long = 1,
    val quantity: Long = 3,
    val pendingOrders: Long = 0,
    val dispatches: Long = 0,
    val book: Long = 1,
    val bookQuantity: Long = 3,
    val matchingThrough: Long = inputs,
    val engine: Long = matchingThrough,
    val admissions: Long = 1,
    val cancellations: Long = 0,
    val pendingCancellations: Long = 0,
    val trades: Long = 0,
    val feeQ: Long = 0,
    val sellerQuoteHold: Long = 0,
    val retiredThrough: Long = 0,
) {
    fun matches(
        states: Map<String, JsonNode>,
        expectedBoots: Map<String, String> = emptyMap(),
    ): Boolean = problems(states, this, expectedBoots).isEmpty()

    internal fun fields(role: String): Map<String, Long> =
        if (role == "funds") {
            mapOf(
                "buyerQ" to buyerQ,
                "buyerQHold" to buyerHold,
                "buyerX" to buyerX,
                "buyerXHold" to buyerXHold,
                "sellerQ" to sellerQ,
                "sellerQHold" to sellerQuoteHold,
                "sellerX" to sellerX,
                "sellerXHold" to sellerHold,
                "feeQ" to feeQ,
                "liveOrders" to live,
                "activeOrders" to live,
                "remainingQuantity" to quantity,
                "pendingOrders" to pendingOrders,
                "dispatchEntries" to dispatches,
                "issued" to inputs,
                "matchingAppliedThrough" to matchingAck,
                "admissionIssued" to admissions,
                "cancellationIssued" to cancellations,
                "pendingCancellations" to pendingCancellations,
                "executedThrough" to admissions - pendingOrders,
                "resultThrough" to fundsThrough,
                "retiredThrough" to retiredThrough,
                "receiptEntries" to fundsThrough,
                "settlementTrades" to trades,
            )
        } else {
            mapOf(
                "bookOrders" to book,
                "bookQuantity" to bookQuantity,
                "activeOrders" to book,
                "engineSequence" to engine,
                "appliedThrough" to matchingThrough,
                "resultIssued" to matchingThrough,
                "resultEntries" to matchingThrough - settled,
                "fundsSettledThrough" to settled,
            )
        }
}

/** 빈 관찰을 성공으로 세지 않고 각 역할의 현재 세 사본을 모두 검사한다. */
internal fun problems(
    states: Map<String, JsonNode>,
    expected: RecoveryExpected,
    expectedBoots: Map<String, String> = emptyMap(),
): List<String> {
    val copies = listOf("funds0", "funds1", "funds2", "matching0", "matching1", "matching2")
    val issues = mutableListOf<String>()
    if (states.keys != copies.toSet()) issues += "사본 목록: expected=$copies actual=${states.keys.sorted()}"
    for (name in expectedBoots.keys - copies.toSet()) issues += "$name: 알 수 없는 boot 기대값"
    for (name in copies) {
        val state = states[name] ?: continue
        val role = if (name.startsWith("funds")) "funds" else "matching"
        if (!state.path("role").isTextual || state.path("role").asString() != role.uppercase()) issues += "$name.role: 역할 불일치"
        if (integer(state.path("memberId")) != name.last().digitToInt().toLong()) issues += "$name.memberId: 사본 번호 불일치"
        if (!state.path("initialized").isBoolean || !state.path("initialized").asBoolean()) issues += "$name.initialized: 준비되지 않음"
        if (!state.path("lifecycle").isTextual || state.path("lifecycle").asString() != "RUNNING") issues += "$name.lifecycle: RUNNING 아님"
        val boot = state.path("bootId")
        if (!boot.isTextual || boot.asString().isBlank()) {
            issues += "$name.bootId: 현재 실행 식별 누락"
        } else if (name in expectedBoots && boot.asString() != expectedBoots.getValue(name)) {
            issues += "$name.bootId: 이전 실행 관찰"
        }
        val values = state.path(role)
        for ((field, target) in expected.fields(role)) {
            val actual = integer(values.path(field))
            if (actual != target) issues += "$name.$field: expected=$target actual=$actual"
        }
        val entries = if (role == "funds") expected.dispatches else expected.matchingThrough - expected.settled
        val byteLimit = if (role == "funds") RECOVERY_FUNDS_BYTE_LIMIT else RECOVERY_MATCHING_BYTE_LIMIT
        checkBytes(issues, name, values, if (role == "funds") "dispatchBytes" else "resultBytes", entries, byteLimit)
        checkBytes(issues, name, values, "activeBytes", if (role == "funds") expected.live else expected.book, byteLimit)
    }
    return issues
}

/** 실행마다 다른 시각·ID·위치를 제외하고 실제 소유자의 결과와 대조할 값만 반환한다. */
internal fun recoverySemanticView(state: JsonNode): Map<String, Long> {
    val role =
        when (state.path("role").asString()) {
            "FUNDS" -> "funds"
            "MATCHING" -> "matching"
            else -> error("관찰의 역할을 알 수 없습니다")
        }
    val byteFields = if (role == "funds") listOf("dispatchBytes", "activeBytes") else listOf("resultBytes", "activeBytes")
    return (RecoveryExpected().fields(role).keys + byteFields).associateWith { field ->
        requireNotNull(integer(state.path(role).path(field))) { "$role.$field: 정수 관찰값이 필요합니다" }
    }
}

/** 최종 다섯 결과의 대상·수량·사건·효과를 보관 중인 최초 응답의 ID와 대조한다. */
internal fun recoveryMeaningProblems(
    state: JsonNode,
    firstCancel: JsonNode,
): List<String> {
    val issues = mutableListOf<String>()
    if (state.path("role").asString() != "FUNDS") return listOf("의미 비교에는 자금 사본이 필요합니다")
    // JSON 정수의 내부 Int/Long 노드 차이는 의미 차이로 세지 않는다. 문자열·실수는 그대로 구분한다.
    val mapper = jacksonObjectMapper()
    val funds = mapper.readTree(state.toString()).path("funds")

    fun tree(value: Any?): JsonNode = mapper.readTree(mapper.writeValueAsString(value))

    fun text(value: JsonNode): String? = if (value.isTextual) value.asString().takeIf { it.isNotBlank() } else null

    val channels = funds.path("inboxChannels")
    if (!channels.isArray || channels.size() != 2) issues += "inboxChannels: 두 호출자 채널이 필요합니다"
    for (channel in channels) {
        if (text(channel.path("userId")) == null || integer(channel.path("channel")) != 0L || integer(channel.path("epoch")) != 1L) {
            issues += "inboxChannels: 호출자·채널·요청 세대 불일치"
        }
        if (!channel.path("orders").isArray || !channel.path("cancellations").isArray) issues += "inboxChannels: 응답 배열 누락"
    }
    val orders = channels.flatMap { it.path("orders").toList() }
    val sequences = orders.map { integer(it.path("admissionSequence")) }
    val orderIds = orders.map { text(it.path("orderId")) }
    if (orders.size != 4 || sequences.toSet() != (1L..4L).toSet() ||
        orderIds.any { it == null } || orderIds.distinct().size != 4 || orders.any { it.path("code").asString() != "ACCEPTED" }
    ) {
        issues += "inboxChannels.orders: 고유한 ACCEPTED 신규 승인1~4가 필요합니다"
        return issues
    }
    val ids = orders.associate { requireNotNull(integer(it.path("admissionSequence"))) to requireNotNull(text(it.path("orderId"))) }
    val requests = mapOf(1L to 1L, 2L to 1L, 3L to 4L, 4L to 2L)
    for (order in orders) {
        val sequence = requireNotNull(integer(order.path("admissionSequence")))
        if (integer(order.path("requestSequence")) != requests.getValue(sequence)) issues += "inboxChannels.orders[$sequence]: 요청 번호 불일치"
    }
    val buyerChannels = channels.filter { channel -> channel.path("orders").any { it.path("orderId").asString() == ids.getValue(1) } }
    val sellerChannels = channels.filter { channel -> channel.path("orders").any { it.path("orderId").asString() == ids.getValue(2) } }
    if (buyerChannels.size != 1 || sellerChannels.size != 1 ||
        buyerChannels.single().path("userId") == sellerChannels.single().path("userId") ||
        buyerChannels
            .single()
            .path("orders")
            .toList()
            .map { text(it.path("orderId")) }
            .toSet() != setOf(ids.getValue(1), ids.getValue(3)) ||
        sellerChannels
            .single()
            .path("orders")
            .toList()
            .map { text(it.path("orderId")) }
            .toSet() != setOf(ids.getValue(2), ids.getValue(4))
    ) {
        issues += "inboxChannels.orders: 최초 주문과 후속 주문의 호출자 불일치"
    }
    val cancelId = text(firstCancel.path("cancelId"))
    if (firstCancel.path("code").asString() != "ACCEPTED" || cancelId == null ||
        text(firstCancel.path("targetOrderId")) != ids.getValue(1) || integer(firstCancel.path("inputSequence")) != 3L
    ) {
        issues += "firstCancel: 최초 주문을 가리키는 입력3 취소 응답이 필요합니다"
    }
    val cancellations = channels.flatMap { it.path("cancellations").toList() }
    val cancellation =
        tree(
            mapOf(
                "requestSequence" to 2L,
                "code" to "ACCEPTED",
                "cancelId" to cancelId,
                "targetOrderId" to ids.getValue(1),
                "inputSequence" to 3L,
            ),
        )
    if (cancellations.size != 1 || cancellations.single() != cancellation ||
        buyerChannels.singleOrNull()?.path("cancellations")?.toList() != cancellations
    ) {
        issues += "inboxChannels.cancellations: 최초 취소 ID·대상·요청·입력의 결속 불일치"
    }
    val details = funds.path("resultDetails")
    if (!details.isArray || details.toList().map { integer(it.path("sequence")) } != (1L..5L).toList()) {
        issues += "resultDetails: 결과1~5가 순서대로 하나씩 필요합니다"
        return issues
    }
    val resultIds = details.toList().map { text(it.path("resultId")) }
    if (resultIds.any { it == null } || resultIds.distinct().size != 5) issues += "resultDetails.resultId: 비어 있지 않은 고유 ID 다섯 개가 필요합니다"

    fun effect(
        admission: Long,
        origin: Long,
        before: Long,
        after: Long,
        disposition: String,
    ): Map<String, Any> =
        mapOf(
            "orderId" to ids.getValue(admission),
            "originInputSequence" to origin,
            "beforeQuantity" to before,
            "afterQuantity" to after,
            "disposition" to disposition,
        )

    for ((index, result) in details.withIndex()) {
        val sequence = index + 1L
        val admission = listOf(1L, 2L, 1L, 3L, 4L)[index]
        val event =
            mapOf<String, Any>("engineSequence" to sequence) +
                when (sequence) {
                    2L, 5L -> {
                        val maker = if (sequence == 2L) 1L else 3L
                        val taker = if (sequence == 2L) 2L else 4L
                        mapOf(
                            "kind" to "TRADE",
                            "makerOrderId" to ids.getValue(maker),
                            "takerOrderId" to ids.getValue(taker),
                            "quantity" to 1L,
                            "price" to 100L,
                        )
                    }

                    3L -> {
                        mapOf("kind" to "CANCELLED", "orderId" to ids.getValue(1), "quantity" to 2L)
                    }

                    else -> {
                        mapOf(
                            "kind" to "RESTING",
                            "orderId" to ids.getValue(admission),
                            "quantity" to
                                if (sequence ==
                                    1L
                                ) {
                                    3L
                                } else {
                                    1L
                                },
                            "price" to 100L,
                        )
                    }
                }
        val effects =
            when (sequence) {
                1L -> listOf(effect(1, 1, 3, 3, "RESTING"))
                2L -> listOf(effect(2, 2, 1, 0, "TERMINAL"), effect(1, 1, 3, 2, "RESTING"))
                3L -> listOf(effect(1, 1, 2, 0, "CANCELLED"))
                4L -> listOf(effect(3, 4, 1, 1, "RESTING"))
                else -> listOf(effect(4, 5, 1, 0, "TERMINAL"), effect(3, 4, 1, 0, "TERMINAL"))
            }
        val wanted =
            tree(
                mapOf(
                    "sequence" to sequence,
                    "resultId" to resultIds[index],
                    "inputSequence" to sequence,
                    "orderId" to ids.getValue(admission),
                    "cancelId" to if (sequence == 3L) cancelId else null,
                    "outcome" to if (sequence == 3L) "CANCELLED" else "MATCHED",
                    "engineBefore" to sequence - 1,
                    "engineAfter" to sequence,
                    "events" to listOf(event),
                    "effects" to effects,
                ),
            )
        for ((field, value) in wanted.properties()) {
            if (result.path(field) != value) issues += "resultDetails[$sequence].$field: 고정 다섯 입력의 의미와 불일치"
        }
        if (result.properties().map { it.key }.toSet() !=
            wanted.properties().map { it.key }.toSet()
        ) {
            issues += "resultDetails[$sequence]: 의미 필드 누락·추가"
        }
    }
    return issues
}

private fun integer(value: JsonNode): Long? = if (value.isIntegralNumber && value.canConvertToLong()) value.asLong() else null

private fun checkBytes(
    issues: MutableList<String>,
    name: String,
    values: JsonNode,
    field: String,
    entries: Long,
    maximum: Long,
) {
    val bytes = integer(values.path(field))
    if (bytes == null || bytes > maximum || (entries == 0L && bytes != 0L) || (entries > 0 && bytes <= 0)) {
        issues += "$name.$field: entries=$entries bytes=$bytes maximum=$maximum"
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

internal data class BoundedHttpCheckpointContext(
    val runId: String,
    val manifestSha256: String,
    val marketId: String = "BTC-USDT",
)

/** 현재 관찰이 도착한 뒤의 판정 실패다. 실행기는 이 예외를 재시도 조건으로 삼지 않는다. */
internal class BoundedHttpCheckpointFailure(
    val reason: String,
    val detail: String,
) : AssertionError("$reason: $detail")

/** 명세의 원시 정수를 직접 대조한다. 제품 계산기나 다른 workload의 기대값을 사용하지 않는다. */
internal object BoundedHttpTradingAssertions {
    private val mapper = jacksonObjectMapper()
    private val phases = listOf("SELL", "BUY", "REPLAY", "CONFLICT")

    fun verifyEpoch(
        response: JsonNode,
        userId: String,
    ) {
        expect(response, "status", "ISSUED", "HTTP_STATUS")
        expect(response, "userId", userId, "HTTP_TOKEN")
        expect(response, "channel", 0, "HTTP_TOKEN")
        expect(response, "epoch", 1L, "HTTP_TOKEN")
        protectionAndPosition(response)
    }

    fun verifySell(
        checkpoints: List<JsonNode>,
        context: BoundedHttpCheckpointContext,
        sellResponse: JsonNode,
    ) {
        accepted(sellResponse, "SELLER", 1, context)
        verifyCheckpoint("SELL", checkpoints, context, position(sellResponse), mapOf("SELLER" to orderId(sellResponse)))
    }

    fun verifyBuy(
        checkpoints: List<JsonNode>,
        context: BoundedHttpCheckpointContext,
        sellResponse: JsonNode,
        buyResponse: JsonNode,
    ) {
        accepted(sellResponse, "SELLER", 1, context)
        accepted(buyResponse, "BUYER", 2, context)
        increasing(sellResponse, buyResponse, "SELL < BUY")
        ensure(orderId(sellResponse) != orderId(buyResponse), "HTTP_IDENTITY", "두 최초 주문의 orderId가 같습니다")
        verifyCheckpoint(
            "BUY",
            checkpoints,
            context,
            position(buyResponse),
            mapOf("SELLER" to orderId(sellResponse), "BUYER" to orderId(buyResponse)),
        )
    }

    fun verifyReplay(
        checkpoints: List<JsonNode>,
        context: BoundedHttpCheckpointContext,
        buyCheckpoints: List<JsonNode>,
        buyResponse: JsonNode,
        replayResponse: JsonNode,
    ) {
        accepted(buyResponse, "BUYER", 2, context)
        // 재전송의 callback 위치는 달라도 최초 업무 identity는 그대로여야 한다.
        listOf("orderId", "admissionSequence", "marketAdmissionSequence").forEach { field ->
            ensure(replayResponse.path(field) == buyResponse.path(field), "HTTP_IDENTITY", "재전송 $field 변경")
        }
        accepted(replayResponse, "BUYER", 2, context)
        increasing(buyResponse, replayResponse, "BUY < REPLAY")
        verifyCheckpoint("REPLAY", checkpoints, context, position(replayResponse), mapOf("BUYER" to orderId(buyResponse)))
        unchanged(buyCheckpoints, checkpoints)
    }

    fun verifyConflict(
        checkpoints: List<JsonNode>,
        context: BoundedHttpCheckpointContext,
        buyCheckpoints: List<JsonNode>,
        replayResponse: JsonNode,
        conflictResponse: JsonNode,
    ) {
        expect(conflictResponse, "status", "REQUEST_CONTENT_CONFLICT", "HTTP_STATUS")
        expect(conflictResponse, "reason", "REQUEST_CONTENT_CONFLICT", "HTTP_STATUS")
        token(conflictResponse, "BUYER", context)
        protectionAndPosition(conflictResponse)
        listOf("orderId", "admissionSequence", "marketAdmissionSequence").forEach { field ->
            val value = conflictResponse.path(field)
            ensure(value.isMissingNode || value.isNull, "HTTP_IDENTITY", "충돌 응답이 새 $field 를 가집니다")
        }
        increasing(replayResponse, conflictResponse, "REPLAY < CONFLICT")
        verifyCheckpoint("CONFLICT", checkpoints, context, position(conflictResponse), emptyMap())
        unchanged(buyCheckpoints, checkpoints)
    }

    fun verifyComplete(
        checkpointsByPhase: Map<String, List<JsonNode>>,
        responses: Map<String, JsonNode>,
        context: BoundedHttpCheckpointContext,
    ) {
        phases.forEach { phase ->
            ensure(!checkpointsByPhase[phase].isNullOrEmpty(), "MISSING_PHASE", "$phase checkpoint 누락")
            ensure(responses.containsKey(phase), "MISSING_PHASE", "$phase HTTP 응답 누락")
        }
        val sell = responses.getValue("SELL")
        val buy = responses.getValue("BUY")
        val replay = responses.getValue("REPLAY")
        verifySell(checkpointsByPhase.getValue("SELL"), context, sell)
        verifyBuy(checkpointsByPhase.getValue("BUY"), context, sell, buy)
        verifyReplay(checkpointsByPhase.getValue("REPLAY"), context, checkpointsByPhase.getValue("BUY"), buy, replay)
        verifyConflict(
            checkpointsByPhase.getValue("CONFLICT"),
            context,
            checkpointsByPhase.getValue("BUY"),
            replay,
            responses.getValue("CONFLICT"),
        )
    }

    private fun verifyCheckpoint(
        phase: String,
        checkpoints: List<JsonNode>,
        context: BoundedHttpCheckpointContext,
        barrier: Long,
        orderIds: Map<String, String>,
    ) {
        members(checkpoints)
        val sell = phase == "SELL"
        checkpoints.forEach { checkpoint ->
            expect(checkpoint, "runId", context.runId, "RUN_ID")
            expect(checkpoint, "label", phase, "CAPTURE_LABEL")
            expect(checkpoint, "manifestSha256", context.manifestSha256, "MANIFEST")
            expect(checkpoint, "protocol", "bounded-wire3", "PROTOCOL")
            expect(checkpoint, "sync", listOf(2, 2, 2), "SYNC")
            expect(checkpoint, "lifecycle", "RUNNING", "LIFECYCLE")
            observation(checkpoint)
            ensure(number(checkpoint, "position", "STALE_CAPTURE") >= barrier, "STALE_CAPTURE", "$phase 적용 barrier=$barrier 미도달")
            expect(checkpoint, "lastAdmissionSequence", if (sell) 1L else 2L, "ADMISSION_SEQUENCE")
            balances(checkpoint, sell)
            expect(checkpoint, "feeRevenue", if (sell) "0" else "30", "FEE_REVENUE")
            expect(checkpoint, "pending", 0, "MARKET_PROGRESS")
            expect(checkpoint, "liveOrders", if (sell) 1 else 0, "LIVE_ORDERS")
            expect(checkpoint, "activeReservations", if (sell) 1 else 0, "ACTIVE_RESERVATIONS")
            expect(checkpoint, "bookOrders", if (sell) 1 else 0, "BOOK_ORDERS")
            receipts(checkpoint, context, sell, orderIds)
            markets(checkpoint, context, sell)
            liveOrders(checkpoint, context, sell, orderIds["SELLER"])
            books(checkpoint, context, sell, orderIds["SELLER"])
            executions(checkpoint, context, sell)
        }
        val hashes = checkpoints.map { text(it, "stateSha256", "STATE_HASH") }
        ensure(hashes.all { it.matches(Regex("[0-9a-f]{64}")) }, "STATE_HASH", "정규화 상태 hash 형식 오류")
        ensure(hashes.distinct().size == 1, "STATE_HASH", "$phase 세 사본의 정규화 상태가 다릅니다")
    }

    private fun members(checkpoints: List<JsonNode>) {
        val members = checkpoints.map { number(it, "memberId", "INVALID_MEMBER") }
        ensure(members.all { it in 0L..2L }, "INVALID_MEMBER", "member 0·1·2 이외의 캡처")
        ensure(members.distinct().size == members.size, "DUPLICATE_MEMBER", "같은 member의 checkpoint 중복")
        ensure(members.toSet() == setOf(0L, 1L, 2L), "MISSING_MEMBER", "member 0·1·2 모두 필요합니다")
    }

    private fun observation(checkpoint: JsonNode) {
        val failure = checkpoint.path("observationFailure")
        ensure(!failure.isMissingNode, "OBSERVATION_FAILURE", "관찰 결과 필드 누락")
        if (!failure.isNull) {
            val detail = failure.asString()
            val reason =
                when {
                    detail.startsWith("EXECUTION_OVERFLOW") -> "EXECUTION_OVERFLOW"
                    detail.startsWith("DUPLICATE_APPLIED") -> "DUPLICATE_APPLIED"
                    else -> "OBSERVATION_FAILURE"
                }
            throw BoundedHttpCheckpointFailure(reason, detail)
        }
    }

    private fun balances(
        checkpoint: JsonNode,
        sell: Boolean,
    ) {
        val rows = array(checkpoint, "balances", "MISSING_BALANCE")
        val keyed = linkedMapOf<Pair<String, String>, Pair<Long, Long>>()
        rows.forEach { row ->
            ensure(row.isArray && row.size() == 4, "BALANCE", "잔고 행 형식 오류")
            val key = row.path(0).asString() to row.path(1).asString()
            ensure(!keyed.containsKey(key), "BALANCE", "잔고 행 중복 $key")
            keyed[key] = integer(row.path(2), "BALANCE", "$key available") to integer(row.path(3), "BALANCE", "$key hold")
        }
        // SELL에서는 아직 쓰지 않은 두 수취 잔고만 누락된 0을 허용한다.
        val expected =
            if (sell) {
                mapOf(("BUYER" to "USDT") to (11_020L to 0L), ("SELLER" to "BTC") to (0L to 100_000_000L))
            } else {
                mapOf(
                    ("BUYER" to "USDT") to (1_000L to 0L),
                    ("BUYER" to "BTC") to (100_000_000L to 0L),
                    ("SELLER" to "BTC") to (0L to 0L),
                    ("SELLER" to "USDT") to (9_990L to 0L),
                )
            }
        expected.forEach { (key, wanted) ->
            ensure(keyed.containsKey(key), "MISSING_BALANCE", "사용한 잔고 행 누락 $key")
            ensure(keyed[key] == wanted, "BALANCE", "$key expected=$wanted actual=${keyed[key]}")
        }
        keyed.filterKeys { it !in expected }.forEach { (key, value) ->
            ensure(value == (0L to 0L), "BALANCE", "준비·거래하지 않은 잔고가 0이 아닙니다: $key=$value")
        }
        if (!sell) {
            // 수수료 30을 포함한 보존식도 독립 원시 정수로 고정한다.
            ensure(
                keyed.getValue("BUYER" to "USDT").first + keyed.getValue("SELLER" to "USDT").first + 30L == 11_020L,
                "BALANCE",
                "USDT 보존 위반",
            )
            ensure(keyed.getValue("BUYER" to "BTC").first + keyed.getValue("SELLER" to "BTC").first == 100_000_000L, "BALANCE", "BTC 보존 위반")
        }
    }

    private fun receipts(
        checkpoint: JsonNode,
        context: BoundedHttpCheckpointContext,
        sell: Boolean,
        orderIds: Map<String, String>,
    ) {
        val expectedUsers = if (sell) setOf("SELLER") else setOf("SELLER", "BUYER")
        val receipts = array(checkpoint, "receipts", "RECEIPT_IDENTITY")
        ensure(receipts.size == expectedUsers.size, "RECEIPT_IDENTITY", "최초 retained receipt 수 오류")
        expect(checkpoint, "retainedReceipts", expectedUsers.size, "RECEIPT_IDENTITY")
        ensure(receipts.map { text(it, "userId", "RECEIPT_IDENTITY") }.toSet() == expectedUsers, "RECEIPT_IDENTITY", "receipt 사용자 누락·중복")
        receipts.forEach { receipt ->
            val user = text(receipt, "userId", "RECEIPT_IDENTITY")
            expect(receipt, "channel", 0, "RECEIPT_IDENTITY")
            expect(receipt, "epoch", 1L, "RECEIPT_IDENTITY")
            expect(receipt, "sequence", 1L, "RECEIPT_IDENTITY")
            expect(receipt, "code", "ACCEPTED", "RECEIPT_IDENTITY")
            val sequence = if (user == "SELLER") 1L else 2L
            expect(receipt, "admissionSequence", sequence, "RECEIPT_IDENTITY")
            expect(receipt, "marketAdmissionSequence", sequence, "RECEIPT_IDENTITY")
            val orderId = text(receipt, "orderId", "RECEIPT_IDENTITY")
            ensure(orderId.isNotBlank(), "RECEIPT_IDENTITY", "빈 orderId")
            orderIds[user]?.let { ensure(orderId == it, "RECEIPT_IDENTITY", "$user HTTP 최초 orderId와 Inbox가 다릅니다") }
            val intent = receipt.path("intent")
            expect(intent, "marketId", context.marketId, "RECEIPT_CONTENT")
            expect(intent, "expectedConfigurationVersion", 1L, "RECEIPT_CONTENT")
            expect(intent, "side", if (user == "SELLER") "SELL" else "BUY", "RECEIPT_CONTENT")
            expect(intent, "orderType", "LIMIT", "RECEIPT_CONTENT")
            expect(intent, "timeInForce", "GTC", "RECEIPT_CONTENT")
            expect(intent, "price", 10_000L, "RECEIPT_CONTENT")
            expect(intent, "quantity", 100_000_000L, "RECEIPT_CONTENT")
        }
        ensure(
            receipts.map { text(it, "orderId", "RECEIPT_IDENTITY") }.distinct().size == receipts.size,
            "RECEIPT_IDENTITY",
            "최초 orderId 중복",
        )
        val floors = array(checkpoint, "receiptFloors", "RECEIPT_FLOORS")
        ensure(floors.size == 2, "RECEIPT_FLOORS", "두 사용자의 channel floor가 필요합니다")
        val actualFloors =
            floors
                .map { row ->
                    ensure(row.isArray && row.size() == 5, "RECEIPT_FLOORS", "channel floor 행 형식 오류")
                    listOf<Any>(
                        row.path(0).asString(),
                        integer(row.path(1), "RECEIPT_FLOORS", "channel"),
                        integer(row.path(2), "RECEIPT_FLOORS", "epoch"),
                        integer(row.path(3), "RECEIPT_FLOORS", "retiredThrough"),
                        integer(row.path(4), "RECEIPT_FLOORS", "processedThrough"),
                    )
                }.toSet()
        val wantedFloors =
            setOf(
                listOf<Any>("BUYER", 0L, 1L, 0L, if (sell) 0L else 1L),
                listOf<Any>("SELLER", 0L, 1L, 0L, 1L),
            )
        ensure(actualFloors == wantedFloors, "RECEIPT_FLOORS", "epoch/retiredThrough/processedThrough 오류")
    }

    private fun markets(
        checkpoint: JsonNode,
        context: BoundedHttpCheckpointContext,
        sell: Boolean,
    ) {
        val rows = array(checkpoint, "markets", "MARKET_PROGRESS")
        val ids = rows.map { text(it, "marketId", "MARKET_PROGRESS") }
        ensure(ids.distinct().size == rows.size && context.marketId in ids, "MARKET_PROGRESS", "시장 누락·중복")
        rows.forEach { row ->
            val expected =
                if (text(row, "marketId", "MARKET_PROGRESS") == context.marketId) {
                    if (sell) {
                        1L
                    } else {
                        2L
                    }
                } else {
                    0L
                }
            expect(row, "lastAssigned", expected, "MARKET_PROGRESS")
            expect(row, "executedThrough", expected, "MARKET_PROGRESS")
            expect(row, "pending", 0, "MARKET_PROGRESS")
        }
    }

    private fun liveOrders(
        checkpoint: JsonNode,
        context: BoundedHttpCheckpointContext,
        sell: Boolean,
        sellOrderId: String?,
    ) {
        val live = array(checkpoint, "liveOrderDetails", "LIVE_ORDERS")
        ensure(live.size == if (sell) 1 else 0, "LIVE_ORDERS", "활성 주문 상세 수 오류")
        if (!sell) return
        val row = live.single()
        expect(row, "admissionSequence", 1L, "LIVE_ORDERS")
        expect(row, "marketAdmissionSequence", 1L, "LIVE_ORDERS")
        expect(row, "userId", "SELLER", "LIVE_ORDERS")
        expect(row, "marketId", context.marketId, "LIVE_ORDERS")
        expect(row, "side", "SELL", "LIVE_ORDERS")
        expect(row, "phase", "RESTING", "LIVE_ORDERS")
        expect(row, "orderType", "LIMIT", "LIVE_ORDERS")
        expect(row, "timeInForce", "GTC", "LIVE_ORDERS")
        expect(row, "price", 10_000L, "LIVE_ORDERS")
        expect(row, "quantity", 100_000_000L, "LIVE_ORDERS")
        expect(row, "reservationRemainingQuantity", 100_000_000L, "ACTIVE_RESERVATIONS")
        expect(row, "reservationRemainingAmount", 100_000_000L, "ACTIVE_RESERVATIONS")
        ensure(sellOrderId != null && text(row, "orderId", "LIVE_ORDERS") == sellOrderId, "LIVE_ORDERS", "SELL 활성 주문 identity 오류")
    }

    private fun books(
        checkpoint: JsonNode,
        context: BoundedHttpCheckpointContext,
        sell: Boolean,
        sellOrderId: String?,
    ) {
        val books = array(checkpoint, "bookDetails", "BOOK_ORDERS")
        val ids = books.map { text(it, "marketId", "BOOK_ORDERS") }
        ensure(ids.distinct().size == books.size && context.marketId in ids, "BOOK_ORDERS", "시장 주문장 누락·중복")
        books.forEach { book ->
            val traded = text(book, "marketId", "BOOK_ORDERS") == context.marketId
            expect(
                book,
                "lastEngineSequence",
                if (traded) {
                    if (sell) {
                        1L
                    } else {
                        2L
                    }
                } else {
                    0L
                },
                "BOOK_ORDERS",
            )
            val orders = array(book, "orders", "BOOK_ORDERS")
            ensure(orders.size == if (traded && sell) 1 else 0, "BOOK_ORDERS", "활성 호가 수 오류")
            if (traded && sell) {
                val order = orders.single()
                expect(order, "orderId", sellOrderId, "BOOK_ORDERS")
                expect(order, "userId", "SELLER", "BOOK_ORDERS")
                expect(order, "side", "SELL", "BOOK_ORDERS")
                expect(order, "price", 10_000L, "BOOK_ORDERS")
                expect(order, "quantity", 100_000_000L, "BOOK_ORDERS")
                expect(order, "remainingQuantity", 100_000_000L, "BOOK_ORDERS")
            }
        }
    }

    private fun executions(
        checkpoint: JsonNode,
        context: BoundedHttpCheckpointContext,
        sell: Boolean,
    ) {
        val executions = array(checkpoint, "executions", "MISSING_EXECUTION")
        val ids = executions.map { number(it, "admissionSequence", "EXECUTION_IDENTITY") }
        ensure(ids.distinct().size == ids.size, "DUPLICATE_APPLIED", "같은 admission의 Applied가 두 번 기록됐습니다")
        val wanted = if (sell) setOf(1L) else setOf(1L, 2L)
        ensure(ids.toSet() == wanted, "MISSING_EXECUTION", "admission $wanted 의 Applied가 각각 한 번 필요합니다: $ids")
        executions.forEach { execution ->
            val sequence = number(execution, "admissionSequence", "EXECUTION_IDENTITY")
            expect(execution, "marketAdmissionSequence", sequence, "EXECUTION_IDENTITY")
            expect(execution, "marketId", context.marketId, "EXECUTION_IDENTITY")
            expect(execution, "lastEngineSequence", sequence, "EXECUTION_IDENTITY")
            expect(execution, "fillCount", if (sequence == 1L) 0 else 1, "EXECUTION_FILL")
            expect(execution, "resting", sequence == 1L, "EXECUTION_FILL")
            val applied = number(execution, "position", "EXECUTION_POSITION")
            ensure(
                applied > 0L && applied <= number(checkpoint, "position", "STALE_CAPTURE"),
                "EXECUTION_POSITION",
                "실행 위치가 capture 경계를 벗어납니다",
            )
        }
    }

    private fun unchanged(
        before: List<JsonNode>,
        after: List<JsonNode>,
    ) {
        members(before)
        val byMember = before.associateBy { number(it, "memberId", "INVALID_MEMBER") }
        val fields =
            listOf(
                "stateSha256",
                "lastAdmissionSequence",
                "balances",
                "feeRevenue",
                "liveOrders",
                "pending",
                "activeReservations",
                "bookOrders",
                "retainedReceipts",
                "receiptFloors",
                "receipts",
                "markets",
                "liveOrderDetails",
                "bookDetails",
                "executions",
            )
        after.forEach { current ->
            val old = byMember.getValue(number(current, "memberId", "INVALID_MEMBER"))
            fields.forEach { field ->
                ensure(current.path(field) == old.path(field), "STATE_CHANGED", "member=${current.path("memberId")} $field 변경")
            }
        }
    }

    private fun accepted(
        response: JsonNode,
        userId: String,
        sequence: Long,
        context: BoundedHttpCheckpointContext,
    ) {
        expect(response, "status", "ACCEPTED", "HTTP_STATUS")
        token(response, userId, context)
        expect(response, "admissionSequence", sequence, "HTTP_ADMISSION_SEQUENCE")
        expect(response, "marketAdmissionSequence", sequence, "HTTP_ADMISSION_SEQUENCE")
        orderId(response)
        protectionAndPosition(response)
    }

    private fun token(
        response: JsonNode,
        userId: String,
        context: BoundedHttpCheckpointContext,
    ) {
        expect(response, "userId", userId, "HTTP_TOKEN")
        expect(response, "channel", 0, "HTTP_TOKEN")
        expect(response, "epoch", 1L, "HTTP_TOKEN")
        expect(response, "sequence", 1L, "HTTP_TOKEN")
        expect(response, "marketId", context.marketId, "HTTP_TOKEN")
    }

    private fun protectionAndPosition(response: JsonNode) {
        expect(response, "protection", "LOCAL_QUORUM", "HTTP_PROTECTION")
        ensure(position(response) > 0, "HTTP_POSITION", "HTTP appliedLogPosition은 양수여야 합니다")
    }

    private fun orderId(response: JsonNode): String =
        text(response, "orderId", "HTTP_IDENTITY").also {
            ensure(it.isNotBlank(), "HTTP_IDENTITY", "HTTP 최초 orderId 누락")
        }

    private fun position(response: JsonNode): Long = number(response, "appliedLogPosition", "HTTP_POSITION")

    private fun increasing(
        before: JsonNode,
        after: JsonNode,
        description: String,
    ) {
        ensure(position(before) < position(after), "HTTP_POSITION_ORDER", "$description 위치 순서 위반")
    }

    private fun expect(
        node: JsonNode,
        field: String,
        expected: Any?,
        reason: String,
    ) {
        val actual = node.path(field)
        val wanted = mapper.valueToTree<JsonNode>(expected)
        val same =
            if (expected is Number) {
                actual.isIntegralNumber && actual.canConvertToLong() && actual.longValue() == expected.toLong()
            } else {
                actual == wanted
            }
        ensure(same, reason, "$field expected=$wanted actual=$actual")
    }

    private fun text(
        node: JsonNode,
        field: String,
        reason: String,
    ): String {
        val value = node.path(field)
        ensure(!value.isMissingNode && !value.isNull, reason, "$field 누락")
        val text = value.asString()
        ensure(value == mapper.valueToTree<JsonNode>(text), reason, "$field 문자열이 필요합니다")
        return text
    }

    private fun array(
        node: JsonNode,
        field: String,
        reason: String,
    ): List<JsonNode> {
        val value = node.path(field)
        ensure(value.isArray, reason, "$field 배열 누락")
        return value.toList()
    }

    private fun number(
        node: JsonNode,
        field: String,
        reason: String,
    ): Long = integer(node.path(field), reason, field)

    private fun integer(
        value: JsonNode,
        reason: String,
        field: String,
    ): Long {
        ensure(value.isIntegralNumber && value.canConvertToLong(), reason, "$field 정수 누락·범위 초과: $value")
        return value.longValue()
    }

    private fun ensure(
        condition: Boolean,
        reason: String,
        detail: String,
    ) {
        if (!condition) throw BoundedHttpCheckpointFailure(reason, detail)
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedHttpTradingAssertionsTest {
    private val mapper = jacksonObjectMapper()
    private val context = BoundedHttpCheckpointContext("http-bounded-fixture", "f".repeat(64))

    @Test
    fun `세 노드의 정상 상태는 손계산과 재전송 불변 조건을 충족한다`() {
        val fixture = normalFixture()
        BoundedHttpTradingAssertions.verifyEpoch(epoch("SELLER", 80L), "SELLER")
        BoundedHttpTradingAssertions.verifyEpoch(epoch("BUYER", 90L), "BUYER")
        fixture.verify()
    }

    @Test
    fun `정상 자료의 한 항목을 바꾸면 해당 실패 이유로 검출한다`() {
        val cases =
            listOf(
                Mutation("missing member", "MISSING_MEMBER") { it.checkpoints["BUY"] = it.checkpoints.getValue("BUY").dropLast(1) },
                Mutation("duplicate member", "DUPLICATE_MEMBER") {
                    it.checkpoints["BUY"] = it.checkpoints.getValue("BUY") + listOf(it.checkpoint("BUY", 0))
                },
                Mutation("legacy protocol", "PROTOCOL") { it.checkpoint("BUY").put("protocol", "legacy-wire2") },
                Mutation("non durable sync", "SYNC") { it.setCheckpoint("BUY", "sync", listOf(2, 1, 2)) },
                Mutation("wrong run", "RUN_ID") { it.checkpoint("BUY").put("runId", "other-run") },
                Mutation("wrong manifest", "MANIFEST") { it.checkpoint("BUY").put("manifestSha256", "e".repeat(64)) },
                Mutation("wrong capture label", "CAPTURE_LABEL") { it.checkpoint("BUY").put("label", "SELL") },
                Mutation("quarantined", "LIFECYCLE") { it.checkpoint("BUY").put("lifecycle", "QUARANTINED") },
                Mutation("fee 31", "FEE_REVENUE") { it.checkpoint("BUY").put("feeRevenue", "31") },
                Mutation("missing used zero balance", "MISSING_BALANCE") {
                    (it.checkpoint("BUY").path("balances") as ArrayNode).remove(2)
                },
                Mutation("remaining BTC hold", "BALANCE") {
                    it.setAt(it.checkpoint("BUY"), "/balances/2", listOf("SELLER", "BTC", 0L, 1L))
                },
                Mutation("remaining active reservation", "ACTIVE_RESERVATIONS") { it.checkpoint("BUY").put("activeReservations", 1) },
                Mutation("remaining book order", "BOOK_ORDERS") { it.checkpoint("BUY").put("bookOrders", 1) },
                Mutation("market sequence advanced", "MARKET_PROGRESS") {
                    (it.checkpoint("BUY").at("/markets/0") as ObjectNode).put("lastAssigned", 3L)
                },
                Mutation("unused market moved", "MARKET_PROGRESS") {
                    (it.checkpoint("BUY").at("/markets/1") as ObjectNode).put("executedThrough", 1L)
                },
                Mutation("receipt content changed", "RECEIPT_CONTENT") {
                    (it.checkpoint("BUY").at("/receipts/1/intent") as ObjectNode).put("price", 20_000L)
                },
                Mutation("receipt retired", "RECEIPT_FLOORS") {
                    it.setAt(it.checkpoint("BUY"), "/receiptFloors/0", listOf("BUYER", 0, 1L, 1L, 1L))
                },
                Mutation("stale replay with matching hash", "STALE_CAPTURE") { it.checkpoint("REPLAY").put("position", 299L) },
                Mutation("replay first order id changed", "HTTP_IDENTITY") { it.response("REPLAY").put("orderId", "new-order") },
                Mutation("replay new admission sequence", "HTTP_IDENTITY") { it.response("REPLAY").put("admissionSequence", 3L) },
                Mutation("replay applied again", "DUPLICATE_APPLIED") {
                    val executions = it.checkpoint("REPLAY").path("executions") as ArrayNode
                    executions.add(mapper.readTree(executions.path(1).toString()))
                },
                Mutation("execution observation overflow", "EXECUTION_OVERFLOW") {
                    it.checkpoint("BUY").put("observationFailure", "EXECUTION_OVERFLOW: capacity=8")
                },
                Mutation("execution observation error", "OBSERVATION_FAILURE") {
                    it.checkpoint("BUY").put("observationFailure", "OBSERVATION_FAILURE: callback")
                },
                Mutation("execution observation field absent", "OBSERVATION_FAILURE") { it.checkpoint("BUY").remove("observationFailure") },
                Mutation("execution records absent", "MISSING_EXECUTION") { it.checkpoint("BUY").remove("executions") },
                Mutation("buy execution missing", "MISSING_EXECUTION") {
                    (it.checkpoint("BUY").path("executions") as ArrayNode).remove(1)
                },
                Mutation("extra fill", "EXECUTION_FILL") {
                    (it.checkpoint("BUY").at("/executions/1") as ObjectNode).put("fillCount", 2)
                },
                Mutation("required phase unfinished", "MISSING_PHASE") { it.checkpoints.remove("CONFLICT") },
                Mutation("required response absent", "MISSING_PHASE") { it.responses.remove("REPLAY") },
                Mutation("HTTP apply position absent", "HTTP_POSITION") { it.response("REPLAY").remove("appliedLogPosition") },
                Mutation("HTTP apply position not positive", "HTTP_POSITION") { it.response("SELL").put("appliedLogPosition", 0L) },
                Mutation("HTTP apply order reversed", "HTTP_POSITION_ORDER") { it.response("REPLAY").put("appliedLogPosition", 200L) },
                Mutation("HTTP protection changed", "HTTP_PROTECTION") { it.response("BUY").put("protection", "REMOTE_CONFIRMED") },
                Mutation("conflict accepted", "HTTP_STATUS") { it.response("CONFLICT").put("status", "ACCEPTED") },
                Mutation("replay inbox identity changed", "STATE_CHANGED") {
                    (it.checkpoint("REPLAY").at("/receipts/0") as ObjectNode).put("orderId", "new-seller-order")
                },
            )
        cases.forEach { case ->
            // 각 위반 예제는 먼저 통과한 정상 자료에서 한 항목만 바꾼다.
            val fixture = normalFixture()
            fixture.verify()
            case.mutate(fixture)
            val failure = assertFailsWith<BoundedHttpCheckpointFailure>(case.name) { fixture.verify() }
            assertEquals(case.reason, failure.reason, "${case.name}: ${failure.message}")
        }
    }

    @Test
    fun `세대 발급 응답에는 엔진의 양수 적용 위치가 필요하다`() {
        val response = epoch("BUYER", 90L)
        BoundedHttpTradingAssertions.verifyEpoch(response, "BUYER")
        (response as ObjectNode).remove("appliedLogPosition")
        val failure = assertFailsWith<BoundedHttpCheckpointFailure> { BoundedHttpTradingAssertions.verifyEpoch(response, "BUYER") }
        assertEquals("HTTP_POSITION", failure.reason)
    }

    private data class Mutation(
        val name: String,
        val reason: String,
        val mutate: (Fixture) -> Unit,
    )

    private inner class Fixture(
        val checkpoints: MutableMap<String, List<JsonNode>>,
        val responses: MutableMap<String, JsonNode>,
    ) {
        fun verify() = BoundedHttpTradingAssertions.verifyComplete(checkpoints, responses, context)

        fun checkpoint(
            phase: String,
            member: Int = 0,
        ): ObjectNode = checkpoints.getValue(phase)[member] as ObjectNode

        fun response(phase: String): ObjectNode = responses.getValue(phase) as ObjectNode

        fun setCheckpoint(
            phase: String,
            field: String,
            value: Any,
        ) {
            checkpoint(phase).set(field, tree(value))
        }

        fun setAt(
            node: JsonNode,
            pointer: String,
            value: Any,
        ) {
            val parent = node.at(pointer.substringBeforeLast('/'))
            val field = pointer.substringAfterLast('/')
            when (parent) {
                is ObjectNode -> parent.set(field, tree(value))
                is ArrayNode -> parent.set(field.toInt(), tree(value))
                else -> error("잘못된 fixture 변경 경로: $pointer")
            }
        }
    }

    private fun normalFixture(): Fixture {
        val responses =
            linkedMapOf(
                "SELL" to response("SELLER", "sell-order", 1L, 100L),
                "BUY" to response("BUYER", "buy-order", 2L, 200L),
                "REPLAY" to response("BUYER", "buy-order", 2L, 300L),
                "CONFLICT" to
                    tree(
                        mapOf(
                            "status" to "REQUEST_CONTENT_CONFLICT",
                            "reason" to "REQUEST_CONTENT_CONFLICT",
                            "userId" to "BUYER",
                            "channel" to 0,
                            "epoch" to 1L,
                            "sequence" to 1L,
                            "marketId" to "BTC-USDT",
                            "protection" to "LOCAL_QUORUM",
                            "appliedLogPosition" to 400L,
                        ),
                    ),
            )
        val checkpoints =
            listOf("SELL", "BUY", "REPLAY", "CONFLICT").associateWithTo(linkedMapOf()) { phase ->
                (0..2).map { member -> checkpoint(phase, member) }
            }
        return Fixture(checkpoints, responses)
    }

    private fun checkpoint(
        phase: String,
        member: Int,
    ): JsonNode {
        val sell = phase == "SELL"
        val position = mapOf("SELL" to 150L, "BUY" to 250L, "REPLAY" to 350L, "CONFLICT" to 450L).getValue(phase)
        val liveOrder =
            mapOf(
                "admissionSequence" to 1L,
                "marketAdmissionSequence" to 1L,
                "userId" to "SELLER",
                "marketId" to "BTC-USDT",
                "orderId" to "sell-order",
                "side" to "SELL",
                "phase" to "RESTING",
                "orderType" to "LIMIT",
                "timeInForce" to "GTC",
                "price" to 10_000L,
                "quantity" to 100_000_000L,
                "reservationRemainingQuantity" to 100_000_000L,
                "reservationRemainingAmount" to 100_000_000L,
            )
        val bookOrder =
            mapOf(
                "orderId" to "sell-order",
                "userId" to "SELLER",
                "side" to "SELL",
                "price" to 10_000L,
                "quantity" to 100_000_000L,
                "remainingQuantity" to 100_000_000L,
            )
        return tree(
            mapOf(
                "runId" to "http-bounded-fixture",
                "label" to phase,
                "memberId" to member,
                "protocol" to "bounded-wire3",
                "position" to position + member,
                "role" to if (member == 0) "LEADER" else "FOLLOWER",
                "sync" to listOf(2, 2, 2),
                "manifestSha256" to "f".repeat(64),
                "lifecycle" to "RUNNING",
                "lastAdmissionSequence" to if (sell) 1L else 2L,
                "balances" to
                    if (sell) {
                        listOf(listOf("BUYER", "USDT", 11_020L, 0L), listOf("SELLER", "BTC", 0L, 100_000_000L))
                    } else {
                        listOf(
                            listOf("BUYER", "USDT", 1_000L, 0L),
                            listOf("BUYER", "BTC", 100_000_000L, 0L),
                            listOf("SELLER", "BTC", 0L, 0L),
                            listOf("SELLER", "USDT", 9_990L, 0L),
                        )
                    },
                "feeRevenue" to if (sell) "0" else "30",
                "liveOrders" to if (sell) 1 else 0,
                "activeReservations" to if (sell) 1 else 0,
                "bookOrders" to if (sell) 1 else 0,
                "pending" to 0,
                "retainedReceipts" to if (sell) 1 else 2,
                "receiptFloors" to
                    listOf(listOf("BUYER", 0, 1L, 0L, if (sell) 0L else 1L), listOf("SELLER", 0, 1L, 0L, 1L)),
                "receipts" to if (sell) listOf(receipt("SELLER")) else listOf(receipt("SELLER"), receipt("BUYER")),
                "liveOrderDetails" to if (sell) listOf(liveOrder) else emptyList(),
                "markets" to
                    listOf(
                        mapOf(
                            "marketId" to "BTC-USDT",
                            "lastAssigned" to if (sell) 1L else 2L,
                            "executedThrough" to if (sell) 1L else 2L,
                            "pending" to 0,
                        ),
                        mapOf("marketId" to "ETH-USDT", "lastAssigned" to 0L, "executedThrough" to 0L, "pending" to 0),
                    ),
                "bookDetails" to
                    listOf(
                        mapOf(
                            "marketId" to "BTC-USDT",
                            "lastEngineSequence" to if (sell) 1L else 2L,
                            "orders" to if (sell) listOf(bookOrder) else emptyList(),
                        ),
                    ),
                "executions" to if (sell) listOf(execution(1L)) else listOf(execution(1L), execution(2L)),
                "observationFailure" to null,
                "stateSha256" to if (sell) "a".repeat(64) else "b".repeat(64),
            ),
        )
    }

    private fun receipt(userId: String): Map<String, Any> {
        val seller = userId == "SELLER"
        return mapOf(
            "userId" to userId,
            "channel" to 0,
            "epoch" to 1L,
            "sequence" to 1L,
            "code" to "ACCEPTED",
            "orderId" to if (seller) "sell-order" else "buy-order",
            "admissionSequence" to if (seller) 1L else 2L,
            "marketAdmissionSequence" to if (seller) 1L else 2L,
            "intent" to
                mapOf(
                    "marketId" to "BTC-USDT",
                    "expectedConfigurationVersion" to 1L,
                    "side" to if (seller) "SELL" else "BUY",
                    "orderType" to "LIMIT",
                    "timeInForce" to "GTC",
                    "price" to 10_000L,
                    "quantity" to 100_000_000L,
                ),
        )
    }

    private fun execution(sequence: Long): Map<String, Any> =
        mapOf(
            "admissionSequence" to sequence,
            "marketAdmissionSequence" to sequence,
            "marketId" to "BTC-USDT",
            "lastEngineSequence" to sequence,
            "fillCount" to if (sequence == 1L) 0 else 1,
            "resting" to (sequence == 1L),
            "position" to if (sequence == 1L) 120L else 220L,
        )

    private fun response(
        userId: String,
        orderId: String,
        admissionSequence: Long,
        position: Long,
    ): JsonNode =
        tree(
            mapOf(
                "status" to "ACCEPTED",
                "userId" to userId,
                "channel" to 0,
                "epoch" to 1L,
                "sequence" to 1L,
                "marketId" to "BTC-USDT",
                "orderId" to orderId,
                "admissionSequence" to admissionSequence,
                "marketAdmissionSequence" to admissionSequence,
                "protection" to "LOCAL_QUORUM",
                "appliedLogPosition" to position,
            ),
        )

    private fun epoch(
        userId: String,
        position: Long,
    ): JsonNode =
        tree(
            mapOf(
                "status" to "ISSUED",
                "userId" to userId,
                "channel" to 0,
                "epoch" to 1L,
                "protection" to "LOCAL_QUORUM",
                "appliedLogPosition" to position,
            ),
        )

    // 실제 보고서 파일과 같이 직렬화 후 읽어 정수 노드 표현 차이도 함께 거친다.
    private fun tree(value: Any): JsonNode = mapper.readTree(mapper.writeValueAsString(value))
}

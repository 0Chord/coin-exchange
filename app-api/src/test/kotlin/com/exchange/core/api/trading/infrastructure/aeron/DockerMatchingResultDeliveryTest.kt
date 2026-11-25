package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.Side
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.TradingIdentifiers
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** 매 사례는 새 기록·동결 이미지·독립 자금3+매칭3 JVM으로 실제 금융 기대값을 검사한다. */
class DockerMatchingResultDeliveryTest {
    @Test
    fun `settlement replies and committed ACKs survive loss full log and snapshot tail recovery`() =
        verify("baseline") { v ->
            v.hold("matching-acks")
            v.hold("funds-result-replies")
            v.tool("admit", "seller", "1", "100", "1")
            val first = v.expect("R4 R8 result1 settles while funds ACK is lost", ResultExpected(1, 0, 1000, 0, 0, 0, 0, 1, 1, 1, 1, 0))
            val originalReceipt = first.getValue("funds0").path("funds").path("receipts")
            v.verifyObservationReadableByDifferentUser()
            v.hold("result-records")
            v.release("funds-result-replies")
            v.awaitObservation("received-funds-result-ack.json", "DUPLICATE", 1)
            v.tool("replay-result", "1")
            v
                .expect(
                    "R8 duplicate leaves original receipt timestamp and financial state",
                    ResultExpected(1, 0, 1000, 0, 0, 0, 0, 1, 1, 1, 1, 0),
                ).also {
                    check(
                        it.filterKeys { name -> name.startsWith("funds") }.values.all { state ->
                            state.path("funds").path("receipts") ==
                                originalReceipt
                        },
                    )
                }
            v.release("result-records")
            v.expect("R4 R13 no-fill is reclaimed before explicit order ACK", ResultExpected(1, 1, 1000, 0, 0, 0, 0, 1, 1, 1, 1, 0))
            v.tool("replay-order-ack")
            v.release("matching-acks")
            v.tool("replay-order", "1")
            v.expect("R13 late original order ACK preserves resting reservation", ResultExpected(1, 1, 1000, 0, 0, 0, 0, 1, 1, 1, 1, 0))

            v.hold("result-records")
            v.tool("admit", "buyer", "1", "100", "1")
            val pending = ResultExpected(2, 1, 900, 0, 1, 100, 0, 0, 0, 0, 2, 1)
            v.expect("R10 funds log settled trade while matching still owns result2", pending)
            v.restartResults("R10 full-log restart with missing matching ACK", false, pending::matches)
            v.tool("replay-result", "2")
            v.expect("R10 recovered duplicate performs no second settlement", pending)
            v.hold("record-result-replies")
            v.release("result-records")
            val completed = ResultExpected(2, 2, 900, 0, 1, 100, 0, 0, 0, 0, 2, 1)
            v.expect("R5 R11 matching ACK commits while its reply is lost", completed)
            v.tool("replay-ack")
            v.release("record-result-replies")
            v.expect("R11 repeated committed ACK preserves zero result payload", completed)
            v.restartResults("R20 full log restores six independent completed copies", false, completed::matches)

            v.tool("snapshot", "funds")
            v.tool("snapshot", "matching")
            val snapshot =
                v.awaitStates("R21 both snapshots before new tail", 45_000) {
                    completed.matches(it) &&
                        it.values.all { state -> v.number(state, "snapshotCount") > 0 && v.number(state, "lastSnapshotPosition") > 0 }
                }
            // 최초 체결로 받은 자산을 역거래한다. 사용한 잔고에 개발 준비금을 추가하지 않는다.
            v.tool("admit", "buyer", "2", "100", "1", "SELL")
            v.expect(
                "R21 original buyer rests acquired X1 before tail trade",
                ResultExpected(3, 3, 900, 0, 0, 100, 0, 0, 1, 1, 3, 1, buyerXHold = 1),
            )
            v.hold("result-records")
            v.tool("admit", "seller", "2", "100", "1", "BUY")
            val tail = ResultExpected(4, 3, 1000, 0, 0, 0, 1, 0, 0, 0, 4, 2)
            val beforeRestart = v.expect("R21 trade and funds settlement in each log tail without matching ACK", tail)
            check(
                beforeRestart.all { (name, state) ->
                    v.number(state, "logPosition") >
                        v.number(snapshot.getValue(name), "lastSnapshotPosition")
                },
            )
            v.restartResults("R21 snapshots plus own tails retain unconfirmed result4", true, tail::matches)
            v.tool("replay-result", "4")
            v.release("result-records")
            val tailCompleted = tail.copy(settled = 4)
            v.expect("R21 recovered matching commits ACK4 and reclaims result4", tailCompleted)
            v.restartResults("R21 same snapshots plus committed ACK tail do not resurrect results", true, tailCompleted::matches)
        }

    @Test
    fun `two makers use maker prices and retain the buyers remaining reservation`() =
        verify("partial") { v ->
            v.tool("admit", "seller", "1", "90", "1")
            v.expect("R6 first resting maker", ResultExpected(1, 1, 1000, 0, 0, 0, 1, 1, 1, 1, 1, 0))
            v.tool("admit", "seller", "2", "95", "1")
            v.expect("R6 second resting maker", ResultExpected(2, 2, 1000, 0, 0, 0, 0, 2, 2, 2, 2, 0))
            v.tool("admit", "buyer", "1", "100", "3")
            v.expect(
                "R6 Q1000-90-95-100=715 with two base units and one remaining buy",
                ResultExpected(3, 3, 715, 100, 2, 185, 0, 0, 1, 1, 5, 2),
            )
        }

    @Test
    fun `maker one percent and taker two percent preserve quote asset total`() =
        verify("fee") { v ->
            v.tool("admit", "seller", "1", "100", "1")
            v.expect("R7 maker waits with its base reservation", ResultExpected(1, 1, 1000, 0, 0, 0, 0, 1, 1, 1, 1, 0))
            v.hold("result-records")
            v.tool("admit", "buyer", "1", "100", "1")
            val pending = ResultExpected(2, 1, 898, 0, 1, 99, 0, 0, 0, 0, 2, 1, feeQ = 3)
            v.expect("R17 funds settles while matching ACK submission stays blocked", pending)
            v.tool("snapshot", "funds")
            v.tool("snapshot", "matching")
            v.awaitStates("R17 both service snapshot callbacks progress while ACK is blocked", 45_000) {
                pending.matches(it) && it.values.all { state -> v.number(state, "snapshotCount") > 0 }
            }
            v.release("result-records")
            v.expect("R7 buyer898 seller99 fee3 equals initial1000", pending.copy(settled = 2))
        }

    @Test
    fun `one retained result returns BUSY then drains without issuing the next order twice`() =
        verify("cap") { v ->
            v.hold("results")
            v.tool("admit", "seller", "1", "100", "1")
            v.awaitStates("R16 first result occupies the single result slot", 45_000) { states ->
                states.filterKeys { it.startsWith("matching") }.values.all {
                    it.path("matching").path("resultEntries").asLong() == 1L && it.path("matching").path("appliedThrough").asLong() == 1L
                }
            }
            v.tool("admit", "seller", "2", "100", "1")
            v.awaitFile(v.control.resolve("received-order-busy.json"), 30_000)
            v.awaitStates("R16 BUSY retains two funds reservations and one matching result", 30_000) { states ->
                states.all { (name, state) ->
                    val role = state.path(if (name.startsWith("funds")) "funds" else "matching")
                    if (name.startsWith("funds")) {
                        role.path("issued").asLong() == 2L && role.path("sellerXHold").asLong() == 2L &&
                            role.path("resultThrough").asLong() == 0L
                    } else {
                        role.path("appliedThrough").asLong() == 1L && role.path("resultEntries").asLong() == 1L
                    }
                }
            }
            v.release("results")
            v.expect(
                "R16 existing result and ACK drain before the same second order progresses",
                ResultExpected(2, 2, 1000, 0, 0, 0, 0, 2, 2, 2, 2, 0),
            )
        }

    @Test
    fun `active cap rejection releases only the new reservation`() =
        verify("active") { v ->
            v.tool("admit", "seller", "1", "400", "1")
            v.expect("R14 original noncrossing maker keeps active reservation", ResultExpected(1, 1, 1000, 0, 0, 0, 1, 1, 1, 1, 1, 0))
            v.hold("results")
            v.tool("admit", "buyer", "1", "300", "1")
            v.awaitStates("R14 buyer reservation300 remains until confirmed final rejection reaches funds", 45_000) { states ->
                states.all { (name, state) ->
                    if (name.startsWith("funds")) {
                        val funds = state.path("funds")
                        funds.path("buyerQ").asLong() == 700L && funds.path("buyerQHold").asLong() == 300L &&
                            funds.path("resultThrough").asLong() == 1L && funds.path("issued").asLong() == 2L &&
                            funds.path("sellerXHold").asLong() == 1L
                    } else {
                        val matching = state.path("matching")
                        matching.path("resultIssued").asLong() == 2L && matching.path("fundsSettledThrough").asLong() == 1L &&
                            matching.path("resultEntries").asLong() == 1L && matching.path("engineSequence").asLong() == 1L
                    }
                }
            }
            v.release("results")
            val expected = ResultExpected(2, 2, 1000, 0, 0, 0, 1, 1, 1, 1, 1, 0)
            v.expect("R14 final rejection returns buyer300 while original seller remains", expected)
            val replay =
                v.mapper.readTree(
                    v
                        .tool("replay-result", "2")
                        .stdout
                        .lineSequence()
                        .last { it.isNotBlank() },
                )
            check(replay.path("code").asString() == "DUPLICATE" && replay.path("through").asLong() == 2L)
            v.expect("R14 repeating final rejection does not release another300", expected)
        }

    @Test
    fun `near logical one MiB full result crosses real Aeron as one complete settlement`() =
        verify("large") { v ->
            v.tool("admit", "seller", "1", "100", "1")
            v.awaitStates("R3 first real maker supplies fixture sizing origin", 45_000) { states ->
                states
                    .filterKeys {
                        it.startsWith(
                            "matching",
                        )
                    }.values
                    .all { it.path("matching").path("fundsSettledThrough").asLong() == 1L }
            }
            val first =
                (
                    SplitResultDeliveryCodec()
                        .decode(
                            Files.readAllBytes(v.control.resolve("result-1.bin")),
                        ).command as SplitResultDeliveryCommand.Apply
                ).result
            val count = largestNormalMakerCount(first)
            check(count in 2..1023)
            v.tool("makers", count.toString())
            v.awaitStates("R3 all actual makers applied and reclaimed before large taker", 60_000) { states ->
                states.all { (name, state) ->
                    if (name.startsWith("funds")) {
                        state.path("funds").path("resultThrough").asLong() == count.toLong()
                    } else {
                        state.path("matching").path("fundsSettledThrough").asLong() == count.toLong() &&
                            state.path("matching").path("bookOrders").asLong() == count.toLong()
                    }
                }
            }
            v.tool("admit", "buyer", "1", "100", count.toString())
            val final =
                ResultExpected(
                    count + 1L,
                    count + 1L,
                    1_000_000L - 100L * count,
                    0,
                    count.toLong(),
                    100L * count,
                    1024L - count,
                    0,
                    0,
                    0,
                    2L * count,
                    count.toLong(),
                )
            val settled = v.expect("R3 every event and effect arrives at all funds callbacks near 1MiB", final)
            val bytes = Files.readAllBytes(v.control.resolve("result-${count + 1}.bin"))
            val result = (SplitResultDeliveryCodec().decode(bytes).command as SplitResultDeliveryCommand.Apply).result
            check(bytes.size > 32 * 1024 && bytes.size <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
            check(result.canonicalBytes in (1024L * 1024 - 4096)..(1024L * 1024))
            check(result.eventCount == count && result.effects.size == count + 1)
            check(
                settled.filterKeys { it.startsWith("funds") }.values.all { state ->
                    val receipt =
                        state
                            .path("funds")
                            .path("receipts")
                            .toList()
                            .last()
                    receipt.path("eventCount").asLong() == count.toLong() && receipt.path("effectCount").asLong() == count + 1L &&
                        receipt.path("logicalBytes").asLong() == result.canonicalBytes
                },
            )
            v.checkpoints +=
                mapOf(
                    "case" to "R3 actual logical boundary and native fragmented frame",
                    "makerCount" to count,
                    "logicalBytes" to result.canonicalBytes,
                    "wireBytes" to bytes.size,
                    "eventCount" to result.eventCount,
                    "effectCount" to result.effects.size,
                )
            check(
                settled.filterKeys { it.startsWith("funds") }.values.all {
                    it.path("funds").path("retiredThrough").asLong() >= 1L
                },
            )
            val pruned =
                v.mapper.readTree(
                    v
                        .tool("replay-result", "1")
                        .stdout
                        .lineSequence()
                        .last { it.isNotBlank() },
                )
            check(pruned.path("code").asString() == "PRUNED" && pruned.path("through").asLong() == count + 1L)
            val afterPruned =
                v.awaitStates("R9 actual FIFO-retired result1 returns prefix without financial changes", 45_000) { states ->
                    final.matches(states) &&
                        states.all { (name, state) ->
                            val role = if (name.startsWith("funds")) "funds" else "matching"
                            state.path(role) == settled.getValue(name).path(role) &&
                                (role != "funds" || v.number(state, "logPosition") > v.number(settled.getValue(name), "logPosition"))
                        }
                }
            v.checkpoints +=
                mapOf(
                    "case" to "R9 actual retired receipt original replay",
                    "reply" to pruned,
                    "retiredThrough" to
                        afterPruned
                            .getValue("funds0")
                            .path("funds")
                            .path("retiredThrough")
                            .asLong(),
                )
            val beforeBoundary = v.readStates()
            val boundary =
                v.mapper.readTree(
                    v
                        .tool("frame-boundary")
                        .stdout
                        .lineSequence()
                        .last { it.isNotBlank() },
                )
            check(boundary.path("frameBytes").asInt() == SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
            check(boundary.path("positiveOfferPosition").asLong() > 0)
            check(boundary.path("code").asString() in setOf("INVALID_STREAM", "INVALID_RESULT"))
            check(boundary.path("maxPlusOneBytes").asInt() == SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1)
            check(boundary.path("maxPlusOneRejected").asBoolean())
            val afterBoundary =
                v.awaitStates("R3 native MAX and MAX+1 leave all six business copies unchanged", 45_000) { states ->
                    final.matches(states) &&
                        states.all { (name, state) ->
                            val role = if (name.startsWith("funds")) "funds" else "matching"
                            state.path(role) == beforeBoundary.getValue(name).path(role) &&
                                (role != "funds" || v.number(state, "logPosition") > v.number(beforeBoundary.getValue(name), "logPosition"))
                        }
                }
            v.checkpoints +=
                mapOf(
                    "case" to "R3 actual native frame MAX callback and MAX+1 connection rejection",
                    "boundaryReply" to boundary,
                    "beforeStates" to beforeBoundary,
                    "afterStates" to afterBoundary,
                )
        }

    private fun verify(
        fixture: String,
        scenario: (DockerFundsOrderDeliveryVerification) -> Unit,
    ) {
        val verification = DockerFundsOrderDeliveryVerification(fixture, scenario)
        assertTrue(verification.run(), "${verification.failures.joinToString("\n")}\n보고서: ${verification.reports}")
    }
}

/** 수신 응답에서 기대값을 만들지 않고 각 역할 세 사본을 수기 산식의 상수에 대조한다. */
private data class ResultExpected(
    val issued: Long,
    val settled: Long,
    val buyerQ: Long,
    val buyerHold: Long,
    val buyerX: Long,
    val sellerQ: Long,
    val sellerX: Long,
    val sellerHold: Long,
    val live: Long,
    val book: Long,
    val engine: Long,
    val trades: Long,
    val feeQ: Long = 0,
    val buyerXHold: Long = 0,
) {
    fun matches(states: Map<String, JsonNode>): Boolean =
        states.size == 6 &&
            states.all { (name, state) ->
                if (!state.path("initialized").asBoolean()) {
                    false
                } else if (name.startsWith("funds")) {
                    val funds = state.path("funds")
                    mapOf(
                        "buyerQ" to buyerQ,
                        "buyerQHold" to buyerHold,
                        "buyerX" to buyerX,
                        "buyerXHold" to buyerXHold,
                        "sellerQ" to sellerQ,
                        "sellerX" to sellerX,
                        "sellerXHold" to sellerHold,
                        "feeQ" to feeQ,
                        "liveOrders" to live,
                        "activeOrders" to live,
                        "pendingOrders" to 0L,
                        "remainingQuantity" to book,
                        "dispatchEntries" to 0L,
                        "dispatchBytes" to 0L,
                        "matchingAppliedThrough" to issued,
                        "issued" to issued,
                        "executedThrough" to issued,
                        "resultThrough" to issued,
                        "settlementTrades" to trades,
                    ).all { (field, expected) ->
                        funds.has(field) && funds.path(field).isIntegralNumber && funds.path(field).asLong() == expected
                    }
                } else {
                    val matching = state.path("matching")
                    mapOf(
                        "bookOrders" to book,
                        "bookQuantity" to book,
                        "activeOrders" to book,
                        "engineSequence" to engine,
                        "appliedThrough" to issued,
                        "resultIssued" to issued,
                        "resultEntries" to issued - settled,
                        "fundsSettledThrough" to settled,
                    ).all { (field, expected) ->
                        matching.has(field) && matching.path(field).isIntegralNumber &&
                            matching.path(field).asLong() == expected
                    } &&
                        (issued != settled || matching.path("resultBytes").asLong() == 0L)
                }
            }
}

private fun DockerFundsOrderDeliveryVerification.hold(name: String) {
    Files.writeString(control.resolve("hold-$name"), UUID.randomUUID().toString())
}

private fun DockerFundsOrderDeliveryVerification.release(name: String) {
    Files.delete(control.resolve("hold-$name"))
}

private fun DockerFundsOrderDeliveryVerification.expect(
    label: String,
    expected: ResultExpected,
): Map<String, JsonNode> = awaitStates(label, 45_000, expected::matches)

private fun DockerFundsOrderDeliveryVerification.awaitObservation(
    name: String,
    code: String,
    through: Long,
) {
    awaitStates("$name code=$code through=$through", 30_000) { states ->
        val path = control.resolve(name)
        if (!Files.exists(path)) {
            false
        } else {
            val observed = mapper.readTree(Files.readString(path))
            observed.path("code").asString() == code && number(observed, "through") == through &&
                states.filterKeys { it.startsWith("matching") }.values.any {
                    it.path("bootId").asString() ==
                        observed.path("bootId").asString()
                }
        }
    }
}

/** 생성 전 크기 계산만 한다. 전송·주문장·정산에는 실제 승인 원본만 사용한다. */
private fun largestNormalMakerCount(first: MatchingResultEnvelope): Int {
    val origin = first.input
    val buyer = UserId("😀".repeat(64))

    val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    fun originAt(
        sequence: Long,
        side: Side,
        quantity: Long = 1,
    ): FundsOrderDispatch {
        val order =
            origin.order.copy(
                orderId = TradingIdentifiers.order(origin.stream.fundsLineage, sequence),
                userId = if (side == Side.BUY) buyer else origin.order.userId,
                side = side,
                quantity = Quantity(quantity),
            )
        val context = origin.context
        val requirement = reservations.calculate(context.market, side, order.price, order.quantity, context.feePolicySnapshot)
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                side,
                order.price,
                order.quantity,
                requirement,
                context.feePolicySnapshot,
            )
        return origin.copy(
            dispatchSequence = sequence,
            admissionSequence = sequence,
            marketAdmissionSequence = sequence,
            order = order,
            reservation = reservation,
        )
    }

    fun bytes(count: Int): Long {
        val taker = originAt(count + 1L, Side.BUY, count.toLong())
        val makers = (1..count).map { originAt(it.toLong(), Side.SELL) }
        return MatchingResultEnvelope(
            first.stream,
            count + 1L,
            taker,
            MatchingOutcome.MATCHED,
            null,
            count.toLong(),
            2L * count,
            makers.mapIndexed { index, maker ->
                TradeExecuted(
                    first.stream.marketId,
                    count + index + 1L,
                    maker.order.orderId,
                    taker.order.orderId,
                    maker.order.userId,
                    taker.order.userId,
                    Side.BUY,
                    maker.order.price,
                    Quantity(1),
                )
            },
            listOf(MatchingOrderEffect(taker, Quantity(count.toLong()), Quantity.ZERO, MatchingOrderDisposition.TERMINAL)) +
                makers.map { MatchingOrderEffect(it, Quantity(1), Quantity.ZERO, MatchingOrderDisposition.TERMINAL) },
        ).canonicalBytes
    }
    var lower = 1
    var upper = 1023
    while (lower < upper) {
        val middle = (lower + upper + 1) / 2
        if (bytes(middle) <= 1024L * 1024) lower = middle else upper = middle - 1
    }
    return lower
}

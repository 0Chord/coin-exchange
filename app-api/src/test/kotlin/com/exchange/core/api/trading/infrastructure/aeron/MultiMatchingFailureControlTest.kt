package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketContext
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 실제 장애 시험과 별도로, 보류가 정상 전송이나 다른 마켓까지 막는 거짓 성공을 방지한다. */
class MultiMatchingFailureControlTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()
    private val orderCodec = SplitOrderDeliveryCodec(2)
    private val resultCodec = SplitResultDeliveryCodec(2)
    private val funds = ClusterLineage(1, 2)
    private val matching = ClusterLineage(3, 4)
    private val endpoint = SplitOrderDeliveryEndpoint("unused", "0=127.0.0.1:19700", "127.0.0.1:19708", 199, 2)

    @Test
    fun `주문 보류는 BTC 입력1의 offer만 막고 해제하면 같은 원문을 실제 delegate에 보낸다`() {
        val calls = Calls()
        val control = MultiMatchingFailureControl("run", "boot", "funds0", directory, orderFactory = calls.orders())
        gate("btc-dispatch", 1, true)
        control.orderConnections().begin(endpoint, 1_000) {}.use { attempt ->
            checkNotNull(attempt.poll()).use { connection ->
                val original = orderCodec.encode(91, SplitOrderDeliveryCommand.Apply(input("BTC", 1)))
                assertEquals(Publication.BACK_PRESSURED, connection.offer(original))
                assertTrue(calls.offers.isEmpty())
                val held = observation().path("blocked")
                assertEquals("boot", held.path("bootId").asString())
                assertContentEquals(original, Files.readAllBytes(directory.resolve("held-funds0-btc-dispatch-1.bin")))
                // 재연결/재시도 상관번호가 달라져도 업무 원문은 같다.
                val retried = orderCodec.encode(92, SplitOrderDeliveryCommand.Apply(input("BTC", 1)))
                assertEquals(Publication.BACK_PRESSURED, connection.offer(retried))
                val peer = orderCodec.encode(93, SplitOrderDeliveryCommand.DescribePeer)
                val sui = orderCodec.encode(94, SplitOrderDeliveryCommand.Apply(input("SUI", 1)))
                for (bytes in listOf(peer, sui)) assertEquals(73L, connection.offer(bytes))
                assertEquals(7, connection.poll())
                assertTrue(connection.keepAlive())
                assertEquals(1, calls.polls)
                assertEquals(1, calls.keepAlives)
                gate("btc-dispatch", 1, false)
                assertEquals(73L, connection.offer(retried))
                assertEquals(3, calls.offers.size)
                assertContentEquals(retried, calls.offers.last())
                val offered = observation().path("offered")
                assertEquals(held.path("payloadSha256"), offered.path("payloadSha256"))
                assertNotEquals(held.path("wireSha256"), offered.path("wireSha256"))
            }
        }
        assertEquals(1, calls.closes)
        assertEquals(1, calls.attemptCloses)
    }

    @Test
    fun `결과 보류는 BTC 결과2만 막고 peer 조회와 다른 마켓은 정상 전송한다`() {
        val calls = Calls()
        val control = MultiMatchingFailureControl("run", "boot", "btc0", directory, resultFactory = calls.results())
        gate("btc-result", 2, true)
        control.resultConnections().begin(endpoint, 1_000) {}.use { attempt ->
            checkNotNull(attempt.poll()).use { connection ->
                val target = resultCodec.encode(91, SplitResultDeliveryCommand.Apply(result("BTC", 2)))
                assertEquals(Publication.BACK_PRESSURED, connection.offer(target))
                assertTrue(calls.offers.isEmpty())
                for (command in listOf(
                    SplitResultDeliveryCommand.DescribePeer,
                    SplitResultDeliveryCommand.Apply(result("BTC", 1)),
                    SplitResultDeliveryCommand.Apply(result("SUI", 2)),
                )) {
                    assertEquals(73L, connection.offer(resultCodec.encode(92, command)))
                }
                gate("btc-result", 2, false)
                assertEquals(73L, connection.offer(target))
                assertEquals(4, calls.offers.size)
                assertContentEquals(target, calls.offers.last())
                assertEquals(result("BTC", 2).resultId, observation().path("offered").path("resultId").asString())
            }
        }
        assertEquals(1, calls.closes)
        assertEquals(1, calls.attemptCloses)
    }

    @Test
    fun `같은 보류 대상의 주문 내용이 바뀌면 전송하지 않고 오류를 드러낸다`() {
        val calls = Calls()
        val control = MultiMatchingFailureControl("run", "boot", "funds0", directory, orderFactory = calls.orders())
        gate("btc-dispatch", 1, true)
        control.orderConnections().begin(endpoint, 1_000) {}.use { attempt ->
            checkNotNull(attempt.poll()).use { connection ->
                assertEquals(
                    Publication.BACK_PRESSURED,
                    connection.offer(orderCodec.encode(91, SplitOrderDeliveryCommand.Apply(input("BTC", 1)))),
                )
                assertFailsWith<IllegalStateException> {
                    connection.offer(orderCodec.encode(92, SplitOrderDeliveryCommand.Apply(input("BTC", 1, 2))))
                }
                assertTrue(calls.offers.isEmpty())
            }
        }
    }

    @Test
    fun `처음 설치한 제어 JSON이 손상됐으면 보류 해제로 간주하지 않는다`() {
        val calls = Calls()
        val control = MultiMatchingFailureControl("run", "boot", "funds0", directory, orderFactory = calls.orders())
        Files.write(directory.resolve("failover-control.json"), "{".toByteArray())
        control.orderConnections().begin(endpoint, 1_000) {}.use { attempt ->
            checkNotNull(attempt.poll()).use { connection ->
                assertFailsWith<tools.jackson.core.exc.UnexpectedEndOfInputException> {
                    connection.offer(orderCodec.encode(91, SplitOrderDeliveryCommand.Apply(input("BTC", 1))))
                }
                assertTrue(calls.offers.isEmpty())
            }
        }
    }

    private fun gate(
        kind: String,
        sequence: Long,
        enabled: Boolean,
    ) {
        if (!enabled) {
            Files.createFile(directory.resolve("failover-release-one"))
            return
        }
        Files.write(
            directory.resolve("failover-control.json"),
            json.writeValueAsBytes(
                mapOf(
                    "installationId" to "one",
                    "gate" to kind,
                    "sequence" to sequence,
                    "enabled" to enabled,
                ),
            ),
        )
    }

    private fun observation() =
        json.readTree(
            Files.readAllBytes(
                directory.resolve("worker-${if (Files.exists(directory.resolve("worker-funds0.json"))) "funds0" else "btc0"}.json"),
            ),
        )

    private fun input(
        base: String,
        sequence: Long,
        quantity: Long = 1,
    ): FundsOrderDispatch {
        val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.VVIP, 1, MakerTakerFeeRates(FeeRate(10_000), FeeRate(10_000)))
        val context = TradingMarketContext(MarketDefinition(MarketId("$base-USDT"), AssetId(base), AssetId("USDT"), 0), policy, 7)
        val order =
            OrderAdmissionInput(
                context.market.marketId,
                TradingIdentifiers.order(funds, sequence),
                UserId("buyer"),
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100),
                Quantity(quantity),
            )
        val quote =
            OrderReservationCalculator(
                BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()),
            ).calculate(context.market, order.side, order.price, order.quantity, policy)
        val reservation =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                quote,
                policy,
            )
        return FundsOrderDispatch(
            FundsDispatchStream(funds, matching, order.marketId, 1, 7),
            sequence,
            sequence,
            sequence,
            order,
            reservation,
            context,
            inputContractVersion = 2,
        )
    }

    private fun result(
        base: String,
        sequence: Long,
    ): MatchingResultEnvelope {
        val input = input(base, sequence)
        val order = input.order
        return MatchingResultEnvelope(
            MatchingResultStream(matching, funds, order.marketId, 1, 7),
            sequence,
            input,
            MatchingOutcome.MATCHED,
            null,
            sequence - 1,
            sequence,
            listOf(OrderEnteredBook(order.marketId, sequence, order.orderId, order.userId, order.side, order.price, order.quantity)),
            listOf(MatchingOrderEffect(input, order.quantity, order.quantity, MatchingOrderDisposition.RESTING)),
        )
    }

    private class Calls {
        val offers = mutableListOf<ByteArray>()
        var polls = 0
        var keepAlives = 0
        var closes = 0
        var attemptCloses = 0

        fun orders() =
            SplitOrderDeliveryConnectionFactory { _, _, _ ->
                object : SplitOrderDeliveryConnectAttempt {
                    override fun poll() = RecordingConnection()

                    override fun close() {
                        attemptCloses++
                    }
                }
            }

        fun results() =
            SplitResultDeliveryConnectionFactory { _, _, _ ->
                object : SplitResultDeliveryConnectAttempt {
                    override fun poll() = RecordingConnection()

                    override fun close() {
                        attemptCloses++
                    }
                }
            }

        private inner class RecordingConnection :
            SplitOrderDeliveryConnection,
            SplitResultDeliveryConnection {
            override val isClosed get() = false

            override fun offer(bytes: ByteArray): Long {
                offers += bytes
                return 73
            }

            override fun poll(): Int {
                polls++
                return 7
            }

            override fun keepAlive(): Boolean {
                keepAlives++
                return true
            }

            override fun close() {
                closes++
            }
        }
    }
}

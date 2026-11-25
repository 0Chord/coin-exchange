package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TradingIdentifierDigestTest {
    private val calculator =
        OrderAdmissionCalculator(
            OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())),
        )
    private val policy =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val user = UserId("user-1")
    private val quote = AssetId("USDT")
    private val cases =
        listOf(
            Input("BTC-USDT", "order-1", 3),
            Input("a:b", "c", 4),
            Input("a", "b:c", Long.MAX_VALUE),
            Input("가", "😀", 1),
            Input("비트:BTC/USDT", "주문:😀", Long.MAX_VALUE),
            Input("😀".repeat(64), "가".repeat(64), 0x0102030405060708L),
            Input("가".repeat(64), "😀".repeat(64), 0x0000000080000000L),
        )

    @Test
    fun `같은 스레드에서 예약과 체결 ID를 번갈아 만들어도 원본 해시 계약과 최초 값이 유지된다`() {
        val first = reserveId(cases.first())
        assertEquals("RESERVE:v1:93da2d89e22f67a13d2b1026ef4c70c430d7e2dcab84c86c606007ff42cdd355", first)
        assertEquals("SETTLEMENT:v1:g-vDKCVwQ6mt0t1hw3lYL7amsJsrH-kDq5PqpXy85hQ", settlementId(cases.first()))
        assertEquals("RESERVE:v1:f74dc04a3a228e5d8c4d2388ddd3631f062eda740e2125fab790fdb3de7d3e4d", reserveId(cases[3]))
        repeat(32) {
            cases.forEach { input ->
                assertEquals(referenceReserve(input), reserveId(input), "예약: $input")
                assertEquals(referenceSettlement(input), settlementId(input), "체결: $input")
                assertEquals(referenceReserve(input), reserveId(input), "체결 직후 예약: $input")
            }
        }
        assertNotEquals(reserveId(cases[1]), reserveId(cases[2]), "콜론이 있는 튜플은 길이 구간으로 구별한다")
        assertEquals(first, reserveId(cases.first()), "뒤 호출이 앞선 결과나 해시 상태를 바꾸지 않는다")
    }

    @Test
    fun `공유 계산기의 동시 예약과 체결 ID는 각 호출의 독립 SHA256 결과와 같다`() {
        val workers = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val results =
                (0..3).map { worker ->
                    workers.submit {
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS), "동시 시작 대기 초과")
                        repeat(200) { iteration ->
                            val input = cases[(worker + iteration) % cases.size]
                            assertEquals(referenceReserve(input), reserveId(input), "예약 worker=$worker, iteration=$iteration")
                            assertEquals(referenceSettlement(input), settlementId(input), "체결 worker=$worker, iteration=$iteration")
                        }
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 작업자의 시작을 확인한다")
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "동시 검사의 작업자를 종료한다")
        }
    }

    private fun reserveId(input: Input): String {
        val market = MarketDefinition(MarketId(input.market), AssetId("BTC"), quote, 8)
        val order =
            OrderAdmissionInput(
                market.marketId,
                OrderId(input.order),
                user,
                Side.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                Price(100_000_000),
                Quantity(300_000_000),
            )
        val result =
            calculator.calculate(
                order,
                market,
                policy,
                Balance(user, quote, Amount(1_000_000_000), Amount.ZERO),
                "ledger-1",
                Instant.EPOCH,
            )
        return assertIs<OrderAdmissionResult.Calculated>(result).candidate.reserveTransaction.sourceEventId
    }

    private fun settlementId(input: Input): String = settlementTransactionId(MarketId(input.market), input.sequence)

    /** 제품 바이트 작성과 별개로 길이·UTF8·순번을 기록하고 매번 새 SHA-256 실행기로 대조한다. */
    private fun referenceReserve(input: Input): String {
        val market = input.market.toByteArray(Charsets.UTF_8)
        val order = input.order.toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(market.size)
            output.write(market)
            output.writeInt(order.size)
            output.write(order)
        }
        return "RESERVE:v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }

    private fun referenceSettlement(input: Input): String {
        val market = input.market.toByteArray(Charsets.UTF_8)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(byteArrayOf(83, 84, 76, 49))
            output.writeInt(market.size)
            output.write(market)
            output.writeLong(input.sequence)
        }
        return "SETTLEMENT:v1:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }

    private data class Input(
        val market: String,
        val order: String,
        val sequence: Long,
    )
}

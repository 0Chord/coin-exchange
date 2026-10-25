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
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.ReservationRequirement
import com.exchange.core.order.Side
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 순수 생성기를 실제 정산 계산과 연결하고, 전체 분개는 손으로 정한 필드와 대조한다. */
class SettlementLedgerTransactionsTest {
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val time = Instant.parse("2026-10-25T01:02:03Z")
    private val calculator = OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator())
    private val transactions = SettlementLedgerTransactions()

    @Test
    fun `가격차와 양쪽 수수료를 maker 다음 taker 분개에 한 번 기록하고 ID 시각을 보존한다`() {
        val policy = policy(makerRate = 10_000, takerRate = 20_000)
        val seller = reservation("seller", "sell", Side.SELL, price = 90, feeReserve = 0, policy = policy)
        val buyer = reservation("buyer", "buy", Side.BUY, price = 100, feeReserve = 4, policy = policy)

        val transaction =
            transactions.create(
                market = market,
                engineSequence = 7,
                makerReservation = seller,
                makerPlan = calculator.calculate(market, seller, Price(90), Quantity(2), LiquidityRole.MAKER),
                takerReservation = buyer,
                takerPlan = calculator.calculate(market, buyer, Price(90), Quantity(2), LiquidityRole.TAKER),
                ledgerTransactionId = "settlement-seven",
                occurredAt = time,
            )

        assertEquals("settlement-seven", transaction.ledgerTransactionId)
        assertEquals("MATCHING:BTC-USDT:7", transaction.sourceEventId)
        assertEquals(LedgerTransactionType.SETTLEMENT, transaction.transactionType)
        assertEquals(time, transaction.occurredAt)
        assertEquals(
            listOf(
                posting("USER:seller:BTC:HOLD", btc, LedgerPostingSide.DEBIT, 2),
                posting("USER:seller:USDT:AVAILABLE", usdt, LedgerPostingSide.CREDIT, 179),
                posting("SYSTEM:USDT:FEE_REVENUE", usdt, LedgerPostingSide.CREDIT, 1),
                posting("USER:buyer:USDT:HOLD", usdt, LedgerPostingSide.DEBIT, 183),
                posting("USER:buyer:USDT:HOLD", usdt, LedgerPostingSide.DEBIT, 21),
                posting("USER:buyer:USDT:AVAILABLE", usdt, LedgerPostingSide.CREDIT, 21),
                posting("USER:buyer:BTC:AVAILABLE", btc, LedgerPostingSide.CREDIT, 2),
                posting("SYSTEM:USDT:FEE_REVENUE", usdt, LedgerPostingSide.CREDIT, 3),
            ),
            transaction.postings,
        )
        assertEquals(Amount(204), buyer.remainingAmount)
        assertEquals(Amount(2), seller.remainingAmount)
    }

    @Test
    fun `BUY maker와 SELL taker의 수수료와 반환이 0이면 네 양수 항목만 만든다`() {
        val buyer = reservation("buyer", "buy", Side.BUY, price = 100)
        val seller = reservation("seller", "sell", Side.SELL, price = 90)
        val makerPlan = calculator.calculate(market, buyer, Price(100), Quantity(2), LiquidityRole.MAKER)
        val takerPlan = calculator.calculate(market, seller, Price(100), Quantity(2), LiquidityRole.TAKER)

        val first = transactions.create(market, 9, buyer, makerPlan, seller, takerPlan, "settlement-nine", time)
        val repeated = transactions.create(market, 9, buyer, makerPlan, seller, takerPlan, "settlement-nine", time)

        assertEquals(
            listOf(
                posting("USER:buyer:USDT:HOLD", usdt, LedgerPostingSide.DEBIT, 200),
                posting("USER:buyer:BTC:AVAILABLE", btc, LedgerPostingSide.CREDIT, 2),
                posting("USER:seller:BTC:HOLD", btc, LedgerPostingSide.DEBIT, 2),
                posting("USER:seller:USDT:AVAILABLE", usdt, LedgerPostingSide.CREDIT, 200),
            ),
            first.postings,
        )
        assertEquals(first.ledgerTransactionId, repeated.ledgerTransactionId)
        assertEquals(first.sourceEventId, repeated.sourceEventId)
        assertEquals(first.transactionType, repeated.transactionType)
        assertEquals(first.occurredAt, repeated.occurredAt)
        assertEquals(first.postings, repeated.postings)
    }

    @Test
    fun `양쪽 계획의 금액이 균형을 이루지 않으면 원장 생성에 실패한다`() {
        val buyer = reservation("buyer", "buy", Side.BUY, price = 100)
        val seller = reservation("seller", "sell", Side.SELL, price = 90)
        val makerPlan = calculator.calculate(market, seller, Price(90), Quantity(2), LiquidityRole.MAKER)
        val takerPlan = calculator.calculate(market, buyer, Price(90), Quantity(2), LiquidityRole.TAKER)

        assertFailsWith<IllegalArgumentException> {
            transactions.create(
                market,
                1,
                seller,
                makerPlan.copy(creditAmount = Amount(179)),
                buyer,
                takerPlan,
                "unbalanced",
                time,
            )
        }
    }

    @Test
    fun `원장 ID가 비었거나 사건 순번이 양수가 아니면 생성하지 않는다`() {
        val buyer = reservation("buyer", "buy", Side.BUY, price = 100)
        val seller = reservation("seller", "sell", Side.SELL, price = 90)
        val makerPlan = calculator.calculate(market, seller, Price(90), Quantity(2), LiquidityRole.MAKER)
        val takerPlan = calculator.calculate(market, buyer, Price(90), Quantity(2), LiquidityRole.TAKER)

        listOf("", " ").forEach { invalidId ->
            assertFailsWith<IllegalArgumentException> {
                transactions.create(market, 1, seller, makerPlan, buyer, takerPlan, invalidId, time)
            }
        }
        listOf(0L, -1L).forEach { invalidSequence ->
            assertFailsWith<IllegalArgumentException> {
                transactions.create(market, invalidSequence, seller, makerPlan, buyer, takerPlan, "settlement", time)
            }
        }
    }

    private fun reservation(
        userId: String,
        orderId: String,
        side: Side,
        price: Long,
        feeReserve: Long = 0,
        policy: TradingFeePolicySnapshot = policy(),
    ): OrderReservation =
        OrderReservation.create(
            marketId = market.marketId,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            side = side,
            limitPrice = Price(price),
            quantity = Quantity(2),
            requirement =
                ReservationRequirement(
                    assetId = if (side == Side.BUY) usdt else btc,
                    tradeReserveAmount = Amount(if (side == Side.BUY) price * 2 else 2),
                    feeReserveAmount = Amount(feeReserve),
                ),
            feePolicySnapshot = policy,
        )

    private fun policy(
        makerRate: Long = 0,
        takerRate: Long = 0,
    ): TradingFeePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 3,
            feeRates = MakerTakerFeeRates(FeeRate(makerRate), FeeRate(takerRate)),
        )

    private fun posting(
        accountId: String,
        assetId: AssetId,
        side: LedgerPostingSide,
        amount: Long,
    ): LedgerPosting = LedgerPosting(accountId, assetId, side, Amount(amount))
}

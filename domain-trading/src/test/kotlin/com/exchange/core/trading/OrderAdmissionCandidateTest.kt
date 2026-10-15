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
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 잔고·예약·원장을 독립적으로 준비하여 SELL 후보의 조합 검증을 직접 확인한다. */
class OrderAdmissionCandidateTest {
    private val user = UserId("user-1")
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 8)
    private val policy =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.VIP,
            7,
            MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)),
        )
    private val order =
        OrderAdmissionInput(
            market.marketId,
            OrderId("order-1"),
            user,
            Side.SELL,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(100_000_000),
            Quantity(200_000_000),
        )
    private val previous = Balance(user, btc, Amount(300_000_000), Amount.ZERO)
    private val next = Balance(user, btc, Amount(100_000_000), Amount(200_000_000))
    private val reservation =
        OrderReservation(
            marketId = market.marketId,
            orderId = order.orderId,
            userId = user,
            side = Side.SELL,
            assetId = btc,
            limitPrice = order.price,
            initialQuantity = Quantity(200_000_000),
            remainingQuantity = Quantity(200_000_000),
            reservedAmount = Amount(200_000_000),
            remainingAmount = Amount(200_000_000),
            feePolicySnapshot = policy,
            initialFeeReserveAmount = Amount.ZERO,
            remainingFeeReserveAmount = Amount.ZERO,
            status = OrderReservationStatus.ACTIVE,
        )
    private val postings =
        listOf(
            LedgerPosting("USER:user-1:BTC:AVAILABLE", btc, LedgerPostingSide.DEBIT, Amount(200_000_000)),
            LedgerPosting("USER:user-1:BTC:HOLD", btc, LedgerPostingSide.CREDIT, Amount(200_000_000)),
        )

    @Test
    fun `서로 맞는 SELL 주문 잔고 예약 원장은 후보를 만들 수 있다`() {
        val record = transaction()
        val candidate = candidate(reserveTransaction = record)

        assertEquals(order, candidate.order)
        assertEquals(market, candidate.market)
        assertEquals(policy, candidate.feePolicySnapshot)
        assertEquals(previous, candidate.previousBalance)
        assertEquals(next, candidate.nextBalance)
        assertEquals(reservation, candidate.reservation)
        assertEquals(record, candidate.reserveTransaction)
    }

    @Test
    fun `SELL의 잔고 예약 원장을 모두 quote로 맞춰도 팔 자산과 다르므로 거절한다`() {
        val wrongPrevious = previous.copy(assetId = usdt)
        val wrongNext = next.copy(assetId = usdt)
        val wrongReservation = reservation.copy(assetId = usdt)
        val wrongRecord = transaction(postings.map { it.copy(accountId = it.accountId.replace("BTC", "USDT"), assetId = usdt) })

        assertFailsWith<IllegalArgumentException> {
            candidate(
                previousBalance = wrongPrevious,
                nextBalance = wrongNext,
                reservation = wrongReservation,
                reserveTransaction = wrongRecord,
            )
        }
    }

    @Test
    fun `SELL 예약액 잔고 원장을 같은 잘못된 1점5개로 맞춰도 주문 수량 2개와 달라 거절한다`() {
        val wrongReservation = reservation.copy(reservedAmount = Amount(150_000_000), remainingAmount = Amount(150_000_000))
        val wrongNext = next.copy(available = Amount(150_000_000), hold = Amount(150_000_000))
        val wrongRecord = transaction(postings.map { it.copy(amount = Amount(150_000_000)) })

        assertFailsWith<IllegalArgumentException> {
            candidate(nextBalance = wrongNext, reservation = wrongReservation, reserveTransaction = wrongRecord)
        }
    }

    @Test
    fun `SELL 수수료를 예약액 안에 포함한 조합은 총액이 맞아도 거절한다`() {
        val wrongReservation = reservation.copy(initialFeeReserveAmount = Amount(1), remainingFeeReserveAmount = Amount(1))

        assertFailsWith<IllegalArgumentException> { candidate(reservation = wrongReservation) }
    }

    @Test
    fun `새 SELL의 주문 예약 정책 상태 잔량 나머지가 다르면 거절한다`() {
        val invalidReservations =
            listOf(
                reservation.copy(marketId = MarketId("OTHER-USDT")),
                reservation.copy(orderId = OrderId("other-order")),
                reservation.copy(userId = UserId("other-user")),
                reservation.copy(side = Side.BUY),
                reservation.copy(limitPrice = Price(200_000_000)),
                reservation.copy(initialQuantity = Quantity(300_000_000)),
                reservation.copy(remainingQuantity = Quantity(100_000_000)),
                reservation.copy(remainingAmount = Amount(100_000_000)),
                reservation.copy(feePolicySnapshot = policy.copy(scheduleVersion = 8)),
                reservation.copy(initialFeeReserveAmount = Amount(1)),
                reservation.copy(feeRemainder = FeeRemainder(1)),
                reservation.release(),
                reservation.copy(remainingQuantity = Quantity.ZERO, remainingAmount = Amount.ZERO, status = OrderReservationStatus.SETTLED),
            )

        invalidReservations.forEach { wrong ->
            assertFailsWith<IllegalArgumentException>(wrong.toString()) { candidate(reservation = wrong) }
        }
        listOf(order.copy(orderType = OrderType.MARKET), order.copy(timeInForce = TimeInForce.IOC)).forEach { wrong ->
            assertFailsWith<IllegalArgumentException> { candidate(order = wrong) }
        }
    }

    @Test
    fun `SELL의 잔고 소유자 자산 금액이 맞지 않으면 거절한다`() {
        listOf(
            previous.copy(userId = UserId("other-user")),
            previous.copy(assetId = usdt),
        ).forEach { wrong ->
            assertFailsWith<IllegalArgumentException> { candidate(previousBalance = wrong) }
        }
        listOf(
            next.copy(userId = UserId("other-user")),
            next.copy(assetId = usdt),
            next.copy(available = Amount(100_000_001)),
            next.copy(hold = Amount(200_000_001)),
        ).forEach { wrong ->
            assertFailsWith<IllegalArgumentException> { candidate(nextBalance = wrong) }
        }
        assertFailsWith<IllegalArgumentException> { candidate(market = market.copy(marketId = MarketId("OTHER-USDT"))) }
    }

    @Test
    fun `SELL 원장이 균형이어도 사용자 자산 계정 방향 금액 항목 수가 다르면 거절한다`() {
        val invalidPostings =
            listOf(
                postings.map { it.copy(accountId = it.accountId.replace("user-1", "other-user")) },
                postings.map { it.copy(accountId = it.accountId.replace("BTC", "USDT"), assetId = usdt) },
                postings.map { it.copy(accountId = it.accountId.replace("BTC", "USDT")) },
                listOf(postings[0].copy(side = LedgerPostingSide.CREDIT), postings[1].copy(side = LedgerPostingSide.DEBIT)),
                postings.map { it.copy(amount = Amount(100_000_000)) },
                postings.reversed(),
                postings + postings,
            )
        val records = invalidPostings.map { transaction(it) } + transaction(type = LedgerTransactionType.SETTLEMENT)

        records.forEach { wrong ->
            assertFailsWith<IllegalArgumentException> { candidate(reserveTransaction = wrong) }
        }
    }

    private fun candidate(
        order: OrderAdmissionInput = this.order,
        market: MarketDefinition = this.market,
        previousBalance: Balance = previous,
        nextBalance: Balance = next,
        reservation: OrderReservation = this.reservation,
        reserveTransaction: LedgerTransaction = transaction(),
    ) = OrderAdmissionCandidate(order, market, policy, previousBalance, nextBalance, reservation, reserveTransaction)

    private fun transaction(
        entries: List<LedgerPosting> = postings,
        type: LedgerTransactionType = LedgerTransactionType.RESERVE,
    ) = LedgerTransaction("ledger-1", "reserve-source", type, Instant.parse("2026-10-15T00:00:00Z"), entries)
}

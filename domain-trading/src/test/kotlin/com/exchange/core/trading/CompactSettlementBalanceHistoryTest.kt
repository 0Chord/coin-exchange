package com.exchange.core.trading

import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFixtures.balance
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.seller
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import com.exchange.core.trading.TradingStateFixtures.zeroPolicy
import java.lang.reflect.Modifier
import java.util.RandomAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompactSettlementBalanceHistoryTest {
    @Test
    fun `무료 유료 단일 다중 체결의 잔고는 정확히 복원하고 체결 예약 원장은 원래 참조를 유지한다`() {
        for (count in 1..2) {
            for (paid in listOf(false, true)) {
                val original = candidate(count, paid)
                val compact = original.compactForHistory()

                assertNotSame(original, compact)
                assertSame(compact, compact.compactForHistory())
                assertSame(original.order, compact.order)
                assertSame(original.market, compact.market)
                assertEquals(original.balanceChanges, compact.balanceChanges)
                assertSame(original.settlements, compact.settlements)
                assertSame(original.reservationChanges, compact.reservationChanges)
                original.settlements.forEachIndexed { index, expected ->
                    assertSame(expected, compact.settlements[index])
                    assertSame(expected.makerPlan, compact.settlements[index].makerPlan)
                    assertSame(expected.takerPlan, compact.settlements[index].takerPlan)
                    assertSame(expected.transaction, compact.settlements[index].transaction)
                }
            }
        }
    }

    @Test
    fun `과거 잔고 조회는 캐시하지 않으며 불변 RandomAccess 목록 계약을 유지한다`() {
        val original = candidate(2, paid = true)
        val compact = original.compactForHistory()

        assertTrue(compact.balanceChanges is RandomAccess)
        assertNotSame(compact.balanceChanges[0], compact.balanceChanges[0])
        assertNotSame(compact.balanceChanges[0].previous, compact.balanceChanges[0].previous)
        assertNotSame(original.balanceChanges[0].next, compact.balanceChanges[0].next)
        assertFailsWith<UnsupportedOperationException> { (compact.balanceChanges as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (compact.balanceChanges as MutableList)[0] = original.balanceChanges[0] }
        assertFailsWith<UnsupportedOperationException> { (compact.balanceChanges as MutableList).add(original.balanceChanges[0]) }
        assertFailsWith<IndexOutOfBoundsException> { compact.balanceChanges[-1] }
        assertFailsWith<IndexOutOfBoundsException> { compact.balanceChanges[compact.balanceChanges.size] }
        assertEquals(original.balanceChanges, compact.balanceChanges)
    }

    @Test
    fun `저장 표현은 원래 잔고와 목록을 붙잡지 않으며 size는 값 복원을 수행하지 않는다`() {
        val original = candidate(2, paid = true)
        val history = CompactSettlementBalanceHistory(original.balanceChanges)
        var identityArrays = 0
        var numericArrays = 0
        for (field in CompactSettlementBalanceHistory::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }) {
            field.isAccessible = true
            when (val value = field.get(history)) {
                is Int -> {
                    Unit
                }

                is LongArray -> {
                    numericArrays++
                }

                is Array<*> -> {
                    identityArrays++
                    assertTrue(value.all { it is String })
                }

                else -> {
                    error("과거 잔고에 예상하지 않은 객체 보관: ${field.name}=${value?.javaClass?.name}")
                }
            }
        }
        assertEquals(1, identityArrays)
        assertEquals(1, numericArrays)
        assertEquals(original.balanceChanges, history)
        (field(history, "amounts") as LongArray)[0] = -1
        assertEquals(original.balanceChanges.size, history.size)
        assertFailsWith<IllegalArgumentException> { history[0] }
    }

    @Test
    fun `빈 변경은 그대로 재사용하고 이전 이후의 소유자 자산 금액은 각각 보존한다`() {
        val empty = candidate(0)
        assertSame(empty, empty.compactForHistory())
        val original =
            OrderSettlementCandidate(
                empty.order,
                empty.market,
                emptyList(),
                listOf(OrderSettlementCandidate.BalanceChange(balance(buyer, usdt, 123, 456), balance(seller, btc, 789, 0))),
                emptyList(),
            )

        val compact = original.compactForHistory()

        assertEquals(original.balanceChanges, compact.balanceChanges)
        assertSame(original.settlements, compact.settlements)
        assertSame(original.reservationChanges, compact.reservationChanges)
        assertFailsWith<UnsupportedOperationException> { (compact.settlements as MutableList).clear() }
    }

    private fun candidate(
        count: Int,
        paid: Boolean = false,
    ): OrderSettlementCandidate {
        val feeReserve = TradingFeeReserveCalculator()
        val reservationCalculator = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(feeReserve))
        val admission = OrderAdmissionCalculator(reservationCalculator)
        val policy = if (paid) zeroPolicy.copy(feeRates = MakerTakerFeeRates(FeeRate(5_000), FeeRate(10_000))) else zeroPolicy
        val buy = order("buy", buyer, Side.BUY, 10_000, 3)
        val first = order("sell-1", seller, Side.SELL, 9_051, 1)
        val second = order("sell-2", seller, Side.SELL, 9_555, 1)

        fun admit(
            input: OrderAdmissionInput,
            appliedPolicy: TradingFeePolicySnapshot,
        ): OrderAdmissionCandidate =
            assertIs<OrderAdmissionResult.Calculated>(
                admission.calculate(
                    input,
                    market,
                    appliedPolicy,
                    if (input.side == Side.BUY) balance(buyer, usdt, 100_000) else balance(seller, btc, 2),
                    "reserve-${input.orderId.value}",
                    time,
                ),
            ).candidate

        val approvedBuy = admit(buy, policy)
        val approvedFirst = admit(first, policy.copy(scheduleVersion = 2))
        val approvedSecond = admit(second, policy.copy(scheduleVersion = 3))
        val executions =
            listOf(first, second).take(count).mapIndexed { index, maker ->
                SettlementExecution(
                    market.marketId,
                    10L + index,
                    maker.orderId,
                    buy.orderId,
                    maker.userId,
                    buy.userId,
                    Side.BUY,
                    Price(maker.price.value),
                    Quantity(1),
                    "settlement-$index",
                    time.plusNanos(index + 1L),
                )
            }
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), feeReserve),
            reservationCalculator,
            feeReserve,
            SettlementLedgerTransactions(),
        ).calculate(
            buy,
            market,
            executions,
            listOf(approvedBuy.reservation, approvedFirst.reservation, approvedSecond.reservation),
            listOf(approvedBuy.nextBalance, balance(seller, btc, 0, 2)),
        )
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)
}

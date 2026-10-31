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
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 계산기와 값 객체를 연결한다. 금액은 USDT 소수 6자리·BTC 소수 8자리의 최소 단위다. */
class OrderAdmissionStateTest {
    private val user = UserId("user-1")
    private val usdt = AssetId("USDT")
    private val btc = AssetId("BTC")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 8)
    private val policy =
        TradingFeePolicySnapshot(
            FeeProductType.SPOT,
            FeeTier.NORMAL,
            1,
            MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
        )
    private val time = Instant.parse("2026-10-16T00:00:00Z")
    private val calculator =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    @Test
    fun `BUY 300을 반영하면 잔고와 예약 원본 정책 원장 대기 순번이 함께 남는다`() {
        val initial = balance(1_000_000_000)
        val state = OrderAdmissionState(listOf(initial), calculator)
        val input = order()

        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, input)).record
        val snapshot = state.snapshot()

        assertEquals(1L, accepted.sequence)
        assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
        assertEquals(listOf(accepted), snapshot.pendingOrders)
        assertEquals(1L, snapshot.lastSequence)
        val candidate = accepted.candidate
        assertEquals(input, candidate.order)
        assertEquals(market, candidate.market)
        assertEquals(policy, candidate.feePolicySnapshot)
        assertEquals(initial, candidate.previousBalance)
        assertEquals(balance(700_000_000, 300_000_000), candidate.nextBalance)
        assertEquals(input.orderId, candidate.reservation.orderId)
        assertEquals(user, candidate.reservation.userId)
        assertEquals(market.marketId, candidate.reservation.marketId)
        assertEquals(OrderReservationStatus.ACTIVE, candidate.reservation.status)
        assertEquals(Amount(300_000_000), candidate.reservation.reservedAmount)
        assertEquals(Amount(300_000_000), candidate.reservation.remainingAmount)
        assertEquals(Quantity(300_000_000), candidate.reservation.remainingQuantity)
        assertEquals(policy, candidate.reservation.feePolicySnapshot)
        assertEquals("ledger-order-1", candidate.reserveTransaction.ledgerTransactionId)
        assertEquals(time, candidate.reserveTransaction.occurredAt)
        assertEquals(LedgerTransactionType.RESERVE, candidate.reserveTransaction.transactionType)
        assertEquals(
            listOf(
                LedgerPosting("USER:user-1:USDT:AVAILABLE", usdt, LedgerPostingSide.DEBIT, Amount(300_000_000)),
                LedgerPosting("USER:user-1:USDT:HOLD", usdt, LedgerPostingSide.CREDIT, Amount(300_000_000)),
            ),
            candidate.reserveTransaction.postings,
        )
        assertEquals(balance(1_000_000_000), initial)
    }

    @Test
    fun `다른 마켓도 바뀐 공통 잔고를 사용하고 거절은 순번을 소비하지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, order(quantity = 600_000_000))).record
        val before = state.snapshot()
        val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 8)
        val second = order("sui-order", quantity = 500_000_000).copy(marketId = sui.marketId)

        assertEquals(
            OrderAdmissionState.Result.Rejected(
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(usdt, Amount(400_000_000), Amount(500_000_000), Amount(100_000_000)),
            ),
            admit(state, second, sui),
        )
        assertEquals(before, state.snapshot())
        val accepted =
            assertIs<OrderAdmissionState.Result.Accepted>(admit(state, second.copy(quantity = Quantity(400_000_000)), sui)).record
        assertEquals(2L, accepted.sequence)
        assertEquals(2L, state.snapshot().lastSequence)
        assertEquals(balance(400_000_000, 600_000_000), accepted.candidate.previousBalance)
        assertEquals(balance(0, 1_000_000_000), accepted.candidate.nextBalance)
        assertEquals(second.copy(quantity = Quantity(400_000_000)), accepted.candidate.order)
        assertEquals(sui, accepted.candidate.market)
        assertEquals(listOf(balance(0, 1_000_000_000)), state.snapshot().balances)
        assertEquals(listOf(first, accepted), state.snapshot().pendingOrders)
        assertEquals(listOf(accepted), state.pendingOrders(sui.marketId))
        assertEquals(listOf(first), state.pendingOrders(market.marketId))
    }

    @Test
    fun `수수료 포함 BUY는 최대 요율 0점2퍼센트까지 함께 예약한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))

        val record = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, appliedPolicy = applied)).record

        assertEquals(listOf(balance(699_400_000, 300_600_000)), state.snapshot().balances)
        assertEquals(Amount(300_600_000), record.candidate.reservation.reservedAmount)
        assertEquals(Amount(600_000), record.candidate.reservation.initialFeeReserveAmount)
        assertEquals(applied, record.candidate.feePolicySnapshot)
        assertEquals(applied, record.candidate.reservation.feePolicySnapshot)
        assertEquals(
            listOf(Amount(300_600_000), Amount(300_600_000)),
            record.candidate.reserveTransaction.postings
                .map { it.amount },
        )
    }

    @Test
    fun `SELL은 USDT 항목 없이도 BTC만 예약하고 수수료 예약은 0이다`() {
        val initial = Balance(user, btc, Amount(300_000_000), Amount.ZERO)
        val state = OrderAdmissionState(listOf(initial), calculator)
        val sell = order(quantity = 200_000_000).copy(side = Side.SELL)
        val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))

        val record = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, sell, appliedPolicy = applied)).record

        assertEquals(listOf(Balance(user, btc, Amount(100_000_000), Amount(200_000_000))), state.snapshot().balances)
        assertEquals(Amount(200_000_000), record.candidate.reservation.reservedAmount)
        assertEquals(Amount.ZERO, record.candidate.reservation.initialFeeReserveAmount)
        assertEquals(
            listOf(btc, btc),
            record.candidate.reserveTransaction.postings
                .map { it.assetId },
        )
        assertEquals(
            listOf(
                LedgerPosting("USER:user-1:BTC:AVAILABLE", btc, LedgerPostingSide.DEBIT, Amount(200_000_000)),
                LedgerPosting("USER:user-1:BTC:HOLD", btc, LedgerPostingSide.CREDIT, Amount(200_000_000)),
            ),
            record.candidate.reserveTransaction.postings,
        )
        assertEquals(listOf(record), state.snapshot().pendingOrders)
    }

    @Test
    fun `없는 BTC 잔고는 0의 부족 거절이며 빈 잔고 항목을 만들지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val before = state.snapshot()
        val sell = order(quantity = 100_000_000).copy(side = Side.SELL)

        assertEquals(
            OrderAdmissionState.Result.Rejected(
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(btc, Amount.ZERO, Amount(100_000_000), Amount(100_000_000)),
            ),
            admit(state, sell),
        )
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        assertEquals(1L, assertIs<OrderAdmissionState.Result.Accepted>(admit(state)).record.sequence)
    }

    @Test
    fun `빈 초기 상태에서도 BUY의 quote 부족을 반환하고 상태를 만들지 않는다`() {
        val state = OrderAdmissionState(emptyList(), calculator)
        val before = state.snapshot()

        assertEquals(
            OrderAdmissionState.Result.Rejected(
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(usdt, Amount.ZERO, Amount(300_000_000), Amount(300_000_000)),
            ),
            admit(state),
        )
        assertEquals(before, state.snapshot())
        assertEquals(emptyList(), state.pendingOrders(market.marketId))
    }

    @Test
    fun `같은 마켓 주문 ID는 사용자나 내용이 달라도 중복 거절하며 원본을 노출하지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val input = order()
        admit(state, input)
        val before = state.snapshot()

        listOf(input, input.copy(quantity = Quantity(100_000_000)), input.copy(userId = UserId("other-user"))).forEach { repeated ->
            assertEquals(OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER), admit(state, repeated))
            assertEquals(before, state.snapshot())
            assertFalse(state.isBlocked)
        }
        assertEquals(2L, assertIs<OrderAdmissionState.Result.Accepted>(admit(state, order("next"))).record.sequence)
    }

    @Test
    fun `마켓이 다르면 같은 주문 ID를 각각 승인하고 두 원장 ID를 구분한다`() {
        val state = OrderAdmissionState(listOf(balance(2_000_000_000)), calculator)
        val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 8)
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, order(quantity = 600_000_000))).record
        val second =
            assertIs<OrderAdmissionState.Result.Accepted>(
                admit(state, order(quantity = 500_000_000).copy(marketId = sui.marketId), sui, ledgerId = "sui-ledger"),
            ).record

        assertEquals(listOf(balance(900_000_000, 1_100_000_000)), state.snapshot().balances)
        assertEquals(listOf(1L, 2L), state.snapshot().pendingOrders.map { it.sequence })
        assertNotEquals(first.candidate.reserveTransaction.ledgerTransactionId, second.candidate.reserveTransaction.ledgerTransactionId)
        assertNotEquals(first.candidate.reserveTransaction.sourceEventId, second.candidate.reserveTransaction.sourceEventId)
        assertEquals(75, second.candidate.reserveTransaction.sourceEventId.length)
    }

    @Test
    fun `사용자별 같은 자산 잔고는 서로 섞지 않는다`() {
        val other = UserId("other-user")
        val state = OrderAdmissionState(listOf(balance(1_000_000_000), balance(500_000_000).copy(userId = other)), calculator)

        admit(state, order())
        val record =
            assertIs<OrderAdmissionState.Result.Accepted>(admit(state, order("other-order").copy(userId = other))).record

        assertEquals(
            listOf(balance(700_000_000, 300_000_000), balance(200_000_000, 300_000_000).copy(userId = other)),
            state.snapshot().balances,
        )
        assertEquals(other, record.candidate.reservation.userId)
        assertEquals(
            listOf(
                "USER:other-user:USDT:AVAILABLE",
                "USER:other-user:USDT:HOLD",
            ),
            record.candidate.reserveTransaction.postings.map {
                it.accountId
            },
        )
    }

    @Test
    fun `원장 거래 ID 충돌은 반영 전 내부 오류이고 뒤의 정상 주문은 계속된다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        admit(state)
        val before = state.snapshot()

        assertFailsWith<IllegalStateException> { admit(state, order("second"), ledgerId = "ledger-order-1") }
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        val record = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, order("second"))).record
        assertEquals(2L, record.sequence)
        assertEquals(listOf(balance(400_000_000, 600_000_000)), state.snapshot().balances)
    }

    @Test
    fun `자금 부족은 뒤의 원장 ID 충돌 검사로 가려지지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(300_000_000)), calculator)
        admit(state)
        val before = state.snapshot()

        assertEquals(
            OrderAdmissionState.Result.Rejected(
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(usdt, Amount.ZERO, Amount(300_000_000), Amount(300_000_000)),
            ),
            admit(state, order("second"), ledgerId = "ledger-order-1"),
        )
        assertEquals(before, state.snapshot())
    }

    @Test
    fun `잔고 유무와 무관하게 계산기의 입력 거절 순서를 유지한다`() {
        val cases =
            listOf(
                order().copy(orderId = OrderId("bad\u0000id")) to OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER,
                order().copy(orderType = OrderType.MARKET) to OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                order().copy(timeInForce = TimeInForce.IOC) to OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                order().copy(quantity = Quantity(0)) to OrderAdmissionResult.Reason.INVALID_QUANTITY,
                order().copy(price = Price(1), quantity = Quantity(1)) to OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION,
            )
        listOf(emptyList(), listOf(balance(1_000_000_000))).forEach { initial ->
            val state = OrderAdmissionState(initial, calculator)
            val before = state.snapshot()
            cases.forEach { (input, reason) ->
                assertEquals(OrderAdmissionState.Result.Rejected(reason), admit(state, input))
                assertEquals(before, state.snapshot())
                assertFalse(state.isBlocked)
            }
            if (initial.isNotEmpty()) assertEquals(1L, assertIs<OrderAdmissionState.Result.Accepted>(admit(state)).record.sequence)
        }
    }

    @Test
    fun `잘못된 서버 문맥은 잔고가 없어도 정상 거절로 숨기지 않는다`() {
        listOf(emptyList(), listOf(balance(1_000_000_000))).forEach { initial ->
            val state = OrderAdmissionState(initial, calculator)
            val before = state.snapshot()
            assertFailsWith<IllegalArgumentException> { admit(state, definition = market.copy(marketId = MarketId("OTHER"))) }
            assertEquals(before, state.snapshot())
            assertFailsWith<IllegalArgumentException> {
                admit(
                    state,
                    appliedPolicy = policy.copy(productType = FeeProductType.PERPETUAL_FUTURES),
                )
            }
            assertEquals(before, state.snapshot())
            assertFailsWith<IllegalArgumentException> { admit(state, ledgerId = "") }
            assertEquals(before, state.snapshot())
            assertFalse(state.isBlocked)
        }
    }

    @Test
    fun `초기 사용자 자산 키 중복이나 근거 없는 hold는 생성 단계에서 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            OrderAdmissionState(listOf(balance(1_000_000_000), balance(500_000_000)), calculator)
        }
        assertFailsWith<IllegalArgumentException> { OrderAdmissionState(listOf(balance(1_000_000_000, 1)), calculator) }
        val zero = OrderAdmissionState(listOf(balance(0)), calculator)
        assertEquals(listOf(balance(0)), zero.snapshot().balances)
        assertFalse(zero.isBlocked)
    }

    @Test
    fun `초기 목록 변경과 반환 목록 변경으로 소유 상태를 수정할 수 없다`() {
        val initial = mutableListOf(balance(1_000_000_000))
        val state = OrderAdmissionState(initial, calculator)
        initial.clear()
        val empty = state.snapshot()
        admit(state)
        val first = state.snapshot()
        val firstMarketOrders = state.pendingOrders(market.marketId)

        assertFailsWith<UnsupportedOperationException> { (first.balances as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.pendingOrders as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (firstMarketOrders as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (
                first.pendingOrders
                    .single()
                    .candidate.reserveTransaction.postings as MutableList
            ).clear()
        }
        admit(state, order("second"))

        assertEquals(listOf(balance(1_000_000_000)), empty.balances)
        assertEquals(emptyList(), empty.pendingOrders)
        assertEquals(0L, empty.lastSequence)
        assertEquals(listOf(balance(700_000_000, 300_000_000)), first.balances)
        assertEquals(listOf(1L), first.pendingOrders.map { it.sequence })
        assertEquals(1L, first.lastSequence)
        assertEquals(first.pendingOrders, firstMarketOrders)
        assertEquals(listOf(balance(400_000_000, 600_000_000)), state.snapshot().balances)
    }

    @Test
    fun `승인 순번 Long 최대값 다음에는 변경 전에 중단하며 순번이 되돌아가지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        field("lastSequence").setLong(state, Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, assertIs<OrderAdmissionState.Result.Accepted>(admit(state)).record.sequence)
        val before = state.snapshot()

        assertFailsWith<ArithmeticException> { admit(state, order("second")) }
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
    }

    @Test
    fun `사건 ID 사용 이력 충돌은 첫 변경 전에 중단한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val input = order()
        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(
                calculator.calculate(input, market, policy, balance(1_000_000_000), "id", time),
            ).candidate

        @Suppress("UNCHECKED_CAST")
        val sourceIds = field("sourceEventIds").get(state) as MutableSet<String>
        sourceIds.add(candidate.reserveTransaction.sourceEventId)
        val before = state.snapshot()

        assertFailsWith<IllegalStateException> { admit(state) }
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
    }

    @Test
    fun `마켓별 대기 조회도 전체 접수 순서에서 상대 순서를 보존한다`() {
        val state = OrderAdmissionState(listOf(balance(2_000_000_000)), calculator)
        val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 8)
        admit(state)
        admit(state, order("sui").copy(marketId = sui.marketId), sui)
        admit(state, order("third"))

        assertEquals(listOf(1L, 2L, 3L), state.snapshot().pendingOrders.map { it.sequence })
        assertEquals(listOf(1L, 3L), state.pendingOrders(market.marketId).map { it.sequence })
        assertEquals(listOf(2L), state.pendingOrders(sui.marketId).map { it.sequence })
        assertEquals(emptyList(), state.pendingOrders(MarketId("UNKNOWN")))
    }

    @Test
    fun `업무 키의 콜론을 문자열 합치기처럼 혼동하지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val firstMarket = market.copy(marketId = MarketId("a:b"))
        val secondMarket = market.copy(marketId = MarketId("a"))
        val first =
            assertIs<OrderAdmissionState.Result.Accepted>(
                admit(state, order("c").copy(marketId = firstMarket.marketId), firstMarket, ledgerId = "first"),
            ).record
        val second =
            assertIs<OrderAdmissionState.Result.Accepted>(
                admit(state, order("b:c").copy(marketId = secondMarket.marketId), secondMarket, ledgerId = "second"),
            ).record

        assertEquals(listOf(first, second), state.snapshot().pendingOrders)
        assertEquals(listOf(balance(400_000_000, 600_000_000)), state.snapshot().balances)
        assertNotEquals(first.candidate.reserveTransaction.sourceEventId, second.candidate.reserveTransaction.sourceEventId)
    }

    @Test
    fun `반영 중 예외나 Error가 나면 후속 접수와 정상 조회를 차단한다`() {
        listOf(IllegalStateException("반영 중 실패"), AssertionError("반영 중 Error")).forEach { failure ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            admit(state)
            val before = state.snapshot()
            failAfterBalanceWrite(state, failure)

            assertSame(failure, assertFailsWith<Throwable> { admit(state, order("second")) })
            assertTrue(state.isBlocked)
            assertFailsWith<IllegalStateException> { admit(state, order("third")) }
            assertFailsWith<IllegalStateException> { admit(state) }
            assertFailsWith<IllegalStateException> { state.snapshot() }
            assertFailsWith<IllegalStateException> { state.pendingOrders(market.marketId) }
            assertEquals(listOf(balance(700_000_000, 300_000_000)), before.balances)
            assertEquals(listOf(1L), before.pendingOrders.map { it.sequence })
            assertEquals(1L, before.lastSequence)
        }
    }

    @Test
    fun `오래된 정산 기준 잔고는 다른 소유 상태를 반영하기 전에 거절한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val candidate =
            OrderSettlementCandidate(
                order(),
                market,
                emptyList(),
                listOf(OrderSettlementCandidate.BalanceChange(balance(900_000_000), balance(800_000_000))),
                emptyList(),
            )
        val before = state.snapshot()
        assertFailsWith<IllegalStateException> { state.prepareSettlement(candidate) }

        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
    }

    @Test
    fun `자금 검증 뒤 다른 소유 상태를 반영하는 동안 접수 재진입과 조회를 막는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val before = state.snapshot()
        val candidate = OrderSettlementCandidate(order(), market, emptyList(), emptyList(), emptyList())
        val preparation = state.prepareSettlement(candidate)

        assertTrue(state.isBlocked)
        assertFailsWith<IllegalStateException> { admit(state) }
        state.applySettlement(preparation) {
            assertTrue(state.isBlocked)
            assertFailsWith<IllegalStateException> { admit(state) }
            assertFailsWith<IllegalStateException> { state.snapshot() }
            assertFailsWith<IllegalStateException> { state.applySettlement(preparation) {} }
        }

        assertFalse(state.isBlocked)
        assertEquals(before, state.snapshot())
        assertFailsWith<IllegalStateException> { state.applySettlement(preparation) {} }
        assertIs<OrderAdmissionState.Result.Accepted>(admit(state))
    }

    @Test
    fun `자금 검증 뒤 다른 소유 상태의 반영이 실패하면 접수 상태도 차단한다`() {
        listOf(IllegalStateException("다른 소유 상태 실패"), AssertionError("다른 소유 상태 Error")).forEach { failure ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            val candidate = OrderSettlementCandidate(order(), market, emptyList(), emptyList(), emptyList())
            val preparation = state.prepareSettlement(candidate)

            assertSame(failure, assertFailsWith<Throwable> { state.applySettlement(preparation) { throw failure } })

            assertTrue(state.isBlocked)
            assertFailsWith<IllegalStateException> { state.applySettlement(preparation) {} }
            assertFailsWith<IllegalStateException> { admit(state) }
            assertFailsWith<IllegalStateException> { state.snapshot() }
        }
    }

    @Test
    fun `다른 소유자의 정산 준비나 차단 후 폐기된 준비는 반영할 수 없다`() {
        val first = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val second = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val candidate = OrderSettlementCandidate(order(), market, emptyList(), emptyList(), emptyList())
        val firstPreparation = first.prepareSettlement(candidate)
        val secondPreparation = second.prepareSettlement(candidate)
        var otherStateApplied = false

        assertFailsWith<IllegalStateException> { second.applySettlement(firstPreparation) { otherStateApplied = true } }
        assertFalse(otherStateApplied)
        second.applySettlement(secondPreparation) {}
        assertFalse(second.isBlocked)
        first.block()
        assertFailsWith<IllegalStateException> { first.applySettlement(firstPreparation) { otherStateApplied = true } }
        assertFalse(otherStateApplied)
        assertTrue(first.isBlocked)
    }

    // 공개 장애 주입 API 없이 실제 잔고 쓰기 직후의 실패만 통제한다. SQL 롤백을 기대하지 않는다.
    private fun failAfterBalanceWrite(
        state: OrderAdmissionState,
        failure: Throwable,
    ) {
        @Suppress("UNCHECKED_CAST")
        val original = field("balances").get(state) as MutableMap<Any, Balance>
        val failing =
            object : MutableMap<Any, Balance> by original {
                override fun put(
                    key: Any,
                    value: Balance,
                ): Balance? {
                    original[key] = value
                    throw failure
                }
            }
        field("balances").set(state, failing)
    }

    private fun field(name: String) = OrderAdmissionState::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun order(
        id: String = "order-1",
        quantity: Long = 300_000_000,
    ) = OrderAdmissionInput(
        market.marketId,
        OrderId(id),
        user,
        Side.BUY,
        OrderType.LIMIT,
        TimeInForce.GTC,
        Price(100_000_000),
        Quantity(quantity),
    )

    private fun balance(
        available: Long,
        hold: Long = 0,
    ) = Balance(user, usdt, Amount(available), Amount(hold))

    private fun admit(
        state: OrderAdmissionState,
        input: OrderAdmissionInput = order(),
        definition: MarketDefinition = market,
        appliedPolicy: TradingFeePolicySnapshot = policy,
        ledgerId: String = "ledger-${input.orderId.value}",
    ) = state.admit(input, definition, appliedPolicy, ledgerId, time)
}

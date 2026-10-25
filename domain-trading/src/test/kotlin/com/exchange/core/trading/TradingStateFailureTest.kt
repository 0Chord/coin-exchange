package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFailureTestSupport.actualValues
import com.exchange.core.trading.TradingStateFailureTestSupport.failAfterNestedSetAdd
import com.exchange.core.trading.TradingStateFailureTestSupport.failAfterPut
import com.exchange.core.trading.TradingStateFailureTestSupport.map
import com.exchange.core.trading.TradingStateFailureTestSupport.read
import com.exchange.core.trading.TradingStateFailureTestSupport.write
import com.exchange.core.trading.TradingStateFixtures.admit
import com.exchange.core.trading.TradingStateFixtures.balance
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.fund
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.state
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import com.exchange.core.trading.TradingStateFixtures.zeroPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 계산 실패의 실제 값 보존과 적용 실패의 실제 쓰기·차단을 서로 다른 관측으로 검증한다. */
class TradingStateFailureTest {
    private val firstSeller = UserId("S1")
    private val secondSeller = UserId("S2")
    private val otherMarket = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 0)
    private val settlementIdAtThree = "SETTLEMENT:v1:g-vDKCVwQ6mt0t1hw3lYL7amsJsrH-kDq5PqpXy85hQ"

    @Test
    fun `미등록 비양수 순번과 같은 마켓의 앞 대기를 건너뛰는 입력은 상태와 차단을 바꾸지 않는다`() {
        val state = state()
        fund(state, buyer, usdt, 1_000)
        val first = admit(state, order("first", buyer, Side.BUY, 100, 1))
        val second = admit(state, order("second", buyer, Side.BUY, 100, 1))
        val before = actualValues(state)

        listOf(0L, -1L, Long.MIN_VALUE, 999L, second.record.sequence).forEach { sequence ->
            assertFailsWith<IllegalArgumentException> { state.executePending(sequence, time) }
            assertEquals(before, actualValues(state))
            assertFalse(state.isBlocked)
        }

        assertEquals(first.record, state.executePending(first.record.sequence, time).admission)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `둘째 판매자 지급의 overflow는 첫 체결의 임시 성공 뒤에도 실제 자금 주문장 이력을 보존한다`() {
        val f = twoMakers(secondSellerQuote = Long.MAX_VALUE)
        val before = actualValues(f.state)
        val snapshot = f.state.snapshot()
        assertEquals(balance(buyer, usdt, 700, 300), snapshot.balances.single { it.userId == buyer && it.assetId == usdt })
        assertTrue(snapshot.balances.none { it.userId == buyer && it.assetId == btc })
        assertEquals(listOf(OrderId("S1-order"), OrderId("S2-order")), f.state.book(market.marketId).map { it.orderId })
        assertEquals(2L, f.state.lastEngineSequence(market.marketId))

        assertFailsWith<ArithmeticException> { f.state.executePending(f.taker.record.sequence, time.plusSeconds(1)) }

        assertEquals(before, actualValues(f.state))
        assertTrue(f.state.isBlocked)
        assertAllEntrancesBlocked(f)
    }

    @Test
    fun `maker 주문장 잔량1과 현재 예약 잔량2가 다르면 첫 실제 쓰기 전에 차단한다`() {
        val state = state()
        fund(state, firstSeller, btc, 2)
        fund(state, buyer, usdt, 1_000)
        val maker = admit(state, order("maker", firstSeller, Side.SELL, 90, 2))
        state.executePending(maker.record.sequence, time)
        val firstFill = admit(state, order("first-fill", buyer, Side.BUY, 100, 1))
        state.executePending(firstFill.record.sequence, time)
        val pending = admit(state, order("next-fill", buyer, Side.BUY, 100, 1))
        assertEquals(Quantity(1), state.book(market.marketId).single().remainingQuantity)
        changeReservation(state, "maker") { it.copy(remainingQuantity = Quantity(2)) }

        assertPreWriteFailure(state, pending.record.sequence)
    }

    @Test
    fun `maker 현재 예약의 최초 수량 소유자 지정가 마켓 자산 정책 손상을 쓰기 전에 거절한다`() {
        val corruptions: List<Pair<String, (OrderReservation) -> OrderReservation>> =
            listOf(
                "최초 수량" to { it.copy(initialQuantity = Quantity(2)) },
                "소유자" to { it.copy(userId = UserId("wrong-owner")) },
                "지정가" to { it.copy(limitPrice = Price(89)) },
                "마켓" to { it.copy(marketId = otherMarket.marketId) },
                "자산" to { it.copy(assetId = usdt) },
                "정책" to { it.copy(feePolicySnapshot = zeroPolicy.copy(scheduleVersion = 2)) },
            )

        corruptions.forEach { (label, corruption) ->
            val f = twoMakers()
            changeReservation(f.state, "S1-order", corruption)
            assertPreWriteFailure(f.state, f.taker.record.sequence, label)
        }
    }

    @Test
    fun `maker 최초 승인 원본의 수량 소유자 지정가와 보존 문맥을 현재 주문장에 대조한다`() {
        val changes: List<Pair<String, (OrderAdmissionCandidate) -> Unit>> =
            listOf(
                "최초 수량" to { write(it, "order", it.order.copy(quantity = Quantity(2))) },
                "최초 소유자" to { write(it, "order", it.order.copy(userId = UserId("wrong-original-owner"))) },
                "최초 지정가" to { write(it, "order", it.order.copy(price = Price(89))) },
                "마켓 scale" to { write(it, "market", market.copy(baseAssetScale = 1, quantityStep = 10)) },
                "마켓 자산" to { write(it, "market", market.copy(baseAssetId = AssetId("OTHER"))) },
                "정책 snapshot" to { write(it, "feePolicySnapshot", zeroPolicy.copy(scheduleVersion = 2)) },
            )

        changes.forEach { (label, change) ->
            val f = twoMakers()
            change(f.firstMaker.record.candidate)
            assertPreWriteFailure(f.state, f.taker.record.sequence, label)
        }
    }

    @Test
    fun `taker 최초 처리 전 현재 예약은 최초 승인 예약과 같아야 한다`() {
        val f = twoMakers()
        changeReservation(f.state, f.taker.record.candidate.order.orderId.value) {
            it.copy(remainingQuantity = Quantity(2), remainingAmount = Amount(200))
        }

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `체결0건이어도 taker 예약의 연결과 hold를 검사한다`() {
        val corruptions: List<Pair<String, (TradingState) -> Unit>> =
            listOf(
                "예약 소유자" to { state -> changeReservation(state, "unmatched") { it.copy(userId = UserId("wrong-owner")) } },
                "예약 지정가" to { state -> changeReservation(state, "unmatched") { it.copy(limitPrice = Price(99)) } },
                "예약 누락" to
                    { state -> reservationMap(state).entries.removeIf { (it.value as OrderReservation).orderId == OrderId("unmatched") } },
                "hold 부족" to { state ->
                    val balances = map(read(state, "admission"), "balances")
                    val entry = balances.entries.single { (it.value as Balance).userId == buyer }
                    balances[entry.key] = (entry.value as Balance).copy(hold = Amount(99))
                },
            )

        corruptions.forEach { (label, corrupt) ->
            val state = state()
            fund(state, buyer, usdt, 1_000)
            val accepted = admit(state, order("unmatched", buyer, Side.BUY, 100, 1))
            assertEquals(emptyList(), state.book(market.marketId))
            corrupt(state)
            assertPreWriteFailure(state, accepted.record.sequence, label)
        }
    }

    @Test
    fun `결과 이력의 이전 마켓 순번이 엔진과 다르면 실제 매칭 전에 차단한다`() {
        val f = twoMakers()
        map(read(f.state, "resultState"), "lastSequences")[market.marketId] = 1L

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `매칭 결과만 남고 전체 완료가 없으면 과거 성공으로 재사용하지 않는다`() {
        val f = twoMakers()
        f.state.executePending(f.taker.record.sequence, time)
        map(f.state, "completions").remove(f.taker.record.sequence)
        assertEquals(3, map(read(f.state, "resultState"), "records").size)

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `신규 실행의 엔진 순번 overflow는 실제 값을 보존하고 차단한다`() {
        val f = twoMakers()
        map(read(f.state, "engine"), "lastEngineSequences")[market.marketId] = Long.MAX_VALUE
        map(read(f.state, "resultState"), "lastSequences")[market.marketId] = Long.MAX_VALUE
        val before = actualValues(f.state)

        assertFailsWith<ArithmeticException> { f.state.executePending(f.taker.record.sequence, time) }

        assertEquals(before, actualValues(f.state))
        assertTrue(f.state.isBlocked)
    }

    @Test
    fun `과거 정상 OPENING의 거래 ID와 새 SETTLEMENT ID 충돌은 첫 쓰기 전에 차단한다`() {
        val f = twoMakers(buyerOpeningId = settlementIdAtThree)

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `과거 정상 RESERVE의 거래 ID와 새 SETTLEMENT ID 충돌은 첫 쓰기 전에 차단한다`() {
        val f = twoMakers(firstReserveId = settlementIdAtThree)

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `새 SETTLEMENT의 실제 체결 사건 ID가 사용 이력과 충돌하면 쓰기 전에 차단한다`() {
        val f = twoMakers()
        TradingStateFailureTestSupport.set(read(f.state, "admission"), "sourceEventIds").add("MATCHING:BTC-USDT:3")

        assertPreWriteFailure(f.state, f.taker.record.sequence)
    }

    @Test
    fun `확정 SETTLEMENT 거래 ID를 신규 OPENING이나 RESERVE에 재사용하면 보존하고 정상 사용을 유지한다`() {
        val f = twoMakers()
        f.state.executePending(f.taker.record.sequence, time)
        val newUser = UserId("new-user")
        val beforeOpening = actualValues(f.state)

        assertFailsWith<IllegalStateException> {
            fund(f.state, newUser, usdt, 100, ledgerId = settlementIdAtThree)
        }

        assertEquals(beforeOpening, actualValues(f.state))
        assertFalse(f.state.isBlocked)
        fund(f.state, newUser, usdt, 100)
        val beforeReserve = actualValues(f.state)
        assertFailsWith<IllegalStateException> {
            f.state.admit(
                "new-reserve-request",
                order("new-reserve", newUser, Side.BUY, 100, 1),
                market,
                zeroPolicy,
                settlementIdAtThree,
                time,
            )
        }
        assertEquals(beforeReserve, actualValues(f.state))
        assertFalse(f.state.isBlocked)
        assertFalse(f.state.hasRequest(newUser, "new-reserve-request"))
    }

    @Test
    fun `첫 seen-ID 실제 add 직후 Exception이면 쓰기는 남고 모든 입구가 차단된다`() =
        assertAfterWriteFailure(WriteTarget.FIRST_SEEN_ID, IllegalStateException("first actual add"))

    @Test
    fun `첫 seen-ID 실제 add 직후 Error이면 쓰기는 남고 모든 입구가 차단된다`() =
        assertAfterWriteFailure(WriteTarget.FIRST_SEEN_ID, AssertionError("first actual add"))

    @Test
    fun `중간 잔고 실제 put 직후 Exception이면 쓰기는 남고 모든 입구가 차단된다`() =
        assertAfterWriteFailure(WriteTarget.MIDDLE_BALANCE, IllegalStateException("middle actual put"))

    @Test
    fun `중간 잔고 실제 put 직후 Error이면 쓰기는 남고 모든 입구가 차단된다`() =
        assertAfterWriteFailure(WriteTarget.MIDDLE_BALANCE, AssertionError("middle actual put"))

    @Test
    fun `마지막 전체 완료 실제 put 직후 Exception이어도 완료 재전송을 먼저 꺼내지 않는다`() =
        assertAfterWriteFailure(WriteTarget.LAST_COMPLETION, IllegalStateException("last actual put"))

    @Test
    fun `마지막 전체 완료 실제 put 직후 Error이어도 완료 재전송을 먼저 꺼내지 않는다`() =
        assertAfterWriteFailure(WriteTarget.LAST_COMPLETION, AssertionError("last actual put"))

    private fun assertAfterWriteFailure(
        target: WriteTarget,
        failure: Throwable,
    ) {
        val f = twoMakers()
        val state = f.state
        val rejectedInput = order("rejected", buyer, Side.BUY, 100, 1).copy(orderType = OrderType.MARKET)
        assertIs<OrderAdmissionState.Result.Rejected>(state.admit("rejected-request", rejectedInput, market, zeroPolicy, "unused", time))
        val admission = read(state, "admission")
        val engine = read(state, "engine")
        val balancesBefore = map(admission, "balances").toMap()
        val before = actualValues(state)
        val probe =
            when (target) {
                WriteTarget.FIRST_SEEN_ID -> failAfterNestedSetAdd(engine, "seenOrderIds", market.marketId, failure)
                WriteTarget.MIDDLE_BALANCE -> failAfterPut(admission, "balances", failure)
                WriteTarget.LAST_COMPLETION -> failAfterPut(state, "completions", failure)
            }

        assertSame(failure, assertFailsWith<Throwable> { state.executePending(f.taker.record.sequence, time.plusSeconds(1)) })

        assertEquals(1, probe.count, "위임 대상의 실제 쓰기를 마친 뒤에만 실패 표지가 남아야 합니다")
        when (target) {
            WriteTarget.FIRST_SEEN_ID -> {
                assertEquals(f.taker.record.candidate.order.orderId, probe.writtenKey)
                assertTrue((map(engine, "seenOrderIds").getValue(market.marketId) as Set<*>).contains(probe.writtenKey))
                assertEquals(2L, map(engine, "lastEngineSequences")[market.marketId])
            }

            WriteTarget.MIDDLE_BALANCE -> {
                val key = assertNotNull(probe.writtenKey)
                assertTrue(map(admission, "balances").containsKey(key))
                assertNotEquals(balancesBefore[key], map(admission, "balances")[key])
                assertEquals(5L, map(engine, "lastEngineSequences")[market.marketId])
            }

            WriteTarget.LAST_COMPLETION -> {
                assertEquals(f.taker.record.sequence, probe.writtenKey)
                assertNotNull(map(state, "completions")[f.taker.record.sequence])
                val balances = map(admission, "balances").values.filterIsInstance<Balance>()
                assertTrue(balance(buyer, usdt, 715, 100) in balances)
                assertTrue(balance(buyer, btc, 2) in balances)
            }
        }
        assertNotEquals(before, actualValues(state), "실제 적용 실패를 계산 실패처럼 원상복구했다고 검증하면 안 됩니다")
        assertTrue(state.isBlocked)
        assertAllEntrancesBlocked(f)
    }

    private fun assertAllEntrancesBlocked(f: Fixture) {
        val state = f.state
        val before = actualValues(state)
        val original = f.firstMaker.record.candidate.order
        val invalid = original.copy(orderId = OrderId("bad\u0000id"))
        val newOtherMarketOrder = order("other-market", buyer, Side.BUY, 1, 1, otherMarket)
        val blocked: List<Pair<String, () -> Any?>> =
            listOf(
                "최초 성공 요청 재전송" to { state.admit("request-${original.orderId.value}", original, market, zeroPolicy, "unused", time) },
                "실패 요청 재전송" to { state.admit("request-B-order", f.taker.record.candidate.order, market, zeroPolicy, "unused", time) },
                "최초 거절 재전송" to
                    {
                        state.admit(
                            "rejected-request",
                            order("rejected", buyer, Side.BUY, 100, 1).copy(orderType = OrderType.MARKET),
                            market,
                            zeroPolicy,
                            "unused",
                            time,
                        )
                    },
                "요청 내용 충돌" to
                    {
                        state.admit(
                            "request-${original.orderId.value}",
                            original.copy(quantity = Quantity(2)),
                            market,
                            zeroPolicy,
                            "unused",
                            time,
                        )
                    },
                "잘못된 요청 ID" to { state.admit("", original, market, zeroPolicy, "unused", time) },
                "잘못된 주문 ID" to { state.admit("invalid", invalid, market, zeroPolicy, "unused", time) },
                "다른 마켓 접수" to { state.admit("other-market-request", newOtherMarketOrder, otherMarket, zeroPolicy, "other-reserve", time) },
                "요청 ID 없는 접수" to { state.admit(newOtherMarketOrder, otherMarket, zeroPolicy, "legacy-reserve", time) },
                "최초 준비 재전송" to { fund(state, buyer, usdt, 1_000) },
                "새 준비" to { fund(state, UserId("new-funding"), usdt, 10) },
                "잘못된 준비 원장 ID" to
                    { state.prepareDevelopmentBalance(OpeningBalance("invalid-ledger", UserId("fresh"), usdt, Amount(1)), "", time) },
                "완료 실행 재전송" to { state.executePending(f.firstMaker.record.sequence, time.plusSeconds(100)) },
                "실패 실행 재전송" to { state.executePending(f.taker.record.sequence, time.plusSeconds(100)) },
                "비양수 실행" to { state.executePending(0, time) },
                "없는 실행" to { state.executePending(Long.MAX_VALUE, time) },
                "현재 요청 유무" to { state.hasRequest(buyer, "request-B-order") },
                "거절 요청 유무" to { state.hasRequest(buyer, "rejected-request") },
                "없는 요청 유무" to { state.hasRequest(UserId("missing"), "missing") },
                "잘못된 요청 유무" to { state.hasRequest(buyer, "") },
                "전체 현재 조회" to { state.snapshot() },
                "현재 마켓 대기" to { state.pendingOrders(market.marketId) },
                "다른 마켓 대기" to { state.pendingOrders(otherMarket.marketId) },
                "현재 주문장" to { state.book(market.marketId) },
                "다른 주문장" to { state.book(otherMarket.marketId) },
                "현재 순번" to { state.lastEngineSequence(market.marketId) },
                "다른 순번" to { state.lastEngineSequence(otherMarket.marketId) },
            )

        blocked.forEach { (name, call) ->
            assertFailsWith<IllegalStateException>(name) { call() }
            assertTrue(state.isBlocked, name)
        }
        assertEquals(before, actualValues(state), "차단 뒤 호출은 부분 반영된 실제 값도 더 바꾸면 안 됩니다")
    }

    private fun assertPreWriteFailure(
        state: TradingState,
        sequence: Long,
        label: String = "내부 관계",
    ) {
        val before = actualValues(state)

        val failure = assertFailsWith<RuntimeException>(label) { state.executePending(sequence, time.plusSeconds(1)) }

        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException, "$label: 다른 실패로 검사됐습니다: $failure")
        assertEquals(before, actualValues(state), "$label: 첫 업무 쓰기 전의 실제 상태를 유지해야 합니다")
        assertTrue(state.isBlocked, label)
    }

    private fun reservationMap(state: TradingState) = map(read(state, "admission"), "currentReservations")

    private fun changeReservation(
        state: TradingState,
        orderId: String,
        change: (OrderReservation) -> OrderReservation,
    ) {
        val reservations = reservationMap(state)
        val entry = reservations.entries.single { (it.value as OrderReservation).orderId == OrderId(orderId) }
        reservations[entry.key] = change(entry.value as OrderReservation)
    }

    private fun twoMakers(
        secondSellerQuote: Long? = null,
        buyerOpeningId: String = "opening-fund-B-USDT",
        firstReserveId: String = "reserve-S1-order",
    ): Fixture {
        val state = state()
        fund(state, buyer, usdt, 1_000, ledgerId = buyerOpeningId)
        fund(state, firstSeller, btc, 1)
        fund(state, secondSeller, btc, 1)
        if (secondSellerQuote != null) fund(state, secondSeller, usdt, secondSellerQuote)
        val firstInput = order("S1-order", firstSeller, Side.SELL, 90, 1)
        val first =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admit("request-S1-order", firstInput, market, zeroPolicy, firstReserveId, time),
            )
        state.executePending(first.record.sequence, time)
        val second = admit(state, order("S2-order", secondSeller, Side.SELL, 95, 1))
        state.executePending(second.record.sequence, time)
        val taker = admit(state, order("B-order", buyer, Side.BUY, 100, 3))
        return Fixture(state, first, taker)
    }

    private data class Fixture(
        val state: TradingState,
        val firstMaker: OrderAdmissionState.Result.Accepted,
        val taker: OrderAdmissionState.Result.Accepted,
    )

    private enum class WriteTarget {
        FIRST_SEEN_ID,
        MIDDLE_BALANCE,
        LAST_COMPLETION,
    }
}

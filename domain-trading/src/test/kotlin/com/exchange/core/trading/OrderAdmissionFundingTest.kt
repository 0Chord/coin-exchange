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
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 메모리 상태·접수 계산기·개시 규칙을 연결한다. DB나 통신 대역은 없다. */
class OrderAdmissionFundingTest {
    private val user = UserId("user-1")
    private val usdt = AssetId("USDT")
    private val btc = AssetId("BTC")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 8)
    private val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val time = Instant.parse("2026-10-17T00:00:00.123456789Z")
    private val calculator =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    @Test
    fun `빈 상태에 1000 USDT를 준비하면 잔고와 OPENING만 남는다`() {
        val state = state()
        val opening = opening()

        val result = fund(state, opening)
        val snapshot = state.snapshot()

        assertEquals(OpeningBalanceResult(opening, "opening-ledger", time, false), result)
        assertEquals(listOf(balance(1_000_000_000)), snapshot.balances)
        assertEquals(0L, snapshot.lastSequence)
        assertEquals(emptyList(), snapshot.pendingOrders)
        assertEquals(emptyList(), snapshot.requests)
        assertEquals(1, snapshot.preparations.size)
        assertEquals(result, snapshot.preparations.single().result)
        assertOpening(snapshot.preparations.single(), opening)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `같은 준비 요청은 최초 원장과 나노초 시각을 재사용하고 다시 지급하지 않는다`() {
        val state = state()
        val original = fund(state)
        val before = state.snapshot()

        val replay = state.prepareDevelopmentBalance(opening(), "unused-ledger", time.plusSeconds(1))

        assertEquals(original.copy(alreadyPrepared = true), replay)
        assertEquals(before, state.snapshot())
        assertFalse(
            state
                .snapshot()
                .preparations
                .single()
                .result.alreadyPrepared,
        )
    }

    @Test
    fun `300 USDT 주문 예약 후 준비를 반복해도 사용 가능 700 예약 300을 유지한다`() {
        val state = state()
        val original = fund(state)
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state))
        val before = state.snapshot()

        assertEquals(original.copy(alreadyPrepared = true), fund(state))

        assertEquals(before, state.snapshot())
        assertEquals(listOf(balance(700_000_000, 300_000_000)), before.balances)
        assertEquals(1L, before.lastSequence)
        assertEquals(listOf(accepted.record), before.pendingOrders)
        assertEquals(1, before.requests.size)
        assertOpening(before.preparations.single(), opening())
        assertEquals(LedgerTransactionType.RESERVE, accepted.record.candidate.reserveTransaction.transactionType)
    }

    @Test
    fun `같은 준비 ID의 금액 사용자 자산 변경은 원본 충돌이며 아무 기록도 덮어쓰지 않는다`() {
        val state = state()
        fund(state)
        admit(state)
        val before = state.snapshot()
        val changes = listOf(opening(amount = 2_000_000_000), opening().copy(userId = UserId("other")), opening().copy(assetId = btc))

        changes.forEach { changed ->
            assertFailsWith<OpeningBalanceRequestConflictException> { fund(state, changed) }
            assertEquals(before, state.snapshot())
            assertFalse(state.isBlocked)
        }
        assertEquals(2L, assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R2", order("second"), "next-ledger")).record.sequence)
    }

    @Test
    fun `같은 계정의 새 준비 ID는 추가 지급 거절이고 다른 사용자 자산은 각각 준비한다`() {
        val state = state()
        fund(state)
        val before = state.snapshot()
        assertFailsWith<OpeningBalanceConflictException> { fund(state, opening("P2")) }
        assertEquals(before, state.snapshot())

        val otherUser = opening("P2", 500_000_000).copy(userId = UserId("other"))
        state.prepareDevelopmentBalance(otherUser, "other-ledger", time)
        val otherAsset = opening("P3", 300_000_000).copy(assetId = btc)
        state.prepareDevelopmentBalance(otherAsset, "btc-ledger", time)

        val snapshot = state.snapshot()
        assertEquals(
            listOf(
                balance(1_000_000_000),
                balance(500_000_000).copy(userId = UserId("other")),
                Balance(user, btc, Amount(300_000_000), Amount.ZERO),
            ),
            snapshot.balances,
        )
        assertEquals(listOf(opening(), otherUser, otherAsset), snapshot.preparations.map { it.result.opening })
        assertEquals(
            listOf("opening-ledger", "other-ledger", "btc-ledger"),
            snapshot.preparations.map { it.transaction.ledgerTransactionId },
        )
        assertEquals(0L, snapshot.lastSequence)
        assertEquals(emptyList(), snapshot.pendingOrders)
        assertEquals(emptyList(), snapshot.requests)
    }

    @Test
    fun `생성자의 이력 없는 영 잔고는 준비하고 비영 잔고는 소급 개시하지 않는다`() {
        val zero = state(listOf(balance(0)))
        fund(zero)
        assertEquals(listOf(balance(1_000_000_000)), zero.snapshot().balances)
        assertEquals(1, zero.snapshot().preparations.size)

        val used = state(listOf(balance(1)))
        val before = used.snapshot()
        assertFailsWith<OpeningBalanceConflictException> { fund(used) }
        assertEquals(before, used.snapshot())
        assertFalse(used.isBlocked)
    }

    @Test
    fun `현재 잔고가 0이어도 초기 자금 개시 예약 이력을 잊고 재지급하지 않는다`() {
        // 현재 코어에는 출금·전액 정산 입구가 없다. 잔고만 0으로 만든 뒤 기존 근거의 보존을 검사한다.
        listOf("initial", "opening", "reserve").forEach { history ->
            val state = if (history == "opening") state() else state(listOf(balance(1_000_000_000)))
            if (history == "opening") fund(state)
            if (history == "reserve") admit(state)
            @Suppress("UNCHECKED_CAST")
            val balances = field("balances").get(state) as MutableMap<Any, Balance>
            val key = balances.entries.single().key
            balances[key] = balance(0)
            val before = state.snapshot()

            assertFailsWith<OpeningBalanceConflictException> { fund(state, opening("new-preparation")) }

            assertEquals(before, state.snapshot())
            assertFalse(state.isBlocked)
        }
    }

    @Test
    fun `부족 거절 후 자금을 준비해도 같은 요청은 첫 거절 새 요청만 최신 잔고로 판단한다`() {
        val state = state()
        val shortage =
            OrderAdmissionState.Result.Rejected(
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(usdt, Amount.ZERO, Amount(300_000_000), Amount(300_000_000)),
            )
        assertEquals(shortage, admit(state, "R0"))
        assertEquals(emptyList(), state.snapshot().balances)
        val firstRequest = state.snapshot().requests.single()

        fund(state)

        assertEquals(listOf(firstRequest), state.snapshot().requests)
        assertEquals(shortage, admit(state, "R0"))
        assertEquals(listOf(balance(1_000_000_000)), state.snapshot().balances)
        assertEquals(1L, assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1")).record.sequence)
        assertEquals(shortage, admit(state, "R0"))
        assertEquals(listOf(balance(700_000_000, 300_000_000)), state.snapshot().balances)
        assertEquals(listOf("R0", "R1"), state.snapshot().requests.map { it.requestId })
        assertEquals(setOf("opening-ledger", "reserve-ledger"), usedIds(state, "transactionIds"))
    }

    @Test
    fun `개시와 예약은 원장 ID를 양방향으로 공유하고 충돌은 쓰기 전에 끝난다`() {
        val state = state()
        fund(state)
        val funded = state.snapshot()
        assertFailsWith<IllegalStateException> { admit(state, ledger = "opening-ledger") }
        assertEquals(funded, state.snapshot())
        assertFalse(state.isBlocked)
        admit(state)
        val before = state.snapshot()
        val other = opening("other-preparation").copy(userId = UserId("other"))

        assertFailsWith<IllegalStateException> { state.prepareDevelopmentBalance(other, "reserve-ledger", time) }
        assertEquals(before, state.snapshot())
        assertFalse(state.isBlocked)
        state.prepareDevelopmentBalance(other, "other-ledger", time)
        assertEquals(setOf("opening-ledger", "reserve-ledger", "other-ledger"), usedIds(state, "transactionIds"))
        assertEquals(3, usedIds(state, "sourceEventIds").size)
    }

    @Test
    fun `개시 사건 ID 충돌은 새 잔고 준비 기록 ID를 남기지 않는다`() {
        val state = state()

        @Suppress("UNCHECKED_CAST")
        val ids = field("sourceEventIds").get(state) as MutableSet<String>
        ids.add("OPENING:P1")
        val before = state.snapshot()

        assertFailsWith<IllegalStateException> { fund(state) }

        assertEquals(before, state.snapshot())
        assertEquals(emptySet(), usedIds(state, "transactionIds"))
        assertFalse(state.isBlocked)
        ids.remove("OPENING:P1")
        fund(state)
        assertEquals(listOf(balance(1_000_000_000)), state.snapshot().balances)
    }

    @Test
    fun `재전송의 새 원장 문맥은 검증하거나 소비하지 않고 첫 결과를 반환한다`() {
        val state = state()
        val first = fund(state)
        admit(state)
        val before = state.snapshot()
        listOf("", "bad\u0000id", "\uD800", "x".repeat(65), "reserve-ledger", "available-for-next").forEach { ignored ->
            assertEquals(first.copy(alreadyPrepared = true), state.prepareDevelopmentBalance(opening(), ignored, Instant.MIN))
            assertEquals(before, state.snapshot())
        }
        val other = opening("P2").copy(assetId = btc)
        state.prepareDevelopmentBalance(other, "available-for-next", time)
        assertEquals(setOf("opening-ledger", "reserve-ledger", "available-for-next"), usedIds(state, "transactionIds"))
    }

    @Test
    fun `잘못된 새 입력과 원장 ID는 상태와 준비 ID를 소비하지 않아 수정 후 지급할 수 있다`() {
        val state = state()
        val before = state.snapshot()
        assertFailsWith<IllegalArgumentException> { fund(state, opening(amount = 0)) }
        assertFailsWith<IllegalArgumentException> { fund(state, opening("")) }
        assertFailsWith<IllegalArgumentException> { fund(state, opening().copy(userId = UserId("bad:user"))) }
        listOf("", " \t", "bad\u0000id", "\uD800", "\uDC00", "x".repeat(65)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { state.prepareDevelopmentBalance(opening(), invalid, time) }
            assertEquals(before, state.snapshot())
            assertFalse(state.isBlocked)
            assertEquals(emptySet(), usedIds(state, "transactionIds"))
            assertEquals(emptySet(), usedIds(state, "sourceEventIds"))
        }
        val validId = "😀".repeat(64)
        val result = state.prepareDevelopmentBalance(opening(), validId, time)
        assertEquals(validId, result.ledgerTransactionId)
        assertEquals(listOf(balance(1_000_000_000)), state.snapshot().balances)
    }

    @Test
    fun `최소 단위 1과 Long 최대 금액도 절삭이나 보정 없이 준비한다`() {
        listOf(1L, Long.MAX_VALUE).forEach { amount ->
            val state = state()
            val input = opening(" 준비:원문 ", amount)
            fund(state, input)
            assertEquals(listOf(balance(amount)), state.snapshot().balances)
            assertOpening(state.snapshot().preparations.single(), input)
            assertEquals(
                input,
                state
                    .snapshot()
                    .preparations
                    .single()
                    .result.opening,
            )
        }
    }

    @Test
    fun `준비 관찰 목록과 분개를 외부에서 바꾸거나 나중의 준비로 소급 변경할 수 없다`() {
        val state = state()
        val empty = state.snapshot()
        fund(state)
        val first = state.snapshot()
        assertFailsWith<UnsupportedOperationException> { (empty.preparations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.preparations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (
                first.preparations
                    .single()
                    .transaction.postings as MutableList
            ).clear()
        }
        state.prepareDevelopmentBalance(opening("P2").copy(assetId = btc), "btc-ledger", time)
        admit(state)

        assertEquals(emptyList(), empty.preparations)
        assertEquals(emptyList(), empty.balances)
        assertEquals(1, first.preparations.size)
        assertEquals(listOf(balance(1_000_000_000)), first.balances)
        assertEquals(emptyList(), first.pendingOrders)
        assertEquals(emptyList(), first.requests)
        assertEquals(0L, first.lastSequence)
        assertOpening(first.preparations.single(), opening())
        assertEquals(2, state.snapshot().preparations.size)
    }

    @Test
    fun `준비 반영의 각 쓰기 전후 Exception과 Error는 과거 재전송과 새 요청 조회를 모두 차단한다`() {
        listOf("balances", "preparations", "transactionIds", "sourceEventIds").forEach { target ->
            listOf(false, true).forEach { afterWrite ->
                listOf(false, true).forEach { error ->
                    val state = state()
                    fund(state)
                    admit(state)
                    val before = state.snapshot()
                    val failure = if (error) AssertionError("$target Error") else IllegalStateException("$target 실패")
                    failOnWrite(state, target, afterWrite, failure)
                    val other = opening("P2").copy(userId = UserId("other"))

                    assertSame(failure, assertFailsWith<Throwable> { state.prepareDevelopmentBalance(other, "other-ledger", time) })

                    assertBlocked(state)
                    assertEquals(listOf(balance(700_000_000, 300_000_000)), before.balances)
                    assertEquals(1, before.preparations.size)
                    assertOpening(before.preparations.single(), opening())
                    assertEquals(1, before.requests.size)
                    assertEquals(1, before.pendingOrders.size)
                    assertEquals(1L, before.lastSequence)
                }
            }
        }
    }

    @Test
    fun `주문 반영 중 실패로 차단된 상태에서도 자금 준비를 새로 하거나 재전송할 수 없다`() {
        val state = state()
        fund(state)
        val failure = AssertionError("주문 쓰기 실패")
        failOnWrite(state, "records", true, failure)
        assertSame(failure, assertFailsWith<Throwable> { admit(state) })
        assertBlocked(state)
    }

    @Test
    fun `같은 고정 입력을 독립 상태 둘에 적용하면 최초 결과와 업무 상태가 필드별로 같다`() {
        val first = state()
        val second = state()
        val observations =
            listOf(first, second).map { state ->
                val rejected = admit(state, "R0")
                fund(state)
                fund(state)
                val accepted = admit(state, "R1")
                admit(state, "R1")
                fund(state)
                assertFailsWith<OpeningBalanceRequestConflictException> { fund(state, opening(amount = 2_000_000_000)) }
                val shortage = admit(state, "R2", order("large", 800_000_000), "large-ledger")
                assertEquals(
                    OrderAdmissionState.Result.Rejected(
                        OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
                        OrderAdmissionResult.InsufficientBalance(usdt, Amount(700_000_000), Amount(800_000_000), Amount(100_000_000)),
                    ),
                    shortage,
                )
                val snapshot = state.snapshot()
                assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
                assertEquals(1L, snapshot.lastSequence)
                assertEquals(1, snapshot.pendingOrders.size)
                assertEquals(1, snapshot.preparations.size)
                assertEquals(listOf("R0", "R1", "R2"), snapshot.requests.map { it.requestId })
                assertEquals(listOf(rejected, accepted, shortage), snapshot.requests.map { it.result })
                assertEquals(setOf("opening-ledger", "reserve-ledger"), usedIds(state, "transactionIds"))
                assertEquals(2, usedIds(state, "sourceEventIds").size)
                snapshot
            }
        // RequestRecord·Candidate·LedgerTransaction의 참조나 equals 대신 업무 의미 필드만 비교한다.
        assertEquals(snapshotValues(observations[0]), snapshotValues(observations[1]))
        assertEquals(usedIds(first, "sourceEventIds"), usedIds(second, "sourceEventIds"))
    }

    private fun snapshotValues(snapshot: OrderAdmissionState.Snapshot): List<Any> =
        listOf(
            snapshot.balances,
            snapshot.lastSequence,
            snapshot.pendingOrders.map { recordValues(it) },
            snapshot.preparations.map { listOf(it.result, transactionValues(it.transaction)) },
            snapshot.requests.map {
                listOf(
                    it.requestId,
                    it.order,
                    it.market,
                    it.feePolicySnapshot,
                    it.ledgerTransactionId,
                    it.occurredAt,
                    resultValues(it.result),
                )
            },
        )

    private fun recordValues(record: OrderAdmissionState.Record): List<Any> {
        val candidate = record.candidate
        return listOf(
            record.sequence,
            candidate.order,
            candidate.market,
            candidate.feePolicySnapshot,
            candidate.previousBalance,
            candidate.nextBalance,
            candidate.reservation,
            transactionValues(candidate.reserveTransaction),
        )
    }

    private fun resultValues(result: OrderAdmissionState.Result): Any =
        when (result) {
            is OrderAdmissionState.Result.Accepted -> recordValues(result.record)
            is OrderAdmissionState.Result.Rejected -> result
        }

    private fun transactionValues(transaction: LedgerTransaction): List<Any> =
        listOf(
            transaction.ledgerTransactionId,
            transaction.sourceEventId,
            transaction.transactionType,
            transaction.occurredAt,
            transaction.postings,
        )

    private fun assertBlocked(state: OrderAdmissionState) {
        assertTrue(state.isBlocked)
        assertBlockedOperation { fund(state) }
        assertBlockedOperation {
            state.prepareDevelopmentBalance(opening("new").copy(userId = UserId("unused-user")), "unused-ledger", time)
        }
        assertBlockedOperation { admit(state) }
        assertBlockedOperation { admit(state, "new", order("new")) }
        assertBlockedOperation { state.admit(order(), market, policy, "legacy", time) }
        assertBlockedOperation { state.snapshot() }
        assertBlockedOperation { state.pendingOrders(market.marketId) }
    }

    private fun assertBlockedOperation(operation: () -> Unit) {
        val error = assertFailsWith<IllegalStateException> { operation() }
        assertEquals("반영 중 오류로 접수 상태가 차단됐습니다", error.message)
    }

    // 기존 접수 테스트의 방식으로만 실제 collection 쓰기 경계를 통제한다. 제품 장애 스위치는 없다.
    private fun failOnWrite(
        state: OrderAdmissionState,
        target: String,
        afterWrite: Boolean,
        failure: Throwable,
    ) {
        if (target == "transactionIds" || target == "sourceEventIds") {
            @Suppress("UNCHECKED_CAST")
            val original = field(target).get(state) as MutableSet<String>
            field(target).set(
                state,
                object : MutableSet<String> by original {
                    override fun add(element: String): Boolean {
                        if (afterWrite) original.add(element)
                        throw failure
                    }
                },
            )
        } else {
            @Suppress("UNCHECKED_CAST")
            val original = field(target).get(state) as MutableMap<Any, Any>
            field(target).set(
                state,
                object : MutableMap<Any, Any> by original {
                    override fun put(
                        key: Any,
                        value: Any,
                    ): Any? {
                        if (afterWrite) original[key] = value
                        throw failure
                    }
                },
            )
        }
    }

    private fun usedIds(
        state: OrderAdmissionState,
        name: String,
    ): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return (field(name).get(state) as Set<String>).toSet()
    }

    private fun field(name: String) = OrderAdmissionState::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun assertOpening(
        record: OrderAdmissionState.PreparationRecord,
        opening: OpeningBalance,
    ) {
        val transaction = record.transaction
        assertEquals("opening-ledger", transaction.ledgerTransactionId)
        assertEquals("OPENING:${opening.preparationId}", transaction.sourceEventId)
        assertEquals(LedgerTransactionType.OPENING, transaction.transactionType)
        assertEquals(time, transaction.occurredAt)
        assertEquals(
            listOf(
                LedgerPosting(
                    "SYSTEM:${opening.assetId.value}:DEVELOPMENT_FUNDING",
                    opening.assetId,
                    LedgerPostingSide.DEBIT,
                    opening.amount,
                ),
                LedgerPosting(
                    "USER:${opening.userId.value}:${opening.assetId.value}:AVAILABLE",
                    opening.assetId,
                    LedgerPostingSide.CREDIT,
                    opening.amount,
                ),
            ),
            transaction.postings,
        )
    }

    private fun state(initial: List<Balance> = emptyList()) = OrderAdmissionState(initial, calculator)

    private fun opening(
        id: String = "P1",
        amount: Long = 1_000_000_000,
    ) = OpeningBalance(id, user, usdt, Amount(amount))

    private fun balance(
        available: Long,
        hold: Long = 0,
    ) = Balance(user, usdt, Amount(available), Amount(hold))

    private fun fund(
        state: OrderAdmissionState,
        opening: OpeningBalance = opening(),
    ) = state.prepareDevelopmentBalance(opening, "opening-ledger", time)

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

    private fun admit(
        state: OrderAdmissionState,
        request: String = "R1",
        input: OrderAdmissionInput = order(),
        ledger: String = "reserve-ledger",
    ) = state.admit(request, input, market, policy, ledger, time)
}

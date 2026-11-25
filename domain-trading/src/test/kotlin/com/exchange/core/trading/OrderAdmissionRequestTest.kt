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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 계산기를 사용한다. 금액은 USDT 6자리·BTC 8자리의 최소 단위다. */
class OrderAdmissionRequestTest {
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
    fun `R01 같은 BUY 요청 재전송은 최초 승인과 한 번의 예약 원장 순번만 남긴다`() {
        val initial = balance(1_000_000_000)
        val state = OrderAdmissionState(listOf(initial), calculator)
        val input = order()

        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        val afterFirst = state.snapshot()
        val replayed = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        val afterReplay = state.snapshot()

        listOf(first, replayed).forEach { accepted ->
            assertBuyRecord(accepted.record, input, initial, balance(700_000_000, 300_000_000))
        }
        assertResultValue(first, replayed)
        listOf(afterFirst, afterReplay).forEach { snapshot ->
            assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
            assertEquals(1L, snapshot.lastSequence)
            assertEquals(1, snapshot.pendingOrders.size)
            assertBuyRecord(snapshot.pendingOrders.single(), input, initial, balance(700_000_000, 300_000_000))
            assertEquals(1, snapshot.requests.size)
            assertRequest(snapshot.requests.single(), "R1", input, first)
        }
        assertEquals(1, state.pendingOrders(market.marketId).size)
        assertResultValue(first, OrderAdmissionState.Result.Accepted(state.pendingOrders(market.marketId).single()))
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertEquals(setOf(first.record.candidate.reserveTransaction.sourceEventId), usedIds(state, "sourceEventIds"))
        assertEquals(initial, balance(1_000_000_000))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `R05 같은 객체의 잔고가 늘고 새 요청이 승인돼도 최초 부족 거절을 재사용한다`() {
        val state = OrderAdmissionState(listOf(balance(200_000_000)), calculator)
        val input = order()

        val first = assertIs<OrderAdmissionState.Result.Rejected>(admit(state, "R1", input))
        assertInitialInsufficientBalance(first)
        val afterRejection = state.snapshot()
        assertEquals(listOf(balance(200_000_000)), afterRejection.balances)
        assertEquals(emptyList(), afterRejection.pendingOrders)
        assertEquals(0L, afterRejection.lastSequence)
        assertEquals(1, afterRejection.requests.size)
        assertRequest(afterRejection.requests.single(), "R1", input, first)
        assertEquals(emptySet(), usedIds(state, "transactionIds"))
        assertEquals(emptySet(), usedIds(state, "sourceEventIds"))

        creditOwnedBalance(state, Amount(1_000_000_000))
        val beforeReplay = state.snapshot()
        assertEquals(listOf(balance(1_200_000_000)), beforeReplay.balances)
        assertInitialInsufficientBalance(assertIs<OrderAdmissionState.Result.Rejected>(admit(state, "R1", input)))
        assertSnapshotValue(beforeReplay, state.snapshot())

        // 최초 거절은 주문 키나 원장 ID를 소비하지 않아 같은 주문·원장 ID를 새 요청에 사용할 수 있다.
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R2", input))
        assertBuyRecord(accepted.record, input, balance(1_200_000_000), balance(900_000_000, 300_000_000))
        val afterAccepted = state.snapshot()
        assertEquals(listOf(balance(900_000_000, 300_000_000)), afterAccepted.balances)
        assertEquals(listOf(accepted.record), afterAccepted.pendingOrders)
        assertEquals(1L, afterAccepted.lastSequence)
        assertEquals(2, afterAccepted.requests.size)
        assertRequest(afterAccepted.requests.single { it.requestId == "R1" }, "R1", input, first)
        assertRequest(afterAccepted.requests.single { it.requestId == "R2" }, "R2", input, accepted)

        // 승인된 주문의 중복 검사보다 R1의 최초 거절 조회가 먼저여야 한다.
        assertInitialInsufficientBalance(assertIs<OrderAdmissionState.Result.Rejected>(admit(state, "R1", input)))
        assertSnapshotValue(afterAccepted, state.snapshot())
        assertEquals(listOf(accepted.record), state.pendingOrders(market.marketId))
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertEquals(setOf(accepted.record.candidate.reserveTransaction.sourceEventId), usedIds(state, "sourceEventIds"))
        assertInitialInsufficientBalance(first)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `R02 SELL 재전송은 BTC 2개만 한 번 예약하고 최초 수수료 정책을 유지한다`() {
        val btcBalance = Balance(user, btc, Amount(300_000_000), Amount.ZERO)
        val state = OrderAdmissionState(listOf(balance(1_000_000_000), btcBalance), calculator)
        val input = order().copy(side = Side.SELL, quantity = Quantity(200_000_000))
        val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))

        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input, appliedPolicy = applied))
        val beforeReplay = state.snapshot()
        val replayed = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input, appliedPolicy = applied))

        listOf(first, replayed).forEach { accepted ->
            assertEquals(1L, accepted.record.sequence)
            val candidate = accepted.record.candidate
            assertEquals(input, candidate.order)
            assertEquals(market, candidate.market)
            assertEquals(applied, candidate.feePolicySnapshot)
            assertEquals(btcBalance, candidate.previousBalance)
            assertEquals(Balance(user, btc, Amount(100_000_000), Amount(200_000_000)), candidate.nextBalance)
            val reservation = candidate.reservation
            assertEquals(Side.SELL, reservation.side)
            assertEquals(btc, reservation.assetId)
            assertEquals(Amount(200_000_000), reservation.reservedAmount)
            assertEquals(Amount(200_000_000), reservation.remainingAmount)
            assertEquals(Quantity(200_000_000), reservation.remainingQuantity)
            assertEquals(Amount.ZERO, reservation.initialFeeReserveAmount)
            assertEquals(Amount.ZERO, reservation.remainingFeeReserveAmount)
            assertEquals(applied, reservation.feePolicySnapshot)
            assertEquals("ledger-order-1", candidate.reserveTransaction.ledgerTransactionId)
            assertEquals(time, candidate.reserveTransaction.occurredAt)
            assertEquals(LedgerTransactionType.RESERVE, candidate.reserveTransaction.transactionType)
            assertEquals(
                listOf(
                    LedgerPosting("USER:user-1:BTC:AVAILABLE", btc, LedgerPostingSide.DEBIT, Amount(200_000_000)),
                    LedgerPosting("USER:user-1:BTC:HOLD", btc, LedgerPostingSide.CREDIT, Amount(200_000_000)),
                ),
                candidate.reserveTransaction.postings,
            )
        }
        assertResultValue(first, replayed)
        assertSnapshotValue(beforeReplay, state.snapshot())
        val snapshot = state.snapshot()
        assertEquals(
            listOf(balance(1_000_000_000), Balance(user, btc, Amount(100_000_000), Amount(200_000_000))),
            snapshot.balances,
        )
        assertEquals(1L, snapshot.lastSequence)
        assertEquals(1, snapshot.pendingOrders.size)
        assertEquals(1, snapshot.requests.size)
        assertRequest(snapshot.requests.single(), "R1", input, first, appliedPolicy = applied)
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertEquals(1, usedIds(state, "sourceEventIds").size)
    }

    @Test
    fun `R03 같은 사용자 요청의 원본 일곱 필드 중 하나라도 바꾸면 첫 기록을 보존하며 충돌한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val input = order()
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        assertBuyRecord(first.record, input, balance(1_000_000_000), balance(700_000_000, 300_000_000))
        val before = state.snapshot()
        val changed =
            listOf(
                input.copy(marketId = MarketId("OTHER-USDT")),
                input.copy(orderId = OrderId("other-order")),
                input.copy(side = Side.SELL),
                input.copy(orderType = OrderType.MARKET),
                input.copy(timeInForce = TimeInForce.IOC),
                input.copy(price = Price(101_000_000)),
                input.copy(quantity = Quantity(400_000_000)),
            )

        changed.forEach { different ->
            assertEquals(
                OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT),
                admit(state, "R1", different),
                "변경한 원본: $different",
            )
            assertSnapshotValue(before, state.snapshot())
        }
        assertResultValue(first, assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input)))
        assertRequest(state.snapshot().requests.single(), "R1", input, first)
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertEquals(1, usedIds(state, "sourceEventIds").size)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `R04 새 요청의 기존 주문은 첫 중복 거절을 기록하고 그 요청 내용 변경은 충돌한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val input = order()
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        val duplicate = OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER)

        assertEquals(duplicate, admit(state, "R2", input, ledgerId = "duplicate-context"))
        val beforeReplay = state.snapshot()
        assertEquals(duplicate, admit(state, "R2", input, ledgerId = "new-unused-context"))
        assertEquals(
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT),
            admit(state, "R2", input.copy(quantity = Quantity(400_000_000))),
        )

        assertSnapshotValue(beforeReplay, state.snapshot())
        assertEquals(listOf(balance(700_000_000, 300_000_000)), beforeReplay.balances)
        assertEquals(1L, beforeReplay.lastSequence)
        assertEquals(1, beforeReplay.pendingOrders.size)
        assertEquals(2, beforeReplay.requests.size)
        assertRequest(beforeReplay.requests.single { it.requestId == "R1" }, "R1", input, first)
        assertRequest(beforeReplay.requests.single { it.requestId == "R2" }, "R2", input, duplicate, ledgerId = "duplicate-context")
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertEquals(1, usedIds(state, "sourceEventIds").size)
    }

    @Test
    fun `R04 요청 유무와 관계없이 두 접수 입구는 승인된 주문 키를 공유한다`() {
        listOf(false, true).forEach { requestFirst ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            val input = order()
            val first =
                assertIs<OrderAdmissionState.Result.Accepted>(
                    if (requestFirst) admit(state, "R1", input) else legacyAdmit(state, input),
                )
            val duplicate =
                if (requestFirst) legacyAdmit(state, input) else admit(state, "R2", input)

            assertEquals(OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER), duplicate)
            val snapshot = state.snapshot()
            assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
            assertEquals(1L, snapshot.lastSequence)
            assertEquals(1, snapshot.pendingOrders.size)
            assertResultValue(first, OrderAdmissionState.Result.Accepted(snapshot.pendingOrders.single()))
            assertEquals(1, snapshot.requests.size)
            assertRequest(snapshot.requests.single(), if (requestFirst) "R1" else "R2", input, if (requestFirst) first else duplicate)
            assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        }
    }

    @Test
    fun `R06 유효한 식별자의 정상 거절은 원본과 최초 사유만 한 건 보관한다`() {
        val cases =
            listOf(
                order().copy(orderType = OrderType.MARKET) to OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                order().copy(timeInForce = TimeInForce.IOC) to OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                order().copy(quantity = Quantity(0)) to OrderAdmissionResult.Reason.INVALID_QUANTITY,
                order().copy(price = Price(1), quantity = Quantity(1)) to OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION,
            )
        cases.forEach { (input, reason) ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            val expected = OrderAdmissionState.Result.Rejected(reason)
            assertEquals(expected, admit(state, "rejected", input))
            assertEquals(expected, admit(state, "rejected", input))

            val snapshot = state.snapshot()
            assertEquals(listOf(balance(1_000_000_000)), snapshot.balances)
            assertEquals(emptyList(), snapshot.pendingOrders)
            assertEquals(0L, snapshot.lastSequence)
            assertEquals(1, snapshot.requests.size)
            assertRequest(snapshot.requests.single(), "rejected", input, expected)
            assertEquals(emptySet(), usedIds(state, "transactionIds"))
            assertEquals(emptySet(), usedIds(state, "sourceEventIds"))
            assertFalse(state.isBlocked)
        }
    }

    @Test
    fun `R07 재전송은 바뀐 서버 문맥과 잘못된 문맥도 무시하고 최초 문맥을 반환한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val input = order()
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        assertBuyRecord(first.record, input, balance(1_000_000_000), balance(700_000_000, 300_000_000))
        val before = state.snapshot()
        val changedPolicy = policy.copy(scheduleVersion = 2, feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000)))

        assertResultValue(
            first,
            admit(
                state,
                "R1",
                input,
                definition = market.copy(baseAssetScale = 7),
                appliedPolicy = changedPolicy,
                ledgerId = "changed-ledger",
                at = time.plusSeconds(60),
            ),
        )
        val invalidContexts =
            listOf(
                Triple(market.copy(marketId = MarketId("MISMATCH")), policy, "unused-ledger"),
                Triple(market, policy, ""),
                Triple(market, policy.copy(productType = FeeProductType.PERPETUAL_FUTURES), "unused-ledger"),
            )
        invalidContexts.forEachIndexed { index, (definition, applied, ledger) ->
            assertResultValue(first, admit(state, "R1", input, definition, applied, ledger, time.plusSeconds(120)))
            // 새 업무 키와 충분한 700 USDT를 준비해 중복·잔고 부족이 서버 문맥 오류를 가리지 않게 한다.
            assertFailsWith<IllegalArgumentException> {
                admit(state, "control-$index", order("control-$index"), definition, applied, ledger)
            }
            assertSnapshotValue(before, state.snapshot())
            assertFalse(state.isBlocked)
        }
        assertRequest(state.snapshot().requests.single(), "R1", input, first)
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
    }

    @Test
    fun `R08 사용자 값은 요청 키를 나누고 새 사용자 요청의 주문 중복은 원래 승인을 노출하지 않는다`() {
        val other = UserId("other-user")
        val state = OrderAdmissionState(listOf(balance(1_000_000_000), balance(1_000_000_000).copy(userId = other)), calculator)
        val original = order()
        val otherInput = order("other-order").copy(userId = other)
        val first = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", original))
        val second = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", otherInput))

        assertEquals(1L, first.record.sequence)
        assertEquals(2L, second.record.sequence)
        assertEquals(otherInput, second.record.candidate.order)
        assertEquals(balance(1_000_000_000).copy(userId = other), second.record.candidate.previousBalance)
        assertEquals(balance(700_000_000, 300_000_000).copy(userId = other), second.record.candidate.nextBalance)
        assertEquals(other, second.record.candidate.reservation.userId)
        assertEquals(
            listOf("USER:other-user:USDT:AVAILABLE", "USER:other-user:USDT:HOLD"),
            second.record.candidate.reserveTransaction.postings
                .map { it.accountId },
        )
        val duplicateInput = original.copy(userId = other)
        val duplicate = OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER)
        assertEquals(duplicate, admit(state, "R2", duplicateInput))
        val beforeConflict = state.snapshot()
        assertEquals(
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT),
            admit(state, "R1", duplicateInput),
        )
        assertSnapshotValue(beforeConflict, state.snapshot())
        assertEquals(
            listOf(balance(700_000_000, 300_000_000), balance(700_000_000, 300_000_000).copy(userId = other)),
            beforeConflict.balances,
        )
        assertEquals(2L, beforeConflict.lastSequence)
        assertEquals(2, beforeConflict.pendingOrders.size)
        assertEquals(3, beforeConflict.requests.size)
        assertRequest(beforeConflict.requests.single { it.order.userId == user }, "R1", original, first)
        assertRequest(beforeConflict.requests.single { it.order.userId == other && it.requestId == "R1" }, "R1", otherInput, second)
        assertRequest(beforeConflict.requests.single { it.requestId == "R2" }, "R2", duplicateInput, duplicate)
        assertResultValue(second, admit(state, "R1", otherInput))
        assertEquals(setOf("ledger-order-1", "ledger-other-order"), usedIds(state, "transactionIds"))
    }

    @Test
    fun `R09 요청 ID는 code point 1개와 64개를 허용하고 원문을 보존한다`() {
        listOf("x", "x".repeat(64), "😀".repeat(64)).forEach { requestId ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, requestId))
            assertRequest(state.snapshot().requests.single(), requestId, order(), accepted)
            assertBuyRecord(accepted.record, order(), balance(1_000_000_000), balance(700_000_000, 300_000_000))
        }
    }

    @Test
    fun `R09 잘못된 요청 ID와 기존 주문 식별자는 이력 조회와 주문 중복보다 먼저 거절한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        admit(state, "R1")
        val before = state.snapshot()
        val invalidRequestIds =
            listOf("", " \t\n", "x".repeat(65), "😀".repeat(65), "R\u0000id", "\uD800", "\uDC00", "\uD800x")
        invalidRequestIds.forEach { requestId ->
            assertEquals(
                OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER),
                admit(state, requestId),
            )
            assertSnapshotValue(before, state.snapshot())
        }
        val invalidIdentifierValues = listOf("x".repeat(65), "😀".repeat(65), "bad\u0000id", "\uD800", "\uDC00", "\uD800x")
        val invalidOrders =
            invalidIdentifierValues.flatMap { invalid ->
                listOf(
                    order().copy(userId = UserId(invalid)),
                    order().copy(marketId = MarketId(invalid)),
                    order().copy(orderId = OrderId(invalid)),
                )
            } + order().copy(userId = UserId("user:part"))
        invalidOrders.forEach { invalid ->
            assertEquals(
                OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER),
                admit(state, "R1", invalid),
            )
            assertSnapshotValue(before, state.snapshot())
        }
        // 빈 기존 ID는 값 객체 생성에서 거절되므로 접수 입구까지 도달했다고 세지 않는다.
        listOf("", " \t").forEach { blank ->
            assertFailsWith<IllegalArgumentException> { UserId(blank) }
            assertFailsWith<IllegalArgumentException> { MarketId(blank) }
            assertFailsWith<IllegalArgumentException> { OrderId(blank) }
        }
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `R09 기존 식별자 64 code point와 마켓 주문의 콜론은 유효하다`() {
        listOf("x".repeat(64), "😀".repeat(64), "a:b").forEach { value ->
            val inputs =
                listOf(order().copy(marketId = MarketId(value)), order().copy(orderId = OrderId(value))) +
                    if (':' in value) emptyList() else listOf(order().copy(userId = UserId(value)))
            inputs.forEach { input ->
                val definition = market.copy(marketId = input.marketId)
                val state = OrderAdmissionState(listOf(balance(1_000_000_000).copy(userId = input.userId)), calculator)
                val accepted =
                    assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "valid", input, definition, ledgerId = "valid-ledger"))
                assertEquals(1L, accepted.record.sequence)
                assertEquals(input, accepted.record.candidate.order)
                assertEquals(listOf(balance(700_000_000, 300_000_000).copy(userId = input.userId)), state.snapshot().balances)
                assertRequest(state.snapshot().requests.single(), "valid", input, accepted, definition, ledgerId = "valid-ledger")
            }
        }
    }

    @Test
    fun `R09 trim 대소문자 Unicode 정규화로 다른 요청 원문을 합치지 않는다`() {
        val requestIds = listOf("R1", " R1 ", "r1", "é", "e\u0301", "R:1")
        val state = OrderAdmissionState(listOf(balance(3_000_000_000)), calculator)
        requestIds.forEachIndexed { index, requestId ->
            val input = order("order-$index")
            val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, requestId, input))
            assertEquals(index.toLong() + 1, accepted.record.sequence)
            assertRequest(state.snapshot().requests.last(), requestId, input, accepted)
        }
        assertEquals(requestIds, state.snapshot().requests.map { it.requestId })
        assertEquals(6, state.snapshot().pendingOrders.size)
        assertEquals(6L, state.snapshot().lastSequence)
        assertEquals(listOf(balance(1_200_000_000, 1_800_000_000)), state.snapshot().balances)
    }

    @Test
    fun `R10 반영 전 문맥과 원장 ID 오류는 요청을 저장하지 않아 같은 요청을 고쳐 재시도할 수 있다`() {
        val cases =
            listOf(
                Triple(market.copy(marketId = MarketId("MISMATCH")), policy, "new-ledger"),
                Triple(market, policy.copy(productType = FeeProductType.PERPETUAL_FUTURES), "new-ledger"),
                Triple(market, policy, ""),
                Triple(market, policy, "ledger-order-1"),
            )
        cases.forEach { (definition, applied, ledger) ->
            val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
            admit(state, "previous")
            val input = order("second")
            val before = state.snapshot()

            if (ledger == "ledger-order-1") {
                assertFailsWith<IllegalStateException> { admit(state, "R1", input, definition, applied, ledger) }
            } else {
                assertFailsWith<IllegalArgumentException> { admit(state, "R1", input, definition, applied, ledger) }
            }
            assertSnapshotValue(before, state.snapshot())
            assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
            assertFalse(state.isBlocked)
            val retried = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input, ledgerId = "fixed-ledger"))
            assertBuyRecord(retried.record, input, balance(700_000_000, 300_000_000), balance(400_000_000, 600_000_000), 2, "fixed-ledger")
            assertEquals(2, state.snapshot().requests.size)
            assertRequest(state.snapshot().requests.last(), "R1", input, retried, ledgerId = "fixed-ledger")
        }
    }

    @Test
    fun `R10 승인 순번 overflow도 반영 전 오류이며 최초 요청 결과를 만들지 않는다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        field("lastSequence").setLong(state, Long.MAX_VALUE)
        val before = state.snapshot()

        assertFailsWith<ArithmeticException> { admit(state, "R1") }

        assertSnapshotValue(before, state.snapshot())
        assertEquals(emptyList(), state.snapshot().requests)
        assertEquals(emptySet(), usedIds(state, "transactionIds"))
        assertEquals(emptySet(), usedIds(state, "sourceEventIds"))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `R10 원장 사건 ID 충돌도 최초 결과를 남기지 않아 준비를 바로잡은 같은 요청이 승인된다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        admit(state, "previous")
        val input = order("second")
        // 정답을 계산하는 용도가 아니라 실제 원장 사건 ID의 충돌 지점을 준비하는 용도다.
        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(
                calculator.calculate(input, market, policy, balance(700_000_000, 300_000_000), "ledger-second", time),
            ).candidate

        @Suppress("UNCHECKED_CAST")
        val sourceIds = field("sourceEventIds").get(state) as MutableSet<String>
        assertTrue(sourceIds.add(candidate.reserveTransaction.sourceEventId))
        val before = state.snapshot()
        val beforeSourceIds = usedIds(state, "sourceEventIds")

        assertFailsWith<IllegalStateException> { admit(state, "R1", input) }

        assertSnapshotValue(before, state.snapshot())
        assertEquals(beforeSourceIds, usedIds(state, "sourceEventIds"))
        assertEquals(setOf("ledger-order-1"), usedIds(state, "transactionIds"))
        assertFalse(state.isBlocked)
        assertTrue(sourceIds.remove(candidate.reserveTransaction.sourceEventId))
        val retried = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "R1", input))
        assertBuyRecord(retried.record, input, balance(700_000_000, 300_000_000), balance(400_000_000, 600_000_000), 2)
        assertEquals(2, state.snapshot().requests.size)
        assertRequest(state.snapshot().requests.last(), "R1", input, retried)
    }

    @Test
    fun `R11 승인 반영의 각 쓰기 전후 Exception과 Error는 재전송과 정상 조회를 차단한다`() {
        listOf("balances", "records", "transactionIds", "sourceEventIds", "requestRecords").forEach { target ->
            listOf(false, true).forEach { afterWrite ->
                listOf(false, true).forEach { error ->
                    assertWriteFailureBlocked(target, afterWrite, error, rejection = false)
                }
            }
        }
    }

    @Test
    fun `R11 정상 거절의 최초 기록 쓰기 전후 Exception과 Error도 객체를 차단한다`() {
        listOf(false, true).forEach { afterWrite ->
            listOf(false, true).forEach { error ->
                assertWriteFailureBlocked("requestRecords", afterWrite, error, rejection = true)
            }
        }
    }

    @Test
    fun `R12 요청 관찰 목록과 최초 결과를 보호하고 거절은 원장 ID와 승인 순번을 소비하지 않는다`() {
        val initial = mutableListOf(balance(1_000_000_000))
        val state = OrderAdmissionState(initial, calculator)
        val empty = state.snapshot()
        initial.clear()
        val rejectedInput = order().copy(quantity = Quantity(0))
        val rejected = OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INVALID_QUANTITY)
        assertEquals(rejected, admit(state, "rejected", rejectedInput, ledgerId = "shared-ledger"))
        val first = state.snapshot()
        val firstRequest = first.requests.single()

        assertFailsWith<UnsupportedOperationException> { (empty.requests as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.requests as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.balances as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (first.pendingOrders as MutableList).clear() }
        assertEquals(emptySet(), usedIds(state, "transactionIds"))
        assertEquals(emptySet(), usedIds(state, "sourceEventIds"))
        assertEquals(0L, first.lastSequence)

        val input = order()
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "accepted", input, ledgerId = "shared-ledger"))
        assertBuyRecord(accepted.record, input, balance(1_000_000_000), balance(700_000_000, 300_000_000), ledgerId = "shared-ledger")
        assertFailsWith<UnsupportedOperationException> {
            (accepted.record.candidate.reserveTransaction.postings as MutableList).clear()
        }
        val second = state.snapshot()
        assertEquals(listOf(balance(700_000_000, 300_000_000)), second.balances)
        assertEquals(1L, second.lastSequence)
        assertEquals(1, second.pendingOrders.size)
        assertEquals(2, second.requests.size)
        assertRequest(firstRequest, "rejected", rejectedInput, rejected, ledgerId = "shared-ledger")
        assertRequest(second.requests.first(), "rejected", rejectedInput, rejected, ledgerId = "shared-ledger")
        assertRequest(second.requests.last(), "accepted", input, accepted, ledgerId = "shared-ledger")
        assertEquals(rejected, admit(state, "rejected", rejectedInput, ledgerId = "different-ledger"))
        assertSnapshotValue(second, state.snapshot())
        assertEquals(listOf(balance(1_000_000_000)), empty.balances)
        assertEquals(emptyList(), empty.requests)
        assertEquals(0L, empty.lastSequence)
        assertEquals(listOf(balance(1_000_000_000)), first.balances)
        assertEquals(emptyList(), first.pendingOrders)
        assertEquals(1, first.requests.size)
        assertEquals(setOf("shared-ledger"), usedIds(state, "transactionIds"))
        assertEquals(1, usedIds(state, "sourceEventIds").size)
    }

    @Test
    fun `R13 요청 존재 조회는 승인과 거절을 찾고 사용자별 키와 상태를 보존한다`() {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val other = UserId("other-user")
        val empty = state.snapshot()

        assertFalse(state.hasRequest(user, "accepted"))
        assertFalse(state.hasRequest(user, "rejected"))
        assertSnapshotValue(empty, state.snapshot())
        assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "accepted"))
        assertEquals(
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INVALID_QUANTITY),
            admit(state, "rejected", order("rejected").copy(quantity = Quantity.ZERO)),
        )
        val before = state.snapshot()

        assertTrue(state.hasRequest(user, "accepted"))
        assertTrue(state.hasRequest(user, "rejected"))
        assertFalse(state.hasRequest(user, "missing"))
        assertFalse(state.hasRequest(other, "accepted"))
        assertFalse(state.hasRequest(other, "rejected"))
        assertSnapshotValue(before, state.snapshot())
        assertEquals(
            OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.INVALID_QUANTITY),
            admit(state, "accepted", order("other-order").copy(userId = other, quantity = Quantity.ZERO)),
        )
        val afterOtherRequest = state.snapshot()

        assertTrue(state.hasRequest(other, "accepted"))
        assertTrue(state.hasRequest(user, "accepted"))
        assertFalse(state.hasRequest(other, "rejected"))
        assertSnapshotValue(afterOtherRequest, state.snapshot())
        assertFalse(state.isBlocked)
    }

    private fun assertBuyRecord(
        record: OrderAdmissionState.Record,
        input: OrderAdmissionInput,
        previous: Balance,
        next: Balance,
        sequence: Long = 1,
        ledgerId: String = "ledger-${input.orderId.value}",
    ) {
        assertEquals(sequence, record.sequence)
        val candidate = record.candidate
        assertEquals(input, candidate.order)
        assertEquals(market, candidate.market)
        assertEquals(policy, candidate.feePolicySnapshot)
        assertEquals(previous, candidate.previousBalance)
        assertEquals(next, candidate.nextBalance)
        val reservation = candidate.reservation
        assertEquals(input.orderId, reservation.orderId)
        assertEquals(user, reservation.userId)
        assertEquals(market.marketId, reservation.marketId)
        assertEquals(Side.BUY, reservation.side)
        assertEquals(usdt, reservation.assetId)
        assertEquals(Price(100_000_000), reservation.limitPrice)
        assertEquals(Quantity(300_000_000), reservation.initialQuantity)
        assertEquals(Quantity(300_000_000), reservation.remainingQuantity)
        assertEquals(Amount(300_000_000), reservation.reservedAmount)
        assertEquals(Amount(300_000_000), reservation.remainingAmount)
        assertEquals(Amount.ZERO, reservation.initialFeeReserveAmount)
        assertEquals(Amount.ZERO, reservation.remainingFeeReserveAmount)
        assertEquals(OrderReservationStatus.ACTIVE, reservation.status)
        assertEquals(policy, reservation.feePolicySnapshot)
        val transaction = candidate.reserveTransaction
        assertEquals(ledgerId, transaction.ledgerTransactionId)
        assertEquals(time, transaction.occurredAt)
        assertEquals(LedgerTransactionType.RESERVE, transaction.transactionType)
        assertEquals(
            listOf(
                LedgerPosting("USER:user-1:USDT:AVAILABLE", usdt, LedgerPostingSide.DEBIT, Amount(300_000_000)),
                LedgerPosting("USER:user-1:USDT:HOLD", usdt, LedgerPostingSide.CREDIT, Amount(300_000_000)),
            ),
            transaction.postings,
        )
    }

    private fun assertInitialInsufficientBalance(result: OrderAdmissionState.Result.Rejected) {
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, result.reason)
        val insufficient = assertIs<OrderAdmissionResult.InsufficientBalance>(result.insufficientBalance)
        assertEquals(usdt, insufficient.assetId)
        assertEquals(Amount(200_000_000), insufficient.available)
        assertEquals(Amount(300_000_000), insufficient.required)
        assertEquals(Amount(100_000_000), insufficient.shortfall)
    }

    private fun assertRequest(
        record: OrderAdmissionState.RequestRecord,
        requestId: String,
        input: OrderAdmissionInput,
        firstResult: OrderAdmissionState.Result,
        definition: MarketDefinition = market,
        appliedPolicy: TradingFeePolicySnapshot = policy,
        ledgerId: String = "ledger-${input.orderId.value}",
        at: Instant = time,
    ) {
        assertEquals(requestId, record.requestId)
        assertEquals(input, record.order)
        assertEquals(definition, record.market)
        assertEquals(appliedPolicy, record.feePolicySnapshot)
        assertEquals(ledgerId, record.ledgerTransactionId)
        assertEquals(at, record.occurredAt)
        assertResultValue(firstResult, record.result)
    }

    // 필드 값으로 비교해 후보·원장 객체의 참조 동일성을 재전송 계약에 추가하지 않는다.
    private fun assertResultValue(
        expected: OrderAdmissionState.Result,
        actual: OrderAdmissionState.Result,
    ) {
        when (expected) {
            is OrderAdmissionState.Result.Rejected -> {
                val rejected = assertIs<OrderAdmissionState.Result.Rejected>(actual)
                assertEquals(expected.reason, rejected.reason)
                assertEquals(expected.insufficientBalance, rejected.insufficientBalance)
            }

            is OrderAdmissionState.Result.Accepted -> {
                val accepted = assertIs<OrderAdmissionState.Result.Accepted>(actual)
                assertEquals(expected.record.sequence, accepted.record.sequence)
                val original = expected.record.candidate
                val candidate = accepted.record.candidate
                assertEquals(original.order, candidate.order)
                assertEquals(original.market, candidate.market)
                assertEquals(original.feePolicySnapshot, candidate.feePolicySnapshot)
                assertEquals(original.previousBalance, candidate.previousBalance)
                assertEquals(original.nextBalance, candidate.nextBalance)
                assertEquals(original.reservation, candidate.reservation)
                val originalTransaction = original.reserveTransaction
                val transaction = candidate.reserveTransaction
                assertEquals(originalTransaction.ledgerTransactionId, transaction.ledgerTransactionId)
                assertEquals(originalTransaction.sourceEventId, transaction.sourceEventId)
                assertEquals(originalTransaction.transactionType, transaction.transactionType)
                assertEquals(originalTransaction.occurredAt, transaction.occurredAt)
                assertEquals(originalTransaction.postings, transaction.postings)
            }
        }
    }

    private fun assertSnapshotValue(
        expected: OrderAdmissionState.Snapshot,
        actual: OrderAdmissionState.Snapshot,
    ) {
        assertEquals(expected.balances, actual.balances)
        assertEquals(expected.lastSequence, actual.lastSequence)
        assertEquals(expected.pendingOrders.size, actual.pendingOrders.size)
        expected.pendingOrders.zip(actual.pendingOrders).forEach { (before, after) ->
            assertResultValue(OrderAdmissionState.Result.Accepted(before), OrderAdmissionState.Result.Accepted(after))
        }
        assertEquals(expected.requests.size, actual.requests.size)
        expected.requests.zip(actual.requests).forEach { (before, after) ->
            assertRequest(
                after,
                before.requestId,
                before.order,
                before.result,
                before.market,
                before.feePolicySnapshot,
                before.ledgerTransactionId,
                before.occurredAt,
            )
        }
    }

    private fun assertWriteFailureBlocked(
        target: String,
        afterWrite: Boolean,
        error: Boolean,
        rejection: Boolean,
    ) {
        val state = OrderAdmissionState(listOf(balance(1_000_000_000)), calculator)
        val previousAccepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, "previous-accepted"))
        val rejectedInput = order("previous-rejected").copy(orderType = OrderType.MARKET)
        val previousRejected = OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION)
        assertEquals(previousRejected, admit(state, "previous-rejected", rejectedInput))
        val before = state.snapshot()
        val input = order("failing").let { if (rejection) it.copy(timeInForce = TimeInForce.IOC) else it }
        val failure = if (error) AssertionError("$target 쓰기 Error") else IllegalStateException("$target 쓰기 실패")
        failOnWrite(state, target, afterWrite, failure)

        assertSame(failure, assertFailsWith<Throwable> { admit(state, "failing", input) })
        assertTrue(state.isBlocked)
        assertFailsWith<IllegalStateException> { admit(state, "new", order("new")) }
        assertFailsWith<IllegalStateException> { admit(state, "failing", input) }
        assertFailsWith<IllegalStateException> { admit(state, "previous-accepted") }
        assertFailsWith<IllegalStateException> { admit(state, "previous-rejected", rejectedInput) }
        assertFailsWith<IllegalStateException> { admit(state, "previous-accepted", order().copy(quantity = Quantity(400_000_000))) }
        assertFailsWith<IllegalStateException> { admit(state, "") }
        assertFailsWith<IllegalStateException> { admit(state, "invalid", order().copy(orderId = OrderId("bad\u0000id"))) }
        assertFailsWith<IllegalStateException> { legacyAdmit(state, order("legacy")) }
        assertFailsWith<IllegalStateException> { state.snapshot() }
        assertFailsWith<IllegalStateException> { state.pendingOrders(market.marketId) }
        assertFailsWith<IllegalStateException> { state.hasRequest(user, "previous-accepted") }
        assertFailsWith<IllegalStateException> { state.hasRequest(user, "previous-rejected") }
        assertFailsWith<IllegalStateException> { state.hasRequest(user, "missing") }
        assertFailsWith<IllegalStateException> { state.hasRequest(UserId("other-user"), "previous-accepted") }
        // 오류 뒤 내부 상태의 rollback을 가정하지 않고, 전에 반환한 관찰값만 그대로 확인한다.
        assertEquals(listOf(balance(700_000_000, 300_000_000)), before.balances)
        assertEquals(1L, before.lastSequence)
        assertEquals(1, before.pendingOrders.size)
        assertBuyRecord(before.pendingOrders.single(), order(), balance(1_000_000_000), balance(700_000_000, 300_000_000))
        assertEquals(2, before.requests.size)
        assertRequest(before.requests.first(), "previous-accepted", order(), previousAccepted)
        assertRequest(before.requests.last(), "previous-rejected", rejectedInput, previousRejected)
    }

    private fun failOnWrite(
        state: OrderAdmissionState,
        target: String,
        afterWrite: Boolean,
        failure: Throwable,
    ) {
        if (target == "transactionIds" || target == "sourceEventIds") {
            @Suppress("UNCHECKED_CAST")
            val original = field(target).get(state) as MutableSet<String>
            val failing =
                object : MutableSet<String> by original {
                    override fun add(element: String): Boolean {
                        if (afterWrite) original.add(element)
                        throw failure
                    }
                }
            field(target).set(state, failing)
        } else {
            @Suppress("UNCHECKED_CAST")
            val original = field(target).get(state) as MutableMap<Any, Any>
            val failing =
                object : MutableMap<Any, Any> by original {
                    override fun put(
                        key: Any,
                        value: Any,
                    ): Any? {
                        if (afterWrite) original[key] = value
                        throw failure
                    }
                }
            field(target).set(state, failing)
        }
    }

    // 새 입금 기능 대신 테스트 준비에서만 같은 상태 객체의 잔고를 기존 credit 결과로 바꾼다.
    private fun creditOwnedBalance(
        state: OrderAdmissionState,
        amount: Amount,
    ) {
        @Suppress("UNCHECKED_CAST")
        val balances = field("balances").get(state) as MutableMap<Any, Balance>
        val owned = balances.entries.single { it.value.userId == user && it.value.assetId == usdt }
        balances[owned.key] = owned.value.credit(amount)
    }

    private fun usedIds(
        state: OrderAdmissionState,
        name: String,
    ): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return (field(name).get(state) as Set<String>).toSet()
    }

    private fun field(name: String) = OrderAdmissionState::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun order(id: String = "order-1") =
        OrderAdmissionInput(
            market.marketId,
            OrderId(id),
            user,
            Side.BUY,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(100_000_000),
            Quantity(300_000_000),
        )

    private fun balance(
        available: Long,
        hold: Long = 0,
    ) = Balance(user, usdt, Amount(available), Amount(hold))

    private fun admit(
        state: OrderAdmissionState,
        requestId: String,
        input: OrderAdmissionInput = order(),
        definition: MarketDefinition = market,
        appliedPolicy: TradingFeePolicySnapshot = policy,
        ledgerId: String = "ledger-${input.orderId.value}",
        at: Instant = time,
    ) = state.admit(requestId, input, definition, appliedPolicy, ledgerId, at)

    private fun legacyAdmit(
        state: OrderAdmissionState,
        input: OrderAdmissionInput,
    ) = state.admit(input, market, policy, "ledger-${input.orderId.value}", time)
}

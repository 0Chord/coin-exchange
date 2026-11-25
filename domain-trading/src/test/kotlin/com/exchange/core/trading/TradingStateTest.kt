package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide.CREDIT
import com.exchange.core.ledger.LedgerPostingSide.DEBIT
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingStateFixtures.admit
import com.exchange.core.trading.TradingStateFixtures.balance
import com.exchange.core.trading.TradingStateFixtures.btc
import com.exchange.core.trading.TradingStateFixtures.buyer
import com.exchange.core.trading.TradingStateFixtures.fund
import com.exchange.core.trading.TradingStateFixtures.market
import com.exchange.core.trading.TradingStateFixtures.order
import com.exchange.core.trading.TradingStateFixtures.seller
import com.exchange.core.trading.TradingStateFixtures.state
import com.exchange.core.trading.TradingStateFixtures.time
import com.exchange.core.trading.TradingStateFixtures.usdt
import com.exchange.core.trading.TradingStateFixtures.zeroPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 접수·매칭·정산 상태를 연결한다. 정상 상태를 내부 map에 주입하지 않는다. */
class TradingStateTest {
    @Test
    fun `상대 없는 SELL은 자금과 예약을 유지하며 대기에서 주문장과 완료로 한 번 이동한다`() {
        val state = state()
        fund(state, seller, btc, 2)
        val input = order("S1", seller, Side.SELL, 90, 1)
        val accepted = admit(state, input)
        val admitted = state.snapshot()
        val executionTime = time.plusSeconds(1)

        val completion = state.executePending(accepted.record.sequence, executionTime)
        val completed = state.snapshot()

        assertSame(accepted.record, completion.admission)
        assertEquals("EXECUTE:v1:1", completion.executionId)
        assertEquals(executionTime, completion.occurredAt)
        assertEquals(admitted.balances, completed.balances)
        assertEquals(listOf(balance(seller, btc, 1, 1)), completed.balances)
        assertEquals(admitted.reservations, completed.reservations)
        assertEquals(emptyList(), completed.pendingOrders)
        assertEquals(listOf(completion), completed.completions)
        assertEquals(listOf(completion.matchingResult), completed.matchingResults)
        assertEquals(
            listOf(OrderEnteredBook(market.marketId, 1, input.orderId, seller, Side.SELL, Price(90), Quantity(1))),
            completion.matchingResult.events,
        )
        assertEquals(emptyList(), completion.settlement.settlements)
        assertEquals(0, completed.ledgerTransactions.count { it.transactionType == LedgerTransactionType.SETTLEMENT })
        assertEquals(admitted.ledgerTransactions, completed.ledgerTransactions)
        val resting = state.book(market.marketId).single()
        assertEquals(input.orderId, resting.orderId)
        assertEquals(seller, resting.userId)
        assertEquals(Side.SELL, resting.side)
        assertEquals(Price(90), resting.price)
        assertEquals(Quantity(1), resting.originalQuantity)
        assertEquals(Quantity(1), resting.remainingQuantity)
        assertEquals(1L, state.lastEngineSequence(market.marketId))
        assertFalse(state.isBlocked)

        assertSame(completion, state.executePending(accepted.record.sequence, time.plusSeconds(99)))
        assertEquals(completed, state.snapshot())
        assertEquals(listOf(resting), state.book(market.marketId))
        assertEquals(1L, state.lastEngineSequence(market.marketId))
    }

    @Test
    fun `두 maker 체결은 실제 잔고715 예약100 BTC2와 BUY 주문장 잔량1을 함께 반영한다`() {
        val example = twoMakerExample()
        val state = example.state
        val before = state.snapshot()
        val executionTime = time.plusSeconds(3)

        val completion = state.executePending(example.buy.record.sequence, executionTime)
        val after = state.snapshot()

        assertEquals(3L, example.buy.record.sequence)
        assertEquals(setOf(balance(buyer, usdt, 700, 300), balance(seller, btc, 0, 2)), before.balances.toSet())
        assertEquals(
            setOf(balance(buyer, usdt, 715, 100), balance(buyer, btc, 2), balance(seller, btc, 0), balance(seller, usdt, 185)),
            after.balances.toSet(),
        )
        assertEquals(listOf(3L, 4L, 5L), completion.matchingResult.events.map { it.engineSequence })
        assertEquals(
            listOf(
                TradeExecuted(
                    market.marketId,
                    3,
                    example.sell1.record.candidate.order.orderId,
                    example.input.orderId,
                    seller,
                    buyer,
                    Side.BUY,
                    Price(90),
                    Quantity(1),
                ),
                TradeExecuted(
                    market.marketId,
                    4,
                    example.sell2.record.candidate.order.orderId,
                    example.input.orderId,
                    seller,
                    buyer,
                    Side.BUY,
                    Price(95),
                    Quantity(1),
                ),
                OrderEnteredBook(market.marketId, 5, example.input.orderId, buyer, Side.BUY, Price(100), Quantity(1)),
            ),
            completion.matchingResult.events,
        )
        assertEquals(3L, completion.matchingResult.firstEngineSequence)
        assertEquals(5L, completion.matchingResult.lastEngineSequence)
        assertEquals(5L, state.lastEngineSequence(market.marketId))
        assertEquals(
            SubmitOrderCommand(
                market.marketId,
                example.input.orderId,
                buyer,
                Side.BUY,
                example.input.orderType,
                example.input.timeInForce,
                Price(100),
                Quantity(3),
            ),
            completion.matchingResult.command,
        )
        assertEquals(3, after.reservations.size)
        assertEquals(
            setOf(example.sell1.record.candidate.order.orderId, example.sell2.record.candidate.order.orderId, example.input.orderId),
            after.reservations.map { it.orderId }.toSet(),
        )
        val buyReservation = after.reservations.single { it.orderId == example.input.orderId }
        assertEquals(Quantity(1), buyReservation.remainingQuantity)
        assertEquals(Amount(100), buyReservation.remainingAmount)
        assertEquals(OrderReservationStatus.ACTIVE, buyReservation.status)
        val sellReservations = after.reservations.filter { it.side == Side.SELL }
        assertEquals(2, sellReservations.size)
        assertEquals(
            setOf(example.sell1.record.candidate.order.orderId, example.sell2.record.candidate.order.orderId),
            sellReservations.map { it.orderId }.toSet(),
        )
        sellReservations.forEach {
            assertEquals(Quantity.ZERO, it.remainingQuantity)
            assertEquals(Amount.ZERO, it.remainingAmount)
            assertEquals(OrderReservationStatus.SETTLED, it.status)
        }
        val resting = state.book(market.marketId).single()
        assertEquals(example.input.orderId, resting.orderId)
        assertEquals(Quantity(3), resting.originalQuantity)
        assertEquals(Quantity(1), resting.remainingQuantity)
        assertEquals(emptyList(), after.pendingOrders)
        assertEquals(3, after.completions.size)
        assertEquals(3, after.matchingResults.size)
        assertSame(completion, after.completions.last())
        assertSame(completion.matchingResult, after.matchingResults.last())
        assertEquals(3L, after.lastAdmissionSequence)
        assertEquals(
            listOf(
                LedgerTransactionType.OPENING,
                LedgerTransactionType.OPENING,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.SETTLEMENT,
                LedgerTransactionType.SETTLEMENT,
            ),
            after.ledgerTransactions.map { it.transactionType },
        )
        before.ledgerTransactions.forEachIndexed { index, transaction -> assertSame(transaction, after.ledgerTransactions[index]) }
        assertEquals(listOf(3L, 4L), completion.settlement.settlements.map { it.execution.engineSequence })
        val settlementIds =
            listOf(
                "SETTLEMENT:v1:g-vDKCVwQ6mt0t1hw3lYL7amsJsrH-kDq5PqpXy85hQ",
                "SETTLEMENT:v1:TmbW_fQXcNhOewi7RiUrnt2AVcIuPLESBGHraFBIxkU",
            )
        assertEquals(settlementIds, completion.settlement.settlements.map { it.execution.ledgerTransactionId })
        assertEquals(settlementIds, completion.settlement.settlements.map { it.transaction.ledgerTransactionId })
        assertEquals(
            settlementIds,
            after.ledgerTransactions.filter { it.transactionType == LedgerTransactionType.SETTLEMENT }.map { it.ledgerTransactionId },
        )
        completion.settlement.settlements.forEachIndexed { index, settlement ->
            val price = listOf(90L, 95L)[index]
            assertEquals(executionTime, settlement.execution.occurredAt)
            assertEquals(executionTime, settlement.transaction.occurredAt)
            assertEquals("MATCHING:BTC-USDT:${index + 3}", settlement.transaction.sourceEventId)
            assertEquals(
                listOf(
                    LedgerPosting("USER:S:BTC:HOLD", btc, DEBIT, Amount(1)),
                    LedgerPosting("USER:S:USDT:AVAILABLE", usdt, CREDIT, Amount(price)),
                    LedgerPosting("USER:B:USDT:HOLD", usdt, DEBIT, Amount(price)),
                    LedgerPosting("USER:B:USDT:HOLD", usdt, DEBIT, Amount(100 - price)),
                    LedgerPosting("USER:B:USDT:AVAILABLE", usdt, CREDIT, Amount(100 - price)),
                    LedgerPosting("USER:B:BTC:AVAILABLE", btc, CREDIT, Amount(1)),
                ),
                settlement.transaction.postings,
            )
            assertSame(settlement.transaction, after.ledgerTransactions[index + 5])
        }
        assertFalse(state.isBlocked)
    }

    @Test
    fun `체결 뒤 최초 요청 재전송은 접수 당시700 예약300을 재사용하고 현재715 예약100을 유지한다`() {
        val example = twoMakerExample()
        val state = example.state
        state.executePending(example.buy.record.sequence, time.plusSeconds(3))
        val before = state.snapshot()

        val replay =
            state.admit(
                "request-B1",
                example.input,
                market,
                zeroPolicy.copy(scheduleVersion = 999),
                "unused-ledger",
                time.plusSeconds(50),
            )

        assertSame(example.buy, replay)
        assertEquals(balance(buyer, usdt, 700, 300), example.buy.record.candidate.nextBalance)
        assertEquals(Amount(300), example.buy.record.candidate.reservation.remainingAmount)
        assertEquals(Quantity(3), example.buy.record.candidate.reservation.remainingQuantity)
        assertEquals(3L, example.buy.record.sequence)
        assertTrue(state.hasRequest(buyer, "request-B1"))
        assertEquals(before, state.snapshot())
        assertEquals(balance(buyer, usdt, 715, 100), state.snapshot().balances.single { it.userId == buyer && it.assetId == usdt })
        assertEquals(
            Amount(100),
            state
                .snapshot()
                .reservations
                .single { it.orderId == example.input.orderId }
                .remainingAmount,
        )
    }

    @Test
    fun `완료 재실행은 새 시각을 무시하고 원장 주문장 순번 결과를 다시 만들지 않는다`() {
        val example = twoMakerExample()
        val state = example.state
        val firstTime = time.plusSeconds(3)
        val first = state.executePending(example.buy.record.sequence, firstTime)
        val before = state.snapshot()
        val book = state.book(market.marketId)

        val replay = state.executePending(example.buy.record.sequence, time.plusSeconds(99))

        assertSame(first, replay)
        assertEquals("EXECUTE:v1:3", replay.executionId)
        assertEquals(firstTime, replay.occurredAt)
        assertEquals(listOf(firstTime, firstTime), replay.settlement.settlements.map { it.transaction.occurredAt })
        assertEquals(before, state.snapshot())
        assertEquals(book, state.book(market.marketId))
        assertEquals(5L, state.lastEngineSequence(market.marketId))
    }

    @Test
    fun `완료한 주문도 새 요청과 요청 없는 재접수를 중복 거절하며 최초 이력을 유지한다`() {
        val example = twoMakerExample()
        val state = example.state
        state.executePending(example.buy.record.sequence, time.plusSeconds(3))
        val before = state.snapshot()

        val newRequest = state.admit("new-request", example.input, market, zeroPolicy, "unused-one", time)
        val withoutRequest = state.admit(example.input, market, zeroPolicy, "unused-two", time)
        val after = state.snapshot()

        assertEquals(OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER), newRequest)
        assertEquals(OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER), withoutRequest)
        assertEquals(before.copy(requests = after.requests), after)
        assertEquals(before.requests.size + 1, after.requests.size)
        assertEquals(before.requests, after.requests.dropLast(1))
        val remembered = after.requests.last()
        assertEquals("new-request", remembered.requestId)
        assertEquals(example.input, remembered.order)
        assertSame(newRequest, remembered.result)
        assertTrue(state.hasRequest(buyer, "new-request"))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `다른 마켓의 신규 주문은 최신715를 예약하고 앞 마켓의 hold100을 보존한다`() {
        val example = twoMakerExample()
        val state = example.state
        state.executePending(example.buy.record.sequence, time.plusSeconds(3))
        val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 0)

        val accepted = admit(state, order("SUI-B1", buyer, Side.BUY, 715, 1, sui), sui)
        val completion = state.executePending(accepted.record.sequence, time.plusSeconds(4))

        assertEquals(4L, accepted.record.sequence)
        assertEquals(balance(buyer, usdt, 715, 100), accepted.record.candidate.previousBalance)
        assertEquals(balance(buyer, usdt, 0, 815), state.snapshot().balances.single { it.userId == buyer && it.assetId == usdt })
        assertEquals(
            Amount(100),
            state
                .snapshot()
                .reservations
                .single { it.orderId == example.input.orderId }
                .remainingAmount,
        )
        assertEquals(
            Amount(715),
            state
                .snapshot()
                .reservations
                .single { it.orderId == accepted.record.candidate.order.orderId }
                .remainingAmount,
        )
        assertEquals(Quantity(1), state.book(market.marketId).single().remainingQuantity)
        assertEquals(Quantity(1), state.book(sui.marketId).single().remainingQuantity)
        assertEquals(emptyList(), completion.settlement.settlements)
        assertEquals(5L, state.lastEngineSequence(market.marketId))
        assertEquals(1L, state.lastEngineSequence(sui.marketId))
    }

    @Test
    fun `전량 체결은 양쪽 예약을 정산 완료하고 taker를 주문장에 넣지 않는다`() {
        val state = state()
        fund(state, buyer, usdt, 1_000)
        val preparation = fund(state, seller, btc, 1)
        val maker = admit(state, order("S1", seller, Side.SELL, 90, 1))
        state.executePending(maker.record.sequence, time.plusSeconds(1))
        val input = order("B1", buyer, Side.BUY, 100, 1)
        val taker = admit(state, input)

        val completion = state.executePending(taker.record.sequence, time.plusSeconds(2))
        val beforeReplay = state.snapshot()

        assertEquals(
            listOf(
                TradeExecuted(
                    market.marketId,
                    2,
                    maker.record.candidate.order.orderId,
                    input.orderId,
                    seller,
                    buyer,
                    Side.BUY,
                    Price(90),
                    Quantity(1),
                ),
            ),
            completion.matchingResult.events,
        )
        assertEquals(
            setOf(balance(buyer, usdt, 910), balance(buyer, btc, 1), balance(seller, btc, 0), balance(seller, usdt, 90)),
            beforeReplay.balances.toSet(),
        )
        assertEquals(2, beforeReplay.reservations.size)
        assertEquals(setOf(maker.record.candidate.order.orderId, input.orderId), beforeReplay.reservations.map { it.orderId }.toSet())
        beforeReplay.reservations.forEach {
            assertEquals(OrderReservationStatus.SETTLED, it.status)
            assertEquals(Amount.ZERO, it.remainingAmount)
            assertEquals(Quantity.ZERO, it.remainingQuantity)
        }
        assertEquals(emptyList(), state.book(market.marketId))
        assertEquals(emptyList(), beforeReplay.pendingOrders)
        assertEquals(2L, state.lastEngineSequence(market.marketId))
        assertSame(maker.record, state.executePending(maker.record.sequence, time.plusSeconds(99)).admission)
        assertEquals(preparation.copy(alreadyPrepared = true), fund(state, seller, btc, 1))
        assertFailsWith<OpeningBalanceConflictException> { fund(state, seller, btc, 1, "new-fund") }
        assertEquals(beforeReplay, state.snapshot())
        assertFalse(state.isBlocked)
    }

    @Test
    fun `같은 사용자의 두 SELL과 BUY는 공유 잔고 변경을 모두 이어 받아 자금을 보존한다`() {
        val state = state()
        fund(state, buyer, usdt, 1_000)
        fund(state, buyer, btc, 2)
        val sell1 = admit(state, order("S1", buyer, Side.SELL, 90, 1))
        state.executePending(sell1.record.sequence, time.plusSeconds(1))
        val sell2 = admit(state, order("S2", buyer, Side.SELL, 95, 1))
        state.executePending(sell2.record.sequence, time.plusSeconds(2))
        val buy = admit(state, order("B1", buyer, Side.BUY, 100, 2))
        assertEquals(setOf(balance(buyer, usdt, 800, 200), balance(buyer, btc, 0, 2)), state.snapshot().balances.toSet())

        val completion = state.executePending(buy.record.sequence, time.plusSeconds(3))

        assertEquals(setOf(balance(buyer, usdt, 1_000), balance(buyer, btc, 2)), state.snapshot().balances.toSet())
        assertEquals(2, completion.settlement.settlements.size)
        assertEquals(emptyList(), state.book(market.marketId))
        val reservations = state.snapshot().reservations
        assertEquals(3, reservations.size)
        assertEquals(
            setOf(sell1.record.candidate.order.orderId, sell2.record.candidate.order.orderId, buy.record.candidate.order.orderId),
            reservations.map { it.orderId }.toSet(),
        )
        reservations.forEach {
            assertEquals(OrderReservationStatus.SETTLED, it.status)
            assertEquals(Amount.ZERO, it.remainingAmount)
            assertEquals(Quantity.ZERO, it.remainingQuantity)
        }
        assertFalse(state.isBlocked)
    }

    @Test
    fun `없는 순번과 같은 마켓의 대기 건너뛰기는 변경 없이 거절하고 정상 순서로 계속한다`() {
        val state = state()
        fund(state, seller, btc, 2)
        val first = admit(state, order("S1", seller, Side.SELL, 90, 1))
        val second = admit(state, order("S2", seller, Side.SELL, 95, 1))
        val before = state.snapshot()

        listOf(0L, -1L, 999L, second.record.sequence).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { state.executePending(invalid, time) }
            assertEquals(before, state.snapshot())
            assertEquals(emptyList(), state.book(market.marketId))
            assertEquals(0L, state.lastEngineSequence(market.marketId))
            assertFalse(state.isBlocked)
        }
        state.executePending(first.record.sequence, time.plusSeconds(1))
        state.executePending(second.record.sequence, time.plusSeconds(2))

        assertEquals(emptyList(), state.snapshot().pendingOrders)
        assertEquals(listOf(first.record, second.record), state.snapshot().completions.map { it.admission })
        assertEquals(2L, state.lastEngineSequence(market.marketId))
    }

    @Test
    fun `다른 마켓은 전체 승인 순서와 관계없이 실행하되 마켓 안의 대기 순서는 보존한다`() {
        val state = state()
        val sui = MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), usdt, 0)
        fund(state, seller, btc, 2)
        fund(state, seller, sui.baseAssetId, 1)
        val first = admit(state, order("BTC-S1", seller, Side.SELL, 90, 1))
        val otherMarket = admit(state, order("SUI-S1", seller, Side.SELL, 5, 1, sui), sui)
        val last = admit(state, order("BTC-S2", seller, Side.SELL, 95, 1))

        state.executePending(otherMarket.record.sequence, time.plusSeconds(1))

        assertEquals(listOf(first.record, last.record), state.snapshot().pendingOrders)
        assertEquals(0L, state.lastEngineSequence(market.marketId))
        assertEquals(1L, state.lastEngineSequence(sui.marketId))
        assertFailsWith<IllegalArgumentException> { state.executePending(last.record.sequence, time) }
        assertFalse(state.isBlocked)
        state.executePending(first.record.sequence, time.plusSeconds(2))
        state.executePending(last.record.sequence, time.plusSeconds(3))
        assertEquals(listOf(otherMarket.record, first.record, last.record), state.snapshot().completions.map { it.admission })
    }

    @Test
    fun `정상 거절은 승인 순번과 실행 대기를 만들지 않고 뒤의 정상 주문이 실행된다`() {
        val state = state()
        fund(state, buyer, usdt, 100)

        val rejected = state.admit(order("large", buyer, Side.BUY, 100, 2), market, zeroPolicy, "unused-ledger", time)

        assertIs<OrderAdmissionState.Result.Rejected>(rejected)
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, rejected.reason)
        assertEquals(0L, state.snapshot().lastAdmissionSequence)
        assertEquals(emptyList(), state.snapshot().pendingOrders)
        assertFailsWith<IllegalArgumentException> { state.executePending(1, time) }
        val accepted =
            assertIs<OrderAdmissionState.Result.Accepted>(
                state.admit(order("small", buyer, Side.BUY, 100, 1), market, zeroPolicy, "reserve-small", time),
            )
        assertEquals(1L, accepted.record.sequence)
        state.executePending(accepted.record.sequence, time.plusSeconds(1))
        assertEquals(1, state.snapshot().completions.size)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `조회 목록과 결과의 중첩 목록으로 소유 상태를 바꿀 수 없고 과거 조회는 고정된다`() {
        val state = state()
        fund(state, seller, btc, 2)
        val maker = admit(state, order("S1", seller, Side.SELL, 90, 1))
        val completion = state.executePending(maker.record.sequence, time.plusSeconds(1))
        fund(state, buyer, usdt, 1_000)
        val buy = admit(state, order("B1", buyer, Side.BUY, 100, 1))
        val before = state.snapshot()
        val book = state.book(market.marketId)

        assertFailsWith<UnsupportedOperationException> { (before.balances as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.reservations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.pendingOrders as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.completions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.ledgerTransactions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.matchingResults as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (book as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (completion.matchingResult.events as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (before.ledgerTransactions.first().postings as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (buy.record.candidate.reserveTransaction.postings as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (completion.settlement.settlements as MutableList).clear() }

        state.executePending(buy.record.sequence, time.plusSeconds(2))

        assertEquals(listOf(buy.record), before.pendingOrders)
        assertEquals(1, before.completions.size)
        assertEquals(1, before.matchingResults.size)
        assertEquals(2, before.reservations.size)
        assertEquals(4, before.ledgerTransactions.size)
        assertEquals(Quantity(1), book.single().remainingQuantity)
        assertEquals(balance(seller, btc, 1, 1), before.balances.single { it.userId == seller && it.assetId == btc })
        assertEquals(2, state.snapshot().completions.size)
        assertEquals(emptyList(), state.book(market.marketId))
    }

    @Test
    fun `체결로 처음 받은 BTC를 모두 팔아 0이 돼도 새 개발 자금을 준비할 수 없다`() {
        val state = state()
        fund(state, buyer, usdt, 1_000)
        fund(state, seller, btc, 1)
        val firstSell = admit(state, order("S1", seller, Side.SELL, 90, 1))
        state.executePending(firstSell.record.sequence, time.plusSeconds(1))
        val firstBuy = admit(state, order("B1", buyer, Side.BUY, 100, 1))
        state.executePending(firstBuy.record.sequence, time.plusSeconds(2))
        assertEquals(balance(buyer, btc, 1), state.snapshot().balances.single { it.userId == buyer && it.assetId == btc })
        assertFalse(state.snapshot().preparations.any { it.result.opening.userId == buyer && it.result.opening.assetId == btc })
        val buyBack = admit(state, order("S2", seller, Side.BUY, 90, 1))
        state.executePending(buyBack.record.sequence, time.plusSeconds(3))
        val sellBack = admit(state, order("B2", buyer, Side.SELL, 90, 1))

        state.executePending(sellBack.record.sequence, time.plusSeconds(4))
        val before = state.snapshot()

        assertEquals(balance(buyer, btc, 0), before.balances.single { it.userId == buyer && it.assetId == btc })
        assertEquals(balance(buyer, usdt, 1_000), before.balances.single { it.userId == buyer && it.assetId == usdt })
        assertFailsWith<OpeningBalanceConflictException> { fund(state, buyer, btc, 1, "new-received-btc") }
        assertEquals(before, state.snapshot())
        assertEquals(emptyList(), state.book(market.marketId))
        assertFalse(state.isBlocked)
    }

    private data class TwoMakerExample(
        val state: TradingState,
        val sell1: OrderAdmissionState.Result.Accepted,
        val sell2: OrderAdmissionState.Result.Accepted,
        val input: OrderAdmissionInput,
        val buy: OrderAdmissionState.Result.Accepted,
    )

    private fun twoMakerExample(): TwoMakerExample {
        val state = state()
        fund(state, buyer, usdt, 1_000)
        fund(state, seller, btc, 2)
        val sell1 = admit(state, order("S1", seller, Side.SELL, 90, 1))
        state.executePending(sell1.record.sequence, time.plusSeconds(1))
        val sell2 = admit(state, order("S2", seller, Side.SELL, 95, 1))
        state.executePending(sell2.record.sequence, time.plusSeconds(2))
        val input = order("B1", buyer, Side.BUY, 100, 3)
        return TwoMakerExample(state, sell1, sell2, input, admit(state, input))
    }
}

package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MatchingResultStateTest : MatchingResultTestFixture() {
    @Test
    fun `체결 두 건과 잔량 한 건을 모두 확인한 뒤 결과 하나로 등록한다`() {
        val state = prepared()
        val buy = buy()

        val result = registerBuy(state)

        assertEquals("C3", result.commandId)
        assertEquals(buy, result.command)
        assertEquals(3, result.expectedEventCount)
        assertEquals(3L, result.firstEngineSequence)
        assertEquals(5L, result.lastEngineSequence)
        assertEquals(buyEvents(), result.events)
        assertSame(result, state.find(market, "C3"))
        assertEquals(listOf("C1", "C2", "C3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)
        assertNull(state.find(otherMarket, "C3"))
        assertNull(state.find(market, "unknown"))
    }

    @Test
    fun `체결이나 잔량이 빠지면 완료로 등록하지 않고 기존 순번을 유지한다`() {
        val incomplete = listOf(buyEvents().filterIndexed { index, _ -> index != 1 }, buyEvents().dropLast(1), emptyList())

        incomplete.forEach { events ->
            val state = prepared()

            assertRejectedWithoutChanges(state) { state.register("C3", buy(), 3, 3, 5, events) }
            assertNull(state.find(market, "C3"))
            assertEquals(mapOf(market to 2L), state.snapshot().lastEngineSequences)
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `새 마켓의 체결 없는 매수와 매도는 원수량 전부를 순번 1의 잔량으로 등록한다`() {
        listOf(Side.BUY, Side.SELL).forEach { side ->
            val state = MatchingResultState()
            val command = submit("O1", "U-O1", side, 100, 3)
            val expected = listOf(entered(1, "O1", "U-O1", side, 100, 3))

            val result = state.register("C1", command, 1, 1, 1, expected)

            assertEquals(expected, result.events)
            assertEquals(command, result.command)
            assertEquals(listOf(result), state.snapshot().results)
            assertEquals(mapOf(market to 1L), state.snapshot().lastEngineSequences)
        }
    }

    @Test
    fun `한 건 또는 여러 건으로 전량 체결되면 잔량 이벤트 없이 등록한다`() {
        val singleState = MatchingResultState()
        singleState.register("C1", submit("S1", "U-S1", Side.SELL, 90, 1), 1, 1, 1, listOf(entered(1, "S1", "U-S1", Side.SELL, 90, 1)))
        val singleTrade = trade(2, "S1", "B1", "U-S1", "U-B1", Side.BUY, 90, 1)

        val single = singleState.register("C2", buy().copy(quantity = Quantity(1)), 1, 2, 2, listOf(singleTrade))

        assertEquals(listOf(singleTrade), single.events)
        assertEquals(mapOf(market to 2L), singleState.snapshot().lastEngineSequences)

        val multipleState = prepared()
        val expected = listOf(firstTrade(), secondTrade())
        val multiple = multipleState.register("C3", buy().copy(quantity = Quantity(2)), 2, 3, 4, expected)

        assertEquals(expected, multiple.events)
        assertEquals(mapOf(market to 4L), multipleState.snapshot().lastEngineSequences)
    }

    @Test
    fun `매수와 매도는 지정가와 같은 가격의 전량 체결을 허용한다`() {
        listOf(Side.BUY, Side.SELL).forEach { side ->
            val state = MatchingResultState()
            val command = submit("T1", "U-T1", side, 100, 1)
            val event = trade(1, "M1", "T1", "U-M1", "U-T1", side, 100, 1)

            assertEquals(listOf(event), state.register("C1", command, 1, 1, 1, listOf(event)).events)
            assertEquals(mapOf(market to 1L), state.snapshot().lastEngineSequences)
        }
    }

    @Test
    fun `매도 체결가는 지정가 이상이어야 하며 높은 체결가는 허용한다`() {
        val command = submit("T1", "U-T1", Side.SELL, 100, 1)
        val state = MatchingResultState()
        val belowLimit = trade(1, "M1", "T1", "U-M1", "U-T1", Side.SELL, 99, 1)

        assertRejectedWithoutChanges(state) { state.register("C1", command, 1, 1, 1, listOf(belowLimit)) }
        assertNull(state.find(market, "C1"))

        val aboveLimit = belowLimit.copy(price = Price(101))
        assertEquals(listOf(aboveLimit), state.register("C1", command, 1, 1, 1, listOf(aboveLimit)).events)
    }

    @Test
    fun `예상 개수와 범위는 양수이며 실제 연속 순번과 정확히 일치해야 한다`() {
        val events = buyEvents()
        val cases =
            listOf(
                Batch(0, 3, 5, events),
                Batch(-1, 3, 5, events),
                Batch(2, 3, 5, events),
                Batch(4, 3, 5, events),
                Batch(3, 0, 2, events),
                Batch(3, -1, 1, events),
                Batch(3, 3, 0, events),
                Batch(3, 3, 4, events),
                Batch(3, 3, 6, events),
                Batch(3, 5, 3, events),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade().copy(engineSequence = 5), remainder().copy(engineSequence = 4))),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade().copy(engineSequence = 3), remainder())),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade().copy(engineSequence = 0), remainder())),
            )

        cases.forEach { batch ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { register(state, buy(), batch) }
            assertNull(state.find(market, "C3"))
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `빈 내부 명령 ID와 지원하지 않는 제출 조건은 상태 변경 전에 거절한다`() {
        listOf("", "  ").forEach { id ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register(id, buy(), 3, 3, 5, buyEvents()) }
            assertEquals(buyEvents(), registerBuy(state).events)
        }
        listOf(buy().copy(orderType = OrderType.MARKET), buy().copy(timeInForce = TimeInForce.IOC)).forEach { command ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register("C3", command, 3, 3, 5, buyEvents()) }
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `체결의 마켓 taker 방향 수량과 지정가 위반을 각각 거절한다`() {
        val invalidTrades =
            listOf(
                firstTrade().copy(marketId = otherMarket),
                firstTrade().copy(takerOrderId = OrderId("different")),
                firstTrade().copy(takerUserId = UserId("different")),
                firstTrade().copy(side = Side.SELL),
                firstTrade().copy(makerOrderId = OrderId("B1")),
                firstTrade().copy(price = Price(101)),
                firstTrade().copy(quantity = Quantity.ZERO),
                firstTrade().copy(quantity = Quantity(2)),
            )

        invalidTrades.forEach { event ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register("C3", buy(), 3, 3, 5, listOf(event, secondTrade(), remainder())) }
            assertNull(state.find(market, "C3"))
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `maker와 taker의 사용자가 같아도 서로 다른 주문이면 새 거래 정책으로 거절하지 않는다`() {
        val state = prepared()
        val events = listOf(firstTrade().copy(makerUserId = UserId("U-B1")), secondTrade(), remainder())

        assertEquals(events, state.register("C3", buy(), 3, 3, 5, events).events)
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `잔량의 마켓 주문 사용자 방향 가격과 양수 수량을 원본에 대조한다`() {
        val invalidRemainders =
            listOf(
                remainder().copy(marketId = otherMarket),
                remainder().copy(orderId = OrderId("different")),
                remainder().copy(userId = UserId("different")),
                remainder().copy(side = Side.SELL),
                remainder().copy(price = Price(99)),
                remainder().copy(remainingQuantity = Quantity.ZERO),
                remainder().copy(remainingQuantity = Quantity(2)),
            )

        invalidRemainders.forEach { event ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register("C3", buy(), 3, 3, 5, listOf(firstTrade(), secondTrade(), event)) }
            assertNull(state.find(market, "C3"))
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `체결과 잔량이 모두 양수여도 합이 원수량보다 적거나 많으면 거절한다`() {
        listOf(2L, 4L).forEach { quantity ->
            val state = prepared()
            val command = buy().copy(quantity = Quantity(quantity))

            assertRejectedWithoutChanges(state) { state.register("C3", command, 3, 3, 5, buyEvents()) }
            assertNull(state.find(market, "C3"))
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `수량 합이 같아도 잔량 뒤 체결과 두 잔량 및 취소 혼합을 허용하지 않는다`() {
        val invalidEvents =
            listOf(
                listOf(remainder().copy(engineSequence = 3), firstTrade().copy(engineSequence = 4), secondTrade().copy(engineSequence = 5)),
                listOf(firstTrade(), remainder().copy(engineSequence = 4), remainder()),
                listOf(firstTrade(), secondTrade(), OrderCancelled(market, 5, OrderId("B1"), UserId("U-B1"), Quantity(1))),
                listOf(firstTrade(), secondTrade(), OrderCancelRejected(market, 5, OrderId("B1"), UserId("U-B1"), "not found")),
            )

        invalidEvents.forEach { events ->
            val state = prepared()
            assertRejectedWithoutChanges(state) { state.register("C3", buy(), 3, 3, 5, events) }
            assertEquals(buyEvents(), registerBuy(state).events)
        }
    }

    @Test
    fun `동일한 전체 결과 재전송은 최초 객체를 반환하고 이력을 늘리지 않는다`() {
        val state = prepared()
        val original = registerBuy(state)
        val before = state.snapshot()

        val replay = state.register("C3", buy().copy(), 3, 3, 5, buyEvents().toMutableList())

        assertSame(original, replay)
        assertEquals(before, state.snapshot())
        assertSame(original, state.find(market, "C3"))
    }

    @Test
    fun `뒤에 다른 결과가 등록되어도 오래된 동일 결과를 최초 객체로 재사용한다`() {
        val state = prepared()
        val original = registerBuy(state)
        val cancellation = CancelOrderCommand(market, OrderId("B1"), UserId("U-B1"))
        val cancelled = OrderCancelled(market, 6, OrderId("B1"), UserId("U-B1"), Quantity(1))
        state.register("C4", cancellation, 1, 6, 6, listOf(cancelled))
        val before = state.snapshot()

        assertSame(original, registerBuy(state))
        assertEquals(before, state.snapshot())
        assertEquals(listOf("C1", "C2", "C3", "C4"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 6L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `같은 키의 원본 명령을 바꾸면 전체 내용 충돌이며 최초 결과를 보존한다`() {
        val changedCommands =
            listOf(
                buy().copy(orderId = OrderId("different")),
                buy().copy(userId = UserId("different")),
                buy().copy(side = Side.SELL),
                buy().copy(price = Price(101)),
                buy().copy(quantity = Quantity(4)),
                buy().copy(orderType = OrderType.MARKET),
                buy().copy(timeInForce = TimeInForce.IOC),
                CancelOrderCommand(market, OrderId("B1"), UserId("U-B1")),
            )

        changedCommands.forEach { command ->
            val state = prepared()
            val original = registerBuy(state)
            assertRejectedWithoutChanges(state, MatchingResultException.Reason.CONTENT_CONFLICT) {
                state.register("C3", command, 3, 3, 5, buyEvents())
            }
            assertSame(original, state.find(market, "C3"))
            assertSame(original, registerBuy(state))
        }
    }

    @Test
    fun `같은 키의 이벤트 의미 필드 순서 또는 메타데이터를 바꾸면 충돌이다`() {
        val changedBatches =
            listOf(
                Batch(2, 3, 5, buyEvents()),
                Batch(3, 2, 5, buyEvents()),
                Batch(3, 3, 6, buyEvents()),
                Batch(3, 3, 5, listOf(firstTrade().copy(makerUserId = UserId("different")), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade().copy(makerOrderId = OrderId("different")), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade().copy(price = Price(91)), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade().copy(quantity = Quantity(2)), secondTrade(), remainder())),
                Batch(3, 3, 5, listOf(secondTrade(), firstTrade(), remainder())),
                Batch(3, 3, 5, listOf(firstTrade(), secondTrade())),
            )

        changedBatches.forEach { batch ->
            val state = prepared()
            val original = registerBuy(state)
            assertRejectedWithoutChanges(state, MatchingResultException.Reason.CONTENT_CONFLICT) { register(state, buy(), batch) }
            assertSame(original, state.find(market, "C3"))
            assertSame(original, registerBuy(state))
        }
    }

    @Test
    fun `새 내부 ID로 같은 제출 주문을 등록하면 중복이며 순번을 소비하지 않는다`() {
        val state = prepared()
        registerBuy(state)
        val repeated = entered(6, "B1", "U-B1", Side.BUY, 100, 3)

        assertRejectedWithoutChanges(state, MatchingResultException.Reason.DUPLICATE_SUBMIT) {
            state.register("C9", buy(), 1, 6, 6, listOf(repeated))
        }
        assertNull(state.find(market, "C9"))
        val next = submit("B2", "U-B2", Side.BUY, 100, 3)
        val event = entered(6, "B2", "U-B2", Side.BUY, 100, 3)
        assertEquals(listOf(event), state.register("C4", next, 1, 6, 6, listOf(event)).events)
        assertEquals(mapOf(market to 6L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `다른 마켓은 같은 내부 명령 ID와 주문 ID를 순번 1부터 독립 등록한다`() {
        val state = prepared()
        val btc = registerBuy(state)
        val suiCommand = buy().copy(marketId = otherMarket)
        val suiEvent = entered(1, "B1", "U-B1", Side.BUY, 100, 3).copy(marketId = otherMarket)

        val sui = state.register("C3", suiCommand, 1, 1, 1, listOf(suiEvent))

        assertSame(btc, state.find(market, "C3"))
        assertSame(sui, state.find(otherMarket, "C3"))
        assertEquals(listOf(suiEvent), sui.events)
        assertEquals(listOf("C1", "C2", "C3", "C3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L, otherMarket to 1L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `마켓과 ID에 구분자가 있어도 명령 키와 제출 키가 충돌하지 않는다`() {
        val state = MatchingResultState()
        val firstMarket = MarketId("a:b")
        val secondMarket = MarketId("a")
        val firstCommand = submit("c", "U1", Side.BUY, 100, 1).copy(marketId = firstMarket)
        val secondCommand = submit("b:c", "U2", Side.BUY, 100, 1).copy(marketId = secondMarket)
        val firstEvent = entered(1, "c", "U1", Side.BUY, 100, 1).copy(marketId = firstMarket)
        val secondEvent = entered(1, "b:c", "U2", Side.BUY, 100, 1).copy(marketId = secondMarket)

        val first = state.register("c", firstCommand, 1, 1, 1, listOf(firstEvent))
        val second = state.register("b:c", secondCommand, 1, 1, 1, listOf(secondEvent))

        assertSame(first, state.find(firstMarket, "c"))
        assertSame(second, state.find(secondMarket, "b:c"))
        assertEquals(listOf(first, second), state.snapshot().results)
        assertEquals(mapOf(firstMarket to 1L, secondMarket to 1L), state.snapshot().lastEngineSequences)
    }

    @Test
    fun `신규 결과는 기존 범위와 겹치거나 다음 순번을 건너뛸 수 없다`() {
        listOf(2L, 4L).forEach { sequence ->
            val state = prepared()
            val command = submit("B2", "U-B2", Side.BUY, 100, 1)
            val event = entered(sequence, "B2", "U-B2", Side.BUY, 100, 1)

            assertRejectedWithoutChanges(state) { state.register("C3", command, 1, sequence, sequence, listOf(event)) }
            assertNull(state.find(market, "C3"))
            val valid = event.copy(engineSequence = 3)
            assertEquals(listOf(valid), state.register("C3", command, 1, 3, 3, listOf(valid)).events)
        }
        val empty = MatchingResultState()
        val command = submit("B1", "U-B1", Side.BUY, 100, 1)
        assertRejectedWithoutChanges(empty) {
            empty.register("C1", command, 1, 2, 2, listOf(entered(2, "B1", "U-B1", Side.BUY, 100, 1)))
        }
    }

    @Test
    fun `입력 목록을 바꾸거나 반환 목록 수정을 시도해도 등록 결과는 불변이다`() {
        val state = prepared()
        val input = buyEvents().toMutableList()
        val result = state.register("C3", buy(), 3, 3, 5, input)
        val snapshot = state.snapshot()

        input[0] = firstTrade().copy(price = Price(91))
        input.clear()
        assertFailsWith<UnsupportedOperationException> { (result.events as MutableList<MatchingEvent>).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.results as MutableList<MatchingCommandResult>).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.lastEngineSequences as MutableMap<MarketId, Long>)[market] = 999 }

        assertEquals(buyEvents(), result.events)
        assertEquals(buyEvents(), state.find(market, "C3")!!.events)
        assertEquals(snapshot, state.snapshot())
        assertEquals(mapOf(market to 5L), snapshot.lastEngineSequences)
    }

    @Test
    fun `이전 조회 복사본은 이후 결과 등록으로 변하지 않는다`() {
        val state = prepared()
        val earlier = state.snapshot()

        registerBuy(state)

        assertEquals(listOf("C1", "C2"), earlier.results.map { it.commandId })
        assertEquals(mapOf(market to 2L), earlier.lastEngineSequences)
        assertEquals(listOf("C1", "C2", "C3"), state.snapshot().results.map { it.commandId })
        assertEquals(mapOf(market to 5L), state.snapshot().lastEngineSequences)
    }
}

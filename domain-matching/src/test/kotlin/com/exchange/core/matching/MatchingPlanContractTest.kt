package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MatchingPlanContractTest {
    private val market = MarketId("BTC-USDT")
    private val otherMarket = MarketId("ETH-USDT")

    @Test
    fun `빈 새 마켓의 준비와 검증은 map과 주문 ID와 순번을 만들지 않는다`() {
        val engine = MatchingEngine()
        val command = submit("B1", Side.BUY, 100, 3)
        val empty = EngineSnapshot(emptyMap(), emptyMap(), emptyMap())

        val discarded = engine.prepareSubmit(command)
        engine.validate(discarded)
        assertEquals(command, discarded.command)
        assertEquals(listOf(entered(1, "B1", Side.BUY, 100, 3)), discarded.events)
        assertEquals(1L, discarded.firstEngineSequence)
        assertEquals(1L, discarded.lastEngineSequence)
        assertEquals(empty, snapshot(engine))

        val replacement = engine.prepareSubmit(command)
        engine.validate(replacement)
        assertEquals(empty, snapshot(engine))
        assertEquals(listOf(entered(1, "B1", Side.BUY, 100, 3)), engine.apply(replacement))
        assertEquals(
            expectedState(
                lastSequence = 1,
                seen = setOf("B1"),
                bids = listOf(order("B1", Side.BUY, 100, 3, 3)),
            ),
            snapshot(engine),
        )
    }

    @Test
    fun `가격이 교차하지 않는 계획은 상대 주문을 보존하고 자기 잔량만 대기시킨다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 101, 2))
        val before = snapshot(engine)

        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        engine.validate(plan)

        assertEquals(listOf(entered(2, "B1", Side.BUY, 100, 1)), plan.events)
        assertEquals(before, snapshot(engine))
        assertEquals(listOf(entered(2, "B1", Side.BUY, 100, 1)), engine.apply(plan))
        assertEquals(
            expectedState(
                lastSequence = 2,
                seen = setOf("S1", "B1"),
                bids = listOf(order("B1", Side.BUY, 100, 1, 1)),
                asks = listOf(order("S1", Side.SELL, 101, 2, 2)),
            ),
            snapshot(engine),
        )
    }

    @Test
    fun `사전 검증 뒤 같은 마켓에 정상 submit이 적용되면 다시 검사해 계획을 거절한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        engine.validate(plan)

        assertEquals(
            listOf(entered(2, "S2", Side.SELL, 110, 1)),
            engine.process(submit("S2", Side.SELL, 110, 1)),
        )
        val afterSubmit = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, afterSubmit)
        assertEquals(
            expectedState(
                lastSequence = 2,
                seen = setOf("S1", "S2"),
                asks = listOf(order("S1", Side.SELL, 100, 2, 2), order("S2", Side.SELL, 110, 1, 1)),
            ),
            snapshot(engine),
        )
    }

    @Test
    fun `같은 상태에서 만든 두 계획은 생성 순서로 예약하지 않고 먼저 적용한 뒤 나머지를 거절한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val before = snapshot(engine)

        val first = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        val second = engine.prepareSubmit(submit("B2", Side.BUY, 100, 1))
        engine.validate(first)
        engine.validate(second)
        assertEquals(before, snapshot(engine))

        assertEquals(listOf(trade(2, "S1", "B2", Side.BUY, 100, 1)), engine.apply(second))
        assertRejectedWithoutChanges(engine, first, snapshot(engine))
        assertEquals(
            expectedState(
                lastSequence = 2,
                seen = setOf("S1", "B2"),
                asks = listOf(order("S1", Side.SELL, 100, 2, 1)),
            ),
            snapshot(engine),
        )
    }

    @Test
    fun `같은 마켓의 정상 취소 뒤에는 계획을 거절하고 취소 뒤 상태를 보존한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(listOf(cancelled(2, "S1", 2)), engine.process(cancel("S1")))
        val afterCancel = snapshot(engine)
        assertRejectedWithoutChanges(engine, plan, afterCancel)
        assertEquals(expectedState(2, setOf("S1")), snapshot(engine))
    }

    @Test
    fun `없는 주문 취소도 거절 순번을 소비하므로 같은 마켓의 계획을 무효화한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(listOf(cancelRejected(2, "missing", "order not found")), engine.process(cancel("missing")))
        val afterCancel = snapshot(engine)
        assertRejectedWithoutChanges(engine, plan, afterCancel)
        assertEquals(
            expectedState(2, setOf("S1"), asks = listOf(order("S1", Side.SELL, 100, 2, 2))),
            snapshot(engine),
        )
    }

    @Test
    fun `소유자 불일치 취소도 거절 순번을 소비하므로 같은 마켓의 계획을 무효화한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(
            listOf(cancelRejected(2, "S1", "order owner mismatch", userId = "intruder")),
            engine.process(cancel("S1", userId = "intruder")),
        )
        val afterCancel = snapshot(engine)
        assertRejectedWithoutChanges(engine, plan, afterCancel)
        assertEquals(
            expectedState(2, setOf("S1"), asks = listOf(order("S1", Side.SELL, 100, 2, 2))),
            snapshot(engine),
        )
    }

    @Test
    fun `중복과 미지원 submit 예외는 상태를 바꾸거나 기존 정상 계획을 무효화하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val command = submit("B1", Side.BUY, 100, 1)
        val plan = engine.prepareSubmit(command)
        val before = snapshot(engine)
        val rejectedCommands =
            listOf(
                submit("S1", Side.SELL, 100, 2),
                command.copy(orderType = OrderType.MARKET),
                command.copy(timeInForce = TimeInForce.IOC),
            )

        for (rejected in rejectedCommands) {
            assertFailsWith<IllegalArgumentException> { engine.process(rejected) }
            assertFailsWith<IllegalArgumentException> { engine.prepareSubmit(rejected) }
            assertEquals(before, snapshot(engine))
            engine.validate(plan)
            assertEquals(before, snapshot(engine))
        }

        assertEquals(listOf(trade(2, "S1", "B1", Side.BUY, 100, 1)), engine.apply(plan))
        assertEquals(
            expectedState(2, setOf("S1", "B1"), asks = listOf(order("S1", Side.SELL, 100, 2, 1))),
            snapshot(engine),
        )
    }

    @Test
    fun `다른 마켓의 submit과 정상 취소와 취소 거절은 현재 계획을 무효화하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(
            listOf(entered(1, "E1", Side.BUY, 50, 3, marketId = otherMarket)),
            engine.process(submit("E1", Side.BUY, 50, 3, marketId = otherMarket)),
        )
        assertEquals(
            listOf(cancelled(2, "E1", 3, marketId = otherMarket)),
            engine.process(cancel("E1", marketId = otherMarket)),
        )
        assertEquals(
            listOf(cancelRejected(3, "missing", "order not found", marketId = otherMarket)),
            engine.process(cancel("missing", marketId = otherMarket)),
        )
        val otherState = snapshot(engine)
        engine.validate(plan)
        assertEquals(otherState, snapshot(engine))

        assertEquals(listOf(trade(2, "S1", "B1", Side.BUY, 100, 1)), engine.apply(plan))
        val afterApply = snapshot(engine)
        assertEquals(otherState.books[otherMarket], afterApply.books[otherMarket])
        assertEquals(setOf(OrderId("E1")), afterApply.seenOrderIds[otherMarket])
        assertEquals(3L, afterApply.lastEngineSequences[otherMarket])
        assertEquals(
            expectedState(2, setOf("S1", "B1"), asks = listOf(order("S1", Side.SELL, 100, 2, 1))).books[market],
            afterApply.books[market],
        )
    }

    @Test
    fun `다른 엔진의 거절은 두 엔진 상태와 원래 엔진의 적용 권한을 보존한다`() {
        val original = MatchingEngine()
        val foreign = MatchingEngine()
        original.process(submit("S1", Side.SELL, 100, 2))
        foreign.process(submit("S2", Side.SELL, 99, 3))
        val plan = original.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        val originalBefore = snapshot(original)
        val foreignBefore = snapshot(foreign)

        assertFailsWith<IllegalArgumentException> { foreign.validate(plan) }
        assertEquals(foreignBefore, snapshot(foreign))
        assertFailsWith<IllegalArgumentException> { foreign.apply(plan) }
        assertEquals(foreignBefore, snapshot(foreign))
        assertEquals(originalBefore, snapshot(original))

        original.validate(plan)
        assertEquals(listOf(trade(2, "S1", "B1", Side.BUY, 100, 1)), original.apply(plan))
        assertEquals(foreignBefore, snapshot(foreign))
    }

    @Test
    fun `이미 적용한 계획의 검증과 재적용은 거절하고 첫 결과 상태를 보존한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(listOf(trade(2, "S1", "B1", Side.BUY, 100, 1)), engine.apply(plan))
        val applied = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, applied)
        assertEquals(
            expectedState(2, setOf("S1", "B1"), asks = listOf(order("S1", Side.SELL, 100, 2, 1))),
            snapshot(engine),
        )
    }

    @Test
    fun `부분 체결된 maker는 같은 가격에서 FIFO의 앞자리를 유지한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        engine.process(submit("S2", Side.SELL, 100, 1))
        val before = snapshot(engine)
        val partial = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        assertEquals(listOf(trade(3, "S1", "B1", Side.BUY, 100, 1)), partial.events)
        assertEquals(before, snapshot(engine))
        assertEquals(listOf(trade(3, "S1", "B1", Side.BUY, 100, 1)), engine.apply(partial))
        assertEquals(
            expectedState(
                3,
                setOf("S1", "S2", "B1"),
                asks = listOf(order("S1", Side.SELL, 100, 2, 1), order("S2", Side.SELL, 100, 1, 1)),
            ),
            snapshot(engine),
        )

        val remaining = engine.prepareSubmit(submit("B2", Side.BUY, 100, 2))
        val expected =
            listOf(
                trade(4, "S1", "B2", Side.BUY, 100, 1),
                trade(5, "S2", "B2", Side.BUY, 100, 1),
            )
        assertEquals(expected, remaining.events)
        assertEquals(expected, engine.apply(remaining))
        assertEquals(expectedState(5, setOf("S1", "S2", "B1", "B2")), snapshot(engine))
    }

    @Test
    fun `sell 계획은 높은 bid와 같은 가격 FIFO를 따라 maker 가격으로 전량 체결한다`() {
        val engine = MatchingEngine()
        engine.process(submit("B1", Side.BUY, 100, 2))
        engine.process(submit("B2", Side.BUY, 110, 1))
        engine.process(submit("B3", Side.BUY, 110, 1))
        val before = snapshot(engine)
        val plan = engine.prepareSubmit(submit("S1", Side.SELL, 95, 3))
        val expected =
            listOf(
                trade(4, "B2", "S1", Side.SELL, 110, 1),
                trade(5, "B3", "S1", Side.SELL, 110, 1),
                trade(6, "B1", "S1", Side.SELL, 100, 1),
            )

        assertEquals(expected, plan.events)
        engine.validate(plan)
        assertEquals(before, snapshot(engine))
        assertEquals(expected, engine.apply(plan))
        assertEquals(
            expectedState(6, setOf("B1", "B2", "B3", "S1"), bids = listOf(order("B1", Side.BUY, 100, 2, 1))),
            snapshot(engine),
        )
    }

    @Test
    fun `순번이 같아도 maker의 기준 잔량이 바뀌면 첫 쓰기 전에 거절한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))

        engineBooks(engine).getValue(market).find(OrderId("S1"))!!.fill(Quantity(1))
        val changedMaker = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, changedMaker)
        assertEquals(
            expectedState(1, setOf("S1"), asks = listOf(order("S1", Side.SELL, 100, 2, 1))),
            snapshot(engine),
        )
    }

    @Test
    fun `순번과 maker 값이 같아도 동일 가격 FIFO 위치가 바뀌면 적용 전에 거절한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        engine.process(submit("S2", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        engine.validate(plan)
        val book = engineBooks(engine).getValue(market)
        val moved = book.cancel(OrderId("S1"))!!
        book.addRestingOrder(moved)
        val reordered = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, reordered)
        assertEquals(
            expectedState(2, setOf("S1", "S2"), asks = listOf(order("S2", Side.SELL, 100, 2, 2), order("S1", Side.SELL, 100, 2, 2))),
            snapshot(engine),
        )
    }

    @Test
    fun `같은 순번에서 더 유리한 maker가 앞에 추가되면 기존 후보를 적용하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 2))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 1))
        engineBooks(engine).getValue(market).addRestingOrder(
            BookOrder(OrderId("S2"), UserId("user-S2"), Side.SELL, Price(90), Quantity(1), Quantity(1)),
        )
        val changed = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, changed)
    }

    @Test
    fun `잔량을 대기시키는 후보 뒤에 새 체결 상대가 생기면 순번이 같아도 적용하지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 90, 1))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 3))
        engineBooks(engine).getValue(market).addRestingOrder(
            BookOrder(OrderId("S2"), UserId("user-S2"), Side.SELL, Price(95), Quantity(1), Quantity(1)),
        )
        val changed = snapshot(engine)

        assertRejectedWithoutChanges(engine, plan, changed)
    }

    @Test
    fun `후보와 적용 반환 목록은 변경할 수 없고 후속 체결도 과거 이벤트 값을 바꾸지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, 1))
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 2))
        val expected =
            listOf(
                trade(2, "S1", "B1", Side.BUY, 100, 1),
                entered(3, "B1", Side.BUY, 100, 1),
            )
        val before = snapshot(engine)

        assertListCannotBeChanged(plan.events)
        assertEquals(expected, plan.events)
        engine.validate(plan)
        assertEquals(before, snapshot(engine))

        val returned = engine.apply(plan)
        val applied = snapshot(engine)
        assertListCannotBeChanged(returned)
        assertEquals(applied, snapshot(engine))
        assertEquals(expected, returned)
        assertEquals(expected, plan.events)

        assertEquals(
            listOf(trade(4, "B1", "S2", Side.SELL, 100, 1)),
            engine.process(submit("S2", Side.SELL, 90, 1)),
        )
        assertEquals(expected, returned)
        assertEquals(expected, plan.events)
    }

    @Test
    fun `최대 수량의 계획은 여러 maker를 체결해도 수량과 부분 maker 잔량을 정확히 보존한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 100, Long.MAX_VALUE - 1))
        engine.process(submit("S2", Side.SELL, 100, Long.MAX_VALUE))
        val before = snapshot(engine)
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, Long.MAX_VALUE))
        val expected =
            listOf(
                trade(3, "S1", "B1", Side.BUY, 100, Long.MAX_VALUE - 1),
                trade(4, "S2", "B1", Side.BUY, 100, 1),
            )

        assertEquals(expected, plan.events)
        assertEquals(
            BigInteger.valueOf(Long.MAX_VALUE),
            plan.events.filterIsInstance<TradeExecuted>().fold(BigInteger.ZERO) { sum, event ->
                sum + BigInteger.valueOf(event.quantity.value)
            },
        )
        engine.validate(plan)
        assertEquals(before, snapshot(engine))
        assertEquals(expected, engine.apply(plan))
        assertEquals(
            expectedState(
                4,
                setOf("S1", "S2", "B1"),
                asks = listOf(order("S2", Side.SELL, 100, Long.MAX_VALUE, Long.MAX_VALUE - 1)),
            ),
            snapshot(engine),
        )
    }

    @Test
    fun `마지막 이벤트에 MAX 순번을 허용하고 이후 submit과 모든 cancel은 변경 전에 실패한다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 90, 1))
        engine.process(submit("S2", Side.SELL, 95, 1))
        setLastSequence(engine, Long.MAX_VALUE - 3)
        val before = snapshot(engine)
        val plan = engine.prepareSubmit(submit("B1", Side.BUY, 100, 3))
        val expected =
            listOf(
                trade(Long.MAX_VALUE - 2, "S1", "B1", Side.BUY, 90, 1),
                trade(Long.MAX_VALUE - 1, "S2", "B1", Side.BUY, 95, 1),
                entered(Long.MAX_VALUE, "B1", Side.BUY, 100, 1),
            )

        assertEquals(Long.MAX_VALUE - 2, plan.firstEngineSequence)
        assertEquals(Long.MAX_VALUE, plan.lastEngineSequence)
        assertEquals(expected, plan.events)
        engine.validate(plan)
        assertEquals(before, snapshot(engine))
        assertEquals(expected, engine.apply(plan))
        val exhausted = snapshot(engine)
        assertEquals(
            expectedState(
                Long.MAX_VALUE,
                setOf("S1", "S2", "B1"),
                bids = listOf(order("B1", Side.BUY, 100, 3, 1)),
            ),
            exhausted,
        )

        val nextSubmit = submit("B2", Side.BUY, 100, 1)
        assertFailsWith<ArithmeticException> { engine.prepareSubmit(nextSubmit) }
        assertEquals(exhausted, snapshot(engine))
        assertFailsWith<ArithmeticException> { engine.process(nextSubmit) }
        assertEquals(exhausted, snapshot(engine))
        for (nextCancel in listOf(cancel("B1"), cancel("missing"), cancel("B1", userId = "intruder"))) {
            assertFailsWith<ArithmeticException> { engine.process(nextCancel) }
            assertEquals(exhausted, snapshot(engine))
        }
    }

    @Test
    fun `세 이벤트 전체 순번이 부족하면 prepare와 기존 process 모두 어떤 상태도 바꾸지 않는다`() {
        val engine = MatchingEngine()
        engine.process(submit("S1", Side.SELL, 90, 1))
        engine.process(submit("S2", Side.SELL, 95, 1))
        setLastSequence(engine, Long.MAX_VALUE - 2)
        val before = snapshot(engine)
        val command = submit("B1", Side.BUY, 100, 3)

        assertFailsWith<ArithmeticException> { engine.prepareSubmit(command) }
        assertEquals(before, snapshot(engine))
        assertFailsWith<ArithmeticException> { engine.process(command) }
        assertEquals(before, snapshot(engine))

        setLastSequence(engine, 2)
        assertEquals(
            listOf(
                trade(3, "S1", "B1", Side.BUY, 90, 1),
                trade(4, "S2", "B1", Side.BUY, 95, 1),
                entered(5, "B1", Side.BUY, 100, 1),
            ),
            engine.process(command),
        )
    }

    @Test
    fun `순번이 소진된 빈 마켓의 cancel은 새 주문장 map을 만들지 않는다`() {
        val engine = MatchingEngine()
        setLastSequence(engine, Long.MAX_VALUE)
        val before = EngineSnapshot(emptyMap(), emptyMap(), mapOf(market to Long.MAX_VALUE))
        assertEquals(before, snapshot(engine))

        assertFailsWith<ArithmeticException> { engine.process(cancel("missing")) }
        assertEquals(before, snapshot(engine))
    }

    private fun assertRejectedWithoutChanges(
        engine: MatchingEngine,
        plan: SubmitOrderPlan,
        expected: EngineSnapshot,
    ) {
        assertFailsWith<IllegalStateException> { engine.apply(plan) }
        assertEquals(expected, snapshot(engine))
        assertFailsWith<IllegalStateException> { engine.validate(plan) }
        assertEquals(expected, snapshot(engine))
    }

    private fun assertListCannotBeChanged(events: List<MatchingEvent>) {
        val mutable = events as? MutableList<MatchingEvent> ?: return
        val forged = entered(999, "forged", Side.BUY, 1, 1)
        assertFailsWith<UnsupportedOperationException> { mutable[0] = forged }
        assertFailsWith<UnsupportedOperationException> { mutable.add(forged) }
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }
    }

    private fun submit(
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
        marketId: MarketId = market,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId,
            OrderId(orderId),
            UserId("user-$orderId"),
            side,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(price),
            Quantity(quantity),
        )

    private fun cancel(
        orderId: String,
        userId: String = "user-$orderId",
        marketId: MarketId = market,
    ): CancelOrderCommand = CancelOrderCommand(marketId, OrderId(orderId), UserId(userId))

    private fun entered(
        sequence: Long,
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
        marketId: MarketId = market,
    ): OrderEnteredBook =
        OrderEnteredBook(
            marketId,
            sequence,
            OrderId(orderId),
            UserId("user-$orderId"),
            side,
            Price(price),
            Quantity(quantity),
        )

    private fun trade(
        sequence: Long,
        maker: String,
        taker: String,
        side: Side,
        price: Long,
        quantity: Long,
    ): TradeExecuted =
        TradeExecuted(
            market,
            sequence,
            OrderId(maker),
            OrderId(taker),
            UserId("user-$maker"),
            UserId("user-$taker"),
            side,
            Price(price),
            Quantity(quantity),
        )

    private fun cancelled(
        sequence: Long,
        orderId: String,
        quantity: Long,
        marketId: MarketId = market,
    ): OrderCancelled = OrderCancelled(marketId, sequence, OrderId(orderId), UserId("user-$orderId"), Quantity(quantity))

    private fun cancelRejected(
        sequence: Long,
        orderId: String,
        reason: String,
        userId: String = "user-$orderId",
        marketId: MarketId = market,
    ): OrderCancelRejected = OrderCancelRejected(marketId, sequence, OrderId(orderId), UserId(userId), reason)

    private fun order(
        orderId: String,
        side: Side,
        price: Long,
        originalQuantity: Long,
        remainingQuantity: Long,
    ): OrderSnapshot = OrderSnapshot(orderId, "user-$orderId", side, price, originalQuantity, remainingQuantity)

    private fun expectedState(
        lastSequence: Long,
        seen: Set<String>,
        bids: List<OrderSnapshot> = emptyList(),
        asks: List<OrderSnapshot> = emptyList(),
    ): EngineSnapshot =
        EngineSnapshot(
            books =
                mapOf(
                    market to
                        BookSnapshot(
                            bids = bids.groupBy { it.price }.map { (price, orders) -> LevelSnapshot(price, orders) },
                            asks = asks.groupBy { it.price }.map { (price, orders) -> LevelSnapshot(price, orders) },
                            orderIndex =
                                (bids + asks).associate { order ->
                                    OrderId(order.orderId) to IndexSnapshot(order.side, order.price)
                                },
                        ),
                ),
            seenOrderIds = mapOf(market to seen.map(::OrderId).toSet()),
            lastEngineSequences = mapOf(market to lastSequence),
        )

    private fun snapshot(engine: MatchingEngine): EngineSnapshot =
        EngineSnapshot(
            books =
                engineBooks(engine).mapValues { (_, book) ->
                    BookSnapshot(
                        bids = levels(book, "bids"),
                        asks = levels(book, "asks"),
                        orderIndex =
                            readField<Map<OrderId, Any>>(book, "orderIndex").mapValues { (_, index) ->
                                IndexSnapshot(
                                    readField(index, "side"),
                                    readField(index, "price"),
                                )
                            },
                    )
                },
            seenOrderIds =
                readField<Map<MarketId, Set<OrderId>>>(engine, "seenOrderIds").mapValues { (_, ids) -> ids.toSet() },
            lastEngineSequences = readField<Map<MarketId, Long>>(engine, "lastEngineSequences").toMap(),
        )

    private fun levels(
        book: OrderBook,
        fieldName: String,
    ): List<LevelSnapshot> =
        readField<Map<Price, PriceLevel>>(book, fieldName).map { (price, level) ->
            LevelSnapshot(
                price = price.value,
                orders =
                    readField<Map<OrderId, BookOrder>>(level, "orders").values.map { order ->
                        OrderSnapshot(
                            order.orderId.value,
                            order.userId.value,
                            order.side,
                            order.price.value,
                            order.originalQuantity.value,
                            order.remainingQuantity.value,
                        )
                    },
            )
        }

    private fun engineBooks(engine: MatchingEngine): Map<MarketId, OrderBook> = readField(engine, "orderBooks")

    private fun setLastSequence(
        engine: MatchingEngine,
        sequence: Long,
    ) {
        readField<MutableMap<MarketId, Long>>(engine, "lastEngineSequences")[market] = sequence
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(
        owner: Any,
        name: String,
    ): T =
        owner.javaClass
            .getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(owner) as T

    private data class EngineSnapshot(
        val books: Map<MarketId, BookSnapshot>,
        val seenOrderIds: Map<MarketId, Set<OrderId>>,
        val lastEngineSequences: Map<MarketId, Long>,
    )

    private data class BookSnapshot(
        val bids: List<LevelSnapshot>,
        val asks: List<LevelSnapshot>,
        val orderIndex: Map<OrderId, IndexSnapshot>,
    )

    private data class LevelSnapshot(
        val price: Long,
        val orders: List<OrderSnapshot>,
    )

    private data class OrderSnapshot(
        val orderId: String,
        val userId: String,
        val side: Side,
        val price: Long,
        val originalQuantity: Long,
        val remainingQuantity: Long,
    )

    private data class IndexSnapshot(
        val side: Side,
        val price: Long,
    )
}

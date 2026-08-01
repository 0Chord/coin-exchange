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

class MatchingEngineTest {
    private val marketId = MarketId("BTC-KRW")

    @Test
    fun `빈 book에 매수 지정가 주문을 넣으면 bid book에 남는다`() {
        val events = MatchingEngine().process(
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 10),
        )

        assertEquals(
            listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 10)),
            events,
        )
    }

    @Test
    fun `빈 book에 매도 지정가 주문을 넣으면 ask book에 남는다`() {
        val events = MatchingEngine().process(
            submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 10),
        )

        assertEquals(
            listOf(entered(seq = 1, orderId = "s1", side = Side.SELL, price = 100, quantity = 10)),
            events,
        )
    }

    @Test
    fun `가격이 교차하지 않는 지정가 주문은 book에 남는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 10))
        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 101, quantity = 3))

        assertEquals(
            listOf(entered(seq = 2, orderId = "s1", side = Side.SELL, price = 101, quantity = 3)),
            events,
        )
    }

    @Test
    fun `best ask보다 낮은 매수 주문은 체결되지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 101, quantity = 3))
        val events = engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2))

        assertEquals(
            listOf(entered(seq = 2, orderId = "b1", side = Side.BUY, price = 100, quantity = 2)),
            events,
        )
    }

    @Test
    fun `매수 지정가 주문은 가장 낮은 ask부터 maker 가격으로 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a100", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "a90", side = Side.SELL, price = 90, quantity = 1))
        val events = engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 3, maker = "a90", taker = "b1", side = Side.BUY, price = 90, quantity = 1)),
            events,
        )
    }

    @Test
    fun `매도 지정가 주문은 가장 높은 bid부터 maker 가격으로 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b100", side = Side.BUY, price = 100, quantity = 1))
        engine.process(submit(orderId = "b110", side = Side.BUY, price = 110, quantity = 1))
        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 3, maker = "b110", taker = "s1", side = Side.SELL, price = 110, quantity = 1)),
            events,
        )
    }

    @Test
    fun `주문 가격이 정확히 같으면 체결된다`() {
        val buyEngine = MatchingEngine()
        buyEngine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
            buyEngine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )

        val sellEngine = MatchingEngine()
        sellEngine.process(submit(orderId = "b2", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 2, maker = "b2", taker = "s2", side = Side.SELL, price = 100, quantity = 1)),
            sellEngine.process(submit(orderId = "s2", side = Side.SELL, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `가장 낮은 ask 레벨이 비면 다음 ask 레벨로 넘어간다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a90", side = Side.SELL, price = 90, quantity = 1))
        engine.process(submit(orderId = "a100", side = Side.SELL, price = 100, quantity = 1))

        assertEquals(
            listOf(
                trade(seq = 3, maker = "a90", taker = "b1", side = Side.BUY, price = 90, quantity = 1),
                trade(seq = 4, maker = "a100", taker = "b1", side = Side.BUY, price = 100, quantity = 1),
            ),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2)),
        )
    }

    @Test
    fun `가장 높은 bid 레벨이 비면 다음 bid 레벨로 넘어간다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b110", side = Side.BUY, price = 110, quantity = 1))
        engine.process(submit(orderId = "b100", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(
                trade(seq = 3, maker = "b110", taker = "s1", side = Side.SELL, price = 110, quantity = 1),
                trade(seq = 4, maker = "b100", taker = "s1", side = Side.SELL, price = 100, quantity = 1),
            ),
            engine.process(submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 2)),
        )
    }

    @Test
    fun `ask 최우선 레벨을 취소하면 다음 ask 레벨이 체결된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a90", side = Side.SELL, price = 90, quantity = 1))
        engine.process(submit(orderId = "a100", side = Side.SELL, price = 100, quantity = 1))
        engine.process(cancel(orderId = "a90"))

        assertEquals(
            listOf(trade(seq = 4, maker = "a100", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `bid 최우선 레벨을 취소하면 다음 bid 레벨이 체결된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b110", side = Side.BUY, price = 110, quantity = 1))
        engine.process(submit(orderId = "b100", side = Side.BUY, price = 100, quantity = 1))
        engine.process(cancel(orderId = "b110"))

        assertEquals(
            listOf(trade(seq = 4, maker = "b100", taker = "s1", side = Side.SELL, price = 100, quantity = 1)),
            engine.process(submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `같은 ask 가격에서는 먼저 들어온 주문부터 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 3, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
        assertEquals(
            listOf(trade(seq = 4, maker = "a2", taker = "b2", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b2", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `같은 bid 가격에서는 먼저 들어온 주문부터 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
        engine.process(submit(orderId = "b2", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(trade(seq = 3, maker = "b1", taker = "s1", side = Side.SELL, price = 100, quantity = 1)),
            engine.process(submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 1)),
        )
        assertEquals(
            listOf(trade(seq = 4, maker = "b2", taker = "s2", side = Side.SELL, price = 100, quantity = 1)),
            engine.process(submit(orderId = "s2", side = Side.SELL, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `같은 가격의 중간 주문을 취소해도 FIFO 순서가 유지된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "a3", side = Side.SELL, price = 100, quantity = 1))
        assertEquals(
            listOf(cancelled(seq = 4, orderId = "a2", quantity = 1)),
            engine.process(cancel(orderId = "a2")),
        )

        assertEquals(
            listOf(
                trade(seq = 5, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1),
                trade(seq = 6, maker = "a3", taker = "b1", side = Side.BUY, price = 100, quantity = 1),
            ),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2)),
        )
    }

    @Test
    fun `부분 체결된 maker는 남은 수량으로 취소할 수 있다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5))
        assertEquals(
            listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 2)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2)),
        )
        assertEquals(
            listOf(cancelled(seq = 3, orderId = "a1", quantity = 3)),
            engine.process(cancel(orderId = "a1")),
        )
    }

    @Test
    fun `첫 maker가 부분 체결되면 다음 maker로 넘어가지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 100, quantity = 5))

        assertEquals(
            listOf(trade(seq = 3, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 3)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 3)),
        )
        assertEquals(
            listOf(cancelled(seq = 4, orderId = "a1", quantity = 2)),
            engine.process(cancel(orderId = "a1")),
        )
        assertEquals(
            listOf(cancelled(seq = 5, orderId = "a2", quantity = 5)),
            engine.process(cancel(orderId = "a2")),
        )
    }

    @Test
    fun `부분 체결된 maker는 다음 taker에게 남은 수량만 체결된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2))

        assertEquals(
            listOf(trade(seq = 3, maker = "a1", taker = "b2", side = Side.BUY, price = 100, quantity = 3)),
            engine.process(submit(orderId = "b2", side = Side.BUY, price = 100, quantity = 3)),
        )
        assertEquals(
            listOf(cancelRejected(seq = 4, orderId = "a1")),
            engine.process(cancel(orderId = "a1")),
        )
    }

    @Test
    fun `여러 maker를 정확히 전량 체결하면 taker는 book에 남지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 2))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 101, quantity = 3))

        assertEquals(
            listOf(
                trade(seq = 3, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 2),
                trade(seq = 4, maker = "a2", taker = "b1", side = Side.BUY, price = 101, quantity = 3),
            ),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 5)),
        )
        assertEquals(
            listOf(cancelRejected(seq = 5, orderId = "b1")),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `매수 taker는 여러 maker를 체결하고 남은 GTC 수량을 book에 넣는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 2))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 100, quantity = 5))
        engine.process(submit(orderId = "a3", side = Side.SELL, price = 101, quantity = 4))

        val events = engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 12))

        assertEquals(
            listOf(
                trade(seq = 4, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 2),
                trade(seq = 5, maker = "a2", taker = "b1", side = Side.BUY, price = 100, quantity = 5),
                trade(seq = 6, maker = "a3", taker = "b1", side = Side.BUY, price = 101, quantity = 4),
                entered(seq = 7, orderId = "b1", side = Side.BUY, price = 101, quantity = 1),
            ),
            events,
        )
    }

    @Test
    fun `매도 taker는 여러 bid를 체결하고 남은 GTC 수량을 book에 넣는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 2))
        engine.process(submit(orderId = "b2", side = Side.BUY, price = 100, quantity = 5))
        engine.process(submit(orderId = "b3", side = Side.BUY, price = 99, quantity = 4))

        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 8))

        assertEquals(
            listOf(
                trade(seq = 4, maker = "b1", taker = "s1", side = Side.SELL, price = 101, quantity = 2),
                trade(seq = 5, maker = "b2", taker = "s1", side = Side.SELL, price = 100, quantity = 5),
                entered(seq = 6, orderId = "s1", side = Side.SELL, price = 100, quantity = 1),
            ),
            events,
        )
    }

    @Test
    fun `매수 taker는 가격 조건을 만족하는 ask까지만 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a100", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "a101", side = Side.SELL, price = 101, quantity = 1))

        assertEquals(
            listOf(
                trade(seq = 3, maker = "a100", taker = "b1", side = Side.BUY, price = 100, quantity = 1),
                entered(seq = 4, orderId = "b1", side = Side.BUY, price = 100, quantity = 4),
            ),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 5)),
        )
        assertEquals(
            listOf(trade(seq = 5, maker = "a101", taker = "b2", side = Side.BUY, price = 101, quantity = 1)),
            engine.process(submit(orderId = "b2", side = Side.BUY, price = 101, quantity = 1)),
        )
    }

    @Test
    fun `매도 taker는 가격 조건을 만족하는 bid까지만 체결한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b101", side = Side.BUY, price = 101, quantity = 1))
        engine.process(submit(orderId = "b100", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(
                trade(seq = 3, maker = "b101", taker = "s1", side = Side.SELL, price = 101, quantity = 1),
                entered(seq = 4, orderId = "s1", side = Side.SELL, price = 101, quantity = 4),
            ),
            engine.process(submit(orderId = "s1", side = Side.SELL, price = 101, quantity = 5)),
        )
        assertEquals(
            listOf(trade(seq = 5, maker = "b100", taker = "s2", side = Side.SELL, price = 100, quantity = 1)),
            engine.process(submit(orderId = "s2", side = Side.SELL, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `book에 남은 taker GTC 잔량은 취소할 수 있다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 2))
        engine.process(submit(orderId = "a2", side = Side.SELL, price = 101, quantity = 3))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 10))

        assertEquals(
            listOf(cancelled(seq = 6, orderId = "b1", quantity = 5)),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `취소된 resting 주문은 이후 체결되지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5))
        assertEquals(
            listOf(cancelled(seq = 2, orderId = "a1", quantity = 5)),
            engine.process(cancel(orderId = "a1")),
        )
        assertEquals(
            listOf(entered(seq = 3, orderId = "b1", side = Side.BUY, price = 100, quantity = 5)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 5)),
        )
    }

    @Test
    fun `resting 상태의 중복 orderId는 book과 sequence 변경 없이 거절된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 5))

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 3))
        }

        assertEquals("order already exists", exception.message)
        assertEquals(
            listOf(cancelled(seq = 2, orderId = "b1", quantity = 5)),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `다른 가격의 중복 resting orderId도 ghost order 없이 거절된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5))

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(submit(orderId = "a1", side = Side.SELL, price = 101, quantity = 3))
        }

        assertEquals("order already exists", exception.message)
        engine.process(cancel(orderId = "a1"))
        assertEquals(
            listOf(entered(seq = 3, orderId = "b1", side = Side.BUY, price = 101, quantity = 10)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 10)),
        )
    }

    @Test
    fun `체결 완료된 orderId는 같은 마켓에서 재사용할 수 없다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(submit(orderId = "a1", side = Side.SELL, price = 101, quantity = 1))
        }

        assertEquals("order already exists", exception.message)
        assertEquals(
            listOf(entered(seq = 3, orderId = "b2", side = Side.BUY, price = 99, quantity = 1)),
            engine.process(submit(orderId = "b2", side = Side.BUY, price = 99, quantity = 1)),
        )
    }

    @Test
    fun `취소된 orderId는 같은 마켓에서 재사용할 수 없다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
        engine.process(cancel(orderId = "b1"))

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 1))
        }

        assertEquals("order already exists", exception.message)
        assertEquals(
            listOf(entered(seq = 3, orderId = "b2", side = Side.BUY, price = 99, quantity = 1)),
            engine.process(submit(orderId = "b2", side = Side.BUY, price = 99, quantity = 1)),
        )
    }

    @Test
    fun `없는 주문 취소는 reject event를 반환한다`() {
        val events = MatchingEngine().process(cancel(orderId = "missing"))

        assertEquals(
            listOf(cancelRejected(seq = 1, orderId = "missing")),
            events,
        )
    }

    @Test
    fun `존재하지 않는 주문 취소는 book 상태를 바꾸지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        assertEquals(
            listOf(cancelRejected(seq = 2, orderId = "missing")),
            engine.process(cancel(orderId = "missing")),
        )
        assertEquals(
            listOf(trade(seq = 3, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `존재하지 않는 주문 취소는 submit orderId를 선점하지 않는다`() {
        val engine = MatchingEngine()

        assertEquals(
            listOf(cancelRejected(seq = 1, orderId = "b1")),
            engine.process(cancel(orderId = "b1")),
        )
        assertEquals(
            listOf(entered(seq = 2, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `다른 유저가 주문을 취소하면 reject되고 주문은 그대로 남는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5, userId = "seller"))

        assertEquals(
            listOf(
                cancelRejected(
                    seq = 2,
                    orderId = "a1",
                    userId = "attacker",
                    reason = "order owner mismatch",
                ),
            ),
            engine.process(cancel(orderId = "a1", userId = "attacker")),
        )
        assertEquals(
            listOf(cancelled(seq = 3, orderId = "a1", quantity = 5, userId = "seller")),
            engine.process(cancel(orderId = "a1", userId = "seller")),
        )
    }

    @Test
    fun `다른 유저가 부분 체결된 주문을 취소해도 남은 수량은 유지된다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 5, userId = "seller"))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2, userId = "buyer"))

        assertEquals(
            listOf(
                cancelRejected(
                    seq = 3,
                    orderId = "a1",
                    userId = "attacker",
                    reason = "order owner mismatch",
                ),
            ),
            engine.process(cancel(orderId = "a1", userId = "attacker")),
        )
        assertEquals(
            listOf(cancelled(seq = 4, orderId = "a1", quantity = 3, userId = "seller")),
            engine.process(cancel(orderId = "a1", userId = "seller")),
        )
    }

    @Test
    fun `완전 체결된 maker 주문 취소는 reject event를 반환한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(cancelRejected(seq = 3, orderId = "a1")),
            engine.process(cancel(orderId = "a1")),
        )
    }

    @Test
    fun `완전 체결된 taker 주문 취소는 reject event를 반환한다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(cancelRejected(seq = 3, orderId = "b1")),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `전량 체결된 crossing 주문은 book에 남지 않는다`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 90, quantity = 1))

        assertEquals(
            listOf(trade(seq = 2, maker = "b1", taker = "s1", side = Side.SELL, price = 100, quantity = 1)),
            events,
        )
    }

    @Test
    fun `서로 다른 마켓은 book과 sequence를 독립적으로 유지한다`() {
        val engine = MatchingEngine()
        val ethMarket = MarketId("ETH-KRW")

        assertEquals(
            listOf(entered(seq = 1, orderId = "btc-a1", side = Side.SELL, price = 100, quantity = 1)),
            engine.process(submit(orderId = "btc-a1", side = Side.SELL, price = 100, quantity = 1)),
        )
        assertEquals(
            listOf(
                entered(
                    market = ethMarket,
                    seq = 1,
                    orderId = "eth-b1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                ),
            ),
            engine.process(
                submit(
                    market = ethMarket,
                    orderId = "eth-b1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                ),
            ),
        )
        assertEquals(
            listOf(trade(seq = 2, maker = "btc-a1", taker = "btc-b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "btc-b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `서로 다른 마켓에서는 같은 orderId가 격리된다`() {
        val engine = MatchingEngine()
        val ethMarket = MarketId("ETH-KRW")

        engine.process(submit(orderId = "shared", side = Side.BUY, price = 100, quantity = 1))
        engine.process(submit(market = ethMarket, orderId = "shared", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(cancelled(seq = 2, orderId = "shared", quantity = 1)),
            engine.process(cancel(orderId = "shared")),
        )
        assertEquals(
            listOf(
                trade(
                    market = ethMarket,
                    seq = 2,
                    maker = "shared",
                    taker = "eth-s1",
                    side = Side.SELL,
                    price = 100,
                    quantity = 1,
                ),
            ),
            engine.process(
                submit(
                    market = ethMarket,
                    orderId = "eth-s1",
                    side = Side.SELL,
                    price = 100,
                    quantity = 1,
                ),
            ),
        )
    }

    @Test
    fun `다른 마켓의 취소 실패는 원래 마켓 sequence와 book에 영향을 주지 않는다`() {
        val engine = MatchingEngine()
        val ethMarket = MarketId("ETH-KRW")

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
        assertEquals(
            listOf(cancelRejected(market = ethMarket, seq = 1, orderId = "b1")),
            engine.process(cancel(market = ethMarket, orderId = "b1")),
        )
        assertEquals(
            listOf(cancelled(seq = 2, orderId = "b1", quantity = 1)),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `지원하지 않는 시장가 주문은 event 생성 전에 실패한다`() {
        val engine = MatchingEngine()

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(
                submit(
                    orderId = "m1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                    orderType = OrderType.MARKET,
                ),
            )
        }

        assertEquals("only LIMIT order is supported", exception.message)
        assertEquals(
            listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `지원하지 않는 IOC 주문은 event 생성 전에 실패한다`() {
        val engine = MatchingEngine()

        val exception = assertFailsWith<IllegalArgumentException> {
            engine.process(
                submit(
                    orderId = "ioc1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                    timeInForce = TimeInForce.IOC,
                ),
            )
        }

        assertEquals("only GTC order is supported", exception.message)
        assertEquals(
            listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
        )
    }

    @Test
    fun `지원하지 않는 submit 실패는 orderId와 sequence를 사용하지 않는다`() {
        val engine = MatchingEngine()

        assertFailsWith<IllegalArgumentException> {
            engine.process(
                submit(
                    orderId = "m1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                    orderType = OrderType.MARKET,
                ),
            )
        }
        assertEquals(
            listOf(entered(seq = 1, orderId = "m1", side = Side.BUY, price = 100, quantity = 1)),
            engine.process(submit(orderId = "m1", side = Side.BUY, price = 100, quantity = 1)),
        )

        assertFailsWith<IllegalArgumentException> {
            engine.process(
                submit(
                    orderId = "ioc1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                    timeInForce = TimeInForce.IOC,
                ),
            )
        }
        assertEquals(
            listOf(entered(seq = 2, orderId = "ioc1", side = Side.BUY, price = 101, quantity = 1)),
            engine.process(submit(orderId = "ioc1", side = Side.BUY, price = 101, quantity = 1)),
        )
    }

    @Test
    fun `수량이 0인 submit command는 생성 시점에 거절된다`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 0)
        }

        assertEquals("quantity must be positive", exception.message)
    }

    @Test
    fun `수량이 음수인 submit command는 생성 시점에 거절된다`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = -1)
        }

        assertEquals("quantity must be positive", exception.message)
    }

    @Test
    fun `가격이 0인 submit command는 생성 시점에 거절된다`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            submit(orderId = "b1", side = Side.BUY, price = 0, quantity = 1)
        }

        assertEquals("price must be positive", exception.message)
    }

    @Test
    fun `가격이 음수인 submit command는 생성 시점에 거절된다`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            submit(orderId = "b1", side = Side.BUY, price = -1, quantity = 1)
        }

        assertEquals("price must be positive", exception.message)
    }

    @Test
    fun `같은 command stream은 같은 event stream을 만든다`() {
        val commands = listOf(
            submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 2),
            submit(orderId = "a2", side = Side.SELL, price = 101, quantity = 3),
            submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 4),
            cancel(orderId = "a2"),
            cancel(orderId = "missing"),
            submit(orderId = "s1", side = Side.SELL, price = 99, quantity = 1),
        )

        val events = runCommands(MatchingEngine(), commands)
        assertPerMarketSequencesAreContiguous(events)
        assertEquals(
            events,
            runCommands(MatchingEngine(), commands),
        )
    }

    @Test
    fun `긴 command stream도 같은 event stream을 만든다`() {
        val commands = buildList {
            for (index in 1..50) {
                add(submit(orderId = "ask-$index", side = Side.SELL, price = 100L + (index % 5), quantity = 2))
            }
            for (index in 1..50) {
                add(submit(orderId = "bid-$index", side = Side.BUY, price = 102L + (index % 4), quantity = 3))
            }
            for (index in 1..20) {
                add(cancel(orderId = "ask-$index"))
                add(cancel(orderId = "bid-$index"))
            }
        }

        val firstRun = runCommands(MatchingEngine(), commands)
        val secondRun = runCommands(MatchingEngine(), commands)

        assertEquals(firstRun, secondRun)
        assertEquals(firstRun.size, secondRun.size)
        assertPerMarketSequencesAreContiguous(firstRun)
    }

    @Test
    fun `여러 마켓 command stream도 결정성과 마켓별 sequence를 유지한다`() {
        val markets = listOf(
            MarketId("BTC-KRW"),
            MarketId("ETH-KRW"),
            MarketId("SOL-KRW"),
        )
        val commands = buildList {
            markets.forEach { market ->
                for (index in 1..20) {
                    add(
                        submit(
                            market = market,
                            orderId = "${market.value}-ask-$index",
                            side = Side.SELL,
                            price = 100L + (index % 4),
                            quantity = 2,
                        ),
                    )
                }
            }
            markets.asReversed().forEach { market ->
                for (index in 1..20) {
                    add(
                        submit(
                            market = market,
                            orderId = "${market.value}-bid-$index",
                            side = Side.BUY,
                            price = 101L + (index % 5),
                            quantity = 3,
                        ),
                    )
                }
            }
            markets.forEach { market ->
                for (index in 1..10) {
                    add(cancel(market = market, orderId = "${market.value}-ask-$index"))
                    add(cancel(market = market, orderId = "${market.value}-bid-$index"))
                }
            }
        }

        val firstRun = runCommands(MatchingEngine(), commands)
        val secondRun = runCommands(MatchingEngine(), commands)

        assertEquals(firstRun, secondRun)
        assertPerMarketSequencesAreContiguous(firstRun)
    }

    private fun runCommands(
        engine: MatchingEngine,
        commands: List<MatchingCommand>,
    ): List<MatchingEvent> =
        commands.flatMap { engine.process(it) }

    private fun assertPerMarketSequencesAreContiguous(events: List<MatchingEvent>) {
        val sequencesByMarket = events.groupBy { it.marketId }

        sequencesByMarket.forEach { (_, marketEvents) ->
            val expectedSequences = (1L..marketEvents.size.toLong()).toList()
            assertEquals(
                expectedSequences,
                marketEvents.map { it.engineSequence },
            )
        }
    }

    private fun submit(
        market: MarketId = marketId,
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
        orderType: OrderType = OrderType.LIMIT,
        timeInForce: TimeInForce = TimeInForce.GTC,
        userId: String = "user-$orderId",
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = market,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            side = side,
            orderType = orderType,
            timeInForce = timeInForce,
            price = Price(price),
            quantity = Quantity(quantity),
        )

    private fun cancel(
        market: MarketId = marketId,
        orderId: String,
        userId: String = "user-$orderId",
    ): CancelOrderCommand =
        CancelOrderCommand(
            marketId = market,
            orderId = OrderId(orderId),
            userId = UserId(userId),
        )

    private fun entered(
        market: MarketId = marketId,
        seq: Long,
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
        userId: String = "user-$orderId",
    ): OrderEnteredBook =
        OrderEnteredBook(
            marketId = market,
            engineSequence = seq,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            side = side,
            price = Price(price),
            remainingQuantity = Quantity(quantity),
        )

    private fun trade(
        market: MarketId = marketId,
        seq: Long,
        maker: String,
        taker: String,
        side: Side,
        price: Long,
        quantity: Long,
        makerUserId: String = "user-$maker",
        takerUserId: String = "user-$taker",
    ): TradeExecuted =
        TradeExecuted(
            marketId = market,
            engineSequence = seq,
            makerOrderId = OrderId(maker),
            takerOrderId = OrderId(taker),
            makerUserId = UserId(makerUserId),
            takerUserId = UserId(takerUserId),
            side = side,
            price = Price(price),
            quantity = Quantity(quantity),
        )

    private fun cancelled(
        market: MarketId = marketId,
        seq: Long,
        orderId: String,
        quantity: Long,
        userId: String = "user-$orderId",
    ): OrderCancelled =
        OrderCancelled(
            marketId = market,
            engineSequence = seq,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            remainingQuantity = Quantity(quantity),
        )

    private fun cancelRejected(
        market: MarketId = marketId,
        seq: Long,
        orderId: String,
        userId: String = "user-$orderId",
        reason: String = "order not found",
    ): OrderCancelRejected =
        OrderCancelRejected(
            marketId = market,
            engineSequence = seq,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            reason = reason,
        )
}

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
    fun `empty buy limit order enters bid book`() {
        val events = MatchingEngine().process(
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 10),
        )

        assertEquals(
            listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 10)),
            events,
        )
    }

    @Test
    fun `empty sell limit order enters ask book`() {
        val events = MatchingEngine().process(
            submit(orderId = "s1", side = Side.SELL, price = 100, quantity = 10),
        )

        assertEquals(
            listOf(entered(seq = 1, orderId = "s1", side = Side.SELL, price = 100, quantity = 10)),
            events,
        )
    }

    @Test
    fun `non crossing limit order enters book`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 10))
        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 101, quantity = 3))

        assertEquals(
            listOf(entered(seq = 2, orderId = "s1", side = Side.SELL, price = 101, quantity = 3)),
            events,
        )
    }

    @Test
    fun `buy below best ask does not match`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 101, quantity = 3))
        val events = engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 2))

        assertEquals(
            listOf(entered(seq = 2, orderId = "b1", side = Side.BUY, price = 100, quantity = 2)),
            events,
        )
    }

    @Test
    fun `buy limit matches lowest ask first at maker price`() {
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
    fun `sell limit matches highest bid first at maker price`() {
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
    fun `orders match when prices are exactly equal`() {
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
    fun `same ask price uses fifo order`() {
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
    fun `same bid price uses fifo order`() {
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
    fun `cancelled middle order is skipped while same price fifo is preserved`() {
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
    fun `partially filled maker remains cancellable with remaining quantity`() {
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
    fun `taker consumes multiple makers and remaining gtc quantity enters book`() {
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
    fun `sell taker consumes multiple bids and remaining gtc quantity enters book`() {
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
    fun `remaining taker gtc quantity can be cancelled`() {
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
    fun `cancel removes resting order from future matching`() {
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
    fun `duplicate resting order id is rejected without mutating book or sequence`() {
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
    fun `duplicate resting order id at another price is rejected without ghost order`() {
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
    fun `filled order id cannot be reused in same market`() {
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
    fun `cancelled order id cannot be reused in same market`() {
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
    fun `cancel unknown order returns rejected event`() {
        val events = MatchingEngine().process(cancel(orderId = "missing"))

        assertEquals(
            listOf(cancelRejected(seq = 1, orderId = "missing")),
            events,
        )
    }

    @Test
    fun `cancel filled order returns rejected event`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(cancelRejected(seq = 3, orderId = "a1")),
            engine.process(cancel(orderId = "a1")),
        )
    }

    @Test
    fun `cancel fully filled taker returns rejected event`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))

        assertEquals(
            listOf(cancelRejected(seq = 3, orderId = "b1")),
            engine.process(cancel(orderId = "b1")),
        )
    }

    @Test
    fun `fully crossing order does not enter book`() {
        val engine = MatchingEngine()

        engine.process(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
        val events = engine.process(submit(orderId = "s1", side = Side.SELL, price = 90, quantity = 1))

        assertEquals(
            listOf(trade(seq = 2, maker = "b1", taker = "s1", side = Side.SELL, price = 100, quantity = 1)),
            events,
        )
    }

    @Test
    fun `different markets keep independent books and sequences`() {
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
    fun `same order id in different markets is isolated`() {
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
    fun `unsupported market order fails before event creation`() {
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
    fun `unsupported ioc order fails before event creation`() {
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
    fun `zero submit quantity is rejected by command`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 0)
        }

        assertEquals("quantity must be positive", exception.message)
    }

    @Test
    fun `same command stream produces identical event stream`() {
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
    fun `longer deterministic command stream produces identical event stream`() {
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
    fun `generated multi market command stream is deterministic and keeps per market sequences`() {
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
    ): OrderCancelRejected =
        OrderCancelRejected(
            marketId = market,
            engineSequence = seq,
            orderId = OrderId(orderId),
            userId = UserId(userId),
            reason = "order not found",
        )
}

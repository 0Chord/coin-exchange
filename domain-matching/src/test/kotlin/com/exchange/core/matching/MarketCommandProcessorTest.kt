package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarketCommandProcessorTest {
    private val marketId = MarketId("BTC-KRW")

    @Test
    fun `같은 market command는 순서대로 처리된다`() {
        val processor = InMemoryMarketCommandProcessor()

        try {
            assertEquals(
                listOf(entered(seq = 1, orderId = "a1", side = Side.SELL, price = 100, quantity = 1)),
                processor.submit(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1)).await(),
            )
            assertEquals(
                listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
                processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `서로 다른 market은 sequence를 독립적으로 사용한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val ethMarket = MarketId("ETH-KRW")

        try {
            assertEquals(
                listOf(entered(seq = 1, orderId = "btc-b1", side = Side.BUY, price = 100, quantity = 1)),
                processor.submit(submit(orderId = "btc-b1", side = Side.BUY, price = 100, quantity = 1)).await(),
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
                processor.submit(
                    submit(
                        market = ethMarket,
                        orderId = "eth-b1",
                        side = Side.BUY,
                        price = 100,
                        quantity = 1,
                    ),
                ).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `서로 다른 market에서는 같은 orderId를 독립적으로 처리한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val ethMarket = MarketId("ETH-KRW")

        try {
            assertEquals(
                listOf(entered(seq = 1, orderId = "shared", side = Side.BUY, price = 100, quantity = 1)),
                processor.submit(submit(orderId = "shared", side = Side.BUY, price = 100, quantity = 1)).await(),
            )
            assertEquals(
                listOf(
                    entered(
                        market = ethMarket,
                        seq = 1,
                        orderId = "shared",
                        side = Side.BUY,
                        price = 100,
                        quantity = 1,
                    ),
                ),
                processor.submit(
                    submit(
                        market = ethMarket,
                        orderId = "shared",
                        side = Side.BUY,
                        price = 100,
                        quantity = 1,
                    ),
                ).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `여러 thread가 같은 market에 동시에 submit해도 sequence는 중복되거나 빠지지 않는다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerPool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val commandCount = 100

        try {
            val callerFutures = (1..commandCount).map { index ->
                callerPool.submit<List<MatchingEvent>> {
                    start.await()
                    processor.submit(
                        submit(
                            orderId = "b-$index",
                            side = Side.BUY,
                            price = 100L + index,
                            quantity = 1,
                        ),
                    ).await()
                }
            }

            start.countDown()

            val events = callerFutures.flatMap { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(commandCount, events.size)
            assertEquals((1L..commandCount.toLong()).toList(), events.map { it.engineSequence }.sorted())
            assertEquals(commandCount, events.map { it.engineSequence }.toSet().size)
            assertEquals(setOf(marketId), events.map { it.marketId }.toSet())
        } finally {
            callerPool.shutdownNow()
            processor.close()
        }
    }

    @Test
    fun `여러 thread가 여러 market에 동시에 submit해도 market별 sequence는 독립적이다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerPool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val markets = listOf(
            MarketId("BTC-KRW"),
            MarketId("ETH-KRW"),
            MarketId("SOL-KRW"),
        )
        val commandCountPerMarket = 40

        try {
            val callerFutures = markets.flatMap { market ->
                (1..commandCountPerMarket).map { index ->
                    callerPool.submit<List<MatchingEvent>> {
                        start.await()
                        processor.submit(
                            submit(
                                market = market,
                                orderId = "${market.value}-b-$index",
                                side = Side.BUY,
                                price = 100L + index,
                                quantity = 1,
                            ),
                        ).await()
                    }
                }
            }

            start.countDown()

            val events = callerFutures.flatMap { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(markets.toSet(), events.map { it.marketId }.toSet())

            events.groupBy { it.marketId }.forEach { (_, marketEvents) ->
                assertEquals(commandCountPerMarket, marketEvents.size)
                assertEquals(
                    (1L..commandCountPerMarket.toLong()).toList(),
                    marketEvents.map { it.engineSequence }.sorted(),
                )
            }
        } finally {
            callerPool.shutdownNow()
            processor.close()
        }
    }

    @Test
    fun `여러 thread가 같은 market에 같은 orderId를 동시에 submit하면 하나만 성공한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerPool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val submitCount = 20

        try {
            val callerFutures = (1..submitCount).map { index ->
                callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
                    start.await()
                    processor.submit(
                        submit(
                            orderId = "same",
                            side = Side.BUY,
                            price = 100L + index,
                            quantity = 1,
                        ),
                    )
                }
            }

            start.countDown()

            val outcomes = callerFutures.map { callerFuture ->
                runCatching {
                    callerFuture.get(5, TimeUnit.SECONDS).await()
                }
            }
            val successes = outcomes.filter { it.isSuccess }.map { it.getOrThrow() }
            val failures = outcomes.mapNotNull { outcome ->
                outcome.exceptionOrNull()?.cause
            }

            assertEquals(1, successes.size)
            assertEquals(submitCount - 1, failures.size)
            assertEquals(setOf("order already exists"), failures.map { it.message }.toSet())

            val enteredEvent = successes.single().single()
            assertIs<OrderEnteredBook>(enteredEvent)
            assertEquals(1, enteredEvent.engineSequence)
            assertEquals(OrderId("same"), enteredEvent.orderId)

            assertEquals(
                listOf(cancelled(seq = 2, orderId = "same", quantity = 1)),
                processor.submit(cancel(orderId = "same")).await(),
            )
        } finally {
            callerPool.shutdownNow()
            processor.close()
        }
    }

    @Test
    fun `같은 resting 주문에 cancel과 crossing order가 동시에 들어와도 결과는 한쪽으로 수렴한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerPool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)

        try {
            processor.submit(submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1)).await()

            val cancelCaller = callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
                start.await()
                processor.submit(cancel(orderId = "a1"))
            }
            val buyCaller = callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
                start.await()
                processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1))
            }

            start.countDown()

            val cancelEvents = cancelCaller.get(5, TimeUnit.SECONDS).await()
            val buyEvents = buyCaller.get(5, TimeUnit.SECONDS).await()
            val allEvents = cancelEvents + buyEvents

            assertEquals(listOf(2L, 3L), allEvents.map { it.engineSequence }.sorted())

            when (val cancelEvent = cancelEvents.single()) {
                is OrderCancelled -> {
                    assertEquals(cancelled(seq = 2, orderId = "a1", quantity = 1), cancelEvent)
                    assertEquals(
                        listOf(entered(seq = 3, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
                        buyEvents,
                    )
                }
                is OrderCancelRejected -> {
                    assertEquals(cancelRejected(seq = 3, orderId = "a1"), cancelEvent)
                    assertEquals(
                        listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1)),
                        buyEvents,
                    )
                }
                else -> error("unexpected cancel result: $cancelEvent")
            }
        } finally {
            callerPool.shutdownNow()
            processor.close()
        }
    }

    @Test
    fun `지원하지 않는 command가 실패해도 worker는 다음 command를 처리한다`() {
        val processor = InMemoryMarketCommandProcessor()

        try {
            val failure = processor.submit(
                submit(
                    orderId = "m1",
                    side = Side.BUY,
                    price = 100,
                    quantity = 1,
                    orderType = OrderType.MARKET,
                ),
            ).awaitFailure()

            assertEquals("only LIMIT order is supported", failure.message)
            assertEquals(
                listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
                processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `중복 orderId 실패 후에도 기존 주문은 유지되고 worker는 계속 처리한다`() {
        val processor = InMemoryMarketCommandProcessor()

        try {
            processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 5)).await()

            val failure = processor.submit(
                submit(orderId = "b1", side = Side.BUY, price = 101, quantity = 1),
            ).awaitFailure()

            assertEquals("order already exists", failure.message)
            assertEquals(
                listOf(cancelled(seq = 2, orderId = "b1", quantity = 5)),
                processor.submit(cancel(orderId = "b1")).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `다른 유저의 cancel reject 이후에도 원래 주문자는 취소할 수 있다`() {
        val processor = InMemoryMarketCommandProcessor()

        try {
            processor.submit(
                submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 3, userId = "seller"),
            ).await()

            assertEquals(
                listOf(
                    cancelRejected(
                        seq = 2,
                        orderId = "a1",
                        userId = "attacker",
                        reason = "order owner mismatch",
                    ),
                ),
                processor.submit(cancel(orderId = "a1", userId = "attacker")).await(),
            )
            assertEquals(
                listOf(cancelled(seq = 3, orderId = "a1", quantity = 3, userId = "seller")),
                processor.submit(cancel(orderId = "a1", userId = "seller")).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `close 전에 접수된 command는 close 이후에도 처리 완료된다`() {
        val processor = InMemoryMarketCommandProcessor()
        val commandCount = 30

        val futures = (1..commandCount).map { index ->
            processor.submit(
                submit(
                    orderId = "b-$index",
                    side = Side.BUY,
                    price = 100L + index,
                    quantity = 1,
                ),
            )
        }

        processor.close()

        val events = futures.flatMap { future ->
            future.await()
        }

        assertEquals(commandCount, events.size)
        assertEquals((1L..commandCount.toLong()).toList(), events.map { it.engineSequence }.sorted())
    }

    @Test
    fun `close 이전에 worker가 없었으면 close 이후 submit은 실패한 future를 반환한다`() {
        val processor = InMemoryMarketCommandProcessor()

        processor.close()

        val failure = processor.submit(
            submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1),
        ).awaitFailure()

        assertIs<RejectedExecutionException>(failure)
        assertEquals("market command processor is closed", failure.message)
    }

    @Test
    fun `close 이전에 worker가 있었어도 close 이후 submit은 실패한 future를 반환한다`() {
        val processor = InMemoryMarketCommandProcessor()

        processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)).await()
        processor.close()

        val failure = processor.submit(
            submit(orderId = "b2", side = Side.BUY, price = 101, quantity = 1),
        ).awaitFailure()

        assertIs<RejectedExecutionException>(failure)
        assertEquals("market command processor is closed", failure.message)
    }

    @Test
    fun `close는 여러 번 호출해도 예외가 나지 않는다`() {
        val processor = InMemoryMarketCommandProcessor()

        processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)).await()
        processor.close()
        processor.close()

        val failure = processor.submit(
            submit(orderId = "b2", side = Side.BUY, price = 101, quantity = 1),
        ).awaitFailure()

        assertIs<RejectedExecutionException>(failure)
    }

    @Test
    fun `close와 submit이 동시에 호출되어도 command는 성공 또는 rejected 중 하나로 완료된다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerPool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val submitCount = 120

        try {
            val submitCallers = (1..submitCount).map { index ->
                callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
                    start.await()
                    processor.submit(
                        submit(
                            orderId = "b-$index",
                            side = Side.BUY,
                            price = 100L + index,
                            quantity = 1,
                        ),
                    )
                }
            }
            val closeCaller = callerPool.submit {
                start.await()
                processor.close()
            }

            start.countDown()
            closeCaller.get(5, TimeUnit.SECONDS)

            val outcomes = submitCallers.map { callerFuture ->
                runCatching {
                    callerFuture.get(5, TimeUnit.SECONDS).await()
                }
            }
            val successfulEvents = outcomes
                .filter { it.isSuccess }
                .flatMap { it.getOrThrow() }
            val failures = outcomes.mapNotNull { outcome ->
                outcome.exceptionOrNull()?.cause
            }

            assertEquals(submitCount, successfulEvents.size + failures.size)
            assertTrue(failures.all { it is RejectedExecutionException })
            assertEquals(
                (1L..successfulEvents.size.toLong()).toList(),
                successfulEvents.map { it.engineSequence }.sorted(),
            )
        } finally {
            callerPool.shutdownNow()
            processor.close()
        }
    }

    private fun CompletableFuture<List<MatchingEvent>>.await(): List<MatchingEvent> =
        get(5, TimeUnit.SECONDS)

    private fun CompletableFuture<List<MatchingEvent>>.awaitFailure(): Throwable {
        val exception = assertFailsWith<ExecutionException> {
            await()
        }

        return exception.cause ?: exception
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

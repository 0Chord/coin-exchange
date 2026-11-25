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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
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
    fun `before와 handler는 같은 worker에서 직렬 실행되고 대기 중 다른 market은 진행한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val callerThread = Thread.currentThread()
        val firstBeforeStarted = CountDownLatch(1)
        val releaseFirstBefore = CountDownLatch(1)
        val firstHandlerStarted = CountDownLatch(1)
        val releaseFirstHandler = CountDownLatch(1)
        val secondBeforeCalls = AtomicInteger(0)
        val steps = CopyOnWriteArrayList<String>()
        val callbackThreads = CopyOnWriteArrayList<Thread>()
        val ethMarket = MarketId("ETH-KRW")
        val firstExpected = listOf(entered(seq = 1, orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        val secondExpected = listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1))

        try {
            val firstFuture =
                processor.submit(
                    command = submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1),
                    beforeMatching = {
                        callbackThreads.add(Thread.currentThread())
                        steps.add("first-before-start")
                        firstBeforeStarted.countDown()
                        releaseFirstBefore.awaitSignal("first before release")
                        steps.add("first-before-end")
                    },
                    eventHandler = { events ->
                        callbackThreads.add(Thread.currentThread())
                        assertEquals(firstExpected, events)
                        steps.add("first-handler-start")
                        firstHandlerStarted.countDown()
                        releaseFirstHandler.awaitSignal("first handler release")
                        steps.add("first-handler-end")
                    },
                )
            firstBeforeStarted.awaitSignal("first before start")

            val secondFuture =
                processor.submit(
                    command = submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1),
                    beforeMatching = {
                        callbackThreads.add(Thread.currentThread())
                        secondBeforeCalls.incrementAndGet()
                        steps.add("second-before")
                    },
                    eventHandler = { events ->
                        callbackThreads.add(Thread.currentThread())
                        assertEquals(secondExpected, events)
                        steps.add("second-handler")
                    },
                )

            assertFalse(firstFuture.isDone)
            assertFalse(secondFuture.isDone)
            assertEquals(0, secondBeforeCalls.get())
            releaseFirstBefore.countDown()
            firstHandlerStarted.awaitSignal("first handler start")

            assertEquals(
                listOf(entered(market = ethMarket, seq = 1, orderId = "eth-b1", side = Side.BUY, price = 100, quantity = 1)),
                processor
                    .submit(
                        submit(market = ethMarket, orderId = "eth-b1", side = Side.BUY, price = 100, quantity = 1),
                    ).await(),
            )
            assertFalse(firstFuture.isDone)
            assertFalse(secondFuture.isDone)
            assertEquals(0, secondBeforeCalls.get())

            releaseFirstHandler.countDown()
            assertEquals(firstExpected, firstFuture.await())
            assertEquals(secondExpected, secondFuture.await())
            assertEquals(
                listOf(
                    "first-before-start",
                    "first-before-end",
                    "first-handler-start",
                    "first-handler-end",
                    "second-before",
                    "second-handler",
                ),
                steps.toList(),
            )
            assertEquals(4, callbackThreads.size)
            callbackThreads.forEach { thread -> assertSame(callbackThreads.first(), thread) }
            assertNotSame(callerThread, callbackThreads.first())
        } finally {
            releaseFirstBefore.countDown()
            releaseFirstHandler.countDown()
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
                processor
                    .submit(
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
                processor
                    .submit(
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
            val callerFutures =
                (1..commandCount).map { index ->
                    callerPool.submit<List<MatchingEvent>> {
                        start.await()
                        processor
                            .submit(
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

            val events =
                callerFutures.flatMap { future ->
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
        val markets =
            listOf(
                MarketId("BTC-KRW"),
                MarketId("ETH-KRW"),
                MarketId("SOL-KRW"),
            )
        val commandCountPerMarket = 40

        try {
            val callerFutures =
                markets.flatMap { market ->
                    (1..commandCountPerMarket).map { index ->
                        callerPool.submit<List<MatchingEvent>> {
                            start.await()
                            processor
                                .submit(
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

            val events =
                callerFutures.flatMap { future ->
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
            val callerFutures =
                (1..submitCount).map { index ->
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

            val outcomes =
                callerFutures.map { callerFuture ->
                    runCatching {
                        callerFuture.get(5, TimeUnit.SECONDS).await()
                    }
                }
            val successes = outcomes.filter { it.isSuccess }.map { it.getOrThrow() }
            val failures =
                outcomes.mapNotNull { outcome ->
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

            val cancelCaller =
                callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
                    start.await()
                    processor.submit(cancel(orderId = "a1"))
                }
            val buyCaller =
                callerPool.submit<CompletableFuture<List<MatchingEvent>>> {
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

                else -> {
                    error("unexpected cancel result: $cancelEvent")
                }
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
            val failure =
                processor
                    .submit(
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
    fun `before 실패는 engine을 실행하지 않고 같은 주문의 다음 접수를 막지 않는다`() {
        val processor = InMemoryMarketCommandProcessor()
        val expectedFailure = IllegalStateException("funding failed")
        val handlerCalls = AtomicInteger(0)
        val command = submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1)

        try {
            val failure =
                processor
                    .submit(
                        command = command,
                        beforeMatching = { throw expectedFailure },
                        eventHandler = { handlerCalls.incrementAndGet() },
                    ).awaitFailure()

            assertSame(expectedFailure, failure)
            assertEquals(0, handlerCalls.get())
            assertEquals(
                listOf(entered(seq = 1, orderId = "b1", side = Side.BUY, price = 100, quantity = 1)),
                processor.submit(command).await(),
            )
        } finally {
            processor.close()
        }
    }

    @Test
    fun `before 성공 뒤 engine 실패는 같은 market의 대기와 신규 명령만 중단한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val firstBeforeStarted = CountDownLatch(1)
        val releaseFirstBefore = CountDownLatch(1)
        val beforeMarkers = AtomicInteger(0)
        val firstHandlerCalls = AtomicInteger(0)
        val followingBeforeCalls = AtomicInteger(0)
        val followingHandlerCalls = AtomicInteger(0)
        val ethMarket = MarketId("ETH-KRW")

        try {
            val firstFuture =
                processor.submit(
                    command = submit(orderId = "m1", side = Side.BUY, price = 100, quantity = 1, orderType = OrderType.MARKET),
                    beforeMatching = {
                        beforeMarkers.incrementAndGet()
                        firstBeforeStarted.countDown()
                        releaseFirstBefore.awaitSignal("first before release")
                    },
                    eventHandler = { firstHandlerCalls.incrementAndGet() },
                )
            firstBeforeStarted.awaitSignal("first before start")

            val queuedFuture =
                processor.submit(
                    command = submit(orderId = "queued", side = Side.BUY, price = 100, quantity = 1),
                    beforeMatching = { followingBeforeCalls.incrementAndGet() },
                    eventHandler = { followingHandlerCalls.incrementAndGet() },
                )
            assertFalse(queuedFuture.isDone)
            releaseFirstBefore.countDown()

            val firstFailure = firstFuture.awaitFailure()
            assertIs<IllegalArgumentException>(firstFailure)
            assertEquals("only LIMIT order is supported", firstFailure.message)

            val queuedFailure = assertIs<RejectedExecutionException>(queuedFuture.awaitFailure())
            val newFailure =
                assertIs<RejectedExecutionException>(
                    processor
                        .submit(
                            command = submit(orderId = "new", side = Side.BUY, price = 100, quantity = 1),
                            beforeMatching = { followingBeforeCalls.incrementAndGet() },
                            eventHandler = { followingHandlerCalls.incrementAndGet() },
                        ).awaitFailure(),
                )

            assertSame(firstFailure, queuedFailure.cause)
            assertSame(firstFailure, newFailure.cause)
            assertEquals(1, beforeMarkers.get())
            assertEquals(0, firstHandlerCalls.get())
            assertEquals(0, followingBeforeCalls.get())
            assertEquals(0, followingHandlerCalls.get())
            assertEquals(
                listOf(entered(market = ethMarket, seq = 1, orderId = "eth-b1", side = Side.BUY, price = 100, quantity = 1)),
                processor
                    .submit(
                        submit(market = ethMarket, orderId = "eth-b1", side = Side.BUY, price = 100, quantity = 1),
                    ).await(),
            )
        } finally {
            releaseFirstBefore.countDown()
            processor.close()
        }
    }

    @Test
    fun `중복 orderId 실패 후에도 기존 주문은 유지되고 worker는 계속 처리한다`() {
        val processor = InMemoryMarketCommandProcessor()

        try {
            processor.submit(submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 5)).await()

            val failure =
                processor
                    .submit(
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
            processor
                .submit(
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

        val futures =
            (1..commandCount).map { index ->
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

        val events =
            futures.flatMap { future ->
                future.await()
            }

        assertEquals(commandCount, events.size)
        assertEquals((1L..commandCount.toLong()).toList(), events.map { it.engineSequence }.sorted())
    }

    @Test
    fun `close 뒤 latch를 해제하면 기존 handler와 접수된 명령은 완료되고 새 콜백은 실행하지 않는다`() {
        val processor = InMemoryMarketCommandProcessor()
        val firstHandlerStarted = CountDownLatch(1)
        val releaseFirstHandler = CountDownLatch(1)
        val firstHandlerCompletions = AtomicInteger(0)
        val queuedBeforeCalls = AtomicInteger(0)
        val queuedHandlerCalls = AtomicInteger(0)
        val rejectedBeforeCalls = AtomicInteger(0)
        val rejectedHandlerCalls = AtomicInteger(0)
        val firstExpected = listOf(entered(seq = 1, orderId = "a1", side = Side.SELL, price = 100, quantity = 1))
        val secondExpected = listOf(trade(seq = 2, maker = "a1", taker = "b1", side = Side.BUY, price = 100, quantity = 1))

        try {
            val firstFuture =
                processor.submit(
                    command = submit(orderId = "a1", side = Side.SELL, price = 100, quantity = 1),
                    eventHandler = { events ->
                        assertEquals(firstExpected, events)
                        firstHandlerStarted.countDown()
                        releaseFirstHandler.awaitSignal("first handler release")
                        firstHandlerCompletions.incrementAndGet()
                    },
                )
            firstHandlerStarted.awaitSignal("first handler start")
            val queuedFuture =
                processor.submit(
                    command = submit(orderId = "b1", side = Side.BUY, price = 100, quantity = 1),
                    beforeMatching = { queuedBeforeCalls.incrementAndGet() },
                    eventHandler = { events ->
                        assertEquals(secondExpected, events)
                        queuedHandlerCalls.incrementAndGet()
                    },
                )

            processor.close()
            val rejectedFailure =
                processor
                    .submit(
                        command = submit(orderId = "new", side = Side.BUY, price = 100, quantity = 1),
                        beforeMatching = { rejectedBeforeCalls.incrementAndGet() },
                        eventHandler = { rejectedHandlerCalls.incrementAndGet() },
                    ).awaitFailure()

            assertIs<RejectedExecutionException>(rejectedFailure)
            assertEquals("market command processor is closed", rejectedFailure.message)
            assertEquals(0, rejectedBeforeCalls.get())
            assertEquals(0, rejectedHandlerCalls.get())
            assertFalse(firstFuture.isDone)
            assertFalse(queuedFuture.isDone)
            assertEquals(0, firstHandlerCompletions.get())
            assertEquals(0, queuedBeforeCalls.get())

            releaseFirstHandler.countDown()
            assertEquals(firstExpected, firstFuture.await())
            assertEquals(secondExpected, queuedFuture.await())
            assertEquals(1, firstHandlerCompletions.get())
            assertEquals(1, queuedBeforeCalls.get())
            assertEquals(1, queuedHandlerCalls.get())
        } finally {
            releaseFirstHandler.countDown()
            processor.close()
        }
    }

    @Test
    fun `close 이전에 worker가 없었으면 close 이후 submit은 실패한 future를 반환한다`() {
        val processor = InMemoryMarketCommandProcessor()

        processor.close()

        val failure =
            processor
                .submit(
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

        val failure =
            processor
                .submit(
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

        val failure =
            processor
                .submit(
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
            val submitCallers =
                (1..submitCount).map { index ->
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
            val closeCaller =
                callerPool.submit {
                    start.await()
                    processor.close()
                }

            start.countDown()
            closeCaller.get(5, TimeUnit.SECONDS)

            val outcomes =
                submitCallers.map { callerFuture ->
                    runCatching {
                        callerFuture.get(5, TimeUnit.SECONDS).await()
                    }
                }
            val successfulEvents =
                outcomes
                    .filter { it.isSuccess }
                    .flatMap { it.getOrThrow() }
            val failures =
                outcomes.mapNotNull { outcome ->
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

    @Test
    fun `event handler 실패 전에 queue에 들어온 같은 market command도 거부한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val firstHandlerStarted = CountDownLatch(1)
        val releaseFirstHandler = CountDownLatch(1)
        val secondBeforeCalls = AtomicInteger(0)
        val secondHandlerCalls = AtomicInteger(0)

        try {
            val firstFuture =
                processor.submit(
                    submit(
                        orderId = "a1",
                        side = Side.SELL,
                        price = 100,
                        quantity = 1,
                    ),
                ) {
                    firstHandlerStarted.countDown()

                    if (!releaseFirstHandler.await(5, TimeUnit.SECONDS)) {
                        error("first event handler release timed out")
                    }

                    throw IllegalStateException(
                        "event persistence failed",
                    )
                }

            assertTrue(
                firstHandlerStarted.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            )

            val secondFuture =
                processor.submit(
                    submit(
                        orderId = "b1",
                        side = Side.BUY,
                        price = 100,
                        quantity = 1,
                    ),
                    beforeMatching = { secondBeforeCalls.incrementAndGet() },
                ) {
                    secondHandlerCalls.incrementAndGet()
                }

            releaseFirstHandler.countDown()

            val firstFailure = firstFuture.awaitFailure()

            assertEquals(
                "event persistence failed",
                firstFailure.message,
            )

            val secondFailure = secondFuture.awaitFailure()

            assertIs<RejectedExecutionException>(secondFailure)
            assertEquals(
                "event persistence failed",
                secondFailure.cause?.message,
            )
            assertSame(firstFailure, secondFailure.cause)
            assertEquals(0, secondBeforeCalls.get())
            assertEquals(0, secondHandlerCalls.get())
        } finally {
            releaseFirstHandler.countDown()
            processor.close()
        }
    }

    private fun CountDownLatch.awaitSignal(description: String) {
        assertTrue(await(5, TimeUnit.SECONDS), "signal not observed: $description")
    }

    private fun CompletableFuture<List<MatchingEvent>>.await(): List<MatchingEvent> = get(5, TimeUnit.SECONDS)

    private fun CompletableFuture<List<MatchingEvent>>.awaitFailure(): Throwable {
        val exception =
            assertFailsWith<ExecutionException> {
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

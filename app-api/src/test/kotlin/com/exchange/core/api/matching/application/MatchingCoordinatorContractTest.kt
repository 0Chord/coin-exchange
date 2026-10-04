package com.exchange.core.api.matching.application

import com.exchange.core.api.matching.application.port.MatchingEventPublisher
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 마켓 worker를 사용해 조율 경계의 실패, 대기 종료와 이후 작업을 확인한다. */
@Timeout(20)
class MatchingCoordinatorContractTest {
    private val marketId = MarketId("BTC-KRW")
    private val otherMarketId = MarketId("ETH-KRW")

    @Test
    fun `publisher 실패는 원래 오류를 전파하고 after를 생략하며 같은 마켓만 중단한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val expectedFailure = IllegalStateException("publisher failed")
        val afterCalls = AtomicInteger(0)
        val rejectedBeforeCalls = AtomicInteger(0)
        val rejectedAfterCalls = AtomicInteger(0)
        val publishCalls = AtomicInteger(0)
        val coordinator =
            MatchingCoordinator(
                processor,
                object : MatchingEventPublisher {
                    override fun publish(events: List<MatchingEvent>) {
                        publishCalls.incrementAndGet()
                        if (events.single().marketId == marketId) {
                            throw expectedFailure
                        }
                    }
                },
            )

        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    coordinator.process(order(orderId = "failed-ask")) {
                        afterCalls.incrementAndGet()
                    }
                }

            assertSame(expectedFailure, failure)
            assertEquals(0, afterCalls.get())
            assertEquals(1, publishCalls.get())

            val rejection =
                assertFailsWith<RejectedExecutionException> {
                    coordinator.process(
                        order(orderId = "rejected-ask"),
                        beforeMatching = { rejectedBeforeCalls.incrementAndGet() },
                        afterMatching = { rejectedAfterCalls.incrementAndGet() },
                    )
                }

            assertSame(expectedFailure, rejection.cause)
            assertEquals(0, rejectedBeforeCalls.get())
            assertEquals(0, rejectedAfterCalls.get())
            assertEquals(1, publishCalls.get())
            assertEntered(
                coordinator.process(order(market = otherMarketId, orderId = "healthy-ask")),
                market = otherMarketId,
                orderId = "healthy-ask",
                sequence = 1,
            )
            assertEquals(2, publishCalls.get())
        } finally {
            processor.close()
        }
    }

    @Test
    fun `publisher 성공 뒤 after 실패는 성공 표식을 유지하고 같은 마켓을 계속 거절한다`() {
        val processor = InMemoryMarketCommandProcessor()
        val expectedFailure = IllegalStateException("after matching failed")
        val publishedEvents = AtomicReference<List<MatchingEvent>>()
        val afterSawPublishedEvents = AtomicBoolean(false)
        val beforeMarker = AtomicBoolean(false)
        val rejectedBeforeCalls = AtomicInteger(0)
        val rejectedAfterCalls = AtomicInteger(0)
        val coordinator =
            MatchingCoordinator(
                processor,
                object : MatchingEventPublisher {
                    override fun publish(events: List<MatchingEvent>) {
                        if (events.single().marketId == marketId) {
                            publishedEvents.set(events)
                        }
                    }
                },
            )

        try {
            val failure =
                assertFailsWith<IllegalStateException> {
                    coordinator.process(
                        order(orderId = "failed-ask"),
                        beforeMatching = { beforeMarker.set(true) },
                        afterMatching = { events ->
                            afterSawPublishedEvents.set(publishedEvents.get() === events)
                            throw expectedFailure
                        },
                    )
                }

            assertSame(expectedFailure, failure)
            assertTrue(afterSawPublishedEvents.get())
            assertTrue(beforeMarker.get())
            val committedPublishMarker = publishedEvents.get()
            assertEntered(committedPublishMarker, orderId = "failed-ask", sequence = 1)

            repeat(2) { index ->
                val rejection =
                    assertFailsWith<RejectedExecutionException> {
                        coordinator.process(
                            order(orderId = "rejected-ask-$index"),
                            beforeMatching = { rejectedBeforeCalls.incrementAndGet() },
                            afterMatching = { rejectedAfterCalls.incrementAndGet() },
                        )
                    }

                assertSame(expectedFailure, rejection.cause)
            }

            assertEquals(0, rejectedBeforeCalls.get())
            assertEquals(0, rejectedAfterCalls.get())
            assertSame(committedPublishMarker, publishedEvents.get())
            assertTrue(beforeMarker.get())
            assertEntered(
                coordinator.process(order(market = otherMarketId, orderId = "healthy-ask")),
                market = otherMarketId,
                orderId = "healthy-ask",
                sequence = 1,
            )
            assertSame(committedPublishMarker, publishedEvents.get())
        } finally {
            processor.close()
        }
    }

    @Test
    fun `실제 3초 대기가 끝나도 작업과 같은 마켓 큐는 취소되지 않고 발행 해제 뒤 완료된다`() {
        val processor = ObservedProcessor()
        val callers = Executors.newFixedThreadPool(2)
        val publisherStarted = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val beforeMarker = AtomicBoolean(false)
        val afterCalls = AtomicInteger(0)
        val nextBeforeCalls = AtomicInteger(0)
        val nextAfterCalls = AtomicInteger(0)
        val firstCommand = order(orderId = "slow-ask")
        val nextCommand = order(orderId = "queued-ask")
        val coordinator = MatchingCoordinator(processor, blockingPublisher(publisherStarted, releasePublisher))

        try {
            val firstCaller =
                callers.submit<List<MatchingEvent>> {
                    coordinator.process(
                        firstCommand,
                        beforeMatching = { beforeMarker.set(true) },
                        afterMatching = { afterCalls.incrementAndGet() },
                    )
                }
            publisherStarted.awaitSignal("first publisher did not start")
            val firstWorkerFuture = processor.awaitSubmission(firstCommand)

            val callerFailure =
                assertFailsWith<ExecutionException> {
                    firstCaller.get(5, TimeUnit.SECONDS)
                }

            assertIs<TimeoutException>(callerFailure.cause)
            assertFalse(firstWorkerFuture.isCancelled)
            assertFalse(firstWorkerFuture.isDone)
            assertTrue(beforeMarker.get())
            assertEquals(0, afterCalls.get())
            assertEntered(
                coordinator.process(order(market = otherMarketId, orderId = "healthy-ask")),
                market = otherMarketId,
                orderId = "healthy-ask",
                sequence = 1,
            )
            assertFalse(firstWorkerFuture.isDone)
            assertEquals(0, afterCalls.get())

            val nextCaller =
                callers.submit<List<MatchingEvent>> {
                    coordinator.process(
                        nextCommand,
                        beforeMatching = { nextBeforeCalls.incrementAndGet() },
                        afterMatching = { nextAfterCalls.incrementAndGet() },
                    )
                }
            val nextWorkerFuture = processor.awaitSubmission(nextCommand)

            assertFalse(nextWorkerFuture.isDone)
            assertFalse(nextWorkerFuture.isCancelled)
            assertEquals(0, nextBeforeCalls.get())
            assertEquals(0, nextAfterCalls.get())
            assertFalse(firstWorkerFuture.isDone)
            assertEquals(0, afterCalls.get())

            releasePublisher.countDown()

            assertEntered(firstWorkerFuture.get(5, TimeUnit.SECONDS), orderId = "slow-ask", sequence = 1)
            assertEquals(1, afterCalls.get())
            assertTrue(beforeMarker.get())
            assertFalse(firstWorkerFuture.isCancelled)
            assertEntered(nextCaller.get(5, TimeUnit.SECONDS), orderId = "queued-ask", sequence = 2)
            assertTrue(nextWorkerFuture.isDone)
            assertFalse(nextWorkerFuture.isCancelled)
            assertEquals(1, nextBeforeCalls.get())
            assertEquals(1, nextAfterCalls.get())
        } finally {
            releasePublisher.countDown()
            processor.close()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS), "caller threads did not terminate")
            processor.awaitCompletion()
        }
    }

    @Test
    fun `기다리는 호출자 interrupt는 flag와 원인을 보존하고 worker 작업은 계속된다`() {
        val processor = ObservedProcessor()
        val publisherStarted = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val callerFinished = CountDownLatch(1)
        val callerFailure = AtomicReference<Throwable>()
        val callerInterruptRestored = AtomicBoolean(false)
        val beforeMarker = AtomicBoolean(false)
        val afterCalls = AtomicInteger(0)
        val command = order(orderId = "interrupted-ask")
        val coordinator = MatchingCoordinator(processor, blockingPublisher(publisherStarted, releasePublisher))
        val caller =
            Thread({
                try {
                    coordinator.process(
                        command,
                        beforeMatching = { beforeMarker.set(true) },
                        afterMatching = { afterCalls.incrementAndGet() },
                    )
                } catch (error: Throwable) {
                    callerFailure.set(error)
                    callerInterruptRestored.set(Thread.currentThread().isInterrupted)
                } finally {
                    callerFinished.countDown()
                }
            }, "matching-coordinator-interrupt-caller")

        try {
            caller.start()
            publisherStarted.awaitSignal("publisher did not start")
            val workerFuture = processor.awaitSubmission(command)
            assertFalse(workerFuture.isDone)

            caller.interrupt()
            callerFinished.awaitSignal("interrupted caller did not finish")

            val failure = assertIs<IllegalStateException>(callerFailure.get())
            assertIs<InterruptedException>(failure.cause)
            assertTrue(callerInterruptRestored.get())
            assertFalse(workerFuture.isCancelled)
            assertFalse(workerFuture.isDone)
            assertTrue(beforeMarker.get())
            assertEquals(0, afterCalls.get())

            releasePublisher.countDown()

            assertEntered(workerFuture.get(5, TimeUnit.SECONDS), orderId = "interrupted-ask", sequence = 1)
            assertEquals(1, afterCalls.get())
            assertTrue(beforeMarker.get())
            assertFalse(workerFuture.isCancelled)
            assertEntered(coordinator.process(order(orderId = "next-ask")), orderId = "next-ask", sequence = 2)
        } finally {
            releasePublisher.countDown()
            processor.close()
            caller.interrupt()
            caller.join(5_000)
            assertFalse(caller.isAlive, "caller thread did not terminate")
            processor.awaitCompletion()
        }
    }

    private fun blockingPublisher(
        publisherStarted: CountDownLatch,
        releasePublisher: CountDownLatch,
    ): MatchingEventPublisher =
        object : MatchingEventPublisher {
            override fun publish(events: List<MatchingEvent>) {
                if (events.single().marketId == marketId) {
                    publisherStarted.countDown()
                    assertTrue(releasePublisher.await(10, TimeUnit.SECONDS), "publisher release timed out")
                }
            }
        }

    /** submit의 실제 future만 기록하며 완료, 취소와 대기 동작은 바꾸지 않는다. */
    private class ObservedProcessor : MarketCommandProcessor {
        private val delegate = InMemoryMarketCommandProcessor()
        private val futures = ConcurrentHashMap<MatchingCommand, CompletableFuture<List<MatchingEvent>>>()
        private val submissions = ConcurrentHashMap<MatchingCommand, CountDownLatch>()

        override fun submit(
            command: MatchingCommand,
            beforeMatching: (() -> Unit)?,
            eventHandler: (List<MatchingEvent>) -> Unit,
        ): CompletableFuture<List<MatchingEvent>> {
            val future = delegate.submit(command, beforeMatching, eventHandler)
            futures[command] = future
            submissions.computeIfAbsent(command) { CountDownLatch(1) }.countDown()
            return future
        }

        fun awaitSubmission(command: MatchingCommand): CompletableFuture<List<MatchingEvent>> {
            submissions
                .computeIfAbsent(command) { CountDownLatch(1) }
                .awaitSignal("command was not submitted: $command")
            return requireNotNull(futures[command])
        }

        fun awaitCompletion() {
            // 실패한 future도 정리 완료로 세되 아직 실행 중인 작업은 기다린다.
            val completions = futures.values.map { future -> future.handle { _, _ -> Unit } }
            CompletableFuture.allOf(*completions.toTypedArray()).get(5, TimeUnit.SECONDS)
        }

        override fun close() = delegate.close()
    }

    private fun assertEntered(
        events: List<MatchingEvent>,
        market: MarketId = marketId,
        orderId: String,
        sequence: Long,
    ) {
        assertEquals(
            listOf(
                OrderEnteredBook(
                    marketId = market,
                    engineSequence = sequence,
                    orderId = OrderId(orderId),
                    userId = UserId("user-$orderId"),
                    side = Side.SELL,
                    price = Price(100),
                    remainingQuantity = Quantity(5),
                ),
            ),
            events,
        )
    }

    private fun order(
        market: MarketId = marketId,
        orderId: String,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = market,
            orderId = OrderId(orderId),
            userId = UserId("user-$orderId"),
            side = Side.SELL,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(100),
            quantity = Quantity(5),
        )
}

private fun CountDownLatch.awaitSignal(message: String) {
    assertTrue(await(5, TimeUnit.SECONDS), message)
}

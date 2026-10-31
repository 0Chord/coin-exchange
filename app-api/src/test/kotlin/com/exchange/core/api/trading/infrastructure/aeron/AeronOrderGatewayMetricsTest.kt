package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.TradingAdmissionResult
import com.exchange.core.api.trading.application.TradingOrderSubmission
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Price
import io.aeron.Publication
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.distribution.pause.PauseDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 worker와 기존 transport 경계로 업무 결과와 익명 계측의 수명을 함께 확인한다. */
class AeronOrderGatewayMetricsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `요청별 구간은 같은 실제 응답과 연결되고 운영 tag에 요청을 추가하지 않는다`() {
        val transport = Transport()
        val observed = ConcurrentLinkedQueue<GatewayRequestTiming>()
        SimpleMeterRegistry().withRegistry { registry ->
            gateway(transport, registry, observer = GatewayRequestTimingObserver { observed.add(it) }).use { gateway ->
                val original = submission()
                val result = gateway.submit(original)
                transport.awaitOffer()
                transport.respond(accepted(original))
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                awaitDrained(registry)
                val row = observed.single()
                assertEquals(original.requestId, row.requestId)
                assertEquals(original.order.userId.value, row.userId)
                assertEquals(
                    GatewayMetrics.Phase.entries
                        .map { it.tag }
                        .toSet(),
                    row.phaseDurationsNs.keys,
                )
                assertTrue(row.phaseDurationsNs.values.all { it >= 0 })
                assertTrue(row.resultReadyNs - row.enqueuedNs >= 0 && row.completedNs - row.resultReadyNs >= 0)
                assertTrue(registry.meters.all { meter -> meter.id.tags.none { it.key in setOf("userId", "requestId", "orderId") } })
            }
        }
    }

    @Test
    fun `같은 Pending의 재사용과 내용 충돌은 새 전송이나 단계 표본을 만들지 않는다`() {
        val releaseOffer = CountDownLatch(1)
        val transport = Transport(offerAllowed = releaseOffer)
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry)
            try {
                val submission = submission()
                val result = gateway.submit(submission)
                transport.awaitOffer()
                assertSame(result, gateway.submit(submission.copy()))
                assertEquals(
                    TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"),
                    gateway.submit(submission.copy(order = submission.order.copy(price = Price(200_000_000)))).await(),
                )
                assertGauges(registry, queued = 1, inFlight = 0, keys = 1)
                assertEquals(1.0, counter(registry, GatewayMetrics.SUBMISSIONS, "new"))
                assertEquals(1.0, counter(registry, GatewayMetrics.SUBMISSIONS, "reused"))
                assertEquals(1.0, counter(registry, GatewayMetrics.SUBMISSIONS, "conflict"))
                releaseOffer.countDown()
                awaitCondition { gauge(registry, "inflight") == 1.0 }
                transport.respond(accepted(submission))
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                awaitDrained(registry)
                assertGauges(registry, queued = 0, inFlight = 0, keys = 0)
                assertEquals(1, transport.offerCalls.get())
                GatewayMetrics.Phase.entries.forEach { phase ->
                    assertEquals(if (phase == GatewayMetrics.Phase.SUBMIT_LOCK) 3L else 1L, timer(registry, phase).count(), phase.tag)
                }
                assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "accepted"))
            } finally {
                releaseOffer.countDown()
                gateway.close()
            }
        }
    }

    @Test
    fun `backpressure와 admin 재시도는 최초 바이트와 단계 경계를 유지한다`() {
        val transport = Transport(listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 17L))
        SimpleMeterRegistry().withRegistry { registry ->
            gateway(transport, registry).use { gateway ->
                val submission = submission()
                val result = gateway.submit(submission)
                val bytes = transport.awaitOffer()
                assertSame(bytes, transport.awaitOffer())
                assertSame(bytes, transport.awaitOffer())
                awaitCondition { gauge(registry, "inflight") == 1.0 }
                transport.respond(accepted(submission))
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                awaitDrained(registry)
                listOf("accepted", "back_pressured", "admin_action").forEach {
                    assertEquals(
                        1.0,
                        registry
                            .get(GatewayMetrics.OFFERS)
                            .tag("status", it)
                            .counter()
                            .count(),
                    )
                }
                listOf(GatewayMetrics.Phase.QUEUE, GatewayMetrics.Phase.ENCODE, GatewayMetrics.Phase.OFFER_WAIT).forEach {
                    assertEquals(1L, timer(registry, it).count())
                }
            }
        }
    }

    @Test
    fun `변환 실패에는 offer와 응답 표본을 0으로 채우지 않는다`() {
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            gateway(transport, registry).use { gateway ->
                assertEquals(TradingAdmissionResult.InvalidInput("INVALID_COMMAND"), gateway.submit(submission("R".repeat(257))).await())
                awaitDrained(registry)
                assertEquals(0, transport.offerCalls.get())
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.ENCODE).count())
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.RESULT_READY).count())
                assertEquals(0L, timer(registry, GatewayMetrics.Phase.OFFER_WAIT).count())
                assertEquals(0L, timer(registry, GatewayMetrics.Phase.RESPONSE_WAIT).count())
                assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "invalid"))
                assertGauges(registry, queued = 0, inFlight = 0, keys = 0)
            }
        }
    }

    @Test
    fun `future 취소는 실제 응답 처리나 Pending terminal 계측을 취소하지 않는다`() {
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            gateway(transport, registry).use { gateway ->
                val submission = submission()
                val future = gateway.submit(submission)
                transport.awaitOffer()
                assertTrue(future.cancel(false))
                transport.respond(accepted(submission))
                awaitCondition { counter(registry, GatewayMetrics.RESULTS, "accepted") == 1.0 }
                awaitDrained(registry)
                assertTrue(future.isCancelled)
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.RESULT_READY).count())
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                assertEquals(1, transport.offerCalls.get())
            }
        }
    }

    @Test
    fun `동기 future callback이 느려도 결과 준비와 완료 호출 표본을 섞지 않는다`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry)
            try {
                val submission = submission()
                val future = gateway.submit(submission)
                transport.awaitOffer()
                future.thenRun {
                    callbackEntered.countDown()
                    check(releaseCallback.await(3, TimeUnit.SECONDS))
                }
                transport.respond(accepted(submission))
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                assertEquals(TradingAdmissionResult.Accepted(1), future.await())
                assertGauges(registry, queued = 0, inFlight = 0, keys = 0)
                assertEquals(1.0, gauge(registry, "active_completions"), "자료구조는 비어도 완료 호출이 진행 중이다")
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.RESULT_READY).count())
                assertEquals(0L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                val readyTime = timer(registry, GatewayMetrics.Phase.RESULT_READY).totalTime(TimeUnit.NANOSECONDS)
                releaseCallback.countDown()
                awaitDrained(registry)
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                assertEquals(readyTime, timer(registry, GatewayMetrics.Phase.RESULT_READY).totalTime(TimeUnit.NANOSECONDS))
            } finally {
                releaseCallback.countDown()
                gateway.close()
            }
        }
    }

    @Test
    fun `동시 제출과 응답 처리가 겹쳐도 중복 future와 종료 경계의 Gauge를 보존한다`() {
        val transport = Transport()
        val workers = Executors.newFixedThreadPool(4)
        val readyToAck = ConcurrentHashMap<String, TradingOrderSubmission>()
        val reserved = ConcurrentLinkedQueue<CompletableFuture<TradingAdmissionResult>>()
        val negativeSamples = ConcurrentLinkedQueue<String>()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry, queueCapacity = 8, maxInFlight = 4)
            // close가 Gauge 등록을 제거해도 같은 상태 객체의 최종값을 확인한다.
            val sizes =
                listOf("queued", "inflight", "reserved_keys", "active_completions")
                    .associateWith { registry.get("${GatewayMetrics.PREFIX}.$it").gauge() }

            fun sampleSizes() {
                sizes.forEach { (name, gauge) ->
                    val value = gauge.value()
                    if (value < 0) negativeSamples.add("$name=$value")
                }
            }
            try {
                val producers =
                    (0..2).map { producer ->
                        CompletableFuture.runAsync({
                            repeat(3) { round ->
                                val pending = submission("P$producer-$round")
                                val first = gateway.submit(pending)
                                val duplicate = gateway.submit(pending.copy())
                                assertSame(first, duplicate)
                                reserved.add(first)
                                reserved.add(duplicate)
                                sampleSizes()
                                // 이 키의 중복 확인을 마친 뒤 consumer가 응답하도록 허용한다.
                                readyToAck[pending.requestId] = pending
                                val expected =
                                    if (round < 2) TradingAdmissionResult.Accepted(1) else TradingAdmissionResult.Unknown("GATEWAY_CLOSED")
                                assertEquals(expected, first.await())
                                sampleSizes()
                            }
                        }, workers)
                    }
                val consumer =
                    CompletableFuture.runAsync({
                        val codec = AdmissionCommandCodec()
                        repeat(9) {
                            val offered = codec.decode(transport.awaitOffer())
                            awaitCondition { readyToAck.containsKey(offered.requestId) }
                            val pending = checkNotNull(readyToAck[offered.requestId])
                            sampleSizes()
                            // 각 producer는 앞선 응답을 받은 뒤 다음 제출을 한다. 마지막 셋은 close가 완료한다.
                            if (!pending.requestId.endsWith("-2")) transport.respond(accepted(pending))
                            sampleSizes()
                        }
                    }, workers)
                consumer.get(3, TimeUnit.SECONDS)
                awaitCondition {
                    sizes.getValue("queued").value() == 0.0 && sizes.getValue("inflight").value() == 3.0 &&
                        sizes.getValue("reserved_keys").value() == 3.0
                }
                gateway.close()
                producers.forEach { it.get(3, TimeUnit.SECONDS) }

                assertEquals(18, reserved.size)
                assertTrue(reserved.all { it.isDone && !it.isCompletedExceptionally })
                assertTrue(negativeSamples.isEmpty(), negativeSamples.joinToString())
                sizes.forEach { (name, gauge) -> assertEquals(0.0, gauge.value(), name) }
                assertEquals(9.0, counter(registry, GatewayMetrics.SUBMISSIONS, "new"))
                assertEquals(9.0, counter(registry, GatewayMetrics.SUBMISSIONS, "reused"))
                assertEquals(6.0, counter(registry, GatewayMetrics.RESULTS, "accepted"))
                assertEquals(3.0, counter(registry, GatewayMetrics.RESULTS, "unknown_closed"))
                assertEquals(9L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                assertEquals(1, transport.closeCalls.get())
            } finally {
                try {
                    gateway.close()
                } finally {
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test
    fun `한 완료 callback이 막혀도 다른 응답과 다음 주문 전송은 진행한다`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry)
            try {
                val first = submission()
                val firstResult = gateway.submit(first)
                transport.awaitOffer()
                val callback =
                    firstResult.thenRun {
                        callbackThread.set(Thread.currentThread())
                        callbackEntered.countDown()
                        check(releaseCallback.await(3, TimeUnit.SECONDS))
                    }
                transport.respond(accepted(first))
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                assertTrue(callbackThread.get().isVirtual)

                val second = submission("R2")
                val secondResult = gateway.submit(second)
                transport.awaitOffer()
                transport.respond(accepted(second))
                assertEquals(TradingAdmissionResult.Accepted(1), secondResult.await())
                val third = submission("R3")
                val thirdResult = gateway.submit(third)
                transport.awaitOffer()
                transport.respond(accepted(third))
                assertEquals(TradingAdmissionResult.Accepted(1), thirdResult.await())
                assertFalse(callback.isDone, "첫 callback을 풀기 전에도 뒤의 응답과 전송이 끝난다")
                awaitCondition { gauge(registry, "active_completions") == 1.0 }
                releaseCallback.countDown()
                callback.get(3, TimeUnit.SECONDS)
                awaitDrained(registry)
                assertEquals(3L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
            } finally {
                releaseCallback.countDown()
                gateway.close()
            }
        }
    }

    @Test
    fun `완료 슬롯이 가득 차면 worker에서 결과를 보존하고 슬롯 반환 뒤 다시 넘긴다`() {
        val enteredCallbacks = CountDownLatch(2)
        val releaseCallbacks = CountDownLatch(1)
        val inlineThread = AtomicReference<Thread>()
        val recoveredThread = AtomicReference<Thread>()
        val heldThreads = ConcurrentLinkedQueue<Thread>()
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry, queueCapacity = 1, maxInFlight = 1)
            try {
                val heldResults =
                    (1..2).map { index ->
                        val pending = submission("held-$index")
                        val result = gateway.submit(pending)
                        transport.awaitOffer()
                        val callback =
                            result.thenRun {
                                heldThreads.add(Thread.currentThread())
                                enteredCallbacks.countDown()
                                check(releaseCallbacks.await(3, TimeUnit.SECONDS))
                            }
                        transport.respond(accepted(pending))
                        assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                        callback
                    }
                assertTrue(enteredCallbacks.await(3, TimeUnit.SECONDS))
                assertEquals(2.0, gauge(registry, "active_completions"))
                val fallback = submission("fallback")
                val fallbackResult = gateway.submit(fallback)
                transport.awaitOffer()
                val fallbackCallback = fallbackResult.thenRun { inlineThread.set(Thread.currentThread()) }
                transport.respond(accepted(fallback))
                fallbackCallback.get(3, TimeUnit.SECONDS)
                assertEquals(TradingAdmissionResult.Accepted(1), fallbackResult.await())
                assertEquals("aeron-order-gateway", inlineThread.get().name)
                assertFalse(inlineThread.get().isVirtual)

                releaseCallbacks.countDown()
                heldResults.forEach { it.get(3, TimeUnit.SECONDS) }
                awaitDrained(registry)
                awaitCondition { heldThreads.all { !it.isAlive } }
                val recovered = submission("recovered")
                val recoveredResult = gateway.submit(recovered)
                transport.awaitOffer()
                val recoveredCallback = recoveredResult.thenRun { recoveredThread.set(Thread.currentThread()) }
                transport.respond(accepted(recovered))
                recoveredCallback.get(3, TimeUnit.SECONDS)
                assertEquals(TradingAdmissionResult.Accepted(1), recoveredResult.await())
                assertTrue(recoveredThread.get().isVirtual)
                awaitDrained(registry)
                assertEquals(4.0, counter(registry, GatewayMetrics.RESULTS, "accepted"))
                assertEquals(4L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
            } finally {
                releaseCallbacks.countDown()
                gateway.close()
            }
        }
    }

    @Test
    fun `종료는 미확인 결과를 전달하고 진행 중인 완료 callback까지 기다린다`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry)
            try {
                val first = submission()
                val firstResult = gateway.submit(first)
                transport.awaitOffer()
                firstResult.thenRun {
                    callbackEntered.countDown()
                    check(releaseCallback.await(3, TimeUnit.SECONDS))
                }
                transport.respond(accepted(first))
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                val pending = gateway.submit(submission("R2"))
                transport.awaitOffer()
                val closed = CompletableFuture.runAsync { gateway.close() }

                assertTrue(transport.closeStarted.await(3, TimeUnit.SECONDS))
                assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), pending.await())
                awaitCondition { gauge(registry, "active_completions") == 1.0 }
                assertFalse(closed.isDone, "연결을 닫아도 진행 중인 완료 callback은 아직 남아 있다")
                releaseCallback.countDown()
                closed.get(3, TimeUnit.SECONDS)
                assertEquals(TradingAdmissionResult.Accepted(1), firstResult.await())
                assertEquals(2L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
            } finally {
                releaseCallback.countDown()
                gateway.close()
            }
        }
    }

    @Test
    fun `완료 callback이 종료를 불러도 worker와 서로 기다리지 않는다`() {
        val closeReturned = CountDownLatch(1)
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry)
            try {
                val pending = submission()
                val result = gateway.submit(pending)
                transport.awaitOffer()
                val callback =
                    result.thenRun {
                        gateway.close()
                        closeReturned.countDown()
                    }
                transport.respond(accepted(pending))

                assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
                callback.get(3, TimeUnit.SECONDS)
                gateway.close()
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                assertEquals(1, transport.closeCalls.get())
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
            } finally {
                gateway.close()
            }
        }
    }

    @Test
    fun `완료 callback이 interrupt에 끝나도 종료 기한 초과는 실패로 알리고 결과를 보존한다`() {
        assertCompletionShutdownTimeout(ignoreInterrupt = false)
    }

    @Test
    fun `완료 callback이 interrupt를 무시하면 종료 실패 뒤에도 진행 중 Gauge를 유지한다`() {
        assertCompletionShutdownTimeout(ignoreInterrupt = true)
    }

    @Test
    fun `슬롯 포화로 worker callback이 막혀도 외부 close는 제한 시간에 실패한다`() {
        val enteredVirtual = CountDownLatch(2)
        val enteredInline = CountDownLatch(1)
        val releaseCallbacks = CountDownLatch(1)
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry, queueCapacity = 1, maxInFlight = 1, startupTimeoutMs = 10)
            try {
                val results = mutableListOf<CompletableFuture<TradingAdmissionResult>>()
                val callbacks =
                    (1..3).map { index ->
                        val pending = submission("held-$index")
                        val result = gateway.submit(pending)
                        results += result
                        transport.awaitOffer()
                        val callback =
                            result.thenRun {
                                if (Thread.currentThread().isVirtual) enteredVirtual.countDown() else enteredInline.countDown()
                                releaseCallbacks.await()
                            }
                        transport.respond(accepted(pending))
                        assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                        callback
                    }
                assertTrue(enteredVirtual.await(3, TimeUnit.SECONDS))
                assertTrue(enteredInline.await(3, TimeUnit.SECONDS))
                val failure =
                    CompletableFuture.supplyAsync { runCatching { gateway.close() }.exceptionOrNull() }.get(3, TimeUnit.SECONDS)
                assertIs<IllegalStateException>(failure)
                assertEquals(3.0, gauge(registry, "active_completions"))
                assertEquals(0L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())

                releaseCallbacks.countDown()
                callbacks.forEach { it.get(3, TimeUnit.SECONDS) }
                gateway.close()
                results.forEach { assertEquals(TradingAdmissionResult.Accepted(1), it.await()) }
                assertEquals(3.0, counter(registry, GatewayMetrics.RESULTS, "accepted"))
                assertEquals(0.0, counter(registry, GatewayMetrics.RESULTS, "unknown_closed"))
                assertEquals(3L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                assertEquals(null, registry.find("${GatewayMetrics.PREFIX}.active_completions").gauge())
            } finally {
                releaseCallbacks.countDown()
                runCatching { gateway.close() }
            }
        }
    }

    @Test
    fun `timeout 결과가 나와도 이전 연결 close와 clear 전까지 예약 Gauge가 유지된다`() {
        val releaseClose = CountDownLatch(1)
        val transport = Transport(closeAllowed = releaseClose)
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway = gateway(transport, registry, timeoutMs = 200)
            try {
                val future = gateway.submit(submission())
                transport.awaitOffer()
                assertEquals(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true), future.await())
                assertTrue(transport.closeStarted.await(3, TimeUnit.SECONDS))
                assertGauges(registry, queued = 0, inFlight = 1, keys = 1)
                assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "unknown_timeout"))
                assertEquals(0L, timer(registry, GatewayMetrics.Phase.RESPONSE_WAIT).count())
            } finally {
                releaseClose.countDown()
                gateway.close()
            }
            assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "unknown_timeout"))
            assertEquals(0.0, counter(registry, GatewayMetrics.RESULTS, "unknown_closed"))
        }
    }

    @Test
    fun `close 실패로 disconnect가 반복되어도 같은 Pending terminal은 한 번이다`() {
        val transport = Transport(listOf(Publication.CLOSED), failFirstClose = true)
        SimpleMeterRegistry().withRegistry { registry ->
            gateway(transport, registry).use { gateway ->
                val result = gateway.submit(submission())
                assertEquals(TradingAdmissionResult.Unknown("CONNECTION_LOST"), result.await())
                awaitCondition { transport.closeCalls.get() >= 2 }
            }
            assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "unknown_connection"))
            assertEquals(0.0, counter(registry, GatewayMetrics.RESULTS, "unknown_closed"))
            assertEquals(1L, timer(registry, GatewayMetrics.Phase.RESULT_READY).count())
            assertEquals(1L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
            assertEquals(0L, timer(registry, GatewayMetrics.Phase.OFFER_WAIT).count())
        }
    }

    @Test
    fun `계측 기록 예외가 실제 offer 응답 future와 연결 수명을 바꾸지 않는다`() {
        val transport = Transport()
        FailingRegistry().withRegistry { registry ->
            gateway(transport, registry).use { gateway ->
                val submission = submission()
                val result = gateway.submit(submission)
                transport.awaitOffer()
                transport.respond(accepted(submission))
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                assertEquals(1, transport.offerCalls.get())
                assertEquals(0, transport.closeCalls.get())
                assertEquals(1.0, gauge(registry, "degraded"))
            }
        }
    }

    private fun assertCompletionShutdownTimeout(ignoreInterrupt: Boolean) {
        val callbackEntered = CountDownLatch(1)
        val callbackInterrupted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val completionThread = AtomicReference<Thread>()
        val observed = ConcurrentLinkedQueue<GatewayRequestTiming>()
        val transport = Transport()
        SimpleMeterRegistry().withRegistry { registry ->
            val gateway =
                gateway(
                    transport,
                    registry,
                    observer = GatewayRequestTimingObserver { observed.add(it) },
                    startupTimeoutMs = 10,
                )
            var callback: CompletableFuture<Void>? = null
            try {
                val pending = submission()
                val result = gateway.submit(pending)
                transport.awaitOffer()
                callback =
                    result.thenRun {
                        completionThread.set(Thread.currentThread())
                        callbackEntered.countDown()
                        while (true) {
                            try {
                                releaseCallback.await()
                                break
                            } catch (_: InterruptedException) {
                                callbackInterrupted.countDown()
                                if (!ignoreInterrupt) break
                            }
                        }
                    }
                transport.respond(accepted(pending))
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
                val failure =
                    CompletableFuture.supplyAsync { runCatching { gateway.close() }.exceptionOrNull() }.get(3, TimeUnit.SECONDS)
                assertIs<IllegalStateException>(failure)
                assertIs<IllegalStateException>(failure.cause, "worker join을 마치고 완료 drain 실패를 전달한다")
                assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS))
                assertEquals(TradingAdmissionResult.Accepted(1), result.await())
                if (ignoreInterrupt) {
                    assertFalse(callback.isDone)
                    assertEquals(1.0, gauge(registry, "active_completions"))
                    assertEquals(0L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                    assertTrue(observed.isEmpty())
                    releaseCallback.countDown()
                    callback.get(3, TimeUnit.SECONDS)
                    awaitDrained(registry)
                    assertEquals(0.0, gauge(registry, "active_completions"), "늦은 완료도 한 번만 차감한다")
                } else {
                    callback.get(3, TimeUnit.SECONDS)
                    assertEquals(null, registry.find("${GatewayMetrics.PREFIX}.active_completions").gauge())
                }
                awaitCondition { !completionThread.get().isAlive }
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.FUTURE_COMPLETION).count())
                assertEquals(1L, timer(registry, GatewayMetrics.Phase.RESULT_READY).count())
                assertEquals(1.0, counter(registry, GatewayMetrics.RESULTS, "accepted"))
                assertEquals(0.0, counter(registry, GatewayMetrics.RESULTS, "unknown_closed"))
                assertEquals(1, observed.size)
            } finally {
                releaseCallback.countDown()
                callback?.get(3, TimeUnit.SECONDS)
                runCatching { gateway.close() }
            }
        }
    }

    private fun gateway(
        transport: Transport,
        registry: SimpleMeterRegistry,
        timeoutMs: Long = 5_000,
        observer: GatewayRequestTimingObserver? = null,
        queueCapacity: Int = 8,
        maxInFlight: Int = 8,
        startupTimeoutMs: Long = 1_000,
    ): AeronOrderGateway =
        AeronOrderGateway(
            AeronGatewaySettings(
                directory.resolve("run"),
                "0=127.0.0.1:19700",
                "127.0.0.1:19701",
                queueCapacity,
                maxInFlight,
                timeoutMs,
                startupTimeoutMs,
            ),
            DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT)),
            GatewayMetrics(registry, observer),
        ) { transport }

    private fun submission(requestId: String = "R1"): TradingOrderSubmission =
        admissionCommand(requestId, "O1").let { TradingOrderSubmission(requestId, it.order) }

    private fun accepted(submission: TradingOrderSubmission): AdmissionResultPayload.Accepted =
        AdmissionResultPayload.Accepted(
            submission.order.userId.value,
            submission.requestId,
            submission.order.marketId.value,
            submission.order.orderId.value,
            1,
        )

    private fun timer(
        registry: SimpleMeterRegistry,
        phase: GatewayMetrics.Phase,
    ): Timer = registry.get(GatewayMetrics.DURATION).tag("phase", phase.tag).timer()

    private fun counter(
        registry: SimpleMeterRegistry,
        name: String,
        outcome: String,
    ): Double =
        registry
            .get(name)
            .tag("outcome", outcome)
            .counter()
            .count()

    private fun gauge(
        registry: SimpleMeterRegistry,
        suffix: String,
    ): Double = registry.get("${GatewayMetrics.PREFIX}.$suffix").gauge().value()

    private fun assertGauges(
        registry: SimpleMeterRegistry,
        queued: Int,
        inFlight: Int,
        keys: Int,
    ) {
        assertEquals(queued.toDouble(), gauge(registry, "queued"))
        assertEquals(inFlight.toDouble(), gauge(registry, "inflight"))
        assertEquals(keys.toDouble(), gauge(registry, "reserved_keys"))
    }

    private fun awaitDrained(registry: SimpleMeterRegistry) {
        awaitCondition {
            listOf("queued", "inflight", "reserved_keys", "active_completions").all { gauge(registry, it) == 0.0 }
        }
    }

    private fun CompletableFuture<TradingAdmissionResult>.await(): TradingAdmissionResult = get(3, TimeUnit.SECONDS)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Gateway 계측 조건 시간이 초과됐습니다" }
            Thread.sleep(1)
        }
    }

    private class Transport(
        offerResults: List<Long> = emptyList(),
        private val offerAllowed: CountDownLatch? = null,
        private val closeAllowed: CountDownLatch? = null,
        private val failFirstClose: Boolean = false,
    ) : GatewayOrderTransport {
        val offerCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closeStarted = CountDownLatch(1)
        private val positions = ConcurrentLinkedQueue(offerResults)
        private val sent = LinkedBlockingQueue<ByteArray>()
        private val responses = ConcurrentLinkedQueue<AdmissionResultPayload>()

        override fun offer(bytes: ByteArray): Long {
            offerCalls.incrementAndGet()
            sent.add(bytes)
            offerAllowed?.let { check(it.await(3, TimeUnit.SECONDS)) }
            return positions.poll() ?: 1
        }

        override fun poll(receive: (AdmissionResultPayload) -> Unit): Int {
            var count = 0
            while (true) {
                receive(responses.poll() ?: return count)
                count++
            }
        }

        override fun close() {
            val calls = closeCalls.incrementAndGet()
            closeStarted.countDown()
            if (failFirstClose && calls == 1) error("close failure")
            closeAllowed?.let { check(it.await(3, TimeUnit.SECONDS)) }
        }

        fun awaitOffer(): ByteArray = checkNotNull(sent.poll(3, TimeUnit.SECONDS))

        fun respond(response: AdmissionResultPayload) {
            responses.add(response)
        }
    }

    private class FailingRegistry : SimpleMeterRegistry() {
        override fun newTimer(
            id: Meter.Id,
            distributionStatisticConfig: DistributionStatisticConfig,
            pauseDetector: PauseDetector,
        ): Timer {
            val timer = super.newTimer(id, distributionStatisticConfig, pauseDetector)
            return object : Timer by timer {
                override fun record(
                    amount: Long,
                    unit: TimeUnit,
                ) = throw IllegalStateException("metrics down")
            }
        }
    }

    private inline fun <T : SimpleMeterRegistry, R> T.withRegistry(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }
}

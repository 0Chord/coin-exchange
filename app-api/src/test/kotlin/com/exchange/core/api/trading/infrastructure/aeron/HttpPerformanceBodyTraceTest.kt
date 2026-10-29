package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HttpPerformanceBodyTraceTest {
    @Test
    fun `본문 인수 시각을 기록해도 원래 바이트와 요청 개수는 바뀌지 않는다`() {
        val clock = AtomicLong()
        val trace = HttpPerformanceBodyTrace { clock.addAndGet(10) }
        val original = "주문 원문"
        val publisher = trace.wrap(HttpRequest.BodyPublishers.ofString(original))
        val bytes = ArrayList<Byte>()
        var completed = false
        publisher.subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

                override fun onNext(item: ByteBuffer) {
                    while (item.hasRemaining()) bytes.add(item.get())
                }

                override fun onError(throwable: Throwable): Unit = throw AssertionError(throwable)

                override fun onComplete() {
                    completed = true
                }
            },
        )
        assertEquals(original, bytes.toByteArray().toString(StandardCharsets.UTF_8))
        assertEquals(original.toByteArray().size.toLong(), publisher.contentLength())
        assertEquals(true, completed)
        assertEquals(1, trace.subscriptions)
        assertEquals(10L, trace.startedNs)
        assertEquals(20L, trace.completedNs)
    }

    @Test
    fun `본문 발행 실패를 정상 완료 시각으로 채우지 않는다`() {
        val trace = HttpPerformanceBodyTrace { 42 }
        val failure = IllegalStateException("publisher failed")
        val delegate =
            object : HttpRequest.BodyPublisher {
                override fun contentLength(): Long = 0

                override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) = subscriber.onError(failure)
            }
        var observed: Throwable? = null
        trace.wrap(delegate).subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) = Unit

                override fun onNext(item: ByteBuffer) = Unit

                override fun onComplete() = error("must not complete")

                override fun onError(throwable: Throwable) {
                    observed = throwable
                }
            },
        )
        assertSame(failure, observed)
        assertEquals(42L, trace.startedNs)
        assertNull(trace.completedNs)
    }
}

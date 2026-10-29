package com.exchange.core.api.trading.infrastructure.aeron

import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** HTTP client가 본문을 요구하고 인수한 시각이다. 실제 TCP write 완료로 부르지 않는다. */
internal class HttpPerformanceBodyTrace(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class TimePoint(
        val ns: Long,
    )

    private val started = AtomicReference<TimePoint?>()
    private val completed = AtomicReference<TimePoint?>()
    private val count = AtomicInteger()
    val startedNs: Long? get() = started.get()?.ns
    val completedNs: Long? get() = completed.get()?.ns
    val subscriptions: Int get() = count.get()

    fun wrap(delegate: HttpRequest.BodyPublisher): HttpRequest.BodyPublisher =
        object : HttpRequest.BodyPublisher {
            override fun contentLength(): Long = delegate.contentLength()

            override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
                count.incrementAndGet()
                started.compareAndSet(null, TimePoint(nanoTime()))
                delegate.subscribe(
                    object : Flow.Subscriber<ByteBuffer> {
                        override fun onSubscribe(subscription: Flow.Subscription) = subscriber.onSubscribe(subscription)

                        override fun onNext(item: ByteBuffer) = subscriber.onNext(item)

                        override fun onError(throwable: Throwable) = subscriber.onError(throwable)

                        override fun onComplete() {
                            completed.compareAndSet(null, TimePoint(nanoTime()))
                            subscriber.onComplete()
                        }
                    },
                )
            }
        }
}

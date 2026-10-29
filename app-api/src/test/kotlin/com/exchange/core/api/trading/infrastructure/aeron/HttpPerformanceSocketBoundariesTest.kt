package com.exchange.core.api.trading.infrastructure.aeron

import org.apache.tomcat.util.net.NioChannel
import org.apache.tomcat.util.net.NioEndpoint
import org.apache.tomcat.util.net.SocketBufferHandler
import org.apache.tomcat.util.net.SocketEvent
import org.apache.tomcat.util.threads.ThreadPoolExecutor
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpPerformanceSocketBoundariesTest {
    @Test
    fun `요청에 연결한 socket event는 중복 없이 16개까지만 보존하고 초과를 오류로 표시한다`() {
        val span = HttpPerformanceMvcHandle(0)
        val events = (1L..17L).map { trace(it) }
        for (event in events) {
            HttpPerformanceSocketContext.executing(event) {
                synchronized(span) {
                    HttpPerformanceSocketContext.bind(span)
                    HttpPerformanceSocketContext.bind(span)
                }
            }
        }
        assertEquals(events.take(16), span.socketDispatches)
        assertEquals(setOf("SOCKET_DISPATCH_LIMIT_EXCEEDED"), span.errors)
        assertNull(HttpPerformanceSocketContext.executing.get())
    }

    @Test
    fun `cached NioChannel이 다른 연결로 reset되면 이전 연결의 event에 쓰기를 붙이지 않는다`() {
        val endpoint = HttpPerformanceSocketEndpoint()
        val channel = HttpPerformanceSocketEndpoint.ObservedChannel(SocketBufferHandler(1024, 1024, false))
        val first = wrapper(endpoint, channel = channel)
        channel.reset(null, first)
        val firstTrace = trace(connectionId = first.getServletConnection("http/1.1", "").connectionId)
        HttpPerformanceSocketContext.executing(firstTrace) { channel.observedWrite { 7L } }
        val firstSnapshot = firstTrace.snapshot()

        val second = wrapper(endpoint, channel = channel)
        channel.reset(null, second)
        HttpPerformanceSocketContext.executing(firstTrace) { channel.observedWrite { 11L } }
        assertEquals(firstSnapshot, firstTrace.snapshot())
        val secondTrace = trace(2L, second.getServletConnection("http/1.1", "").connectionId)
        HttpPerformanceSocketContext.executing(secondTrace) { channel.observedWrite { 13L } }
        assertEquals(13L, secondTrace.nativeWriteBytes)
        assertEquals(1, secondTrace.nativeWriteCalls)
        assertNull(HttpPerformanceSocketContext.executing.get())
    }

    @Test
    fun `executor가 거절한 event는 실행 시각을 만들지 않고 원래 제출 문맥을 복구한다`() {
        val endpoint = HttpPerformanceSocketEndpoint()
        var captured: HttpPerformanceSocketDispatch? = null
        val native =
            Executor {
                captured = HttpPerformanceSocketContext.submitting.get()
                throw RejectedExecutionException("expected diagnostic rejection")
            }
        endpoint.executor = native
        val previous = trace()
        HttpPerformanceSocketContext.submitting.set(previous)
        try {
            assertFalse(endpoint.processSocket(wrapper(endpoint), SocketEvent.OPEN_READ, true))
            assertSame(previous, HttpPerformanceSocketContext.submitting.get())
        } finally {
            HttpPerformanceSocketContext.submitting.remove()
        }
        assertSame(native, endpoint.executor)
        val rejected = assertNotNull(captured)
        assertTrue(rejected.rejected)
        assertSubmissionCall(rejected)
        assertNull(rejected.workerStartedNs)
        assertNull(rejected.handlerEnteredNs)
        assertEquals("127.0.0.1", rejected.snapshot()["remoteAddr"])
        assertEquals(51000, rejected.snapshot()["remotePort"])
        assertEquals(8080, rejected.snapshot()["localPort"])
    }

    @Test
    fun `이미 닫힌 socket event는 worker가 실행되어도 처리 진입과 handler 시각을 만들지 않는다`() {
        val endpoint = HttpPerformanceSocketEndpoint()
        var captured: HttpPerformanceSocketDispatch? = null
        val native =
            Executor { command ->
                captured = HttpPerformanceSocketContext.submitting.get()
                command.run()
            }
        endpoint.executor = native
        assertTrue(endpoint.processSocket(wrapper(endpoint, closed = true), SocketEvent.OPEN_READ, true))
        val skipped = assertNotNull(captured)
        assertNotNull(skipped.workerStartedNs)
        assertNotNull(skipped.workerReturnedNs)
        assertSubmissionCall(skipped)
        assertTrue(checkNotNull(skipped.workerStartedNs) >= checkNotNull(skipped.submittedNs))
        assertTrue(checkNotNull(skipped.workerReturnedNs) >= checkNotNull(skipped.workerStartedNs))
        // The delegate executes inline, so submission returns only after the worker wrapper returns.
        assertTrue(checkNotNull(skipped.submissionReturnedNs) >= checkNotNull(skipped.workerReturnedNs))
        assertNull(skipped.socketProcessingStartedNs)
        assertNull(skipped.handlerEnteredNs)
        assertNull(HttpPerformanceSocketContext.submitting.get())
        assertNull(HttpPerformanceSocketContext.executing.get())
        assertSame(native, endpoint.executor)
    }

    @Test
    fun `native Tomcat pool 제출 시각을 보존하고 endpoint 종료가 실제 worker도 종료한다`() {
        val endpoint =
            HttpPerformanceSocketEndpoint().apply {
                name = "socket-boundary-native-executor-test"
                minSpareThreads = 1
                maxThreads = 1
                createExecutor()
            }
        val native = endpoint.executor as ThreadPoolExecutor
        val captured = AtomicReference<HttpPerformanceSocketDispatch>()
        val closed =
            object : NioEndpoint.NioSocketWrapper(NioChannel(SocketBufferHandler(0, 0, false)), endpoint) {
                override fun isClosed(): Boolean {
                    captured.set(checkNotNull(HttpPerformanceSocketContext.executing.get()))
                    return true
                }

                override fun getRemoteAddr(): String = "127.0.0.1"

                override fun getRemotePort(): Int = 51000

                override fun getLocalPort(): Int = 8080
            }
        try {
            assertTrue(endpoint.processSocket(closed, SocketEvent.OPEN_READ, true))
            // A single native worker executes this fence after the observed socket task, without sleeping.
            val residue =
                native
                    .submit<Pair<HttpPerformanceSocketDispatch?, HttpPerformanceSocketDispatch?>> {
                        HttpPerformanceSocketContext.submitting.get() to HttpPerformanceSocketContext.executing.get()
                    }.get(3, TimeUnit.SECONDS)
            assertEquals(null to null, residue)
            val trace = assertNotNull(captured.get())
            assertSubmissionCall(trace)
            assertTrue(checkNotNull(trace.workerStartedNs) >= checkNotNull(trace.submittedNs))
            assertTrue(checkNotNull(trace.workerReturnedNs) >= checkNotNull(trace.workerStartedNs))
            assertFalse(trace.rejected)
            assertSame(native, endpoint.executor)
            assertNull(HttpPerformanceSocketContext.submitting.get())
            assertNull(HttpPerformanceSocketContext.executing.get())
        } finally {
            endpoint.shutdownExecutor()
            assertNull(endpoint.executor)
            assertTrue(native.isShutdown)
            assertTrue(native.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(native.isTerminated)
        }
    }

    @Test
    fun `빈 NioChannel 쓰기와 zero 반환은 실제 바이트 쓰기의 시각과 횟수를 늘리지 않는다`() {
        val trace = trace()
        var now = 100L
        val clock = { now.also { now += 10L } }
        val channel = NioChannel(SocketBufferHandler(0, 0, false))
        // Tomcat returns zero here without any SocketChannel having been assigned.
        assertEquals(0L, observeHttpPerformanceNativeWrite(trace, clock) { channel.write(ByteBuffer.allocate(0)).toLong() })
        assertEquals(0, trace.nativeWriteCalls)
        assertNull(trace.firstNativeWriteStartedNs)
        assertNull(trace.lastNativeWriteReturnedNs)

        assertEquals(7L, observeHttpPerformanceNativeWrite(trace, clock) { 7L })
        val positive = trace.snapshot()
        assertEquals(0L, observeHttpPerformanceNativeWrite(trace, clock) { 0L })
        assertEquals(positive, trace.snapshot())
        assertEquals(1, trace.nativeWriteCalls)
        assertEquals(7L, trace.nativeWriteBytes)
        assertEquals(10L, trace.nativeWriteDurationNs)
        assertEquals(120L, trace.firstNativeWriteStartedNs)
        assertEquals(130L, trace.lastNativeWriteReturnedNs)
    }

    @Test
    fun `실패한 쓰기는 원래 예외를 유지하고 성공 바이트와 쓰기 종료 시각을 만들지 않는다`() {
        val trace = trace()
        val original = IllegalStateException("write failed")
        val thrown =
            assertFailsWith<IllegalStateException> {
                observeHttpPerformanceNativeWrite(trace, { 100L }) { throw original }
            }
        assertSame(original, thrown)
        assertEquals(1, trace.nativeWriteFailures)
        assertEquals(0, trace.nativeWriteCalls)
        assertEquals(0L, trace.nativeWriteBytes)
        assertEquals(0L, trace.nativeWriteDurationNs)
        assertNull(trace.firstNativeWriteStartedNs)
        assertNull(trace.lastNativeWriteReturnedNs)
    }

    private fun assertSubmissionCall(trace: HttpPerformanceSocketDispatch) {
        val submitted = assertNotNull(trace.submittedNs)
        val returned = assertNotNull(trace.submissionReturnedNs)
        assertTrue(returned >= submitted)
        assertEquals(returned, trace.snapshot()["submissionReturnedNs"])
        assertEquals(returned - submitted, trace.snapshot()["submissionCallNs"])
    }

    private fun trace(
        id: Long = 1L,
        connectionId: String = "connection-1",
    ): HttpPerformanceSocketDispatch = HttpPerformanceSocketDispatch(id, connectionId, "OPEN_READ", true, 0L, "test")

    private fun wrapper(
        endpoint: NioEndpoint,
        closed: Boolean = false,
        channel: NioChannel = NioChannel(SocketBufferHandler(0, 0, false)),
    ): NioEndpoint.NioSocketWrapper =
        object : NioEndpoint.NioSocketWrapper(channel, endpoint) {
            override fun isClosed(): Boolean = closed

            override fun getRemoteAddr(): String = "127.0.0.1"

            override fun getRemotePort(): Int = 51000

            override fun getLocalPort(): Int = 8080
        }
}

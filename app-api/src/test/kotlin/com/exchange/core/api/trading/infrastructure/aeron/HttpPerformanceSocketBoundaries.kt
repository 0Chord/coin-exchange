package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.config.TradingGatewayHttpProtocol
import org.apache.coyote.AbstractProtocol
import org.apache.coyote.Processor
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState
import org.apache.tomcat.util.net.NioChannel
import org.apache.tomcat.util.net.NioEndpoint
import org.apache.tomcat.util.net.SocketBufferHandler
import org.apache.tomcat.util.net.SocketEvent
import org.apache.tomcat.util.net.SocketProcessorBase
import org.apache.tomcat.util.net.SocketWrapperBase
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal const val HTTP_PERFORMANCE_NATIVE_WRITE_BOUNDARY =
    "positive-return NioChannel write accepted bytes; excludes empty/zero attempts; failures counted separately; not TCP ACK"

/** A socket event, not a packet-arrival timestamp or necessarily one HTTP request. */
internal class HttpPerformanceSocketDispatch(
    val id: Long,
    val connectionId: String,
    val event: String,
    val dispatch: Boolean,
    val admittedNs: Long,
    val callerThread: String,
    val remoteAddr: String? = null,
    val remotePort: Int? = null,
    val localPort: Int? = null,
) {
    var submittedNs: Long? = null
    var submissionReturnedNs: Long? = null
    var workerStartedNs: Long? = null
    var socketProcessingStartedNs: Long? = null
    var handlerEnteredNs: Long? = null
    var handlerReturnedNs: Long? = null
    var workerReturnedNs: Long? = null
    var processorCreateStartedNs: Long? = null
    var processorCreateReturnedNs: Long? = null
    var registerStartedNs: Long? = null
    var registerReturnedNs: Long? = null
    var state: String? = null
    var rejected = false
    var nativeWriteCalls = 0
    var nativeWriteBytes = 0L
    var nativeWriteFailures = 0
    var nativeWriteDurationNs = 0L
    var firstNativeWriteStartedNs: Long? = null
    var lastNativeWriteReturnedNs: Long? = null

    fun snapshot(): Map<String, Any?> =
        synchronized(this) {
            fun duration(
                start: Long?,
                end: Long?,
            ): Long? = start?.let { first -> end?.minus(first) }
            mapOf(
                "dispatchId" to id,
                "connectionId" to connectionId,
                "socketEvent" to event,
                "dispatched" to dispatch,
                "callerThread" to callerThread,
                "remoteAddr" to remoteAddr,
                "remotePort" to remotePort,
                "localPort" to localPort,
                "admittedNs" to admittedNs,
                "submittedNs" to submittedNs,
                "submissionReturnedNs" to submissionReturnedNs,
                "submissionCallNs" to duration(submittedNs, submissionReturnedNs),
                "workerStartedNs" to workerStartedNs,
                "socketProcessingStartedNs" to socketProcessingStartedNs,
                "handlerEnteredNs" to handlerEnteredNs,
                "handlerReturnedNs" to handlerReturnedNs,
                "workerReturnedNs" to workerReturnedNs,
                "processorCreateStartedNs" to processorCreateStartedNs,
                "processorCreateReturnedNs" to processorCreateReturnedNs,
                "registerStartedNs" to registerStartedNs,
                "registerReturnedNs" to registerReturnedNs,
                "executorWaitNs" to duration(submittedNs, workerStartedNs),
                "socketLockAndEntryNs" to duration(workerStartedNs, socketProcessingStartedNs),
                "processorCreateNs" to duration(processorCreateStartedNs, processorCreateReturnedNs),
                "processorRegisterNs" to duration(registerStartedNs, registerReturnedNs),
                "returnedState" to state,
                "rejected" to rejected,
                "nativeWriteCalls" to nativeWriteCalls,
                "nativeWriteBoundary" to HTTP_PERFORMANCE_NATIVE_WRITE_BOUNDARY,
                "nativeWriteBytes" to nativeWriteBytes,
                "nativeWriteFailures" to nativeWriteFailures,
                "nativeWriteDurationNs" to nativeWriteDurationNs,
                "firstNativeWriteStartedNs" to firstNativeWriteStartedNs,
                "lastNativeWriteReturnedNs" to lastNativeWriteReturnedNs,
            )
        }
}

/** A zero result may be NioChannel's empty-buffer short circuit; it must never advance a write point. */
internal inline fun observeHttpPerformanceNativeWrite(
    trace: HttpPerformanceSocketDispatch?,
    nanoTime: () -> Long = System::nanoTime,
    action: () -> Long,
): Long {
    if (trace == null) return action()
    val started = nanoTime()
    var bytes = 0L
    var failed = true
    try {
        bytes = action()
        failed = false
        return bytes
    } finally {
        val returned = nanoTime()
        synchronized(trace) {
            if (failed) {
                trace.nativeWriteFailures++
            } else if (bytes > 0) {
                trace.nativeWriteCalls++
                trace.nativeWriteBytes += bytes
                trace.nativeWriteDurationNs += returned - started
                if (trace.firstNativeWriteStartedNs == null) trace.firstNativeWriteStartedNs = started
                trace.lastNativeWriteReturnedNs = returned
            }
        }
    }
}

internal object HttpPerformanceSocketContext {
    val submitting = ThreadLocal<HttpPerformanceSocketDispatch?>()
    val executing = ThreadLocal<HttpPerformanceSocketDispatch?>()

    /** Called only under this request's existing monitor, after its identity was validated. */
    fun bind(span: HttpPerformanceMvcHandle) {
        val current = executing.get() ?: return
        if (span.socketDispatches.any { it === current }) return
        if (span.socketDispatches.size >= 16) {
            span.errors.add("SOCKET_DISPATCH_LIMIT_EXCEEDED")
        } else {
            span.socketDispatches.add(current)
        }
    }

    fun <T> executing(
        trace: HttpPerformanceSocketDispatch?,
        action: () -> T,
    ): T {
        val previous = executing.get()
        executing.set(trace)
        try {
            return action()
        } finally {
            if (previous == null) executing.remove() else executing.set(previous)
        }
    }
}

/** Public no-arg constructor is required by Tomcat ProtocolHandler.create(String). Test classpath only. */
class HttpPerformanceSocketProtocol : TradingGatewayHttpProtocol(HttpPerformanceSocketEndpoint()) {
    init {
        val observedHandler = ObservedHandler(this)
        endpoint.handler = observedHandler
        handler = observedHandler
    }

    override fun createProcessor(): Processor {
        val trace = HttpPerformanceSocketContext.executing.get()
        trace?.let { synchronized(it) { it.processorCreateStartedNs = System.nanoTime() } }
        try {
            return super.createProcessor()
        } finally {
            trace?.let { synchronized(it) { it.processorCreateReturnedNs = System.nanoTime() } }
        }
    }

    private class ObservedHandler(
        protocol: AbstractProtocol<NioChannel>,
    ) : GatewayConnectionHandler(protocol) {
        override fun process(
            wrapper: SocketWrapperBase<NioChannel>?,
            status: SocketEvent,
        ): SocketState {
            val trace = HttpPerformanceSocketContext.executing.get()
            trace?.let { synchronized(it) { it.handlerEnteredNs = System.nanoTime() } }
            try {
                val state = super.process(wrapper, status)
                trace?.let { synchronized(it) { it.state = state.name } }
                return state
            } finally {
                trace?.let { synchronized(it) { it.handlerReturnedNs = System.nanoTime() } }
            }
        }

        override fun register(processor: Processor) {
            val trace = HttpPerformanceSocketContext.executing.get()
            trace?.let { synchronized(it) { it.registerStartedNs = System.nanoTime() } }
            try {
                super.register(processor)
            } finally {
                trace?.let { synchronized(it) { it.registerReturnedNs = System.nanoTime() } }
            }
        }
    }
}

internal class HttpPerformanceSocketEndpoint : NioEndpoint() {
    private val sequence = AtomicLong()

    @Volatile private var observedExecutor: ObservedExecutor? = null

    override fun processSocket(
        wrapper: SocketWrapperBase<NioChannel>?,
        event: SocketEvent,
        dispatch: Boolean,
    ): Boolean {
        if (wrapper == null) return super.processSocket(null, event, dispatch)
        val admitted = System.nanoTime()
        val trace =
            HttpPerformanceSocketDispatch(
                sequence.incrementAndGet(),
                wrapper.getServletConnection("http/1.1", "").connectionId,
                event.name,
                dispatch,
                admitted,
                Thread.currentThread().name,
                wrapper.remoteAddr,
                wrapper.remotePort,
                wrapper.localPort,
            )
        val previous = HttpPerformanceSocketContext.submitting.get()
        HttpPerformanceSocketContext.submitting.set(trace)
        try {
            return super.processSocket(wrapper, event, dispatch).also { accepted ->
                if (!accepted) synchronized(trace) { trace.rejected = true }
            }
        } finally {
            if (previous ==
                null
            ) {
                HttpPerformanceSocketContext.submitting.remove()
            } else {
                HttpPerformanceSocketContext.submitting.set(previous)
            }
        }
    }

    /** The native executor field, lifecycle, settings and introspection remain owned by Tomcat. */
    override fun getExecutor(): Executor? {
        val native = super.getExecutor() ?: return null
        if (HttpPerformanceSocketContext.submitting.get() == null) return native
        var observed = observedExecutor
        if (observed == null || observed.delegate !== native) {
            observed = ObservedExecutor(native)
            observedExecutor = observed
        }
        return observed
    }

    override fun createSocketProcessor(
        wrapper: SocketWrapperBase<NioChannel>,
        event: SocketEvent,
    ): SocketProcessorBase<NioChannel> = ObservedProcessor(wrapper, event)

    private inner class ObservedProcessor(
        wrapper: SocketWrapperBase<NioChannel>,
        event: SocketEvent,
    ) : SocketProcessor(wrapper, event) {
        override fun doRun() {
            // Capture before super clears/recycles this mutable SocketProcessor.
            val trace = HttpPerformanceSocketContext.submitting.get() ?: HttpPerformanceSocketContext.executing.get()
            trace?.let { synchronized(it) { it.socketProcessingStartedNs = System.nanoTime() } }
            HttpPerformanceSocketContext.executing(trace) { super.doRun() }
        }
    }

    private inner class ObservedExecutor(
        val delegate: Executor,
    ) : Executor {
        override fun execute(command: Runnable) {
            val trace = HttpPerformanceSocketContext.submitting.get()
            if (command !is ObservedProcessor || trace == null) {
                delegate.execute(command)
                return
            }
            synchronized(trace) { trace.submittedNs = System.nanoTime() }
            try {
                delegate.execute {
                    synchronized(trace) { trace.workerStartedNs = System.nanoTime() }
                    try {
                        HttpPerformanceSocketContext.executing(trace) { command.run() }
                    } finally {
                        synchronized(trace) { trace.workerReturnedNs = System.nanoTime() }
                    }
                }
            } finally {
                synchronized(trace) { trace.submissionReturnedNs = System.nanoTime() }
            }
        }
    }

    override fun createChannel(buffer: SocketBufferHandler): NioChannel =
        if (isSSLEnabled) super.createChannel(buffer) else ObservedChannel(buffer)

    internal class ObservedChannel(
        buffer: SocketBufferHandler,
    ) : NioChannel(buffer) {
        private var connectionId: String? = null

        override fun reset(
            channel: SocketChannel?,
            wrapper: NioSocketWrapper,
        ) {
            super.reset(channel, wrapper)
            connectionId = wrapper.getServletConnection("http/1.1", "").connectionId
        }

        override fun write(src: ByteBuffer): Int = observedWrite { super.write(src).toLong() }.toInt()

        override fun write(
            srcs: Array<out ByteBuffer>,
            offset: Int,
            length: Int,
        ): Long = observedWrite { super.write(srcs, offset, length) }

        internal fun observedWrite(action: () -> Long): Long {
            val trace = HttpPerformanceSocketContext.executing.get()?.takeIf { it.connectionId == connectionId }
            return observeHttpPerformanceNativeWrite(trace, action = action)
        }
    }
}

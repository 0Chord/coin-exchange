package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** WAS가 전용 Driver와 자금 세션을 소유한다. 매칭 전달은 자금 노드의 작업이 담당한다. */
internal class SplitFundsGatewayTransport(
    settings: AeronGatewaySettings,
    private val expectedFundsLineage: ClusterLineage,
    private val onReply: (BoundedTradingReply) -> Unit,
    connectionFactory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
) : BoundedGatewayTransport {
    private val directory = Files.createTempDirectory(settings.runRoot, "split-funds-client-")
    private val failure = AtomicReference<Throwable?>()
    private val driver: MediaDriver
    private val connection: SplitOrderDeliveryConnection
    private var lastKeepAlive = System.nanoTime()
    private var described = false

    init {
        var openedDriver: MediaDriver? = null
        var openedConnection: SplitOrderDeliveryConnection? = null
        try {
            openedDriver =
                MediaDriver.launch(
                    MediaDriver
                        .Context()
                        .aeronDirectoryName(directory.resolve("driver").toString())
                        .dirDeleteOnStart(false)
                        .dirDeleteOnShutdown(true)
                        .socketRcvbufLength(TradingAeronRuntime.SOCKET_BUFFER_BYTES)
                        .socketSndbufLength(TradingAeronRuntime.SOCKET_BUFFER_BYTES)
                        .initialWindowLength(TradingAeronRuntime.RECEIVER_WINDOW_BYTES)
                        .threadingMode(ThreadingMode.SHARED)
                        .sharedIdleStrategy(TradingAeronRuntime.idleStrategy())
                        .errorHandler { failure.compareAndSet(null, it) },
                )
            val endpoint =
                SplitOrderDeliveryEndpoint(
                    openedDriver.aeronDirectoryName(),
                    settings.ingressEndpoints,
                    settings.egressEndpoint,
                    SplitTradingNodeSettings.FUNDS_CLUSTER_ID,
                    2,
                )
            val timeoutNs = TimeUnit.MILLISECONDS.toNanos(settings.startupTimeoutMs)
            val deadline = System.nanoTime() + timeoutNs
            val attempt = connectionFactory.begin(endpoint, timeoutNs, ::receive)
            var attemptFailure: Throwable? = null
            try {
                val idle = TradingAeronRuntime.idleStrategy()
                while (System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) {
                    checkDriver()
                    openedConnection = attempt.poll()
                    if (openedConnection != null) break
                    idle.idle()
                }
                checkNotNull(openedConnection) { "split funds gateway connection timed out" }
            } catch (error: Throwable) {
                attemptFailure = error
                throw error
            } finally {
                try {
                    attempt.close()
                } catch (cleanup: Throwable) {
                    val original = attemptFailure
                    if (original == null) throw cleanup
                    if (original !== cleanup) original.addSuppressed(cleanup)
                }
            }
            driver = checkNotNull(openedDriver)
            connection = checkNotNull(openedConnection)
            // 업무 correlation은 1부터 시작한다. 첫 금융 요청 전에 상대의 자금 소유권을 확인한다.
            val description = codec.encode(0, SplitOrderDeliveryCommand.DescribePeer)
            var offered = false
            val idle = TradingAeronRuntime.idleStrategy()
            while (!described && System.nanoTime() - deadline < 0 && !Thread.currentThread().isInterrupted) {
                if (!offered) {
                    val result = offer(description)
                    offered = result > 0
                    check(
                        offered || result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION ||
                            result == Publication.NOT_CONNECTED,
                    ) { "split funds gateway description publication is unavailable: $result" }
                }
                idle.idle(poll())
            }
            check(described) { "split funds gateway description timed out" }
        } catch (error: Throwable) {
            closeAfterFailure(error) { openedConnection?.close() }
            closeAfterFailure(error) { openedDriver?.close() }
            closeAfterFailure(error, ::deleteDirectory)
            throw error
        }
    }

    override fun offer(bytes: ByteArray): Long {
        checkDriver()
        check(!connection.isClosed) { "split funds gateway connection is closed" }
        return connection.offer(bytes)
    }

    override fun poll(): Int {
        checkDriver()
        check(!connection.isClosed) { "split funds gateway connection is closed" }
        val work = connection.poll()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
            connection.keepAlive()
            lastKeepAlive = now
        }
        return work
    }

    private fun receive(reply: SplitOrderDeliveryReply) {
        require(reply.role == TradingServiceRole.FUNDS && reply.lineage == expectedFundsLineage) {
            "split funds gateway reply owner differs"
        }
        if (reply.correlationId == 0L) {
            require(
                reply.code == "DESCRIBED" && reply.control == null && reply.ack == null &&
                    reply.expectedSequence == null && reply.cancellation == null,
            ) { "split funds gateway requires its peer description" }
            described = true
            return
        }
        check(described) { "split funds gateway peer is not described" }
        val control = requireNotNull(reply.control) { "split funds gateway requires a control reply" }
        require(reply.ack == null && reply.expectedSequence == null && reply.cancellation == null) {
            "split funds gateway received a delivery reply"
        }
        require(
            control.correlationId == reply.correlationId && control.lineage == expectedFundsLineage &&
                control.code == reply.code && control.appliedLogPosition == reply.appliedLogPosition,
        ) { "split funds gateway control envelope differs" }
        // 만료된 correlation은 gateway의 대기 목록에서 제외되어 다른 HTTP 요청을 완료하지 않는다.
        onReply(control)
    }

    private fun checkDriver() {
        failure.get()?.let { throw IllegalStateException("split funds gateway driver failed", it) }
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            try {
                driver.close()
            } finally {
                deleteDirectory()
            }
        }
    }

    private fun deleteDirectory() {
        if (Files.exists(directory)) {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun closeAfterFailure(
        original: Throwable,
        close: () -> Unit,
    ) {
        try {
            close()
        } catch (cleanup: Throwable) {
            if (cleanup !== original) original.addSuppressed(cleanup)
        }
    }

    companion object {
        private val codec = SplitOrderDeliveryCodec(2)

        fun encodeCommand(
            correlationId: Long,
            command: BoundedTradingCommand,
        ): ByteArray {
            require(
                command is BoundedTradingCommand.Admit || command is BoundedTradingCommand.Acknowledge ||
                    command is BoundedTradingCommand.Rotate,
            ) { "HTTP gateway only submits orders and receipt controls" }
            return codec.encode(correlationId, SplitOrderDeliveryCommand.Control(command))
        }
    }
}

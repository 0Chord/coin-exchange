package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.InboxRejection
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import io.aeron.cluster.ConsensusModule.Configuration.SESSION_LIMIT_MSG
import io.aeron.cluster.client.ClusterException
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.TimeoutException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** WAS가 전용 Driver와 자금 세션을 소유한다. 매칭 전달은 자금 노드의 작업이 담당한다. */
internal class SplitFundsGatewayTransport(
    settings: AeronGatewaySettings,
    private val expectedFundsLineage: ClusterLineage,
    private val onReply: (BoundedTradingReply) -> Unit,
    connectionFactory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
    private val onCancellation: (SplitOrderDeliveryReply) -> Unit = { error("취소 응답을 받을 대기 경로가 없습니다") },
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
                if (openedConnection == null) throw BoundedGatewayConnectionLost("split funds gateway connection timed out")
            } catch (error: Throwable) {
                attemptFailure = error
                // Aeron 1.53.3의 접속 단절, 세션 만원과 버전 오류는 같은 예외 타입을 사용한다.
                val reason = (error as? ClusterException)?.message?.removePrefix("ERROR - ")
                if (reason == "unexpected loss of connection to cluster" || reason == SESSION_LIMIT_MSG) {
                    throw BoundedGatewayConnectionLost("split funds gateway could not open a session", error)
                }
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
            if (!described) throw BoundedGatewayConnectionLost("split funds gateway description timed out")
        } catch (error: Throwable) {
            closeAfterFailure(error) { openedConnection?.close() }
            closeAfterFailure(error) { openedDriver?.close() }
            closeAfterFailure(error, ::deleteDirectory)
            if (error !is BoundedGatewayConnectionLost && error.isAeronTimeout()) {
                throw BoundedGatewayConnectionLost("split funds gateway connection timed out", error)
            }
            throw error
        }
    }

    override val leadershipTermId: Long?
        get() {
            val cluster = connection.aeronCluster ?: return null
            return if (cluster.isClosed || cluster.ingressPublication().isClosed) null else cluster.leadershipTermId()
        }

    override fun offer(bytes: ByteArray): Long {
        checkDriver()
        checkSession()
        return when (val result = connection.offer(bytes)) {
            Publication.CLOSED -> {
                // 리더 변경 중 기존 publication만 닫힐 수 있다. 세션이 열려 있으면 새 리더 응답을 계속 읽는다.
                checkSession()
                Publication.NOT_CONNECTED
            }

            Publication.MAX_POSITION_EXCEEDED -> {
                throw BoundedGatewayConnectionLost("split funds gateway publication position exhausted")
            }

            else -> {
                result
            }
        }
    }

    override fun poll(): Int {
        checkDriver()
        checkSession()
        val work =
            try {
                connection.poll()
            } catch (error: RuntimeException) {
                if (error.isAeronTimeout()) throw BoundedGatewayConnectionLost("split funds gateway session timed out", error)
                throw error
            }
        checkSession()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
            try {
                connection.keepAlive()
            } catch (error: ClusterException) {
                throw BoundedGatewayConnectionLost("split funds gateway could not keep the session alive", error)
            }
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
        require(reply.ack == null && reply.expectedSequence == null) {
            "split funds gateway received a delivery reply"
        }
        val control = reply.control
        if (control == null) {
            val receipt = reply.cancellation
            require(if (receipt == null) reply.code in cancellationRefusals else reply.code == receipt.code.name) {
                "분리 Gateway의 취소 응답 코드가 일치하지 않습니다"
            }
            onCancellation(reply)
            return
        }
        require(
            reply.cancellation == null && control.correlationId == reply.correlationId && control.lineage == expectedFundsLineage &&
                control.code == reply.code && control.appliedLogPosition == reply.appliedLogPosition,
        ) { "split funds gateway control envelope differs" }
        // 만료된 correlation은 gateway의 대기 목록에서 제외되어 다른 HTTP 요청을 완료하지 않는다.
        onReply(control)
    }

    private fun checkDriver() {
        failure.get()?.let { throw BoundedGatewayConnectionLost("split funds gateway driver failed", it) }
    }

    private fun checkSession() {
        if (connection.isClosed) throw BoundedGatewayConnectionLost("split funds gateway connection is closed")
    }

    // 클라이언트 오류 처리기가 받은 Aeron 시간 초과는 연결 구현에서 한 번 감싸서 전파한다.
    private fun Throwable.isAeronTimeout(): Boolean =
        this is TimeoutException || (this is IllegalStateException && cause is TimeoutException)

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
        private val cancellationRefusals =
            setOf("EXECUTION_CONTEXT_MISMATCH", "EXHAUSTED", "NOT_READY", "UNSUPPORTED_COMMAND") + InboxRejection.entries.map { it.name }

        fun encodeCancellation(
            correlationId: Long,
            command: SplitOrderDeliveryCommand.Cancel,
        ): ByteArray = codec.encode(correlationId, command)

        fun encodeCommand(
            correlationId: Long,
            command: BoundedTradingCommand,
        ): ByteArray {
            require(
                command is BoundedTradingCommand.Admit || command is BoundedTradingCommand.Acknowledge ||
                    command is BoundedTradingCommand.Rotate || command == BoundedTradingCommand.Progress,
            ) { "gateway only permits order, receipt and shutdown progress commands" }
            return codec.encode(correlationId, SplitOrderDeliveryCommand.Control(command))
        }
    }
}

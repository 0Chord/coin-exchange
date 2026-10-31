package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.UserId
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.TimeoutException
import org.agrona.concurrent.UnsafeBuffer
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BoundedResponseUnconfirmedException(
    val transportAccepted: Boolean,
) : IllegalStateException("bounded request result is unconfirmed; retry the same token and body")

/** 연결과 polling은 한 소유자가 담당한다. 노드의 driver와는 별도인 driver를 소유한다. */
internal class BoundedAeronClient private constructor(
    runRoot: java.nio.file.Path,
    ingressEndpoints: String,
    egressEndpoint: String,
    startupTimeoutMs: Long,
    private val resultTimeoutMs: Long,
    private val keepAliveMs: Long,
    private val onResponse: (BoundedTradingReply) -> Unit = {},
) : AutoCloseable {
    constructor(
        config: DevelopmentFundingSettings,
        onResponse: (BoundedTradingReply) -> Unit = {
        },
    ) : this(
        config.runRoot,
        config.ingressEndpoints,
        "${config.advertisedHost}:0",
        config.startupTimeoutMs,
        config.resultTimeoutMs,
        config.keepAliveMs,
        onResponse,
    )
    constructor(
        config: AeronGatewaySettings,
        onResponse: (BoundedTradingReply) -> Unit = {
        },
    ) : this(
        config.runRoot,
        config.ingressEndpoints,
        config.egressEndpoint,
        config.startupTimeoutMs,
        config.resultTimeoutMs,
        500,
        onResponse,
    )

    private val codec = BoundedTradingCodec()
    private val stateCodec = BoundedTradingStateCodec()
    private val directory = Files.createTempDirectory(runRoot, "bounded-client-")
    private val failure = AtomicReference<Throwable?>()
    private val driver =
        MediaDriver.launch(
            MediaDriver
                .Context()
                .aeronDirectoryName(directory.resolve("driver").toString())
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(false)
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(TradingAeronRuntime.idleStrategy())
                .errorHandler { failure.compareAndSet(null, it) },
        )
    private var expectedCorrelation: Long? = null
    private var received: BoundedTradingReply? = null
    private var nextCorrelation = 1L
    private var lastKeepAlive = System.nanoTime()
    internal val connection: AeronCluster

    init {
        try {
            connection =
                connectInitialBoundedCluster(
                    TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs),
                    contextFactory = {
                        AeronCluster
                            .Context()
                            .aeronDirectoryName(driver.aeronDirectoryName())
                            .ingressChannel("aeron:udp?term-length=${TradingAeronRuntime.TERM_LENGTH_BYTES}")
                            .ingressEndpoints(ingressEndpoints)
                            .egressChannel("aeron:udp?endpoint=$egressEndpoint")
                            .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs))
                            .errorHandler { failure.compareAndSet(null, it) }
                            .egressListener { _, _, buffer, offset, length, _ ->
                                require(length in 9..BoundedTradingCodec.MAX_MESSAGE_BYTES) { "invalid bounded response length" }
                                val response = codec.decodeResponse(ByteArray(length).also { buffer.getBytes(offset, it) })
                                if (response.correlationId == expectedCorrelation) received = response
                                onResponse(response)
                            }
                    },
                    failure = { failure.get() },
                )
        } catch (error: Throwable) {
            try {
                driver.close()
                Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    fun offer(
        correlationId: Long,
        command: BoundedTradingCommand,
    ): Long {
        val bytes = codec.encode(correlationId, command)
        return connection.offer(UnsafeBuffer(bytes), 0, bytes.size)
    }

    fun poll(): Int {
        failure.get()?.let { throw IllegalStateException("bounded transport failed", it) }
        val work = connection.pollEgress()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(keepAliveMs)) {
            connection.sendKeepAlive()
            lastKeepAlive = now
        }
        return work
    }

    fun request(command: BoundedTradingCommand): BoundedTradingReply {
        check(expectedCorrelation == null) { "finish the previous request before starting another" }
        val correlation = nextCorrelation.also { nextCorrelation = Math.addExact(it, 1) }
        val bytes = codec.encode(correlation, command)
        val buffer = UnsafeBuffer(bytes)
        expectedCorrelation = correlation
        received = null
        var accepted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(resultTimeoutMs)
        val idle = TradingAeronRuntime.idleStrategy()
        try {
            while (System.nanoTime() - deadline < 0 && !connection.isClosed && failure.get() == null) {
                if (!accepted) {
                    val result = connection.offer(buffer, 0, bytes.size)
                    if (result > 0) {
                        accepted = true
                    } else if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                        break
                    }
                }
                idle.idle(poll())
                received?.let { return it }
            }
            throw BoundedResponseUnconfirmedException(accepted)
        } finally {
            expectedCorrelation = null
            received = null
        }
    }

    fun initialize(manifest: TradingClusterManifest) = request(BoundedTradingCommand.Initialize(stateCodec.encodeManifest(manifest)))

    fun fund(command: DevelopmentBalanceCommandPayload) = request(BoundedTradingCommand.Funding(command))

    fun admit(
        token: RequestToken,
        intent: OrderIntent,
    ) = request(BoundedTradingCommand.Admit(token, intent))

    fun acknowledge(
        userId: UserId,
        channel: Int,
        epoch: Long,
        through: Long,
    ) = request(BoundedTradingCommand.Acknowledge(userId, channel, epoch, through))

    fun rotate(
        userId: UserId,
        channel: Int,
        expectedEpoch: Long,
    ) = request(BoundedTradingCommand.Rotate(userId, channel, expectedEpoch))

    fun progress() = request(BoundedTradingCommand.Progress)

    fun quarantine(
        position: Long,
        reason: String,
    ) = request(BoundedTradingCommand.Quarantine(position, reason))

    override fun close() {
        try {
            connection.close()
        } finally {
            driver.close()
            Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/**
 * 금융 요청이 생기기 전에 탐색을 재시도한다. 오래된 leader 리다이렉트는 짧게 시도한 뒤
 * 포기하지만, 이미 연결된 세션은 설정한 timeout을 유지한다.
 * Aeron 1.53.3의 AsyncConnect.close는 미완료 시도를 정리하며, DONE 이후에는 아무 작업도 하지 않는다.
 */
internal fun connectInitialBoundedCluster(
    startupTimeoutNs: Long,
    contextFactory: () -> AeronCluster.Context,
    failure: () -> Throwable? = { null },
    nanoTime: () -> Long = System::nanoTime,
    open: (AeronCluster.Context) -> AeronCluster.AsyncConnect = AeronCluster::asyncConnect,
    idle: () -> Unit = TradingAeronRuntime.idleStrategy()::idle,
): AeronCluster {
    require(startupTimeoutNs > 0)
    val deadline = nanoTime() + startupTimeoutNs
    var lastTimeout: TimeoutException? = null
    while (true) {
        failure()?.let { throw it }
        check(!Thread.currentThread().isInterrupted) { "initial bounded cluster discovery interrupted" }
        val remaining = deadline - nanoTime()
        if (remaining <= 0) throw lastTimeout ?: TimeoutException("initial bounded cluster discovery deadline exceeded")
        val context = contextFactory()
        var attempt: AeronCluster.AsyncConnect? = null
        var connected: AeronCluster? = null
        var primaryFailure: Throwable? = null
        try {
            attempt = open(context)
            val attemptDeadline = nanoTime() + minOf(TimeUnit.SECONDS.toNanos(2), maxOf(0L, deadline - nanoTime()))
            while (nanoTime() - deadline < 0 && nanoTime() - attemptDeadline < 0) {
                failure()?.let { throw it }
                check(!Thread.currentThread().isInterrupted) { "initial bounded cluster discovery interrupted" }
                connected = attempt.poll()
                if (connected != null) {
                    if (nanoTime() - deadline >= 0) {
                        connected.close()
                        connected = null
                        throw TimeoutException("initial bounded cluster discovery deadline exceeded")
                    }
                    return connected
                }
                idle()
            }
            throw TimeoutException("initial bounded cluster discovery attempt timed out: ${attempt.state()}")
        } catch (error: Throwable) {
            primaryFailure = error
            if (error !is TimeoutException) throw error
            lastTimeout = error
        } finally {
            // 연결에 성공한 AsyncConnect는 DONE 상태이므로 close()해도 반환한 client는 닫히지 않는다.
            // 시도를 만들기 전에 생성이나 conclude가 실패하면 context가 정리를 담당한다.
            try {
                if (attempt == null) context.close() else attempt.close()
            } catch (cleanup: Throwable) {
                val original = primaryFailure
                if (original != null) {
                    if (cleanup !== original) original.addSuppressed(cleanup)
                    throw original
                }
                try {
                    connected?.close()
                } catch (closingClient: Throwable) {
                    if (closingClient !==
                        cleanup
                    ) {
                        cleanup.addSuppressed(closingClient)
                    }
                }
                throw cleanup
            }
        }
    }
}

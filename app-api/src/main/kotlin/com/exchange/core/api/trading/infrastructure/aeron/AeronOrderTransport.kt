package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import io.aeron.cluster.client.AeronCluster
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** HTTP 서버가 소유하는 연결 설정이다. 거래 노드의 기록 디렉터리와 공유하지 않는다. */
internal data class AeronGatewaySettings(
    val runRoot: Path,
    val ingressEndpoints: String,
    val egressEndpoint: String,
    val queueCapacity: Int = 1024,
    val maxInFlight: Int = 256,
    val resultTimeoutMs: Long = 5_000,
    val startupTimeoutMs: Long = 10_000,
) {
    init {
        require(runRoot.isAbsolute) { "gateway 실행 루트는 절대 경로여야 합니다" }
        require(queueCapacity in 1..65_536 && maxInFlight in 1..4096)
        require(resultTimeoutMs in 1..60_000 && startupTimeoutMs in 1..60_000)
        val endpoint = Regex("[A-Za-z0-9_.-]+:[0-9]{1,5}")

        fun validAddress(value: String): Boolean = endpoint.matches(value) && value.substringAfterLast(':').toInt() in 1..65_535
        require(validAddress(egressEndpoint)) { "gateway 응답 주소가 잘못됐습니다" }
        val ingress = ingressEndpoints.split(',')
        require(
            ingress.isNotEmpty() &&
                ingress.all { value ->
                    val member = value.substringBefore('=', "").toIntOrNull()
                    member != null && member >= 0 && validAddress(value.substringAfter('=', ""))
                },
        ) { "gateway ingress 멤버 주소가 잘못됐습니다" }
        require(ingress.map { it.substringBefore('=').toInt() }.distinct().size == ingress.size) { "gateway 멤버 ID가 중복됐습니다" }
    }

    companion object {
        fun from(environment: Map<String, String>): AeronGatewaySettings {
            fun required(name: String): String = requireNotNull(environment[name]?.takeIf { it.isNotBlank() }) { "$name 설정이 필요합니다" }

            fun number(
                name: String,
                default: Long,
            ): Long = environment[name]?.let { requireNotNull(it.toLongOrNull()) { "$name 정수가 필요합니다" } } ?: default

            fun boundedInt(
                name: String,
                default: Int,
            ): Int {
                val value = number(name, default.toLong())
                require(value in 1..Int.MAX_VALUE.toLong()) { "$name 양수 Int가 필요합니다" }
                return value.toInt()
            }
            return AeronGatewaySettings(
                Path.of(required("TRADING_GATEWAY_RUN_ROOT")),
                required("TRADING_GATEWAY_INGRESS_ENDPOINTS"),
                required("TRADING_GATEWAY_EGRESS_ENDPOINT"),
                boundedInt("TRADING_GATEWAY_QUEUE_CAPACITY", 1024),
                boundedInt("TRADING_GATEWAY_MAX_IN_FLIGHT", 256),
                number("TRADING_GATEWAY_RESULT_TIMEOUT_MS", 5_000),
                number("TRADING_GATEWAY_STARTUP_TIMEOUT_MS", 10_000),
            )
        }
    }
}

/** 송수신과 대기 요청 관리를 나눈다. 모든 메서드는 gateway의 연결 소유 스레드가 호출한다. */
internal interface GatewayOrderTransport : AutoCloseable {
    fun offer(bytes: ByteArray): Long

    fun poll(receive: (AdmissionResultPayload) -> Unit): Int
}

/** 재연결은 새 session과 전용 Driver를 만든다. 이전 session의 응답을 새 대기에 섞지 않는다. */
internal class AeronOrderTransport(
    settings: AeronGatewaySettings,
) : GatewayOrderTransport {
    private val errors = AtomicReference<Throwable?>()
    private val codec = AdmissionResultCodec()
    private var receiver: ((AdmissionResultPayload) -> Unit)? = null
    private val driver =
        MediaDriver.launch(
            MediaDriver
                .Context()
                .aeronDirectoryName(settings.runRoot.resolve("driver-${UUID.randomUUID()}").toString())
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(SleepingMillisIdleStrategy(1))
                .errorHandler { errors.compareAndSet(null, it) },
        )
    private val connection: AeronCluster =
        try {
            AeronCluster.connect(
                AeronCluster
                    .Context()
                    .aeronDirectoryName(driver.aeronDirectoryName())
                    .ingressChannel("aeron:udp?term-length=64k")
                    .ingressEndpoints(settings.ingressEndpoints)
                    .egressChannel("aeron:udp?endpoint=${settings.egressEndpoint}")
                    .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(settings.startupTimeoutMs))
                    .errorHandler { errors.compareAndSet(null, it) }
                    .egressListener { _, _, buffer, offset, length, _ ->
                        if (length !in 8..4096) throw AdmissionMessageException("접수 응답 길이가 잘못됐습니다")
                        val bytes = ByteArray(length)
                        buffer.getBytes(offset, bytes)
                        receiver?.invoke(codec.decode(bytes))
                    },
            )
        } catch (error: Throwable) {
            driver.close()
            throw error
        }
    private var lastKeepAlive = System.nanoTime()

    override fun offer(bytes: ByteArray): Long {
        checkConnection()
        return connection.offer(UnsafeBuffer(bytes), 0, bytes.size)
    }

    override fun poll(receive: (AdmissionResultPayload) -> Unit): Int {
        checkConnection()
        receiver = receive
        val work = connection.pollEgress()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(500)) {
            connection.sendKeepAlive()
            lastKeepAlive = now
        }
        checkConnection()
        return work
    }

    private fun checkConnection() {
        check(errors.get() == null && !connection.isClosed) { "Aeron 주문 연결을 사용할 수 없습니다" }
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            try {
                connection.context().close()
            } finally {
                driver.close()
            }
        }
    }
}

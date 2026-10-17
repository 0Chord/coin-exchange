package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderAdmissionState
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.ErrorHandler
import org.agrona.concurrent.SleepingMillisIdleStrategy
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 단일 개발 노드만을 위한 고정 포트 묶음. 기존 실행 디렉터리는 재사용하지 않는다. */
internal data class DevelopmentFundingSettings(
    val runRoot: Path,
    val basePort: Int = 19700,
    val startupTimeoutMs: Long = 10_000,
    val resultTimeoutMs: Long = 5_000,
    val maxReplyAttempts: Int = 100,
    val replyTimeoutMs: Long = 100,
) {
    init {
        require(runRoot.isAbsolute) { "실행 루트는 절대 경로여야 합니다" }
        require(basePort in 1024..65528) { "연속 8개 포트가 필요합니다" }
        require(startupTimeoutMs in 1..60_000 && resultTimeoutMs in 1..60_000)
        require(maxReplyAttempts in 1..10_000 && replyTimeoutMs in 1..1_000)
    }

    val driverDirectory: String get() = runRoot.resolve("driver").toString()
    val archiveDirectory: Path get() = runRoot.resolve("archive")
    val clusterDirectory: Path get() = runRoot.resolve("cluster")
    val clusterId: Int get() = 97
    val serviceId: Int get() = 0
    val memberId: Int get() = 0
    val sessionTimeoutMs: Long get() = 10_000
    val keepAliveMs: Long get() = 500

    fun endpoint(portOffset: Int): String = "127.0.0.1:${basePort + portOffset}"

    fun udp(portOffset: Int): String = "aeron:udp?endpoint=${endpoint(portOffset)}"

    val members: String get() = "0,${(0..4).joinToString(",") { endpoint(it) }}"

    fun archiveClient(): AeronArchive.Context =
        AeronArchive
            .Context()
            .aeronDirectoryName(driverDirectory)
            .controlRequestChannel("aeron:ipc")
            .controlResponseChannel("aeron:ipc")
            .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(startupTimeoutMs))
}

internal data class FundingRecording(
    val archiveId: Long,
    val recordingId: Long,
    val logSessionId: Int,
)

/** 개발 실행의 자원을 소유한다. 서비스 실패 시 닫으며 기록 파일은 지우지 않는다. */
internal class DevelopmentFundingNode private constructor(
    val config: DevelopmentFundingSettings,
) : AutoCloseable {
    private val failure = AtomicReference<Throwable?>()
    private val stopping = AtomicBoolean()
    private val stopSignal = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private var driver: MediaDriver? = null
    private var archive: Archive? = null
    private var consensus: ConsensusModule? = null
    private var container: ClusteredServiceContainer? = null
    private val service =
        DevelopmentFundingAdapter(
            OrderAdmissionState(
                emptyList(),
                OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))),
            ),
            config.maxReplyAttempts,
            TimeUnit.MILLISECONDS.toNanos(config.replyTimeoutMs),
            ::fail,
        )
    val observation: FundingObservation? get() = service.observation
    val error: Throwable? get() = failure.get()
    val isClosed: Boolean get() = stopped.count == 0L
    val storageLevels: List<Int>
        get() =
            listOf(
                requireNotNull(archive).context().fileSyncLevel(),
                requireNotNull(archive).context().catalogFileSyncLevel(),
                requireNotNull(consensus).context().fileSyncLevel(),
            )

    private fun fail(error: Throwable) {
        if (stopping.get()) return
        failure.compareAndSet(null, error)
        stopSignal.countDown()
    }

    private fun launch() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs)
        // 루트를 배타적으로 만들어 동시 실행도 거절한다. 기존 기록을 Aeron에 넘기지 않는다.
        Files.createDirectory(config.runRoot)
        // Aeron의 UDP 재사용 옵션으로 다른 프로세스의 포트를 공유하지 않도록 먼저 확인한다.
        val ports = mutableListOf<DatagramSocket>()
        try {
            (0..7).forEach { offset ->
                val socket = DatagramSocket(null)
                ports.add(socket)
                socket.reuseAddress = false
                socket.bind(InetSocketAddress("127.0.0.1", config.basePort + offset))
            }
        } finally {
            ports.forEach { it.close() }
        }
        val errors = ErrorHandler(::fail)
        driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(false)
                    .spiesSimulateConnection(true)
                    .threadingMode(ThreadingMode.SHARED)
                    .sharedIdleStrategy(SleepingMillisIdleStrategy(1))
                    .errorHandler(errors),
            )
        archive =
            Archive.launch(
                Archive
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .archiveId(config.clusterId.toLong())
                    .archiveDir(config.archiveDirectory.toFile())
                    .deleteArchiveOnStart(false)
                    .controlChannel(config.udp(4))
                    .localControlChannel("aeron:ipc")
                    .replicationChannel(config.udp(5))
                    .recordingEventsEnabled(false)
                    .segmentFileLength(64 * 1024)
                    .fileSyncLevel(2)
                    .catalogFileSyncLevel(2)
                    .threadingMode(ArchiveThreadingMode.SHARED)
                    .idleStrategySupplier { SleepingMillisIdleStrategy(1) }
                    .errorHandler(errors),
            )
        consensus =
            ConsensusModule.launch(
                ConsensusModule
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .clusterId(config.clusterId)
                    .clusterMemberId(config.memberId)
                    .appointedLeaderId(config.memberId)
                    .clusterMembers(config.members)
                    .clusterDir(config.clusterDirectory.toFile())
                    .deleteDirOnStart(false)
                    .ingressChannel("aeron:udp?term-length=64k")
                    .logChannel("aeron:udp?term-length=64k|control-mode=manual|control=${config.endpoint(2)}")
                    .replicationChannel(config.udp(6))
                    .archiveContext(config.archiveClient())
                    .sessionTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.sessionTimeoutMs))
                    .fileSyncLevel(2)
                    .idleStrategySupplier { SleepingMillisIdleStrategy(1) }
                    .errorHandler(errors)
                    .terminationHook { fail(IllegalStateException("Consensus가 종료됐습니다")) },
            )
        container =
            ClusteredServiceContainer.launch(
                ClusteredServiceContainer
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .clusterId(config.clusterId)
                    .serviceId(config.serviceId)
                    .clusterDir(config.clusterDirectory.toFile())
                    .archiveContext(config.archiveClient())
                    .clusteredService(service)
                    .idleStrategySupplier { SleepingMillisIdleStrategy(1) }
                    .errorHandler(errors)
                    .terminationHook { fail(IllegalStateException("자금 준비 서비스가 종료됐습니다")) },
            )
        check(storageLevels == listOf(2, 2, 2)) { "실제 저장 설정이 다릅니다" }
        while (!service.started ||
            requireNotNull(consensus).context().clusterNodeRoleCounter().get() !=
            Cluster.Role.LEADER
                .code()
                .toLong()
        ) {
            error?.let { throw IllegalStateException("개발 노드 시작 실패", it) }
            check(System.nanoTime() < deadline) { "개발 노드 준비 기한을 초과했습니다" }
            Thread.sleep(1)
        }
        error?.let { throw IllegalStateException("개발 노드 시작 실패", it) }
        check(System.nanoTime() < deadline) { "개발 노드 준비 기한을 초과했습니다" }
        Thread({
            stopSignal.await()
            close()
        }, "development-funding-owner").apply { isDaemon = true }.start()
    }

    /** 기록 식별만 제공한다. 원문 재생·비교는 #99에서 구현한다. */
    fun recordings(): List<FundingRecording> {
        check(!isClosed && error == null)
        return AeronArchive.connect(config.archiveClient()).use { client ->
            buildList {
                client.listRecordings(0, 100) { _, _, recordingId, _, _, _, _, _, _, _, _, sessionId, streamId, _, _, _ ->
                    if (streamId == requireNotNull(consensus).context().logStreamId()) {
                        add(FundingRecording(client.archiveId(), recordingId, sessionId))
                    }
                }
            }
        }
    }

    fun awaitClosed(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        stopSignal.countDown()
        try {
            listOf(container, consensus, archive, driver).forEach { resource ->
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        } finally {
            stopped.countDown()
        }
    }

    companion object {
        fun start(config: DevelopmentFundingSettings): DevelopmentFundingNode =
            DevelopmentFundingNode(config).also { node ->
                try {
                    node.launch()
                } catch (error: Throwable) {
                    node.fail(error)
                    node.close()
                    throw error
                }
            }
    }
}

/** 기본 HTTP 실행과 독립된 개발 진입점. 자금은 클라이언트의 명시 요청으로만 준비한다. */
fun main(args: Array<String>) {
    require(args.size in 1..2) { "사용법: 절대경로의새실행루트 [기준포트=19700]" }
    val config = DevelopmentFundingSettings(Path.of(args[0]), args.getOrNull(1)?.toInt() ?: 19700)
    DevelopmentFundingNode.start(config).use { node ->
        val shutdown = Thread({ node.close() }, "development-funding-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            println("READY root=${config.runRoot} members=${config.members} driver=${config.driverDirectory} sync=${node.storageLevels}")
            var last: FundingObservation? = null
            while (!node.awaitClosed(100)) {
                val observed = node.observation
                if (observed != null && observed !== last) {
                    println("OBSERVATION $observed")
                    last = observed
                }
            }
            node.error?.let { throw IllegalStateException("개발 노드가 오류로 중단됐습니다", it) }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown)
            } catch (_: IllegalStateException) {
                // JVM 종료 중에는 hook 등록을 변경할 수 없다. 자원은 같은 close 경로로 닫힌다.
            }
        }
    }
}

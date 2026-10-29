package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.RocksDbTradingHistoryStore
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingState
import io.aeron.ChannelUri
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.ClusterMember
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.ElectionState
import io.aeron.cluster.MillisecondClusterClock
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.exceptions.AeronException
import org.agrona.ErrorHandler
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 기존 단일 실행과 세 노드 실행의 주소·멤버·경로. 기존 실행 디렉터리는 재사용하지 않는다. */
internal data class DevelopmentFundingSettings(
    val runRoot: Path,
    val basePort: Int = 19700,
    val startupTimeoutMs: Long = 10_000,
    val resultTimeoutMs: Long = 5_000,
    val maxReplyAttempts: Int = 100,
    val replyTimeoutMs: Long = 100,
    val markets: DevelopmentMarketRegistry = DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT)),
    val memberId: Int = 0,
    val advertisedHost: String = "127.0.0.1",
    val bindHost: String = advertisedHost,
    val clusterMembers: String? = null,
    val runId: String? = null,
    val archiveFileSyncLevel: Int = 2,
) {
    init {
        require(runRoot.isAbsolute) { "실행 루트는 절대 경로여야 합니다" }
        require(basePort in 1024..65528) { "연속 8개 포트가 필요합니다" }
        require(startupTimeoutMs in 1..60_000 && resultTimeoutMs in 1..60_000)
        require(maxReplyAttempts in 1..10_000 && replyTimeoutMs in 1..1_000)
        require(advertisedHost.isNotBlank() && advertisedHost != "0.0.0.0") { "도달 가능한 광고 주소가 필요합니다" }
        require(bindHost.isNotBlank())
        val configured = ClusterMember.parse(members)
        require(configured.size == 1 || configured.size == 3) { "개발 실행은 한 노드 또는 세 노드입니다" }
        require(configured.map { it.id() }.distinct().size == configured.size)
        require(configured.any { it.id() == memberId }) { "멤버 목록에 자기 ID가 없습니다" }
        require(runId == null || runId.matches(Regex("[a-z0-9-]{1,64}")))
        require(archiveFileSyncLevel in 0..2)
    }

    val market: MarketDefinition get() = markets.single().market
    val feePolicySnapshot: TradingFeePolicySnapshot get() = markets.single().feePolicySnapshot

    val driverDirectory: String get() = runRoot.resolve("driver").toString()
    val archiveDirectory: Path get() = runRoot.resolve("archive")
    val clusterDirectory: Path get() = runRoot.resolve("cluster")
    val clusterId: Int get() = 97
    val serviceId: Int get() = 0
    val sessionTimeoutMs: Long get() = 10_000
    val keepAliveMs: Long get() = 500

    fun endpoint(portOffset: Int): String = "$advertisedHost:${basePort + portOffset}"

    fun udp(portOffset: Int): String = "aeron:udp?endpoint=${endpoint(portOffset)}"

    val members: String get() = clusterMembers ?: "$memberId,${(0..4).joinToString(",") { endpoint(it) }}"
    val ingressEndpoints: String get() = ClusterMember.parse(members).joinToString(",") { "${it.id()}=${it.ingressEndpoint()}" }
    val clustered: Boolean get() = ClusterMember.parse(members).size > 1

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
    private val serviceDecorator: (DevelopmentFundingAdapter) -> ClusteredService,
) : AutoCloseable {
    private val failure = AtomicReference<Throwable?>()
    private val stopping = AtomicBoolean()
    private val stopSignal = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private var driver: MediaDriver? = null
    private var archive: Archive? = null
    private var consensus: ConsensusModule? = null
    private var container: ClusteredServiceContainer? = null
    private var history: RocksDbTradingHistoryStore? = null

    /** 요청 ID를 tag로 쓰지 않는 운영용 고정 구간 지표. 서비스 상태·복제 로그와 분리한다. */
    private val metricsRegistry: io.micrometer.core.instrument.MeterRegistry =
        io.micrometer.core.instrument.simple
            .SimpleMeterRegistry()
    private val metricRecorder = TradingNodeMetrics(metricsRegistry, asynchronous = true, onFatal = ::fail)
    val metrics: io.micrometer.core.instrument.MeterRegistry
        get() {
            metricRecorder.awaitRecorded()
            return metricsRegistry
        }
    private val service by lazy {
        DevelopmentFundingAdapter(
            run {
                // launch가 새 실행 루트를 만든 뒤 연다. 이전 이력만 재사용해 로그 재실행을 건너뛰지 않는다.
                val historyStore =
                    RocksDbTradingHistoryStore.create(config.runRoot.resolve("history"), onFatal = ::fail).also {
                        history =
                            it
                    }
                val fees = TradingFeeReserveCalculator()
                val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
                TradingState(
                    OrderAdmissionCalculator(reservation),
                    OrderSettlementCalculator(
                        OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                        reservation,
                        fees,
                        SettlementLedgerTransactions(),
                    ),
                    historyStore = historyStore,
                )
            },
            config.maxReplyAttempts,
            TimeUnit.MILLISECONDS.toNanos(config.replyTimeoutMs),
            markets = config.markets,
            serviceId = config.serviceId,
            telemetry = metricRecorder,
            onFatal = ::fail,
        )
    }
    val observation: FundingObservation? get() = service.observation

    /** 전체 조회는 서비스 스레드에 요청한다. 일반 명령은 누적 상태를 복사하지 않는다. */
    fun captureObservation(timeoutMs: Long = config.resultTimeoutMs): FundingObservation? {
        require(timeoutMs in 1..60_000)
        val capture = service.captureObservation()
        return try {
            capture.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            capture.cancel(false)
            throw failure
        }
    }

    internal val logStreamId: Int get() = requireNotNull(consensus).context().logStreamId()

    /** Aeron이 단일 노드의 MDC publication에 적용하는 실제 기본 채널 설정이다. 실행 설정은 바꾸지 않는다. */
    internal val logChannel: String
        get() {
            val context = requireNotNull(consensus).context()
            val channel = ChannelUri.parse(context.logChannel())
            if (channel.isUdp && channel.get("endpoint") == null) {
                channel.put("control-mode", "manual")
                if (context.enableControlOnLogControl()) {
                    val members = ClusterMember.parse(context.clusterMembers())
                    val member = requireNotNull(ClusterMember.findMember(members, context.clusterMemberId()))
                    channel.put("control", ChannelUri.replacePortWithWildcard(member.logEndpoint()))
                }
            }
            return channel.toString()
        }
    val error: Throwable? get() = failure.get()
    val isClosed: Boolean get() = stopped.count == 0L
    val role: Cluster.Role
        get() = Cluster.Role.get(requireNotNull(consensus).context().clusterNodeRoleCounter().get())
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
                socket.bind(InetSocketAddress(config.bindHost, config.basePort + offset))
            }
        } finally {
            ports.forEach { it.close() }
        }
        val errors =
            ErrorHandler { error ->
                // Aeron은 오류 로그·카운터에 기록한 WARN 뒤에도 선출 등 복구 절차를 이어간다.
                // 업무 어댑터의 onFatal은 이 분류를 거치지 않고 계속 노드를 중단한다.
                if (error !is AeronException || error.category() != AeronException.Category.WARN) fail(error)
            }
        driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(false)
                    .spiesSimulateConnection(true)
                    .socketRcvbufLength(TradingAeronRuntime.SOCKET_BUFFER_BYTES)
                    .socketSndbufLength(TradingAeronRuntime.SOCKET_BUFFER_BYTES)
                    .initialWindowLength(TradingAeronRuntime.RECEIVER_WINDOW_BYTES)
                    .threadingMode(ThreadingMode.SHARED)
                    .sharedIdleStrategy(TradingAeronRuntime.idleStrategy())
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
                    .segmentFileLength(TradingAeronRuntime.TERM_LENGTH_BYTES)
                    .fileSyncLevel(config.archiveFileSyncLevel)
                    .catalogFileSyncLevel(2)
                    .threadingMode(ArchiveThreadingMode.SHARED)
                    .idleStrategySupplier(TradingAeronRuntime::idleStrategy)
                    .errorHandler(errors),
            )
        consensus =
            ConsensusModule.launch(
                ConsensusModule
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .clusterId(config.clusterId)
                    .clusterClock(MillisecondClusterClock())
                    .clusterMemberId(config.memberId)
                    .appointedLeaderId(if (config.clustered) -1 else config.memberId)
                    .clusterMembers(config.members)
                    .clusterDir(config.clusterDirectory.toFile())
                    .deleteDirOnStart(false)
                    .ingressChannel(TradingAeronRuntime.UDP_CHANNEL)
                    .ingressFragmentLimit(TradingAeronRuntime.INGRESS_FRAGMENT_LIMIT)
                    .logChannel("${TradingAeronRuntime.LOG_CHANNEL}|control-mode=manual|control=${config.endpoint(2)}")
                    .replicationChannel(config.udp(6))
                    .archiveContext(config.archiveClient())
                    .sessionTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.sessionTimeoutMs))
                    .fileSyncLevel(2)
                    .idleStrategySupplier(TradingAeronRuntime::idleStrategy)
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
                    .clusteredService(serviceDecorator(service))
                    .idleStrategySupplier(TradingAeronRuntime::idleStrategy)
                    .errorHandler(errors)
                    .terminationHook { fail(IllegalStateException("자금 준비 서비스가 종료됐습니다")) },
            )
        check(storageLevels == listOf(config.archiveFileSyncLevel, 2, 2)) { "실제 저장 설정이 다릅니다" }
        while (!service.started ||
            requireNotNull(consensus).context().electionStateCounter().get() != ElectionState.CLOSED.code().toLong() ||
            (!config.clustered && role != Cluster.Role.LEADER)
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
            listOf(container, consensus, archive, driver, history).forEach { resource ->
                try {
                    resource?.close()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
            try {
                try {
                    metricRecorder.close()
                } finally {
                    metricsRegistry.close()
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        } finally {
            stopped.countDown()
        }
    }

    companion object {
        fun start(
            config: DevelopmentFundingSettings,
            serviceDecorator: (DevelopmentFundingAdapter) -> ClusteredService = { it },
        ): DevelopmentFundingNode =
            DevelopmentFundingNode(config, serviceDecorator).also { node ->
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
    val environment = System.getenv()
    val structured = args.contentEquals(arrayOf("--cluster-env"))
    require(structured || args.size in 1..2) { "사용법: 절대경로의새실행루트 [기준포트=19700] 또는 --cluster-env" }
    val config =
        if (structured) {
            developmentClusterSettings(environment)
        } else {
            DevelopmentFundingSettings(
                Path.of(args[0]),
                args.getOrNull(1)?.toInt() ?: 19700,
                markets = readDevelopmentMarkets(environment),
            )
        }
    try {
        DevelopmentFundingNode.start(config).use { node ->
            val shutdown =
                Thread({
                    node.close()
                    if (structured) {
                        fundingEvent(
                            mapOf(
                                "kind" to "STOPPED",
                                "runId" to config.runId,
                                "memberId" to config.memberId,
                                "closed" to node.isClosed,
                                "failure" to node.error?.toString(),
                            ),
                        )
                    }
                }, "development-funding-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                if (structured) {
                    fundingReadyEvent(config, node)
                } else {
                    println(
                        "READY root=${config.runRoot} members=${config.members} driver=${config.driverDirectory} " +
                            "sync=${node.storageLevels} markets=${fundingMarketFields(config.markets)} " +
                            "marketConfigVersion=${config.markets.configurationVersion} marketConfigSha256=${config.markets.contentSha256}",
                    )
                }
                var last: FundingObservation? = null
                while (!node.awaitClosed(100)) {
                    val observed = node.observation
                    if (observed != null && observed !== last) {
                        val captured = node.captureObservation()
                        if (captured != null) {
                            if (structured) fundingAppliedEvent(config, captured) else println("OBSERVATION $captured")
                        }
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
    } catch (error: Throwable) {
        if (structured) {
            fundingEvent(
                mapOf(
                    "kind" to "ERROR",
                    "runId" to config.runId,
                    "memberId" to config.memberId,
                    "failure" to error.toString(),
                ),
            )
        }
        throw error
    }
}

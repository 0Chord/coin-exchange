package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingServiceRole
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.ClusterControl
import io.aeron.cluster.ClusterMember
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.ElectionState
import io.aeron.cluster.MillisecondClusterClock
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.driver.exceptions.InvalidChannelException
import io.aeron.driver.status.StreamCounter
import io.aeron.exceptions.AeronException
import org.agrona.ErrorHandler
import org.agrona.concurrent.status.CountersReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 일반 main의 역할 차단과 별개로 명시한 개발 실행만 허용한다. */
internal data class SplitTradingNodeSettings(
    val transport: DevelopmentFundingSettings,
    val role: TradingServiceRole,
    val configuration: SplitTradingConfiguration,
    val fundsIngressEndpoints: String,
    val matchingIngressEndpoints: String,
    val mode: String = "DISABLED",
    val matchingTargets: List<SplitMatchingTarget> = emptyList(),
) {
    private val explicitTargets = matchingTargets.isNotEmpty()
    val targets: List<SplitMatchingTarget> =
        Collections.unmodifiableList(
            matchingTargets
                .ifEmpty { listOf(SplitMatchingTarget(configuration.matchingLineage, MATCHING_CLUSTER_ID, matchingIngressEndpoints)) }
                .sortedWith(compareBy({ it.lineage.high }, { it.lineage.low })),
        )
    val resultDeliveryEnabled: Boolean get() = mode == "RESULT_SETTLEMENT_DEV"
    val clusterId: Int get() = if (role == TradingServiceRole.FUNDS) FUNDS_CLUSTER_ID else target(configuration.matchingLineage).clusterId
    val lastPortOffset: Int get() = 6 + 2 * targets.size

    init {
        configuration.requireTransportCompatible(resultDeliveryEnabled)
        require(mode == "ORDER_DELIVERY_DEV" || resultDeliveryEnabled) { "분리 역할은 명시한 개발 모드에서만 실행합니다" }
        if (resultDeliveryEnabled) configuration.requireResultDeliveryCompatible()
        require(role == TradingServiceRole.FUNDS || role == TradingServiceRole.MATCHING)
        require(transport.serviceRole == TradingServiceRole.INTEGRATED) {
            "기존 실행 설정의 역할 차단을 우회하지 않습니다"
        }
        require(transport.boundedManifest == configuration.topology.fundsManifest) { "실행 manifest와 고정 배치가 다릅니다" }
        require(transport.archiveFileSyncLevel == 2)
        require(transport.basePort <= 65535 - lastPortOffset) { "노드와 담당별 두 client의 연속 포트가 필요합니다" }
        require(fundsIngressEndpoints.isNotBlank() && matchingIngressEndpoints.isNotBlank())
        require(targets.size in 1..2 && targets.map { it.lineage }.distinct().size == targets.size)
        require(targets.map { it.lineage }.toSet() == configuration.matchingDeclarations.map { it.lineage }.toSet()) {
            "배치의 모든 매칭 담당에 연결 설정이 하나씩 필요합니다"
        }
        require(targets.all { it.clusterId >= 0 && it.clusterId != FUNDS_CLUSTER_ID })
        require(targets.map { it.clusterId }.distinct().size == targets.size) { "매칭 cluster ID가 중복됐습니다" }
        require(explicitTargets || configuration.matchingDeclarations.size == 1)
        require(matchingIngressEndpoints == target(configuration.matchingLineage).ingressEndpoints) { "선택 담당 ingress가 다릅니다" }
        val funds = ingress(fundsIngressEndpoints)
        val matching = targets.associate { it.lineage to ingress(it.ingressEndpoints) }
        val endpoints = listOf(funds) + matching.values
        val ingressValues = endpoints.flatMap { it.values }
        require(ingressValues.distinct().size == ingressValues.size) { "클러스터별 ingress가 겹칩니다" }
        val own = if (role == TradingServiceRole.FUNDS) funds else matching.getValue(configuration.matchingLineage)
        val declared = ClusterMember.parse(transport.members).associate { it.id() to normalizeEndpoint(it.ingressEndpoint()) }
        require(own == declared) { "자기 역할의 멤버와 ingress 배치가 다릅니다" }
        val self = declared.getValue(transport.memberId)
        require(self.substringAfterLast(':').toInt() == transport.basePort) { "자기 ingress와 실제 노드 기본 포트가 다릅니다" }
        val localHosts = setOf(transport.advertisedHost.lowercase(), self.substringBeforeLast(':'))
        // 자기 ingress만 제외한다. 같은 그룹의 다른 멤버도 응답·서비스 포트와 겹치면 안 된다.
        val peers =
            endpoints.flatMap { group ->
                group.filterKeys { group !== own || it != transport.memberId }.values
            }
        require(
            peers.none { endpoint ->
                val port = endpoint.substringAfterLast(':').toInt()
                endpoint.substringBeforeLast(':') in localHosts &&
                    port <= transport.basePort + lastPortOffset && port + lastPortOffset >= transport.basePort
            },
        ) { "다른 멤버의 서비스·응답 포트가 자기 노드 포트와 겹칩니다" }
        require(transport.runRoot.normalize() == transport.runRoot && !Files.isSymbolicLink(transport.runRoot))
        require(TradingAeronRuntime.TERM_LENGTH_BYTES / 8 >= 32768 + 32) { "분리 메시지가 transport 한도를 넘습니다" }
    }

    fun endpoint(
        matching: Boolean,
        lineage: ClusterLineage = configuration.matchingLineage,
    ): SplitOrderDeliveryEndpoint {
        val target = target(lineage)
        val index = targets.indexOf(target)
        return SplitOrderDeliveryEndpoint(
            transport.driverDirectory,
            if (matching) target.ingressEndpoints else fundsIngressEndpoints,
            transport.endpoint(7 + index * 2 + if (matching) 1 else 0),
            if (matching) target.clusterId else FUNDS_CLUSTER_ID,
            configuration.inputContractVersion,
        )
    }

    private fun target(lineage: ClusterLineage): SplitMatchingTarget =
        requireNotNull(targets.singleOrNull { it.lineage == lineage }) { "등록되지 않은 매칭 담당입니다" }

    fun identityBytes(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(
                    if (explicitTargets) {
                        "exchange-split-multiple-matching-run-v1"
                    } else if (resultDeliveryEnabled) {
                        "exchange-split-result-delivery-run-v1"
                    } else {
                        "exchange-split-order-delivery-run-v1"
                    },
                )
                out.writeUTF(mode)
                out.writeUTF(role.name)
                out.writeUTF(if (resultDeliveryEnabled) configuration.resultTopologySha256 else configuration.topologySha256)
                out.writeUTF(BoundedExecutionIdentity.artifactSha256)
                out.writeUTF(BoundedExecutionIdentity.schemaSha256)
                out.writeInt(clusterId)
                out.writeInt(transport.memberId)
                out.writeUTF(transport.members)
                out.writeUTF(transport.advertisedHost)
                out.writeUTF(transport.bindHost)
                out.writeInt(transport.basePort)
                out.writeUTF(fundsIngressEndpoints)
                out.writeUTF(matchingIngressEndpoints)
                out.writeInt(transport.archiveFileSyncLevel)
                if (explicitTargets) {
                    out.writeLong(configuration.matchingLineage.high)
                    out.writeLong(configuration.matchingLineage.low)
                    out.writeInt(targets.size)
                    for (target in targets) {
                        out.writeLong(target.lineage.high)
                        out.writeLong(target.lineage.low)
                        out.writeInt(target.clusterId)
                        out.writeUTF(target.ingressEndpoints)
                    }
                }
            }
            bytes.toByteArray()
        }

    companion object {
        const val FUNDS_CLUSTER_ID = 198
        const val MATCHING_CLUSTER_ID = 199

        private fun normalizeEndpoint(value: String): String {
            val host = value.substringBeforeLast(':').lowercase()
            val port = value.substringAfterLast(':').toInt()
            require(host.isNotBlank() && host != "0.0.0.0" && port in 1024..65535)
            return "$host:$port"
        }

        private fun ingress(value: String): Map<Int, String> {
            val pairs =
                value.split(',').map { part ->
                    val id = part.substringBefore('=').toInt()
                    require(id >= 0 && part.count { it == '=' } == 1)
                    id to normalizeEndpoint(part.substringAfter('='))
                }
            require(pairs.size in setOf(1, 3) && pairs.map { it.first }.distinct().size == pairs.size)
            require(pairs.map { it.second }.distinct().size == pairs.size)
            return pairs.toMap()
        }
    }
}

/** 실제 노드와 경량 검사에서 같은 담당별 조립을 사용한다. 자금 상태를 추가로 만들지 않는다. */
internal fun splitTradingDeliveryTasks(
    settings: SplitTradingNodeSettings,
    service: SplitTradingClusterAdapter,
    orderConnectionFactory: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
    resultConnectionFactory: SplitResultDeliveryConnectionFactory = AeronSplitResultDeliveryConnectionFactory,
    onFailure: (Throwable) -> Unit = {},
    nanoTime: () -> Long = System::nanoTime,
    legacyOrderFactory: ((SplitTradingClusterAdapter) -> FundsOrderDeliveryAgent)? = null,
    legacyResultFactory: ((SplitTradingClusterAdapter) -> MatchingResultDeliveryAgent)? = null,
): SplitTradingDeliveryTasks {
    require(service.role == settings.role) { "작업 source와 실행 역할이 다릅니다" }
    require(settings.targets.size == 1 || (legacyOrderFactory == null && legacyResultFactory == null)) {
        "단일 담당 factory로 복수 담당 작업을 대체할 수 없습니다"
    }
    val orders =
        if (settings.role == TradingServiceRole.FUNDS) {
            settings.targets.map { target ->
                legacyOrderFactory?.invoke(service) ?: FundsOrderDeliveryAgent(
                    settings.configuration.forMatching(target.lineage),
                    service,
                    settings.endpoint(false, target.lineage),
                    settings.endpoint(true, target.lineage),
                    connectionFactory = orderConnectionFactory,
                    onFailure = onFailure,
                    nanoTime = nanoTime,
                )
            }
        } else {
            emptyList()
        }
    val result =
        if (settings.resultDeliveryEnabled && settings.role == TradingServiceRole.MATCHING) {
            legacyResultFactory?.invoke(service) ?: MatchingResultDeliveryAgent(
                settings.configuration,
                service,
                settings.endpoint(false),
                settings.endpoint(true),
                connectionFactory = resultConnectionFactory,
                onFailure = onFailure,
                nanoTime = nanoTime,
            )
        } else {
            null
        }
    return SplitTradingDeliveryTasks(orders, result)
}

/** 각 작업의 연결/재시도는 독립이고 모든 작업의 생명주기는 노드 하나가 소유한다. */
internal class SplitTradingDeliveryTasks(
    orders: List<FundsOrderDeliveryAgent>,
    val result: MatchingResultDeliveryAgent?,
) : AutoCloseable {
    val orders: List<FundsOrderDeliveryAgent> = Collections.unmodifiableList(orders.toList())
    val failure: Throwable? get() = orders.firstNotNullOfOrNull { it.failure } ?: result?.failure

    fun start() {
        try {
            orders.forEach { it.start() }
            result?.start()
        } catch (error: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    internal fun doWork(): Int = orders.sumOf { it.doWork() } + (result?.doWork() ?: 0)

    override fun close() {
        var first: Throwable? = null
        for (resource in listOfNotNull(result) + orders) {
            try {
                resource.close()
            } catch (error: Throwable) {
                if (first == null) first = error else first.addSuppressed(error)
            }
        }
        first?.let { throw it }
    }
}

/** 역할별 고정 설정과 기록을 소유하는 명시적 개발 노드다. */
internal class DevelopmentSplitTradingNode private constructor(
    val settings: SplitTradingNodeSettings,
    private val serviceDecorator: (SplitTradingClusterAdapter) -> ClusteredService,
    private val deliveryFactory: ((SplitTradingClusterAdapter) -> FundsOrderDeliveryAgent)?,
    private val resultDeliveryFactory: ((SplitTradingClusterAdapter) -> MatchingResultDeliveryAgent)?,
    private val orderConnectionFactory: SplitOrderDeliveryConnectionFactory,
    private val resultConnectionFactory: SplitResultDeliveryConnectionFactory,
) : AutoCloseable {
    private val stopped = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val signal = CountDownLatch(1)
    private var driver: MediaDriver? = null
    private var archive: Archive? = null
    private var consensus: ConsensusModule? = null
    private var container: ClusteredServiceContainer? = null
    private var run: SplitTradingRun? = null
    private var tasks: SplitTradingDeliveryTasks? = null
    private val service = SplitTradingClusterAdapter(settings.role, settings.configuration, ::fail, settings.resultDeliveryEnabled)
    val error: Throwable? get() = failure.get() ?: tasks?.failure
    val isClosed: Boolean get() = stopped.get()
    val role: Cluster.Role get() = Cluster.Role.get(checkNotNull(consensus).context().clusterNodeRoleCounter().get())
    val tradingReady: Boolean get() = service.tradingReady
    val storageLevels: List<Int>
        get() =
            listOf(
                checkNotNull(archive).context().fileSyncLevel(),
                checkNotNull(archive).context().catalogFileSyncLevel(),
                checkNotNull(consensus).context().fileSyncLevel(),
            )

    // 같은 부팅에서 확인한 정상 log recorder 하나만 보관한다. 과거 금액이나 append 값을 현재 값으로 쓰지 않는다.
    private var lastObservedAppendCounter: Map<String, Any?>? = null

    /** 합의와 transport의 현재 counter만 읽는다. 금융 상태나 서비스 callback에는 접근하지 않는다. */
    @Synchronized
    internal fun runtimeObservation(): Map<String, Any?> {
        val context = checkNotNull(consensus).context()
        val aeron = context.aeron()
        val reader = aeron.countersReader()
        val archiveId = checkNotNull(archive).context().archiveId()
        val roleCounter = context.clusterNodeRoleCounter()
        val electionCounter = context.electionStateCounter()
        val electionCount = context.electionCounter()
        val termCounter = context.leadershipTermIdCounter()
        val consensusIds = listOf(roleCounter.id(), electionCounter.id(), electionCount.id(), termCounter.id())
        val registrationsBefore = consensusIds.map(reader::getCounterRegistrationId)
        val roleBefore = roleCounter.get()
        val electionBefore = electionCounter.get()
        val electionCountBefore = electionCount.get()
        val termBefore = termCounter.get()
        val counters = mutableListOf<Map<String, Any?>>()
        var stable = true
        val essentialTypes = setOf(0, 11, 100, 200, 201, 203, 207, 238, 239)
        val streamTypes = setOf(1, 2, 3, 4, 5, 9, 12)
        var allocatedObserved = 0
        reader.forEach(
            CountersReader.MetaData { id, type, key, label ->
                allocatedObserved++
                val logStreamCounter = type in streamTypes && key.getInt(StreamCounter.STREAM_ID_OFFSET) == context.logStreamId()
                if (type in essentialTypes || logStreamCounter) {
                    check(counters.size < 256) { "로컬 witness counter 한도를 넘었습니다" }
                    val registration = reader.getCounterRegistrationId(id)
                    val value = reader.getCounterValue(id)
                    val keyBytes = ByteArray(CountersReader.MAX_KEY_LENGTH).also { key.getBytes(0, it) }
                    val counterClusterId = if (type >= 200) key.getInt(0) else null
                    val owner = reader.getCounterOwnerId(id)
                    val reference = reader.getCounterReferenceId(id)
                    val recording = if (type == RecordingPos.RECORDING_POSITION_TYPE_ID) RecordingPos.getRecordingId(reader, id) else null
                    val source = if (type == RecordingPos.RECORDING_POSITION_TYPE_ID) RecordingPos.getSourceIdentity(reader, id) else null
                    val labelParts = if (type == RecordingPos.RECORDING_POSITION_TYPE_ID) label.split(' ', limit = 5) else emptyList()
                    val sourceLength = if (type == RecordingPos.RECORDING_POSITION_TYPE_ID) key.getInt(12) else -1
                    val recordingArchive =
                        if (sourceLength in 0..CountersReader.MAX_KEY_LENGTH - 24) key.getLong(16 + sourceLength) else null
                    val recordingStream = labelParts.getOrNull(3)?.toIntOrNull()
                    val recordingChannel = labelParts.getOrNull(4)?.substringBeforeLast(" - archiveId=")
                    val identified =
                        type == RecordingPos.RECORDING_POSITION_TYPE_ID && recording != null && recording >= 0 &&
                            labelParts.firstOrNull() == "rec-pos:" && labelParts.getOrNull(1)?.toLongOrNull() == recording &&
                            labelParts.getOrNull(2)?.toIntOrNull() == key.getInt(8) && recordingStream != null &&
                            recordingArchive == archiveId && label.endsWith(" - archiveId=$archiveId") &&
                            recordingChannel?.startsWith("aeron:") == true && !source.isNullOrBlank()
                    val current =
                        reader.getCounterState(id) == CountersReader.RECORD_ALLOCATED &&
                            reader.getCounterTypeId(id) == type && reader.getCounterRegistrationId(id) == registration
                    stable = stable && current
                    counters.add(
                        mapOf(
                            "counterId" to id,
                            "typeId" to type,
                            "registrationId" to registration,
                            "ownerId" to owner,
                            "referenceId" to reference,
                            "clusterId" to counterClusterId,
                            "transportStreamId" to if (logStreamCounter) context.logStreamId() else null,
                            "label" to label,
                            "keyHex" to keyBytes.joinToString("") { "%02x".format(it) },
                            "value" to value,
                            "stable" to current,
                            "recordingId" to recording,
                            "sourceIdentity" to source,
                            "archiveId" to recordingArchive,
                            "recordingStreamId" to recordingStream,
                            "recordingChannel" to recordingChannel,
                            "recordingMetadataIdentified" to identified,
                        ),
                    )
                }
            },
        )
        val recordingsIdentified =
            counters.filter { it["typeId"] == RecordingPos.RECORDING_POSITION_TYPE_ID }.all {
                it["recordingMetadataIdentified"] ==
                    true
            }
        val append =
            counters.filter {
                it["typeId"] == RecordingPos.RECORDING_POSITION_TYPE_ID && it["archiveId"] == archiveId &&
                    it["recordingStreamId"] == context.logStreamId()
            }
        val previousAppend = lastObservedAppendCounter
        val inactiveEvidence =
            previousAppend?.let { previous ->
                val id = previous.getValue("counterId") as Int
                val originalRecording = previous.getValue("recordingId") as Long
                val currentState = reader.getCounterState(id)
                val currentRegistration = reader.getCounterRegistrationId(id)
                val currentType = reader.getCounterTypeId(id)
                val activeRecording = RecordingPos.findCounterIdByRecording(reader, originalRecording, archiveId)
                val identityStable =
                    currentState == reader.getCounterState(id) && currentRegistration == reader.getCounterRegistrationId(id) &&
                        currentType == reader.getCounterTypeId(id) &&
                        activeRecording == RecordingPos.findCounterIdByRecording(reader, originalRecording, archiveId)
                mapOf(
                    "previousActiveCounter" to previous,
                    "currentCounterId" to id,
                    "currentRecordState" to currentState,
                    "currentRegistrationId" to currentRegistration,
                    "currentTypeId" to currentType,
                    "activeRecordingCounterId" to activeRecording,
                    "counterIdentityReadStable" to identityStable,
                )
            }
        val recordingIdentitiesAfter = mutableListOf<Pair<Int, Long>>()
        reader.forEach(
            CountersReader.MetaData { id, type, _, _ ->
                if (type == RecordingPos.RECORDING_POSITION_TYPE_ID) {
                    recordingIdentitiesAfter.add(id to reader.getCounterRegistrationId(id))
                }
            },
        )
        val recordingIdentitiesBefore =
            counters
                .filter { it["typeId"] == RecordingPos.RECORDING_POSITION_TYPE_ID }
                .map { (it.getValue("counterId") as Int) to (it.getValue("registrationId") as Long) }
        val recordingIdentitiesStable = recordingIdentitiesBefore == recordingIdentitiesAfter
        val roleAfter = roleCounter.get()
        val electionAfter = electionCounter.get()
        val electionCountAfter = electionCount.get()
        val termAfter = termCounter.get()
        val roleName = Cluster.Role.get(roleAfter).name
        val electionName = ElectionState.get(electionAfter).name
        val consensusStable =
            roleBefore == roleAfter && electionBefore == electionAfter && electionCountBefore == electionCountAfter &&
                termBefore == termAfter &&
                registrationsBefore == consensusIds.map(reader::getCounterRegistrationId) &&
                consensusIds.all { reader.getCounterState(it) == CountersReader.RECORD_ALLOCATED }
        val baseStable = stable && consensusStable && recordingIdentitiesStable && recordingsIdentified
        val activeAppend = append.singleOrNull()?.takeIf { baseStable && (it["value"] as Long) >= 0 && it["stable"] == true }
        // Aeron 1.53.3은 새 선거 준비에서 recorder를 닫고 CANVASS/ballot에 진입한다.
        // 같은 로컬 recorder의 해제/재사용과 새 election counter까지 확인한 경우만 숫자 부재를 분류한다.
        val electionAllowsInactive =
            (electionName in setOf("CANVASS", "NOMINATE", "FOLLOWER_BALLOT") && roleName == "FOLLOWER") ||
                (electionName == "CANDIDATE_BALLOT" && roleName == "CANDIDATE")
        val inactiveAppend =
            inactiveEvidence?.let { evidence ->
                val previous = checkNotNull(previousAppend)
                val originalRegistration = previous.getValue("registrationId") as Long
                val currentRegistration = evidence.getValue("currentRegistrationId") as Long
                val released =
                    evidence["currentRecordState"] == CountersReader.RECORD_RECLAIMED && currentRegistration == originalRegistration &&
                        evidence["currentTypeId"] == RecordingPos.RECORDING_POSITION_TYPE_ID
                val reused =
                    evidence["currentRecordState"] == CountersReader.RECORD_ALLOCATED && currentRegistration > 0 &&
                        currentRegistration != originalRegistration
                baseStable && append.isEmpty() && electionAllowsInactive &&
                    electionCountAfter > (previous.getValue("observedElectionCount") as Long) &&
                    evidence["counterIdentityReadStable"] == true &&
                    evidence["activeRecordingCounterId"] == CountersReader.NULL_COUNTER_ID &&
                    (released || reused)
            } ?: false
        val appendState =
            when {
                activeAppend != null -> "ACTIVE"
                inactiveAppend -> "RECORDING_INACTIVE_DURING_ELECTION"
                else -> "UNRESOLVED"
            }
        if (activeAppend != null && electionName == "CLOSED") {
            lastObservedAppendCounter =
                activeAppend + mapOf("observedElectionState" to electionName, "observedElectionCount" to electionCountAfter)
        }
        val heartbeat = aeron.context().toDriverBuffer().consumerHeartbeatTime()
        val heartbeatAge = System.currentTimeMillis() - heartbeat
        return mapOf(
            "clusterId" to settings.clusterId,
            "archiveId" to archiveId,
            "memberId" to settings.transport.memberId,
            "role" to roleName,
            "roleBefore" to Cluster.Role.get(roleBefore).name,
            "roleAfter" to roleName,
            "electionState" to electionName,
            "electionStateBefore" to ElectionState.get(electionBefore).name,
            "electionStateAfter" to electionName,
            "electionCountBefore" to electionCountBefore,
            "electionCountAfter" to electionCountAfter,
            "leadershipTermId" to termAfter.takeIf { it >= 0 },
            "leadershipTermIdBefore" to termBefore.takeIf { it >= 0 },
            "leadershipTermIdAfter" to termAfter.takeIf { it >= 0 },
            "consensusReadStable" to consensusStable,
            "commitPosition" to context.commitPositionCounter().get(),
            "commitCounterId" to context.commitPositionCounter().id(),
            "appendState" to appendState,
            "appendPosition" to activeAppend?.get("value"),
            "appendCounters" to append,
            "inactiveAppendEvidence" to if (append.isEmpty()) inactiveEvidence else null,
            "recordingCountersIdentified" to recordingsIdentified,
            "recordingIdentitiesStable" to recordingIdentitiesStable,
            "counterReadStable" to (stable && consensusStable && recordingIdentitiesStable),
            "driverDirectory" to settings.transport.driverDirectory,
            "driverHeartbeatMs" to heartbeat,
            "driverHeartbeatAgeMs" to heartbeatAge,
            "driverActive" to (!aeron.isClosed && heartbeatAge in 0..2000),
            "driverClosed" to aeron.isClosed,
            "members" to settings.transport.members,
            "logChannel" to context.logChannel(),
            "logStreamId" to context.logStreamId(),
            "counterScope" to "CONSENSUS_ARCHIVE_LOG_STREAM",
            "counterDumpComplete" to false,
            "counterAllocatedObserved" to allocatedObserved,
            "counterSelectedCount" to counters.size,
            "counterExcludedCount" to allocatedObserved - counters.size,
            "counterLimit" to 256,
            "counters" to counters,
        )
    }

    fun requestSnapshot(): Boolean {
        check(service.tradingReady) { "초기화되지 않은 역할의 snapshot은 요청하지 않습니다" }
        return ClusterControl.ToggleState.SNAPSHOT.toggle(checkNotNull(consensus).context().controlToggleCounter())
    }

    private fun fail(error: Throwable) {
        if (!stopped.get()) {
            failure.compareAndSet(null, error)
            signal.countDown()
        }
    }

    private fun launch() {
        val config = settings.transport
        run = SplitTradingRun.open(settings)
        val ports = mutableListOf<DatagramSocket>()
        try {
            (0..settings.lastPortOffset).forEach { offset ->
                DatagramSocket(null).also { socket ->
                    ports.add(socket)
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(config.bindHost, config.basePort + offset))
                }
            }
        } finally {
            ports.forEach { it.close() }
        }
        val errors = ErrorHandler { error -> if (isSplitNodeFatalError(error)) fail(error) }
        driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(config.driverRoot != null)
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
                    .archiveId(settings.clusterId.toLong())
                    .archiveDir(config.archiveDirectory.toFile())
                    .deleteArchiveOnStart(false)
                    .controlChannel(config.udp(4))
                    .localControlChannel("aeron:ipc")
                    .replicationChannel(config.udp(5))
                    .recordingEventsEnabled(false)
                    .segmentFileLength(TradingAeronRuntime.TERM_LENGTH_BYTES)
                    .fileSyncLevel(2)
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
                    .clusterId(settings.clusterId)
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
                    .snapshotChannel("aeron:ipc?term-length=64k|alias=split-snapshot")
                    .maxConcurrentSessions(16)
                    .sessionTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.sessionTimeoutMs))
                    .fileSyncLevel(2)
                    .idleStrategySupplier(TradingAeronRuntime::idleStrategy)
                    .errorHandler(errors)
                    .terminationHook { fail(IllegalStateException("분리 consensus가 종료됐습니다")) },
            )
        container =
            ClusteredServiceContainer.launch(
                ClusteredServiceContainer
                    .Context()
                    .aeronDirectoryName(config.driverDirectory)
                    .clusterId(settings.clusterId)
                    .serviceId(config.serviceId)
                    .clusterDir(config.clusterDirectory.toFile())
                    .archiveContext(config.archiveClient())
                    .snapshotChannel("aeron:ipc?term-length=64k|alias=split-snapshot")
                    .clusteredService(serviceDecorator(service))
                    .idleStrategySupplier(TradingAeronRuntime::idleStrategy)
                    .errorHandler(errors)
                    .terminationHook { fail(IllegalStateException("분리 서비스가 종료됐습니다")) },
            )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs)
        while (!service.started || checkNotNull(consensus).context().electionStateCounter().get() != ElectionState.CLOSED.code().toLong()) {
            error?.let { throw IllegalStateException("분리 노드 기동 실패", it) }
            check(System.nanoTime() < deadline) { "분리 노드 기동 기한을 초과했습니다" }
            Thread.sleep(1)
        }
        check(storageLevels == listOf(2, 2, 2))
        // 먼저 소유권을 저장해야 두 번째 작업의 시작 실패도 첫 작업 정리로 이어진다.
        tasks =
            splitTradingDeliveryTasks(
                settings,
                service,
                orderConnectionFactory = orderConnectionFactory,
                resultConnectionFactory = resultConnectionFactory,
                onFailure = ::fail,
                legacyOrderFactory = deliveryFactory,
                legacyResultFactory = resultDeliveryFactory,
            )
        checkNotNull(tasks).start()
        Thread({
            signal.await()
            close()
        }, "split-trading-owner").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        signal.countDown()
        listOf(tasks, container, consensus, archive, driver, run).forEach { resource ->
            try {
                resource?.close()
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
    }

    companion object {
        fun start(
            settings: SplitTradingNodeSettings,
            serviceDecorator: (SplitTradingClusterAdapter) -> ClusteredService = { it },
            resultDeliveryFactory: ((SplitTradingClusterAdapter) -> MatchingResultDeliveryAgent)? = null,
            deliveryFactory: ((SplitTradingClusterAdapter) -> FundsOrderDeliveryAgent)? = null,
        ): DevelopmentSplitTradingNode =
            start(
                settings,
                AeronSplitOrderDeliveryConnectionFactory,
                AeronSplitResultDeliveryConnectionFactory,
                serviceDecorator,
                resultDeliveryFactory,
                deliveryFactory,
            )

        fun start(
            settings: SplitTradingNodeSettings,
            orderConnectionFactory: SplitOrderDeliveryConnectionFactory,
            resultConnectionFactory: SplitResultDeliveryConnectionFactory,
            serviceDecorator: (SplitTradingClusterAdapter) -> ClusteredService = { it },
            resultDeliveryFactory: ((SplitTradingClusterAdapter) -> MatchingResultDeliveryAgent)? = null,
            deliveryFactory: ((SplitTradingClusterAdapter) -> FundsOrderDeliveryAgent)? = null,
        ): DevelopmentSplitTradingNode =
            DevelopmentSplitTradingNode(
                settings,
                serviceDecorator,
                deliveryFactory,
                resultDeliveryFactory,
                orderConnectionFactory,
                resultConnectionFactory,
            ).also { node ->
                try {
                    node.launch()
                } catch (failure: Throwable) {
                    node.close()
                    throw failure
                }
            }
    }
}

/** 일시적 peer DNS 단절은 client가 재연결한다. 업무 오류는 이 분류를 거치지 않고 노드를 차단한다. */
internal fun isSplitNodeFatalError(error: Throwable): Boolean {
    if (error is AeronException && error.category() == AeronException.Category.WARN) return false
    if (error is InvalidChannelException &&
        generateSequence(error as Throwable?) { it.cause }.any { it is UnknownHostException }
    ) {
        return false
    }
    return true
}

/** 같은 실행물·고정 설정·기록만 재사용하며 통합 역할의 식별 형식은 유지한다. */
private class SplitTradingRun(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun open(settings: SplitTradingNodeSettings): SplitTradingRun {
            val config = settings.transport
            val root = config.runRoot
            val expected = settings.identityBytes()
            if (config.recoverExisting) {
                require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "복구할 분리 실행 루트가 없습니다" }
                val identity = root.resolve("split-recovery-identity.bin")
                require(Files.isRegularFile(identity, NOFOLLOW_LINKS) && Files.size(identity) in 1..1_048_576)
                require(Files.readAllBytes(identity).contentEquals(expected)) {
                    "분리 실행물·배치·연결 설정이 원래 기록과 다릅니다"
                }
                for (file in listOf(config.archiveDirectory.resolve("archive.catalog"), config.clusterDirectory.resolve("recording.log"))) {
                    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0) { "분리 복구 기록이 없습니다" }
                }
            } else {
                Files.createDirectory(root)
            }
            val channel = FileChannel.open(root.resolve("recovery.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
            try {
                val lock = checkNotNull(channel.tryLock()) { "동일 분리 기록은 한 노드만 소유합니다" }
                if (!config.recoverExisting) {
                    FileChannel.open(root.resolve("split-recovery-identity.bin"), CREATE_NEW, WRITE).use { output ->
                        val buffer = java.nio.ByteBuffer.wrap(expected)
                        while (buffer.hasRemaining()) output.write(buffer)
                        output.force(true)
                    }
                }
                return SplitTradingRun(channel, lock)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }
}

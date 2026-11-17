package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.ElectionState
import io.aeron.cluster.service.Cluster
import io.aeron.driver.status.StreamCounter
import org.agrona.concurrent.status.CountersReader

/** 같은 부팅의 witness 호출 스레드 하나가 관찰하며 실제 노드의 생명주기는 소유하지 않는다. */
internal class FundsQuorumRuntimeObservation(
    private val node: DevelopmentSplitTradingNode,
) {
    // 같은 부팅에서 확인한 정상 log recorder 하나만 보관한다. 과거 금액이나 append 값을 현재 값으로 쓰지 않는다.
    private var lastObservedAppendCounter: Map<String, Any?>? = null

    /** 합의와 transport의 현재 counter만 읽는다. 금융 상태나 서비스 callback에는 접근하지 않는다. */
    fun view(): Map<String, Any?> {
        val settings = node.settings
        val context = node.consensusContext
        val aeron = context.aeron()
        val reader = aeron.countersReader()
        val archiveId = node.archiveId
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
}

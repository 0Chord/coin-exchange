package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.trading.RequestToken
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 금융 객체는 서비스 스레드가 복사하고 witness에는 완결된 불변 관찰만 공개한다. */
internal class FundsQuorumServiceObservation(
    private val callbackLimit: Int = 64,
) {
    private data class Callback(
        val metadataJson: String,
        val entryIndex: Long,
        val completed: Boolean,
    )

    private data class Published(
        val snapshot: String? = null,
        val callbacks: List<Callback> = emptyList(),
        val stableVersion: Long = 0,
        val inFlight: Boolean = false,
    )

    private val json = JsonMapper.builder().build()
    private val published = AtomicReference(Published())
    private val background = AtomicLong()

    /** 이 메서드와 publish의 writer는 실제 서비스 스레드 하나다. */
    fun enter(metadata: Map<String, Any?>) {
        val previous = published.get()
        check(!previous.inFlight && previous.callbacks.size < callbackLimit) { "P callback 관찰 누락 또는 상한 초과입니다" }
        val metadataJson = json.writeValueAsString(metadata)
        check(metadataJson.length <= 4096) { "P callback 관찰 원문 한도를 넘었습니다" }
        val callback = Callback(metadataJson, previous.callbacks.size + 1L, false)
        published.set(previous.copy(callbacks = previous.callbacks + callback, stableVersion = previous.stableVersion + 1, inFlight = true))
    }

    fun publish(snapshot: String) {
        check(snapshot.toByteArray().size <= 256 * 1024) { "서비스 금융 관찰 상한을 넘었습니다" }
        val previous = published.get()
        val callbacks =
            if (previous.inFlight) previous.callbacks.dropLast(1) + previous.callbacks.last().copy(completed = true) else previous.callbacks
        published.set(
            Published(
                snapshot,
                callbacks,
                previous.stableVersion + if (previous.inFlight) 1 else 2,
                false,
            ),
        )
    }

    /** 금융 상태에 접근하지 않는 Aeron 서비스 poll의 실제 진행 근거다. */
    fun backgroundWork() {
        background.incrementAndGet()
    }

    fun view(): Map<String, Any?> {
        val state = published.get()
        return mapOf(
            "snapshot" to state.snapshot?.let(json::readTree),
            "callbacks" to
                state.callbacks.map { callback ->
                    val row = json.readTree(callback.metadataJson) as tools.jackson.databind.node.ObjectNode
                    row.put("entryIndex", callback.entryIndex)
                    row.put("phase", if (callback.completed) "COMPLETED" else "ENTERED")
                    row
                },
            "callbackEntries" to state.callbacks.size.toLong(),
            "callbackCompleted" to state.callbacks.count { it.completed }.toLong(),
            "backgroundWorkCount" to background.get(),
            "stableVersion" to state.stableVersion,
            "ready" to (state.snapshot != null && !state.inFlight && state.stableVersion % 2 == 0L),
        )
    }
}

/** 새 nonce만으로 현재 프로세스에 질의한다. 서비스 callback이나 금융 객체 접근은 만들지 않는다. */
internal class FundsQuorumWitness(
    private val control: Path,
    private val service: String,
    private val identity: Map<String, Any?>,
    private val observation: FundsQuorumServiceObservation,
    private val runtime: () -> Map<String, Any?>,
) {
    private val json = JsonMapper.builder().build()
    private var lastNonce: String? = null

    fun poll() {
        val request = control.resolve("witness-request-$service.json")
        if (!Files.isRegularFile(request)) return
        check(Files.size(request) <= 2048) { "witness 요청 한도를 넘었습니다" }
        val nonce = json.readTree(Files.readAllBytes(request)).path("nonce").asString()
        require(nonce.matches(Regex("[A-Za-z0-9_-]{8,96}"))) { "witness nonce가 유효하지 않습니다" }
        if (nonce == lastNonce) return

        fun failed(error: Exception): Map<String, Any?> =
            identity +
                mapOf(
                    "nonce" to nonce,
                    "observationFailure" to "${error.javaClass.simpleName}: ${error.message}".take(2048),
                    "ready" to false,
                    "serviceObservation" to mapOf("ready" to false),
                )
        val response =
            try {
                val before = observation.view()
                val node = runtime()
                val after = observation.view()
                val stable = before["stableVersion"] == after["stableVersion"] && after["ready"] == true
                identity +
                    mapOf(
                        "nonce" to nonce,
                        "node" to node,
                        "serviceObservation" to (after + ("ready" to stable)),
                    )
            } catch (error: Exception) {
                // 관찰 실패는 시험 실패 자료다. 새 노드 장애를 만들어 원래 장애와 섞지 않는다.
                failed(error)
            }
        try {
            writeBoundedHttpJson(control.resolve("witness-$service.json"), response)
            // 실패도 현재 nonce의 확정된 관찰 결과로 둔다. 다음 새 nonce에서 다시 읽는다.
            lastNonce = nonce
        } catch (error: Exception) {
            try {
                writeBoundedHttpJson(control.resolve("witness-$service.json"), failed(error))
                lastNonce = nonce
            } catch (_: Exception) {
                // 기록 자체가 불가능하면 runner가 현재 nonce 응답 부재로 실패한다. 노드는 종료하지 않는다.
            }
        }
    }
}

internal fun fundsQuorumPendingLabel(token: RequestToken): String? =
    when {
        token.userId.value == "buyer" && token.channel == 0 && token.epoch == 1L && token.sequence == 2L -> "pb"
        token.userId.value == "buyer" && token.channel == 1 && token.epoch == 1L && token.sequence == 1L -> "ps"
        else -> null
    }

/** active recorder와 선거 준비로 해제된 recorder를 구분한다. 미식별·변화 중 관찰은 통과시키지 않는다. */
internal fun fundsQuorumNodeObservationViolations(node: tools.jackson.databind.JsonNode): List<String> {
    val violations = mutableListOf<String>()

    fun requireObserved(
        condition: Boolean,
        reason: String,
    ) {
        if (!condition) violations.add(reason)
    }
    val role = node.path("role").asString()
    val election = node.path("electionState").asString()
    requireObserved(
        node.path("counterReadStable").asBoolean() && node.path("consensusReadStable").asBoolean(),
        "counter 또는 합의 상태가 관찰 중 바뀌었습니다",
    )
    requireObserved(
        node.path("recordingCountersIdentified").asBoolean() && node.path("recordingIdentitiesStable").asBoolean(),
        "recording counter의 식별 또는 전후 동일성이 확인되지 않았습니다",
    )
    for (field in listOf("role", "electionState")) {
        requireObserved(
            node.path(field) == node.path("${field}Before") && node.path(field) == node.path("${field}After"),
            "$field 전후 관찰이 다릅니다",
        )
    }
    for (field in listOf("electionCount", "leadershipTermId")) {
        val before = node.path("${field}Before")
        val after = node.path("${field}After")
        requireObserved(before.isIntegralNumber && before.asLong() >= 0 && before == after, "$field 전후 관찰이 없거나 다릅니다")
    }
    val append = node.path("appendCounters")
    val position = node.path("appendPosition")

    fun identified(counter: tools.jackson.databind.JsonNode): Boolean =
        counter.path("counterId").isIntegralNumber && counter.path("counterId").asInt() >= 0 &&
            counter.path("typeId").asInt(-1) == 100 && counter.path("registrationId").asLong(-1) > 0 &&
            counter.path("recordingId").asLong(-1) >= 0 && counter.path("stable").asBoolean() &&
            counter.path("recordingMetadataIdentified").asBoolean() &&
            counter.path("archiveId").isIntegralNumber && counter.path("archiveId") == node.path("archiveId") &&
            counter.path("recordingStreamId").isIntegralNumber && counter.path("recordingStreamId") == node.path("logStreamId") &&
            counter.path("recordingChannel").asString().startsWith("aeron:") && !counter.path("sourceIdentity").asString().isBlank()
    when (node.path("appendState").asString()) {
        "ACTIVE" -> {
            requireObserved(append.isArray && append.size() == 1 && identified(append.path(0)), "현재 active log recording이 하나로 식별되지 않았습니다")
            requireObserved(
                position.isIntegralNumber && position.asLong() >= 0 && position == append.path(0).path("value"),
                "active append 위치가 실제 recorder 값과 다릅니다",
            )
        }

        "RECORDING_INACTIVE_DURING_ELECTION" -> {
            val electionAllowsInactive =
                (election in setOf("CANVASS", "NOMINATE", "FOLLOWER_BALLOT") && role == "FOLLOWER") ||
                    (election == "CANDIDATE_BALLOT" && role == "CANDIDATE")
            val evidence = node.path("inactiveAppendEvidence")
            val previous = evidence.path("previousActiveCounter")
            val originalRegistration = previous.path("registrationId").asLong(-1)
            val currentRegistration = evidence.path("currentRegistrationId").asLong(-1)
            val released =
                evidence.path("currentRecordState").asInt() == -1 && currentRegistration == originalRegistration &&
                    evidence.path("currentTypeId").asInt(-1) == 100
            val reused =
                evidence.path("currentRecordState").asInt() == 1 && currentRegistration > 0 && currentRegistration != originalRegistration
            requireObserved(
                electionAllowsInactive && position.isNull && append.isArray && append.isEmpty,
                "현재 선거 단계가 recorder 비활성 상태와 맞지 않습니다",
            )
            requireObserved(
                identified(previous) && previous.path("observedElectionState").asString() == "CLOSED",
                "같은 로컬 archive의 선거 전 active recorder 근거가 없습니다",
            )
            requireObserved(
                previous.path("observedElectionCount").isIntegralNumber &&
                    node.path("electionCountAfter").asLong(-1) > previous.path("observedElectionCount").asLong(),
                "원 recorder 관찰 뒤 새 선거가 시작되지 않았습니다",
            )
            requireObserved(
                evidence.path("currentCounterId") == previous.path("counterId") && evidence.path("counterIdentityReadStable").asBoolean(),
                "원 recorder counter의 현재 식별이 불안정합니다",
            )
            requireObserved(
                evidence.path("activeRecordingCounterId").isIntegralNumber && evidence.path("activeRecordingCounterId").asInt() == -1 &&
                    (released || reused),
                "원 recording이 해제됐다는 실제 counter 근거가 없습니다",
            )
        }

        else -> {
            violations.add("append 관찰이 미해결 상태입니다")
        }
    }
    return violations
}

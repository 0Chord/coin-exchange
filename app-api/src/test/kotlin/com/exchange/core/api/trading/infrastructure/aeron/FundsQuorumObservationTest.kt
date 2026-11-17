package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 무과반 witness는 callback을 만들지 않고 완결된 금융 사본과 진행만 읽는다. */
class FundsQuorumObservationTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `P 진입과 delegate 완료 사이의 금융 관찰은 미준비이며 완료 뒤 한 번에 게시한다`() {
        val observation = FundsQuorumServiceObservation()
        observation.publish("{\"funds\":{\"hold\":600}}")
        observation.enter(mapOf("requestSha256" to "p", "logPosition" to 128L))
        val entering = json.valueToTree<tools.jackson.databind.JsonNode>(observation.view())
        assertFalse(entering.path("ready").asBoolean())
        assertEquals(1, entering.path("callbackEntries").asInt())
        assertEquals(0, entering.path("callbackCompleted").asInt())
        assertEquals(
            600,
            entering
                .path("snapshot")
                .path("funds")
                .path("hold")
                .asInt(),
        )
        observation.publish("{\"funds\":{\"hold\":700}}")
        val completed = json.valueToTree<tools.jackson.databind.JsonNode>(observation.view())
        assertTrue(completed.path("ready").asBoolean())
        assertEquals(1, completed.path("callbackCompleted").asInt())
        assertEquals(
            "COMPLETED",
            completed
                .path("callbacks")
                .path(0)
                .path("phase")
                .asString(),
        )
        assertEquals(
            700,
            completed
                .path("snapshot")
                .path("funds")
                .path("hold")
                .asInt(),
        )
    }

    @Test
    fun `새 nonce 응답을 읽는 동안 service background 진행은 금융 callback 수를 바꾸지 않는다`() {
        val observation = FundsQuorumServiceObservation()
        observation.publish("{\"funds\":{\"hold\":600}}")
        val before = observation.view()
        repeat(3) { observation.backgroundWork() }
        val after = observation.view()
        assertEquals(0L, before["backgroundWorkCount"])
        assertEquals(3L, after["backgroundWorkCount"])
        assertEquals(before["snapshot"], after["snapshot"])
        assertEquals(0L, after["callbackEntries"])
        assertEquals(0L, after["callbackCompleted"])
    }

    @Test
    fun `새 nonce의 witness는 실제 runtime과 같은 boot의 불변 금융 사본을 현재 진행에 결속한다`(
        @TempDir directory: Path,
    ) {
        val observation = FundsQuorumServiceObservation()
        observation.publish("{\"bootId\":\"boot-current\",\"funds\":{\"hold\":600}}")
        var runtimeReads = 0
        val witness =
            FundsQuorumWitness(directory, "funds0", mapOf("bootId" to "boot-current"), observation) {
                runtimeReads++
                mapOf("commitPosition" to 256, "driverHeartbeatMs" to runtimeReads)
            }
        val request = directory.resolve("witness-request-funds0.json")
        Files.writeString(request, "{\"nonce\":\"nonce-first-001\"}")
        witness.poll()
        val first = json.readTree(Files.readAllBytes(directory.resolve("witness-funds0.json")))
        assertEquals("nonce-first-001", first.path("nonce").asString())
        assertEquals(
            "boot-current",
            first
                .path("serviceObservation")
                .path("snapshot")
                .path("bootId")
                .asString(),
        )
        witness.poll()
        assertEquals(1, runtimeReads)
        repeat(3) { observation.backgroundWork() }
        Files.writeString(request, "{\"nonce\":\"nonce-second-002\"}")
        witness.poll()
        val second = json.readTree(Files.readAllBytes(directory.resolve("witness-funds0.json")))
        assertEquals("nonce-second-002", second.path("nonce").asString())
        assertEquals(2, runtimeReads)
        assertEquals(3, second.path("serviceObservation").path("backgroundWorkCount").asInt())
        assertEquals(0, second.path("serviceObservation").path("callbackEntries").asInt())
        assertEquals(first.path("serviceObservation").path("snapshot"), second.path("serviceObservation").path("snapshot"))
    }

    @Test
    fun `runtime 관찰 예외는 현재 nonce 실패로 보존하고 새 nonce에서 callback 없이 재시도한다`(
        @TempDir directory: Path,
    ) {
        val observation = FundsQuorumServiceObservation()
        observation.publish("{\"funds\":{\"hold\":600}}")
        var runtimeReads = 0
        val witness =
            FundsQuorumWitness(directory, "funds1", mapOf("bootId" to "same-boot"), observation) {
                runtimeReads++
                if (runtimeReads == 1) error("counter 상한 초과")
                mapOf("commitPosition" to 256)
            }
        val request = directory.resolve("witness-request-funds1.json")
        val response = directory.resolve("witness-funds1.json")
        Files.writeString(request, "{\"nonce\":\"nonce-failure-001\"}")
        witness.poll()
        val failed = json.readTree(Files.readAllBytes(response))
        assertEquals("nonce-failure-001", failed.path("nonce").asString())
        assertEquals("same-boot", failed.path("bootId").asString())
        assertTrue(failed.path("observationFailure").asString().contains("counter 상한 초과"))
        assertFalse(failed.path("serviceObservation").path("ready").asBoolean())
        witness.poll()
        assertEquals(1, runtimeReads)
        assertEquals(failed, json.readTree(Files.readAllBytes(response)))
        Files.writeString(request, "{\"nonce\":\"nonce-retry-002\"}")
        witness.poll()
        val retried = json.readTree(Files.readAllBytes(response))
        assertEquals(2, runtimeReads)
        assertEquals("nonce-retry-002", retried.path("nonce").asString())
        assertTrue(retried.path("serviceObservation").path("ready").asBoolean())
        assertEquals(0, retried.path("serviceObservation").path("callbackEntries").asInt())
        assertEquals(0, retried.path("serviceObservation").path("callbackCompleted").asInt())
        assertEquals(0L, observation.view()["callbackEntries"])
    }

    @Test
    fun `정상 active append와 선거 중 식별된 recording 해제만 허용한다`() {
        assertTrue(fundsQuorumNodeObservationViolations(nodeObservation()).isEmpty())
        assertTrue(fundsQuorumNodeObservationViolations(nodeObservation(inactive = true)).isEmpty())
        val reused = nodeObservation(inactive = true)
        val evidence = reused.path("inactiveAppendEvidence") as tools.jackson.databind.node.ObjectNode
        evidence.put("currentRecordState", 1)
        evidence.put("currentRegistrationId", 999L)
        evidence.put("currentTypeId", 2)
        assertTrue(fundsQuorumNodeObservationViolations(reused).isEmpty())
    }

    @Test
    fun `미식별 중복 append와 CLOSED 상태의 누락을 선거 중단으로 바꾸지 않는다`() {
        val missing = nodeObservation()
        missing.putNull("appendPosition")
        missing.set("appendCounters", json.createArrayNode())
        assertTrue(fundsQuorumNodeObservationViolations(missing).isNotEmpty())
        val ambiguous = nodeObservation()
        (
            ambiguous.path(
                "appendCounters",
            ) as tools.jackson.databind.node.ArrayNode
        ).add(
            ambiguous.path("appendCounters").path(0).deepCopy(),
        )
        assertTrue(fundsQuorumNodeObservationViolations(ambiguous).isNotEmpty())
        val unidentified = nodeObservation()
        (unidentified.path("appendCounters").path(0) as tools.jackson.databind.node.ObjectNode).put("recordingMetadataIdentified", false)
        assertTrue(fundsQuorumNodeObservationViolations(unidentified).isNotEmpty())
        val closed = nodeObservation(inactive = true)
        closed.put("electionState", "CLOSED")
        closed.put("electionStateBefore", "CLOSED")
        closed.put("electionStateAfter", "CLOSED")
        assertTrue(fundsQuorumNodeObservationViolations(closed).isNotEmpty())
        val noIdentity = nodeObservation(inactive = true)
        noIdentity.putNull("inactiveAppendEvidence")
        assertTrue(fundsQuorumNodeObservationViolations(noIdentity).isNotEmpty())
    }

    @Test
    fun `role election 전후 변화와 원래 recording의 잔존 및 다른 archive 근거는 거절한다`() {
        val changed = nodeObservation(inactive = true)
        changed.put("roleAfter", "CANDIDATE")
        assertTrue(fundsQuorumNodeObservationViolations(changed).isNotEmpty())
        val stillActive = nodeObservation(inactive = true)
        (stillActive.path("inactiveAppendEvidence") as tools.jackson.databind.node.ObjectNode).put("activeRecordingCounterId", 25)
        assertTrue(fundsQuorumNodeObservationViolations(stillActive).isNotEmpty())
        val otherArchive = nodeObservation(inactive = true)
        (
            otherArchive
                .path(
                    "inactiveAppendEvidence",
                ).path("previousActiveCounter") as tools.jackson.databind.node.ObjectNode
        ).put("archiveId", 999L)
        assertTrue(fundsQuorumNodeObservationViolations(otherArchive).isNotEmpty())
        val unchangedElection = nodeObservation(inactive = true)
        (
            unchangedElection
                .path(
                    "inactiveAppendEvidence",
                ).path("previousActiveCounter") as tools.jackson.databind.node.ObjectNode
        ).put("observedElectionCount", 2L)
        assertTrue(fundsQuorumNodeObservationViolations(unchangedElection).isNotEmpty())
    }

    private fun nodeObservation(inactive: Boolean = false): tools.jackson.databind.node.ObjectNode {
        val role = if (inactive) "FOLLOWER" else "LEADER"
        val election = if (inactive) "CANVASS" else "CLOSED"
        val recording =
            mapOf(
                "counterId" to 25,
                "typeId" to 100,
                "registrationId" to 125L,
                "value" to 256L,
                "stable" to true,
                "recordingId" to 7L,
                "archiveId" to 198L,
                "recordingStreamId" to 100,
                "recordingMetadataIdentified" to true,
                "recordingChannel" to "aeron:udp?control=10.0.0.2:9002|control-mode=manual",
                "sourceIdentity" to "10.0.0.2:44000",
                "observedElectionState" to "CLOSED",
                "observedElectionCount" to 1L,
            )
        return json.valueToTree(
            mapOf(
                "clusterId" to 198,
                "archiveId" to 198L,
                "logStreamId" to 100,
                "role" to role,
                "roleBefore" to role,
                "roleAfter" to role,
                "electionState" to election,
                "electionStateBefore" to election,
                "electionStateAfter" to election,
                "electionCountBefore" to 2L,
                "electionCountAfter" to 2L,
                "leadershipTermIdBefore" to 0L,
                "leadershipTermIdAfter" to 0L,
                "consensusReadStable" to true,
                "counterReadStable" to true,
                "recordingCountersIdentified" to true,
                "recordingIdentitiesStable" to true,
                "appendState" to if (inactive) "RECORDING_INACTIVE_DURING_ELECTION" else "ACTIVE",
                "appendPosition" to if (inactive) null else 256L,
                "appendCounters" to if (inactive) emptyList() else listOf(recording),
                "inactiveAppendEvidence" to
                    if (inactive) {
                        mapOf(
                            "previousActiveCounter" to recording,
                            "currentCounterId" to 25,
                            "currentRecordState" to -1,
                            "currentRegistrationId" to 125L,
                            "currentTypeId" to 100,
                            "activeRecordingCounterId" to -1,
                            "counterIdentityReadStable" to true,
                        )
                    } else {
                        null
                    },
            ),
        )
    }

    @Test
    fun `공개한 callback 자료는 원본 변경에 영향받지 않고 유한 상한 초과는 실패한다`() {
        val observation = FundsQuorumServiceObservation(callbackLimit = 1)
        val source = mutableMapOf<String, Any?>("requestSha256" to "original")
        observation.enter(source)
        source["requestSha256"] = "changed"
        observation.publish("{}")
        val state = json.valueToTree<tools.jackson.databind.JsonNode>(observation.view())
        assertEquals(
            "original",
            state
                .path("callbacks")
                .path(0)
                .path("requestSha256")
                .asString(),
        )
        assertFailsWith<IllegalStateException> { observation.enter(mapOf("requestSha256" to "extra")) }
    }
}

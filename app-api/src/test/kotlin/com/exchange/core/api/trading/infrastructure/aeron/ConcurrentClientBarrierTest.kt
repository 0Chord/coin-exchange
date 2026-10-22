package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConcurrentClientBarrierTest {
    @TempDir lateinit var directory: Path
    private val runId = "run109"
    private val mapper = jacksonObjectMapper()

    @Test
    fun `두 연결 준비 뒤 하나의 출발 파일을 공개한다`() {
        val barrier = ConcurrentClientBarrier(directory, runId)
        assertTrue(barrier.release(ready()).isEmpty())
        assertEquals(runId, Files.readString(barrier.go))
        assertFalse(Files.exists(directory.resolve("go.pending")))
    }

    @Test
    fun `다중 마켓은 BTC A와 SUI B가 준비되면 GO를 공개한다`() {
        val barrier = ConcurrentClientBarrier(directory, runId, ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)

        assertTrue(barrier.release(ready(marketB = "SUI-USDT")).isEmpty())
        assertEquals(runId, Files.readString(barrier.go))
        assertFalse(Files.exists(directory.resolve("go.pending")))
    }

    @Test
    fun `다중 마켓은 B의 잘못된 마켓이나 준비 누락과 조기 진행에 GO를 공개하지 않는다`() {
        val expectedReady = ready(marketB = "SUI-USDT")
        val samples =
            listOf(
                ready(),
                expectedReady - "B",
                expectedReady +
                    ("B" to listOf(event("B", "READY", marketId = "SUI-USDT"), event("B", "SEND_STARTED", marketId = "SUI-USDT"))),
            )

        for (events in samples) {
            val barrier = ConcurrentClientBarrier(directory, runId, ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS)
            val issues = barrier.release(events)

            assertTrue(issues.isNotEmpty())
            assertTrue(issues.all { it.category == "SETUP" })
            assertFalse(Files.exists(barrier.go))
            assertFalse(Files.exists(directory.resolve("go.pending")))
        }
    }

    @Test
    fun `한 연결 누락이나 이전 실행과 조기 전송은 GO를 만들지 않는다`() {
        val samples =
            listOf(
                ready() - "B",
                ready() + ("A" to listOf(event("A", "READY", "old-run"))),
                ready() + ("A" to listOf(event("A", "READY"), event("A", "SEND_STARTED"))),
                ready() + ("A" to emptyList()),
            )
        for (events in samples) {
            val barrier = ConcurrentClientBarrier(directory, runId)
            assertTrue(barrier.release(events).isNotEmpty())
            assertFalse(Files.exists(barrier.go))
        }
    }

    @Test
    fun `이전 GO를 현재 runId로 덮어쓰지 않는다`() {
        val barrier = ConcurrentClientBarrier(directory, runId)
        Files.writeString(barrier.go, "old-run")
        assertTrue(barrier.release(ready()).isNotEmpty())
        assertEquals("old-run", Files.readString(barrier.go))
    }

    @Test
    fun `같은 세션이나 egress와 driver를 공유하면 준비 실패다`() {
        for (field in listOf("sessionId", "egressChannel", "driverDirectory", "advertisedHost", "runRoot")) {
            val b = mapper.readTree(event("B", "READY").toString()) as tools.jackson.databind.node.ObjectNode
            b.set(field, event("A", "READY").path(field))
            assertTrue(compareConcurrentClientReadiness(runId, ready() + ("B" to listOf(b))).isNotEmpty(), field)
        }
    }

    @Test
    fun `각 클라이언트의 인과 순서만 검사하고 뒤바뀐 단계와 결과 미확인은 실패한다`() {
        val kinds = listOf("READY", "GO_OBSERVED", "SEND_STARTED", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED")
        val normal = listOf("A", "B").associateWith { id -> kinds.map { event(id, it) } }
        assertTrue(compareConcurrentClientProgress(runId, normal).isEmpty())
        val swapped = listOf("READY", "SEND_STARTED", "GO_OBSERVED", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED")
        assertTrue(compareConcurrentClientProgress(runId, normal + ("B" to swapped.map { event("B", it) })).isNotEmpty())
        assertTrue(
            compareConcurrentClientProgress(runId, normal + ("A" to normal.getValue("A") + listOf(event("A", "UNCONFIRMED")))).isNotEmpty(),
        )
    }

    @Test
    fun `다중 마켓의 B는 READY부터 응답까지 SUI identity를 유지해야 한다`() {
        val scenario = ConcurrentOrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS
        val kinds = listOf("READY", "GO_OBSERVED", "SEND_STARTED", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED")
        val normal =
            mapOf(
                "A" to kinds.map { event("A", it) },
                "B" to kinds.map { event("B", it, marketId = "SUI-USDT") },
            )

        assertTrue(compareConcurrentClientProgress(runId, normal, scenario).isEmpty())

        for (changedKind in kinds) {
            val changedB = kinds.map { event("B", it, marketId = if (it == changedKind) "BTC-USDT" else "SUI-USDT") }
            val issues = compareConcurrentClientProgress(runId, normal + ("B" to changedB), scenario)

            assertTrue(issues.any { it.category == "MISMATCH" && "marketId" in it.message }, changedKind)
        }
    }

    private fun ready(marketB: String = "BTC-USDT") =
        mapOf("A" to listOf(event("A", "READY")), "B" to listOf(event("B", "READY", marketId = marketB)))

    private fun event(
        id: String,
        kind: String,
        sourceRunId: String = runId,
        marketId: String = "BTC-USDT",
    ): JsonNode =
        mapper.valueToTree(
            mapOf(
                "kind" to kind,
                "runId" to sourceRunId,
                "clientId" to id,
                "requestId" to "R$id",
                "orderId" to "O$id",
                "userId" to "U1",
                "marketId" to marketId,
                "inputKind" to "ORDER",
                "sessionId" to
                    if (id ==
                        "A"
                    ) {
                        1
                    } else {
                        2
                    },
                "pid" to 1,
                "advertisedHost" to "client-${id.lowercase()}",
                "egressChannel" to "aeron:udp?endpoint=client-${id.lowercase()}:19707",
                "driverDirectory" to "/tmp/$runId-client-${id.lowercase()}/driver",
                "runRoot" to "/tmp/$runId-client-${id.lowercase()}",
            ),
        )
}

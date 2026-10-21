package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.client.AeronCluster
import org.agrona.DirectBuffer
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevelopmentOrderStartGateTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `연결 준비 후 같은 실행의 GO를 확인해야 출발하고 대기 중에도 연결을 처리한다`() {
        val events = mutableListOf<String>()
        val path = directory.resolve("go")
        var polls = 0
        val gate = DevelopmentOrderStartGate(path, "run-current", "A", 1_000)

        gate.await(
            pollConnection = {
                events.add("POLL")
                if (++polls == 3) Files.writeString(path, "run-current")
            },
            onReady = { events.add("READY") },
            onGo = { events.add("GO_OBSERVED") },
        )
        events.add("SEND_STARTED")

        assertEquals(listOf("READY", "POLL", "POLL", "POLL", "GO_OBSERVED", "SEND_STARTED"), events)
    }

    @Test
    fun `준비 전에 이미 있던 GO는 이번 실행의 값이어도 출발 근거로 쓰지 않는다`() {
        listOf("old-run", "run-current").forEach { contents ->
            val path = directory.resolve("go")
            Files.writeString(path, contents)
            val events = mutableListOf<String>()
            val gate = DevelopmentOrderStartGate(path, "run-current", "A", 1_000)

            assertFailsWith<IllegalStateException> {
                gate.await({ events.add("POLL") }, { events.add("READY") }, { events.add("GO") })
                events.add("SEND")
            }

            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `준비 후에도 다른 실행의 GO나 손상된 신호로 주문을 보내지 않는다`() {
        listOf("old-run", "", "run-current\nextra", "x".repeat(300)).forEachIndexed { index, contents ->
            val path = directory.resolve("go-$index")
            val events = mutableListOf<String>()
            val gate = DevelopmentOrderStartGate(path, "run-current", "A", 1_000)

            assertFailsWith<IllegalStateException> {
                gate.await(
                    { Files.writeString(path, contents) },
                    { events.add("READY") },
                    { events.add("GO") },
                )
                events.add("SEND")
            }

            assertEquals(listOf("READY"), events)
        }
    }

    @Test
    fun `GO가 없으면 유한 대기 후 끝나고 출발하지 않는다`() {
        val events = mutableListOf<String>()
        var now = 0L
        val gate =
            DevelopmentOrderStartGate(
                directory.resolve("go"),
                "run-current",
                "A",
                10,
                nanoTime = { now },
                idle = { now += TimeUnit.MILLISECONDS.toNanos(1) },
            )

        val error =
            assertFailsWith<IllegalStateException> {
                gate.await({ events.add("POLL") }, { events.add("READY") }, { events.add("GO") })
                events.add("SEND")
            }

        assertTrue(error.message.orEmpty().contains("기한"))
        assertEquals(listOf("READY") + List(10) { "POLL" }, events)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10), now)
    }

    @Test
    fun `대기 중 연결 처리가 실패하면 GO 확인과 전송을 하지 않는다`() {
        val events = mutableListOf<String>()
        val gate = DevelopmentOrderStartGate(directory.resolve("go"), "run-current", "A", 1_000)

        val error =
            assertFailsWith<IllegalStateException> {
                gate.await({ error("connection failed") }, { events.add("READY") }, { events.add("GO") })
                events.add("SEND")
            }

        assertEquals("connection failed", error.message)
        assertEquals(listOf("READY"), events)
    }

    @Test
    fun `실제 클라이언트 대기 루프는 같은 소유 스레드에서 egress와 keepalive를 진행하고 offer는 하지 않는다`() {
        // 전송 API 호출의 경계만 검사한다. 실제 다중 노드 연결은 Docker 사례가 확인한다.
        val connection = mock(AeronCluster::class.java)
        `when`(connection.context()).thenReturn(AeronCluster.Context())
        val path = directory.resolve("go")
        val gate = DevelopmentOrderStartGate(path, "run-current", "A", 10_000)
        val owner = Thread.currentThread()
        val observedThreads = mutableListOf<Thread>()
        var start = 0L
        `when`(connection.pollEgress()).thenAnswer {
            observedThreads.add(Thread.currentThread())
            if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(550)) Files.writeString(path, "run-current")
            0
        }
        `when`(connection.sendKeepAlive()).thenAnswer {
            observedThreads.add(Thread.currentThread())
            true
        }

        mockStatic(AeronCluster::class.java).use { factory ->
            factory.`when`<AeronCluster> { AeronCluster.connect(any(AeronCluster.Context::class.java)) }.thenReturn(connection)
            DevelopmentOrderClient(DevelopmentFundingSettings(directory.resolve("client"))).use { client ->
                gate.await(client::pollConnection, { start = System.nanoTime() }, {})
            }
        }

        assertTrue(observedThreads.isNotEmpty())
        assertTrue(observedThreads.all { it === owner })
        verify(connection, atLeastOnce()).pollEgress()
        verify(connection, atLeastOnce()).sendKeepAlive()
        verify(connection, never()).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.net.DatagramSocket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 UDP·Consensus·Archive의 출처 계약이다. Echo·세션 지우기는 test decorator 안에서만 한다. */
class ClusterMessageOriginNodeTest {
    @Test
    fun `실제 외부 입력과 self-message는 조각 재조립 뒤에도 세션 헤더로 구분된다`() {
        val config = config()
        // 실제 UDP 기본 MTU를 넘는다. Echo는 변환기·자금 코어를 호출하지 않는 계약 시험 입력이다.
        val payload = ByteArray(3_000) { (it * 17).toByte() }
        lateinit var probe: OriginProbe
        DevelopmentFundingNode
            .start(config) { service ->
                OriginProbe(service, echo = payload).also { probe = it }
            }.use { node ->
                DevelopmentFundingClient(config).use { client ->
                    sendRaw(client, payload)
                    eventually { probe.frames.size == 2 }
                    val frames = probe.frames.toList()
                    assertFalse(frames[0].internal)
                    assertFalse(frames[0].nullSession)
                    assertTrue(frames[1].internal)
                    assertTrue(frames[1].nullSession)
                    assertTrue(frames[1].position > frames[0].position)
                    frames.forEach { assertContentEquals(payload, it.bytes) }

                    val recorded =
                        assertIs<RecordedArchiveRead.Complete>(
                            RecordedArchiveReader(config, node.logStreamId, node.logChannel).read(frames[1].position),
                        )
                    val application = recorded.inputs.filter { it.bytes.contentEquals(payload) }
                    assertEquals(2, application.size)
                    assertTrue(application[0].clusterSessionId >= 0)
                    assertTrue(application[1].clusterSessionId < 0)
                    assertEquals(0, (application[1].clusterSessionId ushr 56).toInt() and 0x7F)
                    application.forEach { assertTrue(it.fragmentCount > 1, "실제 Archive 프레임이 여러 조각이어야 한다") }
                    assertEquals(frames.map { it.position }, application.map { it.position })
                    assertNull(node.error)
                }
            }
    }

    @Test
    fun `외부 클라이언트의 유효 kind7은 실행하지 않고 다음 정상 자금 준비를 처리한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            DevelopmentFundingClient(config).use { client ->
                // 존재하지 않는 승인 1이다. 이를 내부로 믿으면 코어 오류로 노드가 실패해야 하므로 대조가 된다.
                sendRaw(client, ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(1)))
                eventually { node.observation?.malformedMessages == 1L }
                assertFalse(node.isClosed)
                assertNull(node.error)
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                assertEquals(1, assertNotNull(node.observation?.snapshot).preparations.size)
                assertEquals(1L, node.observation?.malformedMessages)
                assertNull(node.error)
            }
        }
    }

    @Test
    fun `실제 외부 자금 입력은 callback session을 null로 바꿔도 외부로 적용한다`() {
        val config = config().copy(resultTimeoutMs = 100)
        lateinit var probe: OriginProbe
        DevelopmentFundingNode
            .start(config) { service ->
                OriginProbe(service, eraseExternalSession = true).also { probe = it }
            }.use { node ->
                DevelopmentFundingClient(config).use { client ->
                    assertTrue(client.send(command()))
                    eventually {
                        node.observation
                            ?.snapshot
                            ?.preparations
                            ?.size == 1
                    }
                    assertIs<FundingRequestOutcome.Unconfirmed>(client.awaitResult())
                    val frame = probe.frames.single()
                    assertFalse(frame.internal)
                    assertFalse(frame.nullSession, "Aeron에서 받은 외부 세션은 존재하며 test decorator가 null로 바꾼다")
                    val snapshot = assertNotNull(node.observation?.snapshot)
                    assertEquals(
                        1_000L,
                        snapshot.balances
                            .single()
                            .available.value,
                    )
                    assertEquals(0L, node.observation?.malformedMessages)
                    assertNull(node.error)
                    assertFalse(node.isClosed)
                }
            }
    }

    private class OriginProbe(
        private val service: DevelopmentFundingAdapter,
        private val echo: ByteArray? = null,
        private val eraseExternalSession: Boolean = false,
    ) : ClusteredService by service {
        val frames = ConcurrentLinkedQueue<OriginFrame>()
        private lateinit var cluster: Cluster

        override fun onStart(
            cluster: Cluster,
            snapshotImage: Image?,
        ) {
            this.cluster = cluster
            service.onStart(cluster, snapshotImage)
        }

        override fun onSessionMessage(
            session: ClientSession?,
            timestamp: Long,
            buffer: DirectBuffer,
            offset: Int,
            length: Int,
            header: Header,
        ) {
            val internal = ClusterMessageOrigin.isServiceMessage(buffer, offset, length, 0)
            val bytes = ByteArray(length).also { buffer.getBytes(offset, it) }
            frames.add(OriginFrame(internal, session == null, cluster.logPosition(), bytes))
            if (echo != null && bytes.contentEquals(echo)) {
                if (!internal) offerSelf(bytes)
                return
            }
            service.onSessionMessage(if (eraseExternalSession && !internal) null else session, timestamp, buffer, offset, length, header)
        }

        private fun offerSelf(bytes: ByteArray) {
            val buffer = UnsafeBuffer(bytes)
            val started = System.nanoTime()
            repeat(1_000) {
                val result = cluster.offer(buffer, 0, bytes.size)
                if (result > 0) return
                check(result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) { "테스트 self-message 제출 실패: $result" }
                check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1)) { "테스트 self-message 제출 시간 초과" }
                cluster.idleStrategy().idle()
            }
            error("테스트 self-message 제출 횟수 초과")
        }
    }

    private data class OriginFrame(
        val internal: Boolean,
        val nullSession: Boolean,
        val position: Long,
        val bytes: ByteArray,
    )

    private fun sendRaw(
        client: DevelopmentFundingClient,
        bytes: ByteArray,
    ) {
        eventually { client.connection.offer(UnsafeBuffer(bytes), 0, bytes.size) > 0 }
    }

    private fun command() =
        DevelopmentBalanceCommandPayload(
            OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000)),
            "L1",
            Instant.parse("2026-10-26T00:00:00Z"),
        )

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(Files.createTempDirectory("origin129-").resolve("run"), base)
            } catch (_: java.net.BindException) {
                // 다른 프로세스와 겹친 포트 묶음만 다시 선택한다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트 묶음을 확보하지 못했습니다")
    }

    private fun eventually(condition: () -> Boolean) {
        val started = System.nanoTime()
        while (!condition()) {
            check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) { "실제 Cluster callback 대기 시간 초과" }
            Thread.sleep(1)
        }
    }
}

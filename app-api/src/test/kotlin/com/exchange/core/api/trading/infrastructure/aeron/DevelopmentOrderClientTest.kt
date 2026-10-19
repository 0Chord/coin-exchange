package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejected
import com.exchange.core.api.trading.infrastructure.codec.protobufFrame
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.exceptions.AeronException
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.DatagramSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * main과 올바른 결과는 실제 노드·클라이언트·코어를 사용한다.
 * 잘못된 응답만 수신 callback에 직접 주입하며, 희귀 전송 반환값·conductor 오류는 별도 전송 대역으로 통제한다.
 */
class DevelopmentOrderClientTest {
    private val time = Instant.parse("2026-10-20T00:00:00.123456789Z")
    private val results = AdmissionResultCodec()
    private val isolatedSettings = DevelopmentFundingSettings(Path.of("/unused-order-client-test"), resultTimeoutMs = 20)

    @Test
    fun `실제 주문 main은 전송 수용 다음에 접수 결과를 표시하고 한 주문만 반영한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config, 1_000_000_000)
            val command = order()
            val output = ByteArrayOutputStream()
            val original = System.out
            try {
                System.setOut(PrintStream(output, true, Charsets.UTF_8))
                Class
                    .forName("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentOrderClientKt")
                    .getMethod("main", Array<String>::class.java)
                    .invoke(
                        null,
                        arrayOf(
                            config.runRoot.toString(),
                            config.basePort.toString(),
                            command.requestId,
                            command.order.userId.value,
                            command.order.orderId.value,
                            command.order.side.name,
                            command.order.price.value
                                .toString(),
                            command.order.quantity.value
                                .toString(),
                            command.ledgerTransactionId,
                            command.occurredAt.toString(),
                        ),
                    )
            } finally {
                System.setOut(original)
            }

            val text = output.toString(Charsets.UTF_8)
            val transport = text.indexOf("TRANSPORT_ACCEPTED 결과 대기")
            val received = text.indexOf("RESULT_RECEIVED")
            assertTrue(transport >= 0, text)
            assertTrue(received > transport, text)
            assertTrue(text.contains("접수 결과이며 체결·정산 완료가 아닙니다"), text)
            assertFalse(text.contains("UNCONFIRMED"), text)
            val observation = assertNotNull(node.observation)
            assertEquals(expected(command, 1), assertIs<AdmissionResultPayload.Accepted>(observation.admissionResult))
            val snapshot = assertNotNull(observation.snapshot)
            assertEquals(
                700_000_000L,
                snapshot.balances
                    .single()
                    .available.value,
            )
            assertEquals(
                300_000_000L,
                snapshot.balances
                    .single()
                    .hold.value,
            )
            assertEquals(1L, snapshot.lastSequence)
            assertEquals(1, snapshot.preparations.size)
            assertEquals(1, snapshot.requests.size)
            val candidate = snapshot.pendingOrders.single().candidate
            assertEquals(command.order, candidate.order)
            assertEquals(300_000_000L, candidate.reservation.reservedAmount.value)
            assertEquals(command.ledgerTransactionId, candidate.reserveTransaction.ledgerTransactionId)
            assertEquals(command.occurredAt, candidate.reserveTransaction.occurredAt)
            assertEquals(listOf(300_000_000L, 300_000_000L), candidate.reserveTransaction.postings.map { it.amount.value })
            assertNull(node.error)
        }
    }

    @Test
    fun `다른 네 식별자와 자금 응답 손상 응답은 무시하고 실제 주문 결과를 기다린다`() {
        val config = config()
        // 총 8건의 300 USDT 주문이 모두 실제로 승인될 수 있도록 5,000 USDT를 준비한다.
        val invalidReplies =
            listOf(
                ReplyCase("사용자", "다른 응답") { results.encode(it.copy(userId = "OTHER")) },
                ReplyCase("요청", "다른 응답") { results.encode(it.copy(requestId = "OTHER")) },
                ReplyCase("마켓", "다른 응답") { results.encode(it.copy(marketId = "OTHER")) },
                ReplyCase("주문", "다른 응답") { results.encode(it.copy(orderId = "OTHER")) },
                ReplyCase("자금 준비 결과", "응답 복원 실패") {
                    DevelopmentBalanceResultCodec().encode(
                        DevelopmentBalanceResultPayload.Prepared(
                            OpeningBalanceResult(
                                OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(5_000_000_000)),
                                "OPENING-1",
                                time,
                                false,
                            ),
                        ),
                    )
                },
                ReplyCase("헤더 미만", "응답 복원 실패") { ByteArray(7) },
                ReplyCase("잘린 본문", "응답 복원 실패") { results.encode(it).let { bytes -> bytes.copyOf(bytes.size - 1) } },
                ReplyCase("미지원 거절 사유", "응답 복원 실패") {
                    protobufFrame(
                        3,
                        AdmissionRejected
                            .newBuilder()
                            .setReasonValue(255)
                            .setUserId(it.userId)
                            .setRequestId(it.requestId)
                            .setMarketId(it.marketId)
                            .setOrderId(it.orderId)
                            .build()
                            .toByteArray(),
                    )
                },
            )
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config, 5_000_000_000)
            DevelopmentOrderClient(config).use { client ->
                invalidReplies.forEachIndexed { index, invalid ->
                    val command = order("R-$index", "O-$index")
                    val expected = expected(command, index + 1L)
                    assertTrue(client.send(command), invalid.name)

                    // 서버가 이런 바이트를 보냈다는 주장이 아니다. 실제 연결의 수신 파서 경계만 통제한다.
                    deliver(client, invalid.bytes(expected))

                    assertTrue(client.diagnostic?.contains(invalid.diagnostic) == true, "${invalid.name}: ${client.diagnostic}")
                    val actual = assertIs<OrderRequestOutcome.Confirmed>(client.awaitResult())
                    assertEquals(expected, actual.result, invalid.name)
                }
            }
            val snapshot = assertNotNull(node.observation?.snapshot)
            assertEquals(
                2_600_000_000L,
                snapshot.balances
                    .single()
                    .available.value,
            )
            assertEquals(
                2_400_000_000L,
                snapshot.balances
                    .single()
                    .hold.value,
            )
            assertEquals(8L, snapshot.lastSequence)
            assertEquals(8, snapshot.pendingOrders.size)
            assertEquals(8, snapshot.requests.size)
            assertEquals(1, snapshot.preparations.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `전송 종단 세 반환값은 한 번만 전송하고 수용 전 결과 미확인으로 구분한다`() {
        // 이 사례는 publication 반환값을 통제한다. 실제 노드 접수·네트워크 장애 시험이 아니다.
        listOf(
            Triple(Publication.NOT_CONNECTED, "NOT_CONNECTED", "연결"),
            Triple(Publication.CLOSED, "CLOSED", "연결"),
            Triple(Publication.MAX_POSITION_EXCEEDED, "MAX_POSITION_EXCEEDED", "종단"),
        ).forEach { (offerResult, name, meaning) ->
            val connection = mock(AeronCluster::class.java)
            `when`(connection.context()).thenReturn(AeronCluster.Context())
            `when`(connection.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenReturn(offerResult)
            mockStatic(AeronCluster::class.java).use { factory ->
                factory.`when`<AeronCluster> { AeronCluster.connect(any(AeronCluster.Context::class.java)) }.thenReturn(connection)
                DevelopmentOrderClient(isolatedSettings).use { client ->
                    var transportAccepted = false
                    val outcome = assertIs<OrderRequestOutcome.Unconfirmed>(client.request(order()) { transportAccepted = true })
                    assertFalse(transportAccepted)
                    assertFalse(outcome.transportAccepted)
                    assertTrue(outcome.reason.contains(name), outcome.reason)
                    assertTrue(outcome.reason.contains(meaning), outcome.reason)
                    assertTrue(outcome.reason.contains("같은 사용자·요청 ID와 주문 원본"), outcome.reason)
                    verify(connection, times(1)).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
                    verify(connection, never()).pollEgress()
                }
            }
        }
    }

    @Test
    fun `conductor 오류는 수용 전후를 구분하고 원인을 담은 결과 미확인으로 끝난다`() {
        // 오류 통지 시점만 통제한다. 수용 true도 업무 승인을 뜻하지 않는다.
        listOf(false, true).forEach { afterTransportAccepted ->
            val connection = mock(AeronCluster::class.java)
            `when`(connection.context()).thenReturn(AeronCluster.Context())
            `when`(connection.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenReturn(123L)
            var context: AeronCluster.Context? = null
            mockStatic(AeronCluster::class.java).use { factory ->
                factory.`when`<AeronCluster> { AeronCluster.connect(any(AeronCluster.Context::class.java)) }.thenAnswer {
                    context = it.getArgument(0)
                    connection
                }
                DevelopmentOrderClient(isolatedSettings).use { client ->
                    if (afterTransportAccepted) assertTrue(client.send(order()))
                    assertNotNull(context).errorHandler().onError(AeronException("controlled-conductor"))

                    val outcome =
                        assertIs<OrderRequestOutcome.Unconfirmed>(
                            if (afterTransportAccepted) client.awaitResult() else client.request(order()),
                        )

                    assertEquals(afterTransportAccepted, outcome.transportAccepted)
                    assertTrue(outcome.reason.contains("결과 미확인"), outcome.reason)
                    assertTrue(outcome.reason.contains("AeronException"), outcome.reason)
                    assertTrue(outcome.reason.contains("controlled-conductor"), outcome.reason)
                    verify(connection, times(if (afterTransportAccepted) 1 else 0)).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
                    verify(connection, never()).pollEgress()
                }
            }
        }
    }

    @Test
    fun `offer가 던진 연결 예외도 승인으로 표시하지 않고 미확인 이유를 보존한다`() {
        val connection = mock(AeronCluster::class.java)
        `when`(connection.context()).thenReturn(AeronCluster.Context())
        `when`(connection.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenThrow(AeronException("controlled-offer"))
        mockStatic(AeronCluster::class.java).use { factory ->
            factory.`when`<AeronCluster> { AeronCluster.connect(any(AeronCluster.Context::class.java)) }.thenReturn(connection)
            DevelopmentOrderClient(isolatedSettings).use { client ->
                val outcome = assertIs<OrderRequestOutcome.Unconfirmed>(client.request(order()))
                assertFalse(outcome.transportAccepted)
                assertTrue(outcome.reason.contains("결과 미확인"), outcome.reason)
                assertTrue(outcome.reason.contains("controlled-offer"), outcome.reason)
                assertFailsWith<IllegalStateException> { client.awaitResult() }
                verify(connection, times(1)).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
                verify(connection, never()).pollEgress()
            }
        }
    }

    private data class ReplyCase(
        val name: String,
        val diagnostic: String,
        val bytes: (AdmissionResultPayload.Accepted) -> ByteArray,
    )

    private fun deliver(
        client: DevelopmentOrderClient,
        bytes: ByteArray,
    ) {
        val offset = 5
        val buffer = ByteArray(bytes.size + 11) { 0x5A }
        bytes.copyInto(buffer, destinationOffset = offset)
        client.connection
            .context()
            .egressListener()
            .onMessage(1, 2, UnsafeBuffer(buffer), offset, bytes.size, Header(0, 0))
    }

    private fun expected(
        command: AdmissionCommandPayload,
        sequence: Long,
    ) = AdmissionResultPayload.Accepted(
        command.order.userId.value,
        command.requestId,
        command.order.marketId.value,
        command.order.orderId.value,
        sequence,
    )

    private fun order(
        requestId: String = "R1",
        orderId: String = "O1",
    ): AdmissionCommandPayload =
        admissionCommand(requestId, orderId).let { it.copy(order = it.order.copy(userId = UserId("U1")), occurredAt = time) }

    private fun prepare(
        config: DevelopmentFundingSettings,
        amount: Long,
    ) {
        DevelopmentFundingClient(config).use { client ->
            val outcome =
                assertIs<FundingRequestOutcome.Confirmed>(
                    client.request(
                        DevelopmentBalanceCommandPayload(
                            OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(amount)),
                            "OPENING-1",
                            time,
                        ),
                    ),
                )
            assertEquals(
                amount,
                assertIs<DevelopmentBalanceResultPayload.Prepared>(outcome.result)
                    .result.opening.amount.value,
            )
        }
    }

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(Files.createTempDirectory("admission98-client-").resolve("run"), base)
            } catch (_: java.net.BindException) {
                // 같은 포트 묶음을 다른 프로세스가 사용하면 테스트용 묶음만 다시 고른다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트를 확보하지 못했습니다")
    }
}

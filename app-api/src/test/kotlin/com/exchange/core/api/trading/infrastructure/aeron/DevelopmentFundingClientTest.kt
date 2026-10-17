package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실물 노드 검사는 NodeTest가 맡고, 여기서는 클라이언트의 전송 반환값·표시 시점만 통제한다. */
class DevelopmentFundingClientTest {
    private val settings = DevelopmentFundingSettings(Path.of("/unused-funding-client-test"))
    private val command =
        DevelopmentBalanceCommandPayload(
            OpeningBalance("P1", UserId("A"), AssetId("USDT"), Amount(1_000_000_000)),
            "L1",
            Instant.parse("2026-10-17T00:00:00.123456789Z"),
        )

    @Test
    fun `연결 문제와 publication 종단은 원인을 구분하고 반복 전송하지 않는다`() {
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
                DevelopmentFundingClient(settings).use { client ->
                    val outcome = assertIs<FundingRequestOutcome.Unconfirmed>(client.request(command))
                    assertFalse(outcome.transportAccepted)
                    assertTrue(outcome.reason.contains(name), outcome.reason)
                    assertTrue(outcome.reason.contains(meaning), outcome.reason)
                    verify(connection, times(1)).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
                    verify(connection, never()).pollEgress()
                }
            }
        }
    }

    @Test
    fun `실제 main은 전송 수용과 결과 대기를 먼저 표시한 뒤 수신 결과를 표시한다`() {
        val connection = mock(AeronCluster::class.java)
        `when`(connection.context()).thenReturn(AeronCluster.Context())
        `when`(connection.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenReturn(123L)
        val output = ByteArrayOutputStream()
        var context: AeronCluster.Context? = null
        var polls = 0
        val bytes =
            DevelopmentBalanceResultCodec().encode(
                DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(command.opening, "L1", command.occurredAt, false)),
            )
        doAnswer {
            val beforeReply = output.toString(Charsets.UTF_8)
            assertTrue(beforeReply.contains("TRANSPORT_ACCEPTED"), beforeReply)
            assertTrue(beforeReply.contains("결과 대기"), beforeReply)
            assertFalse(beforeReply.contains("RESULT_RECEIVED"), beforeReply)
            polls++
            context!!.egressListener().onMessage(1, 2, UnsafeBuffer(bytes), 0, bytes.size, Header(0, 0))
            1
        }.`when`(connection).pollEgress()
        mockStatic(AeronCluster::class.java).use { factory ->
            factory.`when`<AeronCluster> { AeronCluster.connect(any(AeronCluster.Context::class.java)) }.thenAnswer {
                context = it.getArgument(0)
                connection
            }
            val original = System.out
            try {
                System.setOut(PrintStream(output, true, Charsets.UTF_8))
                Class
                    .forName("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingClientKt")
                    .getMethod("main", Array<String>::class.java)
                    .invoke(
                        null,
                        arrayOf(
                            settings.runRoot.toString(),
                            settings.basePort.toString(),
                            "P1",
                            "A",
                            "USDT",
                            "1000000000",
                            "L1",
                            command.occurredAt.toString(),
                        ),
                    )
            } finally {
                System.setOut(original)
            }
        }
        val text = output.toString(Charsets.UTF_8)
        assertEquals(1, polls)
        assertTrue(text.indexOf("TRANSPORT_ACCEPTED") < text.indexOf("RESULT_RECEIVED"), text)
        verify(connection).close()
    }
}

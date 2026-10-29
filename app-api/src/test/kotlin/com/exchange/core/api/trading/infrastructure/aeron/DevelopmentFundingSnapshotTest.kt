package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.history.RocksDbTradingHistoryStore
import com.exchange.core.api.trading.infrastructure.history.TradingHistoryFixture
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import io.aeron.ExclusivePublication
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DevelopmentFundingSnapshotTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `snapshot backpressure defers queued observation until exclusive history export ends`() {
        RocksDbTradingHistoryStore.create(directory.resolve("history")).use { history ->
            val fixture = TradingHistoryFixture(history).apply { completedTrade() }
            val failures = mutableListOf<Throwable>()
            val service =
                DevelopmentFundingAdapter(
                    fixture.state,
                    snapshots = TradingStateSnapshotPersistence(history, "a".repeat(64)),
                    onFatal = failures::add,
                )
            val cluster = mock(Cluster::class.java)
            val idle = mock(IdleStrategy::class.java)
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
            `when`(cluster.idleStrategy()).thenReturn(idle)
            service.onStart(cluster, null)
            val command =
                DevelopmentBalanceCommandPayload(
                    OpeningBalance("additional", UserId("additional"), AssetId("KRW"), Amount(100)),
                    "additional-ledger",
                    fixture.time,
                )
            val bytes = DevelopmentBalanceCommandCodec().encode(command)
            service.onSessionMessage(null, 0, clusterFrame(bytes), AeronCluster.SESSION_HEADER_LENGTH, bytes.size, Header(0, 0))
            lateinit var capture: CompletableFuture<FundingObservation?>
            Thread { capture = service.captureObservation() }.also {
                it.start()
                it.join()
            }
            assertFalse(capture.isDone)
            var offers = 0
            var idleCalls = 0
            val publication = mock(ExclusivePublication::class.java)
            doAnswer {
                offers++
                if (offers % 2 == 1) Publication.BACK_PRESSURED else 1L
            }.`when`(publication).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
            doAnswer {
                idleCalls++
                assertEquals(0, service.doBackgroundWork(0))
                assertFalse(capture.isDone)
                null
            }.`when`(idle).idle()

            service.onTakeSnapshot(publication)

            assertTrue(idleCalls > 1, "streaming must span multiple chunks")
            assertEquals(1, service.doBackgroundWork(0))
            val observed = assertNotNull(capture.join()?.snapshot)
            assertEquals(fixture.state.snapshot().balances, observed.balances)
            assertTrue(failures.isEmpty())
            assertTrue(service.started)
        }
    }
}

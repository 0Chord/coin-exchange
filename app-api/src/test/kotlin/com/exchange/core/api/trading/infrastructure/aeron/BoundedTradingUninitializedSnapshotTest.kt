package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.trading.TradingLifecycle
import io.aeron.Image
import io.aeron.cluster.ClusterControl
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 Archive에 초기화 전 Snapshot을 남기고 같은 기록에서 초기화 명령을 이어 처리한다. */
class BoundedTradingUninitializedSnapshotTest {
    @TempDir lateinit var directory: Path

    @Test
    @Timeout(90)
    fun `초기화 전 Snapshot만 복구하면 준비 상태를 유지하고 이후 초기화로 거래를 시작한다`() = recover(initializeInTail = false)

    @Test
    @Timeout(90)
    fun `초기화 전 Snapshot 이후 확정된 초기화와 자금 준비는 tail에서 복구한다`() = recover(initializeInTail = true)

    private fun recover(initializeInTail: Boolean) {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            assertFalse(node.tradingReady)
            takeSnapshot(node)
            assertFalse(node.tradingReady)
            if (initializeInTail) initializeAndFund(config, node)
        }
        val restored = AtomicBoolean()
        val recovery = config.copy(recoverExisting = true)
        DevelopmentFundingNode.start(recovery, snapshotWitness(restored)).use { node ->
            assertTrue(restored.get(), "최초 로그만 재생하지 않고 실제 Snapshot image를 복원해야 한다")
            assertEquals(initializeInTail, node.tradingReady)
            if (!initializeInTail) {
                BoundedAeronClient(config).use { client ->
                    assertEquals(TradingLifecycle.UNINITIALIZED.name, client.progress().lifecycle)
                }
                takeSnapshot(node)
                assertFalse(node.tradingReady)
                initializeAndFund(config, node)
            }
            assertFunded(node)
            takeSnapshot(node)
            assertNull(node.error)
        }
        restored.set(false)
        DevelopmentFundingNode.start(recovery, snapshotWitness(restored)).use { node ->
            assertTrue(restored.get())
            assertTrue(node.tradingReady)
            assertFunded(node)
            assertNull(node.error)
        }
    }

    private fun initializeAndFund(
        config: DevelopmentFundingSettings,
        node: DevelopmentFundingNode,
    ) {
        BoundedAeronClient(config).use { client ->
            assertEquals("INITIALIZED", client.initialize(requireNotNull(config.boundedManifest)).code)
            assertEquals(
                "PREPARED",
                client
                    .fund(
                        DevelopmentBalanceCommandPayload(
                            OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000)),
                            "L1",
                            Instant.EPOCH,
                        ),
                    ).code,
            )
        }
        assertTrue(node.tradingReady)
    }

    private fun assertFunded(node: DevelopmentFundingNode) {
        val recovered = node.captureRecoveryState()
        assertEquals(TradingLifecycle.RUNNING, recovered.lifecycle)
        assertEquals(Amount(1_000), recovered.balances.single().available)
        assertEquals(Amount.ZERO, recovered.balances.single().hold)
        assertEquals(1, recovered.preparations.size)
    }

    private fun snapshotWitness(restored: AtomicBoolean): (DevelopmentFundingAdapter) -> ClusteredService =
        { service ->
            object : ClusteredService by service {
                override fun onStart(
                    cluster: Cluster,
                    snapshotImage: Image?,
                ) {
                    restored.set(snapshotImage != null)
                    service.onStart(cluster, snapshotImage)
                }

                override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
            }
        }

    private fun takeSnapshot(node: DevelopmentFundingNode) {
        val field = DevelopmentFundingNode::class.java.getDeclaredField("consensus").apply { isAccessible = true }
        val context = (field.get(node) as ConsensusModule).context()
        val before = context.snapshotCounter().get()
        assertTrue(node.requestSnapshot())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (context.snapshotCounter().get() <= before ||
            ClusterControl.ToggleState.get(context.controlToggleCounter()) != ClusterControl.ToggleState.NEUTRAL
        ) {
            assertNull(node.error)
            assertFalse(node.isClosed)
            check(System.nanoTime() - deadline < 0) { "Snapshot 완료를 기다리는 기한을 초과했습니다" }
            Thread.sleep(1)
        }
    }

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(
                    directory.resolve("run"),
                    base,
                    startupTimeoutMs = 30_000,
                    boundedManifest = haBoundedProbeManifest(),
                    trustedBoundedControl = true,
                )
            } catch (_: java.net.BindException) {
                // 동시에 실행하는 다른 테스트가 쓰는 포트 묶음은 건너뛴다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("Snapshot 복구 검사에 필요한 포트 묶음을 확보하지 못했습니다")
    }
}

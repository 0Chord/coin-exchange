package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.Side
import com.exchange.core.trading.TradingState
import io.aeron.Aeron
import io.aeron.Image
import io.aeron.cluster.ClusterControl
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 작은 실제 UDP 실행을 닫고 같은 Archive와 클러스터 기록으로 다시 기동한다. */
class DevelopmentFundingRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `Archive 전체 재생은 최초 결과와 주문장 순서를 복구하고 실행을 중복하지 않는다`() = recoverAndContinue(snapshotFirst = false)

    @Test
    fun `Snapshot과 이후 Archive 재생은 최초 결과와 주문장 순서를 함께 복구한다`() = recoverAndContinue(snapshotFirst = true)

    @Test
    fun `마지막 Snapshot 뒤 업무 입력이 없어도 복구 직후 전체 상태를 조회한다`() = recoverAndContinue(snapshotFirst = false, snapshotAtEnd = true)

    @Test
    fun `휘발 Driver 소실과 경로 변경 뒤에도 Snapshot과 Archive로 자금과 maker FIFO를 복구한다`() =
        recoverAndContinue(snapshotFirst = true, volatileDriver = true)

    private fun recoverAndContinue(
        snapshotFirst: Boolean,
        snapshotAtEnd: Boolean = false,
        volatileDriver: Boolean = false,
    ) {
        val config =
            config().let {
                if (volatileDriver) it.copy(driverRoot = Files.createDirectory(directory.resolve("first-volatile-driver"))) else it
            }
        val first = order("R1", "O1")
        val second = order("R2", "O2")
        val rejected = order("REJECT", "REJECTED", "empty-user")
        val mismatch = order("MISMATCH", "MISMATCHED").let { it.copy(feePolicySnapshot = it.feePolicySnapshot.copy(scheduleVersion = 99)) }
        lateinit var expected: List<Any?>
        lateinit var rejectedResult: AdmissionResultPayload
        lateinit var mismatchResult: AdmissionResultPayload
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config, "BUYER", "buyer", "USDT", 1_000_000_000)
            prepare(config, "SELLER", "seller", "BTC", 500_000_000)
            rejectedResult = request(config, rejected)
            assertEquals(
                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                assertIs<AdmissionResultPayload.Rejected>(rejectedResult).reason,
            )
            mismatchResult = request(config, mismatch)
            assertEquals(
                AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH,
                assertIs<AdmissionResultPayload.Rejected>(mismatchResult).reason,
            )
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(request(config, first)).sequence)
            snapshot(node, completed = 1)
            if (snapshotFirst) takeSnapshot(node)
            assertEquals(2L, assertIs<AdmissionResultPayload.Accepted>(request(config, second)).sequence)
            expected = stateValues(snapshot(node, completed = 2))
            if (snapshotAtEnd) takeSnapshot(node)
            assertNull(node.error)
        }

        if (volatileDriver) {
            assertFalse(Files.exists(Path.of(config.driverDirectory)), "정상 종료는 이 노드의 휘발성 Driver 파일만 회수한다")
            assertTrue(Files.isRegularFile(config.archiveDirectory.resolve("archive.catalog")))
            assertTrue(Files.isRegularFile(config.clusterDirectory.resolve("recording.log")))
            Files.delete(requireNotNull(config.driverRoot))
        }
        val recoveredConfig =
            config.copy(
                recoverExisting = true,
                driverRoot =
                    if (volatileDriver) {
                        Files.createDirectory(
                            directory.resolve("replacement-volatile-driver"),
                        )
                    } else {
                        config.driverRoot
                    },
            )

        // 정책 불일치는 기록을 열어 재생하기 전에 거절한다. 실패 뒤 올바른 설정은 같은 기록으로 복구된다.
        val changed = config.markets.single().let { it.copy(feePolicySnapshot = it.feePolicySnapshot.copy(scheduleVersion = 2)) }
        assertFailsWith<IllegalArgumentException> {
            DevelopmentFundingNode.start(recoveredConfig.copy(markets = DevelopmentMarketRegistry(listOf(changed))))
        }
        val replayed = ConcurrentLinkedQueue<FundingObservation>()
        val restoredSnapshot = AtomicBoolean()
        DevelopmentFundingNode
            .start(recoveredConfig) { service ->
                object : ClusteredService by service {
                    override fun onStart(
                        cluster: Cluster,
                        snapshotImage: Image?,
                    ) {
                        restoredSnapshot.set(snapshotImage != null)
                        service.onStart(cluster, snapshotImage)
                    }

                    override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)

                    override fun onSessionMessage(
                        session: ClientSession?,
                        timestamp: Long,
                        buffer: DirectBuffer,
                        offset: Int,
                        length: Int,
                        header: Header,
                    ) {
                        service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                        service.observation?.takeIf { it.appliedRole == Cluster.Role.FOLLOWER.name }?.let(replayed::add)
                    }
                }
            }.use { node ->
                assertEquals(snapshotFirst || snapshotAtEnd, restoredSnapshot.get())
                assertEquals(expected, stateValues(snapshot(node, completed = 2)))
                if (snapshotAtEnd) {
                    assertTrue(replayed.isEmpty(), "마지막 Snapshot 뒤 업무 입력 없이도 복구 상태를 조회해야 한다")
                } else {
                    assertTrue(replayed.isNotEmpty(), "실제 Archive의 확정 callback을 재생해야 한다")
                }
                assertTrue(replayed.all { it.replyAttempts == 0 && it.replyStatus != "OFFERED" }, "과거 요청에는 응답하지 않는다")
                assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(request(recoveredConfig, first)).sequence)
                assertEquals(rejectedResult, request(recoveredConfig, rejected))
                assertEquals(mismatchResult, request(recoveredConfig, mismatch))
                assertEquals(expected, stateValues(snapshot(node, completed = 2)))

                // 복구한 주문장의 FIFO를 다음 실제 체결로 검증한다. O1 전량 뒤 O2 일부가 체결된다.
                val taker =
                    order(
                        "R3",
                        "O3",
                        "seller",
                    ).let { it.copy(order = it.order.copy(side = Side.SELL, quantity = Quantity(400_000_000))) }
                assertEquals(3L, assertIs<AdmissionResultPayload.Accepted>(request(recoveredConfig, taker)).sequence)
                val completed = snapshot(node, completed = 3)
                val trades =
                    completed.completions
                        .last()
                        .matchingResult.events
                        .filterIsInstance<TradeExecuted>()
                assertEquals(listOf("O1", "O2"), trades.map { it.makerOrderId.value })
                assertEquals(listOf(300_000_000L, 100_000_000L), trades.map { it.quantity.value })
                assertEquals(3L, completed.lastAdmissionSequence)
                assertNull(node.error)
            }
    }

    @Test
    fun `확정 승인 뒤 내부 제출 전에 중단되면 Archive 재생이 대기 실행을 다시 생성한다`() {
        val config = config().copy(resultTimeoutMs = 300)
        val interruptSubmission = AtomicBoolean()
        val interruptedState = AtomicReference<TradingState.Snapshot?>()
        val command = order("PENDING", "PENDING-ORDER")
        val node =
            DevelopmentFundingNode.start(config) { service ->
                object : ClusteredService by service {
                    override fun onStart(
                        cluster: Cluster,
                        snapshotImage: Image?,
                    ) {
                        val interruptedCluster =
                            object : Cluster by cluster {
                                override fun tryClaim(
                                    length: Int,
                                    claim: BufferClaim,
                                ): Long {
                                    if (interruptSubmission.compareAndSet(true, false)) {
                                        interruptedState.set(service.captureSnapshotOnServiceThread())
                                        error("test interruption after committed admission before execution submission")
                                    }
                                    return cluster.tryClaim(length, claim)
                                }
                            }
                        service.onStart(interruptedCluster, snapshotImage)
                    }

                    override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
                }
            }
        node.use {
            prepare(config, "PENDING-FUNDS", "buyer", "USDT", 1_000_000_000)
            DevelopmentOrderClient(config).use { client ->
                interruptSubmission.set(true)
                assertIs<OrderRequestOutcome.Unconfirmed>(client.request(command))
            }
            assertTrue(node.awaitClosed(10_000))
            assertNotNull(node.error)
        }
        val pending = assertNotNull(interruptedState.get())
        assertEquals(listOf(1L), pending.pendingOrders.map { it.sequence })
        assertTrue(pending.completions.isEmpty())

        val recoveredConfig = config.copy(recoverExisting = true, resultTimeoutMs = 5_000)
        DevelopmentFundingNode.start(recoveredConfig).use { recovered ->
            val after = snapshot(recovered, completed = 1)
            assertTrue(after.pendingOrders.isEmpty())
            assertEquals(1, after.completions.size)
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(request(recoveredConfig, command)).sequence)
            assertEquals(stateValues(after), stateValues(snapshot(recovered, completed = 1)))
            assertNull(recovered.error)
        }
    }

    private fun order(
        requestId: String,
        orderId: String,
        user: String = "buyer",
    ): AdmissionCommandPayload = admissionCommand(requestId, orderId).let { it.copy(order = it.order.copy(userId = UserId(user))) }

    private fun prepare(
        config: DevelopmentFundingSettings,
        id: String,
        user: String,
        asset: String,
        amount: Long,
    ) {
        DevelopmentFundingClient(config).use { client ->
            val command =
                DevelopmentBalanceCommandPayload(
                    OpeningBalance(id, UserId(user), AssetId(asset), Amount(amount)),
                    "opening-$id",
                    Instant.parse("2026-10-20T00:00:00.123456789Z"),
                )
            val outcome = assertIs<FundingRequestOutcome.Confirmed>(client.request(command))
            assertFalse(assertIs<DevelopmentBalanceResultPayload.Prepared>(outcome.result).result.alreadyPrepared)
        }
    }

    private fun request(
        config: DevelopmentFundingSettings,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload = DevelopmentOrderClient(config).use { assertIs<OrderRequestOutcome.Confirmed>(it.request(command)).result }

    private fun snapshot(
        node: DevelopmentFundingNode,
        completed: Int,
    ): TradingState.Snapshot {
        var observed: TradingState.Snapshot? = null
        eventually {
            assertNull(node.error)
            observed = node.captureObservation()?.snapshot
            observed?.let { it.pendingOrders.isEmpty() && it.completions.size == completed } == true
        }
        return assertNotNull(observed)
    }

    private fun stateValues(snapshot: TradingState.Snapshot): List<Any?> =
        listOf(
            fundingAppliedFields(
                DevelopmentFundingSettings(directory.resolve("evidence-only")),
                FundingObservation(0, snapshot, null),
            )["state"],
            snapshot.reservations.map(::verificationReservation),
            snapshot.completions.map(::verificationCompletionFields),
        )

    private fun takeSnapshot(node: DevelopmentFundingNode) {
        val field = DevelopmentFundingNode::class.java.getDeclaredField("consensus").apply { isAccessible = true }
        val context = (field.get(node) as ConsensusModule).context()
        val previous = context.snapshotCounter().get()
        Aeron.connect(Aeron.Context().aeronDirectoryName(node.config.driverDirectory)).use { aeron ->
            val toggle = assertNotNull(ClusterControl.findControlToggle(aeron.countersReader(), node.config.clusterId))
            eventually { ClusterControl.ToggleState.SNAPSHOT.toggle(toggle) }
            eventually {
                assertNull(node.error)
                context.snapshotCounter().get() > previous
            }
        }
    }

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(directory.resolve("run"), base, startupTimeoutMs = 30_000)
            } catch (_: java.net.BindException) {
                // 다른 테스트와 포트가 겹치면 다음 묶음을 확인한다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("복구 테스트용 포트를 확보하지 못했습니다")
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            assertTrue(System.nanoTime() < deadline, "실제 복구 상태 관찰 기한을 초과했습니다")
            Thread.sleep(1)
        }
    }
}

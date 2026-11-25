package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.InitializationReceipt
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingBalanceKey
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingInboxChannelSnapshot
import com.exchange.core.trading.TradingInboxSnapshot
import com.exchange.core.trading.TradingIncident
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingLiveOrderSnapshot
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingMarketProgressSnapshot
import com.exchange.core.trading.TradingRecoveryState
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.FrameDescriptor
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedTradingSnapshotPersistenceTest {
    private val workload = TradingEngineProbeWorkload
    private val market = workload.markets.first()
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(1, 158),
            "snapshot-test-v1",
            "0".repeat(64),
            "1".repeat(64),
            1,
            listOf(TradingMarketContext(market, workload.policy, 0)),
            TradingResourceLimits(),
        )
    private val persistence = BoundedTradingSnapshotPersistence(manifest)
    private val codec = BoundedTradingStateCodec()
    private val position = 1_024L
    private val token = RequestToken(workload.buyer, 0, 1, 1)
    private val intent =
        OrderIntent(market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, workload.price, workload.quantity(market))

    @Test
    fun `초기화 전 스냅샷은 실제 소유자를 초기화하지 않고 스트림 복원 뒤에도 초기화를 기다린다`() {
        val original = state()
        val before = original.progress()

        val bytes = save(original)

        assertEquals(before, original.progress())
        assertFalse(original.isBlocked)
        val decoded = codec.read(ByteArrayInputStream(bytes), manifest, position)
        assertEquals(TradingLifecycle.UNINITIALIZED, decoded.lifecycle)
        assertContentEquals(encode(emptyRecovery()), bytes)
        val restored = state()
        assertEquals(0L, persistence.restore(restored, image(bytes), cluster()))
        assertEquals(before, restored.progress())
        assertFalse(restored.isBlocked)
        assertFailsWith<IllegalStateException> { restored.recoveryState() }
        assertEquals(InitializationReceipt.INITIALIZED, restored.initialize(manifest))
        assertEquals(TradingLifecycle.RUNNING, restored.lifecycle)
    }

    @Test
    fun `초기화 전 상태에 금융 정보나 순번이 섞인 스냅샷은 버리지 않고 거절한다`() {
        val running = fundedState().recoveryState()
        val invalid =
            mutableListOf(
                "잔액" to emptyRecovery(balances = listOf(Balance(workload.buyer, workload.quote, Amount.ZERO, Amount.ZERO))),
                "수수료" to emptyRecovery(fees = mapOf(workload.quote to BigInteger.ZERO)),
                "사용 잔액 키" to emptyRecovery(used = listOf(TradingBalanceKey(workload.buyer, workload.quote))),
                "자금 준비" to emptyRecovery(preparations = running.preparations),
                "계정" to emptyRecovery(accounts = listOf(workload.buyer)),
                "활성 주문" to emptyRecovery(liveOrders = running.liveOrders),
                "시장 진행" to emptyRecovery(markets = listOf(TradingMarketProgressSnapshot(market.marketId, 0, 0, emptyList()))),
                "매칭" to emptyRecovery(matching = MatchingCheckpoint(listOf(MatchingCheckpoint.Market(market.marketId, 0, emptyList())))),
                "요청 채널" to
                    emptyRecovery(
                        inbox = TradingInboxSnapshot(listOf(TradingInboxChannelSnapshot(workload.buyer, 0, 1, 0, 0, emptyList()))),
                    ),
                "사고" to emptyRecovery(incident = TradingIncident(0, "corrupted")),
            )
        for (value in listOf(-1L, 1L)) {
            invalid += "접수 순번 $value" to emptyRecovery(lastAdmission = value)
            invalid += "준비 순번 $value" to emptyRecovery(lastPreparation = value)
            invalid += "준비 바이트 $value" to emptyRecovery(preparationBytes = value)
        }
        for ((field, candidate) in invalid) {
            val restored = state()
            assertFailsWith<IllegalArgumentException>(field) {
                // 체크섬까지 정상인 바이트를 보내 생명주기와 내용의 조합을 검사한다.
                persistence.restore(restored, image(encode(candidate)), cluster())
            }
            assertEquals(TradingLifecycle.UNINITIALIZED, restored.lifecycle, field)
        }
    }

    @Test
    fun `실행 중 스냅샷은 예약과 요청 영수증을 기존 도메인 복구로 되살린다`() {
        val original = fundedState()
        val receipt = assertIs<AdmissionReply.Recorded>(original.admit(token, intent, workload.time)).receipt
        val bytes = save(original)
        val restored = state()

        persistence.restore(restored, image(bytes), cluster())

        assertEquals(TradingLifecycle.RUNNING, restored.lifecycle)
        assertEquals(Amount(1_000_000 - workload.RESERVE_PER_BUY), restored.currentBalance(workload.buyer, workload.quote).available)
        assertEquals(Amount(workload.RESERVE_PER_BUY), restored.currentBalance(workload.buyer, workload.quote).hold)
        val retry = assertIs<AdmissionReply.Recorded>(restored.admit(token, intent, workload.time))
        assertEquals(receipt, retry.receipt)
        assertFalse(retry.newlyRecorded)
        assertContentEquals(bytes, save(restored))
    }

    @Test
    fun `격리된 스냅샷은 사고와 격리 생명주기를 유지한다`() {
        val original = fundedState()
        val incident = TradingIncident(512, "invariant")
        original.quarantine(incident.position, incident.reason)
        val bytes = save(original)
        val restored = state()

        persistence.restore(restored, image(bytes), cluster())

        assertEquals(TradingLifecycle.QUARANTINED, restored.lifecycle)
        assertEquals(incident, restored.recoveryState().incident)
        assertContentEquals(bytes, save(restored))
    }

    @Test
    fun `초기화 전 스냅샷 저장과 복원도 bounded 소유자와 비차단 상태를 요구한다`() {
        val legacy = state(bounded = false)
        val blocked = state()
        assertFailsWith<IllegalArgumentException> { blocked.restoreRecoveryState(emptyRecovery()) }
        assertTrue(blocked.isBlocked)
        for (invalidOwner in listOf(legacy, blocked)) {
            assertFailsWith<IllegalStateException> { save(invalidOwner) }
            assertFailsWith<IllegalStateException> {
                persistence.restore(invalidOwner, image(encode(emptyRecovery())), cluster())
            }
        }
        val initialized = state().also { it.initialize(manifest) }
        assertFailsWith<IllegalStateException> {
            persistence.restore(initialized, image(encode(emptyRecovery())), cluster())
        }
        assertEquals(TradingLifecycle.RUNNING, initialized.lifecycle)
    }

    private fun fundedState(): TradingState =
        state().also {
            it.initialize(manifest)
            it.prepareDevelopmentBalance(
                OpeningBalance("buyer", workload.buyer, workload.quote, Amount(1_000_000)),
                "ignored",
                workload.time,
            )
            it.rotateEpoch(workload.buyer, 0, 0)
            it.admit(token, intent, workload.time)
        }

    private fun state(bounded: Boolean = true): TradingState {
        val calculators = workload.calculators()
        return TradingState(calculators.first, calculators.second, boundedMode = bounded)
    }

    private fun cluster(): Cluster = mock(Cluster::class.java).also { `when`(it.logPosition()).thenReturn(position) }

    private fun save(state: TradingState): ByteArray {
        val bytes = ByteArrayOutputStream()
        val publication = mock(ExclusivePublication::class.java)
        doAnswer { invocation ->
            val chunk = ByteArray(invocation.getArgument<Int>(2))
            invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument<Int>(1), chunk)
            bytes.write(chunk)
            1L
        }.`when`(publication).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        persistence.save(state, publication, cluster(), 0)
        return bytes.toByteArray()
    }

    private fun image(bytes: ByteArray): Image {
        val image = mock(Image::class.java)
        val header = mock(Header::class.java)
        `when`(header.flags()).thenReturn(FrameDescriptor.UNFRAGMENTED)
        var offset = 0
        doAnswer { invocation ->
            if (offset == bytes.size) {
                0
            } else {
                val length = minOf(4_096, bytes.size - offset)
                invocation.getArgument<FragmentHandler>(0).onFragment(UnsafeBuffer(bytes), offset, length, header)
                offset += length
                1
            }
        }.`when`(image).poll(any(FragmentHandler::class.java), anyInt())
        `when`(image.isEndOfStream).thenAnswer { offset == bytes.size }
        return image
    }

    private fun encode(candidate: TradingRecoveryState): ByteArray =
        ByteArrayOutputStream().also { codec.write(candidate, position, it) }.toByteArray()

    private fun emptyRecovery(
        balances: List<Balance> = emptyList(),
        fees: Map<AssetId, BigInteger> = emptyMap(),
        used: List<TradingBalanceKey> = emptyList(),
        preparations: List<OpeningBalanceResult> = emptyList(),
        accounts: List<UserId> = emptyList(),
        liveOrders: List<TradingLiveOrderSnapshot> = emptyList(),
        markets: List<TradingMarketProgressSnapshot> = emptyList(),
        matching: MatchingCheckpoint = MatchingCheckpoint(emptyList()),
        inbox: TradingInboxSnapshot = TradingInboxSnapshot(emptyList()),
        lastAdmission: Long = 0,
        lastPreparation: Long = 0,
        preparationBytes: Long = 0,
        incident: TradingIncident? = null,
    ) = TradingRecoveryState(
        manifest,
        TradingLifecycle.UNINITIALIZED,
        balances,
        fees,
        used,
        preparations,
        accounts,
        liveOrders,
        markets,
        matching,
        inbox,
        lastAdmission,
        lastPreparation,
        preparationBytes,
        incident,
    )
}

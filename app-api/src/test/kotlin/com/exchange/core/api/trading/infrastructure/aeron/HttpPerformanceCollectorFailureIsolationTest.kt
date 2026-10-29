package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.api.trading.infrastructure.history.TradingHistoryFixture
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionState
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.concurrent.SystemNanoClock
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.rocksdb.RocksDB
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 decorator를 통과한 업무 실행과 관찰 전용 RocksDB 오류의 경계를 검사한다. */
class HttpPerformanceCollectorFailureIsolationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `손상된 완료 인덱스 조회는 상세 계측 여부와 관계없이 업무 callback을 중단하지 않는다`() {
        for (detailed in listOf(false, true)) {
            executeThroughDecorator("corrupt-$detailed", detailed, corruptIndex = true)
        }
    }

    @Test
    fun `정상 완료 인덱스는 실제 업무 완료 표식을 한 번만 보낸다`() {
        executeThroughDecorator("healthy", detailed = false, corruptIndex = false)
    }

    @Test
    fun `보관량 진단 오류는 background 업무 결과와 상태를 유지하고 보고서를 무효로 한다`() {
        val fixture = TradingHistoryFixture()
        fixture.funding("seller", "BTC", 100)
        val state = spy(fixture.state)
        val before = state.snapshot()
        val fatalErrors = mutableListOf<Throwable>()
        val service = DevelopmentFundingAdapter(state, onFatal = fatalErrors::add)
        collector("retention", detailed = false) {}.use { collector ->
            val decorated = collector.decorate(service)
            decorated.onStart(cluster(), null)
            doThrow(IllegalStateException("retention observer failed")).`when`(state).historyRetention()

            assertEquals(0, decorated.doBackgroundWork(TimeUnit.SECONDS.toNanos(1)))
            assertEquals(0, decorated.doBackgroundWork(TimeUnit.SECONDS.toNanos(1) + 1))

            val failure = assertNotNull(collector.error)
            assertTrue(failure.contains("업무 이력 보관량 수집 실패"))
            assertTrue(failure.contains("retention observer failed"))
            assertFalse(state.isBlocked)
            assertEquals(before, state.snapshot())
            assertTrue(fatalErrors.isEmpty())
            verify(state, times(1)).historyRetention()
        }
    }

    private fun executeThroughDecorator(
        name: String,
        detailed: Boolean,
        corruptIndex: Boolean,
    ) {
        val fixture = TradingHistoryFixture()
        fixture.funding("seller", "BTC", 100)
        val admission = assertIs<OrderAdmissionState.Result.Accepted>(fixture.admit("sell", "seller", Side.SELL, 1))
        val sequence = admission.record.sequence
        val markers = mutableListOf<HttpPerformanceCompletion>()
        val fatalErrors = mutableListOf<Throwable>()
        val service =
            DevelopmentFundingAdapter(
                fixture.state,
                markets = DevelopmentMarketRegistry(listOf(DevelopmentMarketContext(fixture.market, fixture.policy))),
                onFatal = fatalErrors::add,
            )
        collector(name, detailed, markers::add).use { collector ->
            val decorated = collector.decorate(service)
            decorated.onStart(cluster(), null)
            collector.observe(
                FundingObservation(
                    64,
                    null,
                    null,
                    admissionResult = AdmissionResultPayload.Accepted("seller", "request-sell", "BTC-KRW", "sell", sequence),
                    appliedRole = "FOLLOWER",
                ),
            )
            val history = field<HttpPerformanceCollectorHistory>(collector, "history")
            val index = field<HttpPerformanceCollectorDiskIndex>(history, "diskIndex")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (index.offset(sequence) == -1L && System.nanoTime() < deadline) {
                collector.drainIdentityHistory()
                Thread.sleep(1)
            }
            assertTrue(index.offset(sequence) >= 0, "정상 Accepted 원문 저장 확인이 선행되어야 한다")
            assertNull(collector.error)
            if (corruptIndex) {
                val key = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array()
                val database = field<RocksDB>(index, "db")
                database.put(key, byteArrayOf(1))
                field<MutableMap<*, *>>(index, "recent").clear()
                assertContentEquals(byteArrayOf(1), database.get(key))
            }

            // 여기서 관찰 예외가 escape하면 실제 decorated callback 호출 자체가 테스트를 실패시킨다.
            execute(decorated, sequence, fixture.time.toEpochMilli())

            assertEquals(sequence, assertNotNull(service.observation).executionSequence)
            assertFalse(fixture.state.isBlocked)
            assertEquals(
                1,
                fixture.state
                    .snapshot()
                    .completions.size,
            )
            assertTrue(fatalErrors.isEmpty())
            val completed = fixture.state.snapshot()
            execute(decorated, sequence, fixture.time.toEpochMilli())
            assertEquals(completed, fixture.state.snapshot(), "관찰 실패 뒤 중복 실행도 업무 상태를 바꾸지 않는다")
            assertFalse(fixture.state.isBlocked)
            assertTrue(fatalErrors.isEmpty())
            if (corruptIndex) {
                assertTrue(assertNotNull(collector.error).contains("완료 표식 인덱스 길이 오류"))
                assertTrue(markers.isEmpty(), "손상된 관찰에서 정상 완료 표식을 만들지 않는다")
            } else {
                assertNull(collector.error)
                assertEquals(sequence, markers.single().admissionSequence)
            }
        }
    }

    private fun collector(
        name: String,
        detailed: Boolean,
        send: (HttpPerformanceCompletion) -> Unit,
    ) = HttpPerformanceCollector(
        "observer-isolation",
        0,
        send,
        SystemNanoClock.INSTANCE,
        HTTP_PERFORMANCE_CALLBACK_CAPACITY,
        detailedTracing = detailed,
        identityEvidencePath = directory.resolve("$name.bin"),
    )

    private fun cluster(): Cluster =
        mock(Cluster::class.java).also {
            `when`(it.role()).thenReturn(Cluster.Role.FOLLOWER)
            `when`(it.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(it.logPosition()).thenReturn(128L)
        }

    private fun execute(
        service: ClusteredService,
        sequence: Long,
        timestamp: Long,
    ) {
        val command = ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(sequence))
        service.onSessionMessage(
            null,
            timestamp,
            clusterFrame(command, Long.MIN_VALUE),
            AeronCluster.SESSION_HEADER_LENGTH,
            command.size,
            Header(0, 0),
        )
    }

    private inline fun <reified T> field(
        owner: Any,
        name: String,
    ): T =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner) as T
}

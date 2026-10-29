package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.TradingExecutionObserver
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.core.instrument.distribution.pause.PauseDetector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.NanoClock
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 제품 callback을 위임한다. 제어한 노드 시계는 구간 경계 검사용이며 실측 성능 근거가 아니다. */
class HttpPerformanceCallbackTimingsTest {
    private val callbackTimingMapper = jacksonObjectMapper()
    private val now = Instant.parse("2026-10-26T00:00:00Z")
    private val context = DevelopmentMarketContext.BTC_USDT

    @Test
    fun `명령은 전체 snapshot을 복사하지 않고 체크포인트에서 한 번만 캡처한다`() {
        val f = Fixture()
        f.fund("SELLER", "BTC", 100_000_000)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("S-1", "SELLER", Side.SELL)
        f.execute(1)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(2)

        verify(f.state, never()).snapshot()
        assertEquals(-974L, f.nodeClock(), "일반 callback은 완료 observer 두 번의 26ns만 사용한다")
        val checkpoint = f.checkpoint()
        verify(f.state, times(1)).snapshot()
        assertEquals(-934L, f.nodeClock(), "체크포인트 한 번에서만 snapshot의 40ns를 사용한다")
        val raw = raw(checkpoint)

        assertEquals(listOf("FUNDING", "FUNDING", "ADMISSION", "EXECUTION", "ADMISSION", "EXECUTION"), raw.map { it.kind })
        assertEquals(listOf(128L, 256L, 384L, 512L, 640L, 768L), raw.map { it.position })
        assertTrue(raw.all { it.productCallbackNs == 0L }, "제어 시계에서 비용을 부여한 전체 snapshot이 일반 callback에 없다")
        assertEquals(listOf(0L, 0L, 0L, 13L, 0L, 13L), raw.map { it.observerNs }, "완료 marker observer의 13ns는 제품 비용에 더하지 않는다")
        assertEquals(listOf("S-1", "M-1"), raw.filter { it.kind == "EXECUTION" }.map { it.requestId })
        assertEquals(listOf(1L, 2L), raw.filter { it.kind == "EXECUTION" }.map { it.admissionSequence })
        assertEquals(2, summary(checkpoint, "all", "EXECUTION", "productCallback").count)
        assertEquals(0L, summary(checkpoint, "all", "EXECUTION", "productCallback").totalNs)
        assertEquals(26L, summary(checkpoint, "all", "EXECUTION", "observer").totalNs)
        assertEquals(1, summary(checkpoint, "measuredBuy", "ADMISSION", "productCallback").count)
        assertEquals(1, summary(checkpoint, "measuredBuy", "EXECUTION", "productCallback").count)
        assertEquals(0, summary(checkpoint, "measuredBuy", "FUNDING", "productCallback").count)
        assertEquals(listOf(0L, 1L), f.markers.map { it.tradeCount })
        assertEquals(listOf(0L, 1L), f.markers.map { it.settlementCount })
        val balances =
            f.state.snapshot().balances.associate {
                (it.userId.value to it.assetId.value) to (it.available.value to it.hold.value)
            }
        assertEquals(900L to 0L, balances["BUYER" to "USDT"])
        assertEquals(100_000_000L to 0L, balances["BUYER" to "BTC"])
        assertEquals(100L to 0L, balances["SELLER" to "USDT"])
        assertNull(f.collector.error)
    }

    @Test
    fun `거절 callback도 접수 비용에 남기고 빈 실행 요약을 0ns 표본으로 채우지 않는다`() {
        val f = Fixture()
        f.fund("BUYER", "USDT", 99)

        assertIs<AdmissionResultPayload.Rejected>(f.admit("M-POOR", "BUYER", Side.BUY))

        val checkpoint = f.checkpoint()
        assertEquals(listOf("FUNDING", "ADMISSION"), raw(checkpoint).map { it.kind })
        assertEquals("M-POOR", raw(checkpoint).last().requestId)
        assertNull(raw(checkpoint).last().admissionSequence)
        assertEquals(1, summary(checkpoint, "measuredBuy", "ADMISSION", "productCallback").count)
        val execution = summary(checkpoint, "measuredBuy", "EXECUTION", "productCallback")
        assertEquals(0, execution.count)
        assertEquals(0L, execution.totalNs)
        assertNull(execution.p50Ns)
        assertNull(execution.p95Ns)
        assertNull(execution.p99Ns)
        assertNull(execution.maxNs)
        assertTrue(f.markers.isEmpty())
        val snapshot = f.state.snapshot()
        assertEquals(1, snapshot.requests.size)
        assertEquals(1, snapshot.ledgerTransactions.size)
        assertTrue(snapshot.reservations.isEmpty() && snapshot.completions.isEmpty() && snapshot.pendingOrders.isEmpty())
        assertNull(f.collector.error)
    }

    @Test
    fun `재전송 비용은 각 callback 원본에 남지만 고유 업무 완료와 표식은 늘리지 않는다`() {
        val f = Fixture()
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)
        val original = f.state.snapshot()

        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)

        val checkpoint = f.checkpoint()
        assertEquals(original, f.state.snapshot())
        assertEquals(1, f.markers.size)
        val measured = raw(checkpoint).filter { it.requestId == "M-1" }
        assertEquals(listOf("ADMISSION", "EXECUTION", "ADMISSION", "EXECUTION"), measured.map { it.kind })
        assertTrue(measured.all { it.admissionSequence == 1L })
        assertEquals(listOf(0L, 13L, 0L, 0L), measured.map { it.observerNs })
        assertEquals(2, summary(checkpoint, "measuredBuy", "EXECUTION", "productCallback").count)
        assertNull(f.collector.error)
    }

    @Test
    fun `백분위는 callback 횟수의 최근접 순위이며 warmup 접수는 측정 BUY에서 제외한다`() {
        val f = Fixture()
        f.admissionCostNs = 1_000
        f.admit("W-0", "BUYER", Side.BUY)
        for (i in 1..100) {
            f.admissionCostNs = i.toLong()
            f.admit("M-$i", "BUYER", Side.BUY)
        }

        val checkpoint = f.checkpoint()
        assertEquals(101, raw(checkpoint).size)
        assertEquals(101, summary(checkpoint, "all", "ADMISSION", "productCallback").count)
        assertEquals(
            HttpPerformanceCallbackDurationSummary(100, 5_050, 50, 95, 99, 100),
            summary(checkpoint, "measuredBuy", "ADMISSION", "productCallback"),
        )
        assertTrue(f.markers.isEmpty())
        assertNull(f.collector.error)
    }

    @Test
    fun `작은 raw 버퍼를 넘기면 보고서 오류를 고정하고 현재 업무와 다음 재전송은 유지한다`() {
        val f = Fixture(capacity = 2)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)

        f.execute(1)

        val failure = assertNotNull(f.collector.error)
        assertTrue(failure.contains("callback 시간 버퍼 초과: capacity=2"))
        assertEquals(1, f.markers.size)
        val original = f.state.snapshot()
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)
        assertEquals(original, f.state.snapshot())
        assertEquals(1, f.markers.size)
        assertFalse(f.state.isBlocked)
        assertTrue(f.service.started)
        val checkpoint = f.checkpoint()
        assertEquals(2, raw(checkpoint).size)
        assertEquals(failure, checkpoint.collectorFailure)
        assertEquals(failure, f.collector.error)
    }

    @Test
    fun `체크포인트가 확정한 raw를 보존한 뒤 다음 phase의 유한 버퍼를 새로 시작한다`() {
        val f = Fixture(capacity = 2)
        f.fund("BUYER", "USDT", 1_000)
        val prepared = f.checkpoint("prepared")

        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)
        val measured = f.checkpoint("measured")

        assertEquals(listOf("FUNDING"), raw(prepared).map { it.kind }, "새 phase의 추가가 이전 원본을 바꾸지 않는다")
        assertEquals(listOf("ADMISSION", "EXECUTION"), raw(measured).map { it.kind })
        assertEquals(3, raw(prepared).size + raw(measured).size)
        assertEquals(listOf(128L, 256L, 384L), (raw(prepared) + raw(measured)).map { it.position })
        val preparedTiming = timing(prepared)
        val measuredTiming = timing(measured)
        assertNull(preparedTiming["afterCheckpoint"])
        assertEquals("prepared", preparedTiming["throughCheckpoint"])
        assertEquals("prepared", measuredTiming["afterCheckpoint"])
        assertEquals("measured", measuredTiming["throughCheckpoint"])
        assertEquals(0, summary(measured, "all", "FUNDING", "productCallback").count)
        assertEquals(1, summary(measured, "measuredBuy", "ADMISSION", "productCallback").count)
        assertEquals(1, summary(measured, "measuredBuy", "EXECUTION", "productCallback").count)
        assertNull(f.collector.error)
    }

    @Test
    fun `계측 버퍼가 이미 넘쳤어도 제품 callback의 원래 종료 예외를 가리지 않는다`() {
        val f = Fixture(capacity = 1)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        val instrumentationFailure = assertNotNull(f.collector.error)
        val productFailure = IllegalStateException("원래 제품 오류")
        doAnswer { throw productFailure }.`when`(f.state).executePending(eq(1L), matchedEq(now), anyExecutionObserver())

        val fatal = assertFailsWith<AgentTerminationException> { f.execute(1) }

        assertSame(productFailure, fatal.cause)
        assertFalse(f.service.started)
        assertTrue(f.markers.isEmpty())
        assertEquals(instrumentationFailure, f.collector.error)
    }

    @Test
    fun `최초 Admission 종료부터 실행 시작까지 같은 노드 차분만 raw와 요약에 남긴다`() {
        val f = Fixture()
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        f.advanceNodeClock(80)

        f.execute(1)

        val checkpoint = f.checkpoint()
        val execution = raw(checkpoint).single { it.kind == "EXECUTION" }
        assertEquals(80L, execution.admissionToExecutionGapNs)
        assertEquals(0L, execution.productCallbackNs)
        assertEquals(13L, execution.observerNs)
        assertTrue(raw(checkpoint).filter { it.kind != "EXECUTION" }.all { it.admissionToExecutionGapNs == null })
        assertEquals(
            HttpPerformanceCallbackDurationSummary(1, 80, 80, 80, 80, 80),
            summary(checkpoint, "measuredBuy", "EXECUTION", "admissionToExecutionGap"),
        )
        val mapper = jacksonObjectMapper()
        val serialized = mapper.readTree(mapper.writeValueAsString(execution))
        assertEquals(
            setOf(
                "kind",
                "userId",
                "requestId",
                "admissionSequence",
                "position",
                "productCallbackNs",
                "observerNs",
                "admissionToExecutionGapNs",
                "phases",
                "phaseDurationsNs",
                "executionOfferAttempts",
                "executionBackPressured",
                "executionAdminActions",
                "executionOfferPosition",
                "replyAttempts",
                "replyBackPressured",
                "replyAdminActions",
                "replyOfferPosition",
                "replyStatus",
                "callbackStartedNs",
                "callbackCompletedNs",
            ),
            serialized.properties().map { it.key }.toSet(),
        )
        assertTrue(timing(checkpoint).getValue("admissionToExecutionGapScope").toString().contains("not total consensus latency"))
        assertNull(f.collector.error)
    }

    @Test
    fun `Admission 재전송은 최초 종료를 덮지 않고 최초 실행 뒤 재전송은 gap을 다시 만들지 않는다`() {
        val f = Fixture()
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        f.advanceNodeClock(10)
        f.admit("M-1", "BUYER", Side.BUY)
        f.advanceNodeClock(20)
        f.execute(1)
        f.advanceNodeClock(30)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)

        val checkpoint = f.checkpoint()
        assertEquals(listOf(30L, null), raw(checkpoint).filter { it.kind == "EXECUTION" }.map { it.admissionToExecutionGapNs })
        assertEquals(1, summary(checkpoint, "measuredBuy", "EXECUTION", "admissionToExecutionGap").count)
        assertEquals(0, timing(checkpoint)["pendingAdmissionTimingCount"])
        assertEquals(1, f.markers.size)
        assertEquals(
            1,
            f.state
                .snapshot()
                .completions.size,
        )
        assertNull(f.collector.error)
    }

    @Test
    fun `raw phase를 비워도 대기 Admission 종료시각은 다음 체크포인트의 최초 실행까지 유지한다`() {
        val f = Fixture(capacity = 2)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        val admitted = f.checkpoint("admitted")
        f.advanceNodeClock(20)

        f.execute(1)

        val executed = f.checkpoint("executed")
        assertEquals(1, timing(admitted)["pendingAdmissionTimingCount"])
        assertEquals(0, timing(executed)["pendingAdmissionTimingCount"])
        assertEquals(listOf("FUNDING", "ADMISSION"), raw(admitted).map { it.kind })
        assertEquals(60L, raw(executed).single().admissionToExecutionGapNs, "체크포인트의 snapshot 한 번 40ns와 별도 대기 20ns를 포함한다")
        assertEquals("admitted", timing(executed)["afterCheckpoint"])
        assertEquals(1, summary(executed, "measuredBuy", "EXECUTION", "admissionToExecutionGap").count)
        assertNull(f.collector.error)
    }

    @Test
    fun `체크포인트를 넘어 대기시각 맵이 차도 계측 오류만 남기고 첫 실행은 원래 항목을 소비한다`() {
        val f = Fixture(capacity = 1)
        f.fund("BUYER", "USDT", 1_000)
        f.checkpoint("funded")
        f.admit("M-1", "BUYER", Side.BUY)
        val admitted = f.checkpoint("admitted")

        assertIs<AdmissionResultPayload.Accepted>(f.admit("M-2", "BUYER", Side.BUY))

        val failure = assertNotNull(f.collector.error)
        assertTrue(failure.contains("Admission 종료시각 버퍼 초과: capacity=1"))
        f.execute(1)
        val executed = f.checkpoint("executed")
        assertEquals(1, timing(admitted)["pendingAdmissionTimingCount"])
        assertEquals(0, timing(executed)["pendingAdmissionTimingCount"])
        assertNotNull(raw(executed).single().admissionToExecutionGapNs)
        assertEquals(failure, f.collector.error)
        assertEquals(1, f.markers.size)
        assertEquals(
            1,
            f.state
                .snapshot()
                .completions.size,
        )
        assertEquals(
            1,
            f.state
                .snapshot()
                .pendingOrders.size,
        )
        assertFalse(f.state.isBlocked)
        assertTrue(f.service.started)
    }

    @Test
    fun `상세 기록 off는 노드 시계를 읽지 않고 업무 결과와 완료 표식을 유지한다`() {
        val f = Fixture(detailedTracing = false)
        f.fund("SELLER", "BTC", 100_000_000)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("S-1", "SELLER", Side.SELL)
        f.execute(1)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(2)

        val checkpoint = f.checkpoint()

        assertEquals(2, f.markers.size)
        assertEquals(listOf(0L, 1L), f.markers.map { it.settlementCount })
        assertTrue(raw(checkpoint).isEmpty())
        assertEquals(false, timing(checkpoint)["detailedTracing"])
        assertEquals(0, f.nodeClockReads)
        assertEquals(0, timing(checkpoint)["pendingAdmissionTimingCount"])
        assertEquals(
            2,
            f.state
                .snapshot()
                .completions.size,
        )
        assertFalse(f.state.isBlocked)
        assertNull(f.collector.error)
    }

    @Test
    fun `raw 용량을 8192개보다 크게 설정할 수 없다`() {
        listOf(-1, 0, HTTP_PERFORMANCE_CALLBACK_CAPACITY + 1, Int.MAX_VALUE).forEach { capacity ->
            assertFailsWith<IllegalArgumentException> {
                HttpPerformanceCollector("timing-test", 0, {}, { 0L }, capacity)
            }
        }
    }

    @Test
    fun `노드 내부 단계는 원 요청에 연결하고 운영 metric에는 고정 태그만 남긴다`() {
        val registry = SimpleMeterRegistry()
        val records = mutableListOf<TradingNodeCallbackTelemetry>()
        val telemetry = TradingNodeTelemetry.compose(TradingNodeMetrics(registry), TradingNodeTelemetry { records.add(it) })
        val f = Fixture(telemetry = telemetry)
        f.fund("BUYER", "USDT", 1_000)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)
        f.admit("M-1", "BUYER", Side.BUY)
        f.execute(1)

        val rows = raw(f.checkpoint()).filter { it.requestId == "M-1" }
        val admitted = rows[0]
        assertTrue(
            admitted.phases.keys.containsAll(
                listOf(
                    "callback",
                    "decode",
                    "admission_context",
                    "admission_apply",
                    "execution_encode",
                    "execution_offer",
                    "reply_encode",
                    "reply_offer",
                ),
            ),
        )
        assertEquals(1, admitted.executionOfferAttempts)
        assertEquals(1L, admitted.executionOfferPosition)
        assertEquals(0, admitted.replyAttempts)
        assertEquals("NO_SESSION", admitted.replyStatus)
        val executed = rows[1]
        assertEquals(1L, executed.admissionSequence)
        assertNotNull(executed.callbackStartedNs)
        assertNotNull(executed.callbackCompletedNs)
        assertEquals(executed.phases.getValue("callback"), executed.callbackCompletedNs - executed.callbackStartedNs)
        assertTrue(
            executed.phases.keys.containsAll(
                listOf(
                    "execution_total",
                    "matching_candidate",
                    "settlement_input_validation",
                    "settlement_candidate",
                    "result_validation",
                    "execution_apply",
                    "completion",
                ),
            ),
        )
        assertNull(rows[2].executionOfferAttempts, "멱등 접수는 새 실행 제출을 하지 않는다")
        assertFalse("matching_candidate" in rows[3].phases, "멱등 실행은 매칭을 다시 계산하지 않는다")
        assertEquals(
            2L,
            registry
                .get(TradingNodeMetrics.DURATION)
                .tag("phase", "admission_apply")
                .timer()
                .count(),
        )
        assertEquals(
            1L,
            registry
                .get(TradingNodeMetrics.DURATION)
                .tag("phase", "matching_candidate")
                .timer()
                .count(),
        )
        assertTrue(registry.meters.flatMap { it.id.tags }.all { it.key in setOf("phase", "kind", "target") })
        assertEquals(listOf("M-1", "M-1"), records.filter { it.kind == TradingNodeCallbackKind.ADMISSION }.map { it.requestId })
        assertEquals(1, f.markers.size)
        assertNull(f.collector.error)
        registry.close()
    }

    @Test
    fun `재사용한 capture는 보관한 이전 record의 시간과 요청 정보를 바꾸지 않는다`() {
        var nowNs = 0L
        val capture = TradingNodeCallbackCapture { nowNs++ }
        val retained = mutableListOf<TradingNodeCallbackTelemetry>()
        val observer = TradingNodeTelemetry { retained.add(it) }
        capture.kind = TradingNodeCallbackKind.ADMISSION
        capture.requestId = "R1"
        capture.executionOfferAttempts = 2
        capture.failed = true
        capture.begin(TradingNodePhase.DECODE)
        capture.end(TradingNodePhase.DECODE)
        capture.complete()
        observer.recordCompleted(capture, 128, "LEADER", "NO_SESSION")

        capture.reset()
        capture.kind = TradingNodeCallbackKind.EXECUTION
        capture.sequence = 1
        capture.begin(TradingNodePhase.EXECUTION_TOTAL)
        capture.end(TradingNodePhase.EXECUTION_TOTAL)
        capture.complete()
        observer.recordCompleted(capture, 256, "FOLLOWER", "EXECUTED")

        val first = retained[0]
        val second = retained[1]
        assertEquals("R1", first.requestId)
        assertEquals(2, first.executionOfferAttempts)
        assertTrue(first.failed)
        assertEquals(1L, first.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(-1L, first.phaseDurationsNs[TradingNodePhase.EXECUTION_TOTAL.ordinal])
        assertNull(second.requestId)
        assertEquals(0, second.executionOfferAttempts)
        assertFalse(second.failed)
        assertEquals(-1L, second.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(1L, second.phaseDurationsNs[TradingNodePhase.EXECUTION_TOTAL.ordinal])
        assertTrue(first.phaseDurationsNs !== second.phaseDurationsNs)
    }

    @Test
    fun `완료 capture를 직접 기록해도 운영 timer와 시도 횟수를 전부 남긴다`() {
        SimpleMeterRegistry().use { registry ->
            var nowNs = 0L
            val metrics: TradingNodeTelemetry = TradingNodeMetrics(registry)
            val capture = TradingNodeCallbackCapture { nowNs++ }
            capture.kind = TradingNodeCallbackKind.ADMISSION
            capture.executionOfferAttempts = 3
            capture.replyAttempts = 2
            capture.begin(TradingNodePhase.DECODE)
            capture.end(TradingNodePhase.DECODE)
            capture.complete()

            metrics.recordCompleted(capture, 128, "LEADER", "SENT")

            assertEquals(
                1.0,
                registry
                    .get(TradingNodeMetrics.CALLBACKS)
                    .tag("kind", "admission")
                    .counter()
                    .count(),
            )
            assertEquals(
                1L,
                registry
                    .get(TradingNodeMetrics.DURATION)
                    .tag("phase", "decode")
                    .timer()
                    .count(),
            )
            assertEquals(
                1.0,
                registry
                    .get(TradingNodeMetrics.DURATION)
                    .tag("phase", "decode")
                    .timer()
                    .totalTime(TimeUnit.NANOSECONDS),
            )
            assertEquals(
                3.0,
                registry
                    .get(TradingNodeMetrics.DURATION)
                    .tag("phase", "callback")
                    .timer()
                    .totalTime(TimeUnit.NANOSECONDS),
            )
            assertEquals(
                3.0,
                registry
                    .get("${TradingNodeMetrics.PREFIX}.offer_attempts")
                    .tag("target", "execution")
                    .counter()
                    .count(),
            )
            assertEquals(
                2.0,
                registry
                    .get("${TradingNodeMetrics.PREFIX}.offer_attempts")
                    .tag("target", "reply")
                    .counter()
                    .count(),
            )
            assertTrue(metrics.isOperational)
        }
    }

    @Test
    fun `관측자에서 중첩 callback이 발생해도 이전 record를 덮거나 단계 정보를 섞지 않는다`() {
        lateinit var fixture: Fixture
        val retained = mutableListOf<TradingNodeCallbackTelemetry>()
        var nested = false
        val observer =
            TradingNodeTelemetry { value ->
                retained.add(value)
                if (!nested) {
                    nested = true
                    fixture.fund("OTHER", "USDT", 2)
                }
            }
        fixture = Fixture(detailedTracing = false, telemetry = observer)

        fixture.fund("BUYER", "USDT", 1_000)
        fixture.admit("M-1", "BUYER", Side.BUY)

        assertEquals(listOf("P-BUYER-USDT", "P-OTHER-USDT", "M-1"), retained.map { it.requestId })
        assertTrue(retained[0].phaseDurationsNs[TradingNodePhase.FUNDING_APPLY.ordinal] >= 0)
        assertEquals(-1L, retained[0].phaseDurationsNs[TradingNodePhase.ADMISSION_APPLY.ordinal])
        assertEquals(-1L, retained[2].phaseDurationsNs[TradingNodePhase.FUNDING_APPLY.ordinal])
        assertTrue(retained[2].phaseDurationsNs[TradingNodePhase.ADMISSION_APPLY.ordinal] >= 0)
        assertTrue(retained[0].phaseDurationsNs !== retained[1].phaseDurationsNs)
        assertTrue(retained[0].phaseDurationsNs !== retained[2].phaseDurationsNs)
        assertTrue(retained[1].phaseDurationsNs !== retained[2].phaseDurationsNs)
        assertTrue(fixture.service.telemetryOperational)
    }

    @Test
    fun `실패하는 추가 관측자는 제품 결과와 다음 관측자를 바꾸지 않고 시작 뒤 교체는 거절한다`() {
        val f = Fixture(telemetry = TradingNodeTelemetry { throw IllegalStateException("observer unavailable") })
        f.fund("BUYER", "USDT", 1_000)
        assertIs<AdmissionResultPayload.Accepted>(f.admit("M-1", "BUYER", Side.BUY))
        f.execute(1)

        assertEquals(1, f.markers.size)
        assertTrue(raw(f.checkpoint()).all { it.phases.isNotEmpty() })
        assertFalse(f.state.isBlocked)
        assertTrue(assertNotNull(f.collector.error).contains("노드 계측 장애"))
        assertFailsWith<IllegalStateException> { f.service.attachTelemetry(TradingNodeTelemetry.NONE) }
    }

    @Test
    fun `노드 관측 시계 예외는 거짓 0ns 대신 계측 실패와 빠진 경계를 남긴다`() {
        var unavailable = false
        val capture =
            TradingNodeCallbackCapture {
                if (unavailable) throw IllegalStateException("clock unavailable")
                -1_000L
            }
        capture.begin(TradingNodePhase.DECODE)
        unavailable = true

        capture.end(TradingNodePhase.DECODE)
        val value = capture.finish(128, "LEADER", "NO_SESSION")

        assertEquals(-1_000L, value.callbackStartedNs)
        assertNull(value.callbackCompletedNs)
        assertEquals(-1L, value.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(-1L, value.phaseDurationsNs[TradingNodePhase.CALLBACK.ordinal])
        assertEquals(2, value.timingErrors)
        assertFalse(value.failed, "시계 실패를 업무 실패로 바꾸지 않는다")
        SimpleMeterRegistry().use { registry ->
            val metrics = TradingNodeMetrics(registry)
            metrics.record(value)
            assertFalse(metrics.isOperational)
            assertEquals(2L, metrics.failureCount)
            assertEquals(2.0, registry.get("${TradingNodeMetrics.PREFIX}.observation_failures").counter().count())
        }
    }

    @Test
    fun `primitive 시계는 signed wrap을 지나는 음수 시각과 정확한 호출 수를 유지한다`() {
        val readings = longArrayOf(Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2)
        var reads = 0
        val clock = NanoClock { readings[reads++] }
        val capture = TradingNodeCallbackCapture(clock)

        capture.begin(TradingNodePhase.DECODE)
        capture.end(TradingNodePhase.DECODE)
        capture.complete()
        val value = capture.finish(128, "LEADER", "NO_SESSION")

        assertEquals(4, reads, "finish는 이미 완료한 callback의 시계를 다시 읽지 않는다")
        assertEquals(Long.MAX_VALUE - 2, value.callbackStartedNs)
        assertEquals(Long.MIN_VALUE + 2, value.callbackCompletedNs)
        assertEquals(3L, value.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(5L, value.phaseDurationsNs[TradingNodePhase.CALLBACK.ordinal])
        assertEquals(0, value.timingErrors)
        assertFalse(value.failed)
    }

    @Test
    fun `primitive 시계의 시작 예외 뒤 reset은 이전 실패 관찰을 보존하고 다시 측정한다`() {
        var unavailable = true
        var nowNs = -10L
        var reads = 0
        val clock =
            NanoClock {
                reads++
                if (unavailable) throw IllegalStateException("clock unavailable")
                nowNs++
            }
        val capture = TradingNodeCallbackCapture(clock)
        capture.begin(TradingNodePhase.DECODE)
        capture.end(TradingNodePhase.DECODE)
        val failed = capture.finish(128, "LEADER", "NO_SESSION")
        assertEquals(2, reads, "시작하지 못한 phase의 end는 시계를 읽지 않는다")
        assertNull(failed.callbackStartedNs)
        assertNull(failed.callbackCompletedNs)
        assertEquals(-1L, failed.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(2, failed.timingErrors)
        assertFalse(failed.failed)

        unavailable = false
        capture.reset()
        capture.begin(TradingNodePhase.DECODE)
        capture.end(TradingNodePhase.DECODE)
        val recovered = capture.finish(256, "LEADER", "NO_SESSION")

        assertEquals(6, reads)
        assertEquals(-10L, recovered.callbackStartedNs)
        assertEquals(-7L, recovered.callbackCompletedNs)
        assertEquals(1L, recovered.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
        assertEquals(3L, recovered.phaseDurationsNs[TradingNodePhase.CALLBACK.ordinal])
        assertEquals(0, recovered.timingErrors)
        assertEquals(2, failed.timingErrors)
        assertNull(failed.callbackStartedNs)
        assertEquals(-1L, failed.phaseDurationsNs[TradingNodePhase.DECODE.ordinal])
    }

    @Test
    fun `등록 실패와 NOOP 지표는 비정상 계측이며 금융 결과는 유지한다`() {
        val filters =
            listOf(
                MeterFilter.deny(),
                object : MeterFilter {
                    override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("registration unavailable")
                },
            )
        filters.forEach { filter ->
            SimpleMeterRegistry().use { registry ->
                registry.config().meterFilter(filter)
                val metrics = TradingNodeMetrics(registry)
                assertFalse(metrics.isOperational)
                assertEquals(1L, metrics.failureCount)
                val f = Fixture(telemetry = metrics)
                f.fund("BUYER", "USDT", 1_000)
                assertIs<AdmissionResultPayload.Accepted>(f.admit("M-1", "BUYER", Side.BUY))
                f.execute(1)
                assertEquals(1, f.markers.size)
                assertFalse(f.state.isBlocked)
                assertTrue(assertNotNull(f.collector.error).contains("노드 계측 장애"))
            }
        }
    }

    @Test
    fun `Timer 기록 장애 뒤에는 한 번의 실패와 degraded를 남기고 반복 기록을 중단한다`() {
        val registry =
            object : SimpleMeterRegistry() {
                var attempts = 0

                override fun newTimer(
                    id: Meter.Id,
                    distributionStatisticConfig: DistributionStatisticConfig,
                    pauseDetector: PauseDetector,
                ): Timer {
                    val actual = super.newTimer(id, distributionStatisticConfig, pauseDetector)
                    return object : Timer by actual {
                        override fun record(
                            amount: Long,
                            unit: TimeUnit,
                        ) {
                            attempts++
                            throw IllegalStateException("timer unavailable")
                        }
                    }
                }
            }
        registry.use {
            val metrics = TradingNodeMetrics(registry)
            val f = Fixture(telemetry = metrics)
            f.fund("BUYER", "USDT", 1_000)
            assertIs<AdmissionResultPayload.Accepted>(f.admit("M-1", "BUYER", Side.BUY))
            f.execute(1)

            assertEquals(1, registry.attempts)
            assertFalse(metrics.isOperational)
            assertEquals(1L, metrics.failureCount)
            assertEquals(1.0, registry.get("${TradingNodeMetrics.PREFIX}.degraded").gauge().value())
            assertEquals(1.0, registry.get("${TradingNodeMetrics.PREFIX}.observation_failures").counter().count())
            assertEquals(1, f.markers.size)
            assertFalse(f.state.isBlocked)
            assertTrue(assertNotNull(f.collector.error).contains("노드 계측 장애"))
        }
    }

    private fun timing(checkpoint: HttpPerformanceCheckpointCapture): Map<String, Any?> = checkpoint.callbackTimings

    private inline fun <T : SimpleMeterRegistry, R> T.use(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }

    /** Mockito의 null dummy를 Kotlin의 non-null 인자로 전달하지 않고 matcher 등록은 유지한다. */
    private fun <T : Any> matchedEq(value: T): T = eq(value) ?: value

    private fun anyExecutionObserver(): TradingExecutionObserver =
        any(TradingExecutionObserver::class.java) ?: TradingExecutionObserver.NONE

    private fun raw(checkpoint: HttpPerformanceCheckpointCapture): List<HttpPerformanceCallbackTiming> =
        (
            timing(
                checkpoint,
            ).getValue("raw") as List<*>
        ).map { callbackTimingMapper.convertValue(it, HttpPerformanceCallbackTiming::class.java) }

    @Suppress("UNCHECKED_CAST")
    private fun summary(
        checkpoint: HttpPerformanceCheckpointCapture,
        scope: String,
        kind: String,
        segment: String,
    ): HttpPerformanceCallbackDurationSummary {
        val groups = timing(checkpoint).getValue(scope) as Map<String, Any?>
        return (groups.getValue(kind) as Map<String, Any?>).getValue(segment) as HttpPerformanceCallbackDurationSummary
    }

    private inner class Fixture(
        capacity: Int = HTTP_PERFORMANCE_CALLBACK_CAPACITY,
        detailedTracing: Boolean = true,
        telemetry: TradingNodeTelemetry = TradingNodeTelemetry.NONE,
    ) {
        private var nodeNowNs = -1_000L
        private var position = 0L
        private val snapshotCostNs = 40L
        var admissionCostNs = 0L
        var nodeClockReads = 0
        val state = spy(tradingState())
        val markers = mutableListOf<HttpPerformanceCompletion>()
        private val cluster = mock(Cluster::class.java)
        val service = DevelopmentFundingAdapter(state, telemetry = telemetry, onFatal = { throw AssertionError("제품 서비스가 실패했습니다", it) })
        val collector =
            HttpPerformanceCollector(
                "timing-test",
                0,
                { marker ->
                    nodeNowNs += 13
                    markers.add(marker)
                },
                {
                    nodeClockReads++
                    nodeNowNs
                },
                capacity,
                detailedTracing,
            )
        private val decorated = collector.decorate(service)

        init {
            `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
            `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
            // 역할 조회 횟수와 무관하게 한 callback의 관찰 경계에서 모의 비용을 한 번만 더한다.
            `when`(cluster.logPosition()).thenAnswer {
                nodeNowNs += admissionCostNs
                position
            }
            ClusterExecutionClaimStub(cluster)
            doAnswer { invocation ->
                nodeNowNs += snapshotCostNs
                invocation.callRealMethod()
            }.`when`(state).snapshot()
            collector.configure(DevelopmentFundingSettings(Path.of("/performance-callback-timing-test")))
            decorated.onStart(cluster, null)
        }

        fun fund(
            user: String,
            asset: String,
            amount: Long,
        ) {
            val opening = OpeningBalance("P-$user-$asset", UserId(user), AssetId(asset), Amount(amount))
            send(DevelopmentBalanceCommandCodec().encode(DevelopmentBalanceCommandPayload(opening, "OPEN-$user-$asset", now)))
        }

        fun admit(
            request: String,
            user: String,
            side: Side,
        ): AdmissionResultPayload {
            val order =
                OrderAdmissionInput(
                    context.market.marketId,
                    OrderId(request),
                    UserId(user),
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(100),
                    Quantity(100_000_000),
                )
            val command = AdmissionCommandPayload(request, order, context.market, context.feePolicySnapshot, "RESERVE-$request", now)
            send(AdmissionCommandCodec().encode(command))
            return assertNotNull(service.observation?.admissionResult)
        }

        fun execute(sequence: Long) {
            send(ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(sequence)), internal = true)
        }

        fun nodeClock(): Long = nodeNowNs

        fun advanceNodeClock(durationNs: Long) {
            nodeNowNs += durationNs
        }

        private fun send(
            bytes: ByteArray,
            internal: Boolean = false,
        ) {
            position += 128
            decorated.onSessionMessage(
                null,
                now.toEpochMilli(),
                clusterFrame(bytes, if (internal) Long.MIN_VALUE else 1L),
                AeronCluster.SESSION_HEADER_LENGTH,
                bytes.size,
                Header(0, 0),
            )
        }

        fun checkpoint(label: String = "after"): HttpPerformanceCheckpointCapture {
            collector.requestCheckpoint(label)
            decorated.doBackgroundWork(0)
            return assertNotNull(collector.pollCheckpoint())
        }
    }
}

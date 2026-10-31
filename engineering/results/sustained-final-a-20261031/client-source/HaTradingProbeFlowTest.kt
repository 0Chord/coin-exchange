package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.order.Side
import org.agrona.concurrent.NanoClock
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HaTradingProbeFlowTest {
    @Test
    fun `같은 gate mask는 시계를 읽지 않고 전이와 종료만으로 같은 dwell을 기록한다`() {
        val gates = HaProbeGateDwell(100, 200)
        val reference = HaProbeGateDwell(100, 200)
        var now = 100L
        var clockReads = 0
        val clock =
            NanoClock {
                clockReads++
                now
            }
        for ((at, mask) in listOf(110L to 0, 120L to 1, 130L to 1, 140L to 3, 170L to 3, 190L to 0, 250L to 0)) {
            now = at
            gates.observeIfChanged(mask, clock)
            reference.observe(at, mask)
        }
        gates.close(250)
        reference.close(250)
        gates.observeIfChanged(7, NanoClock { error("closed gate must not read the clock") })
        assertEquals(3, clockReads)
        assertEquals(reference.summary(), gates.summary())
        assertEquals(100L, gates.summary()["elapsedNs"])

        val unchanged = HaProbeGateDwell(100, 200)
        repeat(8) { unchanged.observeIfChanged(0, NanoClock { error("initial zero mask must not read the clock") }) }
        unchanged.close(250)
        val unchangedReference = HaProbeGateDwell(100, 200)
        unchangedReference.close(250)
        assertEquals(unchangedReference.summary(), unchanged.summary())
        assertEquals(100L, unchanged.summary()["elapsedNs"])
    }

    @Test
    fun `겹친 admission 완료 ACK gate는 한 mask에서만 시간을 소비하고 window 뒤 drain을 더하지 않는다`() {
        assertEquals(7, haProbeGateMask(1_024, 0, 0, 256, 1_024, 4_097, 0, 4_096))
        assertEquals(0, haProbeGateMask(1, 1, 1, 256, 1_024, 2, 0, 4_096))
        val gates = HaProbeGateDwell(100, 200)
        gates.observe(110, 1)
        gates.observe(130, 3)
        gates.observe(170, 7)
        gates.close(250)
        gates.observe(1_000, 0)
        val report = gates.summary()

        @Suppress("UNCHECKED_CAST")
        val masks = report["masks"] as List<Map<String, Any>>
        assertEquals(100L, report["elapsedNs"])
        assertEquals(listOf(10L, 20L, 0L, 40L, 0L, 0L, 0L, 30L), masks.map { it["totalNs"] })
        assertEquals(100L, masks.sumOf { it["totalNs"] as Long })
        assertEquals(30L, masks[7]["maxObservedDwellNs"])
    }

    @Test
    fun `시간제 TPS는 이른 마지막 marker 뒤의 남은 window와 최종 ACK를 구분한다`() {
        val rate = haProbeThroughput(10, true, 0, 0, 1_000, 100, 1_200)
        assertEquals(100L, rate.rawSpanNs)
        assertEquals(1_000L, rate.elapsedNs)
        assertEquals(1_200L, rate.cohortElapsedNs)
        assertEquals(100_000_000.0, rate.rawSpanTps)
        assertEquals(10_000_000.0, rate.tps)
        assertTrue(rate.cohortTps < rate.tps)
        val delayed = haProbeThroughput(10, true, 0, 10, 1_000, 1_500, 1_700)
        assertEquals(1_500L, delayed.elapsedNs)
        assertEquals(1_700L, delayed.cohortElapsedNs)
    }

    @Test
    fun `기존 유한 cohort TPS는 첫 송신부터 마지막 관찰까지의 의미를 유지한다`() {
        val rate = haProbeThroughput(10, false, 0, 10, 1_000, 100, 1_200)
        assertEquals(90L, rate.rawSpanNs)
        assertEquals(90L, rate.elapsedNs)
        assertEquals(1_190L, rate.cohortElapsedNs)
        assertEquals(rate.rawSpanTps, rate.tps)
    }

    @Test
    fun `기존 count 기본값을 유지하고 partial 프로필은 시간과 총 표본 상한을 명시한다`() {
        val options = TradingEngineProbeOptions()
        assertEquals(HaProbeRunPlan(false, 6_000), HaProbeRunPlan.from(options, emptyMap()))
        assertFailsWith<IllegalArgumentException> {
            HaProbeRunPlan.from(options, mapOf("HA_PROBE_WORKLOAD" to "partial-maker"))
        }
        val environment =
            mapOf(
                "HA_PROBE_WORKLOAD" to "partial-maker",
                "HA_PROBE_MAX_SAMPLES" to "2000000",
                "HA_PROBE_WARMUP_SECONDS" to "20",
                "HA_PROBE_MEASURE_SECONDS" to "10",
            )
        assertEquals(HaProbeRunPlan(true, 2_000_000, 20, 10), HaProbeRunPlan.from(options, environment))
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 2_000_000, 19, 10) }
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 2_000_000, 20, 16) }
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 4_000_001, 20, 10) }
        assertFailsWith<IllegalArgumentException> { AckWire().configure(HaProbeRunPlan(true, 10, 20, 10)) }
    }

    @Test
    fun `600초 프로필만 funding 상한을 늘리고 warmup 배열은 200만 표본으로 제한한다`() {
        val plan = HaProbeRunPlan(true, 64_000_000, 20, 600)
        assertTrue(plan.sustained)
        assertEquals(2_000_000, plan.warmupCapacity)
        assertEquals(641_280_001_000L, plan.remainingQuote(0))
        assertEquals(1_000L, plan.remainingQuote(64_000_000))
        assertTrue((0..2).all { plan.remainingBase(64_000_000, it) == 0L })
        assertEquals(21_333_334L * 100_000_000L, plan.baseFunding(0))
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 64_000_001, 20, 600) }
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 4_000_001, 20, 15) }
        assertFailsWith<IllegalArgumentException> { HaProbeRunPlan(true, 64_000_000, 20, 599) }
        val short = HaProbeRunPlan(true, 4_000_000, 20, 10)
        assertFalse(short.sustained)
        assertEquals(short.capacity, short.warmupCapacity)
    }

    @Test
    fun `지속 시험 percentile은 구간 평균 대신 전체 표본 분포를 합친다`() {
        val latencies = HaProbeLatencies()
        repeat(9_900) { latencies.record(1_000, 2_000, 3_000) }
        repeat(100) { latencies.record(1_000_000, 2_000_000, 3_000_000) }
        assertEquals(10_000L, latencies.count)
        // Exactly 99 percent of the combined observations are in the fast group.
        assertEquals(listOf(1_000L, 1_000L, 2_000L, 2_000L, 3_001L, 3_001L), latencies.percentiles())
        assertFailsWith<IllegalArgumentException> { latencies.record(-1, 0, 0) }
        assertEquals(10_000L, latencies.count)
    }

    @Test
    fun `배열 준비 중 전역 deadline을 넘으면 전송과 gate window를 0으로 닫는다`() {
        assertEquals(200L, haProbeSendDeadline(200, 100, 150))
        val gates = HaProbeGateDwell(200, haProbeSendDeadline(200, 100, 150))
        gates.close(210)
        assertEquals(0L, gates.summary()["elapsedNs"])
        assertEquals(250L, haProbeSendDeadline(200, 100, 250))
        assertEquals(300L, haProbeSendDeadline(200, 100, 500))
        assertEquals(300L, haProbeSendDeadline(200, 100, null))
        assertEquals(null, haProbeSendDeadline(200, null, null))
    }

    @Test
    fun `600초 TPS는 첫 배열 준비부터 구간 공백과 마지막 ACK drain까지 포함한다`() {
        val rate = haProbeThroughput(10, true, 100, 150, 700, 650, 800, includeFinalAck = true)
        assertEquals(500L, rate.rawSpanNs)
        assertEquals(700L, rate.elapsedNs)
        assertEquals(700L, rate.cohortElapsedNs)
        assertEquals(rate.cohortTps, rate.tps)
        assertEquals(20_000_000.0, rate.rawSpanTps)
    }

    @Test
    fun `새 구간의 ACK floor는 이전 구간의 누적 BUY 순번부터 시작한다`() {
        val wire = AckWire()
        val ack = HaProbeAckCoordinator(wire, Side.BUY, 1_234_567)
        ack.observeThrough(1_234_800)
        ack.pump({ true }, 1, flush = true)
        assertEquals(listOf(1_234_800L), wire.encoded)
        assertEquals(1_234_567L, ack.confirmedThrough)
        assertTrue(ack.accept(wire.reply(1_234_800)))
        assertEquals(1_234_800L, ack.confirmedThrough)
    }

    @Test
    fun `큰 maker의 원금 잔량과 미사용 현금은 실제 체결 건수로 계산한다`() {
        val plan = HaProbeRunPlan(true, 10, 20, 10)
        assertEquals(3, plan.sellOrders)
        assertEquals(400_000_000L, plan.orderQuantity(0, Side.SELL).value)
        assertEquals(100_000_000L, plan.orderQuantity(3, Side.BUY).value)
        assertEquals(61_120L, plan.remainingQuote(4))
        assertEquals(
            listOf(200_000_000L, 2_000_000_000L, 2_000_000_000L),
            (0..2).map { plan.remainingBase(4, it) },
        )
        assertEquals(1_000L, plan.remainingQuote(10))
        assertTrue((0..2).all { plan.remainingBase(10, it) == 0L })
        assertFailsWith<IllegalArgumentException> { plan.remainingQuote(11) }
        assertFailsWith<IllegalArgumentException> { plan.remainingBase(-1, 0) }
    }

    @Test
    fun `ACK 하나가 진행 중일 때 새 prefix를 합치고 응답 뒤에만 floor를 전진한다`() {
        val wire = AckWire()
        val ack = HaProbeAckCoordinator(wire, Side.BUY, 0)
        ack.observeThrough(256)
        ack.pump({ true }, 1)
        assertTrue(ack.awaitingReply)
        ack.observeThrough(768)
        ack.pump({ true }, 2)
        assertEquals(listOf(256L), wire.encoded)
        assertEquals(0L, ack.confirmedThrough)
        assertTrue(ack.accept(wire.reply(256)))
        assertFalse(ack.awaitingReply)
        assertEquals(256L, ack.confirmedThrough)
        ack.pump({ true }, 3)
        assertEquals(listOf(256L, 768L), wire.encoded)
        assertTrue(ack.accept(wire.reply(768)))
        assertEquals(768L, ack.confirmedThrough)
    }

    @Test
    fun `전송 역압과 다른 응답 및 잘못된 ACK floor가 credit을 풀지 않는다`() {
        val wire = AckWire()
        val ack = HaProbeAckCoordinator(wire, Side.BUY, 0)
        ack.observeThrough(256)
        ack.pump({ false }, 1)
        assertFalse(ack.accept(byteArrayOf()))
        assertEquals(0L, ack.confirmedThrough)
        ack.pump({ true }, 2)
        assertFailsWith<IllegalStateException> { ack.accept(wire.reply(257)) }
        assertEquals(0L, ack.confirmedThrough)
        assertTrue(ack.accept(wire.reply(256)))
        ack.observeThrough(260)
        ack.pump({ true }, 3)
        assertEquals(1, wire.encoded.size)
        ack.pump({ true }, 4, flush = true)
        assertEquals(listOf(256L, 260L), wire.encoded)
    }

    @Test
    fun `응답보다 빠른 세 노드 완료를 상관하고 소비 후 회수한다`() {
        val collector = HaProbeMarkerCollector(2)
        collector.observe(0, 50, 1_000, true, 10)
        collector.observe(2, 50, 1_000, false, 30)
        collector.observe(1, 50, 1_000, false, 20)
        assertEquals(1, collector.retained)
        collector.register(50, 0)
        val completed = mutableListOf<HaProbeCompletion>()
        assertEquals(1, collector.drain(completed::add))
        assertEquals(listOf(HaProbeCompletion(0, 50, 10, 30)), completed)
        assertEquals(0, collector.retained)
        assertEquals(3L, collector.summary()["observedFrames"])
        assertEquals(1L, collector.summary()["registeredResponses"])
        assertEquals(1L, collector.summary()["drainedCompletions"])
        collector.observe(0, 50, 1_000, true, 40) // Exact last-frame duplicate is harmless after reclamation.
        assertEquals(0, collector.retained)
    }

    @Test
    fun `빈 완료 poll은 lock 표본을 만들지 않고 새 완료를 다시 발행할 수 있다`() {
        val collector = HaProbeMarkerCollector(1)
        val completed = mutableListOf<HaProbeCompletion>()
        repeat(1_024) { assertEquals(0, collector.drain(completed::add)) }
        assertEquals(0L, collector.summary()["lockWaitSamples"])
        for (sequence in 1L..2L) {
            collector.register(sequence, sequence.toInt() - 1)
            for (member in 0..2) collector.observe(member, sequence, sequence * 100, member == 0, sequence * 10 + member)
            assertEquals(1, collector.drain(completed::add))
            repeat(1_024) { assertEquals(0, collector.drain(completed::add)) }
            assertEquals(0, collector.retained)
        }
        assertEquals(listOf(1L, 2L), completed.map { it.sequence })
        assertEquals(0L, collector.summary()["lockWaitSamples"])
        assertEquals(2L, collector.summary()["drainedCompletions"])
    }

    @Test
    fun `drain 중 시작한 발행이 monitor 뒤에서 새 완료 flag를 다시 세운다`() {
        repeat(8) {
            val collector = HaProbeMarkerCollector(2)
            collector.register(1, 0)
            for (member in 0..2) collector.observe(member, 1, 100, member == 0, 10 + member.toLong())
            val start = CountDownLatch(1)
            val attempting = CountDownLatch(1)
            val published = CompletableFuture<Unit>()
            val producer =
                Thread {
                    try {
                        check(start.await(2, TimeUnit.SECONDS))
                        attempting.countDown()
                        collector.register(2, 1)
                        for (member in 0..2) collector.observe(member, 2, 200, member == 0, 20 + member.toLong())
                        published.complete(Unit)
                    } catch (failure: Throwable) {
                        published.completeExceptionally(failure)
                    }
                }.also { it.start() }
            val completed = mutableListOf<HaProbeCompletion>()
            try {
                assertEquals(
                    1,
                    collector.drain { value ->
                        completed.add(value)
                        start.countDown()
                        check(attempting.await(2, TimeUnit.SECONDS))
                        assertFalse(published.isDone)
                    },
                )
                published.get(2, TimeUnit.SECONDS)
                assertEquals(1, collector.drain(completed::add))
                assertEquals(listOf(1L, 2L), completed.map { it.sequence })
                assertEquals(0, collector.drain(completed::add))
                assertEquals(0, collector.retained)
            } finally {
                start.countDown()
                producer.join(2_000)
            }
            assertFalse(producer.isAlive)
        }
    }

    @Test
    fun `마켓별 실행 순서가 달라도 순번을 추정하지 않고 세 사본 누락을 숨기지 않는다`() {
        val collector = HaProbeMarkerCollector(3)
        collector.register(20, 0)
        collector.register(10, 1)
        for (member in 0..2) collector.observe(member, 20, 1_000, member == 0, 10 + member.toLong())
        collector.observe(0, 10, 2_000, true, 20)
        collector.observe(1, 10, 2_000, false, 21)
        val completed = mutableListOf<HaProbeCompletion>()
        assertEquals(1, collector.drain(completed::add))
        assertEquals(20L, completed.single().sequence)
        assertEquals(1, collector.retained)
        collector.observe(2, 10, 2_000, false, 22)
        assertEquals(1, collector.drain(completed::add))
        assertEquals(0, collector.retained)
        assertFailsWith<IllegalStateException> { collector.observe(0, 20, 1_000, true, 30) }
    }

    @Test
    fun `관찰 cap과 세 노드 log position 불일치가 측정을 실패시킨다`() {
        val collector = HaProbeMarkerCollector(1)
        collector.observe(0, 1, 100, true, 10)
        assertFailsWith<IllegalStateException> { collector.observe(1, 1, 101, false, 11) }
        assertFailsWith<IllegalStateException> { collector.register(2, 0) }
    }

    private class AckWire : HaProbeWire {
        override val mode = "test"
        val encoded = mutableListOf<Long>()

        override fun initialize(connection: HaProbeConnection) = Unit

        override fun fund(
            connection: HaProbeConnection,
            command: DevelopmentBalanceCommandPayload,
        ) = Unit

        override fun open(connection: HaProbeConnection) = Unit

        override fun acknowledge(
            connection: HaProbeConnection,
            side: Side,
            through: Long,
        ) = Unit

        override fun encodeOrder(
            index: Int,
            side: Side,
        ) = byteArrayOf()

        override fun admission(
            bytes: ByteArray,
            side: Side,
        ): HaProbeAdmission? = null

        override fun encodeAcknowledgement(
            side: Side,
            through: Long,
        ): HaProbeAcknowledgement {
            encoded.add(through)
            return HaProbeAcknowledgement(1, through, byteArrayOf(1))
        }

        override fun acknowledgement(
            bytes: ByteArray,
            expected: HaProbeAcknowledgement,
        ): Long? = if (bytes.size == Long.SIZE_BYTES) ByteBuffer.wrap(bytes).long else null

        fun reply(through: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(through).array()
    }
}

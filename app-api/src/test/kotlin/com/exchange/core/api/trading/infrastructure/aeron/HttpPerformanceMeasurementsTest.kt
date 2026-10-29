package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 정해진 부하 발생기 시각으로 집계 의미를 검사한다. 실제 HTTP/Aeron 성능 근거는 아니다. */
class HttpPerformanceMeasurementsTest {
    @Test
    fun `완료가 HTTP 승인보다 먼저 와도 원래 요청과 연결하고 두 지연을 따로 보존한다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 10)
        val marker = marker()
        measurements.completionObserved(marker, 20)
        measurements.httpCompleted("buyer", "R1", 30, HttpPerformanceOutcome.ACCEPTED, 1)

        val summary = measurements.finish(2_000, listOf(marker))
        val sample = measurements.requests().single()

        assertTrue(summary.completionLatencyValid)
        assertEquals(10L, sample.generatorDelayNs)
        assertEquals(20L, sample.ackRttNs)
        assertEquals(10L, sample.completionLatencyNs)
        assertEquals(30L, sample.scheduledAckLatencyNs)
        assertEquals(20L, sample.scheduledCompletionLatencyNs)
        assertEquals(1, summary.accepted)
        assertEquals(1, summary.completed)
        assertEquals(1L, summary.tradeCount)
        assertEquals(1L, summary.settlementCount)
        assertEquals(20L, summary.ackRtt.p99Ns)
        assertEquals(10L, summary.completionLatency.p99Ns)
    }

    @Test
    fun `HTTP timeout과 늦은 실제 완료를 같은 요청의 두 축으로 남기고 timeout을 지연 0으로 채우지 않는다`() {
        val measurements = measurements(windowEnd = SECOND)
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 6 * SECOND, HttpPerformanceOutcome.HTTP_TIMEOUT, detail = "client timeout")
        val marker = marker()
        measurements.completionObserved(marker, 11 * SECOND)

        val summary = measurements.finish(61 * SECOND, listOf(marker))
        val sample = measurements.requests().single()

        assertTrue(summary.completionLatencyValid)
        assertEquals(HttpPerformanceOutcome.HTTP_TIMEOUT, sample.httpOutcome)
        assertNull(sample.ackNs)
        assertNull(sample.ackRttNs)
        assertEquals(0, summary.ackRtt.sampleCount)
        assertNull(summary.ackRtt.p99Ns)
        assertEquals(1, summary.httpUnknown)
        assertEquals(1, summary.httpTimeouts)
        assertEquals(1.0, summary.httpUnknownRate)
        assertEquals(0, summary.completionUnknown)
        assertEquals(1, summary.lateCompleted)
        assertEquals(11 * SECOND, summary.completionLatency.p99Ns)
    }

    @Test
    fun `나노시계 절대값이 음수여도 같은 시계의 양수 차분을 그대로 계산한다`() {
        val measurements = measurements(firstDue = -1_000, windowEnd = -500)
        measurements.requestStarted("buyer", "R1", -990)
        measurements.httpCompleted("buyer", "R1", -980, HttpPerformanceOutcome.ACCEPTED, 1)
        val marker = marker()
        measurements.completionObserved(marker, -970)

        val summary = measurements.finish(-400, listOf(marker))

        assertTrue(summary.completionLatencyValid)
        assertEquals(10L, summary.ackRtt.p50Ns)
        assertEquals(20L, summary.completionLatency.p50Ns)
    }

    @Test
    fun `같은 요청 문자열을 쓴 다른 사용자와 다른 순서의 응답을 혼동하지 않는다`() {
        val measurements = measurements(plans = listOf(plan(), plan(userId = "other", orderId = "O2")))
        measurements.requestStarted("buyer", "R1", 0)
        measurements.requestStarted("other", "R1", 1)
        val other = marker(userId = "other", orderId = "O2", sequence = 2)
        val first = marker()
        measurements.completionObserved(other, 21)
        measurements.httpCompleted("buyer", "R1", 30, HttpPerformanceOutcome.ACCEPTED, 1)
        measurements.completionObserved(first, 40)
        measurements.httpCompleted("other", "R1", 50, HttpPerformanceOutcome.ACCEPTED, 2)

        val summary = measurements.finish(2_000, listOf(first, other))
        val samples = measurements.requests().associateBy { it.userId }

        assertTrue(summary.completionLatencyValid)
        assertEquals(40L, samples.getValue("buyer").completionLatencyNs)
        assertEquals(20L, samples.getValue("other").completionLatencyNs)
        assertEquals(49L, samples.getValue("other").ackRttNs)
    }

    @Test
    fun `체결 없는 완료는 완료 주문이지만 정산 표본이나 정산 TPS가 아니다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.ACCEPTED, 1)
        val marker = marker(trades = 0, settlements = 0)
        measurements.completionObserved(marker, 20)

        val summary = measurements.finish(2_000, listOf(marker))

        assertEquals(1, summary.completed)
        assertEquals(1, summary.completionLatency.sampleCount)
        assertEquals(0, summary.settlementLatency.sampleCount)
        assertEquals(0L, summary.tradeCount)
        assertEquals(0L, summary.settlementCount)
        assertEquals(0.0, summary.settlementsObservedPerSecond)
        assertNull(summary.settlementLatency.p99Ns)
    }

    @Test
    fun `같은 표식 재수신과 팔로워 표식을 새 업무 완료로 세지 않는다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val leader = marker()
        val follower = marker(memberId = 1, role = "FOLLOWER")
        measurements.completionObserved(follower, 10)
        measurements.completionObserved(leader, 20)
        measurements.completionObserved(leader, 30)
        measurements.completionObserved(follower, 40)
        measurements.httpCompleted("buyer", "R1", 50, HttpPerformanceOutcome.ACCEPTED, 1)

        val summary = measurements.finish(2_000, listOf(leader, follower, leader))

        assertTrue(summary.completionLatencyValid)
        assertEquals(2, summary.duplicateMarkers)
        assertEquals(1, summary.completed)
        assertEquals(1L, summary.settlementCount)
        assertEquals(20L, summary.completionLatency.p50Ns)
        assertEquals(0, measurements.requests().single().completionMemberId)
    }

    @Test
    fun `같은 노드와 실행 순번의 표식 내용이 달라지면 최초 관측을 보존하고 결과를 무효로 한다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val marker = marker()
        measurements.completionObserved(marker, 10)
        measurements.completionObserved(marker.copy(settlementCount = 2), 20)

        val summary = measurements.finish(2_000, listOf(marker))

        assertFalse(summary.completionLatencyValid)
        assertTrue(summary.instrumentationErrors.any { it.startsWith("MARKER_CONTENT_CONFLICT") })
        assertEquals(0, summary.duplicateMarkers)
        assertEquals(1L, summary.settlementCount)
        assertEquals(10L, summary.completionLatency.p50Ns)
    }

    @Test
    fun `HTTP와 완료 또는 노드 사이의 다른 실행 순번은 다른 주문에 억지로 연결하지 않는다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val leader = marker()
        val follower = marker(memberId = 1, role = "FOLLOWER", sequence = 2)
        measurements.completionObserved(leader, 10)
        measurements.completionObserved(follower, 20)
        measurements.httpCompleted("buyer", "R1", 30, HttpPerformanceOutcome.ACCEPTED, 3)

        val summary = measurements.finish(2_000, listOf(leader, follower))

        assertFalse(summary.completionLatencyValid)
        assertTrue(summary.instrumentationErrors.any { it.startsWith("HTTP_COMPLETION_SEQUENCE_CONFLICT") })
    }

    @Test
    fun `원본 주문 ID와 충돌한 표식은 완료로 세지 않는다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val wrongOrder = marker(orderId = "different-order")
        measurements.completionObserved(wrongOrder, 10)

        val summary = measurements.finish(2_000, listOf(wrongOrder))

        assertFalse(summary.completionLatencyValid)
        assertEquals(0, summary.completed)
        assertEquals(1, summary.completionUnknown)
        assertTrue(summary.instrumentationErrors.any { it.startsWith("MARKER_REQUEST_IDENTITY_CONFLICT") })
    }

    @Test
    fun `리더 표본이 있어도 송신한 팔로워 표식이 누락되면 완료 지연 결과는 무효다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val leader = marker()
        val follower = marker(memberId = 1, role = "FOLLOWER")
        measurements.completionObserved(leader, 10)

        val summary = measurements.finish(2_000, listOf(leader, follower))

        assertFalse(summary.completionLatencyValid)
        assertEquals(1, summary.completed)
        assertTrue(summary.instrumentationErrors.contains("MARKER_NOT_RECEIVED:1/1"))
    }

    @Test
    fun `송신 목록이 없으면 수신 표본만으로 계측 완전성을 주장하지 않는다`() {
        val measurements = measurements()

        val summary = measurements.finish(2_000)

        assertFalse(summary.completionLatencyValid)
        assertTrue(summary.instrumentationErrors.contains("SENT_MARKER_MANIFEST_MISSING"))
    }

    @Test
    fun `미연결 표식은 제한된 버퍼에 보존하고 넘치면 계측 오류로 남긴다`() {
        val measurements = measurements(capacity = 1)
        val unknown = marker(requestId = "unknown", orderId = "unknown")
        val overflow = marker(requestId = "overflow", orderId = "overflow", sequence = 2)
        measurements.completionObserved(unknown, 10)
        measurements.completionObserved(unknown, 20)
        measurements.completionObserved(overflow, 30)

        val summary = measurements.finish(2_000, listOf(unknown, overflow))

        assertFalse(summary.completionLatencyValid)
        assertEquals(1, summary.unmatchedMarkers)
        assertEquals(1, summary.duplicateMarkers)
        assertTrue(summary.instrumentationErrors.contains("EARLY_MARKER_BUFFER_OVERFLOW"))
        assertTrue(summary.instrumentationErrors.contains("MARKER_NOT_RECEIVED:0/2"))
    }

    @Test
    fun `start 콜백보다 먼저 전달된 완료도 원시 시각을 보존하고 나중에 start와 연결한다`() {
        val measurements = measurements(capacity = 1)
        val marker = marker()
        measurements.completionObserved(marker, 100)
        measurements.requestStarted("buyer", "R1", 50)
        measurements.httpCompleted("buyer", "R1", 150, HttpPerformanceOutcome.ACCEPTED, 1)

        val summary = measurements.finish(2_000, listOf(marker))

        assertTrue(summary.completionLatencyValid)
        assertEquals(50L, summary.completionLatency.p50Ns)
        assertEquals(100L, measurements.requests().single().doneNs)
    }

    @Test
    fun `실제 수신 시각이 start보다 이른 충돌은 음수 지연으로 보고하지 않는다`() {
        val measurements = measurements()
        val marker = marker()
        measurements.completionObserved(marker, 10)
        measurements.requestStarted("buyer", "R1", 20)

        val summary = measurements.finish(2_000, listOf(marker))

        assertFalse(summary.completionLatencyValid)
        assertNull(measurements.requests().single().completionLatencyNs)
        assertEquals(0, summary.completed)
        assertEquals(10L, measurements.requests().single().doneNs)
    }

    @Test
    fun `발생기 포화와 기록하지 못한 미발송도 고정 cohort 분모에서 삭제하지 않는다`() {
        val measurements = measurements(plans = listOf(plan(), plan(requestId = "R2", orderId = "O2", dueNs = 100)))
        measurements.requestNotSent("buyer", "R1", HttpPerformanceMeasurements.GENERATOR_SATURATED, 10)

        val summary = measurements.finish(2_000, emptyList())

        assertTrue(summary.completionLatencyValid)
        assertEquals(2, summary.planned)
        assertEquals(0, summary.started)
        assertEquals(2, summary.unsent)
        assertEquals(1, summary.generatorSaturated)
        assertEquals(2, summary.completionUnknown)
        assertEquals(1.0, summary.completionUnknownRate)
        assertEquals(2, summary.generatorDelay.unknownCount)
        assertEquals(0, summary.generatorDelay.sampleCount)
        assertEquals("NOT_STARTED", measurements.requests()[1].notSentReason)
    }

    @Test
    fun `업무 거절 HTTP 오류 미확인과 아직 응답 없는 요청을 분리한다`() {
        val plans = (1..4).map { plan(requestId = "R$it", orderId = "O$it") }
        val measurements = measurements(plans = plans)
        plans.forEach { measurements.requestStarted(it.userId, it.requestId, 0) }
        measurements.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.BUSINESS_REJECTED, detail = "INSUFFICIENT_FUNDS")
        measurements.httpCompleted("buyer", "R2", 20, HttpPerformanceOutcome.HTTP_ERROR, detail = "500")
        measurements.httpCompleted("buyer", "R3", 30, HttpPerformanceOutcome.HTTP_UNKNOWN, detail = "504")

        val summary = measurements.finish(2_000, emptyList())

        assertTrue(summary.completionLatencyValid)
        assertEquals(1, summary.businessRejected)
        assertEquals(1, summary.httpErrors)
        assertEquals(1, summary.httpUnknown)
        assertEquals(1, summary.httpUnfinished)
        assertEquals(0.5, summary.httpUnknownRate)
        assertEquals(0, summary.ackRtt.sampleCount)
        assertEquals(4, summary.ackRtt.cohortSize)
    }

    @Test
    fun `측정창과 cohort 배출 처리량은 실제 관측 시각과 각자의 분모를 쓴다`() {
        val plans = (1..3).map { plan(requestId = "R$it", orderId = "O$it", dueNs = (it - 1) * SECOND / 2) }
        val measurements = measurements(plans = plans, windowEnd = 2 * SECOND)
        val markers = plans.mapIndexed { index, plan -> marker(requestId = plan.requestId, orderId = plan.orderId, sequence = index + 1L) }
        val ackTimes = listOf(SECOND / 10, 2 * SECOND, 21 * SECOND / 10)
        val doneTimes = listOf(SECOND / 5, 18 * SECOND / 10, 22 * SECOND / 10)
        plans.forEachIndexed { index, plan ->
            measurements.requestStarted(plan.userId, plan.requestId, plan.dueNs)
            measurements.httpCompleted(plan.userId, plan.requestId, ackTimes[index], HttpPerformanceOutcome.ACCEPTED, index + 1L)
            measurements.completionObserved(markers[index], doneTimes[index])
        }

        val summary = measurements.finish(62 * SECOND, markers)

        assertTrue(summary.completionLatencyValid)
        assertEquals(1, summary.acceptedResponseWindowCount)
        assertEquals(2, summary.completionWindowCount)
        assertEquals(2L, summary.settlementWindowCount)
        assertEquals(0.5, summary.acceptedResponsesPerSecond)
        assertEquals(1.0, summary.settlementsObservedPerSecond)
        assertEquals(22 * SECOND / 10, summary.cohortDurationNs)
        assertEquals(3.0 / 2.2, summary.cohortCompletedOrdersPerSecond, 0.000001)
        assertEquals(60 * SECOND, summary.drainDurationNs)
        assertEquals(1.0, summary.drainCompletionRate)
    }

    @Test
    fun `cohort가 미완료면 마지막 성공만으로 배출 분모를 짧게 만들지 않는다`() {
        val measurements = measurements(plans = listOf(plan(), plan(requestId = "R2", orderId = "O2")), windowEnd = SECOND)
        measurements.requestStarted("buyer", "R1", 0)
        measurements.requestStarted("buyer", "R2", 0)
        val marker = marker()
        measurements.completionObserved(marker, SECOND / 2)

        val summary = measurements.finish(61 * SECOND, listOf(marker))

        assertEquals(61 * SECOND, summary.cohortDurationNs)
        assertEquals(1.0 / 61, summary.cohortCompletedOrdersPerSecond, 0.000001)
        assertEquals(0.5, summary.drainCompletionRate)
        assertEquals(0.5, summary.completionUnknownRate)
    }

    @Test
    fun `고정 drain 뒤의 완료도 송수신 대조는 통과하지만 성공 지연과 처리량 표본으로 채우지 않는다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.ACCEPTED, 1)
        val marker = marker()
        measurements.completionObserved(marker, 2_001)

        val summary = measurements.finish(2_000, listOf(marker))
        val sample = measurements.requests().single()

        assertTrue(summary.completionLatencyValid)
        assertTrue(summary.instrumentationErrors.isEmpty())
        assertEquals(2_001L, sample.doneNs)
        assertEquals(1L, sample.settlementCount)
        assertEquals("AFTER_DRAIN", sample.completionTimeliness)
        assertNull(sample.completionLatencyNs)
        assertNull(sample.scheduledCompletionLatencyNs)
        assertEquals(0, summary.completed)
        assertEquals(1, summary.afterDrainCompleted)
        assertEquals(1, summary.completionUnknown)
        assertEquals(1.0, summary.completionUnknownRate)
        assertEquals(0, summary.completionLatency.sampleCount)
        assertNull(summary.completionLatency.p99Ns)
        assertEquals(2_000L, summary.completionLatency.unknownObservationLowerBoundNs)
        assertEquals(0L, summary.settlementCount)
        assertEquals(0.0, summary.completedOrdersObservedPerSecond)
        assertEquals(0.0, summary.cohortCompletedOrdersPerSecond)
        assertEquals(2_000L, summary.cohortDurationNs)
    }

    @Test
    fun `drain 뒤에 받은 팔로워 표식도 실제 송수신 완전성 대조에 포함한다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val leader = marker()
        val follower = marker(memberId = 1, role = "FOLLOWER")
        measurements.completionObserved(leader, 10)
        measurements.completionObserved(follower, 2_001)

        val summary = measurements.finish(2_000, listOf(leader, follower))

        assertTrue(summary.completionLatencyValid)
        assertEquals(1, summary.completed)
        assertEquals(0, summary.afterDrainCompleted)
        assertEquals(10L, summary.completionLatency.p50Ns)
    }

    @Test
    fun `drain 뒤 HTTP 승인은 원시 응답을 남기되 cutoff 시점의 승인이나 RTT 성공 표본이 아니다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val marker = marker()
        measurements.completionObserved(marker, 100)
        measurements.httpCompleted("buyer", "R1", 2_001, HttpPerformanceOutcome.ACCEPTED, 1)

        val summary = measurements.finish(2_000, listOf(marker))
        val sample = measurements.requests().single()

        assertTrue(summary.completionLatencyValid)
        assertEquals(HttpPerformanceOutcome.ACCEPTED, sample.httpOutcome)
        assertEquals(2_001L, sample.ackNs)
        assertEquals(2_001L, sample.httpResultNs)
        assertEquals("AFTER_DRAIN", sample.httpTimeliness)
        assertNull(sample.ackRttNs)
        assertNull(sample.scheduledAckLatencyNs)
        assertEquals(0, summary.accepted)
        assertEquals(1, summary.httpAfterDrain)
        assertEquals(1, summary.httpUnfinished)
        assertEquals(1.0, summary.httpUnknownRate)
        assertEquals(0, summary.ackRtt.sampleCount)
        assertEquals(1, summary.ackRtt.unknownCount)
        assertEquals(1, summary.completed)
        assertEquals(100L, summary.completionLatency.p99Ns)
    }

    @Test
    fun `고정 drain 경계와 같은 시각의 HTTP 응답과 완료는 관측된 표본에 포함한다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        val marker = marker()
        measurements.completionObserved(marker, 2_000)
        measurements.httpCompleted("buyer", "R1", 2_000, HttpPerformanceOutcome.ACCEPTED, 1)

        val summary = measurements.finish(2_000, listOf(marker))

        assertTrue(summary.completionLatencyValid)
        assertEquals(1, summary.accepted)
        assertEquals(1, summary.completed)
        assertEquals(0, summary.httpAfterDrain)
        assertEquals(0, summary.afterDrainCompleted)
        assertEquals("WITHIN_DRAIN", measurements.requests().single().httpTimeliness)
        assertEquals(2_000L, summary.ackRtt.p99Ns)
        assertEquals(2_000L, summary.completionLatency.p99Ns)
    }

    @Test
    fun `성공 표본 분위수는 조건부 값과 표본수를 내고 누락이 p99를 가리면 censor와 하한을 낸다`() {
        val plans = (1..100).map { plan(requestId = "R$it", orderId = "O$it") }
        val measurements = measurements(plans = plans)
        val markers = mutableListOf<HttpPerformanceCompletion>()
        plans.forEachIndexed { index, plan ->
            measurements.requestStarted(plan.userId, plan.requestId, 0)
            if (index < 98) {
                measurements.httpCompleted(plan.userId, plan.requestId, index + 1L, HttpPerformanceOutcome.ACCEPTED, index + 1L)
                val marker = marker(requestId = plan.requestId, orderId = plan.orderId, sequence = index + 1L)
                markers.add(marker)
                measurements.completionObserved(marker, index + 1L)
            } else {
                measurements.httpCompleted(plan.userId, plan.requestId, 500, HttpPerformanceOutcome.HTTP_TIMEOUT)
            }
        }

        val summary = measurements.finish(2_000, markers)
        val latency = summary.completionLatency

        assertEquals(98, latency.sampleCount)
        assertEquals(100, latency.cohortSize)
        assertEquals(2, latency.unknownCount)
        assertEquals(0.02, latency.unknownRate)
        assertEquals(49L, latency.p50Ns)
        assertEquals(94L, latency.p95Ns)
        assertEquals(98L, latency.p99Ns)
        assertEquals(98L, latency.maxNs)
        assertFalse(latency.p95Censored)
        assertTrue(latency.p99Censored)
        assertEquals(97L, latency.populationP99LowerBoundNs)
        assertEquals(2_000L, latency.unknownObservationLowerBoundNs)
    }

    @Test
    fun `다른 실행 표식은 이번 cohort의 중복 누락이나 지연에 섞지 않는다`() {
        val measurements = measurements()
        val otherRun = marker().copy(runId = "other-run")
        measurements.completionObserved(otherRun, 10)

        val summary = measurements.finish(2_000, listOf(otherRun))

        assertTrue(summary.completionLatencyValid)
        assertEquals(0, summary.completed)
        assertEquals(0, summary.unmatchedMarkers)
    }

    @Test
    fun `관찰 송신 실패는 업무 거절로 바꾸지 않고 계측 무효 사유로 남긴다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.ACCEPTED, 1)
        measurements.recordInstrumentationError("NODE_1_MARKER_SEND_FAILED")

        val summary = measurements.finish(2_000, emptyList())

        assertFalse(summary.completionLatencyValid)
        assertEquals(1, summary.accepted)
        assertEquals(0, summary.businessRejected)
        assertTrue(summary.instrumentationErrors.contains("NODE_1_MARKER_SEND_FAILED"))
    }

    @Test
    fun `같은 HTTP 원본을 중복 등록하거나 측정 종료 후 표본을 바꿀 수 없다`() {
        assertFailsWith<IllegalArgumentException> { measurements(plans = listOf(plan(), plan())) }
        val measurements = measurements()
        val before = measurements.requests()
        measurements.requestStarted("buyer", "R1", 10)
        measurements.finish(2_000, emptyList())

        assertNull(before.single().startNs)
        assertEquals(10L, measurements.requests().single().startNs)
        assertFailsWith<IllegalStateException> { measurements.completionObserved(marker(), 20) }
    }

    @Test
    fun `raw 순차방문은승인 unknown 미전송의모든필드와기존표본순서를보존한다`() {
        val plans = listOf(plan(), plan(requestId = "R2", orderId = "O2"), plan(requestId = "R3", orderId = "O3"))
        val measurements = measurements(plans = plans)
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 5, HttpPerformanceOutcome.ACCEPTED, 1)
        val marker = marker()
        measurements.completionObserved(marker, 10)
        measurements.requestStarted("buyer", "R2", 1)
        measurements.httpCompleted(
            "buyer",
            "R2",
            20,
            HttpPerformanceOutcome.HTTP_UNKNOWN,
            detail = "EOFException",
            responseReceived = false,
        )
        measurements.requestNotSent("buyer", "R3", HttpPerformanceMeasurements.GENERATOR_SATURATED, 2)
        measurements.finish(2_000, listOf(marker))
        val expected = measurements.requests()
        val visited = mutableListOf<HttpPerformanceRequestSample>()
        assertEquals(3, measurements.forEachRequestSample { visited.add(it) })
        assertEquals(expected, visited)
        assertEquals(listOf("R1", "R2", "R3"), visited.map { it.requestId })
        assertEquals(HttpPerformanceOutcome.HTTP_UNKNOWN, visited[1].httpOutcome)
        assertNull(visited[1].ackNs)
        assertEquals("EOFException", visited[1].httpDetail)
        assertEquals(HttpPerformanceMeasurements.GENERATOR_SATURATED, visited[2].notSentReason)
    }

    @Test
    fun `중단된미완료cohort도현재raw를전량방문하고finish성공을꾸미지않는다`() {
        val measurements = measurements(plans = listOf(plan(), plan(requestId = "R2", orderId = "O2")))
        measurements.requestStarted("buyer", "R1", 10)
        val expected = measurements.requests()
        val visited = mutableListOf<HttpPerformanceRequestSample>()
        assertEquals(2, measurements.forEachRequestSample { visited.add(it) })
        assertEquals(expected, visited)
        assertEquals("UNKNOWN", visited[0].httpTimeliness)
        assertEquals("NOT_STARTED", visited[1].notSentReason)
        assertNull(visited[0].httpOutcome)
        // Export does not finalize the recorder or turn missing HTTP/completion observations into success.
        measurements.httpCompleted("buyer", "R1", 20, HttpPerformanceOutcome.HTTP_UNKNOWN, responseReceived = false)
        assertEquals(HttpPerformanceOutcome.HTTP_UNKNOWN, measurements.requests()[0].httpOutcome)
    }

    @Test
    fun `raw visitor예외는원래객체로전파하고나머지raw나기록기를삭제하지않는다`() {
        val measurements = measurements(plans = listOf(plan(), plan(requestId = "R2", orderId = "O2")))
        val expected = measurements.requests()
        val failure = IllegalStateException("writer failed")
        var visited = 0
        val actual =
            assertFailsWith<IllegalStateException> {
                measurements.forEachRequestSample {
                    visited++
                    throw failure
                }
            }
        assertSame(failure, actual)
        assertEquals(1, visited)
        assertEquals(expected, measurements.requests())
        assertEquals(2, measurements.forEachRequestSample { })
    }

    @Test
    fun `마지막raw visitor와입장전interrupt는성공행수대신실패와flag를보존한다`() {
        val measurements = measurements(plans = listOf(plan(), plan(requestId = "R2", orderId = "O2")))
        val expected = measurements.requests()
        val previous = Thread.interrupted()
        try {
            var visited = 0
            assertFailsWith<InterruptedException> {
                measurements.forEachRequestSample { if (++visited == 2) Thread.currentThread().interrupt() }
            }
            assertEquals(2, visited)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFailsWith<InterruptedException> { measurements.forEachRequestSample { error("must not visit while interrupted") } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previous) Thread.currentThread().interrupt()
        }
        assertEquals(expected, measurements.requests())
    }

    @Test
    fun `파일markervisitor는기존Collection과동일한전체payload대조결과를낸다`() {
        fun prepared(): HttpPerformanceMeasurements =
            measurements().also {
                it.requestStarted("buyer", "R1", 0)
                it.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.ACCEPTED, 1)
                for (member in 0..2) {
                    it.completionObserved(
                        marker(
                            memberId = member,
                            role =
                                if (member ==
                                    0
                                ) {
                                    "LEADER"
                                } else {
                                    "FOLLOWER"
                                },
                        ),
                        20 + member.toLong(),
                    )
                }
            }
        val sent = (0..2).map { marker(memberId = it, role = if (it == 0) "LEADER" else "FOLLOWER") }
        val original = prepared().finish(2_000, sent)
        val actual = prepared().finishWithSentMarkerVisitor(2_000) { visit -> sent.reversed().forEach(visit) }
        assertEquals(original, actual)
        val invalid =
            prepared().finishWithSentMarkerVisitor(2_000) { visit ->
                sent
                    .map {
                        if (it.memberId ==
                            2
                        ) {
                            it.copy(settlementCount = 2)
                        } else {
                            it
                        }
                    }.forEach(visit)
            }
        assertFalse(invalid.completionLatencyValid)
        assertTrue(invalid.instrumentationErrors.any { it.startsWith("SENT_RECEIVED_MARKER_CONFLICT:") })
    }

    @Test
    fun `파일visitor의마지막EOF실패와interrupt는finish성공으로숨기지않는다`() {
        val measurements = measurements()
        measurements.requestStarted("buyer", "R1", 0)
        measurements.httpCompleted("buyer", "R1", 10, HttpPerformanceOutcome.ACCEPTED, 1)
        measurements.completionObserved(marker(), 20)
        val failure = java.io.IOException("third source EOF/SHA failed")
        assertSame(
            failure,
            assertFailsWith<java.io.IOException> {
                measurements.finishWithSentMarkerVisitor(2_000) { visit ->
                    visit(marker())
                    throw failure
                }
            },
        )
        val previous = Thread.interrupted()
        try {
            assertFailsWith<InterruptedException> {
                measurements.finishWithSentMarkerVisitor(2_000) { visit ->
                    visit(marker())
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previous) Thread.currentThread().interrupt()
        }
        assertFalse(measurements.finish(2_000, null).completionLatencyValid)
        assertEquals(1, measurements.requests().size)
    }

    @Test
    fun `세노드raw는첫수신시각의불변projection이고리더지연은그대로유지한다`() {
        val first = Long.MAX_VALUE - 1_000
        val measurements = measurements(firstDue = first, windowEnd = first + 1_000)
        measurements.requestStarted("buyer", "R1", first)
        measurements.httpCompleted("buyer", "R1", first + 50, HttpPerformanceOutcome.ACCEPTED, 1)
        val markers = (0..2).map { marker(memberId = it, role = if (it == 0) "LEADER" else "FOLLOWER") }
        measurements.completionObserved(markers[0], first + 100)
        measurements.completionObserved(markers[1], first + 200)
        var before: HttpPerformanceSustainedRequestObservation? = null
        assertEquals(1, measurements.forEachSustainedRequestObservation { before = it })
        measurements.completionObserved(markers[2], first + 1_200)
        measurements.completionObserved(markers[0], first + 1_500)
        val summary = measurements.finish(first + 2_000, markers)
        var after: HttpPerformanceSustainedRequestObservation? = null
        assertEquals(1, measurements.forEachSustainedRequestObservation { after = it })
        assertEquals(measurements.requests().single(), requireNotNull(after).sample)
        assertEquals(first + 100, requireNotNull(after).sample.doneNs)
        assertEquals(100L, summary.completionLatency.p50Ns)
        assertEquals(first + 100, requireNotNull(after).completionCopies.member0ReceivedNs)
        assertEquals(first + 1_200, requireNotNull(after).completionCopies.allCopiesDoneNs)
        assertNull(requireNotNull(before).completionCopies.member2ReceivedNs)
        assertNull(requireNotNull(before).completionCopies.allCopiesDoneNs)
    }

    @Test
    fun `세노드projection도visitor실패와마지막interrupt를성공행수로바꾸지않는다`() {
        val measurements = measurements()
        val failure = java.io.IOException("three-copy writer failed")
        assertSame(failure, assertFailsWith<java.io.IOException> { measurements.forEachSustainedRequestObservation { throw failure } })
        val previous = Thread.interrupted()
        try {
            assertFailsWith<InterruptedException> { measurements.forEachSustainedRequestObservation { Thread.currentThread().interrupt() } }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previous) Thread.currentThread().interrupt()
        }
        var recovered: HttpPerformanceSustainedRequestObservation? = null
        assertEquals(1, measurements.forEachSustainedRequestObservation { recovered = it })
        assertEquals(measurements.requests().single(), requireNotNull(recovered).sample)
    }

    private fun measurements(
        plans: List<PlannedHttpRequest>? = null,
        firstDue: Long = 0,
        windowEnd: Long = 1_000,
        capacity: Int = 8192,
    ) = HttpPerformanceMeasurements(
        RUN_ID,
        firstDue,
        windowEnd,
        plans ?: listOf(plan(dueNs = firstDue)),
        unmatchedMarkerCapacity = capacity,
    )

    private fun plan(
        userId: String = "buyer",
        requestId: String = "R1",
        orderId: String = "O1",
        dueNs: Long = 0,
    ) = PlannedHttpRequest(userId, requestId, orderId, dueNs)

    private fun marker(
        memberId: Int = 0,
        role: String = "LEADER",
        userId: String = "buyer",
        requestId: String = "R1",
        orderId: String = "O1",
        sequence: Long = 1,
        trades: Long = 1,
        settlements: Long = 1,
    ) = HttpPerformanceCompletion(RUN_ID, memberId, role, userId, requestId, orderId, sequence, 256 * sequence, trades, settlements)

    companion object {
        private const val RUN_ID = "measurement-run"
        private const val SECOND = 1_000_000_000L
    }
}

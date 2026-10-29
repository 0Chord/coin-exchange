package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPerformanceFinalEvidenceTest {
    @Test
    fun `프로파일 실행 보고서는 일반 성능과 분리하고 계측 비용을 명시한다`() {
        val profile = httpStagePerformanceReport(emptyList(), profile = true)
        val baseline = httpStagePerformanceReport(emptyList(), profile = false)
        assertTrue(profile.contains("원인 진단 (JFR 포함)"))
        assertTrue(profile.contains("계측 비용을 포함한 원인 조사"))
        assertTrue(!profile.contains("원인별 계측은 하지 않아"))
        assertTrue(baseline.contains("구간·관측 경로 비교"))
        assertTrue(baseline.contains("운영 Gateway 계측"))
        assertTrue(!baseline.contains("개선 코드 측정"))
    }

    @Test
    fun `CPU 자원 변경은 코드 개선으로 표시하지 않고 각 행에 예산과 주기를 남긴다`() {
        val result = HttpPerformanceRunResult("cpu-run", 1000, 1, "/report", null, null, listOf("의도한 미측정 예제"))
        val options = HttpPerformanceOptions(gatewayCpuPeriodUs = 100_000, gatewayCpuCount = 8)
        val report = httpStagePerformanceReport(listOf(result), false, mapOf(result.runId to options))
        assertTrue(report.contains("CPU 자원 변경 진단"))
        assertTrue(report.contains("같은 자원의 코드 개선 수치가 아니며"))
        assertTrue(report.contains("8 CPU / 100000µs"))
        assertTrue(!report.contains("개선 코드 측정"))
    }

    @Test
    fun `계측 종료 오류가 나도 금융 상태 수집 검증과 건강 검사를 생략하지 않는다`() {
        val calls = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val (snapshot, verified) =
            collectHttpPerformanceFinalEvidence(
                errors,
                closeObservation = {
                    calls.add("observation")
                    error("missing response phase")
                },
                capture = {
                    calls.add("capture")
                    "actual-state"
                },
                verify = { state ->
                    calls.add("finance:$state")
                    true
                },
                health = {
                    calls.add("health")
                    error("observation degraded")
                },
            )
        assertEquals(listOf("observation", "capture", "finance:actual-state", "health"), calls)
        assertEquals("actual-state", snapshot)
        assertEquals(true, verified)
        assertEquals(2, errors.size)
        assertTrue(errors.first().contains("missing response phase"))
    }

    @Test
    fun `상태 수집이 실패하면 검증 성공을 만들지 않고 건강 검사와 오류 보존은 계속한다`() {
        val errors = mutableListOf<String>()
        var healthAttempted = false
        var financeAttempted = false
        val (snapshot, verified) =
            collectHttpPerformanceFinalEvidence<Unit, Boolean>(
                errors,
                closeObservation = {},
                capture = { error("node unavailable") },
                verify = {
                    financeAttempted = true
                    true
                },
                health = { healthAttempted = true },
            )
        assertNull(snapshot)
        assertNull(verified)
        assertTrue(healthAttempted)
        assertEquals(false, financeAttempted)
        assertEquals(1, errors.size)
    }
}

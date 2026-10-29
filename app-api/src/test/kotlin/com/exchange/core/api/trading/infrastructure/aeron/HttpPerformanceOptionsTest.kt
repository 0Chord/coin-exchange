package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 인자와 고정 전송 계획만 검사한다. HTTP·Aeron을 기동하거나 성능을 입증하지 않는다. */
class HttpPerformanceOptionsTest {
    @Test
    fun `동시 연결 예열은 더 긴 업무 예열에서도 같은 입력 자원을 유지한다`() {
        val base = parseHttpPerformanceOptions(arrayOf("--http-warm-connections-diagnostic"))
        val warmed = base.copy(warmup = 2000)
        assertEquals(base, warmed.copy(warmup = 200))
        assertFailsWith<IllegalArgumentException> { warmed.copy(warmup = 2001) }
        assertFailsWith<IllegalArgumentException> { warmed.copy(orders = 999) }
        assertFailsWith<IllegalArgumentException> { warmed.copy(gatewayCpuCount = 8) }
    }

    @Test
    fun `긴 예열을 선택해도 본 측정 계획에는 측정 주문만 포함한다`() {
        val options = HttpPerformanceOptions(warmup = 2000, orders = 1000, rates = listOf(5000))
        val planned = plannedHttpPerformanceRequests(options, 5000, 100L)
        assertEquals(1000, planned.size)
        assertEquals(199_800_000L, planned.last().dueNs - planned.first().dueNs)
        assertEquals(6000, HttpPerformanceMarkerPlan("warmup-test", options.warmup, options.orders).identitiesPerMember)
        assertFailsWith<IllegalArgumentException> { options.copy(warmup = 2001) }
    }

    @TempDir
    lateinit var temporary: Path

    @Test
    fun `HTTP 장기는정확한1k60만허용하고원본보존은같은명시flag로만켠다`() {
        val options = parseHttpPerformanceOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60"))
        assertTrue(options.sustained)
        assertEquals(60_000, options.orders)
        assertEquals(200, options.warmup)
        assertEquals(listOf(1000), options.rates)
        assertEquals(1024, options.maxInFlight)
        assertEquals(1, options.repetitions)
        assertEquals(60L, options.drainSeconds)
        assertFalse(options.preserveNodeRecordings)
        assertFalse(options.detailedTracing)
        assertFalse(options.stageDiagnostics)
        assertFalse(options.diagnosticProfiles)
        assertEquals(60_000_000_000L, httpPerformanceWindowNs(options, 1000))
        val preserved = parseHttpPerformanceOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60", "--preserve-node-recordings"))
        assertEquals(options.copy(preserveNodeRecordings = true), preserved)
        val plans = plannedHttpPerformanceRequests(options, 1000, Long.MAX_VALUE - 123)
        assertEquals(60_000, plans.size)
        assertEquals(59_999_000_000L, plans.last().dueNs - plans.first().dueNs)
    }

    @Test
    fun `HTTP 장기flag는rate기간자원상세진단과기존모드guard를우회하지못한다`() {
        val options = parseHttpPerformanceOptions(arrayOf("--sustained", "--rate=1000", "--seconds=60"))
        for (args in listOf(
            arrayOf("--sustained", "--rate=2000", "--seconds=60"),
            arrayOf("--sustained", "--rate=1000", "--seconds=61"),
            arrayOf("--sustained", "--rate=1000", "--seconds=60", "--other"),
            arrayOf("--smoke", "--preserve-node-recordings"),
            arrayOf("--sustained", "--seconds=60", "--rate=1000"),
            arrayOf("--preserve-node-recordings"),
        )) {
            assertFailsWith<IllegalArgumentException> { parseHttpPerformanceOptions(args) }
        }
        assertFailsWith<IllegalArgumentException> { options.copy(maxInFlight = 4096) }
        assertFailsWith<IllegalArgumentException> { options.copy(orders = 120_000) }
        assertFailsWith<IllegalArgumentException> { options.copy(warmup = 1) }
        assertFailsWith<IllegalArgumentException> { options.copy(repetitions = 2) }
        assertFailsWith<IllegalArgumentException> { options.copy(detailedTracing = true) }
        assertFailsWith<IllegalArgumentException> { options.copy(diagnosticProfiles = true) }
        assertFailsWith<IllegalArgumentException> { options.copy(stageDiagnostics = true) }
        assertFailsWith<IllegalArgumentException> { options.copy(gatewayCpuPeriodUs = 100_000) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(orders = 60_000) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(preserveNodeRecordings = true) }
    }

    @Test
    fun `connection warmup flag만 기존 boundary 진단의 모든 옵션을 유지하며 추가 예열을 켠다`() {
        val original = parseHttpPerformanceOptions(arrayOf("--http-boundary-diagnostic"))
        val warm = parseHttpPerformanceOptions(arrayOf("--http-warm-connections-diagnostic"))

        assertTrue(warm.httpWarmConnectionsDiagnostic)
        assertEquals(original, warm.copy(httpWarmConnectionsDiagnostic = false))
        for (args in listOf(
            emptyArray(),
            arrayOf("--smoke"),
            arrayOf("--http-boundary-smoke"),
            arrayOf("--http-boundary-diagnostic"),
            arrayOf("--http-overload-diagnostic"),
            arrayOf("--capacity-probe"),
        )) {
            assertFalse(parseHttpPerformanceOptions(args).httpWarmConnectionsDiagnostic)
        }
    }

    @Test
    fun `connection warmup 진단의 입력 자원 프로파일 및 계측 조건을 다른 옵션으로 바꾸지 못한다`() {
        val warm = parseHttpPerformanceOptions(arrayOf("--http-warm-connections-diagnostic"))
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(httpWarmConnectionsDiagnostic = true) }
        assertFailsWith<IllegalArgumentException> { warm.copy(orders = 999) }
        assertFailsWith<IllegalArgumentException> { warm.copy(warmup = 1) }
        assertFailsWith<IllegalArgumentException> { warm.copy(rates = listOf(1000)) }
        assertFailsWith<IllegalArgumentException> { warm.copy(diagnosticProfiles = false) }
        assertFailsWith<IllegalArgumentException> { warm.copy(httpBoundaryDiagnostics = false) }
        assertFailsWith<IllegalArgumentException> { warm.copy(detailedTracing = false) }
        assertFailsWith<IllegalArgumentException> { warm.copy(maxInFlight = 1000) }
        assertFailsWith<IllegalArgumentException> { warm.copy(gatewayCpuPeriodUs = 10_000) }
        assertFailsWith<IllegalArgumentException> { warm.copy(markerIdlePolicy = HttpPerformanceMarkerIdlePolicy.BACKOFF) }
        assertFailsWith<IllegalArgumentException> {
            parseHttpPerformanceOptions(arrayOf("--http-boundary-diagnostic", "--http-warm-connections-diagnostic"))
        }
    }

    @Test
    fun `HTTP 경계 진단은 명시한 작은 cohort만 관찰하고 기본 실행에는 추가하지 않는다`() {
        val diagnostic = parseHttpPerformanceOptions(arrayOf("--http-boundary-diagnostic"))
        assertTrue(diagnostic.httpBoundaryDiagnostics)
        assertTrue(diagnostic.stageDiagnostics)
        assertTrue(diagnostic.diagnosticProfiles)
        assertTrue(diagnostic.optimizedProduct)
        assertEquals(1000, diagnostic.orders)
        assertEquals(listOf(5000), diagnostic.rates)
        assertEquals(1024, diagnostic.maxInFlight)
        assertEquals(2, diagnostic.gatewayCpuCount)
        assertNull(diagnostic.gatewayCpuPeriodUs)
        val smoke = parseHttpPerformanceOptions(arrayOf("--http-boundary-smoke"))
        assertTrue(smoke.httpBoundaryDiagnostics)
        assertFalse(smoke.diagnosticProfiles)
        assertEquals(2, smoke.orders)
        assertEquals(1, smoke.warmup)
        assertFalse(parseHttpPerformanceOptions(emptyArray()).httpBoundaryDiagnostics)
        assertFalse(parseHttpPerformanceOptions(arrayOf("--capacity-probe")).httpBoundaryDiagnostics)
        assertFalse(parseHttpPerformanceOptions(arrayOf("--http-overload-diagnostic")).httpBoundaryDiagnostics)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(httpBoundaryDiagnostics = true) }
    }

    @Test
    fun `HTTP 과부하 진단은 기존 발생기 예산 안의 한 cohort와 상세 프로파일을 분리한다`() {
        val options = parseHttpPerformanceOptions(arrayOf("--http-overload-diagnostic"))

        assertFalse(options.capacityProbe)
        assertTrue(options.optimizedProduct)
        assertEquals(listOf(5000), options.rates)
        assertEquals(1000, options.orders)
        assertEquals(200, options.warmup)
        assertEquals(1, options.repetitions)
        assertEquals(1024, options.maxInFlight)
        assertTrue(options.orders < options.maxInFlight)
        assertTrue(options.diagnosticProfiles)
        assertTrue(options.stageDiagnostics)
        assertTrue(options.detailedTracing)
        assertEquals(200_000_000L, httpPerformanceWindowNs(options, 5000))
        assertEquals(2, options.gatewayCpuCount)
        assertNull(options.gatewayCpuPeriodUs)
    }

    @Test
    fun `명시한 capacity probe만 기존 자원으로 네 rate의 유한한 burst를 계획한다`() {
        val options = parseHttpPerformanceOptions(arrayOf("--capacity-probe"))

        assertTrue(options.capacityProbe)
        assertTrue(options.optimizedProduct)
        assertEquals(listOf(1000, 5000, 10_000, 20_000), options.rates)
        assertEquals(200, options.warmup)
        assertEquals(2000, options.orders)
        assertEquals(1, options.repetitions)
        assertEquals(1024, options.maxInFlight)
        assertEquals(60L, options.drainSeconds)
        assertFalse(options.diagnosticProfiles)
        assertFalse(options.stageDiagnostics)
        assertFalse(options.detailedTracing)
        assertEquals(HttpPerformanceMarkerIdlePolicy.SLEEP_1MS, options.markerIdlePolicy)
        assertEquals(2, options.gatewayCpuCount)
        assertNull(options.gatewayCpuPeriodUs)
    }

    @Test
    fun `burst 예정 창은 drain이 아니라 N을 rate로 나눈 시각이며 요청을 모두 그 안에 둔다`() {
        val options = parseHttpPerformanceOptions(arrayOf("--capacity-probe"))
        val expectedWindows = mapOf(1000 to 2_000_000_000L, 5000 to 400_000_000L, 10_000 to 200_000_000L, 20_000 to 100_000_000L)
        val firstDue = -3_000_000_000L

        for ((rate, expectedWindow) in expectedWindows) {
            val plans = plannedHttpPerformanceRequests(options, rate, firstDue)

            assertEquals(expectedWindow, httpPerformanceWindowNs(options, rate))
            assertEquals(2000, plans.size)
            assertEquals(2000, plans.map { it.requestId }.distinct().size)
            assertEquals(firstDue, plans.first().dueNs)
            assertEquals(firstDue + 1999L * 1_000_000_000L / rate, plans.last().dueNs)
            assertTrue(plans.zipWithNext().all { (previous, next) -> previous.dueNs < next.dueNs })
            assertTrue(plans.all { it.dueNs >= firstDue && it.dueNs < firstDue + expectedWindow })
            assertTrue(plans.all { it.userId == "BUYER" && it.orderId == it.requestId })
        }
    }

    @Test
    fun `일반 모드의 rate와 주문 상한을 늘리거나 probe 조건을 바꿀 수 없다`() {
        assertEquals(listOf(10_000), HttpPerformanceOptions(rates = listOf(10_000)).rates)
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(rates = listOf(20_000)) }
        assertFailsWith<IllegalArgumentException> { HttpPerformanceOptions(orders = 2001) }
        val probe = parseHttpPerformanceOptions(arrayOf("--capacity-probe"))
        assertFailsWith<IllegalArgumentException> { probe.copy(rates = listOf(20_001)) }
        assertFailsWith<IllegalArgumentException> { probe.copy(orders = 2001) }
        assertFailsWith<IllegalArgumentException> { probe.copy(detailedTracing = true) }
        assertFailsWith<IllegalArgumentException> { probe.copy(diagnosticProfiles = true) }
        assertFailsWith<IllegalArgumentException> { probe.copy(stageDiagnostics = true) }
        assertFailsWith<IllegalArgumentException> { probe.copy(gatewayCpuPeriodUs = 10_000) }
        assertFailsWith<IllegalArgumentException> { httpPerformanceWindowNs(HttpPerformanceOptions(), 20_000) }
    }

    @Test
    fun `기존 일반 smoke diagnostic optimized 인자의 옵션을 유지한다`() {
        assertEquals(HttpPerformanceOptions(), parseHttpPerformanceOptions(emptyArray()))
        assertEquals(HttpPerformanceOptions(1, 2, listOf(10), 1), parseHttpPerformanceOptions(arrayOf("--smoke")))
        assertEquals(
            HttpPerformanceOptions(1, 2, listOf(10), 1, optimizedProduct = true),
            parseHttpPerformanceOptions(arrayOf("--optimized-smoke")),
        )
        assertEquals(
            HttpPerformanceOptions(rates = listOf(50, 1000), repetitions = 1, diagnosticProfiles = true),
            parseHttpPerformanceOptions(arrayOf("--diagnostic")),
        )
        assertEquals(HttpPerformanceOptions(optimizedProduct = true), parseHttpPerformanceOptions(arrayOf("--optimized")))
        assertEquals(
            HttpPerformanceOptions(rates = listOf(50, 1000), repetitions = 1, optimizedProduct = true),
            parseHttpPerformanceOptions(arrayOf("--optimized-probe")),
        )
        assertEquals(
            HttpPerformanceOptions(rates = listOf(50, 1000), repetitions = 1, diagnosticProfiles = true, optimizedProduct = true),
            parseHttpPerformanceOptions(arrayOf("--optimized-diagnostic")),
        )
    }

    @Test
    fun `알 수 없거나 여러 개의 모드 인자는 거절한다`() {
        assertFailsWith<IllegalArgumentException> { parseHttpPerformanceOptions(arrayOf("--unknown")) }
        assertFailsWith<IllegalArgumentException> { parseHttpPerformanceOptions(arrayOf("--capacity-probe", "--diagnostic")) }
        assertFailsWith<IllegalArgumentException> { parseHttpPerformanceOptions(arrayOf("--capacity-probe", "--capacity-probe")) }
    }

    @Test
    fun `사후 소스 삭제와 배포 읽기 실패는 각각 무효로 남기고 이미 얻은 금융 측정 증거를 유지한다`() {
        val source = temporary.resolve("source.kt")
        Files.writeString(source, "original")
        val sourceBefore: Map<String, Any> = mapOf("content" to Files.readString(source))
        Files.delete(source)
        val financialEvidence = mapOf("actualMeasuredSettled" to 2000L)
        val measurementEvidence = mapOf("accepted" to 2000)
        val manifest = linkedMapOf<String, Any?>("stateCheck" to financialEvidence, "summary" to measurementEvidence)
        val errors = mutableListOf<String>()
        var distributionAttempted = false

        checkHttpPerformancePostFingerprints(
            manifest,
            errors,
            sourceBefore,
            "distribution-before",
            readSource = { mapOf("content" to Files.readString(source)) },
            readDistribution = {
                distributionAttempted = true
                throw IOException("distribution read failed")
            },
        )

        assertTrue(distributionAttempted)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.contains("사후 소스 지문 조회 실패") && it.contains("NoSuchFileException") })
        assertTrue(errors.any { it.contains("사후 배포 산출물 지문 조회 실패") && it.contains("distribution read failed") })
        assertTrue(manifest.containsKey("sourceUnchanged") && manifest.containsKey("distributionUnchanged"))
        assertNull(manifest["sourceUnchanged"])
        assertNull(manifest["distributionUnchanged"])
        assertEquals(financialEvidence, manifest["stateCheck"])
        assertEquals(measurementEvidence, manifest["summary"])
        assertEquals(errors[0], manifest["sourceVerificationError"])
        assertEquals(errors[1], manifest["distributionVerificationError"])
    }

    @Test
    fun `사후 지문을 읽었지만 내용이 바뀌면 기존 변경 감지를 그대로 무효 사유로 남긴다`() {
        val manifest = linkedMapOf<String, Any?>()
        val errors = mutableListOf<String>()

        checkHttpPerformancePostFingerprints(
            manifest,
            errors,
            mapOf("content" to "before"),
            "distribution-before",
            readSource = { mapOf("content" to "after") },
            readDistribution = { "distribution-after" },
        )

        assertEquals(false, manifest["sourceUnchanged"])
        assertEquals(false, manifest["distributionUnchanged"])
        assertEquals(listOf("실행 중 소스 지문 변경", "실행 중 배포 산출물 변경"), errors)
        assertFalse(manifest.containsKey("sourceVerificationError"))
        assertFalse(manifest.containsKey("distributionVerificationError"))
    }

    @Test
    fun `사후 지문을 모두 읽고 같은 내용일 때만 두 unchanged를 확인한다`() {
        val manifest = linkedMapOf<String, Any?>()
        val errors = mutableListOf<String>()
        val source: Map<String, Any> = mapOf("content" to "unchanged")

        checkHttpPerformancePostFingerprints(
            manifest,
            errors,
            source,
            "distribution",
            readSource = { source },
            readDistribution = { "distribution" },
        )

        assertEquals(true, manifest["sourceUnchanged"])
        assertEquals(true, manifest["distributionUnchanged"])
        assertTrue(errors.isEmpty())
    }
}

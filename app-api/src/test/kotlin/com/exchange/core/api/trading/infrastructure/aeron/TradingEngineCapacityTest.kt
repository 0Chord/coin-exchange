package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradingEngineCapacityTest {
    @Test
    fun `중단 신호가 있어도 실패 상태를 파일에 보존하고 중단 신호는 되돌린다`(
        @TempDir directory: Path,
    ) {
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 1, orders = 2),
                beforeMeasuredExecution = { error("test-only execution failure") },
            )
        val output = directory.resolve("interrupted.json")
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            writeEngineCapacityReport(output, mapOf("runs" to listOf(result)))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        val run = jacksonObjectMapper().readTree(output.toFile()).path("runs").first()
        assertEquals("MEASUREMENT_FAILURE", run.path("stopReason").asString())
        assertEquals(
            7,
            run
                .path("failureStateEvidence")
                .path("snapshot")
                .path("ledgerTransactions")
                .size(),
        )
    }

    @Test
    fun `보고서는 기존 파일을 덮어쓰지 않고 쓰기 실패에도 중단 신호를 되돌린다`(
        @TempDir directory: Path,
    ) {
        val output = directory.resolve("existing.json")
        Files.writeString(output, "preserved")
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<FileAlreadyExistsException> { writeEngineCapacityReport(output, mapOf("runs" to emptyList<Any>())) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertEquals("preserved", Files.readString(output))
    }

    @Test
    fun `한 BUY의 실제 예약 매칭 수수료 정산 원장을 모두 실행하고 중복과 무자금 거절을 검증한다`() {
        val result = runTradingEngineCapacity(TradingEngineCapacityOptions(warmup = 2, orders = 3))

        assertEquals("ORDER_LIMIT", result.stopReason)
        assertTrue(result.fixedCohortComplete, result.errors.joinToString("\n"))
        assertFalse(result.sustainedWindowComplete)
        assertEquals(5, result.preparationSells)
        assertEquals(2, result.warmupCompleted)
        assertEquals(3, result.measuredAttempts)
        assertEquals(3, result.measuredCompleted)
        assertEquals(3L, result.latency.count)
        assertTrue(result.elapsedNs >= result.latency.totalNs)
        assertEquals(3_000_000_000.0 / result.elapsedNs, result.completedTransactionsPerSecond)
        val state = assertNotNull(result.stateCheck)
        assertEquals(5L, state["actualSettlements"])
        assertEquals(150L, state["expectedFeeRevenue"])
        assertEquals(17, state["ledgerTransactions"])
        assertEquals(11, state["requests"])
        assertEquals(10, state["completions"])
        assertEquals(true, state["duplicateAdmissionAndExecutionVerified"])
        assertEquals(true, state["insufficientBalanceVerified"])
        assertEquals(true, state["ledgerBalancedByAsset"])
        assertEquals("BASELINE", result.measurementMode)
        assertFalse(result.profiled)
        assertEquals(false, result.runtime["testOnlyFailureInjectionEnabled"])
        val cpuNs = result.runtime["currentThreadCpuTimeNs"] as? Long
        if (cpuNs == null) {
            assertNotNull(result.runtime["cpuTimeUnavailableReason"])
            assertNull(result.runtime["currentThreadCpuTimeNsPerAttempt"])
        } else {
            assertTrue(cpuNs >= 0)
            assertEquals(cpuNs / 3.0, result.runtime["currentThreadCpuTimeNsPerAttempt"])
            assertNull(result.runtime["cpuTimeUnavailableReason"])
        }
        assertTrue(result.errors.isEmpty())
        assertNull(result.failureStateEvidence)
    }

    @Test
    fun `history 선택은 기본 off와 rebuildable만 허용하고 기존 계획을 유지한다`() {
        val defaults = engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=3", "--warmup=2")).single()
        val off = engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=3", "--warmup=2", "--history=off")).single()
        val rebuildable =
            engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=3", "--warmup=2", "--history=rebuildable")).single()

        assertEquals("off", defaults.historyMode)
        assertEquals(defaults, off)
        assertEquals(defaults.copy(historyMode = "rebuildable"), rebuildable)
        assertFailsWith<IllegalArgumentException> { engineCapacityOptionsFromArgs(arrayOf("--smoke", "--history=durable")) }
        assertFailsWith<IllegalArgumentException> { TradingEngineCapacityOptions(historyMode = "") }
    }

    @Test
    fun `rebuildable 선택은 실제 금융 검증과 cold 중복 검증을 유지하고 임시 이력을 정리한다`() {
        val result = runTradingEngineCapacity(TradingEngineCapacityOptions(warmup = 2, orders = 3, historyMode = "rebuildable"))

        assertTrue(result.fixedCohortComplete, result.errors.joinToString("\n"))
        assertEquals(3L, result.latency.count)
        val state = assertNotNull(result.stateCheck)
        assertEquals(5L, state["actualSettlements"])
        assertEquals(150L, state["expectedFeeRevenue"])
        assertEquals(17, state["ledgerTransactions"])
        assertEquals(11, state["requests"])
        assertEquals(10, state["completions"])
        assertEquals(true, state["duplicateAdmissionAndExecutionVerified"])
        assertEquals(true, state["insufficientBalanceVerified"])
        assertEquals(true, state["ledgerBalancedByAsset"])
        val history = result.runtime["history"] as Map<*, *>
        assertEquals("rebuildable", history["mode"])
        assertEquals(true, history["materializationFlushed"])
        assertEquals(true, history["storeClosed"])
        assertEquals(true, history["temporaryDirectoryRemoved"])
        assertEquals(false, history["durabilityClaim"])
        assertFalse(Files.exists(Path.of(history["temporaryDirectory"] as String)))
        assertTrue((result.runtime["cpuTimeBoundary"] as String).contains("not process CPU"))
        assertNull(result.failureStateEvidence)
    }

    @Test
    fun `rebuildable 측정 실패도 정리하고 다음 실행은 같은 요청 ID를 새 이력에서 처리한다`() {
        val options = TradingEngineCapacityOptions(warmup = 1, orders = 2, historyMode = "rebuildable")
        val failed = runTradingEngineCapacity(options, beforeMeasuredExecution = { error("test-only execution failure") })
        val next = runTradingEngineCapacity(options)

        assertEquals("MEASUREMENT_FAILURE", failed.stopReason)
        assertTrue(failed.errors.any { it.contains("test-only execution failure") })
        assertNotNull(failed.failureStateEvidence)
        assertTrue(next.fixedCohortComplete, next.errors.joinToString("\n"))
        val failedHistory = failed.runtime["history"] as Map<*, *>
        val nextHistory = next.runtime["history"] as Map<*, *>
        assertTrue(failedHistory["temporaryDirectory"] != nextHistory["temporaryDirectory"])
        for (history in listOf(failedHistory, nextHistory)) {
            assertEquals(true, history["freshStorePerRun"])
            assertEquals(true, history["storeClosed"])
            assertEquals(true, history["temporaryDirectoryRemoved"])
            assertFalse(Files.exists(Path.of(history["temporaryDirectory"] as String)))
        }
        assertEquals(true, next.stateCheck?.get("duplicateAdmissionAndExecutionVerified"))
    }

    @Test
    fun `선택한 측정 전 GC는 금융 준비와 완료를 유지하고 별도 진단으로 표시한다`() {
        val options = engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=3", "--warmup=2", "--pre-gc", "--post-gc")).single()
        assertTrue(options.preGc)
        assertTrue(options.postGc)
        assertFalse(engineCapacityOptionsFromArgs(arrayOf("--smoke")).single().preGc)

        val result = runTradingEngineCapacity(options)

        assertTrue(result.fixedCohortComplete, result.errors.joinToString("\n"))
        assertEquals("PRE_GC_DIAGNOSTIC", result.measurementMode)
        assertEquals(5, result.preparationSells)
        assertEquals(2, result.warmupCompleted)
        assertEquals(3, result.measuredCompleted)
        val state = assertNotNull(result.stateCheck)
        assertEquals(5L, state["actualSettlements"])
        assertEquals(150L, state["expectedFeeRevenue"])
        assertEquals(17, state["ledgerTransactions"])
        assertEquals(true, state["duplicateAdmissionAndExecutionVerified"])
        assertEquals(true, state["ledgerBalancedByAsset"])
        val before = assertNotNull(result.runtime["preGcDiagnostic"] as? Map<*, *>)
        val after = assertNotNull(result.runtime["postGcDiagnostic"] as? Map<*, *>)
        assertTrue((before["scope"] as String).startsWith("after preparation and warmup, before JFR"))
        assertTrue((after["scope"] as String).startsWith("after measurement"))
        // Explicit GC can be disabled: report what was observed rather than assuming it completed.
        assertEquals((before["gcCollectionCountDelta"] as Long) > 0, before["collectionCountIncreased"])
    }

    @Test
    fun `heap 상한에서 준비가 멈추면 측정 성공이나 지속 처리량을 주장하지 않고 실제 부분 상태를 검증한다`() {
        var checks = 0
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 1, orders = 129),
                heapUsedBytes = { if (++checks < 2) 10L else 70L },
                maxHeapBytes = 100,
            )

        assertEquals("PREPARATION_HEAP_LIMIT", result.stopReason)
        assertEquals(128, result.preparationSells)
        assertEquals(0, result.measuredAttempts)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        assertNull(result.completedTransactionsPerSecond)
        assertNull(result.latency.p99UpperBoundNs)
        assertNotNull(result.stateCheck, result.errors.joinToString("\n"))
        assertEquals(0L, result.stateCheck["actualSettlements"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
    }

    @Test
    fun `예열 128건 뒤 heap 중단은 예열 이력을 보존하고 측정 성공으로 표시하지 않는다`() {
        var checks = 0
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 129, orders = 1),
                heapUsedBytes = { if (++checks < 4) 10L else 70L },
                maxHeapBytes = 100,
            )

        assertEquals("WARMUP_HEAP_LIMIT", result.stopReason)
        assertEquals(130, result.preparationSells)
        assertEquals(128, result.warmupCompleted)
        assertEquals(0, result.measuredAttempts)
        assertEquals(0, result.measuredCompleted)
        assertEquals(0L, result.latency.count)
        assertNull(result.completedTransactionsPerSecond)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        val state = assertNotNull(result.stateCheck, result.errors.joinToString("\n"))
        assertEquals(128L, state["actualSettlements"])
        assertEquals(3840L, state["expectedFeeRevenue"])
        assertEquals(388, state["ledgerTransactions"])
        assertEquals(258, state["completions"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
    }

    @Test
    fun `측정 128건 뒤 heap 중단은 모든 측정 표본과 부분 정산 상태를 보존한다`() {
        var checks = 0
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 1, orders = 129),
                heapUsedBytes = { if (++checks < 6) 10L else 70L },
                maxHeapBytes = 100,
            )

        assertEquals("MEASUREMENT_HEAP_LIMIT", result.stopReason)
        assertEquals(130, result.preparationSells)
        assertEquals(1, result.warmupCompleted)
        assertEquals(128, result.measuredAttempts)
        assertEquals(128, result.measuredCompleted)
        assertEquals(128L, result.latency.count)
        assertNotNull(result.completedTransactionsPerSecond)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        val state = assertNotNull(result.stateCheck, result.errors.joinToString("\n"))
        assertEquals(129L, state["actualSettlements"])
        assertEquals(3870L, state["expectedFeeRevenue"])
        assertEquals(390, state["ledgerTransactions"])
        assertEquals(259, state["completions"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
    }

    @Test
    fun `두 번째 측정 시도의 예외도 지연 표본에 포함하고 실패와 상태검사 오류를 숨기지 않는다`() {
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 3),
                beforeMeasuredExecution = { index -> check(index != 1) { "test-only measured execution failure" } },
            )

        assertEquals("MEASUREMENT_FAILURE", result.stopReason)
        assertEquals(2, result.measuredAttempts)
        assertEquals(1, result.measuredCompleted)
        assertEquals(result.measuredCompleted + 1, result.measuredAttempts)
        assertEquals(result.measuredAttempts.toLong(), result.latency.count)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        assertNull(result.stateCheck)
        assertTrue(result.errors.any { "test-only measured execution failure" in it })
        assertTrue(result.errors.any { "state verification:" in it && "a measured BUY was not completed" in it })
        assertEquals(true, result.runtime["testOnlyFailureInjectionEnabled"])
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(false, evidence["financialVerificationPassed"])
        assertEquals(false, evidence["isBlocked"])
        assertNotNull(evidence["snapshot"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
        assertNotNull(evidence["book"])
        assertEquals(emptyList<String>(), evidence["captureErrors"])
    }

    @Test
    fun `예열 뒤 첫 측정 오류도 실제 잔고 예약 원장과 주문장을 JSON 원본으로 보존한다`() {
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 1, orders = 2),
                beforeMeasuredExecution = { error("measured operation failed before admission") },
            )

        assertEquals("MEASUREMENT_FAILURE", result.stopReason)
        assertEquals(1, result.measuredAttempts)
        assertEquals(0, result.measuredCompleted)
        assertNull(result.stateCheck)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        val mapper = jacksonObjectMapper()
        val json = mapper.readTree(mapper.writeValueAsString(result)).path("failureStateEvidence")
        assertFalse(json.path("financialVerificationPassed").asBoolean())
        assertFalse(json.path("isBlocked").asBoolean())
        assertFalse(json.has("snapshotOmittedReason"))
        val snapshot = json.path("snapshot")
        assertEquals(4, snapshot.path("balances").size())
        assertEquals(2, snapshot.path("preparations").size())
        assertEquals(4, snapshot.path("reservations").size())
        assertEquals(7, snapshot.path("ledgerTransactions").size())
        assertEquals(5, snapshot.path("requests").size())
        assertEquals(4, snapshot.path("completions").size())
        assertEquals(2, json.path("book").size())
        assertTrue(json.path("captureErrors").isEmpty)
    }

    @Test
    fun `JFR는 절대 경로와 단일 cohort로만 요청하고 일반 실행에는 활성화하지 않는다`() {
        val output = Path.of(System.getProperty("java.io.tmpdir"), "engine-capacity-test.jfr").toAbsolutePath().toString()
        val options = engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=10", "--jfr=$output")).single()
        assertEquals(output, options.jfrPath)
        assertEquals(10, options.orders)
        assertEquals(10_000, options.warmup)
        assertNull(engineCapacityOptionsFromArgs(arrayOf("--smoke")).single().jfrPath)
        assertFailsWith<IllegalArgumentException> {
            engineCapacityOptionsFromArgs(arrayOf("--smoke", "--jfr=relative.jfr"))
        }
        assertFailsWith<IllegalArgumentException> {
            engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=10,20", "--jfr=$output"))
        }
    }

    @Test
    fun `기존 JFR 파일은 덮어쓰지 않고 profile 시작 실패를 정상 성능과 구분한다`(
        @TempDir directory: Path,
    ) {
        val output = directory.resolve("existing.jfr")
        Files.writeString(output, "preserved")
        val result = runTradingEngineCapacity(TradingEngineCapacityOptions(warmup = 0, orders = 1, jfrPath = output.toString()))

        assertEquals("PROFILE_START_FAILURE", result.stopReason)
        assertEquals("JFR_PROFILE", result.measurementMode)
        assertFalse(result.profiled)
        assertEquals(0, result.measuredAttempts)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        assertTrue(result.errors.any { "JFR output already exists" in it })
        assertNotNull(result.stateCheck)
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
        assertEquals("preserved", Files.readString(output))
    }

    @Test
    fun `JFR dump 실패가 측정 128건 뒤 heap 중단 원인을 덮어쓰지 않는다`(
        @TempDir directory: Path,
    ) {
        val output = directory.resolve("blocked-dump.jfr")
        var heapChecks = 0
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 129, jfrPath = output.toString()),
                heapUsedBytes = { if (++heapChecks < 5) 10L else 70L },
                maxHeapBytes = 100,
                beforeMeasuredExecution = { index -> if (index == 0) Files.createDirectory(output) },
            )

        assertEquals("MEASUREMENT_HEAP_LIMIT", result.stopReason)
        assertEquals(128, result.measuredAttempts)
        assertEquals(128, result.measuredCompleted)
        assertEquals(128L, result.latency.count)
        assertTrue(result.profiled)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        val jfr = assertNotNull(result.runtime["jfr"] as? Map<*, *>)
        assertEquals(true, jfr["started"])
        assertEquals(true, jfr["stopped"])
        assertEquals(false, jfr["dumped"])
        assertEquals(listOf("DUMP"), jfr["failedStages"])
        assertTrue(result.errors.any { "profile dump:" in it })
        assertTrue(result.errors.none { "state verification:" in it })
        val state = assertNotNull(result.stateCheck, result.errors.joinToString("\n"))
        assertEquals(128L, state["actualSettlements"])
        assertEquals(3840L, state["expectedFeeRevenue"])
        assertTrue(Files.isDirectory(output))
    }

    @Test
    fun `시간 모드는 주문 상한에 먼저 닿으면 지속 구간 완료가 아니다`() {
        val result = runTradingEngineCapacity(TradingEngineCapacityOptions(warmup = 0, orders = 2, durationSeconds = 60))

        assertEquals("ORDER_LIMIT", result.stopReason)
        assertEquals(2, result.measuredCompleted)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        assertNotNull(result.stateCheck, result.errors.joinToString("\n"))
    }

    @Test
    fun `요청 시간 경계가 지나면 새 거래를 시작하지 않고 남은 SELL 예약도 검증한다`() {
        var time = -2_000_000_000L
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 10, durationSeconds = 1),
                nanoTime = { time.also { time += 250_000_000 } },
            )

        assertEquals("DURATION_REACHED", result.stopReason)
        assertEquals(1, result.measuredCompleted)
        assertTrue(result.sustainedWindowComplete, result.errors.joinToString("\n"))
        assertEquals(1L, result.latency.count)
        assertEquals(250_000_000L, result.latency.totalNs)
        assertNotNull(result.stateCheck)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `paced는 명시한 rate와 seconds의 단일 cohort를 만들고 기존 heap 상한을 유지한다`() {
        val options = engineCapacityOptionsFromArgs(arrayOf("--paced", "--rate=1000", "--seconds=60")).single()

        assertEquals(1000, options.pacedRate)
        assertEquals(60L, options.durationSeconds)
        assertEquals(60_000, options.orders)
        assertEquals(10_000, options.warmup)
        assertEquals(0.65, options.maxHeapFraction)
        assertFalse(options.postGc)
        assertNull(options.jfrPath)
        assertEquals(60L, engineCapacityOptionsFromArgs(arrayOf("--paced", "--rate=1")).single().durationSeconds)
        assertEquals(
            2_000_000,
            engineCapacityOptionsFromArgs(arrayOf("--paced", "--rate=2000000", "--seconds=1", "--max-heap-fraction=0.75")).single().orders,
        )
        assertFailsWith<IllegalArgumentException> {
            engineCapacityOptionsFromArgs(arrayOf("--paced", "--rate=1000", "--max-heap-fraction=0.76"))
        }
    }

    @Test
    fun `paced는 누락 rate와 별도 orders 및 기존 이백만 상한을 벗어난 계획을 거절한다`() {
        listOf(
            arrayOf("--paced"),
            arrayOf("--paced", "--rate=0"),
            arrayOf("--paced", "--rate=-1"),
            arrayOf("--paced", "--rate=1", "--seconds=0"),
            arrayOf("--paced", "--rate=1", "--seconds=3601"),
            arrayOf("--paced", "--rate=2147483647", "--seconds=9223372036854775807"),
            arrayOf("--paced", "--rate=1000", "--orders=60000"),
            arrayOf("--paced", "--rate=33334", "--seconds=60"),
            arrayOf("--probe", "--rate=1000"),
            arrayOf("--sustain", "--rate=1000"),
            arrayOf("--smoke", "--rate=1000"),
        ).forEach { args ->
            assertFailsWith<IllegalArgumentException>(args.joinToString(" ")) { engineCapacityOptionsFromArgs(args) }
        }
        assertFailsWith<IllegalArgumentException> {
            TradingEngineCapacityOptions(warmup = 0, orders = 2, durationSeconds = 1, pacedRate = 1)
        }
    }

    @Test
    fun `paced 추가 후에도 기존 smoke probe sustain 계획은 그대로다`() {
        val smoke = engineCapacityOptionsFromArgs(arrayOf("--smoke")).single()
        assertEquals(2, smoke.warmup)
        assertEquals(4, smoke.orders)
        assertNull(smoke.durationSeconds)
        assertNull(smoke.pacedRate)
        val probe = engineCapacityOptionsFromArgs(arrayOf("--probe"))
        assertEquals(listOf(10_000, 50_000, 100_000), probe.map { it.orders })
        assertTrue(probe.all { it.warmup == 10_000 && it.durationSeconds == null && it.pacedRate == null })
        val sustain = engineCapacityOptionsFromArgs(arrayOf("--sustain")).single()
        assertEquals(100_000, sustain.orders)
        assertEquals(60L, sustain.durationSeconds)
        assertNull(sustain.pacedRate)
        assertEquals(listOf(5, 7), engineCapacityOptionsFromArgs(arrayOf("--probe", "--orders=5,7")).map { it.orders })
    }

    @Test
    fun `paced는 마지막 거래 뒤에도 예정 60초 끝까지 기다리고 대기를 업무 지연에서 제외한다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 1, orders = 60, durationSeconds = 60, pacedRate = 1),
                nanoTime = clock::read,
                beforeMeasuredExecution = { clock.advance(10) },
                parkNanos = clock::park,
            )

        assertEquals("PACED_WINDOW_REACHED", result.stopReason)
        assertTrue(result.pacedWindowComplete, result.errors.joinToString("\n"))
        assertNull(result.failureStateEvidence)
        assertFalse(result.fixedCohortComplete)
        assertFalse(result.sustainedWindowComplete)
        assertEquals(60_000_000_000L, result.elapsedNs)
        assertEquals(1.0, result.completedTransactionsPerSecond)
        assertEquals(60L, result.latency.count)
        assertEquals(600L, result.latency.totalNs)
        assertTrue(clock.parkCalls > 0)
        val paced = assertNotNull(result.paced)
        assertTrue(paced.windowReached)
        assertEquals(60, paced.startedWithinWindow)
        assertEquals(60, paced.completedWithinWindow)
        assertEquals(0, paced.completedAtOrAfterWindowEnd)
        assertEquals(0, paced.notExecuted)
        assertEquals(0, paced.failedAttempts)
        assertNull(paced.firstNotExecutedDueOffsetNs)
        assertNull(paced.lastNotExecutedDueOffsetNs)
        assertNull(paced.notExecutedDelayLowerBoundNs)
        assertEquals(0L, paced.scheduledStartDelay.totalNs)
        assertEquals(600L, paced.scheduledAttemptLatency.totalNs)
        assertEquals(61, paced.progress.size)
        assertEquals(0L, paced.progress.first().elapsedNs)
        assertEquals(60_000_000_000L, paced.progress.last().elapsedNs)
        assertEquals(60, paced.progress.last().completed)
        assertTrue(paced.progress.all { it.failedAttempts == 0 })
        assertEquals(0L, paced.progressOverflow)
        assertEquals(61, result.preparationSells)
        assertEquals(1, result.warmupCompleted)
        val state = assertNotNull(result.stateCheck)
        assertEquals(true, state["verified"])
        assertEquals(1, state["snapshotCount"])
        assertEquals(61L, state["actualSettlements"])
        assertEquals(1830L, state["expectedFeeRevenue"])
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `paced의 음수 nanoTime과 Long wrap은 절대시각 비교 없이 같은 한 초를 측정한다`() {
        listOf(-2_000_000_000L, Long.MAX_VALUE - 500_000_000L, Long.MIN_VALUE + 17).forEach { origin ->
            val clock = EngineCapacityPacedClock(origin)
            val result =
                runTradingEngineCapacity(
                    TradingEngineCapacityOptions(warmup = 0, orders = 1, durationSeconds = 1, pacedRate = 1),
                    nanoTime = clock::read,
                    beforeMeasuredExecution = { clock.advance(10) },
                    parkNanos = clock::park,
                )

            assertTrue(result.pacedWindowComplete, "origin=$origin ${result.errors}")
            assertEquals(1_000_000_000L, result.elapsedNs)
            assertEquals(10L, result.latency.totalNs)
            val paced = assertNotNull(result.paced)
            assertEquals(origin, paced.firstDueNs)
            assertEquals(0L, paced.scheduledStartDelay.totalNs)
            assertEquals(10L, paced.scheduledAttemptLatency.totalNs)
        }
    }

    @Test
    fun `예정시각은 느린 이전 호출 때문에 재설정되지 않고 창 밖 완료도 별도로 보존한다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 2, durationSeconds = 1, pacedRate = 2),
                nanoTime = clock::read,
                beforeMeasuredExecution = { clock.advance(600_000_000) },
                parkNanos = clock::park,
            )

        assertEquals("PACED_WINDOW_REACHED", result.stopReason)
        assertEquals(2, result.measuredCompleted)
        assertEquals(1_200_000_000L, result.elapsedNs)
        assertFalse(result.pacedWindowComplete)
        val paced = assertNotNull(result.paced)
        assertTrue(paced.windowReached)
        assertEquals(1, paced.completedWithinWindow)
        assertEquals(1, paced.completedAtOrAfterWindowEnd)
        assertEquals(0, paced.notExecuted)
        assertEquals(100_000_000L, paced.scheduledStartDelay.totalNs)
        assertEquals(1_300_000_000L, paced.scheduledAttemptLatency.totalNs)
        assertEquals(1_200_000_000L, result.latency.totalNs)
        assertEquals(2, paced.progress.size)
        val state = assertNotNull(result.stateCheck)
        assertEquals(true, state["verified"])
        assertEquals(1, state["snapshotCount"])
        assertEquals(true, state["duplicateAdmissionAndExecutionVerified"])
        assertEquals(true, state["ledgerBalancedByAsset"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(false, evidence["isBlocked"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshotOmittedReason"])
        assertEquals(state, evidence["stateCheck"])
        assertEquals(emptyList<String>(), evidence["captureErrors"])
        assertFalse(evidence.containsKey("snapshot"))
        assertFalse(evidence.containsKey("book"))
        val mapper = jacksonObjectMapper()
        val json = mapper.readTree(mapper.writeValueAsString(result)).path("failureStateEvidence")
        assertFalse(json.has("snapshot"))
        assertFalse(json.has("book"))
        assertEquals(1, json.path("stateCheck").path("snapshotCount").asInt())
        assertTrue(json.path("snapshotOmittedReason").asString().contains("full financial verification"))
    }

    @Test
    fun `정확히 창 끝에 완료된 거래도 창 안 목표 달성으로 표시하지 않는다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 1, durationSeconds = 1, pacedRate = 1),
                nanoTime = clock::read,
                beforeMeasuredExecution = { clock.advance(1_000_000_000) },
                parkNanos = clock::park,
            )

        assertEquals(1_000_000_000L, result.elapsedNs)
        assertEquals(1, result.measuredCompleted)
        val paced = assertNotNull(result.paced)
        assertEquals(0, paced.completedWithinWindow)
        assertEquals(1, paced.completedAtOrAfterWindowEnd)
        assertFalse(result.pacedWindowComplete)
        val state = assertNotNull(result.stateCheck)
        assertEquals(true, state["verified"])
        assertEquals(1, state["snapshotCount"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshotOmittedReason"])
        assertEquals(state, evidence["stateCheck"])
        assertEquals(emptyList<String>(), evidence["captureErrors"])
        assertFalse(evidence.containsKey("snapshot"))
        assertFalse(evidence.containsKey("book"))
    }

    @Test
    fun `창을 넘긴 호출 뒤에는 미실행 예정 구간을 보존하고 남은 SELL까지 금융 검증한다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 4, durationSeconds = 1, pacedRate = 4),
                nanoTime = clock::read,
                beforeMeasuredExecution = { clock.advance(1_200_000_000) },
                parkNanos = clock::park,
            )

        assertEquals("PACED_WINDOW_REACHED", result.stopReason)
        assertEquals(1, result.measuredAttempts)
        assertEquals(1, result.measuredCompleted)
        assertFalse(result.pacedWindowComplete)
        val paced = assertNotNull(result.paced)
        assertEquals(0, paced.completedWithinWindow)
        assertEquals(1, paced.completedAtOrAfterWindowEnd)
        assertEquals(3, paced.notExecuted)
        assertEquals(250_000_000L, paced.firstNotExecutedDueOffsetNs)
        assertEquals(750_000_000L, paced.lastNotExecutedDueOffsetNs)
        assertEquals(450_000_000L, paced.notExecutedDelayLowerBoundNs)
        assertEquals(4, result.preparationSells)
        val state = assertNotNull(result.stateCheck)
        assertEquals(true, state["verified"])
        assertEquals(1L, state["actualSettlements"])
        assertEquals(30L, state["expectedFeeRevenue"])
        assertTrue(result.errors.isEmpty())
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
        val mapper = jacksonObjectMapper()
        val json = mapper.readTree(mapper.writeValueAsString(evidence))
        assertEquals(8, json.path("snapshot").path("ledgerTransactions").size())
        assertEquals(3, json.path("book").size())
    }

    @Test
    fun `paced도 실패한 시도의 업무 분포와 예정 지연을 보존하고 미실행을 구별한다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 3, durationSeconds = 1, pacedRate = 3),
                nanoTime = clock::read,
                beforeMeasuredExecution = { index ->
                    clock.advance(if (index == 0) 10L else 20L)
                    check(index != 1) { "test-only paced execution failure" }
                },
                parkNanos = clock::park,
            )

        assertEquals("MEASUREMENT_FAILURE", result.stopReason)
        assertEquals(2, result.measuredAttempts)
        assertEquals(1, result.measuredCompleted)
        assertEquals(2L, result.latency.count)
        assertEquals(30L, result.latency.totalNs)
        assertFalse(result.pacedWindowComplete)
        val paced = assertNotNull(result.paced)
        assertFalse(paced.windowReached)
        assertEquals(1, paced.failedAttempts)
        assertEquals(1, paced.notExecuted)
        assertEquals(666_666_666L, paced.firstNotExecutedDueOffsetNs)
        assertEquals(2L, paced.scheduledStartDelay.count)
        assertEquals(2L, paced.scheduledAttemptLatency.count)
        assertEquals(30L, paced.scheduledAttemptLatency.totalNs)
        assertTrue(result.errors.any { "test-only paced execution failure" in it })
        assertTrue(result.errors.any { "state verification:" in it })
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(false, evidence["financialVerificationPassed"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
    }

    @Test
    fun `마지막 창 대기의 실패도 숨기지 않고 이미 완료한 금융 상태와 지연은 보존한다`() {
        val clock = EngineCapacityPacedClock()
        val result =
            runTradingEngineCapacity(
                TradingEngineCapacityOptions(warmup = 0, orders = 1, durationSeconds = 1, pacedRate = 1),
                nanoTime = clock::read,
                beforeMeasuredExecution = { clock.advance(10) },
                parkNanos = { throw IllegalStateException("test-only park failure") },
            )

        assertEquals("MEASUREMENT_FAILURE", result.stopReason)
        assertEquals(1, result.measuredCompleted)
        assertEquals(1L, result.latency.count)
        assertEquals(10L, result.latency.totalNs)
        assertFalse(result.pacedWindowComplete)
        val paced = assertNotNull(result.paced)
        assertFalse(paced.windowReached)
        assertEquals(0, paced.failedAttempts)
        assertEquals(0, paced.notExecuted)
        assertEquals(true, assertNotNull(result.stateCheck)["verified"])
        assertTrue(result.errors.any { "test-only park failure" in it })
        val evidence = assertNotNull(result.failureStateEvidence)
        assertEquals(true, evidence["financialVerificationPassed"])
        assertEquals(true, evidence["snapshotCaptureAttempted"])
        assertNotNull(evidence["snapshot"])
        assertNotNull(evidence["book"])
        assertFalse(evidence.containsKey("snapshotOmittedReason"))
    }

    @Test
    fun `고정 크기 분포는 큰 지연을 버리지 않고 전 표본과 평균 및 분위수 상한을 보존한다`() {
        val histogram = TradingEngineCapacityHistogram()
        repeat(99_999) { histogram.record(1000) }
        histogram.record(10_000_000_000)
        val result = histogram.summary()

        assertEquals(100_000L, result.count)
        assertEquals(10_099_999_000L, result.totalNs)
        assertEquals(100_999.99, result.meanNs)
        assertEquals(1007L, result.p50UpperBoundNs)
        assertEquals(1007L, result.p95UpperBoundNs)
        assertEquals(1007L, result.p99UpperBoundNs)
        assertEquals(10_000_000_000L, result.maxNs)
        assertFailsWith<IllegalArgumentException> { histogram.record(-1) }
    }

    @Test
    fun `0ns 표본도 별도 버킷으로 보존하고 단일 큰 지연의 분위수를 잘라내지 않는다`() {
        val zeros = TradingEngineCapacityHistogram()
        repeat(99) { zeros.record(0) }
        zeros.record(1)
        assertEquals(0L, zeros.summary().p99UpperBoundNs)
        val maximum = TradingEngineCapacityHistogram()
        maximum.record(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, maximum.summary().p99UpperBoundNs)
    }
}

private class EngineCapacityPacedClock(
    private var nowNs: Long = 0L,
) {
    var parkCalls: Int = 0
        private set

    fun read(): Long = nowNs

    fun advance(durationNs: Long) {
        require(durationNs >= 0)
        // Intentional two's-complement wrap, matching System.nanoTime difference semantics.
        nowNs += durationNs
    }

    fun park(durationNs: Long) {
        require(durationNs in 1..1_000_000L)
        parkCalls++
        advance(durationNs)
    }
}

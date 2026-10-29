package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpPerformanceCleanupTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `원시 표본 경로가 디렉터리여서 실제 저장이 실패해도 클러스터를 종료하고 무효 사유를 남긴다`() {
        val raw = Files.createDirectory(directory.resolve("requests.jsonl"))
        val errors = mutableListOf<String>()
        var actualFailure: Throwable? = null
        var cleanupCalled = false

        finishHttpPerformanceRun(
            errors = errors,
            closeHttp = {},
            preserveRawSamples = {
                try {
                    Files.newBufferedWriter(raw).use { it.appendLine(RAW_SAMPLE) }
                } catch (failure: FileSystemException) {
                    actualFailure = failure
                    throw failure
                }
            },
            closeCluster = { cleanupCalled = true },
        )

        assertIs<FileSystemException>(actualFailure)
        assertTrue(cleanupCalled)
        assertEquals(1, errors.size)
        assertTrue(errors.single().startsWith("원시 표본 저장: "))
        assertTrue(errors.single().contains("requests.jsonl"))
    }

    @Test
    fun `정상 원시 표본은 클러스터 종료 전에 원문 그대로 보존하고 오류를 만들지 않는다`() {
        val raw = directory.resolve("requests.jsonl")
        val errors = mutableListOf<String>()
        val steps = mutableListOf<String>()

        finishHttpPerformanceRun(
            errors = errors,
            closeHttp = { steps.add("http") },
            preserveRawSamples = {
                Files.newBufferedWriter(raw).use { it.appendLine(RAW_SAMPLE) }
                steps.add("raw")
            },
            closeCluster = {
                assertEquals("$RAW_SAMPLE\n", Files.readString(raw))
                steps.add("cluster")
            },
        )

        assertEquals(listOf("http", "raw", "cluster"), steps)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `클러스터 종료 실패는 원시 표본을 보존한 채 정리 실패로 따로 분류한다`() {
        val raw = directory.resolve("requests.jsonl")
        val errors = mutableListOf<String>()
        var cleanupCalled = false

        finishHttpPerformanceRun(
            errors = errors,
            closeHttp = {},
            preserveRawSamples = { Files.newBufferedWriter(raw).use { it.appendLine(RAW_SAMPLE) } },
            closeCluster = {
                cleanupCalled = true
                throw IllegalStateException("container close failed")
            },
        )

        assertTrue(cleanupCalled)
        assertEquals("$RAW_SAMPLE\n", Files.readString(raw))
        assertEquals(listOf("클러스터 정리: IllegalStateException: container close failed"), errors)
    }

    @Test
    fun `원시 저장과 클러스터 종료가 모두 실패하면 두 실패를 각각 보존한다`() {
        val raw = Files.createDirectory(directory.resolve("requests.jsonl"))
        val errors = mutableListOf<String>()
        var cleanupCalled = false

        finishHttpPerformanceRun(
            errors = errors,
            closeHttp = {},
            preserveRawSamples = { Files.newBufferedWriter(raw).use { it.appendLine(RAW_SAMPLE) } },
            closeCluster = {
                cleanupCalled = true
                throw IllegalStateException("container close failed")
            },
        )

        assertTrue(cleanupCalled)
        assertEquals(2, errors.size)
        assertTrue(errors[0].startsWith("원시 표본 저장: "))
        assertTrue(errors[0].contains("requests.jsonl"))
        assertEquals("클러스터 정리: IllegalStateException: container close failed", errors[1])
    }

    companion object {
        private const val RAW_SAMPLE = """{"requestId":"M-0","startNs":100,"ackNs":120,"doneNs":140}"""
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VerificationCommandsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `두 프로세스를 먼저 시작해도 각 출력이 다른 파일에 보존된다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        try {
            val first = commands.start(listOf("sh", "-c", "printf A; printf error-A >&2"))
            val second = commands.start(listOf("sh", "-c", "printf B; printf error-B >&2"))
            assertNotEquals(first.stdoutPath, second.stdoutPath)
            assertEquals(2, commands.records.size)
            val a = first.await(5_000)
            val b = second.await(5_000)
            assertTrue(a.succeeded && b.succeeded)
            assertEquals("A", a.stdout)
            assertEquals("B", b.stdout)
            assertEquals("error-A", a.stderr)
            assertEquals("error-B", b.stderr)
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
    }

    @Test
    fun `두 번째 기동 실패 뒤 이미 실행한 첫 프로세스도 종료한다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        val first = commands.start(listOf("sh", "-c", "exec sleep 30"))
        try {
            val missing = commands.start(listOf(directory.resolve("missing-executable").toString()))
            assertTrue(missing.failedToStart)
            assertFalse(missing.await(1).succeeded)
            assertNotEquals(first.stdoutPath, missing.stdoutPath)
            assertEquals(2, commands.records.size)
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
        assertFalse(first.isAlive)
    }

    @Test
    fun `기다림 상한 초과는 실패이며 CLI 프로세스를 남기지 않는다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        val running = commands.start(listOf("sh", "-c", "exec sleep 30"))
        try {
            val result = running.await(10)
            assertTrue(result.timedOut)
            assertFalse(result.succeeded)
            assertFalse(running.isAlive)
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
    }

    @Test
    fun `한도 안의 출력은 두 스트림 모두 그대로 보존하며 성공한다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        try {
            val running = commands.start(listOf("sh", "-c", "printf ABCD; printf 1234 >&2"), outputLimitBytes = 4)
            val result = running.await(5_000)
            assertTrue(result.succeeded, result.failure())
            assertEquals("ABCD", result.stdout)
            assertEquals("1234", result.stderr)
            assertEquals(4L, Files.size(running.stdoutPath))
            assertEquals(4L, Files.size(running.stderrPath))
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
    }

    @Test
    fun `파이프보다 큰 양쪽 출력은 계속 소비하되 한도만 보존하고 실패한다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        val script =
            "i=0; while [ \"\$i\" -lt 4096 ]; do " +
                "printf %064d 0; printf %064d 0 >&2; i=\$((i+1)); done"
        try {
            val running = commands.start(listOf("sh", "-c", script), outputLimitBytes = 128)
            val result = running.await(5_000)
            assertEquals(0, result.exitCode)
            assertFalse(result.timedOut, result.failure())
            assertFalse(result.succeeded)
            assertTrue(result.error.orEmpty().contains("stdout 출력 보존 한도 초과"))
            assertTrue(result.error.orEmpty().contains("stderr 출력 보존 한도 초과"))
            assertEquals(128L, Files.size(running.stdoutPath))
            assertEquals(128L, Files.size(running.stderrPath))
            assertEquals(128, result.stdout.length)
            assertEquals(128, result.stderr.length)
            assertEquals(result.error, commands.records.single()["failure"])
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
    }

    @Test
    fun `계속 출력하는 프로세스도 기다림 상한에서 종료하고 한도만 남긴다`() {
        val commands = VerificationCommands(directory, directory, emptyMap())
        val running =
            commands.start(
                listOf("sh", "-c", "while :; do printf 0123456789; printf 0123456789 >&2; done"),
                outputLimitBytes = 128,
            )
        try {
            val result = running.await(50)
            assertTrue(result.timedOut)
            assertFalse(result.succeeded)
            assertFalse(running.isAlive)
            assertTrue(Files.size(running.stdoutPath) <= 128L)
            assertTrue(Files.size(running.stderrPath) <= 128L)
        } finally {
            assertTrue(commands.closeRunning().isEmpty())
        }
    }
}

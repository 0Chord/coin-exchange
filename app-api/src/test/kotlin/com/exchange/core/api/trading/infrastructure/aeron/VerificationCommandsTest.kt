package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
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
}

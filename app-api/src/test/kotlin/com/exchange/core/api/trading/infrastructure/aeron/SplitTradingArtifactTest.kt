package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals

/** 같은 UID로 읽을 수 있다는 사실만으로 Docker와 호스트 사이의 읽기 가능 여부를 판단하지 않는다. */
class SplitTradingArtifactTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `분리 노드의 첫 관찰 파일은 다른 사용자에게 읽기만 허용한다`() {
        val path = directory.resolve("runtime-funds1.json")

        writeSplitTestJson(path, mapOf("role" to "FUNDS", "memberId" to 1))

        val observed = jacksonObjectMapper().readTree(Files.readString(path))
        assertEquals("FUNDS", observed.path("role").asString())
        assertEquals(1, observed.path("memberId").asInt())
        assertSharedObservation(path)
    }

    @Test
    fun `소유자만 읽을 수 있던 관찰 파일을 교체하면 내용과 공유 읽기 권한을 함께 갱신한다`() {
        val path = directory.resolve("state-funds1.json")
        writeSplitTestJson(path, mapOf("position" to 1))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))

        writeSplitTestJson(path, mapOf("position" to 2))

        assertEquals(2L, jacksonObjectMapper().readTree(Files.readString(path)).path("position").asLong())
        assertSharedObservation(path)
    }

    private fun assertSharedObservation(path: Path) {
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(path))
        assertEquals(listOf(path), Files.list(directory).use { it.toList() })
    }
}

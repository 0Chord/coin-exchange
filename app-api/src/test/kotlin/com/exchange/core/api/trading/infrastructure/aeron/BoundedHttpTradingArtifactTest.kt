package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BoundedHttpTradingArtifactTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `바이너리 설정은 다른 사용자에게 읽기만 허용하고 임시 파일을 남기지 않는다`() {
        val path = directory.resolve("manifest.bin")
        val bytes = byteArrayOf(1, 2, 3, 4)

        writeBoundedHttpArtifact(path, bytes, replaceExisting = false)

        assertContentEquals(bytes, Files.readAllBytes(path))
        assertSharedArtifact(path)
    }

    @Test
    fun `JSON 관찰 결과를 교체해도 CI 사용자 읽기 권한과 완성된 내용이 남는다`() {
        val path = directory.resolve("state.json")
        writeBoundedHttpJson(path, mapOf("position" to 1))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))

        writeBoundedHttpJson(path, mapOf("position" to 2))

        assertEquals(2L, jacksonObjectMapper().readTree(Files.readString(path)).path("position").asLong())
        assertSharedArtifact(path)
    }

    private fun assertSharedArtifact(path: Path) {
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(path))
        assertEquals(listOf(path), Files.list(directory).use { it.toList() })
    }
}

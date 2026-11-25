package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerInspectionReportTest {
    @TempDir
    lateinit var reports: Path

    @Test
    fun `컨테이너 조회는 환경과 실행 명령을 제외한 진단 항목만 Docker에서 출력한다`() {
        val command = dockerContainerInspection("container-116")
        val format = command[3]

        assertEquals(listOf("docker", "inspect", "--format"), command.take(3))
        assertEquals("container-116", command.last())
        assertTrue(format.startsWith("[{"))
        assertTrue(format.endsWith("}]"))
        assertEquals(
            setOf("Id", "Image", "State", "HostConfig.NetworkMode", "HostConfig.IpcMode", "Mounts", "NetworkSettings"),
            Regex("\\{\\{json \\.([A-Za-z.]+)}}").findAll(format).map { it.groupValues[1] }.toSet(),
        )
        assertFalse(format.contains(".Config"))
        assertFalse(format.contains(".Env"))
        assertFalse(format.contains(".Args"))
        assertFalse(format.contains(".Path"))
    }

    @Test
    fun `원본 출력과 inspect와 최종 보고서의 자격증명을 모두 경로로 찾는다`() {
        val secret = "issue116-unique-database-credential"
        val raw = Files.writeString(reports.resolve("command-7.stdout.log"), "{\"Env\":[\"DB_PASSWORD=$secret\"]}")
        val inspection = Files.writeString(reports.resolve("node1.inspect.json"), "{\"Env\":[\"DB_PASSWORD=$secret\"]}")
        val result = Files.writeString(reports.resolve("result.json"), "{\"unexpected\":\"$secret\"}")
        Files.writeString(reports.resolve("node0.log"), "READY: configuration version 1")

        val leaks = credentialLeakFiles(reports, secret)

        assertEquals(listOf(raw, inspection, result).sorted(), leaks)
        assertFalse(leaks.toString().contains(secret))
    }

    @Test
    fun `하위 바이너리 파일과 읽기 경계를 가로지르는 UTF8 자격증명도 놓치지 않는다`() {
        val secret = "issue116-비밀-marker"
        val nested = Files.createDirectories(reports.resolve("records/node0"))
        val file = nested.resolve("archive.rec")
        Files.write(file, ByteArray(64 * 1024 - 3) { 0xff.toByte() } + secret.toByteArray(Charsets.UTF_8) + ByteArray(10))

        assertEquals(listOf(file), credentialLeakFiles(reports, secret))
    }

    @Test
    fun `진단만 남은 보고서와 빈 파일은 자격증명 유출이 아니다`() {
        Files.writeString(
            reports.resolve("node0.inspect.json"),
            """[{"Id":"node0","State":{"Running":false,"ExitCode":0},"HostConfig":{"NetworkMode":"bridge","IpcMode":"private"}}]""",
        )
        Files.createFile(reports.resolve("command-9.stderr.log"))

        assertTrue(credentialLeakFiles(reports, "issue116-absent-secret").isEmpty())
        assertFailsWith<IllegalArgumentException> { credentialLeakFiles(reports, "") }
    }
}

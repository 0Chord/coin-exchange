package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpPerformanceStartupTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `제품 소스가 달라 측정을 거절해도 관찰 통신 스레드를 남기지 않고 자식 JVM이 종료한다`() {
        val project = temporary.resolve("app-api")
        val source = project.resolve("src/main/Changed.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "original")

        fun git(vararg arguments: String) {
            val process = ProcessBuilder(listOf("git") + arguments).directory(temporary.toFile()).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertTrue(process.exitValue() == 0, process.errorStream.bufferedReader().readText())
        }
        git("init", "--quiet")
        git("add", ".")
        git("-c", "user.name=Performance Test", "-c", "user.email=performance@example.invalid", "commit", "--quiet", "-m", "fixture")
        Files.writeString(source, "changed")
        val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
        val output = temporary.resolve("startup.log")
        val process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-DfundingProjectDirectory=$project",
                "-DfundingDistributionDirectory=$distribution",
                "-cp",
                "${distribution.resolve("test-classes")}:${distribution.resolve("lib/*")}",
                "com.exchange.core.api.trading.infrastructure.aeron.HttpAeronPerformanceBaselineKt",
                "--smoke",
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "측정을 시작하지 못한 JVM의 통신 스레드가 남았습니다")
            assertNotEquals(0, process.exitValue())
            assertTrue(Files.readString(output).contains("제품 소스 변경 없이 기준선을 측정해야 합니다"))
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

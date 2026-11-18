package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 실제 WAS 메인을 별도 JVM에서 실행하고 다음 기동 전에 프로세스를 회수한다. */
internal class OrderRecoveryWas private constructor(
    private val process: Process,
    port: Int,
) : AutoCloseable {
    val url = "http://127.0.0.1:$port"
    val pid: Long get() = process.pid()

    override fun close() = stop(process)

    companion object {
        private val startedPort = Regex("Tomcat started on port ([0-9]+) \\(http\\)")

        fun start(
            arguments: Array<String>,
            log: Path,
        ): OrderRecoveryWas {
            val java = Path.of(System.getProperty("java.home"), "bin", "java")
            val classpath = checkNotNull(System.getProperty("developmentFundingRuntimeClasspath"))
            val command =
                listOf(
                    java.toString(),
                    "-XX:ActiveProcessorCount=2",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    "com.exchange.core.ExchangeCoreApplicationKt",
                ) + arguments + listOf("--server.address=127.0.0.1", "--logging.level.org.springframework.boot.tomcat=INFO")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (process.isAlive && System.nanoTime() < deadline) {
                    val startup = startedPort.find(Files.readAllBytes(log).toString(Charsets.UTF_8))
                    if (startup != null) return OrderRecoveryWas(process, startup.groupValues[1].toInt())
                    Thread.sleep(50)
                }
                error("WAS가 시작되지 않았습니다 (PID ${process.pid()}, $log)\n${Files.readString(log).takeLast(12_000)}")
            } catch (failure: Throwable) {
                val cleanupFailure = runCatching { stop(process) }.exceptionOrNull()
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
                throw failure
            }
        }

        private fun stop(process: Process) {
            process.destroy()
            try {
                if (process.waitFor(5, TimeUnit.SECONDS)) return
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "WAS 프로세스를 종료하지 못했습니다 (PID ${process.pid()})" }
            } catch (failure: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                throw failure
            }
        }
    }
}

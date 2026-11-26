package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 실제 WAS 메인을 별도 JVM에서 실행하고 다음 기동 전에 프로세스를 회수한다. */
internal class OrderRecoveryWas private constructor(
    private val process: Process,
    port: Int,
    private val log: Path,
) : AutoCloseable {
    val url = "http://127.0.0.1:$port"
    val pid: Long get() = process.pid()

    fun stopGracefully(expectedThrough: Map<String, Long>) {
        process.stopTradingProcess(log)
        val output = Files.readString(log)
        val expected = "거래 종료 결과=COMPLETED 대상 순번=$expectedThrough 정산 순번=$expectedThrough"
        check(output.lineSequence().any { it.endsWith(expected) }) {
            "WAS가 자금 정산 완료를 확인하지 못했습니다 (PID $pid, $log)\n${output.takeLast(12_000)}"
        }
    }

    override fun close() = process.cleanupTradingProcess()

    companion object {
        private val startedPort = Regex("Tomcat started on port ([0-9]+) \\(http\\)")

        fun start(
            arguments: Array<String>,
            log: Path,
        ): OrderRecoveryWas {
            val command =
                tradingProcessCommand("com.exchange.core.ExchangeCoreApplicationKt") +
                    arguments + listOf("--server.address=127.0.0.1", "--logging.level.org.springframework.boot.tomcat=INFO")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (process.isAlive && System.nanoTime() < deadline) {
                    val startup = startedPort.find(Files.readAllBytes(log).toString(Charsets.UTF_8))
                    if (startup != null) return OrderRecoveryWas(process, startup.groupValues[1].toInt(), log)
                    Thread.sleep(50)
                }
                error("WAS가 시작되지 않았습니다 (PID ${process.pid()}, $log)\n${Files.readString(log).takeLast(12_000)}")
            } catch (failure: Throwable) {
                val cleanupFailure = runCatching { process.cleanupTradingProcess() }.exceptionOrNull()
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure)
                throw failure
            }
        }
    }
}

internal fun tradingProcessCommand(mainClass: String): List<String> =
    listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-XX:ActiveProcessorCount=2",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
        "-cp",
        checkNotNull(System.getProperty("developmentFundingRuntimeClasspath")),
        mainClass,
    )

internal fun Process.stopTradingProcess(log: Path) {
    check(isAlive) { "정상 종료를 요청하기 전에 프로세스가 끝났습니다 (PID ${pid()}, $log)\n${Files.readString(log).takeLast(12_000)}" }
    destroy()
    check(waitFor(25, TimeUnit.SECONDS)) { "정상 종료 시간이 초과됐습니다 (PID ${pid()}, $log)" }
    val exit = exitValue()
    check(exit == 0 || exit == 143) {
        "정상 종료가 아닌 종료 코드 $exit (PID ${pid()}, $log)\n${Files.readString(log).takeLast(12_000)}"
    }
}

internal fun Process.cleanupTradingProcess() {
    if (!isAlive) return
    destroy()
    try {
        if (waitFor(5, TimeUnit.SECONDS)) return
        destroyForcibly()
        check(waitFor(5, TimeUnit.SECONDS)) { "프로세스를 회수하지 못했습니다 (PID ${pid()})" }
    } catch (failure: InterruptedException) {
        destroyForcibly()
        Thread.currentThread().interrupt()
        throw failure
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 시작과 기다림을 분리하되, 프로세스·출력 이름은 테스트 조정 스레드 하나가 소유한다. */
internal class VerificationCommands(
    private val directory: Path,
    private val reportDirectory: Path,
    private val environment: Map<String, String>,
) {
    private val processes = mutableListOf<RunningVerificationCommand>()
    val records: List<Map<String, Any?>> get() = processes.map { it.record() }

    fun start(arguments: List<String>): RunningVerificationCommand {
        val index = processes.size
        val command =
            RunningVerificationCommand(
                arguments,
                reportDirectory.resolve("command-$index.stdout.log"),
                reportDirectory.resolve("command-$index.stderr.log"),
            )
        processes.add(command) // 기동 실패도 고유 출력 경로와 정리 대상에 남긴다.
        command.start(directory, environment)
        return command
    }

    fun closeRunning(): List<FundingVerificationIssue> = processes.filter { it.isAlive }.flatMap { it.stop() }
}

internal class RunningVerificationCommand(
    private val arguments: List<String>,
    val stdoutPath: Path,
    val stderrPath: Path,
) {
    private var process: Process? = null
    private val started = System.nanoTime()
    private var exit: Int? = null
    private var error: String? = null
    private var timedOut = false
    private var durationMs: Long? = null
    val isAlive: Boolean get() = process?.isAlive == true
    val failedToStart: Boolean get() = process == null

    fun start(
        directory: Path,
        environment: Map<String, String>,
    ) {
        try {
            val builder =
                ProcessBuilder(
                    arguments,
                ).directory(directory.toFile()).redirectOutput(stdoutPath.toFile()).redirectError(stderrPath.toFile())
            // Default-market checks must not inherit a caller's unrelated market configuration.
            DEVELOPMENT_MARKET_ENVIRONMENT_KEYS.forEach { builder.environment().remove(it) }
            builder.environment().putAll(environment)
            process = builder.start()
        } catch (failure: Exception) {
            error = "${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    fun await(timeoutMs: Long): VerificationCommandResult {
        val running = process
        if (running != null && exit == null) {
            if (!running.waitFor(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)) {
                timedOut = true
                stop()
            } else {
                exit = running.exitValue()
            }
        }
        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return VerificationCommandResult(exit, timedOut, error, output(stdoutPath), output(stderrPath))
    }

    fun stop(): List<FundingVerificationIssue> {
        val running = process ?: return emptyList()
        if (running.isAlive) running.destroyForcibly()
        if (!running.waitFor(1, TimeUnit.SECONDS)) {
            return listOf(FundingVerificationIssue("CLEANUP", "실행한 CLI 프로세스 종료 미확인: $arguments"))
        }
        exit = running.exitValue()
        return emptyList()
    }

    fun record(): Map<String, Any?> =
        mapOf(
            "arguments" to arguments,
            "stdout" to stdoutPath.fileName.toString(),
            "stderr" to stderrPath.fileName.toString(),
            "exitCode" to exit,
            "timedOut" to timedOut,
            "failure" to error,
            "durationMs" to durationMs,
        )

    private fun output(path: Path) = if (Files.exists(path)) Files.readString(path) else ""
}

internal data class VerificationCommandResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val error: String?,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0 && !timedOut && error == null

    fun failure(): String = "exit=$exitCode, timedOut=$timedOut, error=$error, stderr=${stderr.takeLast(4000)}"
}

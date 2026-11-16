package com.exchange.core.api.trading.infrastructure.aeron

import java.io.InputStream
import java.io.OutputStream
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

    fun start(
        arguments: List<String>,
        outputLimitBytes: Long? = null,
    ): RunningVerificationCommand {
        require(outputLimitBytes == null || outputLimitBytes >= 0) { "출력 보존 한도는 음수일 수 없다" }
        val index = processes.size
        val command =
            RunningVerificationCommand(
                arguments,
                reportDirectory.resolve("command-$index.stdout.log"),
                reportDirectory.resolve("command-$index.stderr.log"),
                outputLimitBytes,
            )
        processes.add(command) // 기동 실패도 고유 출력 경로와 정리 대상에 남긴다.
        command.start(directory, environment)
        return command
    }

    fun closeRunning(): List<FundingVerificationIssue> = processes.flatMap { it.stop() }
}

internal class RunningVerificationCommand(
    private val arguments: List<String>,
    val stdoutPath: Path,
    val stderrPath: Path,
    private val outputLimitBytes: Long? = null,
) {
    private val outputs = mutableListOf<BoundedVerificationOutput>()
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
            val builder = ProcessBuilder(arguments).directory(directory.toFile())
            if (outputLimitBytes == null) {
                builder.redirectOutput(stdoutPath.toFile()).redirectError(stderrPath.toFile())
            }
            // 기본 마켓 검증에 호출자의 무관한 마켓 설정을 물려주지 않는다.
            DEVELOPMENT_MARKET_ENVIRONMENT_KEYS.forEach { builder.environment().remove(it) }
            builder.environment().putAll(environment)
            val running = builder.start()
            process = running
            if (outputLimitBytes != null) {
                outputs += BoundedVerificationOutput("stdout", running.inputStream, stdoutPath, outputLimitBytes)
                outputs += BoundedVerificationOutput("stderr", running.errorStream, stderrPath, outputLimitBytes)
                outputs.forEach { it.start() }
            }
        } catch (failure: Exception) {
            error = "${failure.javaClass.simpleName}: ${failure.message}"
        }
    }

    fun await(timeoutMs: Long): VerificationCommandResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        val running = process
        if (running != null && exit == null) {
            if (!running.waitFor(remainingNs(deadline), TimeUnit.NANOSECONDS)) {
                timedOut = true
                stop()
            } else {
                exit = running.exitValue()
            }
        }
        if (!timedOut && !awaitOutputs(deadline)) {
            timedOut = true
            stop()
        }
        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return VerificationCommandResult(exit, timedOut, failure(), output(stdoutPath), output(stderrPath))
    }

    fun stop(): List<FundingVerificationIssue> {
        val running = process ?: return emptyList()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        val issues = mutableListOf<FundingVerificationIssue>()
        if (running.isAlive) running.destroyForcibly()
        if (!running.waitFor(remainingNs(deadline), TimeUnit.NANOSECONDS)) {
            issues += FundingVerificationIssue("CLEANUP", "실행한 CLI 프로세스 종료 미확인: $arguments")
        } else {
            exit = running.exitValue()
        }
        if (!awaitOutputs(deadline)) {
            error = "CLI 출력 소비 스레드 종료 미확인"
            issues += FundingVerificationIssue("CLEANUP", "$error: $arguments")
        }
        return issues
    }

    fun record(): Map<String, Any?> =
        mapOf(
            "arguments" to arguments,
            "stdout" to stdoutPath.fileName.toString(),
            "stderr" to stderrPath.fileName.toString(),
            "exitCode" to exit,
            "timedOut" to timedOut,
            "failure" to failure(),
            "durationMs" to durationMs,
            "outputLimitBytes" to outputLimitBytes,
        )

    private fun awaitOutputs(deadline: Long): Boolean {
        outputs.forEach { it.await(remainingNs(deadline)) }
        return outputs.none { it.isAlive }
    }

    private fun failure(): String? = (listOfNotNull(error) + outputs.mapNotNull { it.failure() }).joinToString("; ").ifEmpty { null }

    private fun output(path: Path): String =
        when {
            !Files.exists(path) -> ""

            outputLimitBytes == null -> Files.readString(path)

            // 바이트 한도에서 잘린 마지막 UTF-8 문자는 대체 문자로 읽는다.
            else -> String(Files.readAllBytes(path), Charsets.UTF_8)
        }

    private fun remainingNs(deadline: Long): Long = (deadline - System.nanoTime()).coerceAtLeast(0)
}

/** 보존 한도를 넘긴 뒤에도 계속 읽어 자식 프로세스의 출력 파이프가 막히지 않게 한다. */
private class BoundedVerificationOutput(
    private val name: String,
    private val input: InputStream,
    private val path: Path,
    private val limit: Long,
) {
    @Volatile private var exceeded = false

    @Volatile private var error: String? = null

    private val thread = Thread({ drain() }, "verification-$name-${path.fileName}").apply { isDaemon = true }
    val isAlive: Boolean get() = thread.isAlive

    fun start() = thread.start()

    fun await(timeoutNs: Long) {
        if (timeoutNs > 0) TimeUnit.NANOSECONDS.timedJoin(thread, timeoutNs)
    }

    fun failure(): String? =
        listOfNotNull(
            if (exceeded) "$name 출력 보존 한도 초과: $limit bytes" else null,
            error,
        ).joinToString("; ").ifEmpty { null }

    private fun drain() {
        var sink: OutputStream? =
            try {
                Files.newOutputStream(path)
            } catch (failure: Exception) {
                error = "$name 출력 파일 열기 실패: ${failure.message}"
                null
            }
        try {
            input.use { source ->
                val buffer = ByteArray(8192)
                var retained = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    val keep = minOf(read.toLong(), limit - retained).toInt()
                    if (keep < read) exceeded = true
                    val destination = sink
                    if (keep > 0 && destination != null) {
                        try {
                            destination.write(buffer, 0, keep)
                            retained += keep
                        } catch (failure: Exception) {
                            error = "$name 출력 파일 쓰기 실패: ${failure.message}"
                            runCatching { sink?.close() }
                            sink = null
                        }
                    }
                }
            }
        } catch (failure: Exception) {
            error = "$name 출력 읽기 실패: ${failure.message}"
        } finally {
            runCatching { sink?.close() }.onFailure { error = "$name 출력 파일 닫기 실패: ${it.message}" }
        }
    }
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

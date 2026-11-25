package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal const val CONCURRENT_READY_TIMEOUT_MS = 90_000L
internal const val CONCURRENT_CLIENT_INSPECTION_TIMEOUT_MS = 5_000L

// 먼저 READY인 클라이언트도 두 READY 수집과 컨테이너 두 개의 확인을 기다려야 한다.
internal const val CONCURRENT_GATE_TIMEOUT_MS = CONCURRENT_READY_TIMEOUT_MS + 2 * CONCURRENT_CLIENT_INSPECTION_TIMEOUT_MS + 20_000L

/** 개발 동시 전송 검사에서만 사용하는 실행별 출발 신호. 업무 입력이나 복구 기록이 아니다. */
internal class DevelopmentOrderStartGate(
    val path: Path,
    val runId: String,
    val clientId: String,
    val timeoutMs: Long = CONCURRENT_GATE_TIMEOUT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val idle: () -> Unit = { Thread.sleep(1) },
) {
    init {
        require(path.isAbsolute) { "출발 신호 경로는 절대 경로여야 합니다" }
        require(runId.matches(Regex("[a-z0-9-]{1,64}"))) { "출발 신호에는 이번 실행 ID가 필요합니다" }
        require(clientId.isNotBlank()) { "클라이언트 ID가 필요합니다" }
        require(timeoutMs in 1..CONCURRENT_GATE_TIMEOUT_MS) { "출발 대기 기한은 1~${CONCURRENT_GATE_TIMEOUT_MS}ms입니다" }
    }

    fun await(
        pollConnection: () -> Unit,
        onReady: () -> Unit,
        onGo: () -> Unit,
    ) {
        check(!Files.exists(path)) { "준비 전에 존재한 출발 신호는 사용할 수 없습니다" }
        onReady()
        val start = nanoTime()
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (nanoTime() - start < timeout) {
            pollConnection()
            if (Files.exists(path)) {
                check(Files.size(path) in 1..256) { "출발 신호 길이가 잘못됐습니다" }
                check(Files.readString(path).trimEnd('\r', '\n') == runId) { "다른 실행이거나 손상된 출발 신호입니다" }
                onGo()
                return
            }
            idle()
        }
        error("출발 신호 대기 기한을 초과했습니다")
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

/** 관찰 실패를 무효 사유로 남기면서 최종 금융 상태 검사를 별도로 끝까지 시도한다. */
internal fun <S, V> collectHttpPerformanceFinalEvidence(
    errors: MutableList<String>,
    closeObservation: () -> Unit,
    capture: () -> S,
    verify: (S) -> V,
    health: () -> Unit,
): Pair<S?, V?> {
    attemptHttpPerformanceEvidence(errors, "관찰 종료", closeObservation)
    val snapshot = attemptHttpPerformanceEvidence(errors, "최종 상태 수집", capture)
    val verification = snapshot?.let { attemptHttpPerformanceEvidence(errors, "최종 금융 검증") { verify(it) } }
    attemptHttpPerformanceEvidence(errors, "최종 실행 상태", health)
    return snapshot to verification
}

internal fun <T> attemptHttpPerformanceEvidence(
    errors: MutableList<String>,
    label: String,
    action: () -> T,
): T? =
    try {
        action()
    } catch (failure: Exception) {
        errors.add("$label: ${failure.javaClass.simpleName}: ${failure.message}")
        null
    }

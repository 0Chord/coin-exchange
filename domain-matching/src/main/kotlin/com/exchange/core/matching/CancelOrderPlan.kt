package com.exchange.core.matching

import java.util.Collections

/** 취소 전체 결과를 보존할 수 있는지 검사하기 위한 읽기 전용 후보다. */
class CancelOrderPlan internal constructor(
    private val engineToken: Any,
    internal val baseSequence: Long,
    val command: CancelOrderCommand,
    internal val target: RestingOrderSnapshot?,
    val event: MatchingEvent,
) {
    val events: List<MatchingEvent> = Collections.singletonList(event)
    val firstEngineSequence: Long get() = event.engineSequence
    val lastEngineSequence: Long get() = event.engineSequence

    internal fun belongsTo(token: Any): Boolean = engineToken === token
}

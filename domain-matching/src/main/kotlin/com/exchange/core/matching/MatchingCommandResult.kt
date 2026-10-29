package com.exchange.core.matching

import java.util.Collections
import java.util.Objects

/** 한 명령의 결과 묶음. 메모리 등록 완료이며 거래·정산·영구 저장 완료를 뜻하지 않는다. */
class MatchingCommandResult internal constructor(
    val commandId: String,
    val command: MatchingCommand,
    val expectedEventCount: Int,
    val firstEngineSequence: Long,
    val lastEngineSequence: Long,
    events: List<MatchingEvent>,
) {
    val events: List<MatchingEvent> = Collections.unmodifiableList(events.toList())

    override fun equals(other: Any?): Boolean =
        other is MatchingCommandResult && commandId == other.commandId && command == other.command &&
            expectedEventCount == other.expectedEventCount && firstEngineSequence == other.firstEngineSequence &&
            lastEngineSequence == other.lastEngineSequence && events == other.events

    override fun hashCode(): Int = Objects.hash(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)

    companion object {
        /** Restores a validated result without advancing a live matching owner. */
        fun restore(
            commandId: String,
            command: MatchingCommand,
            expectedEventCount: Int,
            firstEngineSequence: Long,
            lastEngineSequence: Long,
            events: List<MatchingEvent>,
        ): MatchingCommandResult {
            val result = MatchingCommandResult(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)
            MatchingResultState().validateHistoricalResult(result)
            return result
        }
    }
}

package com.exchange.core.matching

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.Collections

/** 한 실행자가 소유하는 결과 등록 이력. 매칭 실행·자금 반영·I/O는 수행하지 않는다. */
class MatchingResultState {
    private data class CommandKey(
        val marketId: MarketId,
        val commandId: String,
    )

    private data class OrderKey(
        val marketId: MarketId,
        val orderId: OrderId,
    )

    private val records: MutableMap<CommandKey, MatchingCommandResult> = linkedMapOf()
    private val lastSequences: MutableMap<MarketId, Long> = linkedMapOf()
    private val submittedOrders = mutableSetOf<OrderKey>()
    private var planToken = Any()
    private var checkpointRestored = false

    /** Restores active first results and all market high-water marks without filling historical gaps. */
    fun restoreActiveCheckpoint(
        lastEngineSequences: Map<MarketId, Long>,
        results: List<MatchingCommandResult>,
    ) {
        checkUsable()
        check(!checkpointRestored && records.isEmpty() && lastSequences.isEmpty() && submittedOrders.isEmpty()) {
            "matching result checkpoint requires an empty state"
        }
        require(lastEngineSequences.values.all { it > 0 }) { "checkpoint result sequences must be positive" }
        val restored = linkedMapOf<CommandKey, MatchingCommandResult>()
        val orders = hashSetOf<OrderKey>()
        val ranges = hashMapOf<MarketId, MutableList<MatchingCommandResult>>()
        for (result in results) {
            validateHistoricalResult(result)
            val command = result.command
            require(result.lastEngineSequence <= (lastEngineSequences[command.marketId] ?: 0L)) {
                "checkpoint result exceeds market sequence"
            }
            require(restored.put(CommandKey(command.marketId, result.commandId), result) == null) { "duplicate checkpoint result" }
            require(command is SubmitOrderCommand && orders.add(OrderKey(command.marketId, command.orderId))) {
                "checkpoint must contain distinct active submit results"
            }
            ranges.getOrPut(command.marketId) { mutableListOf() }.add(result)
        }
        for (marketResults in ranges.values) {
            var last = 0L
            for (result in marketResults.sortedBy { it.firstEngineSequence }) {
                require(result.firstEngineSequence > last) { "checkpoint result sequence ranges overlap" }
                last = result.lastEngineSequence
            }
        }
        isBlocked = true
        records.putAll(restored)
        lastSequences.putAll(lastEngineSequences)
        submittedOrders.addAll(orders)
        planToken = Any()
        checkpointRestored = true
        isBlocked = false
    }

    /** 검증을 마친 불변 결과. 발급한 상태의 같은 마켓 순번에서만 처음 등록할 수 있다. */
    class PreparedRegistration internal constructor(
        private val ownerToken: Any,
        internal val baseSequence: Long,
        internal val result: MatchingCommandResult,
    ) {
        internal fun belongsTo(token: Any): Boolean = ownerToken === token
    }

    /** 반영 중 오류 뒤에는 같은 결과의 재전송과 정상 조회도 차단한다. */
    var isBlocked: Boolean = false
        private set

    @ConsistentCopyVisibility
    data class Snapshot internal constructor(
        val results: List<MatchingCommandResult>,
        val lastEngineSequences: Map<MarketId, Long>,
    )

    /** 실제 쓰기 전의 전체 조건을 검사한다. 동일 결과의 재전송은 최초 등록 값을 반환한다. */
    fun validateRegistration(
        commandId: String,
        command: MatchingCommand,
        expectedEventCount: Int,
        firstEngineSequence: Long,
        lastEngineSequence: Long,
        events: List<MatchingEvent>,
    ): MatchingCommandResult {
        checkUsable()
        requireResult(commandId.isNotBlank(), "내부 명령 ID가 비어 있습니다")
        val candidate = MatchingCommandResult(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)
        val key = CommandKey(command.marketId, commandId)
        val previous = records[key]
        if (previous != null) {
            if (previous != candidate) {
                throw MatchingResultException(MatchingResultException.Reason.CONTENT_CONFLICT, "같은 명령의 결과 내용이 다릅니다")
            }
            return previous
        }

        validate(candidate)
        val expectedFirst = exactAdd(lastSequences[command.marketId] ?: 0L, 1)
        requireResult(firstEngineSequence == expectedFirst, "마켓의 다음 이벤트 순번과 다릅니다")
        val orderKey = (command as? SubmitOrderCommand)?.let { OrderKey(it.marketId, it.orderId) }
        if (orderKey != null && orderKey in submittedOrders) {
            throw MatchingResultException(MatchingResultException.Reason.DUPLICATE_SUBMIT, "이미 결과를 등록한 제출 주문입니다")
        }
        return candidate
    }

    /** 결과를 한 번 복사·검증한다. 준비만으로 이력이나 마켓 순번을 바꾸지 않는다. */
    fun prepareRegistration(
        commandId: String,
        command: MatchingCommand,
        expectedEventCount: Int,
        firstEngineSequence: Long,
        lastEngineSequence: Long,
        events: List<MatchingEvent>,
    ): PreparedRegistration {
        val result = validateRegistration(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)
        return PreparedRegistration(planToken, result.firstEngineSequence - 1, result)
    }

    /** 전체 검증 뒤 등록하며, 같은 키의 재전송은 현재의 다음 순번보다 먼저 대조한다. */
    fun register(
        commandId: String,
        command: MatchingCommand,
        expectedEventCount: Int,
        firstEngineSequence: Long,
        lastEngineSequence: Long,
        events: List<MatchingEvent>,
    ): MatchingCommandResult {
        val candidate = validateRegistration(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)
        val key = CommandKey(command.marketId, commandId)
        records[key]?.let { return it }
        return commit(candidate, key)
    }

    /** 불변 결과의 전체 검증은 재실행하지 않고 소유자·현재 순번·동일 키를 확인한 뒤 쓴다. */
    fun register(prepared: PreparedRegistration): MatchingCommandResult {
        checkUsable()
        require(prepared.belongsTo(planToken)) { "matching result registration belongs to another state" }
        val candidate = prepared.result
        val command = candidate.command
        val key = CommandKey(command.marketId, candidate.commandId)
        val previous = records[key]
        if (previous != null) {
            if (previous !== candidate && previous != candidate) {
                throw MatchingResultException(MatchingResultException.Reason.CONTENT_CONFLICT, "같은 명령의 결과 내용이 다릅니다")
            }
            return previous
        }
        requireResult((lastSequences[command.marketId] ?: 0L) == prepared.baseSequence, "준비한 결과의 마켓 순번이 바뀌었습니다")

        return commit(candidate, key)
    }

    private fun commit(
        candidate: MatchingCommandResult,
        key: CommandKey,
    ): MatchingCommandResult {
        val command = candidate.command
        val orderKey = (command as? SubmitOrderCommand)?.let { OrderKey(it.marketId, it.orderId) }

        // SQL 롤백 대신, 어느 쓰기에서든 실패하면 차단 상태를 남긴다.
        isBlocked = true
        records[key] = candidate
        lastSequences[command.marketId] = candidate.lastEngineSequence
        if (orderKey != null) submittedOrders.add(orderKey)
        isBlocked = false
        return candidate
    }

    fun find(
        marketId: MarketId,
        commandId: String,
    ): MatchingCommandResult? {
        checkUsable()
        return records[CommandKey(marketId, commandId)]
    }

    /** 전체 이력을 복사하지 않고 해당 마켓의 마지막 등록 순번만 읽는다. */
    fun lastEngineSequence(marketId: MarketId): Long {
        checkUsable()
        return lastSequences[marketId] ?: 0L
    }

    /** The common owner must durably preserve the result and exact submitted-order guard first. */
    fun releaseHistory(
        marketId: MarketId,
        commandId: String,
        terminalOrderId: OrderId,
    ) {
        checkUsable()
        val key = CommandKey(marketId, commandId)
        val result = records[key] ?: return
        val command = result.command as? SubmitOrderCommand
        require(command?.orderId == terminalOrderId) { "history release does not match the submitted order" }
        records.remove(key)
        submittedOrders.remove(OrderKey(marketId, terminalOrderId))
    }

    fun snapshot(): Snapshot {
        checkUsable()
        return Snapshot(
            Collections.unmodifiableList(records.values.toList()),
            Collections.unmodifiableMap(LinkedHashMap(lastSequences)),
        )
    }

    internal fun validateHistoricalResult(result: MatchingCommandResult) = validateCompleteResult(result)

    private fun checkUsable() = check(!isBlocked) { "반영 중 오류로 결과 등록 상태가 차단됐습니다" }

    companion object {
        /** Checks a transient result without opening the module-private result constructor. */
        fun validateCompleteResult(
            commandId: String,
            command: MatchingCommand,
            expectedEventCount: Int,
            firstEngineSequence: Long,
            lastEngineSequence: Long,
            events: List<MatchingEvent>,
        ) {
            validateCompleteResult(
                MatchingCommandResult(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events),
            )
        }

        /** Pure completeness validation; no command, result, or duplicate identity is retained. */
        fun validateCompleteResult(result: MatchingCommandResult) {
            requireResult(result.commandId.isNotBlank(), "내부 명령 ID가 비어 있습니다")
            validate(result)
        }

        /** The plan already owns an immutable event copy; validate it against the original command. */
        fun validateCompleteResult(
            commandId: String,
            command: MatchingCommand,
            plan: SubmitOrderPlan,
        ) {
            requireResult(commandId.isNotBlank(), "내부 명령 ID가 비어 있습니다")
            validate(command, plan.events.size, plan.firstEngineSequence, plan.lastEngineSequence, plan.events)
        }

        private fun validate(result: MatchingCommandResult) =
            validate(
                result.command,
                result.expectedEventCount,
                result.firstEngineSequence,
                result.lastEngineSequence,
                result.events,
            )

        private fun validate(
            command: MatchingCommand,
            expectedEventCount: Int,
            firstEngineSequence: Long,
            lastEngineSequence: Long,
            events: List<MatchingEvent>,
        ) {
            requireResult(expectedEventCount > 0 && events.size == expectedEventCount, "전체 이벤트 개수가 다릅니다")
            requireResult(firstEngineSequence > 0 && lastEngineSequence >= firstEngineSequence, "이벤트 순번 범위가 잘못됐습니다")
            requireResult(
                exactAdd(firstEngineSequence, expectedEventCount.toLong() - 1) == lastEngineSequence,
                "이벤트 개수와 순번 범위가 다릅니다",
            )
            events.forEachIndexed { index, event ->
                requireResult(event.marketId == command.marketId, "다른 마켓의 이벤트입니다")
                requireResult(event.engineSequence == exactAdd(firstEngineSequence, index.toLong()), "이벤트 순서가 연속되지 않습니다")
            }
            when (command) {
                is SubmitOrderCommand -> validateSubmit(command, events)
                is CancelOrderCommand -> validateCancel(command, events)
            }
        }

        private fun validateSubmit(
            command: SubmitOrderCommand,
            events: List<MatchingEvent>,
        ) {
            requireResult(command.orderType == OrderType.LIMIT && command.timeInForce == TimeInForce.GTC, "LIMIT/GTC 결과만 등록할 수 있습니다")
            var totalQuantity = 0L
            events.forEachIndexed { index, event ->
                when (event) {
                    is TradeExecuted -> {
                        requireResult(
                            event.takerOrderId == command.orderId && event.takerUserId == command.userId && event.side == command.side,
                            "체결의 taker 정보가 원본 제출과 다릅니다",
                        )
                        requireResult(event.makerOrderId != command.orderId && event.quantity.value > 0, "체결 상대 또는 수량이 잘못됐습니다")
                        requireResult(
                            if (command.side == Side.BUY) event.price <= command.price else event.price >= command.price,
                            "체결가가 지정가 조건을 위반합니다",
                        )
                        totalQuantity = exactAdd(totalQuantity, event.quantity.value)
                    }

                    is OrderEnteredBook -> {
                        requireResult(index == events.lastIndex, "잔량 대기는 마지막 이벤트 하나여야 합니다")
                        requireResult(
                            event.orderId == command.orderId && event.userId == command.userId && event.side == command.side &&
                                event.price == command.price && event.remainingQuantity.value > 0,
                            "잔량 대기 정보가 원본 제출과 다릅니다",
                        )
                        totalQuantity = exactAdd(totalQuantity, event.remainingQuantity.value)
                    }

                    else -> {
                        requireResult(false, "제출 결과에 취소 이벤트가 포함됐습니다")
                    }
                }
            }
            requireResult(totalQuantity == command.quantity.value, "체결 수량과 잔량의 합이 원수량과 다릅니다")
        }

        private fun validateCancel(
            command: CancelOrderCommand,
            events: List<MatchingEvent>,
        ) {
            requireResult(events.size == 1, "취소 결과는 이벤트 한 개여야 합니다")
            when (val event = events.single()) {
                is OrderCancelled -> {
                    requireResult(
                        event.orderId == command.orderId && event.userId == command.userId && event.remainingQuantity.value > 0,
                        "취소 성공 결과가 원본 요청과 다릅니다",
                    )
                }

                is OrderCancelRejected -> {
                    requireResult(
                        event.orderId == command.orderId && event.userId == command.userId && event.reason.isNotBlank(),
                        "취소 거절 결과가 원본 요청과 다릅니다",
                    )
                }

                else -> {
                    requireResult(false, "취소 결과에 제출 이벤트가 포함됐습니다")
                }
            }
        }

        private fun requireResult(
            valid: Boolean,
            message: String,
        ) {
            if (!valid) throw MatchingResultException(MatchingResultException.Reason.INVALID_RESULT, message)
        }

        private fun exactAdd(
            left: Long,
            right: Long,
        ): Long =
            try {
                Math.addExact(left, right)
            } catch (_: ArithmeticException) {
                throw MatchingResultException(MatchingResultException.Reason.INVALID_RESULT, "수량 또는 순번의 정수 범위를 넘었습니다")
            }
    }
}

class MatchingResultException(
    val reason: Reason,
    message: String,
) : IllegalArgumentException(message) {
    enum class Reason {
        INVALID_RESULT,
        CONTENT_CONFLICT,
        DUPLICATE_SUBMIT,
    }
}

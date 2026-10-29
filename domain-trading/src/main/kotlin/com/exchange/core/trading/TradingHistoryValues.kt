package com.exchange.core.trading

import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingCommandResult
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import java.time.Instant

/** Explicit restoration boundaries for exact historical values; never reapplies a transaction. */
object TradingHistoryValues {
    fun admissionCandidate(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        previousBalance: Balance,
        nextBalance: Balance,
        reservation: OrderReservation,
        reserveTransaction: LedgerTransaction,
    ): OrderAdmissionCandidate =
        OrderAdmissionCandidate(order, market, feePolicySnapshot, previousBalance, nextBalance, reservation, reserveTransaction)

    fun admissionRecord(
        sequence: Long,
        candidate: OrderAdmissionCandidate,
    ): OrderAdmissionState.Record {
        require(sequence > 0)
        candidate.validate()
        return OrderAdmissionState.Record(sequence, candidate)
    }

    fun requestRecord(
        requestId: String,
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        ledgerTransactionId: String,
        occurredAt: Instant,
        result: OrderAdmissionState.Result,
    ): OrderAdmissionState.RequestRecord {
        require(requestId.isNotEmpty())
        if (result is OrderAdmissionState.Result.Accepted) {
            val candidate = result.record.candidate
            require(candidate.order == order && candidate.market == market && candidate.feePolicySnapshot == feePolicySnapshot)
            require(candidate.reserveTransaction.ledgerTransactionId == ledgerTransactionId)
            require(candidate.reserveTransaction.occurredAt == occurredAt)
        }
        return OrderAdmissionState.RequestRecord(requestId, order, market, feePolicySnapshot, ledgerTransactionId, occurredAt, result)
    }

    fun preparationRecord(
        result: OpeningBalanceResult,
        transaction: LedgerTransaction,
    ): OrderAdmissionState.PreparationRecord {
        require(!result.alreadyPrepared)
        require(result.ledgerTransactionId == transaction.ledgerTransactionId && result.occurredAt == transaction.occurredAt)
        val expected = result.opening.transaction(result.ledgerTransactionId, result.occurredAt)
        require(expected.sourceEventId == transaction.sourceEventId && expected.transactionType == transaction.transactionType)
        require(expected.postings == transaction.postings)
        return OrderAdmissionState.PreparationRecord(result, transaction)
    }

    fun matchingResult(
        commandId: String,
        command: MatchingCommand,
        expectedEventCount: Int,
        firstEngineSequence: Long,
        lastEngineSequence: Long,
        events: List<MatchingEvent>,
    ): MatchingCommandResult =
        MatchingCommandResult.restore(commandId, command, expectedEventCount, firstEngineSequence, lastEngineSequence, events)

    fun settlementCandidate(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        settlements: List<OrderSettlementCandidate.Settlement>,
        balanceChanges: List<OrderSettlementCandidate.BalanceChange>,
        reservationChanges: List<OrderSettlementCandidate.ReservationChange>,
    ): OrderSettlementCandidate {
        require(order.marketId == market.marketId)
        for (change in balanceChanges) {
            require(change.previous.userId == change.next.userId && change.previous.assetId == change.next.assetId)
        }
        for (change in reservationChanges) {
            require(change.previous.marketId == change.next.marketId && change.previous.orderId == change.next.orderId)
        }
        for (settlement in settlements) {
            require(settlement.execution.marketId == market.marketId)
            require(settlement.transaction.ledgerTransactionId == settlement.execution.ledgerTransactionId)
            require(settlement.transaction.occurredAt == settlement.execution.occurredAt)
            require(settlement.makerPlan.updatedReservation.orderId == settlement.execution.makerOrderId)
            require(settlement.takerPlan.updatedReservation.orderId == settlement.execution.takerOrderId)
        }
        return OrderSettlementCandidate(order, market, settlements, balanceChanges, reservationChanges).compactForHistory()
    }

    fun completion(
        admission: OrderAdmissionState.Record,
        executionId: String,
        occurredAt: Instant,
        matchingResult: MatchingCommandResult,
        settlement: OrderSettlementCandidate,
    ): TradingState.Completion {
        require(executionId == "EXECUTE:v1:${admission.sequence}" && matchingResult.commandId == executionId)
        require(admission.candidate.order == settlement.order && admission.candidate.market == settlement.market)
        val order = admission.candidate.order
        require(
            matchingResult.command ==
                SubmitOrderCommand(
                    order.marketId,
                    order.orderId,
                    order.userId,
                    order.side,
                    order.orderType,
                    order.timeInForce,
                    order.price,
                    order.quantity,
                ),
        )
        val trades = matchingResult.events.filterIsInstance<TradeExecuted>()
        require(trades.size == settlement.settlements.size)
        trades.zip(settlement.settlements).forEach { (event, item) ->
            require(
                item.execution ==
                    SettlementExecution(
                        event.marketId,
                        event.engineSequence,
                        event.makerOrderId,
                        event.takerOrderId,
                        event.makerUserId,
                        event.takerUserId,
                        event.side,
                        event.price,
                        event.quantity,
                        settlementTransactionId(event.marketId, event.engineSequence),
                        occurredAt,
                    ),
            )
        }
        return TradingState.Completion(admission, executionId, occurredAt, matchingResult, settlement)
    }
}

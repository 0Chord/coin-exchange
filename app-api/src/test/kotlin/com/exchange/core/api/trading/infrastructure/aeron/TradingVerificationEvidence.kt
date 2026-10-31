package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.ledger.Balance
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.RestingOrderSnapshot
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import com.exchange.core.trading.TradingState

private val verificationTradingStateField = DevelopmentFundingAdapter::class.java.getDeclaredField("state").apply { isAccessible = true }

private fun verificationTradingState(service: DevelopmentFundingAdapter): TradingState =
    verificationTradingStateField.get(service) as TradingState

internal fun isVerificationTradingBlocked(service: DevelopmentFundingAdapter): Boolean = verificationTradingState(service).isBlocked

/** 전체 상태의 학습·대조용 값이다. 명시적으로 캡처한 callback 상태를 재사용한다. */
internal fun tradingVerificationEvidence(
    service: DevelopmentFundingAdapter,
    captured: TradingState.Snapshot? = null,
): Map<String, Any?> {
    val state = verificationTradingState(service)
    if (state.isBlocked) return mapOf("blocked" to true)
    val snapshot = captured ?: service.captureSnapshotOnServiceThread()
    val markets =
        (snapshot.requests.map { it.order.marketId } + snapshot.reservations.map { it.marketId }).distinct().sortedBy { it.value }
    return mapOf(
        "currentReservations" to snapshot.reservations.map(::verificationReservation),
        "books" to markets.map { market -> verificationBook(state, market) },
        "completions" to
            snapshot.completions.map(::verificationCompletionFields),
    )
}

internal fun verificationCompletionFields(completion: TradingState.Completion): Map<String, Any?> {
    val matching = completion.matchingResult
    return mapOf(
        "admissionSequence" to completion.admission.sequence,
        "executionId" to completion.executionId,
        "occurredAt" to completion.occurredAt.toString(),
        "marketId" to completion.admission.candidate.market.marketId.value,
        "orderId" to completion.admission.candidate.order.orderId.value,
        "expectedEventCount" to matching.expectedEventCount,
        "firstEngineSequence" to matching.firstEngineSequence,
        "lastEngineSequence" to matching.lastEngineSequence,
        "events" to matching.events.map(::verificationMatchingEvent),
        "settlements" to
            completion.settlement.settlements.map { settlement ->
                val execution = settlement.execution
                mapOf(
                    "marketId" to execution.marketId.value,
                    "engineSequence" to execution.engineSequence,
                    "makerOrderId" to execution.makerOrderId.value,
                    "takerOrderId" to execution.takerOrderId.value,
                    "makerUserId" to execution.makerUserId.value,
                    "takerUserId" to execution.takerUserId.value,
                    "side" to execution.side.name,
                    "price" to execution.price.value,
                    "quantity" to execution.quantity.value,
                    "ledgerTransactionId" to execution.ledgerTransactionId,
                    "occurredAt" to execution.occurredAt.toString(),
                    "makerPlan" to verificationFillPlan(settlement.makerPlan),
                    "takerPlan" to verificationFillPlan(settlement.takerPlan),
                )
            },
        "balanceChanges" to
            completion.settlement.balanceChanges.map {
                mapOf("previous" to verificationBalance(it.previous), "next" to verificationBalance(it.next))
            },
        "reservationChanges" to
            completion.settlement.reservationChanges.map {
                mapOf("previous" to verificationReservation(it.previous), "next" to verificationReservation(it.next))
            },
    )
}

private fun verificationBook(
    state: TradingState,
    market: MarketId,
): Map<String, Any?> =
    mapOf(
        "marketId" to market.value,
        "lastEngineSequence" to state.lastEngineSequence(market),
        "orders" to
            state.book(market).map(::verificationRestingOrderFields),
    )

internal fun verificationRestingOrderFields(order: RestingOrderSnapshot): Map<String, Any?> =
    mapOf(
        "orderId" to order.orderId.value,
        "userId" to order.userId.value,
        "side" to order.side.name,
        "price" to order.price.value,
        "originalQuantity" to order.originalQuantity.value,
        "remainingQuantity" to order.remainingQuantity.value,
    )

private fun verificationMatchingEvent(event: MatchingEvent): Map<String, Any?> =
    mapOf("marketId" to event.marketId.value, "engineSequence" to event.engineSequence) +
        when (event) {
            is OrderEnteredBook -> {
                mapOf(
                    "kind" to "ORDER_ENTERED_BOOK",
                    "orderId" to event.orderId.value,
                    "userId" to event.userId.value,
                    "side" to event.side.name,
                    "price" to event.price.value,
                    "remainingQuantity" to event.remainingQuantity.value,
                )
            }

            is TradeExecuted -> {
                mapOf(
                    "kind" to "TRADE_EXECUTED",
                    "makerOrderId" to event.makerOrderId.value,
                    "takerOrderId" to event.takerOrderId.value,
                    "makerUserId" to event.makerUserId.value,
                    "takerUserId" to event.takerUserId.value,
                    "side" to event.side.name,
                    "price" to event.price.value,
                    "quantity" to event.quantity.value,
                )
            }

            else -> {
                error("이번 자동 접수 검증에서 예상하지 않은 사건: $event")
            }
        }

private fun verificationFillPlan(plan: OrderFillSettlementPlan): Map<String, Any?> =
    mapOf(
        "reservation" to verificationReservation(plan.updatedReservation),
        "reservedAmountToReduce" to plan.reservedAmountToReduce.value,
        "holdAmountToConsume" to plan.holdAmountToConsume.value,
        "holdAmountToRelease" to plan.holdAmountToRelease.value,
        "creditAssetId" to plan.creditAssetId.value,
        "creditAmount" to plan.creditAmount.value,
        "feeAssetId" to plan.feeAssetId.value,
        "actualFeeAmount" to plan.actualFeeAmount.value,
    )

internal fun verificationReservation(reservation: OrderReservation): Map<String, Any?> =
    mapOf(
        "marketId" to reservation.marketId.value,
        "orderId" to reservation.orderId.value,
        "userId" to reservation.userId.value,
        "side" to reservation.side.name,
        "assetId" to reservation.assetId.value,
        "limitPrice" to reservation.limitPrice.value,
        "initialQuantity" to reservation.initialQuantity.value,
        "remainingQuantity" to reservation.remainingQuantity.value,
        "reservedAmount" to reservation.reservedAmount.value,
        "remainingAmount" to reservation.remainingAmount.value,
        "initialFeeReserveAmount" to reservation.initialFeeReserveAmount.value,
        "remainingFeeReserveAmount" to reservation.remainingFeeReserveAmount.value,
        "feeRemainder" to reservation.feeRemainder.numerator,
        "status" to reservation.status.name,
        "releasedAmount" to reservation.releasedAmount?.value,
        "feePolicy" to
            mapOf(
                "productType" to reservation.feePolicySnapshot.productType.name,
                "feeTier" to reservation.feePolicySnapshot.feeTier.name,
                "scheduleVersion" to reservation.feePolicySnapshot.scheduleVersion,
                "makerPartsPerMillion" to reservation.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion,
                "takerPartsPerMillion" to reservation.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion,
            ),
    )

private fun verificationBalance(balance: Balance): Map<String, Any?> =
    mapOf(
        "userId" to balance.userId.value,
        "assetId" to balance.assetId.value,
        "available" to balance.available.value,
        "hold" to balance.hold.value,
    )

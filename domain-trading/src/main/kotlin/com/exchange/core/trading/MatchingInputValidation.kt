package com.exchange.core.trading

import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.MatchingResultState
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** 자금 잔고를 읽지 않고 승인 원본의 수학적 관계만 검사한다. 실제 발신자 인증은 relay의 책임이다. */
internal object MatchingInputValidation {
    fun reservationCalculator(): OrderReservationCalculator =
        OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    fun requireValid(
        configuration: MatchingIntakeConfiguration,
        input: FundsOrderDispatch,
    ) {
        require(input.formatVersion == 1 && configuration.matches(input.stream)) { "matching input stream differs" }
        require(
            input.dispatchSequence > 0 && input.admissionSequence >= input.dispatchSequence &&
                input.marketAdmissionSequence == input.dispatchSequence,
        )
        val order = input.order
        val context = configuration.context(input.stream.marketId)
        require(input.context == context && order.marketId == input.stream.marketId)
        require(order.orderId == TradingIdentifiers.order(configuration.manifest.lineage, input.admissionSequence)) {
            "matching approval identity differs"
        }
        for (text in listOf(order.marketId.value, order.orderId.value, order.userId.value)) {
            require(tradingTextBytes(text) <= configuration.manifest.limits.maxIdentifierBytes) { "matching identifier exceeds limit" }
        }
        require(':' !in order.userId.value) { "matching user identifier differs from admission contract" }
        require(order.orderType == OrderType.LIMIT && order.timeInForce == TimeInForce.GTC)
        val requirement =
            reservationCalculator().calculate(
                context.market,
                order.side,
                order.price,
                order.quantity,
                context.feePolicySnapshot,
            )
        val initial =
            OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                requirement,
                context.feePolicySnapshot,
            )
        require(initial == input.reservation) { "matching initial reservation differs" }
    }

    fun command(input: FundsOrderDispatch): SubmitOrderCommand =
        input.order.let {
            SubmitOrderCommand(it.marketId, it.orderId, it.userId, it.side, it.orderType, it.timeInForce, it.price, it.quantity)
        }

    fun requireResult(
        configuration: MatchingIntakeConfiguration,
        result: MatchingResultEnvelope,
    ) {
        requireValid(configuration, result.input)
        require(configuration.matches(result.stream) && result.stream == configuration.resultStream(result.input.stream.marketId))
        require(result.resultSequence > 0 && result.resultSequence == result.input.dispatchSequence)
        require(result.engineSequenceBefore in 0 until Long.MAX_VALUE && result.engineSequenceAfter >= result.engineSequenceBefore)
        require(
            result.eventCount <= configuration.limits.maxResultEvents && result.canonicalBytes <= configuration.limits.maxSingleResultBytes,
        )
        require(result.effects.isNotEmpty() && result.effects.first().origin == result.input) { "result taker effect differs" }
        require(
            result.effects
                .map { it.orderId }
                .distinct()
                .size == result.effects.size,
        ) { "duplicate result effect" }
        for (effect in result.effects) {
            requireValid(configuration, effect.origin)
            require(effect.origin.stream == result.input.stream)
            require(effect.beforeQuantity.value > 0 && effect.beforeQuantity <= effect.origin.order.quantity)
            require(effect.afterQuantity.value >= 0 && effect.afterQuantity <= effect.beforeQuantity)
        }
        val taker = result.effects.first()
        require(taker.beforeQuantity == result.input.order.quantity)
        when (result.outcome) {
            MatchingOutcome.NOT_EXECUTED -> {
                require(result.reason != null && result.events.isEmpty() && result.effects.size == 1)
                require(
                    result.engineSequenceAfter == result.engineSequenceBefore && taker.afterQuantity == taker.beforeQuantity &&
                        taker.disposition == MatchingOrderDisposition.NOT_EXECUTED,
                )
            }

            MatchingOutcome.MATCHED -> {
                require(result.reason == null && result.eventCount > 0)
                require(result.engineSequenceAfter == Math.addExact(result.engineSequenceBefore, result.eventCount.toLong()))
                MatchingResultState.validateCompleteResult(
                    result.resultId,
                    command(result.input),
                    result.eventCount,
                    Math.addExact(result.engineSequenceBefore, 1),
                    result.engineSequenceAfter,
                    result.events,
                )
                val trades = result.events.filterIsInstance<TradeExecuted>()
                require(result.effects.size == trades.size + 1)
                var takerRemaining = taker.beforeQuantity.value
                for ((index, trade) in trades.withIndex()) {
                    val maker = result.effects[index + 1]
                    require(
                        maker.origin.dispatchSequence < result.input.dispatchSequence &&
                            maker.origin.admissionSequence < result.input.admissionSequence,
                    )
                    require(
                        maker.orderId == trade.makerOrderId && maker.userId == trade.makerUserId && maker.side != result.input.order.side,
                    )
                    require(trade.price == maker.origin.order.price && trade.quantity.value <= maker.beforeQuantity.value)
                    configuration.context(trade.marketId).market.requireOrderUnits(trade.price, trade.quantity)
                    require(maker.afterQuantity.value == Math.subtractExact(maker.beforeQuantity.value, trade.quantity.value))
                    require(
                        maker.disposition ==
                            if (maker.afterQuantity.value == 0L) MatchingOrderDisposition.TERMINAL else MatchingOrderDisposition.RESTING,
                    )
                    takerRemaining = Math.subtractExact(takerRemaining, trade.quantity.value)
                    if (index > 0) {
                        val previous = result.effects[index]
                        if (previous.origin.order.price ==
                            maker.origin.order.price
                        ) {
                            require(
                                previous.origin.marketAdmissionSequence < maker.origin.marketAdmissionSequence,
                            ) { "maker FIFO differs" }
                        }
                    }
                }
                require(taker.afterQuantity.value == takerRemaining)
                require(
                    taker.disposition == if (takerRemaining == 0L) MatchingOrderDisposition.TERMINAL else MatchingOrderDisposition.RESTING,
                )
                val entered = result.events.filterIsInstance<OrderEnteredBook>()
                require((entered.size == 1) == (takerRemaining > 0))
            }
        }
    }
}

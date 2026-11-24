package com.exchange.core.trading

import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.CancelOrderCommand
import com.exchange.core.matching.MatchingResultState
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/** 자금 잔고를 읽지 않고 승인 원본의 수학적 관계만 검사한다. 실제 발신자 인증은 relay의 책임이다. */
internal object MatchingInputValidation {
    val reservationCalculator: OrderReservationCalculator =
        OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    fun requireValid(
        configuration: MatchingIntakeConfiguration,
        input: FundsOrderDispatch,
    ) {
        require(configuration.matches(input.stream)) { "matching input stream differs" }
        requireValid(
            configuration.manifest,
            configuration.context(input.stream.marketId),
            input.stream,
            input,
            configuration.inputContractVersion,
        )
    }

    fun requireValid(
        manifest: TradingClusterManifest,
        context: TradingMarketContext,
        expectedStream: FundsDispatchStream,
        input: FundsOrderDispatch,
        inputContractVersion: Int = 1,
    ) {
        require(inputContractVersion in 1..2 && input.formatVersion == inputContractVersion && input.stream == expectedStream) {
            "matching input stream differs"
        }
        require(
            input.dispatchSequence > 0 && input.admissionSequence > 0 &&
                input.marketAdmissionSequence in 1..input.dispatchSequence && input.marketAdmissionSequence <= input.admissionSequence,
        )
        if (inputContractVersion == 1) {
            require(!input.isCancellation && input.admissionSequence >= input.dispatchSequence)
            require(input.marketAdmissionSequence == input.dispatchSequence)
        }
        val order = input.order
        require(input.context == context && order.marketId == input.stream.marketId)
        require(TradingIdentifiers.matchesOrder(order.orderId, manifest.lineage, input.admissionSequence)) {
            "matching approval identity differs"
        }
        val maxIdentifierBytes = manifest.limits.maxIdentifierBytes
        require(
            tradingTextBytes(order.marketId.value) <= maxIdentifierBytes &&
                tradingTextBytes(order.orderId.value) <= maxIdentifierBytes &&
                tradingTextBytes(order.userId.value) <= maxIdentifierBytes,
        ) { "matching identifier exceeds limit" }
        require(':' !in order.userId.value) { "matching user identifier differs from admission contract" }
        input.cancel?.let { cancel ->
            require(cancel.requestToken.userId == order.userId) { "matching cancellation requester differs" }
            require(inputContractVersion == 2 && cancel.cancelSequence > 0)
            require(cancel.cancelId == TradingIdentifiers.cancel(manifest.lineage, cancel.cancelSequence)) {
                "matching cancellation identity differs"
            }
            require(cancel.targetInputSequence in input.marketAdmissionSequence until input.dispatchSequence) {
                "matching cancellation must follow its target input"
            }
        }
        require(order.orderType == OrderType.LIMIT && order.timeInForce == TimeInForce.GTC)
        val requirement =
            reservationCalculator.calculate(
                context.market,
                order.side,
                order.price,
                order.quantity,
                context.feePolicySnapshot,
            )
        require(
            input.reservation.matchesInitial(
                marketId = order.marketId,
                orderId = order.orderId,
                userId = order.userId,
                side = order.side,
                limitPrice = order.price,
                quantity = order.quantity,
                requirement = requirement,
                feePolicySnapshot = context.feePolicySnapshot,
            ),
        ) { "matching initial reservation differs" }
    }

    fun command(input: FundsOrderDispatch): SubmitOrderCommand =
        input.order.let {
            require(!input.isCancellation) { "cancellation is not a submit command" }
            SubmitOrderCommand(it.marketId, it.orderId, it.userId, it.side, it.orderType, it.timeInForce, it.price, it.quantity)
        }

    fun cancelCommand(input: FundsOrderDispatch): CancelOrderCommand {
        require(input.isCancellation)
        return CancelOrderCommand(input.order.marketId, input.order.orderId, requireNotNull(input.cancel).requestToken.userId)
    }

    fun requireCancellationOrigin(
        input: FundsOrderDispatch,
        origin: FundsOrderDispatch,
    ) {
        require(input.isCancellation && !origin.isCancellation && origin.dispatchSequence < input.dispatchSequence)
        require(requireNotNull(input.cancel).targetInputSequence == origin.dispatchSequence)
        require(input.inputContractVersion == origin.inputContractVersion)
        require(
            input.stream == origin.stream && input.admissionSequence == origin.admissionSequence &&
                input.marketAdmissionSequence == origin.marketAdmissionSequence && input.order == origin.order &&
                input.reservation == origin.reservation && input.context == origin.context,
        ) { "matching cancellation target origin differs" }
    }

    fun requireResult(
        configuration: MatchingIntakeConfiguration,
        result: MatchingResultEnvelope,
    ) {
        require(configuration.matches(result.stream))
        requireResult(
            configuration.manifest,
            configuration.resultStream(result.input.stream.marketId),
            configuration.stream(result.input.stream.marketId),
            configuration.context(result.input.stream.marketId),
            configuration.limits.maxResultEvents,
            configuration.limits.maxSingleResultBytes,
            result,
            configuration.inputContractVersion,
        )
    }

    /** 금융·매칭 역할이 전체 결과의 수학적 관계를 확인하고 체결 이벤트 수를 반환한다. */
    fun requireResult(
        manifest: TradingClusterManifest,
        expectedStream: MatchingResultStream,
        inputStream: FundsDispatchStream,
        context: TradingMarketContext,
        maxResultEvents: Int,
        maxSingleResultBytes: Long,
        result: MatchingResultEnvelope,
        inputContractVersion: Int = 1,
    ): Int {
        require(result.formatVersion == inputContractVersion)
        require(result.stream == expectedStream)
        require(result.eventCount <= maxResultEvents && result.effects.size <= maxResultEvents.toLong() + 1L)
        requireValid(manifest, context, inputStream, result.input, inputContractVersion)
        require(result.resultSequence > 0 && result.resultSequence == result.input.dispatchSequence)
        require(result.engineSequenceBefore in 0 until Long.MAX_VALUE && result.engineSequenceAfter >= result.engineSequenceBefore)
        require(
            result.eventCount <= maxResultEvents && result.canonicalBytes <= maxSingleResultBytes,
        )
        if (result.input.isCancellation) {
            requireCancellationResult(manifest, context, inputStream, result, inputContractVersion)
            return 0
        }
        require(result.outcome == MatchingOutcome.MATCHED || result.outcome == MatchingOutcome.NOT_EXECUTED)
        require(result.effects.isNotEmpty() && result.effects.first().origin == result.input) { "result taker effect differs" }
        val effects = result.effects
        if (effects.size == 2) {
            require(effects[0].orderId != effects[1].orderId) { "duplicate result effect" }
        } else if (effects.size > 2) {
            require(effects.mapTo(HashSet(effects.size)) { it.orderId }.size == effects.size) { "duplicate result effect" }
        }
        for (effect in effects) {
            require(!effect.origin.isCancellation) { "submit effect must contain an admission origin" }
            if (effect.origin != result.input) requireValid(manifest, context, inputStream, effect.origin, inputContractVersion)
            require(effect.origin.stream == result.input.stream)
            require(effect.beforeQuantity.value > 0 && effect.beforeQuantity <= effect.origin.order.quantity)
            require(effect.afterQuantity.value >= 0 && effect.afterQuantity <= effect.beforeQuantity)
        }
        val taker = result.effects.first()
        require(taker.beforeQuantity == result.input.order.quantity)
        return when (result.outcome) {
            MatchingOutcome.NOT_EXECUTED -> {
                require(result.reason != null && result.events.isEmpty() && result.effects.size == 1)
                require(
                    result.engineSequenceAfter == result.engineSequenceBefore && taker.afterQuantity == taker.beforeQuantity &&
                        taker.disposition == MatchingOrderDisposition.NOT_EXECUTED,
                )
                0
            }

            MatchingOutcome.MATCHED -> {
                require(result.reason == null && result.eventCount > 0)
                require(result.engineSequenceAfter == Math.addExact(result.engineSequenceBefore, result.eventCount.toLong()))
                MatchingResultState.validateCompleteEvents(
                    command(result.input),
                    result.eventCount,
                    Math.addExact(result.engineSequenceBefore, 1),
                    result.engineSequenceAfter,
                    result.events,
                )
                // 검증된 제출 결과는 순서대로 이어진 체결과 마지막의 잔량 대기 한 건으로만 구성된다.
                val enteredBook = result.events.last() is OrderEnteredBook
                val tradeCount = if (enteredBook) result.eventCount - 1 else result.eventCount
                require(result.effects.size == tradeCount + 1)
                var takerRemaining = taker.beforeQuantity.value
                for (index in 0 until tradeCount) {
                    val trade = result.events[index] as TradeExecuted
                    val maker = result.effects[index + 1]
                    require(
                        maker.origin.dispatchSequence < result.input.dispatchSequence &&
                            maker.origin.admissionSequence < result.input.admissionSequence,
                    )
                    require(
                        maker.orderId == trade.makerOrderId && maker.userId == trade.makerUserId && maker.side != result.input.order.side,
                    )
                    require(trade.price == maker.origin.order.price && trade.quantity.value <= maker.beforeQuantity.value)
                    // 보이는 maker의 잔량을 두고 다음 maker로 건너뛰는 결과는 정상 매칭이 만들 수 없다.
                    require(trade.quantity.value == minOf(takerRemaining, maker.beforeQuantity.value)) {
                        "result skips visible maker quantity"
                    }
                    context.market.requireOrderUnits(trade.price, trade.quantity)
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
                require(enteredBook == (takerRemaining > 0))
                tradeCount
            }
        }
    }

    private fun requireCancellationResult(
        manifest: TradingClusterManifest,
        context: TradingMarketContext,
        inputStream: FundsDispatchStream,
        result: MatchingResultEnvelope,
        inputContractVersion: Int,
    ) {
        require(result.reason == null && result.eventCount == 1)
        require(result.engineSequenceAfter == Math.addExact(result.engineSequenceBefore, 1))
        MatchingResultState.validateCompleteEvents(
            cancelCommand(result.input),
            1,
            result.engineSequenceAfter,
            result.engineSequenceAfter,
            result.events,
        )
        when (result.outcome) {
            MatchingOutcome.CANCELLED -> {
                val event = result.events.single() as? OrderCancelled ?: throw IllegalArgumentException("missing cancellation event")
                require(result.effects.size == 1)
                val effect = result.effects.single()
                requireValid(manifest, context, inputStream, effect.origin, inputContractVersion)
                requireCancellationOrigin(result.input, effect.origin)
                require(effect.beforeQuantity == event.remainingQuantity && effect.beforeQuantity.value > 0)
                require(effect.beforeQuantity <= effect.origin.order.quantity && effect.afterQuantity.value == 0L)
                require(effect.disposition == MatchingOrderDisposition.CANCELLED)
                context.market.requireOrderUnits(effect.origin.order.price, effect.beforeQuantity)
            }

            MatchingOutcome.CANCEL_REJECTED -> {
                val event =
                    result.events.single() as? OrderCancelRejected ?: throw IllegalArgumentException("missing cancellation rejection")
                require(event.reason == "order not found") { "invalid cancellation rejection reason" }
                require(result.effects.isEmpty())
            }

            else -> {
                throw IllegalArgumentException("cancellation result contains submit outcome")
            }
        }
    }
}

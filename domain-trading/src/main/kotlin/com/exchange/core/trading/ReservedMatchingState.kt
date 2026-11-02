package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.matching.BoundedSubmitPreparation
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.matching.RestingOrderSnapshot
import com.exchange.core.matching.SubmitOrderPlan
import com.exchange.core.order.Side

/** 단일 서비스 스레드에서 입력 prefix·주문장·결과 전달 의무를 함께 변경하는 매칭 소유자다. */
class ReservedMatchingState(
    val configuration: MatchingIntakeConfiguration,
) {
    private class Progress {
        var appliedThrough = 0L
        var lastSourceAdmissionSequence = 0L
        var resultIssued = 0L
        var fundsSettledThrough = 0L
        var settledEngineSequence = 0L
        var resultBytes = 0L
        var activeOrders = 0
        var activeBytes = 0L
        val outstanding: MutableMap<Long, MatchingResultEnvelope> = linkedMapOf()
    }

    private class Data(
        configuration: MatchingIntakeConfiguration,
    ) {
        val engine = MatchingEngine()
        val markets = configuration.markets.associate { it.market.marketId to Progress() }
        val active = linkedMapOf<Pair<MarketId, OrderId>, FundsOrderDispatch>()
        var resultEntries = 0
        var resultBytes = 0L
        var activeBytes = 0L
    }

    private var data: Data? = null
    var isBlocked: Boolean = false
        private set
    val lifecycle: TradingLifecycle get() =
        if (isBlocked) {
            TradingLifecycle.QUARANTINED
        } else if (data ==
            null
        ) {
            TradingLifecycle.UNINITIALIZED
        } else {
            TradingLifecycle.RUNNING
        }

    fun initialize(): InitializationReceipt {
        check(!isBlocked) { "matching owner is blocked" }
        if (data != null) return InitializationReceipt.ALREADY_INITIALIZED
        data = Data(configuration)
        return InitializationReceipt.INITIALIZED
    }

    fun apply(input: FundsOrderDispatch): MatchingIntakeReply {
        val state = usable()
        if (input.formatVersion != 1 || input.dispatchSequence <= 0 || !configuration.matches(input.stream)) {
            return MatchingIntakeReply(MatchingIntakeStatus.INVALID_STREAM)
        }
        val progress = state.markets.getValue(input.stream.marketId)

        fun ack() = FundsMatchingAppliedAck(input.stream, progress.appliedThrough, configuration.context(input.stream.marketId))
        if (input.dispatchSequence <= progress.appliedThrough) {
            val old = progress.outstanding[input.dispatchSequence]
            return when {
                old == null -> MatchingIntakeReply(MatchingIntakeStatus.PRUNED, ack = ack())
                old.input != input -> MatchingIntakeReply(MatchingIntakeStatus.CONTENT_CONFLICT)
                else -> MatchingIntakeReply(MatchingIntakeStatus.DUPLICATE, old, ack())
            }
        }
        val next = next(progress.appliedThrough)
        if (next == null) return MatchingIntakeReply(MatchingIntakeStatus.SEQUENCE_EXHAUSTED)
        if (input.dispatchSequence != next) return MatchingIntakeReply(MatchingIntakeStatus.GAP, expectedSequence = next)
        try {
            MatchingInputValidation.requireValid(configuration, input)
            require(input.admissionSequence > progress.lastSourceAdmissionSequence) { "source admission does not advance" }
        } catch (_: IllegalArgumentException) {
            return MatchingIntakeReply(MatchingIntakeStatus.INVALID_INPUT)
        } catch (_: ArithmeticException) {
            return MatchingIntakeReply(MatchingIntakeStatus.INVALID_INPUT)
        }
        val before = state.engine.lastEngineSequence(input.stream.marketId)
        if (before == Long.MAX_VALUE || progress.resultIssued == Long.MAX_VALUE) {
            return MatchingIntakeReply(MatchingIntakeStatus.SEQUENCE_EXHAUSTED)
        }
        // 어떤 제출 이벤트도 고정폭만으로 32 byte 이상이다. 이 보수적 하한을 넘는 계획은
        // 전체 결과에도 들어갈 수 없으므로 작은 byte 예산에서 거대한 임시 목록을 만들지 않는다.
        val candidateEvents = minOf(configuration.limits.maxResultEvents.toLong(), configuration.limits.maxSingleResultBytes / 32).toInt()
        val prepared = state.engine.prepareSubmitBounded(MatchingInputValidation.command(input), candidateEvents)
        if (prepared is BoundedSubmitPreparation.SequenceExhausted) return MatchingIntakeReply(MatchingIntakeStatus.SEQUENCE_EXHAUSTED)
        var plan = (prepared as? BoundedSubmitPreparation.Prepared)?.plan
        var result =
            if (plan ==
                null
            ) {
                rejected(input, before, MatchingNotExecutedReason.RESULT_TOO_LARGE)
            } else {
                matched(state, input, plan, before)
            }
        if (result.canonicalBytes > configuration.limits.maxSingleResultBytes) {
            result = rejected(input, before, MatchingNotExecutedReason.RESULT_TOO_LARGE)
            plan = null
        }
        var removed =
            if (plan ==
                null
            ) {
                emptyList()
            } else {
                result.effects.drop(1).filter { it.disposition == MatchingOrderDisposition.TERMINAL }
            }
        var add = result.outcome == MatchingOutcome.MATCHED && result.effects.first().disposition == MatchingOrderDisposition.RESTING

        fun activeBytesAfter(): Long {
            var bytes = state.activeBytes
            for (effect in removed) bytes = Math.subtractExact(bytes, MatchingResultCharge.active(effect.origin))
            return if (add) Math.addExact(bytes, MatchingResultCharge.active(input)) else bytes
        }

        fun localBytesAfter(): Long {
            var bytes = progress.activeBytes
            for (effect in removed) bytes = Math.subtractExact(bytes, MatchingResultCharge.active(effect.origin))
            return if (add) Math.addExact(bytes, MatchingResultCharge.active(input)) else bytes
        }
        val local = configuration.limits.marketLimits.getValue(input.stream.marketId)
        if (state.active.size.toLong() - removed.size + (if (add) 1 else 0) > configuration.limits.maxActiveOrders ||
            progress.activeOrders.toLong() - removed.size + (if (add) 1 else 0) > local.maxActiveOrders ||
            activeBytesAfter() > configuration.limits.maxActiveBytes || localBytesAfter() > local.maxActiveBytes
        ) {
            result = rejected(input, before, MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT)
            plan = null
            removed = emptyList()
            add = false
        }
        val bytes = result.canonicalBytes
        if (state.resultEntries >= configuration.limits.maxResultEntries || progress.outstanding.size >= local.maxResultEntries ||
            bytes > configuration.limits.maxResultBytes - state.resultBytes || bytes > local.maxResultBytes - progress.resultBytes
        ) {
            return MatchingIntakeReply(MatchingIntakeStatus.BUSY)
        }
        MatchingInputValidation.requireResult(configuration, result)
        val newActiveBytes = activeBytesAfter()
        val newLocalBytes = localBytesAfter()
        val newResultBytes = Math.addExact(state.resultBytes, bytes)
        val newLocalResultBytes = Math.addExact(progress.resultBytes, bytes)
        val newResultCount = Math.addExact(state.resultEntries, 1)
        val newLocalActiveCount = progress.activeOrders - removed.size + if (add) 1 else 0
        // 여기부터는 일부 쓰기가 끝났을 수 있다. 예상 밖 오류는 롤백 성공으로 꾸미지 않고 소유자를 차단한다.
        mutate {
            if (plan != null) state.engine.apply(plan)
            for (effect in removed) state.active.remove(input.stream.marketId to effect.orderId)
            if (add) check(state.active.put(input.stream.marketId to input.order.orderId, input) == null)
            check(progress.outstanding.put(result.resultSequence, result) == null)
            progress.appliedThrough = input.dispatchSequence
            progress.lastSourceAdmissionSequence = input.admissionSequence
            progress.resultIssued = result.resultSequence
            progress.resultBytes = newLocalResultBytes
            progress.activeOrders = newLocalActiveCount
            progress.activeBytes = newLocalBytes
            state.activeBytes = newActiveBytes
            state.resultBytes = newResultBytes
            state.resultEntries = newResultCount
            for (effect in result.effects) {
                if (effect.disposition ==
                    MatchingOrderDisposition.TERMINAL
                ) {
                    state.engine.releaseTerminalOrderIdentity(input.stream.marketId, effect.orderId)
                }
            }
        }
        return MatchingIntakeReply(MatchingIntakeStatus.APPLIED, result, ack())
    }

    fun readResults(
        stream: MatchingResultStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): MatchingResultRead {
        val state = usable()
        if (!configuration.matches(stream)) return MatchingResultRead(MatchingResultReadStatus.INVALID_STREAM)
        if (fromSequence <= 0 || maxEntries <= 0 || maxBytes <= 0) return MatchingResultRead(MatchingResultReadStatus.INVALID_QUERY)
        val progress = state.markets.getValue(stream.marketId)

        fun reply(
            status: MatchingResultReadStatus,
            rows: List<MatchingResultEnvelope> = emptyList(),
            required: Long = 0,
        ): MatchingResultRead {
            val cursor =
                when {
                    rows.isNotEmpty() -> next(rows.last().resultSequence)
                    status == MatchingResultReadStatus.PRUNED -> next(progress.fundsSettledThrough)
                    status == MatchingResultReadStatus.BATCH || status == MatchingResultReadStatus.BATCH_TOO_SMALL -> fromSequence
                    else -> null
                }
            return MatchingResultRead(status, rows, progress.fundsSettledThrough, progress.resultIssued, required, cursor)
        }
        if (fromSequence <= progress.fundsSettledThrough) return reply(MatchingResultReadStatus.PRUNED)
        if (fromSequence >
            progress.resultIssued
        ) {
            return reply(
                if (fromSequence ==
                    next(progress.resultIssued)
                ) {
                    MatchingResultReadStatus.BATCH
                } else {
                    MatchingResultReadStatus.INVALID_QUERY
                },
            )
        }
        val rows = ArrayList<MatchingResultEnvelope>()
        var bytes = 0L
        var sequence: Long? = fromSequence
        while (sequence != null && sequence <= progress.resultIssued && rows.size < maxEntries) {
            val result = checkNotNull(progress.outstanding[sequence])
            val charge = result.canonicalBytes
            if (charge >
                maxBytes - bytes
            ) {
                return if (rows.isEmpty()) {
                    reply(
                        MatchingResultReadStatus.BATCH_TOO_SMALL,
                        required = charge,
                    )
                } else {
                    reply(MatchingResultReadStatus.BATCH, rows)
                }
            }
            rows.add(result)
            bytes = Math.addExact(bytes, charge)
            sequence = next(sequence)
        }
        return reply(MatchingResultReadStatus.BATCH, rows)
    }

    fun acknowledgeFundsSettled(ack: MatchingFundsSettledAck): MatchingResultAck {
        val state = usable()
        if (!configuration.matches(ack.stream)) return MatchingResultAck(MatchingResultAckStatus.INVALID_STREAM, 0)
        val progress = state.markets.getValue(ack.stream.marketId)

        fun reply(status: MatchingResultAckStatus) = MatchingResultAck(status, progress.fundsSettledThrough)
        if (ack.context != configuration.context(ack.stream.marketId)) return reply(MatchingResultAckStatus.INVALID_POLICY)
        if (ack.through < 0) return reply(MatchingResultAckStatus.INVALID_SEQUENCE)
        if (ack.through > progress.resultIssued) return reply(MatchingResultAckStatus.FUTURE_ACK)
        if (ack.through <= progress.fundsSettledThrough) return reply(MatchingResultAckStatus.ACKNOWLEDGED)
        val retired = progress.outstanding.values.takeWhile { it.resultSequence <= ack.through }
        check(
            retired.isNotEmpty() && retired.first().resultSequence == Math.addExact(progress.fundsSettledThrough, 1) &&
                retired.last().resultSequence == ack.through,
        )
        var expected = progress.fundsSettledThrough
        var bytes = 0L
        for (result in retired) {
            check(result.resultSequence == Math.addExact(expected, 1))
            expected = result.resultSequence
            bytes = Math.addExact(bytes, result.canonicalBytes)
        }
        val remainingBytes = Math.subtractExact(progress.resultBytes, bytes)
        val totalBytes = Math.subtractExact(state.resultBytes, bytes)
        val count = Math.subtractExact(state.resultEntries, retired.size)
        mutate {
            for (result in retired) check(progress.outstanding.remove(result.resultSequence) === result)
            progress.fundsSettledThrough = ack.through
            progress.settledEngineSequence = retired.last().engineSequenceAfter
            progress.resultBytes = remainingBytes
            state.resultBytes = totalBytes
            state.resultEntries = count
        }
        return reply(MatchingResultAckStatus.ACKNOWLEDGED)
    }

    fun restingOrders(marketId: MarketId): List<RestingOrderSnapshot> {
        configuration.stream(marketId)
        return usable().engine.restingOrders(marketId)
    }

    fun lastEngineSequence(marketId: MarketId): Long {
        configuration.stream(marketId)
        return usable().engine.lastEngineSequence(marketId)
    }

    fun recoveryState(): MatchingRoleRecoveryState {
        val state = usable()
        return MatchingRoleRecoveryState(
            configuration,
            TradingLifecycle.RUNNING,
            state.engine.activeCheckpoint(),
            state.active.values.toList(),
            configuration.markets.map { context ->
                val progress = state.markets.getValue(context.market.marketId)
                MatchingStreamRecovery(
                    configuration.stream(context.market.marketId),
                    progress.appliedThrough,
                    progress.lastSourceAdmissionSequence,
                    progress.resultIssued,
                    progress.fundsSettledThrough,
                    progress.settledEngineSequence,
                    progress.outstanding.values.toList(),
                    progress.resultBytes,
                    progress.activeOrders,
                    progress.activeBytes,
                )
            },
            state.resultEntries,
            state.resultBytes,
            state.active.size,
            state.activeBytes,
        )
    }

    /** 복원 실패 자료를 실행 상태로 공개하지 않는다. 임시 엔진이 모든 관계 검사를 통과한 뒤 게시한다. */
    fun restore(saved: MatchingRoleRecoveryState) {
        check(!isBlocked && data == null) { "matching restore requires an empty owner" }
        try {
            require(saved.configuration == configuration && saved.lifecycle == TradingLifecycle.RUNNING) {
                "matching recovery configuration or lifecycle differs"
            }
            val restored = Data(configuration)
            validateRecovery(saved, restored)
            data = restored
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun validateRecovery(
        saved: MatchingRoleRecoveryState,
        restored: Data,
    ) {
        require(
            saved.streams.map {
                it.stream
            } ==
                configuration.markets.map {
                    configuration.stream(
                        it.market.marketId,
                    )
                },
        ) { "matching recovery stream coverage or order differs" }
        require(saved.active.size <= configuration.limits.maxActiveOrders)
        require(
            saved.matching.markets.all { it.marketId in restored.markets } &&
                saved.matching.markets
                    .map { it.marketId }
                    .distinct()
                    .size == saved.matching.markets.size,
        )
        require(
            saved.matching.markets.map {
                it.marketId.value
            } ==
                saved.matching.markets
                    .map { it.marketId.value }
                    .sorted(),
        ) { "matching checkpoint market order differs" }
        restored.engine.restoreActiveCheckpoint(saved.matching)
        for (row in saved.streams) {
            val progress = restored.markets.getValue(row.stream.marketId)
            val local = configuration.limits.marketLimits.getValue(row.stream.marketId)
            require(row.appliedThrough >= 0 && row.resultIssued == row.appliedThrough && row.fundsSettledThrough in 0..row.resultIssued)
            require(
                row.lastSourceAdmissionSequence >= row.appliedThrough &&
                    (row.appliedThrough == 0L) == (row.lastSourceAdmissionSequence == 0L),
            )
            require(row.settledEngineSequence >= 0 && row.settledEngineSequence <= restored.engine.lastEngineSequence(row.stream.marketId))
            require(row.fundsSettledThrough != 0L || row.settledEngineSequence == 0L) {
                "initial settlement frontier cannot contain past matching events"
            }
            require(
                row.outstanding.size <= local.maxResultEntries &&
                    row.outstanding.size.toLong() == row.resultIssued - row.fundsSettledThrough,
            )
            var previous = row.fundsSettledThrough
            var engine = row.settledEngineSequence
            var source = 0L
            var bytes = 0L
            for (result in row.outstanding) {
                MatchingInputValidation.requireResult(configuration, result)
                require(result.input.stream == row.stream && result.resultSequence == Math.addExact(previous, 1))
                require(result.engineSequenceBefore == engine && result.input.admissionSequence > source)
                previous = result.resultSequence
                source = result.input.admissionSequence
                engine = result.engineSequenceAfter
                bytes = Math.addExact(bytes, result.canonicalBytes)
                progress.outstanding[result.resultSequence] = result
            }
            require(engine == restored.engine.lastEngineSequence(row.stream.marketId)) { "matching recovery event frontier differs" }
            require(row.outstanding.isEmpty() || source == row.lastSourceAdmissionSequence)
            require(bytes == row.resultBytes && bytes <= local.maxResultBytes)
            progress.appliedThrough = row.appliedThrough
            progress.lastSourceAdmissionSequence = row.lastSourceAdmissionSequence
            progress.resultIssued = row.resultIssued
            progress.fundsSettledThrough = row.fundsSettledThrough
            progress.settledEngineSequence = row.settledEngineSequence
            progress.resultBytes = bytes
            restored.resultEntries = Math.addExact(restored.resultEntries, row.outstanding.size)
            restored.resultBytes = Math.addExact(restored.resultBytes, bytes)
        }
        for (input in saved.active) {
            MatchingInputValidation.requireValid(configuration, input)
            val progress = restored.markets.getValue(input.stream.marketId)
            require(input.dispatchSequence <= progress.appliedThrough && input.admissionSequence <= progress.lastSourceAdmissionSequence)
            require(restored.active.put(input.stream.marketId to input.order.orderId, input) == null)
            progress.outstanding[input.dispatchSequence]?.let { require(it.input == input) }
            val charge = MatchingResultCharge.active(input)
            progress.activeOrders = Math.addExact(progress.activeOrders, 1)
            progress.activeBytes = Math.addExact(progress.activeBytes, charge)
            restored.activeBytes = Math.addExact(restored.activeBytes, charge)
        }
        for (row in saved.streams) {
            val progress = restored.markets.getValue(row.stream.marketId)
            val local = configuration.limits.marketLimits.getValue(row.stream.marketId)
            require(progress.activeOrders == row.activeOrders && progress.activeBytes == row.activeBytes)
            require(progress.activeOrders <= local.maxActiveOrders && progress.activeBytes <= local.maxActiveBytes)
            val book = restored.engine.restingOrders(row.stream.marketId)
            require(book.size == progress.activeOrders)
            var previous: RestingOrderSnapshot? = null
            for (order in book) {
                val original = requireNotNull(restored.active[row.stream.marketId to order.orderId]) { "missing matching active origin" }
                requireBookOrigin(order, original)
                previous?.let { prior ->
                    if (prior.side == order.side && prior.price == order.price) {
                        require(
                            restored.active.getValue(row.stream.marketId to prior.orderId).marketAdmissionSequence <
                                original.marketAdmissionSequence,
                        ) { "matching recovery FIFO differs" }
                    }
                }
                previous = order
            }
            validateSuffix(row, book, restored.active)
        }
        require(
            saved.active
                .map { it.stream to it.dispatchSequence }
                .distinct()
                .size == saved.active.size,
        )
        require(
            saved.resultEntries == restored.resultEntries && saved.resultBytes == restored.resultBytes &&
                saved.activeOrders == restored.active.size &&
                saved.activeBytes == restored.activeBytes,
        )
        require(
            saved.resultEntries <= configuration.limits.maxResultEntries && saved.resultBytes <= configuration.limits.maxResultBytes &&
                saved.activeBytes <= configuration.limits.maxActiveBytes,
        )
    }

    /** 현재 주문장에서 미정산 suffix를 되감아 각 결과를 다시 계산한다. 외부 자금이나 실제 실행 owner는 변경하지 않는다. */
    private fun validateSuffix(
        row: MatchingStreamRecovery,
        book: List<RestingOrderSnapshot>,
        active: Map<Pair<MarketId, OrderId>, FundsOrderDispatch>,
    ) {
        val remaining = book.associate { it.orderId to it.remainingQuantity }.toMutableMap()
        val origins = active.filterKeys { it.first == row.stream.marketId }.mapKeys { it.key.second }.toMutableMap()
        for (result in row.outstanding.asReversed()) {
            if (result.outcome == MatchingOutcome.NOT_EXECUTED) {
                require(result.input.order.orderId !in remaining)
                continue
            }
            for (effect in result.effects) {
                if (effect.disposition == MatchingOrderDisposition.RESTING) {
                    require(remaining[effect.orderId] == effect.afterQuantity && origins[effect.orderId] == effect.origin) {
                        "matching recovery effect tail differs"
                    }
                } else {
                    require(effect.orderId !in remaining) { "terminal matching effect remains active" }
                }
            }
            val taker = result.effects.first()
            remaining.remove(taker.orderId)
            origins.remove(taker.orderId)
            for (effect in result.effects.drop(1)) {
                remaining[effect.orderId] = effect.beforeQuantity
                origins[effect.orderId] = effect.origin
            }
        }
        val beforeOrders =
            origins.values
                .sortedWith(
                    compareBy<FundsOrderDispatch> { if (it.order.side == Side.BUY) 0 else 1 }
                        .thenComparator { left, right ->
                            if (left.order.side ==
                                Side.BUY
                            ) {
                                right.order.price.compareTo(left.order.price)
                            } else {
                                left.order.price.compareTo(right.order.price)
                            }
                        }.thenBy { it.marketAdmissionSequence },
                ).map { origin ->
                    require(origin.dispatchSequence <= row.fundsSettledThrough)
                    MatchingCheckpoint.Order(
                        origin.order.orderId,
                        origin.order.userId,
                        origin.order.side,
                        origin.order.price,
                        origin.order.quantity,
                        remaining.getValue(origin.order.orderId),
                    )
                }
        val engine = MatchingEngine()
        if (row.settledEngineSequence ==
            0L
        ) {
            require(beforeOrders.isEmpty())
        } else {
            engine.restoreActiveCheckpoint(
                MatchingCheckpoint(listOf(MatchingCheckpoint.Market(row.stream.marketId, row.settledEngineSequence, beforeOrders))),
            )
        }
        for (result in row.outstanding) {
            if (result.outcome == MatchingOutcome.NOT_EXECUTED) {
                validateRejection(result, engine, origins)
                continue
            }
            val plan = engine.prepareSubmit(MatchingInputValidation.command(result.input))
            require(plan.events == result.events) { "matching recovery result does not match its book prefix" }
            val expected = effects(result.input, plan, origins)
            require(expected == result.effects) { "matching recovery effects differ" }
            engine.apply(plan)
            for (effect in expected) {
                if (effect.disposition ==
                    MatchingOrderDisposition.RESTING
                ) {
                    origins[effect.orderId] = effect.origin
                } else {
                    origins.remove(effect.orderId)
                }
                if (effect.disposition ==
                    MatchingOrderDisposition.TERMINAL
                ) {
                    engine.releaseTerminalOrderIdentity(row.stream.marketId, effect.orderId)
                }
            }
        }
        require(engine.restingOrders(row.stream.marketId) == book)
    }

    private fun validateRejection(
        result: MatchingResultEnvelope,
        engine: MatchingEngine,
        origins: Map<OrderId, FundsOrderDispatch>,
    ) {
        val limit = minOf(configuration.limits.maxResultEvents.toLong(), configuration.limits.maxSingleResultBytes / 32).toInt()
        val prepared = engine.prepareSubmitBounded(MatchingInputValidation.command(result.input), limit)
        require(prepared !is BoundedSubmitPreparation.SequenceExhausted) { "exhausted input cannot issue a rejection result" }
        val plan = (prepared as? BoundedSubmitPreparation.Prepared)?.plan
        val candidate =
            plan?.let {
                MatchingResultEnvelope(
                    result.stream,
                    result.resultSequence,
                    result.input,
                    MatchingOutcome.MATCHED,
                    null,
                    result.engineSequenceBefore,
                    it.lastEngineSequence,
                    it.events,
                    effects(result.input, it, origins),
                )
            }
        val tooLarge = candidate == null || candidate.canonicalBytes > configuration.limits.maxSingleResultBytes
        if (result.reason == MatchingNotExecutedReason.RESULT_TOO_LARGE) {
            require(tooLarge) { "result-too-large rejection fits the permanent budget" }
            return
        }
        require(!tooLarge && candidate != null) { "active-order rejection conceals an oversized result" }
        val removed = candidate.effects.drop(1).filter { it.disposition == MatchingOrderDisposition.TERMINAL }
        val add = candidate.effects.first().disposition == MatchingOrderDisposition.RESTING
        val afterCount = origins.size.toLong() - removed.size + (if (add) 1 else 0)
        var afterBytes = 0L
        for (origin in origins.values) afterBytes = Math.addExact(afterBytes, MatchingResultCharge.active(origin))
        for (effect in removed) afterBytes = Math.subtractExact(afterBytes, MatchingResultCharge.active(effect.origin))
        if (add) afterBytes = Math.addExact(afterBytes, MatchingResultCharge.active(result.input))
        val local = configuration.limits.marketLimits.getValue(result.input.stream.marketId)
        var otherCount = 0L
        var otherBytes = 0L
        for ((market, limits) in configuration.limits.marketLimits) {
            if (market != result.input.stream.marketId) {
                otherCount = Math.addExact(otherCount, minOf(limits.maxActiveOrders, configuration.limits.maxActiveOrders).toLong())
                otherBytes = Math.addExact(otherBytes, minOf(limits.maxActiveBytes, configuration.limits.maxActiveBytes))
            }
        }
        // 서로 다른 정산 frontier의 snapshot만으로 다른 마켓의 당시 점유를 알아낼 수는 없다.
        // 로컬 초과를 확인하거나, 고정된 다른 마켓 한도상 공통 초과가 가능한지까지만 검사한다.
        require(
            afterCount > local.maxActiveOrders || afterBytes > local.maxActiveBytes ||
                afterCount > configuration.limits.maxActiveOrders - otherCount ||
                afterBytes > configuration.limits.maxActiveBytes - otherBytes,
        ) { "active-order rejection cannot exceed configured capacity" }
    }

    private fun requireBookOrigin(
        order: RestingOrderSnapshot,
        input: FundsOrderDispatch,
    ) {
        require(
            order.orderId == input.order.orderId && order.userId == input.order.userId && order.side == input.order.side &&
                order.price == input.order.price &&
                order.originalQuantity == input.order.quantity,
        )
        require(order.remainingQuantity.value > 0 && order.remainingQuantity <= input.order.quantity)
        configuration.context(input.stream.marketId).market.requireOrderUnits(order.price, order.remainingQuantity)
    }

    private fun matched(
        state: Data,
        input: FundsOrderDispatch,
        plan: SubmitOrderPlan,
        before: Long,
    ): MatchingResultEnvelope {
        val origins = plan.fills.associate { fill -> fill.orderId to checkNotNull(state.active[input.stream.marketId to fill.orderId]) }
        return MatchingResultEnvelope(
            configuration.resultStream(input.stream.marketId),
            input.dispatchSequence,
            input,
            MatchingOutcome.MATCHED,
            null,
            before,
            plan.lastEngineSequence,
            plan.events,
            effects(input, plan, origins),
        )
    }

    private fun effects(
        input: FundsOrderDispatch,
        plan: SubmitOrderPlan,
        origins: Map<OrderId, FundsOrderDispatch>,
    ): List<MatchingOrderEffect> {
        var remaining = input.order.quantity.value
        val makers =
            plan.fills.map { fill ->
                val origin = checkNotNull(origins[fill.orderId])
                check(
                    origin.order.userId == fill.userId && origin.order.side == fill.side && origin.order.price == fill.price &&
                        origin.order.quantity == fill.originalQuantity,
                )
                remaining = Math.subtractExact(remaining, fill.filledQuantity.value)
                val after = Math.subtractExact(fill.remainingQuantity.value, fill.filledQuantity.value)
                MatchingOrderEffect(
                    origin,
                    fill.remainingQuantity,
                    Quantity(after),
                    if (after ==
                        0L
                    ) {
                        MatchingOrderDisposition.TERMINAL
                    } else {
                        MatchingOrderDisposition.RESTING
                    },
                )
            }
        return listOf(
            MatchingOrderEffect(
                input,
                input.order.quantity,
                Quantity(remaining),
                if (remaining ==
                    0L
                ) {
                    MatchingOrderDisposition.TERMINAL
                } else {
                    MatchingOrderDisposition.RESTING
                },
            ),
        ) +
            makers
    }

    private fun rejected(
        input: FundsOrderDispatch,
        before: Long,
        reason: MatchingNotExecutedReason,
    ): MatchingResultEnvelope =
        MatchingResultEnvelope(
            configuration.resultStream(input.stream.marketId),
            input.dispatchSequence,
            input,
            MatchingOutcome.NOT_EXECUTED,
            reason,
            before,
            before,
            emptyList(),
            listOf(MatchingOrderEffect(input, input.order.quantity, input.order.quantity, MatchingOrderDisposition.NOT_EXECUTED)),
        )

    private fun usable(): Data {
        check(!isBlocked) { "matching owner is blocked" }
        return checkNotNull(data) { "matching owner is not initialized" }
    }

    private inline fun mutate(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun next(value: Long): Long? = if (value == Long.MAX_VALUE) null else value + 1
}

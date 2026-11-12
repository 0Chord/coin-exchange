package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import java.time.Instant

/** 매칭 엔진 없이 공통 잔고·예약·접수와 정산 효과를 배타적으로 쓰는 금융 소유자다. */
class BoundedFundingState(
    private val admissionCalculator: OrderAdmissionCalculator,
    private val expectedManifest: TradingClusterManifest? = null,
    private val dispatchConfiguration: FundsDispatchConfiguration? = null,
    private val resultLimits: FundsResultLimits = FundsResultLimits(),
) {
    private var bounded: BoundedTradingData? = null
    private var dispatch: FundsDispatchData? = dispatchConfiguration?.let(::FundsDispatchData)
    private var results: FundsResultData? = dispatchConfiguration?.let { FundsResultData(it, resultLimits) }
    private val assetTotals = linkedMapOf<AssetId, Long>()

    init {
        require(dispatchConfiguration == null || expectedManifest == dispatchConfiguration.manifest) {
            "funds dispatch requires the assembled financial manifest"
        }
    }

    val lifecycle: TradingLifecycle get() = bounded?.lifecycle ?: TradingLifecycle.UNINITIALIZED
    var isBlocked: Boolean = false
        private set

    fun initialize(manifest: TradingClusterManifest): InitializationReceipt {
        check(!isBlocked) { "bounded initialization requires a usable bounded owner" }
        requireExpectedManifest(manifest)
        bounded?.let {
            require(it.manifest == manifest) { "cluster manifest cannot change" }
            return InitializationReceipt.ALREADY_INITIALIZED
        }
        validateBoundedManifest(manifest)
        bounded = BoundedTradingData(manifest)
        return InitializationReceipt.INITIALIZED
    }

    private fun validateBoundedManifest(manifest: TradingClusterManifest) {
        require(manifest.markets.size <= manifest.limits.maxMarkets)
        val assets = manifest.markets.flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }.toSet()
        require(assets.size <= manifest.limits.maxAssets)
        for (context in manifest.markets) {
            require(context.feePolicySnapshot.productType == com.exchange.core.fee.FeeProductType.SPOT)
            require(admissionCalculator.validIdentifier(context.market.marketId.value))
            require(admissionCalculator.validIdentifier(context.market.baseAssetId.value, accountPart = true))
            require(admissionCalculator.validIdentifier(context.market.quoteAssetId.value, accountPart = true))
            require(tradingTextBytes(context.market.marketId.value) <= manifest.limits.maxIdentifierBytes)
            require(tradingTextBytes(context.market.baseAssetId.value) <= manifest.limits.maxIdentifierBytes)
            require(tradingTextBytes(context.market.quoteAssetId.value) <= manifest.limits.maxIdentifierBytes)
        }
    }

    fun rotateEpoch(
        userId: UserId,
        channel: Int,
        expectedEpoch: Long,
    ): EpochReceipt {
        val current = runningBounded()
        if (!admissionCalculator.validIdentifier(userId.value, accountPart = true) ||
            tradingTextBytes(userId.value) > current.manifest.limits.maxIdentifierBytes
        ) {
            return EpochReceipt(EpochStatus.INVALID_CHANNEL, 0)
        }
        if (userId !in current.accounts && current.accounts.size >= current.manifest.limits.maxAccounts) {
            return EpochReceipt(EpochStatus.RETRYABLE_CAPACITY, 0)
        }
        return boundedMutation {
            val result = current.inbox.rotateEpoch(userId, channel, expectedEpoch)
            if (result.status == EpochStatus.ISSUED || result.status == EpochStatus.ALREADY_ISSUED ||
                result.status == EpochStatus.SUPERSEDED
            ) {
                current.accounts.add(userId)
            }
            result
        }
    }

    fun acknowledgeReceipts(
        userId: UserId,
        channel: Int,
        epoch: Long,
        through: Long,
    ): AckReceipt {
        val current = runningBounded()
        return boundedMutation { current.inbox.acknowledge(userId, channel, epoch, through) }
    }

    fun admit(
        token: RequestToken,
        intent: OrderIntent,
        at: Instant,
    ): AdmissionReply {
        val current = runningBounded()
        when (val lookup = current.inbox.lookup(token, intent)) {
            is InboxLookup.Repeated -> return AdmissionReply.Recorded(lookup.receipt, newlyRecorded = false)
            is InboxLookup.Rejected -> return AdmissionReply.Refused(lookup.reason.name)
            InboxLookup.Ready -> Unit
        }
        val context = current.contexts[intent.marketId]
        if (context == null || intent.expectedConfigurationVersion != current.manifest.configurationVersion) {
            return AdmissionReply.Refused("EXECUTION_CONTEXT_MISMATCH")
        }
        val progress = current.markets.getValue(intent.marketId)
        val limits = current.manifest.limits
        val baseKey = TradingBalanceKey(token.userId, context.market.baseAssetId)
        val quoteKey = TradingBalanceKey(token.userId, context.market.quoteAssetId)
        val baseExists = baseKey in current.balances
        val quoteExists = quoteKey in current.balances
        val requiredKeys = (if (baseExists) 0 else 1) + (if (quoteExists) 0 else 1)
        if (current.liveBySequence.size >= limits.maxActiveOrders || progress.activeCount >= limits.maxMarketActiveOrders ||
            current.pendingCount >= limits.maxPendingOrders || progress.pending.size >= limits.maxMarketPendingOrders ||
            requiredKeys > limits.maxBalanceKeys - current.balances.size ||
            (token.userId !in current.accounts && current.accounts.size >= limits.maxAccounts)
        ) {
            return AdmissionReply.Refused("RETRYABLE_CAPACITY")
        }
        val sequence = Math.addExact(current.lastAdmissionSequence, 1L)
        val marketSequence = Math.addExact(progress.lastAssigned, 1L)
        val identifiers = TradingIdentifiers.admission(current.manifest.lineage, sequence)
        val input =
            OrderAdmissionInput(
                intent.marketId,
                identifiers.orderId,
                token.userId,
                intent.side,
                intent.orderType,
                intent.timeInForce,
                intent.price,
                intent.quantity,
            )
        if (dispatch?.canAdmit(input) == false) return AdmissionReply.Refused("RETRYABLE_CAPACITY")
        val reservedKey = if (intent.side == Side.BUY) quoteKey else baseKey
        val balance = current.balances[reservedKey] ?: Balance(token.userId, reservedKey.assetId, Amount.ZERO, Amount.ZERO)
        val calculation =
            admissionCalculator.calculateTransition(
                input,
                context.market,
                context.feePolicySnapshot,
                balance,
            )
        if (calculation is AdmissionTransitionResult.Rejected) {
            val receipt =
                AdmissionReceipt(AdmissionCode.REJECTED, reason = calculation.reason, insufficientBalance = calculation.insufficientBalance)
            boundedMutation { current.inbox.remember(token, intent, receipt) }
            return AdmissionReply.Recorded(receipt, newlyRecorded = true)
        }
        val candidate = calculation as AdmissionTransitionResult.Calculated
        check(candidate.hasPreviousBalance(balance))
        val live = BoundedLiveOrder(sequence, marketSequence, input, candidate.reservation, LiveOrderPhase.PENDING)
        val receipt = AdmissionReceipt(AdmissionCode.ACCEPTED, input.orderId, sequence, marketSequence)
        val delivery = dispatch?.prepare(live.snapshot())
        boundedMutation {
            current.accounts.add(token.userId)
            if (!baseExists) current.balances[baseKey] = Balance(baseKey.userId, baseKey.assetId, Amount.ZERO, Amount.ZERO)
            if (!quoteExists) current.balances[quoteKey] = Balance(quoteKey.userId, quoteKey.assetId, Amount.ZERO, Amount.ZERO)
            current.usedBalances.add(baseKey)
            current.usedBalances.add(quoteKey)
            current.balances[reservedKey] = candidate.nextBalance
            current.liveBySequence[sequence] = live
            current.liveByOrder[input.marketId to input.orderId] = live
            progress.pending.addLast(live)
            progress.activeCount++
            current.pendingCount++
            progress.lastAssigned = marketSequence
            current.lastAdmissionSequence = sequence
            current.inbox.remember(token, intent, receipt)
            delivery?.let { checkNotNull(dispatch).record(it) }
        }
        return AdmissionReply.Recorded(receipt, newlyRecorded = true)
    }

    fun prepareDevelopmentBalance(
        opening: OpeningBalance,
        at: Instant,
    ): OpeningBalanceResult {
        val current = runningBounded()
        current.preparations[opening.preparationId]?.let {
            if (it.opening !=
                opening
            ) {
                throw com.exchange.core.ledger.OpeningBalanceRequestConflictException(
                    "same preparation ID has different funding content",
                )
            }
            return it.copy(alreadyPrepared = true)
        }
        val limits = current.manifest.limits
        if (opening.assetId !in current.assets) throw TradingFundingInputException(TradingFundingRejection.UNKNOWN_ASSET)
        if (tradingTextBytes(opening.userId.value) > limits.maxIdentifierBytes ||
            tradingTextBytes(opening.preparationId) > limits.maxIdentifierBytes
        ) {
            throw TradingFundingInputException(TradingFundingRejection.INVALID_IDENTIFIER)
        }
        val key = TradingBalanceKey(opening.userId, opening.assetId)
        val old = current.balances[key] ?: Balance(opening.userId, opening.assetId, Amount.ZERO, Amount.ZERO)
        opening.requireUnused(old, key in current.usedBalances)
        val bytes = preparationBytes(opening)
        if (current.preparations.size >= limits.maxPreparations || bytes > limits.maxPreparationBytes - current.preparationBytes ||
            (key !in current.balances && current.balances.size >= limits.maxBalanceKeys) ||
            (opening.userId !in current.accounts && current.accounts.size >= limits.maxAccounts)
        ) {
            throw TradingCapacityException("development funding capacity exceeded")
        }
        val sequence = Math.addExact(current.lastPreparationSequence, 1L)
        val ledgerId = TradingIdentifiers.opening(current.manifest.lineage, sequence)
        val nextTotal =
            if (dispatch != null) {
                val previous = assetTotals[opening.assetId] ?: 0L
                if (opening.amount.value > Long.MAX_VALUE - previous) {
                    throw TradingFundingInputException(TradingFundingRejection.ASSET_TOTAL_LIMIT)
                }
                previous + opening.amount.value
            } else {
                null
            }
        val next = old.credit(opening.amount)
        opening.transaction(ledgerId, at) // 원장 항목 그래프를 보관하지 않고 같은 OPENING의 균형을 검증한다.
        val result = OpeningBalanceResult(opening, ledgerId, at, alreadyPrepared = false)
        boundedMutation {
            current.accounts.add(opening.userId)
            current.balances[key] = next
            current.usedBalances.add(key)
            current.preparations[opening.preparationId] = result
            current.preparationBytes = Math.addExact(current.preparationBytes, bytes)
            current.lastPreparationSequence = sequence
            nextTotal?.let { assetTotals[opening.assetId] = it }
        }
        return result
    }

    /** 조회는 전달 자료를 소비하거나 예약을 해제하지 않는다. */
    fun pendingDispatch(
        stream: FundsDispatchStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): FundsDispatchRead {
        runningBounded()
        return checkNotNull(dispatch) { "dispatch is only available to the funds role" }
            .read(stream, fromSequence, maxEntries, maxBytes)
    }

    /** 상대의 연속 업무 적용 확인은 전달 보존만 회수한다. 금융 실행 완료와 별개다. */
    fun acknowledgeMatchingApplied(ack: FundsMatchingAppliedAck): FundsDispatchAck {
        runningBounded()
        val currentDispatch = checkNotNull(dispatch) { "dispatch is only available to the funds role" }
        return boundedMutation { currentDispatch.acknowledge(ack) }
    }

    /** 자금에 확정한 결과까지의 누적 확인이다. 조회만으로 자료를 회수하지 않는다. */
    fun matchingSettlementAck(stream: MatchingResultStream): MatchingFundsSettledAck? {
        runningBounded()
        val currentResults = checkNotNull(results) { "result settlement requires a funds role" }
        return if (currentResults.matches(stream)) currentResults.ack(stream.marketId) else null
    }

    /** 전체 후보를 검사한 뒤 금융·결과 prefix·전달 확인을 한 소유자의 변경으로 반영한다. */
    fun applyMatchingResult(
        result: MatchingResultEnvelope,
        appliedAt: Instant,
        settlementCalculator: OrderSettlementCalculator,
    ): FundsResultReply {
        val current = runningBounded()
        val configuration = checkNotNull(dispatchConfiguration) { "result settlement requires a funds role" }
        val currentResults = checkNotNull(results)
        if (!currentResults.matches(result.stream)) return FundsResultReply(FundsResultStatus.INVALID_STREAM)
        val context = configuration.context(result.stream.marketId)
        val inputStream = configuration.stream(result.stream.marketId)
        if (result.resultSequence <= 0 || result.input.dispatchSequence != result.resultSequence ||
            result.input.marketAdmissionSequence != result.resultSequence || result.input.admissionSequence <= 0 ||
            result.effects.size.toLong() > resultLimits.maxResultEvents.toLong() + 1 || result.eventCount > resultLimits.maxResultEvents
        ) {
            return FundsResultReply(FundsResultStatus.INVALID_RESULT)
        }
        if (result.input.stream != inputStream || result.input.context != context ||
            result.effects.any { it.origin.stream != inputStream || it.origin.context != context }
        ) {
            return FundsResultReply(FundsResultStatus.INVALID_STREAM)
        }

        fun validApprovalIdentity(input: FundsOrderDispatch): Boolean =
            input.admissionSequence > 0 && input.dispatchSequence > 0 && input.marketAdmissionSequence == input.dispatchSequence &&
                input.order.marketId == result.stream.marketId &&
                input.order.orderId == TradingIdentifiers.order(configuration.manifest.lineage, input.admissionSequence)
        if (!validApprovalIdentity(result.input) || result.effects.any { !validApprovalIdentity(it.origin) }) {
            return FundsResultReply(FundsResultStatus.INVALID_RESULT)
        }
        try {
            if (result.canonicalBytes > resultLimits.maxSingleResultBytes) return FundsResultReply(FundsResultStatus.INVALID_RESULT)
        } catch (_: IllegalArgumentException) {
            return FundsResultReply(FundsResultStatus.INVALID_RESULT)
        } catch (_: ArithmeticException) {
            return FundsResultReply(FundsResultStatus.INVALID_RESULT)
        }
        currentResults.repeated(result)?.let { return it }
        val next = Math.addExact(currentResults.through(result.stream.marketId), 1L)
        if (result.resultSequence != next) return FundsResultReply(FundsResultStatus.GAP, expectedSequence = next)
        val progress = current.markets.getValue(result.stream.marketId)
        val candidate =
            try {
                currentResults.requireResult(result)
                require(result.engineSequenceBefore == currentResults.engine(result.stream.marketId)) { "result engine boundary differs" }
                val taker = requireNotNull(progress.pending.firstOrNull()) { "result has no pending financial order" }
                require(taker.admissionSequence == result.input.admissionSequence && taker.marketAdmissionSequence == result.resultSequence)
                val participants =
                    result.effects.mapIndexed { index, effect ->
                        val live =
                            requireNotNull(
                                current.liveByOrder[effect.origin.order.marketId to effect.orderId],
                            ) { "unknown financial order" }
                        require(
                            (index == 0 && live === taker && live.phase == LiveOrderPhase.PENDING) ||
                                (index > 0 && live.phase == LiveOrderPhase.RESTING),
                        ) { "financial order phase differs" }
                        requireFundsOriginal(effect.origin, live.snapshot())
                        require(effect.beforeQuantity == live.reservation.remainingQuantity) { "financial quantity differs" }
                        live
                    }
                val trades = result.events.filterIsInstance<TradeExecuted>()
                val executions =
                    trades.map { trade ->
                        SettlementExecution(
                            trade.marketId,
                            trade.engineSequence,
                            trade.makerOrderId,
                            trade.takerOrderId,
                            trade.makerUserId,
                            trade.takerUserId,
                            trade.side,
                            trade.price,
                            trade.quantity,
                            TradingIdentifiers.settlement(current.manifest.lineage, context.ordinal, trade.engineSequence),
                            appliedAt,
                        )
                    }
                val settlement =
                    if (result.outcome == MatchingOutcome.NOT_EXECUTED) {
                        val previous = taker.reservation
                        require(
                            previous.remainingQuantity == previous.initialQuantity && previous.remainingAmount == previous.reservedAmount,
                        )
                        val key = TradingBalanceKey(previous.userId, previous.assetId)
                        val balance = requireNotNull(current.balances[key])
                        val released = previous.release()
                        val nextBalance = balance.release(previous.remainingAmount)
                        fundsResultReleaseTransaction(result, previous, appliedAt)
                        OrderSettlementCandidate(
                            taker.order,
                            context.market,
                            emptyList(),
                            listOf(OrderSettlementCandidate.BalanceChange(balance, nextBalance)),
                            listOf(OrderSettlementCandidate.ReservationChange(previous, released)),
                        )
                    } else {
                        validateNoFillBalances(taker.snapshot(), context)
                        settlementCalculator.calculate(
                            taker.order,
                            context.market,
                            executions,
                            participants.map { it.reservation },
                            participantBalances(participants.map { it.reservation }, context.market),
                        )
                    }
                val changes = settlement.reservationChanges.associateBy { it.next.orderId }
                for ((index, effect) in result.effects.withIndex()) {
                    val after = changes[effect.orderId]?.next ?: participants[index].reservation
                    require(after.remainingQuantity == effect.afterQuantity)
                    when (effect.disposition) {
                        MatchingOrderDisposition.RESTING -> require(after.status == OrderReservationStatus.ACTIVE)
                        MatchingOrderDisposition.TERMINAL -> require(after.status == OrderReservationStatus.SETTLED)
                        MatchingOrderDisposition.NOT_EXECUTED -> require(after.status == OrderReservationStatus.RELEASED)
                        MatchingOrderDisposition.CANCELLED -> error("cancellation effect in a submit result")
                    }
                }
                validateSettlementCurrent(settlement)
                val fee = prepareFeeRevenue(settlement, context.market.quoteAssetId)
                validateResultConservation(settlement, context.market.quoteAssetId, fee)
                FundsFinancialResultCandidate(taker, participants, settlement, fee, FundsResultReceipt(result, appliedAt))
            } catch (_: IllegalArgumentException) {
                return FundsResultReply(FundsResultStatus.INVALID_RESULT)
            } catch (_: IllegalStateException) {
                return FundsResultReply(FundsResultStatus.INVALID_RESULT)
            } catch (_: ArithmeticException) {
                return FundsResultReply(FundsResultStatus.INVALID_RESULT)
            }
        boundedMutation {
            for (change in candidate.settlement.balanceChanges) {
                current.balances[TradingBalanceKey(change.next.userId, change.next.assetId)] = change.next
            }
            for (change in candidate.settlement.reservationChanges) {
                current.liveByOrder.getValue(change.next.marketId to change.next.orderId).reservation = change.next
            }
            candidate.nextFeeRevenue?.let { current.feeRevenue[context.market.quoteAssetId] = it }
            if (result.effects.first().disposition == MatchingOrderDisposition.RESTING) candidate.taker.phase = LiveOrderPhase.RESTING
            for (live in candidate.participants) {
                if (live.reservation.status != OrderReservationStatus.ACTIVE) {
                    check(current.liveByOrder.remove(live.order.marketId to live.order.orderId) === live)
                    check(current.liveBySequence.remove(live.admissionSequence) === live)
                    progress.activeCount--
                    checkNotNull(dispatch).finish(live.order)
                }
            }
            check(progress.pending.removeFirst() === candidate.taker)
            current.pendingCount--
            progress.executedThrough = result.resultSequence
            currentResults.record(candidate.receipt)
            val confirmed = checkNotNull(dispatch).acknowledge(FundsMatchingAppliedAck(inputStream, result.input.dispatchSequence, context))
            check(confirmed.status == FundsDispatchAckStatus.ACKNOWLEDGED)
        }
        return FundsResultReply(FundsResultStatus.APPLIED, currentResults.ack(result.stream.marketId))
    }

    private data class FundsFinancialResultCandidate(
        val taker: BoundedLiveOrder,
        val participants: List<BoundedLiveOrder>,
        val settlement: OrderSettlementCandidate,
        val nextFeeRevenue: java.math.BigInteger?,
        val receipt: FundsResultReceipt,
    )

    private fun validateResultConservation(
        settlement: OrderSettlementCandidate,
        feeAsset: AssetId,
        nextFee: java.math.BigInteger?,
    ) {
        val deltas = linkedMapOf<AssetId, java.math.BigInteger>()
        for (change in settlement.balanceChanges) {
            val before =
                java.math.BigInteger
                    .valueOf(
                        change.previous.available.value,
                    ).add(java.math.BigInteger.valueOf(change.previous.hold.value))
            val after =
                java.math.BigInteger
                    .valueOf(change.next.available.value)
                    .add(java.math.BigInteger.valueOf(change.next.hold.value))
            deltas[change.next.assetId] = (deltas[change.next.assetId] ?: java.math.BigInteger.ZERO).add(after.subtract(before))
        }
        if (nextFee != null) {
            val previous = runningBounded().feeRevenue[feeAsset] ?: java.math.BigInteger.ZERO
            deltas[feeAsset] = (deltas[feeAsset] ?: java.math.BigInteger.ZERO).add(nextFee.subtract(previous))
        }
        require(deltas.values.all { it.signum() == 0 }) { "result financial conservation differs" }
    }

    fun fundsRecoveryState(): FundsRoleRecoveryState {
        val configuration = checkNotNull(dispatchConfiguration) { "funds recovery requires a funds role" }
        val financial = recoveryState()
        return FundsRoleRecoveryState(
            configuration,
            financial,
            checkNotNull(dispatch).snapshot(),
            assetTotals,
            checkNotNull(results).snapshot(),
        )
    }

    /** 모든 후보를 검사한 뒤 금융과 전달 상태를 함께 게시한다. 실패한 소유자는 재사용하지 않는다. */
    fun restoreFundsRecoveryState(
        saved: FundsRoleRecoveryState,
        settlementCalculator: OrderSettlementCalculator,
    ) {
        check(!isBlocked && bounded == null) { "funds restore requires an empty owner" }
        isBlocked = true
        try {
            val configuration = checkNotNull(dispatchConfiguration) { "funds recovery requires a funds role" }
            require(saved.configuration == configuration) { "funds recovery configuration differs" }
            val financial = saved.financial
            val candidate = BoundedFundingState(admissionCalculator, expectedManifest)
            candidate.restoreRecoveryState(financial, settlementCalculator)
            if (saved.results == null) {
                // 결과 상태가 없는 과거 v1은 최초 PENDING 형식만 허용한다.
                require(financial.markets.all { it.executedThrough == 0L }) { "legacy funds snapshot has settled progress" }
                require(financial.liveOrders.all { it.phase == LiveOrderPhase.PENDING }) { "legacy funds snapshot has settled orders" }
            }
            val totals = linkedMapOf<AssetId, java.math.BigInteger>()
            for (balance in financial.balances) {
                val amount =
                    java.math.BigInteger
                        .valueOf(balance.available.value)
                        .add(java.math.BigInteger.valueOf(balance.hold.value))
                totals[balance.assetId] = (totals[balance.assetId] ?: java.math.BigInteger.ZERO).add(amount)
            }
            for ((asset, fee) in financial.feeRevenueByAsset) {
                totals[asset] = (totals[asset] ?: java.math.BigInteger.ZERO).add(fee)
            }
            val reconstructed = linkedMapOf<AssetId, Long>()
            for ((asset, total) in totals) {
                require(total.signum() >= 0 && total <= java.math.BigInteger.valueOf(Long.MAX_VALUE)) { "asset total limit exceeded" }
                // 준비가 없던 0 잔고 행은 총량 기록을 새로 만들지 않는다.
                if (financial.preparations.any { it.opening.assetId == asset }) reconstructed[asset] = total.longValueExact()
            }
            require(saved.assetTotals == reconstructed) { "funds asset total counters differ" }
            val restoredDispatch = FundsDispatchData(configuration)
            restoredDispatch.restore(saved.dispatch, financial.liveOrders, financial.markets)
            val restoredResults = FundsResultData(configuration, resultLimits)
            saved.results?.let { restoredResults.restore(it, financial, saved.dispatch) }
            bounded = candidate.bounded
            dispatch = restoredDispatch
            results = restoredResults
            assetTotals.putAll(reconstructed)
            isBlocked = false
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    /** 통합 조율자가 읽는 값은 현재 가변 객체의 참조를 포함하지 않는다. */
    internal fun executionInput(command: ExecuteAdmission): BoundedFundingExecution? {
        check(dispatch == null) { "split funds execution requires the matching result protocol" }
        val current = runningBounded()
        require(command.lineage == current.manifest.lineage) { "execution lineage differs" }
        val context = requireNotNull(current.contexts[command.marketId]) { "execution market is not registered" }
        val progress = current.markets.getValue(command.marketId)
        require(command.marketAdmissionSequence > 0 && command.admissionSequence > 0)
        if (command.marketAdmissionSequence <= progress.executedThrough) return null
        require(command.marketAdmissionSequence == Math.addExact(progress.executedThrough, 1L)) { "execution is out of order" }
        val live = requireNotNull(progress.pending.firstOrNull()) { "execution has no pending order" }
        require(live.admissionSequence == command.admissionSequence && live.marketAdmissionSequence == command.marketAdmissionSequence) {
            "execution target differs from first pending order"
        }
        return BoundedFundingExecution(current.manifest, context, live.snapshot())
    }

    internal fun liveOrder(
        marketId: MarketId,
        orderId: OrderId,
    ): TradingLiveOrderSnapshot? = runningBounded().liveByOrder[marketId to orderId]?.snapshot()

    internal fun participantBalances(
        reservations: List<OrderReservation>,
        market: MarketDefinition,
    ): List<Balance> {
        val current = runningBounded()
        val keys = linkedSetOf<TradingBalanceKey>()
        for (reservation in reservations) {
            keys.add(TradingBalanceKey(reservation.userId, market.baseAssetId))
            keys.add(TradingBalanceKey(reservation.userId, market.quoteAssetId))
        }
        return keys.map { checkNotNull(current.balances[it]) { "participant balance capacity was not reserved" } }
    }

    internal fun validateSettlementCurrent(settlement: OrderSettlementCandidate) {
        val current = runningBounded()
        for (change in settlement.balanceChanges) {
            check(current.balances[TradingBalanceKey(change.previous.userId, change.previous.assetId)] == change.previous)
        }
        for (change in settlement.reservationChanges) {
            check(current.liveByOrder[change.previous.marketId to change.previous.orderId]?.reservation == change.previous)
        }
    }

    /** 검사를 마친 한 주문의 금융 효과와 진행만 함께 반영한다. 주문장은 통합 조율자가 소유한다. */
    internal fun applyExecution(
        input: BoundedFundingExecution,
        settlement: OrderSettlementCandidate,
        resting: Boolean,
        nextFeeRevenue: java.math.BigInteger?,
    ): List<OrderAdmissionInput> {
        check(dispatch == null) { "split funds execution requires the matching result protocol" }
        val current = runningBounded()
        check(input.manifest == current.manifest)
        val progress = current.markets.getValue(input.live.order.marketId)
        val live = checkNotNull(progress.pending.firstOrNull())
        check(live.snapshot() == input.live) { "financial execution input has changed" }
        check(settlement.order == live.order) { "financial settlement order differs" }
        validateSettlementCurrent(settlement)
        return boundedMutation {
            for (change in settlement.balanceChanges) {
                current.balances[TradingBalanceKey(change.next.userId, change.next.assetId)] = change.next
            }
            for (change in settlement.reservationChanges) {
                current.liveByOrder.getValue(change.next.marketId to change.next.orderId).reservation = change.next
            }
            nextFeeRevenue?.let { current.feeRevenue[input.context.market.quoteAssetId] = it }
            if (resting) live.phase = LiveOrderPhase.RESTING
            val terminal =
                settlement.reservationChanges.mapNotNull { change ->
                    current.liveByOrder
                        .getValue(change.next.marketId to change.next.orderId)
                        .takeIf { it.reservation.status != OrderReservationStatus.ACTIVE }
                }
            for (finished in terminal) {
                current.liveByOrder.remove(finished.order.marketId to finished.order.orderId)
                current.liveBySequence.remove(finished.admissionSequence)
                progress.activeCount--
            }
            check(progress.pending.removeFirst() === live)
            current.pendingCount--
            progress.executedThrough = live.marketAdmissionSequence
            terminal.map { it.order }
        }
    }

    internal fun block() {
        isBlocked = true
    }

    private fun requireExpectedManifest(manifest: TradingClusterManifest) {
        require(expectedManifest == null || expectedManifest == manifest) { "funding manifest differs from assembled configuration" }
    }

    internal fun validateNoFillBalances(
        live: TradingLiveOrderSnapshot,
        context: TradingMarketContext,
    ) {
        val current = runningBounded()
        val order = live.order
        val baseKey = TradingBalanceKey(order.userId, context.market.baseAssetId)
        val quoteKey = TradingBalanceKey(order.userId, context.market.quoteAssetId)
        val base = checkNotNull(current.balances[baseKey]) { "participant balance capacity was not reserved" }
        val quote = checkNotNull(current.balances[quoteKey]) { "participant balance capacity was not reserved" }
        check(base.userId == baseKey.userId && base.assetId == baseKey.assetId)
        check(quote.userId == quoteKey.userId && quote.assetId == quoteKey.assetId)
        val reserved = if (order.side == Side.BUY) quote else base
        check(reserved.hold >= live.reservation.remainingAmount)
    }

    internal fun validateReservationRelationship(
        live: TradingLiveOrderSnapshot,
        context: TradingMarketContext,
    ) {
        val order = live.order
        val reservation = live.reservation
        check(order.marketId == context.market.marketId && reservation.marketId == order.marketId && reservation.orderId == order.orderId)
        check(reservation.userId == order.userId && reservation.side == order.side && reservation.limitPrice == order.price)
        check(reservation.initialQuantity == order.quantity && reservation.feePolicySnapshot == context.feePolicySnapshot)
        check(reservation.status == OrderReservationStatus.ACTIVE)
    }

    internal fun prepareFeeRevenue(
        settlement: OrderSettlementCandidate,
        feeAsset: AssetId,
    ): java.math.BigInteger? {
        val current = runningBounded()
        // SPOT 정산에서는 두 참여자 모두에게 이 시장의 quote 자산으로 수수료를 부과한다.
        // 체결별 임시 맵이나 maker·taker 목록 없이 정확한 수익을 한 번만 누적한다.
        val transactionIds = hashSetOf<String>()
        val eventIds = hashSetOf<String>()
        return validateBoundedStage(TradingInvariantReason.FEE_REVENUE_INVALID, TradingExecutionPhase.RESULT_VALIDATION) {
            var feeDelta = java.math.BigInteger.ZERO
            var ledgerRevenue = java.math.BigInteger.ZERO
            for (fill in settlement.settlements) {
                check(transactionIds.add(fill.transaction.ledgerTransactionId) && eventIds.add(fill.transaction.sourceEventId))
                check(fill.makerPlan.feeAssetId == feeAsset && fill.takerPlan.feeAssetId == feeAsset)
                feeDelta =
                    feeDelta
                        .add(java.math.BigInteger.valueOf(fill.makerPlan.actualFeeAmount.value))
                        .add(java.math.BigInteger.valueOf(fill.takerPlan.actualFeeAmount.value))
                for (posting in fill.transaction.postings) {
                    if (posting.accountId == "SYSTEM:${posting.assetId.value}:FEE_REVENUE") {
                        check(posting.assetId == feeAsset && posting.side == com.exchange.core.ledger.LedgerPostingSide.CREDIT)
                        ledgerRevenue = ledgerRevenue.add(java.math.BigInteger.valueOf(posting.amount.value))
                    }
                }
            }
            check(feeDelta == ledgerRevenue) { "fee delta differs from ledger revenue" }
            if (settlement.settlements.isEmpty()) {
                null
            } else {
                val next = (current.feeRevenue[feeAsset] ?: java.math.BigInteger.ZERO).add(feeDelta)
                check(next.signum() >= 0 && next.bitLength() <= feeBitLimit(current.manifest.limits))
                if (next.signum() != 0) next else null
            }
        }
    }

    private inline fun <T> validateBoundedStage(
        reason: TradingInvariantReason,
        stage: TradingExecutionPhase,
        action: () -> T,
    ): T =
        try {
            action()
        } catch (failure: TradingInvariantViolation) {
            throw failure
        } catch (_: IllegalArgumentException) {
            throw TradingInvariantViolation(reason, stage)
        } catch (_: IllegalStateException) {
            throw TradingInvariantViolation(reason, stage)
        }

    fun quarantine(
        position: Long,
        reason: String,
    ) {
        check(!isBlocked)
        val current = checkNotNull(bounded) { "trading is not initialized" }
        require(position >= 0 && reason.isNotBlank() && reason.length <= 128)
        val incident = TradingIncident(position, reason)
        if (current.lifecycle == TradingLifecycle.QUARANTINED) {
            require(current.incident == incident) { "first quarantine incident cannot change" }
            return
        }
        boundedMutation {
            current.incident = incident
            current.lifecycle = TradingLifecycle.QUARANTINED
        }
    }

    fun boundedRetention(): TradingBoundedRetention {
        val current = runningBounded()
        return TradingBoundedRetention(
            current.balances.size,
            current.accounts.size,
            current.preparations.size,
            current.liveBySequence.size,
            current.pendingCount,
            current.inbox.entryCount,
            current.inbox.canonicalBytes,
        )
    }

    fun currentBalance(
        userId: UserId,
        assetId: AssetId,
    ): Balance {
        val current = runningBounded()
        return current.balances[TradingBalanceKey(userId, assetId)] ?: Balance(userId, assetId, Amount.ZERO, Amount.ZERO)
    }

    internal fun recoveryState(): BoundedFundingRecoveryState {
        check(!isBlocked) { "cannot snapshot a failed or legacy owner" }
        val current = checkNotNull(bounded) { "trading is not initialized" }
        return BoundedFundingRecoveryState(
            current.manifest,
            current.lifecycle,
            current.balances.values.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })),
            current.feeRevenue.toSortedMap(compareBy { it.value }),
            current.usedBalances.sortedWith(compareBy({ it.userId.value }, { it.assetId.value })),
            current.preparations.values.toList(),
            current.accounts.sortedBy { it.value },
            current.liveBySequence.values
                .sortedBy { it.admissionSequence }
                .map { it.snapshot() },
            current.markets.entries.sortedBy { it.key.value }.map { (market, progress) ->
                TradingMarketProgressSnapshot(
                    market,
                    progress.lastAssigned,
                    progress.executedThrough,
                    progress.pending.map { it.admissionSequence },
                )
            },
            current.inbox.snapshot(),
            current.lastAdmissionSequence,
            current.lastPreparationSequence,
            current.preparationBytes,
            current.incident,
        )
    }

    /** 크기가 제한된 설정 시장 벡터만 복사하므로 주문·이력 수가 늘어도 작업량은 일정하다. */
    fun progress(): BoundedTradingProgress {
        check(!isBlocked)
        val current = bounded ?: return BoundedTradingProgress(TradingLifecycle.UNINITIALIZED, 0L, emptyList())
        return BoundedTradingProgress(
            current.lifecycle,
            current.lastAdmissionSequence,
            current.markets.entries.map { (market, state) ->
                BoundedMarketExecutionProgress(market, state.lastAssigned, state.executedThrough)
            },
        )
    }

    internal fun restoreRecoveryState(
        saved: BoundedFundingRecoveryState,
        settlementCalculator: OrderSettlementCalculator,
    ) {
        requireExpectedManifest(saved.manifest)
        check(!isBlocked && bounded == null) { "bounded restore requires an empty owner" }
        try {
            val manifest = saved.manifest
            val limits = manifest.limits
            validateBoundedManifest(manifest)
            val candidate = BoundedTradingData(manifest)
            require(candidate.assets.size <= limits.maxAssets)
            require(saved.lifecycle != TradingLifecycle.UNINITIALIZED)
            require((saved.lifecycle == TradingLifecycle.QUARANTINED) == (saved.incident != null))
            saved.incident?.let { require(it.position >= 0 && it.reason.isNotBlank() && it.reason.length <= 128) }
            require(saved.lastAdmissionSequence >= 0 && saved.lastPreparationSequence >= 0 && saved.preparationCanonicalBytes >= 0)
            require(saved.accounts.size <= limits.maxAccounts && saved.accounts.size == saved.accounts.distinct().size)
            for (account in saved.accounts) {
                require(admissionCalculator.validIdentifier(account.value, accountPart = true))
                require(tradingTextBytes(account.value) <= limits.maxIdentifierBytes)
                candidate.accounts.add(account)
            }
            require(saved.balances.size <= limits.maxBalanceKeys)
            for (balance in saved.balances) {
                require(balance.userId in candidate.accounts && balance.assetId in candidate.assets)
                require(
                    candidate.balances.put(TradingBalanceKey(balance.userId, balance.assetId), balance) == null,
                ) { "duplicate recovery balance" }
            }
            require(saved.usedBalances.size == saved.usedBalances.distinct().size && saved.usedBalances.toSet() == candidate.balances.keys)
            candidate.usedBalances.addAll(saved.usedBalances)
            require(saved.preparations.size <= limits.maxPreparations && saved.lastPreparationSequence == saved.preparations.size.toLong())
            val supplied = linkedMapOf<AssetId, java.math.BigInteger>()
            val preparedKeys = hashSetOf<TradingBalanceKey>()
            for ((index, preparation) in saved.preparations.withIndex()) {
                val opening = preparation.opening
                val key = TradingBalanceKey(opening.userId, opening.assetId)
                require(key in candidate.usedBalances && preparedKeys.add(key)) { "duplicate or unknown prepared balance" }
                require(!preparation.alreadyPrepared)
                require(preparation.ledgerTransactionId == TradingIdentifiers.opening(manifest.lineage, index.toLong() + 1))
                require(candidate.preparations.put(opening.preparationId, preparation) == null) { "duplicate preparation identity" }
                val bytes = preparationBytes(opening)
                require(tradingTextBytes(opening.preparationId) <= limits.maxIdentifierBytes)
                candidate.preparationBytes = Math.addExact(candidate.preparationBytes, bytes)
                supplied[opening.assetId] =
                    (supplied[opening.assetId] ?: java.math.BigInteger.ZERO).add(java.math.BigInteger.valueOf(opening.amount.value))
            }
            require(
                candidate.preparationBytes == saved.preparationCanonicalBytes && candidate.preparationBytes <= limits.maxPreparationBytes,
            )
            for ((asset, fee) in saved.feeRevenueByAsset) {
                require(asset in candidate.assets && fee.signum() >= 0 && fee.bitLength() <= feeBitLimit(limits))
                if (fee.signum() != 0) candidate.feeRevenue[asset] = fee
            }
            require(saved.liveOrders.size <= limits.maxActiveOrders)
            val marketOrderSequences = hashSetOf<Pair<MarketId, Long>>()
            for (order in saved.liveOrders) {
                require(order.admissionSequence in 1..saved.lastAdmissionSequence && order.marketAdmissionSequence > 0)
                require(order.order.orderId == TradingIdentifiers.order(manifest.lineage, order.admissionSequence))
                require(order.order.userId in candidate.accounts)
                require(
                    marketOrderSequences.add(order.order.marketId to order.marketAdmissionSequence),
                ) { "duplicate live market sequence" }
                val context = requireNotNull(candidate.contexts[order.order.marketId])
                require(admissionCalculator.validOrderIdentifiers(order.order))
                require(admissionCalculator.supports(order.order.orderType, order.order.timeInForce))
                val live =
                    BoundedLiveOrder(order.admissionSequence, order.marketAdmissionSequence, order.order, order.reservation, order.phase)
                validateReservationRelationship(live.snapshot(), context)
                settlementCalculator.validateReservation(context.market, live.reservation)
                require(candidate.liveBySequence.put(order.admissionSequence, live) == null)
                require(candidate.liveByOrder.put(order.order.marketId to order.order.orderId, live) == null)
                require(TradingBalanceKey(order.order.userId, context.market.baseAssetId) in candidate.balances)
                require(TradingBalanceKey(order.order.userId, context.market.quoteAssetId) in candidate.balances)
                candidate.markets.getValue(order.order.marketId).activeCount++
            }
            require(
                saved.markets.size == candidate.markets.size && saved.markets
                    .map { it.marketId }
                    .distinct()
                    .size == saved.markets.size,
            )
            var assignedTotal = 0L
            val pendingSequences = hashSetOf<Long>()
            for (market in saved.markets) {
                val progress = requireNotNull(candidate.markets[market.marketId])
                require(market.executedThrough >= 0 && market.lastAssigned >= market.executedThrough)
                require(progress.activeCount <= limits.maxMarketActiveOrders)
                require(market.pendingAdmissionSequences.size <= limits.maxMarketPendingOrders)
                require(market.lastAssigned - market.executedThrough == market.pendingAdmissionSequences.size.toLong())
                progress.lastAssigned = market.lastAssigned
                progress.executedThrough = market.executedThrough
                assignedTotal = Math.addExact(assignedTotal, market.lastAssigned)
                for ((index, sequence) in market.pendingAdmissionSequences.withIndex()) {
                    val order = requireNotNull(candidate.liveBySequence[sequence])
                    require(
                        order.order.marketId == market.marketId && order.phase == LiveOrderPhase.PENDING && pendingSequences.add(sequence),
                    )
                    require(order.marketAdmissionSequence == Math.addExact(market.executedThrough, index.toLong() + 1L))
                    require(
                        order.reservation.remainingQuantity == order.order.quantity &&
                            order.reservation.remainingAmount == order.reservation.reservedAmount,
                    )
                    require(
                        order.reservation.remainingFeeReserveAmount == order.reservation.initialFeeReserveAmount &&
                            order.reservation.feeRemainder == com.exchange.core.fee.FeeRemainder.ZERO,
                    )
                    progress.pending.addLast(order)
                }
                candidate.pendingCount = Math.addExact(candidate.pendingCount, progress.pending.size)
            }
            require(assignedTotal == saved.lastAdmissionSequence && candidate.pendingCount <= limits.maxPendingOrders)
            val holds = linkedMapOf<TradingBalanceKey, Long>()
            for (order in candidate.liveBySequence.values) {
                val progress = candidate.markets.getValue(order.order.marketId)
                require(order.marketAdmissionSequence <= progress.lastAssigned)
                require((order.phase == LiveOrderPhase.PENDING) == (order.admissionSequence in pendingSequences))
                if (order.phase == LiveOrderPhase.RESTING) require(order.marketAdmissionSequence <= progress.executedThrough)
                val key = TradingBalanceKey(order.reservation.userId, order.reservation.assetId)
                holds[key] = Math.addExact(holds[key] ?: 0L, order.reservation.remainingAmount.value)
            }
            val totals = linkedMapOf<AssetId, java.math.BigInteger>()
            for ((key, balance) in candidate.balances) {
                require(balance.hold.value == (holds.remove(key) ?: 0L)) { "hold differs from active reservations" }
                val amount =
                    java.math.BigInteger
                        .valueOf(balance.available.value)
                        .add(java.math.BigInteger.valueOf(balance.hold.value))
                totals[balance.assetId] = (totals[balance.assetId] ?: java.math.BigInteger.ZERO).add(amount)
            }
            require(holds.isEmpty())
            for (asset in candidate.assets) {
                require(
                    (totals[asset] ?: java.math.BigInteger.ZERO).add(candidate.feeRevenue[asset] ?: java.math.BigInteger.ZERO) ==
                        (supplied[asset] ?: java.math.BigInteger.ZERO),
                ) {
                    "recovery asset conservation differs from opening funding"
                }
            }
            candidate.inbox.restore(saved.inbox)
            val acceptedSequences = hashSetOf<Long>()
            val acceptedMarketSequences = hashSetOf<Pair<MarketId, Long>>()
            for (channel in saved.inbox.channels) {
                require(channel.userId in candidate.accounts)
                for (entry in channel.entries) {
                    val context = requireNotNull(candidate.contexts[entry.intent.marketId])
                    require(entry.intent.expectedConfigurationVersion == manifest.configurationVersion)
                    val receipt = entry.receipt
                    if (receipt.code == AdmissionCode.ACCEPTED) {
                        val sequence = requireNotNull(receipt.admissionSequence)
                        require(acceptedSequences.add(sequence)) { "multiple request tokens claim one admission" }
                        require(
                            sequence <= saved.lastAdmissionSequence &&
                                receipt.orderId == TradingIdentifiers.order(manifest.lineage, sequence),
                        )
                        require(
                            requireNotNull(receipt.marketAdmissionSequence) <=
                                candidate.markets.getValue(context.market.marketId).lastAssigned,
                        )
                        require(acceptedMarketSequences.add(context.market.marketId to receipt.marketAdmissionSequence)) {
                            "multiple request tokens claim one market admission"
                        }
                        admissionCalculator.validateAcceptedOrder(
                            OrderAdmissionInput(
                                entry.intent.marketId,
                                requireNotNull(receipt.orderId),
                                channel.userId,
                                entry.intent.side,
                                entry.intent.orderType,
                                entry.intent.timeInForce,
                                entry.intent.price,
                                entry.intent.quantity,
                            ),
                            context.market,
                            context.feePolicySnapshot,
                        )
                        candidate.liveBySequence[sequence]?.let { live ->
                            require(
                                live.order.userId == channel.userId && live.order.marketId == entry.intent.marketId &&
                                    live.marketAdmissionSequence == receipt.marketAdmissionSequence &&
                                    live.order.side == entry.intent.side &&
                                    live.order.orderType == entry.intent.orderType && live.order.timeInForce == entry.intent.timeInForce &&
                                    live.order.price == entry.intent.price && live.order.quantity == entry.intent.quantity,
                            )
                        }
                    }
                }
            }
            candidate.lastAdmissionSequence = saved.lastAdmissionSequence
            candidate.lastPreparationSequence = saved.lastPreparationSequence
            candidate.lifecycle = saved.lifecycle
            candidate.incident = saved.incident
            isBlocked = true
            bounded = candidate
            isBlocked = false
        } catch (failure: Throwable) {
            isBlocked = true
            throw failure
        }
    }

    private fun runningBounded(): BoundedTradingData {
        check(!isBlocked) { "bounded trading owner is not usable" }
        val current = checkNotNull(bounded) { "trading is not initialized" }
        check(current.lifecycle == TradingLifecycle.RUNNING) { "trading lineage is quarantined" }
        return current
    }

    private inline fun <T> boundedMutation(action: () -> T): T {
        check(!isBlocked)
        isBlocked = true
        val result = action()
        isBlocked = false
        return result
    }

    private fun preparationBytes(opening: OpeningBalance): Long =
        128L + tradingTextBytes(opening.preparationId) + tradingTextBytes(opening.userId.value) + tradingTextBytes(opening.assetId.value)

    private fun feeBitLimit(limits: TradingResourceLimits): Int =
        java.math.BigInteger
            .valueOf(limits.maxBalanceKeys.toLong())
            .multiply(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            .bitLength()
}

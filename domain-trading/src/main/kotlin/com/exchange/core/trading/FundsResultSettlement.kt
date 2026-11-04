package com.exchange.core.trading

import com.exchange.core.common.MarketId
import java.time.Instant
import java.util.Collections

/** 수신 한 건과 정산 후 비교 원문의 마켓별·공통 보존 상한이다. */
data class FundsResultLimits(
    val maxResultEvents: Int = 1_024,
    val maxSingleResultBytes: Long = 1L * 1_024 * 1_024,
    val maxReceiptEntries: Int = 4_096,
    val maxReceiptBytes: Long = 4L * 1_024 * 1_024,
    val maxMarketReceiptEntries: Int = 2_048,
    val maxMarketReceiptBytes: Long = 2L * 1_024 * 1_024,
) {
    init {
        require(maxResultEvents in 1..1_000_000 && maxSingleResultBytes > 0)
        require(maxReceiptEntries > 0 && maxMarketReceiptEntries > 0)
        require(maxReceiptBytes >= maxSingleResultBytes && maxMarketReceiptBytes >= maxSingleResultBytes) {
            "result receipt budgets must retain one maximum result"
        }
    }

    /** 실제 분리 연결을 조립할 때 생산자의 허용 최대 결과를 빠짐없이 받을 수 있어야 한다. */
    fun requireCompatible(producer: MatchingIntakeLimits) {
        require(maxResultEvents >= producer.maxResultEvents && maxSingleResultBytes >= producer.maxSingleResultBytes) {
            "funds receiver cannot accept every permitted matching result"
        }
    }

    fun snapshotByteUpperBound(configuration: FundsDispatchConfiguration): Long {
        val bound =
            try {
                Math.addExact(
                    configuration.snapshotByteUpperBound,
                    Math.addExact(
                        Math.multiplyExact(maxReceiptBytes, 4L),
                        Math.addExact(maxReceiptEntries.toLong() * 32L, configuration.assignments.size.toLong() * 256L + 1_024L),
                    ),
                )
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("funds result limits overflow the snapshot bound", failure)
            }
        require(bound <= FundsDispatchConfiguration.MAX_SNAPSHOT_BYTES) { "funds results exceed the snapshot bound" }
        return bound
    }
}

enum class FundsResultStatus { APPLIED, DUPLICATE, PRUNED, CONTENT_CONFLICT, GAP, INVALID_STREAM, INVALID_RESULT }

data class FundsResultReply(
    val status: FundsResultStatus,
    val ack: MatchingFundsSettledAck? = null,
    val expectedSequence: Long? = null,
)

data class FundsResultReceipt(
    val result: MatchingResultEnvelope,
    val appliedAt: Instant,
) {
    val canonicalBytes: Long = result.canonicalBytes
}

data class FundsResultStreamRecovery(
    val stream: MatchingResultStream,
    val resultThrough: Long,
    val settledEngineSequence: Long,
    val retiredThrough: Long,
    val retiredEngineSequence: Long,
    val receiptEntries: Int,
    val receiptBytes: Long,
    val settledMarketAdmissionSequence: Long = resultThrough,
    val retiredMarketAdmissionSequence: Long = retiredThrough,
)

/** receipts는 자금에 적용한 전체 순서다. 복원 뒤에도 같은 FIFO 회수를 재현한다. */
class FundsResultRecoveryState(
    val limits: FundsResultLimits,
    streams: List<FundsResultStreamRecovery>,
    receipts: List<FundsResultReceipt>,
    val receiptBytes: Long,
) {
    val streams: List<FundsResultStreamRecovery> = Collections.unmodifiableList(streams.toList())
    val receipts: List<FundsResultReceipt> = Collections.unmodifiableList(receipts.toList())
}

/** 금융 소유자의 변경 경계 안에서만 갱신하는 정산 prefix와 제한된 비교 원문이다. */
internal class FundsResultData(
    private val configuration: FundsDispatchConfiguration,
    val limits: FundsResultLimits,
) {
    private class Progress {
        var resultThrough = 0L
        var settledEngineSequence = 0L
        var retiredThrough = 0L
        var retiredEngineSequence = 0L
        var receiptEntries = 0
        var receiptBytes = 0L
        var settledMarketAdmissionSequence = 0L
        var retiredMarketAdmissionSequence = 0L
    }

    private val markets = configuration.assignments.associate { it.marketId to Progress() }
    private val receipts = linkedMapOf<Pair<MarketId, Long>, FundsResultReceipt>()
    private var receiptBytes = 0L

    init {
        limits.snapshotByteUpperBound(configuration)
    }

    fun stream(market: MarketId): MatchingResultStream {
        val source = configuration.stream(market)
        return MatchingResultStream(
            source.matchingLineage,
            source.fundsLineage,
            source.marketId,
            source.ownershipEpoch,
            source.configurationVersion,
        )
    }

    fun matches(stream: MatchingResultStream): Boolean = markets.containsKey(stream.marketId) && stream == stream(stream.marketId)

    fun through(market: MarketId): Long = markets.getValue(market).resultThrough

    fun engine(market: MarketId): Long = markets.getValue(market).settledEngineSequence

    fun ack(market: MarketId): MatchingFundsSettledAck =
        MatchingFundsSettledAck(stream(market), through(market), configuration.context(market))

    fun repeated(result: MatchingResultEnvelope): FundsResultReply? {
        val progress = markets.getValue(result.stream.marketId)
        if (result.resultSequence > progress.resultThrough) return null
        val previous = receipts[result.stream.marketId to result.resultSequence]
        val status =
            when {
                previous == null -> FundsResultStatus.PRUNED
                previous.result == result -> FundsResultStatus.DUPLICATE
                else -> FundsResultStatus.CONTENT_CONFLICT
            }
        return FundsResultReply(status, if (status == FundsResultStatus.CONTENT_CONFLICT) null else ack(result.stream.marketId))
    }

    fun record(receipt: FundsResultReceipt) {
        val result = receipt.result
        val market = result.stream.marketId
        val progress = markets.getValue(market)
        check(progress.resultThrough < Long.MAX_VALUE && result.resultSequence == progress.resultThrough + 1)
        check(result.engineSequenceBefore == progress.settledEngineSequence)
        val charge = receipt.canonicalBytes
        while (progress.receiptEntries >= limits.maxMarketReceiptEntries || charge > limits.maxMarketReceiptBytes - progress.receiptBytes) {
            val first = market to Math.addExact(progress.retiredThrough, 1L)
            retire(first)
        }
        while (receipts.size >= limits.maxReceiptEntries || charge > limits.maxReceiptBytes - receiptBytes) {
            retire(receipts.entries.first().key)
        }
        check(receipts.put(market to result.resultSequence, receipt) == null)
        receiptBytes = Math.addExact(receiptBytes, charge)
        progress.receiptBytes = Math.addExact(progress.receiptBytes, charge)
        progress.receiptEntries++
        progress.resultThrough = result.resultSequence
        progress.settledEngineSequence = result.engineSequenceAfter
        if (!result.input.isCancellation) progress.settledMarketAdmissionSequence = result.input.marketAdmissionSequence
    }

    private fun retire(key: Pair<MarketId, Long>) {
        val receipt = checkNotNull(receipts.remove(key))
        val progress = markets.getValue(key.first)
        check(progress.retiredThrough < Long.MAX_VALUE && key.second == progress.retiredThrough + 1)
        val charge = receipt.canonicalBytes
        progress.retiredThrough = key.second
        progress.retiredEngineSequence = receipt.result.engineSequenceAfter
        if (!receipt.result.input.isCancellation) progress.retiredMarketAdmissionSequence = receipt.result.input.marketAdmissionSequence
        progress.receiptEntries--
        progress.receiptBytes -= charge
        receiptBytes -= charge
    }

    fun snapshot(): FundsResultRecoveryState =
        FundsResultRecoveryState(
            limits,
            configuration.manifest.markets.map { context ->
                val progress = markets.getValue(context.market.marketId)
                FundsResultStreamRecovery(
                    stream(context.market.marketId),
                    progress.resultThrough,
                    progress.settledEngineSequence,
                    progress.retiredThrough,
                    progress.retiredEngineSequence,
                    progress.receiptEntries,
                    progress.receiptBytes,
                    progress.settledMarketAdmissionSequence,
                    progress.retiredMarketAdmissionSequence,
                )
            },
            receipts.values.toList(),
            receiptBytes,
        )

    fun restore(
        saved: FundsResultRecoveryState,
        financial: BoundedFundingRecoveryState,
        dispatch: FundsDispatchSnapshot,
    ) {
        require(saved.limits == limits)
        require(saved.streams.size == markets.size && saved.streams.map { it.stream.marketId }.toSet() == markets.keys)
        require(saved.receipts.size <= limits.maxReceiptEntries && saved.receiptBytes in 0..limits.maxReceiptBytes)
        val byMarket = saved.streams.associateBy { it.stream.marketId }
        val financialMarkets = financial.markets.associateBy { it.marketId }
        val dispatchMarkets = dispatch.streams.associateBy { it.stream.marketId }
        val next = linkedMapOf<MarketId, Long>()
        val engines = linkedMapOf<MarketId, Long>()
        val admissions = linkedMapOf<MarketId, Long>()
        for (entry in saved.streams) {
            require(matches(entry.stream))
            val market = entry.stream.marketId
            val finance = requireNotNull(financialMarkets[market])
            val delivery = requireNotNull(dispatchMarkets[market])
            require(
                entry.settledMarketAdmissionSequence == finance.executedThrough &&
                    entry.resultThrough in entry.settledMarketAdmissionSequence..delivery.matchingAppliedThrough,
            )
            require(entry.retiredMarketAdmissionSequence in 0..entry.settledMarketAdmissionSequence)
            require(entry.retiredMarketAdmissionSequence <= entry.retiredThrough)
            if (entry.retiredThrough == entry.resultThrough) {
                require(entry.retiredMarketAdmissionSequence == entry.settledMarketAdmissionSequence)
            }
            require(entry.retiredThrough in 0..entry.resultThrough)
            require(entry.settledEngineSequence >= 0 && entry.retiredEngineSequence in 0..entry.settledEngineSequence)
            require(entry.resultThrough != 0L || entry.settledEngineSequence == 0L)
            require(entry.retiredThrough != 0L || entry.retiredEngineSequence == 0L)
            require(entry.resultThrough - entry.retiredThrough == entry.receiptEntries.toLong())
            require(entry.receiptEntries in 0..limits.maxMarketReceiptEntries && entry.receiptBytes in 0..limits.maxMarketReceiptBytes)
            if (entry.retiredThrough == entry.resultThrough) require(entry.retiredEngineSequence == entry.settledEngineSequence)
            next[market] = entry.retiredThrough
            engines[market] = entry.retiredEngineSequence
            admissions[market] = entry.retiredMarketAdmissionSequence
        }
        val counts = linkedMapOf<MarketId, Int>()
        val bytes = linkedMapOf<MarketId, Long>()
        val latestEffects = linkedMapOf<Pair<MarketId, com.exchange.core.common.OrderId>, MatchingOrderEffect>()
        val sourceAdmissions = linkedMapOf<MarketId, Long>()
        val seenAdmissions = hashSetOf<Long>()
        var totalBytes = 0L
        for (receipt in saved.receipts) {
            val result = receipt.result
            require(matches(result.stream))
            val market = result.stream.marketId
            val previous = next.getValue(market)
            require(previous < Long.MAX_VALUE && result.resultSequence == previous + 1)
            require(result.engineSequenceBefore == engines.getValue(market))
            requireResult(result)
            require(result.input.admissionSequence in 1..financial.lastAdmissionSequence)
            if (!result.input.isCancellation) {
                require(seenAdmissions.add(result.input.admissionSequence))
                require(result.input.admissionSequence > (sourceAdmissions[market] ?: 0L))
                require(result.input.marketAdmissionSequence == Math.addExact(admissions.getValue(market), 1L))
                sourceAdmissions[market] = result.input.admissionSequence
                admissions[market] = result.input.marketAdmissionSequence
            } else {
                require(result.input.marketAdmissionSequence <= admissions.getValue(market))
            }
            next[market] = result.resultSequence
            engines[market] = result.engineSequenceAfter
            counts[market] = Math.addExact(counts[market] ?: 0, 1)
            bytes[market] = Math.addExact(bytes[market] ?: 0L, result.canonicalBytes)
            totalBytes = Math.addExact(totalBytes, result.canonicalBytes)
            for (effect in result.effects) {
                val key = market to effect.orderId
                latestEffects[key]?.let { previousEffect ->
                    require(previousEffect.disposition == MatchingOrderDisposition.RESTING) { "terminal result order reappeared" }
                    require(previousEffect.origin == effect.origin && previousEffect.afterQuantity == effect.beforeQuantity) {
                        "retained result quantities are not contiguous"
                    }
                }
                latestEffects[key] = effect
            }
        }
        require(totalBytes == saved.receiptBytes)
        for ((market, entry) in byMarket) {
            require(
                next[market] == entry.resultThrough && engines[market] == entry.settledEngineSequence &&
                    admissions[market] == entry.settledMarketAdmissionSequence,
            )
            require((counts[market] ?: 0) == entry.receiptEntries && (bytes[market] ?: 0L) == entry.receiptBytes)
        }
        val liveByOrder = financial.liveOrders.associateBy { it.order.marketId to it.order.orderId }
        for ((key, effect) in latestEffects) {
            val live = liveByOrder[key]
            if (effect.disposition == MatchingOrderDisposition.RESTING) {
                // 한 stream의 연속 보존 구간 안에서 이 주문에 적용한 마지막 효과다.
                require(live != null && live.phase == LiveOrderPhase.RESTING)
                requireFundsOriginal(effect.origin, live)
                require(live.reservation.remainingQuantity == effect.afterQuantity)
            } else {
                require(live == null) { "terminal retained effect has a live financial order" }
            }
        }
        for (entry in saved.streams) {
            val progress = markets.getValue(entry.stream.marketId)
            progress.resultThrough = entry.resultThrough
            progress.settledEngineSequence = entry.settledEngineSequence
            progress.retiredThrough = entry.retiredThrough
            progress.retiredEngineSequence = entry.retiredEngineSequence
            progress.receiptEntries = entry.receiptEntries
            progress.receiptBytes = entry.receiptBytes
            progress.settledMarketAdmissionSequence = entry.settledMarketAdmissionSequence
            progress.retiredMarketAdmissionSequence = entry.retiredMarketAdmissionSequence
        }
        for (receipt in saved.receipts) {
            val key = receipt.result.stream.marketId to receipt.result.resultSequence
            require(receipts.put(key, receipt) == null)
        }
        receiptBytes = totalBytes
    }

    fun requireResult(result: MatchingResultEnvelope) {
        MatchingInputValidation.requireResult(
            configuration.manifest,
            stream(result.stream.marketId),
            configuration.context(result.stream.marketId),
            limits.maxResultEvents,
            limits.maxSingleResultBytes,
            result,
        )
    }
}

/** 실시간 적용과 복원에서 같은 로컬 최초 승인 관계를 검사한다. 현재 잔량은 호출자가 대조한다. */
internal fun requireFundsOriginal(
    origin: FundsOrderDispatch,
    live: TradingLiveOrderSnapshot,
) {
    require(
        origin.order == live.order && origin.admissionSequence == live.admissionSequence &&
            origin.marketAdmissionSequence == live.marketAdmissionSequence &&
            origin.dispatchSequence >= live.marketAdmissionSequence && !origin.isCancellation,
    ) { "matching origin differs from local financial approval" }
    val current = live.reservation
    val initial = origin.reservation
    require(current.reservedAmount == initial.reservedAmount && current.initialFeeReserveAmount == initial.initialFeeReserveAmount)
    require(current.assetId == initial.assetId && current.feePolicySnapshot == initial.feePolicySnapshot)
}

/** 무체결 최종 거절의 반환을 정산과 다른 원장 사건으로 균형 검증한다. */
internal fun fundsResultReleaseTransaction(
    result: MatchingResultEnvelope,
    reservation: com.exchange.core.order.OrderReservation,
    appliedAt: Instant,
): com.exchange.core.ledger.LedgerTransaction {
    require(
        (result.outcome == MatchingOutcome.NOT_EXECUTED && result.events.isEmpty()) ||
            (result.outcome == MatchingOutcome.CANCELLED && result.events.singleOrNull() is com.exchange.core.matching.OrderCancelled),
    )
    val id =
        if (result.outcome == MatchingOutcome.CANCELLED) {
            "F4" + TradingIdentifiers.reserve(result.stream.fundsLineage, result.input.admissionSequence).substring(2)
        } else {
            "F3" +
                TradingIdentifiers.settlement(result.stream.fundsLineage, result.input.context.ordinal, result.resultSequence).substring(2)
        }
    return com.exchange.core.ledger.LedgerTransaction(
        id,
        if (result.outcome == MatchingOutcome.CANCELLED) "ORDER_CANCELLED:$id" else "MATCHING_NOT_EXECUTED:$id",
        com.exchange.core.ledger.LedgerTransactionType.RELEASE,
        appliedAt,
        listOf(
            com.exchange.core.ledger.LedgerPosting(
                "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
                reservation.assetId,
                com.exchange.core.ledger.LedgerPostingSide.DEBIT,
                reservation.remainingAmount,
            ),
            com.exchange.core.ledger.LedgerPosting(
                "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                reservation.assetId,
                com.exchange.core.ledger.LedgerPostingSide.CREDIT,
                reservation.remainingAmount,
            ),
        ),
    )
}

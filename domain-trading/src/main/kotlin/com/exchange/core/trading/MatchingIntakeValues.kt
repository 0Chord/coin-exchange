package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.OrderCancelRejected
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import java.util.Collections

/** 매칭 전달 의무와 활성 승인 원본은 서로 다른 수명과 예산을 가진다. */
data class MarketMatchingIntakeLimits(
    val maxResultEntries: Int = 2_048,
    val maxResultBytes: Long = 2L * 1_024 * 1_024,
    val maxActiveOrders: Int = 4_096,
    val maxActiveBytes: Long = 2L * 1_024 * 1_024,
) {
    init {
        require(maxResultEntries > 0 && maxResultBytes > 0 && maxActiveOrders > 0 && maxActiveBytes > 0)
    }
}

class MatchingIntakeLimits(
    marketLimits: Map<MarketId, MarketMatchingIntakeLimits>,
    val maxResultEntries: Int = 4_096,
    val maxResultBytes: Long = 4L * 1_024 * 1_024,
    val maxActiveOrders: Int = 8_192,
    val maxActiveBytes: Long = 4L * 1_024 * 1_024,
    val maxSingleResultBytes: Long = 1L * 1_024 * 1_024,
    val maxResultEvents: Int = 1_024,
    val maxCandidateEvents: Int = 1_024,
) {
    val marketLimits: Map<MarketId, MarketMatchingIntakeLimits> = Collections.unmodifiableMap(LinkedHashMap(marketLimits))

    init {
        require(this.marketLimits.isNotEmpty())
        require(maxResultEntries > 0 && maxResultBytes > 0 && maxActiveOrders > 0 && maxActiveBytes > 0)
        require(maxSingleResultBytes > 0 && maxResultEvents in 1..1_000_000 && maxCandidateEvents in maxResultEvents..1_000_000)
    }

    override fun equals(other: Any?): Boolean =
        other is MatchingIntakeLimits && marketLimits == other.marketLimits && maxResultEntries == other.maxResultEntries &&
            maxResultBytes == other.maxResultBytes && maxActiveOrders == other.maxActiveOrders && maxActiveBytes == other.maxActiveBytes &&
            maxSingleResultBytes == other.maxSingleResultBytes && maxResultEvents == other.maxResultEvents &&
            maxCandidateEvents == other.maxCandidateEvents

    override fun hashCode(): Int =
        listOf(
            marketLimits,
            maxResultEntries,
            maxResultBytes,
            maxActiveOrders,
            maxActiveBytes,
            maxSingleResultBytes,
            maxResultEvents,
            maxCandidateEvents,
        ).hashCode()

    companion object {
        fun defaults(
            topology: TradingRoleTopology,
            declaration: TradingRoleDeclaration,
        ): MatchingIntakeLimits =
            MatchingIntakeLimits(
                topology.assignments.filter { it.matchingLineage == declaration.lineage }.associate {
                    it.marketId to
                        MarketMatchingIntakeLimits()
                },
            )
    }
}

/** 전체 배치와 원본 context를 고정한다. 담당 부분의 위치로 전역 ordinal을 바꾸지 않는다. */
class MatchingIntakeConfiguration(
    topology: TradingRoleTopology,
    val declaration: TradingRoleDeclaration,
    val limits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(topology, declaration),
    val inputContractVersion: Int = 1,
) {
    val manifest: TradingClusterManifest = topology.fundsManifest
    val assignments: List<TradingMarketAssignment> =
        Collections.unmodifiableList(
            manifest.markets.map { context ->
                topology.assignments.single {
                    it.marketId ==
                        context.market.marketId
                }
            },
        )
    val markets: List<TradingMarketContext> =
        Collections.unmodifiableList(
            manifest.markets.filter { context ->
                assignments.any {
                    it.marketId == context.market.marketId &&
                        it.matchingLineage == declaration.lineage
                }
            },
        )
    private val streams =
        markets.associate { context ->
            val assignment = assignments.single { it.marketId == context.market.marketId }
            context.market.marketId to
                FundsDispatchStream(
                    manifest.lineage,
                    declaration.lineage,
                    context.market.marketId,
                    assignment.ownershipEpoch,
                    manifest.configurationVersion,
                )
        }
    val snapshotByteUpperBound: Long

    init {
        require(inputContractVersion in 1..2) { "unsupported matching input contract" }
        topology.validate(declaration, TradingServiceRole.MATCHING)
        require(limits.marketLimits.keys == streams.keys) { "matching limits must cover exactly the owned markets" }
        require(manifest.markets.size <= 128 && manifest.limits.maxIdentifierBytes >= 34)
        require(manifest.markets.size <= manifest.limits.maxMarkets)
        require(
            manifest.markets
                .flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }
                .distinct()
                .size <= manifest.limits.maxAssets,
        )
        for (context in manifest.markets) {
            require(context.feePolicySnapshot.productType == com.exchange.core.fee.FeeProductType.SPOT)
            require(':' !in context.market.baseAssetId.value && ':' !in context.market.quoteAssetId.value)
            for (text in listOf(context.market.marketId.value, context.market.baseAssetId.value, context.market.quoteAssetId.value)) {
                require(tradingTextBytes(text) <= manifest.limits.maxIdentifierBytes)
            }
        }
        var manifestBytes =
            120L + tradingTextBytes(manifest.algorithmId) + tradingTextBytes(manifest.executionArtifactSha256) +
                tradingTextBytes(manifest.schemaSha256)
        for (context in manifest.markets) {
            manifestBytes =
                Math.addExact(
                    manifestBytes,
                    68L + tradingTextBytes(context.market.marketId.value) +
                        tradingTextBytes(context.market.baseAssetId.value) + tradingTextBytes(context.market.quoteAssetId.value) +
                        tradingTextBytes(context.feePolicySnapshot.productType.name) +
                        tradingTextBytes(context.feePolicySnapshot.feeTier.name),
                )
        }
        require(manifestBytes <= 3_072L) { "matching manifest exceeds snapshot-v1 bound" }
        for (context in markets) {
            val local = limits.marketLimits.getValue(context.market.marketId)
            require(limits.maxSingleResultBytes <= minOf(limits.maxResultBytes, local.maxResultBytes))
            for (side in com.exchange.core.order.Side.entries) {
                val maximal = maximumInput(context, side)
                val rejected =
                    MatchingResultEnvelope(
                        resultStream(context.market.marketId),
                        Long.MAX_VALUE,
                        maximal,
                        MatchingOutcome.NOT_EXECUTED,
                        MatchingNotExecutedReason.RESULT_TOO_LARGE,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        emptyList(),
                        listOf(
                            MatchingOrderEffect(
                                maximal,
                                maximal.order.quantity,
                                maximal.order.quantity,
                                MatchingOrderDisposition.NOT_EXECUTED,
                            ),
                        ),
                    )
                require(
                    rejected.canonicalBytes <= limits.maxSingleResultBytes,
                ) { "matching result budget cannot retain the largest input rejection" }
                if (inputContractVersion == 2) {
                    val target = maximal.copy(dispatchSequence = Long.MAX_VALUE - 1, marketAdmissionSequence = Long.MAX_VALUE - 1)
                    val cancellation =
                        target.copy(
                            dispatchSequence = Long.MAX_VALUE,
                            cancel =
                                FundsCancelDispatch(
                                    TradingIdentifiers.cancel(manifest.lineage, Long.MAX_VALUE),
                                    RequestToken(target.order.userId, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                                    Long.MAX_VALUE,
                                    target.dispatchSequence,
                                ),
                        )
                    val cancelled =
                        MatchingResultEnvelope(
                            resultStream(context.market.marketId),
                            Long.MAX_VALUE,
                            cancellation,
                            MatchingOutcome.CANCELLED,
                            null,
                            Long.MAX_VALUE - 1,
                            Long.MAX_VALUE,
                            listOf(
                                OrderCancelled(
                                    target.order.marketId,
                                    Long.MAX_VALUE,
                                    target.order.orderId,
                                    target.order.userId,
                                    target.order.quantity,
                                ),
                            ),
                            listOf(MatchingOrderEffect(target, target.order.quantity, Quantity.ZERO, MatchingOrderDisposition.CANCELLED)),
                        )
                    require(cancelled.canonicalBytes <= limits.maxSingleResultBytes) {
                        "matching result budget cannot retain the largest cancellation"
                    }
                }
            }
        }
        snapshotByteUpperBound =
            try {
                Math.addExact(
                    256L * 1_024 + Math.multiplyExact(manifest.markets.size.toLong(), 16_384),
                    Math.multiplyExact(Math.addExact(limits.maxResultBytes, limits.maxActiveBytes), 8),
                )
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("matching snapshot bound overflow", failure)
            }
        require(snapshotByteUpperBound <= MAX_SNAPSHOT_BYTES) { "matching limits exceed snapshot bound" }
    }

    fun stream(marketId: MarketId): FundsDispatchStream = requireNotNull(streams[marketId]) { "unassigned matching market" }

    fun resultStream(marketId: MarketId): MatchingResultStream =
        stream(marketId).let {
            MatchingResultStream(it.matchingLineage, it.fundsLineage, it.marketId, it.ownershipEpoch, it.configurationVersion)
        }

    fun context(marketId: MarketId): TradingMarketContext = markets.single { it.market.marketId == marketId }

    internal fun matches(stream: FundsDispatchStream): Boolean = streams[stream.marketId] == stream

    internal fun matches(stream: MatchingResultStream): Boolean =
        streams[stream.marketId]?.let { resultStream(stream.marketId) == stream } == true

    private fun maximumInput(
        context: TradingMarketContext,
        side: com.exchange.core.order.Side,
    ): FundsOrderDispatch {
        val order =
            OrderAdmissionInput(
                context.market.marketId,
                TradingIdentifiers.order(manifest.lineage, Long.MAX_VALUE),
                UserId("u".repeat(manifest.limits.maxIdentifierBytes)),
                side,
                com.exchange.core.order.OrderType.LIMIT,
                com.exchange.core.order.TimeInForce.GTC,
                com.exchange.core.common
                    .Price(context.market.priceTick),
                Quantity(context.market.quantityStep),
            )
        // charge는 값의 폭만 보므로 합법 숫자 중 가장 작은 예약으로 최대 문자열 원본을 구성한다.
        val calculator = MatchingInputValidation.reservationCalculator()
        val requirement = calculator.calculate(context.market, order.side, order.price, order.quantity, context.feePolicySnapshot)
        val reservation =
            com.exchange.core.order.OrderReservation.create(
                order.marketId,
                order.orderId,
                order.userId,
                order.side,
                order.price,
                order.quantity,
                requirement,
                context.feePolicySnapshot,
            )
        return FundsOrderDispatch(
            stream(order.marketId),
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            order,
            reservation,
            context,
            inputContractVersion = inputContractVersion,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is MatchingIntakeConfiguration && manifest == other.manifest && assignments == other.assignments && limits == other.limits &&
            declaration.serviceRole == other.declaration.serviceRole && declaration.lineage == other.declaration.lineage &&
            declaration.configurationVersion == other.declaration.configurationVersion &&
            declaration.markets == other.declaration.markets &&
            declaration.ownershipEpochs == other.declaration.ownershipEpochs && inputContractVersion == other.inputContractVersion

    override fun hashCode(): Int =
        listOf(
            manifest,
            assignments,
            limits,
            declaration.lineage,
            declaration.markets,
            declaration.ownershipEpochs,
            inputContractVersion,
        ).hashCode()

    companion object {
        const val MAX_SNAPSHOT_BYTES: Long = 128L * 1_024 * 1_024
    }
}

enum class MatchingResultDirection { MATCHING_TO_FUNDS }

data class MatchingResultStream(
    val matchingLineage: ClusterLineage,
    val fundsLineage: ClusterLineage,
    val marketId: MarketId,
    val ownershipEpoch: Long,
    val configurationVersion: Long,
) {
    val direction: MatchingResultDirection get() = MatchingResultDirection.MATCHING_TO_FUNDS

    init {
        require(matchingLineage != fundsLineage && ownershipEpoch > 0 && configurationVersion > 0)
    }
}

enum class MatchingOutcome { MATCHED, NOT_EXECUTED, CANCELLED, CANCEL_REJECTED }

enum class MatchingNotExecutedReason { ACTIVE_ORDER_LIMIT, RESULT_TOO_LARGE }

enum class MatchingOrderDisposition { RESTING, TERMINAL, NOT_EXECUTED, CANCELLED }

data class MatchingOrderEffect(
    val origin: FundsOrderDispatch,
    val beforeQuantity: Quantity,
    val afterQuantity: Quantity,
    val disposition: MatchingOrderDisposition,
) {
    val orderId: OrderId get() = origin.order.orderId
    val userId: UserId get() = origin.order.userId
    val side: com.exchange.core.order.Side get() = origin.order.side
}

/** 신규의 대기·최종 거절과 취소의 성공·거절도 모두 완결된 전달 결과다. */
class MatchingResultEnvelope(
    val stream: MatchingResultStream,
    val resultSequence: Long,
    val input: FundsOrderDispatch,
    val outcome: MatchingOutcome,
    val reason: MatchingNotExecutedReason?,
    val engineSequenceBefore: Long,
    val engineSequenceAfter: Long,
    events: List<MatchingEvent>,
    effects: List<MatchingOrderEffect>,
) {
    val events: List<MatchingEvent> = Collections.unmodifiableList(events.toList())
    val effects: List<MatchingOrderEffect> = Collections.unmodifiableList(effects.toList())
    val formatVersion: Int get() = input.inputContractVersion
    val eventCount: Int get() = events.size
    val resultId: String get() =
        "M$formatVersion:${stream.matchingLineage.high}:${stream.matchingLineage.low}:" +
            "${stream.fundsLineage.high}:${stream.fundsLineage.low}:${stream.marketId.value}:" +
            "${stream.ownershipEpoch}:${stream.configurationVersion}:$resultSequence"
    val canonicalBytes: Long get() = MatchingResultCharge.result(this)

    override fun equals(other: Any?): Boolean =
        other is MatchingResultEnvelope && stream == other.stream && resultSequence == other.resultSequence &&
            input == other.input && outcome == other.outcome && reason == other.reason &&
            engineSequenceBefore == other.engineSequenceBefore &&
            engineSequenceAfter == other.engineSequenceAfter && events == other.events && effects == other.effects

    override fun hashCode(): Int =
        listOf(stream, resultSequence, input, outcome, reason, engineSequenceBefore, engineSequenceAfter, events, effects).hashCode()
}

/** 원본을 참조로 공유해도 각 보존 의무에서 독립 계산한다. 기존 v1 charge는 유지한다. */
object MatchingResultCharge {
    fun active(input: FundsOrderDispatch): Long = input.dispatchCanonicalBytes

    fun effect(effect: MatchingOrderEffect): Long = Math.addExact(20L, effect.origin.dispatchCanonicalBytes)

    fun result(result: MatchingResultEnvelope): Long {
        // format/stream(두 lineage·epoch·version·direction)/seq/outcome/reason/범위/count/list 길이.
        var bytes = Math.addExact(104L + text(result.stream.marketId.value) + text(result.resultId), result.input.dispatchCanonicalBytes)
        for (event in result.events) bytes = Math.addExact(bytes, event(event))
        for (effect in result.effects) bytes = Math.addExact(bytes, effect(effect))
        return bytes
    }

    fun event(event: MatchingEvent): Long =
        when (event) {
            is TradeExecuted -> {
                36L + text(event.marketId.value) + text(event.makerOrderId.value) + text(event.takerOrderId.value) +
                    text(event.makerUserId.value) +
                    text(event.takerUserId.value)
            }

            is OrderEnteredBook -> {
                32L + text(event.marketId.value) + text(event.orderId.value) + text(event.userId.value)
            }

            is OrderCancelled -> {
                20L + text(event.marketId.value) + text(event.orderId.value) + text(event.userId.value)
            }

            is OrderCancelRejected -> {
                12L + text(event.marketId.value) + text(event.orderId.value) + text(event.userId.value) + text(event.reason)
            }
        }

    private fun text(value: String): Long = 4L + tradingTextBytes(value)
}

enum class MatchingIntakeStatus {
    APPLIED,
    DUPLICATE,
    PRUNED,
    CONTENT_CONFLICT,
    GAP,
    BUSY,
    INVALID_STREAM,
    INVALID_INPUT,
    SEQUENCE_EXHAUSTED,
}

data class MatchingIntakeReply(
    val status: MatchingIntakeStatus,
    val result: MatchingResultEnvelope? = null,
    val ack: FundsMatchingAppliedAck? = null,
    val expectedSequence: Long? = null,
)

enum class MatchingResultReadStatus { BATCH, PRUNED, INVALID_QUERY, INVALID_STREAM, BATCH_TOO_SMALL }

class MatchingResultRead(
    val status: MatchingResultReadStatus,
    entries: List<MatchingResultEnvelope> = emptyList(),
    val fundsSettledThrough: Long = 0,
    val resultIssued: Long = 0,
    val requiredBytes: Long = 0,
    val nextSequence: Long? = null,
) {
    val entries: List<MatchingResultEnvelope> = Collections.unmodifiableList(entries.toList())
}

data class MatchingFundsSettledAck(
    val stream: MatchingResultStream,
    val through: Long,
    val context: TradingMarketContext,
)

enum class MatchingResultAckStatus { ACKNOWLEDGED, INVALID_STREAM, INVALID_POLICY, INVALID_SEQUENCE, FUTURE_ACK }

data class MatchingResultAck(
    val status: MatchingResultAckStatus,
    val fundsSettledThrough: Long,
)

class MatchingStreamRecovery(
    val stream: FundsDispatchStream,
    val appliedThrough: Long,
    val lastSourceAdmissionSequence: Long,
    val resultIssued: Long,
    val fundsSettledThrough: Long,
    val settledEngineSequence: Long,
    outstanding: List<MatchingResultEnvelope>,
    val resultBytes: Long,
    val activeOrders: Int,
    val activeBytes: Long,
    val lastSourceMarketAdmissionSequence: Long = appliedThrough,
) {
    val outstanding: List<MatchingResultEnvelope> = Collections.unmodifiableList(outstanding.toList())
}

class MatchingRoleRecoveryState(
    val configuration: MatchingIntakeConfiguration,
    val lifecycle: TradingLifecycle,
    val matching: MatchingCheckpoint,
    active: List<FundsOrderDispatch>,
    streams: List<MatchingStreamRecovery>,
    val resultEntries: Int,
    val resultBytes: Long,
    val activeOrders: Int,
    val activeBytes: Long,
) {
    val active: List<FundsOrderDispatch> = Collections.unmodifiableList(active.toList())
    val streams: List<MatchingStreamRecovery> = Collections.unmodifiableList(streams.toList())
}

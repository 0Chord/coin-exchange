package com.exchange.core.trading

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import java.math.BigInteger
import java.util.Collections

enum class TradingLifecycle { UNINITIALIZED, RUNNING, QUARANTINED }

data class TradingMarketContext(
    val market: MarketDefinition,
    val feePolicySnapshot: TradingFeePolicySnapshot,
    val ordinal: Int,
)

/** 고정한 실행 입력이다. 로컬 주소나 디렉터리는 포함하지 않는다. */
class TradingClusterManifest(
    val lineage: ClusterLineage,
    val algorithmId: String,
    val executionArtifactSha256: String,
    val schemaSha256: String,
    val configurationVersion: Long,
    markets: List<TradingMarketContext>,
    val limits: TradingResourceLimits,
) {
    val markets: List<TradingMarketContext> = Collections.unmodifiableList(markets.toList())

    init {
        require(algorithmId.isNotBlank() && algorithmId.length <= 128)
        require(executionArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(schemaSha256.matches(Regex("[0-9a-f]{64}")))
        require(configurationVersion > 0 && markets.isNotEmpty())
        require(markets.map { it.market.marketId }.distinct().size == markets.size)
        require(markets.map { it.ordinal }.distinct().size == markets.size && markets.all { it.ordinal >= 0 })
        require(markets.map { it.ordinal } == markets.map { it.ordinal }.sorted())
    }

    override fun equals(other: Any?): Boolean =
        other is TradingClusterManifest && lineage == other.lineage && algorithmId == other.algorithmId &&
            executionArtifactSha256 == other.executionArtifactSha256 && schemaSha256 == other.schemaSha256 &&
            configurationVersion == other.configurationVersion && markets == other.markets && limits == other.limits

    override fun hashCode(): Int =
        listOf(lineage, algorithmId, executionArtifactSha256, schemaSha256, configurationVersion, markets, limits).hashCode()
}

enum class InitializationReceipt { INITIALIZED, ALREADY_INITIALIZED }

sealed class AdmissionReply {
    data class Recorded(
        val receipt: AdmissionReceipt,
        val newlyRecorded: Boolean,
    ) : AdmissionReply()

    data class Refused(
        val reason: String,
    ) : AdmissionReply()
}

enum class LiveOrderPhase { PENDING, RESTING }

data class ExecuteAdmission(
    val lineage: ClusterLineage,
    val marketId: MarketId,
    val marketAdmissionSequence: Long,
    val admissionSequence: Long,
)

data class ExecutionReceipt(
    val admissionSequence: Long,
    val marketAdmissionSequence: Long,
    val marketId: MarketId,
    val lastEngineSequence: Long,
    val fillCount: Int,
    val resting: Boolean,
)

sealed class ExecutionReply {
    data class Applied(
        val receipt: ExecutionReceipt,
    ) : ExecutionReply()

    data object AlreadyPassed : ExecutionReply()
}

data class TradingLiveOrderSnapshot(
    val admissionSequence: Long,
    val marketAdmissionSequence: Long,
    val order: OrderAdmissionInput,
    val reservation: OrderReservation,
    val phase: LiveOrderPhase,
)

data class TradingMarketProgressSnapshot(
    val marketId: MarketId,
    val lastAssigned: Long,
    val executedThrough: Long,
    val pendingAdmissionSequences: List<Long>,
)

data class TradingIncident(
    val position: Long,
    val reason: String,
)

/** 현재 금융 상태만 담는다. 완료 주문·전체 원장·과거 식별자 인덱스는 포함하지 않는다. */
class TradingRecoveryState(
    val manifest: TradingClusterManifest,
    val lifecycle: TradingLifecycle,
    balances: List<Balance>,
    feeRevenueByAsset: Map<AssetId, BigInteger>,
    usedBalances: List<TradingBalanceKey>,
    preparations: List<OpeningBalanceResult>,
    accounts: List<com.exchange.core.common.UserId>,
    liveOrders: List<TradingLiveOrderSnapshot>,
    markets: List<TradingMarketProgressSnapshot>,
    val matching: MatchingCheckpoint,
    val inbox: TradingInboxSnapshot,
    val lastAdmissionSequence: Long,
    val lastPreparationSequence: Long,
    val preparationCanonicalBytes: Long,
    val incident: TradingIncident? = null,
) {
    val balances = Collections.unmodifiableList(balances.toList())
    val feeRevenueByAsset = Collections.unmodifiableMap(LinkedHashMap(feeRevenueByAsset))
    val usedBalances = Collections.unmodifiableList(usedBalances.toList())
    val preparations = Collections.unmodifiableList(preparations.toList())
    val accounts = Collections.unmodifiableList(accounts.toList())
    val liveOrders = Collections.unmodifiableList(liveOrders.toList())
    val markets =
        Collections.unmodifiableList(
            markets.map {
                it.copy(pendingAdmissionSequences = Collections.unmodifiableList(it.pendingAdmissionSequences.toList()))
            },
        )
}

data class TradingBoundedRetention(
    val balances: Int,
    val accounts: Int,
    val preparations: Int,
    val liveOrders: Int,
    val pendingOrders: Int,
    val receipts: Int,
    val receiptCanonicalBytes: Long,
)

data class BoundedMarketExecutionProgress(
    val marketId: MarketId,
    val lastAssigned: Long,
    val executedThrough: Long,
)

data class BoundedTradingProgress(
    val lifecycle: TradingLifecycle,
    val lastAdmissionSequence: Long,
    val markets: List<BoundedMarketExecutionProgress>,
)

/** 기존 TradingState 작성자가 배타적으로 소유하는 가변 현재 상태다. */
internal class BoundedTradingData(
    val manifest: TradingClusterManifest,
) {
    var lifecycle = TradingLifecycle.RUNNING
    var incident: TradingIncident? = null
    val contexts = manifest.markets.associateBy { it.market.marketId }
    val assets = manifest.markets.flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }.toSet()
    val accounts = linkedSetOf<com.exchange.core.common.UserId>()
    val balances = linkedMapOf<TradingBalanceKey, Balance>()
    val usedBalances = linkedSetOf<TradingBalanceKey>()
    val preparations = linkedMapOf<String, OpeningBalanceResult>()
    val feeRevenue = linkedMapOf<AssetId, BigInteger>()
    val liveBySequence = linkedMapOf<Long, BoundedLiveOrder>()
    val liveByOrder = hashMapOf<Pair<MarketId, com.exchange.core.common.OrderId>, BoundedLiveOrder>()
    val markets = manifest.markets.associate { it.market.marketId to BoundedMarketProgress() }
    val inbox = TradingRequestInbox(manifest.limits)
    var lastAdmissionSequence = 0L
    var lastPreparationSequence = 0L
    var preparationBytes = 0L
    var pendingCount = 0
}

internal class BoundedMarketProgress {
    var lastAssigned = 0L
    var executedThrough = 0L
    var activeCount = 0
    val pending = ArrayDeque<BoundedLiveOrder>()
}

internal class BoundedLiveOrder(
    val admissionSequence: Long,
    val marketAdmissionSequence: Long,
    val order: OrderAdmissionInput,
    var reservation: OrderReservation,
    var phase: LiveOrderPhase,
) {
    fun snapshot() = TradingLiveOrderSnapshot(admissionSequence, marketAdmissionSequence, order, reservation, phase)
}

class TradingCapacityException(
    message: String,
) : IllegalStateException(message)

enum class TradingFundingRejection { UNKNOWN_ASSET, INVALID_IDENTIFIER }

class TradingFundingInputException(
    val reason: TradingFundingRejection,
) : IllegalArgumentException(reason.name)

enum class TradingInvariantReason {
    MATCHING_CANDIDATE_INVALID,
    PARTICIPANT_RELATION_INVALID,
    SETTLEMENT_CALCULATION_INVALID,
    RESULT_RELATION_INVALID,
    FEE_REVENUE_INVALID,
}

/** 반영 전 검증 실패를 일정한 기준으로 분류한다. 원본 메시지나 원인 예외는 상태에 넣지 않는다. */
class TradingInvariantViolation(
    val reason: TradingInvariantReason,
    val stage: TradingExecutionPhase,
) : IllegalStateException("${reason.name}:${stage.name}")

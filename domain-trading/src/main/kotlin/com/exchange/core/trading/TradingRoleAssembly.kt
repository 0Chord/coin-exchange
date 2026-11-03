package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.matching.MatchingEngine
import com.exchange.core.matching.MatchingEvent
import com.exchange.core.matching.RestingOrderSnapshot
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.SubmitOrderPlan
import java.util.Collections

/** 최초 고정 배치다. 리더 교체로 세대나 업무 이력 식별자를 다시 발급하지 않는다. */
data class TradingMarketAssignment(
    val marketId: MarketId,
    val matchingLineage: ClusterLineage,
    val ownershipEpoch: Long,
) {
    init {
        require(ownershipEpoch > 0) { "ownership epoch must be positive" }
    }
}

/** 역할이 따를 기대 배치다. 실행할 역할의 자기 선언과 별도로 검증한다. */
class TradingRoleTopology(
    val fundsManifest: TradingClusterManifest,
    assignments: List<TradingMarketAssignment>,
) {
    val assignments: List<TradingMarketAssignment> = Collections.unmodifiableList(assignments.toList())

    init {
        require(
            this.assignments
                .map { it.marketId }
                .distinct()
                .size == this.assignments.size,
        ) { "duplicate market assignment" }
        require(this.assignments.map { it.marketId }.toSet() == fundsManifest.markets.map { it.market.marketId }.toSet()) {
            "assignments must cover exactly the registered markets"
        }
        require(this.assignments.all { it.matchingLineage != fundsManifest.lineage }) { "funds and matching lineages must differ" }
    }

    internal fun validate(
        declaration: TradingRoleDeclaration,
        expectedRole: TradingServiceRole,
    ) {
        require(declaration.serviceRole == expectedRole) { "ROLE_DECLARATION_MISMATCH" }
        require(declaration.configurationVersion == fundsManifest.configurationVersion) { "role configuration version differs" }
        val owned =
            when (expectedRole) {
                TradingServiceRole.FUNDS -> {
                    require(declaration.lineage == fundsManifest.lineage) { "funds lineage differs" }
                    assignments
                }

                TradingServiceRole.MATCHING -> {
                    assignments.filter { it.matchingLineage == declaration.lineage }
                }

                TradingServiceRole.INTEGRATED -> {
                    throw IllegalArgumentException("integrated composition does not use a split declaration")
                }
            }
        require(owned.isNotEmpty()) { "role must own registered markets" }
        val ids = owned.map { it.marketId }.toSet()
        require(
            declaration.markets
                .map { it.market.marketId }
                .distinct()
                .size == declaration.markets.size,
        ) { "duplicate declared market" }
        require(declaration.markets.map { it.market.marketId }.toSet() == ids) { "declared markets differ from assigned markets" }
        val expectedContexts = fundsManifest.markets.filter { it.market.marketId in ids }.associateBy { it.market.marketId }
        require(declaration.markets.all { expectedContexts[it.market.marketId] == it }) { "declared market or fee policy differs" }
        require(declaration.ownershipEpochs == owned.associate { it.marketId to it.ownershipEpoch }) { "declared ownership epochs differ" }
    }
}

/** 호출자가 선택한 역할의 자기 선언이다. 컬렉션을 바꿔 이미 검증한 배치를 변경할 수 없다. */
class TradingRoleDeclaration(
    val serviceRole: TradingServiceRole,
    val lineage: ClusterLineage,
    val configurationVersion: Long,
    markets: List<TradingMarketContext>,
    ownershipEpochs: Map<MarketId, Long>,
) {
    val markets: List<TradingMarketContext> = Collections.unmodifiableList(markets.toList())
    val ownershipEpochs: Map<MarketId, Long> = Collections.unmodifiableMap(LinkedHashMap(ownershipEpochs))

    init {
        require(configurationVersion > 0) { "configuration version must be positive" }
        require(this.ownershipEpochs.values.all { it > 0 }) { "ownership epochs must be positive" }
    }
}

/** 순수 메모리 조립만 수행한다. 이 객체에서 노드·통신·기록을 시작하지 않는다. */
object TradingRoleAssembly {
    fun funds(
        topology: TradingRoleTopology,
        declaration: TradingRoleDeclaration,
        admissionCalculator: OrderAdmissionCalculator,
        dispatchLimits: FundsDispatchLimits = FundsDispatchLimits.defaults(topology.fundsManifest),
        resultLimits: FundsResultLimits = FundsResultLimits(),
    ): BoundedFundingState {
        topology.validate(declaration, TradingServiceRole.FUNDS)
        return BoundedFundingState(
            admissionCalculator,
            expectedManifest = topology.fundsManifest,
            dispatchConfiguration = FundsDispatchConfiguration(topology, dispatchLimits),
            resultLimits = resultLimits,
        )
    }

    fun matching(
        topology: TradingRoleTopology,
        declaration: TradingRoleDeclaration,
    ): MatchingRole {
        topology.validate(declaration, TradingServiceRole.MATCHING)
        return MatchingRole(declaration.markets.map { it.market.marketId }.toSet())
    }

    /** 입력과 결과 전달 의무를 우회하지 않는 새 매칭 역할의 단일 상태 소유자다. */
    fun reservedMatching(
        topology: TradingRoleTopology,
        declaration: TradingRoleDeclaration,
        limits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(topology, declaration),
    ): ReservedMatchingState = ReservedMatchingState(MatchingIntakeConfiguration(topology, declaration, limits))

    fun integrated(
        admissionCalculator: OrderAdmissionCalculator,
        settlementCalculator: OrderSettlementCalculator,
        historyStore: TradingHistoryStore? = null,
        historyBatchLimit: Int = 1_024,
        boundedMode: Boolean = false,
    ): TradingState = TradingState(admissionCalculator, settlementCalculator, historyStore, historyBatchLimit, boundedMode)
}

/** 기존 엔진의 접근 범위를 고정 배치에 묶는다. 금융 상태나 새 매칭 알고리즘을 소유하지 않는다. */
class MatchingRole internal constructor(
    marketIds: Set<MarketId>,
) {
    val marketIds: Set<MarketId> = Collections.unmodifiableSet(LinkedHashSet(marketIds))
    private val engine = MatchingEngine()

    fun prepareSubmit(command: SubmitOrderCommand): SubmitOrderPlan {
        requireOwned(command.marketId)
        return engine.prepareSubmit(command)
    }

    fun apply(plan: SubmitOrderPlan): List<MatchingEvent> {
        requireOwned(plan.command.marketId)
        return engine.apply(plan)
    }

    fun restingOrders(marketId: MarketId): List<RestingOrderSnapshot> {
        requireOwned(marketId)
        return engine.restingOrders(marketId)
    }

    fun lastEngineSequence(marketId: MarketId): Long {
        requireOwned(marketId)
        return engine.lastEngineSequence(marketId)
    }

    fun activeCheckpoint(): MatchingCheckpoint = engine.activeCheckpoint()

    private fun requireOwned(marketId: MarketId) {
        require(marketId in marketIds) { "market is not assigned to this matching role" }
    }
}

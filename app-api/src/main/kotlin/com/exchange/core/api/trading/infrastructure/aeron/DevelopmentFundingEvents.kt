package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path

/** 개발 프로세스의 입출력 경계다. 업무 callback은 값을 완성하고 main만 출력한다. */
private val fundingJson = JsonMapper.builder().build()

internal fun developmentClusterSettings(environment: Map<String, String> = System.getenv()): DevelopmentFundingSettings {
    fun required(name: String): String = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    return DevelopmentFundingSettings(
        runRoot = Path.of(required("FUNDING_RUN_ROOT")),
        basePort = environment["FUNDING_BASE_PORT"]?.toInt() ?: 19700,
        startupTimeoutMs = 60_000,
        memberId = environment["FUNDING_MEMBER_ID"]?.toInt() ?: 0,
        advertisedHost = required("FUNDING_HOST"),
        bindHost = environment["FUNDING_BIND_HOST"] ?: "0.0.0.0",
        clusterMembers = required("FUNDING_MEMBERS"),
        runId = required("FUNDING_RUN_ID"),
    )
}

internal fun fundingEvent(fields: Map<String, Any?>) {
    println("FUNDING_EVENT ${fundingJson.writeValueAsString(fields)}")
}

internal fun fundingReadyEvent(
    config: DevelopmentFundingSettings,
    node: DevelopmentFundingNode,
) {
    fundingEvent(
        mapOf(
            "kind" to "READY",
            "runId" to config.runId,
            "memberId" to config.memberId,
            "pid" to ProcessHandle.current().pid(),
            "role" to node.role.name,
            "settings" to
                mapOf(
                    "clusterId" to config.clusterId,
                    "serviceId" to config.serviceId,
                    "members" to config.members,
                    "runRoot" to config.runRoot.toString(),
                    "driverDirectory" to config.driverDirectory,
                    "archiveDirectory" to config.archiveDirectory.toString(),
                    "clusterDirectory" to config.clusterDirectory.toString(),
                    "storageLevels" to node.storageLevels,
                    "market" to
                        mapOf(
                            "marketId" to config.market.marketId.value,
                            "baseAssetId" to config.market.baseAssetId.value,
                            "quoteAssetId" to config.market.quoteAssetId.value,
                            "baseQuantityScale" to config.market.baseAssetScale,
                        ),
                    "feePolicy" to
                        mapOf(
                            "productType" to config.feePolicySnapshot.productType.name,
                            "feeTier" to config.feePolicySnapshot.feeTier.name,
                            "scheduleVersion" to config.feePolicySnapshot.scheduleVersion,
                            "makerPartsPerMillion" to config.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion,
                            "takerPartsPerMillion" to config.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion,
                        ),
                ),
        ),
    )
}

internal fun fundingResultFields(payload: DevelopmentBalanceResultPayload): Map<String, Any?> =
    when (payload) {
        is DevelopmentBalanceResultPayload.Prepared -> {
            payload.result.let { result ->
                mapOf(
                    "preparationId" to result.opening.preparationId,
                    "userId" to result.opening.userId.value,
                    "assetId" to result.opening.assetId.value,
                    "amount" to result.opening.amount.value,
                    "ledgerTransactionId" to result.ledgerTransactionId,
                    "occurredAt" to result.occurredAt.toString(),
                    "alreadyPrepared" to result.alreadyPrepared,
                )
            }
        }

        is DevelopmentBalanceResultPayload.Rejected -> {
            mapOf(
                "preparationId" to payload.opening.preparationId,
                "userId" to payload.opening.userId.value,
                "assetId" to payload.opening.assetId.value,
                "amount" to payload.opening.amount.value,
                "reason" to payload.reason.name,
            )
        }
    }

internal fun fundingAppliedEvent(
    config: DevelopmentFundingSettings,
    observation: FundingObservation,
) {
    val snapshot = observation.snapshot
    val result = observation.result
    fundingEvent(
        mapOf(
            "kind" to if (observation.failure == null) "APPLIED" else "ERROR",
            "runId" to config.runId,
            "memberId" to config.memberId,
            "role" to observation.appliedRole,
            "position" to observation.position,
            "preparationId" to result?.let { fundingResultFields(it)["preparationId"] },
            "failure" to observation.failure,
            "replyStatus" to observation.replyStatus,
            "result" to result?.let(::fundingResultFields),
            "state" to
                snapshot?.let { state ->
                    mapOf(
                        "balances" to
                            state.balances.map { balance ->
                                mapOf(
                                    "userId" to balance.userId.value,
                                    "assetId" to balance.assetId.value,
                                    "available" to balance.available.value,
                                    "hold" to balance.hold.value,
                                )
                            },
                        "preparationCount" to state.preparations.size,
                        "pendingOrderCount" to state.pendingOrders.size,
                        "requestRecordCount" to state.requests.size,
                        "lastSequence" to state.lastSequence,
                        "preparations" to
                            state.preparations.map {
                                fundingResultFields(DevelopmentBalanceResultPayload.Prepared(it.result))
                            },
                        "transactions" to
                            state.preparations.map { preparation ->
                                val transaction = preparation.transaction
                                mapOf(
                                    "transactionId" to transaction.ledgerTransactionId,
                                    "sourceEventId" to transaction.sourceEventId,
                                    "kind" to transaction.transactionType.name,
                                    "occurredAt" to transaction.occurredAt.toString(),
                                    "postings" to
                                        transaction.postings.map { posting ->
                                            mapOf(
                                                "accountId" to posting.accountId,
                                                "assetId" to posting.assetId.value,
                                                "side" to posting.side.name,
                                                "amount" to posting.amount.value,
                                            )
                                        },
                                )
                            },
                    )
                },
        ),
    )
}

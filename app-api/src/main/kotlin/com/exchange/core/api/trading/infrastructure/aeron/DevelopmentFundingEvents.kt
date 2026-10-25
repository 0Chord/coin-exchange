package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant

/** 개발 프로세스의 입출력 경계다. 업무 callback은 값을 완성하고 main만 출력한다. */
private val fundingJson = JsonMapper.builder().build()

internal fun developmentClusterSettings(environment: Map<String, String> = System.getenv()): DevelopmentFundingSettings {
    fun required(name: String): String = requireNotNull(environment[name]) { "$name 설정이 필요합니다" }
    return DevelopmentFundingSettings(
        runRoot = Path.of(required("FUNDING_RUN_ROOT")),
        markets = readDevelopmentMarkets(environment),
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
) = fundingEvent(fundingReadyFields(config, node))

internal fun fundingReadyFields(
    config: DevelopmentFundingSettings,
    node: DevelopmentFundingNode,
): Map<String, Any?> =
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
                "markets" to fundingMarketFields(config.markets),
                "marketConfigVersion" to config.markets.configurationVersion,
                "marketConfigSha256" to config.markets.contentSha256,
            ),
    )

internal fun fundingMarketFields(markets: DevelopmentMarketRegistry): List<Map<String, Any?>> =
    markets.all().map { context ->
        marketFields(context.market) + ("feePolicy" to feePolicyFields(context.feePolicySnapshot))
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
) = fundingEvent(fundingAppliedFields(config, observation))

/** 불변 관찰의 의미 필드만 펼친다. 역할과 응답 시도는 업무 상태 밖에 둔다. */
internal fun fundingAppliedFields(
    config: DevelopmentFundingSettings,
    observation: FundingObservation,
): Map<String, Any?> {
    val funding = observation.result?.let(::fundingResultFields)
    val admission = observation.admissionResult?.let(::admissionResultFields)
    return mapOf(
        "kind" to if (observation.failure == null) "APPLIED" else "ERROR",
        "runId" to config.runId,
        "memberId" to config.memberId,
        "role" to observation.appliedRole,
        "position" to observation.position,
        "inputKind" to
            when {
                funding != null -> "FUNDING"
                admission != null -> "ORDER"
                else -> null
            },
        "preparationId" to funding?.get("preparationId"),
        "userId" to (funding?.get("userId") ?: admission?.get("userId")),
        "requestId" to admission?.get("requestId"),
        "marketId" to admission?.get("marketId"),
        "orderId" to admission?.get("orderId"),
        "failure" to observation.failure,
        "replyStatus" to observation.replyStatus,
        "result" to (funding ?: admission),
        "state" to observation.snapshot?.let(::admissionStateFields),
    )
}

/** wire 접수 응답과 같은 작은 결과만 내보낸다. 내부 후보는 state에만 있다. */
internal fun admissionResultFields(payload: AdmissionResultPayload): Map<String, Any?> =
    mapOf(
        "userId" to payload.userId,
        "requestId" to payload.requestId,
        "marketId" to payload.marketId,
        "orderId" to payload.orderId,
    ) +
        when (payload) {
            is AdmissionResultPayload.Accepted -> {
                mapOf("outcome" to "ACCEPTED", "sequence" to payload.sequence)
            }

            is AdmissionResultPayload.Rejected -> {
                mapOf(
                    "outcome" to "REJECTED",
                    "reason" to payload.reason.name,
                    "insufficientBalance" to payload.insufficientBalance?.let(::insufficientBalanceFields),
                )
            }
        }

private fun admissionStateFields(state: OrderAdmissionState.Snapshot): Map<String, Any?> =
    mapOf(
        "balances" to state.balances.map(::balanceFields),
        "preparationCount" to state.preparations.size,
        "pendingOrderCount" to state.pendingOrders.size,
        "requestRecordCount" to state.requests.size,
        "lastSequence" to state.lastSequence,
        "preparations" to state.preparations.map { fundingResultFields(DevelopmentBalanceResultPayload.Prepared(it.result)) },
        // Snapshot의 실제 대기 순서와 최초 결과를 그대로 보존한다.
        "pendingOrders" to state.pendingOrders.map(::admissionRecordFields),
        "requests" to
            state.requests.map { request ->
                mapOf(
                    "requestId" to request.requestId,
                    "order" to orderFields(request.order),
                    "market" to marketFields(request.market),
                    "feePolicy" to feePolicyFields(request.feePolicySnapshot),
                    "ledgerTransactionId" to request.ledgerTransactionId,
                    "result" to storedAdmissionResultFields(request.result),
                ) + instantFields(request.occurredAt)
            },
        // 제출한 거절 원장 ID는 실제 발행한 전표가 아니다.
        "transactions" to
            state.preparations.map { transactionFields(it.transaction) } +
            state.pendingOrders.map { transactionFields(it.candidate.reserveTransaction) },
    )

private fun admissionRecordFields(record: OrderAdmissionState.Record): Map<String, Any?> =
    mapOf(
        "sequence" to record.sequence,
        "candidate" to
            record.candidate.let { candidate ->
                mapOf(
                    "order" to orderFields(candidate.order),
                    "market" to marketFields(candidate.market),
                    "feePolicy" to feePolicyFields(candidate.feePolicySnapshot),
                    "previousBalance" to balanceFields(candidate.previousBalance),
                    "nextBalance" to balanceFields(candidate.nextBalance),
                    "reservation" to reservationFields(candidate.reservation),
                    "reserveTransaction" to transactionFields(candidate.reserveTransaction),
                )
            },
    )

private fun storedAdmissionResultFields(result: OrderAdmissionState.Result): Map<String, Any?> =
    when (result) {
        is OrderAdmissionState.Result.Accepted -> {
            mapOf("outcome" to "ACCEPTED", "record" to admissionRecordFields(result.record))
        }

        is OrderAdmissionState.Result.Rejected -> {
            mapOf(
                "outcome" to "REJECTED",
                "reason" to result.reason.name,
                "insufficientBalance" to result.insufficientBalance?.let(::insufficientBalanceFields),
            )
        }
    }

private fun orderFields(order: OrderAdmissionInput): Map<String, Any?> =
    mapOf(
        "marketId" to order.marketId.value,
        "orderId" to order.orderId.value,
        "userId" to order.userId.value,
        "side" to order.side.name,
        "orderType" to order.orderType.name,
        "timeInForce" to order.timeInForce.name,
        "price" to order.price.value,
        "quantity" to order.quantity.value,
    )

private fun marketFields(market: MarketDefinition): Map<String, Any?> =
    mapOf(
        "marketId" to market.marketId.value,
        "baseAssetId" to market.baseAssetId.value,
        "quoteAssetId" to market.quoteAssetId.value,
        "baseQuantityScale" to market.baseAssetScale,
        "priceTick" to market.priceTick,
        "quantityStep" to market.quantityStep,
    )

private fun feePolicyFields(policy: TradingFeePolicySnapshot): Map<String, Any?> =
    mapOf(
        "productType" to policy.productType.name,
        "feeTier" to policy.feeTier.name,
        "scheduleVersion" to policy.scheduleVersion,
        "makerPartsPerMillion" to policy.feeRates.makerFeeRate.partsPerMillion,
        "takerPartsPerMillion" to policy.feeRates.takerFeeRate.partsPerMillion,
    )

private fun balanceFields(balance: Balance): Map<String, Any?> =
    mapOf(
        "userId" to balance.userId.value,
        "assetId" to balance.assetId.value,
        "available" to balance.available.value,
        "hold" to balance.hold.value,
    )

private fun reservationFields(reservation: OrderReservation): Map<String, Any?> =
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
        "feePolicy" to feePolicyFields(reservation.feePolicySnapshot),
        "initialFeeReserveAmount" to reservation.initialFeeReserveAmount.value,
        "remainingFeeReserveAmount" to reservation.remainingFeeReserveAmount.value,
        "status" to reservation.status.name,
        "feeRemainder" to reservation.feeRemainder.numerator,
        "releasedAmount" to reservation.releasedAmount?.value,
    )

private fun insufficientBalanceFields(balance: OrderAdmissionResult.InsufficientBalance): Map<String, Any?> =
    mapOf(
        "assetId" to balance.assetId.value,
        "available" to balance.available.value,
        "required" to balance.required.value,
        "shortfall" to balance.shortfall.value,
    )

private fun transactionFields(transaction: LedgerTransaction): Map<String, Any?> =
    mapOf(
        "transactionId" to transaction.ledgerTransactionId,
        "sourceEventId" to transaction.sourceEventId,
        "kind" to transaction.transactionType.name,
        "postings" to
            transaction.postings.map { posting ->
                mapOf(
                    "accountId" to posting.accountId,
                    "assetId" to posting.assetId.value,
                    "side" to posting.side.name,
                    "amount" to posting.amount.value,
                )
            },
    ) + instantFields(transaction.occurredAt)

private fun instantFields(instant: Instant): Map<String, Any?> =
    mapOf(
        "occurredAt" to instant.toString(),
        "occurredAtEpochSecond" to instant.epochSecond,
        "occurredAtNano" to instant.nano,
    )

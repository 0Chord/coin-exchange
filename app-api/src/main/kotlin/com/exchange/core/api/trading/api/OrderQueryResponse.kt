package com.exchange.core.api.trading.api

import java.time.Instant

/** 상태 미확정 응답에는 주문 필드를 채우지 않는다. revision은 조회 DB의 정규화 스트림 기준이다. */
data class OrderQueryResponse(
    val code: String,
    val orderId: String? = null,
    val marketId: String? = null,
    val userId: String? = null,
    val side: String? = null,
    val orderType: String? = null,
    val timeInForce: String? = null,
    val price: Long? = null,
    val initialQuantity: Long? = null,
    val admissionSequence: Long? = null,
    val marketAdmissionSequence: Long? = null,
    val filledQuantity: Long? = null,
    val remainingQuantity: Long? = null,
    val cancelledQuantity: Long? = null,
    val notExecutedQuantity: Long? = null,
    val state: String? = null,
    val financialStatus: String? = null,
    val pendingReason: String? = null,
    val resultStream: OrderQueryResultStreamResponse? = null,
    val lastAffectedResultSequence: Long? = null,
    val financialThrough: Long? = null,
    val appliedRevision: Long? = null,
    val observedRevision: Long? = null,
    val coveredAdmissionSequence: Long? = null,
    val lifecycle: String? = null,
    val freshness: String? = null,
    val updatedAt: Instant? = null,
)

data class OrderQueryResultStreamResponse(
    val matchingLineageHigh: Long,
    val matchingLineageLow: Long,
    val fundsLineageHigh: Long,
    val fundsLineageLow: Long,
    val marketId: String,
    val ownershipEpoch: Long,
    val configurationVersion: Long,
    val direction: String,
)

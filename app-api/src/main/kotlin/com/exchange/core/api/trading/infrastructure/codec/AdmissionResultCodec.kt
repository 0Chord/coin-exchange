package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionAccepted
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejected
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.api.trading.infrastructure.codec.generated.InsufficientBalance as WireInsufficientBalance

/** 접수 확인에 필요한 값만 변환한다. 거절 사유와 부족 상세가 모순되면 응답을 신뢰하지 않는다. */
class AdmissionResultCodec {
    fun encode(result: AdmissionResultPayload): ByteArray {
        val requestId = AdmissionMessageFormat.text(result.requestId)
        val userId = AdmissionMessageFormat.text(result.userId)
        val marketId = AdmissionMessageFormat.text(result.marketId)
        val orderId = AdmissionMessageFormat.text(result.orderId)
        return when (result) {
            is AdmissionResultPayload.Accepted -> {
                AdmissionMessageFormat.verify(result.sequence > 0, "승인 순번은 양수여야 합니다")
                val message =
                    AdmissionAccepted
                        .newBuilder()
                        .setSequence(result.sequence)
                        .setRequestId(requestId)
                        .setUserId(userId)
                        .setMarketId(marketId)
                        .setOrderId(orderId)
                        .build()
                AdmissionMessageFormat.encode(AdmissionMessageFormat.ACCEPTED, message)
            }

            is AdmissionResultPayload.Rejected -> {
                validateDetails(result.reason, result.insufficientBalance)
                val message =
                    AdmissionRejected
                        .newBuilder()
                        .setReasonValue(
                            when (result.reason) {
                                AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION -> 1
                                AdmissionResultPayload.Reason.INVALID_QUANTITY -> 2
                                AdmissionResultPayload.Reason.INVALID_ORDER_IDENTIFIER -> 3
                                AdmissionResultPayload.Reason.UNREPRESENTABLE_RESERVATION -> 4
                                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE -> 5
                                AdmissionResultPayload.Reason.DUPLICATE_ORDER -> 6
                                AdmissionResultPayload.Reason.INVALID_REQUEST_IDENTIFIER -> 7
                                AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT -> 8
                                AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH -> 9
                            },
                        ).setRequestId(requestId)
                        .setUserId(userId)
                        .setMarketId(marketId)
                        .setOrderId(orderId)
                result.insufficientBalance?.let { detail ->
                    message.setInsufficientBalance(
                        WireInsufficientBalance
                            .newBuilder()
                            .setAssetId(AdmissionMessageFormat.text(detail.assetId.value))
                            .setAvailable(detail.available.value)
                            .setRequired(detail.required.value)
                            .setShortfall(detail.shortfall.value),
                    )
                }
                AdmissionMessageFormat.encode(AdmissionMessageFormat.REJECTED, message.build())
            }
        }
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AdmissionResultPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            when (reader.kind) {
                AdmissionMessageFormat.ACCEPTED -> decodeAccepted(reader)
                AdmissionMessageFormat.REJECTED -> decodeRejected(reader)
                else -> throw AdmissionMessageException("지원하지 않는 응답 종류입니다")
            }
        }

    private fun decodeAccepted(reader: AdmissionMessageReader): AdmissionResultPayload.Accepted {
        val message = reader.parse(AdmissionAccepted.parser())
        AdmissionMessageFormat.verify(
            message.hasSequence() && message.hasRequestId() && message.hasUserId() && message.hasMarketId() && message.hasOrderId(),
            "승인 응답의 필수 필드가 누락됐습니다",
        )
        AdmissionMessageFormat.verify(message.sequence > 0, "승인 순번은 양수여야 합니다")
        return AdmissionResultPayload.Accepted(
            AdmissionMessageFormat.text(message.userId),
            AdmissionMessageFormat.text(message.requestId),
            AdmissionMessageFormat.text(message.marketId),
            AdmissionMessageFormat.text(message.orderId),
            message.sequence,
        )
    }

    private fun decodeRejected(reader: AdmissionMessageReader): AdmissionResultPayload.Rejected {
        val message = reader.parse(AdmissionRejected.parser())
        AdmissionMessageFormat.verify(
            message.hasReason() && message.hasRequestId() && message.hasUserId() && message.hasMarketId() && message.hasOrderId(),
            "거절 응답의 필수 필드가 누락됐습니다",
        )
        val reason =
            when (message.reasonValue) {
                1 -> AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION
                2 -> AdmissionResultPayload.Reason.INVALID_QUANTITY
                3 -> AdmissionResultPayload.Reason.INVALID_ORDER_IDENTIFIER
                4 -> AdmissionResultPayload.Reason.UNREPRESENTABLE_RESERVATION
                5 -> AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE
                6 -> AdmissionResultPayload.Reason.DUPLICATE_ORDER
                7 -> AdmissionResultPayload.Reason.INVALID_REQUEST_IDENTIFIER
                8 -> AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT
                9 -> AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH
                else -> throw AdmissionMessageException("지원하지 않는 거절 사유입니다")
            }
        val detail =
            if (message.hasInsufficientBalance()) {
                val value = message.insufficientBalance
                AdmissionMessageFormat.verifyKnownFields(value)
                AdmissionMessageFormat.verify(
                    value.hasAssetId() && value.hasAvailable() && value.hasRequired() && value.hasShortfall(),
                    "부족 상세의 필수 필드가 누락됐습니다",
                )
                OrderAdmissionResult.InsufficientBalance(
                    AssetId(AdmissionMessageFormat.text(value.assetId)),
                    Amount(value.available),
                    Amount(value.required),
                    Amount(value.shortfall),
                )
            } else {
                null
            }
        validateDetails(reason, detail)
        return AdmissionResultPayload.Rejected(
            AdmissionMessageFormat.text(message.userId),
            AdmissionMessageFormat.text(message.requestId),
            AdmissionMessageFormat.text(message.marketId),
            AdmissionMessageFormat.text(message.orderId),
            reason,
            detail,
        )
    }

    private fun validateDetails(
        reason: AdmissionResultPayload.Reason,
        detail: OrderAdmissionResult.InsufficientBalance?,
    ) {
        AdmissionMessageFormat.verify(
            (reason == AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE) == (detail != null),
            "거절 사유와 부족 상세의 유무가 다릅니다",
        )
        if (detail != null) {
            AdmissionMessageFormat.verify(
                detail.required > detail.available && detail.shortfall.value == detail.required.value - detail.available.value,
                "부족 상세의 필요 금액 또는 차액이 잘못됐습니다",
            )
        }
    }
}

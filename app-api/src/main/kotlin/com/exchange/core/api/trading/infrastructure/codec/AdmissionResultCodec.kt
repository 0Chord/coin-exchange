package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionAcceptedDecoder
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionAcceptedEncoder
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejectedDecoder
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejectedEncoder
import com.exchange.core.api.trading.infrastructure.codec.generated.InsufficientBalancePresence
import com.exchange.core.api.trading.infrastructure.codec.generated.MessageHeaderEncoder
import com.exchange.core.api.trading.infrastructure.codec.generated.RejectionReason
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.trading.OrderAdmissionResult
import org.agrona.concurrent.UnsafeBuffer

/** 접수 확인에 필요한 값만 변환한다. 거절 사유와 부족 상세가 모순되면 응답을 신뢰하지 않는다. */
class AdmissionResultCodec {
    fun encode(result: AdmissionResultPayload): ByteArray {
        val identifiers = listOf(result.requestId, result.userId, result.marketId, result.orderId).map(AdmissionMessageFormat::text)
        val bytes = ByteArray(AdmissionMessageFormat.MAX_MESSAGE_LENGTH)
        val buffer = UnsafeBuffer(bytes)
        val size =
            when (result) {
                is AdmissionResultPayload.Accepted -> {
                    AdmissionMessageFormat.verify(result.sequence > 0, "승인 순번은 양수여야 합니다")
                    val encoder = AdmissionAcceptedEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
                    encoder.sequence(result.sequence)
                    encoder.putRequestId(identifiers[0], 0, identifiers[0].size)
                    encoder.putUserId(identifiers[1], 0, identifiers[1].size)
                    encoder.putMarketId(identifiers[2], 0, identifiers[2].size)
                    encoder.putOrderId(identifiers[3], 0, identifiers[3].size)
                    encoder.checkEncodingIsComplete()
                    encoder.encodedLength()
                }

                is AdmissionResultPayload.Rejected -> {
                    validateDetails(result.reason, result.insufficientBalance)
                    val detail = result.insufficientBalance
                    val asset = AdmissionMessageFormat.text(detail?.assetId?.value ?: "")
                    val encoder = AdmissionRejectedEncoder().wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
                    encoder.reason(RejectionReason.valueOf(result.reason.name))
                    encoder.hasInsufficientBalance(
                        if (detail ==
                            null
                        ) {
                            InsufficientBalancePresence.ABSENT
                        } else {
                            InsufficientBalancePresence.PRESENT
                        },
                    )
                    encoder.available(detail?.available?.value ?: 0)
                    encoder.required(detail?.required?.value ?: 0)
                    encoder.shortfall(detail?.shortfall?.value ?: 0)
                    encoder.putRequestId(identifiers[0], 0, identifiers[0].size)
                    encoder.putUserId(identifiers[1], 0, identifiers[1].size)
                    encoder.putMarketId(identifiers[2], 0, identifiers[2].size)
                    encoder.putOrderId(identifiers[3], 0, identifiers[3].size)
                    encoder.putAssetId(asset, 0, asset.size)
                    encoder.checkEncodingIsComplete()
                    encoder.encodedLength()
                }
            }
        return bytes.copyOf(AdmissionMessageFormat.HEADER_LENGTH + size)
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AdmissionResultPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            when (reader.templateId) {
                AdmissionAcceptedDecoder.TEMPLATE_ID -> decodeAccepted(reader)
                AdmissionRejectedDecoder.TEMPLATE_ID -> decodeRejected(reader)
                else -> throw AdmissionMessageException("지원하지 않는 응답 종류입니다")
            }
        }

    private fun decodeAccepted(reader: AdmissionMessageReader): AdmissionResultPayload.Accepted {
        reader.expect(AdmissionAcceptedDecoder.TEMPLATE_ID, AdmissionAcceptedDecoder.BLOCK_LENGTH)
        val decoder =
            AdmissionAcceptedDecoder().wrap(
                reader.buffer,
                AdmissionMessageFormat.HEADER_LENGTH,
                AdmissionAcceptedDecoder.BLOCK_LENGTH,
                1,
            )
        val sequence = decoder.sequence()
        AdmissionMessageFormat.verify(sequence > 0, "승인 순번은 양수여야 합니다")
        val requestId = reader.text(decoder.limit(), decoder::requestIdLength, decoder::getRequestId)
        val userId = reader.text(decoder.limit(), decoder::userIdLength, decoder::getUserId)
        val marketId = reader.text(decoder.limit(), decoder::marketIdLength, decoder::getMarketId)
        val orderId = reader.text(decoder.limit(), decoder::orderIdLength, decoder::getOrderId)
        reader.finish(decoder.limit())
        return AdmissionResultPayload.Accepted(userId, requestId, marketId, orderId, sequence)
    }

    private fun decodeRejected(reader: AdmissionMessageReader): AdmissionResultPayload.Rejected {
        reader.expect(AdmissionRejectedDecoder.TEMPLATE_ID, AdmissionRejectedDecoder.BLOCK_LENGTH)
        val decoder =
            AdmissionRejectedDecoder().wrap(
                reader.buffer,
                AdmissionMessageFormat.HEADER_LENGTH,
                AdmissionRejectedDecoder.BLOCK_LENGTH,
                1,
            )
        val reason = OrderAdmissionResult.Reason.valueOf(decoder.reason().name)
        val presence = decoder.hasInsufficientBalance()
        AdmissionMessageFormat.verify(presence != InsufficientBalancePresence.NULL_VAL, "부족 상세의 유무 코드가 잘못됐습니다")
        val available = decoder.available()
        val required = decoder.required()
        val shortfall = decoder.shortfall()
        val requestId = reader.text(decoder.limit(), decoder::requestIdLength, decoder::getRequestId)
        val userId = reader.text(decoder.limit(), decoder::userIdLength, decoder::getUserId)
        val marketId = reader.text(decoder.limit(), decoder::marketIdLength, decoder::getMarketId)
        val orderId = reader.text(decoder.limit(), decoder::orderIdLength, decoder::getOrderId)
        val assetId = reader.text(decoder.limit(), decoder::assetIdLength, decoder::getAssetId)
        reader.finish(decoder.limit())
        val detail =
            if (presence == InsufficientBalancePresence.PRESENT) {
                OrderAdmissionResult.InsufficientBalance(AssetId(assetId), Amount(available), Amount(required), Amount(shortfall))
            } else {
                AdmissionMessageFormat.verify(
                    assetId.isEmpty() && available == 0L && required == 0L && shortfall == 0L,
                    "없는 부족 상세에 값이 남았습니다",
                )
                null
            }
        validateDetails(reason, detail)
        return AdmissionResultPayload.Rejected(userId, requestId, marketId, orderId, reason, detail)
    }

    private fun validateDetails(
        reason: OrderAdmissionResult.Reason,
        detail: OrderAdmissionResult.InsufficientBalance?,
    ) {
        AdmissionMessageFormat.verify(
            (reason == OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE) == (detail != null),
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

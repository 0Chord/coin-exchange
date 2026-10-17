package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalancePrepared
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceRejected
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import java.time.Instant

/** 성공은 처음 준비한 원본, 거절은 지금 제출한 원본을 담는다. 현재 잔고는 담지 않는다. */
class DevelopmentBalanceResultCodec {
    fun encode(result: DevelopmentBalanceResultPayload): ByteArray =
        when (result) {
            is DevelopmentBalanceResultPayload.Prepared -> {
                val prepared = result.result
                val message =
                    DevelopmentBalancePrepared
                        .newBuilder()
                        .setPreparationId(AdmissionMessageFormat.text(prepared.opening.preparationId))
                        .setUserId(AdmissionMessageFormat.text(prepared.opening.userId.value))
                        .setAssetId(AdmissionMessageFormat.text(prepared.opening.assetId.value))
                        .setAmount(prepared.opening.amount.value)
                        .setLedgerTransactionId(preparedLedgerId(prepared.ledgerTransactionId))
                        .setOccurredAtEpochSecond(prepared.occurredAt.epochSecond)
                        .setOccurredAtNano(prepared.occurredAt.nano)
                        .setAlreadyPrepared(prepared.alreadyPrepared)
                        .build()
                AdmissionMessageFormat.encode(AdmissionMessageFormat.DEVELOPMENT_BALANCE_PREPARED, message)
            }

            is DevelopmentBalanceResultPayload.Rejected -> {
                val message =
                    DevelopmentBalanceRejected
                        .newBuilder()
                        .setPreparationId(AdmissionMessageFormat.text(result.opening.preparationId))
                        .setUserId(AdmissionMessageFormat.text(result.opening.userId.value))
                        .setAssetId(AdmissionMessageFormat.text(result.opening.assetId.value))
                        .setAmount(result.opening.amount.value)
                        .setReasonValue(
                            when (result.reason) {
                                DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT -> 1
                                DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED -> 2
                            },
                        ).build()
                AdmissionMessageFormat.encode(AdmissionMessageFormat.DEVELOPMENT_BALANCE_REJECTED, message)
            }
        }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): DevelopmentBalanceResultPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            when (reader.kind) {
                AdmissionMessageFormat.DEVELOPMENT_BALANCE_PREPARED -> readPrepared(reader)
                AdmissionMessageFormat.DEVELOPMENT_BALANCE_REJECTED -> readRejected(reader)
                else -> throw AdmissionMessageException("자금 준비 결과가 아닙니다")
            }
        }

    private fun readPrepared(reader: AdmissionMessageReader): DevelopmentBalanceResultPayload.Prepared {
        val message = reader.parse(DevelopmentBalancePrepared.parser())
        AdmissionMessageFormat.verify(
            message.hasPreparationId() && message.hasUserId() && message.hasAssetId() && message.hasAmount() &&
                message.hasLedgerTransactionId() && message.hasOccurredAtEpochSecond() && message.hasOccurredAtNano() &&
                message.hasAlreadyPrepared(),
            "자금 준비 성공의 필수 필드가 누락됐습니다",
        )
        AdmissionMessageFormat.verify(message.occurredAtNano in 0..999_999_999, "나노초 범위가 잘못됐습니다")
        return DevelopmentBalanceResultPayload.Prepared(
            OpeningBalanceResult(
                OpeningBalance(
                    AdmissionMessageFormat.text(message.preparationId),
                    UserId(AdmissionMessageFormat.text(message.userId)),
                    AssetId(AdmissionMessageFormat.text(message.assetId)),
                    Amount(message.amount),
                ),
                preparedLedgerId(message.ledgerTransactionId),
                Instant.ofEpochSecond(message.occurredAtEpochSecond, message.occurredAtNano.toLong()),
                message.alreadyPrepared,
            ),
        )
    }

    private fun readRejected(reader: AdmissionMessageReader): DevelopmentBalanceResultPayload.Rejected {
        val message = reader.parse(DevelopmentBalanceRejected.parser())
        AdmissionMessageFormat.verify(
            message.hasPreparationId() && message.hasUserId() && message.hasAssetId() && message.hasAmount() && message.hasReason(),
            "자금 준비 거절의 필수 필드가 누락됐습니다",
        )
        val reason =
            when (message.reasonValue) {
                1 -> DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT
                2 -> DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED
                else -> throw AdmissionMessageException("자금 준비 거절 사유가 잘못됐습니다")
            }
        return DevelopmentBalanceResultPayload.Rejected(
            OpeningBalance(
                AdmissionMessageFormat.text(message.preparationId),
                UserId(AdmissionMessageFormat.text(message.userId)),
                AssetId(AdmissionMessageFormat.text(message.assetId)),
                Amount(message.amount),
            ),
            reason,
        )
    }

    private fun preparedLedgerId(value: String): String {
        AdmissionMessageFormat.text(value)
        // 명령의 미사용 문맥과 달리 성공 결과는 코어에서 실제 사용한 유효 ID여야 한다.
        AdmissionMessageFormat.verify(
            value.isNotBlank() && '\u0000' !in value && value.codePointCount(0, value.length) <= 64,
            "성공 결과의 원장 ID가 잘못됐습니다",
        )
        return value
    }
}

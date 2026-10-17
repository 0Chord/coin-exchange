package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import java.time.Instant

/** 바이트 변환만 담당한다. 중복 판단과 자금 반영은 기존 코어에 맡긴다. */
class DevelopmentBalanceCommandCodec {
    fun encode(command: DevelopmentBalanceCommandPayload): ByteArray {
        val message =
            DevelopmentBalanceCommand
                .newBuilder()
                .setPreparationId(AdmissionMessageFormat.text(command.opening.preparationId))
                .setUserId(AdmissionMessageFormat.text(command.opening.userId.value))
                .setAssetId(AdmissionMessageFormat.text(command.opening.assetId.value))
                .setAmount(command.opening.amount.value)
                // 재전송에서 쓰이지 않을 문맥의 업무 유효성은 여기서 앞당겨 판단하지 않는다.
                .setLedgerTransactionId(AdmissionMessageFormat.text(command.ledgerTransactionId))
                .setOccurredAtEpochSecond(command.occurredAt.epochSecond)
                .setOccurredAtNano(command.occurredAt.nano)
                .build()
        return AdmissionMessageFormat.encode(AdmissionMessageFormat.DEVELOPMENT_BALANCE_COMMAND, message)
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): DevelopmentBalanceCommandPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            AdmissionMessageFormat.verify(reader.kind == AdmissionMessageFormat.DEVELOPMENT_BALANCE_COMMAND, "자금 준비 명령이 아닙니다")
            val message = reader.parse(DevelopmentBalanceCommand.parser())
            AdmissionMessageFormat.verify(
                message.hasPreparationId() && message.hasUserId() && message.hasAssetId() && message.hasAmount() &&
                    message.hasLedgerTransactionId() && message.hasOccurredAtEpochSecond() && message.hasOccurredAtNano(),
                "자금 준비 명령의 필수 필드가 누락됐습니다",
            )
            AdmissionMessageFormat.verify(message.occurredAtNano in 0..999_999_999, "나노초 범위가 잘못됐습니다")
            DevelopmentBalanceCommandPayload(
                OpeningBalance(
                    AdmissionMessageFormat.text(message.preparationId),
                    UserId(AdmissionMessageFormat.text(message.userId)),
                    AssetId(AdmissionMessageFormat.text(message.assetId)),
                    Amount(message.amount),
                ),
                AdmissionMessageFormat.text(message.ledgerTransactionId),
                Instant.ofEpochSecond(message.occurredAtEpochSecond, message.occurredAtNano.toLong()),
            )
        }
}

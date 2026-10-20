package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageFormat
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageReader
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload

/** bytes는 실제 offer의 복사본, values는 검사에서 독립적으로 준비한 명령 값이다. */
internal data class ExpectedRecordedInput(
    val bytes: ByteArray,
    val position: Long,
    val values: Map<String, Any>,
    val offerPosition: Long,
)

internal enum class RecordingComparisonStatus { MATCH, MISMATCH, UNVERIFIABLE }

internal data class RecordingComparison(
    val status: RecordingComparisonStatus,
    val reasons: List<String>,
)

internal fun compareRecordedInputs(
    expected: List<ExpectedRecordedInput>,
    actual: RecordedArchiveRead,
): RecordingComparison {
    if (actual is RecordedArchiveRead.Unavailable) {
        return RecordingComparison(RecordingComparisonStatus.UNVERIFIABLE, listOf(actual.reason))
    }
    actual as RecordedArchiveRead.Complete
    if (expected.isEmpty() || actual.inputs.isEmpty()) {
        return RecordingComparison(RecordingComparisonStatus.UNVERIFIABLE, listOf("대상 입력이 비어 있습니다"))
    }
    if (expected.any { it.offerPosition <= 0 }) {
        return RecordingComparison(RecordingComparisonStatus.UNVERIFIABLE, listOf("실제 전송 수용 위치가 없습니다"))
    }
    if (actual.consumedPosition != actual.limitPosition || actual.inputs.last().position != actual.limitPosition) {
        return RecordingComparison(RecordingComparisonStatus.UNVERIFIABLE, listOf("고정한 L까지 완전히 읽지 못했습니다"))
    }
    val differences = mutableListOf<String>()
    if (expected.size != actual.inputs.size) differences.add("입력 건수: expected=${expected.size}, actual=${actual.inputs.size}")
    expected.zip(actual.inputs).forEachIndexed { index, (sent, recorded) ->
        val prefix = "입력 ${index + 1}"
        if (sent.position != recorded.position) differences.add("$prefix 처리 위치: expected=${sent.position}, actual=${recorded.position}")
        if (!sent.bytes.contentEquals(recorded.bytes)) differences.add("$prefix 원본 바이트 불일치")
        val values =
            try {
                when (AdmissionMessageReader(recorded.bytes, 0, recorded.bytes.size).kind) {
                    AdmissionMessageFormat.DEVELOPMENT_BALANCE_COMMAND -> {
                        fundingValues(
                            DevelopmentBalanceCommandCodec().decode(recorded.bytes),
                        )
                    }

                    AdmissionMessageFormat.COMMAND -> {
                        orderValues(AdmissionCommandCodec().decode(recorded.bytes))
                    }

                    else -> {
                        error("지원하지 않는 업무 명령 종류")
                    }
                }
            } catch (error: Exception) {
                return RecordingComparison(RecordingComparisonStatus.UNVERIFIABLE, listOf("$prefix 업무 값 해석 실패: ${error.message}"))
            }
        (sent.values.keys + values.keys).forEach { field ->
            if (sent.values[field] != values[field]) {
                differences.add("$prefix $field: expected=${sent.values[field]}, actual=${values[field]}")
            }
        }
    }
    return RecordingComparison(
        if (differences.isEmpty()) RecordingComparisonStatus.MATCH else RecordingComparisonStatus.MISMATCH,
        differences,
    )
}

internal fun fundingValues(command: DevelopmentBalanceCommandPayload): Map<String, Any> =
    linkedMapOf(
        "kind" to "FUNDING",
        "preparationId" to command.opening.preparationId,
        "userId" to command.opening.userId.value,
        "assetId" to command.opening.assetId.value,
        "amount" to command.opening.amount.value,
        "ledgerTransactionId" to command.ledgerTransactionId,
        "occurredAt.epochSecond" to command.occurredAt.epochSecond,
        "occurredAt.nano" to command.occurredAt.nano,
    )

internal fun orderValues(command: AdmissionCommandPayload): Map<String, Any> =
    linkedMapOf(
        "kind" to "ORDER",
        "requestId" to command.requestId,
        "order.userId" to command.order.userId.value,
        "order.marketId" to command.order.marketId.value,
        "order.orderId" to command.order.orderId.value,
        "order.side" to command.order.side,
        "order.orderType" to command.order.orderType,
        "order.timeInForce" to command.order.timeInForce,
        "order.price" to command.order.price.value,
        "order.quantity" to command.order.quantity.value,
        "market.marketId" to command.market.marketId.value,
        "market.baseAssetId" to command.market.baseAssetId.value,
        "market.quoteAssetId" to command.market.quoteAssetId.value,
        "market.baseAssetScale" to command.market.baseAssetScale,
        "fee.productType" to command.feePolicySnapshot.productType,
        "fee.feeTier" to command.feePolicySnapshot.feeTier,
        "fee.scheduleVersion" to command.feePolicySnapshot.scheduleVersion,
        "fee.makerPpm" to command.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion,
        "fee.takerPpm" to command.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion,
        "ledgerTransactionId" to command.ledgerTransactionId,
        "occurredAt.epochSecond" to command.occurredAt.epochSecond,
        "occurredAt.nano" to command.occurredAt.nano,
    )

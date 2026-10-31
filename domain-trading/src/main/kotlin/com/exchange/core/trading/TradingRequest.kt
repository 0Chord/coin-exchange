package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.util.Base64

/** 리더/프로세스 재시작에서도 유지하는 128-bit 업무 이력 식별자. 여기서 난수를 만들지 않는다. */
data class ClusterLineage(
    val high: Long,
    val low: Long,
)

data class RequestToken(
    val userId: UserId,
    val channel: Int,
    val epoch: Long,
    val sequence: Long,
) {
    init {
        require(channel >= 0 && epoch > 0 && sequence > 0) { "invalid request token" }
        require(tradingTextBytes(userId.value) in 1..MAX_TRADING_TEXT_BYTES && ':' !in userId.value)
    }
}

data class OrderIntent(
    val marketId: MarketId,
    val expectedConfigurationVersion: Long,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Price,
    val quantity: Quantity,
) {
    init {
        require(expectedConfigurationVersion > 0)
        require(tradingTextBytes(marketId.value) in 1..MAX_TRADING_TEXT_BYTES)
    }
}

enum class AdmissionCode { ACCEPTED, REJECTED }

/** 금융 후보/원장/과거 Balance를 참조하지 않는 최초 응답. */
data class AdmissionReceipt(
    val code: AdmissionCode,
    val orderId: OrderId? = null,
    val admissionSequence: Long? = null,
    val marketAdmissionSequence: Long? = null,
    val insufficientBalance: OrderAdmissionResult.InsufficientBalance? = null,
    val reason: OrderAdmissionResult.Reason? = null,
) {
    init {
        when (code) {
            AdmissionCode.ACCEPTED -> {
                require(orderId != null && orderId.value.length <= 64 && orderId.value.all { it.code in 33..126 })
                require(admissionSequence != null && admissionSequence > 0)
                require(marketAdmissionSequence != null && marketAdmissionSequence > 0)
                require(reason == null && insufficientBalance == null)
            }

            AdmissionCode.REJECTED -> {
                require(orderId == null && admissionSequence == null && marketAdmissionSequence == null && reason != null)
                require((insufficientBalance != null) == (reason == OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE))
                insufficientBalance?.let {
                    require(tradingTextBytes(it.assetId.value) in 1..MAX_TRADING_TEXT_BYTES)
                    require(it.required > it.available && it.shortfall.value == it.required.value - it.available.value)
                }
            }
        }
    }
}

/** 같은 승인 identity에서 나온 주문/예약 원장 ID. 외부 임의 문자열을 짝으로 받지 않는다. */
class AdmissionIdentifiers internal constructor(
    val orderId: OrderId,
    val reserveTransactionId: String,
)

/** 고정 길이 숫자를 그대로 encode한다. 시장 문자열 hash나 로컬 시각에 의존하지 않는다. */
object TradingIdentifiers {
    fun order(
        lineage: ClusterLineage,
        sequence: Long,
    ): OrderId = OrderId(id("O2", lineage, sequence))

    fun reserve(
        lineage: ClusterLineage,
        sequence: Long,
    ): String = id("R2", lineage, sequence)

    fun opening(
        lineage: ClusterLineage,
        sequence: Long,
    ): String = id("B2", lineage, sequence)

    fun admission(
        lineage: ClusterLineage,
        sequence: Long,
    ): AdmissionIdentifiers {
        val body = identityBody(lineage, sequence)
        return AdmissionIdentifiers(OrderId("O2$body"), "R2$body")
    }

    fun settlement(
        lineage: ClusterLineage,
        marketOrdinal: Int,
        engineSequence: Long,
    ): String {
        require(marketOrdinal >= 0 && engineSequence > 0)
        val bytes = ByteArray(28)
        writeLong(bytes, 0, lineage.high)
        writeLong(bytes, 8, lineage.low)
        for (index in 0..3) bytes[16 + index] = (marketOrdinal ushr (24 - index * 8)).toByte()
        writeLong(bytes, 20, engineSequence)
        return "S2" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun id(
        kind: String,
        lineage: ClusterLineage,
        sequence: Long,
    ): String = kind + identityBody(lineage, sequence)

    private fun identityBody(
        lineage: ClusterLineage,
        sequence: Long,
    ): String {
        require(sequence > 0)
        val bytes = ByteArray(24)
        writeLong(bytes, 0, lineage.high)
        writeLong(bytes, 8, lineage.low)
        writeLong(bytes, 16, sequence)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun writeLong(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 0..7) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}

internal const val MAX_TRADING_TEXT_BYTES = 256

/** UTF-8 크기를 임시 바이트 배열 없이 구하며 잘못된 서로게이트/공백 식별자는 거절한다. */
internal fun tradingTextBytes(text: String): Int {
    require(text.isNotBlank() && '\u0000' !in text) { "invalid trading text" }
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val character = text[index++]
        bytes =
            Math.addExact(
                bytes,
                when {
                    character.code < 0x80 -> {
                        1
                    }

                    character.code < 0x800 -> {
                        2
                    }

                    Character.isHighSurrogate(character) -> {
                        require(index < text.length && Character.isLowSurrogate(text[index])) { "invalid trading text surrogate" }
                        index++
                        4
                    }

                    Character.isLowSurrogate(character) -> {
                        throw IllegalArgumentException("invalid trading text surrogate")
                    }

                    else -> {
                        3
                    }
                },
            )
    }
    return bytes
}

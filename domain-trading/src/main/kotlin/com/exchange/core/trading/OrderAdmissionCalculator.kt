package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.InsufficientBalanceException
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionResult.Reason
import java.time.Instant
import java.util.HexFormat

/** 기존 주문·자금 규칙으로 후보를 조합한다. 외부 상태는 변경하지 않는다. */
class OrderAdmissionCalculator(
    private val reservationCalculator: OrderReservationCalculator,
) {
    /**
     * 예상 거절은 결과로, 잘못된 서버 문맥이나 계산 오류는 예외로 전달한다.
     * 기록 ID·시각은 호출자가 정하며, 계산 완료가 접수·저장 완료를 뜻하지 않는다.
     */
    fun calculate(
        order: OrderAdmissionInput,
        market: MarketDefinition,
        feePolicySnapshot: TradingFeePolicySnapshot,
        balance: Balance,
        ledgerTransactionId: String,
        occurredAt: Instant,
    ): OrderAdmissionResult {
        if (!validOrderIdentifiers(order)) {
            return OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER)
        }
        if (order.orderType != OrderType.LIMIT || order.timeInForce != TimeInForce.GTC) {
            return OrderAdmissionResult.Rejected(Reason.UNSUPPORTED_ORDER_CONDITION)
        }
        if (order.quantity.value == 0L) {
            return OrderAdmissionResult.Rejected(Reason.INVALID_QUANTITY)
        }

        require(order.marketId == market.marketId) { "주문과 마켓 정의가 다릅니다" }
        require(order.userId == balance.userId) { "주문과 잔고 소유자가 다릅니다" }
        val expectedAssetId =
            when (order.side) {
                Side.BUY -> market.quoteAssetId
                Side.SELL -> market.baseAssetId
            }
        require(expectedAssetId == balance.assetId) { "주문 방향에 맞는 예약 자산의 잔고가 아닙니다" }
        require(validIdentifier(market.baseAssetId.value, accountPart = true)) { "base 자산 식별자가 잘못됐습니다" }
        require(validIdentifier(market.quoteAssetId.value, accountPart = true)) { "quote 자산 식별자가 잘못됐습니다" }
        require(feePolicySnapshot.productType == FeeProductType.SPOT) { "현물 수수료 정책이 필요합니다" }
        require(validIdentifier(ledgerTransactionId)) { "원장 거래 식별자가 잘못됐습니다" }

        // 수량·정책 문맥을 먼저 확인했으므로 이 단계의 알려진 실패는 금액 표현 거절이다.
        // 뒤의 잔고·예약·원장 오류까지 같은 거절로 바꾸지 않는다.
        val requirement =
            try {
                reservationCalculator.calculate(market, order.side, order.price, order.quantity, feePolicySnapshot)
            } catch (_: IllegalArgumentException) {
                return OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION)
            }

        val nextBalance =
            try {
                balance.reserve(requirement.totalReserveAmount)
            } catch (error: InsufficientBalanceException) {
                return OrderAdmissionResult.Rejected(
                    Reason.INSUFFICIENT_BALANCE,
                    OrderAdmissionResult.InsufficientBalance(
                        assetId = error.assetId,
                        available = error.available,
                        required = error.requested,
                        shortfall = Amount(error.requested.value - error.available.value),
                    ),
                )
            }
        val reservation =
            OrderReservation.create(
                marketId = order.marketId,
                orderId = order.orderId,
                userId = order.userId,
                side = order.side,
                limitPrice = order.price,
                quantity = order.quantity,
                requirement = requirement,
                feePolicySnapshot = feePolicySnapshot,
            )
        val account = "USER:${order.userId.value}:${requirement.assetId.value}"
        val transaction =
            LedgerTransaction(
                ledgerTransactionId = ledgerTransactionId,
                sourceEventId = reserveSourceId(order),
                transactionType = LedgerTransactionType.RESERVE,
                occurredAt = occurredAt,
                postings =
                    listOf(
                        LedgerPosting("$account:AVAILABLE", requirement.assetId, LedgerPostingSide.DEBIT, requirement.totalReserveAmount),
                        LedgerPosting("$account:HOLD", requirement.assetId, LedgerPostingSide.CREDIT, requirement.totalReserveAmount),
                    ),
            )
        return OrderAdmissionResult.Calculated(
            OrderAdmissionCandidate(order, market, feePolicySnapshot, balance, nextBalance, reservation, transaction),
        )
    }

    internal fun validOrderIdentifiers(order: OrderAdmissionInput): Boolean =
        validIdentifier(order.marketId.value) &&
            validIdentifier(order.orderId.value) &&
            validIdentifier(order.userId.value, accountPart = true)

    /** UTF-8 변환 전에 잘못된 서로게이트를 거절하고, 길이는 UTF-16 칸 수가 아닌 code point로 센다. */
    internal fun validIdentifier(
        value: String,
        accountPart: Boolean = false,
    ): Boolean {
        if (value.isBlank() || '\u0000' in value || (accountPart && ':' in value)) return false
        var index = 0
        var count = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }

                Character.isLowSurrogate(character) -> {
                    return false
                }

                else -> {
                    index++
                }
            }
            if (++count > 64) return false
        }
        return true
    }

    /** 기존 RESERVE와 같은 원본 바이트 계약. market/order의 콜론이나 다국어를 그대로 구분한다. */
    private fun reserveSourceId(order: OrderAdmissionInput): String {
        val marketBytes = order.marketId.value.toByteArray(Charsets.UTF_8)
        val orderBytes = order.orderId.value.toByteArray(Charsets.UTF_8)
        val source = ByteArray(Int.SIZE_BYTES * 2 + marketBytes.size + orderBytes.size)
        writeTradingIdentifierInt(source, 0, marketBytes.size)
        marketBytes.copyInto(source, Int.SIZE_BYTES)
        val orderOffset = Int.SIZE_BYTES + marketBytes.size
        writeTradingIdentifierInt(source, orderOffset, orderBytes.size)
        orderBytes.copyInto(source, orderOffset + Int.SIZE_BYTES)
        val digest = tradingIdentifierSha256(source)
        return "RESERVE:v1:${HexFormat.of().formatHex(digest)}"
    }
}

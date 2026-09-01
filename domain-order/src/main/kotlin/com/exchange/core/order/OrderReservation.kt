package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot

/**
 * 하나의 market을 구성하는 자산 정보.
 *
 * BTC-KRW 기준:
 * - base asset: BTC
 * - quote asset: KRW
 *
 * @property marketId 마켓 식별자. 예: `BTC-KRW`
 * @property baseAssetId 거래 대상 자산. 예: BTC
 * @property quoteAssetId 가격과 대금을 표현하는 자산. 예: KRW
 * @property baseAssetScale base 자산 수량을 최소 단위로 표현할 때 사용하는 소수점 자릿수.
 * 예를 들어 BTC가 8이면 1 BTC는 100,000,000 최소 단위다
 */
data class MarketDefinition(
    val marketId: MarketId,
    val baseAssetId: AssetId,
    val quoteAssetId: AssetId,
    val baseAssetScale: Int,
) {
    init {
        require(baseAssetId != quoteAssetId) {
            "base asset and quote asset must be different"
        }

        require(baseAssetScale in 0..18) {
            "baseAssetScale must be between 0 and 18"
        }
    }
}

/**
 * 주문을 MatchingEngine에 넣기 전에 동결해야 하는 자산과 금액.
 *
 * BUY는 quote 자산에서 주문 대금과 수수료 예약액을 함께 동결한다.
 * SELL은 base 자산 수량만 동결하고 수수료 예약액은 0으로 둔다.
 *
 * @property assetId Balance에서 hold할 자산
 * @property tradeReserveAmount 주문 대금 또는 SELL 수량을 위해 예약할 금액
 * @property feeReserveAmount BUY 수수료를 위해 추가로 예약할 금액
 */
data class ReservationRequirement(
    val assetId: AssetId,
    val tradeReserveAmount: Amount,
    val feeReserveAmount: Amount,
) {
    /**
     * 기존 수수료 미지원 호출부에서 단일 예약 금액으로 생성한다.
     *
     * [amount] 전체를 거래 예약액으로 사용하고 수수료 예약액은 0으로 둔다.
     */
    constructor(
        assetId: AssetId,
        amount: Amount,
    ) : this(
        assetId = assetId,
        tradeReserveAmount = amount,
        feeReserveAmount = Amount.ZERO,
    )

    /**
     * Balance의 available에서 hold로 이동해야 할 전체 금액.
     *
     * 거래 예약액과 수수료 예약액의 합이 Long 범위를 넘으면 생성에 실패한다.
     */
    val totalReserveAmount: Amount =
        try {
            Amount(
                Math.addExact(
                    tradeReserveAmount.value,
                    feeReserveAmount.value,
                ),
            )
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException(
                "total reservation amount overflow",
                error,
            )
        }

}

/**
 * 주문별 자산 예약의 생명주기 상태.
 */
enum class OrderReservationStatus {
    /** 아직 미체결 수량과 예약 금액이 남아 있어 추가 체결이나 취소가 가능한 상태. */
    ACTIVE,

    /** 주문이 전량 체결되어 남은 수량과 예약 금액이 모두 0인 상태. */
    SETTLED,

    /**
     * 주문이 취소되어 남은 예약 금액을 반환한 상태.
     *
     * 미체결 수량은 남아 있을 수 있다.
     */
    RELEASED,
}

/**
 * 한 주문이 사용자의 전체 hold 중 얼마를 책임지고 있는지 기록하는 주문별 예약.
 *
 * Balance는 사용자·자산별 전체 available/hold를 나타내지만, 이 객체는 특정 주문 하나의
 * 최초 주문 수량과 현재 남은 수량, 최초 예약 금액과 현재 남은 예약 금액을 추적한다.
 *
 * BUY 예약의 [assetId]는 market의 quote 자산이고, SELL 예약의 [assetId]는 base 자산이다.
 * 이 객체는 불변 객체이며 [release]와 [applyFill]은 원본을 변경하지 않고
 * 새 객체를 반환한다.
 *
 * @property marketId 주문이 속한 마켓
 * @property orderId 예약을 소유한 주문 식별자
 * @property userId 주문 소유자
 * @property side 주문 방향. BUY 또는 SELL
 * @property assetId 이 주문 때문에 hold된 자산
 * @property limitPrice 주문자가 허용한 지정가
 * @property initialQuantity 주문 생성 시점의 최초 수량
 * @property remainingQuantity 아직 체결되지 않은 수량
 * @property reservedAmount 거래 금액과 수수료 예약액을 합한 주문 생성 시점의 최초 예약 금액
 * @property remainingAmount 체결이나 취소 후에도 이 주문이 잡고 있는 거래·수수료 예약
 * 금액의 합
 * @property feePolicySnapshot 주문 접수 시점에 확정한 상품, 등급, 정책 버전과 maker/taker 요율.
 * 이후 정책이 바뀌어도 이 주문의 체결 수수료는 이 snapshot을 기준으로 계산한다.
 * @property initialFeeReserveAmount 주문 생성 시 [reservedAmount]에 포함해 최초로 예약한 최대
 * 수수료
 * @property remainingFeeReserveAmount 아직 체결에 사용하거나 사용자에게 반환하지 않은 수수료
 * 예약액
 * @property status 예약의 현재 생명주기 상태
 * @throws IllegalArgumentException 최초/남은 수량, 거래·수수료 예약 금액 또는 [status]의 조합이
 * 유효하지 않은 경우
 */
data class OrderReservation(
    val marketId: MarketId,
    val orderId: OrderId,
    val userId: UserId,
    val side: Side,
    val assetId: AssetId,
    val limitPrice: Price,
    val initialQuantity: Quantity,
    val remainingQuantity: Quantity,
    val reservedAmount: Amount,
    val remainingAmount: Amount,
    val feePolicySnapshot: TradingFeePolicySnapshot,
    val initialFeeReserveAmount: Amount,
    val remainingFeeReserveAmount: Amount,
    val status: OrderReservationStatus,
) {
    init {
        require(initialQuantity.value > 0) {
            "initialQuantity must be positive"
        }

        require(remainingQuantity <= initialQuantity) {
            "remaining quantity must not exceed initial quantity"
        }
        require(reservedAmount.value > 0) {
            "reserved amount must be positive"
        }

        require(remainingAmount <= reservedAmount) {
            "remaining amount must not exceed reserved amount"
        }

        require(initialFeeReserveAmount <= reservedAmount) {
            "initial fee reserve must not exceed reserved amount"
        }

        require(remainingFeeReserveAmount <= initialFeeReserveAmount) {
            "remaining fee reserve must not exceed initial fee reserve"
        }

        require(remainingFeeReserveAmount <= remainingAmount) {
            "remaining fee reserve must not exceed remaining reserved amount"
        }

        when (status) {
            OrderReservationStatus.ACTIVE -> {
                require(!remainingQuantity.isZero()) {
                    "active reservation must have remaining quantity"
                }

                require(!remainingAmount.isZero()) {
                    "active reservation must have remaining amount"
                }
            }

            OrderReservationStatus.SETTLED -> {
                require(remainingQuantity.isZero()) {
                    "settled reservation must not have remaining quantity"
                }

                require(remainingAmount.isZero()) {
                    "settled reservation must not have remaining amount"
                }
            }

            OrderReservationStatus.RELEASED -> {
                require(remainingAmount.isZero()) {
                    "released reservation must not have remaining amount"
                }
            }
        }
    }

    /**
     * 활성 주문의 남은 예약 금액을 모두 반환한 상태로 전환한다.
     *
     * 취소된 미체결 수량을 기록하기 위해 [remainingQuantity]는 그대로 유지하고,
     * [remainingAmount]와 그 안에 포함된 [remainingFeeReserveAmount]를 모두 0으로 만든 뒤
     * 상태를 [OrderReservationStatus.RELEASED]로 바꾼다. 실제 Balance의 hold 반환과 DB 저장은
     * 애플리케이션 서비스가 같은 트랜잭션에서 처리한다.
     *
     * @return 남은 예약 금액이 0이고 상태가 RELEASED인 새 주문 예약
     * @throws IllegalStateException 현재 상태가 ACTIVE가 아닐 경우
     */
    fun release(): OrderReservation {
        check(status == OrderReservationStatus.ACTIVE) {
            "only active reservation can be released"
        }

        return copy(
            remainingAmount = Amount.ZERO,
            remainingFeeReserveAmount = Amount.ZERO,
            status = OrderReservationStatus.RELEASED,
        )
    }

    /**
     * 이미 발생한 체결을 주문 예약의 남은 수량과 금액에 반영한다.
     *
     * 이 메서드는 매칭을 수행하거나 Balance를 변경하지 않는다. BUY와 SELL에 따라 달라지는
     * 거래 예약 감소액과 BUY 수수료 예약 감소액은 [OrderFillSettlementCalculator]가
     * 계산해서 전달한다. 남은 수량이 0이면 상태를 SETTLED로, 수량이 남으면 ACTIVE로
     * 유지한다.
     *
     * @param filledQuantity 이번 체결에서 처리된 base 자산 수량
     * @param tradeReserveAmountToReduce 이번 체결로 거래 예약 장부에서 줄일 금액
     * @param feeReserveAmountToReduce 이번 체결 몫으로 수수료 예약 장부에서 줄일 금액
     * @return 체결 후 남은 수량, 거래·수수료 예약 금액과 상태를 담은 새 주문 예약
     * @throws IllegalStateException 현재 상태가 ACTIVE가 아닐 경우
     * @throws IllegalArgumentException 체결 수량이나 거래·수수료 예약 감소 금액이 유효 범위를
     * 벗어날 경우
     */
    fun applyFill(
        filledQuantity: Quantity,
        tradeReserveAmountToReduce: Amount,
        feeReserveAmountToReduce: Amount,
    ): OrderReservation {
        check(status == OrderReservationStatus.ACTIVE) {
            "only active reservation can be filled"
        }

        require(filledQuantity.value > 0) {
            "filled quantity must be positive"
        }

        require(filledQuantity <= remainingQuantity) {
            "filled quantity must not exceed remaining quantity"
        }

        require(tradeReserveAmountToReduce.value > 0) {
            "trade reserve amount to reduce must be positive"
        }

        /*
         * remainingAmount에는 거래 예약액과 수수료 예약액이 함께 들어 있다.
         * 따라서 남은 거래 예약액은 수수료 예약액을 제외한 값이다.
         */
        val remainingTradeReserveAmount =
            Amount(
                remainingAmount.value - remainingFeeReserveAmount.value,
            )

        require(tradeReserveAmountToReduce <= remainingTradeReserveAmount) {
            "trade reserve amount to reduce must not exceed remaining trade reserve"
        }

        require(feeReserveAmountToReduce <= remainingFeeReserveAmount) {
            "fee reserve amount to reduce must not exceed remaining fee reserve"
        }

        val totalReserveAmountToReduce =
            Amount(
                Math.addExact(
                    tradeReserveAmountToReduce.value,
                    feeReserveAmountToReduce.value,
                ),
            )

        val nextRemainingQuantity =
            remainingQuantity - filledQuantity

        val nextRemainingAmount =
            Amount(
                remainingAmount.value - totalReserveAmountToReduce.value,
            )

        val nextRemainingFeeReserveAmount =
            Amount(
                remainingFeeReserveAmount.value -
                    feeReserveAmountToReduce.value,
            )

        val nextStatus =
            if (nextRemainingQuantity.isZero()) {
                OrderReservationStatus.SETTLED
            } else {
                OrderReservationStatus.ACTIVE
            }

        return copy(
            remainingQuantity = nextRemainingQuantity,
            remainingAmount = nextRemainingAmount,
            remainingFeeReserveAmount = nextRemainingFeeReserveAmount,
            status = nextStatus,
        )
    }

    companion object {
        /**
         * 주문 자금 예약 결과로 새로운 ACTIVE 주문 예약을 생성한다.
         *
         * 최초 값과 남은 값을 동일하게 시작하며, [requirement]의 자산과 금액은
         * [OrderReservationCalculator]가 주문 방향에 맞게 계산한다. 수수료 정책과 수수료
         * 예약액도 함께 보존해 이후 체결과 취소에서 주문 접수 시점의 기준을 사용한다.
         *
         * @param marketId 주문이 제출될 마켓
         * @param orderId 주문 식별자
         * @param userId 주문 소유자
         * @param side 주문 방향
         * @param limitPrice 주문 지정가
         * @param quantity 최초 주문 수량
         * @param requirement 주문 제출 전에 동결할 자산, 거래 금액과 수수료 예약액
         * @param feePolicySnapshot 주문 접수 시점에 확정한 maker/taker 수수료 정책
         * @return 최초 수량과 예약 금액을 가진 ACTIVE 주문 예약
         */
        fun create(
            marketId: MarketId,
            orderId: OrderId,
            userId: UserId,
            side: Side,
            limitPrice: Price,
            quantity: Quantity,
            requirement: ReservationRequirement,
            feePolicySnapshot: TradingFeePolicySnapshot,
        ): OrderReservation =
            OrderReservation(
                marketId = marketId,
                orderId = orderId,
                userId = userId,
                side = side,
                assetId = requirement.assetId,
                limitPrice = limitPrice,
                initialQuantity = quantity,
                remainingQuantity = quantity,
                reservedAmount = requirement.totalReserveAmount,
                remainingAmount = requirement.totalReserveAmount,
                feePolicySnapshot = feePolicySnapshot,
                initialFeeReserveAmount = requirement.feeReserveAmount,
                remainingFeeReserveAmount = requirement.feeReserveAmount,
                status = OrderReservationStatus.ACTIVE,
            )
    }
}

/**
 * 주문을 MatchingEngine에 넣기 전에 필요한 예약 자산과 금액을 계산한다.
 *
 * BUY는 market의 quote 자산에서 지정가 기준 거래 대금과 maker/taker 중 높은 요율의
 * 수수료 예약액을 함께 계산한다. SELL은 market의 base 자산을 주문 수량만큼 예약하고
 * 수수료는 체결 대금에서 차감하므로 주문 접수 시점에는 미리 예약하지 않는다.
 *
 * @property buyOrderFundingQuoteCalculator BUY 거래 대금과 최대 수수료 예약액을 계산하는 객체
 */
class OrderReservationCalculator(
    private val buyOrderFundingQuoteCalculator: BuyOrderFundingQuoteCalculator,
) {
    /**
     * 주문 방향에 맞는 자산 예약 요구사항을 계산한다.
     *
     * @param market base/quote 자산과 수량 scale을 가진 마켓 정보
     * @param side BUY 또는 SELL 주문 방향
     * @param price 주문 지정가
     * @param quantity 주문할 base 자산 수량
     * @param feePolicySnapshot 주문 접수 시점에 확정한 maker/taker 수수료 정책
     * @return Balance에서 hold로 이동해야 할 자산과 금액
     * @throws IllegalArgumentException 주문 수량이 0이거나 quote 금액을 정확히 표현할 수
     * 없는 경우
     */
    fun calculate(
        market: MarketDefinition,
        side: Side,
        price: Price,
        quantity: Quantity,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): ReservationRequirement {
        require(quantity.value > 0) {
            "reservation quantity must be positive"
        }

        return when (side) {
            Side.BUY -> {
                val tradeReserveAmount =
                    calculateQuoteAmount(
                        price = price,
                        quantity = quantity,
                        baseAssetScale = market.baseAssetScale,
                    )

                val fundingQuote =
                    buyOrderFundingQuoteCalculator.calculate(
                        market = market,
                        tradeBudgetAmount = tradeReserveAmount,
                        feePolicySnapshot = feePolicySnapshot,
                    )

                ReservationRequirement(
                    assetId = fundingQuote.quoteAssetId,
                    tradeReserveAmount = fundingQuote.tradeBudgetAmount,
                    feeReserveAmount = fundingQuote.feeReserveAmount,
                )
            }

            Side.SELL ->
                ReservationRequirement(
                    assetId = market.baseAssetId,
                    tradeReserveAmount = Amount(quantity.value),
                    feeReserveAmount = Amount.ZERO,
                )
        }
    }
}

package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity

/**
 * 한 주문의 체결 결과를 Reservation과 Balance에 반영하기 위한 정산 계획.
 *
 * 이 객체는 계산 결과만 표현하며 DB나 Balance를 직접 변경하지 않는다.
 * BUY 가격 개선이 발생하면 [reservedAmountToReduce]는 지정가 기준 예약 감소액이고,
 * [holdAmountToConsume]은 실제 체결가 기준 소비액이며 두 값의 차이가 [holdAmountToRelease]다.
 *
 * 각 값이 가리키는 장부는 서로 다르다.
 * - [updatedReservation], [reservedAmountToReduce]: 특정 주문의 예약 장부
 * - [holdAmountToConsume], [holdAmountToRelease]: 사용자·자산별 Balance 장부
 * - [creditAssetId], [creditAmount]: 거래 결과로 받을 반대편 자산
 *
 * @property updatedReservation 체결 수량과 예약 감소액을 반영한 새 주문 예약
 * @property reservedAmountToReduce 주문별 예약 장부에서 줄일 금액
 * @property holdAmountToConsume 실제 거래에 사용되어 Balance hold에서 제거할 금액
 * @property holdAmountToRelease 거래에 사용되지 않아 Balance available로 반환할 금액
 * @property creditAssetId 체결 결과로 사용자에게 지급할 자산
 * @property creditAmount 체결 결과로 사용자에게 지급할 최소 단위 기준 수량 또는 금액
 */
data class OrderFillSettlementPlan(
    val updatedReservation: OrderReservation,
    val reservedAmountToReduce: Amount,
    val holdAmountToConsume: Amount,
    val holdAmountToRelease: Amount,
    val creditAssetId: AssetId,
    val creditAmount: Amount,
)

/**
 * 체결 가격과 체결 수량을 한 주문의 [OrderFillSettlementPlan]으로 변환한다.
 *
 * BUY는 quote 자산 hold에서 실제 체결 대금을 소비하고 가격 개선분을 반환한 뒤
 * 체결 수량만큼 base 자산을 지급한다. SELL은 체결 수량만큼 base 자산 hold를 소비하고
 * 실제 체결 대금만큼 quote 자산을 지급한다.
 *
 * BUY 계산:
 * - 예약 감소액 = 지정가 × 체결 수량
 * - hold 소비액 = 체결가 × 체결 수량
 * - hold 반환액 = 예약 감소액 - hold 소비액
 * - 지급 = base 자산 체결 수량
 *
 * SELL 계산:
 * - 예약 감소액 = hold 소비액 = base 자산 체결 수량
 * - hold 반환액 = 0
 * - 지급 = quote 자산 기준 체결가 × 체결 수량
 *
 * 이 계산기는 순수 도메인 계산만 담당하며 DB 조회, Reservation 저장 또는 Balance 변경을
 * 수행하지 않는다. 실제 저장과 자산 이동은 이후 TradeSettlementService가 담당한다.
 */
class OrderFillSettlementCalculator {
    /**
     * 현재 주문 예약에 한 번의 체결을 적용할 정산 계획을 계산한다.
     *
     * @param market 체결 마켓의 base/quote 자산과 수량 scale 정보
     * @param reservation 체결을 적용할 주문의 현재 예약 상태
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결에서 처리된 base 자산 수량
     * @return Reservation 갱신과 Balance 변경에 필요한 정산 계획
     * @throws IllegalArgumentException Reservation과 market이 다르거나, 예약 자산 또는 체결 가격이
     * 주문 방향의 규칙을 위반하거나, quote 금액을 정확히 표현할 수 없는 경우
     * @throws IllegalStateException ACTIVE 상태가 아닌 Reservation을 정산하려는 경우
     */
    fun calculate(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        // 다른 마켓의 scale이나 자산으로 계산하면 잘못된 자산 이동이 생기므로
        // 먼저 차단한다.
        require(market.marketId == reservation.marketId) {
            "reservation market must match settlement market"
        }

        return when (reservation.side) {
            Side.BUY ->
                calculateBuy(
                    market = market,
                    reservation = reservation,
                    executionPrice = executionPrice,
                    filledQuantity = filledQuantity,
                )

            Side.SELL ->
                calculateSell(
                    market = market,
                    reservation = reservation,
                    executionPrice = executionPrice,
                    filledQuantity = filledQuantity,
                )
        }
    }

    /**
     * BUY 체결의 예약 감소액, 실제 소비액, 가격 개선 반환액과 지급할 base 수량을
     * 계산한다.
     *
     * 예약 감소액은 지정가 기준이고 실제 소비액은 체결가 기준이다.
     * `hold 반환액 = 지정가 기준 예약 감소액 - 실제 체결 대금` 관계를 만족한다.
     *
     * @param market 체결 마켓 정보
     * @param reservation BUY 주문의 현재 예약
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결 수량
     * @return BUY 주문에 적용할 정산 계획
     */
    private fun calculateBuy(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        require(reservation.assetId == market.quoteAssetId) {
            "BUY reservation asset must be market quote asset"
        }

        require(executionPrice <= reservation.limitPrice) {
            "BUY execution price must not exceed limit price"
        }

        // 주문별 예약은 지정가로 잡았으므로 체결된 수량의 지정가 대금만큼 감소시킨다.
        val reservedAmountToReduce =
            calculateQuoteAmount(
                price = reservation.limitPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 사용자의 quote hold에서 실제로 사라질 금액은 더 유리할 수 있는 체결가 대금이다.
        val holdAmountToConsume =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 지정가로 넉넉히 잡았던 금액 중 실제 체결에 쓰지 않은 가격 개선분을 반환한다.
        val holdAmountToRelease =
            Amount(
                reservedAmountToReduce.value - holdAmountToConsume.value,
            )

        // Balance와 별개로 주문별 남은 수량과 지정가 기준 예약 잔액을 갱신한다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                reservedAmountToReduce = reservedAmountToReduce,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = reservedAmountToReduce,
            holdAmountToConsume = holdAmountToConsume,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.baseAssetId,
            creditAmount = Amount(filledQuantity.value),
        )
    }

    /**
     * SELL 체결의 base 자산 소비량과 판매자가 받을 quote 대금을 계산한다.
     *
     * SELL은 체결 수량만큼 base 자산을 예약하고 그대로 소비하므로 반환할 hold가 없다.
     * 판매자에게 지급할 quote 금액은 실제 체결가와 체결 수량으로 계산한다.
     *
     * @param market 체결 마켓 정보
     * @param reservation SELL 주문의 현재 예약
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결 수량
     * @return SELL 주문에 적용할 정산 계획
     */
    private fun calculateSell(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
    ): OrderFillSettlementPlan {
        require(reservation.assetId == market.baseAssetId) {
            "SELL reservation asset must be market base asset"
        }

        require(executionPrice >= reservation.limitPrice) {
            "SELL execution price must not be below limit price"
        }

        // SELL 예약 자산은 base이므로 체결 수량 자체가 예약 감소액이자 hold 소비액이다.
        val reservedAmountToReduce = Amount(filledQuantity.value)

        // SELL은 예약한 base 수량을 그대로 판매하므로 가격 개선 반환액이 없다.
        val holdAmountToRelease = Amount.ZERO

        // 판매자가 받을 quote 금액은 지정가가 아니라 실제 체결가로 계산한다.
        val creditAmount =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 주문별 남은 base 수량과 예약된 base 금액을 같은 체결 수량만큼 줄인다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                reservedAmountToReduce = reservedAmountToReduce,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = reservedAmountToReduce,
            holdAmountToConsume = reservedAmountToReduce,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.quoteAssetId,
            creditAmount = creditAmount,
        )
    }
}

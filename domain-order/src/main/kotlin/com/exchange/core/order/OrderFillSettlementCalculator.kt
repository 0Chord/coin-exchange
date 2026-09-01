package com.exchange.core.order

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator

/**
 * 한 주문의 체결 결과를 Reservation과 Balance에 반영하기 위한 정산 계획.
 *
 * 이 객체는 계산 결과만 표현하며 DB나 Balance를 직접 변경하지 않는다.
 * BUY 가격 개선이 발생하면 [reservedAmountToReduce]는 지정가 기준 거래·수수료 예약
 * 감소액이고, [holdAmountToConsume]은 실제 체결 대금과 수수료 소비액이다. 두 값의 차이가
 * 가격 개선분과 사용하지 않은 수수료 예약액을 합한 [holdAmountToRelease]다.
 * SELL의 [creditAmount]는 실제 체결 대금에서 해당 체결의 maker/taker 수수료를
 * 차감한 quote 자산 순지급액이다.
 *
 * 각 값이 가리키는 장부는 서로 다르다.
 * - [updatedReservation], [reservedAmountToReduce]: 특정 주문의 예약 장부
 * - [holdAmountToConsume], [holdAmountToRelease]: 사용자·자산별 Balance 장부
 * - [creditAssetId], [creditAmount]: 거래 결과로 받을 반대편 자산
 *
 * @property updatedReservation 체결 수량과 예약 감소액을 반영한 새 주문 예약
 * @property reservedAmountToReduce 주문별 거래·수수료 예약 장부에서 줄일 전체 금액
 * @property holdAmountToConsume 실제 거래와 수수료에 사용되어 Balance hold에서 제거할 금액
 * @property holdAmountToRelease 거래에 사용되지 않아 Balance available로 반환할 금액
 * @property creditAssetId 체결 결과로 사용자에게 지급할 자산
 * @property creditAmount 체결 결과로 사용자에게 지급할 최소 단위 기준 수량 또는 순지급액
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
 * BUY는 quote 자산 hold에서 실제 체결 대금과 maker/taker 수수료를 소비하고 가격 개선분과
 * 사용하지 않은 수수료 예약액을 반환한 뒤 체결 수량만큼 base 자산을 지급한다. SELL은
 * 체결 수량만큼 base 자산 hold를 소비하고 실제 체결 대금에서 maker/taker 수수료를
 * 차감한 quote 자산을 지급한다.
 *
 * BUY 계산:
 * - 거래 예약 감소액 = 지정가 × 체결 수량
 * - 수수료 예약 감소액 = 거래 예약 감소액 × 최대 수수료율
 * - 실제 수수료 = 체결가 대금 × maker/taker 수수료율
 * - hold 소비액 = 체결가 대금 + 실제 수수료
 * - hold 반환액 = 전체 예약 감소액 - hold 소비액
 * - 지급 = base 자산 체결 수량
 *
 * SELL 계산:
 * - 예약 감소액 = hold 소비액 = base 자산 체결 수량
 * - hold 반환액 = 0
 * - 총 판매 대금 = quote 자산 기준 체결가 × 체결 수량
 * - 실제 수수료 = 총 판매 대금 × maker/taker 수수료율
 * - 지급 = 총 판매 대금 - 실제 수수료
 *
 * 이 계산기는 순수 도메인 계산만 담당하며 DB 조회, Reservation 저장 또는 Balance 변경을
 * 수행하지 않는다. 실제 저장과 자산 이동은 이후 TradeSettlementService가 담당한다.
 *
 * @property tradingFeeCalculator 체결가 대금과 maker/taker 요율로 실제 수수료를 계산하는 객체
 * @property tradingFeeReserveCalculator 지정가 대금과 최대 요율로 수수료 예약액을 계산하는 객체
 */
class OrderFillSettlementCalculator(
    private val tradingFeeCalculator: TradingFeeCalculator,
    private val tradingFeeReserveCalculator: TradingFeeReserveCalculator,
) {
    /**
     * 현재 주문 예약에 한 번의 체결을 적용할 정산 계획을 계산한다.
     *
     * @param market 체결 마켓의 base/quote 자산과 수량 scale 정보
     * @param reservation 체결을 적용할 주문의 현재 예약 상태
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결에서 처리된 base 자산 수량
     * @param liquidityRole 이번 체결에서 주문이 수행한 maker 또는 taker 역할
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
        liquidityRole: LiquidityRole,
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
                    liquidityRole = liquidityRole,
                )

            Side.SELL ->
                calculateSell(
                    market = market,
                    reservation = reservation,
                    executionPrice = executionPrice,
                    filledQuantity = filledQuantity,
                    liquidityRole = liquidityRole,
                )
        }
    }

    /**
     * BUY 체결의 거래·수수료 예약 감소액, 실제 소비액, 반환액과 지급할 base 수량을
     * 계산한다.
     *
     * 수수료 예약액은 최대 수수료율로 확보하지만 실제 수수료는 체결가 대금과 이번
     * maker/taker 역할의 요율로 계산한다. 따라서 가격 개선분과 사용하지 않은 수수료
     * 예약액을 함께 반환한다.
     *
     * @param market 체결 마켓 정보
     * @param reservation BUY 주문의 현재 예약
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결 수량
     * @param liquidityRole BUY 주문의 이번 체결 maker/taker 역할
     * @return BUY 주문에 적용할 정산 계획
     */
    private fun calculateBuy(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
        liquidityRole: LiquidityRole,
    ): OrderFillSettlementPlan {
        require(reservation.assetId == market.quoteAssetId) {
            "BUY reservation asset must be market quote asset"
        }

        require(executionPrice <= reservation.limitPrice) {
            "BUY execution price must not exceed limit price"
        }

        // 주문별 거래 예약은 지정가로 잡았으므로 체결 수량의 지정가 대금만큼 줄인다.
        val tradeReserveAmountToReduce =
            calculateQuoteAmount(
                price = reservation.limitPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 실제 거래에 사용되는 quote 금액은 지정가가 아니라 체결가를 기준으로 계산한다.
        val executionTradeAmount =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 이번 체결 몫의 최대 수수료 예약액을 계산하고 남은 예약액을 넘지 않게
        // 제한한다.
        val calculatedFeeReserveAmount =
            tradingFeeReserveCalculator.calculateReserve(
                feeReserveBaseAmount = tradeReserveAmountToReduce,
                maximumFeeRate = reservation.feePolicySnapshot.maximumRate(),
            )

        val feeReserveAmountToReduce =
            minOf(
                calculatedFeeReserveAmount,
                reservation.remainingFeeReserveAmount,
            )

        // 실제 수수료는 체결가 대금과 이번 체결의 maker/taker 요율을 사용한다.
        val actualFeeAmount =
            tradingFeeCalculator.calculateFee(
                feeBaseAmount = executionTradeAmount,
                feeRate = reservation.feePolicySnapshot.rateFor(liquidityRole),
            )

        require(actualFeeAmount <= feeReserveAmountToReduce) {
            "actual trading fee must not exceed reserved fee amount"
        }

        val totalReservedAmountToReduce =
            Amount(
                Math.addExact(
                    tradeReserveAmountToReduce.value,
                    feeReserveAmountToReduce.value,
                ),
            )

        val totalHoldAmountToConsume =
            Amount(
                Math.addExact(
                    executionTradeAmount.value,
                    actualFeeAmount.value,
                ),
            )

        // 가격 개선분과 실제로 쓰지 않은 수수료 예약액을 available로 반환한다.
        val holdAmountToRelease =
            Amount(
                totalReservedAmountToReduce.value -
                    totalHoldAmountToConsume.value,
            )

        // 주문별 거래 예약액과 수수료 예약액을 각 장부에서 분리해 감소시킨다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                tradeReserveAmountToReduce = tradeReserveAmountToReduce,
                feeReserveAmountToReduce = feeReserveAmountToReduce,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = totalReservedAmountToReduce,
            holdAmountToConsume = totalHoldAmountToConsume,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.baseAssetId,
            creditAmount = Amount(filledQuantity.value),
        )
    }

    /**
     * SELL 체결의 base 자산 소비량과 판매자가 받을 quote 순지급액을 계산한다.
     *
     * SELL은 체결 수량만큼 base 자산을 예약하고 그대로 소비하므로 반환할 hold가 없다.
     * SELL 수수료는 주문 접수 시 별도로 hold하지 않고, 실제 체결가와 체결 수량으로
     * 계산한 총 판매 대금에서 이번 체결의 maker/taker 수수료를 차감한다.
     *
     * @param market 체결 마켓 정보
     * @param reservation SELL 주문의 현재 예약
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결 수량
     * @param liquidityRole SELL 주문의 이번 체결 maker/taker 역할. SELL 수수료 정산에서 사용한다
     * @return SELL 주문에 적용할 정산 계획
     */
    private fun calculateSell(
        market: MarketDefinition,
        reservation: OrderReservation,
        executionPrice: Price,
        filledQuantity: Quantity,
        liquidityRole: LiquidityRole,
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

        // 판매자가 수수료 차감 전에 받을 실제 체결가 기준 총 quote 대금이다.
        val grossCreditAmount =
            calculateQuoteAmount(
                price = executionPrice,
                quantity = filledQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 주문에 저장된 정책에서 이번 maker/taker 역할의 실제 수수료율을 선택한다.
        val actualFeeAmount =
            tradingFeeCalculator.calculateFee(
                feeBaseAmount = grossCreditAmount,
                feeRate = reservation.feePolicySnapshot.rateFor(liquidityRole),
            )

        require(actualFeeAmount <= grossCreditAmount) {
            "actual trading fee must not exceed gross settlement amount"
        }

        // SELL 수수료는 미리 hold하지 않고 판매 대금에서 바로 차감한다.
        val creditAmount =
            Amount(
                grossCreditAmount.value - actualFeeAmount.value,
            )

        // 주문별 남은 base 수량과 예약된 base 금액을 같은 체결 수량만큼 줄인다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                tradeReserveAmountToReduce = reservedAmountToReduce,
                feeReserveAmountToReduce = Amount.ZERO,
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

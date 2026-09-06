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
 * SELL의 [creditAmount]는 이전 소수 나머지와 이번 maker/taker 요율로 계산한 수수료를
 * 실제 체결 대금에서 차감한 quote 자산 순지급액이다.
 *
 * [actualFeeAmount]는 BUY hold 소비액 또는 SELL 순지급액 계산에 이미 반영된 수수료다.
 * 후속 원장 기록에서 사용할 수 있도록 별도로 반환하며 사용자에게 다시 차감하지 않는다.
 * 현재 BUY와 SELL 모두 quote 자산으로 수수료를 부과하고, 무료 정책이면 금액은 0이다.
 *
 * 각 값이 가리키는 장부는 서로 다르다.
 * - [updatedReservation], [reservedAmountToReduce]: 특정 주문의 예약 장부
 * - [holdAmountToConsume], [holdAmountToRelease]: 사용자·자산별 Balance 장부
 * - [creditAssetId], [creditAmount]: 거래 결과로 받을 반대편 자산
 *
 * @property updatedReservation 체결 수량, 예약 감소액과 다음 체결로 넘길 수수료 소수
 * 나머지를 반영한 새 주문 예약
 * @property reservedAmountToReduce 주문별 거래·수수료 예약 장부에서 줄일 전체 금액
 * @property holdAmountToConsume 실제 거래와 수수료에 사용되어 Balance hold에서 제거할 금액
 * @property holdAmountToRelease 거래에 사용되지 않아 Balance available로 반환할 금액
 * @property creditAssetId 체결 결과로 사용자에게 지급할 자산
 * @property creditAmount 체결 결과로 사용자에게 지급할 최소 단위 기준 수량 또는 순지급액
 * @property feeAssetId 이번 체결의 수수료를 부과하는 자산. 현재 정책에서는 마켓의 quote 자산
 * @property actualFeeAmount 이번 체결에 청구할 수수료. 주문 시 예약액이나 주문 전체 누적 청구액이 아니다.
 * BUY와 SELL 모두 이전 체결의 소수 나머지도 합산해 계산한다.
 */
data class OrderFillSettlementPlan(
    val updatedReservation: OrderReservation,
    val reservedAmountToReduce: Amount,
    val holdAmountToConsume: Amount,
    val holdAmountToRelease: Amount,
    val creditAssetId: AssetId,
    val creditAmount: Amount,
    val feeAssetId: AssetId,
    val actualFeeAmount: Amount,
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
 * - 실제 수수료 = (체결가 대금 × maker/taker 수수료율 + 이전 소수 나머지)의
 *   최소 금액 단위 미만을 버린 금액
 * - 다음 수수료 예약액 = (남은 지정가 대금 × 최대 수수료율 + 새 소수 나머지)를
 *   최소 금액 단위로 올림한 금액. 전량 체결이면 0이다.
 * - 수수료 예약 감소액 = 현재 수수료 예약액 - 다음 수수료 예약액
 * - hold 소비액 = 체결가 대금 + 실제 수수료
 * - hold 반환액 = 전체 예약 감소액 - hold 소비액
 * - 지급 = base 자산 체결 수량
 * - 새 소수 나머지는 주문 예약에 반영해 다음 체결 계산으로 넘긴다
 *
 * SELL 계산:
 * - 예약 감소액 = hold 소비액 = base 자산 체결 수량
 * - hold 반환액 = 0
 * - 총 판매 대금 = quote 자산 기준 체결가 × 체결 수량
 * - 실제 수수료 = (총 판매 대금 × maker/taker 수수료율 + 이전 소수 나머지)의
 *   최소 금액 단위 미만을 버린 금액
 * - 지급 = 총 판매 대금 - 실제 수수료
 * - 새 소수 나머지는 주문 예약에 반영해 다음 체결 계산으로 넘긴다
 *
 * 이 계산기는 순수 도메인 계산만 담당하며 DB 조회, Reservation 저장 또는 Balance 변경을
 * 수행하지 않는다. 실제 저장과 자산 이동은 이후 TradeSettlementService가 담당한다.
 *
 * @property tradingFeeCalculator 체결가 대금과 maker/taker 요율로 실제 수수료를 계산하는 객체
 * @property tradingFeeReserveCalculator 남은 지정가 대금, 최대 요율과 소수 나머지로
 * 유지할 수수료 예약액을 계산하는 객체
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
     * 실제 수수료는 체결가 대금에 이번 maker/taker 요율을 곱하고 이전 소수 나머지를 합산한다.
     * 부분 체결 후에는 남은 지정가 대금의 최대 수수료와 새 나머지를 합산해 올림한 금액을
     * 계속 예약한다. 이번 수수료를 소비하고도 남는 초과분과 가격 개선분만 반환한다.
     * 전량 체결되면 새 나머지는 기록하되, 앞으로의 체결이 없으므로 수수료 예약액은 0이다.
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

        // 이번 청구액을 계산하고, 최소 금액 단위 미만의 나머지는 다음 체결로 넘긴다.
        val feeCalculation =
            tradingFeeCalculator.calculateFee(
                feeBaseAmount = executionTradeAmount,
                feeRate = reservation.feePolicySnapshot.rateFor(liquidityRole),
                previousRemainder = reservation.feeRemainder,
            )

        val actualFeeAmount = feeCalculation.actualFeeAmount

        // 이번 체결 이후에도 남아 있을 수량과 그 수량의 지정가 기준 거래대금이다.
        val nextRemainingQuantity =
            reservation.remainingQuantity - filledQuantity

        val nextRemainingTradeReserveAmount =
            calculateQuoteAmount(
                price = reservation.limitPrice,
                quantity = nextRemainingQuantity,
                baseAssetScale = market.baseAssetScale,
            )

        // 남은 주문에 필요한 수수료와 새 소수 나머지를 함께 고려해 예약액을 유지한다.
        // 주문이 끝나면 나머지가 있어도 실제로 묶어둘 수수료 예약액은 없다.
        val nextRemainingFeeReserveAmount =
            if (nextRemainingQuantity.isZero()) {
                Amount.ZERO
            } else {
                tradingFeeReserveCalculator.calculateReserve(
                    feeReserveBaseAmount = nextRemainingTradeReserveAmount,
                    maximumFeeRate = reservation.feePolicySnapshot.maximumRate(),
                    feeRemainder = feeCalculation.remainder,
                )
            }

        require(
            nextRemainingFeeReserveAmount <= reservation.remainingFeeReserveAmount,
        ) {
            "required fee reserve must not exceed remaining fee reserve"
        }

        // 이번에 수수료로 소비할 금액과 사용자에게 반환할 초과분을 합친 예약 감소액이다.
        val feeReserveAmountToReduce =
            Amount(
                reservation.remainingFeeReserveAmount.value -
                    nextRemainingFeeReserveAmount.value,
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

        // 새 주문 예약 객체를 만든다. 실제 DB 저장이나 Balance 변경은 여기서 하지 않는다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                tradeReserveAmountToReduce = tradeReserveAmountToReduce,
                feeReserveAmountToReduce = feeReserveAmountToReduce,
                nextFeeRemainder = feeCalculation.remainder,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = totalReservedAmountToReduce,
            holdAmountToConsume = totalHoldAmountToConsume,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.baseAssetId,
            creditAmount = Amount(filledQuantity.value),
            feeAssetId = market.quoteAssetId,
            actualFeeAmount = actualFeeAmount,
        )
    }

    /**
     * SELL 체결의 base 자산 소비량과 판매자가 받을 quote 순지급액을 계산한다.
     *
     * SELL은 체결 수량만큼 base 자산을 예약하고 그대로 소비하므로 반환할 hold가 없다.
     * SELL 수수료는 주문 접수 시 별도로 hold하지 않고, 실제 체결가와 체결 수량으로
     * 계산한 총 판매 대금에서 이번 체결의 maker/taker 수수료를 차감한다.
     *
     * 이번 수수료에는 주문에 보관된 이전 소수 나머지를 합산한다. 최소 금액 단위의
     * 정수 금액만 차감하고, 새 나머지는 갱신된 주문 예약에 반영해 다음 체결로 넘긴다.
     *
     * @param market 체결 마켓 정보
     * @param reservation SELL 주문의 현재 예약
     * @param executionPrice 실제 체결 가격
     * @param filledQuantity 이번 체결 수량
     * @param liquidityRole SELL 주문의 이번 체결 maker/taker 역할. SELL 수수료 정산에서 사용한다
     * @return 이번 순지급액·수수료와 새 소수 나머지를 반영한 주문 예약을 포함하는 정산 계획
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

        // 이번 maker/taker 요율과 주문에 보관된 이전 나머지로 수수료를 계산한다.
        val feeCalculation =
            tradingFeeCalculator.calculateFee(
                feeBaseAmount = grossCreditAmount,
                feeRate = reservation.feePolicySnapshot.rateFor(liquidityRole),
                previousRemainder = reservation.feeRemainder,
            )

        val actualFeeAmount = feeCalculation.actualFeeAmount

        require(actualFeeAmount <= grossCreditAmount) {
            "actual trading fee must not exceed gross settlement amount"
        }

        // SELL 수수료는 미리 hold하지 않고 판매 대금에서 바로 차감한다.
        val creditAmount =
            Amount(
                grossCreditAmount.value - actualFeeAmount.value,
            )

        // 체결된 base 수량만큼 예약을 줄이고 다음 체결로 넘길 수수료 나머지도 반영한다.
        val updatedReservation =
            reservation.applyFill(
                filledQuantity = filledQuantity,
                tradeReserveAmountToReduce = reservedAmountToReduce,
                feeReserveAmountToReduce = Amount.ZERO,
                nextFeeRemainder = feeCalculation.remainder,
            )

        return OrderFillSettlementPlan(
            updatedReservation = updatedReservation,
            reservedAmountToReduce = reservedAmountToReduce,
            holdAmountToConsume = reservedAmountToReduce,
            holdAmountToRelease = holdAmountToRelease,
            creditAssetId = market.quoteAssetId,
            creditAmount = creditAmount,
            feeAssetId = market.quoteAssetId,
            actualFeeAmount = actualFeeAmount,
        )
    }
}

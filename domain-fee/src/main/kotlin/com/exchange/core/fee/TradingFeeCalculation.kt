package com.exchange.core.fee

import com.exchange.core.common.Amount

/**
 * 한 번의 체결에서 계산한 수수료와 다음 체결로 넘길 소수 나머지.
 *
 * @property actualFeeAmount 이전 소수 나머지를 합산해 이번에 청구할 최소 금액 단위의 정수 금액. 주문 전체 누적 청구액은 아니다.
 * @property remainder 아직 청구하지 않은 최소 금액 단위 미만의 나머지. 호출부가 같은 주문의 다음 체결 계산에 전달한다.
 */
data class TradingFeeCalculation(
    val actualFeeAmount: Amount,
    val remainder: FeeRemainder,
)

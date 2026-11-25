package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId

/**
 * 잔고를 원자적으로 변경하는 영속성 포트.
 *
 * 도메인의 [Balance] 변경 규칙과 같은 결과를 저장소에서 직접 적용한다. 구현체는 동시
 * 요청이 같은 잔고를 갱신해도 음수 잔고가 생기지 않도록 조건부 UPDATE나 행 잠금을
 * 사용해야 한다.
 */
interface BalanceStore {
    /**
     * available을 줄이고 같은 금액을 hold에 더한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 예약할 자산
     * @param amount 동결할 최소 단위 기준 금액
     * @return DB 변경 직후의 Balance
     * @throws BalanceNotFoundException 사용자·자산 잔고가 존재하지 않는 경우
     * @throws InsufficientBalanceException available이 [amount]보다 적은 경우
     */
    fun reserve(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance

    /**
     * hold를 줄이고 같은 금액을 available에 돌려놓는다.
     *
     * @param userId 잔고 소유자
     * @param assetId 동결을 해제할 자산
     * @param amount 반환할 최소 단위 기준 금액
     * @return DB 변경 직후의 Balance
     * @throws BalanceNotFoundException 사용자·자산 잔고가 존재하지 않는 경우
     * @throws InsufficientHoldException hold가 [amount]보다 적은 경우
     */
    fun release(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance

    /**
     * 체결에 사용된 금액을 hold에서 제거한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 소비할 자산
     * @param amount 소비할 최소 단위 기준 금액
     * @return DB 변경 직후의 Balance
     * @throws BalanceNotFoundException 사용자·자산 잔고가 존재하지 않는 경우
     * @throws InsufficientHoldException hold가 [amount]보다 적은 경우
     */
    fun consumeHold(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance

    /**
     * 지급받은 자산을 available에 더한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 지급할 자산
     * @param amount 지급할 최소 단위 기준 금액
     * @return DB 변경 직후의 Balance
     * @throws BalanceNotFoundException 사용자·자산 잔고가 존재하지 않는 경우
     */
    fun credit(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance
}

package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId

/**
 * 한 사용자가 한 자산에 보유한 잔고 상태.
 *
 * [available]은 새 주문이나 출금에 사용할 수 있는 금액이고, [hold]는 주문 때문에
 * 잠겨 있는 금액이다. 주문 제출 시 `available -> hold`, 주문 취소 시
 * `hold -> available`, 체결 시 hold 감소와 상대 자산 available 증가가 일어난다.
 *
 * 이 객체는 불변 객체다. 각 변경 함수는 원본을 수정하지 않고 계산된 새 Balance를
 * 반환하며, 실제 DB 저장은 [BalanceStore] 구현체가 담당한다.
 *
 * @property userId 잔고 소유자
 * @property assetId 이 잔고가 표현하는 자산
 * @property available 현재 자유롭게 사용할 수 있는 최소 단위 기준 금액
 * @property hold 주문을 위해 동결된 최소 단위 기준 금액
 */
data class Balance(
    val userId: UserId,
    val assetId: AssetId,
    val available: Amount,
    val hold: Amount,
) {
    /**
     * 주문에 사용할 금액을 available에서 hold로 이동한다.
     *
     * 계산 후 `available' = available - amount`, `hold' = hold + amount`가 된다.
     * 총액 `available + hold`는 변하지 않는다.
     *
     * @param amount 새 주문을 위해 동결할 최소 단위 기준 금액
     * @return 예약 반영 후의 새 Balance
     * @throws InsufficientBalanceException available이 [amount]보다 적은 경우
     * @throws ArithmeticException Long 범위를 넘는 덧셈이나 뺄셈이 발생한 경우
     */
    fun reserve(
        amount: Amount,
    ): Balance {
        if (available < amount) {
            throw InsufficientBalanceException(
                userId = userId,
                assetId = assetId,
                available = available,
                requested = amount,
            )
        }

        val nextAvailable =
            Amount(
                Math.subtractExact(
                    available.value,
                    amount.value,
                ),
            )

        val nextHold =
            Amount(
                Math.addExact(
                    hold.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
            hold = nextHold,
        )
    }

    /**
     * 취소 또는 BUY 가격 개선으로 사용하지 않은 hold를 available로 되돌린다.
     *
     * 계산 후 `hold' = hold - amount`, `available' = available + amount`가 된다.
     * 총액 `available + hold`는 변하지 않는다.
     *
     * @param amount 동결을 해제할 최소 단위 기준 금액
     * @return 반환 반영 후의 새 Balance
     * @throws InsufficientHoldException hold가 [amount]보다 적은 경우
     * @throws ArithmeticException Long 범위를 넘는 덧셈이나 뺄셈이 발생한 경우
     */
    fun release(
        amount: Amount,
    ): Balance {
        if (hold < amount) {
            throw InsufficientHoldException(
                userId = userId,
                assetId = assetId,
                hold = hold,
                requested = amount,
            )
        }

        val nextHold =
            Amount(
                Math.subtractExact(
                    hold.value,
                    amount.value,
                ),
            )

        val nextAvailable =
            Amount(
                Math.addExact(
                    available.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
            hold = nextHold,
        )
    }

    /**
     * 체결에 실제로 사용된 금액을 hold에서 제거한다.
     *
     * [release]와 달리 available에는 더하지 않는다. 소비된 금액의 반대편 자산 지급은
     * 별도의 [credit] 호출로 처리한다.
     *
     * @param amount 체결 대금 또는 체결 수량으로 소비할 최소 단위 기준 금액
     * @return hold만 [amount]만큼 감소한 새 Balance
     * @throws InsufficientHoldException hold가 [amount]보다 적은 경우
     * @throws ArithmeticException Long 범위를 넘는 뺄셈이 발생한 경우
     */
    fun consumeHold(
        amount: Amount,
    ): Balance {
        if (hold < amount) {
            throw InsufficientHoldException(
                userId = userId,
                assetId = assetId,
                hold = hold,
                requested = amount,
            )
        }

        val nextHold =
            Amount(
                Math.subtractExact(
                    hold.value,
                    amount.value,
                ),
            )

        return copy(
            hold = nextHold,
        )
    }

    /**
     * 입금 또는 체결로 받은 자산을 available에 더한다.
     *
     * @param amount 지급할 최소 단위 기준 금액
     * @return available이 [amount]만큼 증가한 새 Balance
     * @throws ArithmeticException 합계가 Long 범위를 넘는 경우
     */
    fun credit(
        amount: Amount,
    ): Balance {
        val nextAvailable =
            Amount(
                Math.addExact(
                    available.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
        )
    }
}

/**
 * 주문 예약에 필요한 금액보다 사용 가능한 잔고가 적을 때 발생하는 예외.
 *
 * @property userId 잔고 소유자
 * @property assetId 부족한 자산
 * @property available 실제 사용 가능 금액
 * @property requested 요청한 예약 금액
 */
class InsufficientBalanceException(
    val userId: UserId,
    val assetId: AssetId,
    val available: Amount,
    val requested: Amount,
) : IllegalStateException(
    "insufficient balance: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}, " +
        "available=${available.value}, " +
        "requested=${requested.value}",
)

/**
 * 반환하거나 소비할 금액보다 현재 동결 잔고가 적을 때 발생하는 예외.
 *
 * @property userId 잔고 소유자
 * @property assetId 부족한 자산
 * @property hold 실제 동결 금액
 * @property requested 반환 또는 소비하려던 금액
 */
class InsufficientHoldException(
    val userId: UserId,
    val assetId: AssetId,
    val hold: Amount,
    val requested: Amount,
) : IllegalStateException(
    "insufficient hold: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}, " +
        "hold=${hold.value}, " +
        "requested=${requested.value}",
)

/**
 * 사용자와 자산 조합에 해당하는 잔고 row가 없을 때 발생하는 예외.
 *
 * @property userId 조회한 사용자
 * @property assetId 조회한 자산
 */
class BalanceNotFoundException(
    val userId: UserId,
    val assetId: AssetId,
) : IllegalStateException(
    "balance not found: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}",
)

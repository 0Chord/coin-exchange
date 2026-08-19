package com.exchange.core.common

/**
 * 가격.
 *
 * 부동소수점 오차를 피하기 위해 Double 대신 Long으로 다룬다.
 * 오더북에서는 가격 정렬이 필요하므로 Comparable을 구현한다.
 *
 * 현재 계산에서 [value]는 base 자산 1단위의 quote 자산 가격이다. 예를 들어
 * BTC-KRW에서 `Price(50_000_000)`은 1 BTC가 50,000,000 KRW라는 뜻이다.
 *
 * @property value 0보다 큰 정수 가격
 * @throws IllegalArgumentException [value]가 0 이하인 경우
 */
@JvmInline
value class Price(val value: Long) : Comparable<Price> {
    init {
        require(value > 0) { "price must be positive" }
    }

    override fun compareTo(other: Price): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

/**
 * 수량.
 *
 * 주문 생성 시에는 0보다 커야 하지만, 체결 후 잔량은 0이 될 수 있다.
 * 그래서 Quantity 타입 자체는 0을 허용한다.
 *
 * [value]는 base 자산의 최소 단위 개수다. baseAssetScale이 8인 BTC에서
 * `Quantity(10_000_000)`은 0.1 BTC를 의미한다.
 *
 * @property value 0 이상의 base 자산 최소 단위 개수
 * @throws IllegalArgumentException [value]가 음수인 경우
 */
@JvmInline
value class Quantity(val value: Long) : Comparable<Quantity> {
    init {
        require(value >= 0) {
            "quantity must be positive"
        }
    }

    /**
     * 두 최소 단위 수량을 더한다.
     *
     * @param other 현재 수량에 더할 수량
     * @return 두 수량의 합
     */
    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)

    /**
     * 체결 수량만큼 잔량을 줄일 때 사용한다.
     *
     * 결과가 음수가 되면 Quantity 생성 규칙에서 예외가 난다.
     *
     * @param other 현재 수량에서 뺄 수량
     * @return 두 수량의 차이
     * @throws IllegalArgumentException [other]가 현재 수량보다 큰 경우
     */
    operator fun minus(other: Quantity): Quantity = Quantity(value - other.value)

    /**
     * 주문이 전부 체결되었는지 확인할 때 사용한다.
     *
     * @return 최소 단위 수량이 0이면 `true`
     */
    fun isZero(): Boolean = value == 0L

    override fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /**
         * 잔량이 없는 상태를 명확하게 표현하기 위한 상수.
         */
        val ZERO = Quantity(0)
    }
}

/**
 * 특정 자산의 최소 단위 기준 금액.
 *
 * 실제 자산 종류는 Amount 자체가 아니라 AssetId와 함께 표현한다.
 * 잔고가 0인 상태도 필요하므로 0을 허용한다.
 * 예를 들어 KRW의 최소 단위가 1원이라면 `Amount(5_000_000)`은 500만원이고,
 * BTC의 scale이 8이면 `Amount(10_000_000)`은 0.1 BTC다.
 *
 * @property value 0 이상의 자산 최소 단위 개수
 * @throws IllegalArgumentException [value]가 음수인 경우
 */
@JvmInline
value class Amount(
    val value: Long,
) : Comparable<Amount> {
    init {
        require(value >= 0) {
            "amount must not be negative"
        }
    }

    /**
     * 금액이 하나도 남지 않았는지 확인한다.
     *
     * @return 최소 단위 금액이 0이면 `true`
     */
    fun isZero(): Boolean = value == 0L

    override fun compareTo(other: Amount): Int =
        value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        /** 자산 금액이 없는 상태를 표현하는 공통 상수. */
        val ZERO = Amount(0)
    }
}

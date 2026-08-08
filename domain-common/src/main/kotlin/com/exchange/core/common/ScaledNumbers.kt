package com.exchange.core.common

/**
 * 가격.
 *
 * 부동소수점 오차를 피하기 위해 Double 대신 Long으로 다룬다.
 * 오더북에서는 가격 정렬이 필요하므로 Comparable을 구현한다.
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
 */
@JvmInline
value class Quantity(val value: Long) : Comparable<Quantity> {
    init {
        require(value >= 0) {
            "quantity must be positive"
        }
    }

    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)

    /**
     * 체결 수량만큼 잔량을 줄일 때 사용한다.
     *
     * 결과가 음수가 되면 Quantity 생성 규칙에서 예외가 난다.
     */
    operator fun minus(other: Quantity): Quantity = Quantity(value - other.value)

    /**
     * 주문이 전부 체결되었는지 확인할 때 사용한다.
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

    fun isZero(): Boolean = value == 0L

    override fun compareTo(other: Amount): Int =
        value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        val ZERO = Amount(0)
    }
}

package com.exchange.core.common

@JvmInline
value class Price(val value: Long) : Comparable<Price> {
    init {
        require(value > 0) { "price must be positive" }
    }

    override fun compareTo(other: Price): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()
}

@JvmInline
value class Quantity(val value: Long) : Comparable<Quantity> {
    init {
        require(value >= 0) {
            "quantity must be positive"
        }
    }

    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)

    operator fun minus(other: Quantity): Quantity = Quantity(value - other.value)

    fun isZero(): Boolean = value == 0L

    override fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    override fun toString(): String = value.toString()

    companion object {
        val ZERO = Quantity(0)
    }
}
package com.exchange.core.common

@JvmInline
value class MarketId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "marketId must not be blank"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class OrderId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "orderId must not be blank"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "userId must not be blank"
        }
    }

    override fun toString(): String = value
}
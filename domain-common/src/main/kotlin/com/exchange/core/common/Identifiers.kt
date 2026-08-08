package com.exchange.core.common

/**
 * 거래되는 마켓 식별자.
 *
 * 예: BTC-USDT, BTC-WON.
 * 문자열을 그대로 쓰지 않고 MarketId로 감싸서 다른 id와 섞이는 실수를 막는다.
 */
@JvmInline
value class MarketId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "marketId must not be blank"
        }
    }

    override fun toString(): String = value
}

/**
 * 주문 식별자.
 *
 * 주문 등록, 체결, 취소에서 같은 주문을 추적할 때 사용한다.
 */
@JvmInline
value class OrderId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "orderId must not be blank"
        }
    }

    override fun toString(): String = value
}

/**
 * 사용자 식별자.
 *
 * maker, taker, 주문 소유자를 구분할 때 사용한다.
 */
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "userId must not be blank"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class AssetId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            "assetId must not be blank"
        }
    }

    override fun toString(): String = value
}

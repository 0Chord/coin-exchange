package com.exchange.core.common

/**
 * 거래되는 마켓 식별자.
 *
 * 예: BTC-USDT, BTC-WON.
 * 문자열을 그대로 쓰지 않고 MarketId로 감싸서 다른 id와 섞이는 실수를 막는다.
 *
 * @property value 외부 API와 저장소에서 사용하는 비어 있지 않은 마켓 문자열
 * @throws IllegalArgumentException [value]가 빈 문자열이거나 공백뿐인 경우
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
 *
 * @property value 마켓 안에서 주문을 식별하는 비어 있지 않은 문자열
 * @throws IllegalArgumentException [value]가 빈 문자열이거나 공백뿐인 경우
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
 *
 * @property value 사용자를 식별하는 비어 있지 않은 문자열
 * @throws IllegalArgumentException [value]가 빈 문자열이거나 공백뿐인 경우
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

/**
 * 잔고와 마켓을 구성하는 자산 식별자.
 *
 * 예를 들어 `BTC-KRW` 마켓의 base 자산은 `BTC`, quote 자산은 `KRW`다.
 * [Amount]가 어느 자산의 금액인지 판단하려면 항상 AssetId와 함께 보아야 한다.
 *
 * @property value 자산을 식별하는 비어 있지 않은 문자열
 * @throws IllegalArgumentException [value]가 빈 문자열이거나 공백뿐인 경우
 */
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

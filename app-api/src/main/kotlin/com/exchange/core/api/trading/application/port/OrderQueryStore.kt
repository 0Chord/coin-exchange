package com.exchange.core.api.trading.application.port

import com.exchange.core.api.trading.application.LatestSettledTradeSnapshot
import com.exchange.core.api.trading.application.OrderFillCursor
import com.exchange.core.api.trading.application.OrderFillQuerySnapshot
import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQuerySnapshot

/** 저장 입력은 내부 연결용이다. 제품 HTTP로 공개하지 않는다. */
interface OrderQueryStore {
    val namespace: OrderQueryNamespace

    fun initialize()

    fun observe(observedRevision: Long)

    fun transitionLifecycle(lifecycle: OrderQueryLifecycle)

    /** 같은 ID와 정규화 내용의 재전송은 처음 반환한 revision을 그대로 반환한다. */
    fun apply(batch: OrderQueryBatch): Long

    /** 주문과 namespace 진행 정보를 반드시 같은 DB 관찰 시점에서 읽는다. */
    fun read(
        marketId: String,
        orderId: String,
    ): OrderQuerySnapshot

    /** 호출자가 연 REPEATABLE READ 트랜잭션에서 주문·체결·정산 진행 위치를 읽는다. */
    fun readFills(
        marketId: String,
        orderId: String,
        minAdmissionSequence: Long,
        limit: Int,
        after: OrderFillCursor?,
    ): OrderFillQuerySnapshot

    /** 마켓의 정산 반영 위치와 마지막 체결을 같은 DB 시점에서 읽는다. */
    fun readLatestSettledTrade(marketId: String): LatestSettledTradeSnapshot?
}

class OrderQueryWriteConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OrderQueryUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OrderQueryInconsistentException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

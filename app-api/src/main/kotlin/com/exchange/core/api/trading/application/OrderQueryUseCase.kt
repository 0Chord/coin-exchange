package com.exchange.core.api.trading.application

import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

enum class OrderQueryCode {
    ORDER_FOUND,
    QUERY_CATCHING_UP,
    ORDER_NOT_FOUND_AS_OF,
    QUERY_NOT_READY,
    QUERY_UNAVAILABLE,
    QUERY_INCONSISTENT,
}

data class OrderQueryResult(
    val code: OrderQueryCode,
    val progress: OrderQueryProgress? = null,
    val record: OrderQueryRecord? = null,
) {
    val freshness: OrderQueryFreshness? get() = progress?.freshness
}

/** 부재 확정보다 읽기 오류, 준비 상태, 최소 접수 prefix 검사를 먼저 한다. */
open class OrderQueryUseCase(
    private val store: OrderQueryStore,
) {
    @Transactional(readOnly = true)
    open fun get(
        marketId: String,
        orderId: String,
        minAdmissionSequence: Long = 0,
    ): OrderQueryResult {
        validateInput(marketId, orderId, minAdmissionSequence)
        val snapshot =
            try {
                store.read(marketId, orderId).also { validateSnapshot(it, marketId, orderId) }
            } catch (_: OrderQueryUnavailableException) {
                return OrderQueryResult(OrderQueryCode.QUERY_UNAVAILABLE)
            } catch (_: OrderQueryInconsistentException) {
                return OrderQueryResult(OrderQueryCode.QUERY_INCONSISTENT)
            } catch (_: IllegalArgumentException) {
                // DB 행 변환의 검증 실패가 요청 입력 오류 400으로 흘러가지 않는다.
                return OrderQueryResult(OrderQueryCode.QUERY_INCONSISTENT)
            }
        return resolveOrder(snapshot, minAdmissionSequence)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    open fun getFills(
        marketId: String,
        orderId: String,
        minAdmissionSequence: Long,
        limit: Int,
        after: OrderFillCursor?,
    ): OrderFillQueryResult {
        validateInput(marketId, orderId, minAdmissionSequence)
        require(limit in 1..100) { "invalid fill page size" }
        val snapshot =
            try {
                val stored = store.readFills(marketId, orderId, minAdmissionSequence, limit, after)
                validateSnapshot(stored.order, marketId, orderId)
                stored
            } catch (_: OrderQueryUnavailableException) {
                return OrderFillQueryResult(OrderQueryCode.QUERY_UNAVAILABLE)
            } catch (_: OrderQueryInconsistentException) {
                return OrderFillQueryResult(OrderQueryCode.QUERY_INCONSISTENT)
            } catch (_: IllegalArgumentException) {
                return OrderFillQueryResult(OrderQueryCode.QUERY_INCONSISTENT)
            }
        val order = resolveOrder(snapshot.order, minAdmissionSequence)
        if (!snapshot.settlementEnabled) return OrderFillQueryResult(OrderQueryCode.QUERY_NOT_READY, order.progress)
        if (order.code != OrderQueryCode.ORDER_FOUND) return OrderFillQueryResult(order.code, order.progress)
        return OrderFillQueryResult(order.code, order.progress, snapshot.fills, snapshot.nextCursor)
    }

    internal open fun validateInput(
        marketId: String,
        orderId: String,
        minAdmissionSequence: Long,
    ) {
        requireOrderQueryText(marketId)
        require(marketId in store.namespace.markets) { "unsupported query market" }
        require(ORDER_ID.matches(orderId)) { "invalid order ID" }
        require(minAdmissionSequence >= 0) { "invalid minimum admission sequence" }
    }

    private fun resolveOrder(
        snapshot: OrderQuerySnapshot,
        minAdmissionSequence: Long,
    ): OrderQueryResult {
        val progress = snapshot.progress ?: return OrderQueryResult(OrderQueryCode.QUERY_NOT_READY)
        if (progress.lifecycle != OrderQueryLifecycle.READY) {
            return OrderQueryResult(OrderQueryCode.QUERY_NOT_READY, progress)
        }
        if (progress.coveredAdmissionSequence < minAdmissionSequence) {
            return OrderQueryResult(OrderQueryCode.QUERY_CATCHING_UP, progress)
        }
        snapshot.record?.let { return OrderQueryResult(OrderQueryCode.ORDER_FOUND, progress, it) }
        return OrderQueryResult(
            if (progress.freshness == OrderQueryFreshness.LAGGING) {
                OrderQueryCode.QUERY_CATCHING_UP
            } else {
                OrderQueryCode.ORDER_NOT_FOUND_AS_OF
            },
            progress,
        )
    }

    private fun validateSnapshot(
        snapshot: OrderQuerySnapshot,
        marketId: String,
        orderId: String,
    ) {
        snapshot.record?.let {
            val progress = requireNotNull(snapshot.progress) { "order exists without namespace progress" }
            store.namespace.requireRecord(it)
            require(it.marketId == marketId && it.orderId == orderId) { "snapshot contains a different order" }
            require(it.admissionSequence <= progress.coveredAdmissionSequence) { "order is ahead of the covered prefix" }
        }
    }

    private companion object {
        val ORDER_ID = Regex("O2[A-Za-z0-9_-]{32}")
    }
}

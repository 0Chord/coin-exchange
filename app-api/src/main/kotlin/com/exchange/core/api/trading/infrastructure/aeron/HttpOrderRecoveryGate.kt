package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.OrderQueryRecoveryStatus
import com.exchange.core.api.trading.application.OrderQueryRecoveryVerdict

/** 수집 작업의 판정 변경과 주문 Gateway의 비차단 전송에 순서를 부여한다. */
internal class HttpOrderRecoveryGate {
    class State(
        val verdict: OrderQueryRecoveryVerdict,
        private val fundsLeadershipTermId: Long?,
    ) {
        fun isReadyFor(leadershipTermId: Long?): Boolean =
            verdict.status == OrderQueryRecoveryStatus.READY &&
                leadershipTermId != null && leadershipTermId == fundsLeadershipTermId

        val refusalReason: String
            get() =
                when (verdict.status) {
                    OrderQueryRecoveryStatus.BLOCKED -> "RECOVERY_BLOCKED"
                    else -> "RECOVERY_IN_PROGRESS"
                }
    }

    @Volatile
    var current = State(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING), null)
        private set

    private var closed = false

    fun publish(
        verdict: OrderQueryRecoveryVerdict,
        fundsLeadershipTermId: Long? = null,
    ) {
        synchronized(this) {
            if (!closed) current = State(verdict, fundsLeadershipTermId)
        }
    }

    fun tryOffer(
        approved: State,
        transport: BoundedGatewayTransport,
        offer: () -> Long?,
    ): Long? =
        synchronized(this) {
            // 같은 임기에서 다시 대사했어도 이전 허용 구간에 쌓인 요청은 돌려보낸다.
            if (current !== approved || !approved.isReadyFor(transport.leadershipTermId)) {
                return@synchronized null
            }
            offer()
        }

    fun close() {
        synchronized(this) {
            closed = true
            if (current.verdict.status != OrderQueryRecoveryStatus.BLOCKED) {
                val unavailable = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.BLOCKED, OrderQueryRecoveryFailure.SOURCE_UNAVAILABLE)
                current = State(unavailable, null)
            }
        }
    }
}

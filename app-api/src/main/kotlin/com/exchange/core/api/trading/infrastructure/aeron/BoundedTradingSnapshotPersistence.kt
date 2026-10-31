package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingInboxSnapshot
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingRecoveryState
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.Cluster

/** 생명주기에서만 I/O를 수행한다. bounded 거래 처리 경로에서는 로컬 이력 인덱스를 열지 않는다. */
internal class BoundedTradingSnapshotPersistence(
    private val expected: TradingClusterManifest,
) : TradingSnapshotPersistence {
    private val codec = BoundedTradingStateCodec()

    override fun save(
        state: TradingState,
        publication: ExclusivePublication,
        cluster: Cluster,
        malformedMessages: Long,
    ) {
        check(state.isBounded && !state.isBlocked) { "cannot snapshot a failed or legacy owner" }
        val candidate =
            if (state.lifecycle == TradingLifecycle.UNINITIALIZED) {
                // 스냅샷을 위해 실제 소유자를 초기화하면 로그의 Initialize보다 먼저 준비 상태가 된다.
                uninitializedRecoveryState()
            } else {
                state.recoveryState()
            }
        val out = AeronSnapshotOutput(publication, cluster)
        codec.write(candidate, cluster.logPosition(), out)
        out.flush()
    }

    override fun restore(
        state: TradingState,
        image: Image,
        cluster: Cluster,
    ): Long {
        val candidate = codec.read(AeronSnapshotInput(image, cluster), expected, cluster.logPosition())
        if (candidate.lifecycle == TradingLifecycle.UNINITIALIZED) {
            check(state.isBounded && !state.isBlocked && state.lifecycle == TradingLifecycle.UNINITIALIZED) {
                "bounded restore requires an empty owner"
            }
            // 초기화 전에는 설정 외 상태가 없어야 한다. 내용을 버리며 복원하면 손상된 금융 상태를 숨기게 된다.
            require(
                candidate.balances.isEmpty() && candidate.feeRevenueByAsset.isEmpty() && candidate.usedBalances.isEmpty() &&
                    candidate.preparations.isEmpty() && candidate.accounts.isEmpty() && candidate.liveOrders.isEmpty() &&
                    candidate.markets.isEmpty() && candidate.matching.markets.isEmpty() && candidate.inbox.channels.isEmpty() &&
                    candidate.lastAdmissionSequence == 0L && candidate.lastPreparationSequence == 0L &&
                    candidate.preparationCanonicalBytes == 0L && candidate.incident == null,
            ) { "uninitialized snapshot must be empty" }
        } else {
            state.restoreRecoveryState(candidate)
        }
        return 0
    }

    private fun uninitializedRecoveryState() =
        TradingRecoveryState(
            manifest = expected,
            lifecycle = TradingLifecycle.UNINITIALIZED,
            balances = emptyList(),
            feeRevenueByAsset = emptyMap(),
            usedBalances = emptyList(),
            preparations = emptyList(),
            accounts = emptyList(),
            liveOrders = emptyList(),
            markets = emptyList(),
            matching = MatchingCheckpoint(emptyList()),
            inbox = TradingInboxSnapshot(emptyList()),
            lastAdmissionSequence = 0,
            lastPreparationSequence = 0,
            preparationCanonicalBytes = 0,
        )
}

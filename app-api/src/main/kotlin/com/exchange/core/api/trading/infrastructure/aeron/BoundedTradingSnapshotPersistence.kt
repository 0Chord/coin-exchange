package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.TradingClusterManifest
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
        val out = AeronSnapshotOutput(publication, cluster)
        codec.write(state.recoveryState(), cluster.logPosition(), out)
        out.flush()
    }

    override fun restore(
        state: TradingState,
        image: Image,
        cluster: Cluster,
    ): Long {
        val candidate = codec.read(AeronSnapshotInput(image, cluster), expected, cluster.logPosition())
        state.restoreRecoveryState(candidate)
        return 0
    }
}

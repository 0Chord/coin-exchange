package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.Cluster

/** Lifecycle-only I/O. No local history index is opened on the bounded transaction path. */
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

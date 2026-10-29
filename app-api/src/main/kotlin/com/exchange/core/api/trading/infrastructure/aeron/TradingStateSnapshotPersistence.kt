package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.RocksDbTradingHistoryStore
import com.exchange.core.api.trading.infrastructure.history.TradingSnapshotCodec
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.Cluster

/** Snapshot I/O is confined to the lifecycle callbacks, outside normal order processing. */
internal class TradingStateSnapshotPersistence(
    history: RocksDbTradingHistoryStore,
    identitySha256: String,
) {
    private val codec = TradingSnapshotCodec(history, identitySha256)

    fun save(
        state: TradingState,
        publication: ExclusivePublication,
        cluster: Cluster,
        malformedMessages: Long,
    ) {
        val output = AeronSnapshotOutput(publication, cluster)
        codec.write(state, malformedMessages, output)
        output.flush()
    }

    fun restore(
        state: TradingState,
        image: Image,
        cluster: Cluster,
    ): Long = codec.read(state, AeronSnapshotInput(image, cluster))
}

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.RocksDbTradingHistoryStore
import com.exchange.core.api.trading.infrastructure.history.TradingSnapshotCodec
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.Cluster

/** Snapshot I/O는 일반 주문 처리 밖의 생명주기 callback에서만 수행한다. */
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

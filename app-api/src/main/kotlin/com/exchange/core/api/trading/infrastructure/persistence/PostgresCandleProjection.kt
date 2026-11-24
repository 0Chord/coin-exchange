package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.Candle
import com.exchange.core.api.trading.application.CandleInterval
import com.exchange.core.api.trading.application.CandleQueryResult
import com.exchange.core.api.trading.application.OrderQueryCode
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.port.CandleQueryReader
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset.UTC

/** 이미 커밋된 정산 결과를 봉과 진행 위치로 함께 반영한다. */
internal open class PostgresCandleProjection(
    private val query: PostgresOrderQueryStore,
    private val jdbc: NamedParameterJdbcTemplate,
) : CandleQueryReader {
    override fun read(
        marketId: String,
        interval: CandleInterval,
        from: Instant,
        until: Instant,
        count: Int,
    ): CandleQueryResult =
        try {
            jdbc
                .query(
                    """
                    select $SOURCE_COLUMNS, b.bucket_start, b.open, b.high, b.low, b.close, b.volume
                    $SOURCE_TABLES
                    left join lateral (
                        select bucket_start, open, high, low, close, volume
                        from order_query.candle
                        where namespace = s.namespace and market_id = s.market_id and interval_code = :interval
                            and bucket_start >= :from and bucket_start < :until
                        order by bucket_start
                        limit :count
                    ) b on p.lifecycle = 'READY' and cp.projected_through between cp.bootstrap_through and s.settlement_through
                    where p.namespace = :namespace
                    order by b.bucket_start
                    """.trimIndent(),
                    market(marketId) +
                        mapOf("interval" to interval.code, "from" to from.atOffset(UTC), "until" to until.atOffset(UTC), "count" to count),
                    ResultSetExtractor { rows -> readCandles(rows, marketId, interval) },
                )
        } catch (error: DataAccessException) {
            throw OrderQueryUnavailableException("봉 조회 DB에 연결할 수 없습니다", error)
        }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    open fun applyNextPage(marketId: String): Boolean {
        require(marketId in query.namespace.markets) { "지원하지 않는 차트 마켓입니다" }
        val market = market(marketId)
        val observed = sourceState(market)
        val observedThrough = observed.sourceThrough ?: return false
        if (observed.projectedThrough == observedThrough) return false

        if (observed.projectedThrough == null) initializeProgress(market, observedThrough)
        val projectedThrough =
            checkNotNull(
                jdbc.queryForObject(
                    """
                    select projected_through from order_query.candle_progress
                    where namespace = :namespace and market_id = :marketId
                    for update
                    """.trimIndent(),
                    market,
                    Long::class.java,
                ),
            )
        val sourceThrough = sourceState(market).sourceThrough ?: return false
        if (projectedThrough >= sourceThrough) return false

        val bounds = market + mapOf("projectedThrough" to projectedThrough, "sourceThrough" to sourceThrough, "pageSize" to PAGE_SIZE)
        val pageThrough = completeResultThrough(bounds)
        val minutes = readMinuteDeltas(bounds + ("pageThrough" to pageThrough))
        writeDeltas(market, minutes)
        check(
            jdbc.update(
                """
                update order_query.candle_progress set projected_through = :pageThrough
                where namespace = :namespace and market_id = :marketId
                """.trimIndent(),
                market + ("pageThrough" to pageThrough),
            ) == 1,
        )
        return true
    }

    private fun market(marketId: String): Map<String, String> = mapOf("namespace" to query.namespaceKey, "marketId" to marketId)

    private fun sourceState(market: Map<String, String>): SourceState =
        jdbc
            .query(
                "select $SOURCE_COLUMNS $SOURCE_TABLES where p.namespace = :namespace",
                market,
            ) { row, _ -> sourceState(row) }
            .singleOrNull() ?: SourceState(OrderQueryCode.QUERY_NOT_READY)

    private fun sourceState(row: ResultSet): SourceState {
        val lifecycle = query.sourceLifecycle(query.progress(row).lifecycle)
        if (lifecycle == OrderQueryLifecycle.BLOCKED) return SourceState(OrderQueryCode.QUERY_INCONSISTENT)
        if (lifecycle != OrderQueryLifecycle.READY || !row.getBoolean("settlement_enabled")) {
            return SourceState(OrderQueryCode.QUERY_NOT_READY)
        }
        val sourceThrough = row.getObject("settlement_through", Long::class.javaObjectType)
        if (sourceThrough == null) {
            return SourceState(OrderQueryCode.QUERY_INCONSISTENT)
        }
        val projectedThrough = row.getObject("projected_through", Long::class.javaObjectType)
        val code =
            when {
                projectedThrough == null -> OrderQueryCode.QUERY_CATCHING_UP
                projectedThrough > sourceThrough -> return SourceState(OrderQueryCode.QUERY_INCONSISTENT)
                projectedThrough < row.getLong("bootstrap_through") -> OrderQueryCode.QUERY_CATCHING_UP
                else -> OrderQueryCode.CANDLES_FOUND
            }
        return SourceState(code, sourceThrough = sourceThrough, projectedThrough = projectedThrough)
    }

    private fun readCandles(
        rows: ResultSet,
        marketId: String,
        interval: CandleInterval,
    ): CandleQueryResult {
        if (!rows.next()) return CandleQueryResult(OrderQueryCode.QUERY_NOT_READY)
        val state = sourceState(rows)
        if (state.sourceThrough == null) return CandleQueryResult(state.code)
        val candles =
            if (state.code == OrderQueryCode.CANDLES_FOUND) {
                val results = mutableListOf<Candle>()
                do {
                    val bucketStart = rows.getTimestamp("bucket_start") ?: break
                    results.add(
                        Candle(
                            startedAt = bucketStart.toInstant(),
                            open = rows.getLong("open"),
                            high = rows.getLong("high"),
                            low = rows.getLong("low"),
                            close = rows.getLong("close"),
                            volume = rows.getBigDecimal("volume").toBigIntegerExact().toString(),
                        ),
                    )
                } while (rows.next())
                results
            } else {
                null
            }
        return CandleQueryResult(
            code = state.code,
            marketId = marketId,
            interval = interval.code,
            asOfSettlementResultSequence = state.projectedThrough ?: 0L,
            sourceSettlementResultSequence = state.sourceThrough,
            candles = candles,
        )
    }

    private fun initializeProgress(
        market: Map<String, String>,
        bootstrapThrough: Long,
    ) {
        jdbc.update(
            """
            insert into order_query.candle_progress(namespace, market_id, projected_through, bootstrap_through)
            values (:namespace, :marketId, 0, :bootstrapThrough)
            on conflict (namespace, market_id) do nothing
            """.trimIndent(),
            market + ("bootstrapThrough" to bootstrapThrough),
        )
    }

    private fun completeResultThrough(bounds: Map<String, Any>): Long =
        checkNotNull(
            jdbc.queryForObject(
                """
                select case when count(*) = :pageSize then max(result_sequence) else :sourceThrough end
                from (
                    select result_sequence from order_query.settlement_transaction
                    where namespace = :namespace and market_id = :marketId
                        and result_sequence > :projectedThrough and result_sequence <= :sourceThrough
                    order by result_sequence
                    limit :pageSize
                ) page
                """.trimIndent(),
                bounds,
                Long::class.java,
            ),
        )

    private fun readMinuteDeltas(bounds: Map<String, Any>): Map<Instant, CandleDelta> {
        val minutes = linkedMapOf<Instant, CandleDelta>()
        jdbc.query(
            """
            select occurred_at, engine_sequence, price, quantity, isfinite(occurred_at) as finite_time
            from order_query.settlement_transaction
            where namespace = :namespace and market_id = :marketId
                and result_sequence > :projectedThrough and result_sequence <= :pageThrough
            """.trimIndent(),
            bounds,
            RowCallbackHandler { row ->
                if (!row.getBoolean("finite_time")) throw OrderQueryInconsistentException("정산 적용 시각이 올바르지 않습니다")
                val price = row.getLong("price")
                val edge =
                    CandleEdge(
                        occurredAt = row.getTimestamp("occurred_at").toInstant(),
                        engineSequence = row.getLong("engine_sequence"),
                        price = price,
                    )
                val quantity = BigInteger.valueOf(row.getLong("quantity"))
                val minute = CandleInterval.ONE_MINUTE.start(edge.occurredAt)
                val previous = minutes[minute]
                if (previous == null) {
                    minutes[minute] = CandleDelta(edge, edge, price, price, quantity)
                } else {
                    previous.add(edge, edge, price, price, quantity)
                }
            },
        )
        return minutes
    }

    private fun writeDeltas(
        market: Map<String, String>,
        minutes: Map<Instant, CandleDelta>,
    ) {
        if (minutes.isEmpty()) return

        val updates = ArrayList<Map<String, Any>>()
        for (interval in CandleInterval.entries) {
            val buckets = if (interval == CandleInterval.ONE_MINUTE) minutes else groupMinutes(interval, minutes)
            for ((start, delta) in buckets) {
                updates.add(
                    market +
                        mapOf(
                            "interval" to interval.code,
                            "startedAt" to start.atOffset(UTC),
                            "open" to delta.first.price,
                            "high" to delta.high,
                            "low" to delta.low,
                            "close" to delta.last.price,
                            "volume" to delta.volume.toBigDecimal(),
                            "firstAt" to delta.first.occurredAt.atOffset(UTC),
                            "firstSequence" to delta.first.engineSequence,
                            "lastAt" to delta.last.occurredAt.atOffset(UTC),
                            "lastSequence" to delta.last.engineSequence,
                        ),
                )
            }
        }
        jdbc.batchUpdate(UPSERT_CANDLES, updates.toTypedArray())
    }

    private fun groupMinutes(
        interval: CandleInterval,
        minutes: Map<Instant, CandleDelta>,
    ): Map<Instant, CandleDelta> {
        val grouped = linkedMapOf<Instant, CandleDelta>()
        for ((minute, delta) in minutes) {
            val start = interval.start(minute)
            val previous = grouped[start]
            if (previous == null) {
                grouped[start] = delta.copy()
            } else {
                previous.add(delta.first, delta.last, delta.high, delta.low, delta.volume)
            }
        }
        return grouped
    }

    private data class SourceState(
        val code: OrderQueryCode,
        val sourceThrough: Long? = null,
        val projectedThrough: Long? = null,
    )

    private data class CandleEdge(
        val occurredAt: Instant,
        val engineSequence: Long,
        val price: Long,
    ) : Comparable<CandleEdge> {
        override fun compareTo(other: CandleEdge): Int {
            val timeOrder = occurredAt.compareTo(other.occurredAt)
            return if (timeOrder != 0) timeOrder else engineSequence.compareTo(other.engineSequence)
        }
    }

    private data class CandleDelta(
        var first: CandleEdge,
        var last: CandleEdge,
        var high: Long,
        var low: Long,
        var volume: BigInteger,
    ) {
        fun add(
            earliest: CandleEdge,
            latest: CandleEdge,
            maxPrice: Long,
            minPrice: Long,
            quantity: BigInteger,
        ) {
            if (earliest < first) first = earliest
            if (latest > last) last = latest
            high = maxOf(high, maxPrice)
            low = minOf(low, minPrice)
            volume += quantity
        }
    }

    private companion object {
        const val PAGE_SIZE = 512
        const val SOURCE_COLUMNS = """
            p.*, c.namespace is not null as settlement_enabled, s.settlement_through, cp.projected_through, cp.bootstrap_through
        """
        const val SOURCE_TABLES = """
            from order_query.query_progress p
            left join order_query.feed_consumer c
                on c.namespace = p.namespace and c.configuration_payload -> 'settlementEnabled' = 'true'::jsonb
            left join order_query.feed_stream s on s.namespace = c.namespace and s.market_id = :marketId
            left join order_query.candle_progress cp on cp.namespace = s.namespace and cp.market_id = s.market_id
        """
        const val FIRST_WINS =
            "(excluded.first_occurred_at, excluded.first_engine_sequence) < (stored.first_occurred_at, stored.first_engine_sequence)"
        const val LAST_WINS =
            "(excluded.last_occurred_at, excluded.last_engine_sequence) > (stored.last_occurred_at, stored.last_engine_sequence)"
        val UPSERT_CANDLES =
            """
            insert into order_query.candle as stored
                (namespace, market_id, interval_code, bucket_start, open, high, low, close, volume,
                 first_occurred_at, first_engine_sequence, last_occurred_at, last_engine_sequence)
            values (:namespace, :marketId, :interval, :startedAt, :open, :high, :low, :close, :volume,
                :firstAt, :firstSequence, :lastAt, :lastSequence)
            on conflict (namespace, market_id, interval_code, bucket_start) do update set
                open = case when $FIRST_WINS then excluded.open else stored.open end,
                close = case when $LAST_WINS then excluded.close else stored.close end,
                high = greatest(stored.high, excluded.high),
                low = least(stored.low, excluded.low),
                volume = stored.volume + excluded.volume,
                first_occurred_at = case when $FIRST_WINS then excluded.first_occurred_at else stored.first_occurred_at end,
                first_engine_sequence = case when $FIRST_WINS then excluded.first_engine_sequence else stored.first_engine_sequence end,
                last_occurred_at = case when $LAST_WINS then excluded.last_occurred_at else stored.last_occurred_at end,
                last_engine_sequence = case when $LAST_WINS then excluded.last_engine_sequence else stored.last_engine_sequence end
            """.trimIndent()
    }
}

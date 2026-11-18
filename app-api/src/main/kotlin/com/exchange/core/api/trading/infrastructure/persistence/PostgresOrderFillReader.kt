package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.OrderFill
import com.exchange.core.api.trading.application.OrderFillCursor
import com.exchange.core.api.trading.application.OrderFillQuerySnapshot
import com.exchange.core.api.trading.application.OrderFillRole
import com.exchange.core.api.trading.application.OrderFillSettlementStatus
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.OrderQuerySnapshot
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.trading.TradingIdentifiers
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 같은 DB 읽기 트랜잭션에서 이미 커밋된 매칭 원문과 정산 행을 연결한다. */
internal class PostgresOrderFillReader(
    private val jdbc: NamedParameterJdbcTemplate,
    private val namespace: String,
) {
    private val codec = OrderQuerySourceCodec()

    fun read(
        order: OrderQuerySnapshot,
        marketId: String,
        minAdmissionSequence: Long,
        limit: Int,
        after: OrderFillCursor?,
    ): OrderFillQuerySnapshot {
        val progress = order.progress
        if (progress?.lifecycle != OrderQueryLifecycle.READY) {
            return OrderFillQuerySnapshot(order, settlementEnabled = false)
        }

        val boundary = boundary(marketId) ?: return OrderFillQuerySnapshot(order, settlementEnabled = false)
        val record = order.record ?: return OrderFillQuerySnapshot(order, settlementEnabled = true)
        if (progress.coveredAdmissionSequence < minAdmissionSequence) {
            return OrderFillQuerySnapshot(order, settlementEnabled = true)
        }
        val lastResult = record.lastAffectedResultSequence ?: 0L
        require(lastResult <= boundary.matchingThrough) { "order is ahead of the matching cursor" }
        val trades = collectTrades(record, lastResult, boundary.marketOrdinal, limit, after)
        val page = trades.take(limit)
        val pageQuantity = page.fold(0L) { sum, fill -> Math.addExact(sum, fill.trade.quantity.value) }
        require(pageQuantity <= record.filledQuantity) { "stored fills exceed the order quantity" }
        if (after == null && trades.size <= limit) {
            require(pageQuantity == record.filledQuantity) { "stored fills do not account for the order quantity" }
        }
        val settlements = readSettlements(record.marketId, page)
        val fills = page.map { fill -> toOrderFill(fill, boundary, settlements[fill.trade.engineSequence]) }
        val nextCursor = if (trades.size > limit) page.last().cursor else null
        return OrderFillQuerySnapshot(order, settlementEnabled = true, fills = fills, nextCursor = nextCursor)
    }

    private fun boundary(marketId: String): FillBoundary? =
        jdbc
            .query(
                """
                select s.matching_through, s.settlement_through,
                    c.configuration_payload -> 'contexts' -> cast(:market as text) ->> 'ordinal' as market_ordinal
                from order_query.feed_consumer c
                left join order_query.feed_stream s on s.namespace = c.namespace and s.market_id = :market
                where c.namespace = :namespace and c.configuration_payload -> 'settlementEnabled' = 'true'::jsonb
                """.trimIndent(),
                mapOf("namespace" to namespace, "market" to marketId),
            ) { row, _ ->
                val matchingThrough = requireNotNull(row.getObject("matching_through", Long::class.javaObjectType))
                val settlementThrough = requireNotNull(row.getObject("settlement_through", Long::class.javaObjectType))
                val marketOrdinal = requireNotNull(row.getString("market_ordinal")?.toIntOrNull())
                require(marketOrdinal >= 0)
                FillBoundary(matchingThrough, settlementThrough, marketOrdinal)
            }.singleOrNull()

    private fun collectTrades(
        order: OrderQueryRecord,
        lastResult: Long,
        marketOrdinal: Int,
        limit: Int,
        after: OrderFillCursor?,
    ): List<StoredTrade> {
        val trades = mutableListOf<StoredTrade>()
        val orderId = order.orderId
        var nextSequence = after?.resultSequence ?: 1L
        while (nextSequence <= lastResult) {
            val batchLimit = minOf(CANDIDATE_BATCH_SIZE, limit + 1 - trades.size)
            val receipts = readCandidates(order, nextSequence, lastResult, batchLimit)
            for (receipt in receipts) {
                val envelope = codec.decodeReply(receipt.payload)
                val result = requireNotNull(envelope.result)
                require(envelope.correlationId == 0L && result.stream == order.resultStream && result.resultSequence == receipt.sequence)
                require(result.input.context.ordinal == marketOrdinal) { "stored matching market ordinal differs" }
                for (event in result.events) {
                    if (event !is TradeExecuted) continue
                    val role =
                        when (orderId) {
                            event.makerOrderId.value -> OrderFillRole.MAKER
                            event.takerOrderId.value -> OrderFillRole.TAKER
                            else -> continue
                        }
                    if (after != null && receipt.sequence == after.resultSequence && event.engineSequence <= after.engineSequence) continue
                    val previous = trades.lastOrNull()
                    require(previous == null || event.engineSequence > previous.trade.engineSequence)
                    require(event.makerOrderId != event.takerOrderId)
                    when (role) {
                        OrderFillRole.MAKER -> require(event.makerUserId.value == order.userId && event.side != order.side)
                        OrderFillRole.TAKER -> require(event.takerUserId.value == order.userId && event.side == order.side)
                    }
                    val cursor = OrderFillCursor(resultSequence = receipt.sequence, engineSequence = event.engineSequence)
                    val tradeId = TradingIdentifiers.settlement(order.fundsLineage, marketOrdinal, event.engineSequence)
                    trades.add(StoredTrade(event, role, cursor, tradeId))
                    if (trades.size > limit) return trades
                }
            }
            if (receipts.size < batchLimit || receipts.last().sequence == lastResult) break
            nextSequence = Math.addExact(receipts.last().sequence, 1L)
        }
        return trades
    }

    private fun readCandidates(
        order: OrderQueryRecord,
        from: Long,
        through: Long,
        limit: Int,
    ): List<MatchingReceipt> =
        jdbc.query(
            """
            select r.source_sequence, r.canonical_payload
            from order_query.applied_batch b
            join order_query.source_receipt r on r.namespace = b.namespace and r.first_revision = b.first_revision
            where b.namespace = :namespace and r.source_kind = 'MATCHING' and r.classification = 'PROJECTED'
                and r.source_key = :market and r.source_sequence between :fromSequence and :throughSequence
                and (b.batch_payload -> 'records') @> jsonb_build_array(
                    jsonb_build_object('marketId', cast(:market as text), 'admissionSequence', cast(:admission as bigint))
                )
            order by r.source_sequence
            limit :candidateLimit
            """.trimIndent(),
            mapOf(
                "namespace" to namespace,
                "market" to order.marketId,
                "admission" to order.admissionSequence,
                "fromSequence" to from,
                "throughSequence" to through,
                "candidateLimit" to limit,
            ),
        ) { row, _ -> MatchingReceipt(row.getLong("source_sequence"), row.getBytes("canonical_payload")) }

    private fun readSettlements(
        marketId: String,
        trades: List<StoredTrade>,
    ): Map<Long, Settlement> {
        if (trades.isEmpty()) return emptyMap()
        return jdbc
            .query(
                """
                select engine_sequence, result_sequence, settlement_id, source_event_id, maker_order_id, taker_order_id, price, quantity
                from order_query.settlement_transaction
                where namespace = :namespace and market_id = :market and engine_sequence in (:engineSequences)
                """.trimIndent(),
                mapOf("namespace" to namespace, "market" to marketId, "engineSequences" to trades.map { it.trade.engineSequence }),
            ) { row, _ ->
                Settlement(
                    engineSequence = row.getLong("engine_sequence"),
                    resultSequence = row.getLong("result_sequence"),
                    id = row.getString("settlement_id"),
                    sourceEventId = row.getString("source_event_id"),
                    makerOrderId = row.getString("maker_order_id"),
                    takerOrderId = row.getString("taker_order_id"),
                    price = row.getLong("price"),
                    quantity = row.getLong("quantity"),
                )
            }.associateBy { it.engineSequence }
    }

    private fun toOrderFill(
        fill: StoredTrade,
        boundary: FillBoundary,
        settlement: Settlement?,
    ): OrderFill {
        val trade = fill.trade
        val committed = fill.cursor.resultSequence <= boundary.settlementThrough
        require(committed == (settlement != null)) { "settlement row disagrees with the stored cursor" }
        if (settlement != null) {
            val sourceEventId = "MATCHING:${trade.marketId.value}:${trade.engineSequence}"
            require(
                settlement.id == fill.tradeId && settlement.resultSequence == fill.cursor.resultSequence &&
                    settlement.sourceEventId == sourceEventId &&
                    settlement.makerOrderId == trade.makerOrderId.value && settlement.takerOrderId == trade.takerOrderId.value &&
                    settlement.price == trade.price.value && settlement.quantity == trade.quantity.value,
            ) { "settlement row disagrees with the matching event" }
        }
        val status = if (committed) OrderFillSettlementStatus.COMPLETED else OrderFillSettlementStatus.PENDING
        return OrderFill(
            tradeId = fill.tradeId,
            price = trade.price.value,
            quantity = trade.quantity.value,
            role = fill.role,
            settlementStatus = status,
        )
    }

    private data class FillBoundary(
        val matchingThrough: Long,
        val settlementThrough: Long,
        val marketOrdinal: Int,
    )

    private data class MatchingReceipt(
        val sequence: Long,
        val payload: ByteArray,
    )

    private data class StoredTrade(
        val trade: TradeExecuted,
        val role: OrderFillRole,
        val cursor: OrderFillCursor,
        val tradeId: String,
    )

    private data class Settlement(
        val engineSequence: Long,
        val resultSequence: Long,
        val id: String,
        val sourceEventId: String,
        val makerOrderId: String,
        val takerOrderId: String,
        val price: Long,
        val quantity: Long,
    )

    private companion object {
        const val CANDIDATE_BATCH_SIZE = 8
    }
}

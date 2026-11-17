package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.port.OrderQueryFeedProgress
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryOriginsPendingException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/** A의 namespace 행 잠금으로 쓰기를 직렬화하고 정규화 전 원본 중복을 먼저 판정한다. */
class PostgresOrderQueryFeedStore(
    private val query: PostgresOrderQueryStore,
    private val jdbc: NamedParameterJdbcTemplate,
) : OrderQueryFeedStore {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val parameters = mapOf("namespace" to "${query.namespace.fundsLineage.high}:${query.namespace.fundsLineage.low}")
    private lateinit var consumerId: String
    private lateinit var streams: Map<String, MatchingResultStream>
    private lateinit var contexts: Map<String, TradingMarketContext>

    override fun initialize(
        consumerId: String,
        streams: Map<String, MatchingResultStream>,
        contexts: Map<String, TradingMarketContext>,
        sourceIdentity: String?,
    ) {
        require(consumerId.isNotBlank() && consumerId.length <= 256 && consumerId.none(Character::isISOControl))
        require(sourceIdentity == null || sourceIdentity.matches(Regex("[a-f0-9]{64}")))
        require(streams.isNotEmpty() && streams.keys == contexts.keys && streams.keys.containsAll(query.namespace.markets))
        streams.forEach { (market, stream) ->
            require(stream.marketId.value == market && stream.fundsLineage == query.namespace.fundsLineage)
            require(contexts.getValue(market).market.marketId == stream.marketId)
        }
        this.consumerId = consumerId
        this.streams = streams.toMap()
        this.contexts = contexts.toMap()
        val configuration =
            mapper.writeValueAsString(
                mapOf(
                    "streams" to streams.toSortedMap(),
                    "contexts" to contexts.toSortedMap(),
                    "sourceIdentity" to sourceIdentity,
                ),
            )
        guarded {
            query.initialize()
            query.withTransaction {
                query.lockedProgress()
                jdbc.update(
                    """
                    insert into order_query.feed_consumer(namespace, consumer_id, configuration_payload)
                    values (:namespace, :consumer, cast(:configuration as jsonb)) on conflict (namespace) do nothing
                    """.trimIndent(),
                    parameters + mapOf("consumer" to consumerId, "configuration" to configuration),
                )
                val same =
                    jdbc.queryForObject(
                        """
                        select consumer_id = :consumer and configuration_payload = cast(:configuration as jsonb)
                        from order_query.feed_consumer where namespace = :namespace
                        """.trimIndent(),
                        parameters + mapOf("consumer" to consumerId, "configuration" to configuration),
                        Boolean::class.java,
                    )
                require(same == true) { "source identity differs from the durable checkpoint" }
                streams.keys.forEach { market ->
                    jdbc.update(
                        """
                        insert into order_query.feed_stream(namespace, market_id) values (:namespace, :market)
                        on conflict (namespace, market_id) do nothing
                        """.trimIndent(),
                        parameters + ("market" to market),
                    )
                }
            }
        }
    }

    override fun progress(): OrderQueryFeedProgress = guarded { query.withTransaction { lockedProgress() } }

    override fun verifySources(
        admission: OrderQuerySourceBounds,
        matching: Map<String, OrderQuerySourceBounds>,
        financial: Map<String, Long>,
        expectedBeforeRead: OrderQueryFeedProgress?,
    ): OrderQueryFeedProgress =
        guarded {
            query
                .withTransaction {
                    val current = lockedProgress()
                    if (current.lifecycle == OrderQueryLifecycle.BLOCKED) return@withTransaction current
                    require(matching.keys == streams.keys) { "source market assignment changed" }
                    require(financial.isEmpty() || financial.keys == streams.keys) { "financial source market assignment changed" }
                    val beforeRead = expectedBeforeRead ?: current
                    require(beforeRead.consumerId == current.consumerId && beforeRead.appliedRevision <= current.appliedRevision)
                    require(beforeRead.admissionThrough <= current.admissionThrough)
                    require(beforeRead.matchingThrough.keys == streams.keys && beforeRead.financialThrough.keys == streams.keys)
                    streams.keys.forEach { market ->
                        require(beforeRead.matchingThrough.getValue(market) <= current.matchingThrough.getValue(market))
                        require(beforeRead.financialThrough.getValue(market) <= current.financialThrough.getValue(market))
                    }
                    val consumer = consumer()
                    if (!consumer.sourcesVerified) {
                        require(admission.completeFromGenesis && matching.values.all { it.completeFromGenesis }) {
                            "source history has no proven bootstrap"
                        }
                    }
                    requireBounds(admission, current.admissionThrough, beforeRead.admissionThrough)
                    matching.forEach { (market, bounds) ->
                        requireBounds(bounds, current.matchingThrough.getValue(market), beforeRead.matchingThrough.getValue(market))
                    }
                    // 소스를 읽는 동안 다른 WAS가 이미 저장한 prefix는 source 회귀로 판정하지 않는다.
                    var backlog = maxOf(0L, Math.subtractExact(admission.issued, current.admissionThrough))
                    matching.forEach { (market, bounds) ->
                        val pending = maxOf(0L, Math.subtractExact(bounds.issued, current.matchingThrough.getValue(market)))
                        backlog = Math.addExact(backlog, pending)
                    }
                    financial.forEach { (market, through) ->
                        val applied = current.financialThrough.getValue(market)
                        require(through >= beforeRead.financialThrough.getValue(market)) { "financial source prefix regressed" }
                        val head = minOf(through, matching.getValue(market).issued)
                        backlog = Math.addExact(backlog, maxOf(0L, Math.subtractExact(head, applied)))
                    }
                    val ready = consumer.bootstrapComplete || backlog == 0L
                    jdbc.update(
                        """
                        update order_query.feed_consumer set initial_sources_verified = true, bootstrap_complete = :ready
                        where namespace = :namespace
                        """.trimIndent(),
                        parameters + ("ready" to ready),
                    )
                    val progress = query.lockedProgress()
                    query.observe(maxOf(progress.observedRevision, Math.addExact(progress.appliedRevision, backlog)))
                    query.transitionLifecycle(if (ready) OrderQueryLifecycle.READY else OrderQueryLifecycle.INITIALIZING)
                    lockedProgress()
                }.also { query.verifySourceReads() }
        }

    override fun applyAdmission(
        fact: FundsOrderDispatch,
        canonicalPayload: ByteArray,
    ): Long =
        applySource(
            "ADMISSION",
            "funds",
            fact.admissionSequence,
            canonicalPayload,
            fact.order.marketId.value in query.namespace.markets,
        ) { current ->
            require(!fact.isCancellation && fact.admissionSequence == Math.addExact(current.admissionThrough, 1))
            requireOrigin(fact)
            val records = if (fact.order.marketId.value in query.namespace.markets) listOf(admissionRecord(fact)) else emptyList()
            commitBatch(fact.admissionSequence, records)
        }

    override fun applyMatching(
        fact: MatchingResultEnvelope,
        canonicalPayload: ByteArray,
    ): Long =
        applySource(
            "MATCHING",
            fact.stream.marketId.value,
            fact.resultSequence,
            canonicalPayload,
            fact.stream.marketId.value in query.namespace.markets,
        ) { current ->
            val market = fact.stream.marketId.value
            require(fact.stream == streams[market] && fact.input.context == contexts[market]) { "matching source identity changed" }
            require(fact.resultSequence == Math.addExact(current.matchingThrough.getValue(market), 1))
            requireOrigin(fact.input)
            require(
                fact.effects
                    .map { it.orderId }
                    .distinct()
                    .size == fact.effects.size,
            )
            if (fact.outcome == MatchingOutcome.CANCEL_REJECTED) require(fact.effects.isEmpty())
            val origins = fact.effects.map { it.origin } + fact.input
            origins.forEach { origin ->
                requireOrigin(origin)
                require(origin.order.marketId == fact.stream.marketId)
                if (origin.admissionSequence > current.admissionThrough) throw OrderQueryOriginsPendingException(origin.admissionSequence)
            }
            val records =
                if (market in query.namespace.markets) {
                    fact.effects.map { effect -> project(effect, fact, current.financialThrough.getValue(market)) }
                } else {
                    emptyList()
                }
            commitBatch(current.admissionThrough, records)
        }

    override fun applyFinancial(watermark: MatchingFundsSettledAck): Long =
        guarded {
            query.withTransaction {
                val current = lockedProgress()
                require(current.lifecycle != OrderQueryLifecycle.BLOCKED)
                val market = watermark.stream.marketId.value
                require(
                    watermark.stream == streams[market] && watermark.context == contexts[market],
                ) { "financial source identity changed" }
                require(watermark.through >= 0)
                if (watermark.through <= current.financialThrough.getValue(market)) return@withTransaction current.appliedRevision
                val applied = current.financialThrough.getValue(market)
                val matching = current.matchingThrough.getValue(market)
                // 이미 수집한 미정산 결과는 하나씩 갱신해 단일 결과의 maker/taker 상한을 지킨다.
                val through = if (applied < matching) minOf(watermark.through, Math.addExact(applied, 1)) else watermark.through
                val records =
                    query.recordsAwaitingFinancial(market, through).map { record ->
                        require(record.resultStream == watermark.stream)
                        record.copy(financialThrough = through)
                    }
                val revision = commitBatch(current.admissionThrough, records)
                jdbc.update(
                    "update order_query.feed_stream set financial_through = :through where namespace = :namespace and market_id = :market",
                    parameters + mapOf("market" to market, "through" to through),
                )
                revision
            }
        }

    override fun suspendProjection() {
        query.suspendSourceReads()
        query.withTransaction {
            if (query.lockedProgress().lifecycle != OrderQueryLifecycle.BLOCKED) {
                query.transitionLifecycle(OrderQueryLifecycle.INITIALIZING)
            }
        }
    }

    override fun block(reason: String) {
        query.blockSourceReads()
        query.withTransaction {
            query.lockedProgress()
            query.transitionLifecycle(OrderQueryLifecycle.BLOCKED)
            jdbc.update(
                "update order_query.feed_consumer set blocked_reason = :reason where namespace = :namespace",
                parameters + ("reason" to reason.take(256)),
            )
        }
    }

    private fun applySource(
        kind: String,
        key: String,
        sequence: Long,
        payload: ByteArray,
        projected: Boolean,
        apply: (OrderQueryFeedProgress) -> Long,
    ): Long {
        require(payload.isNotEmpty())
        val retainedPayload = payload.copyOf()
        return guarded {
            query.withTransaction {
                val current = lockedProgress()
                val sourceParameters =
                    parameters + mapOf("kind" to kind, "key" to key, "sequence" to sequence, "payload" to retainedPayload)
                val duplicate =
                    jdbc
                        .query(
                            """
                            select first_revision, canonical_payload = :payload as same_payload from order_query.source_receipt
                            where namespace = :namespace and source_kind = :kind and source_key = :key and source_sequence = :sequence
                            """.trimIndent(),
                            sourceParameters,
                        ) { rows, _ -> rows.getLong("first_revision") to rows.getBoolean("same_payload") }
                        .singleOrNull()
                if (duplicate != null) {
                    require(duplicate.second) { "source receipt has different canonical content" }
                    require(duplicate.first in 1..current.appliedRevision)
                    return@withTransaction duplicate.first
                }
                require(current.lifecycle != OrderQueryLifecycle.BLOCKED)
                val revision = apply(current)
                val classification = if (projected) "PROJECTED" else "UNSUPPORTED_QUERY_MARKET"
                jdbc.update(
                    """
                    insert into order_query.source_receipt
                        (namespace, source_kind, source_key, source_sequence, canonical_payload, classification, first_revision)
                    values (:namespace, :kind, :key, :sequence, :payload, :classification, :revision)
                    """.trimIndent(),
                    sourceParameters + mapOf("classification" to classification, "revision" to revision),
                )
                if (kind == "ADMISSION") {
                    jdbc.update(
                        "update order_query.feed_consumer set admission_through = :sequence where namespace = :namespace",
                        sourceParameters,
                    )
                } else {
                    jdbc.update(
                        "update order_query.feed_stream set matching_through = :sequence where namespace = :namespace and market_id = :key",
                        sourceParameters,
                    )
                }
                revision
            }
        }
    }

    private fun commitBatch(
        covered: Long,
        records: List<OrderQueryRecord>,
    ): Long {
        val progress = query.lockedProgress()
        val next = Math.addExact(progress.appliedRevision, 1)
        query.observe(maxOf(progress.observedRevision, next))
        return query.apply(OrderQueryBatch("feed:revision:$next", progress.appliedRevision, covered, records))
    }

    private fun requireOrigin(origin: FundsOrderDispatch) {
        val market = origin.order.marketId.value
        val stream = streams[market]
        require(
            stream != null && origin.stream.fundsLineage == stream.fundsLineage &&
                origin.stream.matchingLineage == stream.matchingLineage && origin.stream.marketId == stream.marketId &&
                origin.stream.ownershipEpoch == stream.ownershipEpoch && origin.stream.configurationVersion == stream.configurationVersion,
        )
        require(origin.context == contexts[market] && origin.order.marketId == origin.stream.marketId)
        require(origin.admissionSequence > 0 && origin.marketAdmissionSequence > 0)
        require(origin.order.orderId == TradingIdentifiers.order(query.namespace.fundsLineage, origin.admissionSequence))
    }

    private fun admissionRecord(origin: FundsOrderDispatch): OrderQueryRecord =
        OrderQueryRecord(
            query.namespace.fundsLineage,
            origin.order.marketId.value,
            origin.order.userId.value,
            origin.order.side,
            origin.order.orderType,
            origin.order.timeInForce,
            origin.order.price.value,
            origin.order.quantity.value,
            origin.admissionSequence,
            origin.marketAdmissionSequence,
            0,
            null,
            0,
            0,
            OrderQueryOrderState.MATCHING_PENDING,
        )

    private fun project(
        effect: MatchingOrderEffect,
        result: MatchingResultEnvelope,
        financial: Long,
    ): OrderQueryRecord {
        val origin = admissionRecord(effect.origin)
        val previous =
            query.findRecord(origin.marketId, origin.orderId) ?: throw OrderQueryOriginsPendingException(origin.admissionSequence)
        origin
            .copy(
                filledQuantity = previous.filledQuantity,
                remainingQuantity = previous.remainingQuantity,
                cancelledQuantity = previous.cancelledQuantity,
                notExecutedQuantity = previous.notExecutedQuantity,
                state = previous.state,
                resultStream = previous.resultStream,
                lastAffectedResultSequence = previous.lastAffectedResultSequence,
                financialThrough = previous.financialThrough,
            ).requireSuccessorOf(previous)
        val before = effect.beforeQuantity.value
        val after = effect.afterQuantity.value
        require(before == (previous.remainingQuantity ?: previous.initialQuantity) && after in 0..before)
        val filled: Long
        val remaining: Long
        val cancelled: Long
        val notExecuted: Long
        val state: OrderQueryOrderState
        when (effect.disposition) {
            MatchingOrderDisposition.CANCELLED -> {
                require(result.outcome == MatchingOutcome.CANCELLED && after == 0L)
                filled = previous.filledQuantity
                remaining = 0
                cancelled = before
                notExecuted = 0
                state = OrderQueryOrderState.CANCELLED
            }

            MatchingOrderDisposition.NOT_EXECUTED -> {
                require(result.outcome == MatchingOutcome.NOT_EXECUTED && before == origin.initialQuantity && before == after)
                filled = 0
                remaining = 0
                cancelled = 0
                notExecuted = origin.initialQuantity
                state = OrderQueryOrderState.NOT_EXECUTED
            }

            MatchingOrderDisposition.RESTING, MatchingOrderDisposition.TERMINAL -> {
                require(result.outcome == MatchingOutcome.MATCHED)
                require((effect.disposition == MatchingOrderDisposition.TERMINAL) == (after == 0L))
                filled = Math.addExact(previous.filledQuantity, Math.subtractExact(before, after))
                remaining = after
                cancelled = 0
                notExecuted = 0
                state =
                    when {
                        after == 0L -> OrderQueryOrderState.FILLED
                        filled > 0L -> OrderQueryOrderState.PARTIALLY_FILLED
                        else -> OrderQueryOrderState.RESTING
                    }
            }
        }
        return origin.copy(
            filledQuantity = filled,
            remainingQuantity = remaining,
            cancelledQuantity = cancelled,
            notExecutedQuantity = notExecuted,
            state = state,
            resultStream = result.stream,
            lastAffectedResultSequence = result.resultSequence,
            financialThrough = maxOf(financial, previous.financialThrough ?: 0),
        )
    }

    private fun lockedProgress(): OrderQueryFeedProgress {
        check(::consumerId.isInitialized) { "query source consumer is not initialized" }
        val progress = query.lockedProgress()
        val consumer = consumer()
        require(consumer.id == consumerId) { "query consumer identity changed" }
        val rows =
            jdbc.query(
                "select market_id, matching_through, financial_through from order_query.feed_stream where namespace = :namespace",
                parameters,
            ) { row, _ -> Triple(row.getString("market_id"), row.getLong("matching_through"), row.getLong("financial_through")) }
        require(rows.map { it.first }.toSet() == streams.keys)
        require(consumer.admission == progress.coveredAdmissionSequence)
        return OrderQueryFeedProgress(
            consumerId,
            consumer.admission,
            rows.associate { it.first to it.second },
            rows.associate { it.first to it.third },
            progress.lifecycle,
            progress.appliedRevision,
        )
    }

    private fun consumer(): Consumer =
        jdbc
            .query(
                "select * from order_query.feed_consumer where namespace = :namespace",
                parameters,
            ) { row, _ ->
                Consumer(
                    row.getString("consumer_id"),
                    row.getLong("admission_through"),
                    row.getBoolean("initial_sources_verified"),
                    row.getBoolean("bootstrap_complete"),
                )
            }.singleOrNull() ?: throw OrderQueryInconsistentException("stored source consumer is missing")

    private fun requireBounds(
        bounds: OrderQuerySourceBounds,
        cursor: Long,
        beforeRead: Long,
    ) {
        require(bounds.retiredThrough <= cursor && beforeRead <= bounds.issued) { "source prefix is missing or regressed" }
    }

    private fun <T> guarded(action: () -> T): T =
        try {
            action()
        } catch (failure: OrderQueryWriteConflictException) {
            persistBlockOrSuppress(failure, "source content or identity conflict")
            throw failure
        } catch (failure: OrderQueryInconsistentException) {
            persistBlockOrSuppress(failure, "stored source state is inconsistent")
            throw failure
        }

    private fun persistBlockOrSuppress(
        failure: Exception,
        reason: String,
    ) {
        try {
            block(reason)
        } catch (unavailable: Exception) {
            failure.addSuppressed(unavailable)
        }
    }

    private data class Consumer(
        val id: String,
        val admission: Long,
        val sourcesVerified: Boolean,
        val bootstrapComplete: Boolean,
    )
}

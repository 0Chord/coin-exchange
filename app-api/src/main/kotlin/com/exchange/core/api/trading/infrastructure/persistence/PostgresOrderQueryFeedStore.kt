package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.port.MAX_LEDGER_PAGE_ENTRIES
import com.exchange.core.api.trading.application.port.OrderQueryFeedProgress
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryLedgerException
import com.exchange.core.api.trading.application.port.OrderQueryOriginsPendingException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerReconciliationReport
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.ReservationHold
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsLedgerProgress
import com.exchange.core.trading.FundsLedgerRecovery
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketContext
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.Statement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
    private var settlementEnabled = false

    override fun initialize(
        consumerId: String,
        streams: Map<String, MatchingResultStream>,
        contexts: Map<String, TradingMarketContext>,
        sourceIdentity: String?,
        settlementEnabled: Boolean,
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
        this.settlementEnabled = settlementEnabled
        val sourceConfiguration =
            mutableMapOf<String, Any?>(
                "streams" to streams.toSortedMap(),
                "contexts" to contexts.toSortedMap(),
                "sourceIdentity" to sourceIdentity,
            )
        if (settlementEnabled) sourceConfiguration["settlementEnabled"] = true
        val configuration = mapper.writeValueAsString(sourceConfiguration)
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
                val sameConfiguration =
                    jdbc.queryForObject(
                        """
                        select consumer_id = :consumer and configuration_payload = cast(:configuration as jsonb)
                        from order_query.feed_consumer where namespace = :namespace
                        """.trimIndent(),
                        parameters + mapOf("consumer" to consumerId, "configuration" to configuration),
                        Boolean::class.java,
                    )
                require(sameConfiguration == true) { "source identity differs from the durable checkpoint" }
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
            val verifiedProgress =
                query.withTransaction {
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
                }
            query.verifySourceReads()
            verifiedProgress
        }

    override fun applyAdmission(
        fact: FundsOrderDispatch,
        canonicalPayload: ByteArray,
    ): Long =
        applySource(
            kind = "ADMISSION",
            key = "funds",
            sequence = fact.admissionSequence,
            payload = canonicalPayload,
            projected = fact.order.marketId.value in query.namespace.markets,
        ) { current ->
            require(!fact.isCancellation && fact.admissionSequence == Math.addExact(current.admissionThrough, 1))
            requireOrigin(fact)
            val records = if (fact.order.marketId.value in query.namespace.markets) listOf(admissionRecord(fact)) else emptyList()
            applyBatch(fact.admissionSequence, records)
        }

    override fun applyMatching(
        fact: MatchingResultEnvelope,
        canonicalPayload: ByteArray,
    ): Long =
        applySource(
            kind = "MATCHING",
            key = fact.stream.marketId.value,
            sequence = fact.resultSequence,
            payload = canonicalPayload,
            projected = fact.stream.marketId.value in query.namespace.markets,
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
            applyBatch(current.admissionThrough, records)
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
                val revision = applyBatch(current.admissionThrough, records)
                jdbc.update(
                    "update order_query.feed_stream set financial_through = :through where namespace = :namespace and market_id = :market",
                    parameters + mapOf("market" to market, "through" to through),
                )
                revision
            }
        }

    override fun applySettlement(fact: SettlementFact): Long =
        guarded {
            query.withTransaction {
                val current = lockedProgress()
                val market = fact.stream.marketId.value
                require(settlementEnabled && current.lifecycle != OrderQueryLifecycle.BLOCKED)
                require(fact.stream == streams[market]) { "settlement source identity changed" }
                val context = contexts.getValue(market)
                val previous = current.settlementThrough.getValue(market)
                val sequence = fact.resultSequence
                val resultParameters = parameters + mapOf("market" to market, "resultSequence" to sequence)
                val duplicate = sequence <= previous
                if (duplicate) {
                    val storedCount =
                        jdbc.queryForObject(
                            """
                            select count(*) from order_query.settlement_transaction
                            where namespace = :namespace and market_id = :market and result_sequence = :resultSequence
                            """.trimIndent(),
                            resultParameters,
                            Long::class.java,
                        )
                    if (storedCount != fact.fills.size.toLong()) {
                        throw OrderQueryWriteConflictException("settlement result $market/$sequence has different fills")
                    }
                } else {
                    require(sequence == Math.addExact(previous, 1)) { "settlement source prefix has a gap" }
                }

                for (fill in fact.fills) {
                    val trade = fill.trade
                    val transaction = fill.transaction
                    val settlementId = TradingIdentifiers.settlement(fact.stream.fundsLineage, context.ordinal, trade.engineSequence)
                    require(transaction.ledgerTransactionId == settlementId) { "settlement identity differs from the source" }
                    require(transaction.occurredAt.nano % 1_000 == 0) { "settlement timestamp exceeds PostgreSQL precision" }
                    val header =
                        resultParameters +
                            mapOf(
                                "settlementId" to settlementId,
                                "sourceEventId" to transaction.sourceEventId,
                                "engineSequence" to trade.engineSequence,
                                "makerOrderId" to trade.makerOrderId.value,
                                "takerOrderId" to trade.takerOrderId.value,
                                "makerUserId" to trade.makerUserId.value,
                                "takerUserId" to trade.takerUserId.value,
                                "takerSide" to trade.side.name,
                                "baseAssetId" to context.market.baseAssetId.value,
                                "quoteAssetId" to context.market.quoteAssetId.value,
                                "price" to trade.price.value,
                                "quantity" to trade.quantity.value,
                                "makerFee" to fill.makerFee.value,
                                "takerFee" to fill.takerFee.value,
                                "occurredAt" to OffsetDateTime.ofInstant(transaction.occurredAt, ZoneOffset.UTC),
                            )
                    if (duplicate) {
                        requireStoredSettlement(header, transaction.postings)
                    } else {
                        insertSettlement(header, settlementId, transaction.postings)
                    }
                }
                if (!duplicate) {
                    jdbc.update(
                        """
                        update order_query.feed_stream set settlement_through = :resultSequence
                        where namespace = :namespace and market_id = :market
                        """.trimIndent(),
                        resultParameters,
                    )
                }
                sequence
            }
        }

    override fun ledgerThrough(): Long =
        checkNotNull(
            jdbc.queryForObject(
                "select coalesce(max(sequence), 0) from order_query.funds_ledger_batch where namespace = :namespace",
                parameters,
                Long::class.java,
            ),
        )

    override fun verifyLedgerPrefix(through: Long) {
        require(through >= 0L)
        if (through == 0L) return
        val found =
            jdbc.queryForObject(
                "select count(*) from order_query.funds_ledger_batch where namespace = :namespace and sequence <= :through",
                parameters + ("through" to through),
                Long::class.java,
            )
        if (found != through) throw ledgerIncomplete("자금 원본에서 회수한 원장 범위에 누락이 있다")
    }

    override fun applyLedgerPage(batches: List<FundsLedgerBatch>): Long {
        require(batches.size in 1..MAX_LEDGER_PAGE_ENTRIES)
        require(batches.sumOf { it.canonicalBytes } <= FundsLedgerRecovery.MAX_RETAINED_BYTES)
        // 상위 트랜잭션에 참여하면 커밋 전에 확인 순번을 반환할 수 있다.
        val dataSource = checkNotNull(jdbc.jdbcTemplate.dataSource)
        check(!TransactionSynchronizationManager.hasResource(dataSource)) { "원장 페이지 저장은 독립적으로 커밋해야 한다" }

        var previousSequence = batches.first().sequence - 1
        var previousPosition = 0L
        for (batch in batches) {
            if (batch.lineage != query.namespace.fundsLineage) throw ledgerMismatch("원장 묶음 ${batch.sequence}의 출처가 다르다")
            if (batch.sequence - 1 != previousSequence) throw ledgerIncomplete("원장 묶음 ${batch.sequence} 앞의 순번이 연속되지 않는다")
            if (batch.logPosition <= previousPosition) throw ledgerMismatch("원장 묶음 ${batch.sequence}의 적용 위치가 역행했다")
            previousSequence = batch.sequence
            previousPosition = batch.logPosition
        }

        return query.withTransaction {
            // 같은 DB를 쓰는 수집기는 namespace 잠금 안에서 현재 원장 위치를 읽고 저장한다.
            query.lockedProgress()
            val head =
                jdbc
                    .query(
                        """
                        select sequence, log_position from order_query.funds_ledger_batch
                        where namespace = :namespace order by sequence desc limit 1
                        """.trimIndent(),
                        parameters,
                    ) { row, _ -> row.getLong("sequence") to row.getLong("log_position") }
                    .singleOrNull()
            val (through, lastPosition) = head ?: (0L to 0L)
            val overlapCount = batches.indexOfLast { it.sequence <= through } + 1
            if (overlapCount > 0) verifyStoredLedger(batches.subList(0, overlapCount))
            val firstNew = batches.getOrNull(overlapCount) ?: return@withTransaction batches.last().sequence
            if (firstNew.sequence - 1 != through) throw ledgerIncomplete("원장 저장 순번 ${firstNew.sequence} 앞에 공백이 있다")
            if (firstNew.logPosition <= lastPosition) throw ledgerMismatch("원장 묶음 ${firstNew.sequence}의 적용 위치가 역행했다")

            val rows =
                batches.subList(overlapCount, batches.size).map { batch ->
                    parameters +
                        mapOf(
                            "sequence" to batch.sequence,
                            "position" to batch.logPosition,
                            "transactions" to mapper.writeValueAsString(batch.transactions.map(StoredLedgerTransaction::from)),
                        )
                }
            val inserted =
                jdbc.batchUpdate(
                    """
                    insert into order_query.funds_ledger_batch (namespace, sequence, log_position, transactions)
                    values (:namespace, :sequence, :position, cast(:transactions as jsonb))
                    """.trimIndent(),
                    rows.toTypedArray(),
                )
            check(inserted.size == rows.size && inserted.all { it == 1 || it == Statement.SUCCESS_NO_INFO })
            batches.last().sequence
        }
    }

    private fun verifyStoredLedger(batches: List<FundsLedgerBatch>) {
        val expected =
            batches.map { batch ->
                mapOf(
                    "sequence" to batch.sequence,
                    "log_position" to batch.logPosition,
                    "transactions" to batch.transactions.map(StoredLedgerTransaction::from),
                )
            }
        jdbc.query(
            """
            select expected.sequence,
                actual.sequence is not null as is_present,
                actual.log_position = expected.log_position and actual.transactions::text = expected.transactions::text as same_batch
            from jsonb_to_recordset(cast(:expected as jsonb)) as expected(sequence bigint, log_position bigint, transactions jsonb)
            left join order_query.funds_ledger_batch actual on actual.namespace = :namespace and actual.sequence = expected.sequence
            order by expected.sequence
            """.trimIndent(),
            parameters + ("expected" to mapper.writeValueAsString(expected)),
            RowCallbackHandler { row ->
                val sequence = row.getLong("sequence")
                if (!row.getBoolean("is_present")) throw ledgerIncomplete("원장 묶음 $sequence 누락")
                if (!row.getBoolean("same_batch")) throw ledgerMismatch("원장 묶음 $sequence 재전송 내용과 저장 내용이 다르다")
            },
        )
    }

    override fun reconcileLedger(
        scope: LedgerReconciliationScope,
        logPosition: Long,
        ledger: FundsLedgerProgress,
        balances: List<Balance>,
        reservations: List<ReservationHold>,
        onReadProgress: () -> Unit,
    ): LedgerReconciliationReport =
        query.withTransaction {
            if (!ledger.completeFromGenesis) throw ledgerIncomplete("첫 금융 변경부터 원장이 보존됐는지 확인할 수 없다")
            val source =
                jdbc.jdbcTemplate.queryForStream(
                    """
                    select sequence, log_position, transactions from order_query.funds_ledger_batch
                    where namespace = ? and sequence <= ? order by sequence
                    """.trimIndent(),
                    PreparedStatementSetter { statement ->
                        statement.fetchSize = 128
                        statement.setString(1, parameters.getValue("namespace"))
                        statement.setLong(2, ledger.issued)
                    },
                    RowMapper { row, _ ->
                        try {
                            val payload = mapper.readTree(row.getString("transactions"))
                            val stored = mapper.treeToValue(payload, Array<StoredLedgerTransaction>::class.java)
                            // JSON 숫자나 문자열이 Kotlin 값으로 강제 변환되며 손상이 숨겨지지 않게 한다.
                            val canonical = mapper.readTree(mapper.writeValueAsString(stored))
                            require(payload == canonical) { "저장된 원장 필드의 타입이 올바르지 않다" }
                            FundsLedgerBatch(
                                query.namespace.fundsLineage,
                                row.getLong("sequence"),
                                row.getLong("log_position"),
                                stored.map(StoredLedgerTransaction::toTransaction),
                            )
                        } catch (failure: RuntimeException) {
                            throw ledgerMismatch("저장된 분개를 해석할 수 없다", failure)
                        }
                    },
                )
            source.use {
                var previousSequence = 0L
                var previousPosition = 0L
                var digest = FundsLedgerBatch.GENESIS_DIGEST
                val transactions =
                    source.iterator().asSequence().flatMap { batch ->
                        if (batch.sequence != Math.addExact(previousSequence, 1L)) {
                            throw ledgerIncomplete("원장 묶음 ${previousSequence + 1} 누락")
                        }
                        if (batch.logPosition <= previousPosition || batch.logPosition > logPosition) {
                            throw ledgerMismatch("원장 묶음 ${batch.sequence} 적용 위치가 올바르지 않다")
                        }
                        previousSequence = batch.sequence
                        previousPosition = batch.logPosition
                        digest = batch.digestAfter(digest)
                        if (previousSequence % 128 == 0L) onReadProgress()
                        batch.transactions.asSequence()
                    }
                val report = LedgerReconciliation().compare(scope, transactions, balances, reservations)
                if (previousSequence != ledger.issued) throw ledgerIncomplete("자금 위치까지의 원장이 누락됐다")
                if (digest != ledger.digest) throw ledgerMismatch("자금 클러스터와 저장된 원장 내용이 다르다")
                report
            }
        }

    private fun ledgerIncomplete(message: String) = OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, message)

    private fun ledgerMismatch(
        message: String,
        cause: Throwable? = null,
    ) = OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_MISMATCH, message, cause)

    private data class StoredLedgerTransaction(
        val id: String,
        val sourceEventId: String,
        val type: LedgerTransactionType,
        val occurredAt: String,
        val postings: List<StoredLedgerPosting>,
    ) {
        fun toTransaction(): LedgerTransaction =
            LedgerTransaction(
                id,
                sourceEventId,
                type,
                Instant.parse(occurredAt),
                postings.map { LedgerPosting(it.accountId, AssetId(it.assetId), it.side, Amount(it.amount)) },
            )

        companion object {
            fun from(transaction: LedgerTransaction): StoredLedgerTransaction =
                StoredLedgerTransaction(
                    transaction.ledgerTransactionId,
                    transaction.sourceEventId,
                    transaction.transactionType,
                    transaction.occurredAt.toString(),
                    transaction.postings.map { StoredLedgerPosting(it.accountId, it.assetId.value, it.side, it.amount.value) },
                )
        }
    }

    private data class StoredLedgerPosting(
        val accountId: String,
        val assetId: String,
        val side: LedgerPostingSide,
        val amount: Long,
    )

    private fun insertSettlement(
        header: Map<String, Any>,
        settlementId: String,
        postings: List<LedgerPosting>,
    ) {
        val inserted =
            jdbc.update(
                """
                insert into order_query.settlement_transaction
                    (namespace, market_id, result_sequence, settlement_id, source_event_id, engine_sequence,
                     maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, base_asset_id,
                     quote_asset_id, price, quantity, maker_fee, taker_fee, occurred_at)
                values (:namespace, :market, :resultSequence, :settlementId, :sourceEventId, :engineSequence,
                        :makerOrderId, :takerOrderId, :makerUserId, :takerUserId, :takerSide, :baseAssetId,
                        :quoteAssetId, :price, :quantity, :makerFee, :takerFee, :occurredAt)
                on conflict do nothing
                """.trimIndent(),
                header,
            )
        if (inserted != 1) throw OrderQueryWriteConflictException("settlement $settlementId already belongs to a stored result")
        val rows =
            postings.mapIndexed { index, posting ->
                parameters +
                    mapOf(
                        "settlementId" to settlementId,
                        "postingSequence" to index + 1,
                        "accountId" to posting.accountId,
                        "assetId" to posting.assetId.value,
                        "side" to posting.side.name,
                        "amount" to posting.amount.value,
                    )
            }
        jdbc.batchUpdate(
            """
            insert into order_query.settlement_posting (namespace, settlement_id, posting_sequence, account_id, asset_id, side, amount)
            values (:namespace, :settlementId, :postingSequence, :accountId, :assetId, :side, :amount)
            """.trimIndent(),
            rows.toTypedArray(),
        )
    }

    private fun requireStoredSettlement(
        header: Map<String, Any>,
        postings: List<LedgerPosting>,
    ) {
        val sameHeader =
            jdbc.queryForObject(
                """
                select exists (
                    select 1 from order_query.settlement_transaction
                    where namespace = :namespace and settlement_id = :settlementId and market_id = :market
                        and result_sequence = :resultSequence and source_event_id = :sourceEventId
                        and engine_sequence = :engineSequence and maker_order_id = :makerOrderId and taker_order_id = :takerOrderId
                        and maker_user_id = :makerUserId and taker_user_id = :takerUserId and taker_side = :takerSide
                        and base_asset_id = :baseAssetId and quote_asset_id = :quoteAssetId and price = :price and quantity = :quantity
                        and maker_fee = :makerFee and taker_fee = :takerFee and occurred_at = :occurredAt
                )
                """.trimIndent(),
                header,
                Boolean::class.java,
            )
        if (sameHeader != true) throw OrderQueryWriteConflictException("settlement ${header["settlementId"]} has different content")
        val storedPostings =
            jdbc.query(
                """
                select posting_sequence, account_id, asset_id, side, amount from order_query.settlement_posting
                where namespace = :namespace and settlement_id = :settlementId order by posting_sequence
                """.trimIndent(),
                header,
            ) { row, index ->
                if (row.getInt("posting_sequence") != index + 1) {
                    throw OrderQueryInconsistentException("stored settlement posting positions are not contiguous")
                }
                LedgerPosting(
                    accountId = row.getString("account_id"),
                    assetId = AssetId(row.getString("asset_id")),
                    side = LedgerPostingSide.valueOf(row.getString("side")),
                    amount = Amount(row.getLong("amount")),
                )
            }
        if (postings != storedPostings) {
            throw OrderQueryWriteConflictException("settlement ${header["settlementId"]} has different postings")
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
                    val (firstRevision, samePayload) = duplicate
                    require(samePayload) { "source receipt has different canonical content" }
                    require(firstRevision in 1..current.appliedRevision)
                    return@withTransaction firstRevision
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

    private fun applyBatch(
        coveredAdmissionSequence: Long,
        records: List<OrderQueryRecord>,
    ): Long {
        val progress = query.lockedProgress()
        val nextRevision = Math.addExact(progress.appliedRevision, 1)
        query.observe(maxOf(progress.observedRevision, nextRevision))
        val batch =
            OrderQueryBatch(
                batchId = "feed:revision:$nextRevision",
                expectedRevision = progress.appliedRevision,
                coveredAdmissionSequence = coveredAdmissionSequence,
                records = records,
            )
        return query.apply(batch)
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
            fundsLineage = query.namespace.fundsLineage,
            marketId = origin.order.marketId.value,
            userId = origin.order.userId.value,
            side = origin.order.side,
            orderType = origin.order.orderType,
            timeInForce = origin.order.timeInForce,
            price = origin.order.price.value,
            initialQuantity = origin.order.quantity.value,
            admissionSequence = origin.admissionSequence,
            marketAdmissionSequence = origin.marketAdmissionSequence,
            filledQuantity = 0,
            remainingQuantity = null,
            cancelledQuantity = 0,
            notExecutedQuantity = 0,
            state = OrderQueryOrderState.MATCHING_PENDING,
        )

    private fun project(
        effect: MatchingOrderEffect,
        result: MatchingResultEnvelope,
        financial: Long,
    ): OrderQueryRecord {
        val origin = admissionRecord(effect.origin)
        val previous =
            query.findRecord(origin.marketId, origin.orderId) ?: throw OrderQueryOriginsPendingException(origin.admissionSequence)
        val originWithPreviousState =
            origin.copy(
                filledQuantity = previous.filledQuantity,
                remainingQuantity = previous.remainingQuantity,
                cancelledQuantity = previous.cancelledQuantity,
                notExecutedQuantity = previous.notExecutedQuantity,
                state = previous.state,
                resultStream = previous.resultStream,
                lastAffectedResultSequence = previous.lastAffectedResultSequence,
                financialThrough = previous.financialThrough,
            )
        originWithPreviousState.requireSuccessorOf(previous)
        val beforeQuantity = effect.beforeQuantity.value
        val afterQuantity = effect.afterQuantity.value
        require(beforeQuantity == (previous.remainingQuantity ?: previous.initialQuantity) && afterQuantity in 0..beforeQuantity)
        val filled: Long
        val remaining: Long
        val cancelled: Long
        val notExecuted: Long
        val state: OrderQueryOrderState
        when (effect.disposition) {
            MatchingOrderDisposition.CANCELLED -> {
                require(result.outcome == MatchingOutcome.CANCELLED && afterQuantity == 0L)
                filled = previous.filledQuantity
                remaining = 0
                cancelled = beforeQuantity
                notExecuted = 0
                state = OrderQueryOrderState.CANCELLED
            }

            MatchingOrderDisposition.NOT_EXECUTED -> {
                require(
                    result.outcome == MatchingOutcome.NOT_EXECUTED &&
                        beforeQuantity == origin.initialQuantity && beforeQuantity == afterQuantity,
                )
                filled = 0
                remaining = 0
                cancelled = 0
                notExecuted = origin.initialQuantity
                state = OrderQueryOrderState.NOT_EXECUTED
            }

            MatchingOrderDisposition.RESTING, MatchingOrderDisposition.TERMINAL -> {
                require(result.outcome == MatchingOutcome.MATCHED)
                require((effect.disposition == MatchingOrderDisposition.TERMINAL) == (afterQuantity == 0L))
                filled = Math.addExact(previous.filledQuantity, Math.subtractExact(beforeQuantity, afterQuantity))
                remaining = afterQuantity
                cancelled = 0
                notExecuted = 0
                state =
                    when {
                        afterQuantity == 0L -> OrderQueryOrderState.FILLED
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
        val streamCursors =
            jdbc.query(
                """
                select market_id, matching_through, financial_through, settlement_through
                from order_query.feed_stream where namespace = :namespace
                """.trimIndent(),
                parameters,
            ) { row, _ ->
                StreamCursor(
                    market = row.getString("market_id"),
                    matching = row.getLong("matching_through"),
                    financial = row.getLong("financial_through"),
                    settlement = row.getLong("settlement_through"),
                )
            }
        require(streamCursors.map { it.market }.toSet() == streams.keys)
        require(consumer.admission == progress.coveredAdmissionSequence)
        return OrderQueryFeedProgress(
            consumerId = consumerId,
            admissionThrough = consumer.admission,
            matchingThrough = streamCursors.associate { it.market to it.matching },
            financialThrough = streamCursors.associate { it.market to it.financial },
            lifecycle = progress.lifecycle,
            appliedRevision = progress.appliedRevision,
            settlementThrough = if (settlementEnabled) streamCursors.associate { it.market to it.settlement } else emptyMap(),
        )
    }

    private fun consumer(): Consumer =
        jdbc
            .query(
                "select * from order_query.feed_consumer where namespace = :namespace",
                parameters,
            ) { row, _ ->
                Consumer(
                    id = row.getString("consumer_id"),
                    admission = row.getLong("admission_through"),
                    sourcesVerified = row.getBoolean("initial_sources_verified"),
                    bootstrapComplete = row.getBoolean("bootstrap_complete"),
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

    private data class StreamCursor(
        val market: String,
        val matching: Long,
        val financial: Long,
        val settlement: Long,
    )
}

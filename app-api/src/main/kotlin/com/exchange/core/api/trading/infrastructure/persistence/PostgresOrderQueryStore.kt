package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.LatestSettledTrade
import com.exchange.core.api.trading.application.LatestSettledTradeSnapshot
import com.exchange.core.api.trading.application.OrderFillCursor
import com.exchange.core.api.trading.application.OrderFillQuerySnapshot
import com.exchange.core.api.trading.application.OrderListQuerySnapshot
import com.exchange.core.api.trading.application.OrderQueryBatch
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryProgress
import com.exchange.core.api.trading.application.OrderQueryRecord
import com.exchange.core.api.trading.application.OrderQuerySnapshot
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.type.LogicalType
import tools.jackson.module.kotlin.KotlinModule
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicReference

/** 공용 RDB의 조회 테이블만 사용하며 금융·매칭 상태를 계산하거나 갱신하지 않는다. */
class PostgresOrderQueryStore(
    override val namespace: OrderQueryNamespace,
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : OrderQueryStore {
    private val transaction = TransactionTemplate(transactionManager)

    // DB 장애 중 준비/차단 상태를 저장하지 못해도 같은 WAS가 낡은 READY를 노출하지 않는다.
    private val sourceReadGate = AtomicReference<OrderQueryLifecycle?>(null)

    internal fun suspendSourceReads() {
        sourceReadGate.compareAndSet(null, OrderQueryLifecycle.INITIALIZING)
    }

    internal fun blockSourceReads() {
        sourceReadGate.set(OrderQueryLifecycle.BLOCKED)
    }

    internal fun verifySourceReads() {
        sourceReadGate.compareAndSet(OrderQueryLifecycle.INITIALIZING, null)
    }

    internal fun sourceLifecycle(lifecycle: OrderQueryLifecycle): OrderQueryLifecycle =
        if (lifecycle == OrderQueryLifecycle.BLOCKED) lifecycle else sourceReadGate.get() ?: lifecycle

    private val mapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .enable(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            ).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual) { config ->
                listOf(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)
                    .forEach { config.setCoercion(it, CoercionAction.Fail) }
            }.build()
    internal val namespaceKey = "${namespace.fundsLineage.high}:${namespace.fundsLineage.low}"
    private val namespaceParameters = mapOf("namespace" to namespaceKey)
    private val marketsPayload = mapper.writeValueAsString(namespace.markets.sorted())
    private val fills = PostgresOrderFillReader(jdbc, namespaceKey)

    override fun initialize() {
        write {
            jdbc.update(
                """
                insert into order_query.query_progress
                    (namespace, supported_markets, lifecycle, applied_revision, observed_revision, covered_admission_sequence)
                values (:namespace, cast(:markets as jsonb), 'INITIALIZING', 0, 0, 0)
                on conflict (namespace) do nothing
                """.trimIndent(),
                namespaceParameters + ("markets" to marketsPayload),
            )
            lockedProgress()
        }
    }

    override fun observe(observedRevision: Long) {
        write {
            val progress = lockedProgress()
            require(observedRevision >= progress.observedRevision && observedRevision >= progress.appliedRevision)
            if (observedRevision != progress.observedRevision) {
                jdbc.update(
                    """
                    update order_query.query_progress set observed_revision = :observed, updated_at = current_timestamp
                    where namespace = :namespace
                    """.trimIndent(),
                    namespaceParameters + ("observed" to observedRevision),
                )
            }
        }
    }

    override fun transitionLifecycle(lifecycle: OrderQueryLifecycle) {
        write {
            val progress = lockedProgress()
            require(progress.lifecycle != OrderQueryLifecycle.BLOCKED || lifecycle != OrderQueryLifecycle.READY)
            if (lifecycle != progress.lifecycle) {
                jdbc.update(
                    """
                    update order_query.query_progress set lifecycle = :lifecycle, updated_at = current_timestamp
                    where namespace = :namespace
                    """.trimIndent(),
                    namespaceParameters + ("lifecycle" to lifecycle.name),
                )
            }
        }
    }

    override fun apply(batch: OrderQueryBatch): Long =
        write {
            val progress = lockedProgress()
            val payload = batchPayload(batch)
            // 잠금 뒤 중복을 먼저 확인하므로 최초 적용 이후의 재시도도 같은 결과를 받는다.
            val repeated =
                jdbc
                    .query(
                        """
                        select first_revision, batch_payload = cast(:payload as jsonb) as same_payload
                        from order_query.applied_batch where namespace = :namespace and batch_id = :batchId
                        """.trimIndent(),
                        namespaceParameters + mapOf("batchId" to batch.batchId, "payload" to payload),
                    ) { rows, _ -> rows.getLong("first_revision") to rows.getBoolean("same_payload") }
                    .singleOrNull()
            if (repeated != null) {
                require(repeated.second) { "batch identity already has different content" }
                if (repeated.first !in 1..progress.appliedRevision) inconsistent()
                return@write repeated.first
            }
            require(progress.lifecycle != OrderQueryLifecycle.BLOCKED)
            require(batch.expectedRevision == progress.appliedRevision)
            val nextRevision = Math.addExact(progress.appliedRevision, 1)
            require(nextRevision <= progress.observedRevision)
            saveNewBatch(progress, batch, payload, nextRevision)
        }

    /** 수집기는 원본의 재전송을 처리한 뒤 같은 트랜잭션에서 다음 조회 버전을 저장한다. */
    internal fun applyNextFeedBatch(
        coveredAdmissionSequence: Long,
        records: List<OrderQueryRecord>,
    ): Long =
        write {
            val progress = lockedProgress()
            require(progress.lifecycle != OrderQueryLifecycle.BLOCKED)
            val nextRevision = Math.addExact(progress.appliedRevision, 1)
            val batch =
                OrderQueryBatch(
                    batchId = "feed:revision:$nextRevision",
                    expectedRevision = progress.appliedRevision,
                    coveredAdmissionSequence = coveredAdmissionSequence,
                    records = records,
                )
            saveNewBatch(progress, batch, batchPayload(batch), nextRevision)
        }

    private fun saveNewBatch(
        progress: OrderQueryProgress,
        batch: OrderQueryBatch,
        payload: String,
        nextRevision: Long,
    ): Long {
        require(batch.coveredAdmissionSequence >= progress.coveredAdmissionSequence)
        batch.records.forEach { record ->
            namespace.requireRecord(record)
            require(record.admissionSequence <= batch.coveredAdmissionSequence)
            findRecord(record.marketId, record.orderId)?.let { previous -> record.requireSuccessorOf(previous) }
            save(record)
        }
        jdbc.update(
            """
            insert into order_query.applied_batch(namespace, batch_id, batch_payload, first_revision)
            values (:namespace, :batchId, cast(:payload as jsonb), :revision)
            """.trimIndent(),
            namespaceParameters + mapOf("batchId" to batch.batchId, "payload" to payload, "revision" to nextRevision),
        )
        check(
            jdbc.update(
                """
                update order_query.query_progress
                set applied_revision = :revision, observed_revision = greatest(observed_revision, :revision),
                    covered_admission_sequence = :covered, updated_at = current_timestamp
                where namespace = :namespace and applied_revision = :expected
                """.trimIndent(),
                namespaceParameters +
                    mapOf(
                        "revision" to nextRevision,
                        "covered" to batch.coveredAdmissionSequence,
                        "expected" to batch.expectedRevision,
                    ),
            ) == 1,
        )
        return nextRevision
    }

    override fun read(
        marketId: String,
        orderId: String,
    ): OrderQuerySnapshot =
        try {
            // 한 SQL의 snapshot에서 진행 위치와 주문을 함께 관찰한다. 읽기는 어떤 행도 갱신하지 않는다.
            jdbc
                .query(
                    """
                    select p.*, r.market_id, r.order_id, r.admission_sequence, r.market_admission_sequence, r.record_payload
                    from order_query.query_progress p
                    left join order_query.order_record r
                        on r.namespace = p.namespace and r.market_id = :marketId and r.order_id = :orderId
                    where p.namespace = :namespace
                    """.trimIndent(),
                    namespaceParameters + mapOf("marketId" to marketId, "orderId" to orderId),
                ) { rows, _ ->
                    val progress = progress(rows)
                    val record = if (rows.getString("record_payload") == null) null else record(rows)
                    if (record != null && record.admissionSequence > progress.coveredAdmissionSequence) inconsistent()
                    val lifecycle = sourceLifecycle(progress.lifecycle)
                    val readable = if (lifecycle == progress.lifecycle) progress else progress.copy(lifecycle = lifecycle)
                    OrderQuerySnapshot(readable, record)
                }.singleOrNull() ?: OrderQuerySnapshot(null, null)
        } catch (error: OrderQueryInconsistentException) {
            throw error
        } catch (_: DataAccessException) {
            throw OrderQueryUnavailableException("order query database read failed")
        } catch (_: Exception) {
            throw OrderQueryInconsistentException("stored order query state is inconsistent")
        }

    override fun readUserOrders(
        userId: String,
        maxAdmissionSequence: Long,
        maxRows: Int,
    ): OrderListQuerySnapshot =
        try {
            val parameters =
                namespaceParameters +
                    mapOf("userId" to userId, "maxAdmissionSequence" to maxAdmissionSequence, "maxRows" to maxRows)
            checkNotNull(
                jdbc.query<OrderListQuerySnapshot>(
                    """
                    select p.*, r.market_id, r.order_id, r.admission_sequence, r.market_admission_sequence, r.record_payload
                    from order_query.query_progress p
                    left join lateral (
                        select market_id, order_id, admission_sequence, market_admission_sequence, record_payload
                        from order_query.order_record
                        where namespace = p.namespace and record_payload ->> 'userId' = :userId
                            and admission_sequence <= :maxAdmissionSequence
                        order by admission_sequence desc
                        limit :maxRows
                    ) r on true
                    where p.namespace = :namespace
                    order by r.admission_sequence desc
                    """.trimIndent(),
                    parameters,
                ) { rows ->
                    if (!rows.next()) return@query OrderListQuerySnapshot(progress = null, orders = emptyList())
                    val progress = progress(rows)
                    val orders = ArrayList<OrderQueryRecord>()
                    do {
                        if (rows.getString("order_id") == null) break
                        val order = record(rows)
                        if (order.admissionSequence > progress.coveredAdmissionSequence) inconsistent()
                        orders.add(order)
                    } while (rows.next())
                    OrderListQuerySnapshot(progress.copy(lifecycle = sourceLifecycle(progress.lifecycle)), orders)
                },
            )
        } catch (error: DataAccessException) {
            throw OrderQueryUnavailableException("order list database read failed", error)
        }

    override fun readFills(
        marketId: String,
        orderId: String,
        minAdmissionSequence: Long,
        limit: Int,
        after: OrderFillCursor?,
    ): OrderFillQuerySnapshot =
        try {
            val order = read(marketId, orderId)
            fills.read(order, marketId, minAdmissionSequence, limit, after)
        } catch (error: OrderQueryInconsistentException) {
            throw error
        } catch (error: OrderQueryUnavailableException) {
            throw error
        } catch (error: DataAccessException) {
            throw OrderQueryUnavailableException("order fills database read failed", error)
        } catch (error: Exception) {
            throw OrderQueryInconsistentException("stored order fills are inconsistent", error)
        }

    override fun readLatestSettledTrade(marketId: String): LatestSettledTradeSnapshot? =
        try {
            jdbc
                .query(
                    """
                    select p.*, c.namespace is not null as settlement_enabled,
                        s.settlement_through, t.price, t.quantity, t.occurred_at
                    from order_query.query_progress p
                    left join order_query.feed_consumer c
                        on c.namespace = p.namespace and c.configuration_payload -> 'settlementEnabled' = 'true'::jsonb
                    left join order_query.feed_stream s on s.namespace = c.namespace and s.market_id = :marketId
                    left join lateral (
                        select price, quantity, occurred_at
                        from order_query.settlement_transaction
                        where namespace = s.namespace and market_id = s.market_id and result_sequence <= s.settlement_through
                        order by engine_sequence desc
                        limit 1
                    ) t on true
                    where p.namespace = :namespace
                    """.trimIndent(),
                    namespaceParameters + ("marketId" to marketId),
                ) { rows, _ ->
                    val lifecycle = sourceLifecycle(progress(rows).lifecycle)
                    if (lifecycle != OrderQueryLifecycle.READY || !rows.getBoolean("settlement_enabled")) {
                        return@query LatestSettledTradeSnapshot(lifecycle, settlementThrough = null, trade = null)
                    }

                    val settlementThrough = rows.getObject("settlement_through", Long::class.javaObjectType) ?: inconsistent()
                    val price = rows.getObject("price", Long::class.javaObjectType)
                    val trade =
                        if (price == null) {
                            null
                        } else {
                            LatestSettledTrade(
                                price = price,
                                quantity = rows.getLong("quantity"),
                                settledAt = rows.getTimestamp("occurred_at").toInstant(),
                            )
                        }
                    LatestSettledTradeSnapshot(lifecycle, settlementThrough, trade)
                }.singleOrNull()
        } catch (error: DataAccessException) {
            throw OrderQueryUnavailableException("latest settled trade database read failed", error)
        }

    internal fun lockedProgress(): OrderQueryProgress =
        jdbc
            .query(
                "select * from order_query.query_progress where namespace = :namespace for update",
                namespaceParameters,
            ) { rows, _ -> progress(rows) }
            .singleOrNull()
            ?: throw OrderQueryWriteConflictException("order query namespace is not initialized")

    internal fun progress(rows: ResultSet): OrderQueryProgress =
        stored {
            require(rows.getString("namespace") == namespaceKey)
            require(mapper.readTree(rows.getString("supported_markets")) == mapper.readTree(marketsPayload))
            OrderQueryProgress(
                OrderQueryLifecycle.valueOf(rows.getString("lifecycle")),
                rows.getLong("applied_revision"),
                rows.getLong("observed_revision"),
                rows.getLong("covered_admission_sequence"),
                rows.getTimestamp("updated_at").toInstant(),
            )
        }

    internal fun findRecord(
        marketId: String,
        orderId: String,
    ): OrderQueryRecord? =
        jdbc
            .query(
                "select * from order_query.order_record where namespace = :namespace and market_id = :marketId and order_id = :orderId",
                namespaceParameters + mapOf("marketId" to marketId, "orderId" to orderId),
            ) { rows, _ -> record(rows) }
            .singleOrNull()

    private fun record(rows: ResultSet): OrderQueryRecord =
        stored {
            val payload = mapper.readTree(rows.getString("record_payload"))
            require(payload.isObject && payload.propertyNames().toSet() == RECORD_FIELDS)
            val stream = payload.path("resultStream")
            if (!stream.isNull) require(stream.isObject && stream.propertyNames().toSet() == STREAM_FIELDS)
            val record = mapper.treeToValue(payload, OrderQueryRecord::class.java)
            namespace.requireRecord(record)
            require(record.marketId == rows.getString("market_id"))
            require(record.orderId == rows.getString("order_id"))
            require(record.admissionSequence == rows.getLong("admission_sequence"))
            require(record.marketAdmissionSequence == rows.getLong("market_admission_sequence"))
            record
        }

    /** 원본 receipt·cursor와 기존 정규화 batch를 같은 공용 JDBC 트랜잭션에 참여시킨다. */
    internal fun <T> withTransaction(action: () -> T): T = write(action)

    internal fun recordsAwaitingFinancial(
        marketId: String,
        through: Long,
    ): List<OrderQueryRecord> =
        jdbc.query(
            """
            select * from order_query.order_record
            where namespace = :namespace and market_id = :marketId
                and record_payload ->> 'state' <> 'MATCHING_PENDING'
                and cast(record_payload ->> 'financialThrough' as bigint) <
                    cast(record_payload ->> 'lastAffectedResultSequence' as bigint)
                and cast(record_payload ->> 'lastAffectedResultSequence' as bigint) <= :through
            """.trimIndent(),
            namespaceParameters + mapOf("marketId" to marketId, "through" to through),
        ) { rows, _ -> record(rows) }

    private fun save(record: OrderQueryRecord) {
        jdbc.update(
            """
            insert into order_query.order_record
                (namespace, market_id, order_id, admission_sequence, market_admission_sequence, record_payload)
            values (:namespace, :marketId, :orderId, :admission, :marketAdmission, cast(:payload as jsonb))
            on conflict (namespace, market_id, order_id)
            do update set record_payload = excluded.record_payload, updated_at = current_timestamp
            """.trimIndent(),
            namespaceParameters +
                mapOf(
                    "marketId" to record.marketId,
                    "orderId" to record.orderId,
                    "admission" to record.admissionSequence,
                    "marketAdmission" to record.marketAdmissionSequence,
                    "payload" to mapper.writeValueAsString(recordPayload(record)),
                ),
        )
    }

    private fun batchPayload(batch: OrderQueryBatch): String =
        mapper.writeValueAsString(
            mapOf(
                "expectedRevision" to batch.expectedRevision,
                "coveredAdmissionSequence" to batch.coveredAdmissionSequence,
                "records" to batch.records.sortedWith(compareBy({ it.marketId }, { it.orderId })).map(::recordPayload),
            ),
        )

    // 생성자 필드만 저장한다. orderId·금전 상태 같은 파생 getter를 역직렬화하며 무시하지 않는다.
    private fun recordPayload(record: OrderQueryRecord): Map<String, Any?> =
        mapOf(
            "fundsLineage" to record.fundsLineage,
            "marketId" to record.marketId,
            "userId" to record.userId,
            "side" to record.side,
            "orderType" to record.orderType,
            "timeInForce" to record.timeInForce,
            "price" to record.price,
            "initialQuantity" to record.initialQuantity,
            "admissionSequence" to record.admissionSequence,
            "marketAdmissionSequence" to record.marketAdmissionSequence,
            "filledQuantity" to record.filledQuantity,
            "remainingQuantity" to record.remainingQuantity,
            "cancelledQuantity" to record.cancelledQuantity,
            "notExecutedQuantity" to record.notExecutedQuantity,
            "state" to record.state,
            "resultStream" to
                record.resultStream?.let {
                    mapOf(
                        "matchingLineage" to it.matchingLineage,
                        "fundsLineage" to it.fundsLineage,
                        "marketId" to it.marketId.value,
                        "ownershipEpoch" to it.ownershipEpoch,
                        "configurationVersion" to it.configurationVersion,
                    )
                },
            "lastAffectedResultSequence" to record.lastAffectedResultSequence,
            "financialThrough" to record.financialThrough,
        )

    private fun <T> write(action: () -> T): T =
        try {
            transaction.execute { action() }!!
        } catch (error: OrderQueryWriteConflictException) {
            throw error
        } catch (error: OrderQueryInconsistentException) {
            throw error
        } catch (_: DataIntegrityViolationException) {
            throw OrderQueryWriteConflictException("order query identity conflicts with stored data")
        } catch (_: DataAccessException) {
            throw OrderQueryUnavailableException("order query database write failed")
        } catch (_: IllegalArgumentException) {
            throw OrderQueryWriteConflictException("order query batch violates stored state")
        } catch (_: ArithmeticException) {
            throw OrderQueryWriteConflictException("order query revision exceeds its range")
        }

    private fun <T> stored(action: () -> T): T =
        try {
            action()
        } catch (_: Exception) {
            inconsistent()
        }

    private fun inconsistent(): Nothing = throw OrderQueryInconsistentException("stored order query state is inconsistent")

    private companion object {
        val RECORD_FIELDS =
            setOf(
                "fundsLineage",
                "marketId",
                "userId",
                "side",
                "orderType",
                "timeInForce",
                "price",
                "initialQuantity",
                "admissionSequence",
                "marketAdmissionSequence",
                "filledQuantity",
                "remainingQuantity",
                "cancelledQuantity",
                "notExecutedQuantity",
                "state",
                "resultStream",
                "lastAffectedResultSequence",
                "financialThrough",
            )
        val STREAM_FIELDS = setOf("matchingLineage", "fundsLineage", "marketId", "ownershipEpoch", "configurationVersion")
    }
}

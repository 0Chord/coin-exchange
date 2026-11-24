package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.OrderChargedFee
import com.exchange.core.api.trading.application.OrderFillCursor
import com.exchange.core.api.trading.application.OrderFillQuerySnapshot
import com.exchange.core.api.trading.application.OrderFillRole
import com.exchange.core.api.trading.application.OrderFillSettlementStatus
import com.exchange.core.api.trading.application.OrderQueryCode
import com.exchange.core.api.trading.application.OrderQueryFinancialStatus
import com.exchange.core.api.trading.application.OrderQueryFreshness
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryOrderState
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryLedgerException
import com.exchange.core.api.trading.application.port.OrderQueryOriginsPendingException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide.CREDIT
import com.exchange.core.ledger.LedgerPostingSide.DEBIT
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsCancelDispatch
import com.exchange.core.trading.FundsDispatchStream
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.SettledTrade
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresOrderQueryFeedStoreTest {
    private val database = PostgreSQLContainer("postgres:16-alpine")
    private val identities = AtomicLong(1_000)
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var migratedFillFixture: Fixture
    private lateinit var migratedLedgerFixture: Fixture

    @BeforeAll
    fun start() {
        database.start()
        jdbc = NamedParameterJdbcTemplate(dataSource())
        val migrations = Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")
        migrations.target("12").load().migrate()
        migratedFillFixture = storeFillHistoryBeforeUpgrade()
        migrations.target("15").load().migrate()
        migratedLedgerFixture = fixture()
        migratedLedgerFixture.feed.applyLedgerPage(listOf(migratedLedgerFixture.ledgerBatch(1)))
        migrations.target(MigrationVersion.LATEST).load().migrate()
    }

    @AfterAll
    fun stop() = database.stop()

    @Test
    fun `조회 인덱스 추가 뒤 기존 원문에서 같은 결과의 체결을 순서대로 조회한다`() {
        val f = migratedFillFixture.reopen()
        val firstMaker = f.input(1, side = Side.SELL, quantity = 1)
        val secondMaker = f.input(2, side = Side.SELL)
        val taker = f.input(3, quantity = 2)
        val progressBeforeRead = f.feed.progress()

        val waiting =
            OrderQueryUseCase(f.query).getFills(
                taker.order.marketId.value,
                taker.order.orderId.value,
                minAdmissionSequence = 4,
                limit = 1,
                after = null,
            )
        assertEquals(OrderQueryCode.QUERY_CATCHING_UP, waiting.code)

        val firstPage = f.readFills(taker, limit = 1)
        val first = firstPage.fills.single()
        val next = assertNotNull(firstPage.nextCursor)
        assertEquals(3L, next.resultSequence)
        assertEquals(OrderFillRole.TAKER, first.role)
        assertEquals(OrderFillSettlementStatus.COMPLETED, first.settlementStatus)
        assertEquals(100L, first.price)
        assertEquals(1L, first.quantity)
        val makerFee = OrderChargedFee(amount = "1", assetId = "QUOTE")
        val takerFee = OrderChargedFee(amount = "2", assetId = "QUOTE")
        val total = OrderChargedFee(amount = "4", assetId = "QUOTE")
        assertEquals(takerFee, first.chargedFee)
        assertEquals(total, firstPage.settledFeeTotal)
        val makerPage = f.readFills(firstMaker)
        assertEquals(first.copy(role = OrderFillRole.MAKER, chargedFee = makerFee), makerPage.fills.single())
        assertEquals(makerFee, makerPage.settledFeeTotal)

        val secondPage = f.readFills(taker, limit = 1, after = next)
        val second = secondPage.fills.single()
        assertNull(secondPage.nextCursor)
        assertEquals(OrderFillRole.TAKER, second.role)
        assertEquals(OrderFillSettlementStatus.COMPLETED, second.settlementStatus)
        assertEquals(100L, second.price)
        assertEquals(1L, second.quantity)
        assertEquals(takerFee, second.chargedFee)
        assertEquals(total, secondPage.settledFeeTotal)
        val cancelledMakerPage = f.readFills(secondMaker)
        assertEquals(OrderQueryOrderState.CANCELLED, cancelledMakerPage.order.record?.state)
        assertEquals(second.copy(role = OrderFillRole.MAKER, chargedFee = makerFee), cancelledMakerPage.fills.single())
        assertEquals(makerFee, cancelledMakerPage.settledFeeTotal)
        val allFills = f.readFills(taker)
        assertEquals(listOf(first, second), allFills.fills)
        assertEquals(total, allFills.settledFeeTotal)

        val ledgerIds =
            jdbc.queryForList(
                "select settlement_id from order_query.settlement_transaction where namespace = :namespace order by engine_sequence",
                f.parameters,
                String::class.java,
            )
        assertEquals(2, ledgerIds.distinct().size)
        assertEquals(ledgerIds, listOf(first.tradeId, second.tradeId))
        assertEquals(progressBeforeRead, f.feed.progress())
    }

    @Test
    fun `기존 매칭 범위보다 앞서 저장된 체결은 조회에 반영된 뒤 합계에 들어간다`() {
        val f = fixture(settlementEnabled = true, fees = MakerTakerFeeRates(FeeRate(10_000), FeeRate(20_000)))
        val firstMaker = f.input(1, side = Side.SELL, quantity = 1)
        val buy = f.input(2, quantity = 4)
        f.admit(firstMaker)
        val resting = assertNotNull(f.owner.apply(firstMaker).result)
        f.match(resting)
        f.feed.applySettlement(f.settlement(resting))
        f.admit(buy)
        val partial = assertNotNull(f.owner.apply(buy).result)
        f.match(partial)
        f.feed.applySettlement(f.settlement(partial, makerFee = 1, takerFee = 2))
        f.verify(admissions = 2, resultsA = 2)

        val followUpSale = f.input(3, side = Side.SELL, quantity = 2)
        f.admit(followUpSale)
        val followUpMatch = assertNotNull(f.owner.apply(followUpSale).result)
        f.feed.applySettlement(f.settlement(followUpMatch, makerFee = 2, takerFee = 4))
        val beforeProjection = f.readFills(buy)
        assertEquals(1, beforeProjection.fills.size)
        assertEquals(OrderChargedFee(amount = "2", assetId = "QUOTE"), beforeProjection.settledFeeTotal)
        val pendingSale = f.readFills(followUpSale)
        assertTrue(pendingSale.fills.isEmpty())
        assertNull(pendingSale.settledFeeTotal)

        f.match(followUpMatch)
        val firstPage = f.readFills(buy, limit = 1)
        assertNotNull(firstPage.nextCursor)
        assertEquals(OrderChargedFee(amount = "4", assetId = "QUOTE"), firstPage.settledFeeTotal)
        assertEquals(OrderChargedFee(amount = "4", assetId = "QUOTE"), f.readFills(followUpSale).settledFeeTotal)

        val lastSale = f.input(4, side = Side.SELL, quantity = 1)
        f.admit(lastSale)
        val lastMatch = assertNotNull(f.owner.apply(lastSale).result)
        f.match(lastMatch)
        val withPendingFill = f.readFills(buy)
        assertEquals(3, withPendingFill.fills.size)
        assertEquals(OrderFillSettlementStatus.PENDING, withPendingFill.fills.last().settlementStatus)
        assertEquals(firstPage.settledFeeTotal, withPendingFill.settledFeeTotal)

        val resultParameters = f.parameters + ("result" to followUpMatch.resultSequence)
        val transaction = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.jdbcTemplate.dataSource)))
        transaction.executeWithoutResult { status ->
            val removed =
                jdbc.update(
                    """
                    delete from order_query.applied_batch b
                    using order_query.source_receipt r
                    where b.namespace = r.namespace and b.first_revision = r.first_revision
                        and r.namespace = :namespace and r.source_kind = 'MATCHING' and r.source_sequence = :result
                    """.trimIndent(),
                    resultParameters,
                )
            assertEquals(1, removed)
            assertFailsWith<OrderQueryInconsistentException> { f.readFills(buy, limit = 1) }
            status.setRollbackOnly()
        }
        transaction.executeWithoutResult { status ->
            val removed =
                jdbc.update(
                    """
                    delete from order_query.source_receipt
                    where namespace = :namespace and source_kind = 'MATCHING' and source_sequence = :result
                    """.trimIndent(),
                    resultParameters,
                )
            assertEquals(1, removed)
            assertFailsWith<OrderQueryInconsistentException> { f.readFills(buy, limit = 1) }
            status.setRollbackOnly()
        }
        assertEquals(firstPage.settledFeeTotal, f.readFills(buy, limit = 1).settledFeeTotal)

        val followUpTrade = followUpMatch.events.filterIsInstance<TradeExecuted>().single()
        val tradeId = TradingIdentifiers.settlement(f.namespace.fundsLineage, f.a.ordinal, followUpTrade.engineSequence)
        val parameters = f.parameters + ("tradeId" to tradeId)
        jdbc.update("delete from order_query.settlement_posting where namespace = :namespace and settlement_id = :tradeId", parameters)
        jdbc.update("delete from order_query.settlement_transaction where namespace = :namespace and settlement_id = :tradeId", parameters)
        // 세 번째 체결이 정산 대기 중이어도 저장 순번에 포함된 두 번째 체결은 합계에 필요하다.
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(buy, limit = 1) }
    }

    @Test
    fun `한 사용자의 별도 주문 사이에서 페이지 밖 정산 귀속이 바뀌면 합계를 거절한다`() {
        val f = fixture(settlementEnabled = true, fees = MakerTakerFeeRates(FeeRate(10_000), FeeRate(20_000)))
        val owner = UserId("same-buyer")
        val firstSale = f.input(1, side = Side.SELL, quantity = 1)
        val firstBuy = f.input(2, quantity = 2, userId = owner)
        val otherBuy = f.input(3, quantity = 1, userId = owner)
        for (order in listOf(firstSale, firstBuy, otherBuy)) {
            f.admit(order)
            val result = assertNotNull(f.owner.apply(order).result)
            f.match(result)
            f.feed.applySettlement(f.settlement(result, makerFee = 1, takerFee = 2))
        }

        val crossingSale = f.input(4, side = Side.SELL, quantity = 2)
        f.admit(crossingSale)
        val crossed = assertNotNull(f.owner.apply(crossingSale).result)
        f.match(crossed)
        val firstFeeFact = f.settlement(crossed, makerFee = 1, takerFee = 2)
        val otherFeeFact = f.settlement(crossed, makerFee = 2, takerFee = 2)
        val firstFill = firstFeeFact.fills.single { it.trade.makerOrderId == firstBuy.order.orderId }
        val otherFill = otherFeeFact.fills.single { it.trade.makerOrderId == otherBuy.order.orderId }
        assertEquals(1L, firstFill.trade.quantity.value)
        assertEquals(1L, otherFill.trade.quantity.value)
        f.feed.applySettlement(SettlementFact(crossed.stream, crossed.resultSequence, listOf(firstFill, otherFill)))
        f.verify(admissions = 4, resultsA = 4)

        val firstPage = f.readFills(firstBuy, limit = 1)
        assertEquals(OrderFillRole.TAKER, firstPage.fills.single().role)
        assertNotNull(firstPage.nextCursor)
        assertEquals(OrderChargedFee(amount = "3", assetId = "QUOTE"), firstPage.settledFeeTotal)
        assertEquals(OrderChargedFee(amount = "2", assetId = "QUOTE"), f.readFills(otherBuy).settledFeeTotal)

        val exchangedOrders =
            jdbc.update(
                """
                update order_query.settlement_transaction
                set maker_order_id = case when maker_order_id = :firstBuy then :otherBuy else :firstBuy end
                where namespace = :namespace and result_sequence = :resultSequence
                    and maker_order_id in (:firstBuy, :otherBuy)
                """.trimIndent(),
                f.parameters +
                    mapOf(
                        "firstBuy" to firstBuy.order.orderId.value,
                        "otherBuy" to otherBuy.order.orderId.value,
                        "resultSequence" to crossed.resultSequence,
                    ),
            )
        assertEquals(2, exchangedOrders)
        // 두 정산의 사용자·수량은 같으므로 체결별 원본에서 주문 귀속을 대조해야 한다.
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(firstBuy, limit = 1) }
    }

    @Test
    fun `체결 조회는 외부 트랜잭션과 분리되어 저장 전 시점을 유지한다`() {
        val f = fixture(settlementEnabled = true)
        val maker = f.input(1, side = Side.SELL, quantity = 1)
        val taker = f.input(2, quantity = 1)
        f.admit(maker)
        f.admit(taker)
        val resting = assertNotNull(f.owner.apply(maker).result)
        val matched = assertNotNull(f.owner.apply(taker).result)
        f.match(resting)
        f.match(matched)
        f.feed.applySettlement(f.settlement(resting))
        f.verify(2, 2)

        val transactionManager = DataSourceTransactionManager(requireNotNull(jdbc.jdbcTemplate.dataSource))
        val outerTransaction =
            TransactionTemplate(transactionManager).apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
        val settlement = f.settlement(matched)
        val marketId = taker.order.marketId.value
        val orderId = taker.order.orderId.value
        val settings =
            """
            select pg_backend_pid() as backend, current_setting('transaction_isolation') as isolation,
                current_setting('transaction_read_only') as read_only
            """.trimIndent()

        fun advisedQuery(store: OrderQueryStore): OrderQueryUseCase {
            val advice =
                TransactionInterceptor().apply {
                    setTransactionManager(transactionManager)
                    setTransactionAttributeSource(AnnotationTransactionAttributeSource())
                }
            val factory = ProxyFactory(OrderQueryUseCase(store))
            factory.addAdvice(advice)
            return factory.proxy as OrderQueryUseCase
        }

        val firstRead =
            outerTransaction.execute {
                val before = jdbc.jdbcTemplate.queryForMap(settings)
                assertEquals("read committed", before["isolation"])
                val observedStore =
                    object : OrderQueryStore by f.query {
                        override fun readFills(
                            marketId: String,
                            orderId: String,
                            minAdmissionSequence: Long,
                            limit: Int,
                            after: OrderFillCursor?,
                        ): OrderFillQuerySnapshot {
                            val active = jdbc.jdbcTemplate.queryForMap(settings)
                            assertEquals("repeatable read", active["isolation"])
                            assertEquals("on", active["read_only"])
                            assertNotEquals(before["backend"], active["backend"])
                            // 정산 저장 전에 읽기 시점을 잡고, 다른 연결에서 정산을 커밋한다.
                            assertEquals(0L, f.settlementTransactionCount())
                            Executors.newSingleThreadExecutor().use { writer ->
                                val task = writer.submit(Callable { f.feed.applySettlement(settlement) })
                                assertEquals(matched.resultSequence, task.get(10, TimeUnit.SECONDS))
                            }
                            return f.query.readFills(marketId, orderId, minAdmissionSequence, limit, after)
                        }
                    }
                val useCase = advisedQuery(observedStore)
                val result = useCase.getFills(marketId, orderId, minAdmissionSequence = 0, limit = 50, after = null)
                assertEquals(before, jdbc.jdbcTemplate.queryForMap(settings))
                assertEquals(OrderQueryCode.ORDER_FOUND, result.code)
                assertNull(result.settledFeeTotal)
                result.fills.single()
            }
        val pending = assertNotNull(firstRead)
        assertEquals(OrderFillSettlementStatus.PENDING, pending.settlementStatus)
        assertNull(pending.chargedFee)

        val useCase = advisedQuery(f.query)
        val afterCommit = useCase.getFills(marketId, orderId, minAdmissionSequence = 0, limit = 50, after = null)
        assertEquals(OrderQueryCode.ORDER_FOUND, afterCommit.code)
        val zero = OrderChargedFee(amount = "0", assetId = "QUOTE")
        assertEquals(pending.copy(settlementStatus = OrderFillSettlementStatus.COMPLETED, chargedFee = zero), afterCommit.fills.single())
        assertEquals(zero, afterCommit.settledFeeTotal)
    }

    @Test
    fun `정산 저장 순번이 체결을 포함하면 누락되거나 다른 정산 행을 거절한다`() {
        val f = fixture(settlementEnabled = true)
        val maker = f.input(1, side = Side.SELL, quantity = 1)
        val taker = f.input(2, quantity = 1)
        f.admit(maker)
        f.admit(taker)
        val resting = assertNotNull(f.owner.apply(maker).result)
        val matched = assertNotNull(f.owner.apply(taker).result)
        f.match(resting)
        f.match(matched)
        f.feed.applySettlement(f.settlement(resting))
        f.verify(2, 2)
        val pendingFill = f.readFills(taker).fills.single()
        assertEquals(OrderFillSettlementStatus.PENDING, pendingFill.settlementStatus)

        val settlement = f.settlement(matched)
        f.feed.applySettlement(settlement)
        val completed = f.readFills(taker).fills.single()
        assertEquals(OrderFillSettlementStatus.COMPLETED, completed.settlementStatus)
        val changeCursor = "update order_query.feed_stream set settlement_through = :through where namespace = :namespace"
        jdbc.update(changeCursor, f.parameters + ("through" to 1L))
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(taker) }
        jdbc.update(changeCursor, f.parameters + ("through" to 2L))
        jdbc.update(
            "update order_query.settlement_transaction set source_event_id = 'incorrect-source' where namespace = :namespace",
            f.parameters,
        )
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(taker) }
        val settledTrade = settlement.fills.single()
        jdbc.update(
            "update order_query.settlement_transaction set source_event_id = :sourceEventId where namespace = :namespace",
            f.parameters + ("sourceEventId" to settledTrade.transaction.sourceEventId),
        )
        val changeAsset = "update order_query.settlement_transaction set quote_asset_id = :asset where namespace = :namespace"
        jdbc.update(changeAsset, f.parameters + ("asset" to "WRONG"))
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(taker) }
        jdbc.update(changeAsset, f.parameters + ("asset" to "QUOTE"))
        jdbc.update("update order_query.settlement_transaction set quantity = 2 where namespace = :namespace", f.parameters)
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(taker) }
        jdbc.update("delete from order_query.settlement_posting where namespace = :namespace", f.parameters)
        jdbc.update("delete from order_query.settlement_transaction where namespace = :namespace", f.parameters)
        assertFailsWith<OrderQueryInconsistentException> { f.readFills(taker) }
    }

    @Test
    fun `정산 저장을 사용하지 않는 계보는 주문 조회를 제공하고 체결 조회 근거는 준비되지 않는다`() {
        val f = fixture()
        val maker = f.input(1, side = Side.SELL, quantity = 1)
        val taker = f.input(2, quantity = 1)
        f.admit(maker)
        f.admit(taker)
        f.match(assertNotNull(f.owner.apply(maker).result))
        f.match(assertNotNull(f.owner.apply(taker).result))
        f.verify(2, 2)

        val useCase = OrderQueryUseCase(f.query)
        val marketId = taker.order.marketId.value
        val orderId = taker.order.orderId.value
        val order = useCase.get(marketId, orderId)
        assertEquals(OrderQueryCode.ORDER_FOUND, order.code)
        assertEquals(OrderQueryOrderState.FILLED, order.record?.state)
        val fills = useCase.getFills(marketId, orderId, minAdmissionSequence = 0, limit = 50, after = null)
        assertEquals(OrderQueryCode.QUERY_NOT_READY, fills.code)
        assertTrue(fills.fills.isEmpty())
    }

    @Test
    fun `실제 매칭 원문으로 양쪽 주문과 취소 반환을 저장하고 재개방 중복에도 최초 결과를 보존한다`() {
        val f = fixture()
        val maker = f.input(1, side = Side.SELL)
        val taker = f.input(2, quantity = 1)
        f.verify(2, 3)
        assertEquals(1L, f.admit(maker))
        assertNull(f.read(maker).record!!.remainingQuantity)
        assertEquals(2L, f.match(f.owner.apply(maker).result!!))
        assertEquals(3L, f.admit(taker))
        val partial = f.owner.apply(taker).result!!
        assertEquals(4L, f.match(partial))
        assertEquals(1L, f.read(maker).record!!.filledQuantity)
        assertEquals(2L, f.read(maker).record!!.remainingQuantity)
        assertEquals(OrderQueryOrderState.FILLED, f.read(taker).record!!.state)
        val cancelled = f.owner.apply(f.cancel(maker, 3)).result!!
        assertEquals(5L, f.match(cancelled))
        val before = f.read(maker).record!!
        assertEquals(OrderQueryOrderState.CANCELLED, before.state)
        assertEquals(1L, before.filledQuantity)
        assertEquals(0L, before.remainingQuantity)
        assertEquals(2L, before.cancelledQuantity)
        assertEquals(OrderQueryFinancialStatus.PENDING, before.financialStatus)
        assertEquals(6L, f.feed.applyFinancial(f.watermark(3)))
        assertEquals(
            1L,
            f.feed
                .progress()
                .financialThrough
                .getValue(f.a.market.marketId.value),
        )
        assertEquals(7L, f.feed.applyFinancial(f.watermark(3)))
        assertEquals(8L, f.feed.applyFinancial(f.watermark(3)))
        f.verify(2, 3)
        val saved = f.read(maker)
        assertEquals(OrderQueryLifecycle.READY, saved.progress!!.lifecycle)
        assertEquals(OrderQueryFreshness.CAUGHT_UP, saved.progress!!.freshness)
        assertEquals(OrderQueryFinancialStatus.APPLIED, saved.record!!.financialStatus)
        val reopened = f.reopen()
        assertEquals(saved, reopened.read(maker))
        assertEquals(1L, reopened.admit(maker))
        assertEquals(4L, reopened.match(partial))
        assertEquals(5L, reopened.match(cancelled))
        assertEquals(saved, reopened.read(maker))
        assertEquals(
            3L,
            reopened.feed
                .progress()
                .matchingThrough
                .getValue(f.a.market.marketId.value),
        )
        assertContentEquals(f.resultBytes(partial), f.raw("MATCHING", f.a.market.marketId.value, 2))
        assertContentEquals(f.admissionBytes(maker), f.raw("ADMISSION", "funds", 1))
    }

    @Test
    fun `먼저 받은 정산 위치는 주문을 만들지 않고 늦은 매칭의 금융 상태를 되돌리지 않는다`() {
        val f = fixture()
        f.verify(1, 1)
        f.feed.applyFinancial(f.watermark(1))
        val input = f.input(1)
        assertNull(f.read(input).record)
        f.admit(input)
        assertEquals(OrderQueryOrderState.MATCHING_PENDING, f.read(input).record!!.state)
        f.match(f.owner.apply(input).result!!)
        val saved = f.read(input)
        assertEquals(OrderQueryFinancialStatus.APPLIED, saved.record!!.financialStatus)
        assertEquals(1L, saved.record!!.financialThrough)
        assertEquals(3L, saved.progress!!.appliedRevision)
        assertEquals(3L, f.feed.applyFinancial(f.watermark(0)))
        assertEquals(saved, f.read(input))
    }

    @Test
    fun `정산 cursor 뒤의 같은 결과 주문만 갱신하고 이미 완료된 과거 행은 다시 쓰지 않는다`() {
        val f = fixture()
        f.verify(2, 2)
        val maker = f.input(1, side = Side.SELL)
        val taker = f.input(2)
        f.admit(maker)
        f.match(f.owner.apply(maker).result!!)
        f.admit(taker)
        f.match(f.owner.apply(taker).result!!)
        f.feed.applyFinancial(f.watermark(2))
        assertEquals(
            1L,
            f.feed
                .progress()
                .financialThrough
                .getValue(f.a.market.marketId.value),
        )
        assertEquals(OrderQueryFinancialStatus.PENDING, f.read(maker).record!!.financialStatus)
        assertEquals(OrderQueryFinancialStatus.PENDING, f.read(taker).record!!.financialStatus)
        f.feed.applyFinancial(f.watermark(2))
        val makerApplied = f.read(maker).record
        val takerApplied = f.read(taker).record
        val makerUpdated = f.updatedAt(maker)
        val takerUpdated = f.updatedAt(taker)
        assertEquals(OrderQueryFinancialStatus.APPLIED, makerApplied!!.financialStatus)
        assertEquals(OrderQueryFinancialStatus.APPLIED, takerApplied!!.financialStatus)
        val later = f.input(3)
        f.admit(later)
        f.match(f.owner.apply(later).result!!)
        f.feed.applyFinancial(f.watermark(3))
        assertEquals(OrderQueryFinancialStatus.APPLIED, f.read(later).record!!.financialStatus)
        assertEquals(makerApplied, f.read(maker).record)
        assertEquals(takerApplied, f.read(taker).record)
        assertEquals(makerUpdated, f.updatedAt(maker))
        assertEquals(takerUpdated, f.updatedAt(taker))
        assertEquals(
            3L,
            f.feed
                .progress()
                .financialThrough
                .getValue(f.a.market.marketId.value),
        )
    }

    @Test
    fun `접수와 매칭을 따라잡아도 확인된 정산 backlog가 남으면 LAGGING을 유지한다`() {
        val f = fixture()
        f.verify(0, 0)
        val input = f.input(1)
        f.admit(input)
        f.match(f.owner.apply(input).result!!)
        val heads = f.streams.mapValues { if (it.key == f.a.market.marketId.value) 1L else 0L }
        f.feed.verifySources(bounds(1), f.streams.mapValues { bounds(heads.getValue(it.key)) }, heads)
        val lagging = f.read(input)
        assertEquals(OrderQueryLifecycle.READY, lagging.progress!!.lifecycle)
        assertEquals(OrderQueryFreshness.LAGGING, lagging.progress!!.freshness)
        assertEquals(3L, lagging.progress!!.observedRevision)
        f.feed.applyFinancial(f.watermark(1))
        f.feed.verifySources(bounds(1), f.streams.mapValues { bounds(heads.getValue(it.key)) }, heads)
        assertEquals(OrderQueryFreshness.CAUGHT_UP, f.read(input).progress!!.freshness)
    }

    @Test
    fun `매칭의 접수 원문이 없으면 전체 결과와 cursor를 저장하지 않고 같은 원문으로 재개한다`() {
        val f = fixture()
        f.verify(2, 2)
        val maker = f.input(1, side = Side.SELL)
        val taker = f.input(2, quantity = 1)
        f.admit(maker)
        f.match(f.owner.apply(maker).result!!)
        val partial = f.owner.apply(taker).result!!
        val previous = f.feed.progress()
        val waiting = assertFailsWith<OrderQueryOriginsPendingException> { f.match(partial) }
        assertEquals(2L, waiting.admissionSequence)
        assertEquals(previous, f.feed.progress())
        assertEquals(3L, f.read(maker).record!!.remainingQuantity)
        assertEquals(0L, f.receiptCount("MATCHING", 2))
        f.admit(taker)
        f.match(partial)
        assertEquals(2L, f.read(maker).record!!.remainingQuantity)
        assertEquals(1L, f.read(taker).record!!.filledQuantity)
    }

    @Test
    fun `중간 SQL 실패는 maker taker 원문 revision cursor를 모두 롤백하고 재시도로 한 번만 반영한다`() {
        val f = fixture()
        f.verify(2, 2)
        val maker = f.input(1, side = Side.SELL)
        val taker = f.input(2, quantity = 1)
        f.admit(maker)
        f.admit(taker)
        f.match(f.owner.apply(maker).result!!)
        val partial = f.owner.apply(taker).result!!
        val previousMaker = f.read(maker)
        val previousTaker = f.read(taker)
        val previous = f.feed.progress()
        // 주문 행 저장 뒤 실제 PostgreSQL 오류가 나면 원본 트랜잭션 전체가 롤백돼야 한다.
        jdbc.jdbcTemplate.execute(
            """
            create function order_query.reject_feed_receipt() returns trigger language plpgsql as '
            begin raise exception ''injected source receipt failure''; end';
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute(
            "create trigger reject_feed_receipt before insert on order_query.source_receipt " +
                "for each row execute function order_query.reject_feed_receipt()",
        )
        try {
            assertFailsWith<OrderQueryUnavailableException> { f.match(partial) }
        } finally {
            jdbc.jdbcTemplate.execute("drop trigger reject_feed_receipt on order_query.source_receipt")
            jdbc.jdbcTemplate.execute("drop function order_query.reject_feed_receipt()")
        }
        assertEquals(previous, f.feed.progress())
        assertEquals(previousMaker, f.read(maker))
        assertEquals(previousTaker, f.read(taker))
        assertEquals(0L, f.receiptCount("MATCHING", 2))
        assertEquals(previous.appliedRevision + 1, f.match(partial))
        assertEquals(2L, f.read(maker).record!!.remainingQuantity)
        assertEquals(1L, f.read(taker).record!!.filledQuantity)
    }

    @Test
    fun `두 번째 체결의 분개 저장 실패는 두 체결과 저장 위치를 함께 롤백한다`() {
        val f = fixture(settlementEnabled = true)
        val firstMaker = f.owner.apply(f.input(1, side = Side.SELL, quantity = 1)).result!!
        val secondMaker = f.owner.apply(f.input(2, side = Side.SELL, quantity = 1)).result!!
        val result = f.owner.apply(f.input(3, quantity = 2)).result!!
        val market = result.stream.marketId.value

        f.feed.applySettlement(SettlementFact(firstMaker.stream, firstMaker.resultSequence, emptyList()))
        f.feed.applySettlement(SettlementFact(secondMaker.stream, secondMaker.resultSequence, emptyList()))
        val progressBeforeWrite = f.feed.progress()
        assertEquals(2L, progressBeforeWrite.settlementThrough.getValue(market))
        assertEquals(0L, f.settlementTransactionCount())

        val settled = f.settlement(result)
        assertEquals(2, settled.fills.size)
        val secondFill = settled.fills.last()
        val secondSettlementId = secondFill.transaction.ledgerTransactionId
        jdbc.jdbcTemplate.execute(
            """
            create function order_query.reject_settlement_posting() returns trigger language plpgsql as '
            begin raise exception ''injected settlement posting failure''; end';
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute(
            """
            create trigger reject_settlement_posting before insert on order_query.settlement_posting
            for each row when (new.settlement_id = '$secondSettlementId' and new.posting_sequence = 2)
            execute function order_query.reject_settlement_posting()
            """.trimIndent(),
        )
        try {
            assertFailsWith<OrderQueryUnavailableException> { f.feed.applySettlement(settled) }
        } finally {
            jdbc.jdbcTemplate.execute("drop trigger reject_settlement_posting on order_query.settlement_posting")
            jdbc.jdbcTemplate.execute("drop function order_query.reject_settlement_posting()")
        }
        val progressAfterFailure = f.feed.progress()
        assertEquals(2L, progressAfterFailure.settlementThrough.getValue(market))
        assertEquals(0L, f.settlementTransactionCount())
        assertEquals(0L, f.settlementPostingCount())

        assertEquals(3L, f.feed.applySettlement(settled))
        val reopened = f.reopen()
        val progressAfterRetry = reopened.feed.progress()
        assertEquals(3L, progressAfterRetry.settlementThrough.getValue(market))
        assertEquals(2L, f.settlementTransactionCount())
        assertEquals(8L, f.settlementPostingCount())
        assertEquals(0L, progressAfterRetry.financialThrough.getValue(market))
    }

    @Test
    fun `새 정산 결과의 두 번째 체결 ID가 충돌하면 첫 체결과 저장 위치도 롤백한다`() {
        val f = fixture(settlementEnabled = true)
        val firstMaker = f.owner.apply(f.input(1, side = Side.SELL, quantity = 1)).result!!
        val secondMaker = f.owner.apply(f.input(2, side = Side.SELL, quantity = 1)).result!!
        val result = f.owner.apply(f.input(3, quantity = 2)).result!!
        f.feed.applySettlement(SettlementFact(firstMaker.stream, firstMaker.resultSequence, emptyList()))
        f.feed.applySettlement(SettlementFact(secondMaker.stream, secondMaker.resultSequence, emptyList()))

        val settled = f.settlement(result)
        assertEquals(2, settled.fills.size)
        val storedFill = settled.fills.last()
        f.feed.applySettlement(SettlementFact(result.stream, result.resultSequence, listOf(storedFill)))

        val conflicting = SettlementFact(result.stream, result.resultSequence + 1, settled.fills)
        assertFailsWith<OrderQueryWriteConflictException> { f.feed.applySettlement(conflicting) }
        val progress = f.reopen().feed.progress()
        assertEquals(OrderQueryLifecycle.BLOCKED, progress.lifecycle)
        assertEquals(result.resultSequence, progress.settlementThrough.getValue(result.stream.marketId.value))
        assertEquals(1L, f.settlementTransactionCount())
        assertEquals(4L, f.settlementPostingCount())
    }

    @Test
    fun `같은 정산 원문의 동시 재시도는 한 건을 남기고 최초 시각이 바뀌면 차단한다`() {
        val f = fixture(settlementEnabled = true)
        val maker = f.owner.apply(f.input(1, side = Side.SELL, quantity = 1)).result!!
        val result = f.owner.apply(f.input(2, quantity = 1)).result!!
        f.feed.applySettlement(SettlementFact(maker.stream, maker.resultSequence, emptyList()))
        val settled = f.settlement(result)
        val sameSequence =
            Executors.newFixedThreadPool(2).use { pool ->
                List(2) { pool.submit(Callable { f.reopen().feed.applySettlement(settled) }) }.map { it.get() }
            }
        assertEquals(listOf(2L, 2L), sameSequence)
        assertEquals(1L, f.settlementTransactionCount())
        assertEquals(4L, f.settlementPostingCount())

        val fill = settled.fills.single()
        val conflicting = f.settlement(result, occurredAt = fill.transaction.occurredAt.plusMillis(1))
        assertFailsWith<OrderQueryWriteConflictException> { f.reopen().feed.applySettlement(conflicting) }
        val blockedProgress = f.feed.progress()
        val market = result.stream.marketId.value
        assertEquals(OrderQueryLifecycle.BLOCKED, blockedProgress.lifecycle)
        assertEquals(2L, blockedProgress.settlementThrough.getValue(market))
        assertEquals(1L, f.settlementTransactionCount())
        assertEquals(4L, f.settlementPostingCount())
    }

    @Test
    fun `동일 원문 동시 재시도는 최초 revision 하나를 공유하고 다른 원문은 지속적으로 차단한다`() {
        val f = fixture()
        f.verify(1, 0)
        val input = f.input(1)
        val revisions =
            Executors.newFixedThreadPool(2).use { pool ->
                List(2) { pool.submit(Callable { f.reopen().admit(input) }) }.map { it.get() }
            }
        assertEquals(listOf(1L, 1L), revisions)
        val saved = f.read(input).record
        assertEquals(1L, f.receiptCount("ADMISSION", 1))
        assertFailsWith<OrderQueryWriteConflictException> {
            f.feed.applyAdmission(input, f.admissionBytes(input) + byteArrayOf(1))
        }
        assertEquals(OrderQueryLifecycle.BLOCKED, f.feed.progress().lifecycle)
        assertEquals(saved, f.read(input).record)
        assertEquals(1L, f.reopen().admit(input))
        assertEquals(
            OrderQueryLifecycle.BLOCKED,
            f
                .reopen()
                .feed
                .progress()
                .lifecycle,
        )
    }

    @Test
    fun `새 DB에서 genesis 없는 원본과 이미 회수된 공백은 준비 완료로 바꾸지 않는다`() {
        val absent = fixture()
        assertFailsWith<OrderQueryWriteConflictException> {
            absent.feed.verifySources(bounds(0, genesis = false), absent.streams.mapValues { bounds(0, genesis = false) })
        }
        assertEquals(OrderQueryLifecycle.BLOCKED, absent.feed.progress().lifecycle)
        val pruned = fixture()
        assertFailsWith<OrderQueryWriteConflictException> {
            pruned.feed.verifySources(bounds(2, retired = 1), pruned.streams.mapValues { bounds(0) })
        }
        assertEquals(OrderQueryLifecycle.BLOCKED, pruned.feed.progress().lifecycle)
        val proven = fixture()
        proven.verify(0, 0)
        assertEquals(OrderQueryLifecycle.READY, proven.feed.progress().lifecycle)
        proven.feed.suspendProjection()
        proven.verify(0, 0)
        assertEquals(OrderQueryLifecycle.READY, proven.feed.progress().lifecycle)
    }

    @Test
    fun `지원 밖 마켓도 원본 manifest에서 분류해 전역 접수 prefix를 연속 저장한다`() {
        val f = fixture(supportedOnlyA = true)
        f.verify(3, 0)
        f.admit(f.input(1))
        val unsupported = f.input(2, market = f.b, marketAdmission = 1)
        f.admit(unsupported)
        f.admit(f.input(3, dispatch = 2, marketAdmission = 2))
        assertEquals(3L, f.feed.progress().admissionThrough)
        assertEquals(3L, f.read(f.input(1)).progress!!.coveredAdmissionSequence)
        assertNull(f.query.read(f.b.market.marketId.value, unsupported.order.orderId.value).record)
        assertEquals(
            "UNSUPPORTED_QUERY_MARKET",
            jdbc.queryForObject(
                """
                select classification from order_query.source_receipt
                where namespace = :namespace and source_kind = 'ADMISSION' and source_sequence = 2
                """.trimIndent(),
                f.parameters,
                String::class.java,
            ),
        )
    }

    @Test
    fun `source 관찰 중 다른 WAS의 cursor 전진은 허용하고 관찰 전 cursor보다 뒤인 head만 차단한다`() {
        for (regressed in listOf("admission", "matching", "financial")) {
            val f = fixture()
            val beforeRead = f.feed.progress()
            val writer = f.reopen()
            val input = f.input(1)
            writer.admit(input)
            writer.match(f.owner.apply(input).result!!)
            writer.feed.applyFinancial(f.watermark(1))
            val staleMatching = f.streams.mapValues { bounds(0) }
            val staleFinancial = f.streams.mapValues { 0L }
            val verified = f.feed.verifySources(bounds(0), staleMatching, staleFinancial, beforeRead)
            assertEquals(OrderQueryLifecycle.READY, verified.lifecycle)
            assertEquals(1L, verified.admissionThrough)
            assertEquals(1L, verified.matchingThrough.getValue(f.a.market.marketId.value))
            assertEquals(1L, verified.financialThrough.getValue(f.a.market.marketId.value))
            val current = f.feed.progress()
            val matching =
                f.streams.mapValues { (market, _) ->
                    bounds(if (market == f.a.market.marketId.value && regressed != "matching") 1 else 0)
                }
            val financial =
                f.streams.mapValues { (market, _) ->
                    if (market == f.a.market.marketId.value && regressed != "financial") 1L else 0L
                }
            assertFailsWith<OrderQueryWriteConflictException>(regressed) {
                f.feed.verifySources(bounds(if (regressed == "admission") 0 else 1), matching, financial, current)
            }
            assertEquals(OrderQueryLifecycle.BLOCKED, f.feed.progress().lifecycle)
            assertEquals(OrderQueryFinancialStatus.APPLIED, f.read(input).record!!.financialStatus)
        }
    }

    @Test
    fun `같은 consumer의 소스 lineage 정책 변경은 기존 cursor와 주문을 유지한 채 차단한다`() {
        val f = fixture()
        f.verify(1, 0)
        val input = f.input(1)
        f.admit(input)
        val saved = f.read(input).record
        assertFailsWith<OrderQueryWriteConflictException> {
            f.feed.initialize("query-was", f.streams.mapValues { it.value.copy(ownershipEpoch = 2) }, f.contexts)
        }
        assertEquals(OrderQueryLifecycle.BLOCKED, f.read(input).progress!!.lifecycle)
        assertEquals(saved, f.read(input).record)
        assertEquals(
            1L,
            f
                .reopen()
                .feed
                .progress()
                .admissionThrough,
        )
    }

    @Test
    fun `최대 길이 마켓도 접수 매칭 정산과 재개방 중복의 batch 식별자 상한을 넘지 않는다`() {
        for (length in listOf(239, 240, 256)) {
            val f = fixture(aMarketName = "A".repeat(length))
            val input = f.input(1)
            f.verify(1, 1)
            assertEquals(1L, f.admit(input))
            val result = f.owner.apply(input).result!!
            assertEquals(2L, f.match(result))
            assertEquals(3L, f.feed.applyFinancial(f.watermark(1)))
            f.verify(1, 1)
            val saved = f.read(input)
            assertEquals(OrderQueryLifecycle.READY, saved.progress!!.lifecycle)
            assertEquals(OrderQueryFinancialStatus.APPLIED, saved.record!!.financialStatus)
            val reopened = f.reopen()
            assertEquals(1L, reopened.admit(input))
            assertEquals(2L, reopened.match(result))
            assertEquals(3L, reopened.feed.applyFinancial(f.watermark(1)))
            assertEquals(saved, reopened.read(input))
            assertContentEquals(f.resultBytes(result), reopened.raw("MATCHING", f.a.market.marketId.value, 1))
        }
    }

    @Test
    fun `같은 원본 지문으로 재개방하고 다른 배치표 지문은 영구 차단한다`() {
        val f = fixture(sourceIdentity = "a".repeat(64))
        f.verify(1, 0)
        val input = f.input(1)
        f.admit(input)
        val before = f.read(input)
        assertEquals(before, f.reopen().read(input))
        assertFailsWith<OrderQueryWriteConflictException> {
            f.feed.initialize("query-was", f.streams, f.contexts, "b".repeat(64))
        }
        assertEquals(OrderQueryLifecycle.BLOCKED, f.feed.progress().lifecycle)
        assertEquals(before.record, f.read(input).record)
        assertEquals(1L, f.feed.progress().admissionThrough)
    }

    @Test
    fun `준비 상태 저장이 실패해도 같은 WAS 조회는 원본 재검증 전까지 503을 유지한다`() {
        val f = fixture()
        f.verify(0, 0)
        assertEquals(
            OrderQueryLifecycle.READY,
            f.query
                .read(f.a.market.marketId.value, "absent")
                .progress!!
                .lifecycle,
        )
        rejectLifecycleWrites {
            assertFailsWith<OrderQueryUnavailableException> { f.feed.suspendProjection() }
            assertEquals("READY", durableLifecycle(f))
            assertEquals(
                OrderQueryLifecycle.INITIALIZING,
                f.query
                    .read(f.a.market.marketId.value, "absent")
                    .progress!!
                    .lifecycle,
            )
            val result =
                OrderQueryUseCase(f.query).get(
                    f.a.market.marketId.value,
                    f
                        .input(1)
                        .order.orderId.value,
                )
            assertEquals(OrderQueryCode.QUERY_NOT_READY, result.code)
        }
        f.verify(0, 0)
        assertEquals(
            OrderQueryLifecycle.READY,
            f.query
                .read(f.a.market.marketId.value, "absent")
                .progress!!
                .lifecycle,
        )
    }

    @Test
    fun `영구 차단 저장이 실패해도 조회는 닫히고 같은 이유의 재시도로 DB 차단을 남긴다`() {
        val f = fixture()
        f.verify(0, 0)
        rejectLifecycleWrites {
            assertFailsWith<OrderQueryUnavailableException> { f.feed.block("source identity differs") }
            assertEquals("READY", durableLifecycle(f))
            assertEquals(
                OrderQueryLifecycle.BLOCKED,
                f.query
                    .read(f.a.market.marketId.value, "absent")
                    .progress!!
                    .lifecycle,
            )
            val result =
                OrderQueryUseCase(f.query).get(
                    f.a.market.marketId.value,
                    f
                        .input(1)
                        .order.orderId.value,
                )
            assertEquals(OrderQueryCode.QUERY_NOT_READY, result.code)
        }
        f.feed.block("source identity differs")
        assertEquals("BLOCKED", durableLifecycle(f))
        assertEquals(
            OrderQueryLifecycle.BLOCKED,
            f
                .reopen()
                .query
                .read(f.a.market.marketId.value, "absent")
                .progress!!
                .lifecycle,
        )
        assertEquals(0L, f.feed.progress().admissionThrough)
    }

    @Test
    fun `원장 페이지 안과 저장 경계에서 같은 지급 로그 위치나 이전 위치를 거절한다`() {
        val f = fixture()
        val first = f.ledgerBatch(1)
        val second = f.ledgerBatch(2)
        assertEquals(1L, f.feed.applyLedgerPage(listOf(first)))

        for (position in listOf(first.logPosition, first.logPosition - 1)) {
            val failure = assertFailsWith<OrderQueryLedgerException> { f.feed.applyLedgerPage(listOf(f.ledgerBatch(2, position))) }
            assertEquals(OrderQueryRecoveryFailure.LEDGER_MISMATCH, failure.reason)
        }
        for (position in listOf(second.logPosition, second.logPosition - 1)) {
            val failure =
                assertFailsWith<OrderQueryLedgerException> { f.feed.applyLedgerPage(listOf(second, f.ledgerBatch(3, position))) }
            assertEquals(OrderQueryRecoveryFailure.LEDGER_MISMATCH, failure.reason)
        }
        assertEquals(1L, f.feed.ledgerThrough())
        f.feed.verifyLedgerPrefix(1)
    }

    @Test
    fun `원장 재전송은 같은 원문만 허용하고 공백이나 원문 차이 뒤의 묶음을 저장하지 않는다`() {
        val f = fixture()
        val second = f.ledgerBatch(2)
        val third = f.ledgerBatch(3)
        val fourth = f.ledgerBatch(4)
        val committed = listOf(f.ledgerBatch(1), second)
        assertEquals(2L, f.feed.applyLedgerPage(committed))

        val reopened = f.reopen().feed
        assertEquals(2L, reopened.applyLedgerPage(committed))
        for (page in listOf(listOf(second, fourth), listOf(fourth))) {
            val gap = assertFailsWith<OrderQueryLedgerException> { reopened.applyLedgerPage(page) }
            assertEquals(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, gap.reason)
        }
        val changed = f.ledgerBatch(2, amount = 201)
        val conflict = assertFailsWith<OrderQueryLedgerException> { reopened.applyLedgerPage(listOf(changed, third)) }
        assertEquals(OrderQueryRecoveryFailure.LEDGER_MISMATCH, conflict.reason)
        assertEquals(2L, f.feed.ledgerThrough())
        f.feed.verifyLedgerPrefix(2)

        assertEquals(3L, reopened.applyLedgerPage(listOf(second, third)))
        assertEquals(3L, f.feed.ledgerThrough())
        f.feed.verifyLedgerPrefix(3)
    }

    @Test
    fun `기존 원장 마이그레이션 뒤 한 로그 위치에서 정산한 두 원장을 순번으로 저장한다`() {
        val f = migratedLedgerFixture
        assertEquals(1L, f.feed.applyLedgerPage(listOf(f.ledgerBatch(1))))
        f.owner.apply(f.input(1, side = Side.SELL, quantity = 2))
        val firstFill = f.settlement(f.owner.apply(f.input(2, quantity = 1)).result!!).fills.single()
        val secondFill = f.settlement(f.owner.apply(f.input(3, quantity = 1)).result!!).fills.single()
        val second = FundsLedgerBatch(f.namespace.fundsLineage, 2L, 64L, listOf(firstFill.transaction))
        val third = FundsLedgerBatch(f.namespace.fundsLineage, 3L, 64L, listOf(secondFill.transaction))
        assertEquals(2L, f.feed.applyLedgerPage(listOf(second)))
        assertEquals(3L, f.reopen().feed.applyLedgerPage(listOf(second, third)))
        assertEquals(3L, f.reopen().feed.applyLedgerPage(listOf(second, third)))
        f.feed.verifyLedgerPrefix(3L)

        val backwards = FundsLedgerBatch(f.namespace.fundsLineage, 4L, 63L, listOf(secondFill.transaction))
        val failure = assertFailsWith<OrderQueryLedgerException> { f.feed.applyLedgerPage(listOf(backwards)) }
        assertEquals(OrderQueryRecoveryFailure.LEDGER_MISMATCH, failure.reason)
        assertEquals(3L, f.feed.ledgerThrough())
    }

    @Test
    fun `상위 DB 트랜잭션이 있으면 커밋 전 원장 확인 순번을 반환하지 않는다`() {
        val f = fixture()
        val transaction = TransactionTemplate(DataSourceTransactionManager(jdbc.jdbcTemplate.dataSource!!))
        val page = listOf(f.ledgerBatch(1))

        assertFailsWith<IllegalStateException> { transaction.executeWithoutResult { f.feed.applyLedgerPage(page) } }

        assertEquals(0L, f.feed.ledgerThrough())
        assertEquals(1L, f.feed.applyLedgerPage(page))
    }

    private fun durableLifecycle(f: Fixture): String =
        jdbc.queryForObject(
            "select lifecycle from order_query.query_progress where namespace = :namespace",
            f.parameters,
            String::class.java,
        )!!

    private fun rejectLifecycleWrites(action: () -> Unit) {
        jdbc.jdbcTemplate.execute(
            """
            create function order_query.reject_lifecycle() returns trigger language plpgsql as '
            begin raise exception ''injected lifecycle failure''; end';
            """.trimIndent(),
        )
        jdbc.jdbcTemplate.execute(
            "create trigger reject_lifecycle before update of lifecycle on order_query.query_progress " +
                "for each row execute function order_query.reject_lifecycle()",
        )
        try {
            action()
        } finally {
            jdbc.jdbcTemplate.execute("drop trigger reject_lifecycle on order_query.query_progress")
            jdbc.jdbcTemplate.execute("drop function order_query.reject_lifecycle()")
        }
    }

    private fun fixture(
        supportedOnlyA: Boolean = false,
        aMarketName: String = "A-QUOTE",
        sourceIdentity: String? = null,
        settlementEnabled: Boolean = false,
        fees: MakerTakerFeeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
    ): Fixture {
        val funds = ClusterLineage(identities.incrementAndGet(), 42)
        val matching = ClusterLineage(43, 44)
        val feePolicy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, fees)
        val a = TradingMarketContext(MarketDefinition(MarketId(aMarketName), AssetId("A"), AssetId("QUOTE"), 0, 1, 1), feePolicy, 0)
        val b = TradingMarketContext(MarketDefinition(MarketId("B-QUOTE"), AssetId("B"), AssetId("QUOTE"), 0, 1, 1), feePolicy, 1)
        val contexts = listOf(a, b)
        val manifest =
            TradingClusterManifest(funds, "query-feed-test", "0".repeat(64), "1".repeat(64), 7, contexts, TradingResourceLimits())
        val topology = TradingRoleTopology(manifest, contexts.map { TradingMarketAssignment(it.market.marketId, matching, 1) })
        val declaration =
            TradingRoleDeclaration(
                TradingServiceRole.MATCHING,
                matching,
                7,
                contexts,
                contexts.associate {
                    it.market.marketId to
                        1L
                },
            )
        val configuration = MatchingIntakeConfiguration(topology, declaration, inputContractVersion = 2)
        val owner = ReservedMatchingState(configuration).also { it.initialize() }
        val namespace = OrderQueryNamespace(funds, (if (supportedOnlyA) listOf(a) else contexts).map { it.market.marketId.value }.toSet())
        return Fixture(
            namespace,
            contexts.associateBy { it.market.marketId.value },
            contexts.associate {
                it.market.marketId.value to
                    configuration.resultStream(it.market.marketId)
            },
            owner,
            sourceIdentity,
            settlementEnabled,
        )
    }

    private fun storeFillHistoryBeforeUpgrade(): Fixture {
        val f = fixture(settlementEnabled = true, fees = MakerTakerFeeRates(FeeRate(10_000), FeeRate(20_000)))
        val firstMaker = f.input(1, side = Side.SELL, quantity = 1)
        val secondMaker = f.input(2, side = Side.SELL)
        val taker = f.input(3, quantity = 2)
        for (input in listOf(firstMaker, secondMaker, taker)) {
            f.admit(input)
            val result = assertNotNull(f.owner.apply(input).result)
            f.match(result)
            val settlement = f.settlement(result, makerFee = 1, takerFee = 2)
            f.feed.applySettlement(settlement)
            assertEquals(result.resultSequence, f.reopen().feed.applySettlement(settlement))
        }
        val cancel = assertNotNull(f.owner.apply(f.cancel(secondMaker, 4)).result)
        f.match(cancel)
        f.feed.applySettlement(f.settlement(cancel))
        f.verify(3, 4)
        return f
    }

    private inner class Fixture(
        val namespace: OrderQueryNamespace,
        val contexts: Map<String, TradingMarketContext>,
        val streams: Map<String, MatchingResultStream>,
        val owner: ReservedMatchingState,
        val sourceIdentity: String?,
        val settlementEnabled: Boolean,
    ) {
        val a = contexts.values.single { it.ordinal == 0 }
        val b = contexts.values.single { it.ordinal == 1 }
        val parameters = mapOf("namespace" to "${namespace.fundsLineage.high}:${namespace.fundsLineage.low}")
        val query =
            PostgresOrderQueryStore(
                namespace,
                jdbc,
                DataSourceTransactionManager(jdbc.jdbcTemplate.dataSource!!),
            ).also { it.initialize() }
        val feed =
            PostgresOrderQueryFeedStore(query, jdbc).also {
                it.initialize("query-was", streams, contexts, sourceIdentity, settlementEnabled)
            }

        fun reopen() = Fixture(namespace, contexts, streams, owner, sourceIdentity, settlementEnabled)

        fun ledgerBatch(
            sequence: Long,
            position: Long = sequence * 32,
            amount: Long = sequence * 100,
        ): FundsLedgerBatch {
            val opening = OpeningBalance("open-$sequence", UserId("u$sequence"), AssetId("QUOTE"), Amount(amount))
            val transaction = opening.transaction("ledger-$sequence", Instant.EPOCH)
            return FundsLedgerBatch(namespace.fundsLineage, sequence, position, listOf(transaction))
        }

        fun verify(
            admissions: Long,
            resultsA: Long,
        ) = feed.verifySources(
            bounds(admissions),
            streams.mapValues { bounds(if (it.key == a.market.marketId.value) resultsA else 0) },
        )

        fun input(
            admission: Long,
            side: Side = Side.BUY,
            quantity: Long = 3,
            market: TradingMarketContext = a,
            dispatch: Long = admission,
            marketAdmission: Long = dispatch,
            userId: UserId = UserId("u$admission"),
        ): FundsOrderDispatch {
            val order =
                OrderAdmissionInput(
                    market.market.marketId,
                    TradingIdentifiers.order(namespace.fundsLineage, admission),
                    userId,
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(100),
                    Quantity(quantity),
                )
            val requirement =
                OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())).calculate(
                    market.market,
                    side,
                    order.price,
                    order.quantity,
                    market.feePolicySnapshot,
                )
            val reservation =
                OrderReservation.create(
                    order.marketId,
                    order.orderId,
                    order.userId,
                    side,
                    order.price,
                    order.quantity,
                    requirement,
                    market.feePolicySnapshot,
                )
            val stream = streams.getValue(market.market.marketId.value)
            return FundsOrderDispatch(
                FundsDispatchStream(
                    stream.fundsLineage,
                    stream.matchingLineage,
                    stream.marketId,
                    stream.ownershipEpoch,
                    stream.configurationVersion,
                ),
                dispatch,
                admission,
                marketAdmission,
                order,
                reservation,
                market,
                inputContractVersion = 2,
            )
        }

        fun cancel(
            original: FundsOrderDispatch,
            sequence: Long,
        ) = original.copy(
            dispatchSequence = sequence,
            cancel =
                FundsCancelDispatch(
                    TradingIdentifiers.cancel(namespace.fundsLineage, 1),
                    RequestToken(original.order.userId, 0, 1, 1),
                    1,
                    original.dispatchSequence,
                ),
        )

        fun watermark(through: Long) = MatchingFundsSettledAck(streams.getValue(a.market.marketId.value), through, a)

        fun admit(input: FundsOrderDispatch) = feed.applyAdmission(input, admissionBytes(input))

        fun match(result: MatchingResultEnvelope) = feed.applyMatching(result, resultBytes(result))

        fun admissionBytes(input: FundsOrderDispatch) = SplitOrderDeliveryCodec(2).encode(0, SplitOrderDeliveryCommand.Apply(input))

        fun resultBytes(result: MatchingResultEnvelope) = SplitResultDeliveryCodec(2).encode(0, SplitResultDeliveryCommand.Apply(result))

        fun settlement(
            result: MatchingResultEnvelope,
            occurredAt: Instant = Instant.ofEpochMilli(1_765_000_000_123),
            makerFee: Long = 0,
            takerFee: Long = 0,
        ): SettlementFact {
            val market = contexts.getValue(result.stream.marketId.value)
            val base = market.market.baseAssetId
            val quote = market.market.quoteAssetId
            val fills =
                result.events.filterIsInstance<TradeExecuted>().map { trade ->
                    val taker = trade.takerUserId.value
                    val maker = trade.makerUserId.value
                    val buyer = if (trade.side == Side.BUY) taker else maker
                    val seller = if (trade.side == Side.BUY) maker else taker
                    val buyFee = if (trade.side == Side.BUY) takerFee else makerFee
                    val sellFee = if (trade.side == Side.BUY) makerFee else takerFee
                    val notional = trade.price.value * trade.quantity.value
                    val quantity = Amount(trade.quantity.value)
                    val postings =
                        mutableListOf(
                            LedgerPosting("USER:$seller:${base.value}:HOLD", base, DEBIT, quantity),
                            LedgerPosting("USER:$seller:${quote.value}:AVAILABLE", quote, CREDIT, Amount(notional - sellFee)),
                            LedgerPosting("USER:$buyer:${quote.value}:HOLD", quote, DEBIT, Amount(notional + buyFee)),
                            LedgerPosting("USER:$buyer:${base.value}:AVAILABLE", base, CREDIT, quantity),
                        )
                    val revenue = buyFee + sellFee
                    if (revenue > 0) {
                        postings.add(LedgerPosting("SYSTEM:${quote.value}:FEE_REVENUE", quote, CREDIT, Amount(revenue)))
                    }
                    val transaction =
                        LedgerTransaction(
                            TradingIdentifiers.settlement(namespace.fundsLineage, market.ordinal, trade.engineSequence),
                            "MATCHING:${trade.marketId.value}:${trade.engineSequence}",
                            LedgerTransactionType.SETTLEMENT,
                            occurredAt,
                            postings,
                        )
                    SettledTrade(trade, transaction, Amount(makerFee), Amount(takerFee))
                }
            return SettlementFact(result.stream, result.resultSequence, fills)
        }

        fun settlementTransactionCount(): Long =
            jdbc.queryForObject(
                "select count(*) from order_query.settlement_transaction where namespace = :namespace",
                parameters,
                Long::class.java,
            )!!

        fun settlementPostingCount(): Long =
            jdbc.queryForObject(
                "select count(*) from order_query.settlement_posting where namespace = :namespace",
                parameters,
                Long::class.java,
            )!!

        fun read(input: FundsOrderDispatch) = query.read(input.order.marketId.value, input.order.orderId.value)

        fun readFills(
            input: FundsOrderDispatch,
            limit: Int = 50,
            after: OrderFillCursor? = null,
        ) = query.readFills(input.order.marketId.value, input.order.orderId.value, minAdmissionSequence = 0, limit = limit, after = after)

        fun updatedAt(input: FundsOrderDispatch): Instant =
            jdbc.queryForObject(
                "select updated_at from order_query.order_record where namespace = :namespace and order_id = :orderId",
                parameters + ("orderId" to input.order.orderId.value),
            ) { row, _ -> row.getTimestamp("updated_at").toInstant() }!!

        fun receiptCount(
            kind: String,
            sequence: Long,
        ): Long =
            jdbc.queryForObject(
                """
                select count(*) from order_query.source_receipt
                where namespace = :namespace and source_kind = :kind and source_sequence = :sequence
                """.trimIndent(),
                parameters + mapOf("kind" to kind, "sequence" to sequence),
                Long::class.java,
            )!!

        fun raw(
            kind: String,
            key: String,
            sequence: Long,
        ): ByteArray =
            jdbc.queryForObject(
                """
                select canonical_payload from order_query.source_receipt
                where namespace = :namespace and source_kind = :kind and source_key = :key and source_sequence = :sequence
                """.trimIndent(),
                parameters + mapOf("kind" to kind, "key" to key, "sequence" to sequence),
                ByteArray::class.java,
            )!!
    }

    private fun dataSource() = DriverManagerDataSource(database.jdbcUrl, database.username, database.password)

    private fun bounds(
        issued: Long,
        retired: Long = 0,
        genesis: Boolean = true,
    ) = OrderQuerySourceBounds(issued, retired, genesis)
}

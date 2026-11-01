package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TradingRoleAssemblyTest {
    private val fundsLineage = ClusterLineage(1, 10)
    private val firstMatchingLineage = ClusterLineage(2, 20)
    private val secondMatchingLineage = ClusterLineage(3, 30)
    private val usdt = AssetId("USDT")
    private val buyer = UserId("buyer")
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val firstMarket = MarketDefinition(MarketId("FIRST-USDT"), AssetId("FIRST"), usdt, 0)
    private val secondMarket = MarketDefinition(MarketId("SECOND-USDT"), AssetId("SECOND"), usdt, 0)
    private val fee = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(0), FeeRate(0)))
    private val contexts = listOf(TradingMarketContext(firstMarket, fee, 0), TradingMarketContext(secondMarket, fee, 1))
    private val manifest =
        TradingClusterManifest(fundsLineage, "role-assembly-v1", "0".repeat(64), "1".repeat(64), 7, contexts, TradingResourceLimits())
    private val assignments =
        listOf(
            TradingMarketAssignment(firstMarket.marketId, firstMatchingLineage, 1),
            TradingMarketAssignment(secondMarket.marketId, secondMatchingLineage, 2),
        )
    private val topology = TradingRoleTopology(manifest, assignments)

    private fun admissionCalculator() =
        OrderAdmissionCalculator(OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())))

    private fun declaration(
        role: TradingServiceRole = TradingServiceRole.FUNDS,
        lineage: ClusterLineage = fundsLineage,
        version: Long = 7,
        markets: List<TradingMarketContext> = contexts,
        epochs: Map<MarketId, Long> = assignments.associate { it.marketId to it.ownershipEpoch },
    ) = TradingRoleDeclaration(role, lineage, version, markets, epochs)

    private fun matching(lineage: ClusterLineage = firstMatchingLineage): MatchingRole {
        val owned = assignments.filter { it.matchingLineage == lineage }
        return TradingRoleAssembly.matching(
            topology,
            declaration(
                TradingServiceRole.MATCHING,
                lineage,
                markets = contexts.filter { c -> owned.any { it.marketId == c.market.marketId } },
                epochs = owned.associate { it.marketId to it.ownershipEpoch },
            ),
        )
    }

    private fun order(
        id: String,
        side: Side,
        quantity: Long,
        marketId: MarketId = firstMarket.marketId,
    ) = SubmitOrderCommand(marketId, OrderId(id), buyer, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))

    @Test
    fun `자금 조립은 매칭 없이 공통 잔고와 요청 이력을 사용한다`() {
        val owner = TradingRoleAssembly.funds(topology, declaration(), admissionCalculator())
        owner.initialize(manifest)
        owner.prepareDevelopmentBalance(OpeningBalance("opening", buyer, usdt, Amount(1000)), at)
        owner.rotateEpoch(buyer, 0, 0)

        fun intent(
            market: MarketDefinition,
            quantity: Long,
        ) = OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
        val first = RequestToken(buyer, 0, 1, 1)
        val accepted = assertIs<AdmissionReply.Recorded>(owner.admit(first, intent(firstMarket, 7), at))
        assertEquals(AdmissionCode.ACCEPTED, accepted.receipt.code)
        val insufficient = assertIs<AdmissionReply.Recorded>(owner.admit(RequestToken(buyer, 0, 1, 2), intent(secondMarket, 4), at))
        assertEquals(AdmissionCode.REJECTED, insufficient.receipt.code)
        assertEquals(Balance(buyer, usdt, Amount(300), Amount(700)), owner.currentBalance(buyer, usdt))
        assertEquals(
            AdmissionCode.ACCEPTED,
            assertIs<AdmissionReply.Recorded>(
                owner.admit(RequestToken(buyer, 0, 1, 3), intent(secondMarket, 3), at),
            ).receipt.code,
        )
        val before = owner.progress()
        val conflict = assertIs<AdmissionReply.Refused>(owner.admit(first, intent(secondMarket, 7), at))
        assertEquals("REQUEST_CONTENT_CONFLICT", conflict.reason)
        assertEquals(before, owner.progress())
        assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount(1000)), owner.currentBalance(buyer, usdt))
        val repeated = assertIs<AdmissionReply.Recorded>(owner.admit(first, intent(firstMarket, 7), at))
        assertFalse(repeated.newlyRecorded)
        assertEquals(accepted.receipt, repeated.receipt)
        assertEquals(2, owner.boundedRetention().liveOrders)
    }

    @Test
    fun `같은 역할 설정으로 조립한 자금 인스턴스는 상태를 공유하지 않는다`() {
        val first = TradingRoleAssembly.funds(topology, declaration(), admissionCalculator()).also { it.initialize(manifest) }
        val second = TradingRoleAssembly.funds(topology, declaration(), admissionCalculator()).also { it.initialize(manifest) }
        first.prepareDevelopmentBalance(OpeningBalance("opening", buyer, usdt, Amount(1000)), at)
        assertEquals(Amount(1000), first.currentBalance(buyer, usdt).available)
        assertEquals(Balance(buyer, usdt, Amount.ZERO, Amount.ZERO), second.currentBalance(buyer, usdt))
        assertEquals(0, second.boundedRetention().accounts)
    }

    @Test
    fun `매칭은 금융 계산기 없이 후보 계산과 적용을 수행한다`() {
        val owner = matching()
        val sell = owner.prepareSubmit(order("sell", Side.SELL, 1))
        assertTrue(owner.restingOrders(firstMarket.marketId).isEmpty())
        owner.apply(sell)
        val buy = owner.prepareSubmit(order("buy", Side.BUY, 3))
        assertEquals(1, owner.restingOrders(firstMarket.marketId).size)
        val events = owner.apply(buy)
        assertEquals(Quantity(1), events.filterIsInstance<TradeExecuted>().single().quantity)
        assertEquals(Quantity(2), events.filterIsInstance<OrderEnteredBook>().single().remainingQuantity)
        assertEquals(Quantity(2), owner.restingOrders(firstMarket.marketId).single().remainingQuantity)
        assertTrue(matching().restingOrders(firstMarket.marketId).isEmpty())
    }

    @Test
    fun `담당 밖 마켓과 다른 엔진의 후보를 거절해도 기존 후보는 적용할 수 있다`() {
        val owner = matching()
        val pending = owner.prepareSubmit(order("owned", Side.BUY, 1))
        val foreign = matching(secondMatchingLineage).prepareSubmit(order("foreign", Side.BUY, 1, secondMarket.marketId))
        assertFailsWith<IllegalArgumentException> { owner.prepareSubmit(order("wrong", Side.BUY, 1, secondMarket.marketId)) }
        assertFailsWith<IllegalArgumentException> { owner.apply(foreign) }
        assertFailsWith<IllegalArgumentException> { owner.apply(matching().prepareSubmit(order("another-engine", Side.BUY, 1))) }
        assertEquals(0L, owner.lastEngineSequence(firstMarket.marketId))
        owner.apply(pending)
        assertEquals(OrderId("owned"), owner.restingOrders(firstMarket.marketId).single().orderId)
    }

    @Test
    fun `기대 배치와 다른 역할 lineage 버전 마켓 정책 세대를 조립 전에 거절한다`() {
        val changedFee = fee.copy(feeRates = MakerTakerFeeRates(FeeRate(1), FeeRate(0)))
        val changedContexts =
            listOf(
                contexts[0].copy(feePolicySnapshot = changedFee),
                contexts[0].copy(market = firstMarket.copy(priceTick = 2)),
                contexts[0].copy(market = firstMarket.copy(quantityStep = 2)),
                contexts[0].copy(market = firstMarket.copy(baseAssetId = AssetId("OTHER"))),
            )
        val invalid =
            listOf(
                declaration(role = TradingServiceRole.MATCHING),
                declaration(lineage = ClusterLineage(9, 9)),
                declaration(version = 8),
                declaration(markets = emptyList()),
                declaration(markets = listOf(contexts[0], contexts[0])),
                declaration(markets = listOf(contexts[0])),
                declaration(epochs = mapOf(firstMarket.marketId to 2, secondMarket.marketId to 2)),
                declaration(epochs = emptyMap()),
            ) + changedContexts.map { declaration(markets = listOf(it, contexts[1])) }
        for (value in invalid) {
            assertFailsWith<IllegalArgumentException> { TradingRoleAssembly.funds(topology, value, admissionCalculator()) }
        }
        assertFailsWith<IllegalArgumentException> {
            TradingRoleAssembly.matching(topology, declaration(TradingServiceRole.MATCHING, ClusterLineage(9, 9)))
        }
        assertFailsWith<IllegalArgumentException> { declaration(epochs = mapOf(firstMarket.marketId to 0)) }
        assertFailsWith<IllegalArgumentException> { declaration(epochs = mapOf(firstMarket.marketId to -1)) }
    }

    @Test
    fun `마켓 배치의 누락 중복 잘못된 소유자를 허용하지 않는다`() {
        val invalid =
            listOf(
                emptyList(),
                listOf(assignments[0]),
                assignments + assignments[0],
                assignments + TradingMarketAssignment(MarketId("UNKNOWN"), firstMatchingLineage, 1),
                listOf(assignments[0].copy(matchingLineage = fundsLineage), assignments[1]),
            )
        invalid.forEach { value -> assertFailsWith<IllegalArgumentException> { TradingRoleTopology(manifest, value) } }
        assertFailsWith<IllegalArgumentException> { TradingMarketAssignment(firstMarket.marketId, firstMatchingLineage, 0) }
        assertFailsWith<IllegalArgumentException> { TradingMarketAssignment(firstMarket.marketId, firstMatchingLineage, -1) }
    }

    @Test
    fun `배치 순서와 원본 컬렉션 변경은 검증한 역할을 바꾸지 않는다`() {
        val sourceAssignments = assignments.reversed().toMutableList()
        val sourceContexts = contexts.reversed().toMutableList()
        val sourceEpochs = assignments.associate { it.marketId to it.ownershipEpoch }.toMutableMap()
        val expected = TradingRoleTopology(manifest, sourceAssignments)
        val selected = declaration(markets = sourceContexts, epochs = sourceEpochs)
        sourceAssignments.clear()
        sourceContexts.clear()
        sourceEpochs.clear()
        val owner = TradingRoleAssembly.funds(expected, selected, admissionCalculator())
        assertEquals(InitializationReceipt.INITIALIZED, owner.initialize(manifest))
        assertEquals(2, expected.assignments.size)
        assertEquals(2, selected.markets.size)
        assertFailsWith<UnsupportedOperationException> { (selected.markets as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (selected.ownershipEpochs as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (expected.assignments as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (matching().marketIds as MutableSet).clear() }
    }

    @Test
    fun `업무 역할의 기본값과 잘못된 값 그리고 단독 실행 차단을 구분한다`() {
        assertEquals(TradingServiceRole.INTEGRATED, TradingServiceRole.parse(null))
        assertEquals(TradingServiceRole.INTEGRATED, TradingServiceRole.parse("INTEGRATED"))
        TradingServiceRole.INTEGRATED.requireRuntimeEnabled()
        for (value in listOf("", "funds", "UNKNOWN", " ")) {
            assertEquals("INVALID_SERVICE_ROLE", assertFailsWith<IllegalArgumentException> { TradingServiceRole.parse(value) }.message)
        }
        for (role in listOf(TradingServiceRole.FUNDS, TradingServiceRole.MATCHING)) {
            assertEquals(role, TradingServiceRole.parse(role.name))
            assertEquals(
                "SPLIT_ROLE_NOT_ENABLED: ${role.name}",
                assertFailsWith<IllegalStateException> { role.requireRuntimeEnabled() }.message,
            )
        }
    }
}

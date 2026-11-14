package com.exchange.core.api.trading.infrastructure.aeron

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
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.OrderCancelled
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReply
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchAckStatus
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsResultStatus
import com.exchange.core.trading.MarketFundsDispatchLimits
import com.exchange.core.trading.MatchingIntakeStatus
import com.exchange.core.trading.MatchingResultAckStatus
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 실제 두 소유자의 무중단 대조와 JSON 판정 검사다. native·Archive 검증은 Docker 사례의 책임이다. */
class SplitRecoveryConvergenceTest {
    @Test
    fun `다섯 입력은 수기 금액과 한 번의 취소 반환 뒤 후속 체결까지 일치한다`() {
        val owners = RecoveryOwners()
        val buy = owners.admit(owners.buyer, 1, Side.BUY, 3)
        owners.process(buy)
        assertOwnerState(owners, RecoveryExpected())

        owners.process(owners.admit(owners.seller, 1, Side.SELL, 1))
        assertOwnerState(
            owners,
            RecoveryExpected(
                inputs = 2,
                fundsThrough = 2,
                matchingAck = 2,
                settled = 2,
                buyerHold = 202,
                buyerX = 1,
                sellerQ = 199,
                sellerX = 2,
                quantity = 2,
                bookQuantity = 2,
                admissions = 2,
                trades = 1,
                feeQ = 2,
            ),
        )

        owners.process(owners.cancel(buy))
        assertOwnerState(owners, cancelledExpected())
        owners.process(owners.admit(owners.buyer, 4, Side.SELL, 1))
        assertOwnerState(
            owners,
            cancelledExpected().copy(
                inputs = 4,
                fundsThrough = 4,
                matchingAck = 4,
                settled = 4,
                buyerX = 0,
                buyerXHold = 1,
                live = 1,
                quantity = 1,
                book = 1,
                bookQuantity = 1,
                matchingThrough = 4,
                engine = 4,
                admissions = 3,
            ),
        )

        owners.process(owners.admit(owners.seller, 2, Side.BUY, 1))
        assertOwnerState(owners, completedExpected())
        val final = owners.semanticView()
        val funds = final.getValue("funds")
        // 최초 quote1100 = buyer998 + seller98 + 수수료4, base3 = seller3이다.
        assertEquals(1_100L, funds.getValue("buyerQ") + funds.getValue("sellerQ") + funds.getValue("feeQ"))
        assertEquals(3L, funds.getValue("buyerX") + funds.getValue("sellerX"))
        assertEquals(5L, final.getValue("matching").getValue("engineSequence"))
        assertEquals(2L, funds.getValue("settlementTrades"))
        assertEquals(final, uninterruptedRecoveryReference())
    }

    @Test
    fun `현재 여섯 사본은 무중단 소유자의 의미 상태와 모두 일치해야 한다`() {
        val reference = uninterruptedRecoveryReference()
        val states = observations(reference)

        assertEquals(emptyList(), problems(states, completedExpected(), currentBoots()))
        for ((name, state) in states) {
            assertEquals(reference.getValue(role(name)), recoverySemanticView(state), name)
        }
    }

    @Test
    fun `취소 전달 전에는 자금 입력3과 매칭 입력2를 별도로 판정한다`() {
        val owners = RecoveryOwners()
        val buy = owners.admit(owners.buyer, 1, Side.BUY, 3)
        owners.process(buy)
        owners.match(owners.admit(owners.seller, 1, Side.SELL, 1))
        owners.cancel(buy)
        val waiting =
            RecoveryExpected(
                inputs = 3,
                matchingAck = 2,
                sellerX = 2,
                sellerHold = 1,
                live = 2,
                quantity = 4,
                pendingOrders = 1,
                dispatches = 1,
                bookQuantity = 2,
                matchingThrough = 2,
                admissions = 2,
                cancellations = 1,
                pendingCancellations = 1,
            )

        assertEquals(emptyList(), problems(observations(owners.semanticView()), waiting, currentBoots()))
    }

    @Test
    fun `사본 하나가 없거나 목록이 비어 있으면 수렴 성공이 아니다`() {
        val states = observations(uninterruptedRecoveryReference())
        for (name in copies) assertTrue(problems(states - name, completedExpected(), currentBoots()).isNotEmpty(), name)
        assertTrue(problems(emptyMap(), completedExpected()).isNotEmpty())
        assertTrue(problems(states + ("funds3" to states.getValue("funds0")), completedExpected()).isNotEmpty())
    }

    @Test
    fun `금액이 맞아도 이전 실행의 관찰이면 현재 복구 결과로 인정하지 않는다`() {
        for (name in copies) {
            val states = observations(uninterruptedRecoveryReference())
            (states.getValue(name) as ObjectNode).put("bootId", "old-$name")
            assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("$name.bootId") }, name)
        }
    }

    @Test
    fun `취소 완료나 후속 주문 대기까지만 끝난 상태는 최종 수렴이 아니다`() {
        val owners = RecoveryOwners()
        val buy = owners.admit(owners.buyer, 1, Side.BUY, 3)
        owners.process(buy)
        owners.process(owners.admit(owners.seller, 1, Side.SELL, 1))
        owners.process(owners.cancel(buy))
        assertFalse(completedExpected().matches(observations(owners.semanticView()), currentBoots()))
        owners.process(owners.admit(owners.buyer, 4, Side.SELL, 1))
        assertFalse(completedExpected().matches(observations(owners.semanticView()), currentBoots()))
    }

    @Test
    fun `반환202나 체결 수수료2를 한 번 더 반영한 사본은 거절한다`() {
        val corruptions = listOf("buyerQ" to 1_200L, "feeQ" to 6L, "feeQ" to 2L)
        for ((field, amount) in corruptions) {
            val states = observations(uninterruptedRecoveryReference())
            (states.getValue("funds1").path("funds") as ObjectNode).put(field, amount)
            assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("funds1.$field") }, field)
        }
    }

    @Test
    fun `필수 금액과 경계가 누락되거나 정수가 아니면 기본값으로 통과시키지 않는다`() {
        val reference = uninterruptedRecoveryReference()
        for ((role, fields) in reference) {
            for (field in fields.keys) {
                val states = observations(reference)
                (states.getValue("${role}1").path(role) as ObjectNode).remove(field)
                assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("${role}1.$field") }, field)
            }
        }
        val invalidNumbers: List<Any?> = listOf(null, "0", 0.0, false, BigInteger("9223372036854775808"))
        val mapper = jacksonObjectMapper()
        for (value in invalidNumbers) {
            val states = observations(reference)
            (states.getValue("funds1").path("funds") as ObjectNode).set("buyerQHold", mapper.valueToTree<JsonNode>(value))
            assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("funds1.buyerQHold") }, "$value")
        }
    }

    @Test
    fun `준비 또는 실행 식별이 없거나 차단된 사본은 성공이 아니다`() {
        for (field in listOf("role", "memberId", "initialized", "lifecycle", "bootId")) {
            val states = observations(uninterruptedRecoveryReference())
            (states.getValue("matching2") as ObjectNode).remove(field)
            assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("matching2.$field") }, field)
        }
        val states = observations(uninterruptedRecoveryReference())
        (states.getValue("funds2") as ObjectNode).put("lifecycle", "QUARANTINED")
        assertTrue(problems(states, completedExpected(), currentBoots()).any { it.contains("funds2.lifecycle") })
    }

    @Test
    fun `진행 중 전송과 활성 byte가 고정 프로파일 상한을 넘으면 거절한다`() {
        val owners = RecoveryOwners()
        val buy = owners.admit(owners.buyer, 1, Side.BUY, 3)
        owners.process(buy)
        owners.match(owners.admit(owners.seller, 1, Side.SELL, 1))
        owners.cancel(buy)
        val waiting =
            RecoveryExpected(
                inputs = 3,
                matchingAck = 2,
                sellerX = 2,
                sellerHold = 1,
                live = 2,
                quantity = 4,
                pendingOrders = 1,
                dispatches = 1,
                bookQuantity = 2,
                matchingThrough = 2,
                admissions = 2,
                cancellations = 1,
                pendingCancellations = 1,
            )
        val reference = owners.semanticView()
        assertEquals(emptyList(), problems(observations(reference), waiting, currentBoots()))
        val limits =
            listOf(
                Triple("funds1", "dispatchBytes", 1_048_576L),
                Triple("funds1", "activeBytes", 1_048_576L),
                Triple("matching1", "resultBytes", 2_097_152L),
                Triple("matching1", "activeBytes", 2_097_152L),
            )
        for ((name, field, maximum) in limits) {
            val boundary = observations(reference)
            (boundary.getValue(name).path(role(name)) as ObjectNode).put(field, maximum)
            assertEquals(emptyList(), problems(boundary, waiting, currentBoots()), "$name.$field 상한")
            for (excess in listOf(maximum + 1, Long.MAX_VALUE)) {
                val states = observations(reference)
                (states.getValue(name).path(role(name)) as ObjectNode).put(field, excess)
                assertTrue(problems(states, waiting, currentBoots()).any { it.contains("$name.$field") }, "$name.$field=$excess")
            }
        }
    }

    @Test
    fun `실제 소유자의 다섯 결과는 최초 주문 응답과 취소 대상에 결속된다`() {
        val owners = completedRecoveryOwners()

        assertEquals(emptyList(), recoveryMeaningProblems(owners.meaningObservation(), owners.firstCancelReply()))
    }

    @Test
    fun `금액과 prefix가 맞아도 결과 대상과 사건 효과가 바뀌면 거절한다`() {
        val owners = completedRecoveryOwners()
        assertEquals(emptyList(), recoveryMeaningProblems(owners.meaningObservation(), owners.firstCancelReply()))
        val corruptions: List<Triple<String, String, Any?>> =
            listOf(
                Triple("/funds/resultDetails/2", "orderId", "other-order"),
                Triple("/funds/resultDetails/2", "cancelId", "other-cancel"),
                Triple("/funds/resultDetails/0", "cancelId", "unexpected-cancel"),
                Triple("/funds/resultDetails/2", "inputSequence", 2L),
                Triple("/funds/resultDetails/2", "engineBefore", 1L),
                Triple("/funds/resultDetails/2", "outcome", "MATCHED"),
                Triple("/funds/resultDetails/2/events/0", "quantity", 3L),
                Triple("/funds/resultDetails/4/events/0", "makerOrderId", "other-maker"),
                Triple("/funds/resultDetails/4/events/0", "takerOrderId", "other-taker"),
                Triple("/funds/resultDetails/4/events/0", "price", 99L),
                Triple("/funds/resultDetails/4/events/0", "engineSequence", 4L),
                Triple("/funds/resultDetails/2/effects/0", "originInputSequence", 3L),
                Triple("/funds/resultDetails/2/effects/0", "beforeQuantity", 3L),
                Triple("/funds/resultDetails/2/effects/0", "afterQuantity", 1L),
                Triple("/funds/resultDetails/2/effects/0", "disposition", "TERMINAL"),
                Triple("/funds/inboxChannels/0/cancellations/0", "targetOrderId", "other-order"),
                Triple("/funds/inboxChannels/0/orders/0", "code", "REJECTED"),
                Triple("/funds/inboxChannels/0/orders/0", "orderId", ""),
            )
        val mapper = jacksonObjectMapper()
        for ((parent, field, value) in corruptions) {
            val state = owners.meaningObservation()
            (state.at(parent) as ObjectNode).set(field, mapper.valueToTree<JsonNode>(value))
            assertTrue(recoveryMeaningProblems(state, owners.firstCancelReply()).isNotEmpty(), "$parent/$field")
        }
        val first = mapper.readTree(owners.firstCancelReply().toString()) as ObjectNode
        first.put("targetOrderId", "other-order")
        assertTrue(recoveryMeaningProblems(owners.meaningObservation(), first).isNotEmpty())
    }

    @Test
    fun `최종 결과나 고유 ID가 빠지면 다섯 입력의 의미 대조가 아니다`() {
        val owners = completedRecoveryOwners()
        assertEquals(emptyList(), recoveryMeaningProblems(owners.meaningObservation(), owners.firstCancelReply()))
        val missing = owners.meaningObservation()
        (missing.at("/funds/resultDetails") as ArrayNode).remove(4)
        assertTrue(recoveryMeaningProblems(missing, owners.firstCancelReply()).isNotEmpty())
        for (invalidId in listOf("", " ", owners.meaningObservation().at("/funds/resultDetails/0/resultId").asString())) {
            val state = owners.meaningObservation()
            (state.at("/funds/resultDetails/4") as ObjectNode).put("resultId", invalidId)
            assertTrue(recoveryMeaningProblems(state, owners.firstCancelReply()).isNotEmpty(), invalidId)
        }
        val duplicate = owners.meaningObservation()
        (duplicate.at("/funds/inboxChannels/0/orders/1") as ObjectNode).put("admissionSequence", 1L)
        assertTrue(recoveryMeaningProblems(duplicate, owners.firstCancelReply()).isNotEmpty())
    }

    private fun assertOwnerState(
        owners: RecoveryOwners,
        expected: RecoveryExpected,
    ) {
        val actual = owners.semanticView()
        for (role in listOf("funds", "matching")) {
            for ((field, value) in expected.fields(role)) assertEquals(value, actual.getValue(role).getValue(field), "$role.$field")
        }
        assertEquals(emptyList(), problems(observations(actual), expected, currentBoots()))
    }

    private fun observations(reference: Map<String, Map<String, Long>>): Map<String, JsonNode> {
        val mapper = jacksonObjectMapper()
        return copies.associateWith { name ->
            val role = role(name)
            mapper.valueToTree<JsonNode>(
                mapOf(
                    "role" to role.uppercase(),
                    "memberId" to name.last().digitToInt(),
                    "initialized" to true,
                    "lifecycle" to "RUNNING",
                    "bootId" to currentBoots().getValue(name),
                    role to reference.getValue(role),
                ),
            )
        }
    }

    private fun currentBoots(): Map<String, String> = copies.associateWith { "current-$it" }

    private fun role(name: String): String = if (name.startsWith("funds")) "funds" else "matching"

    private fun cancelledExpected(): RecoveryExpected =
        RecoveryExpected(
            inputs = 3,
            fundsThrough = 3,
            matchingAck = 3,
            settled = 3,
            buyerQ = 899,
            buyerHold = 0,
            buyerX = 1,
            sellerQ = 199,
            sellerX = 2,
            live = 0,
            quantity = 0,
            book = 0,
            bookQuantity = 0,
            admissions = 2,
            cancellations = 1,
            trades = 1,
            feeQ = 2,
        )

    private fun completedExpected(): RecoveryExpected =
        cancelledExpected().copy(
            inputs = 5,
            fundsThrough = 5,
            matchingAck = 5,
            settled = 5,
            buyerQ = 998,
            buyerX = 0,
            sellerQ = 98,
            sellerX = 3,
            matchingThrough = 5,
            engine = 5,
            admissions = 4,
            trades = 2,
            feeQ = 4,
        )

    private val copies = listOf("funds0", "funds1", "funds2", "matching0", "matching1", "matching2")
}

/** 실제 소유자를 순서대로 호출하는 경량 대조다. Docker나 복원 성공의 증거로 세지 않는다. */
internal fun uninterruptedRecoveryReference(): Map<String, Map<String, Long>> = completedRecoveryOwners().semanticView()

private fun completedRecoveryOwners(): RecoveryOwners =
    RecoveryOwners().apply {
        val buy = admit(buyer, 1, Side.BUY, 3)
        process(buy)
        process(admit(seller, 1, Side.SELL, 1))
        process(cancel(buy))
        process(admit(buyer, 4, Side.SELL, 1))
        process(admit(seller, 2, Side.BUY, 1))
    }

/** 수기 정답을 만들지 않고 실제 결과의 제한된 의미 필드를 Docker 관찰과 같은 모양으로 옮긴다. */
private fun observedRecoveryResult(result: MatchingResultEnvelope): Map<String, Any?> =
    mapOf(
        "sequence" to result.resultSequence,
        "resultId" to result.resultId,
        "inputSequence" to result.input.dispatchSequence,
        "orderId" to result.input.order.orderId.value,
        "cancelId" to result.input.cancel?.cancelId,
        "outcome" to result.outcome.name,
        "engineBefore" to result.engineSequenceBefore,
        "engineAfter" to result.engineSequenceAfter,
        "events" to
            result.events.map { event ->
                mapOf<String, Any>("engineSequence" to event.engineSequence) +
                    when (event) {
                        is TradeExecuted -> {
                            mapOf(
                                "kind" to "TRADE",
                                "makerOrderId" to event.makerOrderId.value,
                                "takerOrderId" to event.takerOrderId.value,
                                "quantity" to event.quantity.value,
                                "price" to event.price.value,
                            )
                        }

                        is OrderEnteredBook -> {
                            mapOf(
                                "kind" to "RESTING",
                                "orderId" to event.orderId.value,
                                "quantity" to event.remainingQuantity.value,
                                "price" to event.price.value,
                            )
                        }

                        is OrderCancelled -> {
                            mapOf("kind" to "CANCELLED", "orderId" to event.orderId.value, "quantity" to event.remainingQuantity.value)
                        }

                        else -> {
                            error("고정 다섯 입력에 예상하지 않은 사건: $event")
                        }
                    }
            },
        "effects" to
            result.effects.map {
                mapOf(
                    "orderId" to it.orderId.value,
                    "originInputSequence" to it.origin.dispatchSequence,
                    "beforeQuantity" to it.beforeQuantity.value,
                    "afterQuantity" to it.afterQuantity.value,
                    "disposition" to it.disposition.name,
                )
            },
    )

private class RecoveryOwners {
    val buyer = UserId("buyer")
    val seller = UserId("seller")
    private val quote = AssetId("USDT")
    private val base = AssetId("X")
    private val market = MarketDefinition(MarketId("X-USDT"), base, quote, 0)
    private val at = Instant.parse("2026-11-13T00:00:00Z")
    private val configuration = configuration()
    private val funds = configuration.funds().also { it.initialize(configuration.topology.fundsManifest) }
    private val matching = configuration.matching().also { it.initialize() }
    private val calculator = configuration.settlementCalculator()
    private var firstCancellation: JsonNode? = null

    init {
        for ((user, asset, amount) in listOf(Triple(buyer, quote, 1_000L), Triple(seller, quote, 100L), Triple(seller, base, 3L))) {
            val opening = OpeningBalance("opening-${user.value}-${asset.value}", user, asset, Amount(amount))
            assertFalse(funds.prepareDevelopmentBalance(opening, at).alreadyPrepared)
        }
        for (user in listOf(buyer, seller)) assertEquals(1L, funds.rotateEpoch(user, 0, 0).epoch)
    }

    fun admit(
        user: UserId,
        request: Long,
        side: Side,
        quantity: Long,
    ): FundsOrderDispatch {
        val intent = OrderIntent(market.marketId, 7, side, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(quantity))
        val reply = assertIs<AdmissionReply.Recorded>(funds.admit(RequestToken(user, 0, 1, request), intent, at))
        assertEquals(AdmissionCode.ACCEPTED, reply.receipt.code)
        return latestInput()
    }

    fun cancel(target: FundsOrderDispatch): FundsOrderDispatch {
        val reply =
            assertIs<CancelReply.Recorded>(
                funds.admitCancel(RequestToken(buyer, 0, 1, 2), CancelIntent(market.marketId, target.order.orderId, 7)),
            )
        assertEquals(CancelCode.ACCEPTED, reply.receipt.code)
        assertEquals(3L, reply.receipt.inputSequence)
        firstCancellation =
            jacksonObjectMapper().valueToTree<JsonNode>(
                mapOf(
                    "code" to reply.receipt.code.name,
                    "cancelId" to reply.receipt.cancelId,
                    "targetOrderId" to reply.receipt.targetOrderId?.value,
                    "inputSequence" to reply.receipt.inputSequence,
                ),
            )
        return latestInput()
    }

    fun firstCancelReply(): JsonNode = assertNotNull(firstCancellation)

    fun meaningObservation(): JsonNode {
        val saved = funds.fundsRecoveryState()
        val channels =
            saved.financial.inbox.channels.sortedBy { it.userId.value }.map { channel ->
                mapOf(
                    "userId" to channel.userId.value,
                    "channel" to channel.channel,
                    "epoch" to channel.epoch,
                    "orders" to
                        channel.entries.sortedBy { it.sequence }.map {
                            mapOf(
                                "requestSequence" to it.sequence,
                                "code" to it.receipt.code.name,
                                "orderId" to it.receipt.orderId?.value,
                                "admissionSequence" to it.receipt.admissionSequence,
                            )
                        },
                    "cancellations" to
                        channel.cancelEntries.sortedBy { it.sequence }.map {
                            mapOf(
                                "requestSequence" to it.sequence,
                                "code" to it.receipt.code.name,
                                "cancelId" to it.receipt.cancelId,
                                "targetOrderId" to it.receipt.targetOrderId?.value,
                                "inputSequence" to it.receipt.inputSequence,
                            )
                        },
                )
            }
        val details = assertNotNull(saved.results).receipts.map { observedRecoveryResult(it.result) }
        return jacksonObjectMapper().valueToTree<JsonNode>(
            mapOf(
                "role" to "FUNDS",
                "funds" to mapOf("inboxChannels" to channels, "resultDetails" to details),
            ),
        )
    }

    private fun latestInput(): FundsOrderDispatch {
        val stream =
            funds
                .fundsRecoveryState()
                .dispatch.streams
                .single()
        return funds.pendingDispatch(stream.stream, stream.issued, 1, 65_536).entries.single()
    }

    fun match(input: FundsOrderDispatch): MatchingResultEnvelope {
        val reply = matching.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, reply.status)
        assertEquals(FundsDispatchAckStatus.ACKNOWLEDGED, funds.acknowledgeMatchingApplied(assertNotNull(reply.ack)).status)
        return assertNotNull(reply.result)
    }

    fun process(input: FundsOrderDispatch) {
        val result = match(input)
        val reply = funds.applyMatchingResult(result, at.plusSeconds(result.resultSequence), calculator)
        assertEquals(FundsResultStatus.APPLIED, reply.status)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, matching.acknowledgeFundsSettled(assertNotNull(reply.ack)).status)
    }

    fun semanticView(): Map<String, Map<String, Long>> {
        val savedFunds = funds.fundsRecoveryState()
        val dispatch = savedFunds.dispatch.streams.single()
        val results = assertNotNull(savedFunds.results)
        val resultProgress = results.streams.single()
        val savedMatching = matching.recoveryState()
        val matchingProgress = savedMatching.streams.single()
        val book = savedMatching.matching.markets.singleOrNull()

        fun balance(
            user: UserId,
            asset: AssetId,
            hold: Boolean = false,
        ): Long {
            val value = savedFunds.financial.balances.singleOrNull { it.userId == user && it.assetId == asset }
            return if (hold) value?.hold?.value ?: 0 else value?.available?.value ?: 0
        }

        return mapOf(
            "funds" to
                mapOf(
                    "buyerQ" to balance(buyer, quote),
                    "buyerQHold" to balance(buyer, quote, true),
                    "buyerX" to balance(buyer, base),
                    "buyerXHold" to balance(buyer, base, true),
                    "sellerQ" to balance(seller, quote),
                    "sellerQHold" to balance(seller, quote, true),
                    "sellerX" to balance(seller, base),
                    "sellerXHold" to balance(seller, base, true),
                    "feeQ" to (savedFunds.financial.feeRevenueByAsset[quote]?.longValueExact() ?: 0),
                    "liveOrders" to
                        savedFunds.financial.liveOrders.size
                            .toLong(),
                    "activeOrders" to savedFunds.dispatch.activeOrders.toLong(),
                    "activeBytes" to savedFunds.dispatch.activeBytes,
                    "remainingQuantity" to savedFunds.financial.liveOrders.sumOf { it.reservation.remainingQuantity.value },
                    "pendingOrders" to
                        savedFunds.financial.markets
                            .single()
                            .pendingAdmissionSequences.size
                            .toLong(),
                    "dispatchEntries" to savedFunds.dispatch.dispatchEntries.toLong(),
                    "dispatchBytes" to savedFunds.dispatch.dispatchBytes,
                    "issued" to dispatch.issued,
                    "matchingAppliedThrough" to dispatch.matchingAppliedThrough,
                    "admissionIssued" to savedFunds.financial.lastAdmissionSequence,
                    "cancellationIssued" to savedFunds.lastCancellationSequence,
                    "pendingCancellations" to savedFunds.cancellations.size.toLong(),
                    "executedThrough" to
                        savedFunds.financial.markets
                            .single()
                            .executedThrough,
                    "resultThrough" to resultProgress.resultThrough,
                    "retiredThrough" to resultProgress.retiredThrough,
                    "receiptEntries" to results.receipts.size.toLong(),
                    "settlementTrades" to
                        results.receipts.sumOf { receipt ->
                            receipt.result.events
                                .count { it is TradeExecuted }
                                .toLong()
                        },
                ),
            "matching" to
                mapOf(
                    "bookOrders" to (book?.orders?.size?.toLong() ?: 0),
                    "bookQuantity" to (book?.orders?.sumOf { it.remainingQuantity.value } ?: 0),
                    "activeOrders" to savedMatching.activeOrders.toLong(),
                    "activeBytes" to savedMatching.activeBytes,
                    "engineSequence" to (book?.lastEngineSequence ?: 0),
                    "appliedThrough" to matchingProgress.appliedThrough,
                    "resultIssued" to matchingProgress.resultIssued,
                    "resultEntries" to savedMatching.resultEntries.toLong(),
                    "resultBytes" to savedMatching.resultBytes,
                    "fundsSettledThrough" to matchingProgress.fundsSettledThrough,
                ),
        )
    }

    private fun configuration(): SplitTradingConfiguration {
        val context =
            TradingMarketContext(
                market,
                TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(10_000), FeeRate(10_000))),
                0,
            )
        val manifest =
            TradingClusterManifest(
                ClusterLineage(10, 11),
                "split-recovery-reference",
                "0".repeat(64),
                "1".repeat(64),
                7,
                listOf(context),
                TradingResourceLimits(),
            )
        val matchingLineage = ClusterLineage(20, 21)
        val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matchingLineage, 1)))

        fun declaration(
            role: TradingServiceRole,
            lineage: ClusterLineage,
        ) = TradingRoleDeclaration(role, lineage, 7, listOf(context), mapOf(market.marketId to 1))

        val dispatchLimits =
            FundsDispatchLimits(
                mapOf(market.marketId to MarketFundsDispatchLimits(maxDispatchBytes = 65_536, maxActiveBytes = 65_536)),
                maxDispatchBytes = 65_536,
                maxActiveBytes = 65_536,
            )
        return SplitTradingConfiguration(
            topology,
            declaration(TradingServiceRole.FUNDS, manifest.lineage),
            declaration(TradingServiceRole.MATCHING, matchingLineage),
            dispatchLimits = dispatchLimits,
            inputContractVersion = 2,
        )
    }
}

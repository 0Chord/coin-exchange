package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCalculator
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.api.trading.infrastructure.codec.admit
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionState
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 State의 불변 관찰을 펼친 출력이 원본·과거 후보·거절과 실제 전표를 구별하는지 확인한다. */
class DevelopmentFundingEventsTest {
    private val config = DevelopmentFundingSettings(Path.of("/tmp/event-projection"), runId = "projection")
    private val openingTime = Instant.parse("2026-10-21T00:00:00.123456789Z")
    private val firstTime = Instant.parse("2026-10-21T00:00:01.123456789Z")
    private val secondTime = Instant.parse("2026-10-21T00:00:02.123456789Z")

    @Test
    fun `검증 진입점만 잘못된 메시지 개수를 출력한다`() {
        val observation = FundingObservation(128, null, null, malformedMessages = 1, replyStatus = "MALFORMED_OR_UNSUPPORTED")
        val development = fundingAppliedFields(config, observation)
        val verification = fundingVerificationAppliedFields(config, observation)

        assertFalse(development.containsKey("malformedMessages"))
        assertEquals(development, verification - "malformedMessages")
        assertEquals(1L, verification["malformedMessages"])
    }

    @Test
    fun `자금 관찰은 기존 필드와 OPENING의 전체 시각을 유지한다`() {
        val state = emptyState()
        val prepared =
            state.prepareDevelopmentBalance(
                OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000_000_000)),
                "L-OPEN-1",
                openingTime,
            )
        val event =
            tree(
                fundingAppliedFields(
                    config,
                    FundingObservation(128, state.snapshot(), DevelopmentBalanceResultPayload.Prepared(prepared), appliedRole = "LEADER"),
                ),
            )

        assertEquals("FUNDING", event.path("inputKind").asString())
        assertEquals("P1", event.path("preparationId").asString())
        assertEquals("U1", event.path("userId").asString())
        assertEquals(false, event.at("/result/alreadyPrepared").asBoolean())
        assertEquals(event.path("result"), event.at("/state/preparations/0"))
        assertEquals(tree(listOf(balance("USDT", 1_000_000_000, 0))), event.at("/state/balances"))
        assertEquals(0, event.at("/state/pendingOrders").size())
        assertEquals(0, event.at("/state/requests").size())
        val transaction = event.at("/state/transactions/0")
        assertEquals("OPENING:P1", transaction.path("sourceEventId").asString())
        assertEquals(openingTime.toString(), transaction.path("occurredAt").asString())
        assertEquals(openingTime.epochSecond, transaction.path("occurredAtEpochSecond").asLong())
        assertEquals(123_456_789, transaction.path("occurredAtNano").asInt())
    }

    @Test
    fun `두 BUY 뒤 현재 잔고와 O1 당시 후보를 나누고 실제 순서와 최초 결과를 보존한다`() {
        val state = preparedState()
        val first = command()
        val firstEvent = applyOrder(state, first, 256)
        val second = command("R2", "O2", 100_000_000, secondTime)
        val event = applyOrder(state, second, 384)
        val pending = event.at("/state/pendingOrders")

        assertEquals("ORDER", event.path("inputKind").asString())
        assertEquals("U1", event.path("userId").asString())
        assertEquals("R2", event.path("requestId").asString())
        assertEquals("O2", event.path("orderId").asString())
        assertEquals("BTC-USDT", event.path("marketId").asString())
        assertEquals(listOf("O1", "O2"), pending.toList().map { it.at("/candidate/order/orderId").asString() })
        assertEquals(listOf(1L, 2L), pending.toList().map { it.path("sequence").asLong() })
        assertEquals(firstEvent.at("/state/pendingOrders/0"), pending.path(0))
        assertEquals(tree(listOf(balance("USDT", 600_000_000, 400_000_000))), event.at("/state/balances"))
        val candidate = pending.path(0).path("candidate")
        assertEquals(tree(balance("USDT", 1_000_000_000, 0)), candidate.path("previousBalance"))
        assertEquals(tree(balance("USDT", 700_000_000, 300_000_000)), candidate.path("nextBalance"))
        assertEquals(
            tree(
                mapOf(
                    "marketId" to "BTC-USDT",
                    "orderId" to "O1",
                    "userId" to "U1",
                    "side" to "BUY",
                    "orderType" to "LIMIT",
                    "timeInForce" to "GTC",
                    "price" to 100_000_000L,
                    "quantity" to 300_000_000L,
                ),
            ),
            candidate.path("order"),
        )
        assertEquals(tree(expectedMarket()), candidate.path("market"))
        assertEquals(tree(expectedPolicy()), candidate.path("feePolicy"))
        assertEquals(tree(reservation("BUY", "USDT", 300_000_000, 300_000_000)), candidate.path("reservation"))
        assertEquals(
            "RESERVE:v1:dd2c94ecf984651e09ce084d6492c1b1b5c51256a807608ef55966c9bc56e47e",
            candidate.at("/reserveTransaction/sourceEventId").asString(),
        )
        assertEquals(
            tree(
                listOf(
                    posting("USER:U1:USDT:AVAILABLE", "USDT", "DEBIT", 300_000_000),
                    posting("USER:U1:USDT:HOLD", "USDT", "CREDIT", 300_000_000),
                ),
            ),
            candidate.at("/reserveTransaction/postings"),
        )
        assertEquals(3, event.at("/state/transactions").size())
        assertEquals(pending.path(0), event.at("/state/requests/0/result/record"))
        assertEquals(pending.path(1), event.at("/state/requests/1/result/record"))
        assertEquals("L-RESERVE-1", event.at("/state/requests/0/ledgerTransactionId").asString())
        assertEquals(firstTime.toString(), event.at("/state/requests/0/occurredAt").asString())
        assertEquals(firstTime.epochSecond, event.at("/state/requests/0/occurredAtEpochSecond").asLong())
        assertEquals(123_456_789, event.at("/state/requests/0/occurredAtNano").asInt())
        assertEquals(tree(expectedMarket()), event.at("/state/requests/0/market"))
        assertEquals(tree(expectedPolicy()), event.at("/state/requests/0/feePolicy"))
        assertEquals(
            tree(
                mapOf(
                    "outcome" to "ACCEPTED",
                    "userId" to "U1",
                    "requestId" to "R2",
                    "marketId" to "BTC-USDT",
                    "orderId" to "O2",
                    "sequence" to 2L,
                ),
            ),
            event.path("result"),
        )
    }

    @Test
    fun `부족 거절은 최초 원본과 문맥을 남기며 제출한 원장 ID로 실제 전표를 만들지 않는다`() {
        val state = preparedState()
        val before = applyOrder(state, command(), 256)
        val event = applyOrder(state, command("R2", "O2", 800_000_000, secondTime), 384)

        for (field in listOf("balances", "pendingOrders", "transactions", "lastSequence")) {
            assertEquals(before.at("/state/$field"), event.at("/state/$field"), field)
        }
        assertEquals(2, event.at("/state/requests").size())
        val rejected = event.at("/state/requests/1")
        assertEquals("R2", rejected.path("requestId").asString())
        assertEquals("O2", rejected.at("/order/orderId").asString())
        assertEquals(800_000_000L, rejected.at("/order/quantity").asLong())
        assertEquals(tree(expectedMarket()), rejected.path("market"))
        assertEquals(tree(expectedPolicy()), rejected.path("feePolicy"))
        assertEquals("L-RESERVE-2", rejected.path("ledgerTransactionId").asString())
        assertEquals(secondTime.toString(), rejected.path("occurredAt").asString())
        assertEquals(secondTime.epochSecond, rejected.path("occurredAtEpochSecond").asLong())
        assertEquals(123_456_789, rejected.path("occurredAtNano").asInt())
        val reason =
            mapOf(
                "outcome" to "REJECTED",
                "reason" to "INSUFFICIENT_BALANCE",
                "insufficientBalance" to
                    mapOf("assetId" to "USDT", "available" to 700_000_000L, "required" to 800_000_000L, "shortfall" to 100_000_000L),
            )
        assertEquals(tree(reason), rejected.path("result"))
        assertEquals(
            tree(reason + mapOf("userId" to "U1", "requestId" to "R2", "marketId" to "BTC-USDT", "orderId" to "O2")),
            event.path("result"),
        )
        assertTrue(event.at("/state/transactions").none { it.path("transactionId").asString() == "L-RESERVE-2" })
        assertFalse(event.path("result").has("candidate"))
    }

    @Test
    fun `SELL 관찰은 BTC 예약과 영 수수료를 펼치며 존재하지 않는 USDT 잔고를 추가하지 않는다`() {
        val state = preparedState("BTC", 300_000_000)
        val command = command(quantity = 200_000_000).let { it.copy(order = it.order.copy(side = Side.SELL)) }
        val event = applyOrder(state, command, 256)

        assertEquals(tree(listOf(balance("BTC", 100_000_000, 200_000_000))), event.at("/state/balances"))
        assertEquals(tree(reservation("SELL", "BTC", 200_000_000, 200_000_000)), event.at("/state/pendingOrders/0/candidate/reservation"))
        assertEquals(0L, event.at("/state/pendingOrders/0/candidate/reservation/feeRemainder").asLong())
        assertTrue(event.at("/state/pendingOrders/0/candidate/reservation/releasedAmount").isNull)
    }

    private fun emptyState(): OrderAdmissionState = OrderAdmissionState(emptyList(), admissionCalculator())

    private fun preparedState(
        asset: String = "USDT",
        amount: Long = 1_000_000_000,
    ): OrderAdmissionState =
        emptyState().apply {
            prepareDevelopmentBalance(OpeningBalance("P1", UserId("U1"), AssetId(asset), Amount(amount)), "L-OPEN-1", openingTime)
        }

    private fun command(
        requestId: String = "R1",
        orderId: String = "O1",
        quantity: Long = 300_000_000,
        occurredAt: Instant = firstTime,
    ): AdmissionCommandPayload =
        admissionCommand(requestId, orderId).let {
            it.copy(
                order = it.order.copy(userId = UserId("U1"), quantity = Quantity(quantity)),
                ledgerTransactionId = "L-RESERVE-${orderId.drop(1)}",
                occurredAt = occurredAt,
            )
        }

    private fun applyOrder(
        state: OrderAdmissionState,
        command: AdmissionCommandPayload,
        position: Long,
    ): JsonNode {
        val result = AdmissionResultPayload.from(command, admit(state, command))
        return tree(
            fundingAppliedFields(
                config,
                FundingObservation(position, state.snapshot(), null, admissionResult = result, appliedRole = "LEADER"),
            ),
        )
    }

    private fun balance(
        asset: String,
        available: Long,
        hold: Long,
    ): Map<String, Any> = mapOf("userId" to "U1", "assetId" to asset, "available" to available, "hold" to hold)

    private fun expectedMarket(): Map<String, Any> =
        mapOf("marketId" to "BTC-USDT", "baseAssetId" to "BTC", "quoteAssetId" to "USDT", "baseQuantityScale" to 8)

    private fun expectedPolicy(): Map<String, Any> =
        mapOf(
            "productType" to "SPOT",
            "feeTier" to "NORMAL",
            "scheduleVersion" to 1,
            "makerPartsPerMillion" to 0L,
            "takerPartsPerMillion" to 0L,
        )

    private fun reservation(
        side: String,
        asset: String,
        quantity: Long,
        amount: Long,
    ): Map<String, Any?> =
        mapOf(
            "marketId" to "BTC-USDT",
            "orderId" to "O1",
            "userId" to "U1",
            "side" to side,
            "assetId" to asset,
            "limitPrice" to 100_000_000L,
            "initialQuantity" to quantity,
            "remainingQuantity" to quantity,
            "reservedAmount" to amount,
            "remainingAmount" to amount,
            "feePolicy" to expectedPolicy(),
            "initialFeeReserveAmount" to 0L,
            "remainingFeeReserveAmount" to 0L,
            "status" to "ACTIVE",
            "feeRemainder" to 0L,
            "releasedAmount" to null,
        )

    private fun posting(
        account: String,
        asset: String,
        side: String,
        amount: Long,
    ): Map<String, Any> = mapOf("accountId" to account, "assetId" to asset, "side" to side, "amount" to amount)

    private fun tree(value: Any): JsonNode = jacksonObjectMapper().let { it.readTree(it.writeValueAsString(value)) }
}

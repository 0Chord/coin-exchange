package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.Balance
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejected as WireAdmissionRejected

class AdmissionProtocolFlowTest {
    private val commands = AdmissionCommandCodec()
    private val results = AdmissionResultCodec()

    @Test
    fun `수수료가 있는 매도도 BTC 두 개만 예약하고 당시 정책을 보존한다`() {
        val base = admissionCommand()
        val command =
            base.copy(
                order = base.order.copy(side = Side.SELL, quantity = Quantity(200_000_000)),
                feePolicySnapshot =
                    base.feePolicySnapshot.copy(
                        feeTier = FeeTier.VVIP,
                        scheduleVersion = 7,
                        feeRates = MakerTakerFeeRates(FeeRate(500), FeeRate(1_000)),
                    ),
            )
        val state =
            OrderAdmissionState(
                listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount(300_000_000), Amount.ZERO)),
                admissionCalculator(),
            )
        val decoded = commands.decode(commands.encode(command))
        assertEquals(command, decoded)
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, decoded))
        assertEquals(command.feePolicySnapshot, accepted.record.candidate.feePolicySnapshot)
        assertEquals(Amount(200_000_000), accepted.record.candidate.reservation.reservedAmount)
        assertEquals(Amount.ZERO, accepted.record.candidate.reservation.initialFeeReserveAmount)
        assertEquals(listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount(100_000_000), Amount(200_000_000))), state.snapshot().balances)
        val response = AdmissionResultPayload.from(decoded, accepted)
        assertEquals(response, results.decode(results.encode(response)))
    }

    @Test
    fun `같은 원본 재전송은 쓰지 않을 새 문맥보다 최초 승인을 우선한다`() {
        val state = admissionState()
        val original = admissionCommand()
        val first = send(state, original)
        val snapshot = state.snapshot()
        val contexts =
            listOf(
                original.copy(
                    market =
                        original.market.copy(
                            marketId =
                                com.exchange.core.common
                                    .MarketId("OTHER"),
                        ),
                ) to "주문과 마켓 정의가 다릅니다",
                original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES)) to
                    "현물 수수료 정책이 필요합니다",
                original.copy(ledgerTransactionId = "", occurredAt = java.time.Instant.MAX) to "원장 거래 식별자가 잘못됐습니다",
            )
        contexts.forEach { (changedContext, message) ->
            assertEquals(changedContext, commands.decode(commands.encode(changedContext)))
            assertEquals(first, send(state, changedContext))
            assertEquals(snapshot, state.snapshot())
            val error =
                assertFailsWith<IllegalArgumentException> {
                    send(state, changedContext.copy(requestId = "R2", order = original.order.copy(orderId = OrderId("O2"))))
                }
            assertEquals(message, error.message)
            assertEquals(snapshot, state.snapshot())
        }
        assertFalse(state.isBlocked)
    }

    @Test
    fun `같은 요청의 다른 주문과 새 요청의 기존 주문은 서로 다른 거절이다`() {
        val state = admissionState()
        val original = admissionCommand()
        send(state, original)
        val snapshot = state.snapshot()
        val conflicts =
            listOf(
                original.copy(order = original.order.copy(orderId = OrderId("O2"))),
                original.copy(order = original.order.copy(quantity = Quantity(100_000_000))),
            )
        conflicts.forEach { command ->
            assertEquals(
                AdmissionResultPayload.Rejected(
                    "user-1",
                    "R1",
                    "BTC-USDT",
                    command.order.orderId.value,
                    AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT,
                ),
                send(state, command),
            )
            assertEquals(snapshot, state.snapshot())
        }
        assertEquals(
            AdmissionResultPayload.Rejected("user-1", "R2", "BTC-USDT", "O1", AdmissionResultPayload.Reason.DUPLICATE_ORDER),
            send(state, original.copy(requestId = "R2")),
        )
        val after = state.snapshot()
        assertEquals(snapshot.balances, after.balances)
        assertEquals(snapshot.pendingOrders, after.pendingOrders)
        assertEquals(1L, after.lastSequence)
        assertEquals(2, after.requests.size)
    }

    @Test
    fun `현재 잔고가 줄어도 같은 요청은 최초 부족 금액을 반환한다`() {
        val state = admissionState(200_000_000)
        val original = admissionCommand()
        val first = send(state, original)
        val detail = assertIs<AdmissionResultPayload.Rejected>(first).insufficientBalance!!
        assertEquals(Amount(200_000_000), detail.available)
        assertEquals(Amount(300_000_000), detail.required)
        assertEquals(Amount(100_000_000), detail.shortfall)
        val small = admissionCommand("R2", "O2").let { it.copy(order = it.order.copy(quantity = Quantity(100_000_000))) }
        assertIs<AdmissionResultPayload.Accepted>(send(state, small))
        val snapshot = state.snapshot()
        assertEquals(Amount(100_000_000), snapshot.balances.single().available)
        assertEquals(first, send(state, original))
        assertEquals(snapshot, state.snapshot())
    }

    @Test
    fun `문자열을 정규화하지 않아 서로 다른 요청 키와 원문을 유지한다`() {
        val state = admissionState()
        listOf("é", "e\u0301", "한글:요청").forEachIndexed { index, request ->
            val command = admissionCommand(request, "O$index").let { it.copy(order = it.order.copy(quantity = Quantity(100_000_000))) }
            assertEquals(command, commands.decode(commands.encode(command)))
            assertEquals((index + 1).toLong(), assertIs<AdmissionResultPayload.Accepted>(send(state, command)).sequence)
        }
        assertEquals(listOf("é", "e\u0301", "한글:요청"), state.snapshot().requests.map { it.requestId })
    }

    @Test
    fun `승인 뒤 응답 변환 실패는 이미 반영된 자금을 되돌리지 않는다`() {
        val state = admissionState()
        val command = admissionCommand()
        val accepted = admit(state, command)
        val snapshot = state.snapshot()
        val response = assertIs<AdmissionResultPayload.Accepted>(AdmissionResultPayload.from(command, accepted))
        assertFailsWith<AdmissionMessageException> { results.encode(response.copy(requestId = "x".repeat(257))) }
        assertEquals(snapshot, state.snapshot())
        assertEquals(response, send(state, command))
        assertEquals(snapshot, state.snapshot())
    }

    @Test
    fun `다른 주문의 승인 결과를 응답으로 연결하면 내부 오류로 중단한다`() {
        val state = admissionState()
        val command = admissionCommand()
        val accepted = admit(state, command)
        listOf(
            command.order.copy(orderId = OrderId("OTHER")),
            command.order.copy(userId = UserId("OTHER")),
            command.order.copy(
                marketId =
                    com.exchange.core.common
                        .MarketId("OTHER"),
            ),
            command.order.copy(quantity = Quantity(100_000_000)),
        ).forEach { wrongOrder ->
            assertFailsWith<IllegalStateException> {
                AdmissionResultPayload.from(command.copy(order = wrongOrder), accepted)
            }
        }
        assertEquals(1L, state.snapshot().lastSequence)
    }

    @Test
    fun `다음 변환과 입력 버퍼 덮어쓰기는 이전 바이트와 접수 기록을 바꾸지 않는다`() {
        val original = admissionCommand()
        val firstBytes = commands.encode(original)
        val preservedBytes = firstBytes.copyOf()
        val decoded = commands.decode(firstBytes)
        val state = admissionState()
        val response = AdmissionResultPayload.from(decoded, admit(state, decoded))
        val firstResponse = results.encode(response)
        val preservedResponse = firstResponse.copyOf()
        val snapshot = state.snapshot()

        commands.encode(admissionCommand("R2", "O2"))
        results.encode(AdmissionResultPayload.Rejected("other-user", "R2", "BTC-USDT", "O2", AdmissionResultPayload.Reason.DUPLICATE_ORDER))
        assertContentEquals(preservedBytes, firstBytes)
        assertContentEquals(preservedResponse, firstResponse)
        firstBytes.fill(0)
        firstResponse.fill(0)
        assertEquals(original, decoded)
        assertEquals(snapshot, state.snapshot())
        val request = state.snapshot().requests.single()
        assertEquals(original.requestId, request.requestId)
        assertEquals(original.order, request.order)
        assertEquals(original.market, request.market)
        assertEquals(original.feePolicySnapshot, request.feePolicySnapshot)
        assertEquals(original.ledgerTransactionId, request.ledgerTransactionId)
        assertEquals(original.occurredAt, request.occurredAt)
    }

    @Test
    fun `작은 승인 응답은 독립 예시 바이트와 최대 순번을 보존한다`() {
        val response = AdmissionResultPayload.Accepted("u", "r", "m", "o", 1)
        // 본문 14바이트: 순번 태그 08·값 01, 문자열 태그 12/1a/22/2a·길이 01·본문.
        val golden = "0e0002005700020008011201721a017522016d2a016f".hexToByteArray()
        assertContentEquals(golden, results.encode(response))
        assertEquals(response, results.decode(golden))
        val largest = response.copy(sequence = Long.MAX_VALUE)
        assertEquals(largest, results.decode(results.encode(largest)))
    }

    @Test
    fun `부족 응답의 현재 필요 부족 금액 위치를 독립 바이트로 대조한다`() {
        val response =
            AdmissionResultPayload.Rejected(
                "u",
                "r",
                "m",
                "o",
                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(AssetId("Q"), Amount(200), Amount(300), Amount(100)),
            )
        // 본문 27바이트: 사유 08 05, 상세 12 0b 뒤 자산 Q·200(c8 01)·300(ac 02)·100(64).
        val golden = "1b000300570002000805120b0a015110c80118ac0220641a01722201752a016d32016f".hexToByteArray()
        assertContentEquals(golden, results.encode(response))
        assertEquals(response, results.decode(golden))
    }

    @Test
    fun `모든 거절 사유는 명시된 전송 코드와 상세 유무를 보존한다`() {
        val expectedCodes =
            mapOf(
                AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION to 1,
                AdmissionResultPayload.Reason.INVALID_QUANTITY to 2,
                AdmissionResultPayload.Reason.INVALID_ORDER_IDENTIFIER to 3,
                AdmissionResultPayload.Reason.UNREPRESENTABLE_RESERVATION to 4,
                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE to 5,
                AdmissionResultPayload.Reason.DUPLICATE_ORDER to 6,
                AdmissionResultPayload.Reason.INVALID_REQUEST_IDENTIFIER to 7,
                AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT to 8,
                AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH to 9,
            )
        assertEquals(AdmissionResultPayload.Reason.entries.toSet(), expectedCodes.keys)
        expectedCodes.forEach { (reason, code) ->
            val detail =
                if (reason == AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE) {
                    OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount.ZERO, Amount(Long.MAX_VALUE), Amount(Long.MAX_VALUE))
                } else {
                    null
                }
            val response = AdmissionResultPayload.Rejected("u", "r", "m", "o", reason, detail)
            val bytes = results.encode(response)
            assertEquals(code, WireAdmissionRejected.parseFrom(bytes.copyOfRange(8, bytes.size)).reasonValue)
            assertEquals(response, results.decode(bytes))
        }
    }

    private fun send(
        state: OrderAdmissionState,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload {
        val decoded = commands.decode(commands.encode(command))
        return AdmissionResultPayload.from(decoded, admit(state, decoded)).let { results.decode(results.encode(it)) }
    }
}

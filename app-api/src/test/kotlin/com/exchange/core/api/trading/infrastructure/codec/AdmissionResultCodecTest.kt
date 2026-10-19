package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejected as WireRejected
import com.exchange.core.api.trading.infrastructure.codec.generated.InsufficientBalance as WireInsufficientBalance

class AdmissionResultCodecTest {
    private val commands = AdmissionCommandCodec()
    private val results = AdmissionResultCodec()

    @Test
    fun `실제 접수 승인은 자금 반영 뒤 작은 확인 응답으로 왕복한다`() {
        val state = admissionState()
        val command = commands.decode(commands.encode(admissionCommand()))
        val result = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, command))
        val response = AdmissionResultPayload.from(command, result)
        assertEquals(AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", 1), response)
        assertEquals(response, results.decode(results.encode(response)))
        val snapshot = state.snapshot()
        assertEquals(listOf(Balance(UserId("user-1"), AssetId("USDT"), Amount(700_000_000), Amount(300_000_000))), snapshot.balances)
        assertEquals(1, snapshot.pendingOrders.size)
        assertEquals(1, snapshot.requests.size)
        assertEquals(1L, snapshot.lastSequence)
        assertEquals(2, result.record.candidate.reserveTransaction.postings.size)
        assertEquals(Amount(300_000_000), result.record.candidate.reservation.reservedAmount)
    }

    @Test
    fun `실제 자금 부족 거절은 당시 부족 상세를 그대로 왕복한다`() {
        val state = admissionState(200_000_000)
        val command = commands.decode(commands.encode(admissionCommand()))
        val response = AdmissionResultPayload.from(command, admit(state, command))
        val expected =
            AdmissionResultPayload.Rejected(
                "user-1",
                "R1",
                "BTC-USDT",
                "O1",
                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount(200_000_000), Amount(300_000_000), Amount(100_000_000)),
            )
        assertEquals(expected, response)
        assertEquals(expected, results.decode(results.encode(response)))
        val snapshot = state.snapshot()
        assertEquals(listOf(Balance(UserId("user-1"), AssetId("USDT"), Amount(200_000_000), Amount.ZERO)), snapshot.balances)
        assertEquals(emptyList(), snapshot.pendingOrders)
        assertEquals(0L, snapshot.lastSequence)
        assertEquals(1, snapshot.requests.size)
    }

    @Test
    fun `설정 불일치 사유 9는 독립 고정 바이트로 읽고 같은 바이트로 보낸다`() {
        // 기존 kind 3/schema 87/version 2. 본문은 사유 9와 요청·사용자·마켓·주문 ID뿐이다.
        val golden = "0e0003005700020008091a01722201752a016d32016f".hexToByteArray()

        val decoded = assertIs<AdmissionResultPayload.Rejected>(results.decode(golden))

        assertEquals(AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH, decoded.reason)
        assertEquals("r", decoded.requestId)
        assertEquals("u", decoded.userId)
        assertEquals("m", decoded.marketId)
        assertEquals("o", decoded.orderId)
        assertEquals(null, decoded.insufficientBalance)
        assertContentEquals(golden, results.encode(decoded))
    }

    @Test
    fun `기존 도메인의 여덟 사유는 공개 응답 사유로 명시적으로 변환한다`() {
        val expectedReasons =
            mapOf(
                OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION to AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION,
                OrderAdmissionResult.Reason.INVALID_QUANTITY to AdmissionResultPayload.Reason.INVALID_QUANTITY,
                OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER to AdmissionResultPayload.Reason.INVALID_ORDER_IDENTIFIER,
                OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION to AdmissionResultPayload.Reason.UNREPRESENTABLE_RESERVATION,
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE to AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.Reason.DUPLICATE_ORDER to AdmissionResultPayload.Reason.DUPLICATE_ORDER,
                OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER to AdmissionResultPayload.Reason.INVALID_REQUEST_IDENTIFIER,
                OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT to AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT,
            )
        assertEquals(OrderAdmissionResult.Reason.entries.toSet(), expectedReasons.keys)
        val command = admissionCommand()
        expectedReasons.forEach { (domainReason, publicReason) ->
            val detail =
                if (domainReason == OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE) {
                    OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount(200), Amount(300), Amount(100))
                } else {
                    null
                }

            val payload = AdmissionResultPayload.from(command, OrderAdmissionState.Result.Rejected(domainReason, detail))

            assertEquals(AdmissionResultPayload.Rejected("user-1", "R1", "BTC-USDT", "O1", publicReason, detail), payload)
        }
    }

    @Test
    fun `설정 불일치 사유 9에 부족 상세를 붙이면 송신과 수신 모두 거절한다`() {
        val golden = "0e0003005700020008091a01722201752a016d32016f".hexToByteArray()
        val response = assertIs<AdmissionResultPayload.Rejected>(results.decode(golden))
        val detail = OrderAdmissionResult.InsufficientBalance(AssetId("Q"), Amount(200), Amount(300), Amount(100))
        val wireDetail =
            WireInsufficientBalance
                .newBuilder()
                .setAssetId("Q")
                .setAvailable(200)
                .setRequired(300)
                .setShortfall(100)
                .build()
        val wire =
            WireRejected
                .parseFrom(golden.copyOfRange(8, golden.size))
                .toBuilder()
                .setInsufficientBalance(wireDetail)
                .build()

        assertFailsWith<AdmissionMessageException> { results.encode(response.copy(insufficientBalance = detail)) }
        assertFailsWith<AdmissionMessageException> { results.decode(protobufFrame(3, wire.toByteArray())) }
    }

    @Test
    fun `새 사유 다음의 미지원 숫자 10은 정상 식별자와 부족 상세 없이도 거절한다`() {
        val unsupportedReason = "0e00030057000200080a1a01722201752a016d32016f".hexToByteArray()

        assertFailsWith<AdmissionMessageException> { results.decode(unsupportedReason) }
    }

    @Test
    fun `기존 도메인 이름 매핑 검증 경계는 사유 8을 읽고 새 사유 9는 거절한다`() {
        val oldReason = "0e0003005700020008081a01722201752a016d32016f".hexToByteArray()
        val newReason = "0e0003005700020008091a01722201752a016d32016f".hexToByteArray()

        assertEquals(OrderAdmissionResult.Reason.REQUEST_CONTENT_CONFLICT, legacyReasonBoundary(oldReason))
        assertFailsWith<AdmissionMessageException> { legacyReasonBoundary(newReason) }
    }

    /** #97의 도메인 이름 매핑 검증만 고정한다. 구버전 런타임 전체를 실행하는 fixture는 아니다. */
    private fun legacyReasonBoundary(bytes: ByteArray): OrderAdmissionResult.Reason =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, 0, bytes.size)
            val message = reader.parse(WireRejected.parser())
            OrderAdmissionResult.Reason.valueOf(message.reason.name)
        }
}

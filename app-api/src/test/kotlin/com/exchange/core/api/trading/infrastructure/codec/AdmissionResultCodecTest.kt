package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
                OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
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
}

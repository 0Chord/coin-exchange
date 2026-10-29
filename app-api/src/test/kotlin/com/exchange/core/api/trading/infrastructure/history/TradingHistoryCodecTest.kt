package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.UserId
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingHistoryValues
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingHistoryCodecTest {
    private val codec = TradingHistoryCodec(1_024 * 1_024)

    @Test
    fun `all original values survive explicit binary round trip including nanos and settlement`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        for (batch in fixture.batches) {
            batch.request?.let {
                assertContentEquals(
                    codec.encodeRequest(it),
                    codec.encodeRequest(codec.decodeRequest(codec.encodeRequest(it))),
                )
            }
            batch.admission?.let {
                assertContentEquals(codec.encodeAdmission(it), codec.encodeAdmission(codec.decodeAdmission(codec.encodeAdmission(it))))
            }
            batch.completion?.let {
                assertContentEquals(codec.encodeCompletion(it), codec.encodeCompletion(codec.decodeCompletion(codec.encodeCompletion(it))))
            }
            batch.preparation?.let {
                assertContentEquals(
                    codec.encodePreparation(it),
                    codec.encodePreparation(codec.decodePreparation(codec.encodePreparation(it))),
                )
            }
            batch.reservations.forEach {
                assertContentEquals(
                    codec.encodeReservation(it),
                    codec.encodeReservation(codec.decodeReservation(codec.encodeReservation(it))),
                )
            }
            batch.ledgerTransactions.forEach {
                assertContentEquals(
                    codec.encodeTransaction(it),
                    codec.encodeTransaction(codec.decodeTransaction(codec.encodeTransaction(it))),
                )
            }
        }
        val completed = fixture.batches.mapNotNull { it.completion }.last()
        val restored = codec.decodeCompletion(codec.encodeCompletion(completed))
        assertEquals(123457578, restored.occurredAt.nano)
        assertEquals(completed.matchingResult, restored.matchingResult)
        assertEquals(completed.settlement.balanceChanges, restored.settlement.balanceChanges)
        assertEquals(completed.settlement.reservationChanges, restored.settlement.reservationChanges)
        assertEquals(
            completed.settlement.settlements
                .single()
                .transaction.postings,
            restored.settlement.settlements
                .single()
                .transaction.postings,
        )
    }

    @Test
    fun `UTF16 code units survive without replacement or normalization`() {
        val fixture = TradingHistoryFixture()
        val request =
            TradingHistoryValues.requestRecord(
                "request\uD800",
                fixture.order("order\uDC00", "user\uD800", Side.BUY),
                fixture.market,
                fixture.policy,
                "ledger\uDC00",
                fixture.time,
                OrderAdmissionState.Result.Rejected(OrderAdmissionResult.Reason.DUPLICATE_ORDER),
            )
        val restored = codec.decodeRequest(codec.encodeRequest(request))
        assertEquals(request.requestId, restored.requestId)
        assertEquals(UserId("user\uD800"), restored.order.userId)
        assertEquals(request.order, restored.order)
        assertEquals(request.ledgerTransactionId, restored.ledgerTransactionId)
    }

    @Test
    fun `corrupt truncated mismatched and trailing frames fail instead of decoding a different value`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val bytes = codec.encodeCompletion(fixture.batches.mapNotNull { it.completion }.last())
        val corrupt = bytes.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        assertFailsWith<IllegalStateException> { codec.decodeCompletion(corrupt) }
        assertFailsWith<IllegalStateException> { codec.decodeCompletion(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<IllegalStateException> { codec.decodeCompletion(bytes + 0) }
        assertFailsWith<IllegalStateException> { codec.decodeRequest(bytes) }
        val version = bytes.copyOf().apply { this[7] = 2 }
        assertFailsWith<IllegalStateException> { codec.decodeCompletion(version) }
        assertFailsWith<IllegalStateException> {
            TradingHistoryCodec(
                1_024,
            ).encodeCompletion(fixture.batches.mapNotNull { it.completion }.last())
        }
    }
}

package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingHistoryValues
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32C
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TradingHistoryCodecTest {
    private val codec = TradingHistoryCodec(1_024 * 1_024)

    @Test
    fun `empty UTF16 fields keep their length prefixes and repeated encodes own independent frames`() {
        val fixture = TradingHistoryFixture().apply { funding("seller", "BTC", 100) }
        val original =
            fixture.batches
                .single()
                .ledgerTransactions
                .single()
        val empty = LedgerTransaction("", "", original.transactionType, original.occurredAt, original.postings)
        val expected = originalTransactionFrame(empty)
        val first = codec.encodeTransaction(empty)
        assertContentEquals(expected, first)
        val restored = codec.decodeTransaction(first)
        assertEquals("", restored.ledgerTransactionId)
        assertEquals("", restored.sourceEventId)

        // A later frame grows well beyond the initial buffer and must not mutate a prior result.
        val large =
            LedgerTransaction(
                "가".repeat(800) + "\uD800\u0000\uDFFF",
                "",
                original.transactionType,
                original.occurredAt,
                original.postings,
            )
        val largeExpected = originalTransactionFrame(large)
        val exact = TradingHistoryCodec(largeExpected.size)
        assertContentEquals(largeExpected, exact.encodeTransaction(large))
        assertFailsWith<IllegalStateException> { TradingHistoryCodec(largeExpected.size - 1).encodeTransaction(large) }
        assertContentEquals(expected, first)
        first[0] = 0
        val next = codec.encodeTransaction(empty)
        assertTrue(first !== next)
        assertContentEquals(expected, next)
    }

    @Test
    fun `eager full frames reject copied nonpositive admission sequences before encoding`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val invalid =
            fixture.batches
                .mapNotNull { it.admission }
                .first()
                .copy(sequence = 0)
        assertFailsWith<IllegalStateException> { codec.encodeAdmission(invalid) }
        val original = fixture.batches.mapNotNull { it.request }.first()
        val request =
            TradingHistoryValues.requestRecord(
                original.requestId,
                original.order,
                original.market,
                original.feePolicySnapshot,
                original.ledgerTransactionId,
                original.occurredAt,
                OrderAdmissionState.Result.Accepted(invalid),
            )
        assertFailsWith<IllegalStateException> { codec.encodeRequest(request) }
    }

    @Test
    fun `referenced frames restore the exact original request and completion while legacy frames stay readable`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val admissions = fixture.batches.mapNotNull { it.admission }.associateBy { it.sequence }
        for (batch in fixture.batches) {
            batch.request?.let { request ->
                val full = codec.encodeRequest(request)
                assertContentEquals(full, codec.encodeRequest(codec.decodeStoredRequest(full) { error("unexpected reference") }))
                if (request.result is OrderAdmissionState.Result.Accepted) {
                    val referenced = codec.encodeReferencedRequest(request)
                    assertEquals(7, ByteBuffer.wrap(referenced).getInt(8))
                    assertTrue(referenced.size < full.size)
                    assertContentEquals(full, codec.encodeRequest(codec.decodeStoredRequest(referenced, admissions::get)))
                }
            }
            batch.completion?.let { completion ->
                val full = codec.encodeCompletion(completion)
                assertContentEquals(full, codec.encodeCompletion(codec.decodeStoredCompletion(full) { error("unexpected reference") }))
                val referenced = codec.encodeReferencedCompletion(completion)
                assertEquals(8, ByteBuffer.wrap(referenced).getInt(8))
                assertTrue(referenced.size < full.size)
                assertContentEquals(full, codec.encodeCompletion(codec.decodeStoredCompletion(referenced, admissions::get)))
            }
        }
    }

    @Test
    fun `referenced frames reject missing or mismatched admissions and validate checksum before lookup`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val admissions = fixture.batches.mapNotNull { it.admission }
        val request = fixture.batches.mapNotNull { it.request }.first()
        val requestBytes = codec.encodeReferencedRequest(request)
        val completionBytes = codec.encodeReferencedCompletion(fixture.batches.mapNotNull { it.completion }.first())
        assertFailsWith<IllegalStateException> { codec.decodeStoredRequest(requestBytes) { null } }
        assertFailsWith<IllegalStateException> { codec.decodeStoredCompletion(completionBytes) { null } }
        assertFailsWith<IllegalStateException> { codec.decodeStoredRequest(requestBytes) { admissions.last() } }
        assertFailsWith<IllegalStateException> { codec.decodeStoredCompletion(completionBytes) { admissions.last() } }
        var lookups = 0
        val corrupt = completionBytes.copyOf().apply { this[16] = (this[16].toInt() xor 1).toByte() }
        assertFailsWith<IllegalStateException> {
            codec.decodeStoredCompletion(corrupt) {
                lookups++
                admissions.first()
            }
        }
        assertEquals(0, lookups)
        val invalidSequence = completionBytes.copyOf()
        ByteBuffer.wrap(invalidSequence).putLong(16, 0)
        val checksum = CRC32C().apply { update(invalidSequence, 16, invalidSequence.size - 20) }.value.toInt()
        ByteBuffer.wrap(invalidSequence).putInt(invalidSequence.size - 4, checksum)
        assertFailsWith<IllegalStateException> { codec.decodeStoredCompletion(invalidSequence) { admissions.first() } }
    }

    @Test
    fun `referenced completion retains its exact value limit`() {
        val fixture = TradingHistoryFixture().apply { completedTrade() }
        val completion = fixture.batches.mapNotNull { it.completion }.last()
        val bytes = codec.encodeReferencedCompletion(completion)
        assertTrue(bytes.size > 1_024)
        val exact = TradingHistoryCodec(bytes.size)
        assertContentEquals(bytes, exact.encodeReferencedCompletion(completion))
        assertContentEquals(
            codec.encodeCompletion(completion),
            codec.encodeCompletion(exact.decodeStoredCompletion(bytes) { completion.admission }),
        )
        assertFailsWith<IllegalStateException> { TradingHistoryCodec(bytes.size - 1).encodeReferencedCompletion(completion) }
        assertFailsWith<IllegalStateException> {
            TradingHistoryCodec(bytes.size - 1).decodeStoredCompletion(bytes) { completion.admission }
        }
    }

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
    fun `bulk UTF16 writes preserve the original frame and exact value size limit across chunks`() {
        val fixture = TradingHistoryFixture().apply { funding("seller", "BTC", 100) }
        val original =
            fixture.batches
                .single()
                .ledgerTransactions
                .single()
        // The surrogate pair crosses the 512-code-unit buffer boundary; isolated units stay exact too.
        val identifier = "a".repeat(511) + "\uD800\uDC00\u0000é한\uDFFF" + "b".repeat(1_030) + "\uD800"
        val transaction =
            LedgerTransaction(identifier, "", original.transactionType, original.occurredAt, original.postings)
        val expected = originalTransactionFrame(transaction)
        val exactCodec = TradingHistoryCodec(expected.size)

        assertContentEquals(expected, exactCodec.encodeTransaction(transaction))
        val restored = exactCodec.decodeTransaction(expected)
        assertEquals(identifier, restored.ledgerTransactionId)
        assertEquals("", restored.sourceEventId)
        assertFailsWith<IllegalStateException> { TradingHistoryCodec(expected.size - 1).encodeTransaction(transaction) }
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

    /** Independent version-one reference using the original DataOutputStream character encoding. */
    private fun originalTransactionFrame(value: LedgerTransaction): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            fun text(value: String) {
                output.writeInt(value.length)
                output.writeChars(value)
            }
            text(value.ledgerTransactionId)
            text(value.sourceEventId)
            text(value.transactionType.name)
            output.writeLong(value.occurredAt.epochSecond)
            output.writeInt(value.occurredAt.nano)
            output.writeInt(value.postings.size)
            for (posting in value.postings) {
                text(posting.accountId)
                text(posting.assetId.value)
                text(posting.side.name)
                output.writeLong(posting.amount.value)
            }
        }
        val body = payload.toByteArray()
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use { output ->
            output.writeInt(0x54484831)
            output.writeInt(1)
            output.writeInt(6)
            output.writeInt(body.size)
            output.write(body)
            output.writeInt(CRC32C().apply { update(body) }.value.toInt())
        }
        return frame.toByteArray()
    }
}

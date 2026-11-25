package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.trading.OrderAdmissionState
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceCommand as WireCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalancePrepared as WirePrepared
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceRejected as WireRejected

class DevelopmentBalanceBoundaryTest {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()

    @Test
    fun `읽을 수 없는 명령은 코어 호출 없이 끝나고 빈 상태도 차단하지 않는다`() {
        val valid = wire().build().toByteArray()
        val frame = protobufFrame(4, valid)
        val malformed =
            listOf(
                "헤더 잘림" to frame.copyOf(7),
                "본문 잘림" to frame.copyOf(frame.size - 1),
                "나노초 varint 잘림" to protobufFrame(4, valid.copyOf(valid.size - 1)),
                "varint 잘림" to protobufFrame(4, valid + byteArrayOf(0x20, 0x80.toByte())),
                "tag 0" to protobufFrame(4, valid + byteArrayOf(0)),
                "남은 바이트" to (frame + byteArrayOf(0)),
                "금액 0" to protobufFrame(4, wire().setAmount(0).build().toByteArray()),
                "음수 금액" to protobufFrame(4, wire().setAmount(-1).build().toByteArray()),
                "잘못된 나노초" to protobufFrame(4, wire().setOccurredAtNano(1_000_000_000).build().toByteArray()),
                "음수 나노초" to protobufFrame(4, wire().setOccurredAtNano(-1).build().toByteArray()),
                "최대 초 범위 밖" to protobufFrame(4, wire().setOccurredAtEpochSecond(Long.MAX_VALUE).build().toByteArray()),
                "최소 초 범위 밖" to protobufFrame(4, wire().setOccurredAtEpochSecond(Long.MIN_VALUE).build().toByteArray()),
                "준비 ID 빈 값" to protobufFrame(4, wire().setPreparationId("").build().toByteArray()),
                "준비 ID 길이 초과" to protobufFrame(4, wire().setPreparationId("x".repeat(121)).build().toByteArray()),
                "사용자 콜론" to protobufFrame(4, wire().setUserId("u:x").build().toByteArray()),
                "자산 NUL" to protobufFrame(4, wire().setAssetId("Q\u0000").build().toByteArray()),
                "자산 공백" to protobufFrame(4, wire().setAssetId(" ").build().toByteArray()),
            )
        malformed.forEach { (label, bytes) -> assertBeforeCore(label) { commands.decode(bytes) } }
    }

    @Test
    fun `4096바이트의 정확한 구간과 중복 필드는 읽되 1바이트라도 넘으면 거절한다`() {
        val original = wire().build().toByteArray()
        assertEquals(0, (4088 - original.size) % 2)
        // 알려진 ledger ID를 반복한다. 마지막 값 L1이 원본과 같으며 unknown padding은 쓰지 않는다.
        val padding = ByteArray(4088 - original.size) { if (it % 2 == 0) 0x2A else 0 }
        val lastId = byteArrayOf(0x2A, 0x02, 0x4C, 0x31)
        val body = original + padding.copyOf(padding.size - lastId.size) + lastId
        val frame = protobufFrame(4, body)
        assertEquals(4096, frame.size)
        assertEquals(command(), commands.decode(frame))
        assertFailsWith<AdmissionMessageException> { commands.decode(frame + byteArrayOf(0)) }

        val shared = ByteArray(11 + frame.size + 7) { 0x7F }
        frame.copyInto(shared, 11)
        val decoded = commands.decode(shared, 11, frame.size)
        shared.fill(0)
        assertEquals(command(), decoded)
        listOf(-1 to frame.size, Int.MAX_VALUE to frame.size, 0 to -1, 0 to frame.size + 1, 1 to frame.size).forEach { (offset, length) ->
            assertFailsWith<AdmissionMessageException> { commands.decode(frame, offset, length) }
        }
    }

    @Test
    fun `문자 수와 UTF8 바이트 경계를 함께 지키고 원본 유니코드를 보존한다`() {
        val unicode = "😀".repeat(64)
        val allowed = command().copy(opening = OpeningBalance("x".repeat(120), UserId(unicode), AssetId(unicode), Amount(1)))
        assertEquals(256, unicode.toByteArray(Charsets.UTF_8).size)
        assertEquals(allowed, commands.decode(commands.encode(allowed)))
        val exact = command().copy(opening = OpeningBalance(" e\u0301한글 ", UserId(" u "), AssetId(" Q "), Amount(1)))
        assertEquals(exact, commands.decode(commands.encode(exact)))

        val tooManyBytes = command().copy(opening = OpeningBalance("😀".repeat(65), UserId("u"), AssetId("Q"), Amount(1)))
        assertFailsWith<AdmissionMessageException> { commands.encode(tooManyBytes) }
        val invalidStrings = listOf("x".repeat(257), "\uD800", "\uDC00")
        invalidStrings.forEach { value ->
            assertFailsWith<AdmissionMessageException> { commands.encode(command().copy(ledgerTransactionId = value)) }
        }
        val base = wire().clearLedgerTransactionId().build().toByteArray()
        listOf("2a02c328", "2a02c080", "2a03eda080", "2a800278").forEach { broken ->
            assertBeforeCore(broken) { commands.decode(protobufFrame(4, base + broken.hexToByteArray())) }
        }
        val original = wire().build()
        original.descriptorForType.fields.filter { it.type == Descriptors.FieldDescriptor.Type.STRING }.forEach { field ->
            val bytes =
                original
                    .toBuilder()
                    .setField(field, "x".repeat(257))
                    .build()
                    .toByteArray()
            assertBeforeCore(field.name) { commands.decode(protobufFrame(4, bytes)) }
        }
    }

    @Test
    fun `명령의 미사용 원장 문맥은 보존하지만 성공 응답의 원장 ID는 실제 유효해야 한다`() {
        listOf("", " ", "x\u0000", "x".repeat(65)).forEach { id ->
            val submitted = command().copy(ledgerTransactionId = id)
            assertEquals(submitted, commands.decode(commands.encode(submitted)))
            val prepared = DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(submitted.opening, id, submitted.occurredAt, true))
            assertFailsWith<AdmissionMessageException> { results.encode(prepared) }
            val raw = wire().setLedgerTransactionId(id).build().toByteArray() + byteArrayOf(0x40, 0x01)
            assertFailsWith<AdmissionMessageException> { results.decode(protobufFrame(5, raw)) }
        }
        val id = "😀".repeat(64)
        val prepared = DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(command().opening, id, Instant.EPOCH, false))
        assertEquals(prepared, results.decode(results.encode(prepared)))
    }

    @Test
    fun `바뀐 헤더는 거절하며 결과도 공유 버퍼에서 복사된 값으로 남는다`() {
        val frame = commands.encode(command())
        listOf(0 to 0, 0 to frame.size, 4 to 86, 6 to 1, 6 to 3, 2 to 99).forEach { (index, value) ->
            val broken = frame.copyOf()
            ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putShort(index, value.toShort())
            assertBeforeCore("헤더 $index=$value") { commands.decode(broken) }
        }
        val prepared = DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(command().opening, "L1", command().occurredAt, false))
        val encoded = results.encode(prepared)
        val retained = encoded.copyOf()
        val shared = ByteArray(encoded.size + 20)
        encoded.copyInto(shared, 9)
        val decoded = results.decode(shared, 9, encoded.size)
        shared.fill(0)
        results.encode(prepared.copy(result = prepared.result.copy(ledgerTransactionId = "L2", alreadyPrepared = true)))
        assertEquals(prepared, decoded)
        assertContentEquals(retained, encoded)
        assertFailsWith<AdmissionMessageException> { results.decode(encoded, 9, encoded.size) }
        assertFailsWith<AdmissionMessageException> { results.decode(encoded + byteArrayOf(0)) }
    }

    @Test
    fun `성공과 거절도 손상된 원본 문자열 금액 시간 구간을 받아들이지 않는다`() {
        resultWires().forEach { (kind, message) ->
            val fields = message.descriptorForType.fields.associateBy { it.number }
            val invalidValues =
                listOf(
                    1 to "",
                    1 to "x".repeat(121),
                    2 to " ",
                    2 to "u:x",
                    2 to "x".repeat(65),
                    3 to "Q\u0000",
                    3 to "x".repeat(65),
                    4 to 0L,
                    4 to -1L,
                )
            invalidValues.forEach { (number, invalid) ->
                val body =
                    message
                        .toBuilder()
                        .setField(fields.getValue(number), invalid)
                        .build()
                        .toByteArray()
                assertFailsWith<AdmissionMessageException>(
                    "kind $kind field $number=$invalid",
                ) { results.decode(protobufFrame(kind, body)) }
            }
            fields.values.filter { it.type == Descriptors.FieldDescriptor.Type.STRING }.forEach { field ->
                val tooLong =
                    message
                        .toBuilder()
                        .setField(field, "x".repeat(257))
                        .build()
                        .toByteArray()
                assertFailsWith<AdmissionMessageException> { results.decode(protobufFrame(kind, tooLong)) }
                val without =
                    message
                        .toBuilder()
                        .clearField(field)
                        .build()
                        .toByteArray()
                val malformedString = byteArrayOf(((field.number shl 3) or 2).toByte(), 2, 0xC3.toByte(), 0x28)
                assertFailsWith<AdmissionMessageException> { results.decode(protobufFrame(kind, without + malformedString)) }
            }
            if (kind == 5) {
                listOf(6 to Long.MIN_VALUE, 6 to Long.MAX_VALUE, 7 to -1, 7 to 1_000_000_000).forEach { (number, invalid) ->
                    val body =
                        message
                            .toBuilder()
                            .setField(fields.getValue(number), invalid)
                            .build()
                            .toByteArray()
                    assertFailsWith<AdmissionMessageException> { results.decode(protobufFrame(kind, body)) }
                }
            }
            val body = message.toByteArray()
            val frame = protobufFrame(kind, body)
            listOf(
                frame.copyOf(7),
                frame.copyOf(frame.size - 1),
                frame + byteArrayOf(0),
                protobufFrame(kind, body + byteArrayOf(0)),
                protobufFrame(kind, body + byteArrayOf(0x20, 0x80.toByte())),
                protobufFrame(kind, ByteArray(4089)),
            ).forEach { broken -> assertFailsWith<AdmissionMessageException> { results.decode(broken) } }
            listOf(-1 to frame.size, Int.MAX_VALUE to frame.size, 0 to -1, 0 to frame.size + 1).forEach { (offset, length) ->
                assertFailsWith<AdmissionMessageException> { results.decode(frame, offset, length) }
            }
        }
    }

    @Test
    fun `성공과 거절도 UTF8 경계의 원문과 4096바이트 마지막 필드를 유지한다`() {
        val unicode = "😀".repeat(64)
        val opening = OpeningBalance("x".repeat(120), UserId(unicode), AssetId(unicode), Amount(1))
        val payloads =
            listOf(
                DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(opening, unicode, Instant.EPOCH, false)),
                DevelopmentBalanceResultPayload.Rejected(opening, DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED),
            )
        payloads.forEach { assertEquals(it, results.decode(results.encode(it))) }
        val tooManyBytes = opening.copy(preparationId = "😀".repeat(65))
        assertFailsWith<AdmissionMessageException> {
            results.encode(DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(tooManyBytes, "L1", Instant.EPOCH, false)))
        }
        assertFailsWith<AdmissionMessageException> {
            results.encode(DevelopmentBalanceResultPayload.Rejected(tooManyBytes, DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED))
        }
        resultWires().forEach { (kind, message) ->
            var body = message.toByteArray()
            // amount=1000은 3바이트다. 홀짝을 맞춘 뒤 동일한 알려진 필드만 채운다.
            if ((4088 - body.size) % 2 != 0) body += "20e807".hexToByteArray()
            val lastField = if (kind == 5) byteArrayOf(0x40, 0) else byteArrayOf(0x28, 1)
            body += ByteArray(4088 - body.size) { lastField[it % 2] }
            val frame = protobufFrame(kind, body)
            assertEquals(4096, frame.size)
            val expected = results.decode(protobufFrame(kind, message.toByteArray()))
            assertEquals(expected, results.decode(frame))
            assertFailsWith<AdmissionMessageException> { results.decode(frame + byteArrayOf(0)) }
        }
    }

    private fun resultWires(): List<Pair<Int, Message>> {
        val body = wire().build().toByteArray()
        return listOf(
            5 to WirePrepared.parseFrom(body + byteArrayOf(0x40, 0)),
            6 to
                WireRejected
                    .newBuilder()
                    .setPreparationId("P1")
                    .setUserId("u")
                    .setAssetId("Q")
                    .setAmount(1000)
                    .setReasonValue(1)
                    .build(),
        )
    }

    private fun assertBeforeCore(
        label: String,
        convert: () -> DevelopmentBalanceCommandPayload,
    ) {
        val state = OrderAdmissionState(emptyList(), admissionCalculator())
        var calls = 0
        assertFailsWith<AdmissionMessageException>(label) {
            val decoded = convert()
            calls++
            state.prepareDevelopmentBalance(decoded.opening, decoded.ledgerTransactionId, decoded.occurredAt)
        }
        assertEquals(0, calls, label)
        assertFalse(state.isBlocked, label)
        assertEquals(emptyList(), state.snapshot().balances, label)
        assertEquals(emptyList(), state.snapshot().preparations, label)
    }

    private fun command() =
        DevelopmentBalanceCommandPayload(
            OpeningBalance("P1", UserId("u"), AssetId("Q"), Amount(1000)),
            "L1",
            Instant.ofEpochSecond(1_234_567_890, 123_456_789),
        )

    private fun wire(): WireCommand.Builder =
        WireCommand
            .newBuilder()
            .setPreparationId("P1")
            .setUserId("u")
            .setAssetId("Q")
            .setAmount(1000)
            .setLedgerTransactionId("L1")
            .setOccurredAtEpochSecond(1_234_567_890)
            .setOccurredAtNano(123_456_789)
}

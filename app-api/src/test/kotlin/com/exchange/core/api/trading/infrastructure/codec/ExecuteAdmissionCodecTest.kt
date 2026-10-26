package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.ExecuteAdmission
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecuteAdmissionCodecTest {
    private val codec = ExecuteAdmissionCodec()

    @Test
    fun `내부 실행은 승인 순번 하나만 kind7로 전송한다`() {
        val command = ExecuteAdmissionPayload(1)
        // 본문 08 01 = 필드 1의 승인 순번 1. 기존 schema87 version2를 그대로 사용한다.
        val expected = byteArrayOf(2, 0, 7, 0, 87, 0, 2, 0, 8, 1)
        assertContentEquals(expected, codec.encode(command))
        assertEquals(command, codec.decode(expected))
        assertEquals(listOf(1), ExecuteAdmission.getDescriptor().fields.map { it.number })
        assertEquals(listOf("admission_sequence"), ExecuteAdmission.getDescriptor().fields.map { it.name })
    }

    @Test
    fun `승인 순번은 양수 Long 전체를 손실 없이 보존한다`() {
        listOf(1L, 128L, Long.MAX_VALUE).forEach { sequence ->
            val command = ExecuteAdmissionPayload(sequence)
            val expected =
                protobufFrame(
                    7,
                    ExecuteAdmission
                        .newBuilder()
                        .setAdmissionSequence(sequence)
                        .build()
                        .toByteArray(),
                )
            assertContentEquals(expected, codec.encode(command))
            assertEquals(command, codec.decode(expected))
        }
    }

    @Test
    fun `승인 순번 누락과 0 음수는 실행 명령으로 읽지 않는다`() {
        assertFailsWith<AdmissionMessageException> { codec.decode(protobufFrame(7, byteArrayOf())) }
        listOf(0L, -1L, Long.MIN_VALUE).forEach { sequence ->
            val body =
                ExecuteAdmission
                    .newBuilder()
                    .setAdmissionSequence(sequence)
                    .build()
                    .toByteArray()
            assertFailsWith<AdmissionMessageException>("decode sequence=$sequence") { codec.decode(protobufFrame(7, body)) }
            assertFailsWith<AdmissionMessageException>("encode sequence=$sequence") { codec.encode(ExecuteAdmissionPayload(sequence)) }
        }
    }

    @Test
    fun `미지 필드와 잘못된 wire type은 정상 순번과 함께 있어도 거절한다`() {
        val valid = byteArrayOf(8, 1)
        val wrongType = byteArrayOf(10, 1, 1)
        val unknownField = byteArrayOf(16, 1)
        listOf(wrongType, valid + wrongType, wrongType + valid, valid + unknownField).forEach { body ->
            assertFailsWith<AdmissionMessageException> { codec.decode(protobufFrame(7, body)) }
        }
    }

    @Test
    fun `다른 종류 스키마 버전과 잘린 프레임을 거절한다`() {
        val valid = codec.encode(ExecuteAdmissionPayload(1))
        (1..6).plus(99).forEach { kind ->
            assertFailsWith<AdmissionMessageException>("다른 종류 $kind") { codec.decode(protobufFrame(kind, byteArrayOf(8, 1))) }
        }
        val differentSchema = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 86) }
        val differentVersion = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(6, 3) }
        val frames =
            listOf(
                differentSchema,
                differentVersion,
                valid.copyOf(7),
                valid.copyOf(valid.size - 1),
                valid + byteArrayOf(0),
                protobufFrame(7, byteArrayOf(8, 0x80.toByte())),
                protobufFrame(7, ByteArray(4089)),
            )
        frames.forEach { bytes -> assertFailsWith<AdmissionMessageException> { codec.decode(bytes) } }
    }

    @Test
    fun `공유 배열의 지정 구간만 읽고 잘못된 구간을 거절한다`() {
        val command = ExecuteAdmissionPayload(Long.MAX_VALUE)
        val encoded = codec.encode(command)
        val offset = 11
        val shared = ByteArray(offset + encoded.size + 5) { 0xA5.toByte() }
        encoded.copyInto(shared, offset)
        val decoded = codec.decode(shared, offset, encoded.size)
        shared.fill(0)
        assertEquals(command, decoded)
        listOf(-1 to encoded.size, Int.MAX_VALUE to encoded.size, 0 to -1, 0 to encoded.size + 1).forEach { (start, size) ->
            assertFailsWith<AdmissionMessageException> { codec.decode(encoded, start, size) }
        }
    }

    @Test
    fun `반복된 알려진 필드는 Protobuf의 마지막 값 규칙과 최종 양수 검사를 따른다`() {
        assertEquals(ExecuteAdmissionPayload(2), codec.decode(protobufFrame(7, byteArrayOf(8, 1, 8, 2))))
        assertFailsWith<AdmissionMessageException> { codec.decode(protobufFrame(7, byteArrayOf(8, 1, 8, 0))) }
    }

    @Test
    fun `4096바이트 상한은 포함하고 상한을 넘는 프레임은 거절한다`() {
        val body = ByteArray(4088) { if (it % 2 == 0) 8 else 1 }
        val frame = protobufFrame(7, body)
        assertEquals(4096, frame.size)
        assertEquals(ExecuteAdmissionPayload(1), codec.decode(frame))
        assertFailsWith<AdmissionMessageException> { codec.decode(frame + byteArrayOf(0)) }
    }

    @Test
    fun `기존 외부 명령과 결과 코덱은 내부 kind7을 받아들이지 않는다`() {
        val internal = codec.encode(ExecuteAdmissionPayload(1))
        assertFailsWith<AdmissionMessageException> { AdmissionCommandCodec().decode(internal) }
        assertFailsWith<AdmissionMessageException> { AdmissionResultCodec().decode(internal) }
        assertFailsWith<AdmissionMessageException> { DevelopmentBalanceCommandCodec().decode(internal) }
        assertFailsWith<AdmissionMessageException> { DevelopmentBalanceResultCodec().decode(internal) }
    }
}

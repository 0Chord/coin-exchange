package com.exchange.core.api.trading.infrastructure.codec

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.Parser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException

/** 메시지를 해석할 수 없을 때 발생한다. 접수 코어의 업무 거절·내부 오류와 구분한다. */
class AdmissionMessageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal object AdmissionMessageFormat {
    const val HEADER_LENGTH = 8
    const val MAX_MESSAGE_LENGTH = 4096
    const val MAX_IDENTIFIER_LENGTH = 256
    const val COMMAND = 1
    const val ACCEPTED = 2
    const val REJECTED = 3
    private const val SCHEMA = 87
    private const val VERSION = 2

    fun text(value: String): String {
        verify(value.length <= MAX_IDENTIFIER_LENGTH, "식별자의 전송 길이를 초과했습니다")
        val encoded =
            try {
                Charsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            } catch (error: CharacterCodingException) {
                throw AdmissionMessageException("잘못된 UTF-8 원본 문자열입니다", error)
            }
        verify(encoded.remaining() <= MAX_IDENTIFIER_LENGTH, "식별자의 전송 길이를 초과했습니다")
        return value
    }

    fun encode(
        kind: Int,
        message: Message,
    ): ByteArray {
        val size = message.serializedSize
        verify(size <= MAX_MESSAGE_LENGTH - HEADER_LENGTH, "메시지 전체 길이를 초과했습니다")
        val bytes = ByteArray(HEADER_LENGTH + size)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(size.toShort())
            .putShort(kind.toShort())
            .putShort(SCHEMA.toShort())
            .putShort(VERSION.toShort())
        val output = CodedOutputStream.newInstance(bytes, HEADER_LENGTH, size)
        message.writeTo(output)
        output.checkNoSpaceLeft()
        return bytes
    }

    fun verify(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AdmissionMessageException(message)
    }

    fun verifyKnownFields(message: Message) {
        verify(message.unknownFields.asMap().isEmpty(), "지원하지 않는 필드가 있습니다")
    }

    fun <T> readValues(read: () -> T): T =
        try {
            read()
        } catch (error: AdmissionMessageException) {
            throw error
        } catch (error: InvalidProtocolBufferException) {
            throw AdmissionMessageException("Protobuf 본문이 잘못됐습니다", error)
        } catch (error: IllegalArgumentException) {
            throw AdmissionMessageException("메시지의 값 또는 열거 코드가 잘못됐습니다", error)
        } catch (error: DateTimeException) {
            throw AdmissionMessageException("메시지의 시각 범위가 잘못됐습니다", error)
        }
}

/** 전달받은 한 프레임의 구간만 읽는다. 본문 길이로 잘림·이어 붙은 데이터를 구분한다. */
internal class AdmissionMessageReader(
    private val bytes: ByteArray,
    private val offset: Int,
    private val length: Int,
) {
    val kind: Int

    init {
        AdmissionMessageFormat.verify(
            offset >= 0 && length >= AdmissionMessageFormat.HEADER_LENGTH &&
                length <= AdmissionMessageFormat.MAX_MESSAGE_LENGTH && offset <= bytes.size - length,
            "메시지 구간 또는 전체 길이가 잘못됐습니다",
        )
        val header = ByteBuffer.wrap(bytes, offset, AdmissionMessageFormat.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        val bodyLength = header.short.toInt() and 0xFFFF
        kind = header.short.toInt() and 0xFFFF
        val schema = header.short.toInt() and 0xFFFF
        val version = header.short.toInt() and 0xFFFF
        AdmissionMessageFormat.verify(schema == 87 && version == 2, "지원하지 않는 스키마 또는 버전입니다")
        AdmissionMessageFormat.verify(bodyLength == length - AdmissionMessageFormat.HEADER_LENGTH, "본문 길이와 메시지 구간이 다릅니다")
    }

    fun <T : Message> parse(parser: Parser<T>): T {
        val message = parser.parseFrom(bytes, offset + AdmissionMessageFormat.HEADER_LENGTH, length - AdmissionMessageFormat.HEADER_LENGTH)
        AdmissionMessageFormat.verifyKnownFields(message)
        return message
    }
}

package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.MessageHeaderDecoder
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteBuffer
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

    fun text(value: String): ByteArray {
        // UTF-8 한 바이트보다 적게 표현되는 글자는 없으므로 큰 입력은 변환 전에도 거절할 수 있다.
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
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    fun verify(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AdmissionMessageException(message)
    }

    fun <T> readValues(read: () -> T): T =
        try {
            read()
        } catch (error: AdmissionMessageException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw AdmissionMessageException("메시지의 값 또는 열거 코드가 잘못됐습니다", error)
        } catch (error: DateTimeException) {
            throw AdmissionMessageException("메시지의 시각 범위가 잘못됐습니다", error)
        }
}

/** 큰 공유 버퍼에서도 전달받은 한 메시지의 구간만 읽는다. 반환 값은 버퍼를 참조하지 않는다. */
internal class AdmissionMessageReader(
    bytes: ByteArray,
    offset: Int,
    length: Int,
) {
    val buffer: UnsafeBuffer
    val templateId: Int
    private val blockLength: Int

    init {
        AdmissionMessageFormat.verify(
            offset >= 0 && length >= AdmissionMessageFormat.HEADER_LENGTH &&
                length <= AdmissionMessageFormat.MAX_MESSAGE_LENGTH && offset <= bytes.size - length,
            "메시지 구간 또는 전체 길이가 잘못됐습니다",
        )
        buffer = UnsafeBuffer(bytes, offset, length)
        val header = MessageHeaderDecoder().wrap(buffer, 0)
        AdmissionMessageFormat.verify(header.schemaId() == 87 && header.version() == 1, "지원하지 않는 스키마 또는 버전입니다")
        templateId = header.templateId()
        blockLength = header.blockLength()
    }

    fun expect(
        template: Int,
        fixedLength: Int,
    ) {
        AdmissionMessageFormat.verify(templateId == template && blockLength == fixedLength, "메시지 종류 또는 고정 필드 길이가 다릅니다")
        AdmissionMessageFormat.verify(buffer.capacity() >= AdmissionMessageFormat.HEADER_LENGTH + fixedLength, "고정 필드가 잘렸습니다")
    }

    fun text(
        position: Int,
        getLength: () -> Int,
        readBytes: (ByteArray, Int, Int) -> Int,
    ): String {
        AdmissionMessageFormat.verify(position <= buffer.capacity() - 2, "문자열 길이 필드가 잘렸습니다")
        val count = getLength()
        AdmissionMessageFormat.verify(
            count in 0..AdmissionMessageFormat.MAX_IDENTIFIER_LENGTH && count <= buffer.capacity() - position - 2,
            "문자열 길이가 한도 또는 메시지 구간을 넘습니다",
        )
        val bytes = ByteArray(count)
        check(readBytes(bytes, 0, count) == count) { "생성 디코더가 문자열 전체를 읽지 못했습니다" }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw AdmissionMessageException("문자열의 UTF-8 바이트가 잘못됐습니다", error)
        }
    }

    fun finish(position: Int) {
        AdmissionMessageFormat.verify(position == buffer.capacity(), "메시지 뒤에 해석하지 않은 바이트가 남았습니다")
    }
}

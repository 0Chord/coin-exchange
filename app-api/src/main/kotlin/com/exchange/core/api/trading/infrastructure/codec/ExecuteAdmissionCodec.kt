package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.ExecuteAdmission
import org.agrona.MutableDirectBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** 바이트의 형식만 검사한다. 내부 출처와 승인 존재·실행 순서는 호출자가 검사한다. */
class ExecuteAdmissionCodec {
    /** 기존 Protobuf의 양수 int64 필드 하나를 claim에 쓸 때 필요한 전체 길이다. */
    internal fun encodedLength(admissionSequence: Long): Int {
        AdmissionMessageFormat.verify(admissionSequence > 0, "실행할 승인 순번은 양수여야 합니다")
        val varintLength = (Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(admissionSequence) + 6) / 7
        return AdmissionMessageFormat.HEADER_LENGTH + 1 + varintLength
    }

    /** 한 명령의 기존 외피와 Protobuf 바이트만 쓴다. claim의 세션 헤더는 호출자가 보존한다. */
    internal fun encodeInto(
        admissionSequence: Long,
        buffer: MutableDirectBuffer,
        offset: Int,
    ): Int {
        val length = encodedLength(admissionSequence)
        AdmissionMessageFormat.verify(offset >= 0 && offset <= buffer.capacity() - length, "메시지 출력 구간이 잘못됐습니다")
        // 스키마·버전은 공통 인코더에서 가져오고, 이 명령의 본문 길이만 덮어쓴다.
        buffer.putLong(offset, EMPTY_HEADER_BITS or (length - AdmissionMessageFormat.HEADER_LENGTH).toLong(), LITTLE_ENDIAN)
        var cursor = offset + AdmissionMessageFormat.HEADER_LENGTH
        buffer.putByte(cursor++, 0x08.toByte()) // optional int64 admission_sequence = 1
        var remaining = admissionSequence
        while (remaining >= 0x80) {
            buffer.putByte(cursor++, ((remaining and 0x7F) or 0x80).toByte())
            remaining = remaining ushr 7
        }
        buffer.putByte(cursor, remaining.toByte())
        return length
    }

    fun encode(command: ExecuteAdmissionPayload): ByteArray {
        AdmissionMessageFormat.verify(command.admissionSequence > 0, "실행할 승인 순번은 양수여야 합니다")
        val message = ExecuteAdmission.newBuilder().setAdmissionSequence(command.admissionSequence).build()
        return AdmissionMessageFormat.encode(AdmissionMessageFormat.EXECUTE_ADMISSION, message)
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): ExecuteAdmissionPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            AdmissionMessageFormat.verify(reader.kind == AdmissionMessageFormat.EXECUTE_ADMISSION, "내부 실행 명령이 아닙니다")
            val message = reader.parse(ExecuteAdmission.parser())
            AdmissionMessageFormat.verify(message.hasAdmissionSequence(), "실행할 승인 순번이 누락됐습니다")
            AdmissionMessageFormat.verify(message.admissionSequence > 0, "실행할 승인 순번은 양수여야 합니다")
            ExecuteAdmissionPayload(message.admissionSequence)
        }

    private companion object {
        val EMPTY_HEADER_BITS: Long =
            ByteBuffer
                .wrap(AdmissionMessageFormat.encode(AdmissionMessageFormat.EXECUTE_ADMISSION, ExecuteAdmission.getDefaultInstance()))
                .order(LITTLE_ENDIAN)
                .getLong(0)
    }
}

package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.ExecuteAdmission

/** 바이트의 형식만 검사한다. 내부 출처와 승인 존재·실행 순서는 호출자가 검사한다. */
class ExecuteAdmissionCodec {
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
}

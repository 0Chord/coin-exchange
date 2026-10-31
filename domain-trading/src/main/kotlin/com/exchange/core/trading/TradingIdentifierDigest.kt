package com.exchange.core.trading

import java.security.MessageDigest

private val identifierDigests = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

/** 기존 ID 원본의 big-endian 정수 표현. 호출마다 독립된 최종 해시 입력 배열을 채운다. */
internal fun writeTradingIdentifierInt(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
}

internal fun writeTradingIdentifierLong(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    writeTradingIdentifierInt(target, offset, (value ushr 32).toInt())
    writeTradingIdentifierInt(target, offset + Int.SIZE_BYTES, value.toInt())
}

/** 원장 ID의 원본 바이트 계약은 호출자가 유지하고, 해시 실행기만 스레드 안에서 재사용한다. */
internal fun tradingIdentifierSha256(bytes: ByteArray): ByteArray {
    val digest = identifierDigests.get()
    return try {
        digest.reset()
        digest.digest(bytes)
    } catch (failure: Throwable) {
        // 예상 밖 해시 실패 뒤에도 다음 호출이 부분 입력이나 손상된 실행기를 재사용하지 않는다.
        identifierDigests.remove()
        throw failure
    }
}

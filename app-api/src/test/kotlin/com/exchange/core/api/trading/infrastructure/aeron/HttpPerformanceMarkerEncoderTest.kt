package com.exchange.core.api.trading.infrastructure.aeron

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class HttpPerformanceMarkerEncoderTest {
    @Test
    fun `재사용 인코더는 기존 DataOutputStream 형식과 NUL 및 Unicode 원문을 바이트 단위로 보존한다`() {
        val encoder = HttpPerformanceMarkerCodec.Encoder()
        val markers =
            listOf(
                marker(),
                marker().copy(
                    runId = "실행-\u0000-😀",
                    role = "FOLLOWER",
                    memberId = 2,
                    userId = "사용자-\u007f-\u0080-\u07ff-\u0800",
                    requestId = "요청-\u0000-\ud800",
                    orderId = "주문-\udc00-😀",
                    admissionSequence = Long.MAX_VALUE,
                    logPosition = Long.MAX_VALUE,
                    tradeCount = Int.MAX_VALUE.toLong() + 1,
                    settlementCount = Long.MAX_VALUE,
                ),
                marker().copy(runId = "R".repeat(256), userId = "U".repeat(256), requestId = "Q".repeat(256), orderId = "O".repeat(256)),
            )

        markers.forEach { expected ->
            val bytes = encoder.encode(expected)
            assertContentEquals(referenceBytes(expected), bytes)
            assertContentEquals(bytes, HttpPerformanceMarkerCodec.encode(expected))
            assertEquals(expected, HttpPerformanceMarkerCodec.decode(bytes))
        }
    }

    @Test
    fun `반복 인코딩은 이전 반환 배열을 바꾸거나 다음 payload에 이전 바이트를 덧붙이지 않는다`() {
        val encoder = HttpPerformanceMarkerCodec.Encoder()
        val first = marker()
        val firstBytes = encoder.encode(first)
        val repeatedBytes = encoder.encode(first)
        assertNotSame(firstBytes, repeatedBytes)

        repeat(100) { index ->
            val next =
                marker().copy(
                    memberId = index % 3,
                    requestId = "요청-${"x".repeat(index % 80)}-$index",
                    orderId = "주문-$index",
                    admissionSequence = index + 1L,
                )
            val bytes = encoder.encode(next)
            assertContentEquals(referenceBytes(next), bytes)
            assertEquals(next, HttpPerformanceMarkerCodec.decode(bytes))
        }

        assertContentEquals(referenceBytes(first), firstBytes)
        assertContentEquals(referenceBytes(first), repeatedBytes)
        firstBytes.fill(0)
        assertContentEquals(referenceBytes(first), encoder.encode(first))
        assertContentEquals(referenceBytes(first), repeatedBytes)
    }

    @Test
    fun `문자열 거절 뒤 다음 인코딩은 부분 payload를 버리고 원래 검증을 유지한다`() {
        val encoder = HttpPerformanceMarkerCodec.Encoder()
        val valid = marker()
        val retained = encoder.encode(valid)
        val invalidStrings = listOf("", " \t\n", "x".repeat(257))
        val invalidMarkers =
            invalidStrings.flatMap { value ->
                listOf(
                    valid.copy(runId = value),
                    valid.copy(role = value),
                    valid.copy(userId = value),
                    valid.copy(requestId = value),
                    valid.copy(orderId = value),
                )
            }

        invalidMarkers.forEach { invalid ->
            val failure = assertFailsWith<IllegalArgumentException> { encoder.encode(invalid) }
            assertEquals("완료 표식 문자열 크기가 잘못됐습니다", failure.message)
            assertContentEquals(referenceBytes(valid), encoder.encode(valid))
            assertContentEquals(referenceBytes(valid), retained)
        }

        val numericValues =
            valid.copy(
                memberId = -1,
                admissionSequence = Long.MIN_VALUE,
                logPosition = -1,
                tradeCount = -1,
                settlementCount = -1,
            )
        assertContentEquals(referenceBytes(numericValues), encoder.encode(numericValues))
        assertFailsWith<IllegalArgumentException> { HttpPerformanceMarkerCodec.decode(encoder.encode(numericValues)) }
    }

    @Test
    fun `2048 바이트는 허용하고 초과 및 문자열 거절 뒤에도 다음 원문을 보존한다`() {
        val encoder = HttpPerformanceMarkerCodec.Encoder()
        val boundary =
            marker().copy(
                runId = "가".repeat(256),
                userId = "가".repeat(256),
                requestId = "가".repeat(151),
                orderId = "ABC",
            )
        val bytes = encoder.encode(boundary)
        assertEquals(2048, bytes.size)
        assertContentEquals(referenceBytes(boundary), bytes)
        assertEquals(boundary, HttpPerformanceMarkerCodec.decode(bytes))

        val tooLarge = boundary.copy(orderId = "ABCD")
        assertEquals(2049, referenceBytes(tooLarge).size)
        assertFailsWith<IllegalArgumentException> { encoder.encode(tooLarge) }
        assertContentEquals(referenceBytes(marker()), encoder.encode(marker()))
        assertContentEquals(referenceBytes(boundary), bytes)

        val stringFailure =
            assertFailsWith<IllegalArgumentException> {
                encoder.encode(boundary.copy(requestId = "가".repeat(256), orderId = " "))
            }
        assertEquals("완료 표식 문자열 크기가 잘못됐습니다", stringFailure.message)
        assertContentEquals(referenceBytes(boundary), encoder.encode(boundary))
        assertContentEquals(referenceBytes(boundary), bytes)
    }

    private fun marker() = HttpPerformanceCompletion("marker-test", 0, "LEADER", "BUYER", "M-1", "M-1", 1, 128, 1, 1)

    /** 재사용 이전의 wire writer를 독립된 새 스트림으로 재현한다. 크기 제한은 호출부에서 검증한다. */
    private fun referenceBytes(marker: HttpPerformanceCompletion): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x48503134)
                listOf(marker.runId, marker.role, marker.userId, marker.requestId, marker.orderId).forEach(output::writeUTF)
                output.writeInt(marker.memberId)
                output.writeLong(marker.admissionSequence)
                output.writeLong(marker.logPosition)
                output.writeLong(marker.tradeCount)
                output.writeLong(marker.settlementCount)
            }
            bytes.toByteArray()
        }
}

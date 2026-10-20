package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordedInputComparisonTest {
    @Test
    fun `업무 해석 실패 앞뒤의 불일치를 모두 보존한다`() {
        val (expected, read) = fixture()
        assertEquals(RecordingComparisonStatus.MATCH, compareRecordedInputs(expected, read).status)

        invalidPayloads(read.inputs[1].bytes).forEach { invalid ->
            val changedExpected =
                listOf(
                    expected[0].copy(values = expected[0].values + ("amount" to 1_001L)),
                    expected[1],
                    expected[2].copy(values = expected[2].values + ("assetId" to "BTC")),
                )
            val changedRead = read.copy(inputs = read.inputs.toMutableList().apply { this[1] = this[1].copy(bytes = invalid) })

            val result = compareRecordedInputs(changedExpected, changedRead)

            assertEquals(RecordingComparisonStatus.MISMATCH, result.status)
            assertEquals(4, result.reasons.size)
            assertTrue(result.reasons.any { it.startsWith("입력 1 amount:") })
            assertTrue(result.reasons.any { it == "입력 2 원본 바이트 불일치" })
            assertTrue(result.reasons.any { it.startsWith("입력 2 업무 값 해석 실패:") })
            assertTrue(result.reasons.any { it.startsWith("입력 3 assetId:") })
        }
    }

    @Test
    fun `같은 바이트의 해석 실패 뒤에도 비교를 계속하고 알려진 차이로 최종 판정한다`() {
        val (expected, read) = fixture()
        invalidPayloads(read.inputs[0].bytes).forEach { invalid ->
            val sameBytes = expected.toMutableList().apply { this[0] = this[0].copy(bytes = invalid.copyOf()) }
            val changedRead = read.copy(inputs = read.inputs.toMutableList().apply { this[0] = this[0].copy(bytes = invalid) })

            val unknown = compareRecordedInputs(sameBytes, changedRead)

            assertEquals(RecordingComparisonStatus.UNVERIFIABLE, unknown.status)
            assertEquals(1, unknown.reasons.size)
            assertTrue(unknown.reasons.single().startsWith("입력 1 업무 값 해석 실패:"))

            val laterDifference = sameBytes.toMutableList().apply { this[2] = this[2].copy(values = this[2].values + ("amount" to 1_001L)) }
            val mismatch = compareRecordedInputs(laterDifference, changedRead)

            assertEquals(RecordingComparisonStatus.MISMATCH, mismatch.status)
            assertEquals(2, mismatch.reasons.size)
            assertTrue(mismatch.reasons.any { it.startsWith("입력 1 업무 값 해석 실패:") })
            assertTrue(mismatch.reasons.any { it.startsWith("입력 3 amount:") })
        }
    }

    private fun invalidPayloads(bytes: ByteArray): List<ByteArray> =
        listOf(
            bytes.copyOf().apply {
                this[2] = 127
                this[3] = 0
            }, // 미지원 업무 kind. Cluster wrapper와는 별개다.
            bytes.copyOf().apply { this[8] = 0 }, // 유효한 길이의 Protobuf 본문에 금지된 tag 0을 넣는다.
        )

    /** 읽기 완료 모델을 통제하는 비교기 단위 검사다. 디스크 손상·Archive 읽기를 재현하지 않는다. */
    private fun fixture(): Pair<List<ExpectedRecordedInput>, RecordedArchiveRead.Complete> {
        val command =
            DevelopmentBalanceCommandPayload(
                OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000)),
                "opening-P1",
                Instant.ofEpochSecond(1_700_000_000),
            )
        val bytes = DevelopmentBalanceCommandCodec().encode(command)
        val values =
            mapOf<String, Any>(
                "kind" to "FUNDING",
                "preparationId" to "P1",
                "userId" to "U1",
                "assetId" to "USDT",
                "amount" to 1_000L,
                "ledgerTransactionId" to "opening-P1",
                "occurredAt.epochSecond" to 1_700_000_000L,
                "occurredAt.nano" to 0,
            )
        val expected = (1..3).map { ExpectedRecordedInput(bytes.copyOf(), it * 256L, values, it.toLong()) }
        val inputs = expected.map { RecordedApplicationFrame(it.bytes.copyOf(), it.position, 0, 1, 0) }
        val identity = RecordedLogIdentity(97, 1, 1, 1, "test", "test", "test", 0, -1, 65_536, 1_408)
        val read = RecordedArchiveRead.Complete(identity, 0, inputs.last().position, inputs.last().position, inputs, emptyList())
        return expected to read
    }
}

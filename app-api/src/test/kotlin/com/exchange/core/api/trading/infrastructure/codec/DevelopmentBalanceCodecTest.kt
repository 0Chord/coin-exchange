package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.trading.OrderAdmissionResult
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceCommand as WireCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalancePrepared as WirePrepared
import com.exchange.core.api.trading.infrastructure.codec.generated.DevelopmentBalanceRejected as WireRejected

class DevelopmentBalanceCodecTest {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()

    @Test
    fun `자금 준비 명령은 독립적으로 정한 헤더와 일곱 필드의 바이트를 보존한다`() {
        // 본문 28바이트, kind 4, schema 87, version 2. 초와 나노초도 각각 독립 varint다.
        assertContentEquals(commandGolden, commands.encode(command()))
        assertEquals(command(), commands.decode(commandGolden))
    }

    @Test
    fun `최초와 반복 성공은 독립 바이트의 명시한 false와 true를 보존한다`() {
        listOf(false to preparedGolden, true to replayGolden).forEach { (alreadyPrepared, golden) ->
            val expected = prepared(alreadyPrepared = alreadyPrepared)
            assertContentEquals(golden, results.encode(expected))
            assertEquals(expected, results.decode(golden))
        }
    }

    @Test
    fun `두 충돌은 제출 원본과 전용 사유 번호만 독립 바이트로 보낸다`() {
        val fixtures =
            listOf(
                DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT to conflictGolden,
                DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED to usedAccountGolden,
            )
        fixtures.forEach { (reason, golden) ->
            val expected = DevelopmentBalanceResultPayload.Rejected(opening(), reason)
            assertContentEquals(golden, results.encode(expected))
            assertEquals(expected, results.decode(golden))
        }
        assertEquals(
            DevelopmentBalanceResultPayload.Rejected(opening(), DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT),
            DevelopmentBalanceResultPayload.from(command(), OpeningBalanceRequestConflictException("과거 원본은 응답에 넣지 않는다")),
        )
        assertEquals(
            DevelopmentBalanceResultPayload.Rejected(opening(), DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED),
            DevelopmentBalanceResultPayload.from(command(), OpeningBalanceConflictException("계정 사용 상세는 응답에 넣지 않는다")),
        )
    }

    @Test
    fun `명령 성공 충돌의 필수 필드를 하나씩 빼면 기본값으로 채우지 않는다`() {
        wireFixtures().forEach { fixture ->
            val expectedNumbers =
                if (fixture.kind == 4) {
                    1..7
                } else if (fixture.kind == 5) {
                    1..8
                } else {
                    1..5
                }
            assertEquals(
                expectedNumbers.toList(),
                fixture.message.descriptorForType.fields
                    .map { it.number },
            )
            fixture.message.descriptorForType.fields.forEach { field ->
                val missing =
                    fixture.message
                        .toBuilder()
                        .clearField(field)
                        .build()
                assertRejected("kind ${fixture.kind}: ${field.name} 누락", fixture.kind, missing.toByteArray())
            }
            assertRejected("kind ${fixture.kind}: 빈 본문", fixture.kind, byteArrayOf())
        }
    }

    @Test
    fun `명시한 epoch와 nano 0 및 최초 성공 false는 누락과 구분한다`() {
        val explicitCommand =
            wireCommand()
                .toBuilder()
                .setLedgerTransactionId("")
                .setOccurredAtEpochSecond(0)
                .setOccurredAtNano(0)
                .build()
        assertEquals(
            command().copy(ledgerTransactionId = "", occurredAt = Instant.EPOCH),
            commands.decode(protobufFrame(4, explicitCommand.toByteArray())),
        )
        val explicitPrepared =
            wirePrepared()
                .toBuilder()
                .setOccurredAtEpochSecond(0)
                .setOccurredAtNano(0)
                .setAlreadyPrepared(false)
                .build()
        assertEquals(prepared(occurredAt = Instant.EPOCH), results.decode(protobufFrame(5, explicitPrepared.toByteArray())))
    }

    @Test
    fun `금액 양끝과 Instant 양끝 및 나노초를 줄이거나 보정하지 않는다`() {
        val times = listOf(Instant.EPOCH, Instant.MIN, Instant.MAX, Instant.ofEpochSecond(1_234_567_890, 123_456_789))
        listOf(1L, Long.MAX_VALUE).forEach { amount ->
            times.forEach { time ->
                val input = command().copy(opening = opening(amount), occurredAt = time)
                val wire =
                    wireCommand()
                        .toBuilder()
                        .setAmount(amount)
                        .setOccurredAtEpochSecond(time.epochSecond)
                        .setOccurredAtNano(time.nano)
                        .build()
                val expectedBytes = protobufFrame(4, wire.toByteArray())
                assertEquals(input, commands.decode(expectedBytes))
                assertContentEquals(expectedBytes, commands.encode(input))
                listOf(false, true).forEach { repeated ->
                    val output = prepared(amount, time, repeated)
                    val preparedWire =
                        wirePrepared()
                            .toBuilder()
                            .setAmount(amount)
                            .setOccurredAtEpochSecond(time.epochSecond)
                            .setOccurredAtNano(time.nano)
                            .setAlreadyPrepared(repeated)
                            .build()
                    val preparedBytes = protobufFrame(5, preparedWire.toByteArray())
                    assertEquals(output, results.decode(preparedBytes))
                    assertContentEquals(preparedBytes, results.encode(output))
                }
            }
            val rejected =
                DevelopmentBalanceResultPayload.Rejected(
                    opening(amount),
                    DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT,
                )
            val rejectedBytes =
                protobufFrame(
                    6,
                    wireRejected()
                        .toBuilder()
                        .setAmount(amount)
                        .build()
                        .toByteArray(),
                )
            assertEquals(rejected, results.decode(rejectedBytes))
            assertContentEquals(rejectedBytes, results.encode(rejected))
        }
    }

    @Test
    fun `모든 자금 메시지는 미지 필드와 정상 필드에 동반한 잘못된 wire type도 거절한다`() {
        wireFixtures().forEach { fixture ->
            val valid = fixture.message.toByteArray()
            assertRejected("kind ${fixture.kind}: 미지 필드 99", fixture.kind, valid + "980601".hexToByteArray())
            fixture.message.descriptorForType.fields.forEach { field ->
                val wrongType = wrongWireType(field)
                val withoutField =
                    fixture.message
                        .toBuilder()
                        .clearField(field)
                        .build()
                        .toByteArray()
                listOf(
                    "잘못된 타입만 있음" to (withoutField + wrongType),
                    "정상 값 뒤 잘못된 타입" to (valid + wrongType),
                    "잘못된 타입 뒤 정상 값" to (wrongType + valid),
                ).forEach { (label, body) -> assertRejected("kind ${fixture.kind}: ${field.name} $label", fixture.kind, body) }
            }
        }
    }

    @Test
    fun `충돌 사유의 최종 값이 미지정 또는 미지원 숫자면 변환에 실패한다`() {
        listOf(0, 3, 255).forEach { reason ->
            val body =
                wireRejected()
                    .toBuilder()
                    .setReasonValue(reason)
                    .build()
                    .toByteArray()
            assertRejected("충돌 사유 $reason", 6, body)
        }
        assertRejected("지원 사유 뒤 최종 미지정 사유", 6, wireRejected().toByteArray() + "2800".hexToByteArray())
        assertRejected("지원 사유 뒤 최종 미지원 사유", 6, wireRejected().toByteArray() + "2803".hexToByteArray())
    }

    @Test
    fun `알려진 단일 필드가 반복되면 문자열 수치 bool enum의 마지막 값을 쓴다`() {
        val changedCommand = wireCommand().toByteArray() + "0a02503220012a024c3230003800".hexToByteArray()
        assertEquals(
            command().copy(opening = opening(1).copy(preparationId = "P2"), ledgerTransactionId = "L2", occurredAt = Instant.EPOCH),
            commands.decode(protobufFrame(4, changedCommand)),
        )
        val changedPrepared = wirePrepared().toByteArray() + "20013000380040014000".hexToByteArray()
        assertEquals(
            prepared(amount = 1, occurredAt = Instant.EPOCH, alreadyPrepared = false),
            results.decode(protobufFrame(5, changedPrepared)),
        )
        // 먼저 나온 미지원 enum도 최종 지원 값을 대신하지 않는다.
        val changedRejected = wireRejected().toByteArray() + "280328002802".hexToByteArray()
        assertEquals(
            DevelopmentBalanceResultPayload.Rejected(opening(), DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED),
            results.decode(protobufFrame(6, changedRejected)),
        )
    }

    @Test
    fun `기존 주문 golden과 새 자금 golden은 각자 지원하는 종류에서만 읽힌다`() {
        val orderCommands = AdmissionCommandCodec()
        val orderResults = AdmissionResultCodec()
        val fixtures =
            listOf(
                1 to previousOrderCommandGolden,
                1 to orderCommandGolden,
                2 to orderAcceptedGolden,
                3 to orderRejectedGolden,
                4 to commandGolden,
                5 to preparedGolden,
                6 to conflictGolden,
            )
        val decoders =
            listOf(
                Decoder("주문 명령", setOf(1)) { orderCommands.decode(it) },
                Decoder("주문 결과", setOf(2, 3)) { orderResults.decode(it) },
                Decoder("자금 명령", setOf(4)) { commands.decode(it) },
                Decoder("자금 결과", setOf(5, 6)) { results.decode(it) },
            )
        decoders.forEach { decoder ->
            fixtures.filter { (kind, _) -> kind !in decoder.kinds }.forEach { (kind, bytes) ->
                assertFailsWith<AdmissionMessageException>("${decoder.name}에 kind $kind 전달") { decoder.read(bytes) }
            }
        }
        val previousOrder = orderCommands.decode(previousOrderCommandGolden)
        assertEquals(1L, previousOrder.market.priceTick)
        assertEquals(1L, previousOrder.market.quantityStep)
        assertEquals(73, previousOrderCommandGolden.size)
        assertEquals(79, orderCommandGolden.size)
        assertEquals(previousOrder, orderCommands.decode(orderCommandGolden))
        assertContentEquals(orderCommandGolden, orderCommands.encode(previousOrder))
        assertEquals(AdmissionResultPayload.Accepted("u", "r", "m", "o", 1), orderResults.decode(orderAcceptedGolden))
        assertEquals(
            AdmissionResultPayload.Rejected(
                "u",
                "r",
                "m",
                "o",
                AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(AssetId("Q"), Amount(200), Amount(300), Amount(100)),
            ),
            orderResults.decode(orderRejectedGolden),
        )
        assertContentEquals(orderAcceptedGolden, orderResults.encode(orderResults.decode(orderAcceptedGolden)))
        assertContentEquals(orderRejectedGolden, orderResults.encode(orderResults.decode(orderRejectedGolden)))
    }

    @Test
    fun `성공 결과의 원본이 현재 명령과 다르면 최초 반복 모두 내부 오류다`() {
        val differentOpenings =
            listOf(
                opening().copy(preparationId = "P2"),
                opening().copy(userId = UserId("other-user")),
                opening().copy(assetId = AssetId("other-asset")),
                opening().copy(amount = Amount(2_000)),
            )
        listOf(false, true).forEach { repeated ->
            differentOpenings.forEach { different ->
                val result = prepared(alreadyPrepared = repeated).result.copy(opening = different)
                assertFailsWith<IllegalStateException>("alreadyPrepared=$repeated, opening=$different") {
                    DevelopmentBalanceResultPayload.from(command(), result)
                }
            }
        }
    }

    @Test
    fun `최초 성공은 제출한 원장 문맥과 일치해야 하고 반복 성공은 최초 문맥을 유지한다`() {
        val original = prepared().result
        assertEquals(prepared(), DevelopmentBalanceResultPayload.from(command(), original))
        listOf(
            original.copy(ledgerTransactionId = "other-ledger"),
            original.copy(occurredAt = original.occurredAt.plusNanos(1)),
        ).forEach { mismatched ->
            assertFailsWith<IllegalStateException> { DevelopmentBalanceResultPayload.from(command(), mismatched) }
        }
        val replay = original.copy(alreadyPrepared = true)
        val next = command().copy(ledgerTransactionId = "new-unused-ledger", occurredAt = Instant.MAX)
        assertEquals(DevelopmentBalanceResultPayload.Prepared(replay), DevelopmentBalanceResultPayload.from(next, replay))
    }

    private fun opening(amount: Long = 1_000) = OpeningBalance("P1", UserId("u"), AssetId("Q"), Amount(amount))

    private fun command() = DevelopmentBalanceCommandPayload(opening(), "L1", Instant.ofEpochSecond(1_234_567_890, 123_456_789))

    private fun prepared(
        amount: Long = 1_000,
        occurredAt: Instant = command().occurredAt,
        alreadyPrepared: Boolean = false,
    ) = DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(opening(amount), "L1", occurredAt, alreadyPrepared))

    private fun wireCommand(): WireCommand =
        WireCommand
            .newBuilder()
            .setPreparationId("P1")
            .setUserId("u")
            .setAssetId("Q")
            .setAmount(1_000)
            .setLedgerTransactionId("L1")
            .setOccurredAtEpochSecond(1_234_567_890)
            .setOccurredAtNano(123_456_789)
            .build()

    private fun wirePrepared(): WirePrepared =
        WirePrepared
            .newBuilder()
            .setPreparationId("P1")
            .setUserId("u")
            .setAssetId("Q")
            .setAmount(1_000)
            .setLedgerTransactionId("L1")
            .setOccurredAtEpochSecond(1_234_567_890)
            .setOccurredAtNano(123_456_789)
            .setAlreadyPrepared(false)
            .build()

    private fun wireRejected(): WireRejected =
        WireRejected
            .newBuilder()
            .setPreparationId("P1")
            .setUserId("u")
            .setAssetId("Q")
            .setAmount(1_000)
            .setReasonValue(1)
            .build()

    private fun wireFixtures() = listOf(WireFixture(4, wireCommand()), WireFixture(5, wirePrepared()), WireFixture(6, wireRejected()))

    private fun wrongWireType(field: Descriptors.FieldDescriptor): ByteArray =
        if (field.type == Descriptors.FieldDescriptor.Type.STRING) {
            byteArrayOf((field.number shl 3).toByte(), 1)
        } else {
            byteArrayOf(((field.number shl 3) or 2).toByte(), 1, 'x'.code.toByte())
        }

    private fun assertRejected(
        label: String,
        kind: Int,
        body: ByteArray,
    ) {
        assertFailsWith<AdmissionMessageException>(label) {
            val bytes = protobufFrame(kind, body)
            if (kind == 4) commands.decode(bytes) else results.decode(bytes)
        }
    }

    private data class WireFixture(
        val kind: Int,
        val message: Message,
    )

    private data class Decoder(
        val name: String,
        val kinds: Set<Int>,
        val read: (ByteArray) -> Any,
    )

    private val commandGolden = "1c000400570002000a0250311201751a015120e8072a024c3130d285d8cc0438959aef3a".hexToByteArray()
    private val preparedGolden = "1e000500570002000a0250311201751a015120e8072a024c3130d285d8cc0438959aef3a4000".hexToByteArray()
    private val replayGolden = "1e000500570002000a0250311201751a015120e8072a024c3130d285d8cc0438959aef3a4001".hexToByteArray()
    private val conflictGolden = "0f000600570002000a0250311201751a015120e8072801".hexToByteArray()
    private val usedAccountGolden = "0f000600570002000a0250311201751a015120e8072802".hexToByteArray()

    // 단위 필드가 없던 이전 v2 프레임 73바이트를 그대로 보존한다.
    private val previousOrderCommandGolden =
        (
            "4100010057000200080110011801206428033000380140014807500a581460d285d8cc0468959aef3a" +
                "720252317a01758201016d8a01016f9201016d9a010142a2010151aa01024c31"
        ).hexToByteArray()

    // 새 프레임은 본문 길이 71(47 00), 단위 태그 22·23과 명시한 값 1을 독립적으로 적는다.
    private val orderCommandGolden =
        (
            "4700010057000200080110011801206428033000380140014807500a581460d285d8cc0468959aef3a" +
                "720252317a01758201016d8a01016f9201016d9a010142a2010151aa01024c31b00101b80101"
        ).hexToByteArray()
    private val orderAcceptedGolden = "0e0002005700020008011201721a017522016d2a016f".hexToByteArray()
    private val orderRejectedGolden = "1b000300570002000805120b0a015110c80118ac0220641a01722201752a016d32016f".hexToByteArray()
}

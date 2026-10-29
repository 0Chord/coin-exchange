package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.Balance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import com.google.protobuf.Message
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionAccepted as WireAccepted
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommand as WireCommand
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionRejected as WireRejected
import com.exchange.core.api.trading.infrastructure.codec.generated.InsufficientBalance as WireInsufficientBalance

class AdmissionMessageBoundaryTest {
    private val commands = AdmissionCommandCodec()
    private val results = AdmissionResultCodec()

    @Test
    fun `본문이 없는 정확한 8바이트 헤더와 unsigned 종류 값은 구간 안에서 읽는다`() {
        for (kind in listOf(0, 1, 0x7FFF, 0x8000, 0xFF00, 0xFFFF)) {
            val frame = protobufFrame(kind, byteArrayOf())
            assertEquals(8, frame.size)
            for (offset in listOf(0, 13)) {
                val buffer = ByteArray(offset + frame.size + 7) { 0xA5.toByte() }
                frame.copyInto(buffer, offset)

                assertEquals(kind, AdmissionMessageReader(buffer, offset, frame.size).kind)
            }
        }
    }

    @Test
    fun `스키마 버전 본문 길이의 고위 비트도 unsigned로 읽고 기존 오류로 거절한다`() {
        val frame = protobufFrame(1, byteArrayOf())
        for (fieldOffset in listOf(4, 6)) {
            for (value in listOf(0x8000, 0x8057, 0xFFFF)) {
                val invalid = frame.changed { putShort(fieldOffset, value.toShort()) }
                assertEquals(
                    "지원하지 않는 스키마 또는 버전입니다",
                    assertFailsWith<AdmissionMessageException> { AdmissionMessageReader(invalid, 0, invalid.size) }.message,
                )
            }
        }
        for (value in listOf(0x8000, 0xFFFF)) {
            val invalid = frame.changed { putShort(0, value.toShort()) }
            assertEquals(
                "본문 길이와 메시지 구간이 다릅니다",
                assertFailsWith<AdmissionMessageException> { AdmissionMessageReader(invalid, 0, invalid.size) }.message,
            )
        }
    }

    @Test
    fun `동시에 잘못된 헤더는 구간 스키마 길이 종류 순서로 거절한다`() {
        val invalid =
            protobufFrame(99, byteArrayOf()).changed {
                putShort(0, 1)
                putShort(4, 86)
                putShort(6, 3)
            }
        assertEquals(
            "메시지 구간 또는 전체 길이가 잘못됐습니다",
            assertFailsWith<AdmissionMessageException> { commands.decode(invalid, 0, 7) }.message,
        )
        assertEquals(
            "지원하지 않는 스키마 또는 버전입니다",
            assertFailsWith<AdmissionMessageException> { commands.decode(invalid) }.message,
        )
        val wrongLength =
            invalid.changed {
                putShort(4, 87)
                putShort(6, 2)
            }
        assertEquals(
            "본문 길이와 메시지 구간이 다릅니다",
            assertFailsWith<AdmissionMessageException> { commands.decode(wrongLength) }.message,
        )
        val wrongKind = wrongLength.changed { putShort(0, 0) }
        assertEquals(
            "명령 메시지가 아닙니다",
            assertFailsWith<AdmissionMessageException> { commands.decode(wrongKind) }.message,
        )
    }

    @Test
    fun `공유 버퍼의 정확한 구간만 읽고 반환한 명령은 원본 버퍼와 분리된다`() {
        val command = admissionCommand()
        val encoded = commands.encode(command)
        val offset = 13
        val shared = ByteArray(offset + encoded.size + 17) { 0xA5.toByte() }
        encoded.copyInto(shared, offset)
        val decoded = commands.decode(shared, offset, encoded.size)
        shared.fill(0)
        assertEquals(command, decoded)
        assertCommandRejected("마지막 바이트가 논리 구간 밖에 있음", encoded + byteArrayOf(0x7F), length = encoded.size - 1)
    }

    @Test
    fun `구간과 필수 문자열이 잘렸거나 남은 바이트가 있으면 코어 진입 전에 실패한다`() {
        val payload = wireCommandBuilder().build().toByteArray()
        val encoded = protobufFrame(1, payload)
        val scenarios =
            listOf(
                BrokenFrame("헤더 8바이트 중 7바이트", encoded.copyOf(7)),
                BrokenFrame("헤더가 선언한 본문 중 마지막 바이트 누락", encoded.copyOf(encoded.size - 1)),
                BrokenFrame("Protobuf 문자열 본문이 잘림", protobufFrame(1, payload.copyOf(payload.size - 1))),
                BrokenFrame("Protobuf varint 중간에서 끝남", protobufFrame(1, payload + byteArrayOf(0x20, 0x80.toByte()))),
                BrokenFrame("해석 뒤 잔여 바이트", encoded + byteArrayOf(0)),
                BrokenFrame("음수 offset", encoded, offset = -1, length = encoded.size),
                BrokenFrame("Int 최대 offset", encoded, offset = Int.MAX_VALUE, length = encoded.size),
                BrokenFrame("음수 length", encoded, length = -1),
                BrokenFrame("배열 경계를 넘는 length", encoded, length = encoded.size + 1),
                BrokenFrame("4096바이트 상한 초과", protobufFrame(1, ByteArray(4089))),
            )
        scenarios.forEach { assertCommandRejected(it.label, it.bytes, it.offset, it.length) }
    }

    @Test
    fun `다른 스키마 버전 종류와 본문 길이는 추측해 읽지 않는다`() {
        val encoded = protobufFrame(1, wireCommandBuilder().build().toByteArray())
        val payloadLength = encoded.size - 8
        val scenarios =
            listOf(
                "다른 스키마 86" to encoded.changed { putShort(4, 86) },
                "이전 SBE 버전 1" to encoded.changed { putShort(6, 1) },
                "미지원 새 버전 3" to encoded.changed { putShort(6, 3) },
                "명령 대신 승인 종류 2" to encoded.changed { putShort(2, 2) },
                "알 수 없는 종류 99" to encoded.changed { putShort(2, 99) },
                "실제보다 작은 본문 길이" to encoded.changed { putShort(0, (payloadLength - 1).toShort()) },
                "실제보다 큰 본문 길이" to encoded.changed { putShort(0, (payloadLength + 1).toShort()) },
                "빈 본문이라고 주장" to encoded.changed { putShort(0, 0) },
            )
        scenarios.forEach { (label, bytes) -> assertCommandRejected(label, bytes) }
    }

    @Test
    fun `모든 명령 열거 필드에서 미지 코드와 미지정 코드를 거절한다`() {
        val fields: List<Pair<String, (WireCommand.Builder, Int) -> WireCommand.Builder>> =
            listOf(
                "side" to { builder, code -> builder.setSideValue(code) },
                "orderType" to { builder, code -> builder.setOrderTypeValue(code) },
                "timeInForce" to { builder, code -> builder.setTimeInForceValue(code) },
                "productType" to { builder, code -> builder.setProductTypeValue(code) },
                "feeTier" to { builder, code -> builder.setFeeTierValue(code) },
            )
        fields.forEach { (field, change) ->
            listOf(0, 255).forEach { code ->
                assertCommandRejected("$field 코드 $code", protobufFrame(1, change(wireCommandBuilder(), code).build().toByteArray()))
            }
        }
    }

    @Test
    fun `알려진 명령 열거 코드는 모두 정해진 업무 값으로 읽는다`() {
        val original = admissionCommand()
        val scenarios =
            listOf(
                wireCommandBuilder().setSideValue(1) to original,
                wireCommandBuilder().setSideValue(2) to original.copy(order = original.order.copy(side = Side.SELL)),
                wireCommandBuilder().setOrderTypeValue(1) to original,
                wireCommandBuilder().setOrderTypeValue(2) to original.copy(order = original.order.copy(orderType = OrderType.MARKET)),
                wireCommandBuilder().setTimeInForceValue(1) to original,
                wireCommandBuilder().setTimeInForceValue(2) to original.copy(order = original.order.copy(timeInForce = TimeInForce.IOC)),
                wireCommandBuilder().setProductTypeValue(1) to original,
                wireCommandBuilder().setProductTypeValue(2) to
                    original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES)),
                wireCommandBuilder().setProductTypeValue(3) to
                    original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(productType = FeeProductType.DATED_FUTURES)),
                wireCommandBuilder().setFeeTierValue(1) to original,
                wireCommandBuilder().setFeeTierValue(2) to
                    original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(feeTier = FeeTier.VIP)),
                wireCommandBuilder().setFeeTierValue(3) to
                    original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(feeTier = FeeTier.VVIP)),
                wireCommandBuilder().setFeeTierValue(4) to
                    original.copy(feePolicySnapshot = original.feePolicySnapshot.copy(feeTier = FeeTier.VVVIP)),
            )
        scenarios.forEach { (wire, expected) -> assertEquals(expected, commands.decode(protobufFrame(1, wire.build().toByteArray()))) }
    }

    @Test
    fun `명령의 필수 필드 하나라도 누락하면 0이나 빈 문자열로 채우지 않는다`() {
        val original = wireCommandBuilder().build()
        assertEquals((1..23).toList(), original.descriptorForType.fields.map { it.number })
        original.descriptorForType.fields.forEach { field ->
            val missing = original.toBuilder().clearField(field).build()
            assertCommandRejected("누락 ${field.name}", protobufFrame(1, missing.toByteArray()))
        }
        assertCommandRejected("명령 본문 전체가 비어 있음", protobufFrame(1, byteArrayOf()))

        // optional presence가 있는 명시한 0과 빈 값은 누락과 다르다.
        val explicit =
            original
                .toBuilder()
                .setQuantity(0)
                .setBaseAssetScale(0)
                .setOccurredAtEpochSecond(0)
                .setOccurredAtNano(0)
                .setRequestId("")
                .setLedgerTransactionId("")
                .build()
        val decoded = commands.decode(protobufFrame(1, explicit.toByteArray()))
        assertEquals(Quantity.ZERO, decoded.order.quantity)
        assertEquals(0, decoded.market.baseAssetScale)
        assertEquals(Instant.EPOCH, decoded.occurredAt)
        assertEquals("", decoded.requestId)
        assertEquals("", decoded.ledgerTransactionId)
    }

    @Test
    fun `도메인 값으로 표현할 수 없는 숫자와 정규화될 나노초는 변환 오류다`() {
        val scenarios =
            listOf(
                "가격 0" to wireCommandBuilder().setPrice(0),
                "가격 -1" to wireCommandBuilder().setPrice(-1),
                "수량 -1" to wireCommandBuilder().setQuantity(-1),
                "scale 19" to wireCommandBuilder().setBaseAssetScale(19),
                "scale -1" to wireCommandBuilder().setBaseAssetScale(-1),
                "가격 단위 0" to wireCommandBuilder().setPriceTick(0),
                "가격 단위 -1" to wireCommandBuilder().setPriceTick(-1),
                "수량 단위 0" to wireCommandBuilder().setQuantityStep(0),
                "수량 단위 -1" to wireCommandBuilder().setQuantityStep(-1),
                "scale과 나누어떨어지지 않는 단위" to wireCommandBuilder().setPriceTick(3).setQuantityStep(2),
                "Long 곱 범위를 넘지만 호환되지 않는 단위" to
                    wireCommandBuilder().setBaseAssetScale(18).setPriceTick(Long.MAX_VALUE).setQuantityStep(Long.MAX_VALUE),
                "정책 버전 0" to wireCommandBuilder().setScheduleVersion(0),
                "정책 버전 -1" to wireCommandBuilder().setScheduleVersion(-1),
                "maker -1ppm" to wireCommandBuilder().setMakerPpm(-1),
                "taker 1000001ppm" to wireCommandBuilder().setTakerPpm(1_000_001),
                "나노초 -1" to wireCommandBuilder().setOccurredAtNano(-1),
                "나노초 1000000000" to wireCommandBuilder().setOccurredAtNano(1_000_000_000),
                "Instant 범위 밖 최소 초" to wireCommandBuilder().setOccurredAtEpochSecond(Long.MIN_VALUE),
                "Instant 범위 밖 최대 초" to wireCommandBuilder().setOccurredAtEpochSecond(Long.MAX_VALUE),
            )
        scenarios.forEach { (label, wire) -> assertCommandRejected(label, protobufFrame(1, wire.build().toByteArray())) }
    }

    @Test
    fun `256바이트 UTF8 경계는 보존하고 잘못된 길이와 문자열은 대체하지 않는다`() {
        val boundary = admissionCommand(requestId = "😀".repeat(64))
        assertEquals(256, boundary.requestId.toByteArray(Charsets.UTF_8).size)
        assertEquals(boundary, commands.decode(commands.encode(boundary)))
        val withoutRequest = wireCommandBuilder().clearRequestId().build().toByteArray()
        val scenarios =
            listOf(
                "257바이트 요청 ID" to wireCommandBuilder().setRequestId("x".repeat(257)).build().toByteArray(),
                "문자열 길이 256에 본문 1바이트" to (withoutRequest + "72800278".hexToByteArray()),
                "잘못된 UTF8 C3 28" to (withoutRequest + "7202c328".hexToByteArray()),
                "UTF8 과잉 인코딩 C0 80" to (withoutRequest + "7202c080".hexToByteArray()),
                "공백 사용자" to wireCommandBuilder().setUserId("      ").build().toByteArray(),
            )
        scenarios.forEach { (label, payload) -> assertCommandRejected(label, protobufFrame(1, payload)) }
        val original = wireCommandBuilder().build()
        val identifiers = original.descriptorForType.fields.filter { it.number in 14..21 }
        assertEquals((14..21).toList(), identifiers.map { it.number })
        identifiers.forEach { field ->
            val tooLong = original.toBuilder().setField(field, "x".repeat(257)).build()
            assertCommandRejected("${field.name} 257바이트", protobufFrame(1, tooLong.toByteArray()))
        }
        listOf("257바이트 원문" to "x".repeat(257), "고립 상위 서로게이트" to "\uD800", "고립 하위 서로게이트" to "\uDC00").forEach { (label, requestId) ->
            assertRejectedBeforeAdmission(label) { commands.decode(commands.encode(admissionCommand(requestId = requestId))) }
        }
    }

    @Test
    fun `모든 메시지와 부족 상세의 알 수 없는 필드를 거절한다`() {
        // 필드 99, wire type 0, 값 1. Protobuf 자체는 보관하지만 이 버전 코덱은 거절한다.
        val unknown = "980601".hexToByteArray()
        assertCommandRejected("명령 unknown field", protobufFrame(1, wireCommandBuilder().build().toByteArray() + unknown))
        val accepted = acceptedWire()
        val rejected = rejectedWire()
        listOf(
            protobufFrame(2, accepted.toByteArray() + unknown),
            protobufFrame(3, rejected.toByteArray() + unknown),
            protobufFrame(
                3,
                rejected
                    .toBuilder()
                    .setInsufficientBalance(
                        WireInsufficientBalance.parseFrom(detailWire().toByteArray() + unknown),
                    ).build()
                    .toByteArray(),
            ),
        ).forEach { assertFailsWith<AdmissionMessageException> { results.decode(it) } }
    }

    @Test
    fun `중복 단일 필드는 Protobuf의 마지막 값 규칙을 따르고 4096바이트 구간도 읽는다`() {
        val original = wireCommandBuilder().build().toByteArray()
        val lastPrice =
            WireCommand
                .newBuilder()
                .setPrice(200_000_000)
                .build()
                .toByteArray()
        val changed = commands.decode(protobufFrame(1, original + lastPrice))
        assertEquals(Price(200_000_000), changed.order.price)
        assertEquals(admissionCommand().order.copy(price = Price(200_000_000)), changed.order)

        // 알려진 동일 필드만 반복해 유효한 본문을 정확히 4088바이트로 채운다.
        var payload = original
        if ((4088 - payload.size) % 2 != 0) payload += "68959aef3a".hexToByteArray()
        payload += ByteArray(4088 - payload.size) { if (it % 2 == 0) 0x18 else 0x01 }
        val frame = protobufFrame(1, payload)
        assertEquals(4096, frame.size)
        assertEquals(admissionCommand(), commands.decode(frame))
        assertCommandRejected("같은 구간의 상한 1바이트 초과", frame + byteArrayOf(0))
    }

    @Test
    fun `Long 최대 값과 Instant 양 끝 및 수치 상한은 손실이나 NULL 대체 없이 왕복한다`() {
        val original = admissionCommand()
        val maximum =
            original.copy(
                order = original.order.copy(side = Side.SELL, price = Price(Long.MAX_VALUE), quantity = Quantity(Long.MAX_VALUE)),
                market = original.market.copy(baseAssetScale = 18, quantityStep = 1_000_000_000_000_000_000L),
                feePolicySnapshot =
                    original.feePolicySnapshot.copy(
                        scheduleVersion = Long.MAX_VALUE,
                        feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate(1_000_000)),
                    ),
            )

        listOf(Instant.MIN, Instant.MAX, Instant.ofEpochSecond(-1, 999_999_999)).forEach { time ->
            val command = maximum.copy(occurredAt = time)
            assertEquals(command, commands.decode(commands.encode(command)), "시각 $time")
        }
        val zeroScale = maximum.copy(market = maximum.market.copy(baseAssetScale = 0, quantityStep = 1), occurredAt = Instant.EPOCH)
        val decoded = commands.decode(commands.encode(zeroScale))
        assertEquals(zeroScale, decoded)

        val state =
            OrderAdmissionState(
                listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount(Long.MAX_VALUE), Amount.ZERO)),
                admissionCalculator(),
            )
        // 전송은 최대 정수를 보존하지만, quote 대금 MAX × MAX는 접수 시 표현할 수 없다.
        // 요청 ID 없는 내부 접수 경계로 확인하므로 거절 요청 기록도 추가하지 않는다.
        val before = state.snapshot()
        val rejected =
            assertIs<OrderAdmissionState.Result.Rejected>(
                state.admit(
                    decoded.order,
                    decoded.market,
                    decoded.feePolicySnapshot,
                    decoded.ledgerTransactionId,
                    decoded.occurredAt,
                ),
            )
        assertEquals(OrderAdmissionResult.Reason.UNREPRESENTABLE_RESERVATION, rejected.reason)
        assertEquals(before, state.snapshot())

        // 가격 1이면 quote 대금도 MAX이므로 최대 base 수량의 예약을 승인할 수 있다.
        val representable = zeroScale.copy(order = zeroScale.order.copy(price = Price(1)))
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, commands.decode(commands.encode(representable))))
        assertEquals(representable.order, accepted.record.candidate.order)
        assertEquals(Amount(Long.MAX_VALUE), accepted.record.candidate.reservation.reservedAmount)
        assertEquals(
            listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount.ZERO, Amount(Long.MAX_VALUE))),
            state.snapshot().balances,
            "전체 quote 대금이 표현 가능한 SELL은 최대 BTC 수량만 예약하며 USDT 항목을 만들지 않는다",
        )
    }

    @Test
    fun `표현 가능한 업무 위반은 코어의 정상 거절 사유와 요청 기록 규칙을 유지한다`() {
        val original = admissionCommand()
        val scenarios =
            listOf(
                BusinessRejection(
                    "MARKET",
                    original.copy(order = original.order.copy(orderType = OrderType.MARKET)),
                    OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                    1,
                ),
                BusinessRejection(
                    "IOC",
                    original.copy(order = original.order.copy(timeInForce = TimeInForce.IOC)),
                    OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION,
                    1,
                ),
                BusinessRejection(
                    "수량 0",
                    original.copy(order = original.order.copy(quantity = Quantity.ZERO)),
                    OrderAdmissionResult.Reason.INVALID_QUANTITY,
                    1,
                ),
                BusinessRejection("빈 요청 ID", original.copy(requestId = ""), OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER, 0),
                BusinessRejection(
                    "65글자 요청 ID",
                    original.copy(requestId = "r".repeat(65)),
                    OrderAdmissionResult.Reason.INVALID_REQUEST_IDENTIFIER,
                    0,
                ),
                BusinessRejection(
                    "65글자 주문 ID",
                    original.copy(order = original.order.copy(orderId = OrderId("o".repeat(65)))),
                    OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER,
                    0,
                ),
                BusinessRejection(
                    "NUL을 포함한 주문 ID",
                    original.copy(order = original.order.copy(orderId = OrderId("O\u0000"))),
                    OrderAdmissionResult.Reason.INVALID_ORDER_IDENTIFIER,
                    0,
                ),
            )

        scenarios.forEach { scenario ->
            val state = admissionState()
            val before = state.snapshot()
            val decoded = commands.decode(commands.encode(scenario.command))
            assertEquals(scenario.command, decoded, scenario.label)
            val rejected = assertIs<OrderAdmissionState.Result.Rejected>(admit(state, decoded), scenario.label)
            assertEquals(scenario.reason, rejected.reason, scenario.label)
            val after = state.snapshot()
            assertEquals(before.balances, after.balances, scenario.label)
            assertEquals(before.pendingOrders, after.pendingOrders, scenario.label)
            assertEquals(before.lastSequence, after.lastSequence, scenario.label)
            assertEquals(scenario.storedRequests, after.requests.size, scenario.label)
            assertFalse(state.isBlocked, scenario.label)
        }
    }

    @Test
    fun `작은 응답도 정확한 구간과 Long 최대 순번 및 금액을 보존한다`() {
        val accepted = AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", Long.MAX_VALUE)
        val encoded = results.encode(accepted)
        val shared = ByteArray(encoded.size + 19) { 0xA5.toByte() }
        encoded.copyInto(shared, 7)
        val decoded = results.decode(shared, 7, encoded.size)
        shared.fill(0)
        assertEquals(accepted, decoded)
        val rejected = insufficient(available = 0, required = Long.MAX_VALUE, shortfall = Long.MAX_VALUE)
        assertEquals(rejected, results.decode(results.encode(rejected)))
    }

    @Test
    fun `응답의 필수 필드와 부족 상세의 필드도 하나씩 누락을 거절한다`() {
        val messages: List<Pair<Int, Message>> = listOf(2 to acceptedWire(), 3 to rejectedWire())
        messages.forEach { (kind, message) ->
            val required = if (kind == 2) listOf(1, 2, 3, 4, 5) else listOf(1, 3, 4, 5, 6)
            required.forEach { number ->
                val field = message.descriptorForType.findFieldByNumber(number)
                val missing = message.toBuilder().clearField(field).build()
                assertFailsWith<AdmissionMessageException>("${message.descriptorForType.name} 누락 ${field.name}") {
                    results.decode(protobufFrame(kind, missing.toByteArray()))
                }
            }
        }
        val detail = detailWire()
        assertEquals(listOf(1, 2, 3, 4), detail.descriptorForType.fields.map { it.number })
        detail.descriptorForType.fields.forEach { field ->
            val missing = detail.toBuilder().clearField(field).build()
            assertFailsWith<AdmissionMessageException>("부족 상세 누락 ${field.name}") {
                results.decode(protobufFrame(3, rejectedWire(missing).toByteArray()))
            }
        }
        val explicitZero =
            detail
                .toBuilder()
                .setAvailable(0)
                .setRequired(1)
                .setShortfall(1)
                .build()
        assertEquals(
            Amount.ZERO,
            assertIs<AdmissionResultPayload.Rejected>(
                results.decode(protobufFrame(3, rejectedWire(explicitZero).toByteArray())),
            ).insufficientBalance!!.available,
        )
        val explicitEmpty = acceptedWire().toBuilder().setRequestId("").build()
        assertEquals("", results.decode(protobufFrame(2, explicitEmpty.toByteArray())).requestId)
    }

    @Test
    fun `응답의 종류 본문 길이 문자열과 마지막 바이트도 검증한다`() {
        val acceptedPayload = acceptedWire().toByteArray()
        val rejectedPayload = rejectedWire().toByteArray()
        val accepted = protobufFrame(2, acceptedPayload)
        val rejected = protobufFrame(3, rejectedPayload)
        val invalidAsset =
            detailWire()
                .toBuilder()
                .clearAssetId()
                .build()
                .toByteArray() + "0a02c328".hexToByteArray()
        val invalidNested =
            rejectedWire()
                .toBuilder()
                .clearInsufficientBalance()
                .build()
                .toByteArray() +
                byteArrayOf(0x12, invalidAsset.size.toByte()) + invalidAsset
        val scenarios =
            listOf(
                "승인에 명령 종류" to accepted.changed { putShort(2, 1) },
                "응답에 미지원 종류" to accepted.changed { putShort(2, 99) },
                "승인의 거짓 본문 길이" to accepted.changed { putShort(0, (acceptedPayload.size + 1).toShort()) },
                "거절의 다른 버전" to rejected.changed { putShort(6, 1) },
                "거절의 다른 스키마" to rejected.changed { putShort(4, 86) },
                "승인 마지막 바이트 누락" to accepted.copyOf(accepted.size - 1),
                "거절 마지막 바이트 누락" to rejected.copyOf(rejected.size - 1),
                "승인 Protobuf 문자열 잘림" to protobufFrame(2, acceptedPayload.copyOf(acceptedPayload.size - 1)),
                "거절 Protobuf 문자열 잘림" to protobufFrame(3, rejectedPayload.copyOf(rejectedPayload.size - 1)),
                "승인 뒤 잔여 바이트" to (accepted + byteArrayOf(0)),
                "거절 뒤 잔여 바이트" to (rejected + byteArrayOf(0)),
                "승인 잘못된 UTF8" to
                    protobufFrame(
                        2,
                        acceptedWire()
                            .toBuilder()
                            .clearOrderId()
                            .build()
                            .toByteArray() + "2a02c328".hexToByteArray(),
                    ),
                "거절 잘못된 UTF8" to
                    protobufFrame(
                        3,
                        rejectedWire()
                            .toBuilder()
                            .clearOrderId()
                            .build()
                            .toByteArray() + "3202c328".hexToByteArray(),
                    ),
                "부족 상세 자산 잘못된 UTF8" to protobufFrame(3, invalidNested),
            )
        scenarios.forEach { (label, bytes) -> assertFailsWith<AdmissionMessageException>(label) { results.decode(bytes) } }
        val messages: List<Pair<Int, Message>> = listOf(2 to acceptedWire(), 3 to rejectedWire())
        messages.forEach { (kind, message) ->
            val identifiers =
                message.descriptorForType.fields.filter {
                    it.javaType ==
                        com.google.protobuf.Descriptors.FieldDescriptor.JavaType.STRING
                }
            assertEquals(4, identifiers.size)
            identifiers.forEach { field ->
                val tooLong = message.toBuilder().setField(field, "x".repeat(257)).build()
                assertFailsWith<AdmissionMessageException>(
                    "응답 ${field.name} 257바이트",
                ) { results.decode(protobufFrame(kind, tooLong.toByteArray())) }
            }
        }
        assertFailsWith<AdmissionMessageException>("공유 버퍼 뒤 바이트를 빌려 읽으면 안 됨") {
            results.decode(accepted + byteArrayOf(0), length = accepted.size - 1)
        }
    }

    @Test
    fun `응답 순번과 거절의 상세 유무 금액은 서로 일치해야 한다`() {
        val scenarios =
            listOf(
                "승인 순번 0" to
                    protobufFrame(
                        2,
                        acceptedWire()
                            .toBuilder()
                            .setSequence(0)
                            .build()
                            .toByteArray(),
                    ),
                "승인 순번 -1" to
                    protobufFrame(
                        2,
                        acceptedWire()
                            .toBuilder()
                            .setSequence(-1)
                            .build()
                            .toByteArray(),
                    ),
                "미지정 거절 사유 0" to
                    protobufFrame(
                        3,
                        rejectedWire()
                            .toBuilder()
                            .setReasonValue(0)
                            .build()
                            .toByteArray(),
                    ),
                "알 수 없는 거절 사유 255" to
                    protobufFrame(
                        3,
                        rejectedWire()
                            .toBuilder()
                            .setReasonValue(255)
                            .build()
                            .toByteArray(),
                    ),
                "자금 부족 사유에 상세 없음" to
                    protobufFrame(
                        3,
                        rejectedWire()
                            .toBuilder()
                            .clearInsufficientBalance()
                            .build()
                            .toByteArray(),
                    ),
                "자금 부족 이외 사유에 상세 있음" to
                    protobufFrame(
                        3,
                        rejectedWire()
                            .toBuilder()
                            .setReasonValue(1)
                            .build()
                            .toByteArray(),
                    ),
                "현재 금액 음수" to
                    protobufFrame(
                        3,
                        rejectedWire(detailWire().toBuilder().setAvailable(-1).build()).toByteArray(),
                    ),
                "필요 금액 음수" to protobufFrame(3, rejectedWire(detailWire().toBuilder().setRequired(-1).build()).toByteArray()),
                "부족 금액 음수" to protobufFrame(3, rejectedWire(detailWire().toBuilder().setShortfall(-1).build()).toByteArray()),
                "필요액이 현재액과 같음" to protobufFrame(3, rejectedWire(detailWire().toBuilder().setRequired(200).build()).toByteArray()),
                "부족액 불일치" to protobufFrame(3, rejectedWire(detailWire().toBuilder().setShortfall(99).build()).toByteArray()),
                "상세 자산이 빈 값" to protobufFrame(3, rejectedWire(detailWire().toBuilder().setAssetId("").build()).toByteArray()),
                "상세 자산이 257바이트" to
                    protobufFrame(3, rejectedWire(detailWire().toBuilder().setAssetId("x".repeat(257)).build()).toByteArray()),
            )
        scenarios.forEach { (label, bytes) -> assertFailsWith<AdmissionMessageException>(label) { results.decode(bytes) } }
        val invalidPayloads =
            listOf(
                AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", 0),
                withoutInsufficientBalance().copy(reason = AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE),
                insufficient().copy(reason = AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION),
                insufficient(shortfall = 99),
                insufficient(available = 300, required = 300, shortfall = 0),
            )
        invalidPayloads.forEach { payload -> assertFailsWith<AdmissionMessageException>(payload.toString()) { results.encode(payload) } }
    }

    private fun assertCommandRejected(
        label: String,
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) = assertRejectedBeforeAdmission(label) { commands.decode(bytes, offset, length) }

    private fun assertRejectedBeforeAdmission(
        label: String,
        read: () -> AdmissionCommandPayload,
    ) {
        val state = admissionState()
        assertIs<OrderAdmissionState.Result.Accepted>(admit(state, admissionCommand("seed-request", "seed-order")))
        val before = state.snapshot()
        var coreEntries = 0
        assertFailsWith<AdmissionMessageException>(label) {
            val command = read()
            coreEntries++
            admit(state, command)
        }
        assertEquals(0, coreEntries, "$label: 실제 코어 진입 횟수")
        assertEquals(before, state.snapshot(), "$label: 잔고·대기 주문·순번·요청 이력")
        assertFalse(state.isBlocked, label)
    }

    private fun acceptedWire(): WireAccepted =
        WireAccepted
            .newBuilder()
            .setSequence(1)
            .setRequestId("R1")
            .setUserId("user-1")
            .setMarketId("BTC-USDT")
            .setOrderId("O1")
            .build()

    private fun detailWire(): WireInsufficientBalance =
        WireInsufficientBalance
            .newBuilder()
            .setAssetId("USDT")
            .setAvailable(200)
            .setRequired(300)
            .setShortfall(100)
            .build()

    private fun rejectedWire(detail: WireInsufficientBalance = detailWire()): WireRejected =
        WireRejected
            .newBuilder()
            .setReasonValue(5)
            .setInsufficientBalance(detail)
            .setRequestId("R1")
            .setUserId("user-1")
            .setMarketId("BTC-USDT")
            .setOrderId("O1")
            .build()

    private fun insufficient(
        available: Long = 200,
        required: Long = 300,
        shortfall: Long = 100,
    ) = AdmissionResultPayload.Rejected(
        "user-1",
        "R1",
        "BTC-USDT",
        "O1",
        AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE,
        OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount(available), Amount(required), Amount(shortfall)),
    )

    private fun withoutInsufficientBalance() =
        AdmissionResultPayload.Rejected("user-1", "R1", "BTC-USDT", "O1", AdmissionResultPayload.Reason.UNSUPPORTED_ORDER_CONDITION)

    private fun ByteArray.changed(change: ByteBuffer.() -> Unit): ByteArray =
        copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).change()
        }

    private data class BrokenFrame(
        val label: String,
        val bytes: ByteArray,
        val offset: Int = 0,
        val length: Int = bytes.size - offset,
    )

    private data class BusinessRejection(
        val label: String,
        val command: AdmissionCommandPayload,
        val reason: OrderAdmissionResult.Reason,
        val storedRequests: Int,
    )
}

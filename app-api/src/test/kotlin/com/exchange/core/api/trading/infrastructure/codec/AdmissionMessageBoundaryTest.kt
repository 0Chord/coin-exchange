package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.Balance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AdmissionMessageBoundaryTest {
    private val commands = AdmissionCommandCodec()
    private val results = AdmissionResultCodec()

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
        // 버퍼 끝에 실제 누락 바이트가 있어도 전달받은 length 밖으로 읽어서는 안 된다.
        val adjacent = encoded + byteArrayOf(0x7F)
        assertCommandRejected("마지막 원장 ID 바이트가 논리 구간 밖에 있음", adjacent, length = encoded.size - 1)
    }

    @Test
    fun `구간과 필수 문자열이 잘렸거나 남은 바이트가 있으면 코어 진입 전에 실패한다`() {
        val encoded = commands.encode(admissionCommand())
        val ledgerLengthPrefix = encoded.size - 2 - "ledger-O1".toByteArray(Charsets.UTF_8).size
        val scenarios =
            listOf(
                BrokenFrame("헤더 8바이트 중 7바이트", encoded.copyOf(7)),
                BrokenFrame("고정 블록 50바이트 중 49바이트", encoded.copyOf(COMMAND_VARIABLES - 1)),
                BrokenFrame("마지막 필수 문자열 전체 누락", encoded.copyOf(ledgerLengthPrefix)),
                BrokenFrame("마지막 문자열 길이 2바이트 중 1바이트", encoded.copyOf(ledgerLengthPrefix + 1)),
                BrokenFrame("마지막 문자열 본문 중 1바이트 누락", encoded.copyOf(encoded.size - 1)),
                BrokenFrame("해석 뒤 잔여 바이트 1개", encoded + byteArrayOf(0)),
                BrokenFrame("음수 offset", encoded, offset = -1, length = encoded.size),
                BrokenFrame("Int 최대 offset의 덧셈 범위 초과", encoded, offset = Int.MAX_VALUE, length = encoded.size),
                BrokenFrame("음수 length", encoded, length = -1),
                BrokenFrame("배열 경계를 넘는 length", encoded, length = encoded.size + 1),
                BrokenFrame("4096바이트 전송 상한 초과", ByteArray(4097)),
            )

        scenarios.forEach { assertCommandRejected(it.label, it.bytes, it.offset, it.length) }
    }

    @Test
    fun `다른 스키마 버전 종류 고정 길이는 추측해 읽지 않는다`() {
        val encoded = commands.encode(admissionCommand())
        val scenarios =
            listOf(
                "다른 스키마 86" to encoded.changed { putShort(SCHEMA, 86) },
                "이전 버전 0" to encoded.changed { putShort(VERSION, 0) },
                "미지원 새 버전 2" to encoded.changed { putShort(VERSION, 2) },
                "명령 대신 승인 종류 2" to encoded.changed { putShort(TEMPLATE, 2) },
                "알 수 없는 종류 99" to encoded.changed { putShort(TEMPLATE, 99) },
                "고정 블록이 49바이트라고 주장" to encoded.changed { putShort(BLOCK_LENGTH, 49) },
                "고정 블록이 51바이트라고 주장" to encoded.changed { putShort(BLOCK_LENGTH, 51) },
            )

        scenarios.forEach { (label, bytes) -> assertCommandRejected(label, bytes) }
    }

    @Test
    fun `스키마의 모든 명령 열거 필드에서 미지 코드와 NULL 코드를 거절한다`() {
        val encoded = commands.encode(admissionCommand())
        val fields =
            listOf(
                "side" to SIDE,
                "orderType" to ORDER_TYPE,
                "timeInForce" to TIME_IN_FORCE,
                "productType" to PRODUCT,
                "feeTier" to TIER,
            )

        fields.forEach { (field, offset) ->
            listOf("미지 코드" to 0, "NULL 코드" to 255).forEach { (kind, code) ->
                assertCommandRejected("$field $kind $code", encoded.changed { put(offset, code.toByte()) })
            }
        }
    }

    @Test
    fun `도메인 값으로 표현할 수 없는 숫자와 정규화될 나노초는 변환 오류다`() {
        val encoded = commands.encode(admissionCommand())
        val scenarios =
            listOf(
                "가격 0" to encoded.changed { putLong(PRICE, 0) },
                "가격 -1" to encoded.changed { putLong(PRICE, -1) },
                "수량 -1" to encoded.changed { putLong(QUANTITY, -1) },
                "수량 scale 상한 초과 19" to encoded.changed { put(SCALE, 19) },
                "음수 scale의 uint8 표현 255" to encoded.changed { put(SCALE, 255.toByte()) },
                "정책 버전 0" to encoded.changed { putLong(SCHEDULE_VERSION, 0) },
                "정책 버전 -1" to encoded.changed { putLong(SCHEDULE_VERSION, -1) },
                "maker 수수료 -1ppm" to encoded.changed { putInt(MAKER_PPM, -1) },
                "taker 수수료 상한 초과 1000001ppm" to encoded.changed { putInt(TAKER_PPM, 1_000_001) },
                "나노초 -1을 이전 초로 정규화하면 안 됨" to encoded.changed { putInt(NANO, -1) },
                "나노초 1000000000을 다음 초로 정규화하면 안 됨" to encoded.changed { putInt(NANO, 1_000_000_000) },
                "Instant 범위 밖 Long 최소 초" to encoded.changed { putLong(EPOCH_SECOND, Long.MIN_VALUE) },
                "Instant 범위 밖 Long 최대 초" to encoded.changed { putLong(EPOCH_SECOND, Long.MAX_VALUE) },
            )

        scenarios.forEach { (label, bytes) -> assertCommandRejected(label, bytes) }
    }

    @Test
    fun `256바이트 UTF8 경계는 보존하고 잘못된 길이와 문자열은 대체하지 않는다`() {
        val boundary = admissionCommand(requestId = "😀".repeat(64))
        assertEquals(256, boundary.requestId.toByteArray(Charsets.UTF_8).size)
        assertEquals(boundary, commands.decode(commands.encode(boundary)))

        val encoded = commands.encode(admissionCommand())
        val longField = commands.encode(admissionCommand(requestId = "x".repeat(256)))
        val scenarios =
            listOf(
                "남은 데이터가 있어도 문자열 상한 257바이트 거절" to longField.changed { putShort(COMMAND_VARIABLES, 257) },
                "문자열 길이가 남은 구간을 초과" to encoded.changed { putShort(COMMAND_VARIABLES, 256) },
                "잘못된 UTF8 연속 바이트 C3 28" to
                    encoded.changed {
                        put(COMMAND_VARIABLES + 2, 0xC3.toByte())
                        put(COMMAND_VARIABLES + 3, 0x28)
                    },
                "UTF8 과잉 인코딩 C0 80" to
                    encoded.changed {
                        put(COMMAND_VARIABLES + 2, 0xC0.toByte())
                        put(COMMAND_VARIABLES + 3, 0x80.toByte())
                    },
                "기존 UserId가 표현할 수 없는 공백 사용자" to encoded.changed { repeat(6) { put(COMMAND_VARIABLES + 6 + it, 0x20) } },
            )
        scenarios.forEach { (label, bytes) -> assertCommandRejected(label, bytes) }

        listOf("257바이트 원문" to "x".repeat(257), "고립 상위 서로게이트" to "\uD800", "고립 하위 서로게이트" to "\uDC00").forEach { (label, requestId) ->
            assertRejectedBeforeAdmission(label) { commands.decode(commands.encode(admissionCommand(requestId = requestId))) }
        }
    }

    @Test
    fun `Long 최대 값과 Instant 양 끝 및 수치 상한은 손실이나 NULL 대체 없이 왕복한다`() {
        val original = admissionCommand()
        val maximum =
            original.copy(
                order = original.order.copy(side = Side.SELL, price = Price(Long.MAX_VALUE), quantity = Quantity(Long.MAX_VALUE)),
                market = original.market.copy(baseAssetScale = 18),
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
        val zeroScale = maximum.copy(market = maximum.market.copy(baseAssetScale = 0), occurredAt = Instant.EPOCH)
        val decoded = commands.decode(commands.encode(zeroScale))
        assertEquals(zeroScale, decoded)

        val state =
            OrderAdmissionState(
                listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount(Long.MAX_VALUE), Amount.ZERO)),
                admissionCalculator(),
            )
        val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(state, decoded))
        assertEquals(zeroScale.order, accepted.record.candidate.order)
        assertEquals(Amount(Long.MAX_VALUE), accepted.record.candidate.reservation.reservedAmount)
        assertEquals(
            listOf(Balance(UserId("user-1"), AssetId("BTC"), Amount.ZERO, Amount(Long.MAX_VALUE))),
            state.snapshot().balances,
            "SELL은 최대 가격과 수량의 곱을 계산하지 않고 BTC 수량만 예약하며 USDT 항목을 만들지 않는다",
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
    fun `응답의 종류 고정 블록 필수 문자열과 마지막 바이트도 검증한다`() {
        val accepted = results.encode(AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", 1))
        val rejected = results.encode(insufficient())
        val scenarios =
            listOf(
                "승인에 명령 종류" to accepted.changed { putShort(TEMPLATE, 1) },
                "승인의 잘못된 고정 블록" to accepted.changed { putShort(BLOCK_LENGTH, 9) },
                "거절의 잘못된 고정 블록" to rejected.changed { putShort(BLOCK_LENGTH, 25) },
                "승인 고정 블록 잘림" to accepted.copyOf(15),
                "거절 고정 블록 잘림" to rejected.copyOf(REJECTION_VARIABLES - 1),
                "승인 마지막 주문 ID 바이트 누락" to accepted.copyOf(accepted.size - 1),
                "거절 마지막 자산 ID 바이트 누락" to rejected.copyOf(rejected.size - 1),
                "승인 해석 뒤 잔여 바이트" to (accepted + byteArrayOf(0)),
                "거절 해석 뒤 잔여 바이트" to (rejected + byteArrayOf(0)),
            )
        scenarios.forEach { (label, bytes) -> assertFailsWith<AdmissionMessageException>(label) { results.decode(bytes) } }
        assertFailsWith<AdmissionMessageException>("공유 버퍼의 다음 바이트로 잘린 응답을 완성하면 안 됨") {
            results.decode(accepted + byteArrayOf(0), length = accepted.size - 1)
        }
    }

    @Test
    fun `응답 순번과 거절의 상세 유무 금액은 서로 일치해야 한다`() {
        val accepted = results.encode(AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", 1))
        val rejected = results.encode(insufficient())
        val absent = results.encode(withoutInsufficientBalance())
        val emptyAsset = rejected.copyOf(rejected.size - 4).changed { putShort(rejected.size - 6, 0) }
        val unexpectedAsset = (absent + byteArrayOf('X'.code.toByte())).changed { putShort(absent.size - 2, 1) }
        val scenarios =
            listOf(
                "승인 순번 0" to accepted.changed { putLong(8, 0) },
                "승인 순번 -1" to accepted.changed { putLong(8, -1) },
                "알 수 없는 거절 사유 0" to rejected.changed { put(REASON, 0) },
                "NULL 거절 사유 255" to rejected.changed { put(REASON, 255.toByte()) },
                "알 수 없는 상세 presence 2" to rejected.changed { put(PRESENCE, 2) },
                "NULL 상세 presence 255" to rejected.changed { put(PRESENCE, 255.toByte()) },
                "자금 부족 사유에 상세 없음" to rejected.changed { put(PRESENCE, 0) },
                "자금 부족 이외 사유에 상세 있음" to rejected.changed { put(REASON, 1) },
                "현재 금액 음수" to rejected.changed { putLong(AVAILABLE, -1) },
                "필요 금액 음수" to rejected.changed { putLong(REQUIRED, -1) },
                "부족 금액 음수" to rejected.changed { putLong(SHORTFALL, -1) },
                "필요액이 현재액과 같음" to rejected.changed { putLong(REQUIRED, 200) },
                "부족액이 필요액 빼기 현재액과 다름" to rejected.changed { putLong(SHORTFALL, 99) },
                "상세 없는 응답에 금액 남음" to absent.changed { putLong(AVAILABLE, 1) },
                "상세 없는 응답에 자산 남음" to unexpectedAsset,
                "자금 부족 상세의 자산이 빈 값" to emptyAsset,
            )

        scenarios.forEach { (label, bytes) -> assertFailsWith<AdmissionMessageException>(label) { results.decode(bytes) } }
        val invalidPayloads =
            listOf(
                AdmissionResultPayload.Accepted("user-1", "R1", "BTC-USDT", "O1", 0),
                withoutInsufficientBalance().copy(reason = OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE),
                insufficient().copy(reason = OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION),
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

    private fun insufficient(
        available: Long = 200,
        required: Long = 300,
        shortfall: Long = 100,
    ) = AdmissionResultPayload.Rejected(
        "user-1",
        "R1",
        "BTC-USDT",
        "O1",
        OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE,
        OrderAdmissionResult.InsufficientBalance(AssetId("USDT"), Amount(available), Amount(required), Amount(shortfall)),
    )

    private fun withoutInsufficientBalance() =
        AdmissionResultPayload.Rejected("user-1", "R1", "BTC-USDT", "O1", OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION)

    private fun ByteArray.changed(change: ByteBuffer.() -> Unit): ByteArray =
        copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).change() }

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

    private companion object {
        // XML 스키마를 직접 따라 정한 little-endian 위치다. 생성기의 offset API를 기대값으로 쓰지 않는다.
        const val BLOCK_LENGTH = 0
        const val TEMPLATE = 2
        const val SCHEMA = 4
        const val VERSION = 6
        const val SIDE = 8
        const val ORDER_TYPE = 9
        const val TIME_IN_FORCE = 10
        const val PRICE = 11
        const val QUANTITY = 19
        const val SCALE = 27
        const val PRODUCT = 28
        const val TIER = 29
        const val SCHEDULE_VERSION = 30
        const val MAKER_PPM = 38
        const val TAKER_PPM = 42
        const val EPOCH_SECOND = 46
        const val NANO = 54
        const val COMMAND_VARIABLES = 58
        const val REASON = 8
        const val PRESENCE = 9
        const val AVAILABLE = 10
        const val REQUIRED = 18
        const val SHORTFALL = 26
        const val REJECTION_VARIABLES = 34
    }
}

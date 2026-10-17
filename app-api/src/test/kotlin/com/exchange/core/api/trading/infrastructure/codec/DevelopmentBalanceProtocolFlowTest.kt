package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceConflictException
import com.exchange.core.ledger.OpeningBalanceRequestConflictException
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderAdmissionState
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 자금 준비·주문 계산기에 프레임을 연결한다. DB와 전송 대역은 사용하지 않는다. */
class DevelopmentBalanceProtocolFlowTest {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()
    private val orderCommands = AdmissionCommandCodec()
    private val orderResults = AdmissionResultCodec()
    private val user = UserId("user-1")
    private val usdt = AssetId("USDT")
    private val time = Instant.parse("2026-10-17T00:00:00.123456789Z")

    @Test
    fun `P09 빈 실제 상태는 프레임의 1000 USDT와 두 개시 분개만 반영한다`() {
        val state = state()
        val command = command()
        val before = state.snapshot()

        val response = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(state, command))
        val snapshot = state.snapshot()

        assertEquals(OpeningBalanceResult(command.opening, "L1", time, false), response.result)
        assertEquals(listOf(balance(1_000_000_000)), snapshot.balances)
        assertEquals(before.lastSequence, snapshot.lastSequence)
        assertEquals(emptyList(), snapshot.pendingOrders)
        assertEquals(emptyList(), snapshot.requests)
        assertEquals(1, snapshot.preparations.size)
        assertEquals(response.result, snapshot.preparations.single().result)
        assertOpening(snapshot.preparations.single(), command)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `P09 같은 프레임을 독립된 두 실제 상태에 적용하면 최초와 반복 결과의 의미가 같다`() {
        val firstState = state()
        val secondState = state()
        val command = command()
        val bytes = commands.encode(command)

        val first = sendFrame(firstState, bytes)
        val second = sendFrame(secondState, bytes)
        assertEquals(DevelopmentBalanceResultPayload.Prepared(OpeningBalanceResult(command.opening, "L1", time, false)), first)
        assertEquals(first, second)
        assertEquals(snapshotValues(firstState.snapshot()), snapshotValues(secondState.snapshot()))

        val replay = command.copy(ledgerTransactionId = "unused", occurredAt = Instant.MIN)
        val firstReplay = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(firstState, replay))
        val secondReplay = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(secondState, replay))
        assertEquals(OpeningBalanceResult(command.opening, "L1", time, true), firstReplay.result)
        assertEquals(firstReplay, secondReplay)
        assertEquals(snapshotValues(firstState.snapshot()), snapshotValues(secondState.snapshot()))
    }

    @Test
    fun `P10 같은 원본은 빈 문맥도 허용하고 새 원장 ID와 시각을 소비하지 않는다`() {
        val state = state()
        val command = command()
        val first = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(state, command))
        val before = snapshotValues(state.snapshot())
        val contexts =
            listOf(
                command.copy(ledgerTransactionId = "available-for-next", occurredAt = time.plusSeconds(7)),
                command.copy(ledgerTransactionId = "", occurredAt = Instant.MAX),
                command.copy(ledgerTransactionId = " \t", occurredAt = Instant.MIN),
                command.copy(ledgerTransactionId = "x".repeat(65), occurredAt = Instant.EPOCH),
            )

        contexts.forEach { replay ->
            assertEquals(replay, commands.decode(commands.encode(replay)))
            assertEquals(first.copy(result = first.result.copy(alreadyPrepared = true)), send(state, replay))
            assertEquals(before, snapshotValues(state.snapshot()))
            assertFalse(state.isBlocked)
        }

        val other =
            command.copy(
                opening = command.opening.copy(preparationId = "P2", userId = UserId("other-user")),
                ledgerTransactionId = "available-for-next",
                occurredAt = time.plusSeconds(7),
            )
        assertEquals(
            OpeningBalanceResult(other.opening, "available-for-next", time.plusSeconds(7), false),
            assertIs<DevelopmentBalanceResultPayload.Prepared>(send(state, other)).result,
        )
        assertEquals(listOf("L1", "available-for-next"), state.snapshot().preparations.map { it.transaction.ledgerTransactionId })
    }

    @Test
    fun `P10 표현 불가능한 반복 문맥은 코어를 호출하기 전에 변환 실패한다`() {
        val state = state()
        val original = command()
        send(state, original)
        val before = snapshotValues(state.snapshot())
        listOf("x".repeat(257), "\uD800").forEach { invalid ->
            var coreCalls = 0
            assertFailsWith<AdmissionMessageException> {
                val decoded = commands.decode(commands.encode(original.copy(ledgerTransactionId = invalid)))
                coreCalls++
                prepare(state, decoded)
            }
            assertEquals(0, coreCalls)
            assertEquals(before, snapshotValues(state.snapshot()))
            assertFalse(state.isBlocked)
        }
    }

    @Test
    fun `P11 주문 프레임으로 300 USDT를 예약해도 반복 응답은 최초 1000 USDT 원본을 유지한다`() {
        val state = state()
        val command = command()
        val first = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(state, command))

        val accepted = assertIs<AdmissionResultPayload.Accepted>(sendOrder(state, admissionCommand()))
        val before = snapshotValues(state.snapshot())
        val replay = assertIs<DevelopmentBalanceResultPayload.Prepared>(send(state, command.copy(ledgerTransactionId = "unused")))
        val snapshot = state.snapshot()

        assertEquals(1L, accepted.sequence)
        assertEquals(first.result.copy(alreadyPrepared = true), replay.result)
        assertEquals(Amount(1_000_000_000), replay.result.opening.amount)
        assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
        assertEquals(before, snapshotValues(snapshot))
        assertEquals(1, snapshot.preparations.size)
        assertOpening(snapshot.preparations.single(), command)
        assertEquals(1, snapshot.pendingOrders.size)
        assertEquals(
            LedgerTransactionType.RESERVE,
            snapshot.pendingOrders
                .single()
                .candidate.reserveTransaction.transactionType,
        )
        assertEquals(2, snapshot.preparations.size + snapshot.pendingOrders.size)
        assertEquals(1L, snapshot.lastSequence)
        assertEquals(listOf("R1"), snapshot.requests.map { it.requestId })
    }

    @Test
    fun `P12 원본 변경과 사용 계정 재개시는 제출 원본에 연결된 서로 다른 충돌이다`() {
        val state = state()
        val original = command()
        send(state, original)
        val before = snapshotValues(state.snapshot())
        val changedOpenings =
            listOf(
                original.opening.copy(amount = Amount(2_000_000_000)),
                original.opening.copy(userId = UserId("other-user")),
                original.opening.copy(assetId = AssetId("BTC")),
            )

        changedOpenings.forEach { submitted ->
            val response = send(state, original.copy(opening = submitted, ledgerTransactionId = "unused", occurredAt = Instant.MIN))
            assertEquals(
                DevelopmentBalanceResultPayload.Rejected(submitted, DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT),
                response,
            )
            assertEquals(before, snapshotValues(state.snapshot()))
            assertFalse(state.isBlocked)
        }

        val reusedAccount = original.opening.copy(preparationId = "P2")
        assertEquals(
            DevelopmentBalanceResultPayload.Rejected(reusedAccount, DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED),
            send(state, original.copy(opening = reusedAccount, ledgerTransactionId = "unused", occurredAt = Instant.MIN)),
        )
        assertEquals(before, snapshotValues(state.snapshot()))
        assertEquals(listOf(balance(1_000_000_000)), state.snapshot().balances)
        assertEquals(1, state.snapshot().preparations.size)
        assertFalse(state.isBlocked)
    }

    @Test
    fun `P13 부족했던 R1은 준비 후에도 첫 응답이고 새 R2만 최신 잔고로 승인한다`() {
        val state = state()
        val originalOrder = admissionCommand()
        val first = assertIs<AdmissionResultPayload.Rejected>(sendOrder(state, originalOrder))
        assertEquals(OrderAdmissionResult.Reason.INSUFFICIENT_BALANCE, first.reason)
        assertEquals(
            OrderAdmissionResult.InsufficientBalance(usdt, Amount.ZERO, Amount(300_000_000), Amount(300_000_000)),
            first.insufficientBalance,
        )
        val firstRequest = requestValues(state.snapshot().requests.single())

        send(state, command())

        assertEquals(listOf(firstRequest), state.snapshot().requests.map(::requestValues))
        val funded = snapshotValues(state.snapshot())
        assertEquals(first, sendOrder(state, originalOrder.copy(ledgerTransactionId = "", occurredAt = Instant.MAX)))
        assertEquals(funded, snapshotValues(state.snapshot()))
        assertEquals(listOf(balance(1_000_000_000)), state.snapshot().balances)
        val accepted = assertIs<AdmissionResultPayload.Accepted>(sendOrder(state, admissionCommand("R2", "O2")))
        assertEquals(1L, accepted.sequence)
        assertEquals(first, sendOrder(state, originalOrder))
        val snapshot = state.snapshot()
        assertEquals(listOf(balance(700_000_000, 300_000_000)), snapshot.balances)
        assertEquals(listOf("R1", "R2"), snapshot.requests.map { it.requestId })
        assertEquals(firstRequest, requestValues(snapshot.requests.first()))
        assertEquals(1, snapshot.preparations.size)
        assertEquals(1, snapshot.pendingOrders.size)
    }

    @Test
    fun `P14 반영 중 원래 Exception과 Error는 응답으로 바뀌지 않고 신규와 반복 준비를 차단한다`() {
        listOf(IllegalStateException("준비 기록 쓰기 실패"), AssertionError("준비 기록 쓰기 Error")).forEach { failure ->
            val state = state()
            val original = command()
            send(state, original)
            val observed = state.snapshot()
            val observedValues = snapshotValues(observed)
            failOnPreparationWrite(state, failure)
            val next =
                original
                    .copy(opening = original.opening.copy(preparationId = "P2", userId = UserId("other-user")))
                    .copy(ledgerTransactionId = "L2")
            val decoded = commands.decode(commands.encode(next))
            assertEquals(next, decoded)

            assertSame(failure, assertFailsWith<Throwable> { send(state, decoded) })

            assertTrue(state.isBlocked)
            listOf(original, next.copy(opening = next.opening.copy(preparationId = "P3"))).forEach { blocked ->
                assertEquals(blocked, commands.decode(commands.encode(blocked)))
                val error = assertFailsWith<IllegalStateException> { send(state, blocked) }
                assertEquals("반영 중 오류로 접수 상태가 차단됐습니다", error.message)
                assertIs<IllegalStateException>(error)
            }
            assertFailsWith<IllegalStateException> { state.snapshot() }
            assertEquals(observedValues, snapshotValues(observed))
        }
    }

    @Test
    fun `잘못된 신규 문맥과 이미 쓴 원장 ID는 예상 충돌 응답으로 바뀌지 않는다`() {
        val state = state()
        val original = command()
        val empty = snapshotValues(state.snapshot())
        val invalid = original.copy(ledgerTransactionId = "")
        assertEquals(invalid, commands.decode(commands.encode(invalid)))
        assertEquals("원장 거래 식별자가 잘못됐습니다", assertFailsWith<IllegalArgumentException> { send(state, invalid) }.message)
        assertEquals(empty, snapshotValues(state.snapshot()))
        send(state, original)
        val funded = snapshotValues(state.snapshot())
        val other = original.copy(opening = original.opening.copy(preparationId = "P2", userId = UserId("other-user")))
        assertEquals("원장 거래 ID가 이미 사용됐습니다", assertFailsWith<IllegalStateException> { send(state, other) }.message)
        assertEquals(funded, snapshotValues(state.snapshot()))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `P15 다른 원본이나 최초 원장 ID 시각의 불일치를 성공 응답으로 연결하지 않는다`() {
        val state = state()
        val command = command()
        val first = prepare(state, command)
        val before = snapshotValues(state.snapshot())
        listOf(
            command.opening.copy(preparationId = "OTHER"),
            command.opening.copy(userId = UserId("other-user")),
            command.opening.copy(assetId = AssetId("BTC")),
            command.opening.copy(amount = Amount(1)),
        ).forEach { otherOpening ->
            listOf(false, true).forEach { replay ->
                assertFailsWith<IllegalStateException> {
                    DevelopmentBalanceResultPayload.from(command, first.copy(opening = otherOpening, alreadyPrepared = replay))
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            DevelopmentBalanceResultPayload.from(command, first.copy(ledgerTransactionId = "OTHER"))
        }
        assertFailsWith<IllegalStateException> {
            DevelopmentBalanceResultPayload.from(command, first.copy(occurredAt = time.plusNanos(1)))
        }
        assertEquals(before, snapshotValues(state.snapshot()))
    }

    @Test
    fun `성공 뒤 응답 변환 실패는 지급을 되돌리지 않고 같은 원본으로 최초 결과를 확인한다`() {
        val state = state()
        val command = command()
        val prepared = DevelopmentBalanceResultPayload.from(command, prepare(state, command))
        val before = snapshotValues(state.snapshot())
        val result = assertIs<DevelopmentBalanceResultPayload.Prepared>(prepared).result

        assertFailsWith<AdmissionMessageException> {
            results.encode(DevelopmentBalanceResultPayload.Prepared(result.copy(ledgerTransactionId = "x".repeat(257))))
        }

        assertEquals(before, snapshotValues(state.snapshot()))
        assertEquals(
            DevelopmentBalanceResultPayload.Prepared(result.copy(alreadyPrepared = true)),
            send(state, command.copy(ledgerTransactionId = "", occurredAt = Instant.MAX)),
        )
        assertEquals(before, snapshotValues(state.snapshot()))
        assertFalse(state.isBlocked)
    }

    @Test
    fun `P16 구간 버퍼 덮어쓰기와 다음 인코딩 준비 주문 호출은 이전 값과 관찰을 바꾸지 않는다`() {
        val state = state()
        val command = command()
        val commandBytes = commands.encode(command)
        val preservedCommandBytes = commandBytes.copyOf()
        val commandBuffer = ByteArray(commandBytes.size + 9) { 0x7f }
        commandBytes.copyInto(commandBuffer, 4)
        val decoded = commands.decode(commandBuffer, 4, commandBytes.size)
        commandBuffer.fill(0)
        assertEquals(command, decoded)
        val response = DevelopmentBalanceResultPayload.from(decoded, prepare(state, decoded))
        val responseBytes = results.encode(response)
        val preservedResponseBytes = responseBytes.copyOf()
        val responseBuffer = ByteArray(responseBytes.size + 7) { 0x7e }
        responseBytes.copyInto(responseBuffer, 3)
        val decodedResponse = results.decode(responseBuffer, 3, responseBytes.size)
        responseBuffer.fill(0)
        val observed = state.snapshot()
        val observedValues = snapshotValues(observed)

        val next =
            command.copy(
                opening = command.opening.copy(preparationId = "P2", userId = UserId("other-user")),
                ledgerTransactionId = "L2",
                occurredAt = time.plusNanos(1),
            )
        send(state, next)
        sendOrder(state, admissionCommand())
        results.encode(DevelopmentBalanceResultPayload.Rejected(next.opening, DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED))

        assertContentEquals(preservedCommandBytes, commandBytes)
        assertContentEquals(preservedResponseBytes, responseBytes)
        assertEquals(command, decoded)
        assertEquals(response, decodedResponse)
        assertEquals(
            OpeningBalanceResult(command.opening, "L1", time, false),
            assertIs<DevelopmentBalanceResultPayload.Prepared>(decodedResponse).result,
        )
        assertEquals(observedValues, snapshotValues(observed))
        assertEquals(listOf(balance(1_000_000_000)), observed.balances)
        assertEquals(1, observed.preparations.size)
        assertEquals(emptyList(), observed.pendingOrders)
        assertEquals(emptyList(), observed.requests)
        assertOpening(observed.preparations.single(), command)
        assertFailsWith<UnsupportedOperationException> { (observed.preparations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (
                observed.preparations
                    .single()
                    .transaction.postings as MutableList
            ).clear()
        }
        assertEquals(2, state.snapshot().preparations.size)
        assertEquals(
            listOf(balance(700_000_000, 300_000_000), balance(1_000_000_000).copy(userId = UserId("other-user"))),
            state.snapshot().balances,
        )
    }

    private fun state() = OrderAdmissionState(emptyList(), admissionCalculator())

    private fun command() = DevelopmentBalanceCommandPayload(OpeningBalance("P1", user, usdt, Amount(1_000_000_000)), "L1", time)

    private fun balance(
        available: Long,
        hold: Long = 0,
    ) = Balance(user, usdt, Amount(available), Amount(hold))

    private fun prepare(
        state: OrderAdmissionState,
        command: DevelopmentBalanceCommandPayload,
    ) = state.prepareDevelopmentBalance(command.opening, command.ledgerTransactionId, command.occurredAt)

    private fun send(
        state: OrderAdmissionState,
        command: DevelopmentBalanceCommandPayload,
    ): DevelopmentBalanceResultPayload = sendFrame(state, commands.encode(command))

    private fun sendFrame(
        state: OrderAdmissionState,
        bytes: ByteArray,
    ): DevelopmentBalanceResultPayload {
        val command = commands.decode(bytes)
        val response =
            try {
                DevelopmentBalanceResultPayload.from(command, prepare(state, command))
            } catch (error: OpeningBalanceRequestConflictException) {
                DevelopmentBalanceResultPayload.from(command, error)
            } catch (error: OpeningBalanceConflictException) {
                DevelopmentBalanceResultPayload.from(command, error)
            }
        return results.decode(results.encode(response))
    }

    private fun sendOrder(
        state: OrderAdmissionState,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload {
        val decoded = orderCommands.decode(orderCommands.encode(command))
        val response = AdmissionResultPayload.from(decoded, admit(state, decoded))
        return orderResults.decode(orderResults.encode(response))
    }

    private fun assertOpening(
        record: OrderAdmissionState.PreparationRecord,
        command: DevelopmentBalanceCommandPayload,
    ) {
        val transaction = record.transaction
        assertEquals("L1", transaction.ledgerTransactionId)
        assertEquals("OPENING:P1", transaction.sourceEventId)
        assertEquals(LedgerTransactionType.OPENING, transaction.transactionType)
        assertEquals(time, transaction.occurredAt)
        assertEquals(command.opening, record.result.opening)
        assertEquals(
            listOf(
                LedgerPosting("SYSTEM:USDT:DEVELOPMENT_FUNDING", usdt, LedgerPostingSide.DEBIT, Amount(1_000_000_000)),
                LedgerPosting("USER:user-1:USDT:AVAILABLE", usdt, LedgerPostingSide.CREDIT, Amount(1_000_000_000)),
            ),
            transaction.postings,
        )
    }

    /** 후보·원장·기록의 객체 동일성 대신 원본과 모든 의미 필드를 비교한다. */
    private fun snapshotValues(snapshot: OrderAdmissionState.Snapshot): List<Any> =
        listOf(
            snapshot.balances,
            snapshot.pendingOrders.map(::recordValues),
            snapshot.lastSequence,
            snapshot.requests.map(::requestValues),
            snapshot.preparations.map { listOf(it.result, transactionValues(it.transaction)) },
        )

    private fun requestValues(request: OrderAdmissionState.RequestRecord): List<Any> =
        listOf(
            request.requestId,
            request.order,
            request.market,
            request.feePolicySnapshot,
            request.ledgerTransactionId,
            request.occurredAt,
            when (val result = request.result) {
                is OrderAdmissionState.Result.Accepted -> recordValues(result.record)
                is OrderAdmissionState.Result.Rejected -> result
            },
        )

    private fun recordValues(record: OrderAdmissionState.Record): List<Any> =
        record.candidate.let { candidate ->
            listOf(
                record.sequence,
                candidate.order,
                candidate.market,
                candidate.feePolicySnapshot,
                candidate.previousBalance,
                candidate.nextBalance,
                candidate.reservation,
                transactionValues(candidate.reserveTransaction),
            )
        }

    private fun transactionValues(transaction: LedgerTransaction): List<Any> =
        listOf(
            transaction.ledgerTransactionId,
            transaction.sourceEventId,
            transaction.transactionType,
            transaction.occurredAt,
            transaction.postings,
        )

    // 코어의 기존 장애 테스트처럼 실제 collection 쓰기 한 곳만 실패시킨다.
    private fun failOnPreparationWrite(
        state: OrderAdmissionState,
        failure: Throwable,
    ) {
        val field = OrderAdmissionState::class.java.getDeclaredField("preparations").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val original = field.get(state) as MutableMap<Any, Any>
        field.set(
            state,
            object : MutableMap<Any, Any> by original {
                override fun put(
                    key: Any,
                    value: Any,
                ): Any? = throw failure
            },
        )
    }
}

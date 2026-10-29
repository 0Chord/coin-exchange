package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceRejectionReason
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 코어에 callback을 연결하고 전송 결과만 통제한다. 실제 네트워크 검사는 별도다. */
class DevelopmentFundingAdapterTest {
    private val commands = DevelopmentBalanceCommandCodec()
    private val results = DevelopmentBalanceResultCodec()
    private val time = Instant.parse("2026-10-17T00:00:00.123456789Z")
    private val header = Header(0, 0)

    @Test
    fun `세션 헤더 뒤의 실행 명령을 commit한 다음에만 접수 응답을 보낸다`() {
        val fixture = fixture()
        send(fixture, command())
        val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
        var acknowledgements = 0
        doAnswer {
            acknowledgements++
            assertContentEquals(
                ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(1)),
                fixture.executionClaims.committedMessages.single(),
            )
            assertTrue(fixture.executionClaims.sessionHeaderIntact())
            assertTrue(fixture.executionClaims.boundariesIntact())
            val snapshot = fixture.state.snapshot()
            assertEquals(1, snapshot.pendingOrders.size)
            assertTrue(snapshot.completions.isEmpty(), "별도 확정 실행 callback 전에는 매칭·정산하지 않는다")
            1L
        }.`when`(fixture.session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())

        repeat(2) { sendBytes(fixture, AdmissionCommandCodec().encode(order)) }

        assertEquals(2, acknowledgements)
        assertEquals(1, fixture.executionClaims.attempts.size, "접수 재전송은 새 실행 claim을 만들지 않는다")
        assertEquals(0, fixture.executionClaims.abortedClaims)
        assertEquals("OFFERED", fixture.service.observation!!.replyStatus)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `실행 claim을 쓴 뒤 인코딩이 실패하면 abort하고 예약을 보존한 채 노드를 중단한다`() {
        listOf(IllegalStateException("claim 쓰기 실패"), AssertionError("claim 쓰기 Error")).forEach { failure ->
            mockConstruction(
                ExecuteAdmissionCodec::class.java,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
            ) { codec, _ ->
                doAnswer { invocation ->
                    // 일부 본문을 쓴 다음 실패해도 frame이 정상 메시지로 공개되면 안 된다.
                    invocation.getArgument<MutableDirectBuffer>(1).putByte(invocation.getArgument<Int>(2), 0x66.toByte())
                    throw failure
                }.`when`(codec).encodeInto(anyLong(), anyMutableDirectBuffer(), anyInt())
            }.use {
                val fixture = fixture()
                send(fixture, command())
                val repliesBefore = fixture.replies.size
                val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }

                val fatal =
                    assertFailsWith<AgentTerminationException> {
                        sendBytes(fixture, AdmissionCommandCodec().encode(order))
                    }

                assertSame(failure, fatal.cause)
                assertSame(failure, fixture.failures.single())
                assertEquals(1, fixture.executionClaims.attempts.size)
                assertEquals(1, fixture.executionClaims.abortedClaims)
                assertTrue(fixture.executionClaims.committedMessages.isEmpty())
                assertTrue(fixture.executionClaims.sessionHeaderIntact())
                assertTrue(fixture.executionClaims.boundariesIntact())
                assertEquals(repliesBefore, fixture.replies.size, "실행 의도를 commit하지 못하면 접수 ACK를 보내지 않는다")
                assertFalse(fixture.service.started)
                assertFalse(fixture.state.isBlocked)
                val snapshot = fixture.state.snapshot()
                assertEquals(1, snapshot.pendingOrders.size)
                assertEquals(
                    300_000_000L,
                    snapshot.balances
                        .single()
                        .hold.value,
                )
                assertTrue(snapshot.completions.isEmpty())
                assertFailsWith<AgentTerminationException> { sendBytes(fixture, AdmissionCommandCodec().encode(order)) }
                assertEquals(1, fixture.executionClaims.attempts.size)
            }
        }
    }

    @Test
    fun `실행 claim의 역압력과 ADMIN_ACTION은 같은 길이와 claim으로 유한 재시도한다`() {
        val fixture =
            fixture(
                executionOffers = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 99L),
                maxExecutionOfferAttempts = 3,
            )
        send(fixture, command())
        val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }

        sendBytes(fixture, AdmissionCommandCodec().encode(order))

        val expected = ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(1))
        assertEquals(List(3) { expected.size }, fixture.executionClaims.attempts)
        fixture.executionClaims.attemptClaims.forEach { assertSame(fixture.executionClaims.attemptClaims.first(), it) }
        assertContentEquals(expected, fixture.executionClaims.committedMessages.single())
        assertEquals(0, fixture.executionClaims.abortedClaims)
        verify(fixture.idle, times(2)).idle()
        assertEquals("OFFERED", fixture.service.observation!!.replyStatus)
        assertTrue(
            fixture.state
                .snapshot()
                .completions
                .isEmpty(),
        )
    }

    @Test
    fun `claim 획득과 commit 직후 계측 Error는 소유한 미확정 frame만 abort한다`() {
        listOf(false, true).forEach { failAfterCommit ->
            val failure = AssertionError("claim 경계의 계측 Error: committed=$failAfterCommit")
            val fixture = fixture(start = false)
            var armed = false
            fixture.service.attachTelemetry(TradingNodeTelemetry { }, nanoTime = {
                val reached =
                    if (failAfterCommit) {
                        fixture.executionClaims.committedMessages.isNotEmpty()
                    } else {
                        fixture.executionClaims.claims.isNotEmpty()
                    }
                if (armed && reached) {
                    armed = false
                    throw failure
                }
                System.nanoTime()
            })
            fixture.service.onStart(fixture.cluster, null)
            send(fixture, command())
            val repliesBefore = fixture.replies.size
            val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
            armed = true

            val fatal =
                assertFailsWith<AgentTerminationException> {
                    sendBytes(fixture, AdmissionCommandCodec().encode(order))
                }

            assertSame(failure, fatal.cause)
            assertSame(failure, fixture.failures.single())
            assertEquals(1, fixture.executionClaims.attempts.size)
            assertEquals(if (failAfterCommit) 0 else 1, fixture.executionClaims.abortedClaims)
            assertEquals(if (failAfterCommit) 1 else 0, fixture.executionClaims.committedMessages.size)
            if (failAfterCommit) {
                assertContentEquals(
                    ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(1)),
                    fixture.executionClaims.committedMessages.single(),
                )
            }
            assertTrue(fixture.executionClaims.sessionHeaderIntact())
            assertTrue(fixture.executionClaims.boundariesIntact())
            assertEquals(repliesBefore, fixture.replies.size)
            assertFalse(fixture.service.started)
            assertEquals(
                1,
                fixture.state
                    .snapshot()
                    .pendingOrders.size,
            )
            assertTrue(
                fixture.state
                    .snapshot()
                    .completions
                    .isEmpty(),
            )
        }
    }

    @Test
    fun `실행 claim 횟수 소진과 종단 반환은 commit과 접수 ACK 없이 노드를 중단한다`() {
        val cases =
            listOf(
                Publication.BACK_PRESSURED to 3,
                Publication.ADMIN_ACTION to 3,
                Publication.NOT_CONNECTED to 1,
                Publication.CLOSED to 1,
                Publication.MAX_POSITION_EXCEEDED to 1,
                0L to 1,
            )
        cases.forEach { (result, attempts) ->
            val fixture = fixture(executionOffers = listOf(result), maxExecutionOfferAttempts = 3)
            send(fixture, command())
            val repliesBefore = fixture.replies.size
            val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }

            assertFailsWith<AgentTerminationException> { sendBytes(fixture, AdmissionCommandCodec().encode(order)) }

            assertEquals(attempts, fixture.executionClaims.attempts.size)
            assertTrue(fixture.executionClaims.claims.isEmpty(), "음수 또는 0 반환에는 claim 영역이 없다")
            assertEquals(0, fixture.executionClaims.abortedClaims)
            assertEquals(repliesBefore, fixture.replies.size)
            assertFalse(fixture.service.started)
            assertEquals(1, fixture.failures.size)
            assertEquals(
                1,
                fixture.state
                    .snapshot()
                    .pendingOrders.size,
            )
            assertTrue(
                fixture.state
                    .snapshot()
                    .completions
                    .isEmpty(),
            )
        }
    }

    @Test
    fun `비영점 구간만 복원하고 업무 시각과 불변 관찰을 보존한다`() {
        val fixture = fixture()
        val bytes = commands.encode(command())
        val framed = clusterFrame(bytes)
        val shared = ByteArray(11 + framed.capacity() + 7) { 0x7F }
        framed.getBytes(0, shared, 11, framed.capacity())

        fixture.service.onSessionMessage(
            fixture.session,
            Long.MAX_VALUE,
            UnsafeBuffer(shared),
            11 + AeronCluster.SESSION_HEADER_LENGTH,
            bytes.size,
            header,
        )
        shared.fill(0)

        val observation = assertNotNull(fixture.service.captureObservation().join())
        val prepared = assertIs<DevelopmentBalanceResultPayload.Prepared>(results.decode(fixture.replies.single()))
        assertEquals(OpeningBalanceResult(command().opening, "L1", time, false), prepared.result)
        assertEquals(256L, observation.position)
        assertEquals(prepared, observation.result)
        assertEquals("OFFERED", observation.replyStatus)
        val snapshot = assertNotNull(observation.snapshot)
        assertEquals(Amount(1_000_000_000), snapshot.balances.single().available)
        assertEquals(Amount.ZERO, snapshot.balances.single().hold)
        assertEquals(prepared.result, snapshot.preparations.single().result)
        assertEquals(
            2,
            snapshot.preparations
                .single()
                .transaction.postings.size,
        )
        assertTrue(snapshot.pendingOrders.isEmpty())

        send(fixture, command("P2", "U2", "L2"))
        assertEquals(1, snapshot.balances.size)
        assertEquals(1, snapshot.preparations.size)
        assertEquals(
            2,
            fixture.service
                .captureObservation()
                .join()!!
                .snapshot!!
                .preparations.size,
        )
    }

    @Test
    fun `같은 원본의 재전송은 새 문맥을 사용하지 않고 최초 결과를 보낸다`() {
        val fixture = fixture()
        send(fixture, command())

        send(fixture, command().copy(ledgerTransactionId = "", occurredAt = Instant.MAX))

        val replay = assertIs<DevelopmentBalanceResultPayload.Prepared>(results.decode(fixture.replies.last()))
        assertEquals(OpeningBalanceResult(command().opening, "L1", time, true), replay.result)
        val snapshot =
            assertNotNull(
                fixture.service
                    .captureObservation()
                    .join()!!
                    .snapshot,
            )
        assertEquals(Amount(1_000_000_000), snapshot.balances.single().available)
        assertEquals(1, snapshot.preparations.size)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `두 typed 충돌만 정상 응답이고 다른 계정은 계속 준비한다`() {
        val fixture = fixture()
        send(fixture, command())
        send(fixture, command().copy(opening = command().opening.copy(amount = Amount(2_000_000_000))))
        assertEquals(
            DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT,
            assertIs<DevelopmentBalanceResultPayload.Rejected>(results.decode(fixture.replies.last())).reason,
        )
        send(fixture, command("P2", "U1", "L2"))
        assertEquals(
            DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED,
            assertIs<DevelopmentBalanceResultPayload.Rejected>(results.decode(fixture.replies.last())).reason,
        )
        assertEquals(
            1,
            fixture.service
                .captureObservation()
                .join()!!
                .snapshot!!
                .preparations.size,
        )

        send(fixture, command("P3", "U2", "L3"))

        assertEquals(
            2,
            fixture.service
                .captureObservation()
                .join()!!
                .snapshot!!
                .preparations.size,
        )
        assertTrue(fixture.service.started)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun `리더만 자금과 접수 응답을 인코딩하고 모든 사본은 예약과 실행 제출을 보존한다`() {
        listOf(Cluster.Role.LEADER, Cluster.Role.FOLLOWER).forEach { role ->
            mockConstruction(
                DevelopmentBalanceResultCodec::class.java,
                withSettings().defaultAnswer(CALLS_REAL_METHODS),
            ).use { fundingCodecs ->
                mockConstruction(
                    AdmissionResultCodec::class.java,
                    withSettings().defaultAnswer(CALLS_REAL_METHODS),
                ).use { admissionCodecs ->
                    val fixture = fixture(role = role)

                    send(fixture, command())
                    val funding = assertIs<DevelopmentBalanceResultPayload.Prepared>(fixture.service.observation!!.result)
                    val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
                    sendBytes(fixture, AdmissionCommandCodec().encode(order))

                    val observation = assertNotNull(fixture.service.captureObservation().join())
                    val accepted = assertIs<AdmissionResultPayload.Accepted>(observation.admissionResult)
                    val snapshot = assertNotNull(observation.snapshot)
                    assertEquals(
                        700_000_000L,
                        snapshot.balances
                            .single()
                            .available.value,
                    )
                    assertEquals(
                        300_000_000L,
                        snapshot.balances
                            .single()
                            .hold.value,
                    )
                    assertEquals(accepted.sequence, snapshot.pendingOrders.single().sequence)
                    assertEquals(
                        order.order,
                        snapshot.pendingOrders
                            .single()
                            .candidate.order,
                    )
                    assertEquals(1, snapshot.preparations.size)
                    assertEquals(
                        accepted.sequence,
                        ExecuteAdmissionCodec().decode(fixture.executionClaims.committedMessages.single()).admissionSequence,
                    )
                    assertTrue(fixture.executionClaims.sessionHeaderIntact())
                    assertTrue(fixture.executionClaims.boundariesIntact())
                    verify(fixture.cluster, times(1)).tryClaim(anyInt(), any(BufferClaim::class.java))

                    val fundingCodec = fundingCodecs.constructed().single()
                    val admissionCodec = admissionCodecs.constructed().single()
                    if (role == Cluster.Role.LEADER) {
                        verify(fundingCodec, times(1)).encode(funding)
                        verify(admissionCodec, times(1)).encode(accepted)
                        assertEquals(2, fixture.replies.size)
                        assertEquals(funding, results.decode(fixture.replies.first()))
                        assertEquals(accepted, admissionCodec.decode(fixture.replies.last()))
                        assertEquals("OFFERED", observation.replyStatus)
                        assertEquals(1, observation.replyAttempts)
                    } else {
                        verifyNoInteractions(fundingCodec, admissionCodec, fixture.session)
                        assertTrue(fixture.replies.isEmpty())
                        assertEquals("NOT_LEADER", observation.replyStatus)
                        assertEquals(0, observation.replyAttempts)
                    }
                    assertTrue(fixture.service.started)
                    assertTrue(fixture.failures.isEmpty())
                }
            }
        }
    }

    @Test
    fun `유효한 Cluster 헤더의 외부 본문이 잘리거나 미지원이면 코어 진입 없이 다음 요청을 허용한다`() {
        listOf(Cluster.Role.LEADER, Cluster.Role.FOLLOWER).forEach { role ->
            val fixture = fixture(role = role)
            val bytes = commands.encode(command())
            val missing =
                com.exchange.core.api.trading.infrastructure.codec
                    .protobufFrame(4, byteArrayOf())
            val unsupported =
                com.exchange.core.api.trading.infrastructure.codec
                    .protobufFrame(2, byteArrayOf())
            listOf(bytes.copyOf(7), bytes.copyOf(bytes.size - 1), missing, unsupported, ByteArray(4097)).forEach {
                sendBytes(fixture, it)
            }
            assertEquals(5L, fixture.service.observation!!.malformedMessages)
            verifyNoInteractions(fixture.state, fixture.session)

            send(fixture, command())

            assertEquals(5L, fixture.service.observation!!.malformedMessages)
            assertEquals(
                1,
                fixture.service
                    .captureObservation()
                    .join()!!
                    .snapshot!!
                    .preparations.size,
            )
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `Cluster 세션 헤더를 읽을 수 없는 구간은 외부 본문 오류로 숨기지 않고 중단한다`() {
        val bytes = commands.encode(command())
        listOf(-1, 0, Int.MAX_VALUE).forEach { offset ->
            val fixture = fixture()

            val fatal =
                assertFailsWith<AgentTerminationException>("offset=$offset") {
                    fixture.service.onSessionMessage(fixture.session, 0, clusterFrame(bytes), offset, bytes.size, header)
                }

            assertSame(fatal.cause, fixture.failures.single())
            assertFalse(fixture.service.started)
            assertFalse(fixture.state.isBlocked, "메타데이터 오류는 돈 계산을 시작하지 않는다")
            assertTrue(
                fixture.state
                    .snapshot()
                    .balances
                    .isEmpty(),
            )
            assertNull(fixture.service.observation!!.snapshot)
            verifyNoInteractions(fixture.session)
        }
    }

    @Test
    fun `주문 응답이 밀려도 같은 바이트만 다시 보내고 예약은 한 번만 만든다`() {
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 99L))
        sendBytes(fixture, commands.encode(command()), null)
        val command = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
        val bytes = AdmissionCommandCodec().encode(command)
        val framed = clusterFrame(bytes)
        val shared = ByteArray(framed.capacity() + 17) { 0x7F }
        framed.getBytes(0, shared, 9, framed.capacity())

        fixture.service.onSessionMessage(
            fixture.session,
            Long.MAX_VALUE,
            UnsafeBuffer(shared),
            9 + AeronCluster.SESSION_HEADER_LENGTH,
            bytes.size,
            header,
        )
        shared.fill(0)

        assertEquals(3, fixture.replies.size)
        fixture.replies.forEach { assertContentEquals(fixture.replies.first(), it) }
        val accepted = assertIs<AdmissionResultPayload.Accepted>(AdmissionResultCodec().decode(fixture.replies.first()))
        assertEquals(1L, accepted.sequence)
        val observation = assertNotNull(fixture.service.captureObservation().join())
        assertEquals(accepted, observation.admissionResult)
        assertNull(observation.result)
        assertEquals("OFFERED", observation.replyStatus)
        assertEquals(3, observation.replyAttempts)
        val snapshot = assertNotNull(observation.snapshot)
        assertEquals(
            700_000_000L,
            snapshot.balances
                .single()
                .available.value,
        )
        assertEquals(
            300_000_000L,
            snapshot.balances
                .single()
                .hold.value,
        )
        assertEquals(
            command.occurredAt,
            snapshot.pendingOrders
                .single()
                .candidate.reserveTransaction.occurredAt,
        )
        assertEquals(1, snapshot.requests.size)
        verify(
            fixture.state,
            times(1),
        ).admit(
            command.requestId,
            command.order,
            command.market,
            command.feePolicySnapshot,
            command.ledgerTransactionId,
            command.occurredAt,
        )
    }

    @Test
    fun `주문 응답의 종단 상한 팔로워와 닫는 세션은 예약을 취소하지 않는다`() {
        val cases =
            listOf(
                Publication.NOT_CONNECTED to "NOT_CONNECTED",
                Publication.CLOSED to "CLOSED",
                Publication.MAX_POSITION_EXCEEDED to "MAX_POSITION_EXCEEDED",
                Publication.BACK_PRESSURED to "ATTEMPTS_EXHAUSTED",
            )
        cases.forEach { (offer, expected) ->
            val fixture = fixture(offers = listOf(offer), maxReplyAttempts = 2)
            sendBytes(fixture, commands.encode(command()), null)
            sendBytes(
                fixture,
                AdmissionCommandCodec().encode(admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }),
            )

            val observation = assertNotNull(fixture.service.captureObservation().join())
            assertEquals(expected, observation.replyStatus)
            assertEquals(if (offer == Publication.BACK_PRESSURED) 2 else 1, observation.replyAttempts)
            assertEquals(
                300_000_000L,
                observation.snapshot!!
                    .balances
                    .single()
                    .hold.value,
            )
            assertEquals(1, observation.snapshot.pendingOrders.size)
            assertTrue(fixture.failures.isEmpty())
        }
        listOf(Cluster.Role.FOLLOWER to true, Cluster.Role.LEADER to false, Cluster.Role.LEADER to true).forEach { (role, hasSession) ->
            val fixture = fixture(role = role)
            sendBytes(fixture, commands.encode(command()), null)
            if (hasSession && role == Cluster.Role.LEADER) `when`(fixture.session.isClosing).thenReturn(true)

            sendBytes(
                fixture,
                AdmissionCommandCodec().encode(
                    admissionCommand().let {
                        it.copy(order = it.order.copy(userId = UserId("U1")))
                    },
                ),
                if (hasSession) fixture.session else null,
            )

            val observation = assertNotNull(fixture.service.captureObservation().join())
            assertIs<AdmissionResultPayload.Accepted>(observation.admissionResult)
            assertEquals(
                300_000_000L,
                observation.snapshot!!
                    .balances
                    .single()
                    .hold.value,
            )
            assertEquals(0, observation.replyAttempts)
            assertTrue(fixture.replies.isEmpty())
        }
    }

    @Test
    fun `주문 반영 전 오류와 반영 중 오류는 정상 거절 대신 개발 서비스를 중단한다`() {
        listOf(false, true).forEach { failDuringWrite ->
            val fixture = fixture()
            sendBytes(fixture, commands.encode(command()), null)
            val order = admissionCommand().let { it.copy(order = it.order.copy(userId = UserId("U1"))) }
            if (failDuringWrite) failOnWrite(fixture.state, "records", IllegalStateException("주문 저장 실패"))
            val bad = if (failDuringWrite) order else order.copy(ledgerTransactionId = "")

            val fatal = assertFailsWith<AgentTerminationException> { sendBytes(fixture, AdmissionCommandCodec().encode(bad)) }

            assertSame(fatal.cause, fixture.failures.single())
            assertFalse(fixture.service.started)
            assertEquals(failDuringWrite, fixture.state.isBlocked)
            assertNull(fixture.service.observation!!.snapshot)
            assertNull(fixture.service.observation!!.admissionResult)
            assertTrue(fixture.replies.isEmpty())
            assertFailsWith<AgentTerminationException> { sendBytes(fixture, AdmissionCommandCodec().encode(order)) }
            if (failDuringWrite) assertFailsWith<IllegalStateException> { fixture.state.hasRequest(order.order.userId, order.requestId) }
        }
    }

    @Test
    fun `null 세션과 follower도 같은 업무 상태를 반영하고 응답은 보내지 않는다`() {
        listOf(Cluster.Role.LEADER to false, Cluster.Role.FOLLOWER to true).forEach { (role, hasSession) ->
            val fixture = fixture(role = role)

            sendBytes(fixture, commands.encode(command()), if (hasSession) fixture.session else null)

            assertEquals(
                Amount(1_000_000_000),
                fixture.service
                    .captureObservation()
                    .join()!!
                    .snapshot!!
                    .balances
                    .single()
                    .available,
            )
            assertIs<DevelopmentBalanceResultPayload.Prepared>(fixture.service.observation!!.result)
            assertEquals(0, fixture.service.observation!!.replyAttempts)
            assertEquals(if (hasSession) "NOT_LEADER" else "NO_SESSION", fixture.service.observation!!.replyStatus)
            verifyNoInteractions(fixture.session)
        }
    }

    @Test
    fun `역압력과 admin action은 같은 바이트만 재시도하고 업무를 다시 호출하지 않는다`() {
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 99L))

        send(fixture, command())

        assertEquals(3, fixture.replies.size)
        fixture.replies.forEach { assertContentEquals(fixture.replies.first(), it) }
        fixture.replyBuffers.forEach { assertSame(fixture.replyBuffers.first(), it) }
        verify(fixture.state, times(1)).prepareDevelopmentBalance(command().opening, "L1", time)
        verify(fixture.idle, times(2)).idle()
        assertEquals("OFFERED", fixture.service.observation!!.replyStatus)
        assertEquals(3, fixture.service.observation!!.replyAttempts)
        assertFalse(assertIs<DevelopmentBalanceResultPayload.Prepared>(fixture.service.observation!!.result).result.alreadyPrepared)
    }

    @Test
    fun `역압력 대기 중 follower가 되면 추가 offer 없이 이미 적용한 자금과 원장을 보존한다`() {
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, 99L))
        doAnswer {
            `when`(fixture.cluster.role()).thenReturn(Cluster.Role.FOLLOWER)
            null
        }.`when`(fixture.idle).idle()

        send(fixture, command())

        assertEquals(1, fixture.replies.size)
        assertEquals(1, fixture.service.observation!!.replyAttempts)
        assertEquals("NOT_LEADER", fixture.service.observation!!.replyStatus)
        verify(fixture.idle, times(1)).idle()
        assertFundingPreserved(fixture)
    }

    @Test
    fun `역압력 대기 중 세션이 닫히면 추가 offer 없이 이미 적용한 자금과 원장을 보존한다`() {
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, 99L))
        doAnswer {
            `when`(fixture.session.isClosing).thenReturn(true)
            null
        }.`when`(fixture.idle).idle()

        send(fixture, command())

        assertEquals(1, fixture.replies.size)
        assertEquals(1, fixture.service.observation!!.replyAttempts)
        assertEquals("SESSION_CLOSING", fixture.service.observation!!.replyStatus)
        verify(fixture.idle, times(1)).idle()
        assertFundingPreserved(fixture)
    }

    @Test
    fun `재시도 기한이 지나면 남은 횟수와 전송 가능 여부에 관계없이 추가 offer를 멈춘다`() {
        val timeoutNs = 10_000_000L
        val fixture = fixture(offers = listOf(Publication.BACK_PRESSURED, 99L), maxReplyAttempts = 100, replyTimeoutNs = timeoutNs)
        doAnswer {
            // 단조 시각으로 기한을 실제로 소모한다. 기한이 없는 구현은 두 번째 offer에서 성공하므로 구분된다.
            val startedNs = System.nanoTime()
            do {
                LockSupport.parkNanos(timeoutNs)
            } while (System.nanoTime() - startedNs < timeoutNs)
            null
        }.`when`(fixture.idle).idle()

        send(fixture, command())

        assertEquals(1, fixture.replies.size)
        assertEquals(1, fixture.service.observation!!.replyAttempts)
        assertEquals("DEADLINE_EXCEEDED", fixture.service.observation!!.replyStatus)
        assertFundingPreserved(fixture)
    }

    @Test
    fun `전송 상한과 종단 반환값은 적용을 되돌리거나 무한 재시도하지 않는다`() {
        val cases =
            listOf(
                Publication.BACK_PRESSURED to (3 to "ATTEMPTS_EXHAUSTED"),
                Publication.NOT_CONNECTED to (1 to "NOT_CONNECTED"),
                Publication.CLOSED to (1 to "CLOSED"),
                Publication.MAX_POSITION_EXCEEDED to (1 to "MAX_POSITION_EXCEEDED"),
            )
        cases.forEach { (offer, expected) ->
            val fixture = fixture(offers = listOf(offer), maxReplyAttempts = 3)

            send(fixture, command())

            assertEquals(expected.first, fixture.replies.size)
            assertEquals(expected.second, fixture.service.observation!!.replyStatus)
            assertEquals(
                1,
                fixture.service
                    .captureObservation()
                    .join()!!
                    .snapshot!!
                    .preparations.size,
            )
            verify(fixture.state, times(1)).prepareDevelopmentBalance(command().opening, "L1", time)
            assertTrue(fixture.service.started)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun `닫는 세션은 전송하지 않고 background 작업도 전송하지 않는다`() {
        val fixture = fixture()
        `when`(fixture.session.isClosing).thenReturn(true)

        send(fixture, command())
        repeat(3) { assertEquals(0, fixture.service.doBackgroundWork(it.toLong())) }

        assertTrue(fixture.replies.isEmpty())
        assertEquals("SESSION_CLOSING", fixture.service.observation!!.replyStatus)
        assertEquals(
            1,
            fixture.service
                .captureObservation()
                .join()!!
                .snapshot!!
                .preparations.size,
        )
    }

    @Test
    fun `반영 전 코어 오류도 소유자에게 알리고 후속 callback을 중단한다`() {
        val fixture = fixture()

        val fatal = assertFailsWith<AgentTerminationException> { send(fixture, command().copy(ledgerTransactionId = "")) }

        assertIs<IllegalArgumentException>(fatal.cause)
        assertSame(fatal.cause, fixture.failures.single())
        assertFalse(fixture.service.started)
        assertFalse(fixture.state.isBlocked)
        assertTrue(
            fixture.state
                .snapshot()
                .balances
                .isEmpty(),
        )
        assertNull(fixture.service.observation!!.snapshot)
        assertNotNull(fixture.service.observation!!.failure)
        assertFailsWith<AgentTerminationException> { send(fixture, command()) }
        assertEquals(1, fixture.failures.size)
        assertTrue(fixture.replies.isEmpty())
    }

    @Test
    fun `반영 중 Exception과 Error는 blocked 상태의 정상 snapshot을 읽지 않고 중단한다`() {
        listOf(IllegalStateException("쓰기 실패"), AssertionError("쓰기 Error")).forEach { failure ->
            val fixture = fixture()
            failOnPreparationWrite(fixture.state, failure)

            val fatal = assertFailsWith<AgentTerminationException> { send(fixture, command()) }

            assertSame(failure, fatal.cause)
            assertSame(failure, fixture.failures.single())
            assertTrue(fixture.state.isBlocked)
            assertTrue(fixture.service.observation!!.blocked)
            assertNull(fixture.service.observation!!.snapshot)
            assertNull(fixture.service.observation!!.result)
            assertFailsWith<IllegalStateException> { fixture.state.snapshot() }
            assertFailsWith<AgentTerminationException> { send(fixture, command("P2", "U2", "L2")) }
            assertTrue(fixture.replies.isEmpty())
        }
    }

    @Test
    fun `통제된 코어 반환의 결합 오류와 인코딩 오류도 정상 거절로 숨기지 않는다`() {
        listOf(
            OpeningBalanceResult(command("OTHER").opening, "L1", time, false),
            OpeningBalanceResult(command().opening, "x".repeat(257), time, true),
        ).forEach { invalid ->
            val fixture = fixture()
            doReturn(invalid).`when`(fixture.state).prepareDevelopmentBalance(command().opening, "L1", time)

            val fatal = assertFailsWith<AgentTerminationException> { send(fixture, command()) }

            assertSame(fatal.cause, fixture.failures.single())
            assertFalse(fixture.service.started)
            assertNull(fixture.service.observation!!.result)
            assertTrue(fixture.replies.isEmpty())
        }
    }

    @Test
    fun `복구 image와 snapshot 요청은 성공 callback으로 반환하지 않는다`() {
        val restoring = fixture(start = false)
        val restored = assertFailsWith<AgentTerminationException> { restoring.service.onStart(restoring.cluster, mock(Image::class.java)) }
        assertIs<UnsupportedOperationException>(restored.cause)
        assertFalse(restoring.service.started)
        assertEquals(1, restoring.failures.size)

        val running = fixture()
        send(running, command())
        val repliesBefore = running.replies.size
        val snapshot =
            assertFailsWith<AgentTerminationException> {
                running.service.onTakeSnapshot(mock(ExclusivePublication::class.java))
            }
        assertIs<UnsupportedOperationException>(snapshot.cause)
        assertFalse(running.service.started)
        assertFailsWith<AgentTerminationException> { send(running, command("P2", "U2", "L2")) }
        assertEquals(repliesBefore, running.replies.size)
    }

    @Test
    fun `종료 알림 자체의 오류는 원래 fatal 원인을 가리지 않는다`() {
        val notificationFailure = IllegalStateException("종료 알림 오류")
        val service =
            DevelopmentFundingAdapter(tradingState(), onFatal = { throw notificationFailure })
        val cluster = mock(Cluster::class.java)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        service.onStart(cluster, null)

        val fatal = assertFailsWith<AgentTerminationException> { service.onTakeSnapshot(mock(ExclusivePublication::class.java)) }

        assertIs<UnsupportedOperationException>(fatal.cause)
        assertTrue(fatal.cause!!.suppressed.contains(notificationFailure))
        assertFalse(service.started)
    }

    private fun command(
        preparationId: String = "P1",
        userId: String = "U1",
        ledgerId: String = "L1",
    ) = DevelopmentBalanceCommandPayload(
        OpeningBalance(preparationId, UserId(userId), AssetId("USDT"), Amount(1_000_000_000)),
        ledgerId,
        time,
    )

    private fun send(
        fixture: Fixture,
        command: DevelopmentBalanceCommandPayload,
    ) = sendBytes(fixture, commands.encode(command))

    private fun sendBytes(
        fixture: Fixture,
        bytes: ByteArray,
        session: ClientSession? = fixture.session,
    ) = fixture.service.onSessionMessage(
        session,
        Long.MAX_VALUE,
        clusterFrame(bytes),
        AeronCluster.SESSION_HEADER_LENGTH,
        bytes.size,
        header,
    )

    /** Mockito의 null matcher 반환값을 Kotlin의 비-null 인자 검사 전에 대체한다. */
    private fun anyMutableDirectBuffer(): MutableDirectBuffer = any(MutableDirectBuffer::class.java) ?: UnsafeBuffer(ByteArray(0))

    private fun fixture(
        role: Cluster.Role = Cluster.Role.LEADER,
        offers: List<Long> = listOf(1L),
        maxReplyAttempts: Int = 5,
        replyTimeoutNs: Long = 1_000_000_000,
        start: Boolean = true,
        executionOffers: List<Long> = listOf(1L),
        maxExecutionOfferAttempts: Int = 1_000,
    ): Fixture {
        val state = spy(tradingState())
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        val idle = mock(IdleStrategy::class.java)
        val replies = mutableListOf<ByteArray>()
        val replyBuffers = mutableListOf<DirectBuffer>()
        val failures = mutableListOf<Throwable>()
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        val executionClaims = ClusterExecutionClaimStub(cluster, executionOffers)
        `when`(cluster.role()).thenReturn(role)
        `when`(cluster.logPosition()).thenReturn(256L)
        `when`(cluster.idleStrategy()).thenReturn(idle)
        doAnswer { invocation ->
            val buffer = invocation.getArgument<DirectBuffer>(0)
            val bytes = ByteArray(invocation.getArgument<Int>(2))
            buffer.getBytes(invocation.getArgument<Int>(1), bytes)
            replies += bytes
            replyBuffers += buffer
            offers[(replies.size - 1).coerceAtMost(offers.lastIndex)]
        }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        val service =
            DevelopmentFundingAdapter(
                state,
                maxReplyAttempts,
                replyTimeoutNs,
                maxExecutionOfferAttempts = maxExecutionOfferAttempts,
                onFatal = { failures += it },
            )
        if (start) service.onStart(cluster, null)
        return Fixture(state, cluster, session, idle, service, replies, replyBuffers, failures, executionClaims)
    }

    private fun assertFundingPreserved(fixture: Fixture) {
        val observation = assertNotNull(fixture.service.captureObservation().join())
        val snapshot = assertNotNull(observation.snapshot)
        assertEquals(Amount(1_000_000_000), snapshot.balances.single().available)
        assertEquals(Amount.ZERO, snapshot.balances.single().hold)
        assertEquals(1, snapshot.preparations.size)
        val preparation = snapshot.preparations.single()
        assertEquals(OpeningBalanceResult(command().opening, "L1", time, false), preparation.result)
        assertEquals("L1", preparation.transaction.ledgerTransactionId)
        assertEquals(time, preparation.transaction.occurredAt)
        assertEquals(2, preparation.transaction.postings.size)
        verify(fixture.state, times(1)).prepareDevelopmentBalance(command().opening, "L1", time)
        assertTrue(fixture.service.started)
        assertTrue(fixture.failures.isEmpty())
    }

    private data class Fixture(
        val state: TradingState,
        val cluster: Cluster,
        val session: ClientSession,
        val idle: IdleStrategy,
        val service: DevelopmentFundingAdapter,
        val replies: MutableList<ByteArray>,
        val replyBuffers: MutableList<DirectBuffer>,
        val failures: MutableList<Throwable>,
        val executionClaims: ClusterExecutionClaimStub,
    )

    /** 기존 코어 오류 검증처럼 실제 쓰기 한 지점만 실패시킨다. */
    private fun failOnPreparationWrite(
        state: TradingState,
        failure: Throwable,
    ) = failOnWrite(state, "preparations", failure)

    private fun failOnWrite(
        state: TradingState,
        fieldName: String,
        failure: Throwable,
    ) {
        val admission =
            TradingState::class.java
                .getDeclaredField("admission")
                .apply { isAccessible = true }
                .get(state)
        val field = OrderAdmissionState::class.java.getDeclaredField(fieldName).apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val original = field.get(admission) as MutableMap<Any, Any>
        field.set(
            admission,
            object : MutableMap<Any, Any> by original {
                override fun put(
                    key: Any,
                    value: Any,
                ): Any? = throw failure
            },
        )
    }
}

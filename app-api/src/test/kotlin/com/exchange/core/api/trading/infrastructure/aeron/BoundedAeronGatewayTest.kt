package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.OrderQueryRecoveryStatus
import com.exchange.core.api.trading.application.OrderQueryRecoveryVerdict
import com.exchange.core.api.trading.infrastructure.codec.BoundedCommandEnvelope
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedAeronGatewayTest {
    @TempDir lateinit var directory: Path
    private val token = RequestToken(UserId("U1"), 0, 1, 1)
    private val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(100_000_000))

    @Test
    fun `HTTP edge preserves caller token and correlates responses without creating new tokens`() {
        val codec = BoundedTradingCodec()
        val seen = ConcurrentLinkedQueue<BoundedCommandEnvelope>()
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707")
        BoundedAeronGateway(settings) { receive ->
            object : BoundedGatewayTransport {
                private val responses = ArrayDeque<BoundedTradingReply>()

                override fun offer(bytes: ByteArray): Long {
                    val request = codec.decode(bytes)
                    seen.add(request)
                    responses.addLast(
                        BoundedTradingReply(
                            request.correlationId,
                            "ACCEPTED",
                            640,
                            AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2example"), 4, 2),
                        ),
                    )
                    return 1
                }

                override fun poll(): Int {
                    val response = responses.removeFirstOrNull() ?: return 0
                    receive(response)
                    return 1
                }

                override fun close() = Unit
            }
        }.use { gateway ->
            val result = gateway.submit(BoundedTradingCommand.Admit(token, intent)).get(3, TimeUnit.SECONDS)
            assertEquals("ACCEPTED", result.code)
            assertEquals(BoundedTradingCommand.Admit(token, intent), seen.single().command)
            assertFailsWith<IllegalArgumentException> { gateway.submit(BoundedTradingCommand.Initialize(byteArrayOf(1))) }
        }
    }

    @Test
    fun `transport timeout reports an unknown outcome instead of rejection or automatic resubmission`() {
        val seen =
            java.util.concurrent.atomic
                .AtomicInteger()
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707", resultTimeoutMs = 200)
        BoundedAeronGateway(settings) {
            object : BoundedGatewayTransport {
                override fun offer(bytes: ByteArray): Long {
                    seen.incrementAndGet()
                    return 1
                }

                override fun poll() = 0

                override fun close() = Unit
            }
        }.use { gateway ->
            val failure = assertFails { gateway.submit(BoundedTradingCommand.Admit(token, intent)).get(3, TimeUnit.SECONDS) }
            val cause = (failure as java.util.concurrent.ExecutionException).cause
            assertTrue(assertIs<BoundedGatewayException>(cause).possiblySubmitted)
            assertEquals(1, seen.get())
        }
    }

    @Test
    fun `전송 역압을 받은 주문은 복구 차단 이후 재시도해도 전송되지 않는다`() {
        val gate = HttpOrderRecoveryGate()
        gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY), fundsLeadershipTermId = 7)
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707")
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            ControlledOrderTransport(receive, leadershipTermId = 7).also { transport = it }
        }.use { gateway ->
            transport.backPressured = true
            val command = BoundedTradingCommand.Admit(token, intent)
            val pending = gateway.submit(command)
            assertTrue(transport.firstBackPressure.await(3, TimeUnit.SECONDS))

            gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING))
            transport.backPressured = false
            transport.allowReply.countDown()

            val failure = assertFailsWith<ExecutionException> { pending.get(3, TimeUnit.SECONDS) }
            val rejected = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RECOVERY_IN_PROGRESS", rejected.code)
            assertFalse(rejected.possiblySubmitted)
            assertTrue(transport.offered.isEmpty())
        }
    }

    @Test
    fun `이미 전송한 주문의 응답이 없으면 복구가 닫혀도 결과 미확인으로 남는다`() {
        val gate = HttpOrderRecoveryGate()
        gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY), fundsLeadershipTermId = 7)
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707", resultTimeoutMs = 1_500)
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            ControlledOrderTransport(receive, leadershipTermId = 7).also { transport = it }
        }.use { gateway ->
            val command = BoundedTradingCommand.Admit(token, intent)
            val pending = gateway.submit(command)
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))
            gate.close()
            assertFalse(pending.isDone)

            val failure = assertFailsWith<ExecutionException> { pending.get(3, TimeUnit.SECONDS) }
            val unknown = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RESULT_UNCONFIRMED", unknown.code)
            assertTrue(unknown.possiblySubmitted)
            assertEquals(command, transport.offered.single())
        }
    }

    @Test
    fun `복구를 다시 열어도 이전 판정으로 큐에 들어간 주문은 자동 전송하지 않는다`() {
        val gate = HttpOrderRecoveryGate()
        val ready = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY)
        gate.publish(ready, fundsLeadershipTermId = 7)
        val settings =
            AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707", maxInFlight = 1)
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            ControlledOrderTransport(receive, leadershipTermId = 7).also { transport = it }
        }.use { gateway ->
            val firstCommand = BoundedTradingCommand.Admit(token, intent)
            val nextCommand = BoundedTradingCommand.Admit(token.copy(sequence = 2), intent)
            val alreadyOffered = gateway.submit(firstCommand)
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))

            val queued = gateway.submit(nextCommand)
            assertFalse(queued.isDone)
            gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING))
            gate.publish(ready, fundsLeadershipTermId = 7)
            transport.allowReply.countDown()

            assertEquals("ACCEPTED", alreadyOffered.get(3, TimeUnit.SECONDS).code)
            val failure = assertFailsWith<ExecutionException> { queued.get(3, TimeUnit.SECONDS) }
            val rejected = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RECOVERY_IN_PROGRESS", rejected.code)
            assertFalse(rejected.possiblySubmitted)
            assertEquals(listOf(firstCommand), transport.offered.toList())

            assertEquals("ACCEPTED", gateway.submit(nextCommand).get(3, TimeUnit.SECONDS).code)
            assertEquals(listOf(firstCommand, nextCommand), transport.offered.toList())
        }
    }

    @Test
    fun `송신 연결의 새 임기는 해당 임기의 복구 판정이 나올 때까지 주문을 막는다`() {
        val gate = HttpOrderRecoveryGate()
        val ready = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY)
        gate.publish(ready, fundsLeadershipTermId = 7)
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707")
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            ControlledOrderTransport(receive, leadershipTermId = 8).also { transport = it }
        }.use { gateway ->
            val command = BoundedTradingCommand.Admit(token, intent)
            val failure = assertFailsWith<ExecutionException> { gateway.submit(command).get(3, TimeUnit.SECONDS) }
            val rejected = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RECOVERY_IN_PROGRESS", rejected.code)
            assertFalse(rejected.possiblySubmitted)
            assertTrue(transport.offered.isEmpty())

            gate.publish(ready, fundsLeadershipTermId = 8)
            transport.allowReply.countDown()
            assertEquals("ACCEPTED", gateway.submit(command).get(3, TimeUnit.SECONDS).code)
            assertEquals(command, transport.offered.single())
        }
    }

    @Test
    fun `큐가 가득 차도 새 송신 임기에 대한 복구 판정을 먼저 확인한다`() {
        val gate = HttpOrderRecoveryGate()
        gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY), fundsLeadershipTermId = 7)
        val settings =
            AeronGatewaySettings(
                directory.resolve("gateway"),
                "0=127.0.0.1:19700",
                "127.0.0.1:19707",
                queueCapacity = 1,
                maxInFlight = 1,
                resultTimeoutMs = 10_000,
            )
        val pollsInNewTerm = CountDownLatch(2)
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            val connection =
                ControlledOrderTransport(receive, leadershipTermId = 7) { term ->
                    if (term == 8L) pollsInNewTerm.countDown()
                }
            transport = connection
            connection
        }.use { gateway ->
            val firstCommand = BoundedTradingCommand.Admit(token, intent)
            val queuedCommand = BoundedTradingCommand.Admit(token.copy(sequence = 2), intent)
            val newCommand = BoundedTradingCommand.Admit(token.copy(sequence = 3), intent)
            gateway.submit(firstCommand)
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))
            val queued = gateway.submit(queuedCommand)
            assertFalse(queued.isDone)

            val full = assertFailsWith<ExecutionException> { gateway.submit(newCommand).get(3, TimeUnit.SECONDS) }
            assertEquals("QUEUE_FULL", assertIs<BoundedGatewayException>(full.cause).code)

            transport.leadershipTermId = 8
            // 두 번째 poll은 송신 스레드가 새 임기로 한 번 이상 순회한 뒤에야 실행된다.
            assertTrue(pollsInNewTerm.await(3, TimeUnit.SECONDS))
            val failure = assertFailsWith<ExecutionException> { gateway.submit(newCommand).get(3, TimeUnit.SECONDS) }
            val rejected = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RECOVERY_IN_PROGRESS", rejected.code)
            assertFalse(rejected.possiblySubmitted)
            assertEquals(listOf(firstCommand), transport.offered.toList())
        }
    }

    @Test
    fun `전송 도중 연결이 닫히면 다음 폴링 전에도 복구 상태로 새 주문을 막는다`() {
        val gate = HttpOrderRecoveryGate()
        gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY), fundsLeadershipTermId = 7)
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707", queueCapacity = 1)
        val pollAfterDisconnect = CountDownLatch(1)
        val continuePolling = CountDownLatch(1)
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            val connection =
                ControlledOrderTransport(receive, leadershipTermId = 7) { term ->
                    if (term == null) {
                        pollAfterDisconnect.countDown()
                        check(continuePolling.await(5, TimeUnit.SECONDS))
                    }
                }
            transport = connection
            connection
        }.use { gateway ->
            try {
                transport.disconnectOnOffer = true
                val first = gateway.submit(BoundedTradingCommand.Admit(token, intent))
                assertTrue(pollAfterDisconnect.await(3, TimeUnit.SECONDS))
                assertFalse(first.isDone)

                val nextCommand = BoundedTradingCommand.Admit(token.copy(sequence = 2), intent)
                val failure = assertFailsWith<ExecutionException> { gateway.submit(nextCommand).get(3, TimeUnit.SECONDS) }
                val rejected = assertIs<BoundedGatewayException>(failure.cause)
                assertEquals("RECOVERY_IN_PROGRESS", rejected.code)
                assertFalse(rejected.possiblySubmitted)
                assertTrue(transport.offered.isEmpty())
            } finally {
                continuePolling.countDown()
            }
        }
    }

    @Test
    fun `remote profile does not publish a quorum response before durable recovery coverage`() {
        val lineage = ClusterLineage(1, 2)
        val protected = CompletableFuture<Unit>()
        val gateCalled = CountDownLatch(1)
        val gate =
            object : RecoveryDurabilityGate {
                override fun awaitProtected(
                    actual: ClusterLineage,
                    position: Long,
                ): java.util.concurrent.CompletionStage<Unit> {
                    assertEquals(lineage, actual)
                    assertEquals(640L, position)
                    gateCalled.countDown()
                    return protected
                }
            }
        val codec = BoundedTradingCodec()
        val settings = AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707")
        BoundedAeronGateway(settings, lineage, gate) { receive ->
            object : BoundedGatewayTransport {
                private var response: BoundedTradingReply? = null

                override fun offer(bytes: ByteArray): Long {
                    val request = codec.decode(bytes)
                    response =
                        BoundedTradingReply(request.correlationId, "ISSUED", 640, epoch = 1, lineage = lineage)
                    return 1
                }

                override fun poll(): Int {
                    val r = response ?: return 0
                    response = null
                    receive(r)
                    return 1
                }

                override fun close() = Unit
            }
        }.use { gateway ->
            val result = gateway.submit(BoundedTradingCommand.Rotate(token.userId, 0, 0))
            assertTrue(gateCalled.await(3, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            protected.complete(Unit)
            assertEquals("ISSUED", result.get(3, TimeUnit.SECONDS).code)
        }
    }

    @Test
    fun `oversized UTF8 acknowledgement and epoch input fail only their pending requests`() {
        val codec = BoundedTradingCodec()
        val offered = ConcurrentLinkedQueue<BoundedCommandEnvelope>()
        val settings =
            AeronGatewaySettings(directory.resolve("gateway"), "0=127.0.0.1:19700", "127.0.0.1:19707", queueCapacity = 1, maxInFlight = 1)
        BoundedAeronGateway(settings) { receive ->
            object : BoundedGatewayTransport {
                private val responses = ArrayDeque<BoundedTradingReply>()

                override fun offer(bytes: ByteArray): Long {
                    val request = codec.decode(bytes)
                    offered.add(request)
                    responses.addLast(BoundedTradingReply(request.correlationId, "ACKNOWLEDGED", 640, epoch = 1, retiredThrough = 0))
                    return 1
                }

                override fun poll(): Int {
                    val response = responses.removeFirstOrNull() ?: return 0
                    receive(response)
                    return 1
                }

                override fun close() = Unit
            }
        }.use { gateway ->
            val invalidUser = UserId("가".repeat(86))
            val malformed = listOf(BoundedTradingCommand.Acknowledge(invalidUser, 0, 1, 0), BoundedTradingCommand.Rotate(invalidUser, 0, 0))
            for (command in malformed) {
                val failure = assertFails { gateway.submit(command).get(3, TimeUnit.SECONDS) }
                val rejected = assertIs<BoundedGatewayException>((failure as java.util.concurrent.ExecutionException).cause)
                assertEquals("INVALID_REQUEST", rejected.code)
                assertFalse(rejected.possiblySubmitted)
            }
            assertTrue(offered.isEmpty())
            val valid = BoundedTradingCommand.Acknowledge(token.userId, 0, 1, 0)
            assertEquals("ACKNOWLEDGED", gateway.submit(valid).get(3, TimeUnit.SECONDS).code)
            assertEquals(valid, offered.single().command)
        }
    }

    @Test
    fun `공개 응답의 동기 콜백에서 종료해도 정상 반환하고 전송 소유자가 끝난다`() {
        lateinit var transport: ControlledAcknowledgementTransport
        val settings =
            AeronGatewaySettings(
                directory.resolve("gateway"),
                "0=127.0.0.1:19700",
                "127.0.0.1:19707",
                startupTimeoutMs = 100,
            )
        val gateway =
            BoundedAeronGateway(settings) { receive ->
                ControlledAcknowledgementTransport(receive).also { transport = it }
            }
        val callbackThread = AtomicReference<Thread>()
        val callbackReturned = CountDownLatch(1)
        val result = gateway.acknowledge(token.userId.value, 0, 1, 0)
        // 응답을 풀기 전에 등록해 동기 콜백이 실제 완료 작업 안에서 실행되게 한다.
        val callback =
            result.thenRun {
                callbackThread.set(Thread.currentThread())
                gateway.close()
                callbackReturned.countDown()
            }

        try {
            assertTrue(transport.offered.await(3, TimeUnit.SECONDS))
            transport.replyAllowed.countDown()

            callback.get(3, TimeUnit.SECONDS)
            assertEquals(0L, callbackReturned.count, "콜백 내부의 종료 호출이 정상 반환해야 한다")
            assertEquals("ACKNOWLEDGED", result.get(3, TimeUnit.SECONDS).code)
            assertTrue(transport.closed.await(3, TimeUnit.SECONDS), "전송 자원을 닫아야 한다")
            val owner = checkNotNull(transport.owner.get())
            owner.join(3_000)
            assertFalse(owner.isAlive, "전송 소유 스레드가 종료돼야 한다")
            val completion = checkNotNull(callbackThread.get())
            completion.join(3_000)
            assertFalse(completion.isAlive, "종료를 요청한 완료 작업도 끝나야 한다")

            val rejected = gateway.acknowledge(token.userId.value, 0, 1, 0).get(3, TimeUnit.SECONDS)
            assertEquals("NOT_SUBMITTED", rejected.code)
            assertEquals("GATEWAY_UNAVAILABLE", rejected.reason)
            assertFalse(rejected.possiblySubmitted)
            assertEquals(1, transport.offerCount.get(), "종료 뒤 요청을 전송하면 안 된다")
        } finally {
            transport.replyAllowed.countDown()
            // 회귀 구현이 자기 완료를 영구 대기해도 테스트 실행 스레드까지 묶이지 않는다.
            val cleanup = Thread.ofVirtual().start { runCatching { gateway.close() } }
            cleanup.join(3_000)
            callbackThread.get()?.interrupt()
            if (cleanup.isAlive) cleanup.interrupt()
        }
    }

    @Test
    fun `완료 콜백이 인터럽트를 무시해도 외부 종료는 제한 시간 안에 실패를 알린다`() {
        lateinit var transport: ControlledAcknowledgementTransport
        val settings =
            AeronGatewaySettings(
                directory.resolve("gateway"),
                "0=127.0.0.1:19700",
                "127.0.0.1:19707",
                startupTimeoutMs = 100,
            )
        val gateway =
            BoundedAeronGateway(settings) { receive ->
                ControlledAcknowledgementTransport(receive).also { transport = it }
            }
        val callbackEntered = CountDownLatch(1)
        val callbackRelease = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val result = gateway.acknowledge(token.userId.value, 0, 1, 0)
        val callback =
            result.thenRun {
                callbackThread.set(Thread.currentThread())
                callbackEntered.countDown()
                // shutdownNow만으로 빠져나오지 않게 해 종료 대기 기한 자체를 검증한다.
                var interrupted = false
                try {
                    while (true) {
                        try {
                            callbackRelease.await()
                            break
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        val closeResult = CompletableFuture<Throwable?>()
        var closingThread: Thread? = null

        try {
            assertTrue(transport.offered.await(3, TimeUnit.SECONDS))
            transport.replyAllowed.countDown()
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS))
            closingThread =
                Thread.ofVirtual().start {
                    closeResult.complete(runCatching { gateway.close() }.exceptionOrNull())
                }

            val failure = assertIs<IllegalStateException>(closeResult.get(3, TimeUnit.SECONDS))
            val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(
                messages.any { message ->
                    message.contains("callback", ignoreCase = true) &&
                        (
                            message.contains("초과") || message.contains("timed out", ignoreCase = true) ||
                                message.contains("timeout", ignoreCase = true)
                        )
                },
                "완료 콜백 종료 기한이 초과됐다는 원인을 알려야 한다: $messages",
            )
            assertEquals(1L, callbackRelease.count, "콜백을 풀기 전에 종료 호출이 실패로 돌아와야 한다")
            assertTrue(transport.closed.await(3, TimeUnit.SECONDS))
        } finally {
            transport.replyAllowed.countDown()
            callbackRelease.countDown()
            val cleanup = closingThread ?: Thread.ofVirtual().start { runCatching { gateway.close() } }
            cleanup.join(3_000)
            val owner = transport.owner.get()
            owner?.join(3_000)
            val completion = callbackThread.get()
            completion?.join(3_000)
            assertFalse(cleanup.isAlive, "콜백을 해제하면 외부 종료 작업도 정리돼야 한다")
            assertFalse(owner?.isAlive == true, "전송 소유 스레드가 남으면 안 된다")
            assertFalse(completion?.isAlive == true, "완료 작업이 남으면 안 된다")
            if (callbackEntered.count == 0L) callback.get(3, TimeUnit.SECONDS)
        }
    }

    private class ControlledOrderTransport(
        private val receive: (BoundedTradingReply) -> Unit,
        leadershipTermId: Long,
        private val onPoll: (Long?) -> Unit = {},
    ) : BoundedGatewayTransport {
        @Volatile
        override var leadershipTermId: Long? = leadershipTermId

        @Volatile
        var backPressured = false

        @Volatile
        var disconnectOnOffer = false

        val offered = ConcurrentLinkedQueue<BoundedTradingCommand>()
        val firstOffer = CountDownLatch(1)
        val firstBackPressure = CountDownLatch(1)
        val allowReply = CountDownLatch(1)
        private val codec = BoundedTradingCodec()
        private val replies = ArrayDeque<BoundedTradingReply>()

        override fun offer(bytes: ByteArray): Long {
            if (disconnectOnOffer) {
                leadershipTermId = null
                return Publication.NOT_CONNECTED
            }
            if (backPressured) {
                firstBackPressure.countDown()
                return Publication.BACK_PRESSURED
            }
            val request = codec.decode(bytes)
            offered.add(request.command)
            replies.addLast(
                BoundedTradingReply(
                    request.correlationId,
                    "ACCEPTED",
                    640,
                    AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2example"), 4, 2),
                ),
            )
            firstOffer.countDown()
            return 1
        }

        override fun poll(): Int {
            onPoll(leadershipTermId)
            if (allowReply.count != 0L) return 0
            val reply = replies.removeFirstOrNull() ?: return 0
            receive(reply)
            return 1
        }

        override fun close() = Unit
    }

    private class ControlledAcknowledgementTransport(
        private val receive: (BoundedTradingReply) -> Unit,
    ) : BoundedGatewayTransport {
        val offered = CountDownLatch(1)
        val replyAllowed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val owner = AtomicReference<Thread>()
        val offerCount = AtomicInteger()
        private val codec = BoundedTradingCodec()
        private var reply: BoundedTradingReply? = null

        override fun offer(bytes: ByteArray): Long {
            owner.set(Thread.currentThread())
            offerCount.incrementAndGet()
            val request = codec.decode(bytes)
            reply = BoundedTradingReply(request.correlationId, "ACKNOWLEDGED", 640, epoch = 1, retiredThrough = 0)
            offered.countDown()
            return 1
        }

        override fun poll(): Int {
            if (replyAllowed.count != 0L) return 0
            val response = reply ?: return 0
            reply = null
            receive(response)
            return 1
        }

        override fun close() {
            closed.countDown()
        }
    }
}

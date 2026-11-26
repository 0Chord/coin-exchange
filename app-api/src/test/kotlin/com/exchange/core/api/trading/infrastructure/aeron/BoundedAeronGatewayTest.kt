package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.BoundedTradingCancellation
import com.exchange.core.api.trading.application.OrderQueryRecoveryStatus
import com.exchange.core.api.trading.application.OrderQueryRecoveryVerdict
import com.exchange.core.api.trading.infrastructure.codec.BoundedCommandEnvelope
import com.exchange.core.api.trading.infrastructure.codec.BoundedMarketProgress
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryEnvelope
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
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
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedAeronGatewayTest {
    @TempDir lateinit var directory: Path
    private val token = RequestToken(UserId("U1"), 0, 1, 1)
    private val intent = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(100_000_000))

    @Test
    fun `정상 종료는 먼저 받은 요청을 순서대로 처리하고 확정한 대상까지만 기다린다`() {
        lateinit var transport: ShutdownTransport
        BoundedAeronGateway(
            shutdownSettings(),
            commandEncoder = SplitFundsGatewayTransport.Companion::encodeCommand,
            cancelEncoder = SplitFundsGatewayTransport.Companion::encodeCancellation,
        ) { receive -> ShutdownTransport(receive, splitWire = true).also { transport = it } }.use { gateway ->
            val first = BoundedTradingCommand.Admit(token, intent)
            val cancellation =
                BoundedTradingCancellation(
                    userId = token.userId.value,
                    channel = token.channel,
                    epoch = token.epoch,
                    sequence = 2,
                    expectedConfigurationVersion = 1,
                    marketId = intent.marketId.value,
                    orderId = "O2example",
                )
            val firstResult = gateway.submit(first)
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))
            val cancellationResult = gateway.cancel(cancellation)
            val callback = cancellationResult.thenRun { gateway.close() }

            val stopped = gateway.beginShutdown()
            assertSame(stopped, gateway.beginShutdown())
            assertUnsent(gateway.submit(first.copy(token = token.copy(sequence = 3))))
            assertFalse(cancellationResult.isDone)
            transport.progress.set(drainProgress(issued = 2, settled = 1))
            transport.allowOrders.countDown()
            assertEquals("ACCEPTED", firstResult.get(3, TimeUnit.SECONDS).code)
            assertEquals("ACCEPTED", cancellationResult.get(3, TimeUnit.SECONDS).cancellationCode)
            callback.get(3, TimeUnit.SECONDS)
            assertTrue(transport.firstProgress.await(3, TimeUnit.SECONDS))
            // 첫 조회가 2를 종료 목표로 삼은 뒤, 후속 진척에 다른 요청이 추가돼도 기다리지 않는다.
            assertTrue(transport.secondProgress.await(3, TimeUnit.SECONDS))
            assertFalse(stopped.isDone)
            assertEquals(SplitOrderDeliveryCommand.Control(first), transport.offered.first())
            val queuedCancel = assertIs<SplitOrderDeliveryCommand.Cancel>(transport.offered.elementAt(1))
            assertEquals(cancellation.sequence, queuedCancel.token.sequence)
            assertEquals(cancellation.orderId, queuedCancel.intent.targetOrderId.value)
            transport.progress.set(drainProgress(issued = 3, settled = 2))
            assertEquals(TradingDrainOutcome.COMPLETED, stopped.get(3, TimeUnit.SECONDS))
            assertEquals(1, transport.offered.count { it is SplitOrderDeliveryCommand.Cancel })
        }
    }

    @Test
    fun `종료 시간 초과는 큐의 미전송과 전송 뒤 결과 미확인을 구분한다`() {
        lateinit var transport: ShutdownTransport
        BoundedAeronGateway(shutdownSettings(drainTimeoutMs = 100)) { receive ->
            ShutdownTransport(receive).also { transport = it }
        }.use { gateway ->
            val first = BoundedTradingCommand.Admit(token, intent)
            val sent = gateway.submit(first)
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))
            val unsent = gateway.submit(first.copy(token = token.copy(sequence = 2)))

            assertEquals(TradingDrainOutcome.TIMED_OUT, gateway.beginShutdown().get(3, TimeUnit.SECONDS))
            val failure = assertFailsWith<ExecutionException> { sent.get(3, TimeUnit.SECONDS) }
            val gatewayError = assertIs<BoundedGatewayException>(failure.cause)
            assertEquals("RESULT_UNCONFIRMED", gatewayError.code)
            assertTrue(gatewayError.possiblySubmitted)
            assertUnsent(unsent)
            assertEquals(listOf(SplitOrderDeliveryCommand.Control(first)), transport.offered.toList())
        }
    }

    @Test
    fun `종료 도중 진행 중인 poll에서 리더를 바꿔도 새 임기로 완료를 판정하지 않는다`() {
        lateinit var transport: ShutdownTransport
        BoundedAeronGateway(shutdownSettings()) { receive -> ShutdownTransport(receive).also { transport = it } }.use { gateway ->
            val pollEntered = CountDownLatch(1)
            val changeLeader = CountDownLatch(1)
            transport.progress.set(drainProgress(issued = 0, settled = 0))
            transport.beforePoll.set {
                pollEntered.countDown()
                check(changeLeader.await(3, TimeUnit.SECONDS))
                transport.leadershipTermId = 2
            }
            try {
                assertTrue(pollEntered.await(3, TimeUnit.SECONDS))
                val stopped = gateway.beginShutdown()
                changeLeader.countDown()
                assertEquals(TradingDrainOutcome.UNCONFIRMED, stopped.get(3, TimeUnit.SECONDS))
            } finally {
                changeLeader.countDown()
            }
        }
    }

    @Test
    fun `정상 응답이라도 종료 기한을 넘겨 처음 확인되면 결과 미확인으로 끝난다`() {
        lateinit var transport: ShutdownTransport
        BoundedAeronGateway(shutdownSettings(drainTimeoutMs = 100)) { receive ->
            ShutdownTransport(receive).also { transport = it }
        }.use { gateway ->
            val result = gateway.submit(BoundedTradingCommand.Admit(token, intent))
            assertTrue(transport.firstOffer.await(3, TimeUnit.SECONDS))
            val pollEntered = CountDownLatch(1)
            val allowPoll = CountDownLatch(1)
            transport.beforePoll.set {
                pollEntered.countDown()
                check(allowPoll.await(3, TimeUnit.SECONDS))
            }
            try {
                assertTrue(pollEntered.await(3, TimeUnit.SECONDS))
                val stopped = gateway.beginShutdown()
                gateway.awaitShutdown()
                assertEquals(TradingDrainOutcome.TIMED_OUT, stopped.get(3, TimeUnit.SECONDS))
                transport.allowOrders.countDown()
                allowPoll.countDown()
                val failure = assertFailsWith<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
                val error = assertIs<BoundedGatewayException>(failure.cause)
                assertEquals("RESULT_UNCONFIRMED", error.code)
                assertTrue(error.possiblySubmitted)
            } finally {
                allowPoll.countDown()
            }
        }
    }

    @Test
    fun `종료 기한을 넘긴 인코딩 작업은 주문을 전송하지 않는다`() {
        lateinit var transport: ShutdownTransport
        val encoding = CountDownLatch(1)
        val allowEncoding = CountDownLatch(1)
        val codec = BoundedTradingCodec()
        val settings = shutdownSettings(drainTimeoutMs = 100)
        BoundedAeronGateway(settings, commandEncoder = { correlation, command ->
            encoding.countDown()
            check(allowEncoding.await(3, TimeUnit.SECONDS))
            codec.encode(correlation, command)
        }) { receive -> ShutdownTransport(receive).also { transport = it } }.use { gateway ->
            try {
                val order = gateway.submit(BoundedTradingCommand.Admit(token, intent))
                assertTrue(encoding.await(3, TimeUnit.SECONDS))
                val stopped = gateway.beginShutdown()
                assertFailsWith<TimeoutException> { stopped.get(200, TimeUnit.MILLISECONDS) }
                allowEncoding.countDown()
                assertEquals(TradingDrainOutcome.TIMED_OUT, stopped.get(3, TimeUnit.SECONDS))
                assertUnsent(order)
                assertTrue(transport.offered.isEmpty())
            } finally {
                allowEncoding.countDown()
            }
        }
    }

    @Test
    fun `복구 보호의 잠금 대기 중 종료 기한이 지나도 주문을 전송하지 않는다`() {
        val gate = HttpOrderRecoveryGate()
        gate.publish(OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.READY), fundsLeadershipTermId = 1)
        val encoderThread = CompletableFuture<Thread>()
        val codec = BoundedTradingCodec()
        val settings = shutdownSettings(drainTimeoutMs = 100)
        lateinit var transport: ShutdownTransport
        BoundedAeronGateway(
            settings,
            recoveryGate = gate,
            commandEncoder = { correlation, command ->
                encoderThread.complete(Thread.currentThread())
                codec.encode(correlation, command)
            },
        ) { receive -> ShutdownTransport(receive).also { transport = it } }.use { gateway ->
            val order =
                synchronized(gate) {
                    val queued = gateway.submit(BoundedTradingCommand.Admit(token, intent))
                    val worker = encoderThread.get(3, TimeUnit.SECONDS)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (worker.state != Thread.State.BLOCKED && System.nanoTime() - deadline < 0) {
                        LockSupport.parkNanos(100_000)
                    }
                    assertEquals(Thread.State.BLOCKED, worker.state)
                    val stopped = gateway.beginShutdown()
                    gateway.awaitShutdown()
                    assertEquals(TradingDrainOutcome.TIMED_OUT, stopped.get(3, TimeUnit.SECONDS))
                    queued
                }
            assertUnsent(order)
            assertTrue(transport.offered.isEmpty())
        }
    }

    @Test
    fun `원격 보호는 사용자 접수 응답에 적용하고 내부 종료 조회는 기다리게 하지 않는다`() {
        val fundsLineage = ClusterLineage(1, 2)
        val protectedPositions = ConcurrentLinkedQueue<Long>()
        val gate =
            object : RecoveryDurabilityGate {
                override fun awaitProtected(
                    lineage: ClusterLineage,
                    position: Long,
                ): CompletableFuture<Unit> {
                    assertEquals(fundsLineage, lineage)
                    protectedPositions.add(position)
                    return if (position == 640L) CompletableFuture.completedFuture(Unit) else CompletableFuture()
                }
            }
        BoundedAeronGateway(shutdownSettings(), fundsLineage, gate) { receive ->
            ShutdownTransport(receive, fundsLineage).apply {
                allowOrders.countDown()
                progress.set(drainProgress(issued = 1, settled = 1))
            }
        }.use { gateway ->
            val order = gateway.submit(BoundedTradingCommand.Admit(token, intent))
            assertEquals("ACCEPTED", order.get(3, TimeUnit.SECONDS).code)
            assertEquals(TradingDrainOutcome.COMPLETED, gateway.beginShutdown().get(3, TimeUnit.SECONDS))
            assertEquals(listOf(640L), protectedPositions.toList())
        }
    }

    @Test
    fun `취소를 포함한 순번이 없는 진행 응답은 종료 완료의 증거가 아니다`() {
        BoundedAeronGateway(shutdownSettings()) { receive ->
            ShutdownTransport(receive).apply { progress.set(BoundedMarketProgress("BTC-USDT", 1, 1)) }
        }.use { gateway ->
            assertEquals(TradingDrainOutcome.UNCONFIRMED, gateway.beginShutdown().get(3, TimeUnit.SECONDS))
        }
    }

    private fun shutdownSettings(drainTimeoutMs: Long = 5_000): AeronGatewaySettings =
        AeronGatewaySettings(
            directory.resolve("gateway"),
            "0=127.0.0.1:19700",
            "127.0.0.1:19707",
            maxInFlight = 1,
            drainTimeoutMs = drainTimeoutMs,
        )

    private fun assertUnsent(result: CompletableFuture<BoundedTradingReply>) {
        val failure = assertFailsWith<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
        val gatewayError = assertIs<BoundedGatewayException>(failure.cause)
        assertEquals("GATEWAY_UNAVAILABLE", gatewayError.code)
        assertFalse(gatewayError.possiblySubmitted)
    }

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
        val firstRequestDequeued = CountDownLatch(1)
        val pollsInNewTerm = CountDownLatch(2)
        lateinit var transport: ControlledOrderTransport
        BoundedAeronGateway(settings, recoveryGate = gate) { receive ->
            val connection =
                ControlledOrderTransport(receive, leadershipTermId = 7) { term ->
                    if (transport.firstOffer.count == 0L) firstRequestDequeued.countDown()
                    if (term == 8L) pollsInNewTerm.countDown()
                }
            transport = connection
            connection
        }.use { gateway ->
            val firstCommand = BoundedTradingCommand.Admit(token, intent)
            val queuedCommand = BoundedTradingCommand.Admit(token.copy(sequence = 2), intent)
            val newCommand = BoundedTradingCommand.Admit(token.copy(sequence = 3), intent)
            gateway.submit(firstCommand)
            // 첫 전송이 반환된 뒤의 poll은 Gateway가 첫 요청을 큐에서 꺼낸 뒤에 실행된다.
            assertTrue(firstRequestDequeued.await(3, TimeUnit.SECONDS))
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

    private class ShutdownTransport(
        private val receive: BoundedGatewayReplies,
        private val lineage: ClusterLineage? = null,
        private val splitWire: Boolean = false,
    ) : BoundedGatewayTransport {
        @Volatile override var leadershipTermId: Long? = 1

        val offered = ConcurrentLinkedQueue<SplitOrderDeliveryCommand>()
        val firstOffer = CountDownLatch(1)
        val allowOrders = CountDownLatch(1)
        val firstProgress = CountDownLatch(1)
        val secondProgress = CountDownLatch(2)
        val progress = AtomicReference<BoundedMarketProgress>()
        val beforePoll = AtomicReference<(() -> Unit)?>(null)
        private val codec = BoundedTradingCodec()
        private val splitCodec = SplitOrderDeliveryCodec(2)
        private val progressCommand = SplitOrderDeliveryCommand.Control(BoundedTradingCommand.Progress)
        private var waiting: SplitOrderDeliveryEnvelope? = null

        override fun offer(bytes: ByteArray): Long {
            check(waiting == null)
            val request =
                if (splitWire) {
                    splitCodec.decode(bytes)
                } else {
                    val command = codec.decode(bytes)
                    SplitOrderDeliveryEnvelope(command.correlationId, SplitOrderDeliveryCommand.Control(command.command))
                }
            waiting = request
            offered.add(request.command)
            if (request.command == progressCommand) {
                firstProgress.countDown()
                secondProgress.countDown()
            } else {
                firstOffer.countDown()
            }
            return 1L
        }

        override fun poll(): Int {
            beforePoll.getAndSet(null)?.invoke()
            val request = waiting ?: return 0
            val cancellation = request.command as? SplitOrderDeliveryCommand.Cancel
            if (cancellation != null) {
                if (allowOrders.count != 0L) return 0
                val fundsLineage = lineage ?: ClusterLineage(1, 2)
                val receipt =
                    CancelReceipt(
                        code = CancelCode.ACCEPTED,
                        cancelId = TradingIdentifiers.cancel(fundsLineage, 1),
                        targetOrderId = cancellation.intent.targetOrderId,
                        inputSequence = 2,
                    )
                waiting = null
                receive.cancellation(
                    SplitOrderDeliveryReply(
                        request.correlationId,
                        "ACCEPTED",
                        TradingServiceRole.FUNDS,
                        fundsLineage,
                        "a".repeat(64),
                        640,
                        cancellation = receipt,
                    ),
                )
                return 1
            }
            val reply =
                if (request.command == progressCommand) {
                    val currentProgress = progress.get() ?: return 0
                    BoundedTradingReply(
                        request.correlationId,
                        "PROGRESS",
                        641,
                        lifecycle = TradingLifecycle.RUNNING.name,
                        markets = listOf(currentProgress),
                        lineage = lineage,
                    )
                } else {
                    if (allowOrders.count != 0L) return 0
                    BoundedTradingReply(
                        request.correlationId,
                        "ACCEPTED",
                        640,
                        AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2example"), 4, 2),
                        lineage = lineage,
                    )
                }
            waiting = null
            receive(reply)
            return 1
        }

        override fun close() = Unit
    }

    private fun drainProgress(
        issued: Long,
        settled: Long,
    ): BoundedMarketProgress = BoundedMarketProgress("BTC-USDT", 0, 0, dispatchIssued = issued, settledResultThrough = settled)
}

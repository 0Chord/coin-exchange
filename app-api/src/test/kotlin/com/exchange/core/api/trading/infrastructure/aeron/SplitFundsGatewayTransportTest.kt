package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.BoundedTradingCancellation
import com.exchange.core.api.trading.application.BoundedTradingOrderSubmission
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryEnvelope
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import com.exchange.core.api.trading.infrastructure.recovery.RecoveryDurabilityGate
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingIdentifiers
import com.exchange.core.trading.TradingServiceRole
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실제 WAS Driver의 수명과 split wire 변환을 검사한다. 자금 callback 연결은 native HTTP 검사가 담당한다. */
class SplitFundsGatewayTransportTest {
    @TempDir lateinit var directory: Path
    private val lineage = ClusterLineage(81, 82)

    @Test
    fun `자금 응답이 역순으로 와도 호출 토큰과 각 HTTP 결과를 보존한다`() {
        val factory = Factory()
        factory.afterOffer = { request ->
            if (factory.requests.size == 2) {
                val first = factory.requests.first()
                factory.replies.add(reply(first.correlationId + 100))
                factory.replies.add(reply(request.correlationId))
                factory.replies.add(reply(first.correlationId))
            }
        }
        val settings = settings("pipeline")
        gateway(settings, factory).use { gateway ->
            val first = gateway.submit(submission(7))
            val second = gateway.submit(submission(8))

            assertEquals("order-1", first.get(3, TimeUnit.SECONDS).orderId)
            assertEquals("order-2", second.get(3, TimeUnit.SECONDS).orderId)
            assertEquals(listOf(7L, 8L), factory.requests.map { assertIs<BoundedTradingCommand.Admit>(control(it)).token.sequence })
            assertEquals(listOf(1L, 2L), factory.requests.map { it.correlationId })
            assertEquals(2, factory.endpoint.inputContractVersion)
            assertEquals(SplitTradingNodeSettings.FUNDS_CLUSTER_ID, factory.endpoint.clusterId)
            assertTrue(Path.of(factory.endpoint.driverDirectory).startsWith(settings.runRoot))
            assertTrue(Files.exists(Path.of(factory.endpoint.driverDirectory)))
        }
        assertTrue(factory.attemptClosed)
        assertTrue(factory.closed)
        assertTrue(Files.list(settings.runRoot).use { it.findAny().isEmpty })
    }

    @Test
    fun `주문과 취소가 함께 대기할 때 응답 종류와 취소 대상을 확인한다`() {
        val factory = Factory()
        val target = OrderId("target")
        val cancelId = TradingIdentifiers.cancel(lineage, 1)
        factory.afterOffer = { cancellation ->
            if (factory.requests.size == 2) {
                val orderCorrelation = factory.requests.first().correlationId
                val cancelCorrelation = cancellation.correlationId
                val wrongTarget = CancelReceipt(CancelCode.ACCEPTED, TradingIdentifiers.cancel(lineage, 2), OrderId("other"), 2)
                val receipt = CancelReceipt(CancelCode.ACCEPTED, cancelId, target, 1)
                factory.replies.add(cancellationReply(orderCorrelation, wrongTarget))
                factory.replies.add(reply(cancelCorrelation))
                factory.replies.add(cancellationReply(cancelCorrelation + 100, wrongTarget.copy(targetOrderId = target)))
                factory.replies.add(cancellationReply(cancelCorrelation, wrongTarget))
                factory.replies.add(cancellationReply(cancelCorrelation, receipt))
                factory.replies.add(reply(orderCorrelation))
            }
        }
        gateway(settings("mixed-replies"), factory).use { gateway ->
            val order = gateway.submit(submission(7))
            val cancellation = gateway.cancel(cancellation(target))

            assertEquals("order-1", order.get(3, TimeUnit.SECONDS).orderId)
            val accepted = cancellation.get(3, TimeUnit.SECONDS)
            assertEquals("ACCEPTED", accepted.cancellationCode)
            assertEquals(cancelId, accepted.cancelId)
        }
        assertEquals(
            SplitOrderDeliveryCommand.Cancel(RequestToken(UserId("user"), 2, 4, 8), CancelIntent(MarketId("BTC-USDT"), target, 1)),
            factory.requests.last().command,
        )
    }

    @Test
    fun `취소 영수증이 없는 자금 거절도 해당 요청의 결과로 전달한다`() {
        val factory = Factory()
        factory.afterOffer = { request ->
            factory.replies.add(reply(request.correlationId).copy(code = "RETRYABLE_CAPACITY", control = null))
        }
        gateway(settings("cancel-refused"), factory).use { gateway ->
            val refused = gateway.cancel(cancellation(OrderId("target"))).get(3, TimeUnit.SECONDS)
            assertEquals("RETRYABLE_CAPACITY", refused.code)
            assertEquals(null, refused.failureCode)
            assertEquals(null, refused.cancelId)
        }
    }

    @Test
    fun `원격 보호 구성에서 취소 접수도 자금 로그 위치가 보호된 뒤 응답한다`() {
        val factory = Factory()
        val target = OrderId("target")
        val cancelId = TradingIdentifiers.cancel(lineage, 1)
        val receipt = CancelReceipt(CancelCode.ACCEPTED, cancelId, target, 1)
        factory.afterOffer = { request -> factory.replies.add(cancellationReply(request.correlationId, receipt)) }
        val requiredPosition = CompletableFuture<Pair<ClusterLineage, Long>>()
        val protected = CompletableFuture<Unit>()
        val gate =
            object : RecoveryDurabilityGate {
                override fun awaitProtected(
                    lineage: ClusterLineage,
                    position: Long,
                ): CompletableFuture<Unit> {
                    requiredPosition.complete(lineage to position)
                    return protected
                }
            }
        gateway(settings("cancel-protected", 5_000), factory, gate).use { gateway ->
            val cancellation = gateway.cancel(cancellation(target))
            assertEquals(lineage to 128L, requiredPosition.get(3, TimeUnit.SECONDS))
            assertFalse(cancellation.isDone)

            protected.complete(Unit)
            val accepted = cancellation.get(3, TimeUnit.SECONDS)
            assertEquals(cancelId, accepted.cancelId)
            assertEquals("REMOTE_RECOVERY", accepted.protection)
        }
    }

    @Test
    fun `epoch와 receipt ACK도 같은 자금 세션의 기존 응답으로 변환한다`() {
        val factory = Factory()
        factory.afterOffer = { request ->
            val command = control(request)
            val code = if (command is BoundedTradingCommand.Rotate) "ISSUED" else "ACKNOWLEDGED"
            factory.replies.add(
                reply(request.correlationId).let { outer ->
                    outer.copy(
                        code = code,
                        control = BoundedTradingReply(request.correlationId, code, 128, epoch = 4, retiredThrough = 7, lineage = lineage),
                    )
                },
            )
        }
        gateway(settings("receipts"), factory).use { gateway ->
            assertEquals(4L, gateway.rotate("user", 2, 3).get(3, TimeUnit.SECONDS).epoch)
            assertEquals(7L, gateway.acknowledge("user", 2, 4, 7).get(3, TimeUnit.SECONDS).retiredThrough)
        }
        assertEquals(
            BoundedTradingCommand.Rotate(
                com.exchange.core.common
                    .UserId("user"),
                2,
                3,
            ),
            control(factory.requests.first()),
        )
        assertEquals(
            BoundedTradingCommand.Acknowledge(
                com.exchange.core.common
                    .UserId("user"),
                2,
                4,
                7,
            ),
            control(factory.requests.last()),
        )
    }

    @Test
    fun `UTF8 바이트 한도 위반은 전송 전에 거절하고 다음 주문은 계속 접수한다`() {
        val factory = Factory()
        factory.afterOffer = { factory.replies.add(reply(it.correlationId)) }
        gateway(settings("invalid"), factory).use { gateway ->
            val invalid = gateway.acknowledge("가".repeat(86), 0, 1, 0).get(3, TimeUnit.SECONDS)
            assertEquals("NOT_SUBMITTED", invalid.code)
            assertEquals("INVALID_REQUEST", invalid.failureCode)
            assertFalse(invalid.possiblySubmitted)
            assertEquals("ACCEPTED", gateway.submit(submission(1)).get(3, TimeUnit.SECONDS).code)
        }
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `전송 수용 여부에 따라 split 요청의 미확인 결과를 구분한다`() {
        for (accepted in listOf(false, true)) {
            val factory = Factory().also { it.offerResult = if (accepted) 1 else Publication.BACK_PRESSURED }
            gateway(settings("unconfirmed-$accepted", 100), factory).use { gateway ->
                val result = gateway.submit(submission(1)).get(3, TimeUnit.SECONDS)
                assertEquals(if (accepted) "OUTCOME_UNKNOWN" else "NOT_SUBMITTED", result.code)
                assertEquals(accepted, result.possiblySubmitted)
                assertEquals(if (accepted) 1 else 0, factory.requests.size)

                val cancellation = gateway.cancel(cancellation(OrderId("target"))).get(3, TimeUnit.SECONDS)
                assertEquals(if (accepted) "OUTCOME_UNKNOWN" else "NOT_SUBMITTED", cancellation.code)
                assertEquals(if (accepted) "RESULT_UNCONFIRMED" else "QUEUE_TIMEOUT", cancellation.failureCode)
                assertEquals(accepted, cancellation.possiblySubmitted)
                assertEquals(if (accepted) 2 else 0, factory.requests.size)
            }
        }
    }

    @Test
    fun `기한 뒤 poll에서 받은 취소 접수 응답은 결과 미확인으로 처리한다`() {
        val timeoutMs = 300L
        val target = OrderId("target")
        val receipt = CancelReceipt(CancelCode.ACCEPTED, TradingIdentifiers.cancel(lineage, 1), target, 1)
        val factory = Factory()
        factory.afterOffer = { request -> factory.replies.add(cancellationReply(request.correlationId, receipt)) }
        factory.beforeReply = { response -> if (response.cancellation != null) Thread.sleep(timeoutMs + 100) }

        gateway(settings("late-cancel-reply", timeoutMs), factory).use { gateway ->
            val result = gateway.cancel(cancellation(target)).get(3, TimeUnit.SECONDS)
            assertEquals("OUTCOME_UNKNOWN", result.code)
            assertEquals("RESULT_UNCONFIRMED", result.failureCode)
            assertTrue(result.possiblySubmitted)
        }
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `앞선 전송이 지연되는 동안 기한이 지난 다음 취소는 보내지 않는다`() {
        val timeoutMs = 300L
        val firstOffered = CountDownLatch(1)
        val secondQueued = CountDownLatch(1)
        val factory = Factory()
        factory.afterOffer = {
            if (factory.requests.size == 1) {
                firstOffered.countDown()
                check(secondQueued.await(3, TimeUnit.SECONDS))
                Thread.sleep(timeoutMs + 100)
            }
        }

        gateway(settings("late-cancel-offer", timeoutMs), factory).use { gateway ->
            val first = gateway.cancel(cancellation(OrderId("first")))
            assertTrue(firstOffered.await(3, TimeUnit.SECONDS))
            val second = gateway.cancel(cancellation(OrderId("second")).copy(sequence = 9))
            secondQueued.countDown()

            assertEquals("OUTCOME_UNKNOWN", first.get(3, TimeUnit.SECONDS).code)
            val expired = second.get(3, TimeUnit.SECONDS)
            assertEquals("NOT_SUBMITTED", expired.code)
            assertEquals("QUEUE_TIMEOUT", expired.failureCode)
            assertFalse(expired.possiblySubmitted)
        }
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `기한 뒤 완료된 원격 보호는 취소 접수 성공으로 응답하지 않는다`() {
        val timeoutMs = 300L
        val target = OrderId("target")
        val receipt = CancelReceipt(CancelCode.ACCEPTED, TradingIdentifiers.cancel(lineage, 1), target, 1)
        val factory = Factory()
        factory.afterOffer = { request -> factory.replies.add(cancellationReply(request.correlationId, receipt)) }
        val gateCalled = CountDownLatch(1)
        val gate =
            object : RecoveryDurabilityGate {
                override fun awaitProtected(
                    lineage: ClusterLineage,
                    position: Long,
                ): CompletableFuture<Unit> {
                    gateCalled.countDown()
                    Thread.sleep(timeoutMs + 100)
                    return CompletableFuture.completedFuture(Unit)
                }
            }

        gateway(settings("late-cancel-protection", timeoutMs), factory, gate).use { gateway ->
            val cancellation = gateway.cancel(cancellation(target))
            assertTrue(gateCalled.await(3, TimeUnit.SECONDS))
            val result = cancellation.get(3, TimeUnit.SECONDS)
            assertEquals("OUTCOME_UNKNOWN", result.code)
            assertEquals("REMOTE_PROTECTION_UNCONFIRMED", result.failureCode)
            assertTrue(result.possiblySubmitted)
        }
    }

    @Test
    fun `자금 소유자와 control 봉투가 맞지 않는 응답은 HTTP 완료에 전달하지 않는다`() {
        val factory = Factory()
        val seen = mutableListOf<BoundedTradingReply>()
        val settings = settings("owner")
        Files.createDirectory(settings.runRoot)
        SplitFundsGatewayTransport(settings, lineage, seen::add, factory).use {
            val valid = reply(7)
            val control = checkNotNull(valid.control)
            val invalid =
                listOf(
                    valid.copy(role = TradingServiceRole.MATCHING),
                    valid.copy(lineage = ClusterLineage(91, 92)),
                    valid.copy(control = null),
                    valid.copy(expectedSequence = 1),
                    valid.copy(control = control.copy(correlationId = 8)),
                    valid.copy(control = control.copy(lineage = ClusterLineage(91, 92))),
                    valid.copy(control = control.copy(code = "REJECTED")),
                    valid.copy(control = control.copy(appliedLogPosition = 129)),
                )
            for (response in invalid) {
                assertFailsWith<IllegalArgumentException> { factory.receive(response) }
            }
            assertTrue(seen.isEmpty())
            factory.receive(valid)
            assertEquals(listOf(control), seen)
        }
    }

    @Test
    fun `다른 역할이나 lineage에 연결하면 첫 금융 요청 전에 실패하고 자원을 회수한다`() {
        for (wrongRole in listOf(false, true)) {
            val factory =
                Factory().also {
                    if (wrongRole) it.peerRole = TradingServiceRole.MATCHING else it.peerLineage = ClusterLineage(91, 92)
                }
            val settings = settings("peer-$wrongRole")
            Files.createDirectory(settings.runRoot)
            assertFailsWith<IllegalArgumentException> { SplitFundsGatewayTransport(settings, lineage, {}, factory) }
            assertTrue(factory.requests.isEmpty())
            assertTrue(factory.closed)
            assertTrue(Files.list(settings.runRoot).use { it.findAny().isEmpty })
        }
    }

    @Test
    fun `설정한 자금 소유자가 아직 초기화되지 않았으면 연결 뒤 업무 NOT_READY를 보존한다`() {
        val factory = Factory().also { it.peerLifecycle = "UNINITIALIZED" }
        factory.afterOffer = { request ->
            val control = BoundedTradingReply(request.correlationId, "NOT_READY", 128, lineage = lineage)
            factory.replies.add(reply(request.correlationId).copy(code = control.code, control = control))
        }
        gateway(settings("not-ready"), factory).use { gateway ->
            val result = gateway.submit(submission(1)).get(3, TimeUnit.SECONDS)
            assertEquals("NOT_READY", result.code)
            assertEquals(null, result.failureCode)
        }
        assertEquals(1, factory.requests.size)
    }

    @Test
    fun `연결 시도 실패와 정리 실패에서도 WAS Driver와 열린 세션을 회수한다`() {
        for (stage in listOf("begin", "poll", "close", "timeout", "describe-timeout", "describe-closed")) {
            val factory = Factory().also { it.failAt = stage }
            val settings = settings("failed-$stage").copy(startupTimeoutMs = 10)
            Files.createDirectory(settings.runRoot)
            assertFailsWith<IllegalStateException> { SplitFundsGatewayTransport(settings, lineage, {}, factory) }
            if (stage != "begin") assertTrue(factory.attemptClosed)
            if (stage in listOf("close", "describe-timeout", "describe-closed")) assertTrue(factory.closed)
            assertTrue(Files.list(settings.runRoot).use { it.findAny().isEmpty }, stage)
        }
    }

    private fun gateway(
        settings: AeronGatewaySettings,
        factory: Factory,
        durabilityGate: RecoveryDurabilityGate? = null,
    ) = BoundedAeronGateway(
        settings,
        protectedLineage = lineage.takeIf { durabilityGate != null },
        durabilityGate = durabilityGate,
        commandEncoder = SplitFundsGatewayTransport.Companion::encodeCommand,
        cancelEncoder = SplitFundsGatewayTransport.Companion::encodeCancellation,
    ) { receive ->
        SplitFundsGatewayTransport(settings, lineage, receive, factory, onCancellation = receive.cancellation)
    }

    private fun settings(
        name: String,
        timeoutMs: Long = 1_000,
    ) = AeronGatewaySettings(directory.resolve(name), "0=127.0.0.1:19700", "127.0.0.1:19707", resultTimeoutMs = timeoutMs)

    private fun submission(sequence: Long) =
        BoundedTradingOrderSubmission("user", 2, 4, sequence, 1, "BTC-USDT", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 1)

    private fun cancellation(orderId: OrderId) = BoundedTradingCancellation("user", 2, 4, 8, 1, "BTC-USDT", orderId.value)

    private fun control(request: SplitOrderDeliveryEnvelope) = assertIs<SplitOrderDeliveryCommand.Control>(request.command).command

    private fun cancellationReply(
        correlation: Long,
        receipt: CancelReceipt,
    ) = reply(correlation).copy(code = receipt.code.name, control = null, cancellation = receipt)

    private fun reply(correlation: Long): SplitOrderDeliveryReply {
        val control =
            BoundedTradingReply(
                correlation,
                "ACCEPTED",
                128,
                AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("order-$correlation"), correlation, correlation),
                lineage = lineage,
            )
        return SplitOrderDeliveryReply(correlation, control.code, TradingServiceRole.FUNDS, lineage, "0".repeat(64), 128, control = control)
    }

    private class Factory : SplitOrderDeliveryConnectionFactory {
        lateinit var endpoint: SplitOrderDeliveryEndpoint
        lateinit var receive: (SplitOrderDeliveryReply) -> Unit
        val requests = ConcurrentLinkedQueue<SplitOrderDeliveryEnvelope>()
        val replies = ConcurrentLinkedQueue<SplitOrderDeliveryReply>()
        var afterOffer: (SplitOrderDeliveryEnvelope) -> Unit = {}
        var beforeReply: (SplitOrderDeliveryReply) -> Unit = {}
        var offerResult = 1L
        var peerRole = TradingServiceRole.FUNDS
        var peerLineage = ClusterLineage(81, 82)
        var peerLifecycle = "RUNNING"
        var failAt: String? = null
        var attemptClosed = false
        var closed = false
        private val codec = SplitOrderDeliveryCodec(2)

        override fun begin(
            endpoint: SplitOrderDeliveryEndpoint,
            timeoutNs: Long,
            onReply: (SplitOrderDeliveryReply) -> Unit,
        ): SplitOrderDeliveryConnectAttempt {
            check(failAt != "begin") { "begin failure" }
            this.endpoint = endpoint
            receive = onReply
            val connection =
                object : SplitOrderDeliveryConnection {
                    override val isClosed get() = closed

                    override fun offer(bytes: ByteArray): Long {
                        val request = codec.decode(bytes)
                        if (request.command == SplitOrderDeliveryCommand.DescribePeer) {
                            if (failAt == "describe-closed") return Publication.CLOSED
                            if (failAt != "describe-timeout") {
                                replies.add(
                                    SplitOrderDeliveryReply(
                                        0,
                                        "DESCRIBED",
                                        peerRole,
                                        peerLineage,
                                        "0".repeat(64),
                                        0,
                                        lifecycle = peerLifecycle,
                                    ),
                                )
                            }
                            return 1
                        }
                        if (offerResult > 0) {
                            requests.add(request)
                            afterOffer(request)
                        }
                        return offerResult
                    }

                    override fun poll(): Int {
                        val reply = replies.poll() ?: return 0
                        beforeReply(reply)
                        receive(reply)
                        return 1
                    }

                    override fun keepAlive() = true

                    override fun close() {
                        closed = true
                    }
                }
            return object : SplitOrderDeliveryConnectAttempt {
                override fun poll(): SplitOrderDeliveryConnection? {
                    check(failAt != "poll") { "poll failure" }
                    return connection.takeUnless { failAt == "timeout" }
                }

                override fun close() {
                    attemptClosed = true
                    check(failAt != "close") { "close failure" }
                }
            }
        }
    }
}

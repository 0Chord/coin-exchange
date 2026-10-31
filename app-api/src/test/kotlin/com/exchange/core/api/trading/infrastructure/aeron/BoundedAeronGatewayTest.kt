package com.exchange.core.api.trading.infrastructure.aeron

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}

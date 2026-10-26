package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.TradingAdmissionResult
import com.exchange.core.api.trading.application.TradingOrderSubmission
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.UserId
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** transport 경계에서만 송수신을 통제하며 실제 gateway 소유 스레드와 실제 시간 제한을 실행한다. */
class AeronOrderGatewayTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `양수 offer는 엔진 접수 승인이 아니며 응답을 받아야 완료한다`() {
        val transport = TestTransport()
        gateway(transport).use { gateway ->
            val submission = submission()
            val result = gateway.submit(submission)
            val sent = AdmissionCommandCodec().decode(transport.awaitOffer())

            assertEquals(submission.requestId, sent.requestId)
            assertEquals(submission.order, sent.order)
            assertFalse(result.isDone)

            transport.respond(accepted(submission, 17))
            assertEquals(TradingAdmissionResult.Accepted(17), result.await())
        }
    }

    @Test
    fun `순서가 뒤집힌 응답도 사용자와 요청 키로 각 future에 연결한다`() {
        val transport = TestTransport()
        gateway(transport).use { gateway ->
            val first = submission()
            val otherUser = submission(orderId = "O2", userId = "user-2")
            val otherRequest = submission(requestId = "R2", orderId = "O3")
            val firstResult = gateway.submit(first)
            val userResult = gateway.submit(otherUser)
            val requestResult = gateway.submit(otherRequest)
            repeat(3) { transport.awaitOffer() }

            transport.respond(accepted(otherRequest, 31))
            assertEquals(TradingAdmissionResult.Accepted(31), requestResult.await())
            assertFalse(firstResult.isDone)
            assertFalse(userResult.isDone)
            transport.respond(accepted(otherUser, 22))
            assertEquals(TradingAdmissionResult.Accepted(22), userResult.await())
            assertFalse(firstResult.isDone)
            transport.respond(
                AdmissionResultPayload.Rejected(
                    first.order.userId.value,
                    first.requestId,
                    first.order.marketId.value,
                    first.order.orderId.value,
                    AdmissionResultPayload.Reason.DUPLICATE_ORDER,
                ),
            )
            assertEquals(TradingAdmissionResult.Rejected("DUPLICATE_ORDER"), firstResult.await())
        }
    }

    @Test
    fun `진행 중 동일 원본은 future를 공유하고 경제 내용 충돌은 전송 전에 거절한다`() {
        val transport = TestTransport()
        gateway(transport).use { gateway ->
            val submission = submission()
            val first = gateway.submit(submission)
            transport.awaitOffer()

            assertSame(first, gateway.submit(submission.copy()))
            val changed = submission.copy(order = submission.order.copy(price = Price(200_000_000)))
            assertEquals(
                TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"),
                gateway.submit(changed).await(),
            )
            assertEquals(1, transport.offered.size)
            assertFalse(first.isDone)
            transport.respond(accepted(submission, 1))
            assertEquals(TradingAdmissionResult.Accepted(1), first.await())
        }
    }

    @Test
    fun `미등록 마켓도 주문 원본을 전송하고 최초 판단 충돌과 새 요청의 문맥 거절을 전달한다`() {
        val transport = TestTransport()
        gateway(transport).use { gateway ->
            listOf(true, false).forEachIndexed { index, firstAccepted ->
                val original = submission("R${index + 1}", "O${index + 1}")
                val first = gateway.submit(original)
                transport.awaitOffer()
                if (firstAccepted) {
                    transport.respond(accepted(original, 1))
                    assertEquals(TradingAdmissionResult.Accepted(1), first.await())
                } else {
                    transport.respond(rejected(original, AdmissionResultPayload.Reason.DUPLICATE_ORDER))
                    assertEquals(TradingAdmissionResult.Rejected("DUPLICATE_ORDER"), first.await())
                }

                val changed = original.copy(order = original.order.copy(marketId = MarketId("UNKNOWN-USDT")))
                val retry = gateway.submit(changed)
                val transmitted = AdmissionCommandCodec().decode(transport.awaitOffer())
                assertEquals(changed.order, transmitted.order)
                assertEquals(DevelopmentMarketContext.BTC_USDT.market, transmitted.market)
                assertFalse(retry.isDone)
                transport.respond(rejected(changed, AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT))
                assertEquals(TradingAdmissionResult.Rejected("REQUEST_CONTENT_CONFLICT"), retry.await())
            }

            val fresh = submission("R3", "O3").let { it.copy(order = it.order.copy(marketId = MarketId("UNKNOWN-USDT"))) }
            val freshResult = gateway.submit(fresh)
            assertEquals(fresh.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            transport.respond(rejected(fresh, AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH))
            assertEquals(TradingAdmissionResult.Rejected("EXECUTION_CONTEXT_MISMATCH"), freshResult.await())
        }
    }

    @Test
    fun `inflight 한도까지 보내고 대기 큐가 가득 차면 새 요청만 미전달로 반환한다`() {
        val transport = TestTransport()
        gateway(transport, queueCapacity = 1, maxInFlight = 1).use { gateway ->
            val first = submission()
            val second = submission("R2", "O2")
            val firstResult = gateway.submit(first)
            transport.awaitOffer()
            val secondResult = gateway.submit(second)

            assertEquals(
                TradingAdmissionResult.NotSubmitted("QUEUE_FULL", overloaded = true),
                gateway.submit(submission("R3", "O3")).await(),
            )
            assertSame(secondResult, gateway.submit(second))
            transport.awaitAnotherPoll()
            assertEquals(1, transport.offered.size)
            assertFalse(secondResult.isDone)

            transport.respond(accepted(first, 1))
            assertEquals(TradingAdmissionResult.Accepted(1), firstResult.await())
            val sentSecond = AdmissionCommandCodec().decode(transport.awaitOffer())
            assertEquals(second.order, sentSecond.order)
            transport.respond(accepted(second, 2))
            assertEquals(TradingAdmissionResult.Accepted(2), secondResult.await())
        }
    }

    @Test
    fun `backpressure와 admin action 재시도는 최초 인코딩 바이트를 그대로 사용한다`() {
        val transport = TestTransport(listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 9L))
        gateway(transport).use { gateway ->
            val submission = submission()
            val result = gateway.submit(submission)
            val attempts = List(3) { transport.awaitOffer() }

            attempts.drop(1).forEach { retry ->
                assertSame(attempts.first(), retry)
                assertContentEquals(attempts.first(), retry)
            }
            assertEquals(3, transport.offered.size)
            assertFalse(result.isDone)
            transport.respond(accepted(submission, 7))
            assertEquals(TradingAdmissionResult.Accepted(7), result.await())
        }
    }

    @Test
    fun `실제 시간 초과는 미확인으로 끝나고 이전 session을 닫기 전 같은 키 원본을 바꾸지 못한다`() {
        val releaseClose = CountDownLatch(1)
        val old = TestTransport(closeAllowed = releaseClose)
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val settings = settings(resultTimeoutMs = 200)
        val gateway =
            AeronOrderGateway(settings, markets) {
                if (opens.getAndIncrement() == 0) old else replacement.also { assertTrue(old.fullyClosed.await(1, TimeUnit.SECONDS)) }
            }
        try {
            val original = submission()
            val result = gateway.submit(original)
            old.awaitOffer()

            assertEquals(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true), result.await())
            assertTrue(old.closeStarted.await(1, TimeUnit.SECONDS))
            val changed = original.copy(order = original.order.copy(price = Price(200_000_000)))
            assertSame(result, gateway.submit(original))
            assertEquals(
                TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"),
                gateway.submit(changed).await(),
            )
            assertEquals(1, opens.get())
            releaseClose.countDown()
            awaitCondition { opens.get() == 2 }

            val retry = awaitAvailable(gateway, changed)
            assertEquals(changed.order, AdmissionCommandCodec().decode(replacement.awaitOffer()).order)
            old.respond(accepted(original, 11))
            replacement.awaitAnotherPoll()
            assertFalse(retry.isDone)
            replacement.respond(accepted(changed, 12))
            assertEquals(TradingAdmissionResult.Accepted(12), retry.await())
        } finally {
            releaseClose.countDown()
            gateway.close()
        }
    }

    @Test
    fun `연결 terminal 오류는 이전 session을 닫고 새 시도에 이전 응답을 섞지 않는다`() {
        val old = TestTransport()
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val gateway =
            AeronOrderGateway(settings(), markets) {
                if (opens.getAndIncrement() == 0) old else replacement.also { assertTrue(old.fullyClosed.await(1, TimeUnit.SECONDS)) }
            }
        gateway.use {
            val submission = submission()
            val first = gateway.submit(submission)
            old.awaitOffer()
            old.pollFailure.set(IllegalStateException("terminal transport failure"))

            assertEquals(TradingAdmissionResult.Unknown("CONNECTION_LOST"), first.await())
            awaitCondition { opens.get() == 2 }
            val retry = awaitAvailable(gateway, submission)
            replacement.awaitOffer()
            old.respond(accepted(submission, 100))
            replacement.awaitAnotherPoll()
            assertFalse(retry.isDone)
            replacement.respond(accepted(submission, 101))
            assertEquals(TradingAdmissionResult.Accepted(101), retry.await())
            assertEquals(1, old.closeCalls.get())
        }
    }

    @Test
    fun `종료는 전송된 주문을 미확인으로 완료하고 이후 제출은 미전달이다`() {
        val transport = TestTransport()
        val gateway = gateway(transport)
        val result = gateway.submit(submission())
        transport.awaitOffer()

        gateway.close()

        assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), result.await())
        assertEquals(1, transport.closeCalls.get())
        assertEquals(
            TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE"),
            gateway.submit(submission("R2", "O2")).await(),
        )
        gateway.close()
        assertEquals(1, transport.closeCalls.get())
    }

    @Test
    fun `연결 준비 실패는 생성자에서 원인을 보존해 알린다`() {
        val failure = IllegalStateException("cannot connect")
        val error =
            assertFailsWith<IllegalStateException> {
                AeronOrderGateway(settings(), markets) { throw failure }
            }
        assertSame(failure, error.cause)
    }

    private fun gateway(
        transport: TestTransport,
        queueCapacity: Int = 8,
        maxInFlight: Int = 8,
    ): AeronOrderGateway = AeronOrderGateway(settings(queueCapacity, maxInFlight), markets) { transport }

    private fun settings(
        queueCapacity: Int = 8,
        maxInFlight: Int = 8,
        resultTimeoutMs: Long = 5_000,
    ): AeronGatewaySettings =
        AeronGatewaySettings(
            directory.resolve("run"),
            "0=127.0.0.1:19700",
            "127.0.0.1:19701",
            queueCapacity,
            maxInFlight,
            resultTimeoutMs,
            1_000,
        )

    private fun submission(
        requestId: String = "R1",
        orderId: String = "O1",
        userId: String = "user-1",
    ): TradingOrderSubmission =
        admissionCommand(requestId, orderId).let { TradingOrderSubmission(requestId, it.order.copy(userId = UserId(userId))) }

    private fun accepted(
        submission: TradingOrderSubmission,
        sequence: Long,
    ): AdmissionResultPayload.Accepted =
        AdmissionResultPayload.Accepted(
            submission.order.userId.value,
            submission.requestId,
            submission.order.marketId.value,
            submission.order.orderId.value,
            sequence,
        )

    private fun rejected(
        submission: TradingOrderSubmission,
        reason: AdmissionResultPayload.Reason,
    ): AdmissionResultPayload.Rejected =
        AdmissionResultPayload.Rejected(
            submission.order.userId.value,
            submission.requestId,
            submission.order.marketId.value,
            submission.order.orderId.value,
            reason,
        )

    private fun CompletableFuture<TradingAdmissionResult>.await(): TradingAdmissionResult = get(3, TimeUnit.SECONDS)

    private fun awaitAvailable(
        gateway: AeronOrderGateway,
        submission: TradingOrderSubmission,
    ): CompletableFuture<TradingAdmissionResult> {
        var retry: CompletableFuture<TradingAdmissionResult>? = null
        awaitCondition {
            gateway.submit(submission).also { retry = it }.getNow(null) != TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE")
        }
        return checkNotNull(retry)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "gateway 조건 대기 시간이 초과됐습니다" }
            Thread.sleep(1)
        }
    }

    private class TestTransport(
        offerResults: List<Long> = emptyList(),
        private val closeAllowed: CountDownLatch? = null,
    ) : GatewayOrderTransport {
        val offered = CopyOnWriteArrayList<ByteArray>()
        val pollFailure = AtomicReference<RuntimeException?>()
        val closeStarted = CountDownLatch(1)
        val fullyClosed = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val positions = ConcurrentLinkedQueue(offerResults)
        private val offers = LinkedBlockingQueue<ByteArray>()
        private val responses = ConcurrentLinkedQueue<AdmissionResultPayload>()
        private val polls = AtomicInteger()

        override fun offer(bytes: ByteArray): Long {
            check(fullyClosed.count != 0L)
            offered += bytes
            offers.add(bytes)
            return positions.poll() ?: 1L
        }

        override fun poll(receive: (AdmissionResultPayload) -> Unit): Int {
            pollFailure.getAndSet(null)?.let { throw it }
            var count = 0
            while (true) {
                val response = responses.poll() ?: break
                receive(response)
                count++
            }
            polls.incrementAndGet()
            return count
        }

        fun respond(response: AdmissionResultPayload) {
            responses.add(response)
        }

        fun awaitOffer(): ByteArray = checkNotNull(offers.poll(3, TimeUnit.SECONDS)) { "주문 offer가 없습니다" }

        fun awaitAnotherPoll() {
            val previous = polls.get()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (polls.get() == previous) {
                check(System.nanoTime() < deadline) { "다음 poll이 없습니다" }
                Thread.sleep(1)
            }
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closeStarted.countDown()
            closeAllowed?.let { check(it.await(3, TimeUnit.SECONDS)) { "session 종료 허용을 받지 못했습니다" } }
            fullyClosed.countDown()
        }
    }

    companion object {
        private val markets = DevelopmentMarketRegistry(listOf(DevelopmentMarketContext.BTC_USDT))
    }
}

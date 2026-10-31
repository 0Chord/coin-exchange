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
            transport.awaitAnotherPoll()
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
    fun `동시 제출도 전송 중인 큐 자리를 포함한 한도와 동일 키 future를 보존한다`() {
        val releaseOffer = CountDownLatch(1)
        val transport = TestTransport(offerAllowed = releaseOffer)
        val gateway = gateway(transport, queueCapacity = 4, maxInFlight = 1)
        try {
            val first = gateway.submit(submission())
            transport.awaitOffer()
            val submissions = (2..9).flatMap { index -> List(2) { submission("R$index", "O$index") } }
            val submitted = submissions.map { pending -> CompletableFuture.supplyAsync { gateway.submit(pending) } }
            val results = submitted.map { it.get(3, TimeUnit.SECONDS) }
            val reserved = results.withIndex().filter { !it.value.isDone }
            assertEquals(6, reserved.size, "진행 중 offer 한 자리 외에 세 키만 두 번씩 예약한다")
            reserved.groupBy { submissions[it.index].requestId }.values.forEach { duplicates ->
                assertEquals(2, duplicates.size)
                assertSame(duplicates[0].value, duplicates[1].value)
            }
            results.filter { it.isDone }.forEach {
                assertEquals(TradingAdmissionResult.NotSubmitted("QUEUE_FULL", overloaded = true), it.await())
            }
            releaseOffer.countDown()
            gateway.close()
            assertEquals(1, transport.offered.size)
            assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), first.await())
            reserved.forEach { assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), it.value.await()) }
        } finally {
            releaseOffer.countDown()
            gateway.close()
        }
    }

    @Test
    fun `여러 poll에 걸친 응답 backlog를 비운 뒤 다음 주문을 offer한다`() {
        val transport = TestTransport(fragmentLimit = 1)
        val gateway = AeronOrderGateway(settings(), markets, pollNanoTime = { 0L }) { transport }
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        try {
            val submissions = (1..3).map { submission("R$it", "O$it") }
            val results = submissions.map(gateway::submit)
            repeat(3) { transport.awaitOffer() }
            transport.pauseNextPoll(enteredPoll, releasePoll)
            assertTrue(enteredPoll.await(3, TimeUnit.SECONDS))
            val pollsBeforeDrain = transport.pollCount()
            submissions.forEachIndexed { index, pending -> transport.respond(accepted(pending, index + 1L)) }
            val next = submission("R4", "O4")
            val nextResult = gateway.submit(next)

            releasePoll.countDown()
            assertEquals(next.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            assertEquals(4, transport.pollCountsAtOffer.last() - pollsBeforeDrain, "응답 세 번과 빈 poll 뒤에 추가 offer한다")
            results.forEachIndexed { index, result -> assertEquals(TradingAdmissionResult.Accepted(index + 1L), result.await()) }
            transport.respond(accepted(next, 4))
            assertEquals(TradingAdmissionResult.Accepted(4), nextResult.await())
        } finally {
            releasePoll.countDown()
            gateway.close()
        }
    }

    @Test
    fun `연속 응답 작업도 fragment 예산 뒤에는 대기 주문을 전송한다`() {
        val transport = TestTransport()
        val gateway = AeronOrderGateway(settings(), markets, pollNanoTime = { 0L }) { transport }
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        try {
            val first = gateway.submit(submission())
            transport.awaitOffer()
            transport.pauseNextPoll(enteredPoll, releasePoll)
            assertTrue(enteredPoll.await(3, TimeUnit.SECONDS))
            val pollsBeforeDrain = transport.pollCount()
            transport.continuousWork.set(1)
            val next = submission("R2", "O2")
            val nextResult = gateway.submit(next)

            releasePoll.countDown()
            assertEquals(next.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            assertEquals(64, transport.pollCountsAtOffer.last() - pollsBeforeDrain)
            assertFalse(first.isDone)
            assertFalse(nextResult.isDone)
        } finally {
            releasePoll.countDown()
            gateway.close()
        }
    }

    @Test
    fun `fragment 예산이 남아도 드레인 1ms 뒤에는 대기 주문을 전송한다`() {
        val pollClock =
            java.util.concurrent.atomic
                .AtomicLong()
        val transport = TestTransport(onPollWork = { pollClock.addAndGet(600_000) })
        val gateway = AeronOrderGateway(settings(), markets, pollNanoTime = pollClock::get) { transport }
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        try {
            gateway.submit(submission())
            transport.awaitOffer()
            transport.pauseNextPoll(enteredPoll, releasePoll)
            assertTrue(enteredPoll.await(3, TimeUnit.SECONDS))
            val pollsBeforeDrain = transport.pollCount()
            transport.continuousWork.set(1)
            val next = submission("R2", "O2")
            gateway.submit(next)

            releasePoll.countDown()
            assertEquals(next.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            assertEquals(2, transport.pollCountsAtOffer.last() - pollsBeforeDrain, "각 poll이 0.6ms라 두 번 뒤 전송한다")
        } finally {
            releasePoll.countDown()
            gateway.close()
        }
    }

    @Test
    fun `연속 응답 작업 중 종료 신호가 오면 다음 poll과 추가 전송을 하지 않는다`() {
        val transport = TestTransport()
        val gateway = AeronOrderGateway(settings(queueCapacity = 1), markets, pollNanoTime = { 0L }) { transport }
        val enteredPoll = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        try {
            val first = gateway.submit(submission())
            transport.awaitOffer()
            transport.pauseNextPoll(enteredPoll, releasePoll)
            assertTrue(enteredPoll.await(3, TimeUnit.SECONDS))
            val pollsBeforeDrain = transport.pollCount()
            transport.continuousWork.set(1)
            val queued = gateway.submit(submission("R2", "O2"))
            val closed = CompletableFuture.runAsync { gateway.close() }
            awaitCondition {
                gateway.submit(submission("R3", "O3")).getNow(null) == TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE")
            }

            releasePoll.countDown()
            closed.get(3, TimeUnit.SECONDS)
            assertEquals(1, transport.pollCount() - pollsBeforeDrain, "진행 중인 poll만 마치고 종료한다")
            assertEquals(1, transport.offered.size)
            assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), first.await())
            assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), queued.await())
            assertEquals(1, transport.closeCalls.get())
        } finally {
            releasePoll.countDown()
            gateway.close()
        }
    }

    @Test
    fun `연속 응답 작업 중에도 실제 요청 기한을 확인하고 연결을 교체한다`() {
        val old = TestTransport()
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val gateway =
            AeronOrderGateway(settings(resultTimeoutMs = 200), markets, pollNanoTime = { 0L }) {
                if (opens.getAndIncrement() == 0) old else replacement
            }
        gateway.use {
            val result = gateway.submit(submission())
            old.awaitOffer()
            // 제어 fragment처럼 작업은 계속 있지만 이 주문의 응답은 오지 않는다.
            old.continuousWork.set(1)

            assertEquals(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true), result.await())
            assertTrue(old.fullyClosed.await(3, TimeUnit.SECONDS))
            awaitCondition { opens.get() == 2 }
            assertEquals(1, old.closeCalls.get())
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
    fun `offer가 막혀도 동시 제출과 같은 키 조회는 진행되고 예약한 큐 한도를 유지한다`() {
        val releaseOffer = CountDownLatch(1)
        val transport = TestTransport(offerAllowed = releaseOffer)
        val gateway = gateway(transport, queueCapacity = 2, maxInFlight = 1)
        try {
            val first = submission()
            val firstResult = gateway.submit(first)
            transport.awaitOffer()
            val second = submission("R2", "O2")
            val secondResult = CompletableFuture.supplyAsync { gateway.submit(second) }.get(1, TimeUnit.SECONDS)

            assertSame(firstResult, gateway.submit(first.copy()))
            assertSame(secondResult, gateway.submit(second.copy()))
            assertEquals(
                TradingAdmissionResult.Rejected("REQUEST_IN_FLIGHT_CONTENT_CONFLICT"),
                gateway.submit(second.copy(order = second.order.copy(price = Price(200_000_000)))).await(),
            )
            assertEquals(
                TradingAdmissionResult.NotSubmitted("QUEUE_FULL", overloaded = true),
                gateway.submit(submission("R3", "O3")).await(),
            )
            releaseOffer.countDown()
            transport.awaitAnotherPoll()
            assertEquals(1, transport.offered.size, "offer 중인 요청도 inflight 한도에서 빠지지 않는다")
            assertFalse(secondResult.isDone)

            transport.respond(accepted(first, 1))
            assertEquals(TradingAdmissionResult.Accepted(1), firstResult.await())
            assertEquals(second.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            transport.respond(accepted(second, 2))
            assertEquals(TradingAdmissionResult.Accepted(2), secondResult.await())
        } finally {
            releaseOffer.countDown()
            gateway.close()
        }
    }

    @Test
    fun `인코딩 대기도 같은 future를 공유하고 잘못된 명령은 예약을 해제한 뒤 다음 주문을 처리한다`() {
        val releaseOffer = CountDownLatch(1)
        val transport = TestTransport(offerAllowed = releaseOffer)
        val gateway = gateway(transport, queueCapacity = 2)
        try {
            val first = submission()
            val firstResult = gateway.submit(first)
            transport.awaitOffer()
            val invalid = submission(requestId = "R".repeat(257), orderId = "invalid")
            val invalidResult = CompletableFuture.supplyAsync { gateway.submit(invalid) }.get(1, TimeUnit.SECONDS)

            assertSame(invalidResult, gateway.submit(invalid.copy()))
            assertFalse(invalidResult.isDone, "연결 소유 스레드가 아직 인코딩하지 않은 예약이다")
            releaseOffer.countDown()
            assertEquals(TradingAdmissionResult.InvalidInput("INVALID_COMMAND"), invalidResult.await())
            assertEquals(1, transport.offered.size, "인코딩이 실패한 원본은 전송하지 않는다")

            val retry = gateway.submit(invalid)
            assertTrue(retry !== invalidResult, "인코딩 실패 뒤 요청 키 예약을 해제한다")
            assertEquals(TradingAdmissionResult.InvalidInput("INVALID_COMMAND"), retry.await())
            val next = submission("R2", "O2")
            val nextResult = gateway.submit(next)
            assertEquals(next.order, AdmissionCommandCodec().decode(transport.awaitOffer()).order)
            transport.respond(accepted(first, 1))
            transport.respond(accepted(next, 2))
            assertEquals(TradingAdmissionResult.Accepted(1), firstResult.await())
            assertEquals(TradingAdmissionResult.Accepted(2), nextResult.await())
        } finally {
            releaseOffer.countDown()
            gateway.close()
        }
    }

    @Test
    fun `offer와 종료가 경합해도 신규 제출을 막고 전송 중과 인코딩 대기 future를 모두 완료한다`() {
        val releaseOffer = CountDownLatch(1)
        val transport = TestTransport(offerAllowed = releaseOffer)
        val gateway = gateway(transport)
        try {
            val first = submission()
            val firstResult = gateway.submit(first)
            transport.awaitOffer()
            val queuedResult = CompletableFuture.supplyAsync { gateway.submit(submission("R2", "O2")) }.get(1, TimeUnit.SECONDS)
            val closed = CompletableFuture.runAsync { gateway.close() }
            val closingProbes = mutableListOf<CompletableFuture<TradingAdmissionResult>>()
            awaitCondition {
                val probe = closingProbes.size
                gateway.submit(submission("closing-$probe", "closing-$probe")).also { closingProbes += it }.getNow(null) ==
                    TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE")
            }
            assertSame(firstResult, gateway.submit(first.copy()), "종료 중에도 원래 키는 session 종료까지 유지한다")
            assertFalse(closed.isDone, "진행 중인 offer를 소유 스레드가 마친 뒤 연결을 닫는다")
            releaseOffer.countDown()
            closed.get(3, TimeUnit.SECONDS)

            assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), firstResult.await())
            assertEquals(TradingAdmissionResult.Unknown("GATEWAY_CLOSED"), queuedResult.await())
            assertTrue(closingProbes.all { it.isDone }, "종료 신호와 경합해 예약한 future도 빠짐없이 완료한다")
            assertEquals(1, transport.offered.size, "종료 신호 뒤 대기 주문을 추가로 전송하지 않는다")
            assertEquals(1, transport.closeCalls.get())
        } finally {
            releaseOffer.countDown()
            gateway.close()
        }
    }

    @Test
    fun `terminal offer와 경합한 예약도 이전 연결이 닫힌 뒤에만 새 연결에서 재시도한다`() {
        val releaseOffer = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val old = TestTransport(listOf(Publication.CLOSED), closeAllowed = releaseClose, offerAllowed = releaseOffer)
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val gateway =
            AeronOrderGateway(settings(), markets) {
                if (opens.getAndIncrement() == 0) old else replacement.also { assertEquals(0L, old.fullyClosed.count) }
            }
        try {
            val original = submission()
            val first = gateway.submit(original)
            old.awaitOffer()
            val queued = submission("R2", "O2")
            val queuedResult = CompletableFuture.supplyAsync { gateway.submit(queued) }.get(1, TimeUnit.SECONDS)
            releaseOffer.countDown()

            assertEquals(TradingAdmissionResult.Unknown("CONNECTION_LOST"), first.await())
            assertEquals(TradingAdmissionResult.Unknown("CONNECTION_LOST"), queuedResult.await())
            assertTrue(old.closeStarted.await(1, TimeUnit.SECONDS))
            assertSame(queuedResult, gateway.submit(queued.copy()))
            assertEquals(1, opens.get())
            releaseClose.countDown()
            awaitCondition { opens.get() == 2 }
            val retry = awaitAvailable(gateway, queued)
            replacement.awaitOffer()
            old.respond(accepted(queued, 100))
            replacement.awaitAnotherPoll()
            assertFalse(retry.isDone)
            replacement.respond(accepted(queued, 101))
            assertEquals(TradingAdmissionResult.Accepted(101), retry.await())
        } finally {
            releaseOffer.countDown()
            releaseClose.countDown()
            gateway.close()
        }
    }

    @Test
    fun `기한 검사는 인코딩 실패와 순서가 뒤집힌 완료를 제거한 뒤 가장 오래된 대기를 찾는다`() {
        val releaseClose = CountDownLatch(1)
        val old = TestTransport(closeAllowed = releaseClose)
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val gateway =
            AeronOrderGateway(settings(resultTimeoutMs = 200), markets) {
                if (opens.getAndIncrement() == 0) old else replacement
            }
        try {
            assertEquals(
                TradingAdmissionResult.InvalidInput("INVALID_COMMAND"),
                gateway.submit(submission(requestId = "R".repeat(257))).await(),
            )
            val completed = submission()
            val completedResult = gateway.submit(completed)
            old.awaitOffer()
            old.respond(accepted(completed, 1))
            assertEquals(TradingAdmissionResult.Accepted(1), completedResult.await())

            val oldest = submission("R2", "O2")
            val newest = submission("R3", "O3")
            val oldestResult = gateway.submit(oldest)
            val newestResult = gateway.submit(newest)
            repeat(2) { old.awaitOffer() }
            old.respond(accepted(newest, 3))
            assertEquals(TradingAdmissionResult.Accepted(3), newestResult.await())

            assertEquals(TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true), oldestResult.await())
            assertTrue(old.closeStarted.await(1, TimeUnit.SECONDS))
            assertSame(oldestResult, gateway.submit(oldest.copy()))
            assertEquals(TradingAdmissionResult.Accepted(1), completedResult.await())
            assertEquals(TradingAdmissionResult.Accepted(3), newestResult.await())
            assertEquals(1, opens.get())
        } finally {
            releaseClose.countDown()
            gateway.close()
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
    fun `연결 오류 뒤 교체 연결이 실패해도 재시도하여 새 주문을 처리한다`() {
        assertRecoveryAfterFailedReplacement(timedOut = false)
    }

    @Test
    fun `결과 기한 만료 뒤 교체 연결이 실패해도 재시도하여 새 주문을 처리한다`() {
        assertRecoveryAfterFailedReplacement(timedOut = true)
    }

    @Test
    fun `교체 연결이 계속 실패하는 동안에도 종료할 수 있다`() {
        val old = TestTransport()
        val opens = AtomicInteger()
        val failedAttempts = CountDownLatch(2)
        val gateway =
            AeronOrderGateway(settings(), markets) {
                if (opens.getAndIncrement() == 0) {
                    old
                } else {
                    failedAttempts.countDown()
                    throw IllegalStateException("cluster unavailable")
                }
            }
        try {
            val result = gateway.submit(submission())
            old.awaitOffer()
            old.pollFailure.set(IllegalStateException("connection lost"))

            assertEquals(TradingAdmissionResult.Unknown("CONNECTION_LOST"), result.await())
            assertTrue(failedAttempts.await(3, TimeUnit.SECONDS), "교체 연결을 한 번 실패해도 다시 시도한다")
            gateway.close()

            assertEquals(1, old.closeCalls.get())
            assertEquals(
                TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE"),
                gateway.submit(submission("R2", "O2")).await(),
            )
        } finally {
            gateway.close()
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

    private fun assertRecoveryAfterFailedReplacement(timedOut: Boolean) {
        val old = TestTransport()
        val replacement = TestTransport()
        val opens = AtomicInteger()
        val failedOpenStarted = CountDownLatch(1)
        val releaseFailedOpen = CountDownLatch(1)
        val gateway =
            AeronOrderGateway(settings(resultTimeoutMs = if (timedOut) 200 else 5_000), markets) {
                when (opens.getAndIncrement()) {
                    0 -> {
                        old
                    }

                    1 -> {
                        assertEquals(1, old.closeCalls.get())
                        failedOpenStarted.countDown()
                        check(releaseFailedOpen.await(3, TimeUnit.SECONDS))
                        throw IllegalStateException("replacement connection unavailable")
                    }

                    else -> {
                        replacement
                    }
                }
            }
        try {
            val original = submission()
            val first = gateway.submit(original)
            old.awaitOffer()
            if (!timedOut) old.pollFailure.set(IllegalStateException("connection lost"))
            val unknown =
                if (timedOut) {
                    TradingAdmissionResult.Unknown("ADMISSION_TIMEOUT", timedOut = true)
                } else {
                    TradingAdmissionResult.Unknown("CONNECTION_LOST")
                }
            assertEquals(unknown, first.await())
            assertTrue(failedOpenStarted.await(3, TimeUnit.SECONDS))
            assertEquals(
                TradingAdmissionResult.NotSubmitted("GATEWAY_UNAVAILABLE"),
                gateway.submit(submission("R2", "O2")).await(),
            )

            releaseFailedOpen.countDown()
            val retry = awaitAvailable(gateway, original)
            assertEquals(original.order, AdmissionCommandCodec().decode(replacement.awaitOffer()).order)
            old.respond(accepted(original, 100))
            replacement.awaitAnotherPoll()
            assertFalse(retry.isDone)
            replacement.respond(accepted(original, 101))

            assertEquals(TradingAdmissionResult.Accepted(101), retry.await())
            assertEquals(unknown, first.await(), "복구 후에도 이전 미확인 결과는 바꾸지 않는다")
            assertEquals(3, opens.get())
            assertEquals(1, old.closeCalls.get())
        } finally {
            releaseFailedOpen.countDown()
            gateway.close()
        }
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
        private val offerAllowed: CountDownLatch? = null,
        private val fragmentLimit: Int = Int.MAX_VALUE,
        private val onPollWork: () -> Unit = {},
    ) : GatewayOrderTransport {
        val offered = CopyOnWriteArrayList<ByteArray>()
        val pollCountsAtOffer = CopyOnWriteArrayList<Int>()
        val continuousWork = AtomicInteger()
        val pollFailure = AtomicReference<RuntimeException?>()
        val closeStarted = CountDownLatch(1)
        val fullyClosed = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        private val positions = ConcurrentLinkedQueue(offerResults)
        private val offers = LinkedBlockingQueue<ByteArray>()
        private val responses = ConcurrentLinkedQueue<AdmissionResultPayload>()
        private val polls = AtomicInteger()
        private val pollPause = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>()

        override fun offer(bytes: ByteArray): Long {
            check(fullyClosed.count != 0L)
            pollCountsAtOffer += polls.get()
            offered += bytes
            offers.add(bytes)
            offerAllowed?.let { check(it.await(3, TimeUnit.SECONDS)) { "offer 진행 허용을 받지 못했습니다" } }
            return positions.poll() ?: 1L
        }

        override fun poll(receive: (AdmissionResultPayload) -> Unit): Int {
            pollFailure.getAndSet(null)?.let { throw it }
            pollPause.getAndSet(null)?.let { (entered, release) ->
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "poll 진행 허용을 받지 못했습니다" }
            }
            var count = 0
            while (count < fragmentLimit) {
                val response = responses.poll() ?: break
                receive(response)
                count++
            }
            val work = if (count == 0) continuousWork.get() else count
            if (work > 0) onPollWork()
            polls.incrementAndGet()
            return work
        }

        fun pauseNextPoll(
            entered: CountDownLatch,
            release: CountDownLatch,
        ) {
            check(pollPause.compareAndSet(null, entered to release))
        }

        fun pollCount(): Int = polls.get()

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

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.MarketId
import com.exchange.core.order.Side
import io.aeron.Publication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 codec과 결정적 부하 시계를 쓴다. 대역 transport의 결과는 실제 Aeron 성능 근거가 아니다. */
class DirectAeronCapacityLoadTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `장기 계획은 마지막 ACK 뒤에도 60초 창 끝까지 기다리고 같은 JVM 창 안 전송을 센다`() {
        val fixture = Fixture(60, 4096, rate = 1, sustainedSeconds = 60)
        fixture.clock.minimumParkNs = 1_000_000_000L
        var acknowledged = 0
        fixture.transport.onPoll = { receive ->
            val added = fixture.transport.commands.size - acknowledged
            while (acknowledged < fixture.transport.commands.size) {
                receive(accepted(fixture.transport.commands[acknowledged], acknowledged + 1L))
                acknowledged++
            }
            added
        }
        val result = fixture.run()
        val window = directAeronCapacityWindowEvidence(result)
        assertEquals(60, result.accepted)
        assertEquals(60_000_000_000L, result.finishedNs)
        assertEquals(59_000_000_000L, result.samples.last().dueNs)
        assertTrue(window.windowElapsed)
        assertTrue(window.allPlannedSendsInsideWindow)
        assertEquals(60, window.positiveOfferInsideWindow)
        assertEquals(60, window.acceptedAckInsideWindow)
        assertEquals(0, window.acceptedAckAfterWindow)
        assertTrue(directAeronCapacityClientSucceeded(result))
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `장기 창을 넘겨 깨어나면 남은 예정 요청을 몰아 보내지 않고 미발송 원문으로 남긴다`() {
        val fixture = Fixture(60, 4096, rate = 1, sustainedSeconds = 60)
        fixture.clock.minimumParkNs = 61_000_000_000L
        var responded = false
        fixture.transport.onPoll = { receive ->
            if (!responded && fixture.transport.commands.isNotEmpty()) {
                responded = true
                receive(accepted(fixture.transport.commands.single(), 1))
                1
            } else {
                0
            }
        }
        val result = fixture.run()
        val window = directAeronCapacityWindowEvidence(result)
        assertEquals(1, fixture.transport.offers.size)
        assertEquals(59, result.notSent)
        assertEquals(0, result.resultUnknown)
        assertTrue(result.samples.drop(1).all { it.reason == "SUSTAINED_SCHEDULE_WINDOW_ENDED" && it.startNs == null })
        assertEquals(59_000_000_000L, result.samples.last().dueNs)
        assertEquals(1, window.positiveOfferInsideWindow)
        assertEquals(0, window.positiveOfferAfterWindow)
        assertFalse(window.allPlannedSendsInsideWindow)
        assertFalse(directAeronCapacityClientSucceeded(result))
        conservation(result)
    }

    @Test
    fun `장기 시계는 nanoTime Long 경계를 지나도 차분으로 창과 drain ACK를 분리한다`() {
        val fixture = Fixture(60, 4096, rate = 1, sustainedSeconds = 60)
        fixture.clock.minimumParkNs = 1_000_000_000L
        fixture.clock.now = Long.MAX_VALUE - 30_000_000_000L
        val first = fixture.clock.now
        var acknowledged = 0
        fixture.transport.onPoll = { receive ->
            val added = fixture.transport.commands.size - acknowledged
            while (acknowledged < fixture.transport.commands.size) {
                receive(accepted(fixture.transport.commands[acknowledged], acknowledged + 1L))
                acknowledged++
            }
            added
        }
        val result = fixture.load.run(first)
        assertTrue(result.windowEndNs < 0)
        assertEquals(60_000_000_000L, result.finishedNs - first)
        val late = result.samples.last().copy(ackNs = result.windowEndNs + 1, deadlineNs = result.windowEndNs + 10)
        val withDrain = result.copy(samples = result.samples.dropLast(1) + late)
        val evidence = directAeronCapacityWindowEvidence(withDrain)
        assertEquals(59, evidence.acceptedAckInsideWindow)
        assertEquals(1, evidence.acceptedAckAfterWindow)
        assertFalse(evidence.allAcceptedAckInsideWindow)
        assertTrue(evidence.allPlannedSendsInsideWindow)
        assertTrue(directAeronCapacityClientSucceeded(withDrain))
        assertFalse(directAeronCapacityClientSucceeded(withDrain.copy(resultUnknown = 1)))
    }

    @Test
    fun `장기 workload도 계획곱 고정 업무 4096 경계를 바꾸면 전송 전에 거절한다`() {
        val workload = DirectAeronCapacityWorkload(1000, 60_000, "M-", Side.BUY, "BUYER", 4096, sustainedSeconds = 60)
        assertFailsWith<IllegalArgumentException> { workload.copy(count = 60_001) }
        assertFailsWith<IllegalArgumentException> { workload.copy(sustainedSeconds = null) }
        assertFailsWith<IllegalArgumentException> { workload.copy(price = 9999) }
        assertFailsWith<IllegalArgumentException> { workload.copy(quantity = 1) }
        assertFailsWith<IllegalArgumentException> { workload.copy(side = Side.SELL) }
        assertFailsWith<IllegalArgumentException> { workload.copy(maxInFlight = 4097) }
        assertFailsWith<IllegalArgumentException> { workload.copy(rate = Int.MAX_VALUE) }
    }

    @Test
    fun `첫 ACK 전에 다음 요청을 전송하고 역순 응답으로 각 pending 자리만 회수한다`() {
        val fixture = Fixture(count = 3, maxInFlight = 2)
        var offersBeforeFirstAck = 0
        var thirdResponded = false
        fixture.transport.onPoll = { receive ->
            if (fixture.transport.commands.size >= 2 && offersBeforeFirstAck == 0) {
                offersBeforeFirstAck = fixture.transport.commands.size
                receive(accepted(fixture.transport.commands[1], 2))
                receive(accepted(fixture.transport.commands[0], 1))
                2
            } else if (!thirdResponded && fixture.transport.commands.size == 3) {
                thirdResponded = true
                receive(accepted(fixture.transport.commands[2], 3))
                1
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(2, offersBeforeFirstAck)
        assertEquals(2, result.peakInFlight)
        assertEquals(3, result.transportAccepted)
        assertEquals(3, result.accepted)
        assertEquals(0, result.resultUnknown)
        assertEquals(listOf(0L, 50_000L, 100_000L), result.samples.map { it.dueNs })
        assertEquals(listOf(1L, 2L, 3L), result.samples.map { (it.response as AdmissionResultPayload.Accepted).sequence })
        assertTrue(result.errors.isEmpty())
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `이미 기한이 된 요청도 이전 offer의 다음 poll ACK가 슬롯을 돌려주면 빠뜨리지 않는다`() {
        val fixture = Fixture(count = 3, maxInFlight = 1)
        fixture.clock.now = 125_000L
        val interactions = mutableListOf<String>()
        val offerPolls = mutableListOf<Int>()
        val ackPolls = mutableListOf<Int>()
        var acknowledged = 0
        fixture.transport.onOffer = {
            interactions.add("offer:${fixture.transport.commands.last().requestId}")
            offerPolls.add(fixture.transport.polls)
            fixture.transport.offers.size
                .toLong()
        }
        fixture.transport.onPoll = { receive ->
            if (acknowledged < fixture.transport.commands.size) {
                val command = fixture.transport.commands[acknowledged]
                interactions.add("ack:${command.requestId}")
                ackPolls.add(fixture.transport.polls)
                receive(accepted(command, acknowledged + 1L))
                acknowledged++
                1
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(3, result.accepted)
        assertEquals(0, result.notSent)
        assertEquals(0, result.resultUnknown)
        assertEquals(1, result.peakInFlight)
        assertEquals(listOf("M-0", "M-1", "M-2"), fixture.transport.commands.map { it.requestId })
        assertEquals(3, fixture.transport.offers.size)
        assertEquals(
            listOf("offer:M-0", "ack:M-0", "offer:M-1", "ack:M-1", "offer:M-2", "ack:M-2"),
            interactions,
        )
        assertEquals(listOf(1, 2, 3), offerPolls)
        assertEquals(listOf(2, 3, 4), ackPolls)
        assertEquals(listOf(0L, 50_000L, 100_000L), result.samples.map { it.dueNs })
        assertTrue(result.samples.all { it.startNs == 125_000L && it.offerAttempts == 1 && it.reason == null })
        assertTrue(result.errors.isEmpty())
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `같은 overdue 입력도 다음 poll에 ACK가 없으면 실제 pending 상한을 넘겨 보내지 않는다`() {
        val fixture = Fixture(count = 3, maxInFlight = 1)
        fixture.clock.now = 125_000L

        val result = fixture.run()

        assertEquals(listOf("M-0"), fixture.transport.commands.map { it.requestId })
        assertEquals(1, fixture.transport.offers.size)
        assertEquals(1, result.peakInFlight)
        assertEquals(0, result.accepted)
        assertEquals(1, result.resultUnknown)
        assertEquals(2, result.notSent)
        assertEquals("REQUEST_DEADLINE", result.samples.first().reason)
        assertTrue(result.samples.drop(1).all { it.reason == "GENERATOR_SATURATED" && it.startNs == null })
        assertTrue(result.samples.drop(1).all { it.encodedCommand == null && it.offerAttempts == 0 && it.firstOfferNs == null })
        assertEquals(listOf(0L, 50_000L, 100_000L), result.samples.map { it.dueNs })
        assertTrue(result.samples.all { it.ackNs == null && it.ackRttNs == null })
        assertTrue(result.errors.isEmpty())
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `상한이 찬 예정 요청은 시각을 늦춰 숨기지 않고 미발송으로 남긴다`() {
        val fixture = Fixture(count = 3, maxInFlight = 2)
        var responded = false
        fixture.transport.onPoll = { receive ->
            if (!responded && fixture.clock.now >= 150_000 && fixture.transport.commands.size == 2) {
                responded = true
                fixture.transport.commands.forEachIndexed { index, command -> receive(accepted(command, index + 1L)) }
                2
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(2, fixture.transport.commands.size)
        assertEquals(2, result.accepted)
        assertEquals(1, result.notSent)
        assertEquals(100_000L, result.samples.last().dueNs)
        assertNull(result.samples.last().startNs)
        assertEquals("GENERATOR_SATURATED", result.samples.last().reason)
        assertFalse(result.samples.last().transportAccepted!!)
        conservation(result)
    }

    @Test
    fun `4096 발생기 예산도 raw와 실제 pending은 유한한 예정 cohort를 넘지 않는다`() {
        val fixture = Fixture(count = 3, maxInFlight = 4096)
        var responded = false
        fixture.transport.onPoll = { receive ->
            if (!responded && fixture.transport.commands.size == 3) {
                responded = true
                fixture.transport.commands.forEachIndexed { index, command -> receive(accepted(command, index + 1L)) }
                3
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(4096, result.workload.maxInFlight)
        assertEquals(3, result.peakInFlight)
        assertEquals(3, result.samples.size)
        assertEquals(3, result.accepted)
        assertEquals(0, result.notSent)
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `양수 offer만 있고 ACK가 없으면 전송 수용과 결과 미확인을 분리한다`() {
        val fixture = Fixture(count = 2)

        val result = fixture.run()

        assertEquals(2, result.transportAccepted)
        assertEquals(2, result.resultUnknown)
        assertEquals(0, result.accepted)
        assertEquals(0, result.rejected)
        assertEquals(0, result.notSent)
        assertTrue(result.samples.all { it.outcome == DirectAeronCapacityOutcome.RESULT_UNKNOWN })
        assertTrue(result.samples.all { it.ackNs == null && it.ackRttNs == null && it.transportAccepted == true })
        assertTrue(result.samples.all { it.reason == "REQUEST_DEADLINE" })
        conservation(result)
    }

    @Test
    fun `backpressure와 admin action 재시도는 같은 바이트를 쓰며 양수 뒤 재전송하지 않는다`() {
        val fixture = Fixture(count = 1)
        val codes = ArrayDeque(listOf(Publication.BACK_PRESSURED, Publication.ADMIN_ACTION, 17L))
        fixture.transport.onOffer = { codes.removeFirst() }
        fixture.transport.onPoll = { receive ->
            if (fixture.transport.offers.size == 3) {
                receive(accepted(fixture.transport.commands.last(), 31))
                1
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(3, fixture.transport.offers.size)
        assertTrue(fixture.transport.polls > 3)
        fixture.transport.offers.forEach { assertContentEquals(fixture.transport.offers.first(), it) }
        assertEquals(1, result.transportAccepted)
        assertEquals(1, result.accepted)
        assertEquals(3, result.samples.single().offerAttempts)
        assertEquals(17L, result.samples.single().transportPosition)
        conservation(result)
    }

    @Test
    fun `terminal offer 세 종류는 재시도와 재연결 없이 구분하고 앞선 수용은 미확인으로 남긴다`() {
        val terminals =
            mapOf(
                Publication.NOT_CONNECTED to "NOT_CONNECTED",
                Publication.CLOSED to "CLOSED",
                Publication.MAX_POSITION_EXCEEDED to "MAX_POSITION_EXCEEDED",
            )
        for ((code, reason) in terminals) {
            val fixture = Fixture(count = 3)
            fixture.transport.onOffer = { if (fixture.transport.offers.size == 1) 11 else code }

            val result = fixture.run()

            assertEquals(2, fixture.transport.offers.size)
            assertEquals(1, result.transportAccepted)
            assertEquals(1, result.resultUnknown)
            assertEquals(2, result.notSent)
            assertEquals(code, result.samples[1].lastOfferResult)
            assertTrue(result.samples.all { it.reason == reason })
            assertTrue(result.errors.any { it == "TERMINAL_OFFER:$reason" })
            fixture.assertOwnership()
            conservation(result)
        }
    }

    @Test
    fun `deadline에 도달한 backpressure 요청을 다시 offer하지 않는다`() {
        val fixture = Fixture(count = 1)
        fixture.transport.onOffer = {
            fixture.clock.now = 1_000_000
            Publication.BACK_PRESSURED
        }

        val result = fixture.run()

        assertEquals(1, fixture.transport.offers.size)
        assertEquals(1, result.notSent)
        assertEquals(0, result.transportAccepted)
        assertEquals("REQUEST_DEADLINE", result.samples.single().reason)
        assertEquals(1_000_000L, result.samples.single().deadlineNs)
        conservation(result)
    }

    @Test
    fun `deadline 경계의 늦은 ACK 원문과 시각은 보존하지만 성공 지연으로 채우지 않는다`() {
        val fixture = Fixture(count = 1)
        fixture.transport.onPoll = { receive ->
            if (fixture.transport.commands.isNotEmpty()) {
                fixture.clock.now = 1_000_000
                receive(accepted(fixture.transport.commands.single(), 9))
                1
            } else {
                0
            }
        }

        val result = fixture.run()
        val sample = result.samples.single()

        assertEquals(1, result.resultUnknown)
        assertEquals(0, result.accepted)
        assertEquals(1_000_000L, sample.ackNs)
        assertNotNull(sample.response)
        assertNull(sample.ackRttNs)
        assertEquals("ACK_AFTER_DEADLINE", sample.reason)
        conservation(result)
    }

    @Test
    fun `응답의 네 원본 식별자 중 하나가 다르면 승인하지 않고 올바른 원본을 기다린다`() {
        val mutations: List<(AdmissionResultPayload.Accepted) -> AdmissionResultPayload.Accepted> =
            listOf(
                { it.copy(userId = "OTHER") },
                { it.copy(requestId = "OTHER") },
                { it.copy(marketId = "OTHER") },
                { it.copy(orderId = "OTHER") },
            )
        for (mutate in mutations) {
            val fixture = Fixture(count = 1)
            fixture.transport.onPoll = { receive ->
                if (fixture.transport.commands.isNotEmpty()) {
                    val response = accepted(fixture.transport.commands.single(), 7)
                    receive(mutate(response))
                    fixture.clock.now = 31
                    receive(response)
                    fixture.transport.onPoll = { 0 }
                    2
                } else {
                    0
                }
            }

            val result = fixture.run()

            assertEquals(1, result.accepted)
            assertEquals(31L, result.samples.single().ackNs)
            assertEquals(7L, (result.samples.single().response as AdmissionResultPayload.Accepted).sequence)
            assertTrue(result.errors.any { it.startsWith("RESPONSE_IDENTITY_MISMATCH:") })
            conservation(result)
        }
    }

    @Test
    fun `미전송 원본과 일치하는 응답도 양수 offer가 없으면 ACK가 아니다`() {
        val fixture = Fixture(count = 1)
        var injected = false
        fixture.transport.onPoll = { receive ->
            if (!injected) {
                injected = true
                receive(AdmissionResultPayload.Accepted("BUYER", "M-0", "BTC-USDT", "M-0", 1))
                1
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(0, result.accepted)
        assertEquals(1, result.resultUnknown)
        assertNull(result.samples.single().ackNs)
        assertTrue(result.errors.any { it.startsWith("RESPONSE_WITHOUT_POSITIVE_OFFER:") })
        conservation(result)
    }

    @Test
    fun `중복 응답은 최초 ACK를 유지하고 모순 응답과 업무 거절은 각각 보존한다`() {
        val fixture = Fixture(count = 2)
        var emitted = false
        fixture.transport.onPoll = { receive ->
            if (!emitted && fixture.transport.commands.size == 2) {
                emitted = true
                val first = fixture.transport.commands[0]
                val second = accepted(fixture.transport.commands[1], 2)
                receive(second)
                receive(
                    AdmissionResultPayload.Rejected(
                        "BUYER",
                        first.requestId,
                        "BTC-USDT",
                        first.order.orderId.value,
                        AdmissionResultPayload.Reason.DUPLICATE_ORDER,
                    ),
                )
                fixture.clock.now += 11
                receive(second)
                receive(second.copy(sequence = 99))
                4
            } else {
                0
            }
        }

        val result = fixture.run()

        assertEquals(1, result.accepted)
        assertEquals(1, result.rejected)
        assertEquals(1L, result.duplicateResponses)
        assertEquals(50_000L, result.samples[1].ackNs)
        assertEquals(2L, (result.samples[1].response as AdmissionResultPayload.Accepted).sequence)
        assertEquals(AdmissionResultPayload.Reason.DUPLICATE_ORDER, (result.samples[0].response as AdmissionResultPayload.Rejected).reason)
        assertTrue(result.errors.any { it.startsWith("RESPONSE_CONTENT_CONFLICT:") })
        conservation(result)
    }

    @Test
    fun `실제 codec으로 복원한 BUY SELL의 고정 입력과 offer ACK 시각을 그대로 보존한다`() {
        for (side in listOf(Side.BUY, Side.SELL)) {
            val fixture = Fixture(count = 1, side = side)
            fixture.transport.onOffer = {
                fixture.clock.now = 40
                17
            }
            fixture.transport.onPoll = { receive ->
                if (fixture.transport.commands.isNotEmpty()) {
                    fixture.clock.now = 130
                    receive(accepted(fixture.transport.commands.single(), 31))
                    fixture.transport.onPoll = { 0 }
                    1
                } else {
                    0
                }
            }

            val result = fixture.run()
            val sample = result.samples.single()
            val decoded = AdmissionCommandCodec().decode(assertNotNull(sample.encodedCommand))

            assertEquals(sample.command, decoded)
            assertEquals("M-0", decoded.requestId)
            assertEquals("M-0", decoded.order.orderId.value)
            assertEquals("BUYER", decoded.order.userId.value)
            assertEquals(side, decoded.order.side)
            assertEquals("LIMIT", decoded.order.orderType.name)
            assertEquals("GTC", decoded.order.timeInForce.name)
            assertEquals(10_000L, decoded.order.price.value)
            assertEquals(100_000_000L, decoded.order.quantity.value)
            assertEquals(fixture.config.market, decoded.market)
            assertEquals(fixture.config.feePolicySnapshot, decoded.feePolicySnapshot)
            assertEquals(0L, sample.dueNs)
            assertEquals(0L, sample.startNs)
            assertEquals(0L, sample.firstOfferNs)
            assertEquals(40L, sample.transportAcceptedNs)
            assertEquals(130L, sample.ackNs)
            assertEquals(130L, sample.ackRttNs)
            assertEquals(130L, sample.scheduledAckLatencyNs)
            fixture.assertOwnership()
            conservation(result)
        }
    }

    @Test
    fun `offer 예외는 미발송으로 단정하지 않고 이전 수용과 현재 전송 불확인을 보존한다`() {
        val fixture = Fixture(count = 2)
        fixture.transport.onOffer = {
            if (fixture.transport.offers.size == 1) 11 else throw IllegalStateException("offer boundary failed")
        }

        val result = fixture.run()

        assertEquals(1, result.transportAccepted)
        assertEquals(1, result.offerUnknown)
        assertEquals(2, result.resultUnknown)
        assertEquals(0, result.notSent)
        assertEquals(DirectAeronCapacityOutcome.RESULT_UNKNOWN, result.samples[0].outcome)
        assertEquals(DirectAeronCapacityOutcome.OFFER_UNKNOWN, result.samples[1].outcome)
        assertNull(result.samples[1].transportAccepted)
        assertTrue(assertNotNull(result.loadFailure).contains("offer boundary failed"))
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `poll과 close의 두 예외를 모두 남기며 이미 수용한 요청의 raw를 잃지 않는다`() {
        val fixture = Fixture(count = 2)
        fixture.transport.onPoll = {
            if (fixture.transport.commands.isNotEmpty()) throw IllegalStateException("poll failed") else 0
        }
        fixture.transport.closeFailure = IllegalStateException("close failed")

        val result = fixture.run()

        assertEquals(1, result.transportAccepted)
        assertEquals(1, result.resultUnknown)
        assertEquals(1, result.notSent)
        assertEquals(2, result.samples.size)
        assertTrue(assertNotNull(result.loadFailure).contains("poll failed"))
        assertTrue(assertNotNull(result.closeFailure).contains("close failed"))
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `factory 실패는 모든 예정 원본을 미발송으로 남기고 재실행하지 않는다`() {
        val fixture = Fixture(count = 2)
        var factories = 0
        val load =
            DirectAeronCapacityLoad(
                fixture.config,
                fixture.workload,
                openTransport = {
                    factories++
                    throw IllegalStateException("connect failed")
                },
                nanoTime = { fixture.clock.now },
                parkNanos = fixture.clock::advance,
            )

        val result = load.run(0)

        assertEquals(1, factories)
        assertEquals(2, result.notSent)
        assertEquals(listOf("M-0", "M-1"), result.samples.map { it.command.requestId })
        assertTrue(result.samples.all { it.encodedCommand == null && it.transportAccepted == false })
        assertTrue(assertNotNull(result.loadFailure).contains("connect failed"))
        assertFailsWith<IllegalStateException> { load.run(0) }
        assertEquals(1, factories)
        assertEquals(0, fixture.transport.closed)
        conservation(result)
    }

    @Test
    fun `입력 raw와 pending의 상한 및 등록 마켓을 전송 전에 검증한다`() {
        val workload = DirectAeronCapacityWorkload(1, 2000, "M-", Side.BUY, "BUYER", maxInFlight = 4096)
        assertEquals(2000, workload.count)
        assertEquals(20_000, workload.copy(rate = 20_000, count = 1, maxInFlight = 1).rate)
        for (rate in listOf(0, 20_001)) assertFailsWith<IllegalArgumentException> { workload.copy(rate = rate) }
        for (count in listOf(0, 2001)) assertFailsWith<IllegalArgumentException> { workload.copy(count = count) }
        for (inFlight in listOf(0, 4097)) assertFailsWith<IllegalArgumentException> { workload.copy(maxInFlight = inFlight) }
        val fixture = Fixture(count = 1)
        val load =
            DirectAeronCapacityLoad(fixture.config, workload.copy(marketId = MarketId("OTHER")), openTransport = { fixture.transport })
        assertFailsWith<IllegalArgumentException> { load.run(0) }
        assertTrue(fixture.transport.owners.isEmpty())
    }

    @Test
    fun `timeout prefix와 deadline ACK를 제거한 뒤 새 요청은 남은 offer 원문 뒤에서 진행한다`() {
        val fixture = Fixture(count = 5, maxInFlight = 4)
        fixture.clock.now = 150_000
        var middleOffers = 0
        var lateEmitted = false
        var lastEmitted = false
        fixture.transport.onOffer = {
            if (fixture.transport.commands
                    .last()
                    .requestId == "M-2"
            ) {
                middleOffers++
                if (middleOffers == 2) fixture.clock.now = 1_150_000
                Publication.BACK_PRESSURED
            } else {
                17
            }
        }
        fixture.transport.onPoll = { receive ->
            when {
                !lateEmitted && middleOffers == 2 -> {
                    lateEmitted = true
                    receive(accepted(fixture.transport.commands.first(), 1))
                    1
                }

                middleOffers == 1 -> {
                    // ACK 없는 transport 진행도 work다. 두 cadence 모두 deadline 직후 park로 시각을 늦추지 않는다.
                    1
                }

                !lastEmitted && fixture.transport.commands
                    .lastOrNull()
                    ?.requestId == "M-4" -> {
                    lastEmitted = true
                    receive(accepted(fixture.transport.commands.last(), 5))
                    1
                }

                else -> {
                    0
                }
            }
        }

        val result = fixture.run()

        assertEquals(listOf("M-0", "M-1", "M-2", "M-2", "M-4"), fixture.transport.commands.map { it.requestId })
        assertEquals(2, middleOffers)
        assertContentEquals(fixture.transport.offers[2], fixture.transport.offers[3])
        assertEquals(1, result.accepted)
        assertEquals(2, result.resultUnknown)
        assertEquals(2, result.notSent)
        assertEquals(4, result.peakInFlight)
        assertEquals("ACK_AFTER_DEADLINE", result.samples[0].reason)
        assertEquals(1_150_000L, result.samples[0].ackNs)
        assertNull(result.samples[0].ackRttNs)
        assertEquals("REQUEST_DEADLINE", result.samples[1].reason)
        assertEquals("REQUEST_DEADLINE", result.samples[2].reason)
        assertEquals("REQUEST_DEADLINE", result.samples[3].reason)
        assertEquals(0, result.samples[3].offerAttempts)
        assertEquals(1_150_000L, result.samples[4].startNs)
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `backpressure head는 다음 poll에서 같은 바이트로 재시도하고 후속 원본의 순서를 유지한다`() {
        val fixture = Fixture(count = 3, maxInFlight = 3)
        fixture.clock.now = 100_000
        val pollsAtOffers = mutableListOf<Int>()
        val positive = linkedSetOf<String>()
        val acknowledged = mutableSetOf<String>()
        fixture.transport.onOffer = {
            pollsAtOffers.add(fixture.transport.polls)
            val id =
                fixture.transport.commands
                    .last()
                    .requestId
            if (fixture.transport.commands.size == 1) {
                Publication.BACK_PRESSURED
            } else {
                positive.add(id)
                17
            }
        }
        fixture.transport.onPoll = { receive ->
            var added = 0
            for (id in positive) {
                if (acknowledged.add(id)) {
                    val command = fixture.transport.commands.first { it.requestId == id }
                    receive(accepted(command, id.removePrefix("M-").toLong() + 1))
                    added++
                }
            }
            added
        }

        val result = fixture.run()

        assertEquals(listOf("M-0", "M-0", "M-1", "M-2"), fixture.transport.commands.map { it.requestId })
        assertTrue(pollsAtOffers[1] > pollsAtOffers[0])
        assertContentEquals(fixture.transport.offers[0], fixture.transport.offers[1])
        assertEquals(listOf(2, 1, 1), result.samples.map { it.offerAttempts })
        assertEquals(3, result.accepted)
        assertEquals(0, result.notSent)
        assertEquals(0, result.resultUnknown)
        assertTrue(result.errors.isEmpty())
        fixture.assertOwnership()
        conservation(result)
    }

    @Test
    fun `장기 창 직전 시작한 마지막 원본도 offer 시각이 창 끝이면 미시도로 남긴다`() {
        val fixture = Fixture(60, 4096, rate = 1, sustainedSeconds = 60)
        fixture.clock.minimumParkNs = 1_000_000_000L
        val end = 60_000_000_000L
        val lastStart = end - 302_390L
        var acknowledged = 0
        var movedToTail = false
        var tailReads = 0
        fixture.transport.onPoll = { receive ->
            val added = fixture.transport.commands.size - acknowledged
            while (acknowledged < fixture.transport.commands.size) {
                receive(accepted(fixture.transport.commands[acknowledged], acknowledged + 1L))
                acknowledged++
            }
            if (!movedToTail && acknowledged == 59) {
                movedToTail = true
                fixture.clock.now = lastStart
            }
            added
        }
        val load =
            DirectAeronCapacityLoad(
                fixture.config,
                fixture.workload,
                openTransport = { fixture.transport },
                nanoTime = {
                    if (fixture.clock.now == lastStart && ++tailReads == 2) fixture.clock.now = end
                    fixture.clock.now
                },
                parkNanos = fixture.clock::advance,
            )

        val result = load.run(0)
        val last = result.samples.last()
        val window = directAeronCapacityWindowEvidence(result)

        assertEquals(59, result.accepted)
        assertEquals(1, result.notSent)
        assertEquals(lastStart, last.startNs)
        assertEquals(0, last.offerAttempts)
        assertNull(last.firstOfferNs)
        assertNull(last.transportAcceptedNs)
        assertEquals("SUSTAINED_OFFER_WINDOW_ENDED", last.reason)
        assertEquals(59, fixture.transport.offers.size)
        assertEquals(0, window.positiveOfferAfterWindow)
        assertFalse(window.allPlannedSendsInsideWindow)
        assertFalse(directAeronCapacityClientSucceeded(result))
        assertEquals(1, fixture.transport.closed)
        conservation(result)
    }

    private fun accepted(
        command: AdmissionCommandPayload,
        sequence: Long,
    ): AdmissionResultPayload.Accepted =
        AdmissionResultPayload.Accepted(
            command.order.userId.value,
            command.requestId,
            command.order.marketId.value,
            command.order.orderId.value,
            sequence,
        )

    private fun conservation(result: DirectAeronCapacityResult) {
        assertEquals(result.planned, result.notSent + result.resultUnknown + result.accepted + result.rejected)
        assertEquals(result.transportAccepted, result.accepted + result.rejected + result.resultUnknown - result.offerUnknown)
        assertEquals(result.planned, result.samples.size)
        assertEquals(
            result.planned,
            result.samples
                .map { it.command.requestId }
                .distinct()
                .size,
        )
        assertTrue(result.samples.size <= if (result.workload.sustained) DIRECT_AERON_SUSTAINED_MAX_ORDERS else 2000)
        assertTrue(result.peakInFlight <= result.workload.maxInFlight)
    }

    private inner class Fixture(
        count: Int,
        maxInFlight: Int = 256,
        side: Side = Side.BUY,
        rate: Int = 20_000,
        sustainedSeconds: Int? = null,
    ) {
        val clock = Clock()
        val workload = DirectAeronCapacityWorkload(rate, count, "M-", side, "BUYER", maxInFlight, sustainedSeconds = sustainedSeconds)
        val config = DevelopmentFundingSettings(directory.resolve("direct-client"), resultTimeoutMs = 1)
        val transport = ScriptedTransport()
        var factories = 0
        val load =
            DirectAeronCapacityLoad(
                config,
                workload,
                openTransport = {
                    factories++
                    transport.owners.add(Thread.currentThread().id)
                    transport
                },
                nanoTime = { clock.now },
                parkNanos = clock::advance,
            )

        fun run(): DirectAeronCapacityResult = load.run(0)

        fun assertOwnership() {
            assertEquals(1, factories)
            assertEquals(1, transport.closed)
            assertEquals(setOf(Thread.currentThread().id), transport.owners.toSet())
        }
    }

    private class Clock {
        var now = 0L
        var minimumParkNs = 0L

        fun advance(ns: Long) {
            require(ns > 0)
            now += maxOf(ns, minimumParkNs)
        }
    }

    private class ScriptedTransport : GatewayOrderTransport {
        val offers = mutableListOf<ByteArray>()
        val commands = mutableListOf<AdmissionCommandPayload>()
        val owners = mutableListOf<Long>()
        var polls = 0
        var closed = 0
        var closeFailure: Exception? = null
        var onOffer: (ByteArray) -> Long = { offers.size.toLong() }
        var onPoll: ((AdmissionResultPayload) -> Unit) -> Int = { 0 }

        override fun offer(bytes: ByteArray): Long {
            owners.add(Thread.currentThread().id)
            offers.add(bytes.copyOf())
            commands.add(AdmissionCommandCodec().decode(bytes))
            return onOffer(bytes)
        }

        override fun poll(receive: (AdmissionResultPayload) -> Unit): Int {
            owners.add(Thread.currentThread().id)
            polls++
            return onPoll(receive)
        }

        override fun close() {
            owners.add(Thread.currentThread().id)
            closed++
            closeFailure?.let { throw it }
        }
    }
}

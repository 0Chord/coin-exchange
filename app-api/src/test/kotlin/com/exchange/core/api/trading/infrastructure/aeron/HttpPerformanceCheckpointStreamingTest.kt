package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real small TradingState fixtures. No Aeron/HTTP process, large artifact or load is used. */
class HttpPerformanceCheckpointStreamingTest {
    @TempDir
    lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val time = Instant.parse("2026-10-27T00:00:00.123456789Z")
    private val market = DevelopmentMarketContext.BTC_USDT.market
    private val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(1000), FeeRate(2000)))
    private val markerSummary =
        mapOf(
            "offered" to 3L,
            "sentCount" to 3,
            "sentMarkers" to emptyList<Any>(),
            "failures" to emptyList<String>(),
        )
    private val legacyTrading =
        Class
            .forName("com.exchange.core.api.trading.infrastructure.aeron.HttpPerformanceNodeKt")
            .getDeclaredMethod(
                "performanceCheckpointTradingEvidence",
                DevelopmentFundingAdapter::class.java,
                TradingState.Snapshot::class.java,
            ).apply { isAccessible = true }

    @Test
    fun `부분 체결 대기 거절 수수료와 비영점 nano를 포함한 전체 JSON이 기존 출력과 같다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot)
        val capture = fixture.capture(observation, snapshot)
        val expected = legacyTree(fixture, observation, snapshot)
        val actual = writtenTree(capture)

        assertEquals(expected, actual)
        assertEquals(
            1,
            actual
                .path("applied")
                .path("state")
                .path("pendingOrders")
                .size(),
        )
        assertEquals(
            2,
            actual
                .path("trading")
                .path("books")
                .single()
                .path("orders")
                .size(),
        )
        assertEquals(
            1,
            actual.path("applied").path("state").path("requests").count {
                it.path("result").path("outcome").asString() ==
                    "REJECTED"
            },
        )
        val request =
            actual
                .path("applied")
                .path("state")
                .path("requests")
                .first()
        assertEquals(123456789, request.path("occurredAtNano").asInt())
        val completion = actual.path("trading").path("completions").single { it.path("orderId").asString() == "M-0" }
        val matching = actual.path("trading").path("matchingResults").single { it.path("orderId").asString() == "M-0" }
        assertEquals(
            "SELLER",
            completion
                .path("events")
                .single()
                .path("makerUserId")
                .asString(),
        )
        assertTrue(
            matching
                .path("events")
                .single()
                .path("makerUserId")
                .isMissingNode,
        )
        assertTrue(
            matching
                .path("events")
                .single()
                .path("side")
                .isMissingNode,
        )
        val settlement = completion.path("settlements").single()
        assertEquals(10L, settlement.path("makerPlan").path("actualFeeAmount").asLong())
        assertEquals(20L, settlement.path("takerPlan").path("actualFeeAmount").asLong())
    }

    @Test
    fun `캡처 후 업무와 collector 원본이 바뀌어도 다른 스레드 writer는 앞선 상태와 주문장만 쓴다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot)
        val capture = fixture.capture(observation, snapshot)
        val expected = legacyTree(fixture, observation, snapshot)
        assertSame(snapshot, capture.tradingSnapshot)
        assertSame(snapshot.completions.first(), capture.tradingSnapshot.completions.first())

        fixture.state.executePending(fixture.pending.record.sequence, time.plusSeconds(1))
        fixture.submit("M-LATER", "BUYER", Side.BUY)
        fixture.fund("LATER", "USDT", 100)
        fixture.timing.observerNs = 9999
        fixture.timing.phaseDurationsNs[TradingNodePhase.CALLBACK.ordinal] = 9999
        fixture.rawTimings.clear()
        assertTrue(fixture.state.book(market.marketId).isEmpty())
        val actual = CompletableFuture.supplyAsync { writtenTree(capture) }.get(5, TimeUnit.SECONDS)

        assertEquals(expected, actual)
        assertEquals(
            2,
            actual
                .path("trading")
                .path("books")
                .single()
                .path("orders")
                .size(),
        )
        assertEquals(3, capture.tradingSnapshot.completions.size)
        assertEquals(
            11L,
            actual
                .path("callbackTimings")
                .path("raw")
                .single()
                .path("phaseDurationsNs")[TradingNodePhase.CALLBACK.ordinal]
                .asLong(),
        )
        assertEquals(2, capture.tradingSnapshot.preparations.size)
        assertEquals(
            7L,
            actual
                .path("callbackTimings")
                .path("raw")
                .single()
                .path("observerNs")
                .asLong(),
        )
        assertFailsWith<UnsupportedOperationException> { (capture.books as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (capture.books.first().orders as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (capture.callbackTimings as MutableMap)["raw"] = emptyList<Any>() }
    }

    @Test
    fun `관찰 자체가 없으면 applied null을 유지하고 trading fallback만 순차 출력한다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val actual = writtenTree(fixture.capture(null, snapshot))

        assertEquals(legacyTree(fixture, null, snapshot), actual)
        assertTrue(actual.path("applied").isNull)
        assertEquals(3, actual.path("trading").path("completions").size())
    }

    @Test
    fun `관찰의 snapshot null을 fallback trading snapshot으로 바꾸지 않는다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot).copy(snapshot = null)
        val actual = writtenTree(fixture.capture(observation, snapshot))

        assertEquals(legacyTree(fixture, observation, snapshot), actual)
        assertTrue(actual.path("applied").path("state").isNull)
        assertEquals(3, actual.path("trading").path("completions").size())
    }

    @Test
    fun `기존 blocked projection은 정상 배열을 채우지 않고 matching 결과만 함께 보존한다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot)
        // Only the diagnostic flag is stubbed; all financial records come from the real completed fixture.
        val blockedState = spy(fixture.state)
        doReturn(true).`when`(blockedState).isBlocked
        val blockedService = DevelopmentFundingAdapter(blockedState, onFatal = { throw it })
        val capture =
            captureHttpPerformanceCheckpoint(
                "streaming-test",
                0,
                fixture.config,
                blockedService,
                "final",
                observation,
                snapshot,
                3,
                fixture.callbacks(),
                null,
                0,
            )
        val actual = writtenTree(capture)

        assertEquals(legacyTree(fixture, observation, snapshot, blockedService), actual)
        assertTrue(capture.tradingBlocked)
        assertTrue(capture.books.isEmpty())
        assertTrue(actual.path("trading").path("blocked").asBoolean())
        assertTrue(actual.path("trading").path("currentReservations").isMissingNode)
        assertTrue(actual.path("trading").path("books").isMissingNode)
        assertTrue(actual.path("trading").path("completions").isMissingNode)
        assertEquals(3, actual.path("trading").path("matchingResults").size())
    }

    @Test
    fun `streaming publish도 기존 참조 읽기와 원문 보호 계약을 지킨다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot)
        val capture = fixture.capture(observation, snapshot)
        val reference = HttpPerformanceCheckpointFiles.publish(directory, capture, markerSummary, "FOLLOWER")
        val json = mapper.valueToTree<JsonNode>(reference)
        val actual = HttpPerformanceCheckpointFiles.read(directory, json, "streaming-test", 0, "final")

        assertEquals(legacyTree(fixture, observation, snapshot), actual)
        assertTrue(mapper.writeValueAsString(reference).length < 1024)
        assertEquals(Files.size(directory.resolve("node0-checkpoint-final.json")), json.path("artifactBytes").asLong())
        assertFailsWith<IllegalArgumentException> { HttpPerformanceCheckpointFiles.publish(directory, capture, markerSummary, "FOLLOWER") }
    }

    @Test
    fun `뒤쪽 부가 필드 직렬화가 실패하면 원문과 참조를 공개하지 않고 임시 파일을 정리한다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val capture = fixture.capture(fixture.observation(snapshot), snapshot)
        val failure =
            assertFailsWith<Exception> {
                HttpPerformanceCheckpointFiles.publish(directory, capture, mapOf("late" to FailingMarker()), "FOLLOWER")
            }

        assertTrue(failure.stackTraceToString().contains("late checkpoint failure"))
        assertFalse(Files.exists(directory.resolve("node0-checkpoint-final.json")))
        Files.list(directory).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    @Test
    fun `빈 실제 상태도 모든 빈 배열과 null 필드를 기존 schema 그대로 보존한다`() {
        val fixture = Fixture()
        val snapshot = fixture.state.snapshot()
        val observation = FundingObservation(0, snapshot, null)
        val actual = writtenTree(fixture.capture(observation, snapshot))

        assertEquals(legacyTree(fixture, observation, snapshot), actual)
        assertEquals(
            0,
            actual
                .path("applied")
                .path("state")
                .path("requests")
                .size(),
        )
        assertEquals(0, actual.path("trading").path("books").size())
        assertEquals(0, actual.path("trading").path("matchingResults").size())
        assertTrue(actual.path("applied").path("result").isNull)
    }

    @Test
    fun `최상위 collector 식별자와 applied config 식별자를 각각 기존 의미대로 보존한다`() {
        val fixture = populatedFixture()
        val snapshot = fixture.state.snapshot()
        val observation = fixture.observation(snapshot)
        val config = fixture.config.copy(runId = null)
        val capture =
            captureHttpPerformanceCheckpoint(
                "collector-run",
                2,
                config,
                fixture.service,
                "final",
                observation,
                snapshot,
                3,
                fixture.callbacks(),
                null,
                0,
            )
        val actual = writtenTree(capture)

        assertEquals(legacyTree(fixture, observation, snapshot, config = config, runId = "collector-run", memberId = 2), actual)
        assertEquals("collector-run", actual.path("runId").asString())
        assertEquals(2, actual.path("memberId").asInt())
        assertTrue(actual.path("applied").path("runId").isNull)
        assertEquals(0, actual.path("applied").path("memberId").asInt())
    }

    class FailingMarker {
        val value: String get() = error("late checkpoint failure")
    }

    private fun populatedFixture(): Fixture =
        Fixture().apply {
            fund("SELLER", "BTC", 300_000_000)
            fund("BUYER", "USDT", 1_000_000)
            submit("S-0", "SELLER", Side.SELL, 200_000_000)
            submit("S-1", "SELLER", Side.SELL)
            submit("M-0", "BUYER", Side.BUY)
            assertIs<OrderAdmissionState.Result.Rejected>(admit("R-POOR", "POOR", Side.BUY))
            pending = assertIs<OrderAdmissionState.Result.Accepted>(admit("PENDING", "BUYER", Side.BUY))
        }

    private inner class Fixture {
        val state = tradingState()
        val service = DevelopmentFundingAdapter(state, onFatal = { throw it })
        val config = DevelopmentFundingSettings(directory.resolve("node"), runId = "streaming-test")
        val timing =
            HttpPerformanceCallbackTiming(
                "EXECUTION",
                "BUYER",
                "M-0",
                3,
                1024,
                11,
                7,
                5,
                phases = mapOf("callback" to 11L, "decode" to 2L, "execution_total" to 9L),
                phaseDurationsNs =
                    LongArray(TradingNodePhase.entries.size) { -1 }.apply {
                        this[TradingNodePhase.CALLBACK.ordinal] = 11
                        this[TradingNodePhase.DECODE.ordinal] = 2
                        this[TradingNodePhase.EXECUTION_TOTAL.ordinal] = 9
                    },
                replyStatus = "EXECUTED",
                callbackStartedNs = 1_000,
                callbackCompletedNs = 1_011,
            )
        val rawTimings = mutableListOf(timing)
        lateinit var pending: OrderAdmissionState.Result.Accepted

        fun fund(
            user: String,
            asset: String,
            amount: Long,
        ) {
            state.prepareDevelopmentBalance(
                OpeningBalance("P-$user-$asset", UserId(user), AssetId(asset), Amount(amount)),
                "OPEN-$user-$asset",
                time,
            )
        }

        fun admit(
            id: String,
            user: String,
            side: Side,
            quantity: Long = 100_000_000,
        ): OrderAdmissionState.Result =
            state.admit(
                id,
                OrderAdmissionInput(
                    market.marketId,
                    OrderId(id),
                    UserId(user),
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    Price(10_000),
                    Quantity(quantity),
                ),
                market,
                policy,
                "RESERVE-$id",
                time,
            )

        fun submit(
            id: String,
            user: String,
            side: Side,
            quantity: Long = 100_000_000,
        ) {
            val accepted = assertIs<OrderAdmissionState.Result.Accepted>(admit(id, user, side, quantity))
            state.executePending(accepted.record.sequence, time)
        }

        fun observation(snapshot: TradingState.Snapshot): FundingObservation =
            FundingObservation(
                2048,
                snapshot,
                null,
                admissionResult =
                    AdmissionResultPayload.Accepted(
                        "BUYER",
                        "PENDING",
                        market.marketId.value,
                        "PENDING",
                        pending.record.sequence,
                    ),
                appliedRole = "FOLLOWER",
                replyStatus = "OFFERED",
            )

        fun callbacks(): Map<String, Any?> = mapOf("raw" to rawTimings, "detailedTracing" to true, "pendingAdmissionTimingCount" to 1)

        fun capture(
            observation: FundingObservation?,
            snapshot: TradingState.Snapshot,
        ): HttpPerformanceCheckpointCapture =
            captureHttpPerformanceCheckpoint("streaming-test", 0, config, service, "final", observation, snapshot, 3, callbacks(), null, 0)
    }

    private fun legacyTree(
        fixture: Fixture,
        observation: FundingObservation?,
        snapshot: TradingState.Snapshot,
        service: DevelopmentFundingAdapter = fixture.service,
        config: DevelopmentFundingSettings = fixture.config,
        runId: String = "streaming-test",
        memberId: Int = 0,
    ): JsonNode =
        mapper.readTree(
            mapper.writeValueAsBytes(
                mapOf(
                    "kind" to "PERFORMANCE_CHECKPOINT",
                    "runId" to runId,
                    "memberId" to memberId,
                    "label" to "final",
                    "applied" to observation?.let { fundingAppliedFields(config, it) },
                    "trading" to legacyTrading.invoke(null, service, snapshot),
                    "emittedMarkerCount" to 3,
                    "callbackTimings" to fixture.callbacks(),
                    "collectorFailure" to null,
                    "roleChangeCount" to 0L,
                    "markerSummary" to markerSummary,
                    "role" to "FOLLOWER",
                    "runtimeCounters" to null,
                ),
            ),
        )

    private fun writtenTree(capture: HttpPerformanceCheckpointCapture): JsonNode =
        ByteArrayOutputStream().use { output ->
            HttpPerformanceCheckpointStreaming.write(output, capture, markerSummary, "FOLLOWER")
            mapper.readTree(output.toByteArray())
        }
}

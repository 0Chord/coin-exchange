package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 기존 유한 실행기를 재사용해 실제 자금3+매칭3 JVM과 각 Archive의 취소 복구를 검사한다. */
class DockerSplitCancellationTest {
    @Test
    fun `C01 C09 C10 C11 실제 취소와 반환은 앞결과를 기다리고 양쪽 snapshot tail 재시작을 견딘다`() {
        val verification =
            DockerFundsOrderDeliveryVerification("cancellation") { v ->
                v.tool("admit", "buyer", "1", "100", "3")
                val initial = CancellationExpected()
                v.expectCancellation("C01 신규300 예약과 첫 결과를 여섯 사본에서 확인", initial)
                v.tool("snapshot", "funds")
                v.tool("snapshot", "matching")
                val snapshots =
                    v.awaitStates("C10 두 역할의 실제 Archive snapshot 완료", 45_000) { states ->
                        initial.matches(states) &&
                            states.values.all { v.number(it, "snapshotCount") > 0 && v.number(it, "lastSnapshotPosition") > 0 }
                    }

                v.holdCancellation("matching-acks")
                v.holdCancellation("results")
                v.holdCancellation("result-records")
                v.tool("admit", "seller", "1", "100", "1")
                v.awaitStates("C09 매칭은100 체결했지만 자금은 앞결과를 아직 받지 않음", 45_000) { states ->
                    states.all { (name, state) ->
                        if (name.startsWith("funds")) {
                            state.path("funds").path("resultThrough").asLong() == 1L
                        } else {
                            state.path("matching").path("appliedThrough").asLong() == 2L
                        }
                    }
                }
                val accepted = v.cancellationReply("cancel", "buyer", "2", "1")
                assertEquals("ACCEPTED", accepted.path("code").asString())
                assertEquals(3L, accepted.path("inputSequence").asLong())
                assertTrue(accepted.path("cancelId").asString().startsWith("C2"))
                assertEquals(accepted, v.cancellationReply("cancel", "buyer", "2", "1"))
                val pending =
                    CancellationExpected(
                        inputs = 3,
                        fundsThrough = 1,
                        matchingAck = 1,
                        settled = 1,
                        sellerX = 2,
                        sellerHold = 1,
                        live = 2,
                        quantity = 4,
                        pendingOrders = 1,
                        dispatches = 2,
                        book = 0,
                        bookQuantity = 0,
                        engine = 3,
                        admissions = 2,
                        cancellations = 1,
                        pendingCancellations = 1,
                    )
                val beforeRestart = v.expectCancellation("C01 취소 적용 뒤에도 앞선 정산 전에는 예약300 유지", pending)
                val cancelBytes = Files.readAllBytes(v.control.resolve("dispatch-3.bin"))
                val cancellation = SplitOrderDeliveryCodec(2).decode(cancelBytes).command as SplitOrderDeliveryCommand.Apply
                assertTrue(cancellation.input.isCancellation)
                val runtime = v.mapper.readTree(Files.readString(v.control.resolve("runtime-funds0.json")))
                val mtu = v.number(runtime, "mtuLength")
                assertTrue(cancelBytes.size <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
                v.checkpoints +=
                    mapOf(
                        "case" to "wire2 실제 취소 원본의 크기와 세 매칭 사본 적용",
                        "inputFrameBytes" to cancelBytes.size,
                        "mtuLength" to mtu,
                        "expectedFragments" to (cancelBytes.size + 32L + mtu - 33L) / (mtu - 32L),
                        "matchingCopies" to beforeRestart.filterKeys { it.startsWith("matching") },
                    )
                v.awaitFile(v.control.resolve("retained-result-3.bin"), 30_000)
                val gap = v.cancellationReply("deliver-result", "3")
                assertEquals("GAP", gap.path("code").asString())
                assertEquals(2L, gap.path("expectedSequence").asLong())
                assertTrue(gap.path("through").isNull)
                v.expectCancellation("C09 실제 취소 결과3의 GAP는 반환과 결과 경계를 바꾸지 않음", pending)
                assertTrue(
                    beforeRestart.all { (name, state) ->
                        v.number(state, "logPosition") >
                            v.number(snapshots.getValue(name), "lastSnapshotPosition")
                    },
                )

                v.restartResults("C10 양쪽 snapshot 이후의 신규와 취소 tail을 복원", true, pending::matches)
                assertEquals(accepted, v.cancellationReply("cancel", "buyer", "2", "1"))
                v.tool("replay-order", "3")
                v.expectCancellation("C10 재전달한 취소가 엔진 순번과 의무를 다시 만들지 않음", pending)
                v.releaseCancellation("matching-acks")
                v.releaseCancellation("results")
                val returned =
                    pending.copy(
                        fundsThrough = 3,
                        matchingAck = 3,
                        buyerQ = 900,
                        buyerHold = 0,
                        buyerX = 1,
                        sellerQ = 100,
                        sellerHold = 0,
                        live = 0,
                        quantity = 0,
                        pendingOrders = 0,
                        dispatches = 0,
                        pendingCancellations = 0,
                        trades = 1,
                    )
                val paid = v.expectCancellation("C01 체결100 정산 후 남은200만 반환해900과 X1 보존", returned)
                val originalReceipts =
                    paid.filterKeys { it.startsWith("funds") }.mapValues { (_, state) ->
                        state.path("funds").path("receipts")
                    }
                assertEquals("DUPLICATE", v.cancellationReply("replay-result", "3").path("code").asString())
                v.expectCancellation("C11 같은 취소 결과 재전달은 반환을 추가하지 않음", returned)
                v.restartResults("C11 반환 완료와 유실된 정산 ACK를 같은 snapshot tail에서 복원", true, returned::matches)
                assertEquals("DUPLICATE", v.cancellationReply("replay-result", "3").path("code").asString())
                assertEquals(accepted, v.cancellationReply("cancel", "buyer", "2", "1"))
                v.releaseCancellation("result-records")
                val completed = returned.copy(settled = 3)
                v.expectCancellation("C11 정산 ACK 확정은 보관 결과를 회수하고 금융 상태를 보존", completed)
                v.restartResults("C11 같은 snapshot과 ACK tail 재시작은 회수 결과를 되살리지 않음", true, completed::matches)
                val final = v.expectCancellation("C11 반복 재시작 뒤에도 최초 정산 시각과 반환은 한 번", completed)
                assertTrue(originalReceipts.all { (name, expected) -> final.getValue(name).path("funds").path("receipts") == expected })
                val boundary = v.cancellationReply("frame-boundary")
                assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES, boundary.path("frameBytes").asInt())
                assertTrue(boundary.path("positiveOfferPosition").asLong() > 0)
                assertEquals(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES + 1, boundary.path("maxPlusOneBytes").asInt())
                assertTrue(boundary.path("maxPlusOneRejected").asBoolean())
                val afterBoundary =
                    v.awaitStates("wire2 최대 실제 frame 수신과 MAX 초과 거절은 여섯 금융 사본을 보존", 45_000) { states ->
                        completed.matches(states) &&
                            states.all { (name, state) ->
                                val role = if (name.startsWith("funds")) "funds" else "matching"
                                state.path(role) == final.getValue(name).path(role) &&
                                    (role != "funds" || v.number(state, "logPosition") > v.number(final.getValue(name), "logPosition"))
                            }
                    }
                v.checkpoints += mapOf("case" to "wire2 실제 MAX frame과 MAX+1", "reply" to boundary, "states" to afterBoundary)
            }
        assertTrue(verification.run(), "${verification.failures.joinToString("\n")}\n보고서: ${verification.reports}")
    }
}

/** 금융 기대값은 입력 시나리오의 수기 산식1000-300+200=900과 체결100에서 정한다. */
private data class CancellationExpected(
    val inputs: Long = 1,
    val fundsThrough: Long = 1,
    val matchingAck: Long = 1,
    val settled: Long = 1,
    val buyerQ: Long = 700,
    val buyerHold: Long = 300,
    val buyerX: Long = 0,
    val sellerQ: Long = 0,
    val sellerX: Long = 3,
    val sellerHold: Long = 0,
    val live: Long = 1,
    val quantity: Long = 3,
    val pendingOrders: Long = 0,
    val dispatches: Long = 0,
    val book: Long = 1,
    val bookQuantity: Long = 3,
    val engine: Long = 1,
    val admissions: Long = 1,
    val cancellations: Long = 0,
    val pendingCancellations: Long = 0,
    val trades: Long = 0,
) {
    fun matches(states: Map<String, JsonNode>): Boolean =
        states.size == 6 &&
            states.all { (name, state) ->
                state.path("initialized").asBoolean() &&
                    if (name.startsWith("funds")) {
                        fields(
                            state.path("funds"),
                            mapOf(
                                "buyerQ" to buyerQ,
                                "buyerQHold" to buyerHold,
                                "buyerX" to buyerX,
                                "sellerQ" to sellerQ,
                                "sellerX" to sellerX,
                                "sellerXHold" to sellerHold,
                                "liveOrders" to live,
                                "activeOrders" to live,
                                "remainingQuantity" to quantity,
                                "pendingOrders" to pendingOrders,
                                "dispatchEntries" to dispatches,
                                "issued" to inputs,
                                "matchingAppliedThrough" to matchingAck,
                                "resultThrough" to fundsThrough,
                                "admissionIssued" to admissions,
                                "cancellationIssued" to cancellations,
                                "pendingCancellations" to pendingCancellations,
                                "settlementTrades" to trades,
                                "feeQ" to 0L,
                            ),
                        ) && (dispatches != 0L || state.path("funds").path("dispatchBytes").asLong() == 0L)
                    } else {
                        fields(
                            state.path("matching"),
                            mapOf(
                                "bookOrders" to book,
                                "bookQuantity" to bookQuantity,
                                "activeOrders" to book,
                                "engineSequence" to engine,
                                "appliedThrough" to inputs,
                                "resultIssued" to inputs,
                                "resultEntries" to inputs - settled,
                                "fundsSettledThrough" to settled,
                            ),
                        ) && (inputs != settled || state.path("matching").path("resultBytes").asLong() == 0L)
                    }
            }

    private fun fields(
        node: JsonNode,
        expected: Map<String, Long>,
    ): Boolean =
        expected.all { (field, value) -> node.has(field) && node.path(field).isIntegralNumber && node.path(field).asLong() == value }
}

private fun DockerFundsOrderDeliveryVerification.holdCancellation(name: String) {
    Files.writeString(control.resolve("hold-$name"), UUID.randomUUID().toString())
}

private fun DockerFundsOrderDeliveryVerification.releaseCancellation(name: String) {
    Files.delete(control.resolve("hold-$name"))
}

private fun DockerFundsOrderDeliveryVerification.expectCancellation(
    label: String,
    expected: CancellationExpected,
): Map<String, JsonNode> = awaitStates(label, 45_000, expected::matches)

private fun DockerFundsOrderDeliveryVerification.cancellationReply(vararg arguments: String): JsonNode =
    mapper.readTree(tool(*arguments).stdout.lineSequence().last { it.isNotBlank() })

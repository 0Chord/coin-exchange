package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 기본 복구를 반복하지 않고 역할별 복원 시점과 포화 중 자동 재개를 실제 두 클러스터에서 검사한다. */
class DockerSplitRecoveryTest {
    @Test
    fun `F01 다른 시점의 snapshot과 역할별 재시작 뒤 취소와 다음 체결이 끝난다`() =
        verifyRecovery("recovery") { v ->
            v.tool("admit", "buyer", "1", "100", "3")
            val initial = RecoveryExpected()
            v.expectRecovery("F01 최초 예약303과 결과1 회수", initial)
            val fundsSnapshot = v.snapshotRecovery("funds", initial)

            v.holdRecovery("results")
            v.tool("admit", "seller", "1", "100", "1")
            val tradeWaiting = waitingTrade()
            v.expectRecovery("F01 매칭은 체결2, 자금은 결과1, 적용 ACK2는 확정", tradeWaiting)
            val matchingSnapshot = v.snapshotRecovery("matching", tradeWaiting)
            assertEquals(
                1L,
                fundsSnapshot
                    .getValue("funds0")
                    .path("funds")
                    .path("resultThrough")
                    .asLong(),
            )
            assertEquals(
                2L,
                matchingSnapshot
                    .getValue("matching0")
                    .path("matching")
                    .path("appliedThrough")
                    .asLong(),
            )

            val stoppedMatching = v.stopRole("matching")
            val first = v.recoveryReply("cancel", "buyer", "2", "1")
            assertEquals("ACCEPTED", first.path("code").asString())
            val offlineCancel = tradeWaiting.copy(inputs = 3, dispatches = 1, cancellations = 1, pendingCancellations = 1)
            val offline = v.expectRecovery("F01 매칭 중단 중 취소3 접수와 예약 유지", offlineCancel)
            v.assertPendingRecovery(first, offline)
            for (name in offline.keys.filter { it.startsWith("funds") }) {
                assertEquals(stoppedMatching.before.getValue(name).path("bootId"), offline.getValue(name).path("bootId"))
                assertTrue(v.number(offline.getValue(name), "logPosition") > v.number(fundsSnapshot.getValue(name), "lastSnapshotPosition"))
            }
            val matchedCancel =
                offlineCancel.copy(
                    matchingThrough = 3,
                    matchingAck = 3,
                    dispatches = 0,
                    book = 0,
                    bookQuantity = 0,
                    engine = 3,
                )
            val matchingRestored = v.startRole(stoppedMatching, true, matchedCancel::matches)
            for (name in matchingRestored.keys.filter { it.startsWith("matching") }) {
                assertTrue(
                    v.number(matchingRestored.getValue(name), "logPosition") >
                        v.number(matchingSnapshot.getValue(name), "lastSnapshotPosition"),
                )
            }
            val stoppedFunds = v.stopRole("funds")
            v.startRole(stoppedFunds, true, matchedCancel::matches)
            assertEquals(first, v.recoveryReply("cancel", "buyer", "2", "1"))

            v.holdRecovery("funds-result-replies")
            v.releaseRecovery("results")
            val returned = returnedCancel().copy(settled = 1)
            val paid = v.expectRecovery("F01 결과2와3 정산 뒤 예약202만 반환", returned)
            v.assertRecoveryReceipts(matchingRestored, paid)
            v.awaitRecoveryReply("received-funds-result-ack.json", "DUPLICATE", 3, returned)
            v.assertRecoveryReceipts(paid, v.expectRecovery("F01 응답 유실 중 자동 재전송은 금액과 최초 시각 보존", returned))
            v.releaseRecovery("funds-result-replies")
            v.expectRecovery("F01 매칭에 ACK3 확정 뒤 결과 회수", returned.copy(settled = 3))
            v.finishRecovery(first, paid)
        }

    @Test
    fun `F02 한 칸 결과와 신규 포화 중 연결과 ACK를 잃어도 취소와 다음 체결이 끝난다`() =
        verifyRecovery("recovery-cap") { v ->
            v.tool("admit", "buyer", "1", "100", "3")
            v.expectRecovery("F02 최초 주문과 결과1 확인 완료", RecoveryExpected())
            v.holdRecovery("results")
            v.tool("admit", "seller", "1", "100", "1")
            val tradeWaiting = waitingTrade()
            v.expectRecovery("F02 결과2가 한 칸을 차지하고 적용 ACK2는 source에 확정", tradeWaiting)
            Files.deleteIfExists(v.control.resolve("received-order-busy.json"))
            val first = v.recoveryReply("cancel", "buyer", "2", "1")
            val busy = tradeWaiting.copy(inputs = 3, dispatches = 1, cancellations = 1, pendingCancellations = 1)
            val beforeProbe = v.expectRecovery("F02 취소3 BUSY는 주문장과 예약을 유지", busy)
            v.assertPendingRecovery(first, beforeProbe)
            v.awaitRecoveryReply("received-order-busy.json", "BUSY", null, busy)
            val refused = v.recoveryReply("probe-capacity")
            assertEquals("RETRYABLE_CAPACITY", refused.path("code").asString())
            val afterProbe = v.expectRecovery("F02 신규 접수 거절은 원래 취소와 금융 상태 무변경", busy)
            assertEquals(beforeProbe.mapValues { it.value.path("funds") }, afterProbe.mapValues { it.value.path("funds") })

            val beforeConnection = v.awaitRecoveryConnection(busy)
            val token = UUID.randomUUID().toString()
            val request = v.control.resolve("close-result-connection")
            val temporary = Files.createTempFile(v.control, "close-result-", ".tmp")
            Files.writeString(temporary, token)
            Files.move(temporary, request, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            v.awaitStates("F02 같은 worker boot의 실제 연결 close와 상대 확인 재완료", 45_000) { states ->
                val c = v.readRecoveryControl("result-connection.json")
                busy.matches(states) && c != null &&
                    c.path("bootId") == beforeConnection.path("bootId") &&
                    c.path("consumedCloseToken").asString() == token &&
                    c.path("closedConnectionId").isTextual && c.path("closedConnectionId").asString().isNotBlank() &&
                    c.path("connectionId") != c.path("closedConnectionId") &&
                    c.path("describedConnectionId") == c.path("connectionId") &&
                    c.path("describedCount").asLong() > beforeConnection.path("describedCount").asLong() &&
                    c.path("closeCount").asLong() == beforeConnection.path("closeCount").asLong() + 1
            }
            v.checkpoints +=
                mapOf("case" to "F02 실제 연결 재접속", "before" to beforeConnection, "after" to v.readRecoveryControl("result-connection.json"))

            v.holdRecovery("funds-result-replies")
            v.releaseRecovery("results")
            val settledTrade =
                busy.copy(
                    fundsThrough = 2,
                    buyerHold = 202,
                    buyerX = 1,
                    sellerQ = 199,
                    sellerHold = 0,
                    live = 1,
                    quantity = 2,
                    pendingOrders = 0,
                    trades = 1,
                    feeQ = 2,
                )
            val paid = v.expectRecovery("F02 자금 정산2 뒤에도 유실 ACK와 결과1칸 때문에 취소는 대기", settledTrade)
            v.assertRecoveryReceipts(beforeProbe, paid)
            v.awaitRecoveryReply("received-funds-result-ack.json", "DUPLICATE", 2, settledTrade)
            val stopped = v.stopRole("matching")
            val restored = v.startRole(stopped, false, settledTrade::matches)
            v.assertRecoveryReceipts(paid, restored)
            v.awaitRecoveryReply("received-funds-result-ack.json", "DUPLICATE", 2, settledTrade)
            v.releaseRecovery("funds-result-replies")
            v.expectRecovery("F02 ACK2 회수로 취소3이 자동 적용되고202 반환", returnedCancel())
            v.finishRecovery(first, paid)
        }
}

private fun waitingTrade() =
    RecoveryExpected(
        inputs = 2,
        fundsThrough = 1,
        matchingAck = 2,
        settled = 1,
        sellerX = 2,
        sellerHold = 1,
        live = 2,
        quantity = 4,
        pendingOrders = 1,
        bookQuantity = 2,
        engine = 2,
        admissions = 2,
    )

private fun returnedCancel() =
    RecoveryExpected(
        inputs = 3,
        fundsThrough = 3,
        matchingAck = 3,
        settled = 3,
        buyerQ = 899,
        buyerHold = 0,
        buyerX = 1,
        sellerQ = 199,
        sellerX = 2,
        live = 0,
        quantity = 0,
        book = 0,
        bookQuantity = 0,
        engine = 3,
        admissions = 2,
        cancellations = 1,
        trades = 1,
        feeQ = 2,
    )

private fun verifyRecovery(
    profile: String,
    scenario: (DockerFundsOrderDeliveryVerification) -> Unit,
) {
    val verification = DockerFundsOrderDeliveryVerification(profile, scenario)
    assertTrue(verification.run(), "${verification.failures.joinToString("\n")}\n보고서: ${verification.reports}")
}

private fun DockerFundsOrderDeliveryVerification.expectRecovery(
    label: String,
    expected: RecoveryExpected,
): Map<String, JsonNode> = awaitStates(label, 45_000) { expected.matches(it, recoveryBoots()) }

private fun DockerFundsOrderDeliveryVerification.snapshotRecovery(
    role: String,
    expected: RecoveryExpected,
): Map<String, JsonNode> {
    val before = readStates()
    tool("snapshot", role)
    return awaitStates("$role 자기 업무 경계 snapshot 확정", 45_000) { states ->
        expected.matches(states) &&
            states.filterKeys { it.startsWith(role) }.all { (name, state) ->
                number(state, "snapshotCount") > number(before.getValue(name), "snapshotCount") && number(state, "lastSnapshotPosition") > 0
            }
    }
}

private fun DockerFundsOrderDeliveryVerification.holdRecovery(name: String) {
    Files.writeString(control.resolve("hold-$name"), UUID.randomUUID().toString())
}

private fun DockerFundsOrderDeliveryVerification.releaseRecovery(name: String) {
    Files.delete(control.resolve("hold-$name"))
}

private fun DockerFundsOrderDeliveryVerification.recoveryReply(vararg arguments: String): JsonNode =
    mapper.readTree(tool(*arguments).stdout.lineSequence().last { it.isNotBlank() })

private fun DockerFundsOrderDeliveryVerification.readRecoveryControl(name: String): JsonNode? {
    val path = control.resolve(name)
    return if (Files.isRegularFile(path) && Files.size(path) in 1..262_144L) mapper.readTree(Files.readString(path)) else null
}

private fun DockerFundsOrderDeliveryVerification.awaitRecoveryConnection(expected: RecoveryExpected): JsonNode {
    awaitStates("F02 현재 matching worker의 연결과 상대 확인 완료", 30_000) { states ->
        val c = readRecoveryControl("result-connection.json")
        expected.matches(states) && c != null && c.path("state").asString() == "DESCRIBED" &&
            c.path("describedConnectionId") == c.path("connectionId") &&
            states.filterKeys { it.startsWith("matching") }.any { it.value.path("bootId") == c.path("bootId") }
    }
    return checkNotNull(readRecoveryControl("result-connection.json"))
}

private fun DockerFundsOrderDeliveryVerification.awaitRecoveryReply(
    name: String,
    code: String,
    through: Long?,
    expected: RecoveryExpected,
) {
    awaitStates("$name 현재 worker의 $code 응답", 30_000) { states ->
        val reply = readRecoveryControl(name)
        val role = if (name == "received-order-busy.json") "funds" else "matching"
        expected.matches(states) && reply != null && reply.path("code").asString() == code &&
            (through == null || reply.path("through").asLong() == through) &&
            states.filterKeys { it.startsWith(role) }.any { it.value.path("bootId") == reply.path("bootId") }
    }
}

private fun DockerFundsOrderDeliveryVerification.assertRecoveryReceipts(
    before: Map<String, JsonNode>,
    after: Map<String, JsonNode>,
) {
    for ((name, state) in before.filterKeys { it.startsWith("funds") }) {
        for (receipt in state.path("funds").path("receipts")) {
            val same =
                after
                    .getValue(name)
                    .path("funds")
                    .path("receipts")
                    .single { it.path("sequence") == receipt.path("sequence") }
            assertEquals(receipt, same, "$name 최초 정산 시각과 결과 ID 보존")
        }
    }
    // 같은 실행에서 생성한 전체 사건·효과·대상과 결과 ID가 반영·복구 뒤에도 같아야 한다.
    val originalResults =
        before.values.flatMap { state ->
            val role = if (state.path("role").asString() == "FUNDS") "funds" else "matching"
            val values = state.path(role)
            val details = values.path("resultDetails")
            assertTrue(details.isArray, "원래 결과 의미 관찰이 없습니다")
            assertEquals(values.path(if (role == "funds") "receiptEntries" else "resultEntries").asInt(), details.size())
            details.toList()
        }
    for (original in originalResults) {
        for ((name, state) in after.filterKeys { it.startsWith("funds") }) {
            val actual = state.path("funds").path("resultDetails").single { it.path("sequence") == original.path("sequence") }
            assertEquals(original, actual, "$name 전체 결과의 사건·효과·대상과 ID 보존")
        }
    }
}

private fun DockerFundsOrderDeliveryVerification.assertPendingRecovery(
    first: JsonNode,
    states: Map<String, JsonNode>,
) {
    for ((name, state) in states) {
        if (name.startsWith("funds")) {
            val details = state.path("funds").path("cancellationDetails").single()
            assertEquals(first.path("cancelId"), details.path("cancelId"))
            assertEquals(first.path("targetOrderId"), details.path("targetOrderId"))
            assertEquals(3L, details.path("inputSequence").asLong())
            val target = state.path("funds").path("liveReservations").single { it.path("orderId") == first.path("targetOrderId") }
            assertEquals(3L, target.path("quantity").asLong())
            assertEquals(303L, target.path("remainingAmount").asLong())
        } else {
            val target = state.path("matching").path("bookDetails").single()
            assertEquals(first.path("targetOrderId"), target.path("orderId"))
            assertEquals(2L, target.path("quantity").asLong())
        }
    }
}

private fun DockerFundsOrderDeliveryVerification.finishRecovery(
    first: JsonNode,
    receipts: Map<String, JsonNode>,
) {
    assertEquals(first, recoveryReply("cancel", "buyer", "2", "1"))
    tool("admit", "buyer", "4", "100", "1", "SELL")
    expectRecovery(
        "후속 SELL은 실제 얻은 base1을 예약해 대기",
        returnedCancel().copy(
            inputs = 4,
            matchingThrough = 4,
            fundsThrough = 4,
            matchingAck = 4,
            settled = 4,
            buyerX = 0,
            buyerXHold = 1,
            live = 1,
            quantity = 1,
            book = 1,
            bookQuantity = 1,
            engine = 4,
            admissions = 3,
        ),
    )
    tool("admit", "seller", "2", "100", "1", "BUY")
    val expected =
        returnedCancel().copy(
            inputs = 5,
            matchingThrough = 5,
            fundsThrough = 5,
            matchingAck = 5,
            settled = 5,
            buyerQ = 998,
            buyerX = 0,
            sellerQ = 98,
            sellerX = 3,
            engine = 5,
            admissions = 4,
            trades = 2,
            feeQ = 4,
        )
    val final = expectRecovery("최종998과98과수수료4 및 전체 의무 회수", expected)
    val reference = uninterruptedRecoveryReference()
    val firstFunds = final.getValue("funds0").path("funds")
    for ((name, state) in final) {
        assertEquals(
            reference.getValue(if (name.startsWith("funds")) "funds" else "matching"),
            recoverySemanticView(state),
            "$name 무중단 실제 소유자와 의미 상태 일치",
        )
        if (name.startsWith("funds")) {
            assertEquals(emptyList(), recoveryMeaningProblems(state, first), "$name 대상·사건·효과 대조")
            for (field in listOf("inboxChannels", "resultDetails")) {
                assertEquals(firstFunds.path(field), state.path("funds").path(field), "$name 최초 응답과 전체 결과의 사본 일치")
            }
        }
    }
    assertRecoveryReceipts(receipts, final)
    checkpoints += mapOf("case" to "수기 금액과 무중단 대조 및 후속 정상 처리", "reference" to reference, "states" to final)
}

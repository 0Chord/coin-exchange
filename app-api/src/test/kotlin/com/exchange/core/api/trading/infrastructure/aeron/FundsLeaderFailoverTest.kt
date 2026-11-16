package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals

/** 실제 응답 수신과 응답 미확인을 서로 다른 새9노드 실행에서 검증한다. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FundsLeaderFailoverTest {
    @Test
    @Order(1)
    fun `성공 접수 응답 직후 자금 리더가 종료돼도 최초 승인과 예약을 보존한다`() = verify(hidden = false)

    @Test
    @Order(2)
    fun `실제 성공 응답을 숨긴 뒤 자금 리더가 종료돼도 같은 요청으로 최초 결과를 확인한다`() = verify(hidden = true)

    private fun verify(hidden: Boolean) =
        MultiMatchingRun("race", leaderFailover = true, failoverOwner = "funds").verify { run ->
            val originals = linkedMapOf<String, JsonNode>()
            run.installGate("btc-dispatch", 1)
            val initial = run.awaitFunds(FundsFailoverStage.INITIALIZED)
            // 최초 실응답을 관측한 다음 업무 행동이 구 자금 리더 SIGKILL이다.
            val (first, oldTerm) = run.admitThenKill(initial, hidden)
            originals["B1"] = first
            val elected = run.awaitFunds(FundsFailoverStage.ORDER_HELD, originals, oldTerm)
            run.recordElection(elected)
            run.awaitBlocked()
            assertEquals(first, run.tool("admit", "buyer", "btc", "1", "6", "BUY"))
            run.awaitFunds(FundsFailoverStage.ORDER_HELD, originals, oldTerm)

            originals["S1"] = run.tool("admit", "buyer", "sui", "1", "5", "BUY")
            val refused = run.awaitFunds(FundsFailoverStage.REFUSED_HELD, originals, oldTerm)
            repeatAndConflict(run, originals)
            run.sameBusiness(refused, run.awaitFunds(FundsFailoverStage.REFUSED_HELD, originals, oldTerm))
            run.mark("refusalConfirmed")
            run.releaseGate()
            run.awaitReleased()
            run.awaitFunds(FundsFailoverStage.BTC_RESTING, originals, oldTerm)
            run.mark("btcSettled")

            run.rejoinLeader()
            val rejoined = run.awaitFunds(FundsFailoverStage.BTC_RESTING, originals)
            run.recordRejoined(rejoined)
            repeatAndConflict(run, originals)
            run.sameBusiness(rejoined, run.awaitFunds(FundsFailoverStage.BTC_RESTING, originals))

            originals["S2"] = run.tool("admit", "buyer", "sui", "2", "1", "BUY")
            val final = run.awaitFunds(FundsFailoverStage.FOLLOWUP, originals)
            run.mark("followupCompleted")
            // 현재 가용300과 달라도 S1의 최초 가용400·부족100을 그대로 돌려줘야 한다.
            repeatAndConflict(run, originals)
            assertEquals(originals.getValue("S2"), run.tool("admit", "buyer", "sui", "2", "1", "BUY"))
            run.sameBusiness(final, run.awaitFunds(FundsFailoverStage.FOLLOWUP, originals))
            run.verifyFailoverEvidence()
        }

    private fun repeatAndConflict(
        run: MultiMatchingRun,
        originals: Map<String, JsonNode>,
    ) {
        assertEquals(originals.getValue("B1"), run.tool("admit", "buyer", "btc", "1", "6", "BUY"))
        assertEquals(originals.getValue("S1"), run.tool("admit", "buyer", "sui", "1", "5", "BUY"))
        assertEquals("REQUEST_CONTENT_CONFLICT", run.tool("admit", "buyer", "btc", "1", "7", "BUY").path("code").asString())
        assertEquals("REQUEST_CONTENT_CONFLICT", run.tool("admit", "buyer", "sui", "1", "4", "BUY").path("code").asString())
    }
}

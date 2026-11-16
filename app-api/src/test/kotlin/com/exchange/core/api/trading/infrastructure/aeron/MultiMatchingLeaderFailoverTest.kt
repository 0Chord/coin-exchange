package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals

/** 각 장애창은 새 RDB·동결 이미지·9개 원본 기록에서 실제 기본 전달 작업을 검증한다. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiMatchingLeaderFailoverTest {
    @Test
    @Order(1)
    fun `BTC 주문 전달 보류 중 리더가 종료돼도 SUI를 정산하고 기존 주문을 이어간다`() =
        MultiMatchingRun("settlement", leaderFailover = true).verify { run ->
            val originals = linkedMapOf<String, JsonNode>()
            run.installGate("btc-dispatch", 1)
            originals["buy-btc"] = run.tool("admit", "buyer", "btc", "1", "2", "BUY")
            run.awaitBlocked()
            val held = run.awaitFailover(MultiMatchingFailoverStage.ORDER_HELD, originals)
            val oldTerm = run.killBtcLeader(held)
            val elected = run.awaitFailover(MultiMatchingFailoverStage.ORDER_HELD, originals, oldTerm)
            run.recordElection(elected)
            assertEquals(originals.getValue("buy-btc"), run.tool("admit", "buyer", "btc", "1", "2", "BUY"))
            run.awaitFailover(MultiMatchingFailoverStage.ORDER_HELD, originals, oldTerm)

            // 구 리더가 실제 정지하고 생존 과반이 선출된 뒤 처음 SUI 주문을 시작한다.
            run.mark("suiStarted")
            originals["buy-sui"] = run.tool("admit", "buyer", "sui", "1", "3", "BUY")
            run.awaitFailover(MultiMatchingFailoverStage.ORDER_HELD_SUI_RESERVED, originals, oldTerm)
            originals["sell-sui"] = run.tool("admit", "seller", "sui", "1", "1", "SELL")
            run.awaitFailover(MultiMatchingFailoverStage.ORDER_HELD_SUI_TRADED, originals, oldTerm)
            run.mark("suiSettled")
            run.releaseGate()
            run.awaitReleased()
            run.awaitFailover(MultiMatchingFailoverStage.BUY_RESTING_SUI_TRADED, originals, oldTerm)
            originals["sell-btc"] = run.tool("admit", "seller", "btc", "1", "1", "SELL")
            run.awaitFailover(MultiMatchingFailoverStage.TRADED, originals, oldTerm)
            run.mark("btcSettled")
            finishWithRecovery(run, originals)
        }

    @Test
    @Order(2)
    fun `BTC 체결 결과 보류 중 리더가 종료돼도 SUI를 정산하고 새 리더가 결과를 이어 보낸다`() =
        MultiMatchingRun("settlement", leaderFailover = true).verify { run ->
            val originals = linkedMapOf<String, JsonNode>()
            originals["buy-btc"] = run.tool("admit", "buyer", "btc", "1", "2", "BUY")
            run.awaitFailover(MultiMatchingFailoverStage.BUY_RESTING, originals)
            run.installGate("btc-result", 2)
            originals["sell-btc"] = run.tool("admit", "seller", "btc", "1", "1", "SELL")
            run.awaitBlocked()
            val held = run.awaitFailover(MultiMatchingFailoverStage.RESULT_HELD, originals)
            val oldTerm = run.killBtcLeader(held)
            val elected = run.awaitFailover(MultiMatchingFailoverStage.RESULT_HELD, originals, oldTerm)
            run.recordElection(elected)
            assertEquals(originals.getValue("sell-btc"), run.tool("admit", "seller", "btc", "1", "1", "SELL"))
            run.awaitFailover(MultiMatchingFailoverStage.RESULT_HELD, originals, oldTerm)

            run.mark("suiStarted")
            originals["buy-sui"] = run.tool("admit", "buyer", "sui", "1", "3", "BUY")
            run.awaitFailover(MultiMatchingFailoverStage.RESULT_HELD_SUI_RESERVED, originals, oldTerm)
            originals["sell-sui"] = run.tool("admit", "seller", "sui", "1", "1", "SELL")
            run.awaitFailover(MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED, originals, oldTerm)
            run.mark("suiSettled")
            run.releaseGate()
            run.awaitReleased()
            run.awaitFailover(MultiMatchingFailoverStage.TRADED, originals, oldTerm)
            run.mark("btcSettled")
            finishWithRecovery(run, originals)
        }

    private fun finishWithRecovery(
        run: MultiMatchingRun,
        originals: MutableMap<String, JsonNode>,
    ) {
        run.rejoinBtcLeader()
        val rejoined = run.awaitFailover(MultiMatchingFailoverStage.TRADED, originals)
        run.recordRejoined(rejoined)
        for ((actor, market, quantity) in listOf(
            Triple("buyer", "btc", "2"),
            Triple("buyer", "sui", "3"),
            Triple("seller", "btc", "1"),
            Triple("seller", "sui", "1"),
        )) {
            val key = "${if (actor == "buyer") "buy" else "sell"}-$market"
            assertEquals(originals.getValue(key), run.tool("admit", actor, market, "1", quantity, if (actor == "buyer") "BUY" else "SELL"))
        }
        run.sameBusiness(rejoined, run.awaitFailover(MultiMatchingFailoverStage.TRADED, originals))
        // 기존 token의 응답을 재사용하지 않고 새 순번으로 세 번째 BTC 입력을 실제 완료한다.
        originals["followup-btc"] = run.tool("admit", "buyer", "btc", "2", "1", "BUY")
        run.awaitFailover(MultiMatchingFailoverStage.FOLLOWUP, originals)
        run.mark("followupCompleted")
        run.verifyFailoverEvidence()
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals

internal enum class FundsQuorumCase(
    val id: String,
    val partitioned: Boolean,
) {
    FOLLOWER_LOSS("Q1", false),
    TWO_LOSS("Q2", false),
    ONE_TWO("Q3", true),
    THREE_WAY("Q4", true),
}

/** 판정값은 #138의 손계산 표에 고정하고 실제 예약 계산으로 기대값을 만들지 않는다. */
internal fun verifyFundsQuorum(case: FundsQuorumCase) {
    MultiMatchingRun("quorum", leaderFailover = true, failoverOwner = "funds", quorumCase = case.id).verify { run ->
        val originals = linkedMapOf<String, JsonNode>()
        val initial = run.awaitQuorum(FundsQuorumStage.INITIALIZED, originals)
        val firstLeader = run.quorumLeader(initial)
        val protected =
            if (case.partitioned) {
                run.beginQuorumClient("old-leader", true, setOf(firstLeader))
                val baseline = run.awaitQuorumClient(setOf("BASELINE_READY"))
                run.quorumDecisions(listOf(baseline.path("baseline")), setOf(firstLeader))
                originals["B0"] = quorumReceipt(baseline.path("baseline"))
                run.quorumMark("baseline-session", baseline)
                run.awaitQuorum(FundsQuorumStage.PROTECTED, originals, local = true)
            } else {
                val baseline = run.tool("quorum-admit", "buyer", "btc", "1", "6", "BUY")
                run.quorumDecisions(listOf(baseline), run.quorumLive().filter { it.startsWith("funds") }.toSet())
                originals["B0"] = quorumReceipt(baseline)
                run.awaitQuorum(FundsQuorumStage.PROTECTED, originals)
            }
        val leader = run.quorumLeader(protected)
        if (case.partitioned) check(leader == firstLeader) { "B0의 기존 세션과 실제 현재 리더가 달라졌습니다" }
        val followers = protected.keys.filter { it.startsWith("funds") && it != leader }.sorted()
        val oldTerm = protected.getValue(leader).path("leadershipTermId").asLong()
        run.quorumRecordInitial(protected)

        when (case) {
            FundsQuorumCase.FOLLOWER_LOSS -> {
                run.quorumKill(setOf(followers[0]))
                resolvePending(run, originals)
                run.awaitQuorum(FundsQuorumStage.RESOLVED, originals)
                run.rejoinService(followers[0])
            }

            FundsQuorumCase.TWO_LOSS -> {
                run.quorumKill(setOf(leader, followers[0]))
                attemptWithoutQuorum(run, "remaining", setOf(followers[1]))
                run.observeNoQuorum(setOf(followers[1]), originals)
                run.rejoinService(followers[0])
                resolvePending(run, originals)
                run.awaitQuorum(FundsQuorumStage.RESOLVED, originals, higherThanTerm = oldTerm)
                run.rejoinService(leader)
            }

            FundsQuorumCase.ONE_TWO, FundsQuorumCase.THREE_WAY -> {
                val isolated = followers.map { FundsPeerPair(leader, it) }.toSet()
                val partition = if (case == FundsQuorumCase.THREE_WAY) isolated + FundsPeerPair(followers[0], followers[1]) else isolated
                run.quorumPartition(partition)
                assertNoQuorumReply(run.submitQuorumPending())
                run.finishQuorumClient()
                if (case == FundsQuorumCase.THREE_WAY) {
                    for ((index, follower) in followers.withIndex()) attemptWithoutQuorum(run, "singleton$index", setOf(follower))
                    run.observeNoQuorum(setOf(leader) + followers, originals)
                    // 회복하는 두 follower 쌍만 열며 구 리더의 규칙은 유지한다.
                    run.quorumPartition(isolated)
                } else {
                    run.observeNoQuorum(setOf(leader), originals)
                }
                run.beginQuorumClient("majority", false, followers.toSet())
                run.awaitQuorumClient(setOf("CONNECTED"))
                val resolved = run.submitQuorumPending()
                run.quorumMark("majority-witness", run.quorumWitness(run.quorumLive().filter { it.startsWith("funds") }.toSet()))
                run.quorumDecisions(resolved.path("attempts").toList(), followers.toSet(), oldTerm)
                recordPending(originals, resolved)
                run.finishQuorumClient()
                run.awaitQuorum(FundsQuorumStage.RESOLVED, originals, run.quorumLive() - leader, higherThanTerm = oldTerm)
                run.quorumMinorityStillProtected(leader, originals.getValue("B0"))
                run.quorumPartition(emptySet())
            }
        }

        val restored = run.awaitQuorum(FundsQuorumStage.RESOLVED, originals)
        check(restored.size == 9)
        run.quorumMark("rejoined", restored)
        repeatOriginals(run, originals)
        run.sameBusiness(restored, run.awaitQuorum(FundsQuorumStage.RESOLVED, originals))

        originals["N_B"] = run.tool("admit", "buyer", "btc", "3", "1", "BUY")
        run.awaitQuorum(FundsQuorumStage.FOLLOWUP, originals)
        originals["S_B"] = run.tool("admit", "seller", "btc", "1", "1", "SELL")
        originals["S_S"] = run.tool("admit", "seller", "sui", "1", "1", "SELL")
        val complete = run.awaitQuorum(FundsQuorumStage.COMPLETE, originals)
        repeatOriginals(run, originals)
        assertEquals("REQUEST_CONTENT_CONFLICT", run.tool("admit", "buyer", "btc", "2", "2", "BUY").path("code").asString())
        run.sameBusiness(complete, run.awaitQuorum(FundsQuorumStage.COMPLETE, originals))
        run.quorumMark("completed", mapOf("caseId" to case.id, "originals" to originals, "states" to complete))
    }
}

private fun attemptWithoutQuorum(
    run: MultiMatchingRun,
    scope: String,
    funds: Set<String>,
) {
    run.beginQuorumClient(scope, false, funds)
    run.awaitQuorumClient(setOf("CONNECTED", "CONNECT_UNCONFIRMED"))
    assertNoQuorumReply(run.submitQuorumPending())
    run.finishQuorumClient()
}

private fun assertNoQuorumReply(result: JsonNode) {
    val attempts = result.path("attempts").toList()
    check(attempts.size == 2 && attempts.map { it.path("request").asString() }.toSet() == setOf("pb", "ps"))
    for (attempt in attempts) {
        val classification = attempt.path("classification").asString()
        check(classification in setOf("CONNECT_UNCONFIRMED", "SENT_NO_REPLY", "CONNECTED_NOT_SENT", "TRANSPORT_FAILURE")) {
            "과반 없는 scope의 새 business 응답: $attempt"
        }
        check(attempt.path("code").asString() != "ACCEPTED")
        check(attempt.path("token").path("userId").asString() == "buyer")
        check(attempt.path("quantity").asLong() == 1L)
        when (classification) {
            "CONNECT_UNCONFIRMED" -> {
                check(!attempt.path("connected").asBoolean() && !attempt.path("transportAccepted").asBoolean())
            }

            "SENT_NO_REPLY" -> {
                check(
                    attempt.path("connected").asBoolean() && attempt.path("transportAccepted").asBoolean() &&
                        attempt.path("positiveOfferPosition").asLong(-1) > 0,
                )
            }

            "CONNECTED_NOT_SENT" -> {
                check(attempt.path("connected").asBoolean() && !attempt.path("transportAccepted").asBoolean())
            }

            "TRANSPORT_FAILURE" -> {
                check(attempt.path("connected").asBoolean() && attempt.path("error").isTextual)
                if (attempt.path("transportAccepted").asBoolean()) check(attempt.path("positiveOfferPosition").asLong(-1) > 0)
            }
        }
    }
}

private fun resolvePending(
    run: MultiMatchingRun,
    originals: MutableMap<String, JsonNode>,
) {
    val allowed = run.quorumLive().filter { it.startsWith("funds") }.toSet()
    for ((key, market, sequence) in listOf(Triple("P_B", "btc", "2"), Triple("P_S", "sui", "1"))) {
        val row = run.tool("quorum-admit", "buyer", market, sequence, "1", "BUY")
        run.quorumDecisions(listOf(row), allowed)
        originals[key] = quorumReceipt(row)
    }
}

private fun recordPending(
    originals: MutableMap<String, JsonNode>,
    result: JsonNode,
) {
    for ((label, key) in listOf("pb" to "P_B", "ps" to "P_S")) {
        val row = result.path("attempts").single { it.path("request").asString() == label }
        check(row.path("classification").asString() == "REPLIED" && row.path("code").asString() == "ACCEPTED")
        originals[key] = quorumReceipt(row)
    }
}

private fun quorumReceipt(row: JsonNode): JsonNode =
    jacksonObjectMapper().createObjectNode().apply {
        set("code", row.path("code"))
        set("receipt", row.path("receipt"))
    }

private fun repeatOriginals(
    run: MultiMatchingRun,
    originals: Map<String, JsonNode>,
) {
    val requests =
        mapOf(
            "B0" to listOf("buyer", "btc", "1", "6", "BUY"),
            "P_B" to listOf("buyer", "btc", "2", "1", "BUY"),
            "P_S" to listOf("buyer", "sui", "1", "1", "BUY"),
            "N_B" to listOf("buyer", "btc", "3", "1", "BUY"),
            "S_B" to listOf("seller", "btc", "1", "1", "SELL"),
            "S_S" to listOf("seller", "sui", "1", "1", "SELL"),
        )
    for ((name, original) in originals) assertEquals(original, run.tool("admit", *requests.getValue(name).toTypedArray()), "$name 최초 결과 보존")
}

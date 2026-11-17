package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 이 판정의 통과는 실제 Linux namespace 분할 성공을 대신하지 않는다. */
class FundsQuorumNetworkControlTest {
    private val addresses =
        mapOf(
            "funds0" to "172.29.138.10",
            "funds1" to "172.29.138.11",
            "funds2" to "172.29.138.12",
            "btc0" to "172.29.138.20",
            "sui0" to "172.29.138.30",
            "client" to "172.29.138.40",
        )

    @Test
    fun `일대이 분할은 funds 두쌍만 양방향 전체 IP로 막는다`() {
        val plan = plan(setOf(FundsPeerPair("funds0", "funds1"), FundsPeerPair("funds0", "funds2")))
        assertEquals(4, plan.rules.size)
        assertEquals(setOf("funds0", "funds1", "funds2"), plan.rules.map { it.service }.toSet())
        assertEquals(setOf("172.29.138.10", "172.29.138.11", "172.29.138.12"), plan.rules.map { it.peerAddress }.toSet())
        assertFalse(plan.expectedReachable("funds0", "funds1"))
        assertFalse(plan.expectedReachable("funds1", "funds0"))
        assertTrue(plan.expectedReachable("funds1", "funds2"))
        assertTrue(plan.expectedReachable("funds0", "client"))
        assertTrue(plan.expectedReachable("funds0", "btc0"))
        assertTrue(plan.expectedReachable("funds0", "sui0"))
    }

    @Test
    fun `peer는 양끝 INPUT만 차단하고 scope의 OUTPUT 차단은 남긴다`() {
        val plan = plan(setOf(FundsPeerPair("funds0", "funds1")), mapOf("client" to setOf("funds0")))
        val peers = plan.rules.filter { it.service.startsWith("funds") }
        assertEquals(setOf("funds0", "funds1"), peers.map { it.service }.toSet())
        assertEquals(2, peers.size)
        assertTrue(peers.all { it.direction == FundsQuorumNetworkDirection.INPUT })
        val client = plan.rules.filter { it.service == "client" }
        assertEquals(4, client.size)
        assertEquals(FundsQuorumNetworkDirection.entries.toSet(), client.map { it.direction }.toSet())
        val counters = plan.verifyInstalled(snapshots(plan))
        for (service in setOf("funds0", "funds1", "client")) {
            assertFailsWith<IllegalStateException> {
                plan.verifyDropCounters(counters.map { if (it.rule.service == service) it.copy(packets = 0) else it })
            }
        }
        val actual = snapshots(plan)
        assertFailsWith<IllegalStateException> {
            plan.verifyInstalled(
                actual + (
                    "funds0" to
                        actual.getValue("funds0").replace(
                            "COMMIT",
                            "[1:84] -A ${plan.outputChain} -d 172.29.138.11/32 -m comment --comment ${plan.comment} -j DROP\nCOMMIT",
                        )
                ),
            )
        }
        assertEquals("RECEIVER_INPUT_BOTH_ENDS", FundsQuorumNetworkControl.PEER_DROP_MODE)
    }

    @Test
    fun `scope는 client 자신의 namespace에서 허용 밖 funds만 차단한다`() {
        val plan = plan(emptySet(), mapOf("client" to setOf("funds0")))
        assertEquals(4, plan.rules.size)
        assertEquals(setOf("client"), plan.rules.map { it.service }.toSet())
        assertEquals(setOf("172.29.138.11", "172.29.138.12"), plan.rules.map { it.peerAddress }.toSet())
        assertTrue(plan.expectedReachable("client", "funds0"))
        assertFalse(plan.expectedReachable("client", "funds1"))
        assertFalse(plan.expectedReachable("funds2", "client"))
        assertTrue(plan.expectedReachable("client", "btc0"))
    }

    @Test
    fun `실제 규칙이 한 방향 누락됐거나 client ingress를 막으면 거절한다`() {
        val plan = plan(setOf(FundsPeerPair("funds0", "funds1")))
        val actual = snapshots(plan)
        plan.verifyInstalled(actual)
        assertFailsWith<IllegalStateException> {
            plan.verifyInstalled(actual + ("funds1" to actual.getValue("funds1").replaceFirst("-j DROP", "-j ACCEPT")))
        }
        assertFailsWith<IllegalStateException> {
            plan.verifyInstalled(
                actual + (
                    "funds0" to
                        actual.getValue("funds0").replace(
                            "COMMIT",
                            "[1:84] -A ${plan.outputChain} -d 172.29.138.40/32 -m comment --comment ${plan.comment} -j DROP\nCOMMIT",
                        )
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            plan.verifyInstalled(
                actual + (
                    "funds0" to
                        actual
                            .getValue("funds0")
                            .replaceFirst("-j DROP", "-p udp --dport 19701 -j DROP")
                ),
            )
        }
    }

    @Test
    fun `모든 차단 방향의 실제 counter와 허용 경로 positive control이 필요하다`() {
        val plan = plan(setOf(FundsPeerPair("funds0", "funds1")))
        val reaches = reaches(plan)
        plan.verifyReachability(reaches)
        val dropped = plan.verifyInstalled(snapshots(plan))
        plan.verifyDropCounters(dropped)
        assertFailsWith<IllegalStateException> { plan.verifyDropCounters(dropped.map { it.copy(packets = 0) }) }
        assertFailsWith<IllegalStateException> {
            plan.verifyReachability(
                reaches.map {
                    if (it.fromService == "funds0" && it.toService == "btc0") it.copy(reachable = false) else it
                },
            )
        }
        assertFailsWith<IllegalStateException> { plan.verifyReachability(reaches.dropLast(1)) }
    }

    @Test
    fun `삼분할에서 follower 연결만 회복하면 구리더 고립과 정상 majority 경로를 구분한다`() {
        val all = setOf(FundsPeerPair("funds0", "funds1"), FundsPeerPair("funds0", "funds2"), FundsPeerPair("funds1", "funds2"))
        assertEquals(6, plan(all).rules.size)
        val recovered = plan(all - FundsPeerPair("funds1", "funds2"))
        assertTrue(recovered.expectedReachable("funds1", "funds2"))
        assertFalse(recovered.expectedReachable("funds0", "funds1"))
        assertFalse(recovered.expectedReachable("funds2", "funds0"))
        val changes = recovered.services.map { recovered.changesFrom(plan(all), it) }
        assertTrue(changes.all { it.additions.isEmpty() })
        assertEquals(2, changes.sumOf { it.removals.size })
        assertTrue(
            changes.flatMap { it.removals }.all {
                it.service in setOf("funds1", "funds2") && it.peerAddress != addresses.getValue("funds0")
            },
        )
    }

    @Test
    fun `종료 사례는 명시한 live 집합만 관측하고 매칭 누락은 허용하지 않는다`() {
        val plan = plan(scopes = mapOf("client" to setOf("funds0")))
        val live = addresses.keys - setOf("funds1", "funds2")
        val actual = snapshots(plan).filterKeys { it in live }
        val counters = plan.verifyInstalled(actual, live)
        plan.verifyDropCounters(counters.map { it.copy(packets = 0, bytes = 0) }, live)
        plan.verifyReachability(reaches(plan).filter { it.fromService in live && it.toService in live }, live)
        assertFailsWith<IllegalArgumentException> { plan.probePairs(live - "btc0") }
        assertFailsWith<IllegalStateException> { plan.verifyInstalled(actual) }
    }

    @Test
    fun `중복 주소나 외부 주소와 funds가 아닌 차단 대상을 거절한다`() {
        assertFailsWith<IllegalArgumentException> { plan(addresses = addresses + ("funds1" to addresses.getValue("funds0"))) }
        assertFailsWith<IllegalArgumentException> { plan(addresses = addresses + ("funds1" to "8.8.8.8")) }
        assertFailsWith<IllegalArgumentException> { plan(setOf(FundsPeerPair("funds0", "btc0"))) }
        assertFailsWith<IllegalArgumentException> { plan(scopes = mapOf("funds0" to setOf("funds1"))) }
        assertFailsWith<IllegalArgumentException> { plan(scopes = mapOf("client" to emptySet())) }
    }

    private fun plan(
        blocked: Set<FundsPeerPair> = emptySet(),
        scopes: Map<String, Set<String>> = emptyMap(),
        addresses: Map<String, String> = this.addresses,
    ) = FundsQuorumNetworkPlan("issue138-unit", addresses, blocked, scopes)

    private fun snapshots(plan: FundsQuorumNetworkPlan) =
        plan.services.associateWith { service ->
            buildString {
                appendLine("*filter")
                appendLine(":INPUT ACCEPT [0:0]")
                appendLine(":FORWARD ACCEPT [0:0]")
                appendLine(":OUTPUT ACCEPT [0:0]")
                appendLine(":${plan.inputChain} - [0:0]")
                appendLine(":${plan.outputChain} - [0:0]")
                appendLine("[0:0] -A INPUT -j ${plan.inputChain}")
                appendLine("[0:0] -A OUTPUT -j ${plan.outputChain}")
                plan.rules.filter { it.service == service }.forEach { rule ->
                    appendLine("[1:84] ${rule.savedRule(plan)}")
                }
                appendLine("COMMIT")
            }
        }

    private fun reaches(plan: FundsQuorumNetworkPlan) =
        plan.probePairs().map { (from, to) ->
            FundsQuorumReachability(from, to, addresses.getValue(to), plan.expectedReachable(from, to), 1)
        }
}

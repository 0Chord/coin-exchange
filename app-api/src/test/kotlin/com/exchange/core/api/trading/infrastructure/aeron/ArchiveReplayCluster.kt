package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.ElectionState
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 실제 합의 멤버를 기동하고 시험의 중단 위치만 서비스 콜백에서 관측한다. */
internal class ArchiveReplayCluster private constructor(
    private val nodes: List<DevelopmentSplitTradingNode>,
    private val appliedPositions: List<AtomicLong>,
) : AutoCloseable {
    val settings: SplitTradingNodeSettings get() = nodes.first().settings
    val memberSettings: List<SplitTradingNodeSettings> get() = nodes.map { it.settings }

    data class Leader(
        val node: DevelopmentSplitTradingNode,
        val term: Long,
        val committed: Long,
        val applied: Long,
    ) {
        val memberId: Int get() = node.settings.transport.memberId
    }

    fun awaitLeader(after: Leader? = null): Leader {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() - deadline < 0) {
            assertHealthy()
            val leader = nodes.mapNotNull(::electedLeader).singleOrNull()
            if (leader != null &&
                (
                    after == null ||
                        (
                            leader.memberId != after.memberId && leader.term > after.term &&
                                leader.committed >= after.applied && leader.applied >= after.applied
                        )
                )
            ) {
                return leader
            }
            Thread.sleep(10)
        }
        error("${settings.role}의 리더와 적용 범위를 확인할 수 없습니다. 이전 임기=${after?.term}, 이전 적용 위치=${after?.applied}")
    }

    fun stopLeader(expected: Leader) {
        val leader = awaitLeader()
        assertEquals(expected.memberId, leader.memberId)
        assertEquals(expected.term, leader.term)
        leader.node.close()
        assertNull(leader.node.error)
    }

    fun assertHealthy() {
        nodes.forEach { assertNull(it.error, "${settings.role} 멤버 ${it.settings.transport.memberId}") }
    }

    fun reopen(): ArchiveReplayCluster {
        check(nodes.all { it.isClosed })
        return launch(memberSettings.map { member -> member.copy(transport = member.transport.copy(recoverExisting = true)) })
    }

    private fun electedLeader(node: DevelopmentSplitTradingNode): Leader? {
        if (node.isClosed || node.role != Cluster.Role.LEADER) return null
        val context = node.consensusContext
        val term = context.leadershipTermIdCounter().get()
        if (term < 0 || ElectionState.get(context.electionStateCounter().get()) != ElectionState.CLOSED) return null
        val applied = appliedPositions[node.settings.transport.memberId].get()
        val committed = context.commitPositionCounter().get()
        if (context.leadershipTermIdCounter().get() != term || node.role != Cluster.Role.LEADER ||
            ElectionState.get(context.electionStateCounter().get()) != ElectionState.CLOSED
        ) {
            return null
        }
        return Leader(node, term, committed, applied)
    }

    override fun close() {
        nodes.asReversed().forEach(DevelopmentSplitTradingNode::close)
    }

    companion object {
        private val replicaPortOffsets = listOf(48, 64, 80, 96)
        val additionalPortOffsets: List<Int> = replicaPortOffsets.flatMap { it..it + 8 }

        fun startBoth(
            funds: SplitTradingNodeSettings,
            matching: SplitTradingNodeSettings,
        ): Pair<ArchiveReplayCluster, ArchiveReplayCluster> {
            val base = funds.transport.basePort
            val fundsPorts = listOf(base, base + replicaPortOffsets[0], base + replicaPortOffsets[1])
            val matchingPorts = listOf(matching.transport.basePort, base + replicaPortOffsets[2], base + replicaPortOffsets[3])
            val fundsIngress = ingress(funds, fundsPorts)
            val matchingIngress = ingress(matching, matchingPorts)
            val fundsCluster = launch(members(funds, fundsPorts, fundsIngress, matchingIngress))
            try {
                return fundsCluster to launch(members(matching, matchingPorts, fundsIngress, matchingIngress))
            } catch (error: Throwable) {
                fundsCluster.close()
                throw error
            }
        }

        private fun ingress(
            settings: SplitTradingNodeSettings,
            ports: List<Int>,
        ): String = ports.mapIndexed { memberId, port -> "$memberId=${settings.transport.advertisedHost}:$port" }.joinToString(",")

        private fun members(
            settings: SplitTradingNodeSettings,
            ports: List<Int>,
            fundsIngress: String,
            matchingIngress: String,
        ): List<SplitTradingNodeSettings> {
            val original = settings.transport
            val host = original.advertisedHost
            val members =
                ports
                    .mapIndexed { memberId, port -> "$memberId,${(0..4).joinToString(",") { "$host:${port + it}" }}" }
                    .joinToString("|")
            return ports.mapIndexed { memberId, port ->
                settings.copy(
                    transport =
                        original.copy(
                            runRoot = original.runRoot.resolveSibling("${original.runRoot.fileName}-member-$memberId"),
                            basePort = port,
                            memberId = memberId,
                            clusterMembers = members,
                        ),
                    fundsIngressEndpoints = fundsIngress,
                    matchingIngressEndpoints = matchingIngress,
                    matchingTargets = settings.matchingTargets.map { it.copy(ingressEndpoints = matchingIngress) },
                )
            }
        }

        private fun launch(settings: List<SplitTradingNodeSettings>): ArchiveReplayCluster {
            val applied = settings.map { AtomicLong() }
            val started = Collections.synchronizedList(mutableListOf<DevelopmentSplitTradingNode>())
            try {
                // 각 멤버의 기동은 선거 완료를 기다리므로 과반을 동시에 시작한다.
                val tasks =
                    settings.mapIndexed { memberId, member ->
                        Callable {
                            DevelopmentSplitTradingNode
                                .start(member, serviceDecorator = { observe(it, applied[memberId]) })
                                .also(started::add)
                        }
                    }
                val nodes =
                    Executors.newFixedThreadPool(settings.size).use { executor ->
                        executor.invokeAll(tasks).map { it.get() }
                    }
                nodes.forEach { assertEquals(listOf(2, 2, 2), it.storageLevels) }
                return ArchiveReplayCluster(nodes, applied)
            } catch (error: Throwable) {
                started.asReversed().forEach(DevelopmentSplitTradingNode::close)
                throw error
            }
        }

        private fun observe(
            service: SplitTradingClusterAdapter,
            applied: AtomicLong,
        ): ClusteredService =
            object : ClusteredService by service {
                override fun onSessionMessage(
                    session: ClientSession?,
                    timestamp: Long,
                    buffer: DirectBuffer,
                    offset: Int,
                    length: Int,
                    header: Header,
                ) {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                    applied.set(service.appliedLogPosition)
                }

                override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs)
            }
    }
}

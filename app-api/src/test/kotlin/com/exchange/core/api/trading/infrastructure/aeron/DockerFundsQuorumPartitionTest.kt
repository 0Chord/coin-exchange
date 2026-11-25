package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** 실제 funds peer 단절 동안 JVM은 유지하고 규칙 해제만으로 따라잡는다. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DockerFundsQuorumPartitionTest {
    @Test
    @Order(1)
    fun `Q3 고립된 구 리더는 새 주문을 승인하지 못하고 과반 두 노드는 같은 요청을 처리한다`() = verifyFundsQuorum(FundsQuorumCase.ONE_TWO)

    @Test
    @Order(2)
    fun `Q4 세 singleton은 새 예약을 막고 follower 경로부터 회복해 같은 요청을 한 번 처리한다`() = verifyFundsQuorum(FundsQuorumCase.THREE_WAY)
}

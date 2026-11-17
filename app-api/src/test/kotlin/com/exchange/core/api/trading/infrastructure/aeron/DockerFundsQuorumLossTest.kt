package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** 원래 기록이 남은 한 대/두 대 손실은 각각 별도의 새9노드 실행이다. */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DockerFundsQuorumLossTest {
    @Test
    @Order(1)
    fun `Q1 follower 한 대가 죽어도 과반은 새 주문을 처리하고 같은 기록으로 재합류한다`() = verifyFundsQuorum(FundsQuorumCase.FOLLOWER_LOSS)

    @Test
    @Order(2)
    fun `Q2 리더 포함 두 대가 죽으면 새 예약을 막고 follower부터 복구해 같은 요청을 해결한다`() = verifyFundsQuorum(FundsQuorumCase.TWO_LOSS)
}

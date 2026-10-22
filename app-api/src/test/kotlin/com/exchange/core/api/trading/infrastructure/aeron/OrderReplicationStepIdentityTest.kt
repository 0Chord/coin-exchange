package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OrderReplicationStepIdentityTest {
    @Test
    fun `같은 사용자 요청 주문이라도 명시한 마켓으로 관찰 대상을 구분한다`() {
        val btc = OrderReplicationScenario.BUY_SEQUENCE.steps()[1]
        val sui = btc.copy(marketId = "SUI-USDT")

        assertEquals(mapOf("userId" to "U1", "requestId" to "R1", "marketId" to "SUI-USDT", "orderId" to "O1"), sui.identity)
        assertNotEquals(btc.identity, sui.identity)
        assertEquals(sui.identity, sui.copy(name = "SUI-REPLAY").identity)
    }

    @Test
    fun `ORDER 단계는 마켓을 생략하거나 빈 값으로 만들 수 없다`() {
        val order = OrderReplicationScenario.BUY_SEQUENCE.steps()[1]

        for (marketId in listOf(null, "", " ")) {
            assertFailsWith<IllegalArgumentException> { order.copy(marketId = marketId) }
        }
    }

    @Test
    fun `자금 준비는 마켓 없이 준비 ID로 관찰 대상을 선택한다`() {
        val opening = OrderReplicationScenario.MULTI_MARKET_SHARED_FUNDS.steps().first()

        assertEquals(null, opening.marketId)
        assertEquals(mapOf("preparationId" to "P1"), opening.identity)
    }
}

package com.exchange.core.api.ledger.application

import com.exchange.core.common.*
import com.exchange.core.ledger.*
import java.time.Instant
import kotlin.test.*

class PrepareDevelopmentBalanceUseCaseTest {
    @Test fun `입력은 도메인에서 검사한 뒤 저장 계약으로 전달하며 결과를 바꾸지 않는다`() {
        val expected = OpeningBalance("seed", UserId("buyer"), AssetId("KRW"), Amount(1000))
        val result = OpeningBalanceResult(expected, "ledger", Instant.EPOCH, false)
        var writes = 0
        val store = object : DevelopmentBalanceStore {
            override fun prepare(opening: OpeningBalance): OpeningBalanceResult {
                assertEquals(expected, opening); writes++; return result
            }
            override fun ensureReceivingBalance(userId: UserId, assetId: AssetId) { writes++ }
        }
        val useCase = PrepareDevelopmentBalanceUseCase(store)
        assertSame(result, useCase.prepare("seed", expected.userId, expected.assetId, expected.amount))
        assertEquals(1, writes)
        assertFailsWith<IllegalArgumentException> { useCase.prepare("seed", expected.userId, expected.assetId, Amount(0)) }
        assertFailsWith<IllegalArgumentException> { useCase.ensureReceivingBalance(UserId("buyer:x"), expected.assetId) }
        assertEquals(1, writes)
        useCase.ensureReceivingBalance(expected.userId, expected.assetId)
        assertEquals(2, writes)
    }
}

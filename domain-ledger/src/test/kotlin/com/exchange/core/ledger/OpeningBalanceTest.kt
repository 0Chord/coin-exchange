package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpeningBalanceTest {
    private val opening = OpeningBalance("seed-1", UserId("buyer"), AssetId("KRW"), Amount(1000))
    private val time = Instant.parse("2026-10-04T00:00:00Z")

    @Test fun `개시 1000은 출처 차변과 사용자 available 대변으로 균형을 이룬다`() {
        val plan = opening.transaction("ledger-1", time)
        assertEquals("OPENING:seed-1", plan.sourceEventId)
        assertEquals(LedgerTransactionType.OPENING, plan.transactionType)
        assertEquals(
            listOf(
                LedgerPosting("SYSTEM:KRW:DEVELOPMENT_FUNDING", AssetId("KRW"), LedgerPostingSide.DEBIT, Amount(1000)),
                LedgerPosting("USER:buyer:KRW:AVAILABLE", AssetId("KRW"), LedgerPostingSide.CREDIT, Amount(1000)),
            ),
            plan.postings,
        )
        assertEquals(opening, OpeningBalance.fromTransaction(plan))
    }

    @Test fun `이력 없는 영 잔고만 개시하며 사용 후 영 잔고도 다시 지급하지 않는다`() {
        val zero = Balance(UserId("buyer"), AssetId("KRW"), Amount(0), Amount(0))
        opening.requireUnused(zero, false)
        assertFailsWith<OpeningBalanceConflictException> { opening.requireUnused(zero, true) }
        assertFailsWith<OpeningBalanceConflictException> { opening.requireUnused(zero.copy(available = Amount(1)), false) }
        assertFailsWith<OpeningBalanceConflictException> { opening.requireUnused(zero.copy(hold = Amount(1)), false) }
    }

    @Test fun `금액과 계정 식별자 경계를 개시 입력에서 검사한다`() {
        assertFailsWith<IllegalArgumentException> { opening.copy(amount = Amount(0)) }
        assertFailsWith<IllegalArgumentException> { opening.copy(userId = UserId("buyer:other")) }
        assertFailsWith<IllegalArgumentException> { opening.copy(assetId = AssetId("x".repeat(65))) }
        assertFailsWith<IllegalArgumentException> { opening.copy(preparationId = " ") }
        assertFailsWith<IllegalArgumentException> { opening.copy(preparationId = "x".repeat(121)) }
        assertEquals(
            Long.MAX_VALUE,
            opening
                .copy(amount = Amount(Long.MAX_VALUE))
                .transaction("max", time)
                .postings[0]
                .amount.value,
        )
    }

    @Test fun `균형이 맞아도 개시 방향과 계정이 다르면 완전한 준비 기록이 아니다`() {
        val plan = opening.transaction("ledger-1", time)
        val reversed =
            LedgerTransaction(
                "ledger-1",
                plan.sourceEventId,
                plan.transactionType,
                time,
                plan.postings.map {
                    it.copy(
                        side =
                            if (it.side ==
                                LedgerPostingSide.DEBIT
                            ) {
                                LedgerPostingSide.CREDIT
                            } else {
                                LedgerPostingSide.DEBIT
                            },
                    )
                },
            )
        assertFailsWith<OpeningBalanceStateException> { OpeningBalance.fromTransaction(reversed) }
        val wrongKind = LedgerTransaction("ledger-1", plan.sourceEventId, LedgerTransactionType.RESERVE, time, plan.postings)
        assertFailsWith<OpeningBalanceStateException> { OpeningBalance.fromTransaction(wrongKind) }
    }
}

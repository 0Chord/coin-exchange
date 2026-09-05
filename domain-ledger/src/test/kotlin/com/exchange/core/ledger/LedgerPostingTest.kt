package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 원장 항목의 값 보존과 계정 식별자·양수 금액 제약을 검사한다. */
class LedgerPostingTest {
    @Test
    fun `유효한 원장 항목은 전달한 값을 보존한다`() {
        val posting =
            LedgerPosting(
                accountId = "SYSTEM:KRW:FEE_REVENUE",
                assetId = AssetId("KRW"),
                side = LedgerPostingSide.CREDIT,
                amount = Amount(1_800),
            )

        assertEquals(
            "SYSTEM:KRW:FEE_REVENUE",
            posting.accountId,
        )
        assertEquals(
            AssetId("KRW"),
            posting.assetId,
        )
        assertEquals(
            LedgerPostingSide.CREDIT,
            posting.side,
        )
        assertEquals(
            Amount(1_800),
            posting.amount,
        )
    }

    @Test
    fun `계정 식별자는 비어 있거나 공백일 수 없다`() {
        listOf("", "   ").forEach { accountId ->
            assertFailsWith<IllegalArgumentException> {
                LedgerPosting(
                    accountId = accountId,
                    assetId = AssetId("KRW"),
                    side = LedgerPostingSide.CREDIT,
                    amount = Amount(1_800),
                )
            }
        }
    }

    @Test
    fun `금액이 0인 원장 항목은 생성할 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            LedgerPosting(
                accountId = "SYSTEM:KRW:FEE_REVENUE",
                assetId = AssetId("KRW"),
                side = LedgerPostingSide.CREDIT,
                amount = Amount.ZERO,
            )
        }
    }
}

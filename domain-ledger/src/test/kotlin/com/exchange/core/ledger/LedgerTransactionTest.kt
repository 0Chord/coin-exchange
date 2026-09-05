package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 원장 거래 생성 시 자산별 차변·대변 균형과 빈 항목 목록 거절을 검증한다. */
class LedgerTransactionTest {
    @Test
    fun `같은 자산의 차변과 대변 합계가 같으면 생성된다`() {
        val postings =
            listOf(
                posting("KRW", LedgerPostingSide.DEBIT, 1_800),
                posting("KRW", LedgerPostingSide.CREDIT, 1_800),
            )

        val transaction = newTransaction(postings)

        assertEquals(postings, transaction.postings)
    }

    @Test
    fun `여러 자산도 각각 차변과 대변이 같으면 생성된다`() {
        val postings =
            listOf(
                posting("KRW", LedgerPostingSide.DEBIT, 180_000),
                posting("KRW", LedgerPostingSide.CREDIT, 180_000),
                posting("BTC", LedgerPostingSide.DEBIT, 2),
                posting("BTC", LedgerPostingSide.CREDIT, 2),
            )

        val transaction = newTransaction(postings)

        assertEquals(postings, transaction.postings)
    }

    @Test
    fun `같은 자산의 차변과 대변 합계가 다르면 거절한다`() {
        val postings =
            listOf(
                posting("KRW", LedgerPostingSide.DEBIT, 1_800),
                posting("KRW", LedgerPostingSide.CREDIT, 1_700),
            )

        assertFailsWith<IllegalArgumentException> {
            newTransaction(postings)
        }
    }

    @Test
    fun `다른 자산끼리는 숫자가 같아도 균형으로 인정하지 않는다`() {
        val postings =
            listOf(
                posting("KRW", LedgerPostingSide.DEBIT, 1_800),
                posting("BTC", LedgerPostingSide.CREDIT, 1_800),
            )

        assertFailsWith<IllegalArgumentException> {
            newTransaction(postings)
        }
    }

    @Test
    fun `원장 항목이 비어 있으면 거절한다`() {
        assertFailsWith<IllegalArgumentException> {
            newTransaction(emptyList())
        }
    }

    /** 반복되는 테스트용 원장 항목 생성을 줄이는 보조 함수. */
    private fun posting(
        asset: String,
        side: LedgerPostingSide,
        amount: Long,
    ): LedgerPosting =
        LedgerPosting(
            accountId = "TEST:$asset:${side.name}",
            assetId = AssetId(asset),
            side = side,
            amount = Amount(amount),
        )

    /** 테스트마다 동일한 식별자와 시각을 사용하고 항목 목록만 바꾼다. */
    private fun newTransaction(
        postings: List<LedgerPosting>,
    ): LedgerTransaction =
        LedgerTransaction(
            ledgerTransactionId = "ledger-transaction-1",
            sourceEventId = "matching-event-1",
            transactionType = LedgerTransactionType.SETTLEMENT,
            occurredAt = Instant.parse("2026-09-05T00:00:00Z"),
            postings = postings,
        )
}

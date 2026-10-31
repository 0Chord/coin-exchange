package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** 기존 계약에 맞춰 차변과 대변을 각각 정확히 합산하는 독립 기준값을 사용한다. */
class LedgerTransactionExactBalanceTest {
    @Test
    fun `both overflow directions and totals larger than Long agree with the exact oracle`() {
        val max = Long.MAX_VALUE
        val signedAmounts =
            listOf(
                listOf(7L, -7L),
                listOf(max, -max),
                listOf(max, max, -max, -max),
                listOf(-max, -max, max, max),
                listOf(max, 1L, -max, -1L),
                listOf(-max, -1L, max, 1L), // 정확히 Long.MIN_VALUE에 도달한다.
                listOf(-max, -2L, max, 2L), // Long.MIN_VALUE보다 작아진다.
                listOf(max, max, -max, -(max - 1)),
                listOf(-max, -max, max, max - 1),
                listOf(max, max, 2L), // Long 합계가 오버플로하면 잘못된 0이 된다.
                listOf(-max, -max, -2L),
                listOf(max, 1L),
                listOf(-max, -2L),
            )

        for ((index, amounts) in signedAmounts.withIndex()) {
            assertMatchesOracle(amounts.map { posting("EXACT-$index", it) })
        }
    }

    @Test
    fun `multiple assets never cancel one another and the first unbalanced asset is reported`() {
        val max = Long.MAX_VALUE
        val inputs =
            listOf(
                listOf(posting("EXACT-KRW", max), posting("EXACT-BTC", -max)),
                listOf(
                    posting("EXACT-BTC", max),
                    posting("EXACT-KRW", -max),
                    posting("EXACT-BTC", max),
                    posting("EXACT-KRW", -max),
                ),
                listOf(
                    posting("EXACT-BALANCED", max),
                    posting("EXACT-SECOND", 2),
                    posting("EXACT-BALANCED", -max),
                    posting("EXACT-THIRD", -2),
                ),
            )

        for (input in inputs) {
            assertMatchesOracle(input)
            assertMatchesOracle(input.reversed())
        }
    }

    @Test
    fun `large balanced and unbalanced inputs retain their result across posting orders`() {
        val max = Long.MAX_VALUE
        val balanced =
            listOf(
                posting("EXACT-ORDER-A", max),
                posting("EXACT-ORDER-B", -max),
                posting("EXACT-ORDER-A", max),
                posting("EXACT-ORDER-A", 1),
                posting("EXACT-ORDER-B", max),
                posting("EXACT-ORDER-A", -max),
                posting("EXACT-ORDER-A", -max),
                posting("EXACT-ORDER-A", -1),
            )
        val unbalanced = balanced + posting("EXACT-ORDER-B", -1)

        for (input in listOf(balanced, unbalanced)) {
            for (offset in input.indices) {
                val rotated = input.drop(offset) + input.take(offset)
                assertMatchesOracle(rotated)
                assertMatchesOracle(rotated.reversed())
            }
        }
    }

    @Test
    fun `four interleaved assets retain exact balances and first error order across posting orders`() {
        val max = Long.MAX_VALUE
        val balanced =
            listOf(
                posting("EXACT-MANY-A", max),
                posting("EXACT-MANY-B", -max),
                posting("EXACT-MANY-A", max),
                posting("EXACT-MANY-C", max),
                posting("EXACT-MANY-B", -2),
                posting("EXACT-MANY-D", 3),
                posting("EXACT-MANY-C", max),
                posting("EXACT-MANY-A", -max),
                posting("EXACT-MANY-B", max),
                posting("EXACT-MANY-C", -max),
                posting("EXACT-MANY-D", -3),
                posting("EXACT-MANY-A", -max),
                posting("EXACT-MANY-B", 2),
                posting("EXACT-MANY-C", -max),
            )
        val inputs =
            listOf(
                balanced,
                balanced + posting("EXACT-MANY-C", 1),
                balanced + posting("EXACT-MANY-D", 1),
                balanced + listOf(posting("EXACT-MANY-C", 1), posting("EXACT-MANY-D", -1)),
                balanced +
                    listOf(
                        posting("EXACT-MANY-A", 1),
                        posting("EXACT-MANY-B", -1),
                        posting("EXACT-MANY-C", 2),
                        posting("EXACT-MANY-D", -2),
                    ),
            )

        for (input in inputs) {
            for (offset in input.indices) {
                val rotated = input.drop(offset) + input.take(offset)
                assertMatchesOracle(rotated)
                assertMatchesOracle(rotated.reversed())
            }
        }
    }

    @Test
    fun `large input is copied and remains immutable on a pool miss and hit`() {
        val max = Long.MAX_VALUE
        val expected = listOf(max, max, -max, -max).map { posting("EXACT-COPY", it) }
        val firstInput = expected.toMutableList()
        val firstTime = Instant.parse("2026-10-27T01:00:00Z")
        val secondTime = firstTime.plusNanos(1)
        val first = transaction("copy-first", firstInput, firstTime, LedgerTransactionType.RESERVE)
        firstInput.clear()
        val secondInput = expected.toMutableList()
        val second = transaction("copy-second", secondInput, secondTime, LedgerTransactionType.SETTLEMENT)
        secondInput[0] = posting("EXACT-REPLACED", 1)
        secondInput.clear()

        assertNotSame(first, second)
        assertSame(first.postings, second.postings)
        assertEquals(expected, first.postings)
        assertEquals(expected, second.postings)
        assertEquals("ledger-copy-first", first.ledgerTransactionId)
        assertEquals("event-copy-first", first.sourceEventId)
        assertEquals(firstTime, first.occurredAt)
        assertEquals(LedgerTransactionType.RESERVE, first.transactionType)
        assertEquals("ledger-copy-second", second.ledgerTransactionId)
        assertEquals("event-copy-second", second.sourceEventId)
        assertEquals(secondTime, second.occurredAt)
        assertEquals(LedgerTransactionType.SETTLEMENT, second.transactionType)
        assertFailsWith<UnsupportedOperationException> {
            (second.postings as MutableList<LedgerPosting>).clear()
        }
        assertEquals(expected, first.postings)
    }

    @Test
    fun `even an existing pool entry cannot bypass empty or exact asset validation`() {
        // 풀은 호출자가 검증했다고 가정한다. 이 픽스처에만 잘못된 항목을 넣어
        // 제품 코드에 검증 훅을 추가하지 않고 캐시 적중 시 조기 반환을 관찰한다.
        val field = LedgerTransaction::class.java.getDeclaredField("postingLists")
        field.isAccessible = true
        val pool = field.get(null) as LedgerPostingListPool
        val max = Long.MAX_VALUE
        val inputs =
            listOf(
                emptyList(),
                listOf(max, max, 2L).map { posting("EXACT-POOL-DEBIT", it) },
                listOf(-max, -max, -2L).map { posting("EXACT-POOL-CREDIT", it) },
                listOf(posting("EXACT-POOL-KRW", max), posting("EXACT-POOL-BTC", -max)),
                listOf(
                    posting("EXACT-POOL-FIRST", 1),
                    posting("EXACT-POOL-SECOND", 1),
                    posting("EXACT-POOL-THIRD", max),
                    posting("EXACT-POOL-FIRST", -1),
                    posting("EXACT-POOL-THIRD", max),
                    posting("EXACT-POOL-SECOND", -1),
                    posting("EXACT-POOL-THIRD", 2),
                ),
            )

        for (input in inputs) {
            val seeded = pool.shareValidated(Collections.unmodifiableList(input.toList()))
            assertSame(seeded, pool.shareValidated(Collections.unmodifiableList(input.toList())))
            if (input.isEmpty()) {
                val failure = assertFailsWith<IllegalArgumentException> { transaction("pool-empty", input) }
                assertEquals("ledger transaction postings must not be empty", failure.message)
            } else {
                assertMatchesOracle(input)
            }
        }
    }

    private fun assertMatchesOracle(input: List<LedgerPosting>) {
        val firstUnbalancedAsset =
            input
                .groupBy { it.assetId }
                .entries
                .firstOrNull { (_, postings) ->
                    val debit =
                        postings
                            .filter { it.side == LedgerPostingSide.DEBIT }
                            .fold(BigInteger.ZERO) { total, posting -> total.add(BigInteger.valueOf(posting.amount.value)) }
                    val credit =
                        postings
                            .filter { it.side == LedgerPostingSide.CREDIT }
                            .fold(BigInteger.ZERO) { total, posting -> total.add(BigInteger.valueOf(posting.amount.value)) }
                    debit != credit
                }?.key

        if (firstUnbalancedAsset == null) {
            assertEquals(input, transaction("oracle", input).postings)
        } else {
            val failure = assertFailsWith<IllegalArgumentException> { transaction("oracle", input) }
            assertEquals("ledger transaction must balance for asset ${firstUnbalancedAsset.value}", failure.message)
        }
    }

    private fun posting(
        asset: String,
        signedAmount: Long,
    ): LedgerPosting =
        LedgerPosting(
            accountId = "EXACT:$asset",
            assetId = AssetId(asset),
            side = if (signedAmount > 0) LedgerPostingSide.DEBIT else LedgerPostingSide.CREDIT,
            amount = Amount(if (signedAmount > 0) signedAmount else -signedAmount),
        )

    private fun transaction(
        id: String,
        postings: List<LedgerPosting>,
        time: Instant = Instant.parse("2026-10-27T00:00:00Z"),
        type: LedgerTransactionType = LedgerTransactionType.SETTLEMENT,
    ): LedgerTransaction = LedgerTransaction("ledger-$id", "event-$id", type, time, postings)
}

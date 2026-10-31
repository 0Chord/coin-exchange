package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 같은 분개 목록을 공유해도 용량 한도, 값의 불변성과 원장 검증 계약을 모두 지킨다. */
class LedgerPostingListSharingTest {
    @Test
    fun `equal posting values share while every transaction identity remains independent`() {
        val firstTime = Instant.parse("2026-10-27T00:00:00Z")
        val secondTime = firstTime.plusNanos(1)
        val first = transaction("first", balanced("IDENTITY"), firstTime, LedgerTransactionType.RESERVE)
        val second = transaction("second", balanced("IDENTITY"), secondTime, LedgerTransactionType.SETTLEMENT)

        assertNotSame(first, second)
        assertSame(first.postings, second.postings)
        assertEquals("ledger-first", first.ledgerTransactionId)
        assertEquals("event-first", first.sourceEventId)
        assertEquals(LedgerTransactionType.RESERVE, first.transactionType)
        assertEquals(firstTime, first.occurredAt)
        assertEquals("ledger-second", second.ledgerTransactionId)
        assertEquals("event-second", second.sourceEventId)
        assertEquals(LedgerTransactionType.SETTLEMENT, second.transactionType)
        assertEquals(secondTime, second.occurredAt)
    }

    @Test
    fun `mutable input cannot change a shared transaction snapshot on either cache miss or hit`() {
        val firstInput = balanced("MUTABLE").toMutableList()
        val expected = firstInput.toList()
        val first = transaction("mutable-first", firstInput)
        firstInput.clear()
        val secondInput = balanced("MUTABLE").toMutableList()
        val second = transaction("mutable-second", secondInput)
        secondInput[0] = secondInput[0].copy(accountId = "DIFFERENT")
        secondInput.clear()

        assertSame(first.postings, second.postings)
        assertEquals(expected, first.postings)
        assertEquals(expected, second.postings)
        assertFailsWith<UnsupportedOperationException> {
            (second.postings as MutableList<LedgerPosting>)[0] = expected[0].copy(accountId = "MODIFIED")
        }
        assertFailsWith<UnsupportedOperationException> {
            (first.postings as MutableList<LedgerPosting>).clear()
        }
        assertEquals(expected, first.postings)
    }

    @Test
    fun `hash collisions do not merge different full posting values`() {
        val pool = LedgerPostingListPool()
        val first = immutable(balanced("Aa"))
        val different = immutable(balanced("BB"))
        assertEquals(first.hashCode(), different.hashCode())
        assertTrue(first != different)

        val storedFirst = pool.shareValidated(first)
        val storedDifferent = pool.shareValidated(different)

        assertNotSame(storedFirst, storedDifferent)
        assertEquals(first, storedFirst)
        assertEquals(different, storedDifferent)
        assertSame(storedFirst, pool.shareValidated(immutable(balanced("Aa"))))
        assertSame(storedDifferent, pool.shareValidated(immutable(balanced("BB"))))
    }

    @Test
    fun `account asset direction amount order and list length all participate in equality`() {
        val pool = LedgerPostingListPool()
        val input = balanced("FIELDS")
        val original = pool.shareValidated(immutable(input))
        val variants =
            listOf(
                input.mapIndexed { index, posting -> if (index == 0) posting.copy(accountId = "OTHER") else posting },
                input.map { it.copy(assetId = AssetId("BTC")) },
                input.map { it.copy(side = if (it.side == LedgerPostingSide.DEBIT) LedgerPostingSide.CREDIT else LedgerPostingSide.DEBIT) },
                input.map { it.copy(amount = Amount(8)) },
                input.reversed(),
                input + input,
            )

        for (variant in variants) {
            val shared = pool.shareValidated(immutable(variant))
            assertNotSame(original, shared)
            assertEquals(variant, shared)
        }
        assertSame(original, pool.shareValidated(immutable(balanced("FIELDS"))))
    }

    @Test
    fun `entry limit evicts the least recently used batch without changing an earlier result`() {
        val pool = LedgerPostingListPool(maxEntries = 2, maxPostings = 20)
        val expectedB = balanced("B")
        val a = pool.shareValidated(immutable(balanced("A")))
        val b = pool.shareValidated(immutable(expectedB))
        assertSame(a, pool.shareValidated(immutable(balanced("A"))))
        val c = pool.shareValidated(immutable(balanced("C")))

        assertSame(a, pool.shareValidated(immutable(balanced("A"))))
        assertSame(c, pool.shareValidated(immutable(balanced("C"))))
        val newB = pool.shareValidated(immutable(balanced("B")))
        assertNotSame(b, newB)
        assertEquals(expectedB, b)
        assertEquals(b, newB)
        assertFailsWith<UnsupportedOperationException> { (b as MutableList<LedgerPosting>).clear() }
    }

    @Test
    fun `aggregate posting limit applies even when the entry limit has space`() {
        val pool = LedgerPostingListPool(maxEntries = 10, maxPostings = 4)
        val a = pool.shareValidated(immutable(balanced("A")))
        val b = pool.shareValidated(immutable(balanced("B")))
        val c = pool.shareValidated(immutable(balanced("C")))

        assertSame(b, pool.shareValidated(immutable(balanced("B"))))
        assertSame(c, pool.shareValidated(immutable(balanced("C"))))
        assertNotSame(a, pool.shareValidated(immutable(balanced("A"))))
        assertEquals(balanced("A"), a)
    }

    @Test
    fun `oversized lists and account or asset text bypass caching without evicting eligible values`() {
        val pool = LedgerPostingListPool(maxEntries = 2, maxPostings = 4, maxTextLength = 16)
        val eligible = pool.shareValidated(immutable(balanced("A")))
        val ineligible =
            listOf(
                balanced("BIG") + balanced("BIG") + balanced("BIG"),
                balanced("X".repeat(17)),
                balanced("TEXT", asset = "X".repeat(17)),
            )

        for (values in ineligible) {
            val first = immutable(values)
            val second = immutable(values)
            assertSame(first, pool.shareValidated(first))
            assertSame(second, pool.shareValidated(second))
            assertNotSame(first, second)
            assertEquals(first, second)
        }
        assertSame(eligible, pool.shareValidated(immutable(balanced("A"))))
    }

    @Test
    fun `cache eligibility bounds do not reject valid ledger values`() {
        val longAccount = transaction("long-account", balanced("X".repeat(257)))
        val longAsset = transaction("long-asset", balanced("ASSET", asset = "X".repeat(257)))
        val manyPostings = List(2050) { balanced("LARGE") }.flatten()
        val large = transaction("large", manyPostings)

        assertEquals(balanced("X".repeat(257)), longAccount.postings)
        assertEquals(balanced("ASSET", asset = "X".repeat(257)), longAsset.postings)
        assertEquals(4100, large.postings.size)
        assertEquals(manyPostings, large.postings)
    }

    @Test
    fun `warm cache never accepts empty unbalanced or cross asset unbalanced transactions`() {
        transaction("warm", balanced("INVALID"))
        val input = balanced("INVALID")

        assertFailsWith<IllegalArgumentException> { transaction("empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            transaction("unbalanced", listOf(input[0], input[1].copy(amount = Amount(6))))
        }
        assertFailsWith<IllegalArgumentException> {
            transaction("cross-asset", listOf(input[0], input[1].copy(assetId = AssetId("BTC"))))
        }
        assertEquals(input, transaction("valid-after", balanced("INVALID")).postings)
    }

    @Test
    fun `validation still balances totals larger than Long with BigInteger`() {
        val postings = balanced("OVERFLOW", amount = Long.MAX_VALUE)
        val input = postings + postings

        assertEquals(input, transaction("overflow", input).postings)
        assertEquals(input, transaction("overflow-hit", input.toList()).postings)
    }

    @Test
    fun `concurrent callers receive one immutable value without unsafe cache mutation`() {
        val pool = LedgerPostingListPool()
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures =
                List(4) {
                    executor.submit<List<LedgerPosting>> {
                        check(start.await(5, TimeUnit.SECONDS))
                        pool.shareValidated(immutable(balanced("CONCURRENT")))
                    }
                }
            start.countDown()
            val values = futures.map { it.get(5, TimeUnit.SECONDS) }
            values.forEach {
                assertSame(values.first(), it)
                assertEquals(balanced("CONCURRENT"), it)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun balanced(
        account: String,
        asset: String = "KRW",
        amount: Long = 7,
    ): List<LedgerPosting> =
        listOf(
            LedgerPosting("$account:DEBIT", AssetId(asset), LedgerPostingSide.DEBIT, Amount(amount)),
            LedgerPosting("$account:CREDIT", AssetId(asset), LedgerPostingSide.CREDIT, Amount(amount)),
        )

    private fun immutable(values: List<LedgerPosting>): List<LedgerPosting> = Collections.unmodifiableList(values.toList())

    private fun transaction(
        id: String,
        postings: List<LedgerPosting>,
        time: Instant = Instant.parse("2026-10-27T00:00:00Z"),
        type: LedgerTransactionType = LedgerTransactionType.SETTLEMENT,
    ): LedgerTransaction = LedgerTransaction("ledger-$id", "event-$id", type, time, postings)
}

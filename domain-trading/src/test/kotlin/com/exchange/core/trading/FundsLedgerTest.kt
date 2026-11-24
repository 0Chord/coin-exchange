package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FundsLedgerTest {
    @Test
    fun `같은 정산 로그 위치의 일부를 확인해도 남은 원장 순번을 복원한다`() {
        val lineage = ClusterLineage(1, 2)
        val ledger = FundsLedger(lineage)
        val asset = AssetId("USDT")
        for (user in listOf("first", "second")) {
            val release =
                LedgerTransaction(
                    "release-$user",
                    "cancel-$user",
                    LedgerTransactionType.RELEASE,
                    Instant.EPOCH,
                    listOf(
                        LedgerPosting("USER:$user:USDT:HOLD", asset, LedgerPostingSide.DEBIT, Amount(100)),
                        LedgerPosting("USER:$user:USDT:AVAILABLE", asset, LedgerPostingSide.CREDIT, Amount(100)),
                    ),
                )
            ledger.record(assertNotNull(ledger.prepare(64L, listOf(release))))
        }

        assertTrue(ledger.acknowledge(1L))
        val restored = FundsLedger(lineage)
        restored.restore(ledger.snapshot())
        assertEquals(ledger.progress(), restored.progress())
        assertNull(restored.read(1L))
        val remaining = assertNotNull(restored.read(2L))
        assertEquals(64L, remaining.logPosition)
        assertEquals("release-second", remaining.transactions.single().ledgerTransactionId)
        assertFailsWith<IllegalArgumentException> { restored.prepare(63L, remaining.transactions) }
    }

    @Test
    fun `원장 묶음의 로그 위치 중복을 거절하고 확인한 다음 순번부터 복원한다`() {
        val lineage = ClusterLineage(1, 2)
        val ledger = FundsLedger(lineage)
        val first = OpeningBalance("opening-1", UserId("user-1"), AssetId("USDT"), Amount(100))
        val second = OpeningBalance("opening-2", UserId("user-2"), AssetId("USDT"), Amount(100))
        val firstTransaction = first.transaction("ledger-1", Instant.EPOCH)
        val secondTransactions = listOf(second.transaction("ledger-2", Instant.EPOCH))
        ledger.record(assertNotNull(ledger.prepare(64L, listOf(firstTransaction))))

        val originalProgress = ledger.progress()
        assertFailsWith<IllegalArgumentException> { ledger.prepare(64L, secondTransactions) }
        assertFailsWith<IllegalArgumentException> { ledger.record(FundsLedgerBatch(lineage, 2L, 64L, secondTransactions)) }
        assertEquals(originalProgress, ledger.progress())

        ledger.record(assertNotNull(ledger.prepare(65L, secondTransactions)))

        assertTrue(ledger.acknowledge(1L))
        val saved = ledger.snapshot()
        assertEquals(1L, saved.retiredThrough)
        assertEquals(64L, saved.retiredLogPosition)
        assertEquals(2L, saved.batches.single().sequence)
        assertEquals(65L, saved.batches.single().logPosition)

        val restored = FundsLedger(lineage)
        restored.restore(saved)
        assertEquals(ledger.progress(), restored.progress())
        assertNull(restored.read(1L))
        assertEquals(secondTransactions, assertNotNull(restored.read(2L)).transactions)
        assertFailsWith<IllegalArgumentException> { restored.prepare(64L, secondTransactions) }
        assertFailsWith<IllegalArgumentException> { restored.prepare(65L, secondTransactions) }
        assertTrue(restored.acknowledge(2L))
        assertEquals(2L, restored.progress().retiredThrough)
        assertNull(restored.read(2L))
    }
}

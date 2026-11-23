package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
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
    fun `같은 로그 위치의 원장은 순번별로 확인하고 스냅샷에서 이어 복원한다`() {
        val lineage = ClusterLineage(1, 2)
        val ledger = FundsLedger(lineage)
        for (index in 1..2) {
            val opening = OpeningBalance("opening-$index", UserId("user-$index"), AssetId("USDT"), Amount(100))
            val transaction = opening.transaction("ledger-$index", Instant.EPOCH)
            val batch = assertNotNull(ledger.prepare(64L, listOf(transaction)))
            ledger.record(batch)
        }

        assertTrue(ledger.acknowledge(1L))
        val saved = ledger.snapshot()
        assertEquals(1L, saved.retiredThrough)
        assertEquals(64L, saved.retiredLogPosition)
        assertEquals(2L, saved.batches.single().sequence)
        assertEquals(64L, saved.batches.single().logPosition)

        val restored = FundsLedger(lineage)
        restored.restore(saved)
        assertEquals(ledger.progress(), restored.progress())
        assertNull(restored.read(1L))
        val second = assertNotNull(restored.read(2L))
        assertFailsWith<IllegalArgumentException> { restored.prepare(63L, second.transactions) }
        assertTrue(restored.acknowledge(2L))
        assertEquals(2L, restored.progress().retiredThrough)
        assertNull(restored.read(2L))
    }
}

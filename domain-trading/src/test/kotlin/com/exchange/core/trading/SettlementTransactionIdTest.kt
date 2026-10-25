package com.exchange.core.trading

import com.exchange.core.common.MarketId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SettlementTransactionIdTest {
    @Test
    fun `명세의 실제 사건 순번은 57자 고정 원장 ID를 만든다`() {
        val examples =
            listOf(
                Triple("BTC-USDT", 3L, "SETTLEMENT:v1:g-vDKCVwQ6mt0t1hw3lYL7amsJsrH-kDq5PqpXy85hQ"),
                Triple("BTC-USDT", 4L, "SETTLEMENT:v1:TmbW_fQXcNhOewi7RiUrnt2AVcIuPLESBGHraFBIxkU"),
                Triple("SUI-USDT", 3L, "SETTLEMENT:v1:3JRBJ0su-0uiGKhdC7z7YVnu8N6MZW-_WiHrBgtWKPM"),
            )
        examples.forEach { (market, sequence, expected) ->
            val actual = settlementTransactionId(MarketId(market), sequence)
            assertEquals(expected, actual)
            assertEquals(57, actual.length)
            assertEquals(actual, settlementTransactionId(MarketId(market), sequence))
        }
    }

    @Test
    fun `UTF8 원문과 Long 최대 순번을 줄이거나 다른 순번으로 바꾸지 않는다`() {
        val unicode = settlementTransactionId(MarketId("비트:BTC/USDT"), 3)
        assertEquals("SETTLEMENT:v1:r525DHnbd6pAGDiy9ttqxFSFv1vTxG3pU6oVhCy4PXY", unicode)
        val maximum = settlementTransactionId(MarketId("BTC-USDT"), Long.MAX_VALUE)
        assertEquals("SETTLEMENT:v1:p9W9-5_rC8kxSZntkxd3B9XzNwRYkxOxkTF--KUh-Q4", maximum)
        assertEquals(57, maximum.length)
        assertNotEquals(maximum, settlementTransactionId(MarketId("BTC-USDT"), Long.MAX_VALUE - 1))
        assertNotEquals(unicode, settlementTransactionId(MarketId("비트:BTC/USDT"), 4))
    }

    @Test
    fun `0과 음수 사건 순번으로 원장 ID를 만들지 않는다`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { sequence ->
            assertFailsWith<IllegalArgumentException> { settlementTransactionId(MarketId("BTC-USDT"), sequence) }
        }
    }
}

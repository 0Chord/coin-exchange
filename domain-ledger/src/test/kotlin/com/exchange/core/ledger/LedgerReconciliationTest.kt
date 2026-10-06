package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.UserId
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerReconciliationTest {
    private val buyer = UserId("buyer")
    private val krw = AssetId("KRW")
    private val scope = LedgerReconciliationScope(MarketId("BTC-KRW"), AssetId("BTC"), krw)

    @Test
    fun `원장과 잔고와 활성 예약 700 300 300은 일치한다`() {
        val report = LedgerReconciliation().compare(scope, reservedSnapshot(700))

        assertEquals(ReconciliationStatus.MATCHED, report.status)
        assertEquals(1, report.accounts.size)
        val account = report.accounts.single()
        assertEquals(buyer, account.userId)
        assertEquals(krw, account.assetId)
        assertEquals(BigInteger.valueOf(700), account.ledgerAvailable)
        assertEquals(BigInteger.valueOf(300), account.ledgerHold)
        assertEquals(BigInteger.valueOf(300), account.reservationHold)
        assertEquals(BigInteger.valueOf(700), account.available)
        assertEquals(BigInteger.valueOf(300), account.hold)
        assertEquals(emptyList(), report.differences)
    }

    @Test
    fun `DB available만 701이면 사용자와 자산과 차이 1을 보고한다`() {
        val report = LedgerReconciliation().compare(scope, reservedSnapshot(701))

        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        val difference = report.differences.single()
        assertEquals(buyer, difference.userId)
        assertEquals(krw, difference.assetId)
        assertEquals(ReconciliationItem.AVAILABLE, difference.item)
        assertEquals(BigInteger.valueOf(700), difference.expected)
        assertEquals(BigInteger.valueOf(701), difference.actual)
        assertEquals(BigInteger.ONE, difference.delta)
    }

    @Test
    fun `총액이 같아도 available hold 예약의 세 차이를 따로 보고한다`() {
        val original = reservedSnapshot(700)
        val changed =
            LedgerReconciliationSnapshot(
                original.transactions,
                listOf(Balance(buyer, krw, Amount(800), Amount(200))),
                original.reservations,
            )
        val report = LedgerReconciliation().compare(scope, changed)
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertEquals(
            mapOf(ReconciliationItem.AVAILABLE to 100L, ReconciliationItem.HOLD to -100L, ReconciliationItem.RESERVATION_HOLD to -100L),
            report.differences.associate { it.item to it.delta!!.longValueExact() },
        )
    }

    @Test
    fun `빈 대상과 원장 없는 영 잔고는 서로 다른 결과다`() {
        val empty = LedgerReconciliation().compare(scope, LedgerReconciliationSnapshot(emptyList(), emptyList(), emptyList()))
        assertEquals(ReconciliationStatus.EMPTY, empty.status)
        assertEquals(emptyList(), empty.accounts)
        val zero =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(emptyList(), listOf(Balance(buyer, krw, Amount.ZERO, Amount.ZERO)), emptyList()),
            )
        assertEquals(ReconciliationStatus.MATCHED, zero.status)
        assertEquals(BigInteger.ZERO, zero.accounts.single().ledgerAvailable)
    }

    @Test
    fun `원장과 예약에만 있는 사용자도 누락된 잔고를 보고한다`() {
        val original = reservedSnapshot(700)
        val report =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(original.transactions, emptyList(), original.reservations),
            )
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertEquals(ReconciliationItem.MISSING_BALANCE, report.differences.single().item)
        assertNull(report.accounts.single().available)
        assertNull(report.differences.single().actual)
        assertNull(report.differences.single().delta)
        val orphan =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(emptyList(), emptyList(), original.reservations),
            )
        assertEquals(ReconciliationItem.MISSING_BALANCE, orphan.differences.single().item)
    }

    @Test
    fun `Long 범위를 넘는 원장 합계와 차이도 정확히 계산한다`() {
        val transactions =
            listOf("first", "second").map {
                OpeningBalance(it, buyer, krw, Amount(Long.MAX_VALUE)).transaction(it, Instant.EPOCH)
            }
        val report =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(transactions, listOf(Balance(buyer, krw, Amount(Long.MAX_VALUE), Amount.ZERO)), emptyList()),
            )
        val expected = BigInteger("18446744073709551614")
        assertEquals(expected, report.accounts.single().ledgerAvailable)
        assertEquals(BigInteger("-9223372036854775807"), report.differences.single().delta)
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
    }

    @Test
    fun `역분개도 합산하며 자산 간 차이를 상쇄하지 않는다`() {
        val btc = AssetId("BTC")
        val opening = OpeningBalance("seed", buyer, krw, Amount(100)).transaction("open", Instant.EPOCH)
        val reverse =
            LedgerTransaction(
                "reverse",
                "reverse-event",
                LedgerTransactionType.REVERSAL,
                Instant.EPOCH,
                opening.postings.map {
                    it.copy(
                        side =
                            if (it.side ==
                                LedgerPostingSide.CREDIT
                            ) {
                                LedgerPostingSide.DEBIT
                            } else {
                                LedgerPostingSide.CREDIT
                            },
                    )
                },
            )
        val reversalReport =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(listOf(opening, reverse), listOf(Balance(buyer, krw, Amount.ZERO, Amount.ZERO)), emptyList()),
            )
        assertEquals(ReconciliationStatus.MATCHED, reversalReport.status)
        val second = OpeningBalance("btc", buyer, btc, Amount(100)).transaction("btc", Instant.EPOCH)
        val report =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(
                    listOf(opening, second),
                    listOf(Balance(buyer, krw, Amount(101), Amount.ZERO), Balance(buyer, btc, Amount(99), Amount.ZERO)),
                    emptyList(),
                ),
            )
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertEquals(listOf(BigInteger.valueOf(-1), BigInteger.ONE), report.differences.map { it.delta })
    }

    @Test
    fun `종료된 예약은 대상에 남지만 활성 hold에 더하지 않는다`() {
        val snapshot =
            LedgerReconciliationSnapshot(
                emptyList(),
                listOf(Balance(buyer, krw, Amount.ZERO, Amount.ZERO)),
                listOf("RELEASED", "SETTLED").map { ReservationHold("BTC-KRW", it, buyer, krw, it, 0) },
            )
        val report = LedgerReconciliation().compare(scope, snapshot)
        assertEquals(ReconciliationStatus.MATCHED, report.status)
        assertEquals(BigInteger.ZERO, report.accounts.single().reservationHold)
    }

    @Test
    fun `같은 자산의 다른 마켓 활성 예약은 비교 범위를 지원하지 않는다`() {
        val report =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(
                    emptyList(),
                    emptyList(),
                    listOf(ReservationHold("ETH-KRW", "other", buyer, krw, "ACTIVE", 1)),
                ),
            )
        assertEquals(ReconciliationStatus.UNAVAILABLE, report.status)
        assertEquals(ReconciliationFailure.OTHER_MARKET_HOLD, report.failure)
        assertTrue(report.accounts.isEmpty())
    }

    @Test
    fun `손상된 기록이 있으면 부분 합계를 원장 잔고로 제시하지 않는다`() {
        val original = reservedSnapshot(700)
        val report =
            LedgerReconciliation().compare(
                scope,
                LedgerReconciliationSnapshot(
                    original.transactions,
                    original.balances,
                    original.reservations,
                    listOf(ReconciliationDifference(ReconciliationItem.INVALID_RECORD, source = "broken", reason = "분개 누락")),
                ),
            )
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertEquals("broken", report.differences.single().source)
        assertNull(report.accounts.single().ledgerAvailable)
        assertNull(report.accounts.single().ledgerHold)
    }

    @Test
    fun `사용자 ID의 콜론은 보존하고 계정 자산 불일치는 손상으로 보고한다`() {
        val user = UserId("group:buyer")

        fun snapshot(account: String) =
            LedgerReconciliationSnapshot(
                listOf(
                    LedgerTransaction(
                        "t",
                        "e",
                        LedgerTransactionType.OPENING,
                        Instant.EPOCH,
                        listOf(
                            LedgerPosting(account, krw, LedgerPostingSide.CREDIT, Amount(5)),
                            LedgerPosting("SYSTEM:KRW:DEVELOPMENT_FUNDING", krw, LedgerPostingSide.DEBIT, Amount(5)),
                        ),
                    ),
                ),
                listOf(Balance(user, krw, Amount(5), Amount.ZERO)),
                emptyList(),
            )
        val good = LedgerReconciliation().compare(scope, snapshot("USER:group:buyer:KRW:AVAILABLE"))
        assertEquals(ReconciliationStatus.MATCHED, good.status)
        assertEquals(user, good.accounts.single().userId)
        val bad = LedgerReconciliation().compare(scope, snapshot("USER:group:buyer:BTC:AVAILABLE"))
        assertEquals(ReconciliationStatus.MISMATCHED, bad.status)
        assertEquals(ReconciliationItem.INVALID_RECORD, bad.differences.single().item)
    }

    @Test
    fun `손상된 예약은 무시하지 않고 검사 입력 목록도 외부 수정으로 바뀌지 않는다`() {
        val reservations = mutableListOf(ReservationHold("BTC-KRW", "bad", buyer, krw, "RELEASED", 5))
        val snapshot = LedgerReconciliationSnapshot(emptyList(), emptyList(), reservations)
        reservations.clear()
        val report = LedgerReconciliation().compare(scope, snapshot)
        assertEquals(ReconciliationStatus.MISMATCHED, report.status)
        assertTrue(report.differences.any { it.item == ReconciliationItem.INVALID_RECORD })
        assertEquals(1, snapshot.reservations.size)
        assertFailsWith<UnsupportedOperationException> { (snapshot.reservations as MutableList).clear() }
    }

    @Test
    fun `같은 자산과 와일드카드 범위는 입력에서 거절한다`() {
        assertFailsWith<IllegalArgumentException> { LedgerReconciliationScope(MarketId("market"), krw, krw) }
        assertFailsWith<IllegalArgumentException> { LedgerReconciliationScope(MarketId("*"), AssetId("BTC"), krw) }
    }

    private fun reservedSnapshot(available: Long) =
        LedgerReconciliationSnapshot(
            transactions =
                listOf(
                    OpeningBalance("seed", buyer, krw, Amount(1000)).transaction("opening", Instant.EPOCH),
                    LedgerTransaction(
                        "reserve",
                        "reserve-event",
                        LedgerTransactionType.RESERVE,
                        Instant.EPOCH,
                        listOf(
                            LedgerPosting("USER:buyer:KRW:AVAILABLE", krw, LedgerPostingSide.DEBIT, Amount(300)),
                            LedgerPosting("USER:buyer:KRW:HOLD", krw, LedgerPostingSide.CREDIT, Amount(300)),
                        ),
                    ),
                ),
            balances = listOf(Balance(buyer, krw, Amount(available), Amount(300))),
            reservations = listOf(ReservationHold("BTC-KRW", "buy-1", buyer, krw, "ACTIVE", 300)),
        )
}

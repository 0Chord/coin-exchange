package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingCancelInboxTest {
    private val user = UserId("user")
    private val intent = CancelIntent(MarketId("A-QUOTE"), OrderId("order"), 7)
    private val accepted = CancelReceipt(CancelCode.ACCEPTED, "C2" + "a".repeat(38), intent.targetOrderId, 1)
    private val rejected = CancelReceipt(CancelCode.REJECTED, reason = "NOT_ACTIVE")
    private val order = OrderIntent(intent.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
    private val admission = AdmissionReceipt(AdmissionCode.ACCEPTED, OrderId("O2order"), 1, 1)
    private val limits = TradingResourceLimits(requestWindow = 4, maxInboxEntries = 4)

    @Test
    fun `취소 조회는 채널을 발급하지 않고 발급되지 않은 세대를 거절한다`() {
        val inbox = TradingRequestInbox(limits)
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.UNKNOWN_CHANNEL), inbox.lookupCancel(token(1), intent))
        assertEquals(0, inbox.channelCount)
        inbox.rotateEpoch(user, 0, 0)
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.UNISSUED_EPOCH), inbox.lookupCancel(token(1, 2), intent))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookupCancel(token(5), intent))
        assertEquals(0, inbox.entryCount)
    }

    @Test
    fun `승인과 거절 모두 최초 응답을 유지하고 대상 마켓 정책 변경은 충돌한다`() {
        for (receipt in listOf(accepted, rejected)) {
            val inbox = opened()
            inbox.rememberCancel(token(1), intent, receipt)
            val before = inbox.snapshot()
            inbox.rememberCancel(token(1), intent, receipt)
            assertEquals(CancelInboxLookup.Repeated(receipt), inbox.lookupCancel(token(1), intent))
            for (different in listOf(
                intent.copy(targetOrderId = OrderId("other")),
                intent.copy(marketId = MarketId("B-QUOTE")),
                intent.copy(expectedConfigurationVersion = 8),
            )) {
                assertEquals(CancelInboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), inbox.lookupCancel(token(1), different))
            }
            assertFailsWith<IllegalStateException> {
                inbox.rememberCancel(
                    token(1),
                    intent,
                    if (receipt ==
                        accepted
                    ) {
                        rejected
                    } else {
                        accepted
                    },
                )
            }
            assertEquals(before, inbox.snapshot())
            assertEquals(1, inbox.entryCount)
            assertEquals(480L, inbox.canonicalBytes)
        }
    }

    @Test
    fun `신규 주문과 취소는 같은 토큰을 양쪽 순서 모두에서 재사용하지 못한다`() {
        val firstOrder = opened()
        firstOrder.remember(token(1), order, admission)
        val before = firstOrder.snapshot()
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), firstOrder.lookupCancel(token(1), intent))
        assertFailsWith<IllegalStateException> { firstOrder.rememberCancel(token(1), intent, accepted) }
        assertEquals(before, firstOrder.snapshot())
        val firstCancel = opened()
        firstCancel.rememberCancel(token(1), intent, accepted)
        assertEquals(InboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), firstCancel.lookup(token(1), order))
        assertFailsWith<IllegalStateException> { firstCancel.remember(token(1), order, admission) }
        assertEquals(CancelInboxLookup.Repeated(accepted), firstCancel.lookupCancel(token(1), intent))
        assertEquals(1, firstCancel.entryCount)
    }

    @Test
    fun `혼합 순번의 구멍을 ACK로 건너뛰지 못하고 확인된 두 종류만 회수한다`() {
        val inbox = opened()
        inbox.remember(token(1), order, admission)
        inbox.rememberCancel(token(3), intent, rejected)
        val before = inbox.snapshot()
        assertEquals(AckStatus.FUTURE_ACK, inbox.acknowledge(user, 0, 1, 3).status)
        assertEquals(before, inbox.snapshot())
        inbox.rememberCancel(token(2), intent, accepted)
        assertEquals(
            3L,
            inbox
                .snapshot()
                .channels
                .single()
                .processedThrough,
        )
        assertEquals(AckStatus.ACKNOWLEDGED, inbox.acknowledge(user, 0, 1, 2).status)
        assertEquals(1, inbox.entryCount)
        assertEquals(480L, inbox.canonicalBytes)
        assertEquals(CancelInboxLookup.Repeated(rejected), inbox.lookupCancel(token(3), intent))
        assertEquals(InboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), inbox.lookup(token(1), order))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), inbox.lookupCancel(token(2), intent))
        inbox.acknowledge(user, 0, 1, 3)
        assertEquals(0, inbox.entryCount)
        assertEquals(0L, inbox.canonicalBytes)
    }

    @Test
    fun `종류를 합친 건수와 byte 한도가 차도 최초 응답은 읽고 ACK 뒤 공간을 재사용한다`() {
        for (small in listOf(limits.copy(maxInboxEntries = 1), limits.copy(maxInboxBytes = 480))) {
            val inbox = opened(small)
            inbox.rememberCancel(token(1), intent, accepted)
            assertEquals(CancelInboxLookup.Repeated(accepted), inbox.lookupCancel(token(1), intent))
            assertEquals(InboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookup(token(2), order))
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookupCancel(token(2), intent))
            inbox.acknowledge(user, 0, 1, 1)
            inbox.rememberCancel(token(2), intent, rejected)
            assertEquals(480L, inbox.canonicalBytes)
        }
        val tooSmall = opened(limits.copy(maxInboxBytes = 479))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), tooSmall.lookupCancel(token(1), intent))
        assertEquals(0, tooSmall.entryCount)
        val shortIds = opened(limits.copy(maxIdentifierBytes = 4))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.INVALID_IDENTIFIER), shortIds.lookupCancel(token(1), intent))
    }

    @Test
    fun `세대 변경은 두 종류 기록을 회수하고 이전 세대를 다시 실행하지 않는다`() {
        val inbox = opened()
        inbox.remember(token(1), order, admission)
        inbox.rememberCancel(token(2), intent, accepted)
        assertEquals(EpochReceipt(EpochStatus.ISSUED, 2), inbox.rotateEpoch(user, 0, 1))
        assertEquals(EpochReceipt(EpochStatus.ALREADY_ISSUED, 2), inbox.rotateEpoch(user, 0, 1))
        assertEquals(0, inbox.entryCount)
        assertEquals(0L, inbox.canonicalBytes)
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.FENCED_EPOCH), inbox.lookupCancel(token(2), intent))
        assertEquals(CancelInboxLookup.Ready, inbox.lookupCancel(token(1, 2), intent))
    }

    @Test
    fun `혼합 스냅샷은 독립 복원되며 중복 순번과 잘못된 prefix는 빈 소유자를 오염시키지 않는다`() {
        val inbox = opened()
        inbox.remember(token(1), order, admission)
        inbox.rememberCancel(token(2), intent, accepted)
        val snapshot = inbox.snapshot()
        val restored = TradingRequestInbox(limits).also { it.restore(snapshot) }
        assertEquals(snapshot, restored.snapshot())
        assertEquals(inbox.canonicalBytes, restored.canonicalBytes)
        assertEquals(CancelInboxLookup.Repeated(accepted), restored.lookupCancel(token(2), intent))
        inbox.acknowledge(user, 0, 1, 2)
        assertEquals(2, restored.entryCount)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.channels.single().cancelEntries as MutableList<TradingCancelInboxEntrySnapshot>).clear()
        }
        val channel = snapshot.channels.single()
        val cancel = channel.cancelEntries.single()
        for (invalid in listOf(
            channel.copy(cancelEntries = listOf(cancel.copy(sequence = 1))),
            channel.copy(cancelEntries = listOf(cancel, cancel)),
            channel.copy(entries = emptyList()),
            channel.copy(processedThrough = 1),
        )) {
            val empty = TradingRequestInbox(limits)
            assertFailsWith<IllegalArgumentException> { empty.restore(TradingInboxSnapshot(listOf(invalid))) }
            assertEquals(0, empty.channelCount)
            assertEquals(0, empty.entryCount)
            assertEquals(0L, empty.canonicalBytes)
            empty.restore(snapshot)
            assertEquals(snapshot, empty.snapshot())
        }
        assertFailsWith<IllegalArgumentException> { TradingRequestInbox(limits.copy(maxInboxEntries = 1)).restore(snapshot) }
    }

    @Test
    fun `Long 끝의 취소 기록도 ACK와 세대 변경에서 넘치지 않는다`() {
        val entry = TradingCancelInboxEntrySnapshot(Long.MAX_VALUE, intent, rejected)
        val inbox = TradingRequestInbox(limits)
        inbox.restore(
            TradingInboxSnapshot(
                listOf(
                    TradingInboxChannelSnapshot(user, 0, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE, emptyList(), listOf(entry)),
                ),
            ),
        )
        assertEquals(AckStatus.ACKNOWLEDGED, inbox.acknowledge(user, 0, Long.MAX_VALUE, Long.MAX_VALUE).status)
        assertEquals(0L, inbox.canonicalBytes)
        assertEquals(0, inbox.entryCount)
        assertEquals(EpochStatus.EXHAUSTED, inbox.rotateEpoch(user, 0, Long.MAX_VALUE).status)
    }

    @Test
    fun `요청과 응답 문자열은 고정 슬롯의 UTF8 byte 상한을 지킨다`() {
        val boundary = OrderId("가".repeat(85) + "a")
        CancelIntent(intent.marketId, boundary, 7)
        CancelReceipt(CancelCode.ACCEPTED, accepted.cancelId, boundary, 1)
        CancelReceipt(CancelCode.REJECTED, targetOrderId = boundary, reason = "가".repeat(21) + "a")
        assertFailsWith<IllegalArgumentException> { intent.copy(targetOrderId = OrderId(boundary.value + "a")) }
        assertFailsWith<IllegalArgumentException> { accepted.copy(targetOrderId = OrderId(boundary.value + "a")) }
        assertFailsWith<IllegalArgumentException> { accepted.copy(cancelId = "C2" + "가".repeat(38)) }
        assertFailsWith<IllegalArgumentException> { rejected.copy(reason = "가".repeat(22)) }
        assertFailsWith<IllegalArgumentException> { rejected.copy(reason = " ") }
    }

    @Test
    fun `두 금융 복구 값은 주문과 취소의 최초 응답 및 채널 경계를 그대로 보존한다`() {
        val inbox =
            opened()
                .also {
                    it.remember(token(1), order, admission)
                    it.rememberCancel(token(2), intent, accepted)
                    it.rememberCancel(token(3), intent, rejected)
                }.snapshot()
        for (project in financialProjections()) {
            assertEquals(inbox, project(inbox))
        }
    }

    @Test
    fun `두 금융 복구 값의 주문과 취소 목록은 입력과 독립적이며 직접 변경할 수 없다`() {
        for (project in financialProjections()) {
            val expectedOrder = TradingInboxEntrySnapshot(1, order, admission)
            val expectedCancel = TradingCancelInboxEntrySnapshot(2, intent, accepted)
            val cancellations = mutableListOf(expectedCancel)
            val entries = mutableListOf(expectedOrder)
            val channels = mutableListOf(TradingInboxChannelSnapshot(user, 0, 1, 0, 2, entries, cancellations))
            val projected = project(TradingInboxSnapshot(channels))
            cancellations.clear()
            entries.clear()
            channels.clear()
            assertEquals(listOf(expectedOrder), projected.channels.single().entries)
            assertEquals(listOf(expectedCancel), projected.channels.single().cancelEntries)
            assertFailsWith<UnsupportedOperationException> {
                (projected.channels as MutableList<TradingInboxChannelSnapshot>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (projected.channels.single().entries as MutableList<TradingInboxEntrySnapshot>).clear()
            }
            assertFailsWith<UnsupportedOperationException> {
                (projected.channels.single().cancelEntries as MutableList<TradingCancelInboxEntrySnapshot>).clear()
            }
        }
    }

    private fun financialProjections(): List<(TradingInboxSnapshot) -> TradingInboxSnapshot> {
        val manifest = FundsResultTestFixture().manifest
        return listOf(
            { inbox ->
                TradingRecoveryState(
                    manifest,
                    TradingLifecycle.UNINITIALIZED,
                    emptyList(),
                    emptyMap(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    MatchingCheckpoint(emptyList()),
                    inbox,
                    0,
                    0,
                    0,
                ).inbox
            },
            { inbox ->
                BoundedFundingRecoveryState(
                    manifest,
                    TradingLifecycle.UNINITIALIZED,
                    emptyList(),
                    emptyMap(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    inbox,
                    0,
                    0,
                    0,
                ).inbox
            },
        )
    }

    private fun token(
        sequence: Long,
        epoch: Long = 1,
    ) = RequestToken(user, 0, epoch, sequence)

    private fun opened(resourceLimits: TradingResourceLimits = limits) =
        TradingRequestInbox(resourceLimits).also { assertEquals(EpochStatus.ISSUED, it.rotateEpoch(user, 0, 0).status) }
}

package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingRequestInboxTest {
    private val user = UserId("user-1")
    private val lineage = ClusterLineage(1, 2)
    private val intent = OrderIntent(MarketId("BTC-USDT"), 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
    private val receipt = AdmissionReceipt(AdmissionCode.ACCEPTED, TradingIdentifiers.order(lineage, 1), 1, 1)
    private val limits = TradingResourceLimits(requestWindow = 4, maxInboxEntries = 4)

    @Test
    fun `데이터 요청은 channel을 발급하지 않는다`() {
        val inbox = TradingRequestInbox(limits)
        assertEquals(InboxLookup.Rejected(InboxRejection.UNKNOWN_CHANNEL), inbox.lookup(token(1), intent))
        assertEquals(0, inbox.channelCount)
        assertEquals(0, inbox.accountCount)
        assertEquals(0, inbox.entryCount)
    }

    @Test
    fun `같은 최초 응답을 재사용하고 본문 변경은 충돌한다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        assertEquals(InboxLookup.Repeated(receipt), inbox.lookup(token(1), intent))
        assertEquals(
            InboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT),
            inbox.lookup(token(1), intent.copy(quantity = Quantity(4))),
        )
        assertEquals(
            InboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT),
            inbox.lookup(token(1), intent.copy(expectedConfigurationVersion = 8)),
        )
        assertEquals(1, inbox.entryCount)
        assertFailsWith<IllegalStateException> { inbox.remember(token(1), intent, receipt.copy(admissionSequence = 2)) }
        assertEquals(InboxLookup.Repeated(receipt), inbox.lookup(token(1), intent))
    }

    @Test
    fun `구멍을 건너뛰는 ACK를 저장하지 않고 나중에 결과도 자동 삭제하지 않는다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        inbox.remember(token(3), intent, receipt)
        assertEquals(AckStatus.FUTURE_ACK, inbox.acknowledge(user, 0, 1, 3).status)
        assertEquals(
            0L,
            inbox
                .snapshot()
                .channels
                .single()
                .retiredThrough,
        )
        inbox.remember(token(2), intent, receipt)
        assertEquals(3, inbox.entryCount)
        assertEquals(InboxLookup.Repeated(receipt), inbox.lookup(token(3), intent))
        assertEquals(AckStatus.ACKNOWLEDGED, inbox.acknowledge(user, 0, 1, 3).status)
        assertEquals(0, inbox.entryCount)
        assertEquals(0L, inbox.canonicalBytes)
        assertEquals(InboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), inbox.lookup(token(1), intent))
        assertEquals(AckStatus.ACKNOWLEDGED, inbox.acknowledge(user, 0, 1, 2).status)
        assertEquals(
            3L,
            inbox
                .snapshot()
                .channels
                .single()
                .retiredThrough,
        )
        assertEquals(InboxLookup.Ready, inbox.lookup(token(4), intent))
    }

    @Test
    fun `창 밖 재시도는 요청 순번을 소비하지 않는다`() {
        val inbox = opened()
        assertEquals(InboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookup(token(5), intent))
        assertEquals(0, inbox.entryCount)
        inbox.remember(token(1), intent, receipt)
        inbox.acknowledge(user, 0, 1, 1)
        assertEquals(InboxLookup.Ready, inbox.lookup(token(5), intent))
    }

    @Test
    fun `전체 cap가 차도 이미 저장한 최초 결과는 반환한다`() {
        val inbox = opened(limits.copy(maxInboxEntries = 1))
        inbox.remember(token(1), intent, receipt)
        assertEquals(InboxLookup.Repeated(receipt), inbox.lookup(token(1), intent))
        assertEquals(InboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookup(token(2), intent))
        inbox.acknowledge(user, 0, 1, 1)
        assertEquals(InboxLookup.Ready, inbox.lookup(token(2), intent))
    }

    @Test
    fun `byte 예산을 승인과 거절에 동일하게 선확보한다`() {
        val bytes = TradingRequestInbox.canonicalEntryBytes(user, intent)
        val inbox = opened(limits.copy(maxInboxBytes = bytes))
        assertEquals(InboxLookup.Ready, inbox.lookup(token(1), intent))
        inbox.remember(token(1), intent, receipt)
        assertEquals(bytes, inbox.canonicalBytes)
        assertEquals(InboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), inbox.lookup(token(2), intent))
        inbox.acknowledge(user, 0, 1, 1)
        val rejection = AdmissionReceipt(AdmissionCode.REJECTED, reason = OrderAdmissionResult.Reason.UNSUPPORTED_ORDER_CONDITION)
        inbox.remember(token(2), intent, rejection)
        assertEquals(bytes, inbox.canonicalBytes)
        assertEquals(InboxLookup.Repeated(rejection), inbox.lookup(token(2), intent))
    }

    @Test
    fun `epoch 교체 재전송은 다시 증가하지 않고 옛 요청은 fenced 된다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        assertEquals(EpochReceipt(EpochStatus.ISSUED, 2), inbox.rotateEpoch(user, 0, 1))
        assertEquals(EpochReceipt(EpochStatus.ALREADY_ISSUED, 2), inbox.rotateEpoch(user, 0, 1))
        assertEquals(0, inbox.entryCount)
        assertEquals(0L, inbox.canonicalBytes)
        assertEquals(InboxLookup.Rejected(InboxRejection.FENCED_EPOCH), inbox.lookup(token(1), intent))
        assertEquals(InboxLookup.Ready, inbox.lookup(token(1, epoch = 2), intent))
        assertEquals(InboxLookup.Rejected(InboxRejection.UNISSUED_EPOCH), inbox.lookup(token(1, epoch = 3), intent))
        assertEquals(AckStatus.FENCED_EPOCH, inbox.acknowledge(user, 0, 1, 0).status)
        assertEquals(EpochStatus.SUPERSEDED, inbox.rotateEpoch(user, 0, 0).status)
    }

    @Test
    fun `계정과 channel은 고정된 cap를 넘겨 만들지 않는다`() {
        val inbox = TradingRequestInbox(limits.copy(maxAccounts = 1, maxChannels = 2))
        assertEquals(EpochStatus.ISSUED, inbox.rotateEpoch(user, 0, 0).status)
        assertEquals(EpochStatus.ISSUED, inbox.rotateEpoch(user, 1, 0).status)
        assertEquals(EpochStatus.INVALID_CHANNEL, inbox.rotateEpoch(user, 2, 0).status)
        assertEquals(EpochStatus.RETRYABLE_CAPACITY, inbox.rotateEpoch(UserId("other"), 0, 0).status)
        assertEquals(1, inbox.accountCount)
        assertEquals(2, inbox.channelCount)
    }

    @Test
    fun `snapshot은 구멍과 퇴역 prefix와 byte 예산을 그대로 복구한다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        inbox.acknowledge(user, 0, 1, 1)
        inbox.remember(token(3), intent, receipt)
        val snapshot = inbox.snapshot()
        val restored = TradingRequestInbox(limits)
        restored.restore(snapshot)
        assertEquals(snapshot, restored.snapshot())
        assertEquals(inbox.canonicalBytes, restored.canonicalBytes)
        assertEquals(InboxLookup.Ready, restored.lookup(token(2), intent))
        assertEquals(AckStatus.FUTURE_ACK, restored.acknowledge(user, 0, 1, 3).status)
        assertEquals(InboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), restored.lookup(token(1), intent))
    }

    @Test
    fun `snapshot 결과는 다음 ACK나 호출자의 목록 수정과 분리된다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        val snapshot = inbox.snapshot()
        inbox.acknowledge(user, 0, 1, 1)
        assertEquals(
            1,
            snapshot.channels
                .single()
                .entries.size,
        )
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.channels as MutableList<TradingInboxChannelSnapshot>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.channels.single().entries as MutableList<TradingInboxEntrySnapshot>).clear()
        }
    }

    @Test
    fun `손상된 snapshot은 구멍을 processed prefix로 바꾸지 못하며 빈 소유자가 유지된다`() {
        val inbox = opened()
        inbox.remember(token(2), intent, receipt)
        val saved = inbox.snapshot().channels.single()
        val restored = TradingRequestInbox(limits)
        assertFailsWith<IllegalArgumentException> {
            restored.restore(TradingInboxSnapshot(listOf(saved.copy(processedThrough = 2))))
        }
        assertEquals(0, restored.channelCount)
        assertEquals(0, restored.entryCount)
        assertEquals(0L, restored.canonicalBytes)
        restored.restore(inbox.snapshot())
        assertFailsWith<IllegalStateException> { restored.restore(inbox.snapshot()) }
    }

    @Test
    fun `중복 channel이나 entry와 cap를 넘긴 snapshot을 거절한다`() {
        val inbox = opened()
        inbox.remember(token(1), intent, receipt)
        inbox.remember(token(2), intent, receipt)
        val saved = inbox.snapshot().channels.single()
        assertFailsWith<IllegalArgumentException> {
            TradingRequestInbox(limits).restore(TradingInboxSnapshot(listOf(saved, saved)))
        }
        assertFailsWith<IllegalArgumentException> {
            TradingRequestInbox(
                limits,
            ).restore(TradingInboxSnapshot(listOf(saved.copy(entries = listOf(saved.entries.first(), saved.entries.first())))))
        }
        assertFailsWith<IllegalArgumentException> { TradingRequestInbox(limits.copy(maxInboxEntries = 1)).restore(inbox.snapshot()) }
    }

    @Test
    fun `Long 끝에서도 창과 ACK는 음수로 돌아가지 않는다`() {
        val entry = TradingInboxEntrySnapshot(Long.MAX_VALUE, intent, receipt)
        val inbox = TradingRequestInbox(limits)
        inbox.restore(
            TradingInboxSnapshot(
                listOf(TradingInboxChannelSnapshot(user, 0, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE, listOf(entry))),
            ),
        )
        assertEquals(AckStatus.ACKNOWLEDGED, inbox.acknowledge(user, 0, Long.MAX_VALUE, Long.MAX_VALUE).status)
        assertEquals(0, inbox.entryCount)
        assertEquals(EpochStatus.EXHAUSTED, inbox.rotateEpoch(user, 0, Long.MAX_VALUE).status)
    }

    @Test
    fun `UTF8 byte cap는 한글과 서로게이트를 정확히 검사한다`() {
        assertEquals("가😀".toByteArray(Charsets.UTF_8).size, tradingTextBytes("가😀"))
        assertFailsWith<IllegalArgumentException> { tradingTextBytes("\uD800") }
        assertFailsWith<IllegalArgumentException> { tradingTextBytes("\uDC00") }
        val small = limits.copy(maxIdentifierBytes = 4)
        val inbox = TradingRequestInbox(small)
        assertEquals(EpochStatus.INVALID_CHANNEL, inbox.rotateEpoch(UserId("가나"), 0, 0).status)
        assertEquals(0, inbox.channelCount)
        assertEquals(EpochStatus.ISSUED, inbox.rotateEpoch(UserId("u"), 0, 0).status)
        assertEquals(InboxLookup.Rejected(InboxRejection.INVALID_IDENTIFIER), inbox.lookup(RequestToken(UserId("u"), 0, 1, 1), intent))
    }

    private fun token(
        sequence: Long,
        epoch: Long = 1,
    ) = RequestToken(user, 0, epoch, sequence)

    private fun opened(resourceLimits: TradingResourceLimits = limits): TradingRequestInbox =
        TradingRequestInbox(resourceLimits).also { assertEquals(EpochStatus.ISSUED, it.rotateEpoch(user, 0, 0).status) }
}

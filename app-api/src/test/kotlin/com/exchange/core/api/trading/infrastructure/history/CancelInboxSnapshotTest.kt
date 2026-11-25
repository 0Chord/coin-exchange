package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AckStatus
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.CancelCode
import com.exchange.core.trading.CancelInboxLookup
import com.exchange.core.trading.CancelIntent
import com.exchange.core.trading.CancelReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.EpochStatus
import com.exchange.core.trading.InboxLookup
import com.exchange.core.trading.InboxRejection
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingCancelInboxEntrySnapshot
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingInboxSnapshot
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingRecoveryState
import com.exchange.core.trading.TradingRequestInbox
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CancelInboxSnapshotTest {
    private val codec = BoundedTradingStateCodec()
    private val user = UserId("U1")
    private val usdt = AssetId("USDT")
    private val order = OrderIntent(MarketId("BTC-USDT"), 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))
    private val cancel = CancelIntent(order.marketId, OrderId("completed-target"), 1)
    private val accepted = CancelReceipt(CancelCode.ACCEPTED, "C2" + "a".repeat(38), cancel.targetOrderId, 7)
    private val rejected = CancelReceipt(CancelCode.REJECTED, reason = "취소 불가")

    @Test
    fun `혼합 최초 응답은 v3 바이트와 빈 금융 소유자를 지나도 금전 효과 없이 보존된다`() {
        val saved = mixed()
        val bytes = encode(saved)
        assertEquals(3, ByteBuffer.wrap(bytes).getInt(4))
        val restored = state().also { it.restoreRecoveryState(decode(bytes, saved.manifest)) }
        val after = restored.recoveryState()
        assertEquals(saved.inbox, after.inbox)
        assertEquals(
            listOf(accepted, rejected),
            after.inbox.channels
                .single()
                .cancelEntries
                .map { it.receipt },
        )
        assertEquals(Balance(user, usdt, Amount(700), Amount(300)), restored.currentBalance(user, usdt))
        assertEquals(saved.liveOrders, after.liveOrders)
        assertEquals(saved.markets, after.markets)
        assertEquals(
            saved.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
            after.matching.markets.map { Triple(it.marketId, it.lastEngineSequence, it.orders) },
        )
        assertEquals(saved.feeRevenueByAsset, after.feeRevenueByAsset)
        assertEquals(1L, after.lastAdmissionSequence)
        val inbox = inbox(after)
        assertEquals(CancelInboxLookup.Repeated(accepted), inbox.lookupCancel(token(2), cancel))
        assertEquals(CancelInboxLookup.Repeated(rejected), inbox.lookupCancel(token(3), cancel))
        assertEquals(3, inbox.entryCount)
        assertEquals(
            TradingRequestInbox.canonicalEntryBytes(user, order) + 2 * TradingRequestInbox.canonicalCancelEntryBytes(user, cancel),
            inbox.canonicalBytes,
        )
        assertContentEquals(bytes, encode(after))
    }

    @Test
    fun `복원된 취소 응답은 변경된 내용과 신규 주문으로 같은 순번을 재사용하지 못한다`() {
        val saved = mixed()
        val owner = restore(saved)
        val before = encode(owner.recoveryState())
        val restored = inbox(owner.recoveryState())
        for (different in listOf(
            cancel.copy(targetOrderId = OrderId("another-target")),
            cancel.copy(marketId = MarketId("ETH-USDT")),
            cancel.copy(expectedConfigurationVersion = 2),
        )) {
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), restored.lookupCancel(token(2), different))
        }
        assertEquals(InboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), restored.lookup(token(2), order))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.REQUEST_CONTENT_CONFLICT), restored.lookupCancel(token(1), cancel))
        assertEquals(3, restored.entryCount)
        assertContentEquals(before, encode(owner.recoveryState()))
    }

    @Test
    fun `ACK 경계와 모두 정리된 채널은 v3와 취소 없는 v2 양쪽에서 보존된다`() {
        val owner = restore(mixed())
        assertEquals(AckStatus.ACKNOWLEDGED, owner.acknowledgeReceipts(user, 0, 3, 2).status)
        val partial = owner.recoveryState()
        assertEquals(3, version(encode(partial)))
        val after = inbox(restore(partial).recoveryState())
        assertEquals(
            2L,
            after
                .snapshot()
                .channels
                .single()
                .retiredThrough,
        )
        assertEquals(InboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), after.lookup(token(1), order))
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), after.lookupCancel(token(2), cancel))
        assertEquals(CancelInboxLookup.Repeated(rejected), after.lookupCancel(token(3), cancel))
        val retained = after.snapshot()
        after.acknowledge(user, 0, 3, 2)
        assertEquals(retained, after.snapshot())
        assertEquals(TradingRequestInbox.canonicalCancelEntryBytes(user, cancel), after.canonicalBytes)
        owner.acknowledgeReceipts(user, 0, 3, 3)
        val empty = owner.recoveryState()
        assertEquals(2, version(encode(empty)))
        val retired = inbox(restore(empty).recoveryState())
        assertEquals(1, retired.channelCount)
        assertEquals(
            3L,
            retired
                .snapshot()
                .channels
                .single()
                .retiredThrough,
        )
        assertEquals(0L, retired.canonicalBytes)
        assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE), retired.lookupCancel(token(3), cancel))
    }

    @Test
    fun `세대 변경 뒤 빈 v2와 새 응답이 있는 v3 모두 이전 요청과 ACK를 막는다`() {
        val owner = restore(mixed())
        assertEquals(EpochStatus.ISSUED, owner.rotateEpoch(user, 0, 3).status)
        val empty = owner.recoveryState()
        val next = inbox(empty).also { it.rememberCancel(token(1, 4), cancel, rejected) }
        for (saved in listOf(empty, withInbox(empty, next.snapshot()))) {
            assertEquals(
                if (saved.inbox.channels
                        .single()
                        .cancelEntries
                        .isEmpty()
                ) {
                    2
                } else {
                    3
                },
                version(encode(saved)),
            )
            val restored = inbox(restore(saved).recoveryState())
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.FENCED_EPOCH), restored.lookupCancel(token(2), cancel))
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.UNISSUED_EPOCH), restored.lookupCancel(token(1, 5), cancel))
            assertEquals(AckStatus.FENCED_EPOCH, restored.acknowledge(user, 0, 3, 3).status)
            assertEquals(AckStatus.UNISSUED_EPOCH, restored.acknowledge(user, 0, 5, 0).status)
            assertEquals(EpochStatus.ALREADY_ISSUED, restored.rotateEpoch(user, 0, 3).status)
            assertEquals(
                4L,
                restored
                    .snapshot()
                    .channels
                    .single()
                    .epoch,
            )
        }
    }

    @Test
    fun `구멍을 둔 원본과 바이트 복원본은 신규 주문으로 구멍을 채운 뒤 같은 ACK 결과와 공간을 갖는다`() {
        val base = mixed()
        val channel = base.inbox.channels.single()
        val gap =
            withInbox(
                base,
                TradingInboxSnapshot(
                    listOf(
                        channel.copy(
                            processedThrough = 1,
                            cancelEntries = channel.cancelEntries.map { it.copy(sequence = it.sequence + 1) },
                        ),
                    ),
                ),
            )
        val original = state().also { it.restoreRecoveryState(gap) }
        val restored = restore(gap)
        val replies =
            listOf(original, restored).map { owner ->
                val before = encode(owner.recoveryState())
                assertEquals(AckStatus.FUTURE_ACK, owner.acknowledgeReceipts(user, 0, 3, 4).status)
                assertContentEquals(before, encode(owner.recoveryState()))
                val admission = assertIs<AdmissionReply.Recorded>(owner.admit(token(2), order, Instant.EPOCH))
                assertTrue(admission.newlyRecorded)
                assertEquals(2L, admission.receipt.admissionSequence)
                assertEquals(
                    4L,
                    owner
                        .recoveryState()
                        .inbox.channels
                        .single()
                        .processedThrough,
                )
                assertEquals(AckStatus.ACKNOWLEDGED, owner.acknowledgeReceipts(user, 0, 3, 3).status)
                val after = inbox(owner.recoveryState())
                assertEquals(
                    listOf(4L),
                    after
                        .snapshot()
                        .channels
                        .single()
                        .cancelEntries
                        .map { it.sequence },
                )
                assertEquals(CancelInboxLookup.Repeated(rejected), after.lookupCancel(token(4), cancel))
                assertEquals(1, after.entryCount)
                assertEquals(TradingRequestInbox.canonicalCancelEntryBytes(user, cancel), after.canonicalBytes)
                admission
            }
        assertEquals(replies[0], replies[1])
        assertContentEquals(encode(original.recoveryState()), encode(restored.recoveryState()))
    }

    @Test
    fun `주문과 취소의 합산 건수 및 논리 바이트 예산은 다른 채널로 우회하지 못하고 ACK 뒤 재사용된다`() {
        val charge = TradingRequestInbox.canonicalEntryBytes(user, order) + 2 * TradingRequestInbox.canonicalCancelEntryBytes(user, cancel)
        for (limits in listOf(TradingResourceLimits(maxInboxEntries = 3), TradingResourceLimits(maxInboxBytes = charge))) {
            val saved = mixed(limits)
            val restored = inbox(restore(saved).recoveryState())
            assertEquals(charge, restored.canonicalBytes)
            assertEquals(CancelInboxLookup.Repeated(accepted), restored.lookupCancel(token(2), cancel))
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), restored.lookupCancel(token(4), cancel))
            assertEquals(InboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), restored.lookup(token(4), order))
            restored.rotateEpoch(user, 1, 0)
            assertEquals(CancelInboxLookup.Rejected(InboxRejection.RETRYABLE_CAPACITY), restored.lookupCancel(token(1, 1, 1), cancel))
            restored.acknowledge(user, 0, 3, 2)
            restored.rememberCancel(token(4), cancel, accepted)
            assertEquals(2 * TradingRequestInbox.canonicalCancelEntryBytes(user, cancel), restored.canonicalBytes)
            restored.rotateEpoch(user, 0, 3)
            assertEquals(0L, restored.canonicalBytes)
            assertEquals(CancelInboxLookup.Ready, restored.lookupCancel(token(1, 4), cancel))
        }
    }

    @Test
    fun `v3 읽기는 주문과 취소의 합산 건수 및 채널 한도를 목록 할당 전에 검사한다`() {
        for (limits in listOf(TradingResourceLimits(maxInboxEntries = 2), TradingResourceLimits(requestWindow = 2))) {
            val saved = mixed(limits)
            assertFails { decode(encode(saved), saved.manifest) }
        }
        val charge = TradingRequestInbox.canonicalEntryBytes(user, order) + 2 * TradingRequestInbox.canonicalCancelEntryBytes(user, cancel)
        val saved = mixed(TradingResourceLimits(maxInboxBytes = charge - 1))
        val candidate = decode(encode(saved), saved.manifest)
        val owner = state()
        assertFails { owner.restoreRecoveryState(candidate) }
        assertTrue(owner.isBlocked)
    }

    @Test
    fun `초기화 전 금융 자료에 취소 기록을 끼워 넣어도 빈 정상 상태로 복원하지 않는다`() {
        val base = mixed()
        val channel =
            base.inbox.channels
                .single()
                .copy(entries = emptyList(), processedThrough = 0)
        val saved =
            TradingRecoveryState(
                base.manifest,
                TradingLifecycle.UNINITIALIZED,
                emptyList(),
                emptyMap(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                MatchingCheckpoint(emptyList()),
                TradingInboxSnapshot(listOf(channel)),
                0,
                0,
                0,
            )
        val candidate = decode(encode(saved), saved.manifest)
        val owner = state()
        assertFails { owner.restoreRecoveryState(candidate) }
        assertTrue(owner.isBlocked)
    }

    @Test
    fun `손상된 v3 필드는 금융 소유자를 복원하거나 차단하기 전에 거절된다`() {
        val saved = mixed()
        val valid = encode(saved)
        val reason = offset(valid, rejected.reason!!)
        val code = offset(valid, CancelCode.ACCEPTED.name)
        val id = offset(valid, accepted.cancelId!!)
        val malformed =
            listOf(
                valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, 99) },
                valid.copyOf(id + 4),
                valid.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() },
                valid.copyOf().also { it[it.size - 1] = 0 },
                valid + byteArrayOf(1),
                checksummed(valid.copyOf().also { it[code] = 'X'.code.toByte() }),
                checksummed(valid.copyOf().also { it[reason] = 0xff.toByte() }),
                checksummed(valid.copyOf().also { ByteBuffer.wrap(it).putInt(reason - 4, Int.MAX_VALUE) }),
                checksummed(valid.copyOf().also { it[id] = 'X'.code.toByte() }),
            )
        for ((index, broken) in malformed.withIndex()) {
            val owner = state()
            assertFails("손상 사례 $index") { owner.restoreRecoveryState(decode(broken, saved.manifest)) }
            assertFalse(owner.isBlocked)
            assertEquals(TradingLifecycle.UNINITIALIZED, owner.lifecycle)
            owner.restoreRecoveryState(decode(valid, saved.manifest))
            assertEquals(TradingLifecycle.RUNNING, owner.lifecycle)
        }
    }

    @Test
    fun `정상 체크섬의 순번 충돌 비정렬 retired 항목과 구멍은 부분 금융 상태로 게시되지 않는다`() {
        val base = mixed()
        val channel = base.inbox.channels.single()
        val entries = channel.cancelEntries
        val malformed =
            listOf(
                channel.copy(cancelEntries = listOf(entries[0].copy(sequence = 1), entries[1])),
                channel.copy(cancelEntries = listOf(entries[0], entries[0])),
                channel.copy(cancelEntries = entries.reversed()),
                channel.copy(retiredThrough = 2, entries = emptyList()),
                channel.copy(processedThrough = 4),
                channel.copy(channel = 1, entries = emptyList(), processedThrough = 3),
            )
        for (broken in malformed) {
            val channels = if (broken.channel == 1) listOf(channel, broken) else listOf(broken)
            // 운영 writer가 만든 정상 체크섬을 통과한 뒤 소유자 의미 검증에서 실패해야 한다.
            val candidate = decode(encode(withInbox(base, TradingInboxSnapshot(channels))), base.manifest)
            val owner = state()
            assertFails { owner.restoreRecoveryState(candidate) }
            assertTrue(owner.isBlocked)
            assertFails { owner.recoveryState() }
        }
    }

    @Test
    fun `Long 끝의 세대와 취소 순번은 바이트 복원 뒤 ACK와 EXHAUSTED 의미를 유지한다`() {
        val base = mixed()
        val end =
            base.inbox.channels.single().copy(
                epoch = Long.MAX_VALUE,
                retiredThrough = Long.MAX_VALUE - 1,
                processedThrough = Long.MAX_VALUE,
                entries = emptyList(),
                cancelEntries = listOf(TradingCancelInboxEntrySnapshot(Long.MAX_VALUE, cancel, rejected)),
            )
        val owner = restore(withInbox(base, TradingInboxSnapshot(listOf(end))))
        assertEquals(AckStatus.ACKNOWLEDGED, owner.acknowledgeReceipts(user, 0, Long.MAX_VALUE, Long.MAX_VALUE).status)
        assertEquals(EpochStatus.EXHAUSTED, owner.rotateEpoch(user, 0, Long.MAX_VALUE).status)
        val retired = owner.recoveryState()
        assertEquals(2, version(encode(retired)))
        val restored = inbox(restore(retired).recoveryState())
        assertEquals(0L, restored.canonicalBytes)
        assertEquals(
            CancelInboxLookup.Rejected(InboxRejection.RETIRED_UNAVAILABLE),
            restored.lookupCancel(token(Long.MAX_VALUE, Long.MAX_VALUE), cancel),
        )
        assertEquals(EpochStatus.EXHAUSTED, restored.rotateEpoch(user, 0, Long.MAX_VALUE).status)
    }

    private fun mixed(limits: TradingResourceLimits = TradingResourceLimits()): TradingRecoveryState {
        val base = funded(limits)
        val channel = base.inbox.channels.single()
        return withInbox(
            base,
            TradingInboxSnapshot(
                listOf(
                    channel.copy(
                        processedThrough = 3,
                        cancelEntries =
                            listOf(
                                TradingCancelInboxEntrySnapshot(2, cancel, accepted),
                                TradingCancelInboxEntrySnapshot(3, cancel, rejected),
                            ),
                    ),
                ),
            ),
        )
    }

    private fun funded(limits: TradingResourceLimits = TradingResourceLimits()): TradingRecoveryState {
        val manifest =
            TradingClusterManifest(
                ClusterLineage(31, 32),
                "cancel-snapshot-v1",
                "0".repeat(64),
                "1".repeat(64),
                1,
                listOf(
                    TradingMarketContext(
                        MarketDefinition(order.marketId, AssetId("BTC"), usdt, 0),
                        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO)),
                        0,
                    ),
                ),
                limits,
            )
        val owner = state().also { it.initialize(manifest) }
        owner.prepareDevelopmentBalance(OpeningBalance("P1", user, usdt, Amount(1_000)), "ignored", Instant.EPOCH)
        repeat(3) { owner.rotateEpoch(user, 0, it.toLong()) }
        assertIs<AdmissionReply.Recorded>(owner.admit(token(1), order, Instant.EPOCH))
        return owner.recoveryState()
    }

    private fun withInbox(
        base: TradingRecoveryState,
        inbox: TradingInboxSnapshot,
    ) = TradingRecoveryState(
        base.manifest,
        base.lifecycle,
        base.balances,
        base.feeRevenueByAsset,
        base.usedBalances,
        base.preparations,
        base.accounts,
        base.liveOrders,
        base.markets,
        base.matching,
        inbox,
        base.lastAdmissionSequence,
        base.lastPreparationSequence,
        base.preparationCanonicalBytes,
        base.incident,
    )

    private fun inbox(saved: TradingRecoveryState) = TradingRequestInbox(saved.manifest.limits).also { it.restore(saved.inbox) }

    private fun encode(saved: TradingRecoveryState) = ByteArrayOutputStream().also { codec.write(saved, 512, it) }.toByteArray()

    private fun decode(
        bytes: ByteArray,
        manifest: TradingClusterManifest,
    ) = codec.read(ByteArrayInputStream(bytes), manifest, 512)

    private fun restore(saved: TradingRecoveryState) = state().also { it.restoreRecoveryState(decode(encode(saved), saved.manifest)) }

    private fun version(bytes: ByteArray) = ByteBuffer.wrap(bytes).getInt(4)

    private fun offset(
        bytes: ByteArray,
        text: String,
    ): Int {
        val pattern = text.toByteArray(Charsets.UTF_8)
        return (0..bytes.size - pattern.size).last { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
    }

    private fun checksummed(bytes: ByteArray): ByteArray =
        bytes.also {
            val footer = it.size - 36
            MessageDigest.getInstance("SHA-256").digest(it.copyOf(footer)).copyInto(it, footer)
        }

    private fun token(
        sequence: Long,
        epoch: Long = 3,
        channel: Int = 0,
    ) = RequestToken(user, channel, epoch, sequence)

    private fun state(): TradingState {
        val fees = TradingFeeReserveCalculator()
        val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        return TradingState(
            OrderAdmissionCalculator(reservations),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                reservations,
                fees,
                SettlementLedgerTransactions(),
            ),
            boundedMode = true,
        )
    }
}

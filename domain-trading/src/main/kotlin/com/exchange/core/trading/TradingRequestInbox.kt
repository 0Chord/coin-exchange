package com.exchange.core.trading

import com.exchange.core.common.UserId
import java.util.Collections

enum class InboxRejection {
    UNKNOWN_CHANNEL,
    FENCED_EPOCH,
    UNISSUED_EPOCH,
    RETIRED_UNAVAILABLE,
    REQUEST_CONTENT_CONFLICT,
    RETRYABLE_CAPACITY,
    INVALID_IDENTIFIER,
}

sealed class InboxLookup {
    data object Ready : InboxLookup()

    data class Repeated(
        val receipt: AdmissionReceipt,
    ) : InboxLookup()

    data class Rejected(
        val reason: InboxRejection,
    ) : InboxLookup()
}

enum class AckStatus { ACKNOWLEDGED, FUTURE_ACK, FENCED_EPOCH, UNISSUED_EPOCH, UNKNOWN_CHANNEL, INVALID_SEQUENCE }

data class AckReceipt(
    val status: AckStatus,
    val epoch: Long,
    val retiredThrough: Long,
)

enum class EpochStatus { ISSUED, ALREADY_ISSUED, SUPERSEDED, UNISSUED_EPOCH, RETRYABLE_CAPACITY, INVALID_CHANNEL, EXHAUSTED }

data class EpochReceipt(
    val status: EpochStatus,
    val epoch: Long,
)

data class TradingInboxEntrySnapshot(
    val sequence: Long,
    val intent: OrderIntent,
    val receipt: AdmissionReceipt,
)

data class TradingInboxChannelSnapshot(
    val userId: UserId,
    val channel: Int,
    val epoch: Long,
    val retiredThrough: Long,
    val processedThrough: Long,
    val entries: List<TradingInboxEntrySnapshot>,
)

data class TradingInboxSnapshot(
    val channels: List<TradingInboxChannelSnapshot>,
)

/** 한 서비스 스레드가 소유한다. 창 밖 요청을 자동 발급/retire하거나 금융 함수를 호출하지 않는다. */
class TradingRequestInbox(
    private val limits: TradingResourceLimits,
) {
    private data class ChannelKey(
        val userId: UserId,
        val channel: Int,
    )

    private class Channel(
        var epoch: Long,
        var retiredThrough: Long = 0,
        var processedThrough: Long = 0,
    ) {
        val entries = HashMap<Long, TradingInboxEntrySnapshot>()
    }

    private val channels = LinkedHashMap<ChannelKey, Channel>()
    private val accountChannels = HashMap<UserId, Int>()
    var entryCount: Int = 0
        private set
    var canonicalBytes: Long = 0
        private set
    val channelCount: Int get() = channels.size
    val accountCount: Int get() = accountChannels.size

    fun lookup(
        token: RequestToken,
        intent: OrderIntent,
    ): InboxLookup {
        if (tradingTextBytes(token.userId.value) > limits.maxIdentifierBytes ||
            tradingTextBytes(intent.marketId.value) > limits.maxIdentifierBytes
        ) {
            return rejected(InboxRejection.INVALID_IDENTIFIER)
        }
        val state = channels[ChannelKey(token.userId, token.channel)] ?: return rejected(InboxRejection.UNKNOWN_CHANNEL)
        if (token.epoch < state.epoch) return rejected(InboxRejection.FENCED_EPOCH)
        if (token.epoch > state.epoch) return rejected(InboxRejection.UNISSUED_EPOCH)
        if (token.sequence <= state.retiredThrough) return rejected(InboxRejection.RETIRED_UNAVAILABLE)
        state.entries[token.sequence]?.let {
            return if (it.intent == intent) InboxLookup.Repeated(it.receipt) else rejected(InboxRejection.REQUEST_CONTENT_CONFLICT)
        }
        // Long.MAX_VALUE 근처에서도 넘치지 않도록 뺄셈으로 비교한다.
        if (token.sequence - state.retiredThrough > limits.requestWindow) return rejected(InboxRejection.RETRYABLE_CAPACITY)
        val bytes = canonicalEntryBytes(token.userId, intent)
        if (entryCount >= limits.maxInboxEntries || bytes > limits.maxInboxBytes - canonicalBytes) {
            return rejected(InboxRejection.RETRYABLE_CAPACITY)
        }
        return InboxLookup.Ready
    }

    fun remember(
        token: RequestToken,
        intent: OrderIntent,
        receipt: AdmissionReceipt,
    ) {
        when (val previous = lookup(token, intent)) {
            is InboxLookup.Repeated -> {
                check(previous.receipt == receipt) { "first admission receipt cannot change" }
                return
            }

            InboxLookup.Ready -> {
                Unit
            }

            is InboxLookup.Rejected -> {
                error("cannot remember request: ${previous.reason}")
            }
        }
        val state = channels.getValue(ChannelKey(token.userId, token.channel))
        val bytes = canonicalEntryBytes(token.userId, intent)
        state.entries[token.sequence] = TradingInboxEntrySnapshot(token.sequence, intent, receipt)
        entryCount = Math.addExact(entryCount, 1)
        canonicalBytes = Math.addExact(canonicalBytes, bytes)
        while (state.processedThrough < Long.MAX_VALUE && state.entries.containsKey(state.processedThrough + 1)) {
            state.processedThrough++
        }
    }

    fun acknowledge(
        userId: UserId,
        channel: Int,
        epoch: Long,
        through: Long,
    ): AckReceipt {
        val state = channels[ChannelKey(userId, channel)] ?: return AckReceipt(AckStatus.UNKNOWN_CHANNEL, 0, 0)

        fun reply(status: AckStatus) = AckReceipt(status, state.epoch, state.retiredThrough)
        if (epoch < state.epoch) return reply(AckStatus.FENCED_EPOCH)
        if (epoch > state.epoch) return reply(AckStatus.UNISSUED_EPOCH)
        if (through < 0) return reply(AckStatus.INVALID_SEQUENCE)
        if (through > state.processedThrough) return reply(AckStatus.FUTURE_ACK)
        if (through <= state.retiredThrough) return reply(AckStatus.ACKNOWLEDGED)
        var sequence = state.retiredThrough + 1
        while (sequence <= through) {
            val removed = checkNotNull(state.entries.remove(sequence)) { "acknowledgement crossed an inbox hole" }
            entryCount--
            canonicalBytes -= canonicalEntryBytes(userId, removed.intent)
            if (sequence == Long.MAX_VALUE) break
            sequence++
        }
        state.retiredThrough = through
        return reply(AckStatus.ACKNOWLEDGED)
    }

    /** expectedEpoch=0 만 최초 channel 발급이다. 데이터 요청은 channel을 만들지 않는다. */
    fun rotateEpoch(
        userId: UserId,
        channel: Int,
        expectedEpoch: Long,
    ): EpochReceipt {
        val validUser =
            try {
                tradingTextBytes(userId.value) in 1..limits.maxIdentifierBytes && ':' !in userId.value
            } catch (
                _: IllegalArgumentException,
            ) {
                false
            }
        if (!validUser) return EpochReceipt(EpochStatus.INVALID_CHANNEL, 0)
        if (channel !in 0 until limits.channelsPerAccount || expectedEpoch < 0) return EpochReceipt(EpochStatus.INVALID_CHANNEL, 0)
        val key = ChannelKey(userId, channel)
        val state = channels[key]
        if (state == null) {
            if (expectedEpoch != 0L) return EpochReceipt(EpochStatus.UNISSUED_EPOCH, 0)
            if (channels.size >= limits.maxChannels || (userId !in accountChannels && accountChannels.size >= limits.maxAccounts)) {
                return EpochReceipt(EpochStatus.RETRYABLE_CAPACITY, 0)
            }
            channels[key] = Channel(1)
            accountChannels[userId] = (accountChannels[userId] ?: 0) + 1
            return EpochReceipt(EpochStatus.ISSUED, 1)
        }
        if (expectedEpoch < state.epoch) {
            val status = if (state.epoch - expectedEpoch == 1L) EpochStatus.ALREADY_ISSUED else EpochStatus.SUPERSEDED
            return EpochReceipt(status, state.epoch)
        }
        if (expectedEpoch > state.epoch) return EpochReceipt(EpochStatus.UNISSUED_EPOCH, state.epoch)
        if (state.epoch == Long.MAX_VALUE) return EpochReceipt(EpochStatus.EXHAUSTED, state.epoch)
        for (entry in state.entries.values) canonicalBytes -= canonicalEntryBytes(userId, entry.intent)
        entryCount -= state.entries.size
        state.entries.clear()
        state.epoch++
        state.retiredThrough = 0
        state.processedThrough = 0
        return EpochReceipt(EpochStatus.ISSUED, state.epoch)
    }

    fun snapshot(): TradingInboxSnapshot =
        TradingInboxSnapshot(
            immutable(
                channels.entries
                    .sortedWith(compareBy({ it.key.userId.value }, { it.key.channel }))
                    .map { (key, state) ->
                        TradingInboxChannelSnapshot(
                            key.userId,
                            key.channel,
                            state.epoch,
                            state.retiredThrough,
                            state.processedThrough,
                            immutable(state.entries.values.sortedBy { it.sequence }),
                        )
                    },
            ),
        )

    /** 빈 소유자에만 복원한다. 전체 검증 실패 시 기존 상태를 바꾸지 않는다. */
    fun restore(snapshot: TradingInboxSnapshot) {
        check(channels.isEmpty() && entryCount == 0 && canonicalBytes == 0L) { "inbox restore requires an empty owner" }
        val candidate = TradingRequestInbox(limits)
        for (saved in snapshot.channels) {
            require(saved.epoch > 0 && saved.retiredThrough >= 0 && saved.processedThrough >= saved.retiredThrough)
            require(saved.processedThrough - saved.retiredThrough <= limits.requestWindow)
            val key = ChannelKey(saved.userId, saved.channel)
            require(key !in candidate.channels) { "duplicate inbox channel" }
            require(
                candidate.rotateEpoch(saved.userId, saved.channel, 0).status == EpochStatus.ISSUED,
            ) { "inbox channel capacity exceeded" }
            val state = candidate.channels.getValue(key)
            state.epoch = saved.epoch
            state.retiredThrough = saved.retiredThrough
            state.processedThrough = saved.retiredThrough
            var previous = saved.retiredThrough
            for (entry in saved.entries) {
                require(entry.sequence > previous) { "unordered or duplicate inbox entry" }
                val token = RequestToken(saved.userId, saved.channel, saved.epoch, entry.sequence)
                require(candidate.lookup(token, entry.intent) == InboxLookup.Ready) { "inbox entry exceeds resource limits" }
                candidate.remember(token, entry.intent, entry.receipt)
                previous = entry.sequence
            }
            require(state.processedThrough == saved.processedThrough) { "inbox processed prefix crosses a hole" }
        }
        channels.putAll(candidate.channels)
        accountChannels.putAll(candidate.accountChannels)
        entryCount = candidate.entryCount
        canonicalBytes = candidate.canonicalBytes
    }

    private fun rejected(reason: InboxRejection) = InboxLookup.Rejected(reason)

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

    companion object {
        /**
         * 논리 표준 형식 v1은 고정 scalar·token 필드 64바이트, 길이를 앞에 붙인 문자열,
         * 고정 receipt 슬롯 384바이트(ASCII ID 64바이트, 자산 256바이트, 플래그·숫자)로 구성한다.
         * 금융 상태를 쓰기 전에 고정 슬롯으로 두 종류 중 어느 최초 결과라도 담을 공간을 예약한다.
         * JVM 객체 크기를 나타내는 값은 아니며, 요청마다 인코딩용 메모리를 할당할 필요도 없다.
         */
        fun canonicalEntryBytes(
            userId: UserId,
            intent: OrderIntent,
        ): Long = 64L + Int.SIZE_BYTES * 2 + tradingTextBytes(userId.value) + tradingTextBytes(intent.marketId.value) + 384L
    }
}

package com.exchange.core.trading

import com.exchange.core.common.MarketId
import java.util.Collections

/** 조회 저장 ACK까지 유지하는 독립 원본 예산이다. */
data class QueryRetentionLimits(
    val maxEntries: Int = 2_048,
    val maxBytes: Long = 2L * 1_024 * 1_024,
) {
    init {
        require(maxEntries > 0 && maxBytes > 0)
    }
}

/** 고정 소비자와 한도를 복제 구성에 결속한다. null 구성은 기존 실행 계약을 유지한다. */
class QueryRetentionConfiguration(
    val consumerId: String,
    marketLimits: Map<MarketId, QueryRetentionLimits>,
    val maxEntries: Int = 4_096,
    val maxBytes: Long = 4L * 1_024 * 1_024,
    val capabilityVersion: Int = 1,
) {
    val marketLimits: Map<MarketId, QueryRetentionLimits> = Collections.unmodifiableMap(LinkedHashMap(marketLimits))

    init {
        require(consumerId.isNotBlank() && consumerId.none(Character::isISOControl) && tradingTextBytes(consumerId) <= 256) {
            "invalid query consumer identity"
        }
        require(this.marketLimits.isNotEmpty() && maxEntries > 0 && maxBytes > 0)
        require(capabilityVersion == 1) { "unsupported query retention capability" }
    }

    override fun equals(other: Any?): Boolean =
        other is QueryRetentionConfiguration && consumerId == other.consumerId && marketLimits == other.marketLimits &&
            maxEntries == other.maxEntries && maxBytes == other.maxBytes && capabilityVersion == other.capabilityVersion

    override fun hashCode(): Int = listOf(consumerId, marketLimits, maxEntries, maxBytes, capabilityVersion).hashCode()
}

data class QueryFactsProgress(
    val issued: Long,
    val retiredThrough: Long,
    val retainedEntries: Int,
    val retainedBytes: Long,
)

enum class QueryFactsReadStatus { BATCH, PRUNED, INVALID_QUERY, INVALID_STREAM, BATCH_TOO_SMALL, DISABLED }

class QueryFactsRead<T>(
    val status: QueryFactsReadStatus,
    entries: List<T> = emptyList(),
    val progress: QueryFactsProgress = QueryFactsProgress(0, 0, 0, 0),
    val requiredBytes: Long = 0,
    val nextSequence: Long? = null,
) {
    val entries: List<T> = Collections.unmodifiableList(entries.toList())
}

enum class QueryFactsAckStatus { ACKNOWLEDGED, INVALID_CONSUMER, INVALID_STREAM, INVALID_SEQUENCE, FUTURE_ACK, DISABLED }

data class QueryFactsAck(
    val status: QueryFactsAckStatus,
    val retiredThrough: Long,
)

class QueryFactsRecovery<T>(
    val issued: Long,
    val retiredThrough: Long,
    entries: List<T>,
    val retainedBytes: Long,
) {
    val entries: List<T> = Collections.unmodifiableList(entries.toList())
}

/** 금융 상태 소유자의 같은 스레드에서만 읽고 쓴다. 검증된 불변 원본을 복사 없이 공유한다. */
internal class QueryFactRetention<T>(
    private val configuration: QueryRetentionConfiguration,
    private val perMarketStreams: Boolean,
    private val market: (T) -> MarketId,
    private val sequence: (T) -> Long,
    private val charge: (T) -> Long,
) {
    private class Stream<T> {
        var issued = 0L
        var retiredThrough = 0L
        var bytes = 0L
        val entries = linkedMapOf<Long, T>()
    }

    private val streams: Map<MarketId?, Stream<T>> =
        if (perMarketStreams) configuration.marketLimits.keys.associateWith { Stream() } else mapOf(null to Stream())
    private val marketEntries =
        configuration.marketLimits.keys
            .associateWith { 0 }
            .toMutableMap()
    private val marketBytes =
        configuration.marketLimits.keys
            .associateWith { 0L }
            .toMutableMap()
    private var totalEntries = 0
    private var totalBytes = 0L

    fun canRecord(
        marketId: MarketId,
        bytes: Long,
    ): Boolean {
        val local = configuration.marketLimits[marketId] ?: return false
        return bytes > 0 && totalEntries < configuration.maxEntries && marketEntries.getValue(marketId) < local.maxEntries &&
            bytes <= configuration.maxBytes - totalBytes && bytes <= local.maxBytes - marketBytes.getValue(marketId)
    }

    fun record(fact: T) {
        val marketId = market(fact)
        val bytes = charge(fact)
        check(canRecord(marketId, bytes)) { "query fact retention capacity changed" }
        val stream = stream(if (perMarketStreams) marketId else null)
        val issued = sequence(fact)
        check(issued == Math.addExact(stream.issued, 1L)) { "query fact sequence is not contiguous" }
        check(stream.entries.put(issued, fact) == null)
        stream.issued = issued
        stream.bytes = Math.addExact(stream.bytes, bytes)
        totalEntries = Math.addExact(totalEntries, 1)
        totalBytes = Math.addExact(totalBytes, bytes)
        marketEntries[marketId] = Math.addExact(marketEntries.getValue(marketId), 1)
        marketBytes[marketId] = Math.addExact(marketBytes.getValue(marketId), bytes)
    }

    fun progress(marketId: MarketId? = null): QueryFactsProgress {
        val stream = stream(marketId)
        return QueryFactsProgress(stream.issued, stream.retiredThrough, stream.entries.size, stream.bytes)
    }

    fun read(
        marketId: MarketId?,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): QueryFactsRead<T> {
        val stream = stream(marketId)
        val progress = progress(marketId)

        fun reply(
            status: QueryFactsReadStatus,
            entries: List<T> = emptyList(),
            requiredBytes: Long = 0,
        ): QueryFactsRead<T> =
            QueryFactsRead(
                status,
                entries,
                progress,
                requiredBytes,
                when {
                    entries.isNotEmpty() -> next(sequence(entries.last()))
                    status == QueryFactsReadStatus.PRUNED -> next(stream.retiredThrough)
                    status == QueryFactsReadStatus.BATCH || status == QueryFactsReadStatus.BATCH_TOO_SMALL -> fromSequence
                    else -> null
                },
            )
        if (fromSequence <= 0 || maxEntries <= 0 || maxBytes <= 0) return reply(QueryFactsReadStatus.INVALID_QUERY)
        if (fromSequence <= stream.retiredThrough) return reply(QueryFactsReadStatus.PRUNED)
        if (fromSequence > stream.issued) {
            return reply(if (fromSequence == next(stream.issued)) QueryFactsReadStatus.BATCH else QueryFactsReadStatus.INVALID_QUERY)
        }
        val rows = ArrayList<T>()
        var bytes = 0L
        var cursor: Long? = fromSequence
        while (cursor != null && cursor <= stream.issued && rows.size < maxEntries) {
            val fact = checkNotNull(stream.entries[cursor]) { "query fact prefix contains a gap" }
            val required = charge(fact)
            if (required > maxBytes - bytes) {
                return if (rows.isEmpty()) {
                    reply(QueryFactsReadStatus.BATCH_TOO_SMALL, requiredBytes = required)
                } else {
                    reply(QueryFactsReadStatus.BATCH, rows)
                }
            }
            rows.add(fact)
            bytes = Math.addExact(bytes, required)
            cursor = next(cursor)
        }
        return reply(QueryFactsReadStatus.BATCH, rows)
    }

    fun acknowledge(
        consumerId: String,
        marketId: MarketId?,
        through: Long,
    ): QueryFactsAck {
        val stream = stream(marketId)

        fun reply(status: QueryFactsAckStatus) = QueryFactsAck(status, stream.retiredThrough)
        if (consumerId != configuration.consumerId) return reply(QueryFactsAckStatus.INVALID_CONSUMER)
        if (through < 0) return reply(QueryFactsAckStatus.INVALID_SEQUENCE)
        if (through > stream.issued) return reply(QueryFactsAckStatus.FUTURE_ACK)
        if (through <= stream.retiredThrough) return reply(QueryFactsAckStatus.ACKNOWLEDGED)
        val retired = stream.entries.entries.takeWhile { it.key <= through }
        check(retired.size.toLong() == through - stream.retiredThrough) { "query ACK prefix contains a gap" }
        var expected = stream.retiredThrough
        for ((id, fact) in retired) {
            check(id == Math.addExact(expected, 1L))
            val marketIdOfFact = market(fact)
            val bytes = charge(fact)
            check(stream.entries.remove(id) === fact)
            stream.bytes = Math.subtractExact(stream.bytes, bytes)
            marketEntries[marketIdOfFact] = Math.subtractExact(marketEntries.getValue(marketIdOfFact), 1)
            marketBytes[marketIdOfFact] = Math.subtractExact(marketBytes.getValue(marketIdOfFact), bytes)
            totalEntries = Math.subtractExact(totalEntries, 1)
            totalBytes = Math.subtractExact(totalBytes, bytes)
            expected = id
        }
        stream.retiredThrough = through
        return reply(QueryFactsAckStatus.ACKNOWLEDGED)
    }

    fun snapshot(marketId: MarketId? = null): QueryFactsRecovery<T> {
        val stream = stream(marketId)
        return QueryFactsRecovery(stream.issued, stream.retiredThrough, stream.entries.values.toList(), stream.bytes)
    }

    fun restore(
        marketId: MarketId?,
        saved: QueryFactsRecovery<T>,
        validate: (T) -> Unit,
    ) {
        val stream = stream(marketId)
        require(stream.issued == 0L && stream.entries.isEmpty()) { "query restore requires an empty stream" }
        require(saved.issued >= 0 && saved.retiredThrough in 0..saved.issued)
        require(saved.entries.size.toLong() == saved.issued - saved.retiredThrough) { "query recovery prefix contains a gap" }
        stream.issued = saved.retiredThrough
        stream.retiredThrough = saved.retiredThrough
        for (fact in saved.entries) {
            validate(fact)
            require(!perMarketStreams || market(fact) == marketId) { "query recovery market differs" }
            require(sequence(fact) == Math.addExact(stream.issued, 1L))
            require(canRecord(market(fact), charge(fact))) { "query recovery exceeds retention limits" }
            record(fact)
        }
        require(stream.issued == saved.issued && stream.bytes == saved.retainedBytes) { "query recovery accounting differs" }
    }

    private fun stream(marketId: MarketId?): Stream<T> = requireNotNull(streams[marketId]) { "invalid query retention stream" }

    private fun next(value: Long): Long? = if (value == Long.MAX_VALUE) null else value + 1
}

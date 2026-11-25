package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import java.util.Collections

/** 자금이 체결 한 건에 실제로 반영한 분개와 양쪽 수수료다. */
data class SettledTrade(
    val trade: TradeExecuted,
    val transaction: LedgerTransaction,
    val makerFee: Amount,
    val takerFee: Amount,
) {
    init {
        require(transaction.transactionType == LedgerTransactionType.SETTLEMENT)
        require(transaction.sourceEventId == "MATCHING:${trade.marketId.value}:${trade.engineSequence}")
        require(transaction.postings.size <= 10)
    }

    override fun equals(other: Any?): Boolean =
        other is SettledTrade && trade == other.trade && makerFee == other.makerFee && takerFee == other.takerFee &&
            transaction.ledgerTransactionId == other.transaction.ledgerTransactionId &&
            transaction.sourceEventId == other.transaction.sourceEventId &&
            transaction.occurredAt == other.transaction.occurredAt && transaction.postings == other.transaction.postings

    override fun hashCode(): Int =
        listOf(
            trade,
            makerFee,
            takerFee,
            transaction.ledgerTransactionId,
            transaction.sourceEventId,
            transaction.occurredAt,
            transaction.postings,
        ).hashCode()
}

/** 순번은 매칭 결과 단위다. 체결 없는 결과도 저장 위치가 이어지도록 빈 목록을 남긴다. */
class SettlementFact(
    val stream: MatchingResultStream,
    val resultSequence: Long,
    fills: List<SettledTrade>,
) {
    val fills: List<SettledTrade> = Collections.unmodifiableList(fills.toList())
    val canonicalBytes: Long

    init {
        require(resultSequence > 0)
        var previous = 0L
        var bytes = headerBytes(stream)
        for (fill in this.fills) {
            val trade = fill.trade
            require(trade.marketId == stream.marketId && trade.engineSequence > previous)
            previous = trade.engineSequence
            val transaction = fill.transaction
            bytes += tradeBytes(trade) + tradingTextBytes(transaction.ledgerTransactionId) + tradingTextBytes(transaction.sourceEventId)
            for (posting in transaction.postings) {
                bytes += POSTING_HEADER_BYTES + tradingTextBytes(posting.accountId) + tradingTextBytes(posting.assetId.value)
            }
        }
        require(bytes <= MAX_CANONICAL_BYTES) { "settlement result exceeds the transfer limit" }
        canonicalBytes = bytes
    }

    override fun equals(other: Any?): Boolean =
        other is SettlementFact && stream == other.stream && resultSequence == other.resultSequence && fills == other.fills

    override fun hashCode(): Int = 31 * (31 * stream.hashCode() + resultSequence.hashCode()) + fills.hashCode()

    companion object {
        // Aeron 메시지에 포함되는 헤더와 상관 ID를 제외한 최대 본문 크기다.
        const val MAX_CANONICAL_BYTES = 2L * 1024 * 1024 - 44

        private const val POSTING_HEADER_BYTES = 32L
        private const val TRANSACTION_ID_BYTES = 40L
        private const val SOURCE_EVENT_FIXED_BYTES = 29L

        private fun headerBytes(stream: MatchingResultStream): Long = 96L + tradingTextBytes(stream.marketId.value)

        private fun tradeBytes(trade: TradeExecuted): Long =
            128L + tradingTextBytes(trade.marketId.value) + tradingTextBytes(trade.makerOrderId.value) +
                tradingTextBytes(trade.takerOrderId.value) + tradingTextBytes(trade.makerUserId.value) +
                tradingTextBytes(trade.takerUserId.value)

        /** 매칭 시점에는 아직 분개가 없어 가능한 원장 항목과 문자열의 최대 크기를 확보한다. */
        internal fun creditBytes(
            result: MatchingResultEnvelope,
            market: MarketDefinition,
        ): Long {
            var bytes = headerBytes(result.stream)
            val marketBytes = tradingTextBytes(market.marketId.value)
            val assetBytes = maxOf(tradingTextBytes(market.baseAssetId.value), tradingTextBytes(market.quoteAssetId.value))
            for (event in result.events) {
                if (event !is TradeExecuted) continue
                val userBytes = tradingTextBytes(event.makerUserId.value) + tradingTextBytes(event.takerUserId.value)
                // 체결 ID와 원본 이벤트 ID, 사용자 항목 양쪽 최대 4개씩, 시스템 수수료 양쪽 최대 1개씩.
                val postingBytes = POSTING_HEADER_BYTES + 20L + 2L * assetBytes
                bytes += tradeBytes(event) + TRANSACTION_ID_BYTES + SOURCE_EVENT_FIXED_BYTES + marketBytes +
                    10 * postingBytes + 4L * userBytes
            }
            return bytes
        }
    }
}

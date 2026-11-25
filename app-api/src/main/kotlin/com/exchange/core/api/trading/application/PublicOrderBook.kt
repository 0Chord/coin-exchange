package com.exchange.core.api.trading.application

import com.exchange.core.order.Side
import com.exchange.core.trading.MatchingOrderDisposition
import com.exchange.core.trading.MatchingOrderEffect
import com.exchange.core.trading.MatchingOutcome
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import java.math.BigInteger
import java.util.Collections
import java.util.TreeMap

data class PublicOrderBookLevel(
    val price: Long,
    val quantity: String,
)

internal data class PublicOrderBookSnapshot(
    val asOfMatchingResultSequence: Long,
    val bids: List<PublicOrderBookLevel>,
    val asks: List<PublicOrderBookLevel>,
)

/** Archive 재생 작업 하나가 검증된 매칭 결과를 받아 호가를 갱신한다. */
internal class PublicOrderBook(
    private val expectedStream: MatchingResultStream,
) {
    private data class LevelKey(
        val side: Side,
        val price: Long,
    )

    private val bids = TreeMap<Long, BigInteger>(reverseOrder())
    private val asks = TreeMap<Long, BigInteger>()

    var appliedThrough: Long = 0
        private set

    fun apply(result: MatchingResultEnvelope) {
        require(result.stream == expectedStream) { "호가 매칭 결과의 출처가 다릅니다" }
        val sequence = result.resultSequence
        require(sequence > 0 && sequence - 1 == appliedThrough && sequence == result.input.dispatchSequence) {
            "호가 매칭 결과가 연속되지 않습니다: 처리=$appliedThrough, 수신=$sequence"
        }

        val updated = HashMap<LevelKey, BigInteger>()
        when (result.outcome) {
            MatchingOutcome.MATCHED -> {
                val taker = result.effects.first()
                // 신규 주문은 매칭 전 주문장에 없으므로 대기하게 된 잔량만 더한다.
                if (taker.disposition == MatchingOrderDisposition.RESTING) {
                    stageQuantity(updated, taker, taker.afterQuantity.value)
                }
                for (index in 1 until result.effects.size) {
                    val maker = result.effects[index]
                    val change = maker.afterQuantity.value - maker.beforeQuantity.value
                    stageQuantity(updated, maker, change)
                }
            }

            MatchingOutcome.CANCELLED -> {
                val cancelled = result.effects.single()
                stageQuantity(updated, cancelled, -cancelled.beforeQuantity.value)
            }

            MatchingOutcome.NOT_EXECUTED, MatchingOutcome.CANCEL_REJECTED -> {
                // 거절도 결과 순번을 사용한다.
            }
        }

        // 다른 가격대에서 오류가 나도 한 결과의 일부만 적용된 호가가 남지 않도록 한다.
        for ((key, quantity) in updated) {
            val levels = levels(key.side)
            if (quantity.signum() == 0) levels.remove(key.price) else levels[key.price] = quantity
        }
        appliedThrough = sequence
    }

    fun snapshot(): PublicOrderBookSnapshot =
        PublicOrderBookSnapshot(
            asOfMatchingResultSequence = appliedThrough,
            bids = bestLevels(bids),
            asks = bestLevels(asks),
        )

    private fun stageQuantity(
        updated: MutableMap<LevelKey, BigInteger>,
        effect: MatchingOrderEffect,
        change: Long,
    ) {
        val key = LevelKey(effect.side, effect.origin.order.price.value)
        val previous = updated[key] ?: levels(key.side)[key.price] ?: BigInteger.ZERO
        val quantity = previous.add(BigInteger.valueOf(change))
        require(quantity.signum() >= 0) { "호가 잔량이 음수입니다: ${key.side} ${key.price}" }
        updated[key] = quantity
    }

    private fun levels(side: Side): TreeMap<Long, BigInteger> = if (side == Side.BUY) bids else asks

    private fun bestLevels(levels: Map<Long, BigInteger>): List<PublicOrderBookLevel> {
        val best = ArrayList<PublicOrderBookLevel>(minOf(DEPTH, levels.size))
        for ((price, quantity) in levels) {
            best.add(PublicOrderBookLevel(price, quantity.toString()))
            if (best.size == DEPTH) break
        }
        return Collections.unmodifiableList(best)
    }

    private companion object {
        const val DEPTH = 10
    }
}

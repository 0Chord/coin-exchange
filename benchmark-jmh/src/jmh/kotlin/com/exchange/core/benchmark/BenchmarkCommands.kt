package com.exchange.core.benchmark

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

internal object BenchmarkCommands {
    private val btcKrw = MarketId("BTC-KRW")
    private val ethKrw = MarketId("ETH-KRW")
    private val solKrw = MarketId("SOL-KRW")

    private val markets = listOf(btcKrw, ethKrw, solKrw)

    fun singleMarketReplayCommands(prefix: String = "replay"): List<MatchingCommand> =
        buildList {
            for (index in 1..500) {
                add(
                    submit(
                        market = btcKrw,
                        orderId = "$prefix-ask-$index",
                        side = Side.SELL,
                        price = 100L + (index % 20),
                        quantity = 2L,
                    ),
                )
            }

            for (index in 1..500) {
                add(
                    submit(
                        market = btcKrw,
                        orderId = "$prefix-bid-$index",
                        side = Side.BUY,
                        price = 105L + (index % 25),
                        quantity = 3L,
                    ),
                )
            }
        }

    fun multiMarketReplayCommands(prefix: String = "replay"): List<MatchingCommand> =
        buildList {
            markets.forEach { market ->
                for (index in 1..200) {
                    add(
                        submit(
                            market = market,
                            orderId = "$prefix-${market.value}-ask-$index",
                            side = Side.SELL,
                            price = 100L + (index % 20),
                            quantity = 2L,
                        ),
                    )
                }
            }

            markets.asReversed().forEach { market ->
                for (index in 1..200) {
                    add(
                        submit(
                            market = market,
                            orderId = "$prefix-${market.value}-bid-$index",
                            side = Side.BUY,
                            price = 105L + (index % 25),
                            quantity = 3L,
                        ),
                    )
                }
            }
        }

    fun balancedSingleMarketReplayCommands(prefix: String): List<MatchingCommand> =
        buildList {
            for (index in 1..500) {
                add(
                    submit(
                        market = btcKrw,
                        orderId = "$prefix-balanced-ask-$index",
                        side = Side.SELL,
                        price = 100L + (index % 20),
                        quantity = 1L,
                    ),
                )
            }

            for (index in 1..500) {
                add(
                    submit(
                        market = btcKrw,
                        orderId = "$prefix-balanced-bid-$index",
                        side = Side.BUY,
                        price = 130L,
                        quantity = 1L,
                    ),
                )
            }
        }

    fun balancedMultiMarketReplayCommands(prefix: String): List<MatchingCommand> =
        buildList {
            markets.forEach { market ->
                for (index in 1..200) {
                    add(
                        submit(
                            market = market,
                            orderId = "$prefix-balanced-${market.value}-ask-$index",
                            side = Side.SELL,
                            price = 100L + (index % 20),
                            quantity = 1L,
                        ),
                    )
                }
            }

            markets.asReversed().forEach { market ->
                for (index in 1..200) {
                    add(
                        submit(
                            market = market,
                            orderId = "$prefix-balanced-${market.value}-bid-$index",
                            side = Side.BUY,
                            price = 130L,
                            quantity = 1L,
                        ),
                    )
                }
            }
        }

    private fun submit(
        market: MarketId,
        orderId: String,
        side: Side,
        price: Long,
        quantity: Long,
    ): SubmitOrderCommand =
        SubmitOrderCommand(
            marketId = market,
            orderId = OrderId(orderId),
            userId = UserId("${market.value}-user-$orderId"),
            side = side,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(price),
            quantity = Quantity(quantity),
        )
}

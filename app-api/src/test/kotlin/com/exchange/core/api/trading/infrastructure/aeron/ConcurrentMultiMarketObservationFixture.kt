package com.exchange.core.api.trading.infrastructure.aeron

/** 동시 비교기 및 동시 시나리오를 호출하지 않고 명세 표의 두 관찰 정답을 만든다. 도메인 계산은 사용하지 않는다. */
internal fun independentConcurrentMultiMarketSteps(first: String): List<OrderReplicationStep> {
    val winner = if (first == "A") fixtureBtc() else fixtureSui()
    val loser = if (first == "A") fixtureSui() else fixtureBtc()
    val opening = OrderReplicationScenario.BUY_SEQUENCE.steps().first()
    val winnerRecord = fixtureRecord(winner)
    val firstRequest = fixtureRequest(winner, mapOf("outcome" to "ACCEPTED", "record" to winnerRecord))
    val rejected =
        mapOf(
            "outcome" to "REJECTED",
            "reason" to "INSUFFICIENT_BALANCE",
            "insufficientBalance" to
                mapOf(
                    "assetId" to "USDT",
                    "available" to winner.remaining,
                    "required" to loser.reserve,
                    "shortfall" to 100_000_000L,
                ),
        )
    val state =
        mapOf(
            "balances" to listOf(fixtureBalance(winner.remaining, winner.reserve)),
            "preparationCount" to 1,
            "pendingOrderCount" to 1,
            "requestRecordCount" to 1,
            "lastSequence" to 1,
            "preparations" to opening.expectedState.getValue("preparations"),
            "pendingOrders" to listOf(winnerRecord),
            "requests" to listOf(firstRequest),
            "transactions" to (opening.expectedState.getValue("transactions") as List<*>) + fixtureReserve(winner),
        )
    return listOf(
        fixtureStep(winner, mapOf("outcome" to "ACCEPTED", "sequence" to 1), state),
        fixtureStep(
            loser,
            rejected,
            state +
                mapOf(
                    "requestRecordCount" to 2,
                    "requests" to listOf(firstRequest, fixtureRequest(loser, rejected)),
                ),
        ),
    )
}

private data class FixedObservedOrder(
    val client: String,
    val market: String,
    val base: String,
    val scale: Int,
    val version: Int,
    val price: Long,
    val quantity: Long,
    val reserve: Long,
    val remaining: Long,
    val time: String,
    val epochSecond: Long,
    val source: String,
)

private fun fixtureBtc() =
    FixedObservedOrder(
        "A",
        "BTC-USDT",
        "BTC",
        8,
        1,
        100_000_000L,
        600_000_000L,
        600_000_000L,
        400_000_000L,
        "2026-10-21T00:00:01.123456789Z",
        1_792_540_801L,
        "RESERVE:v1:01d419be6389624da4f0b52f8e172faaaa17cd2d5f4f221506483c403c288dd7",
    )

private fun fixtureSui() =
    FixedObservedOrder(
        "B",
        "SUI-USDT",
        "SUI",
        9,
        2,
        20_000_000L,
        25_000_000_000L,
        500_000_000L,
        500_000_000L,
        "2026-10-21T00:00:02.123456789Z",
        1_792_540_802L,
        "RESERVE:v1:da01faa1fcc3da26f22206344f4d0ca2ffdd865c446e4e5777c6a9dbd997a47f",
    )

private fun fixtureStep(
    order: FixedObservedOrder,
    result: Map<String, Any>,
    state: Map<String, Any?>,
) = OrderReplicationStep(
    order.client,
    "ORDER",
    listOf(
        "R${order.client}",
        "U1",
        "O${order.client}",
        "BUY",
        order.price.toString(),
        order.quantity.toString(),
        "L-RESERVE-${order.client}",
        order.time,
        "--market",
        order.market,
    ),
    mapOf("userId" to "U1", "requestId" to "R${order.client}", "marketId" to order.market, "orderId" to "O${order.client}") + result,
    state,
    order.market,
)

private fun fixtureOrder(order: FixedObservedOrder) =
    mapOf(
        "marketId" to order.market,
        "orderId" to "O${order.client}",
        "userId" to "U1",
        "side" to "BUY",
        "orderType" to "LIMIT",
        "timeInForce" to "GTC",
        "price" to order.price,
        "quantity" to order.quantity,
    )

private fun fixtureMarket(order: FixedObservedOrder) =
    mapOf(
        "marketId" to order.market,
        "baseAssetId" to order.base,
        "quoteAssetId" to "USDT",
        "baseQuantityScale" to order.scale,
        "priceTick" to 1L,
        "quantityStep" to if (order.scale == 8) 100_000_000L else 1_000_000_000L,
    )

private fun fixturePolicy(order: FixedObservedOrder) =
    mapOf(
        "productType" to "SPOT",
        "feeTier" to "NORMAL",
        "scheduleVersion" to order.version,
        "makerPartsPerMillion" to 0,
        "takerPartsPerMillion" to 0,
    )

private fun fixtureBalance(
    available: Long,
    hold: Long,
) = mapOf(
    "userId" to "U1",
    "assetId" to "USDT",
    "available" to available,
    "hold" to hold,
)

private fun fixtureRecord(order: FixedObservedOrder) =
    mapOf(
        "sequence" to 1,
        "candidate" to
            mapOf(
                "order" to fixtureOrder(order),
                "market" to fixtureMarket(order),
                "feePolicy" to fixturePolicy(order),
                "previousBalance" to fixtureBalance(1_000_000_000L, 0L),
                "nextBalance" to fixtureBalance(order.remaining, order.reserve),
                "reservation" to
                    mapOf(
                        "marketId" to order.market,
                        "orderId" to "O${order.client}",
                        "userId" to "U1",
                        "side" to "BUY",
                        "assetId" to "USDT",
                        "limitPrice" to order.price,
                        "initialQuantity" to order.quantity,
                        "remainingQuantity" to order.quantity,
                        "reservedAmount" to order.reserve,
                        "remainingAmount" to order.reserve,
                        "feePolicy" to fixturePolicy(order),
                        "initialFeeReserveAmount" to 0L,
                        "remainingFeeReserveAmount" to 0L,
                        "feeRemainder" to 0L,
                        "status" to "ACTIVE",
                        "releasedAmount" to null,
                    ),
                "reserveTransaction" to fixtureReserve(order),
            ),
    )

private fun fixtureRequest(
    order: FixedObservedOrder,
    result: Map<String, Any?>,
) = mapOf(
    "requestId" to "R${order.client}",
    "order" to fixtureOrder(order),
    "market" to fixtureMarket(order),
    "feePolicy" to fixturePolicy(order),
    "ledgerTransactionId" to "L-RESERVE-${order.client}",
    "result" to result,
    "occurredAt" to order.time,
    "occurredAtEpochSecond" to order.epochSecond,
    "occurredAtNano" to 123_456_789,
)

private fun fixtureReserve(order: FixedObservedOrder) =
    mapOf(
        "transactionId" to "L-RESERVE-${order.client}",
        "sourceEventId" to order.source,
        "kind" to "RESERVE",
        "occurredAt" to order.time,
        "occurredAtEpochSecond" to order.epochSecond,
        "occurredAtNano" to 123_456_789,
        "postings" to
            listOf(
                mapOf("accountId" to "USER:U1:USDT:AVAILABLE", "assetId" to "USDT", "side" to "DEBIT", "amount" to order.reserve),
                mapOf("accountId" to "USER:U1:USDT:HOLD", "assetId" to "USDT", "side" to "CREDIT", "amount" to order.reserve),
            ),
    )

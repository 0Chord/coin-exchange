package com.exchange.core.api.trading.infrastructure.aeron

/** 도메인 계산기를 호출하지 않는 세 노드 검사의 고정 입력과 독립 정답이다. */
internal enum class OrderReplicationScenario(
    val asset: String,
    val openingAmount: Long,
) {
    BUY_SEQUENCE("USDT", 1_000_000_000),
    SELL("BTC", 300_000_000),
    INSUFFICIENT("USDT", 1_000_000_000),
    ACCEPTED_REPLAY("USDT", 1_000_000_000),
    REJECTED_REPLAY("USDT", 1_000_000_000),
    CONTEXT_CORRECTION("USDT", 1_000_000_000),
    ;

    fun steps(): List<OrderReplicationStep> {
        when (this) {
            ACCEPTED_REPLAY -> return acceptedReplaySteps()
            REJECTED_REPLAY -> return rejectedReplaySteps()
            CONTEXT_CORRECTION -> return contextCorrectionSteps()
            else -> Unit
        }
        val opening = OrderReplicationStep("P1", "FUNDING", fundingArguments(), fundingResult(), state(emptyList(), false))
        val first = OrderReplicationStep("R1", "ORDER", orderArguments(1), accepted(1), state(listOf(1), false))
        return when (this) {
            BUY_SEQUENCE -> {
                listOf(
                    opening,
                    first,
                    OrderReplicationStep("R2", "ORDER", orderArguments(2), accepted(2), state(listOf(1, 2), false)),
                )
            }

            SELL -> {
                listOf(opening, first)
            }

            ACCEPTED_REPLAY, REJECTED_REPLAY, CONTEXT_CORRECTION -> {
                error("재전송 사례는 위에서 구성한다")
            }

            INSUFFICIENT -> {
                listOf(
                    opening,
                    first,
                    OrderReplicationStep("R2", "ORDER", orderArguments(2), rejected(), state(listOf(1), true)),
                )
            }
        }
    }

    private fun fundingArguments() = listOf("P1", "U1", asset, openingAmount.toString(), "L-OPEN-1", time(0))

    private fun orderArguments(index: Int) =
        listOf(
            "R$index",
            "U1",
            "O$index",
            side(),
            "100000000",
            quantity(index).toString(),
            "L-RESERVE-$index",
            time(index),
        )

    private fun fundingResult(): Map<String, Any?> =
        mapOf(
            "preparationId" to "P1",
            "userId" to "U1",
            "assetId" to asset,
            "amount" to openingAmount,
            "ledgerTransactionId" to "L-OPEN-1",
            "occurredAt" to time(0),
            "alreadyPrepared" to false,
        )

    private fun state(
        approved: List<Int>,
        rejection: Boolean,
    ): Map<String, Any?> {
        val reserved = approved.sumOf { reserveAmount(it) }
        return mapOf(
            "balances" to listOf(balance(openingAmount - reserved, reserved)),
            "preparationCount" to 1,
            "pendingOrderCount" to approved.size,
            "requestRecordCount" to approved.size + if (rejection) 1 else 0,
            "lastSequence" to approved.size,
            "preparations" to listOf(fundingResult()),
            "transactions" to listOf(openingTransaction()) + approved.map { reserveTransaction(it) },
            "pendingOrders" to approved.map { record(it) },
            "requests" to approved.map { requestRecord(it, false) } + if (rejection) listOf(requestRecord(2, true)) else emptyList(),
        )
    }

    private fun balance(
        available: Long,
        hold: Long,
    ) = mapOf("userId" to "U1", "assetId" to asset, "available" to available, "hold" to hold)

    private fun record(index: Int): Map<String, Any?> {
        val priorReserve = if (index == 2) 300_000_000L else 0L
        val reserved = reserveAmount(index)
        return mapOf(
            "sequence" to index,
            "candidate" to
                mapOf(
                    "order" to order(index),
                    "market" to market(),
                    "feePolicy" to policy(),
                    "previousBalance" to balance(openingAmount - priorReserve, priorReserve),
                    "nextBalance" to balance(openingAmount - priorReserve - reserved, priorReserve + reserved),
                    "reservation" to
                        mapOf(
                            "marketId" to "BTC-USDT",
                            "orderId" to "O$index",
                            "userId" to "U1",
                            "side" to side(),
                            "assetId" to asset,
                            "limitPrice" to 100_000_000L,
                            "initialQuantity" to quantity(index),
                            "remainingQuantity" to quantity(index),
                            "reservedAmount" to reserved,
                            "remainingAmount" to reserved,
                            "feePolicy" to policy(),
                            "initialFeeReserveAmount" to 0L,
                            "remainingFeeReserveAmount" to 0L,
                            "status" to "ACTIVE",
                            "feeRemainder" to 0L,
                            "releasedAmount" to null,
                        ),
                    "reserveTransaction" to reserveTransaction(index),
                ),
        )
    }

    private fun requestRecord(
        index: Int,
        rejection: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "requestId" to "R$index",
            "order" to order(index),
            "market" to market(),
            "feePolicy" to policy(),
            "ledgerTransactionId" to "L-RESERVE-$index",
            "occurredAt" to time(index),
            "occurredAtEpochSecond" to 1_792_540_800L + index,
            "occurredAtNano" to 123_456_789,
            "result" to
                if (rejection) {
                    mapOf("outcome" to "REJECTED", "reason" to "INSUFFICIENT_BALANCE", "insufficientBalance" to shortage())
                } else {
                    mapOf("outcome" to "ACCEPTED", "record" to record(index))
                },
        )

    private fun order(index: Int) =
        mapOf(
            "marketId" to "BTC-USDT",
            "orderId" to "O$index",
            "userId" to "U1",
            "side" to side(),
            "orderType" to "LIMIT",
            "timeInForce" to "GTC",
            "price" to 100_000_000L,
            "quantity" to quantity(index),
        )

    private fun accepted(index: Int): Map<String, Any?> = identifiers(index) + mapOf("outcome" to "ACCEPTED", "sequence" to index)

    private fun rejected(): Map<String, Any?> =
        identifiers(2) + mapOf("outcome" to "REJECTED", "reason" to "INSUFFICIENT_BALANCE", "insufficientBalance" to shortage())

    private fun identifiers(index: Int) =
        mapOf("userId" to "U1", "requestId" to "R$index", "marketId" to "BTC-USDT", "orderId" to "O$index")

    private fun shortage() =
        mapOf("assetId" to "USDT", "available" to 700_000_000L, "required" to 800_000_000L, "shortfall" to 100_000_000L)

    private fun openingTransaction() =
        transaction(
            "L-OPEN-1",
            "OPENING:P1",
            "OPENING",
            0,
            openingAmount,
            "SYSTEM:$asset:DEVELOPMENT_FUNDING",
            "USER:U1:$asset:AVAILABLE",
        )

    private fun reserveTransaction(index: Int) =
        transaction(
            "L-RESERVE-$index",
            reserveSource(index),
            "RESERVE",
            index,
            reserveAmount(index),
            "USER:U1:$asset:AVAILABLE",
            "USER:U1:$asset:HOLD",
        )

    private fun transaction(
        id: String,
        source: String,
        kind: String,
        index: Int,
        amount: Long,
        debit: String,
        credit: String,
    ): Map<String, Any?> =
        mapOf(
            "transactionId" to id,
            "sourceEventId" to source,
            "kind" to kind,
            "occurredAt" to time(index),
            "occurredAtEpochSecond" to 1_792_540_800L + index,
            "occurredAtNano" to 123_456_789,
            "postings" to
                listOf(
                    mapOf("accountId" to debit, "assetId" to asset, "side" to "DEBIT", "amount" to amount),
                    mapOf("accountId" to credit, "assetId" to asset, "side" to "CREDIT", "amount" to amount),
                ),
        )

    private fun quantity(index: Int): Long =
        if (this ==
            SELL
        ) {
            200_000_000
        } else if (index == 1) {
            300_000_000
        } else if (this == INSUFFICIENT) {
            800_000_000
        } else {
            100_000_000
        }

    private fun reserveAmount(index: Int): Long = quantity(index)

    private fun side() = if (this == SELL) "SELL" else "BUY"

    private fun time(index: Int) = "2026-10-21T00:00:0$index.123456789Z"

    private fun reserveSource(index: Int) =
        if (index == 1) {
            "RESERVE:v1:dd2c94ecf984651e09ce084d6492c1b1b5c51256a807608ef55966c9bc56e47e"
        } else {
            "RESERVE:v1:21ba2bc56891bdbbb61c40228d7fcf95d515c4090f0a6f48d07ee83763a50a8b"
        }

    private fun market() = mapOf("marketId" to "BTC-USDT", "baseAssetId" to "BTC", "quoteAssetId" to "USDT", "baseQuantityScale" to 8)

    private fun policy() =
        mapOf(
            "productType" to "SPOT",
            "feeTier" to "NORMAL",
            "scheduleVersion" to 1,
            "makerPartsPerMillion" to 0,
            "takerPartsPerMillion" to 0,
        )
}

internal data class OrderReplicationStep(
    val name: String,
    val inputKind: String,
    val arguments: List<String>,
    val expectedResult: Map<String, Any?>,
    val expectedState: Map<String, Any?>,
) {
    val identity: Map<String, Any?> get() =
        if (inputKind == "FUNDING") {
            mapOf("preparationId" to arguments[0])
        } else {
            mapOf("userId" to arguments[1], "requestId" to arguments[0], "marketId" to "BTC-USDT", "orderId" to arguments[2])
        }
    val mainClass: String get() {
        val operation = if (inputKind == "FUNDING") "Funding" else "Order"
        return "com.exchange.core.api.trading.infrastructure.aeron.Development${operation}ClientKt"
    }
}

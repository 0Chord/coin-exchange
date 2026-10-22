package com.exchange.core.api.trading.infrastructure.aeron

/** #109와 #117의 사전 작성한 두 정답. 실제 승인 응답이나 도메인 계산으로 승자를 정하지 않는다. */
internal enum class ConcurrentOrderReplicationScenario {
    BTC_SHARED_FUNDS,
    MULTI_MARKET_SHARED_FUNDS,
    ;

    val openingStep: OrderReplicationStep = OrderReplicationScenario.BUY_SEQUENCE.steps().first()

    val clientArguments: Map<String, List<String>>
        get() =
            listOf("A", "B").associateWith { id ->
                listOf("R$id", "U1", "O$id", "BUY", price(id).toString(), quantity(id).toString(), "L-RESERVE-$id", time(id)) +
                    if (this == MULTI_MARKET_SHARED_FUNDS) listOf("--market", marketId(id)) else emptyList()
            }

    fun marketId(clientId: String): String {
        require(clientId == "A" || clientId == "B") { "알 수 없는 동시 클라이언트: $clientId" }
        return if (isSui(clientId)) "SUI-USDT" else "BTC-USDT"
    }

    fun orderedSteps(firstClientId: String): List<OrderReplicationStep> {
        require(firstClientId in clientArguments) { "알 수 없는 동시 클라이언트: $firstClientId" }
        val second = if (firstClientId == "A") "B" else "A"
        return listOf(
            OrderReplicationStep(
                firstClientId,
                "ORDER",
                clientArguments.getValue(firstClientId),
                accepted(firstClientId),
                state(firstClientId, false),
                marketId = marketId(firstClientId),
            ),
            OrderReplicationStep(
                second,
                "ORDER",
                clientArguments.getValue(second),
                rejected(firstClientId, second),
                state(firstClientId, true),
                marketId = marketId(second),
            ),
        )
    }

    private fun state(
        first: String,
        hasRejection: Boolean,
    ): Map<String, Any?> {
        val second = if (first == "A") "B" else "A"
        return mapOf(
            "balances" to listOf(balance(available(first), reserve(first))),
            "preparationCount" to 1,
            "pendingOrderCount" to 1,
            "requestRecordCount" to if (hasRejection) 2 else 1,
            "lastSequence" to 1L,
            "preparations" to openingStep.expectedState.getValue("preparations"),
            "transactions" to listOf(openingTransaction(), reserveTransaction(first)),
            "pendingOrders" to listOf(record(first)),
            "requests" to listOf(requestRecord(first, first)) + if (hasRejection) listOf(requestRecord(second, first)) else emptyList(),
        )
    }

    private fun record(first: String): Map<String, Any?> =
        mapOf(
            "sequence" to 1L,
            "candidate" to
                mapOf(
                    "order" to order(first),
                    "market" to market(first),
                    "feePolicy" to policy(first),
                    "previousBalance" to balance(1_000_000_000L, 0L),
                    "nextBalance" to balance(available(first), reserve(first)),
                    "reservation" to
                        mapOf(
                            "marketId" to marketId(first),
                            "orderId" to "O$first",
                            "userId" to "U1",
                            "side" to "BUY",
                            "assetId" to "USDT",
                            "limitPrice" to price(first),
                            "initialQuantity" to quantity(first),
                            "remainingQuantity" to quantity(first),
                            "reservedAmount" to reserve(first),
                            "remainingAmount" to reserve(first),
                            "feePolicy" to policy(first),
                            "initialFeeReserveAmount" to 0L,
                            "remainingFeeReserveAmount" to 0L,
                            "status" to "ACTIVE",
                            "feeRemainder" to 0L,
                            "releasedAmount" to null,
                        ),
                    "reserveTransaction" to reserveTransaction(first),
                ),
        )

    private fun requestRecord(
        id: String,
        first: String,
    ): Map<String, Any?> =
        mapOf(
            "requestId" to "R$id",
            "order" to order(id),
            "market" to market(id),
            "feePolicy" to policy(id),
            "ledgerTransactionId" to "L-RESERVE-$id",
            "occurredAt" to time(id),
            "occurredAtEpochSecond" to epochSecond(id),
            "occurredAtNano" to 123_456_789,
            "result" to
                if (id == first) {
                    mapOf("outcome" to "ACCEPTED", "record" to record(first))
                } else {
                    mapOf("outcome" to "REJECTED", "reason" to "INSUFFICIENT_BALANCE", "insufficientBalance" to shortage(first, id))
                },
        )

    private fun identifiers(id: String) = mapOf("userId" to "U1", "requestId" to "R$id", "marketId" to marketId(id), "orderId" to "O$id")

    private fun accepted(id: String): Map<String, Any?> = identifiers(id) + mapOf("outcome" to "ACCEPTED", "sequence" to 1L)

    private fun rejected(
        first: String,
        id: String,
    ): Map<String, Any?> =
        identifiers(id) + mapOf("outcome" to "REJECTED", "reason" to "INSUFFICIENT_BALANCE", "insufficientBalance" to shortage(first, id))

    private fun shortage(
        first: String,
        id: String,
    ) = mapOf("assetId" to "USDT", "available" to available(first), "required" to reserve(id), "shortfall" to 100_000_000L)

    private fun order(id: String) =
        mapOf(
            "marketId" to marketId(id),
            "orderId" to "O$id",
            "userId" to "U1",
            "side" to "BUY",
            "orderType" to "LIMIT",
            "timeInForce" to "GTC",
            "price" to price(id),
            "quantity" to quantity(id),
        )

    private fun openingTransaction(): Any = (openingStep.expectedState.getValue("transactions") as List<*>).single()!!

    private fun reserveTransaction(id: String): Map<String, Any?> =
        mapOf(
            "transactionId" to "L-RESERVE-$id",
            "sourceEventId" to source(id),
            "kind" to "RESERVE",
            "occurredAt" to time(id),
            "occurredAtEpochSecond" to epochSecond(id),
            "occurredAtNano" to 123_456_789,
            "postings" to
                listOf(
                    mapOf("accountId" to "USER:U1:USDT:AVAILABLE", "assetId" to "USDT", "side" to "DEBIT", "amount" to reserve(id)),
                    mapOf("accountId" to "USER:U1:USDT:HOLD", "assetId" to "USDT", "side" to "CREDIT", "amount" to reserve(id)),
                ),
        )

    private fun balance(
        available: Long,
        hold: Long,
    ) = mapOf("userId" to "U1", "assetId" to "USDT", "available" to available, "hold" to hold)

    private fun isSui(id: String) = this == MULTI_MARKET_SHARED_FUNDS && id == "B"

    private fun price(id: String) = if (isSui(id)) 20_000_000L else 100_000_000L

    private fun quantity(id: String) =
        if (isSui(id)) {
            25_000_000_000L
        } else if (id == "A") {
            600_000_000L
        } else {
            500_000_000L
        }

    private fun reserve(id: String) = if (id == "A") 600_000_000L else 500_000_000L

    private fun available(first: String) = if (first == "A") 400_000_000L else 500_000_000L

    private fun time(id: String) = if (id == "A") "2026-10-21T00:00:01.123456789Z" else "2026-10-21T00:00:02.123456789Z"

    private fun epochSecond(id: String) = if (id == "A") 1_792_540_801L else 1_792_540_802L

    // SHA-256 of length-prefixed market and order UTF-8 bytes; lengths are 4-byte big-endian integers.
    private fun source(id: String) =
        if (id == "A") {
            "RESERVE:v1:01d419be6389624da4f0b52f8e172faaaa17cd2d5f4f221506483c403c288dd7"
        } else if (isSui(id)) {
            "RESERVE:v1:da01faa1fcc3da26f22206344f4d0ca2ffdd865c446e4e5777c6a9dbd997a47f"
        } else {
            "RESERVE:v1:2a1bde88d4f4a215c4be6ab51451a0d0164860b6d7d3c35ed44993d2f2545cae"
        }

    private fun market(id: String) =
        mapOf(
            "marketId" to marketId(id),
            "baseAssetId" to if (isSui(id)) "SUI" else "BTC",
            "quoteAssetId" to "USDT",
            "baseQuantityScale" to if (isSui(id)) 9 else 8,
        )

    private fun policy(id: String) =
        mapOf(
            "productType" to "SPOT",
            "feeTier" to "NORMAL",
            "scheduleVersion" to if (isSui(id)) 2 else 1,
            "makerPartsPerMillion" to 0,
            "takerPartsPerMillion" to 0,
        )

    companion object {
        val openingStep: OrderReplicationStep get() = BTC_SHARED_FUNDS.openingStep
        val clientArguments: Map<String, List<String>> get() = BTC_SHARED_FUNDS.clientArguments

        fun orderedSteps(firstClientId: String): List<OrderReplicationStep> = BTC_SHARED_FUNDS.orderedSteps(firstClientId)
    }
}

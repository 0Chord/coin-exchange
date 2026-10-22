package com.exchange.core.api.trading.infrastructure.aeron

/** 등록할 원문 바이트를 고정한다. READY나 제품 문서 변환기로 기대 설정을 만들지 않는다. */
internal const val MULTI_MARKET_DOCUMENT =
    """[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,""" +
        """"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,""" +
        """"makerPartsPerMillion":0,"takerPartsPerMillion":0}},""" +
        """{"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,""" +
        """"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,""" +
        """"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]"""

internal fun expectedMultiMarketSettings(): List<Map<String, Any>> = listOf(multiMarket("BTC"), multiMarket("SUI"))

/** #116의 여섯 전송과 네 상태. 거절 재전송은 명령 전체와 최초 부족값을 보존한다. */
internal fun multiMarketSharedFundsSteps(): List<OrderReplicationStep> {
    val opening =
        OrderReplicationStep(
            "P1",
            "FUNDING",
            listOf("P1", "U1", "USDT", "1000000000", "OPENING-1", MULTI_OPENING_AT),
            multiOpening(),
            multiState(0),
            marketId = null,
        )
    val rejected = multiStep("R-SUI", SUI_LARGE, multiState(2))
    return listOf(
        opening,
        multiStep("R-BTC", BTC, multiState(1)),
        rejected,
        rejected.copy(name = "R-SUI-REPLAY"),
        multiStep("R-SUI-2", SUI_SMALL, multiState(3)),
        rejected.copy(name = "R-SUI-REPLAY-FINAL", expectedState = multiState(3)),
    )
}

private fun multiStep(
    name: String,
    order: MultiMarketExpectedOrder,
    state: Map<String, Any?>,
) = OrderReplicationStep(
    name,
    "ORDER",
    listOf(
        order.request,
        "U1",
        order.id,
        "BUY",
        order.price.toString(),
        order.quantity.toString(),
        "ledger-${order.request}",
        MULTI_ORDER_AT,
        "--market",
        "${order.asset}-USDT",
    ),
    multiIdentifiers(order) +
        if (order.sequence > 0) {
            mapOf("outcome" to "ACCEPTED", "sequence" to order.sequence)
        } else {
            multiRejection()
        },
    state,
    marketId = "${order.asset}-USDT",
)

/** 현재 잔고와 과거 승인 후보는 따로 만든다. 최종 잔고가 후보의 이전 잔고를 덮어쓰면 안 된다. */
private fun multiState(stage: Int): Map<String, Any?> {
    val pending =
        when (stage) {
            0 -> emptyList()
            1, 2 -> listOf(multiRecord(BTC))
            else -> listOf(multiRecord(BTC), multiRecord(SUI_SMALL))
        }
    val requests =
        buildList {
            if (stage >= 1) add(multiRequest(BTC))
            if (stage >= 2) add(multiRequest(SUI_LARGE))
            if (stage >= 3) add(multiRequest(SUI_SMALL))
        }
    val available =
        when (stage) {
            0 -> 1_000_000_000L
            1, 2 -> 400_000_000L
            else -> 0L
        }
    return mapOf(
        "balances" to listOf(multiBalance(available)),
        "preparationCount" to 1,
        "pendingOrderCount" to pending.size,
        "requestRecordCount" to requests.size,
        "lastSequence" to pending.size,
        "preparations" to listOf(multiOpening()),
        "pendingOrders" to pending,
        "requests" to requests,
        "transactions" to
            listOf(multiTransaction("OPENING-1", "OPENING:P1", "OPENING", 1_000_000_000)) +
            (if (stage >= 1) listOf(multiReserve(BTC)) else emptyList()) +
            (if (stage >= 3) listOf(multiReserve(SUI_SMALL)) else emptyList()),
    )
}

private fun multiRequest(order: MultiMarketExpectedOrder): Map<String, Any?> =
    mapOf(
        "requestId" to order.request,
        "order" to multiOrderFields(order),
        "market" to multiMarket(order.asset).minus("feePolicy"),
        "feePolicy" to multiPolicy(order.asset),
        "ledgerTransactionId" to "ledger-${order.request}",
        "result" to
            if (order.sequence > 0) {
                mapOf("outcome" to "ACCEPTED", "record" to multiRecord(order))
            } else {
                multiRejection()
            },
    ) + multiInstant(opening = false)

private fun multiRecord(order: MultiMarketExpectedOrder): Map<String, Any?> =
    mapOf(
        "sequence" to order.sequence,
        "candidate" to
            mapOf(
                "order" to multiOrderFields(order),
                "market" to multiMarket(order.asset).minus("feePolicy"),
                "feePolicy" to multiPolicy(order.asset),
                "previousBalance" to multiBalance(if (order.asset == "BTC") 1_000_000_000 else 400_000_000),
                "nextBalance" to multiBalance(if (order.asset == "BTC") 400_000_000 else 0),
                "reservation" to
                    mapOf(
                        "marketId" to "${order.asset}-USDT",
                        "orderId" to order.id,
                        "userId" to "U1",
                        "side" to "BUY",
                        "assetId" to "USDT",
                        "limitPrice" to order.price,
                        "initialQuantity" to order.quantity,
                        "remainingQuantity" to order.quantity,
                        "reservedAmount" to order.reserve,
                        "remainingAmount" to order.reserve,
                        "feePolicy" to multiPolicy(order.asset),
                        "initialFeeReserveAmount" to 0L,
                        "remainingFeeReserveAmount" to 0L,
                        "status" to "ACTIVE",
                        "feeRemainder" to 0L,
                        "releasedAmount" to null,
                    ),
                "reserveTransaction" to multiReserve(order),
            ),
    )

private fun multiReserve(order: MultiMarketExpectedOrder): Map<String, Any?> =
    multiTransaction("ledger-${order.request}", order.source, "RESERVE", order.reserve)

private fun multiTransaction(
    id: String,
    source: String,
    kind: String,
    amount: Long,
): Map<String, Any?> =
    mapOf(
        "transactionId" to id,
        "sourceEventId" to source,
        "kind" to kind,
        "postings" to
            listOf(
                mapOf(
                    "accountId" to if (kind == "OPENING") "SYSTEM:USDT:DEVELOPMENT_FUNDING" else "USER:U1:USDT:AVAILABLE",
                    "assetId" to "USDT",
                    "side" to "DEBIT",
                    "amount" to amount,
                ),
                mapOf(
                    "accountId" to if (kind == "OPENING") "USER:U1:USDT:AVAILABLE" else "USER:U1:USDT:HOLD",
                    "assetId" to "USDT",
                    "side" to "CREDIT",
                    "amount" to amount,
                ),
            ),
    ) + multiInstant(opening = kind == "OPENING")

private fun multiMarket(asset: String): Map<String, Any> =
    mapOf(
        "marketId" to "$asset-USDT",
        "baseAssetId" to asset,
        "quoteAssetId" to "USDT",
        "baseQuantityScale" to if (asset == "BTC") 8 else 9,
        "feePolicy" to multiPolicy(asset),
    )

private fun multiPolicy(asset: String): Map<String, Any> =
    mapOf(
        "productType" to "SPOT",
        "feeTier" to "NORMAL",
        "scheduleVersion" to if (asset == "BTC") 1 else 2,
        "makerPartsPerMillion" to 0,
        "takerPartsPerMillion" to 0,
    )

private fun multiOpening(): Map<String, Any?> =
    mapOf(
        "preparationId" to "P1",
        "userId" to "U1",
        "assetId" to "USDT",
        "amount" to 1_000_000_000L,
        "ledgerTransactionId" to "OPENING-1",
        "occurredAt" to MULTI_OPENING_AT,
        "alreadyPrepared" to false,
    )

private fun multiRejection(): Map<String, Any?> =
    mapOf(
        "outcome" to "REJECTED",
        "reason" to "INSUFFICIENT_BALANCE",
        "insufficientBalance" to
            mapOf("assetId" to "USDT", "available" to 400_000_000L, "required" to 500_000_000L, "shortfall" to 100_000_000L),
    )

private fun multiIdentifiers(order: MultiMarketExpectedOrder): Map<String, Any?> =
    mapOf("userId" to "U1", "requestId" to order.request, "marketId" to "${order.asset}-USDT", "orderId" to order.id)

private fun multiOrderFields(order: MultiMarketExpectedOrder): Map<String, Any?> =
    multiIdentifiers(order).minus("requestId") +
        mapOf("side" to "BUY", "orderType" to "LIMIT", "timeInForce" to "GTC", "price" to order.price, "quantity" to order.quantity)

private fun multiBalance(available: Long): Map<String, Any?> =
    mapOf("userId" to "U1", "assetId" to "USDT", "available" to available, "hold" to 1_000_000_000L - available)

private fun multiInstant(opening: Boolean): Map<String, Any?> =
    mapOf(
        "occurredAt" to if (opening) MULTI_OPENING_AT else MULTI_ORDER_AT,
        "occurredAtEpochSecond" to if (opening) 1_792_627_200L else 1_792_627_260L,
        "occurredAtNano" to if (opening) 0 else 123_456_789,
    )

private data class MultiMarketExpectedOrder(
    val asset: String,
    val request: String,
    val id: String,
    val price: Long,
    val quantity: Long,
    val sequence: Int,
    val reserve: Long,
    val source: String,
)

private const val MULTI_OPENING_AT = "2026-10-22T00:00:00Z"
private const val MULTI_ORDER_AT = "2026-10-22T00:01:00.123456789Z"
private val BTC =
    MultiMarketExpectedOrder(
        "BTC",
        "R-BTC",
        "O-BTC",
        100_000_000,
        600_000_000,
        1,
        600_000_000,
        "RESERVE:v1:018073f242abd9639ef45ee15937dc46ccac8474aaa7741930508b38a2a023d6",
    )
private val SUI_LARGE = MultiMarketExpectedOrder("SUI", "R-SUI", "O-SUI", 20_000_000, 25_000_000_000, 0, 500_000_000, "")
private val SUI_SMALL =
    MultiMarketExpectedOrder(
        "SUI",
        "R-SUI-2",
        "O-SUI-2",
        20_000_000,
        20_000_000_000,
        2,
        400_000_000,
        "RESERVE:v1:4743e36b7cefdefa470ba94427a4197aebf6519fe301aa6fdcf2fcf866a7354a",
    )

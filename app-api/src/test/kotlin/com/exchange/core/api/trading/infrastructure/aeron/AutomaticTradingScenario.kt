package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** scale8의 BTC 1개는 100,000,000이다. 아래 금융 정답은 도메인 계산기와 무관한 손계산 표다. */
internal fun automaticTradingSteps(multipleFills: Boolean): List<OrderReplicationStep> {
    val fixture = AutomaticTradingFixture()
    fixture.fund("P-BUY", "BUYER", "USDT", 1_000)
    fixture.fund("P-SELL", "SELLER", "BTC", if (multipleFills) 200_000_000 else 100_000_000)
    fixture.restingSell("S1", if (multipleFills) 90 else 100)
    if (multipleFills) fixture.restingSell("S2", 95)
    fixture.buyAndSettle(multipleFills)
    fixture.replayLast()
    return fixture.steps
}

private class AutomaticTradingFixture {
    val steps = mutableListOf<OrderReplicationStep>()
    private val balances = linkedMapOf<Pair<String, String>, Pair<Long, Long>>()
    private val preparations = mutableListOf<Map<String, Any?>>()
    private val transactions = mutableListOf<Map<String, Any?>>()
    private val requests = mutableListOf<Map<String, Any?>>()
    private val records = mutableListOf<Map<String, Any?>>()
    private val reservations = mutableListOf<Map<String, Any?>>()
    private val completions = mutableListOf<Map<String, Any?>>()
    private val book = mutableListOf<Map<String, Any?>>()
    private var engineSequence = 0L
    private var currentPending: Map<String, Any?>? = null
    private val market =
        mapOf(
            "marketId" to "BTC-USDT",
            "baseAssetId" to "BTC",
            "quoteAssetId" to "USDT",
            "baseQuantityScale" to 8,
            "priceTick" to 1L,
            "quantityStep" to 100_000_000L,
        )
    private val policy =
        mapOf(
            "productType" to "SPOT",
            "feeTier" to "NORMAL",
            "scheduleVersion" to 1L,
            "makerPartsPerMillion" to 0L,
            "takerPartsPerMillion" to 0L,
        )

    fun fund(
        id: String,
        user: String,
        asset: String,
        amount: Long,
    ) {
        balances[user to asset] = amount to 0L
        val ledger = "OPEN-$id"
        val instant = time(steps.size)
        val result =
            mapOf(
                "preparationId" to id,
                "userId" to user,
                "assetId" to asset,
                "amount" to amount,
                "ledgerTransactionId" to ledger,
                "occurredAt" to instant,
                "alreadyPrepared" to false,
            )
        preparations.add(result)
        transactions.add(
            transaction(
                ledger,
                "OPENING:$id",
                "OPENING",
                listOf(
                    posting("SYSTEM:$asset:DEVELOPMENT_FUNDING", asset, "DEBIT", amount),
                    posting("USER:$user:$asset:AVAILABLE", asset, "CREDIT", amount),
                ),
                instant,
            ),
        )
        steps.add(OrderReplicationStep(id, "FUNDING", listOf(id, user, asset, "$amount", ledger, instant), result, state(), null))
    }

    fun restingSell(
        id: String,
        price: Long,
    ) {
        val step = admit(id, "SELLER", "SELL", price, 100_000_000)
        engineSequence++
        book.add(bookOrder(id, "SELLER", "SELL", price, 100_000_000, 100_000_000))
        complete(id, listOf(entered(id, "SELLER", "SELL", price, 100_000_000, engineSequence)), emptyList())
        steps.add(step.copy(executionState = state(), executionTrading = trading()))
    }

    fun buyAndSettle(multiple: Boolean) {
        val quantity = if (multiple) 300_000_000L else 100_000_000L
        val step = admit("B1", "BUYER", "BUY", 100, quantity)
        val priorBalances = balances.toMap()
        val priorReservations = reservations.toList()
        val prices = if (multiple) listOf(90L, 95L) else listOf(100L)
        val settlements = mutableListOf<Map<String, Any?>>()
        val events = mutableListOf<Map<String, Any?>>()
        for ((index, price) in prices.withIndex()) {
            engineSequence++
            val sellerId = "S${index + 1}"
            val transactionId = settlementId(engineSequence)
            val postings =
                buildList {
                    add(posting("USER:SELLER:BTC:HOLD", "BTC", "DEBIT", 100_000_000))
                    add(posting("USER:SELLER:USDT:AVAILABLE", "USDT", "CREDIT", price))
                    add(posting("USER:BUYER:USDT:HOLD", "USDT", "DEBIT", price))
                    if (price < 100) {
                        add(posting("USER:BUYER:USDT:HOLD", "USDT", "DEBIT", 100 - price))
                        add(posting("USER:BUYER:USDT:AVAILABLE", "USDT", "CREDIT", 100 - price))
                    }
                    add(posting("USER:BUYER:BTC:AVAILABLE", "BTC", "CREDIT", 100_000_000))
                }
            transactions.add(transaction(transactionId, "MATCHING:BTC-USDT:$engineSequence", "SETTLEMENT", postings, null))
            events.add(
                mapOf(
                    "kind" to "TRADE_EXECUTED",
                    "marketId" to "BTC-USDT",
                    "engineSequence" to engineSequence,
                    "makerOrderId" to sellerId,
                    "takerOrderId" to "B1",
                    "makerUserId" to "SELLER",
                    "takerUserId" to "BUYER",
                    "side" to "BUY",
                    "price" to price,
                    "quantity" to 100_000_000L,
                ),
            )
            settlements.add(
                mapOf(
                    "marketId" to "BTC-USDT",
                    "engineSequence" to engineSequence,
                    "makerOrderId" to sellerId,
                    "takerOrderId" to "B1",
                    "makerUserId" to "SELLER",
                    "takerUserId" to "BUYER",
                    "side" to "BUY",
                    "price" to price,
                    "quantity" to 100_000_000L,
                    "ledgerTransactionId" to transactionId,
                    "makerPlan" to
                        mapOf(
                            "reservation" to
                                (
                                    priorReservations[index] +
                                        mapOf("remainingQuantity" to 0L, "remainingAmount" to 0L, "status" to "SETTLED")
                                ),
                            "reservedAmountToReduce" to 100_000_000L,
                            "holdAmountToConsume" to 100_000_000L,
                            "holdAmountToRelease" to 0L,
                            "creditAssetId" to "USDT",
                            "creditAmount" to price,
                            "feeAssetId" to "USDT",
                            "actualFeeAmount" to 0L,
                        ),
                    "takerPlan" to
                        mapOf(
                            "reservation" to
                                (
                                    priorReservations.last() +
                                        mapOf(
                                            "remainingQuantity" to quantity - (index + 1L) * 100_000_000L,
                                            "remainingAmount" to
                                                (quantity / 100_000_000L - index - 1L) * 100L,
                                            "status" to if (multiple) "ACTIVE" else "SETTLED",
                                        )
                                ),
                            "reservedAmountToReduce" to 100L,
                            "holdAmountToConsume" to price,
                            "holdAmountToRelease" to 100 - price,
                            "creditAssetId" to "BTC",
                            "creditAmount" to 100_000_000L,
                            "feeAssetId" to "USDT",
                            "actualFeeAmount" to 0L,
                        ),
                ),
            )
            reservations[index] = reservations[index] + mapOf("remainingQuantity" to 0L, "remainingAmount" to 0L, "status" to "SETTLED")
        }
        book.clear()
        if (multiple) {
            engineSequence++
            book.add(bookOrder("B1", "BUYER", "BUY", 100, quantity, 100_000_000))
            events.add(entered("B1", "BUYER", "BUY", 100, 100_000_000, engineSequence))
        }
        val spent = if (multiple) 185L else 100L
        balances["BUYER" to "USDT"] = (1_000 - spent - if (multiple) 100 else 0) to if (multiple) 100L else 0L
        balances["SELLER" to "BTC"] = 0L to 0L
        balances["SELLER" to "USDT"] = spent to 0L
        balances["BUYER" to "BTC"] = (if (multiple) 200_000_000L else 100_000_000L) to 0L
        reservations[reservations.lastIndex] =
            reservations.last() +
            mapOf(
                "remainingQuantity" to if (multiple) 100_000_000L else 0L,
                "remainingAmount" to if (multiple) 100L else 0L,
                "status" to if (multiple) "ACTIVE" else "SETTLED",
            )
        val changedKeys = listOf("SELLER" to "BTC", "SELLER" to "USDT", "BUYER" to "USDT", "BUYER" to "BTC")
        val balanceChanges =
            changedKeys.map { key ->
                mapOf(
                    "previous" to balance(key.first, key.second, priorBalances[key] ?: (0L to 0L)),
                    "next" to balance(key.first, key.second, balances.getValue(key)),
                )
            }
        val reservationOrder = if (multiple) listOf(0, 2, 1) else listOf(0, 1)
        val reservationChanges =
            reservationOrder.map { index ->
                mapOf("previous" to priorReservations[index], "next" to reservations[index])
            }
        complete("B1", events, settlements, balanceChanges, reservationChanges)
        steps.add(step.copy(executionState = state(), executionTrading = trading()))
    }

    fun replayLast() {
        val original = steps.last()
        steps.add(original.copy(name = "B1-REPLAY", expectedState = state(), executionState = null, executionTrading = null))
    }

    private fun admit(
        id: String,
        user: String,
        side: String,
        price: Long,
        quantity: Long,
    ): OrderReplicationStep {
        val sequence = records.size + 1L
        val asset = if (side == "BUY") "USDT" else "BTC"
        val reserved = if (side == "BUY") price * (quantity / 100_000_000L) else quantity
        val previous = balances.getValue(user to asset)
        val next = (previous.first - reserved) to (previous.second + reserved)
        balances[user to asset] = next
        val instant = time(steps.size)
        val ledger = "RESERVE-$id"
        val order =
            mapOf(
                "marketId" to "BTC-USDT",
                "orderId" to id,
                "userId" to user,
                "side" to side,
                "orderType" to "LIMIT",
                "timeInForce" to "GTC",
                "price" to price,
                "quantity" to quantity,
            )
        val reserve =
            mapOf(
                "marketId" to "BTC-USDT",
                "orderId" to id,
                "userId" to user,
                "side" to side,
                "assetId" to asset,
                "limitPrice" to price,
                "initialQuantity" to quantity,
                "remainingQuantity" to quantity,
                "reservedAmount" to reserved,
                "remainingAmount" to reserved,
                "feePolicy" to policy,
                "initialFeeReserveAmount" to 0L,
                "remainingFeeReserveAmount" to 0L,
                "feeRemainder" to 0L,
                "status" to "ACTIVE",
                "releasedAmount" to null,
            )
        val tx =
            transaction(
                ledger,
                reserveSource(id),
                "RESERVE",
                listOf(
                    posting("USER:$user:$asset:AVAILABLE", asset, "DEBIT", reserved),
                    posting("USER:$user:$asset:HOLD", asset, "CREDIT", reserved),
                ),
                instant,
            )
        transactions.add(tx)
        val record =
            mapOf(
                "sequence" to sequence,
                "candidate" to
                    mapOf(
                        "order" to order,
                        "market" to market,
                        "feePolicy" to policy,
                        "previousBalance" to balance(user, asset, previous),
                        "nextBalance" to balance(user, asset, next),
                        "reservation" to reserve,
                        "reserveTransaction" to tx,
                    ),
            )
        records.add(record)
        reservations.add(reserve)
        requests.add(
            mapOf(
                "requestId" to "R-$id",
                "order" to order,
                "market" to market,
                "feePolicy" to policy,
                "ledgerTransactionId" to ledger,
                "result" to mapOf("outcome" to "ACCEPTED", "record" to record),
            ) +
                instantFields(instant),
        )
        currentPending = record
        return OrderReplicationStep(
            id,
            "ORDER",
            listOf("R-$id", user, id, side, "$price", "$quantity", ledger, instant),
            mapOf(
                "outcome" to "ACCEPTED",
                "userId" to user,
                "requestId" to "R-$id",
                "marketId" to "BTC-USDT",
                "orderId" to id,
                "sequence" to sequence,
            ),
            state(),
            "BTC-USDT",
        )
    }

    private fun complete(
        id: String,
        events: List<Map<String, Any?>>,
        settlements: List<Map<String, Any?>>,
        balanceChanges: List<Map<String, Any?>> = emptyList(),
        reservationChanges: List<Map<String, Any?>> = emptyList(),
    ) {
        val sequence = records.size.toLong()
        completions.add(
            mapOf(
                "admissionSequence" to sequence,
                "executionId" to "EXECUTE:v1:$sequence",
                "marketId" to "BTC-USDT",
                "orderId" to id,
                "expectedEventCount" to events.size,
                "firstEngineSequence" to events.first().getValue("engineSequence"),
                "lastEngineSequence" to events.last().getValue("engineSequence"),
                "events" to events,
                "settlements" to settlements,
                "balanceChanges" to balanceChanges,
                "reservationChanges" to reservationChanges,
            ),
        )
        currentPending = null
    }

    private fun state(): Map<String, Any?> =
        mapOf(
            "balances" to balances.map { (key, amount) -> balance(key.first, key.second, amount) },
            "preparationCount" to preparations.size,
            "pendingOrderCount" to
                if (currentPending ==
                    null
                ) {
                    0
                } else {
                    1
                },
            "requestRecordCount" to requests.size,
            "lastSequence" to records.size,
            "preparations" to preparations.toList(),
            "pendingOrders" to listOfNotNull(currentPending),
            "requests" to requests.toList(),
            "transactions" to transactions.toList(),
        )

    private fun trading(): Map<String, Any?> =
        mapOf(
            "currentReservations" to reservations.toList(),
            "books" to listOf(mapOf("marketId" to "BTC-USDT", "lastEngineSequence" to engineSequence, "orders" to book.toList())),
            "completions" to completions.toList(),
        )

    private fun time(index: Int) = "2026-10-26T00:00:${index.toString().padStart(2, '0')}.123456789Z"

    private fun entered(
        id: String,
        user: String,
        side: String,
        price: Long,
        quantity: Long,
        sequence: Long,
    ): Map<String, Any?> =
        mapOf(
            "kind" to "ORDER_ENTERED_BOOK",
            "marketId" to "BTC-USDT",
            "engineSequence" to sequence,
            "orderId" to id,
            "userId" to user,
            "side" to side,
            "price" to price,
            "remainingQuantity" to quantity,
        )

    private fun bookOrder(
        id: String,
        user: String,
        side: String,
        price: Long,
        original: Long,
        remaining: Long,
    ): Map<String, Any?> =
        mapOf(
            "orderId" to id,
            "userId" to user,
            "side" to side,
            "price" to price,
            "originalQuantity" to original,
            "remainingQuantity" to remaining,
        )

    private fun balance(
        user: String,
        asset: String,
        amount: Pair<Long, Long>,
    ) = mapOf(
        "userId" to user,
        "assetId" to asset,
        "available" to amount.first,
        "hold" to amount.second,
    )

    private fun posting(
        account: String,
        asset: String,
        side: String,
        amount: Long,
    ) = mapOf(
        "accountId" to account,
        "assetId" to asset,
        "side" to side,
        "amount" to amount,
    )

    private fun transaction(
        id: String,
        source: String,
        kind: String,
        postings: List<Map<String, Any>>,
        time: String?,
    ): Map<String, Any?> =
        mapOf("transactionId" to id, "sourceEventId" to source, "kind" to kind, "postings" to postings) +
            if (time == null) emptyMap() else instantFields(time)

    private fun instantFields(time: String): Map<String, Any?> =
        Instant.parse(time).let {
            mapOf(
                "occurredAt" to time,
                "occurredAtEpochSecond" to it.epochSecond,
                "occurredAtNano" to it.nano,
            )
        }

    private fun reserveSource(order: String): String {
        val marketBytes = "BTC-USDT".toByteArray()
        val orderBytes = order.toByteArray()
        val bytes =
            ByteBuffer
                .allocate(
                    8 + marketBytes.size + orderBytes.size,
                ).putInt(marketBytes.size)
                .put(marketBytes)
                .putInt(orderBytes.size)
                .put(orderBytes)
                .array()
        return "RESERVE:v1:" +
            java.util.HexFormat
                .of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun settlementId(sequence: Long): String {
        val marketBytes = "BTC-USDT".toByteArray()
        val bytes =
            ByteBuffer
                .allocate(
                    4 + 4 + marketBytes.size + 8,
                ).put(byteArrayOf(83, 84, 76, 49))
                .putInt(marketBytes.size)
                .put(marketBytes)
                .putLong(sequence)
                .array()
        return "SETTLEMENT:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

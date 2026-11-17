package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertTrue

/** 손계산 fixture를 변형해 사업 정답 판정의 거짓 통과를 막는다. 실제 장애 검증은 native 시험이 맡는다. */
class FundsQuorumConvergenceTest {
    private val mapper = jacksonObjectMapper()
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }.toSet()
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val request = listOf("B0", "P_B", "P_S", "N_B", "S_B", "S_S")
    private val market = mapOf("B0" to 0, "P_B" to 0, "P_S" to 1, "N_B" to 0, "S_B" to 0, "S_S" to 1)
    private val input = mapOf("B0" to 1, "P_B" to 2, "P_S" to 1, "N_B" to 3, "S_B" to 4, "S_S" to 2)

    @Test
    fun `다섯 업무 지점의 수기 정답과 명시한 live7 live8 live9를 허용한다`() {
        for (stage in FundsQuorumStage.entries) {
            for (target in listOf(names, names - "funds0", names - setOf("funds0", "funds1"))) {
                valid(stage, states(stage).filterKeys { it in target }, originals(stage), target)
            }
        }
    }

    @Test
    fun `고립 singleton 보호 상태와 과반 해결 상태를 서로 다른 대상 집합으로 검사한다`() {
        valid(
            FundsQuorumStage.PROTECTED,
            states(FundsQuorumStage.PROTECTED).filterKeys {
                it == "funds0"
            },
            originals(FundsQuorumStage.PROTECTED),
            setOf("funds0"),
        )
        val majority = names - "funds0"
        valid(
            FundsQuorumStage.RESOLVED,
            states(FundsQuorumStage.RESOLVED).filterKeys {
                it in majority
            },
            originals(FundsQuorumStage.RESOLVED),
            majority,
        )
        val matching = names.filterNot { it.startsWith("funds") }.toSet()
        valid(
            FundsQuorumStage.PROTECTED,
            states(FundsQuorumStage.PROTECTED).filterKeys {
                it in matching
            },
            originals(FundsQuorumStage.PROTECTED),
            matching,
        )
    }

    @Test
    fun `주문 ID 숫자와 두 마켓 사이 전역 승인 순서는 수기 정답으로 고정하지 않는다`() {
        val rows = states(FundsQuorumStage.COMPLETE)
        val original = originals(FundsQuorumStage.COMPLETE)

        fun renamed(node: JsonNode): JsonNode {
            var json = node.toString()
            request.forEachIndexed { index, key -> json = json.replace("\"$key\"", "\"order-${701 - index * 17}\"") }
            return mapper.readTree(json)
        }
        valid(FundsQuorumStage.COMPLETE, rows.mapValues { renamed(it.value) }, original.mapValues { renamed(it.value) })
        change(original.getValue("P_B"), "/receipt", "admissionSequence", 3)
        change(original.getValue("P_S"), "/receipt", "admissionSequence", 2)
        for (name in names.filter { it.startsWith("funds") }) {
            change(rows.getValue(name), "/funds/receipts/1/receipt", "admissionSequence", 3)
            change(rows.getValue(name), "/funds/receipts/2/receipt", "admissionSequence", 2)
            change(rows.getValue(name), "/funds/liveOrderDetails/1", "admissionSequence", 3)
        }
        valid(FundsQuorumStage.COMPLETE, rows, original)
    }

    @Test
    fun `빈 대상 누락 사본 미래 원응답을 자동으로 허용하지 않는다`() {
        val stage = FundsQuorumStage.PROTECTED
        reject(stage, states(stage) - "funds0")
        reject(stage, emptyMap())
        assertTrue(FundsQuorumConvergence.violations(stage, emptyMap(), originals(stage), emptySet()).isNotEmpty())
        reject(stage, states(stage), originals(FundsQuorumStage.RESOLVED))
        reject(stage, states(stage), emptyMap())
        reject(stage, states(stage) + ("funds3" to states(stage).getValue("funds0")))
    }

    @Test
    fun `총액이 같아도 계정 자산 예약 상태 원문 token 순번이 틀리면 실패한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds/balances/0", "userId", "seller"),
            Triple("/funds/balances/0", "assetId", "BTC"),
            Triple("/funds/balances/0", "available", 101),
            Triple("/funds/balances/0", "hold", 699),
            Triple("/funds/liveOrderDetails/0", "orderId", "unknown"),
            Triple("/funds/liveOrderDetails/0", "marketId", "SUI-USDT"),
            Triple("/funds/liveOrderDetails/0", "phase", "PENDING"),
            Triple("/funds/liveOrderDetails/0", "status", "SETTLED"),
            Triple("/funds/liveOrderDetails/0", "assetId", "BTC"),
            Triple("/funds/liveOrderDetails/0", "remainingAmount", 600),
            Triple("/funds/liveOrderDetails/0", "remainingQuantity", 6),
            Triple("/funds/liveOrderDetails/0", "remainingFeeReserveAmount", 1),
            Triple("/funds/liveOrderDetails/0", "feeRemainder", 1),
            Triple("/funds/receipts/1", "sequence", 1),
            Triple("/funds/receipts/1", "epoch", 2),
            Triple("/funds/receipts/1/intent", "quantity", 2),
            Triple("/funds/receipts/1/intent", "side", "SELL"),
            Triple("/funds", "lastCancellationSequence", 1),
            Triple("/funds", "lastAdmissionSequence", 5),
            Triple("/funds/dispatch/BTC-USDT", "matchingAppliedThrough", 3),
            Triple("/funds/resultProgress/SUI-USDT", "resultThrough", 1),
            Triple("/funds", "dispatchBytes", 1),
            Triple("/funds/fees", "USDT", "1"),
        )) {
            invalid(pointer, field, value)
        }
        val rows = states(FundsQuorumStage.COMPLETE)
        for (name in names.filter { it.startsWith("funds") }) {
            change(rows.getValue(name), "/funds/balances/0", "available", 101)
            change(rows.getValue(name), "/funds/balances/0", "hold", 699)
        }
        reject(FundsQuorumStage.COMPLETE, rows)
    }

    @Test
    fun `최초 receipt와 서로 다른 여섯 주문 ID를 보존해야 한다`() {
        invalid("/funds/receipts/1/receipt", "orderId", "B0")
        val originals = originals(FundsQuorumStage.COMPLETE)
        change(originals.getValue("P_B"), "/receipt", "orderId", "B0")
        reject(FundsQuorumStage.COMPLETE, states(FundsQuorumStage.COMPLETE), originals)
        change(originals.getValue("P_B"), "/receipt", "admissionSequence", 1)
        reject(FundsQuorumStage.COMPLETE, states(FundsQuorumStage.COMPLETE), originals)
    }

    @Test
    fun `결과 누락 중복 잘못된 FIFO 상대와 효과 및 지문 변형을 거절한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds", "resultDetails", emptyList<Any>()),
            Triple("/funds/resultDetails/0", "resultSequence", 2),
            Triple("/funds/resultDetails/1", "resultId", "result-B0"),
            Triple("/funds/resultDetails/0", "payloadSha256", "c".repeat(64)),
            Triple("/funds/resultDetails/0", "inputSequence", 2),
            Triple("/funds/resultDetails/0", "engineAfter", 2),
            Triple("/funds/resultDetails/4/events/0", "makerOrderId", "P_B"),
            Triple("/funds/resultDetails/4/events/0", "quantity", 2),
            Triple("/funds/resultDetails/4/effects/0", "afterQuantity", 6),
            Triple("/funds/resultDetails/5/effects/0", "disposition", "RESTING"),
            Triple("/funds/resultDetails/5/events/0", "tradeId", "result-S_B:4"),
        )) {
            invalid(pointer, field, value)
        }
        for ((pointer, field, value) in listOf(
            Triple("/matching/markets/BTC-USDT", "orders", emptyList<Any>()),
            Triple("/matching/markets/BTC-USDT/orders/0", "remainingQuantity", 6),
            Triple("/matching/markets/BTC-USDT/orders/0", "orderId", "P_B"),
            Triple("/matching/markets/BTC-USDT", "fundsSettledThrough", 3),
            Triple("/matching", "resultBytes", 1),
            Triple("/matching/resultDetails/0", "payloadSha256", "c".repeat(64)),
        )) {
            invalid(pointer, field, value, "btc")
        }
    }

    private fun valid(
        stage: FundsQuorumStage,
        rows: Map<String, JsonNode>,
        original: Map<String, JsonNode>,
        expected: Set<String> = names,
    ) {
        val failures = FundsQuorumConvergence.violations(stage, rows, original, expected)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun reject(
        stage: FundsQuorumStage,
        rows: Map<String, JsonNode>,
        original: Map<String, JsonNode> = originals(stage),
    ) {
        assertTrue(FundsQuorumConvergence.violations(stage, rows, original).isNotEmpty(), "위반 관찰을 통과시켰습니다")
    }

    private fun invalid(
        pointer: String,
        field: String,
        value: Any,
        group: String = "funds",
    ) {
        val rows = states(FundsQuorumStage.COMPLETE)
        for (name in names.filter { it.startsWith(group) }) change(rows.getValue(name), pointer, field, value)
        reject(FundsQuorumStage.COMPLETE, rows)
    }

    private fun change(
        node: JsonNode,
        pointer: String,
        field: String,
        value: Any,
    ) {
        (node.at(pointer) as ObjectNode).set(field, tree(value))
    }

    private fun tree(value: Any?): JsonNode = mapper.valueToTree(value)

    private fun keys(stage: FundsQuorumStage) =
        request.take(
            when (stage) {
                FundsQuorumStage.INITIALIZED -> 0
                FundsQuorumStage.PROTECTED -> 1
                FundsQuorumStage.RESOLVED -> 3
                FundsQuorumStage.FOLLOWUP -> 4
                FundsQuorumStage.COMPLETE -> 6
            },
        )

    private fun token(key: String) =
        mapOf(
            "userId" to if (key.startsWith("S_")) "seller" else "buyer",
            "channel" to market.getValue(key),
            "epoch" to 1,
            "sequence" to
                when (key) {
                    "P_B" -> 2
                    "N_B" -> 3
                    else -> 1
                },
        )

    private fun receipt(key: String) =
        mapOf(
            "code" to "ACCEPTED",
            "orderId" to key,
            "admissionSequence" to request.indexOf(key) + 1,
            "marketAdmissionSequence" to input.getValue(key),
            "reason" to null,
            "insufficientBalance" to null,
        )

    private fun originals(stage: FundsQuorumStage) =
        keys(stage)
            .associateWith {
                tree(mapOf("code" to "ACCEPTED", "receipt" to receipt(it)))
            }.toMutableMap()

    private fun result(key: String): Map<String, Any?> {
        val sequence = input.getValue(key)
        val sell = key.startsWith("S_")
        val maker = if (key == "S_B") "B0" else "P_S"
        val quantity = if (key == "B0") 6 else 1

        fun effect(
            order: String,
            origin: Int,
            before: Int,
            after: Int,
            disposition: String,
        ) = mapOf(
            "orderId" to order,
            "originInputSequence" to origin,
            "beforeQuantity" to before,
            "afterQuantity" to after,
            "disposition" to disposition,
        )
        val event =
            mapOf("engineSequence" to sequence, "quantity" to if (sell) 1 else quantity, "price" to 100) +
                if (sell) {
                    mapOf("kind" to "TRADE", "tradeId" to "result-$key:$sequence", "makerOrderId" to maker, "takerOrderId" to key)
                } else {
                    mapOf("kind" to "RESTING", "orderId" to key)
                }
        val effects =
            if (sell) {
                listOf(
                    effect(
                        maker,
                        1,
                        if (key ==
                            "S_B"
                        ) {
                            6
                        } else {
                            1
                        },
                        if (key ==
                            "S_B"
                        ) {
                            5
                        } else {
                            0
                        },
                        if (key == "S_B") "RESTING" else "TERMINAL",
                    ),
                    effect(key, sequence, 1, 0, "TERMINAL"),
                )
            } else {
                listOf(effect(key, sequence, quantity, quantity, "RESTING"))
            }
        return mapOf(
            "marketId" to markets[market.getValue(key)],
            "resultSequence" to sequence,
            "resultId" to "result-$key",
            "orderId" to key,
            "cancelId" to null,
            "inputSequence" to sequence,
            "outcome" to "MATCHED",
            "engineBefore" to sequence - 1,
            "engineAfter" to sequence,
            "payloadSha256" to "b".repeat(64),
            "events" to listOf(event),
            "effects" to effects,
        )
    }

    private fun states(stage: FundsQuorumStage): MutableMap<String, JsonNode> {
        val keys = keys(stage)
        val complete = stage == FundsQuorumStage.COMPLETE
        val live = keys.filter { !it.startsWith("S_") && !(complete && it == "P_S") }
        val balance =
            when (stage) {
                FundsQuorumStage.INITIALIZED -> 1000 to 0
                FundsQuorumStage.PROTECTED -> 400 to 600
                FundsQuorumStage.RESOLVED -> 200 to 800
                FundsQuorumStage.FOLLOWUP -> 100 to 900
                FundsQuorumStage.COMPLETE -> 100 to 700
            }
        val balances =
            listOf(
                mapOf("userId" to "buyer", "assetId" to "USDT", "available" to balance.first, "hold" to balance.second),
                mapOf("userId" to "seller", "assetId" to "USDT", "available" to if (complete) 200 else 0, "hold" to 0),
            ) +
                listOf("BTC", "SUI").flatMap { asset ->
                    listOf(
                        mapOf(
                            "userId" to "buyer",
                            "assetId" to asset,
                            "available" to if (complete) 1 else 0,
                            "hold" to 0,
                        ),
                        mapOf(
                            "userId" to "seller",
                            "assetId" to asset,
                            "available" to if (complete) 0 else 1,
                            "hold" to 0,
                        ),
                    )
                }

        fun book(key: String) =
            mapOf(
                "orderId" to key,
                "userId" to "buyer",
                "side" to "BUY",
                "price" to 100,
                "originalQuantity" to if (key == "B0") 6 else 1,
                "remainingQuantity" to
                    if (key == "B0") {
                        if (complete) 5 else 6
                    } else {
                        1
                    },
            )
        val funds =
            mapOf(
                "balances" to balances,
                "fees" to emptyMap<String, String>(),
                "lastAdmissionSequence" to keys.size,
                "lastCancellationSequence" to 0,
                "pendingOrders" to 0,
                "liveOrders" to live.size,
                "activeOrders" to live.size,
                "activeBytes" to live.size * 100,
                "dispatchEntries" to 0,
                "dispatchBytes" to 0,
                "receipts" to
                    keys.map { key ->
                        token(key) +
                            mapOf(
                                "kind" to "ORDER",
                                "marketId" to markets[market.getValue(key)],
                                "receipt" to receipt(key),
                                "intent" to
                                    mapOf(
                                        "expectedConfigurationVersion" to 1,
                                        "side" to if (key.startsWith("S_")) "SELL" else "BUY",
                                        "orderType" to "LIMIT",
                                        "timeInForce" to "GTC",
                                        "price" to 100,
                                        "quantity" to if (key == "B0") 6 else 1,
                                    ),
                            )
                    },
                "liveOrderDetails" to
                    live.map { key ->
                        val remaining =
                            if (key ==
                                "B0"
                            ) {
                                if (complete) 5 else 6
                            } else {
                                1
                            }
                        ; mapOf(
                            "orderId" to key,
                            "userId" to "buyer",
                            "marketId" to markets[market.getValue(key)],
                            "admissionSequence" to request.indexOf(key) + 1,
                            "marketAdmissionSequence" to input.getValue(key),
                            "phase" to "RESTING",
                            "status" to "ACTIVE",
                            "assetId" to "USDT",
                            "initialQuantity" to
                                if (key ==
                                    "B0"
                                ) {
                                    6
                                } else {
                                    1
                                },
                            "remainingQuantity" to remaining,
                            "reservedAmount" to
                                if (key ==
                                    "B0"
                                ) {
                                    600
                                } else {
                                    100
                                },
                            "remainingAmount" to remaining * 100,
                            "remainingFeeReserveAmount" to 0,
                            "feeRemainder" to 0,
                        )
                    },
                "dispatch" to
                    markets
                        .mapIndexed { index, market ->
                            val count = keys.count { this.market.getValue(it) == index }
                            val active =
                                live.count {
                                    this.market.getValue(it) ==
                                        index
                                }
                            ; market to
                                mapOf(
                                    "issued" to count,
                                    "matchingAppliedThrough" to count,
                                    "dispatchEntries" to 0,
                                    "dispatchBytes" to 0,
                                    "activeOrders" to active,
                                    "activeBytes" to active * 100,
                                    "outstanding" to emptyList<Any>(),
                                )
                        }.toMap(),
                "resultProgress" to
                    markets
                        .mapIndexed { index, market ->
                            val count = keys.count { this.market.getValue(it) == index }
                            market to
                                mapOf("resultThrough" to count, "settledEngineSequence" to count)
                        }.toMap(),
                "resultDetails" to keys.map { result(it) + ("appliedAt" to "2026-11-17T00:00:00Z") },
            )
        return names
            .associateWith { name ->
                if (name.startsWith("funds")) {
                    tree(mapOf("configurationVersion" to 1, "funds" to funds))
                } else {
                    val index = if (name.startsWith("btc")) 0 else 1
                    val count = keys.count { market.getValue(it) == index }
                    val active = live.filter { market.getValue(it) == index }
                    tree(
                        mapOf(
                            "matching" to
                                mapOf(
                                    "markets" to
                                        mapOf(
                                            markets[index] to
                                                mapOf(
                                                    "orders" to active.map(::book),
                                                    "engineSequence" to count,
                                                    "appliedThrough" to count,
                                                    "resultIssued" to count,
                                                    "fundsSettledThrough" to count,
                                                    "outstanding" to emptyList<Any>(),
                                                ),
                                        ),
                                    "resultEntries" to 0,
                                    "resultBytes" to 0,
                                    "activeOrders" to active.size,
                                    "activeBytes" to active.size * 100,
                                    "resultDetails" to keys.filter { market.getValue(it) == index }.map(::result),
                                ),
                        ),
                    )
                }
            }.toMutableMap()
    }
}

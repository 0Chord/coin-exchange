package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertTrue

/** 수기 정답으로 만든 관찰을 판정한다. 실제9 JVM 실행은 별도 native 검사의 책임이다. */
class MultiMatchingConvergenceTest {
    private val mapper = jacksonObjectMapper()
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val names = listOf("funds0", "funds1", "funds2", "btc0", "btc1", "btc2", "sui0", "sui1", "sui2")

    @Test
    fun `준비 예약 체결 두 취소의 수기 정상 관찰을 허용한다`() {
        for (stage in MultiMatchingStage.entries.filter { it != MultiMatchingStage.RACE }) valid(fixture(stage))
    }

    @Test
    fun `초기화에서 생성하지 않은 0 잔고 행을 생략해도 허용한다`() {
        val f = fixture(MultiMatchingStage.INITIALIZED)
        for (name in names.filter { it.startsWith("funds") }) {
            val funds = f.states.getValue(name).path("funds") as ObjectNode
            val preparedBuyer =
                funds.path("balances").toList().filter { row ->
                    row.path("userId").asString() == "buyer" && row.path("assetId").asString() == "USDT"
                }
            funds.set("balances", tree(preparedBuyer))
        }
        valid(f)
    }

    @Test
    fun `동시 주문은 실제 로그 위치에 따른 두 승자를 모두 허용한다`() {
        valid(fixture(MultiMatchingStage.RACE, "btc"))
        valid(fixture(MultiMatchingStage.RACE, "sui"))
    }

    @Test
    fun `빈 관찰 또는 아홉 중 하나의 누락은 실패다`() {
        val f = fixture()
        invalid(f.copy(states = mutableMapOf()))
        invalid(f.copy(states = (f.states - "sui2").toMutableMap()))
        invalid(f.copy(runtimes = (f.runtimes - "funds1").toMutableMap()))
    }

    @Test
    fun `다른 사본의 복제와 낡은 boot는 실패다`() {
        val duplicate = fixture()
        duplicate.states["btc2"] = duplicate.states.getValue("btc1")
        invalid(duplicate)
        val stale = fixture()
        change(stale.states.getValue("funds1"), "", "bootId", "old-boot")
        invalid(stale)
    }

    @Test
    fun `두 담당의 관찰과 runtime을 함께 교환해도 실패다`() {
        val f = fixture()
        for (index in 0..2) {
            val first = f.states.getValue("btc$index")
            f.states["btc$index"] = f.states.getValue("sui$index")
            f.states["sui$index"] = first
            val runtime = f.runtimes.getValue("btc$index")
            f.runtimes["btc$index"] = f.runtimes.getValue("sui$index")
            f.runtimes["sui$index"] = runtime
        }
        invalid(f)
    }

    @Test
    fun `한 사본의 의미 차이와 공통 지문 image 및 sync 불일치를 거절한다`() {
        val balance = fixture()
        change(balance.states.getValue("funds2"), "/funds/balances/0", "available", 799)
        invalid(balance)
        for ((field, value) in mapOf<String, Any>("topologySha256" to "d".repeat(64), "imageId" to "different")) {
            val f = fixture()
            change(f.runtimes.getValue("btc0"), "", field, value)
            invalid(f)
        }
        val sync = fixture()
        change(sync.runtimes.getValue("sui1"), "", "storageLevels", listOf(2, 0, 2))
        invalid(sync)
    }

    @Test
    fun `세 자금 사본의 합계가 서로 같아도 틀린 금액 수수료 예약은 실패다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds/balances/0", "available", 799),
            Triple("/funds/balances/0", "hold", 1),
            Triple("/funds/fees", "USDT", "3"),
        )) {
            val f = fixture()
            for (i in 0..2) change(f.states.getValue("funds$i"), pointer, field, value)
            invalid(f)
        }
    }

    @Test
    fun `주문장 수량 합계가 맞아도 원래 주문 ID가 다르면 실패다`() {
        val f = fixture(MultiMatchingStage.TRADED)
        for (i in 0..2) change(f.states.getValue("btc$i"), "/matching/markets/BTC-USDT/orders/0", "orderId", "wrong-order")
        invalid(f)
    }

    @Test
    fun `전체 결과 복제와 체결 상대 변경은 실패다`() {
        val duplicate = fixture()
        for (i in 0..2) {
            val details = duplicate.states.getValue("funds$i").at("/funds/resultDetails") as ArrayNode
            details.add(details.first())
        }
        invalid(duplicate)
        val counterpart = fixture()
        for (i in 0..2) change(counterpart.states.getValue("funds$i"), "/funds/resultDetails/1/events/0", "makerOrderId", "another-maker")
        invalid(counterpart)
    }

    @Test
    fun `금액은 최종 정답이어도 미완료 확인이나 남은 payload는 실패다`() {
        for ((pointer, field, value) in listOf(
            Triple("/matching/markets/SUI-USDT", "fundsSettledThrough", 2),
            Triple("/matching", "resultEntries", 1),
            Triple("/matching", "resultBytes", 1),
        )) {
            val f = fixture()
            for (i in 0..2) change(f.states.getValue("sui$i"), pointer, field, value)
            invalid(f)
        }
        val f = fixture()
        for (i in 0..2) change(f.states.getValue("funds$i"), "/funds", "dispatchEntries", 1)
        invalid(f)
    }

    @Test
    fun `응답으로 추정한 승자와 로그 위치가 다르거나 위치가 없으면 실패다`() {
        val f = fixture(MultiMatchingStage.RACE)
        for (i in 0..2) change(f.states.getValue("funds$i"), "/raceRows/0", "position", 300)
        invalid(f)
        val missing = fixture(MultiMatchingStage.RACE)
        for (i in 0..2) change(missing.states.getValue("funds$i"), "/raceRows/1", "position", 100)
        invalid(missing)
    }

    @Test
    fun `원래 사용자 응답을 바꾸거나 취소 대상을 바꾸면 실패다`() {
        val f = fixture()
        change(f.originals.getValue("buy-btc"), "/receipt", "orderId", "changed")
        invalid(f)
        val target = fixture()
        change(target.originals.getValue("cancel-btc"), "/receipt", "targetOrderId", "buy-sui")
        invalid(target)
    }

    @Test
    fun `관찰 위치가 다르고 PID 숫자가 같아도 별도 실행이면 허용한다`() {
        val f = fixture()
        for ((i, name) in names.withIndex()) change(f.states.getValue(name), "", "logPosition", 100L + i)
        valid(f) // 컨테이너별 PID는 모두1이어도 별도 container·boot로 구분한다.
    }

    private data class Fixture(
        val states: MutableMap<String, JsonNode>,
        val runtimes: MutableMap<String, JsonNode>,
        val originals: MutableMap<String, JsonNode>,
        val stage: MultiMatchingStage,
    )

    private fun valid(f: Fixture) {
        val issues = MultiMatchingConvergence.failures(f.states, f.runtimes, f.stage, f.originals)
        assertTrue(issues.isEmpty(), issues.joinToString("\n"))
    }

    private fun invalid(f: Fixture) {
        assertTrue(MultiMatchingConvergence.failures(f.states, f.runtimes, f.stage, f.originals).isNotEmpty())
    }

    private fun change(
        node: JsonNode,
        pointer: String,
        field: String,
        value: Any,
    ) {
        (node.at(pointer) as ObjectNode).set(field, tree(value))
    }

    private fun fixture(
        stage: MultiMatchingStage = MultiMatchingStage.COMPLETE,
        winner: String = "btc",
    ): Fixture {
        val race = stage == MultiMatchingStage.RACE
        val traded = stage in setOf(MultiMatchingStage.TRADED, MultiMatchingStage.BTC_CANCELLED, MultiMatchingStage.COMPLETE)
        val progress =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> listOf(0L, 0L)
                MultiMatchingStage.RACE -> if (winner == "btc") listOf(1L, 0L) else listOf(0L, 1L)
                MultiMatchingStage.RESERVED -> listOf(1L, 1L)
                MultiMatchingStage.TRADED -> listOf(2L, 2L)
                MultiMatchingStage.BTC_CANCELLED -> listOf(3L, 2L)
                MultiMatchingStage.COMPLETE -> listOf(3L, 3L)
            }
        val quantity =
            when (stage) {
                MultiMatchingStage.INITIALIZED, MultiMatchingStage.COMPLETE -> listOf(0L, 0L)
                MultiMatchingStage.RACE -> if (winner == "btc") listOf(6L, 0L) else listOf(0L, 5L)
                MultiMatchingStage.RESERVED -> listOf(2L, 3L)
                MultiMatchingStage.TRADED -> listOf(1L, 2L)
                MultiMatchingStage.BTC_CANCELLED -> listOf(0L, 2L)
            }
        val q =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> 1000L to 0L
                MultiMatchingStage.RACE -> if (winner == "btc") 400L to 600L else 500L to 500L
                MultiMatchingStage.RESERVED -> 495L to 505L
                MultiMatchingStage.TRADED -> 495L to 303L
                MultiMatchingStage.BTC_CANCELLED -> 596L to 202L
                MultiMatchingStage.COMPLETE -> 798L to 0L
            }
        val keys =
            when (stage) {
                MultiMatchingStage.INITIALIZED -> emptyList()
                MultiMatchingStage.RACE -> listOf("race-btc", "race-sui")
                MultiMatchingStage.RESERVED -> listOf("buy-btc", "buy-sui")
                MultiMatchingStage.TRADED -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui")
                MultiMatchingStage.BTC_CANCELLED -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui", "cancel-btc")
                MultiMatchingStage.COMPLETE -> listOf("buy-btc", "buy-sui", "sell-btc", "sell-sui", "cancel-btc", "cancel-sui")
            }
        val originals =
            keys
                .associateWith { key ->
                    val parts = key.split('-')
                    val rejected = race && parts[1] != winner
                    val receipt =
                        if (parts[0] == "cancel") {
                            mapOf<String, Any?>(
                                "code" to "ACCEPTED",
                                "cancelId" to key,
                                "targetOrderId" to "buy-${parts[1]}",
                                "inputSequence" to 3L,
                            )
                        } else {
                            mapOf<String, Any?>(
                                "code" to if (rejected) "REJECTED" else "ACCEPTED",
                                "orderId" to key.takeUnless { rejected },
                                "admissionSequence" to
                                    (
                                        if (race) {
                                            1L
                                        } else {
                                            when (key) {
                                                "buy-btc" -> 1L
                                                "buy-sui" -> 2L
                                                "sell-btc" -> 3L
                                                else -> 4L
                                            }
                                        }
                                    ).takeUnless { rejected },
                                "marketAdmissionSequence" to (if (parts[0] == "sell") 2L else 1L).takeUnless { rejected },
                                "reason" to if (rejected) "INSUFFICIENT_BALANCE" else null,
                            )
                        }
                    tree(mapOf<String, Any?>("code" to receipt.getValue("code"), "receipt" to receipt))
                }.toMutableMap()
        val details = markets.flatMapIndexed { index, market -> (1L..progress[index]).map { result(market, it, race) } }
        val active = quantity.count { it > 0 }.toLong()
        val funds =
            mapOf<String, Any?>(
                "balances" to
                    listOf(
                        balance("buyer", "USDT", q.first, q.second),
                        balance("buyer", "BTC", if (traded) 1 else 0),
                        balance("buyer", "SUI", if (traded) 1 else 0),
                        balance("seller", "USDT", if (traded) 198 else 0),
                        balance(
                            "seller",
                            "BTC",
                            if (stage == MultiMatchingStage.INITIALIZED ||
                                stage == MultiMatchingStage.RESERVED
                            ) {
                                1
                            } else {
                                0
                            },
                        ),
                        balance(
                            "seller",
                            "SUI",
                            if (stage == MultiMatchingStage.INITIALIZED ||
                                stage == MultiMatchingStage.RESERVED
                            ) {
                                1
                            } else {
                                0
                            },
                        ),
                    ),
                "fees" to mapOf<String, Any?>("USDT" to if (traded) "4" else "0"),
                "lastAdmissionSequence" to
                    if (stage == MultiMatchingStage.INITIALIZED) {
                        0L
                    } else if (race) {
                        1L
                    } else if (stage == MultiMatchingStage.RESERVED) {
                        2L
                    } else {
                        4L
                    },
                "lastCancellationSequence" to
                    when (stage) {
                        MultiMatchingStage.BTC_CANCELLED -> 1L
                        MultiMatchingStage.COMPLETE -> 2L
                        else -> 0L
                    },
                "dispatchEntries" to 0L,
                "dispatchBytes" to 0L,
                "activeOrders" to active,
                "activeBytes" to active * 100,
                "pendingOrders" to 0L,
                "liveOrders" to active,
                "dispatch" to
                    markets
                        .mapIndexed { i, market ->
                            market to
                                mapOf<String, Any?>(
                                    "issued" to progress[i],
                                    "matchingAppliedThrough" to progress[i],
                                    "dispatchEntries" to 0L,
                                    "dispatchBytes" to 0L,
                                    "activeOrders" to
                                        if (quantity[i] >
                                            0
                                        ) {
                                            1L
                                        } else {
                                            0L
                                        },
                                    "activeBytes" to if (quantity[i] > 0) 100L else 0L,
                                )
                        }.toMap(),
                "resultProgress" to
                    markets
                        .mapIndexed {
                            i,
                            market,
                            ->
                            market to mapOf<String, Any?>("resultThrough" to progress[i], "settledEngineSequence" to progress[i])
                        }.toMap(),
                "receipts" to
                    keys.map { key ->
                        val parts = key.split('-')
                        mapOf<String, Any?>(
                            "userId" to if (parts[0] == "sell") "seller" else "buyer",
                            "channel" to if (parts[1] == "btc") 0 else 1,
                            "epoch" to 1L,
                            "sequence" to
                                if (parts[0] ==
                                    "cancel"
                                ) {
                                    2L
                                } else {
                                    1L
                                },
                            "kind" to if (parts[0] == "cancel") "CANCEL" else "ORDER",
                            "marketId" to if (parts[1] == "btc") markets[0] else markets[1],
                            "receipt" to originals.getValue(key).path("receipt"),
                        )
                    },
                "resultDetails" to details.map { it + ("appliedAt" to "2026-11-16T00:00:00Z") },
            )
        val states = linkedMapOf<String, JsonNode>()
        val runtimes = linkedMapOf<String, JsonNode>()
        for (name in names) {
            val group = name.dropLast(1)
            val index = if (group == "btc") 0 else 1
            val matching =
                mapOf<String, Any?>(
                    "markets" to
                        mapOf<String, Any?>(
                            markets[index] to
                                mapOf<String, Any?>(
                                    "orders" to
                                        if (quantity[index] == 0L) {
                                            emptyList()
                                        } else {
                                            listOf(
                                                mapOf<String, Any?>(
                                                    "orderId" to (if (race) "race-" else "buy-") + group,
                                                    "userId" to "buyer",
                                                    "side" to "BUY",
                                                    "price" to 100L,
                                                    "originalQuantity" to
                                                        if (race) {
                                                            if (group == "btc") {
                                                                6L
                                                            } else {
                                                                5L
                                                            }
                                                        } else if (group == "btc") {
                                                            2L
                                                        } else {
                                                            3L
                                                        },
                                                    "remainingQuantity" to quantity[index],
                                                ),
                                            )
                                        },
                                    "engineSequence" to progress[index],
                                    "appliedThrough" to progress[index],
                                    "resultIssued" to progress[index],
                                    "fundsSettledThrough" to progress[index],
                                ),
                        ),
                    "resultEntries" to 0L,
                    "resultBytes" to 0L,
                    "activeOrders" to if (quantity[index] > 0) 1L else 0L,
                    "activeBytes" to if (quantity[index] > 0) 100L else 0L,
                    "resultDetails" to details.filter { it["marketId"] == markets[index] },
                )
            val identity =
                mapOf<String, Any?>(
                    "runId" to "native-case",
                    "bootId" to "boot-$name",
                    "role" to if (group == "funds") "FUNDS" else "MATCHING",
                    "memberId" to name.last().digitToInt(),
                    "clusterId" to
                        when (group) {
                            "funds" -> 198
                            "btc" -> 199
                            else -> 200
                        },
                    "lineageHigh" to
                        when (group) {
                            "funds" -> 10L
                            "btc" -> 20L
                            else -> 30L
                        },
                    "lineageLow" to 1L,
                    "markets" to if (group == "funds") markets else listOf(markets[index]),
                    "manifestSha256" to "a".repeat(64),
                    "topologySha256" to "b".repeat(64),
                    "resultTopologySha256" to "c".repeat(64),
                    "pid" to 1L,
                )
            val rows =
                if (!race || group != "funds") {
                    emptyList()
                } else {
                    markets.mapIndexed { i, market ->
                        val label = if (i == 0) "btc" else "sui"
                        mapOf<String, Any?>(
                            "marketId" to market,
                            "channel" to i,
                            "epoch" to 1L,
                            "sequence" to 1L,
                            "position" to if (winner == label) 100L else 200L,
                            "code" to if (winner == label) "ACCEPTED" else "REJECTED",
                            "orderId" to if (winner == label) "race-$label" else null,
                        )
                    }
                }
            states[name] =
                tree(
                    identity +
                        mapOf<String, Any?>(
                            "initialized" to true,
                            "lifecycle" to "RUNNING",
                            "clusterRole" to if (name.last() == '0') "LEADER" else "FOLLOWER",
                            "logPosition" to 200L,
                            "raceRows" to rows,
                            (if (group == "funds") "funds" else "matching") to if (group == "funds") funds else matching,
                        ),
                )
            runtimes[name] =
                tree(
                    identity +
                        mapOf<String, Any?>(
                            "containerId" to "container-$name",
                            "imageId" to "image-frozen",
                            "storageLevels" to listOf(2, 2, 2),
                        ),
                )
        }
        return Fixture(states, runtimes, originals, stage)
    }

    private fun balance(
        user: String,
        asset: String,
        available: Long,
        hold: Long = 0,
    ) = mapOf<String, Any?>(
        "userId" to user,
        "assetId" to asset,
        "available" to available,
        "hold" to hold,
    )

    private fun result(
        market: String,
        sequence: Long,
        race: Boolean,
    ): Map<String, Any?> {
        val label = if (market == markets[0]) "btc" else "sui"
        val buy = (if (race) "race-" else "buy-") + label
        val quantity =
            if (race) {
                if (label == "btc") {
                    6L
                } else {
                    5L
                }
            } else if (label == "btc") {
                2L
            } else {
                3L
            }
        val id = if (sequence == 2L) "sell-$label" else buy
        val event =
            when (sequence) {
                1L -> {
                    mapOf<String, Any?>(
                        "kind" to "RESTING",
                        "engineSequence" to sequence,
                        "orderId" to buy,
                        "quantity" to quantity,
                        "price" to 100L,
                    )
                }

                2L -> {
                    mapOf<String, Any?>(
                        "kind" to "TRADE",
                        "engineSequence" to sequence,
                        "makerOrderId" to buy,
                        "takerOrderId" to id,
                        "quantity" to 1L,
                        "price" to 100L,
                    )
                }

                else -> {
                    mapOf<String, Any?>("kind" to "CANCELLED", "engineSequence" to sequence, "orderId" to buy, "quantity" to quantity - 1)
                }
            }

        fun effect(
            order: String,
            origin: Long,
            before: Long,
            after: Long,
            disposition: String,
        ) = mapOf<String, Any?>(
            "orderId" to order,
            "originInputSequence" to origin,
            "beforeQuantity" to before,
            "afterQuantity" to after,
            "disposition" to disposition,
        )
        val effects =
            when (sequence) {
                1L -> listOf(effect(buy, 1, quantity, quantity, "RESTING"))
                2L -> listOf(effect(buy, 1, quantity, quantity - 1, "RESTING"), effect(id, 2, 1, 0, "TERMINAL"))
                else -> listOf(effect(buy, 1, quantity - 1, 0, "CANCELLED"))
            }
        return mapOf<String, Any?>(
            "marketId" to market,
            "resultSequence" to sequence,
            "resultId" to "$label-result-$sequence",
            "orderId" to id,
            "cancelId" to
                if (sequence ==
                    3L
                ) {
                    "cancel-$label"
                } else {
                    null
                },
            "inputSequence" to sequence,
            "outcome" to if (sequence == 3L) "CANCELLED" else "MATCHED",
            "engineBefore" to sequence - 1,
            "engineAfter" to sequence,
            "events" to listOf(event),
            "effects" to effects,
        )
    }

    private fun tree(value: Any?): JsonNode = mapper.valueToTree(value)
}

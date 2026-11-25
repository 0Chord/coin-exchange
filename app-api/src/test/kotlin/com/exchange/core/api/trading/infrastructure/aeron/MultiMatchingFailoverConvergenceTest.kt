package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertTrue

/** 정상 장애창을 허용하고, 같은 총액/성공 문자열로 가릴 수 있는 반례를 거절한다. */
class MultiMatchingFailoverConvergenceTest {
    private val mapper = jacksonObjectMapper()
    private val markets = listOf("BTC-USDT", "SUI-USDT")
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }

    @Test
    fun `두 장애창과 SUI 예약 정산 및 후속 거래의 독립 수기 관찰을 허용한다`() {
        for (stage in MultiMatchingFailoverStage.entries) valid(fixture(stage))
        val rejoined = fixture(MultiMatchingFailoverStage.FOLLOWUP)
        change(rejoined.states.getValue("btc0"), "/matching", "resultDetails", emptyList<Any>())
        valid(rejoined)
    }

    @Test
    fun `종료 target 실제 리더 SIGKILL과 생존8개를 대조한다`() {
        validLifecycle(lifecycle())
        validLifecycle(lifecycle(orderGate = true))
        for ((pointer, field, value) in listOf(
            Triple("", "target", "btc1"),
            Triple("/stopped", "running", true),
            Triple("/stopped", "oomKilled", true),
            Triple("/stopped", "exitCode", 0),
            Triple("/elected/funds1", "bootId", "restarted-funds"),
            Triple("/elected/sui2/inspection/State", "Running", false),
        )) {
            invalidLifecycle(lifecycle().also { change(it, pointer, field, value) })
        }
        // gate 세 파일을 모두 같은 가짜 원문으로 바꿔도 실제 전달 의무와 달라 실패한다.
        for (orderGate in listOf(false, true)) {
            val otherPayload = lifecycle(orderGate)
            for (phase in listOf("blocked", "offered", "reply")) {
                change(otherPayload, "/gates/0/$phase", "payloadSha256", "c".repeat(64))
            }
            invalidLifecycle(otherPayload)
            val otherOrder = lifecycle(orderGate)
            for (phase in listOf("blocked", "offered")) change(otherOrder, "/gates/0/$phase", "orderId", "other-btc-order")
            invalidLifecycle(otherOrder)
        }
    }

    @Test
    fun `임기0 초기 관찰은 허용하되 같은 낮은 임기와 이전 probe 및 boot 복제를 거절한다`() {
        for (term in listOf(0L, -1L)) {
            val node = lifecycle()
            for (name in listOf("btc1", "btc2")) {
                change(node, "/elected/$name/state", "leadershipTermId", term)
                change(node, "/elected/$name/state/probe", "leadershipTermId", term)
            }
            invalidLifecycle(node)
        }
        val oldProbe = lifecycle()
        change(oldProbe, "/elected/btc1/state/probe", "correlationId", 1_000_000_000_000L)
        invalidLifecycle(oldProbe)
        val oldBoot = lifecycle()
        change(oldBoot, "/rejoined/btc0", "bootId", "boot-btc0")
        change(oldBoot, "/rejoined/btc0/state", "bootId", "boot-btc0")
        change(oldBoot, "/rejoined/btc0/state/probe", "bootId", "boot-btc0")
        invalidLifecycle(oldBoot)
    }

    @Test
    fun `주문 전달 전과 결과 정산 전 경계를 이미 지난 관찰은 실패다`() {
        val applied = fixture(MultiMatchingFailoverStage.ORDER_HELD)
        for (name in listOf(
            "btc0",
            "btc1",
            "btc2",
        )) {
            change(applied.states.getValue(name), "/matching/markets/BTC-USDT", "appliedThrough", 1)
        }
        invalid(applied)
        val noTrade = fixture(MultiMatchingFailoverStage.RESULT_HELD)
        for (name in listOf(
            "btc0",
            "btc1",
            "btc2",
        )) {
            change(noTrade.states.getValue(name), "/matching", "resultDetails", listOf(result("btc", 1)))
        }
        invalid(noTrade)
        val settled = fixture(MultiMatchingFailoverStage.RESULT_HELD)
        fundsChange(settled, "/funds/resultProgress/BTC-USDT", "resultThrough", 2)
        invalid(settled)
        val noPayload = fixture(MultiMatchingFailoverStage.RESULT_HELD)
        for (name in listOf("btc0", "btc1", "btc2")) change(noPayload.states.getValue(name), "/matching", "resultBytes", 0)
        invalid(noPayload)
    }

    @Test
    fun `SUI는 실제 종료 후 gate 해제 전에 신규 입력 체결 자금 반영까지 있어야 한다`() {
        for ((field, time) in listOf("suiStarted" to 1L, "suiSettled" to 70L, "gateReleased" to 35L)) {
            invalidLifecycle(lifecycle().also { change(it, "/timeline", field, time) })
        }
        val noAdvance = fixture(MultiMatchingFailoverStage.ORDER_HELD_SUI_TRADED)
        fundsChange(noAdvance, "/funds/resultProgress/SUI-USDT", "resultThrough", 0)
        invalid(noAdvance)
        val wrongToken = fixture(MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED)
        for (name in names.filter { it.startsWith("funds") }) {
            val row =
                wrongToken.states.getValue(name).path("funds").path("receipts").toList().single {
                    it.path("receipt").path("orderId").asString() ==
                        "buy-sui"
                }
            change(row, "", "sequence", 2)
        }
        invalid(wrongToken)
    }

    @Test
    fun `같은 합계라도 틀린 예약 수수료 원 체결 상대 결과ID와 마켓 진행은 실패다`() {
        val swapped = fixture(MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED)
        for (name in names.filter { it.startsWith("funds") }) {
            val rows =
                swapped.states
                    .getValue(name)
                    .path("funds")
                    .path("balances")
            change(rows.path(0), "", "available", 496)
            change(rows.path(0), "", "hold", 403)
        }
        invalid(swapped)
        val fee = fixture(MultiMatchingFailoverStage.TRADED)
        fundsChange(fee, "/funds/fees", "USDT", "3")
        invalid(fee)
        val opponent = fixture(MultiMatchingFailoverStage.TRADED)
        fundsChange(opponent, "/funds/resultDetails/1/events/0", "makerOrderId", "different-order")
        invalid(opponent)
        val repeated = fixture(MultiMatchingFailoverStage.TRADED)
        fundsChange(repeated, "/funds/resultDetails/3", "resultId", "btc-result-2")
        invalid(repeated)
        val progress = fixture(MultiMatchingFailoverStage.FOLLOWUP)
        fundsChange(progress, "/funds/resultProgress/BTC-USDT", "resultThrough", 2)
        fundsChange(progress, "/funds/resultProgress/SUI-USDT", "resultThrough", 3)
        invalid(progress)
        val seller = fixture(MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED)
        for (name in names.filter { it.startsWith("funds") }) {
            change(seller.states.getValue(name), "/funds/balances/4", "available", 1)
            change(seller.states.getValue(name), "/funds/balances/4", "hold", 0)
        }
        invalid(seller)
    }

    @Test
    fun `빈 기록 재합류 미회수payload 잘못된prefix와 기존후속응답재사용은 실패다`() {
        val emptyVolume = lifecycle()
        change(emptyVolume, "/rejoined/btc0/inspection/Mounts/0", "Name", "new-empty-volume")
        invalidLifecycle(emptyVolume)
        val payload = fixture(MultiMatchingFailoverStage.FOLLOWUP)
        for (name in listOf("btc0", "btc1", "btc2")) change(payload.states.getValue(name), "/matching", "resultEntries", 1)
        invalid(payload)
        val prefix = fixture(MultiMatchingFailoverStage.TRADED)
        fundsChange(prefix, "/funds/dispatch/BTC-USDT", "matchingAppliedThrough", 1)
        invalid(prefix)
        val reused = fixture(MultiMatchingFailoverStage.FOLLOWUP)
        reused.originals["followup-btc"] = reused.originals.getValue("buy-btc")
        invalid(reused)
        val noNewToken = fixture(MultiMatchingFailoverStage.FOLLOWUP)
        fundsChange(noNewToken, "/funds/receipts/4", "sequence", 1)
        invalid(noNewToken)
    }

    @Test
    fun `원 작업 아닌 재개 다른 원문과 가짜 ACK를 거절한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/gates/0", "offeredService", "btc2"),
            Triple("/gates/0/offered", "payloadSha256", "c".repeat(64)),
            Triple("/gates/0/offered", "positiveOfferPosition", 0),
            Triple("/gates/0/reply", "through", 1),
            Triple("/gates/0/reply", "connectionId", 9),
            Triple("/gates/0/blocked/stream", "direction", "FUNDS_TO_MATCHING"),
        )) {
            invalidLifecycle(lifecycle().also { change(it, pointer, field, value) })
        }
    }

    private data class Fixture(
        val stage: MultiMatchingFailoverStage,
        val states: MutableMap<String, JsonNode>,
        val originals: MutableMap<String, JsonNode>,
    )

    private fun valid(f: Fixture) =
        assertTrue(
            MultiMatchingFailoverConvergence.violations(f.stage, f.states, f.originals).isEmpty(),
            MultiMatchingFailoverConvergence.violations(f.stage, f.states, f.originals).joinToString("\n"),
        )

    private fun invalid(f: Fixture) = assertTrue(MultiMatchingFailoverConvergence.violations(f.stage, f.states, f.originals).isNotEmpty())

    private fun validLifecycle(node: JsonNode) =
        assertTrue(
            MultiMatchingFailoverConvergence.lifecycleViolations(node).isEmpty(),
            MultiMatchingFailoverConvergence.lifecycleViolations(node).joinToString("\n"),
        )

    private fun invalidLifecycle(node: JsonNode) = assertTrue(MultiMatchingFailoverConvergence.lifecycleViolations(node).isNotEmpty())

    private fun fundsChange(
        f: Fixture,
        pointer: String,
        field: String,
        value: Any,
    ) {
        for (name in names.filter { it.startsWith("funds") }) change(f.states.getValue(name), pointer, field, value)
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

    private fun fixture(stage: MultiMatchingFailoverStage): Fixture {
        // BUY2=202、BUY3=303、한 체결=101 소비를 독립적으로 적은 checkpoint 표다.
        val row =
            when (stage) {
                MultiMatchingFailoverStage.ORDER_HELD -> listOf(1L, 0, 0, 0, 798, 202)
                MultiMatchingFailoverStage.BUY_RESTING -> listOf(1L, 1, 1, 0, 798, 202)
                MultiMatchingFailoverStage.RESULT_HELD -> listOf(2L, 2, 1, 0, 798, 202)
                MultiMatchingFailoverStage.ORDER_HELD_SUI_RESERVED -> listOf(1L, 0, 0, 1, 495, 505)
                MultiMatchingFailoverStage.RESULT_HELD_SUI_RESERVED -> listOf(2L, 2, 1, 1, 495, 505)
                MultiMatchingFailoverStage.ORDER_HELD_SUI_TRADED -> listOf(1L, 0, 0, 2, 495, 404)
                MultiMatchingFailoverStage.RESULT_HELD_SUI_TRADED -> listOf(2L, 2, 1, 2, 495, 404)
                MultiMatchingFailoverStage.BUY_RESTING_SUI_TRADED -> listOf(1L, 1, 1, 2, 495, 404)
                MultiMatchingFailoverStage.TRADED -> listOf(2L, 2, 2, 2, 495, 303)
                MultiMatchingFailoverStage.FOLLOWUP -> listOf(3L, 3, 3, 2, 394, 404)
            }
        val (issued, applied, settled, sui) = row
        val btcTrade = settled >= 2
        val suiTrade = sui >= 2
        val held = issued >= 2 && !btcTrade
        val keys =
            buildList {
                add("buy-btc")
                if (issued >=
                    2
                ) {
                    add("sell-btc")
                }
                if (sui > 0) add("buy-sui")
                if (sui > 1) add("sell-sui")
                if (issued == 3L) add("followup-btc")
            }
        val originals =
            keys
                .mapIndexed { i, key ->
                    key to
                        tree(
                            mapOf(
                                "code" to "ACCEPTED",
                                "receipt" to
                                    mapOf(
                                        "code" to "ACCEPTED",
                                        "orderId" to key,
                                        "admissionSequence" to i + 1,
                                        "marketAdmissionSequence" to
                                            if (key.startsWith("followup")) {
                                                3
                                            } else if (key.startsWith("sell")) {
                                                2
                                            } else {
                                                1
                                            },
                                    ),
                            ),
                        )
                }.toMap()
                .toMutableMap()

        fun balance(
            user: String,
            asset: String,
            available: Long,
            hold: Long = 0,
        ) = mapOf(
            "userId" to user,
            "assetId" to asset,
            "available" to available,
            "hold" to hold,
        )
        val activeBtc = if (issued == 3L || held) 2L else 1L
        val activeSui = if (sui > 0) 1L else 0L
        val funds =
            mapOf(
                "balances" to
                    listOf(
                        balance("buyer", "USDT", row[4], row[5]),
                        balance("buyer", "BTC", if (btcTrade) 1 else 0),
                        balance("buyer", "SUI", if (suiTrade) 1 else 0),
                        balance(
                            "seller",
                            "USDT",
                            (if (btcTrade) 99L else 0L) + (if (suiTrade) 99L else 0L),
                        ),
                        balance(
                            "seller",
                            "BTC",
                            if (btcTrade ||
                                held
                            ) {
                                0
                            } else {
                                1
                            },
                            if (held) 1 else 0,
                        ),
                        balance("seller", "SUI", if (suiTrade) 0 else 1),
                    ),
                "fees" to mapOf("USDT" to ((if (btcTrade) 2L else 0L) + (if (suiTrade) 2L else 0L)).toString()),
                "lastAdmissionSequence" to keys.size,
                "lastCancellationSequence" to 0,
                "pendingOrders" to issued - settled,
                "activeOrders" to activeBtc + activeSui,
                "liveOrders" to activeBtc + activeSui,
                "activeBytes" to 100 * (activeBtc + activeSui),
                "dispatchEntries" to issued - applied,
                "dispatchBytes" to 100 * (issued - applied),
                "dispatch" to
                    markets
                        .mapIndexed { i, market ->
                            market to
                                mapOf<String, Any?>(
                                    "issued" to
                                        if (i ==
                                            0
                                        ) {
                                            issued
                                        } else {
                                            sui
                                        },
                                    "matchingAppliedThrough" to
                                        if (i ==
                                            0
                                        ) {
                                            applied
                                        } else {
                                            sui
                                        },
                                    "dispatchEntries" to
                                        if (i ==
                                            0
                                        ) {
                                            issued - applied
                                        } else {
                                            0
                                        },
                                    "dispatchBytes" to
                                        if (i ==
                                            0
                                        ) {
                                            100 * (issued - applied)
                                        } else {
                                            0
                                        },
                                    "activeOrders" to
                                        if (i ==
                                            0
                                        ) {
                                            activeBtc
                                        } else {
                                            activeSui
                                        },
                                    "activeBytes" to 100 *
                                        if (i ==
                                            0
                                        ) {
                                            activeBtc
                                        } else {
                                            activeSui
                                        },
                                    "outstanding" to
                                        if (i == 0 &&
                                            applied == 0L
                                        ) {
                                            listOf(
                                                mapOf(
                                                    "dispatchSequence" to 1,
                                                    "orderId" to "buy-btc",
                                                    "token" to token("buy-btc"),
                                                    "payloadSha256" to "a".repeat(64),
                                                ),
                                            )
                                        } else {
                                            emptyList()
                                        },
                                )
                        }.toMap(),
                "resultProgress" to
                    markets
                        .mapIndexed { i, market ->
                            market to
                                mapOf(
                                    "resultThrough" to
                                        if (i ==
                                            0
                                        ) {
                                            settled
                                        } else {
                                            sui
                                        },
                                    "settledEngineSequence" to if (i == 0) settled else sui,
                                )
                        }.toMap(),
                "receipts" to
                    keys.map { key ->
                        token(key) +
                            mapOf(
                                "kind" to "ORDER",
                                "marketId" to if (key.endsWith("btc")) markets[0] else markets[1],
                                "receipt" to originals.getValue(key).path("receipt"),
                            )
                    },
                "resultDetails" to
                    (1L..settled).map { result("btc", it) + ("appliedAt" to "2026-11-16T00:00:00Z") } +
                    (1L..sui).map { result("sui", it) + ("appliedAt" to "2026-11-16T00:00:00Z") },
            )
        val states =
            names
                .associateWith { name ->
                    val label = name.dropLast(1)
                    val count = if (label == "btc") applied else sui
                    val ack = if (label == "btc") settled else sui
                    val market = if (label == "btc") markets[0] else markets[1]
                    val quantity = if (label == "btc") 2L else 3L
                    val orderKeys =
                        buildList {
                            if (count > 0) add("buy-$label")
                            if (count == 3L) add("followup-btc")
                        }
                    tree(
                        mapOf(
                            if (label == "funds") {
                                "funds" to funds
                            } else {
                                "matching" to
                                    mapOf(
                                        "markets" to
                                            mapOf(
                                                market to
                                                    mapOf<String, Any?>(
                                                        "engineSequence" to count,
                                                        "appliedThrough" to count,
                                                        "resultIssued" to count,
                                                        "fundsSettledThrough" to ack,
                                                        "orders" to
                                                            orderKeys.map { key ->
                                                                mapOf(
                                                                    "orderId" to key,
                                                                    "userId" to "buyer",
                                                                    "side" to "BUY",
                                                                    "price" to 100,
                                                                    "originalQuantity" to if (key.startsWith("followup")) 1L else quantity,
                                                                    "remainingQuantity" to
                                                                        if (key.startsWith("followup")) {
                                                                            1L
                                                                        } else if (count >=
                                                                            2
                                                                        ) {
                                                                            quantity - 1
                                                                        } else {
                                                                            quantity
                                                                        },
                                                                )
                                                            },
                                                        "outstanding" to
                                                            if (count >
                                                                ack
                                                            ) {
                                                                listOf(
                                                                    result(label, 2).filterKeys {
                                                                        it in
                                                                            listOf(
                                                                                "resultSequence",
                                                                                "resultId",
                                                                                "orderId",
                                                                                "inputSequence",
                                                                                "payloadSha256",
                                                                            )
                                                                    },
                                                                )
                                                            } else {
                                                                emptyList()
                                                            },
                                                    ),
                                            ),
                                        "resultEntries" to count - ack,
                                        "resultBytes" to 100 * (count - ack),
                                        "activeOrders" to orderKeys.size,
                                        "activeBytes" to 100 * orderKeys.size,
                                        "resultDetails" to (1L..count).map { result(label, it) },
                                    )
                            },
                        ),
                    )
                }.toMutableMap()
        return Fixture(stage, states, originals)
    }

    private fun token(key: String): Map<String, Any> =
        mapOf(
            "userId" to if (key.startsWith("sell")) "seller" else "buyer",
            "channel" to if (key.endsWith("btc")) 0 else 1,
            "epoch" to 1,
            "sequence" to if (key.startsWith("followup")) 2 else 1,
        )

    private fun result(
        label: String,
        sequence: Long,
    ): Map<String, Any?> {
        val quantity = if (label == "btc") 2L else 3L
        val order =
            if (sequence == 3L) {
                "followup-btc"
            } else if (sequence == 2L) {
                "sell-$label"
            } else {
                "buy-$label"
            }
        val event =
            mapOf("engineSequence" to sequence, "quantity" to if (sequence == 1L) quantity else 1L, "price" to 100L) +
                if (sequence ==
                    2L
                ) {
                    mapOf(
                        "kind" to "TRADE",
                        "makerOrderId" to "buy-$label",
                        "takerOrderId" to order,
                        "tradeId" to "$label-result-$sequence:$sequence",
                    )
                } else {
                    mapOf("kind" to "RESTING", "orderId" to order)
                }

        fun effect(
            id: String,
            origin: Long,
            before: Long,
            after: Long,
            disposition: String,
        ) = mapOf(
            "orderId" to id,
            "originInputSequence" to origin,
            "beforeQuantity" to before,
            "afterQuantity" to after,
            "disposition" to disposition,
        )
        val effects =
            if (sequence ==
                2L
            ) {
                listOf(effect("buy-$label", 1, quantity, quantity - 1, "RESTING"), effect(order, 2, 1, 0, "TERMINAL"))
            } else {
                listOf(
                    effect(
                        order,
                        sequence,
                        if (sequence ==
                            1L
                        ) {
                            quantity
                        } else {
                            1
                        },
                        if (sequence == 1L) quantity else 1,
                        "RESTING",
                    ),
                )
            }
        return mapOf(
            "marketId" to
                if (label ==
                    "btc"
                ) {
                    markets[0]
                } else {
                    markets[1]
                },
            "resultSequence" to sequence,
            "resultId" to "$label-result-$sequence",
            "orderId" to order,
            "cancelId" to null,
            "inputSequence" to sequence,
            "outcome" to "MATCHED",
            "engineBefore" to sequence - 1,
            "engineAfter" to sequence,
            "events" to listOf(event),
            "effects" to effects,
            "payloadSha256" to "b".repeat(64),
        )
    }

    private fun lifecycle(orderGate: Boolean = false): JsonNode {
        fun runtime(
            name: String,
            phase: Int,
        ): Map<String, Any?> {
            val member = name.last().digitToInt()
            val group = name.dropLast(1)
            val leader = if (group == "btc" && phase > 0) 1 else 0
            val boot = if (name == "btc0" && phase == 2) "new-boot-btc0" else "boot-$name"
            val term = if (group == "btc" && phase > 0) 1 else 0
            val identity =
                mapOf(
                    "runId" to "run",
                    "bootId" to boot,
                    "role" to
                        if (group ==
                            "funds"
                        ) {
                            "FUNDS"
                        } else {
                            "MATCHING"
                        },
                    "memberId" to member,
                    "clusterId" to
                        if (group ==
                            "funds"
                        ) {
                            198
                        } else if (group ==
                            "btc"
                        ) {
                            199
                        } else {
                            200
                        },
                    "lineageHigh" to
                        if (group ==
                            "funds"
                        ) {
                            10
                        } else if (group ==
                            "btc"
                        ) {
                            20
                        } else {
                            30
                        },
                    "lineageLow" to 1,
                    "markets" to
                        if (group ==
                            "funds"
                        ) {
                            markets
                        } else {
                            listOf(
                                if (group ==
                                    "btc"
                                ) {
                                    markets[0]
                                } else {
                                    markets[1]
                                },
                            )
                        },
                    "manifestSha256" to "a".repeat(64),
                    "topologySha256" to "b".repeat(64),
                    "resultTopologySha256" to "c".repeat(64),
                    "pid" to 1,
                )
            val state =
                identity +
                    mapOf(
                        "clusterRole" to
                            if (member ==
                                leader
                            ) {
                                "LEADER"
                            } else {
                                "FOLLOWER"
                            },
                        "leadershipTermId" to term,
                        "leaderMemberId" to leader,
                        "logPosition" to 100,
                        "probe" to
                            mapOf(
                                "correlationId" to 1_000_000_000_000L + phase,
                                "bootId" to boot,
                                "leadershipTermId" to term,
                                "leaderMemberId" to leader,
                                "logPosition" to 100,
                            ),
                    ) +
                    if (group == "funds") {
                        mapOf(
                            "funds" to
                                mapOf(
                                    "dispatch" to
                                        mapOf(
                                            markets[0] to
                                                mapOf(
                                                    "outstanding" to
                                                        if (orderGate) {
                                                            listOf(
                                                                mapOf(
                                                                    "dispatchSequence" to 1,
                                                                    "orderId" to "buy-btc",
                                                                    "payloadSha256" to "a".repeat(64),
                                                                ),
                                                            )
                                                        } else {
                                                            emptyList()
                                                        },
                                                ),
                                        ),
                                ),
                        )
                    } else {
                        mapOf(
                            "matching" to
                                mapOf(
                                    "markets" to
                                        mapOf(
                                            markets[0] to
                                                mapOf(
                                                    "outstanding" to
                                                        if (!orderGate && group == "btc") {
                                                            listOf(
                                                                mapOf(
                                                                    "resultSequence" to 2,
                                                                    "orderId" to "sell-btc",
                                                                    "resultId" to "btc-result-2",
                                                                    "payloadSha256" to "a".repeat(64),
                                                                ),
                                                            )
                                                        } else {
                                                            emptyList()
                                                        },
                                                ),
                                        ),
                                ),
                        )
                    }
            return identity +
                mapOf(
                    "containerId" to
                        if (name == "btc0" &&
                            phase == 2
                        ) {
                            "new-container-btc0"
                        } else {
                            "container-$name"
                        },
                    "imageId" to "frozen-image",
                    "runRoot" to "/records/run",
                    "archiveDirectory" to "/records/run/archive",
                    "clusterDirectory" to "/records/run/cluster",
                    "recoverExisting" to (name == "btc0" && phase == 2),
                    "inspection" to
                        mapOf(
                            "State" to mapOf("Running" to true, "OOMKilled" to false),
                            "RestartCount" to 0,
                            "Mounts" to listOf(mapOf("Destination" to "/records", "Name" to "volume-$name")),
                        ),
                    "state" to state,
                )
        }
        val installation =
            mapOf(
                "installationId" to "one",
                "gate" to if (orderGate) "btc-dispatch" else "btc-result",
                "sequence" to if (orderGate) 1 else 2,
            )
        val stream =
            mapOf(
                "direction" to if (orderGate) "FUNDS_TO_MATCHING" else "MATCHING_TO_FUNDS",
                "fundsLineageHigh" to 10,
                "fundsLineageLow" to 1,
                "matchingLineageHigh" to 20,
                "matchingLineageLow" to 1,
                "ownershipEpoch" to 1,
                "configurationVersion" to 1,
            )
        val blockedService = if (orderGate) "funds0" else "btc0"
        val offeredService = if (orderGate) "funds0" else "btc1"
        val payload =
            installation +
                mapOf(
                    "marketId" to markets[0],
                    "orderId" to if (orderGate) "buy-btc" else "sell-btc",
                    "resultId" to if (orderGate) null else "btc-result-2",
                    "payloadSha256" to "a".repeat(64),
                    "wireSha256" to "b".repeat(64),
                    "wireBytes" to 100,
                    "stream" to stream,
                    "connectionId" to "connection-1",
                    "correlationId" to 20,
                    "described" to mapOf("code" to "DESCRIBED", "appliedLogPosition" to 100),
                )
        val blocked = payload + mapOf("enabled" to true, "bootId" to "boot-$blockedService")
        val offered = payload + mapOf("enabled" to false, "bootId" to "boot-$offeredService", "positiveOfferPosition" to 200)
        val reply =
            mapOf(
                "bootId" to "boot-$offeredService",
                "connectionId" to "connection-1",
                "correlationId" to 20,
                "code" to "APPLIED",
                "through" to if (orderGate) 1 else 2,
                "appliedLogPosition" to 200,
                "payloadSha256" to "a".repeat(64),
            )
        val gate =
            installation +
                mapOf(
                    "installedAt" to 0,
                    "blockedAt" to 1,
                    "releaseAt" to 60,
                    "blockedService" to blockedService,
                    "offeredService" to offeredService,
                    "blocked" to blocked,
                    "offered" to offered,
                    "reply" to reply,
                    "heldWireSha256" to "b".repeat(64),
                    "heldWireBytes" to 100,
                )
        val phases =
            listOf(
                "killSent",
                "killConfirmed",
                "electionObserved",
                "suiStarted",
                "suiSettled",
                "gateReleased",
                "btcSettled",
                "rejoined",
                "followupCompleted",
            )
        return tree(
            mapOf(
                "target" to "btc0",
                "initial" to names.associateWith { runtime(it, 0) },
                "stopped" to
                    mapOf(
                        "service" to "btc0",
                        "containerId" to "container-btc0",
                        "running" to false,
                        "exitCode" to 137,
                        "oomKilled" to false,
                    ),
                "elected" to names.filter { it != "btc0" }.associateWith { runtime(it, 1) },
                "rejoined" to names.associateWith { runtime(it, 2) },
                "timeline" to phases.mapIndexed { i, phase -> phase to (i.toLong() + 1) * 10 }.toMap(),
                "eventOrder" to phases.mapIndexed { i, phase -> phase to i + 1 }.toMap(),
                "gates" to listOf(gate),
            ),
        )
    }
}

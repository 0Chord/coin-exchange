package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertTrue

/** 실제 장애 실행을 대체하지 않고, 같은 총액·성공 문자열로 위반을 숨길 수 없는지 확인한다. */
class FundsFailoverConvergenceTest {
    private val mapper = jacksonObjectMapper()
    private val names = (0..2).flatMap { listOf("funds$it", "btc$it", "sui$it") }

    @Test
    fun `초기 임기0부터 생존 과반과 재합류 사본의 수기 정답을 허용한다`() {
        for (stage in FundsFailoverStage.entries) {
            valid(stage, states(stage), originals(stage))
            if (stage != FundsFailoverStage.INITIALIZED) valid(stage, states(stage).also { it.remove("funds0") }, originals(stage))
        }
        validLifecycle(lifecycle(hidden = false))
        validLifecycle(lifecycle(hidden = true))
    }

    @Test
    fun `같은 총액이어도 예약 원문 token 상태와 순번이 다르면 거절한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds/balances/0", "available", 401),
            Triple("/funds/balances/0", "hold", 599),
            Triple("/funds/liveOrderDetails/0", "orderId", "other-order"),
            Triple("/funds/liveOrderDetails/0", "phase", "RESTING"),
            Triple("/funds/liveOrderDetails/0", "remainingAmount", 500),
            Triple("/funds/liveOrderDetails/0", "remainingQuantity", 5),
            Triple("/funds/receipts/0", "epoch", 2),
            Triple("/funds/receipts/0/intent", "quantity", 7),
            Triple("/funds/dispatch/BTC-USDT/outstanding/0", "quantity", 7),
            Triple("/funds/dispatch/BTC-USDT/outstanding/0/token", "sequence", 2),
            Triple("/funds/dispatch/BTC-USDT", "issued", 2),
            Triple("/funds", "lastCancellationSequence", 1),
        )) {
            invalid(FundsFailoverStage.ORDER_HELD, pointer, field, value)
        }
    }

    @Test
    fun `원 거절500과400을 최신300으로 재계산하거나 거절에 주문을 발급하면 실패다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds/receipts/1/receipt/insufficientBalance", "available", 300),
            Triple("/funds/receipts/1/receipt/insufficientBalance", "shortfall", 200),
            Triple("/funds/receipts/1/receipt", "orderId", "rejected-order"),
            Triple("/funds/receipts/1/receipt", "reason", "CAPACITY_EXCEEDED"),
            Triple("/funds/receipts/1/intent", "quantity", 4),
        )) {
            invalid(FundsFailoverStage.FOLLOWUP, pointer, field, value)
        }
        val originals = originals(FundsFailoverStage.FOLLOWUP)
        change(originals.getValue("S1"), "/receipt/insufficientBalance", "available", 300)
        val rows = states(FundsFailoverStage.FOLLOWUP)
        for (name in names.filter {
            it.startsWith("funds")
        }) {
            change(rows.getValue(name), "/funds/receipts/1/receipt/insufficientBalance", "available", 300)
        }
        assertTrue(FundsFailoverConvergence.violations(FundsFailoverStage.FOLLOWUP, rows, originals).isNotEmpty())
    }

    @Test
    fun `매칭적용만으로 완료하지 않고 실제 대기 결과 자금반영과 ACK 회수를 요구한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/funds/resultProgress/BTC-USDT", "resultThrough", 0),
            Triple("/funds/dispatch/BTC-USDT", "matchingAppliedThrough", 0),
            Triple("/funds/resultDetails/0/events/0", "kind", "TRADE"),
            Triple("/funds/resultDetails/0/effects/0", "afterQuantity", 5),
            Triple("/funds", "resultDetails", emptyList<Any>()),
            Triple("/funds/fees", "USDT", "1"),
        )) {
            invalid(FundsFailoverStage.BTC_RESTING, pointer, field, value)
        }
        val rows = states(FundsFailoverStage.BTC_RESTING)
        for (name in names.filter { it.startsWith("btc") }) change(rows.getValue(name), "/matching", "resultBytes", 100)
        assertTrue(
            FundsFailoverConvergence
                .violations(
                    FundsFailoverStage.BTC_RESTING,
                    rows,
                    originals(FundsFailoverStage.BTC_RESTING),
                ).isNotEmpty(),
        )
    }

    @Test
    fun `새 SUI token 대신 기존 BTC 결과나 주문 식별자를 재사용하면 실패다`() {
        val originals = originals(FundsFailoverStage.FOLLOWUP)
        originals["S2"] = originals.getValue("B1")
        assertTrue(
            FundsFailoverConvergence.violations(FundsFailoverStage.FOLLOWUP, states(FundsFailoverStage.FOLLOWUP), originals).isNotEmpty(),
        )
        invalid(FundsFailoverStage.FOLLOWUP, "/funds/receipts/2", "sequence", 1)
        invalid(FundsFailoverStage.FOLLOWUP, "/funds/resultDetails/1", "resultId", "result-B1")
    }

    @Test
    fun `실제 리더와 다른 종료 낮은 임기 과거 probe 및 빈 기록 재합류를 거절한다`() {
        for ((pointer, field, value) in listOf(
            Triple("", "target", "funds2"),
            Triple("/stopped", "oomKilled", true),
            Triple("/elected/funds1/state", "leadershipTermId", 0),
            Triple("/elected/funds1/state/probe", "correlationId", 1_000_000_000_000L),
            Triple("/rejoined/funds0", "bootId", "boot-funds0"),
            Triple("/rejoined/funds0/inspection/Mounts/0", "Name", "new-empty-records"),
            Triple("/rejoined/sui1", "containerId", "unrelated-restart"),
        )) {
            invalidLifecycle(pointer, field, value)
        }
    }

    @Test
    fun `실제 응답과 확정 callback 및 상위 미전달과 caller 미확인을 요구한다`() {
        for ((pointer, field, value) in listOf(
            Triple("/admission", "positiveOfferPosition", 0),
            Triple("/admission", "forwarded", true),
            Triple("/admission", "appliedLogPosition", 0),
            Triple("/admission/commit", "service", "funds2"),
            Triple("/admission/commit", "leadershipTermId", 7),
            Triple("/admission/commit", "receipt", emptyMap<String, Any>()),
            Triple("/admission/commit", "requestSha256", "c".repeat(64)),
            Triple("/admission/commit", "balance", emptyMap<String, Any>()),
            Triple("/admission/commit/balance", "assetId", "BTC"),
            Triple("/admission/commit/balance", "available", 1_000),
            Triple("/admission/commit/balance", "hold", 0),
            Triple("/admissionCompleted", "unconfirmed", false),
            Triple("/admissionCompleted", "transportAccepted", false),
            Triple("/admissionCompleted", "code", "ACCEPTED"),
        )) {
            invalidLifecycle(pointer, field, value)
        }
    }

    @Test
    fun `구 리더 보류기록과 가짜 원문 및 ACK만으로 자동재개를 주장할 수 없다`() {
        for ((pointer, field, value) in listOf(
            Triple("/gates/0", "blockedService", "funds0"),
            Triple("/gates/0", "offeredService", "funds2"),
            Triple("/gates/0/blocked", "bootId", "boot-funds0"),
            Triple("/gates/0/offered", "positiveOfferPosition", 0),
            Triple("/gates/0/reply", "correlationId", 8),
            Triple("/gates/0/reply", "through", 0),
            Triple("/gates/0", "blockedAt", 10),
            Triple("/timeline", "gateReleased", 70),
        )) {
            invalidLifecycle(pointer, field, value)
        }
        val evidence = lifecycle(true)
        for (phase in listOf("blocked", "offered", "reply")) change(evidence, "/gates/0/$phase", "payloadSha256", "c".repeat(64))
        assertTrue(FundsFailoverConvergence.lifecycleViolations(evidence).isNotEmpty())
    }

    private fun valid(
        stage: FundsFailoverStage,
        rows: Map<String, JsonNode>,
        originals: Map<String, JsonNode>,
    ) {
        val failures = FundsFailoverConvergence.violations(stage, rows, originals)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun invalid(
        stage: FundsFailoverStage,
        pointer: String,
        field: String,
        value: Any,
    ) {
        val rows = states(stage)
        for (name in names.filter { it.startsWith("funds") }) change(rows.getValue(name), pointer, field, value)
        assertTrue(FundsFailoverConvergence.violations(stage, rows, originals(stage)).isNotEmpty(), "$pointer/$field 위반을 놓쳤습니다")
    }

    private fun validLifecycle(node: JsonNode) {
        val failures = FundsFailoverConvergence.lifecycleViolations(node)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun invalidLifecycle(
        pointer: String,
        field: String,
        value: Any,
    ) {
        val evidence = lifecycle(true)
        change(evidence, pointer, field, value)
        assertTrue(FundsFailoverConvergence.lifecycleViolations(evidence).isNotEmpty(), "$pointer/$field 위반을 놓쳤습니다")
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

    private fun keys(stage: FundsFailoverStage): List<String> =
        when (stage) {
            FundsFailoverStage.INITIALIZED -> emptyList()
            FundsFailoverStage.ORDER_HELD -> listOf("B1")
            FundsFailoverStage.FOLLOWUP -> listOf("B1", "S1", "S2")
            else -> listOf("B1", "S1")
        }

    private fun original(key: String) =
        mapOf(
            "code" to if (key == "S1") "REJECTED" else "ACCEPTED",
            "receipt" to
                mapOf(
                    "code" to if (key == "S1") "REJECTED" else "ACCEPTED",
                    "orderId" to key.takeUnless { it == "S1" },
                    "admissionSequence" to
                        if (key == "B1") {
                            1
                        } else if (key == "S2") {
                            2
                        } else {
                            null
                        },
                    "marketAdmissionSequence" to if (key == "S1") null else 1,
                    "reason" to if (key == "S1") "INSUFFICIENT_BALANCE" else null,
                    "insufficientBalance" to
                        if (key == "S1") mapOf("assetId" to "USDT", "required" to 500, "available" to 400, "shortfall" to 100) else null,
                ),
        )

    private fun originals(stage: FundsFailoverStage) = keys(stage).associateWith { tree(original(it)) }.toMutableMap()

    private fun token(key: String) =
        mapOf(
            "userId" to "buyer",
            "channel" to
                if (key ==
                    "B1"
                ) {
                    0
                } else {
                    1
                },
            "epoch" to 1,
            "sequence" to if (key == "S2") 2 else 1,
        )

    private fun pending() =
        mapOf(
            "dispatchSequence" to 1,
            "orderId" to "B1",
            "marketId" to "BTC-USDT",
            "userId" to "buyer",
            "side" to "BUY",
            "price" to 100,
            "quantity" to 6,
            "payloadSha256" to "a".repeat(64),
            "token" to token("B1"),
        )

    private fun result(key: String) =
        mapOf(
            "marketId" to if (key == "B1") "BTC-USDT" else "SUI-USDT",
            "resultSequence" to 1,
            "resultId" to "result-$key",
            "orderId" to key,
            "cancelId" to null,
            "inputSequence" to 1,
            "outcome" to "MATCHED",
            "engineBefore" to 0,
            "engineAfter" to 1,
            "payloadSha256" to "b".repeat(64),
            "appliedAt" to "2026-11-16T00:00:00Z",
            "events" to
                listOf(
                    mapOf(
                        "kind" to "RESTING",
                        "orderId" to key,
                        "engineSequence" to 1,
                        "quantity" to
                            if (key ==
                                "B1"
                            ) {
                                6
                            } else {
                                1
                            },
                        "price" to 100,
                    ),
                ),
            "effects" to
                listOf(
                    mapOf(
                        "orderId" to key,
                        "originInputSequence" to 1,
                        "beforeQuantity" to
                            if (key ==
                                "B1"
                            ) {
                                6
                            } else {
                                1
                            },
                        "afterQuantity" to if (key == "B1") 6 else 1,
                        "disposition" to "RESTING",
                    ),
                ),
        )

    private fun states(stage: FundsFailoverStage): MutableMap<String, JsonNode> {
        val keys = keys(stage)
        val accepted = keys.filter { it != "S1" }
        val resting = stage in setOf(FundsFailoverStage.BTC_RESTING, FundsFailoverStage.FOLLOWUP)
        val held = "B1" in keys && !resting
        val following = "S2" in keys
        val funds =
            mapOf(
                "balances" to
                    listOf(
                        mapOf(
                            "userId" to "buyer",
                            "assetId" to "USDT",
                            "available" to
                                if (keys.isEmpty()) {
                                    1000
                                } else if (following) {
                                    300
                                } else {
                                    400
                                },
                            "hold" to
                                if (keys.isEmpty()) {
                                    0
                                } else if (following) {
                                    700
                                } else {
                                    600
                                },
                        ),
                    ),
                "fees" to emptyMap<String, String>(),
                "lastAdmissionSequence" to accepted.size,
                "lastCancellationSequence" to 0,
                "pendingOrders" to if (held) 1 else 0,
                "liveOrders" to accepted.size,
                "activeOrders" to accepted.size,
                "activeBytes" to accepted.size * 100,
                "dispatchEntries" to if (held) 1 else 0,
                "dispatchBytes" to if (held) 100 else 0,
                "receipts" to
                    keys.map { key ->
                        token(key) +
                            mapOf(
                                "kind" to "ORDER",
                                "marketId" to
                                    if (key ==
                                        "B1"
                                    ) {
                                        "BTC-USDT"
                                    } else {
                                        "SUI-USDT"
                                    },
                                "receipt" to original(key).getValue("receipt"),
                                "intent" to
                                    mapOf(
                                        "expectedConfigurationVersion" to 1,
                                        "side" to "BUY",
                                        "orderType" to "LIMIT",
                                        "timeInForce" to "GTC",
                                        "price" to 100,
                                        "quantity" to
                                            if (key == "B1") {
                                                6
                                            } else if (key == "S1") {
                                                5
                                            } else {
                                                1
                                            },
                                    ),
                            )
                    },
                "liveOrderDetails" to
                    accepted.map { key ->
                        mapOf(
                            "orderId" to key,
                            "userId" to "buyer",
                            "marketId" to
                                if (key ==
                                    "B1"
                                ) {
                                    "BTC-USDT"
                                } else {
                                    "SUI-USDT"
                                },
                            "admissionSequence" to
                                if (key ==
                                    "B1"
                                ) {
                                    1
                                } else {
                                    2
                                },
                            "marketAdmissionSequence" to 1,
                            "phase" to if (resting) "RESTING" else "PENDING",
                            "initialQuantity" to
                                if (key ==
                                    "B1"
                                ) {
                                    6
                                } else {
                                    1
                                },
                            "remainingQuantity" to
                                if (key ==
                                    "B1"
                                ) {
                                    6
                                } else {
                                    1
                                },
                            "reservedAmount" to
                                if (key ==
                                    "B1"
                                ) {
                                    600
                                } else {
                                    100
                                },
                            "remainingAmount" to
                                if (key ==
                                    "B1"
                                ) {
                                    600
                                } else {
                                    100
                                },
                            "remainingFeeReserveAmount" to 0,
                        )
                    },
                "dispatch" to
                    listOf("BTC-USDT", "SUI-USDT")
                        .mapIndexed { index, market ->
                            val issued = if ((index == 0 && "B1" in keys) || (index == 1 && following)) 1 else 0
                            val applied = if ((index == 0 && resting) || (index == 1 && following)) 1 else 0
                            market to
                                mapOf(
                                    "issued" to issued,
                                    "matchingAppliedThrough" to applied,
                                    "dispatchEntries" to issued - applied,
                                    "dispatchBytes" to (issued - applied) * 100,
                                    "activeOrders" to issued,
                                    "activeBytes" to issued * 100,
                                    "outstanding" to if (issued > applied) listOf(pending()) else emptyList(),
                                )
                        }.toMap(),
                "resultProgress" to
                    mapOf(
                        "BTC-USDT" to mapOf("resultThrough" to if (resting) 1 else 0, "settledEngineSequence" to if (resting) 1 else 0),
                        "SUI-USDT" to mapOf("resultThrough" to if (following) 1 else 0, "settledEngineSequence" to if (following) 1 else 0),
                    ),
                "resultDetails" to accepted.filter { resting }.map(::result),
            )
        return names
            .associateWith { name ->
                val values = linkedMapOf<String, Any?>("configurationVersion" to 1)
                if (name.startsWith("funds")) {
                    values["funds"] = funds
                } else {
                    val key = if (name.startsWith("btc")) "B1" else "S2"
                    val applied = if ((name.startsWith("btc") && resting) || (name.startsWith("sui") && following)) 1 else 0
                    val market = if (name.startsWith("btc")) "BTC-USDT" else "SUI-USDT"
                    val orders: List<Map<String, Any?>> =
                        if (applied == 1) {
                            listOf(
                                mapOf(
                                    "orderId" to key,
                                    "userId" to "buyer",
                                    "side" to "BUY",
                                    "price" to 100,
                                    "originalQuantity" to (if (key == "B1") 6 else 1),
                                    "remainingQuantity" to (if (key == "B1") 6 else 1),
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    val marketState: Map<String, Any?> =
                        mapOf(
                            "engineSequence" to applied,
                            "appliedThrough" to applied,
                            "resultIssued" to applied,
                            "fundsSettledThrough" to applied,
                            "outstanding" to emptyList<Any>(),
                            "orders" to orders,
                        )
                    val matchingState: Map<String, Any?> =
                        mapOf(
                            "markets" to mapOf(market to marketState),
                            "resultEntries" to 0,
                            "resultBytes" to 0,
                            "activeOrders" to applied,
                            "activeBytes" to applied * 100,
                            "resultDetails" to (if (applied == 1) listOf(result(key)) else emptyList()),
                        )
                    values["matching"] = matchingState
                }
                tree(values)
            }.toMutableMap()
    }

    private fun lifecycle(hidden: Boolean): JsonNode {
        fun runtimes(
            phase: Int,
            stage: FundsFailoverStage,
        ): Map<String, JsonNode> =
            states(stage).filterKeys { phase != 1 || it != "funds0" }.mapValues { (name, financial) ->
                val boot = if (phase == 2 && name == "funds0") "rejoined-funds0" else "boot-$name"
                val member = name.last().digitToInt()
                val term = if (name.startsWith("funds") && phase > 0) 1 else 0
                val leader = if (name.startsWith("funds") && phase > 0) 1 else 0
                val role = if (name.startsWith("funds")) "FUNDS" else "MATCHING"
                val identity =
                    mapOf(
                        "runId" to "run",
                        "service" to name,
                        "bootId" to boot,
                        "role" to role,
                        "memberId" to member,
                        "clusterId" to name.dropLast(1),
                        "lineageHigh" to 11,
                        "lineageLow" to 22,
                        "markets" to
                            if (name.startsWith(
                                    "funds",
                                )
                            ) {
                                listOf("BTC-USDT", "SUI-USDT")
                            } else {
                                listOf(if (name.startsWith("btc")) "BTC-USDT" else "SUI-USDT")
                            },
                        "manifestSha256" to "manifest",
                        "topologySha256" to "topology",
                        "resultTopologySha256" to "result-topology",
                        "pid" to 1,
                    )
                val state =
                    identity +
                        mapOf(
                            "leadershipTermId" to term,
                            "leaderMemberId" to leader,
                            "clusterRole" to
                                if (member ==
                                    leader
                                ) {
                                    "LEADER"
                                } else {
                                    "FOLLOWER"
                                },
                            "logPosition" to 500,
                            "probe" to
                                mapOf(
                                    "correlationId" to 1_000_000_000_000L + phase,
                                    "bootId" to boot,
                                    "leadershipTermId" to term,
                                    "leaderMemberId" to leader,
                                    "logPosition" to 400,
                                ),
                        )
                tree(
                    identity +
                        mapOf(
                            "imageId" to "image",
                            "runRoot" to "/records/$name",
                            "archiveDirectory" to "/records/$name/archive",
                            "clusterDirectory" to "/records/$name/cluster",
                            "containerId" to if (boot.startsWith("rejoined")) "new-$name" else "container-$name",
                            "recoverExisting" to (phase == 2 && name == "funds0"),
                            "inspection" to
                                mapOf(
                                    "State" to mapOf("Running" to true, "OOMKilled" to false),
                                    "RestartCount" to 0,
                                    "Mounts" to listOf(mapOf("Destination" to "/records", "Name" to "volume-$name")),
                                ),
                            "state" to (mapper.convertValue(financial, Map::class.java) + state),
                        ),
                )
            }
        val firstReply = original("B1")
        val admission =
            mapOf(
                "code" to if (hidden) "ACCEPTED_OBSERVED" else "ACCEPTED",
                "hidden" to hidden,
                "forwarded" to !hidden,
                "transportAccepted" to true,
                "requestReturned" to !hidden,
                "unconfirmed" to false,
                "positiveOfferPosition" to 100,
                "correlationId" to 1,
                "appliedLogPosition" to 600,
                "requestSha256" to "a".repeat(64),
                "wireSha256" to "b".repeat(64),
                "reply" to firstReply,
                "commit" to
                    mapOf(
                        "runId" to "run",
                        "service" to "funds0",
                        "bootId" to "boot-funds0",
                        "memberId" to 0,
                        "leadershipTermId" to 0,
                        "leaderMemberId" to 0,
                        "clusterRole" to "LEADER",
                        "logPosition" to 600,
                        "correlationId" to 1,
                        "requestSha256" to "a".repeat(64),
                        "wireSha256" to "b".repeat(64),
                        "receipt" to firstReply.getValue("receipt"),
                        "balance" to mapOf("userId" to "buyer", "assetId" to "USDT", "available" to 400, "hold" to 600),
                    ),
            )
        val stream =
            mapOf(
                "direction" to "FUNDS_TO_MATCHING",
                "fundsLineageHigh" to 11,
                "fundsLineageLow" to 22,
                "matchingLineageHigh" to 11,
                "matchingLineageLow" to 22,
                "ownershipEpoch" to 1,
                "configurationVersion" to 1,
            )
        val blocked =
            mapOf(
                "installationId" to "gate",
                "gate" to "btc-dispatch",
                "sequence" to 1,
                "bootId" to "boot-funds1",
                "orderId" to "B1",
                "payloadSha256" to "a".repeat(64),
                "wireSha256" to "b".repeat(64),
                "wireBytes" to 100,
                "connectionId" to "conn",
                "correlationId" to 7,
                "marketId" to "BTC-USDT",
                "described" to mapOf("code" to "DESCRIBED", "appliedLogPosition" to 100),
                "stream" to stream,
                "enabled" to true,
            )
        val offered = blocked + mapOf("enabled" to false, "positiveOfferPosition" to 200)
        val reply =
            mapOf(
                "code" to "APPLIED",
                "through" to 1,
                "appliedLogPosition" to 300,
                "bootId" to "boot-funds1",
                "connectionId" to "conn",
                "correlationId" to 7,
                "payloadSha256" to "a".repeat(64),
            )
        return tree(
            mapOf(
                "target" to "funds0",
                "initial" to runtimes(0, FundsFailoverStage.INITIALIZED),
                "elected" to runtimes(1, FundsFailoverStage.ORDER_HELD),
                "rejoined" to runtimes(2, FundsFailoverStage.BTC_RESTING),
                "stopped" to
                    mapOf(
                        "service" to "funds0",
                        "containerId" to "container-funds0",
                        "running" to false,
                        "oomKilled" to false,
                        "exitCode" to 137,
                    ),
                "admission" to admission,
                "admissionCompleted" to (admission + mapOf("code" to if (hidden) "UNCONFIRMED" else "ACCEPTED", "unconfirmed" to hidden)),
                "timeline" to
                    mapOf(
                        "acceptedObserved" to 10,
                        "killSent" to 11,
                        "killConfirmed" to 12,
                        "electionObserved" to 20,
                        "refusalConfirmed" to 30,
                        "gateReleased" to 40,
                        "btcSettled" to 50,
                        "rejoined" to 60,
                        "followupCompleted" to 70,
                    ),
                "eventOrder" to
                    listOf(
                        "acceptedObserved",
                        "killSent",
                        "killConfirmed",
                        "electionObserved",
                        "refusalConfirmed",
                        "gateReleased",
                        "btcSettled",
                        "rejoined",
                        "followupCompleted",
                    ).mapIndexed {
                        i,
                        key,
                        ->
                        key to
                            i + 1
                    }.toMap(),
                "gates" to
                    listOf(
                        mapOf(
                            "installationId" to "gate",
                            "gate" to "btc-dispatch",
                            "sequence" to 1,
                            "installedAt" to 5,
                            "blockedAt" to 21,
                            "releaseAt" to 40,
                            "blockedService" to "funds1",
                            "offeredService" to "funds1",
                            "heldWireSha256" to "b".repeat(64),
                            "heldWireBytes" to 100,
                            "blocked" to blocked,
                            "offered" to offered,
                            "reply" to reply,
                        ),
                    ),
            ),
        )
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FundingReplicationComparisonTest {
    @Test
    fun `자금 금액과 순번이 Long 범위를 벗어나도 예외 대신 불일치로 남긴다`() {
        val outsideLong = listOf(java.math.BigInteger("9223372036854775808"), java.math.BigInteger("-9223372036854775809"))
        for (value in outsideLong) {
            for ((parent, field) in listOf("/state/balances/0" to "available", "/state" to "lastSequence")) {
                val changed = applied(2).apply { (at(parent) as ObjectNode).set(field, jacksonObjectMapper().valueToTree<JsonNode>(value)) }
                val result = compareFundingReplication("run", replicas() + (2 to listOf(changed)), client())

                assertTrue(
                    result.issues.any {
                        it.category == "MISMATCH" && it.message.contains("$field expected=") &&
                            it.message.contains("actual=$value")
                    },
                    result.issues.toString(),
                )
            }
        }
    }

    @Test
    fun `READY 숫자 비교는 Long 최댓값을 허용하고 이를 넘는 버전은 SETUP으로 남긴다`() {
        val expected = ExpectedMarketConfiguration(Long.MAX_VALUE, "a".repeat(64))
        val markets = readyMarketsFixture(includeSui = true)
        assertTrue(compareFundingReadiness("run", readinessReplicas(markets, expected), markets, expected).isEmpty())
        val changed =
            readinessReplicas(markets, expected).mapValues { (_, events) ->
                events.map { event ->
                    event.apply {
                        (path("settings") as ObjectNode).put("marketConfigVersion", java.math.BigInteger("9223372036854775808"))
                    }
                }
            }

        val issues = compareFundingReadiness("run", changed, markets, expected)

        assertTrue(issues.count { it.category == "SETUP" && it.message.contains("marketConfigVersion") } == 3, issues.toString())
    }

    @Test
    fun `자금 준비 관찰의 손상된 적용 위치도 예외 대신 검증 불가로 남긴다`() {
        val malformedPositions = listOf(null, "unreadable", "128", 128.5, 0L, -1L, java.math.BigInteger("9223372036854775808"))
        val malformed =
            malformedPositions.map { position ->
                applied(0).apply { set("position", jacksonObjectMapper().valueToTree<JsonNode>(position)) }
            } + listOf(applied(0).apply { remove("position") })
        for (event in malformed) {
            val result = compareFundingReplication("run", replicas() + (0 to listOf(event)), client())
            assertTrue(result.issues.any { it.category == "UNVERIFIABLE" && it.message.contains("적용 위치") }, result.issues.toString())
        }
    }

    @Test
    fun `RDB READY는 사전에 지정한 버전과 원문 해시를 세 노드 각각에서 확인한다`() {
        val expected = ExpectedMarketConfiguration(7, "a".repeat(64))
        val markets = readyMarketsFixture(includeSui = true)

        assertTrue(compareFundingReadiness("run", readinessReplicas(markets, expected), markets, expected).isEmpty())
    }

    @Test
    fun `RDB READY의 버전과 해시가 누락 null 오류이면 세 노드 모두 같아도 실패한다`() {
        val expected = ExpectedMarketConfiguration(7, "a".repeat(64))
        val markets = readyMarketsFixture(includeSui = true)
        for (field in listOf("marketConfigVersion", "marketConfigSha256")) {
            for (invalid in listOf("missing", "null", "wrong")) {
                val changed =
                    readinessReplicas(markets, expected).mapValues { (_, events) ->
                        events.map { event ->
                            event.apply {
                                val settings = path("settings") as ObjectNode
                                when (invalid) {
                                    "missing" -> {
                                        settings.remove(field)
                                    }

                                    "null" -> {
                                        settings.putNull(field)
                                    }

                                    else -> {
                                        if (field ==
                                            "marketConfigVersion"
                                        ) {
                                            settings.put(field, 8L)
                                        } else {
                                            settings.put(field, "b".repeat(64))
                                        }
                                    }
                                }
                            }
                        }
                    }

                val issues = compareFundingReadiness("run", changed, markets, expected)

                assertTrue(
                    issues.count { it.category == "SETUP" && it.message.contains("settings.$field") } == 3,
                    "$field/$invalid: $issues",
                )
            }
        }
    }

    @Test
    fun `기본 READY는 BTC 하나의 전체 정의를 확인하고 두 마켓 기대도 전체 배열로 확인한다`() {
        assertTrue(compareFundingReadiness("run", readinessReplicas()).isEmpty())
        val expected = readyMarketsFixture(includeSui = true)
        assertTrue(compareFundingReadiness("run", readinessReplicas(expected), expected).isEmpty())
    }

    @Test
    fun `READY 목록의 누락 빈 값 추가 중복과 순서 변경은 모두 실패한다`() {
        val expected = readyMarketsFixture(includeSui = true)
        val invalid =
            listOf(
                emptyList(),
                expected.take(1),
                expected + expected.last(),
                listOf(expected.first(), expected.first()),
                expected.reversed(),
            )
        invalid.forEach { markets ->
            val issues = compareFundingReadiness("run", readinessReplicas(markets), expected)
            assertTrue(issues.count { it.message.contains("settings.markets expected=") } == 3, markets.toString())
        }
        val missing =
            readinessReplicas(expected).mapValues { (_, events) ->
                events.map { event -> event.apply { (path("settings") as ObjectNode).remove("markets") } }
            }
        assertTrue(compareFundingReadiness("run", missing, expected).any { it.message.contains("settings.markets") })

        // 세 노드에 같은 추가 마켓이 있어도 BTC 기본 실행의 기대를 바꾸지 않는다.
        assertTrue(compareFundingReadiness("run", readinessReplicas(expected)).any { it.message.contains("settings.markets") })
    }

    @Test
    fun `세 READY가 같은 잘못된 SUI 필드를 출력해도 독립 기대값과 다르면 실패한다`() {
        val expected = readyMarketsFixture(includeSui = true)
        val incorrectFields =
            mapOf(
                "marketId" to "OTHER-USDT",
                "baseAssetId" to "OTHER",
                "quoteAssetId" to "OTHER",
                "baseQuantityScale" to 8,
                "productType" to "PERPETUAL_FUTURES",
                "feeTier" to "WRONG",
                "scheduleVersion" to 1,
                "makerPartsPerMillion" to 1,
                "takerPartsPerMillion" to 1,
            )
        incorrectFields.forEach { (field, value) ->
            val changed =
                readinessReplicas(expected).mapValues { (_, events) ->
                    events.map { event ->
                        event.apply {
                            val market = at("/settings/markets/1") as ObjectNode
                            val target = if (market.has(field)) market else market.path("feePolicy") as ObjectNode
                            target.set(field, jacksonObjectMapper().valueToTree<JsonNode>(value))
                        }
                    }
                }
            val issues = compareFundingReadiness("run", changed, expected)
            assertTrue(issues.count { it.message.contains("settings.markets expected=") } == 3, field)
        }
    }

    @Test
    fun `옛 단일 마켓 필드만 있는 READY를 기본 BTC로 보완하지 않는다`() {
        val changed =
            readinessReplicas().mapValues { (_, events) ->
                events.map { event ->
                    event.apply {
                        val settings = path("settings") as ObjectNode
                        val market = settings.path("markets").path(0)
                        settings.set("market", market)
                        settings.set("feePolicy", market.path("feePolicy"))
                        settings.remove("markets")
                    }
                }
            }
        val issues = compareFundingReadiness("run", changed)
        assertTrue(issues.any { it.message.contains("settings.markets") })
        assertTrue(issues.any { it.message.contains("단일 마켓 필드") })
    }

    @Test
    fun `세 사본이 이번 입력의 같은 L에서 독립 잔고와 OPENING 기대값에 맞으면 통과한다`() {
        val events = replicas()
        // 같은 L의 응답 시도 이벤트가 추가되어도 돈을 다시 지급한 것으로 세지 않는다.
        val withReply = events + (0 to (events.getValue(0) + listOf(applied(0))))
        assertTrue(compareFundingReplication("run", withReply, client()).succeeded)
    }

    @Test
    fun `하나의 새 관찰이 없거나 다른 실행 관찰이면 검증 불가다`() {
        val wrongRun = replicas() + (2 to listOf(applied(2).apply { put("runId", "old-run") }))
        for (events in listOf(replicas() - 2, wrongRun)) {
            val result = compareFundingReplication("run", events, client())
            assertFalse(result.succeeded)
            assertTrue(result.issues.any { it.category == "UNVERIFIABLE" && it.message.contains("node2") })
        }
    }

    @Test
    fun `같은 P1이라도 오래된 L이나 다른 L은 이번 완료 근거가 아니다`() {
        val old = compareFundingReplication("run", replicas(), client(), previousPosition = 128)
        assertFalse(old.succeeded)
        val different = replicas() + (2 to listOf(applied(2).apply { put("position", 96) }))
        assertTrue(compareFundingReplication("run", different, client()).issues.any { it.message.contains("actual=[96]") })
        val contradictory = replicas() + (2 to listOf(applied(2), applied(2).apply { put("position", 160) }))
        assertTrue(compareFundingReplication("run", contradictory, client()).issues.any { it.message.contains("서로 다른 적용 위치") })
    }

    @Test
    fun `금액 계정 시각은 각각 독립 기대값과 대조한다`() {
        for (field in listOf("available", "accountId", "occurredAt")) {
            val changed = applied(2)
            when (field) {
                "available" -> (changed.at("/state/balances/0") as ObjectNode).put(field, 999)
                "accountId" -> (changed.at("/state/transactions/0/postings/0") as ObjectNode).put(field, "USER:OTHER:USDT:AVAILABLE")
                else -> (changed.at("/state/transactions/0") as ObjectNode).put(field, "2026-10-20T00:00:00Z")
            }
            val result = compareFundingReplication("run", replicas() + (2 to listOf(changed)), client())
            assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains(field) }, field)
        }
    }

    @Test
    fun `세 사본이 모두 똑같이 틀려도 통과하지 않는다`() {
        val events =
            (0..2).associateWith { member ->
                listOf(applied(member).apply { (at("/state/balances/0") as ObjectNode).put("available", 2_000_000_000) })
            }
        assertTrue(compareFundingReplication("run", events, client()).issues.count { it.category == "MISMATCH" } == 3)
    }

    @Test
    fun `현재 응답이 맞아도 보존한 최초 준비 원본이 다르면 실패한다`() {
        val changed = applied(1).apply { (at("/state/preparations/0") as ObjectNode).put("amount", 2_000_000_000) }
        val result = compareFundingReplication("run", replicas() + (1 to listOf(changed)), client())
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("preparation.amount") })
    }

    @Test
    fun `이미 확인한 불일치는 뒤의 수집 오류와 누락으로 덮어쓰지 않는다`() {
        val bad = applied(0).apply { (at("/state/balances/0") as ObjectNode).put("hold", 1) }
        val result =
            compareFundingReplication(
                "run",
                mapOf(0 to listOf(bad)),
                client(),
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "node2 로그 조회 실패")),
            )
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("hold") })
        assertTrue(result.issues.any { it.category == "COLLECTION" })
        assertTrue(result.issues.any { it.category == "UNVERIFIABLE" })
    }

    @Test
    fun `전송 수용만 있고 결과가 없으면 반영 관찰이 같아도 성공이 아니다`() {
        val result = compareFundingReplication("run", replicas(), client().filter { it.path("kind").asString() == "TRANSPORT_ACCEPTED" })
        assertFalse(result.succeeded)
        assertTrue(result.issues.any { it.message.contains("최초 결과") })
    }

    @Test
    fun `손상된 구조화 출력은 빈 성공 대신 해석 실패로 남는다`() {
        val issues = mutableListOf<FundingVerificationIssue>()
        assertTrue(readFundingEvents("일반 로그\n${FUNDING_EVENT_PREFIX}{bad", issues).isEmpty())
        assertTrue(issues.single().category == "UNVERIFIABLE")
    }

    @Test
    fun `첫 노드가 생성되지 않아도 다른 두 노드의 기록 식별을 계속한다`() {
        val issues = mutableListOf<FundingVerificationIssue>()
        val found =
            discoverFundingNodeIds(issues) { member ->
                check(member != 0) { "node0 생성 실패" }
                "container-$member"
            }
        kotlin.test.assertEquals(mapOf(1 to "container-1", 2 to "container-2"), found)
        assertTrue(issues.single().message.contains("node0"))
    }

    @Test
    fun `후속 로그가 일부만 반환돼도 앞서 읽은 업무 불일치를 잃지 않는다`() {
        val bad = applied(0).apply { (at("/state/balances/0") as ObjectNode).put("available", 999) }
        val truncated = tree(mapOf("kind" to "READY", "runId" to "run", "memberId" to 0))
        val history = mergeFundingObservations(listOf(bad), listOf(truncated))
        val result =
            compareFundingReplication(
                "run",
                mapOf(0 to history),
                client(),
                collectionIssues = listOf(FundingVerificationIssue("COLLECTION", "부분 로그 조회 실패")),
            )
        assertTrue(result.issues.any { it.category == "MISMATCH" && it.message.contains("available") })
        assertTrue(result.issues.any { it.category == "COLLECTION" })
        assertTrue(result.issues.any { it.category == "UNVERIFIABLE" })
    }

    private fun readinessReplicas(
        markets: List<Map<String, Any>> = readyMarketsFixture(),
        configuration: ExpectedMarketConfiguration? = null,
    ): Map<Int, List<ObjectNode>> =
        (0..2).associateWith { member ->
            listOf(
                tree(
                    mapOf(
                        "kind" to "READY",
                        "runId" to "run",
                        "memberId" to member,
                        "pid" to member + 1,
                        "role" to if (member == 0) "LEADER" else "FOLLOWER",
                        "settings" to
                            mapOf(
                                "clusterId" to 97,
                                "serviceId" to 0,
                                "members" to "0,node0:19700|1,node1:19700|2,node2:19700",
                                "runRoot" to "/records/run",
                                "driverDirectory" to "/records/run/node$member/driver",
                                "archiveDirectory" to "/records/run/node$member/archive",
                                "clusterDirectory" to "/records/run/node$member/cluster",
                                "storageLevels" to listOf(2, 2, 2),
                                "markets" to markets,
                                "marketConfigVersion" to configuration?.version,
                                "marketConfigSha256" to configuration?.sha256,
                            ),
                    ),
                ),
            )
        }

    private fun readyMarketsFixture(includeSui: Boolean = false): List<Map<String, Any>> {
        val btc =
            mapOf(
                "marketId" to "BTC-USDT",
                "baseAssetId" to "BTC",
                "quoteAssetId" to "USDT",
                "baseQuantityScale" to 8,
                "feePolicy" to
                    mapOf(
                        "productType" to "SPOT",
                        "feeTier" to "NORMAL",
                        "scheduleVersion" to 1,
                        "makerPartsPerMillion" to 0,
                        "takerPartsPerMillion" to 0,
                    ),
            )
        if (!includeSui) return listOf(btc)
        val sui =
            mapOf(
                "marketId" to "SUI-USDT",
                "baseAssetId" to "SUI",
                "quoteAssetId" to "USDT",
                "baseQuantityScale" to 9,
                "feePolicy" to
                    mapOf(
                        "productType" to "SPOT",
                        "feeTier" to "NORMAL",
                        "scheduleVersion" to 2,
                        "makerPartsPerMillion" to 0,
                        "takerPartsPerMillion" to 0,
                    ),
            )
        return listOf(btc, sui)
    }

    private fun replicas(): Map<Int, List<JsonNode>> = (0..2).associateWith { listOf(applied(it)) }

    private fun client(): List<JsonNode> =
        listOf(
            tree(mapOf("kind" to "TRANSPORT_ACCEPTED", "runId" to "run")),
            tree(mapOf("kind" to "RESULT_RECEIVED", "runId" to "run", "result" to resultFixture())),
        )

    private fun applied(member: Int): ObjectNode =
        tree(
            mapOf(
                "kind" to "APPLIED",
                "runId" to "run",
                "memberId" to member,
                "role" to if (member == 0) "LEADER" else "FOLLOWER",
                "preparationId" to "P1",
                "position" to 128,
                "result" to resultFixture(),
                "state" to
                    mapOf(
                        "balances" to listOf(mapOf("userId" to "U1", "assetId" to "USDT", "available" to 1_000_000_000, "hold" to 0)),
                        "preparationCount" to 1,
                        "preparations" to listOf(resultFixture()),
                        "pendingOrderCount" to 0,
                        "requestRecordCount" to 0,
                        "lastSequence" to 0,
                        "transactions" to
                            listOf(
                                mapOf(
                                    "transactionId" to "L-OPEN-1",
                                    "sourceEventId" to "OPENING:P1",
                                    "kind" to "OPENING",
                                    "occurredAt" to "2026-10-20T00:00:00.123456789Z",
                                    "postings" to
                                        listOf(
                                            mapOf(
                                                "accountId" to "SYSTEM:USDT:DEVELOPMENT_FUNDING",
                                                "assetId" to "USDT",
                                                "side" to "DEBIT",
                                                "amount" to 1_000_000_000,
                                            ),
                                            mapOf(
                                                "accountId" to "USER:U1:USDT:AVAILABLE",
                                                "assetId" to "USDT",
                                                "side" to "CREDIT",
                                                "amount" to 1_000_000_000,
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
        )

    private fun tree(value: Any): ObjectNode = jacksonObjectMapper().valueToTree(value)

    private fun resultFixture(): Map<String, Any> =
        mapOf(
            "preparationId" to "P1",
            "userId" to "U1",
            "assetId" to "USDT",
            "amount" to 1_000_000_000,
            "ledgerTransactionId" to "L-OPEN-1",
            "occurredAt" to "2026-10-20T00:00:00.123456789Z",
            "alreadyPrepared" to false,
        )
}

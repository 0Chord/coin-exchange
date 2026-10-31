package com.exchange.core.api.trading.infrastructure.aeron

import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 제품 HTTP main과 독립된 세 Docker JVM을 사용한다. Docker가 없으면 실패한다. */
class DockerHttpOrderAdmissionTest {
    @Test
    fun `실제 HTTP 접수의 체결 정산 재전송 거절과 기존 RDB 경로 격리를 세 노드에서 확인한다`() {
        DockerHttpOrderVerification().use { verification ->
            verification.start()
            verification.fund("P-BUY", "BUYER", "USDT", 11_000)
            verification.fund("P-SELL", "SELLER", "BTC", 100_000_000)
            verification.fund("P-PARALLEL", "PARALLEL", "USDT", 1_000)

            val sell = order("RS1", "S1", "SELLER", "SELL")
            verification.accept(sell, 1)
            verification.awaitCompletions(1)
            val buy = order("RB1", "B1", "BUYER", "BUY")
            val firstBuy = verification.accept(buy, 2)
            verification.awaitCompletions(2)
            verification.assertTradeCheckpoint()
            val traded = verification.economicState()

            assertEquals(firstBuy, verification.accept(buy, 2), "재전송은 최초 접수 응답을 재사용한다")
            verification.reject(buy + ("price" to 9_999), 409, "REQUEST_CONTENT_CONFLICT")
            verification.reject(buy + ("requestId" to "RB2"), 409, "DUPLICATE_ORDER")
            verification.reject(buy, 409, "REQUEST_CONTENT_CONFLICT", "/api/trading/markets/UNKNOWN/orders")
            val poor = order("R-POOR", "O-POOR", "POOR", "BUY")
            verification.reject(poor, 422, "INSUFFICIENT_BALANCE")
            verification.reject(poor, 409, "REQUEST_CONTENT_CONFLICT", "/api/trading/markets/UNKNOWN/orders")
            verification.reject(
                order("R-MARKET", "O-MARKET", "BUYER", "BUY") + ("orderType" to "MARKET"),
                422,
                "UNSUPPORTED_ORDER_CONDITION",
            )
            verification.reject(order("R-IOC", "O-IOC", "BUYER", "BUY") + ("timeInForce" to "IOC"), 422, "UNSUPPORTED_ORDER_CONDITION")
            verification.expectStatus(order("R-UNKNOWN", "O-UNKNOWN", "BUYER", "BUY"), 422, "/api/trading/markets/UNKNOWN/orders")
            for (body in listOf(
                "{",
                "{}",
                """{"requestId":"R","orderId":"O","userId":"BUYER","side":"UNKNOWN","orderType":"LIMIT","timeInForce":"GTC","price":10000,"quantity":100000000}""",
                verification.json(buy + ("quantity" to 0)),
                verification.json(buy + ("price" to Long.MAX_VALUE.toString() + "0")),
            )) {
                verification.expectRawStatus(body, 400)
            }
            verification.assertLegacyRoutesUnavailable()
            verification.awaitOrderResult("POOR", "R-POOR")
            assertEquals(traded, verification.economicState(), "재전송·충돌·업무 거절·잘못된 HTTP 입력은 경제 상태를 늘리지 않는다")

            // 거절 다음에도 같은 실제 gateway 연결로 정상 주문을 접수할 수 있다.
            val recovery = order("R-RECOVER", "O-RECOVER", "PARALLEL", "BUY", 100)
            verification.accept(recovery, 3)
            verification.awaitCompletions(3)

            val parallel = (1..4).map { order("RP$it", "OP$it", "PARALLEL", "BUY", 100) }
            val responses = verification.parallel(parallel)
            assertEquals(setOf(4L, 5L, 6L, 7L), responses.map { it.path("sequence").asLong() }.toSet())
            parallel.zip(responses).forEach { (request, response) -> verification.assertAccepted(request, response) }
            verification.awaitCompletions(7)
            verification.assertResponseSequences(parallel, responses)

            val same = order("R-SAME", "O-SAME", "PARALLEL", "BUY", 100)
            val shared = verification.parallel(listOf(same, same))
            assertEquals(shared[0], shared[1], "같은 내용의 동시 HTTP 재전송은 같은 최초 결과를 받는다")
            assertEquals(8L, shared[0].path("sequence").asLong())
            verification.awaitCompletions(8)

            val conflict = order("R-RACE", "O-RACE", "PARALLEL", "BUY", 100)
            val conflictPrice = verification.parallelConflict(conflict, conflict + ("price" to 101L))
            verification.awaitCompletions(9)

            // 응답을 읽지 않고 HTTP 연결을 닫아도 주문을 자동 취소하지 않는다.
            val lost = order("R-LOST", "O-LOST", "PARALLEL", "BUY", 100)
            verification.discardResponse(lost)
            verification.awaitOrderResult("PARALLEL", "R-LOST")
            verification.accept(lost, 10)
            verification.awaitCompletions(10)
            verification.assertFinalCoverage(conflictPrice)
            verification.assertDatabaseMoneyUnchanged()
        }
    }
}

private fun order(
    requestId: String,
    orderId: String,
    userId: String,
    side: String,
    price: Long = 10_000,
): Map<String, Any> =
    linkedMapOf(
        "requestId" to requestId,
        "orderId" to orderId,
        "userId" to userId,
        "side" to side,
        "orderType" to "LIMIT",
        "timeInForce" to "GTC",
        "price" to price,
        "quantity" to 100_000_000L,
    )

private const val HTTP_MARKET_DOCUMENT =
    """[{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]"""

/** 제품 코드에 관찰용 HTTP API나 완료 gate를 추가하지 않는다. */
private class DockerHttpOrderVerification : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    private val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "http${System.currentTimeMillis()}${UUID.randomUUID().toString().take(8)}"
    private val reports = project.resolve("build/reports/aeron-http/$runId")
    private val image = "exchange-http:$runId"
    private val environment = mutableMapOf("FUNDING_IMAGE" to image, "FUNDING_RUN_ID" to runId)
    private val commands = VerificationCommands(project, reports, environment)
    private val issues = mutableListOf<FundingVerificationIssue>()
    private val nodes = linkedMapOf<Int, String>()
    private val observations = linkedMapOf<Int, List<JsonNode>>()
    private val httpRecords = mutableListOf<Map<String, Any?>>()
    private val clients = mutableListOf<String>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val password = "http148-${UUID.randomUUID()}"
    private var database: MarketConfigurationDatabase? = null
    private var databaseMoney: JsonNode? = null
    private var network: String? = null
    private var composeAttempted = false
    private var port = 0
    private var gatewayId: String? = null
    private var expectedConfiguration: ExpectedMarketConfiguration? = null

    init {
        Files.createDirectories(reports)
    }

    fun start() {
        must(command(listOf("docker", "version"), 15_000), "Docker 준비")
        must(compose(listOf("version"), 15_000), "Compose 준비")
        check(Files.isDirectory(distribution.resolve("lib"))) { "검증 배포 산출물이 없습니다: $distribution" }
        must(
            command(
                listOf(
                    "docker",
                    "build",
                    "-f",
                    project.resolve("src/test/docker/aeron-funding/Dockerfile").toString(),
                    "-t",
                    image,
                    distribution.toString(),
                ),
                300_000,
            ),
            "이미지 준비",
        )
        val db = MarketConfigurationDatabase(password)
        database = db
        val settings = db.register(HTTP_MARKET_DOCUMENT)
        environment.putAll(settings)
        environment["FUNDING_MARKET_DB_URL"] = db.containerJdbcUrl
        expectedConfiguration =
            ExpectedMarketConfiguration(
                settings.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong(),
                settings.getValue("FUNDING_MARKET_CONFIG_SHA256"),
            )
        seedDatabaseMoney()
        databaseMoney = readDatabaseMoney()
        composeAttempted = true
        must(compose(listOf("create", "node0", "node1", "node2", "gateway"), 30_000), "네 JVM 생성")
        for (member in 0..2) nodes[member] = containerId("node$member")
        gatewayId = containerId("gateway")
        val inspections = nodes.mapValues { inspect(it.value) }
        val networks =
            inspections.values.map {
                it
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .toSet()
            }
        assertTrue(networks.all { it.size == 1 } && networks.distinct().size == 1, "동일한 전용 bridge 하나")
        network = networks.first().single()
        must(
            command(listOf("docker", "network", "connect", "--alias", "market-db", checkNotNull(network), db.containerId), 15_000),
            "마켓 DB 연결",
        )
        must(compose(listOf("up", "-d", "node0", "node1", "node2"), 60_000), "세 노드 기동")
        await("같은 RDB 설정과 sync=2인 세 노드 준비", 60_000) {
            compareFundingReadiness(runId, observations, expectedSettings(), checkNotNull(expectedConfiguration)).isEmpty()
        }
        assertIsolation()
        must(compose(listOf("up", "-d", "gateway"), 30_000), "제품 HTTP gateway 기동")
        val gateway = inspect(checkNotNull(gatewayId))
        val binding =
            gateway
                .path("NetworkSettings")
                .path("Ports")
                .path("8080/tcp")
                .toList()
                .singleOrNull()
        check(binding != null && binding.path("HostIp").asString() == "127.0.0.1") {
            "제품 HTTP 포트의 실제 loopback 매핑이 없습니다. 보고서: $reports"
        }
        port = binding
            .path("HostPort")
            .asString()
            .toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: error("제품 HTTP 포트가 올바르지 않습니다. 보고서: $reports")
        await("실제 제품 HTTP 서버 준비", 60_000) {
            runCatching {
                http
                    .send(
                        HttpRequest
                            .newBuilder(uri("/actuator/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding(),
                    ).statusCode() ==
                    200
            }.getOrDefault(false)
        }
        assertEquals(inspections.values.first().path("Image"), gateway.path("Image"), "제품 gateway와 노드의 실제 이미지")
        assertTrue(gateway.path("Id").asString() !in nodes.values, "HTTP는 노드와 별도 컨테이너/JVM")
        assertEquals(
            networks.first() + "${runId}_gateway-http",
            gateway
                .path("NetworkSettings")
                .path("Networks")
                .propertyNames()
                .toSet(),
        )
    }

    fun json(value: Any): String = mapper.writeValueAsString(value)

    fun fund(
        id: String,
        user: String,
        asset: String,
        amount: Long,
    ) {
        val name = "$runId-${id.lowercase()}"
        clients.add(name)
        val result =
            compose(
                listOf(
                    "run",
                    "--name",
                    name,
                    "--env",
                    "FUNDING_HOST=$name",
                    "client",
                    "com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingClientKt",
                    "--cluster-env",
                    id,
                    user,
                    asset,
                    amount.toString(),
                    "OPEN-$id",
                    "2026-10-26T00:00:00Z",
                ),
                100_000,
            )
        must(result, "$id 엔진 자금 준비")
        val events = readFundingEvents(result.stdout, issues)
        assertEquals(1, events.count { it.path("kind").asString() == "RESULT_RECEIVED" })
        await("$id 세 사본") { (0..2).all { member -> applied(member).any { it.path("preparationId").asString() == id } } }
    }

    fun accept(
        body: Map<String, Any>,
        sequence: Long,
    ): JsonNode {
        val response = request(json(body))
        assertEquals(202, response.first, "HTTP 접수: ${response.second}")
        assertAccepted(body, response.second)
        assertEquals(sequence, response.second.path("sequence").asLong())
        return response.second
    }

    fun assertAccepted(
        body: Map<String, Any>,
        response: JsonNode,
    ) {
        assertEquals("ACCEPTED", response.path("status").asString())
        for (field in listOf(
            "userId",
            "requestId",
            "orderId",
        )) {
            assertEquals(body.getValue(field).toString(), response.path(field).asString(), "HTTP 응답 $field 대응")
        }
        assertEquals("BTC-USDT", response.path("marketId").asString())
        assertTrue(response.path("sequence").asLong() > 0)
    }

    fun reject(
        body: Map<String, Any>,
        code: Int,
        reason: String,
        path: String = ORDER_PATH,
    ) {
        val response = request(json(body), path)
        assertEquals(code, response.first, response.second.toString())
        assertEquals(reason, response.second.path("reason").asString())
    }

    fun expectStatus(
        body: Map<String, Any>,
        code: Int,
        path: String,
    ) = expectRawStatus(json(body), code, path)

    fun expectRawStatus(
        body: String,
        code: Int,
        path: String = ORDER_PATH,
    ) {
        val response = request(body, path)
        assertEquals(code, response.first, response.second.toString())
    }

    fun assertLegacyRoutesUnavailable() {
        val before = economicState()
        for ((method, path) in listOf(
            "POST" to "/api/markets/BTC-USDT/orders",
            "DELETE" to "/api/markets/BTC-USDT/orders/S1?userId=SELLER",
            "DELETE" to "/api/trading/markets/BTC-USDT/orders/S1?userId=SELLER",
        )) {
            val response = request(json(order("LEGACY", "LEGACY", "BUYER", "BUY")), path, method)
            assertTrue(response.first in setOf(404, 405), "$method $path 경로가 실행되면 안 된다: $response")
        }
        collectNodes()
        assertEquals(before, economicState(), "기존 주문·취소 URL은 엔진 상태를 바꾸지 않는다")
        assertDatabaseMoneyUnchanged()
    }

    fun parallel(bodies: List<Map<String, Any>>): List<JsonNode> {
        val futures = bodies.map { body -> http.sendAsync(httpRequest(json(body)), HttpResponse.BodyHandlers.ofString()) }
        CompletableFuture.allOf(*futures.toTypedArray()).get(15, TimeUnit.SECONDS)
        return bodies.zip(futures).map { (body, future) ->
            val response = future.get()
            record("POST", ORDER_PATH, json(body), response.statusCode(), response.body())
            assertEquals(202, response.statusCode(), response.body())
            mapper.readTree(response.body()).also { assertAccepted(body, it) }
        }
    }

    fun parallelConflict(
        first: Map<String, Any>,
        second: Map<String, Any>,
    ): Long {
        val bodies = listOf(first, second)
        val futures = bodies.map { body -> http.sendAsync(httpRequest(json(body)), HttpResponse.BodyHandlers.ofString()) }
        CompletableFuture.allOf(*futures.toTypedArray()).get(15, TimeUnit.SECONDS)
        val responses = futures.map { it.get() }
        assertEquals(listOf(202, 409), responses.map { it.statusCode() }.sorted())
        val acceptedIndex = responses.indexOfFirst { it.statusCode() == 202 }
        responses.forEachIndexed {
            index,
            response,
            ->
            record("POST", ORDER_PATH, json(bodies[index]), response.statusCode(), response.body())
        }
        val accepted = mapper.readTree(responses[acceptedIndex].body())
        assertAccepted(bodies[acceptedIndex], accepted)
        assertEquals(9L, accepted.path("sequence").asLong())
        val rejected = mapper.readTree(responses[1 - acceptedIndex].body())
        assertTrue(rejected.path("reason").asString() in setOf("REQUEST_IN_FLIGHT_CONTENT_CONFLICT", "REQUEST_CONTENT_CONFLICT"))
        return (bodies[acceptedIndex].getValue("price") as Number).toLong()
    }

    fun discardResponse(body: Map<String, Any>) {
        val bytes = json(body).toByteArray(Charsets.UTF_8)
        Socket("127.0.0.1", port).use { socket ->
            val headers =
                "POST $ORDER_PATH HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }
        httpRecords.add(mapOf("method" to "POST", "path" to ORDER_PATH, "request" to body, "responseDiscarded" to true))
    }

    fun awaitOrderResult(
        user: String,
        request: String,
    ) {
        await("$user/$request 최초 결과 세 사본") {
            (0..2).all { member ->
                applied(member).any { it.path("userId").asString() == user && it.path("requestId").asString() == request }
            }
        }
    }

    fun assertResponseSequences(
        requests: List<Map<String, Any>>,
        responses: List<JsonNode>,
    ) {
        for ((request, response) in requests.zip(responses)) {
            val user = request.getValue("userId").toString()
            val requestId = request.getValue("requestId").toString()
            awaitOrderResult(user, requestId)
            for (member in 0..2) {
                val results =
                    applied(member).filter {
                        it.path("inputKind").asString() == "ORDER" && it.path("userId").asString() == user &&
                            it.path("requestId").asString() == requestId
                    }
                assertTrue(results.isNotEmpty(), "node$member $user/$requestId 실제 접수 결과")
                for (result in results) {
                    assertEquals(request.getValue("orderId").toString(), result.path("orderId").asString())
                    assertEquals("ACCEPTED", result.path("result").path("outcome").asString())
                    assertEquals(
                        response.path("sequence").asLong(),
                        result.path("result").path("sequence").asLong(),
                        "node$member $user/$requestId 엔진 결과와 HTTP sequence 대응",
                    )
                }
            }
        }
    }

    fun awaitCompletions(count: Int) {
        await("내부 확정 완료 $count 세 사본") {
            (0..2).all { member -> latest(member)?.path("trading")?.path("completions")?.size() == count } &&
                (0..2).map { member -> latest(member)?.path("position")?.asLong() }.distinct().size == 1
        }
        val copies = (0..2).map { member -> latest(member)!!.let { it.path("state") to it.path("trading") } }
        assertEquals(1, copies.distinct().size, "세 노드의 전체 업무 상태")
    }

    fun assertTradeCheckpoint() {
        for (member in 0..2) {
            val event = latest(member)!!
            val state = event.path("state")
            val balances =
                state.path("balances").associate {
                    (it.path("userId").asString() to it.path("assetId").asString()) to
                        (it.path("available").asLong() to it.path("hold").asLong())
                }
            assertEquals(1_000L to 0L, balances["BUYER" to "USDT"])
            assertEquals(100_000_000L to 0L, balances["BUYER" to "BTC"])
            assertEquals(0L to 0L, balances["SELLER" to "BTC"])
            assertEquals(10_000L to 0L, balances["SELLER" to "USDT"])
            assertEquals(0, state.path("pendingOrderCount").asInt())
            val trading = event.path("trading")
            val trades =
                trading.path("completions").flatMap { it.path("events").toList() }.filter {
                    it.path("kind").asString() ==
                        "TRADE_EXECUTED"
                }
            assertEquals(1, trades.size)
            assertEquals(10_000L, trades.single().path("price").asLong())
            assertEquals(100_000_000L, trades.single().path("quantity").asLong())
            assertEquals("S1", trades.single().path("makerOrderId").asString())
            assertEquals("B1", trades.single().path("takerOrderId").asString())
            assertEquals(1, trading.path("completions").sumOf { it.path("settlements").size() })
            assertEquals(1, state.path("transactions").count { it.path("kind").asString() == "SETTLEMENT" })
            assertTrue(
                trading.path("currentReservations").all {
                    it.path("remainingAmount").asLong() == 0L &&
                        it.path("status").asString() == "SETTLED"
                },
            )
            assertTrue(trading.path("books").all { it.path("orders").isEmpty })
        }
    }

    fun economicState(): List<JsonNode> {
        collectNodes()
        return (0..2).map { member ->
            val event = checkNotNull(latest(member))
            val state = event.path("state")
            val trading = event.path("trading")
            mapper.valueToTree(
                mapOf(
                    "balances" to state.path("balances"),
                    "transactions" to state.path("transactions"),
                    "lastSequence" to state.path("lastSequence"),
                    "pendingOrders" to state.path("pendingOrders"),
                    "reservations" to trading.path("currentReservations"),
                    "completions" to trading.path("completions"),
                    "books" to trading.path("books"),
                ),
            )
        }
    }

    fun assertFinalCoverage(conflictPrice: Long) {
        await("마지막 재전송까지 세 로그 위치 수렴") {
            (0..2).map { member -> applied(member).map { it.path("position").asLong() }.distinct() }.distinct().size == 1
        }
        for (member in 0..2) {
            val events = observations.getValue(member)
            assertTrue(events.none { it.path("kind").asString() == "ERROR" }, "node$member 오류 없음")
            val state = latest(member)!!.path("state")
            assertEquals(3, state.path("preparationCount").asInt())
            assertEquals(10L, state.path("lastSequence").asLong())
            assertEquals(0, state.path("pendingOrderCount").asInt())
            assertEquals(3, state.path("transactions").count { it.path("kind").asString() == "OPENING" })
            assertEquals(10, state.path("transactions").count { it.path("kind").asString() == "RESERVE" })
            assertEquals(1, state.path("transactions").count { it.path("kind").asString() == "SETTLEMENT" })
            assertEquals(14, state.path("transactions").size())
            val completions = latest(member)!!.path("trading").path("completions")
            assertEquals(10, completions.size())
            assertEquals(
                1,
                completions.sumOf { completion ->
                    completion.path("events").count {
                        it.path("kind").asString() ==
                            "TRADE_EXECUTED"
                    }
                },
            )
            val final =
                state.path("balances").single {
                    it.path("userId").asString() == "PARALLEL" &&
                        it.path("assetId").asString() == "USDT"
                }
            assertEquals(300L - conflictPrice, final.path("available").asLong())
            assertEquals(700L + conflictPrice, final.path("hold").asLong())
            assertTrue(applied(member).all { it.path("malformedMessages").asLong() == 0L })
        }
        val positions = (0..2).map { member -> applied(member).map { it.path("position").asLong() }.distinct() }
        assertEquals(1, positions.distinct().size, "세 노드가 동일한 모든 로그 위치를 적용했다")
    }

    fun assertDatabaseMoneyUnchanged() = assertEquals(databaseMoney, readDatabaseMoney(), "HTTP 주문 중 RDB 잔고·예약·원장·기존 매칭 사건은 변하지 않는다")

    private fun seedDatabaseMoney() {
        checkNotNull(database).connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into balance_projection(user_id, asset_id, available, hold) values ('BUYER', 'USDT', 11000, 0), ('SELLER', 'BTC', 100000000, 0), ('PARALLEL', 'USDT', 1000, 0)",
                )
            }
        }
    }

    private fun readDatabaseMoney(): JsonNode =
        checkNotNull(database).connection().use { connection ->
            val tables = listOf("balance_projection", "order_reservations", "ledger_transactions", "ledger_postings", "matching_events")
            mapper.valueToTree(
                tables.associateWith { table ->
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = 3
                        statement
                            .executeQuery(
                                "select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]'::jsonb)::text from $table t",
                            ).use { result ->
                                check(result.next())
                                mapper.readTree(result.getString(1))
                            }
                    }
                },
            )
        }

    private fun request(
        body: String,
        path: String = ORDER_PATH,
        method: String = "POST",
    ): Pair<Int, JsonNode> {
        val response = http.send(httpRequest(body, path, method), HttpResponse.BodyHandlers.ofString())
        record(method, path, body, response.statusCode(), response.body())
        return response.statusCode() to if (response.body().isBlank()) mapper.nullNode() else mapper.readTree(response.body())
    }

    private fun record(
        method: String,
        path: String,
        body: String,
        status: Int,
        response: String,
    ) {
        httpRecords.add(mapOf("method" to method, "path" to path, "request" to body, "status" to status, "response" to response))
    }

    private fun httpRequest(
        body: String,
        path: String = ORDER_PATH,
        method: String = "POST",
    ): HttpRequest =
        HttpRequest
            .newBuilder(
                uri(path),
            ).timeout(
                Duration.ofSeconds(12),
            ).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun latest(member: Int): JsonNode? = applied(member).lastOrNull()

    private fun applied(member: Int) = observations[member].orEmpty().filter { it.path("kind").asString() == "APPLIED" }

    private fun collectNodes() {
        for ((member, id) in nodes) {
            val logs = command(listOf("docker", "logs", id), 10_000)
            must(logs, "node$member 관찰 수집")
            Files.writeString(reports.resolve("node$member.stdout.log"), logs.stdout)
            Files.writeString(reports.resolve("node$member.stderr.log"), logs.stderr)
            observations[member] = mergeFundingObservations(observations[member].orEmpty(), readFundingEvents(logs.stdout, issues))
        }
        check(issues.isEmpty()) { issues.toString() }
    }

    private fun await(
        description: String,
        timeoutMs: Long = 15_000,
        predicate: () -> Boolean,
    ) {
        val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        do {
            collectNodes()
            check(observations.values.flatten().none { it.path("kind").asString() == "ERROR" }) { "$description: 노드 오류" }
            if (predicate()) return
            Thread.sleep(50)
        } while (System.nanoTime() < limit)
        error("$description 제한 시간 초과. 보고서: $reports")
    }

    private fun assertIsolation() {
        val inspected = nodes.mapValues { inspect(it.value) }
        assertEquals(3, nodes.values.distinct().size)
        assertEquals(
            1,
            inspected.values
                .map { it.path("Image").asString() }
                .distinct()
                .size,
        )
        val volumes =
            inspected.values.map { node ->
                node
                    .path("Mounts")
                    .single { it.path("Destination").asString() == "/records" }
                    .path("Name")
                    .asString()
            }
        assertEquals(3, volumes.distinct().size, "각 노드는 별도 Archive 기록 볼륨을 사용한다")
        inspected.values.forEach { node ->
            assertTrue(node.path("State").path("Running").asBoolean())
            assertTrue(node.path("HostConfig").path("NetworkMode").asString() != "host")
            assertTrue(node.path("HostConfig").path("IpcMode").asString() != "host")
        }
        for (member in 0..2) {
            val ready = observations.getValue(member).single { it.path("kind").asString() == "READY" }
            val storageLevels: List<Int> =
                ready
                    .path("settings")
                    .path("storageLevels")
                    .toList()
                    .map { it.asInt() }
            assertEquals(
                listOf(2, 2, 2),
                storageLevels,
                "Archive·Consensus 실제 sync level",
            )
        }
    }

    private fun expectedSettings(): List<Map<String, Any>> =
        listOf(
            mapOf(
                "marketId" to "BTC-USDT",
                "baseAssetId" to "BTC",
                "quoteAssetId" to "USDT",
                "baseQuantityScale" to 8,
                "priceTick" to 1L,
                "quantityStep" to 100_000_000L,
                "feePolicy" to
                    mapOf(
                        "productType" to "SPOT",
                        "feeTier" to "NORMAL",
                        "scheduleVersion" to 1L,
                        "makerPartsPerMillion" to 0L,
                        "takerPartsPerMillion" to 0L,
                    ),
            ),
        )

    private fun containerId(service: String): String {
        val result = compose(listOf("ps", "--all", "--quiet", service), 15_000)
        must(result, "$service 식별")
        return result.stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .single()
    }

    private fun inspect(id: String): JsonNode {
        val result = command(dockerContainerInspection(id), 10_000)
        must(result, "$id 컨테이너 검사")
        Files.writeString(reports.resolve("${id.take(12)}.inspect.json"), result.stdout)
        return mapper.readTree(result.stdout).path(0)
    }

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): VerificationCommandResult =
        command(
            listOf(
                "docker",
                "compose",
                "--project-name",
                runId,
                "--file",
                project.resolve("src/test/docker/aeron-funding/compose.yml").toString(),
                "--file",
                project.resolve("src/test/docker/aeron-funding/http-gateway.compose.yml").toString(),
            ) +
                arguments,
            timeoutMs,
        )

    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ) = commands.start(arguments).await(timeoutMs)

    private fun must(
        result: VerificationCommandResult,
        stage: String,
    ) = check(result.succeeded) {
        "$stage: ${result.failure()}. 보고서: $reports"
    }

    override fun close() {
        val cleanup = mutableListOf<String>()

        fun safely(
            label: String,
            operation: () -> Unit,
        ) {
            runCatching(operation).onFailure { cleanup.add("$label: ${it.message}") }
        }
        safely("CLI 종료") { cleanup.addAll(commands.closeRunning().map { it.message }) }
        if (composeAttempted) {
            safely("gateway 종료") { must(compose(listOf("stop", "--timeout", "15", "gateway"), 20_000), "gateway 종료") }
            safely("노드 종료") { must(compose(listOf("stop", "--timeout", "15", "node0", "node1", "node2"), 20_000), "세 노드 종료") }
            safely("최종 관찰") { collectNodes() }
            gatewayId?.let { id ->
                safely("gateway 로그") {
                    val logs = command(listOf("docker", "logs", id), 10_000)
                    Files.writeString(reports.resolve("gateway.stdout.log"), logs.stdout)
                    Files.writeString(reports.resolve("gateway.stderr.log"), logs.stderr)
                    inspect(id)
                }
            }
            for ((member, id) in nodes) {
                safely("node$member 최종 기록") {
                    val node = inspect(id)
                    assertTrue(!node.path("State").path("Running").asBoolean())
                    assertTrue(node.path("State").path("ExitCode").asInt() in setOf(0, 143), "정상 JVM 종료 또는 SIGTERM")
                    assertTrue(!node.path("State").path("OOMKilled").asBoolean(), "메모리 부족 종료가 아니다")
                    val stopped = observations[member].orEmpty().single { it.path("kind").asString() == "STOPPED" }
                    assertTrue(
                        stopped.path("closed").asBoolean() && stopped.path("observationDrained").asBoolean(),
                        "node$member 정상 종료와 관찰 배출",
                    )
                    assertTrue(stopped.path("failure").isNull)
                }
            }
            for (client in clients) safely("$client 제거") { must(command(listOf("docker", "rm", "--force", client), 15_000), "$client 제거") }
            database?.let { db ->
                network?.let { net ->
                    safely(
                        "DB 네트워크 해제",
                    ) { must(command(listOf("docker", "network", "disconnect", "--force", net, db.containerId), 15_000), "DB 네트워크 해제") }
                }
            }
            safely("Compose 정리") { must(compose(listOf("down", "--remove-orphans", "--timeout", "15"), 30_000), "Compose 정리") }
        }
        safely("DB 종료") { database?.close() }
        safely("HTTP client 종료") { http.close() }
        Files.writeString(reports.resolve("http.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(httpRecords))
        Files.writeString(
            reports.resolve("result.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf(
                    "runId" to runId,
                    "marketConfiguration" to expectedConfiguration,
                    "observations" to observations,
                    "commands" to commands.records,
                    "issues" to issues,
                    "cleanupFailures" to cleanup,
                    "recordVolumesDeleted" to false,
                ),
            ),
        )
        assertTrue(credentialLeakFiles(reports, password).isEmpty(), "DB 비밀번호가 보고서에 남으면 안 된다")
        assertTrue(cleanup.isEmpty(), "정리 실패: $cleanup. 보고서: $reports")
    }

    companion object {
        private const val ORDER_PATH = "/api/trading/markets/BTC-USDT/orders"
    }
}

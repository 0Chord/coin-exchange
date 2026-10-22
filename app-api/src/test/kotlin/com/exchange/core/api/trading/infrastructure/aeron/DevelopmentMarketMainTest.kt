package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.DatagramSocket
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Settings를 직접 주입하지 않고 세 제품 main에 실제 환경·인자를 전달한다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevelopmentMarketMainTest {
    private lateinit var database: MarketConfigurationDatabase

    @BeforeAll
    fun startDatabase() {
        database = MarketConfigurationDatabase()
    }

    @AfterAll
    fun stopDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `환경 기반 세 main은 BTC와 SUI가 공유하는 잔고와 최초 결과를 단계마다 남긴다`() {
        val settings = database.register(MARKETS)
        MainProcesses(structured = true, marketEnvironment = settings).use { run ->
            val node = run.node()
            val readySettings = run.ready(node).path("settings")
            assertEquals(json(listOf(market("BTC"), market("SUI"))), readySettings.path("markets"))
            assertEquals(settings.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong(), readySettings.path("marketConfigVersion").asLong())
            assertEquals(settings.getValue("FUNDING_MARKET_CONFIG_SHA256"), readySettings.path("marketConfigSha256").asString())
            var position = 0L

            fun verify(
                response: VerificationCommandResult,
                expectedResult: Map<String, Any?>,
                stage: Int,
            ) {
                val events = events(response.stdout)
                assertEquals(listOf("TRANSPORT_ACCEPTED", "RESULT_RECEIVED"), events.map { it.path("kind").asString() })
                assertEquals(json(expectedResult), events.last().path("result"))
                val identityKeys =
                    if (expectedResult.containsKey(
                            "preparationId",
                        )
                    ) {
                        listOf("preparationId")
                    } else {
                        listOf("userId", "requestId", "marketId", "orderId")
                    }
                identityKeys.forEach { key -> assertEquals(expectedResult[key], events.first().path(key).asString()) }
                val observed = run.applied(node, expectedResult, position)
                assertEquals(json(expectedResult), observed.path("result"))
                assertEquals(json(expectedState(stage)), observed.path("state"))
                assertEquals(if (stage == 0) "FUNDING" else "ORDER", observed.path("inputKind").asString())
                assertEquals("U1", observed.path("userId").asString())
                assertEquals("LEADER", observed.path("role").asString())
                assertEquals("OFFERED", observed.path("replyStatus").asString())
                assertTrue(observed.path("failure").isNull)
                position = observed.path("position").asLong()
            }

            verify(run.fund(), opening(), 0)
            verify(run.order(BTC, select = false), accepted(BTC), 1)
            verify(run.order(SUI_LARGE), rejected(SUI_LARGE), 2)
            // 버전만 틀린 새 요청은 기록을 만들지 않으므로 같은 ID를 바로잡아 보낼 수 있다.
            verify(run.order(SUI_SMALL, version = "1"), rejected(SUI_SMALL, mismatch = true), 2)
            verify(run.order(SUI_SMALL), accepted(SUI_SMALL), 3)
        }
    }

    @Test
    fun `로컬 세 main도 전체 READY와 BTC 승인 SUI 부족 SUI 승인을 순서대로 출력한다`() {
        val settings = database.register(MARKETS)
        MainProcesses(structured = false, marketEnvironment = settings).use { run ->
            val node = run.node()
            val ready = run.localReady(node)
            assertTrue(ready.contains("markets=${listOf(market("BTC"), market("SUI"))}"), ready)
            assertTrue(ready.contains("marketConfigVersion=${settings.getValue("FUNDING_MARKET_CONFIG_VERSION")}"), ready)
            assertTrue(ready.contains("marketConfigSha256=${settings.getValue("FUNDING_MARKET_CONFIG_SHA256")}"), ready)
            val funding = run.fund().stdout
            assertLocalResult(funding, "Prepared(", "preparationId=P1", "1000000000")
            assertLocalAccepted(run.order(BTC, select = false).stdout, BTC)
            assertLocalResult(
                run.order(SUI_LARGE).stdout,
                "Rejected(userId=U1, requestId=R-SUI, marketId=SUI-USDT, orderId=O-SUI",
                "INSUFFICIENT_BALANCE",
                "400000000",
                "500000000",
                "100000000",
            )
            assertLocalAccepted(run.order(SUI_SMALL).stdout, SUI_SMALL)
        }
    }

    @Test
    fun `부모 환경의 마켓 설정을 제거하면 두 모드의 세 main 모두 기존 BTC 기본값으로 실행된다`() {
        listOf(false, true).forEach { structured ->
            MainProcesses(structured, marketEnvironment = emptyMap()).use { run ->
                val node = run.node()
                if (structured) {
                    val readySettings = run.ready(node).path("settings")
                    assertEquals(json(listOf(market("BTC"))), readySettings.path("markets"))
                    assertTrue(readySettings.path("marketConfigVersion").isNull)
                    assertTrue(readySettings.path("marketConfigSha256").isNull)
                    assertEquals(json(opening()), events(run.fund().stdout).last().path("result"))
                    val openingPosition = run.applied(node, opening(), 0).path("position").asLong()
                    assertEquals(json(accepted(BTC)), events(run.order(BTC, select = false).stdout).last().path("result"))
                    assertEquals(json(expectedState(1)), run.applied(node, accepted(BTC), openingPosition).path("state"))
                } else {
                    val ready = run.localReady(node)
                    assertTrue(ready.contains("markets=${listOf(market("BTC"))}"), ready)
                    assertFalse(ready.contains("SUI"), ready)
                    assertLocalResult(run.fund().stdout, "Prepared(", "preparationId=P1")
                    assertLocalAccepted(run.order(BTC, select = false).stdout, BTC)
                }
            }
        }
    }

    @Test
    fun `DB 설정 실패는 세 main의 두 모드에서 자원 생성과 연결보다 먼저 실패한다`() {
        val settings = database.register(MARKETS)
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val failures =
            listOf(
                settings + ("FUNDING_MARKET_CONFIG_VERSION" to Long.MAX_VALUE.toString()) to "FUNDING_MARKET_CONFIG_VERSION",
                settings + ("FUNDING_MARKET_CONFIG_SHA256" to "0".repeat(64)) to "FUNDING_MARKET_CONFIG_SHA256",
                settings - "FUNDING_MARKET_DB_PASSWORD" to "FUNDING_MARKET_DB_PASSWORD",
                settings + ("FUNDING_MARKET_DB_URL" to "jdbc:postgresql://127.0.0.1:$unavailablePort/unavailable") to
                    "FUNDING_MARKET_DB_URL",
                database.register("[]") to "마켓 배열",
                settings + ("FUNDING_MARKETS_JSON" to MARKETS) to "FUNDING_MARKETS_JSON",
            )
        failures.forEach { (environment, diagnostic) ->
            listOf(false, true).forEach { structured ->
                listOf(NODE, FUNDING, ORDER).forEach { entry ->
                    MainProcesses(structured, environment).use { run ->
                        val (process, resourceRoot) = run.invalidStart(entry)
                        assertInputFailure(process.await(10_000), diagnostic)
                        assertFalse(Files.exists(resourceRoot), "$entry structured=$structured $diagnostic 자원을 만들면 안 됩니다")
                    }
                }
            }
        }
    }

    @Test
    fun `잘못된 DB 주소도 세 main의 stdout과 stderr에 주소나 자격정보를 노출하지 않는다`() {
        val settings = database.environment(1, MARKETS)
        val secret = "market-url-secret"
        val invalidUrls =
            listOf(
                "jdbc:postgresql://127.0.0.1:1?password=$secret",
                "jdbc:postgresql://127.0.0.1:1/db/extra?password=$secret",
                "jdbc:postgresql://127.0.0.1:not-a-port/db?password=$secret",
                "jdbc:postgresql://127.0.0.1:1/db?sslmode=%ZZ&password=$secret",
            )
        invalidUrls.forEach { url ->
            val environment =
                settings +
                    mapOf(
                        "FUNDING_MARKET_DB_URL" to url,
                        "FUNDING_MARKET_DB_USER" to "market-private-user",
                        "FUNDING_MARKET_DB_PASSWORD" to "market-private-password",
                    )
            listOf(false, true).forEach { structured ->
                listOf(NODE, FUNDING, ORDER).forEach { entry ->
                    MainProcesses(structured, environment).use { run ->
                        val (process, resourceRoot) = run.invalidStart(entry)
                        val result = process.await(10_000)
                        assertInputFailure(result, "FUNDING_MARKET_DB_URL")
                        listOf("jdbc:postgresql", secret, "market-private-user", "market-private-password").forEach { value ->
                            assertFalse(result.stdout.contains(value), result.stdout)
                            assertFalse(result.stderr.contains(value), result.stderr)
                        }
                        assertFalse(Files.exists(resourceRoot))
                    }
                }
            }
        }
    }

    @Test
    fun `등록하지 않은 선택과 BTC 없는 기본 선택은 두 모드 모두 전송 전에 실패한다`() {
        listOf(false, true).forEach { structured ->
            listOf(MARKETS to listOf("--market", "DOGE-USDT"), SUI_ONLY to emptyList()).forEach { (markets, selection) ->
                MainProcesses(structured, database.register(markets)).use { run ->
                    val (process, resourceRoot) = run.invalidStart(ORDER, selection)
                    assertInputFailure(process.await(10_000), if (selection.isEmpty()) "BTC-USDT" else "DOGE-USDT")
                    assertFalse(Files.exists(resourceRoot), "잘못된 선택은 Driver를 만들면 안 됩니다")
                }
            }
        }
    }

    private fun assertInputFailure(
        result: VerificationCommandResult,
        diagnostic: String,
    ) {
        assertFalse(result.timedOut, result.failure())
        assertEquals(null, result.error, result.failure())
        assertEquals(1, result.exitCode, result.failure())
        assertTrue(result.stderr.contains(diagnostic), result.failure())
        listOf("READY", "TRANSPORT_ACCEPTED", "RESULT_RECEIVED", "UNCONFIRMED").forEach {
            assertFalse(result.stdout.contains(it), result.stdout)
        }
    }

    private fun assertLocalAccepted(
        output: String,
        order: ExpectedOrder,
    ) = assertLocalResult(
        output,
        "Accepted(userId=U1, requestId=${order.request}, marketId=${order.asset}-USDT, orderId=${order.id}, sequence=${order.sequence})",
    )

    private fun assertLocalResult(
        output: String,
        vararg expected: String,
    ) {
        val accepted = output.indexOf("TRANSPORT_ACCEPTED")
        val result = output.indexOf("RESULT_RECEIVED")
        assertTrue(accepted >= 0 && result > accepted, output)
        assertFalse(output.contains("UNCONFIRMED"), output)
        expected.forEach { assertTrue(output.substring(result).contains(it), output) }
    }

    /** 프로세스와 출력의 수명만 소유한다. Node main의 최신 관찰을 다음 요청 전에 읽는다. */
    private class MainProcesses(
        private val structured: Boolean,
        private val marketEnvironment: Map<String, String>,
    ) : AutoCloseable {
        private val root = Files.createTempDirectory("market-main115-")
        private val nodeRoot = root.resolve("node")
        private val nodePort = freePortBlock()
        private val members = "0,${(0..4).joinToString(",") { "127.0.0.1:${nodePort + it}" }}"
        private val commands = mutableListOf<VerificationCommands>()

        fun node(): RunningVerificationCommand = start(NODE, nodeRoot, nodePort, emptyList())

        fun fund(): VerificationCommandResult = successfulClient(FUNDING, fundingArguments())

        fun order(
            order: ExpectedOrder,
            select: Boolean = true,
            version: String? = null,
        ): VerificationCommandResult =
            successfulClient(
                ORDER,
                orderArguments(order) + listOfNotNull(version) + if (select) listOf("--market", "${order.asset}-USDT") else emptyList(),
            )

        fun invalidStart(
            entry: String,
            selection: List<String> = emptyList(),
        ): Pair<RunningVerificationCommand, Path> {
            val arguments =
                when (entry) {
                    FUNDING -> fundingArguments()
                    ORDER -> orderArguments(BTC) + selection
                    else -> emptyList()
                }
            return start(entry, nodeRoot, nodePort, arguments) to nodeRoot
        }

        fun ready(node: RunningVerificationCommand): JsonNode =
            awaitNode(node) { output -> events(output).firstOrNull { it.path("kind").asString() == "READY" } }

        fun localReady(node: RunningVerificationCommand): String =
            awaitNode(node) { output -> output.lineSequence().firstOrNull { it.startsWith("READY ") } }

        fun applied(
            node: RunningVerificationCommand,
            expected: Map<String, Any?>,
            previousPosition: Long,
        ): JsonNode =
            awaitNode(node) { output ->
                events(output).firstOrNull {
                    it.path("kind").asString() == "APPLIED" &&
                        it.path("position").asLong() > previousPosition &&
                        it.path("replyStatus").asString() == "OFFERED" &&
                        if (expected.containsKey("preparationId")) {
                            it.path("preparationId").asString() == expected["preparationId"]
                        } else {
                            it.path("requestId").asString() == expected["requestId"] &&
                                it.path("marketId").asString() == expected["marketId"] &&
                                it.path("orderId").asString() == expected["orderId"]
                        }
                }
            }

        private fun successfulClient(
            entry: String,
            arguments: List<String>,
        ): VerificationCommandResult {
            val resourceRoot = if (structured) root.resolve("client-${commands.size}") else nodeRoot
            val port = if (structured) freePortBlock() else nodePort
            val result = start(entry, resourceRoot, port, arguments).await(20_000)
            assertTrue(result.succeeded, result.failure() + "\n" + result.stdout)
            return result
        }

        private fun start(
            entry: String,
            resourceRoot: Path,
            port: Int,
            values: List<String>,
        ): RunningVerificationCommand {
            val environment =
                mapOf(
                    "FUNDING_RUN_ID" to "market-main115",
                    "FUNDING_RUN_ROOT" to resourceRoot.toString(),
                    "FUNDING_BASE_PORT" to port.toString(),
                    "FUNDING_MEMBER_ID" to "0",
                    "FUNDING_HOST" to "127.0.0.1",
                    "FUNDING_BIND_HOST" to "127.0.0.1",
                    "FUNDING_MEMBERS" to members,
                ) + marketEnvironment
            val reports = Files.createDirectory(root.resolve("logs-${commands.size}"))
            val runner = VerificationCommands(root, reports, environment)
            commands.add(runner)
            return runner.start(
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    assertNotNull(System.getProperty("developmentFundingRuntimeClasspath")),
                    "com.exchange.core.api.trading.infrastructure.aeron.$entry",
                ) + (if (structured) listOf("--cluster-env") else listOf(resourceRoot.toString(), port.toString())) + values,
            )
        }

        private fun <T : Any> awaitNode(
            node: RunningVerificationCommand,
            find: (String) -> T?,
        ): T {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val output = if (Files.exists(node.stdoutPath)) Files.readString(node.stdoutPath) else ""
                // 마지막 줄의 출력 중간을 JSON 하나로 해석하지 않는다.
                val complete = output.substringBeforeLast('\n', "")
                find(complete)?.let { return it }
                if (!node.isAlive) error("노드 조기 종료: ${node.await(1).failure()}\n$output")
                Thread.sleep(10)
            }
            error("실제 main의 READY 또는 해당 요청의 새 관찰 대기 초과: ${Files.readString(node.stdoutPath)}")
        }

        override fun close() {
            assertTrue(commands.flatMap { it.closeRunning() }.isEmpty(), "실행한 main 프로세스 종료를 확인해야 합니다: $root")
        }
    }

    /** 기대값은 응답이나 제품 계산기에서 가져오지 않고 명세의 금액·원본으로 만든다. */
    private fun expectedState(stage: Int): Map<String, Any?> {
        val pending =
            if (stage == 0) {
                emptyList()
            } else if (stage < 3) {
                listOf(record(BTC))
            } else {
                listOf(record(BTC), record(SUI_SMALL))
            }
        val requests =
            buildList {
                if (stage >= 1) add(request(BTC))
                if (stage >= 2) add(request(SUI_LARGE))
                if (stage >= 3) add(request(SUI_SMALL))
            }
        return mapOf(
            "balances" to
                listOf(
                    balance(
                        if (stage == 0) {
                            1_000_000_000
                        } else if (stage < 3) {
                            400_000_000
                        } else {
                            0
                        },
                    ),
                ),
            "preparationCount" to 1,
            "pendingOrderCount" to pending.size,
            "requestRecordCount" to requests.size,
            "lastSequence" to pending.size,
            "preparations" to listOf(opening()),
            "pendingOrders" to pending,
            "requests" to requests,
            "transactions" to
                listOf(
                    transaction("OPENING-1", "OPENING:P1", "OPENING", 1_000_000_000, OPENING_AT),
                ) + (if (stage >= 1) listOf(reserve(BTC)) else emptyList()) +
                (if (stage >= 3) listOf(reserve(SUI_SMALL)) else emptyList()),
        )
    }

    private fun request(order: ExpectedOrder): Map<String, Any?> =
        mapOf(
            "requestId" to order.request,
            "order" to orderFields(order),
            "market" to market(order.asset).minus("feePolicy"),
            "feePolicy" to policy(order.asset),
            "ledgerTransactionId" to "ledger-${order.request}",
            "result" to
                if (order.sequence > 0) {
                    mapOf("outcome" to "ACCEPTED", "record" to record(order))
                } else {
                    mapOf("outcome" to "REJECTED", "reason" to "INSUFFICIENT_BALANCE", "insufficientBalance" to shortage())
                },
        ) + instant(ORDER_AT)

    private fun record(order: ExpectedOrder): Map<String, Any?> =
        mapOf(
            "sequence" to order.sequence,
            "candidate" to
                mapOf(
                    "order" to orderFields(order),
                    "market" to market(order.asset).minus("feePolicy"),
                    "feePolicy" to policy(order.asset),
                    "previousBalance" to balance(if (order.asset == "BTC") 1_000_000_000 else 400_000_000),
                    "nextBalance" to balance(if (order.asset == "BTC") 400_000_000 else 0),
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
                            "feePolicy" to policy(order.asset),
                            "initialFeeReserveAmount" to 0,
                            "remainingFeeReserveAmount" to 0,
                            "status" to "ACTIVE",
                            "feeRemainder" to 0,
                            "releasedAmount" to null,
                        ),
                    "reserveTransaction" to reserve(order),
                ),
        )

    private fun reserve(order: ExpectedOrder): Map<String, Any?> =
        transaction("ledger-${order.request}", order.source, "RESERVE", order.reserve, ORDER_AT)

    private fun transaction(
        id: String,
        source: String,
        kind: String,
        amount: Long,
        time: String,
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
        ) + instant(time)

    private data class ExpectedOrder(
        val asset: String,
        val request: String,
        val id: String,
        val price: Long,
        val quantity: Long,
        val sequence: Int,
        val reserve: Long,
        val source: String,
    )

    companion object {
        private const val NODE = "DevelopmentFundingNodeKt"
        private const val FUNDING = "DevelopmentFundingClientKt"
        private const val ORDER = "DevelopmentOrderClientKt"
        private const val OPENING_AT = "2026-10-22T00:00:00Z"
        private const val ORDER_AT = "2026-10-22T00:01:00.123456789Z"
        private val mapper = JsonMapper.builder().build()
        private val BTC =
            ExpectedOrder(
                "BTC",
                "R-BTC",
                "O-BTC",
                100_000_000,
                600_000_000,
                1,
                600_000_000,
                "RESERVE:v1:018073f242abd9639ef45ee15937dc46ccac8474aaa7741930508b38a2a023d6",
            )
        private val SUI_LARGE = ExpectedOrder("SUI", "R-SUI", "O-SUI", 20_000_000, 25_000_000_000, 0, 500_000_000, "")
        private val SUI_SMALL =
            ExpectedOrder(
                "SUI",
                "R-SUI-2",
                "O-SUI-2",
                20_000_000,
                20_000_000_000,
                2,
                400_000_000,
                "RESERVE:v1:4743e36b7cefdefa470ba94427a4197aebf6519fe301aa6fdcf2fcf866a7354a",
            )

        // 입력 순서를 뒤집어도 READY는 BTC, SUI 순서이며 선택은 인덱스에 의존하지 않는다.
        private val MARKETS = mapper.writeValueAsString(listOf(market("SUI"), market("BTC")))
        private val SUI_ONLY = mapper.writeValueAsString(listOf(market("SUI")))

        private fun json(value: Any?): JsonNode = mapper.readTree(mapper.writeValueAsString(value))

        private fun events(output: String): List<JsonNode> =
            output
                .lineSequence()
                .filter {
                    it.startsWith(
                        "FUNDING_EVENT ",
                    )
                }.map { mapper.readTree(it.removePrefix("FUNDING_EVENT ")) }
                .toList()

        private fun market(asset: String): Map<String, Any?> =
            mapOf(
                "marketId" to "$asset-USDT",
                "baseAssetId" to asset,
                "quoteAssetId" to "USDT",
                "baseQuantityScale" to if (asset == "BTC") 8 else 9,
                "feePolicy" to policy(asset),
            )

        private fun policy(asset: String): Map<String, Any?> =
            mapOf(
                "productType" to "SPOT",
                "feeTier" to "NORMAL",
                "scheduleVersion" to if (asset == "BTC") 1 else 2,
                "makerPartsPerMillion" to 0,
                "takerPartsPerMillion" to 0,
            )

        private fun opening(): Map<String, Any?> =
            mapOf(
                "preparationId" to "P1",
                "userId" to "U1",
                "assetId" to "USDT",
                "amount" to 1_000_000_000,
                "ledgerTransactionId" to "OPENING-1",
                "occurredAt" to OPENING_AT,
                "alreadyPrepared" to false,
            )

        private fun accepted(order: ExpectedOrder): Map<String, Any?> =
            resultKey(order) + mapOf("outcome" to "ACCEPTED", "sequence" to order.sequence)

        private fun rejected(
            order: ExpectedOrder,
            mismatch: Boolean = false,
        ): Map<String, Any?> =
            resultKey(order) +
                mapOf(
                    "outcome" to "REJECTED",
                    "reason" to if (mismatch) "EXECUTION_CONTEXT_MISMATCH" else "INSUFFICIENT_BALANCE",
                    "insufficientBalance" to if (mismatch) null else shortage(),
                )

        private fun resultKey(order: ExpectedOrder): Map<String, Any?> =
            mapOf("userId" to "U1", "requestId" to order.request, "marketId" to "${order.asset}-USDT", "orderId" to order.id)

        private fun orderFields(order: ExpectedOrder): Map<String, Any?> =
            resultKey(order).minus("requestId") +
                mapOf("side" to "BUY", "orderType" to "LIMIT", "timeInForce" to "GTC", "price" to order.price, "quantity" to order.quantity)

        private fun balance(available: Long): Map<String, Any?> =
            mapOf("userId" to "U1", "assetId" to "USDT", "available" to available, "hold" to 1_000_000_000 - available)

        private fun shortage(): Map<String, Any?> =
            mapOf("assetId" to "USDT", "available" to 400_000_000, "required" to 500_000_000, "shortfall" to 100_000_000)

        private fun instant(value: String): Map<String, Any?> =
            mapOf(
                "occurredAt" to value,
                "occurredAtEpochSecond" to Instant.parse(value).epochSecond,
                "occurredAtNano" to Instant.parse(value).nano,
            )

        private fun fundingArguments(): List<String> = listOf("P1", "U1", "USDT", "1000000000", "OPENING-1", OPENING_AT)

        private fun orderArguments(order: ExpectedOrder): List<String> =
            listOf(
                order.request,
                "U1",
                order.id,
                "BUY",
                order.price.toString(),
                order.quantity.toString(),
                "ledger-${order.request}",
                ORDER_AT,
            )

        private fun freePortBlock(): Int {
            repeat(100) {
                val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
                val sockets = mutableListOf<DatagramSocket>()
                try {
                    (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                    return base
                } catch (_: java.net.BindException) {
                    // 연속 포트가 이미 사용 중이면 새로운 블록을 찾는다.
                } finally {
                    sockets.forEach { it.close() }
                }
            }
            error("실제 main용 연속 UDP 포트를 확보하지 못했습니다")
        }
    }
}

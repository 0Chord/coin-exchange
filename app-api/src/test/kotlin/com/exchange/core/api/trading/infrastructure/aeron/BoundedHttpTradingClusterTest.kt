package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 제품 HTTP main과 실제 wire3 세 노드를 검증한다. Docker 미준비도 실패로 남긴다. */
class BoundedHttpTradingClusterTest {
    @Test
    @Timeout(540)
    fun `실제 HTTP 주문의 체결 정산과 재전송 충돌을 세 bounded 노드에서 확인한다`() {
        BoundedHttpTradingVerification().use { it.run() }
    }
}

private class BoundedHttpTradingVerification : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val project = Path.of(requireNotNull(System.getProperty("fundingProjectDirectory")))
    private val distribution = Path.of(requireNotNull(System.getProperty("fundingDistributionDirectory")))
    private val runId = "http-bounded-${UUID.randomUUID().toString().take(8)}"
    private val image = "exchange-$runId:verification"
    private val report = project.resolve("build/reports/aeron-funding/http-bounded/$runId")
    private val control = report.resolve("control")
    private val environment =
        mapOf(
            "FUNDING_IMAGE" to image,
            "FUNDING_RUN_ID" to runId,
            "FUNDING_CONTROL_DIRECTORY" to control.toString(),
        )
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val containers = linkedMapOf<String, String>()
    private val inspections = linkedMapOf<String, JsonNode>()
    private val responses = linkedMapOf<String, JsonNode>()
    private val checkpoints = linkedMapOf<String, List<JsonNode>>()
    private val requests = mutableListOf<Map<String, Any?>>()
    private val commandRecords = mutableListOf<Map<String, Any?>>()
    private val cleanupFailures = mutableListOf<String>()
    private var sourceEvidence = emptyMap<String, Any?>()
    private var imageAttempted = false
    private var composeAttempted = false
    private var startupDeadline = Long.MAX_VALUE
    private var cleanupDeadline = Long.MAX_VALUE
    private var diagnosticDeadline = Long.MAX_VALUE
    private var failure: String? = null
    private var completed = false
    private var port = 0
    private var network = ""
    private lateinit var context: BoundedHttpCheckpointContext

    init {
        Files.createDirectories(control)
    }

    fun run() {
        try {
            check(Files.getFileStore(project).usableSpace >= 8L * 1024 * 1024 * 1024) { "호스트 여유 공간 8GiB 미만" }
            command(listOf("docker", "version"), 15_000)
            compose(listOf("version"), 15_000)
            sourceEvidence =
                mapOf(
                    "head" to command(listOf("git", "rev-parse", "HEAD"), 5_000).trim(),
                    "tree" to command(listOf("git", "rev-parse", "HEAD^{tree}"), 5_000).trim(),
                    "status" to command(listOf("git", "status", "--porcelain"), 5_000),
                    "sourceSha256" to sourceSha256(),
                )
            check(Files.isDirectory(distribution.resolve("lib"))) { "실제 실행 배포물이 없습니다: $distribution" }
            imageAttempted = true
            command(
                listOf("docker", "build", "-f", dockerFile("Dockerfile"), "-t", image, distribution.toString()),
                300_000,
            )
            startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
            setup("manifest")
            val manifest = mapper.readTree(Files.readString(control.resolve("manifest.json")))
            context = BoundedHttpCheckpointContext(runId, manifest.path("manifestSha256").asString())
            check(context.manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "동일 이미지에서 생성한 manifest 지문 누락" }

            composeAttempted = true
            compose(listOf("create", "node0", "node1", "node2", "gateway"), 30_000)
            for (service in listOf("node0", "node1", "node2", "gateway")) {
                containers[service] =
                    compose(listOf("ps", "--all", "--quiet", service), 10_000).trim().also {
                        check(it.matches(Regex("[0-9a-f]{64}"))) { "$service 컨테이너 식별자 누락" }
                    }
            }
            val initial = containers.mapValues { inspect(it.value) }
            network =
                initial
                    .getValue("node0")
                    .path("NetworkSettings")
                    .path("Networks")
                    .propertyNames()
                    .single()
            compose(listOf("up", "-d", "node0", "node1", "node2"), 60_000)
            await("세 노드 READY") { (0..2).all { ready(it) != null } }
            verifyReadiness()
            setup("prepare")
            awaitCapture("PREPARED", 0, 0).forEach {
                assertEquals("RUNNING", it.path("lifecycle").asString())
                assertEquals(2, it.path("balances").size(), "실제 준비 자금 두 계정")
            }

            compose(listOf("up", "-d", "gateway"), 30_000)
            val gateway = inspect(containers.getValue("gateway"))
            val binding =
                gateway
                    .path("NetworkSettings")
                    .path("Ports")
                    .path("8080/tcp")
                    .single()
            assertEquals("127.0.0.1", binding.path("HostIp").asString())
            port = binding.path("HostPort").asString().toInt()
            val profiles =
                gateway
                    .path("Config")
                    .path("Env")
                    .toList()
                    .map { it.asString() }
            assertTrue("SPRING_PROFILES_ACTIVE=aeron-bounded-gateway" in profiles)
            assertTrue("TRADING_GATEWAY_PROTOCOL=bounded" in profiles)
            await("제품 HTTP 서버 준비") {
                runCatching {
                    http
                        .send(
                            HttpRequest
                                .newBuilder(uri("/actuator/health"))
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.discarding(),
                        ).statusCode() == 200
                }.getOrDefault(false)
            }
            for (user in listOf("SELLER", "BUYER")) {
                val response = post("/api/trading/v3/channels/0/epoch", mapOf("userId" to user, "expectedEpoch" to 0), 200, "E1-$user")
                BoundedHttpTradingAssertions.verifyEpoch(response, user)
            }

            val sell = post(ORDER_PATH, order("SELLER", "SELL"), 202, "SELL")
            val sold = awaitCapture("SELL", position(sell), 1)
            BoundedHttpTradingAssertions.verifySell(sold, context, sell)
            checkpoints["SELL"] = sold
            val buy = post(ORDER_PATH, order("BUYER", "BUY"), 202, "BUY")
            val traded = awaitCapture("BUY", position(buy), 2)
            BoundedHttpTradingAssertions.verifyBuy(traded, context, sell, buy)
            checkpoints["BUY"] = traded
            val replay = post(ORDER_PATH, order("BUYER", "BUY"), 202, "REPLAY")
            val repeated = awaitCapture("REPLAY", position(replay), 2)
            BoundedHttpTradingAssertions.verifyReplay(repeated, context, traded, buy, replay)
            checkpoints["REPLAY"] = repeated
            val conflict = post(ORDER_PATH, order("BUYER", "BUY") + ("price" to 20_000L), 409, "CONFLICT")
            val conflicted = awaitCapture("CONFLICT", position(conflict), 2)
            BoundedHttpTradingAssertions.verifyConflict(conflicted, context, traded, replay, conflict)
            checkpoints["CONFLICT"] = conflicted
            BoundedHttpTradingAssertions.verifyComplete(checkpoints, responses, context)
            verifyReadiness()
            recordSize()
            completed = true
        } catch (error: Throwable) {
            failure = error.toString()
            throw error
        }
    }

    private fun order(
        user: String,
        side: String,
    ): Map<String, Any> =
        mapOf(
            "userId" to user,
            "channel" to 0,
            "epoch" to 1L,
            "sequence" to 1L,
            "expectedConfigurationVersion" to 1L,
            "side" to side,
            "orderType" to "LIMIT",
            "timeInForce" to "GTC",
            "price" to 10_000L,
            "quantity" to 100_000_000L,
        )

    private fun position(response: JsonNode): Long {
        val value = response.path("appliedLogPosition")
        check(value.isIntegralNumber && value.asLong() > 0) { "엔진 적용 위치 누락: $response" }
        return value.asLong()
    }

    private fun post(
        path: String,
        body: Any,
        status: Int,
        phase: String,
    ): JsonNode {
        val record =
            linkedMapOf<String, Any?>(
                "phase" to phase,
                "path" to path,
                "request" to body,
                "outcome" to "PENDING_HTTP_RESULT",
            )
        requests.add(record)
        val response =
            try {
                http.send(
                    HttpRequest
                        .newBuilder(uri(path))
                        .timeout(Duration.ofMillis(remaining(12_000)))
                        .header(
                            "Content-Type",
                            "application/json",
                        ).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (error: Exception) {
                record["outcome"] = "OUTCOME_UNKNOWN"
                record["transportFailure"] = error.toString()
                throw error
            }
        record["httpStatus"] = response.statusCode()
        record["body"] = response.body()
        record["outcome"] = "RESPONSE_RECEIVED"
        assertEquals(status, response.statusCode(), "$phase 실제 HTTP 응답: ${response.body()}")
        return mapper.readTree(response.body()).also { responses[phase] = it }
    }

    private fun uri(path: String) = URI.create("http://127.0.0.1:$port$path")

    private fun setup(action: String) {
        val name = "$runId-setup"
        val networkArguments = if (action == "prepare") listOf("--network", network, "--network-alias", "setup") else emptyList()
        command(
            listOf("docker", "run", "--rm", "--name", name, "--label", "http-bounded.run=$runId") + networkArguments +
                listOf(
                    "--memory",
                    "512m",
                    "-v",
                    "$control:/control",
                    "-e",
                    "JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m",
                    "-e",
                    "FUNDING_HOST=setup",
                    "-e",
                    "FUNDING_RUN_ID=$runId",
                    "-e",
                    "FUNDING_BOUNDED_MANIFEST=/control/manifest.bin",
                    "-e",
                    "FUNDING_RUN_ROOT=/tmp/$runId-setup",
                    "-e",
                    "FUNDING_TRUSTED_BOUNDED_CONTROL=true",
                    "-e",
                    "FUNDING_MEMBERS=$MEMBERS",
                    image,
                    "com.exchange.core.api.trading.infrastructure.aeron.BoundedHttpTradingSetupKt",
                    action,
                ),
            45_000,
        )
    }

    private fun ready(member: Int): JsonNode? {
        val logs = command(listOf("docker", "logs", "--tail", "100", containers.getValue("node$member")), 5_000)
        return logs
            .lineSequence()
            .filter { it.startsWith("HTTP_BOUNDED_EVENT ") }
            .map { mapper.readTree(it.removePrefix("HTTP_BOUNDED_EVENT ")) }
            .also { events -> events.forEach { check(it.path("kind").asString() != "ERROR") { it.toString() } } }
            .firstOrNull { it.path("kind").asString() == "READY" }
    }

    private fun verifyReadiness() {
        val nodes =
            (0..2).map { member ->
                val id = containers.getValue("node$member")
                val info = inspect(id)
                inspections["node$member"] = info
                assertTrue(info.path("State").path("Running").asBoolean(), "node$member JVM 실행")
                assertTrue(!info.path("State").path("OOMKilled").asBoolean())
                assertEquals(
                    2.0,
                    info
                        .path("HostConfig")
                        .path("NanoCpus")
                        .asLong()
                        .toDouble() / 1_000_000_000,
                )
                val ready = checkNotNull(ready(member))
                assertEquals(runId, ready.path("runId").asString())
                assertEquals(member, ready.path("memberId").asInt())
                assertEquals("bounded-wire3", ready.path("protocol").asString())
                assertEquals(listOf(2, 2, 2), ready.path("sync").toList().map { it.asInt() })
                assertEquals(context.manifestSha256, ready.path("manifestSha256").asString())
                val manifest = mapper.readTree(Files.readString(control.resolve("manifest.json")))
                for (field in listOf("artifactSha256", "schemaSha256", "lineage", "configurationVersion", "javaVersion", "javaVendor")) {
                    assertEquals(manifest.path(field), ready.path(field), "같은 이미지의 $field")
                }
                assertEquals(MEMBERS, ready.path("members").asString())
                assertTrue(ready.path("pid").isIntegralNumber && ready.path("pid").asLong() > 0)
                assertTrue(info.path("HostConfig").path("IpcMode").asString() != "host")
                assertTrue(info.path("HostConfig").path("NetworkMode").asString() != "host")
                assertTrue(
                    !info
                        .path("Mounts")
                        .single { it.path("Destination").asString() == "/manifest/manifest.bin" }
                        .path("RW")
                        .asBoolean(),
                )
                info
            }
        assertEquals(3, nodes.map { it.path("Id").asString() }.distinct().size)
        assertEquals(1, nodes.map { it.path("Image").asString() }.distinct().size)
        val volumes =
            nodes.map { node ->
                node
                    .path("Mounts")
                    .single { it.path("Destination").asString() == "/records" }
                    .path("Name")
                    .asString()
            }
        assertEquals(3, volumes.distinct().size, "각 노드의 실제 기록 볼륨")
        assertEquals(nodes.first().path("Image"), inspect(containers.getValue("gateway")).path("Image"))
    }

    private fun awaitCapture(
        phase: String,
        minimumPosition: Long,
        admissions: Long,
    ): List<JsonNode> {
        var result = emptyList<JsonNode>()
        var serial = 0
        await("$phase 세 노드 서비스 경계") {
            val attempt = "$phase-${serial++}"
            for (member in 0..2) {
                val file = control.resolve("node-$member.command")
                check(!Files.exists(file)) { "이전 CAPTURE가 소비되지 않았습니다" }
                val temporary = control.resolve("node-$member.tmp")
                Files.writeString(temporary, "CAPTURE:$attempt")
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
            }
            await("$attempt 관찰 파일") { (0..2).all { Files.isRegularFile(control.resolve("state-$it-$attempt.json")) } }
            result = (0..2).map { mapper.readTree(Files.readString(control.resolve("state-$it-$attempt.json"))) }
            for (state in result) {
                check(state.path("label").asString() == attempt && state.path("runId").asString() == runId) { "잘못된 관찰 응답: $state" }
                // 전송 label과 논리적 checkpoint를 함께 남긴다. 기존 응답 파일을 재사용하지 않는다.
                (state as tools.jackson.databind.node.ObjectNode).put("captureLabel", attempt).put("label", phase)
                check(state.path("observationFailure").isNull) { "실행 관찰 오류: $state" }
            }
            result.all {
                it.path("position").asLong() >= minimumPosition && it.path("lastAdmissionSequence").asLong() == admissions &&
                    it.path("pending").asInt() == 0
            }
        }
        assertEquals(1, result.map { it.path("role").asString() }.count { it == "LEADER" })
        assertEquals(2, result.map { it.path("role").asString() }.count { it == "FOLLOWER" })
        return result
    }

    private fun await(
        description: String,
        predicate: () -> Boolean,
    ) {
        do {
            check(System.nanoTime() < startupDeadline) { "$description 공통 기한 초과. 보고서: $report" }
            if (predicate()) return
            Thread.sleep(minOf(100, remaining(100)))
        } while (true)
    }

    private fun recordSize() {
        val bytes =
            (0..2).sumOf { member ->
                command(
                    listOf(
                        "docker",
                        "exec",
                        containers.getValue("node$member"),
                        "du",
                        "-sb",
                        "/records/$runId/archive",
                        "/records/$runId/cluster",
                    ),
                    5_000,
                ).lineSequence()
                    .filter(String::isNotBlank)
                    .sumOf { it.substringBefore('\t').trim().toLong() }
            }
        assertTrue(bytes <= 2L * 1024 * 1024 * 1024, "기록 합계 2GiB 상한: $bytes")
        Files.writeString(report.resolve("record-bytes.txt"), "$bytes\n")
    }

    private fun inspect(id: String): JsonNode =
        mapper
            .readTree(
                command(
                    listOf(
                        "docker",
                        "inspect",
                        "--format",
                        """[{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},"HostConfig":{"NanoCpus":{{json .HostConfig.NanoCpus}},"Memory":{{json .HostConfig.Memory}},"NetworkMode":{{json .HostConfig.NetworkMode}},"IpcMode":{{json .HostConfig.IpcMode}}},"Config":{"Env":{{json .Config.Env}},"Cmd":{{json .Config.Cmd}}},"Mounts":{{json .Mounts}},"NetworkSettings":{{json .NetworkSettings}}}]""",
                        id,
                    ),
                    5_000,
                ),
            ).path(0)

    private fun sourceSha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val files = command(listOf("git", "-C", project.parent.toString(), "ls-files", "--cached", "--others", "--exclude-standard"), 5_000)
        for (name in files.lineSequence().filter(String::isNotBlank).sorted()) {
            val file = project.parent.resolve(name)
            if (!Files.isRegularFile(file)) continue
            digest.update(name.toByteArray())
            digest.update(0)
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dockerFile(name: String) = project.resolve("src/test/docker/aeron-funding/$name").toString()

    private fun compose(
        arguments: List<String>,
        timeoutMs: Long,
    ): String =
        command(
            listOf(
                "docker",
                "compose",
                "--project-name",
                runId,
                "--file",
                dockerFile("compose.yml"),
                "--file",
                dockerFile("http-gateway.compose.yml"),
                "--file",
                dockerFile("bounded-http.compose.yml"),
            ) + arguments,
            timeoutMs,
        )

    private fun remaining(maximumMs: Long): Long {
        val deadline = minOf(startupDeadline, cleanupDeadline, diagnosticDeadline)
        if (deadline == Long.MAX_VALUE) return maximumMs
        val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        check(left > 0) { "공통 실행 기한 초과. 보고서: $report" }
        return minOf(maximumMs, left)
    }

    /** CLI 출력도 유한하게 보관하고, 제한 초과·시간 초과를 정상 성공으로 바꾸지 않는다. */
    private fun command(
        arguments: List<String>,
        timeoutMs: Long,
    ): String {
        val index = commandRecords.size
        val out = report.resolve("cli-$index.stdout.log")
        val err = report.resolve("cli-$index.stderr.log")
        val record =
            linkedMapOf<String, Any?>(
                "arguments" to arguments,
                "stdout" to out.fileName.toString(),
                "stderr" to err.fileName.toString(),
            )
        commandRecords.add(record)
        val builder = ProcessBuilder(arguments).directory(project.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile())
        DEVELOPMENT_MARKET_ENVIRONMENT_KEYS.forEach { builder.environment().remove(it) }
        builder.environment().putAll(environment)
        val budget = remaining(timeoutMs)
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(budget)
        val process = builder.start()
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                check(System.nanoTime() < deadline) { "CLI 기한 초과: $arguments" }
                check(Files.size(out) <= MAX_CLI_BYTES && Files.size(err) <= MAX_CLI_BYTES) { "CLI 출력 상한 초과: $arguments" }
            }
            record["exitCode"] = process.exitValue()
            check(Files.size(out) <= MAX_CLI_BYTES && Files.size(err) <= MAX_CLI_BYTES) { "CLI 출력 상한 초과: $arguments" }
            check(process.exitValue() == 0) { "CLI 실패: $arguments\n${Files.readString(err).takeLast(4000)}" }
            return Files.readString(out)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(1, TimeUnit.SECONDS)) { "CLI 종료 미확인: $arguments" }
            }
            record["durationMs"] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }
    }

    override fun close() {
        startupDeadline = Long.MAX_VALUE
        cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        diagnosticDeadline = cleanupDeadline - TimeUnit.SECONDS.toNanos(20)

        fun attempt(
            label: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (error: Throwable) {
                cleanupFailures.add("$label: $error")
            }
        }
        // 로그 회수 실패와 무관하게 준비 도구와 모든 JVM·기록 볼륨을 정리한다.
        if (composeAttempted) {
            attempt("JVM 종료") { compose(listOf("stop", "--timeout", "3", "gateway", "node0", "node1", "node2"), 7_000) }
            for ((service, id) in containers) {
                attempt("$service 최종 진단") {
                    val info = inspect(id)
                    inspections[service] = info
                    Files.writeString(report.resolve("$service.stdout.log"), command(listOf("docker", "logs", "--tail", "500", id), 2_000))
                    assertTrue(!info.path("State").path("Running").asBoolean(), "$service 종료 미확인")
                    assertTrue(!info.path("State").path("OOMKilled").asBoolean(), "$service OOM")
                    assertTrue(info.path("State").path("ExitCode").asInt() in setOf(0, 143), "$service 비정상 종료")
                }
            }
        }
        // 진단 출력이 지연돼도 자원 제거에 별도 20초를 남긴다.
        diagnosticDeadline = Long.MAX_VALUE
        attempt("준비 도구 제거") {
            val ids =
                command(listOf("docker", "ps", "--all", "--quiet", "--filter", "label=http-bounded.run=$runId"), 2_000)
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .toList()
            if (ids.isNotEmpty()) command(listOf("docker", "rm", "--force") + ids, 2_000)
        }
        if (composeAttempted) {
            attempt("실행 자원 제거") { compose(listOf("down", "--volumes", "--remove-orphans", "--timeout", "1"), 8_000) }
            for ((kind, label) in listOf("container" to "컨테이너", "volume" to "볼륨", "network" to "네트워크")) {
                attempt("$label 제거 확인") {
                    assertTrue(
                        command(
                            listOf("docker", kind, "ls", "--quiet", "--filter", "label=com.docker.compose.project=$runId"),
                            2_000,
                        ).isBlank(),
                    )
                }
            }
        }
        if (imageAttempted) attempt("이번 실행 이미지 제거") { command(listOf("docker", "image", "rm", image), 3_000) }
        attempt("HTTP client 종료") {
            http.shutdownNow()
            check(http.awaitTermination(Duration.ofSeconds(1))) { "HTTP client 종료 미확인" }
        }
        val result =
            mapOf(
                "runId" to runId,
                "success" to (completed && failure == null && cleanupFailures.isEmpty()),
                "failure" to failure,
                "source" to sourceEvidence,
                "cleanupFailures" to cleanupFailures,
                "recordVolumesDeleted" to (composeAttempted && cleanupFailures.isEmpty()),
                "requests" to requests,
                "checkpoints" to checkpoints,
                "inspections" to inspections,
                "commands" to commandRecords,
                "scope" to "actual HTTP v3, LOCAL_QUORUM, three Docker JVMs, sync 2/2/2; no auth, ledger history or performance claim",
            )
        Files.writeString(report.resolve("result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result))
        val reportBytes = Files.walk(report).use { paths -> paths.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
        check(reportBytes <= 16L * 1024 * 1024) { "진단물 16MiB 상한 초과: $reportBytes" }
        check(cleanupFailures.isEmpty()) { "정리 실패: $cleanupFailures. 보고서: $report" }
    }

    companion object {
        private const val MAX_CLI_BYTES = 1_048_576L
        private const val ORDER_PATH = "/api/trading/v3/markets/BTC-USDT/orders"
        private const val MEMBERS =
            "0,node0:19700,node0:19701,node0:19702,node0:19703,node0:19704|" +
                "1,node1:19700,node1:19701,node1:19702,node1:19703,node1:19704|" +
                "2,node2:19700,node2:19701,node2:19702,node2:19703,node2:19704"
    }
}

package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.BoundedAeronClient
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentMarketContext
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentMarketRegistry
import com.exchange.core.api.trading.infrastructure.aeron.TradingEngineProbeWorkload
import com.exchange.core.api.trading.infrastructure.aeron.haBoundedProbeManifest
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.Quantity
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.TradingClusterManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Four real JVMs and four roots. This is not a claim of physically separate regional storage. */
@EnabledIfEnvironmentVariable(named = "RUN_REMOTE_TRADING_RECOVERY_TESTS", matches = "true")
class RemoteTradingRecoveryTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    @Timeout(value = 420, unit = TimeUnit.SECONDS)
    fun `only the fourth forced recovery set survives all original primary processes and volumes`() {
        val manifest = haBoundedProbeManifest()
        val workload = TradingEngineProbeWorkload
        val base = availablePorts()
        val runId = "remote-${UUID.randomUUID().toString().take(8)}"
        val members =
            (0..2).joinToString(
                "|",
            ) { member -> "$member," + (0..4).joinToString(",") { "127.0.0.1:${base + member * 16 + it}" } }
        val control = directory.resolve("control").also { Files.createDirectories(it) }
        val roots = (0..2).map { directory.resolve("node-$it") }
        val outputs = (0..3).associateWith { directory.resolve("node-$it.stdout.log") }
        val errors = (0..3).associateWith { directory.resolve("node-$it.stderr.log") }
        val processes = mutableListOf<Process>()
        var backup: Process? = null
        val classpath = requireNotNull(System.getProperty("fundingVerificationRuntimeClasspath"))
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val reportRoot =
            Path
                .of(
                    requireNotNull(System.getProperty("fundingProjectDirectory")),
                ).resolve("build/reports/remote-trading-recovery/$runId")
        Files.createDirectories(reportRoot)
        val checks = linkedMapOf<String, Any?>()

        fun launchNode(
            member: Int,
            root: Path,
            port: Int,
            configuredMembers: String,
            recover: Boolean,
            runtimeClasspath: String,
        ): Process {
            val builder =
                ProcessBuilder(
                    javaExecutable,
                    "-Xms128m",
                    "-Xmx512m",
                    "-Drecovery.expected.root=$root",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    runtimeClasspath,
                    "com.exchange.core.api.trading.infrastructure.aeron.BoundedHaTradingNodeKt",
                ).redirectOutput(checkNotNull(outputs[member]).toFile())
                    .redirectError(checkNotNull(errors[member]).toFile())
            builder.environment().putAll(
                mapOf(
                    "FUNDING_RUN_ROOT" to root.toString(),
                    "FUNDING_MEMBER_ID" to "${if (member == 3) 0 else member}",
                    "FUNDING_BASE_PORT" to port.toString(),
                    "FUNDING_HOST" to "127.0.0.1",
                    "FUNDING_BIND_HOST" to "127.0.0.1",
                    "FUNDING_MEMBERS" to configuredMembers,
                    "FUNDING_RUN_ID" to runId,
                    "FUNDING_RECOVER_EXISTING" to recover.toString(),
                    "HA_PROBE_CONTROL" to control.toString(),
                ),
            )
            builder.environment().remove("HA_PROBE_MARKER_ENDPOINT")
            return builder.start().also { processes.add(it) }
        }

        fun waitReady(
            member: Int,
            process: Process,
        ) = eventually(65_000) {
            check(process.isAlive) { "node $member failed: ${Files.readString(checkNotNull(errors[member])).takeLast(6000)}" }
            Files.exists(checkNotNull(outputs[member])) && Files.readString(checkNotNull(outputs[member])).contains("\"kind\":\"HA_READY\"")
        }

        fun command(
            member: Int,
            action: String,
            label: String,
        ) {
            val file = control.resolve("node-$member.command")
            val temporary = file.resolveSibling(file.fileName.toString() + ".new")
            eventually(10_000) { !Files.exists(file) }
            Files.writeString(temporary, "$action:$label")
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            eventually(10_000) { !Files.exists(file) }
        }
        var serial = 0

        fun capture(
            membersToCapture: List<Int>,
            admissions: Long,
            label: String,
        ): List<JsonNode> {
            var result = emptyList<JsonNode>()
            eventually(30_000) {
                val unique = "$label-${serial++}"
                membersToCapture.forEach { command(it, "CAPTURE", unique) }
                eventually(10_000) { membersToCapture.all { Files.exists(control.resolve("state-$it-$unique.json")) } }
                result = membersToCapture.map { mapper.readTree(Files.readString(control.resolve("state-$it-$unique.json"))) }
                result.all { it.path("lastAdmissionSequence").asLong() == admissions && it.path("pending").asInt() == 0 } &&
                    result.map { it.path("stateSha256").asString() }.distinct().size == 1
            }
            return result
        }
        val clientRoot = directory.resolve("client").also { Files.createDirectory(it) }
        val clientSettings =
            DevelopmentFundingSettings(
                clientRoot,
                base,
                startupTimeoutMs = 60_000,
                resultTimeoutMs = 30_000,
                clusterMembers = members,
                runId = runId,
            )
        val backupRoot = directory.resolve("remote-fourth")
        val market = workload.markets.first()
        val intent = OrderIntent(market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, workload.price, workload.quantity(market))
        val token = RequestToken(workload.buyer, 0, 1, 1)
        try {
            val originals = (0..2).map { launchNode(it, roots[it], base + it * 16, members, false, classpath) }
            originals.forEachIndexed(::waitReady)
            val fence = PrimaryProcessFence.capture(originals.zip(roots))
            var originalReceipt: com.exchange.core.trading.AdmissionReceipt? = null
            BoundedAeronClient(clientSettings).use { client ->
                assertEquals("INITIALIZED", client.initialize(manifest).code)
                assertEquals(
                    "PREPARED",
                    client
                        .fund(
                            DevelopmentBalanceCommandPayload(
                                OpeningBalance("buyer", workload.buyer, workload.quote, Amount(1_000_000)),
                                "opening-buyer",
                                workload.time,
                            ),
                        ).code,
                )
                assertEquals(
                    "PREPARED",
                    client
                        .fund(
                            DevelopmentBalanceCommandPayload(
                                OpeningBalance("seller", workload.seller, market.baseAssetId, Amount(300_000_000)),
                                "opening-seller",
                                workload.time,
                            ),
                        ).code,
                )
                assertEquals("ISSUED", client.rotate(workload.seller, 0, 0).code)
                assertEquals("ISSUED", client.rotate(workload.buyer, 0, 0).code)
                assertEquals(
                    AdmissionCode.ACCEPTED,
                    client
                        .admit(
                            RequestToken(workload.seller, 0, 1, 1),
                            intent.copy(side = Side.SELL, quantity = Quantity(300_000_000)),
                        ).admission
                        ?.code,
                )
                originalReceipt = client.admit(token, intent).admission
                assertEquals(AdmissionCode.ACCEPTED, originalReceipt?.code)
            }
            val before = capture(listOf(0, 1, 2), 2, "before")
            val leader = before.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            command(leader, "SNAPSHOT", "remote-checkpoint")
            eventually(30_000) { capture(listOf(0, 1, 2), 2, "snapshot").all { it.path("snapshotCount").asLong() > 0 } }
            val manifestPath =
                directory
                    .resolve(
                        "manifest.bin",
                    ).also { Files.write(it, BoundedTradingStateCodec().encodeManifest(manifest)) }
            val backupOutput = directory.resolve("backup.stdout.log")
            val backupError = directory.resolve("backup.stderr.log")
            backup =
                ProcessBuilder(
                    javaExecutable,
                    "-Xms128m",
                    "-Xmx512m",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    RemoteAeronBackupMain::class.java.name,
                    "--run-root",
                    backupRoot.toString(),
                    "--manifest",
                    manifestPath.toString(),
                    "--consensus-endpoints",
                    (0..2).joinToString(",") { "127.0.0.1:${base + it * 16 + 1}" },
                    "--base-port",
                    (base + 64).toString(),
                    "--authority-epoch",
                    "1",
                ).redirectOutput(backupOutput.toFile())
                    .redirectError(backupError.toFile())
                    .start()
                    .also { processes.add(it) }
            eventually(90_000) {
                check(checkNotNull(backup).isAlive) { "backup failed: ${Files.readString(backupError).takeLast(6000)}" }
                try {
                    RemoteRecoveryFiles.read(backupRoot)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val frozenSha = RemoteRecoveryFiles.sha256(BoundedTradingStateCodec().encodeManifest(manifest))
            FileRecoveryDurabilityGate(backupRoot, manifest.lineage, 1, frozenSha, timeoutMs = 30_000).use { gate ->
                BoundedAeronClient(clientSettings).use { client ->
                    val reply = client.admit(token.copy(sequence = 2), intent)
                    assertEquals(AdmissionCode.ACCEPTED, reply.admission?.code)
                    gate.awaitProtected(manifest.lineage, reply.appliedLogPosition).toCompletableFuture().get(35, TimeUnit.SECONDS)
                    checks["admissionPublicSuccessWaitedForRemotePrefix"] = true
                    val completed = capture(listOf(0, 1, 2), 3, "completed")
                    assertEquals("60", completed.first().path("feeRevenue").asString())
                    // A committed progress request after pending=0 is a causal barrier after execution.
                    val completionBarrier = client.progress().appliedLogPosition
                    gate.awaitProtected(manifest.lineage, completionBarrier).toCompletableFuture().get(35, TimeUnit.SECONDS)
                    checks["completionPublicSuccessWaitedForRemotePrefix"] = true
                    Files.writeString(reportRoot.resolve("before-primary-loss.json"), mapper.writeValueAsString(completed))
                    val expectedSha = completed.first().path("stateSha256").asString()
                    originals.forEach {
                        it.destroyForcibly()
                        assertTrue(it.waitFor(5, TimeUnit.SECONDS))
                    }
                    roots.forEach(::removeTree)
                    assertTrue(roots.none { Files.exists(it) })
                    checks["allOriginalProcessesStoppedAndAllThreeRootsRemoved"] = true
                    checkNotNull(backup).destroy()
                    if (!checkNotNull(backup).waitFor(35, TimeUnit.SECONDS)) {
                        checkNotNull(backup).destroyForcibly()
                        assertTrue(checkNotNull(backup).waitFor(5, TimeUnit.SECONDS))
                    }
                    val targetRoot = directory.resolve("promoted-from-fourth-only")
                    val targetBase = base + 80
                    val target =
                        DevelopmentFundingSettings(
                            targetRoot,
                            targetBase,
                            startupTimeoutMs = 60_000,
                            resultTimeoutMs = 30_000,
                            markets = DevelopmentMarketRegistry(workload.markets.map { DevelopmentMarketContext(it, workload.policy) }),
                            runId = runId,
                            boundedManifest = manifest,
                            trustedBoundedControl = true,
                        )
                    val restored = RemoteRecoveryPromotion.prepare(backupRoot, target, fence)
                    assertTrue(restored.recoverExisting)
                    val promoted =
                        launchNode(3, targetRoot, targetBase, restored.members, true, RetainedExecutionBundle.classPath(backupRoot))
                    waitReady(3, promoted)
                    // Same completed target is idempotent, even while it runs. Another root cannot fork this authority.
                    assertEquals(restored, RemoteRecoveryPromotion.prepare(backupRoot, target, fence))
                    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                        RemoteRecoveryPromotion.prepare(
                            backupRoot,
                            target.copy(runRoot = directory.resolve("forbidden-second-promotion")),
                            fence,
                        )
                    }
                    assertTrue(!Files.exists(directory.resolve("forbidden-second-promotion")))
                    val changedVersion =
                        TradingClusterManifest(
                            manifest.lineage,
                            manifest.algorithmId,
                            manifest.executionArtifactSha256,
                            manifest.schemaSha256,
                            manifest.configurationVersion + 1,
                            manifest.markets,
                            manifest.limits,
                        )
                    val changedLimits =
                        TradingClusterManifest(
                            manifest.lineage,
                            manifest.algorithmId,
                            manifest.executionArtifactSha256,
                            manifest.schemaSha256,
                            manifest.configurationVersion,
                            manifest.markets,
                            manifest.limits.copy(
                                maxInboxBytes =
                                    manifest.limits.maxInboxBytes + 1,
                            ),
                        )
                    for (different in listOf(changedVersion, changedLimits)) {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                            RemoteRecoveryPromotion.prepare(backupRoot, target.copy(boundedManifest = different), fence)
                        }
                    }
                    checks["durableAuthorityConsumedOnceAndSameTargetRetrySafe"] = true
                    val recovered = capture(listOf(0), 3, "recovered-fourth")
                    assertEquals(expectedSha, recovered.first().path("stateSha256").asString())
                    assertEquals("60", recovered.first().path("feeRevenue").asString())
                    checks["fourthOnlySnapshotAndTailFinancialStateExact"] = true
                    BoundedAeronClient(target.copy(runRoot = clientRoot)).use { recoveredClient ->
                        assertEquals(originalReceipt, recoveredClient.admit(token, intent).admission)
                        assertEquals(AdmissionCode.ACCEPTED, recoveredClient.admit(token.copy(sequence = 3), intent).admission?.code)
                    }
                    val after = capture(listOf(0), 4, "after-retry-and-late-maker-fill")
                    assertEquals("90", after.first().path("feeRevenue").asString())
                    assertEquals(0, after.first().path("liveOrders").asInt())
                    checks["oldReceiptNoDoubleReservationAndActiveMakerLateFill"] = true
                    Files.writeString(reportRoot.resolve("recovered.json"), mapper.writeValueAsString(after))
                }
            }
        } catch (failure: Throwable) {
            checks["failure"] = "${failure.javaClass.name}: ${failure.message?.take(2_000)}"
            throw failure
        } finally {
            processes.forEach { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(3, TimeUnit.SECONDS)
                    }
                }
            }
            if (checks.containsKey("failure")) preserveLogTails(reportRoot)
            Files.writeString(reportRoot.resolve("summary.json"), mapper.writeValueAsString(checks))
            // Heavy artifacts remain test-temporary; report contains compact evidence only.
        }
    }

    private fun eventually(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() - deadline < 0) { "remote recovery condition timed out" }
            Thread.sleep(50)
        }
    }

    private fun removeTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun preserveLogTails(reportRoot: Path) {
        Files.list(directory).use { logs ->
            logs
                .filter {
                    Files.isRegularFile(it) &&
                        (it.fileName.toString().endsWith(".stderr.log") || it.fileName.toString().endsWith(".stdout.log"))
                }.forEach { source ->
                    FileChannel.open(source, READ).use { channel ->
                        val size = minOf(channel.size(), 16_384).toInt()
                        val tail = ByteBuffer.allocate(size)
                        channel.position(channel.size() - size)
                        while (tail.hasRemaining() && channel.read(tail) >= 0) { }
                        Files.write(reportRoot.resolve(source.fileName.toString() + ".tail"), tail.array())
                    }
                }
        }
    }

    private fun availablePorts(): Int {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceIn(1024, 65390)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (group in listOf(0, 16, 32, 64, 80)) for (offset in 0..7) sockets.add(DatagramSocket(base + group + offset))
                return base
            } catch (
                _: java.net.BindException,
            ) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("five isolated port ranges unavailable")
    }
}

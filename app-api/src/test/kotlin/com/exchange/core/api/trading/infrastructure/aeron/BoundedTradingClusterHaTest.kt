package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.DatagramSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 명시적으로 선택해 실행하는 실제 프로세스 장애 테스트이며 제품 코드에 테스트 게이트를 설치하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_HA_TRADING_FAULT_TESTS", matches = "true")
class BoundedTradingClusterHaTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    fun `독립 세 JVM은 리더 종료와 Snapshot tail 전체 재기동 뒤 같은 자금과 최초 결과를 유지한다`() {
        val manifest = haBoundedProbeManifest()
        val workload = TradingEngineProbeWorkload
        val processes = arrayOfNulls<Process>(3)
        val boots = IntArray(3)
        val output = arrayOfNulls<Path>(3)
        val base = availablePorts()
        val runId = "ha-fault-${UUID.randomUUID().toString().take(8)}"
        val members =
            (0..2).joinToString("|") { member ->
                "$member," + (0..4).joinToString(",") { "127.0.0.1:${base + member * 16 + it}" }
            }
        val control = directory.resolve("control").also { Files.createDirectories(it) }
        val report =
            Path
                .of(requireNotNull(System.getProperty("fundingProjectDirectory")))
                .resolve("build/reports/ha-trading-faults/$runId")
                .also { Files.createDirectories(it) }
        val classpath = requireNotNull(System.getProperty("fundingVerificationRuntimeClasspath"))
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val checks = linkedMapOf<String, Any?>()
        val persistentRoots = (0..2).map { directory.resolve("node-$it") }
        val persistentRootKeys = arrayOfNulls<Any>(3)
        val persistentIdentities = arrayOfNulls<ByteArray>(3)
        val driverParents = Array(3) { mutableListOf<Path>() }
        val driverBoots = mutableListOf<MutableMap<String, Any?>>()
        val currentBoot = arrayOfNulls<MutableMap<String, Any?>>(3)

        fun rootKey(root: Path): Any =
            checkNotNull(Files.readAttributes(root, BasicFileAttributes::class.java).fileKey()) {
                "the HA fixture requires stable filesystem identity for its persistent root"
            }

        fun assertPersistentRoot(member: Int) {
            val root = persistentRoots[member]
            assertEquals(checkNotNull(persistentRootKeys[member]), rootKey(root))
            assertContentEquals(checkNotNull(persistentIdentities[member]), Files.readAllBytes(root.resolve("recovery-identity.bin")))
            for (file in listOf(root.resolve("archive/archive.catalog"), root.resolve("cluster/recording.log"))) {
                assertTrue(Files.isRegularFile(file) && Files.size(file) > 0, "existing durable recording must remain: $file")
            }
        }

        fun start(
            member: Int,
            recover: Boolean,
            mismatchIdentity: Boolean = false,
            trustedControl: Boolean = true,
        ) {
            val boot = boots[member]++
            val root = persistentRoots[member]
            val previousDriverParent = driverParents[member].lastOrNull()
            if (recover) {
                assertPersistentRoot(member)
                assertFalse(processes[member]?.isAlive == true, "remove transport files only after that JVM has exited")
                val previous = checkNotNull(previousDriverParent)
                assertEquals(directory, previous.parent)
                assertFalse(root.startsWith(previous))
                // 이 파일들은 종료된 JVM의 폐기 가능한 전송 계층에만 속한다.
                // Archive, 클러스터 메타데이터와 복구 식별자는 삭제하거나 다시 쓰지 않는다.
                if (Files.exists(previous)) {
                    Files.walk(previous).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
                assertFalse(Files.exists(previous), "the old Driver must be absent before recovery starts")
                assertPersistentRoot(member)
            }
            val driverParent = Files.createDirectory(directory.resolve("driver-$member-$boot"))
            driverParents[member].add(driverParent)
            val evidence =
                linkedMapOf<String, Any?>(
                    "member" to member,
                    "boot" to boot,
                    "recoverExisting" to recover,
                    "persistentRoot" to root.toString(),
                    "driverRoot" to driverParent.toString(),
                    "previousDriverRoot" to previousDriverParent?.toString(),
                    "previousDriverAbsent" to (previousDriverParent == null || !Files.exists(previousDriverParent)),
                    "ready" to false,
                )
            currentBoot[member] = evidence
            driverBoots.add(evidence)
            val stdout = directory.resolve("node-$member-$boot.stdout.log")
            val stderr = directory.resolve("node-$member-$boot.stderr.log")
            output[member] = stdout
            val builder =
                ProcessBuilder(
                    javaExecutable,
                    "-Xms128m",
                    "-Xmx512m",
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    "com.exchange.core.api.trading.infrastructure.aeron.BoundedHaTradingNodeKt",
                ).redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
            builder.environment().putAll(
                mapOf(
                    "FUNDING_RUN_ROOT" to root.toString(),
                    "FUNDING_DRIVER_ROOT" to driverParent.toString(),
                    "FUNDING_MEMBER_ID" to member.toString(),
                    "FUNDING_BASE_PORT" to (base + member * 16).toString(),
                    "FUNDING_HOST" to "127.0.0.1",
                    "FUNDING_BIND_HOST" to "127.0.0.1",
                    "FUNDING_MEMBERS" to members,
                    "FUNDING_RUN_ID" to runId,
                    "FUNDING_RECOVER_EXISTING" to recover.toString(),
                    "FUNDING_TRUSTED_BOUNDED_CONTROL" to trustedControl.toString(),
                    "HA_PROBE_CONTROL" to control.toString(),
                    "HA_PROBE_FAULTS" to "true",
                    "HA_PROBE_IDENTITY_MISMATCH" to mismatchIdentity.toString(),
                ),
            )
            builder.environment().remove("HA_PROBE_MARKER_ENDPOINT")
            processes[member] = builder.start()
        }

        fun rejectChangedControl(member: Int) {
            val root = persistentRoots[member]
            val before = Files.walk(root).use { it.toList().toSet() }
            start(member, true, trustedControl = false)
            val process = checkNotNull(processes[member])
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "제어 설정이 다른 노드는 기동 전에 종료되어야 한다")
            assertTrue(process.exitValue() != 0)
            assertFalse(Files.readString(checkNotNull(output[member])).contains("\"kind\":\"HA_READY\""))
            val error = Files.readString(directory.resolve("node-$member-${boots[member] - 1}.stderr.log"))
            assertTrue(error.contains("복구 설정"), error.takeLast(4000))
            assertEquals(before, Files.walk(root).use { it.toList().toSet() })
            assertPersistentRoot(member)
            processes[member] = null
        }

        fun waitReady(membersToWait: List<Int>) {
            eventually(65_000) {
                membersToWait.all { member ->
                    val process = checkNotNull(processes[member])
                    check(process.isAlive) {
                        "member $member failed: ${Files.readString(
                            directory.resolve("node-$member-${boots[member] - 1}.stderr.log"),
                        ).takeLast(4000)}"
                    }
                    Files.exists(checkNotNull(output[member])) &&
                        Files.readString(checkNotNull(output[member])).contains("\"kind\":\"HA_READY\"")
                }
            }
            for (member in membersToWait) {
                val root = persistentRoots[member]
                val evidence = checkNotNull(currentBoot[member])
                val readyLine =
                    Files.readAllLines(checkNotNull(output[member])).first {
                        it.startsWith("FUNDING_EVENT ") && it.contains("\"kind\":\"HA_READY\"")
                    }
                val ready = mapper.readTree(readyLine.removePrefix("FUNDING_EVENT "))
                val actualDriver = Path.of(ready.path("driver").asString())
                assertEquals(driverParents[member].last(), actualDriver.parent)
                assertFalse(actualDriver.startsWith(root))
                assertTrue(Files.isRegularFile(actualDriver.resolve("cnc.dat")), "READY must refer to the actual external Driver")
                assertEquals(listOf(2, 2, 2), ready.path("sync").values().map { it.asInt() })
                driverBoots.lastOrNull { it["member"] == member && it["ready"] == true }?.let { previous ->
                    assertNotEquals(previous["driverDirectory"], actualDriver.toString())
                }
                if (persistentRootKeys[member] == null) {
                    persistentRootKeys[member] = rootKey(root)
                    persistentIdentities[member] = Files.readAllBytes(root.resolve("recovery-identity.bin"))
                }
                assertPersistentRoot(member)
                evidence["driverDirectory"] = actualDriver.toString()
                evidence["persistentRootFileKey"] = rootKey(root).toString()
                evidence["recoveryIdentitySha256"] = haProbeSha(checkNotNull(persistentIdentities[member]))
                evidence["storageLevels"] = listOf(2, 2, 2)
                evidence["ready"] = true
            }
        }

        fun command(
            member: Int,
            action: String,
            label: String,
        ) {
            val file = control.resolve("node-$member.command")
            val temporary = control.resolve("node-$member.command.tmp")
            eventually(10_000) { !Files.exists(file) }
            Files.writeString(temporary, "$action:$label")
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            eventually(10_000) { !Files.exists(file) }
        }
        var captureSerial = 0

        fun capture(
            membersToCapture: List<Int>,
            expectedAdmissions: Long,
            name: String,
        ): List<JsonNode> {
            var result = emptyList<JsonNode>()
            eventually(30_000) {
                val label = "$name-${captureSerial++}"
                membersToCapture.forEach { command(it, "CAPTURE", label) }
                eventually(10_000) { membersToCapture.all { Files.exists(control.resolve("state-$it-$label.json")) } }
                result = membersToCapture.map { mapper.readTree(Files.readString(control.resolve("state-$it-$label.json"))) }
                result.all { it.path("lastAdmissionSequence").asLong() == expectedAdmissions && it.path("pending").asInt() == 0 } &&
                    result.map { it.path("stateSha256").asString() }.distinct().size == 1
            }
            Files.writeString(report.resolve("$name.json"), mapper.writeValueAsString(result))
            return result
        }

        fun kill(member: Int) {
            processes[member]?.let {
                it.destroyForcibly()
                check(it.waitFor(5, TimeUnit.SECONDS))
            }
            processes[member] = null
        }
        val clientRoot = directory.resolve("client").also { Files.createDirectories(it) }
        val settings =
            DevelopmentFundingSettings(
                clientRoot,
                base,
                startupTimeoutMs = 60_000,
                resultTimeoutMs = 30_000,
                advertisedHost = "127.0.0.1",
                bindHost = "127.0.0.1",
                clusterMembers = members,
                runId = runId,
            )
        val market = workload.markets.first()
        val buyToken = RequestToken(workload.buyer, 0, 1, 1)
        val buyIntent =
            OrderIntent(market.marketId, 1, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, workload.price, workload.quantity(market))
        var firstReceipt: com.exchange.core.trading.AdmissionReceipt? = null
        try {
            repeat(3) { start(it, false) }
            waitReady(listOf(0, 1, 2))
            BoundedAeronClient(settings).use { client ->
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
                            buyIntent.copy(side = Side.SELL, quantity = Quantity(300_000_000)),
                        ).admission
                        ?.code,
                )
                firstReceipt = client.admit(buyToken, buyIntent).admission
                assertEquals(AdmissionCode.ACCEPTED, firstReceipt?.code)
            }
            // H03의 첫 분기에서는 의도적으로 Snapshot을 실행하지 않는다. 세 프로세스 모두
            // 최초 기록부터 Init, 자금 준비, maker와 정산을 재구성해야 한다.
            val genesis = capture(listOf(0, 1, 2), 2, "before-genesis-only-restart")
            assertTrue(genesis.all { it.path("snapshotCount").asLong() == 0L })
            repeat(3, ::kill)
            rejectChangedControl(0)
            checks["changedControlRejectedBeforeGenesisReplay"] = true
            Thread.sleep(10_500)
            repeat(3) { start(it, true) }
            waitReady(listOf(0, 1, 2))
            val before = capture(listOf(0, 1, 2), 2, "genesis-only-recovered")
            assertTrue(before.all { it.path("snapshotCount").asLong() == 0L })
            assertEquals(genesis.first().path("stateSha256").asString(), before.first().path("stateSha256").asString())
            BoundedAeronClient(settings).use { client -> assertEquals(firstReceipt, client.admit(buyToken, buyIntent).admission) }
            val afterGenesisRetry = capture(listOf(0, 1, 2), 2, "genesis-only-same-receipt")
            assertEquals(before.first().path("stateSha256").asString(), afterGenesisRetry.first().path("stateSha256").asString())
            checks["H03GenesisLogOnlyAllProcessRestart"] = true
            val leader = before.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            assertEquals("30", before.first().path("feeRevenue").asString())
            assertEquals(1, before.first().path("liveOrders").asInt())
            kill(leader)
            eventually(30_000) {
                capture((0..2).filter { it != leader }, 2, "electing-survivors").count { it.path("role").asString() == "LEADER" } == 1
            }
            BoundedAeronClient(settings).use { client -> assertEquals(firstReceipt, client.admit(buyToken, buyIntent).admission) }
            val survivors = capture((0..2).filter { it != leader }, 2, "after-leader-kill")
            assertEquals(before.first().path("stateSha256").asString(), survivors.first().path("stateSha256").asString())
            checks["leaderCrashAndDuplicateReservation"] = true
            // 기존 디렉터리를 재사용하기 전에 Aeron이 종료된 Driver의 생존 신호 만료를 감지해야 한다.
            Thread.sleep(10_500)
            start(leader, true)
            waitReady(listOf(leader))
            val joined = capture(listOf(0, 1, 2), 2, "rejoined")
            assertEquals(before.first().path("stateSha256").asString(), joined.first().path("stateSha256").asString())
            checks["originalVolumeFollowerRejoin"] = true

            // H01: 세 사본 모두 확정된 접수를 적용했지만 실제 Cluster에 이 Execute를
            // 아직 위임하지 않았다. 클라이언트도 아직 응답을 받지 않았다.
            val pendingLeader = joined.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            val admissionArms = (0..2).map { control.resolve("pause-before-execution-offer-$it") }
            admissionArms.forEach { Files.writeString(it, "${workload.buyer.value}:2") }
            var secondReceipt: com.exchange.core.trading.AdmissionReceipt? = null
            val correlation = 90_001L
            var preOfferResponseReceived = false
            BoundedAeronClient(settings, onResponse = {
                if (it.correlationId ==
                    correlation
                ) {
                    preOfferResponseReceived = true
                }
            }).use { client ->
                eventually(10_000) {
                    val offered = client.offer(correlation, BoundedTradingCommand.Admit(buyToken.copy(sequence = 2), buyIntent))
                    client.poll()
                    offered > 0
                }
                val pendingSignals = (0..2).map { control.resolve("paused-before-execution-offer-$it.json") }
                eventually(10_000) {
                    client.poll()
                    assertTrue(processes.all { it?.isAlive == true })
                    pendingSignals.all { Files.exists(it) }
                }
                val pendingEvidence = pendingSignals.map { mapper.readTree(Files.readString(it)) }
                assertTrue(!preOfferResponseReceived, "the original request outcome must still be unknown at the pre-offer barrier")
                assertEquals(pendingLeader, pendingEvidence.single { it.path("role").asString() == "LEADER" }.path("member").asInt())
                assertEquals(1, pendingEvidence.map { it.path("position").asLong() }.distinct().size)
                assertEquals(1, pendingEvidence.map { it.path("stateSha256").asString() }.distinct().size)
                assertEquals(1, pendingEvidence.map { it.path("executionBodySha256").asString() }.distinct().size)
                assertEquals(1, pendingEvidence.map { it.path("receipt").toString() }.distinct().size)
                pendingEvidence.forEach {
                    assertEquals("ADMISSION_COMMITTED_EXECUTION_NOT_OFFERED", it.path("kind").asString())
                    assertEquals(0, it.path("executionOfferDelegations").asInt())
                    assertEquals(1, it.path("pending").asInt())
                    assertEquals(2L, it.path("marketExecutedThrough").asLong())
                    assertEquals(1_000_000L - 2 * workload.RESERVE_PER_BUY, it.path("buyerAvailable").asLong())
                    assertEquals(workload.RESERVE_PER_BUY, it.path("buyerHold").asLong())
                    assertEquals("30", it.path("feeRevenue").asString())
                }
                val recorded = pendingEvidence.first().path("receipt")
                secondReceipt =
                    com.exchange.core.trading.AdmissionReceipt(
                        AdmissionCode.valueOf(recorded.path("code").asString()),
                        OrderId(recorded.path("orderId").asString()),
                        recorded.path("admissionSequence").asLong(),
                        recorded.path("marketAdmissionSequence").asLong(),
                    )
                assertEquals(AdmissionCode.ACCEPTED, secondReceipt?.code)
                Files.writeString(report.resolve("H01-before-execution-offer.json"), mapper.writeValueAsString(pendingEvidence))
                kill(pendingLeader)
                // 선출을 기다리기 전에 해제해 살아남은 서비스 콜백이 반환한 뒤
                // 원래의 Execute를 변경 없이 전달할 수 있어야 한다.
                admissionArms.forEach { Files.deleteIfExists(it) }
            }
            eventually(30_000) {
                capture((0..2).filter { it != pendingLeader }, 3, "pending-electing-survivors").count {
                    it.path("role").asString() ==
                        "LEADER"
                } ==
                    1
            }
            BoundedAeronClient(settings).use { client ->
                assertEquals(secondReceipt, client.admit(buyToken.copy(sequence = 2), buyIntent).admission)
            }
            val pendingSurvivors = capture((0..2).filter { it != pendingLeader }, 3, "pending-leader-survivors")
            assertEquals("60", pendingSurvivors.first().path("feeRevenue").asString())
            val buyerQuote =
                pendingSurvivors.first().path("balances").first {
                    it[0].asString() == workload.buyer.value &&
                        it[1].asString() == workload.quote.value
                }
            assertEquals(1_000_000L - 2 * workload.RESERVE_PER_BUY, buyerQuote[2].asLong())
            assertEquals(0L, buyerQuote[3].asLong())
            val buyerBase =
                pendingSurvivors.first().path("balances").first {
                    it[0].asString() == workload.buyer.value &&
                        it[1].asString() == market.baseAssetId.value
                }
            assertEquals(2 * workload.quantity(market).value, buyerBase[2].asLong())
            Thread.sleep(10_500)
            start(pendingLeader, true)
            waitReady(listOf(pendingLeader))
            val afterPendingCrash = capture(listOf(0, 1, 2), 3, "pending-leader-recovered")
            assertEquals(pendingSurvivors.first().path("stateSha256").asString(), afterPendingCrash.first().path("stateSha256").asString())
            checks["H01CommittedAdmissionBeforeAnyExecutionOfferCrash"] = true

            val currentLeader = afterPendingCrash.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            command(currentLeader, "SNAPSHOT", "checkpoint")
            // 래퍼는 토글 성공 여부뿐 아니라 실제 ConsensusModule 스냅샷 카운터를 노출한다.
            eventually(30_000) {
                capture(listOf(0, 1, 2), 3, "snapshot-complete").all { it.path("snapshotCount").asLong() > 0 }
            }

            // H05: 실제 bounded 스냅샷의 앞부분을 발행하고 Archive RecordingPos를 기다린 뒤
            // 서비스 스냅샷 콜백에서 반환하거나 END를 쓰기 전에 종료한다.
            val completeSnapshotState = capture(listOf(0, 1, 2), 3, "before-partial-snapshot")
            val snapshotLeader = completeSnapshotState.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            val snapshotArm = control.resolve("pause-snapshot-$snapshotLeader")
            Files.writeString(snapshotArm, "armed")
            command(snapshotLeader, "SNAPSHOT", "partial")
            val partialSignal = control.resolve("paused-snapshot-$snapshotLeader.json")
            eventually(15_000) { Files.exists(partialSignal) }
            val partialEvidence = mapper.readTree(Files.readString(partialSignal))
            assertTrue(partialEvidence.path("partialBytes").asInt() > 0)
            assertTrue(partialEvidence.path("partialBytes").asInt() < partialEvidence.path("completeBodyBytes").asInt())
            assertTrue(partialEvidence.path("recordedThrough").asLong() >= partialEvidence.path("publicationPosition").asLong())
            Files.copy(partialSignal, report.resolve("H05-partial-publication.json"))
            kill(snapshotLeader)
            Files.delete(snapshotArm)
            capture((0..2).filter { it != snapshotLeader }, 3, "partial-snapshot-survivors")
            Thread.sleep(10_500)
            start(snapshotLeader, true)
            waitReady(listOf(snapshotLeader))
            val afterPartial = capture(listOf(0, 1, 2), 3, "partial-snapshot-recovered")
            assertEquals(completeSnapshotState.first().path("stateSha256").asString(), afterPartial.first().path("stateSha256").asString())
            checks["H05PartialSnapshotNeverBecomesRecoveryState"] = true

            // H02의 공개 응답은 접수증이다. wire3에서 Execute 자체는 응답하지 않는다.
            // 리더가 접수증을 전달하기 직전에 멈춘 동안 두 팔로워가 모두
            // 해당 Execute의 확정과 적용을 입증한다.
            val replyLeader = afterPartial.single { it.path("role").asString() == "LEADER" }.path("member").asInt()
            val replyArm = control.resolve("pause-before-admission-reply-$replyLeader")
            Files.writeString(replyArm, "${workload.buyer.value}:3")
            val replySignal = control.resolve("paused-before-admission-reply-$replyLeader.json")
            val replyCorrelation = 90_002L
            var thirdResponseReceived = false
            var thirdReceipt: com.exchange.core.trading.AdmissionReceipt? = null
            var completedBeforeReply = emptyList<JsonNode>()
            BoundedAeronClient(settings, onResponse = {
                if (it.correlationId ==
                    replyCorrelation
                ) {
                    thirdResponseReceived = true
                }
            }).use { client ->
                eventually(10_000) {
                    val offered = client.offer(replyCorrelation, BoundedTradingCommand.Admit(buyToken.copy(sequence = 3), buyIntent))
                    client.poll()
                    offered > 0
                }
                eventually(10_000) {
                    client.poll()
                    Files.exists(replySignal)
                }
                val replyEvidence = mapper.readTree(Files.readString(replySignal))
                assertEquals("EXECUTION_OFFERED_ADMISSION_REPLY_NOT_OFFERED", replyEvidence.path("kind").asString())
                assertEquals(0, replyEvidence.path("admissionReplyDelegations").asInt())
                assertTrue(replyEvidence.path("executionOfferDelegations").asInt() > 0)
                val recorded = replyEvidence.path("receipt")
                thirdReceipt =
                    com.exchange.core.trading.AdmissionReceipt(
                        AdmissionCode.valueOf(recorded.path("code").asString()),
                        OrderId(recorded.path("orderId").asString()),
                        recorded.path("admissionSequence").asLong(),
                        recorded.path("marketAdmissionSequence").asLong(),
                    )
                completedBeforeReply = capture((0..2).filter { it != replyLeader }, 4, "H02-completed-before-public-reply")
                completedBeforeReply.forEach {
                    assertTrue(it.path("position").asLong() > replyEvidence.path("admissionPosition").asLong())
                    assertEquals("90", it.path("feeRevenue").asString())
                    assertEquals(0, it.path("liveOrders").asInt())
                }
                client.poll()
                assertTrue(!thirdResponseReceived, "the admission receipt must not be published before leader termination")
                Files.copy(replySignal, report.resolve("H02-before-admission-reply.json"))
                kill(replyLeader)
                Files.delete(replyArm)
            }
            eventually(30_000) {
                capture(
                    (0..2).filter { it != replyLeader },
                    4,
                    "H02-electing-survivors",
                ).count { it.path("role").asString() == "LEADER" } ==
                    1
            }
            BoundedAeronClient(settings).use { client ->
                assertEquals(thirdReceipt, client.admit(buyToken.copy(sequence = 3), buyIntent).admission)
            }
            val repliedSurvivors = capture((0..2).filter { it != replyLeader }, 4, "H02-same-receipt-after-crash")
            assertEquals(
                completedBeforeReply.first().path("stateSha256").asString(),
                repliedSurvivors.first().path("stateSha256").asString(),
            )
            Thread.sleep(10_500)
            start(replyLeader, true)
            waitReady(listOf(replyLeader))
            val tail = capture(listOf(0, 1, 2), 4, "after-snapshot-tail")
            assertEquals(completedBeforeReply.first().path("stateSha256").asString(), tail.first().path("stateSha256").asString())
            assertEquals("90", tail.first().path("feeRevenue").asString())
            checks["H02ExecutionCommittedBeforePublicAdmissionReceiptCrash"] = true
            repeat(3, ::kill)
            rejectChangedControl(0)
            checks["changedControlRejectedBeforeSnapshotTailReplay"] = true
            Thread.sleep(10_500)
            repeat(3) { start(it, true) }
            waitReady(listOf(0, 1, 2))
            val recovered = capture(listOf(0, 1, 2), 4, "all-processes-recovered")
            assertEquals(tail.first().path("stateSha256").asString(), recovered.first().path("stateSha256").asString())
            BoundedAeronClient(settings).use { client ->
                assertEquals(firstReceipt, client.admit(buyToken, buyIntent).admission)
                assertEquals(thirdReceipt, client.admit(buyToken.copy(sequence = 3), buyIntent).admission)
            }
            val done = capture(listOf(0, 1, 2), 4, "final")
            assertEquals(0, done.first().path("liveOrders").asInt())
            assertEquals("90", done.first().path("feeRevenue").asString())
            checks["snapshotAndCommittedTailRestart"] = true
            checks["oldReceiptAndActiveMakerSurviveRestart"] = true
            // 기존 Driver 파일이 모두 없는 상태에서 모든 멤버가 원래 영속 디렉터리로부터
            // 최초 기록만 있는 입력과 Snapshot+tail 입력을 모두 복구했다.
            for (member in 0..2) {
                val readyBoots = driverBoots.filter { it["member"] == member && it["ready"] == true }
                assertTrue(readyBoots.size >= 3)
                assertEquals(readyBoots.size, readyBoots.map { it["driverDirectory"] }.distinct().size)
                assertEquals(1, readyBoots.map { it["persistentRootFileKey"] }.distinct().size)
                assertEquals(1, readyBoots.map { it["recoveryIdentitySha256"] }.distinct().size)
                assertTrue(readyBoots.filter { it["recoverExisting"] == true }.all { it["previousDriverAbsent"] == true })
                assertPersistentRoot(member)
            }
            checks["H09ThreeBoundedJvmsRecoverWithoutPreviousDriverFiles"] = true

            // H06은 완전한 스냅샷이 있는 실제 영속 디렉터리를 사용한다. 메모리 안의 코덱 왕복이 아니며
            // 계보가 다르면 READY 이벤트가 하나라도 발생하기 전에 실패해야 한다.
            val mismatchMember = done.first { it.path("role").asString() != "LEADER" }.path("member").asInt()
            kill(mismatchMember)
            Thread.sleep(10_500)
            start(mismatchMember, true, mismatchIdentity = true)
            val mismatched = checkNotNull(processes[mismatchMember])
            assertTrue(mismatched.waitFor(30, TimeUnit.SECONDS), "mismatched root did not fail startup")
            assertTrue(mismatched.exitValue() != 0)
            val mismatchOutput = Files.readString(checkNotNull(output[mismatchMember]))
            assertTrue(!mismatchOutput.contains("\"kind\":\"HA_READY\""))
            val mismatchError = Files.readString(directory.resolve("node-$mismatchMember-${boots[mismatchMember] - 1}.stderr.log"))
            assertTrue(mismatchError.contains("복구") || mismatchError.contains("identity"), mismatchError.takeLast(4000))
            Files.writeString(report.resolve("H06-identity-startup.stderr.log"), mismatchError)
            processes[mismatchMember] = null
            val unaffected = capture((0..2).filter { it != mismatchMember }, 4, "identity-mismatch-survivors")
            assertEquals(done.first().path("stateSha256").asString(), unaffected.first().path("stateSha256").asString())
            checks["H06MismatchedSnapshotRootNeverBecomesReady"] = true
        } finally {
            for (member in 0..2) {
                processes[member]?.let { process ->
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(3, TimeUnit.SECONDS)
                    }
                }
            }
            Files.writeString(report.resolve("H09-driver-boots.json"), mapper.writeValueAsString(driverBoots))
            Files.writeString(report.resolve("summary.json"), mapper.writeValueAsString(checks))
            if (checks["snapshotAndCommittedTailRestart"] != true) {
                Files.list(directory).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".log") }.forEach { log ->
                        Files.writeString(report.resolve(log.fileName), Files.readString(log).takeLast(16_384))
                    }
                }
            }
        }
    }

    private fun eventually(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() - deadline < 0) { "fault test condition timed out" }
            Thread.sleep(50)
        }
    }

    private fun availablePorts(): Int {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceIn(1024, 65480)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (member in 0..2) for (offset in 0..7) sockets.add(DatagramSocket(base + member * 16 + offset))
                return base
            } catch (_: java.net.BindException) {
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("three isolated node port ranges unavailable")
    }
}

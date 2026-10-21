package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceRejectionReason
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import io.aeron.Aeron
import io.aeron.cluster.ClusterControl
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.AeronEvent
import io.aeron.exceptions.AeronException
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.errors.ErrorLogReader
import java.net.DatagramSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 실제 MediaDriver·Archive·Consensus·서비스와 UDP 클라이언트. DB·MockMvc는 사용하지 않는다. */
class DevelopmentFundingNodeTest {
    @Test
    fun `Driver 경고는 실제 오류 기록에 남기고 다음 자금 준비를 처리한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            val driver = driver(node)
            val warning = AeronEvent("could not re-resolve: endpoint=closed-client:19707")

            driver.context().errorHandler().onError(warning)

            assertNull(node.error)
            assertFalse(node.isClosed)
            val recorded = mutableListOf<String>()
            ErrorLogReader.read(driver.context().errorLog().buffer()) { _, _, _, encoded -> recorded.add(encoded) }
            assertTrue(recorded.any { it.contains("WARN - could not re-resolve: endpoint=closed-client:19707") })
            DevelopmentFundingClient(config).use { client ->
                val response = assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                assertEquals(
                    1_000_000_000L,
                    assertIs<DevelopmentBalanceResultPayload.Prepared>(response.result)
                        .result.opening.amount.value,
                )
                assertEquals(
                    1,
                    node.observation!!
                        .snapshot!!
                        .preparations.size,
                )
            }
            assertNull(node.error)
        }
    }

    @Test
    fun `Driver의 ERROR FATAL 일반 예외는 실제 노드를 중단한다`() {
        listOf(
            AeronEvent("테스트 ERROR", AeronException.Category.ERROR),
            AeronEvent("테스트 FATAL", AeronException.Category.FATAL),
            IllegalStateException("테스트 일반 예외"),
        ).forEach { failure ->
            DevelopmentFundingNode.start(config()).use { node ->
                driver(node).context().errorHandler().onError(failure)

                assertTrue(node.awaitClosed(5_000), "경고가 아닌 오류는 런타임을 닫아야 한다")
                assertTrue(node.isClosed)
                assertSame(failure, node.error)
            }
        }
    }

    @Test
    fun `실제 통신으로 준비한 1000 USDT는 같은 요청을 다시 보내도 한 번만 지급된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            DevelopmentFundingClient(config).use { client ->
                val command = command()
                val first = assertIs<FundingRequestOutcome.Confirmed>(client.request(command))
                assertFalse(assertIs<DevelopmentBalanceResultPayload.Prepared>(first.result).result.alreadyPrepared)
                val repeat =
                    assertIs<FundingRequestOutcome.Confirmed>(
                        client.request(command.copy(ledgerTransactionId = "unused", occurredAt = Instant.MAX)),
                    )
                val result = assertIs<DevelopmentBalanceResultPayload.Prepared>(repeat.result).result
                assertTrue(result.alreadyPrepared)
                assertEquals("L1", result.ledgerTransactionId)
                assertEquals(command.occurredAt, result.occurredAt)
                val snapshot = assertNotNull(node.observation?.snapshot)
                assertEquals(
                    1_000_000_000L,
                    snapshot.balances
                        .single()
                        .available.value,
                )
                assertEquals(
                    0L,
                    snapshot.balances
                        .single()
                        .hold.value,
                )
                assertEquals(1, snapshot.preparations.size)
                assertEquals(
                    2,
                    snapshot.preparations
                        .single()
                        .transaction.postings.size,
                )
                assertTrue(assertNotNull(node.observation).position > 0)
                assertEquals(listOf(2, 2, 2), node.storageLevels)
                val recordings = node.recordings()
                assertTrue(recordings.isNotEmpty())
                assertTrue(recordings.all { it.archiveId == 97L && it.recordingId >= 0 })
            }
        }
    }

    @Test
    fun `실제 노드를 닫아도 아직 출력하지 않은 두 적용이 남는다`() {
        val config = config()
        val collector = FundingVerificationCollector()
        val node =
            DevelopmentFundingNode.start(config) { service ->
                assertFalse(service.started)
                collector.decorate(service)
            }
        node.use {
            val ready = fundingReadyFields(config, node)
            assertFalse((ready.getValue("settings") as Map<*, *>).containsKey("observationCapacity"))
            val testReady = fundingVerificationReadyFields(config, node, collector)
            assertEquals(16, (testReady.getValue("settings") as Map<*, *>)["observationCapacity"])
            DevelopmentFundingClient(config).use { client ->
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command("P2", "U2", "L2")))
            }
        }

        assertTrue(node.isClosed)
        val retained = collector.drainObservations()
        assertEquals(2, retained.size)
        assertTrue(retained[0].position > 0 && retained[1].position > retained[0].position)
        assertEquals(listOf("U1"), retained[0].snapshot!!.balances.map { it.userId.value })
        assertEquals(listOf("U1", "U2"), retained[1].snapshot!!.balances.map { it.userId.value })
        assertTrue(collector.drainObservations().isEmpty())
        assertNull(collector.verificationFailure)
        assertNull(node.error)
    }

    @Test
    fun `수집 버퍼 초과 뒤에도 실제 노드는 다음 자금 준비와 정상 응답을 유지한다`() {
        val config = config()
        val collector = FundingVerificationCollector(1)
        DevelopmentFundingNode.start(config, collector::decorate).use { node ->
            DevelopmentFundingClient(config).use { client ->
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command("P2", "U2", "L2")))
                eventually { collector.verificationFailure != null }
                val failure = collector.verificationFailure
                assertTrue(failure!!.contains("관찰 버퍼 초과"))
                assertEquals(1, collector.drainObservations().size)

                assertIs<FundingRequestOutcome.Confirmed>(client.request(command("P3", "U3", "L3")))

                assertEquals(
                    3,
                    node.observation!!
                        .snapshot!!
                        .preparations.size,
                )
                assertSame(failure, collector.verificationFailure)
                assertNull(node.error)
                assertFalse(node.isClosed)
            }
        }
    }

    @Test
    fun `전송 후 실제 반영을 관찰해도 클라이언트가 읽기 전에는 완료가 아니다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            DevelopmentFundingClient(config).use { client ->
                assertTrue(client.send(command()))
                eventually {
                    node.observation
                        ?.snapshot
                        ?.preparations
                        ?.size == 1
                }
                assertEquals(
                    1_000_000_000L,
                    node.observation!!
                        .snapshot!!
                        .balances
                        .single()
                        .available.value,
                )
                // 여기까지 클라이언트는 pollEgress를 호출하지 않았다. 이제 실제 결과를 읽는다.
                val outcome = assertIs<FundingRequestOutcome.Confirmed>(client.awaitResult())
                assertEquals(command().opening, assertIs<DevelopmentBalanceResultPayload.Prepared>(outcome.result).result.opening)
            }
        }
    }

    @Test
    fun `실제 응답만 버려 대기가 끝나도 같은 준비 ID로 최초 결과를 확인한다`() {
        val config = config().copy(resultTimeoutMs = 100)
        DevelopmentFundingNode.start(config).use { node ->
            var allowResponse = false
            // 통제 지점은 수신 결과의 유실뿐이다. 요청·합의·코어·응답 전송은 실제로 실행한다.
            DevelopmentFundingClient(config, acceptResponse = { allowResponse }).use { client ->
                val unknown = assertIs<FundingRequestOutcome.Unconfirmed>(client.request(command()))
                assertTrue(unknown.transportAccepted)
                assertTrue(unknown.reason.contains("결과 미확인"))
                assertEquals(
                    1,
                    node.observation!!
                        .snapshot!!
                        .preparations.size,
                )
                allowResponse = true
                val outcome = assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                val replay = assertIs<DevelopmentBalanceResultPayload.Prepared>(outcome.result).result
                assertTrue(replay.alreadyPrepared)
                assertEquals("L1", replay.ledgerTransactionId)
                assertEquals(
                    1_000_000_000L,
                    node.observation!!
                        .snapshot!!
                        .balances
                        .single()
                        .available.value,
                )
                assertEquals(
                    1,
                    node.observation!!
                        .snapshot!!
                        .preparations.size,
                )
            }
        }
    }

    @Test
    fun `두 정상 충돌과 손상 미지원 메시지 뒤에도 다른 사용자의 준비를 처리한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            DevelopmentFundingClient(config).use { client ->
                assertIs<FundingRequestOutcome.Confirmed>(client.request(command()))
                val changed = command().copy(opening = command().opening.copy(amount = Amount(2_000_000_000)))
                val conflict = assertIs<FundingRequestOutcome.Confirmed>(client.request(changed))
                assertEquals(
                    DevelopmentBalanceRejectionReason.REQUEST_CONTENT_CONFLICT,
                    assertIs<DevelopmentBalanceResultPayload.Rejected>(conflict.result).reason,
                )
                val used = assertIs<FundingRequestOutcome.Confirmed>(client.request(command("P2")))
                assertEquals(
                    DevelopmentBalanceRejectionReason.ACCOUNT_ALREADY_USED,
                    assertIs<DevelopmentBalanceResultPayload.Rejected>(used.result).reason,
                )
                listOf(
                    ByteArray(7),
                    byteArrayOf(0, 0, 4, 0, 87, 0, 2, 0),
                    byteArrayOf(0, 0, 2, 0, 87, 0, 2, 0),
                ).forEach { bytes ->
                    eventually { client.connection.offer(UnsafeBuffer(bytes), 0, bytes.size) > 0 }
                }
                val next = assertIs<FundingRequestOutcome.Confirmed>(client.request(command("P3", "U2", "L3")))
                assertIs<DevelopmentBalanceResultPayload.Prepared>(next.result)
                val observation = assertNotNull(node.observation)
                assertEquals(3L, observation.malformedMessages)
                assertEquals(2, observation.snapshot!!.preparations.size)
                assertTrue(observation.snapshot.pendingOrders.isEmpty())
                assertNull(node.error)
            }
        }
    }

    @Test
    fun `반영 전 코어 오류도 실제 노드를 닫고 성공을 응답하지 않는다`() {
        val config = config().copy(resultTimeoutMs = 100)
        val node = DevelopmentFundingNode.start(config)
        node.use {
            DevelopmentFundingClient(config).use { client ->
                assertIs<FundingRequestOutcome.Unconfirmed>(client.request(command().copy(ledgerTransactionId = "")))
                assertTrue(node.awaitClosed(5_000), "소유자가 실제 런타임을 닫아야 한다")
                assertTrue(node.isClosed)
                assertNotNull(node.error)
                assertTrue(node.observation!!.failure!!.contains("원장"))
                assertFalse(node.observation!!.blocked, "반영 전 코어 차단 여부와 런타임 중단 정책은 다르다")
                assertNull(node.observation!!.snapshot)
            }
        }
    }

    @Test
    fun `기본 대기의 별도 클라이언트도 노드 종료 뒤 미확인과 재확인 안내를 남긴다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            val classpath = System.getProperty("developmentFundingRuntimeClasspath")
            val output = Files.createTempFile("funding-client-fatal-", ".log")
            val process =
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
                    "-cp",
                    classpath,
                    "com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingClientKt",
                    config.runRoot.toString(),
                    config.basePort.toString(),
                    "P1",
                    "A",
                    "USDT",
                    "1000000000",
                    "",
                    command().occurredAt.toString(),
                ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "별도 클라이언트가 대기 상한 안에 끝나야 한다")
                val text = Files.readString(output)
                assertEquals(1, process.exitValue(), text)
                assertTrue(text.contains("TRANSPORT_ACCEPTED"), text)
                assertTrue(text.contains("UNCONFIRMED transportAccepted=true"), text)
                assertTrue(text.contains("같은 준비 ID"), text)
                assertFalse(text.contains("RESULT_RECEIVED"), text)
                assertTrue(node.awaitClosed(5_000))
                assertNotNull(node.error)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    @Test
    fun `반영 도중 쓰기가 실패해도 실제 노드가 차단된 상태를 공개하지 않고 닫힌다`() {
        val config = config().copy(resultTimeoutMs = 100)
        DevelopmentFundingNode.start(config).use { node ->
            // 실물 노드의 한 메모리 쓰기만 실패시킨다. 제품에 장애 주입 입구를 만들지 않는다.
            val adapterField = DevelopmentFundingNode::class.java.getDeclaredField("service").apply { isAccessible = true }
            val adapter = adapterField.get(node)
            val stateField = DevelopmentFundingAdapter::class.java.getDeclaredField("state").apply { isAccessible = true }
            val state = stateField.get(adapter) as com.exchange.core.trading.OrderAdmissionState
            val preparations = state.javaClass.getDeclaredField("preparations").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val original = preparations.get(state) as MutableMap<Any, Any>
            preparations.set(
                state,
                object : MutableMap<Any, Any> by original {
                    override fun put(
                        key: Any,
                        value: Any,
                    ): Any? = error("준비 쓰기 중단")
                },
            )
            DevelopmentFundingClient(config).use { client ->
                assertIs<FundingRequestOutcome.Unconfirmed>(client.request(command()))
                assertTrue(node.awaitClosed(5_000))
                assertTrue(node.observation!!.blocked)
                assertNull(node.observation!!.snapshot)
                assertNull(node.observation!!.result)
                assertTrue(node.observation!!.failure!!.contains("준비 쓰기 중단"))
                assertFailsWith<IllegalStateException> { state.snapshot() }
                assertFailsWith<IllegalStateException> {
                    state.prepareDevelopmentBalance(command().opening, "L1", command().occurredAt)
                }
            }
        }
    }

    @Test
    fun `기동 준비 기한을 넘기면 새 노드도 READY로 남지 않는다`() {
        val config = config().copy(startupTimeoutMs = 1)
        assertFails { DevelopmentFundingNode.start(config) }
        assertTrue(Files.exists(config.runRoot))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { DevelopmentFundingNode.start(config) }
    }

    @Test
    fun `실제 Snapshot 요청은 빈 성공 대신 노드 종료로 끝난다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            Aeron.connect(Aeron.Context().aeronDirectoryName(config.driverDirectory)).use { aeron ->
                val toggle = assertNotNull(ClusterControl.findControlToggle(aeron.countersReader(), config.clusterId))
                eventually { ClusterControl.ToggleState.SNAPSHOT.toggle(toggle) }
                assertTrue(node.awaitClosed(5_000), "Snapshot 거절이 실제 실행 중단까지 이어져야 한다")
                assertNotNull(node.error)
                assertTrue(node.observation!!.failure!!.contains("Snapshot"))
            }
        }
    }

    @Test
    fun `종료 뒤에도 기록을 보존하고 기존 디렉터리의 재실행을 거절한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            DevelopmentFundingClient(config).use { client -> assertIs<FundingRequestOutcome.Confirmed>(client.request(command())) }
            assertNull(node.error)
        }
        val before = files(config.runRoot)
        assertTrue(before.any { it.startsWith("archive/") })
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { DevelopmentFundingNode.start(config) }
        assertEquals(before, files(config.runRoot))
    }

    @Test
    fun `사용 중 포트와 잘못된 저장 설정은 조용히 대체하지 않는다`() {
        val config = config()
        DatagramSocket(config.basePort).use {
            assertFails { DevelopmentFundingNode.start(config) }
        }
        assertTrue(Files.exists(config.runRoot))
        assertFailsWith<io.aeron.exceptions.ConfigurationException> {
            io.aeron.archive.Archive
                .Context()
                .fileSyncLevel(2)
                .catalogFileSyncLevel(1)
                .conclude()
        }
        assertFailsWith<IllegalArgumentException> { DevelopmentFundingSettings(Path.of("relative")) }
        assertFailsWith<IllegalArgumentException> { config.copy(startupTimeoutMs = 0) }
    }

    /** 실제 Driver에 설치된 래핑 핸들러와 오류 로그를 확인한다. 제품에 테스트용 입구를 만들지 않는다. */
    private fun driver(node: DevelopmentFundingNode): MediaDriver {
        val field = DevelopmentFundingNode::class.java.getDeclaredField("driver").apply { isAccessible = true }
        return field.get(node) as MediaDriver
    }

    private fun command(
        preparation: String = "P1",
        user: String = "U1",
        ledger: String = "L1",
    ) = DevelopmentBalanceCommandPayload(
        OpeningBalance(preparation, UserId(user), AssetId("USDT"), Amount(1_000_000_000)),
        ledger,
        Instant.parse("2026-10-17T00:00:00.123456789Z"),
    )

    private fun config(): DevelopmentFundingSettings {
        // 포트 탐색은 테스트 격리에만 사용한다. 개발 실행은 사용자가 지정한 포트에서 실패한다.
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(Files.createTempDirectory("funding97-").resolve("run"), base)
            } catch (_: java.net.BindException) {
                // 다른 테스트나 프로세스의 포트를 피한다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트 묶음을 확보하지 못했습니다")
    }

    private fun files(root: Path): Set<String> =
        Files.walk(root).use { paths -> paths.map { root.relativize(it).toString() }.toList().toSet() }

    private fun eventually(condition: () -> Boolean) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "관측 대기 기한을 초과했습니다" }
            Thread.sleep(1)
        }
    }
}

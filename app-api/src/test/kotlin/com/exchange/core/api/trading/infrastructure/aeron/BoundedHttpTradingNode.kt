package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.trading.ExecutionReceipt
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingRecoveryState
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val boundedHttpJson = jacksonObjectMapper()

/** 제품 서비스의 같은 소유 스레드에서 만든 상태와 적용 위치를 한 번에 전달한다. */
private data class BoundedHttpCapture(
    val state: TradingRecoveryState,
    val position: Long,
    val role: String,
    val executions: List<BoundedHttpAppliedExecution>,
    val observationFailure: String?,
)

private data class BoundedHttpAppliedExecution(
    val receipt: ExecutionReceipt,
    val position: Long,
)

/** 파일 접근은 하지 않는다. 실제 callback이 새로 만든 작은 Applied 결과만 제한해서 보관한다. */
private class BoundedHttpServiceObserver {
    private val captures = ArrayBlockingQueue<CompletableFuture<BoundedHttpCapture>>(16)

    fun capture(timeoutMs: Long): BoundedHttpCapture {
        val result = CompletableFuture<BoundedHttpCapture>()
        check(captures.offer(result)) { "CAPTURE_MAILBOX_OVERFLOW" }
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            result.cancel(false)
            captures.remove(result)
        }
    }

    fun decorate(service: DevelopmentFundingAdapter): ClusteredService =
        object : ClusteredService by service {
            private lateinit var cluster: Cluster
            private val executions = ArrayList<BoundedHttpAppliedExecution>(EXECUTION_CAPACITY)
            private var observationFailure: String? = null

            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                this.cluster = cluster
                service.onStart(cluster, snapshotImage)
            }

            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                val previous = service.observation
                try {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                } finally {
                    val current = service.observation
                    if (current != null && current !== previous) {
                        when {
                            current.failure != null -> failObservation("OBSERVATION_FAILURE: ${current.failure}")
                            current.blocked -> failObservation("OBSERVATION_FAILURE: service blocked")
                            current.malformedMessages != 0L -> failObservation("OBSERVATION_FAILURE: malformed input")
                        }
                        current.compactExecution?.let { receipt ->
                            when {
                                !haProbeIsAppliedExecution(current) { it.compactExecution != null } -> {
                                    failObservation("OBSERVATION_FAILURE: invalid compact execution")
                                }

                                current.executionSequence != receipt.admissionSequence || current.position <= 0 -> {
                                    failObservation("OBSERVATION_FAILURE: execution identity or position differs")
                                }

                                executions.any { it.receipt.admissionSequence == receipt.admissionSequence } -> {
                                    failObservation("DUPLICATE_APPLIED: admission=${receipt.admissionSequence}")
                                }

                                executions.size == EXECUTION_CAPACITY -> {
                                    failObservation("EXECUTION_OVERFLOW: capacity=$EXECUTION_CAPACITY")
                                }

                                else -> {
                                    executions.add(BoundedHttpAppliedExecution(receipt, current.position))
                                }
                            }
                        }
                    }
                }
            }

            override fun doBackgroundWork(nowNs: Long): Int {
                var work = service.doBackgroundWork(nowNs)
                while (true) {
                    val requested = captures.poll() ?: break
                    if (requested.isCancelled) continue
                    try {
                        val recovery = service.captureRecoveryState()
                        check(recovery.isDone) { "capture must finish synchronously on the service thread" }
                        val state = recovery.join()
                        // 이 두 값 사이에 서비스 callback이 끼어들 수 없다. 외부 observation의 위치는 쓰지 않는다.
                        val position = cluster.logPosition()
                        requested.complete(
                            BoundedHttpCapture(state, position, cluster.role().name, executions.toList(), observationFailure),
                        )
                    } catch (error: Throwable) {
                        requested.completeExceptionally(error)
                    }
                    work++
                }
                return work
            }

            private fun failObservation(reason: String) {
                if (observationFailure == null) observationFailure = reason.take(1_024)
            }
        }

    companion object {
        private const val EXECUTION_CAPACITY = 8
    }
}

/** 제품 설정 로더와 제품 노드를 그대로 사용하며 test 소스에 관찰 래퍼만 더한다. */
fun main(args: Array<String>) {
    require(args.isEmpty())
    val config = developmentClusterSettings()
    val manifest = requireNotNull(config.boundedManifest) { "FUNDING_BOUNDED_MANIFEST is required" }
    check(config.trustedBoundedControl) { "the test preparation control must be explicitly enabled" }
    val manifestBytes = Files.readAllBytes(Path.of(requireNotNull(System.getenv("FUNDING_BOUNDED_MANIFEST"))))
    check(BoundedTradingStateCodec().decodeManifest(manifestBytes) == manifest) { "manifest changed while loading node settings" }
    val manifestSha256 = boundedHttpSha256(manifestBytes)
    val control = boundedHttpControlDirectory()
    Files.createDirectories(control)
    val observer = BoundedHttpServiceObserver()
    try {
        DevelopmentFundingNode.start(config, observer::decorate).use { node ->
            val shutdown = Thread({ node.close() }, "test-http-bounded-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                boundedHttpEvent(
                    fundingReadyFields(config, node) + boundedHttpManifestFields(manifestBytes, manifest) +
                        mapOf(
                            "sync" to node.storageLevels,
                            "driver" to config.driverDirectory,
                            "members" to config.members,
                        ),
                )
                val command = control.resolve("node-${config.memberId}.command")
                while (!node.awaitClosed(25)) {
                    if (!Files.exists(command)) continue
                    check(Files.size(command) in 1..256) { "invalid bounded HTTP node command size" }
                    val input = Files.readString(command).trim()
                    Files.delete(command)
                    check(input.substringBefore(':') == "CAPTURE") { "unsupported bounded HTTP node command" }
                    val label = input.substringAfter(':', "")
                    require(label.matches(Regex("[a-zA-Z0-9-]{1,64}"))) { "invalid capture label" }
                    val capture = observer.capture(config.resultTimeoutMs)
                    val fields = boundedHttpCaptureFields(config, label, manifestSha256, node.storageLevels, capture)
                    writeBoundedHttpJson(control.resolve("state-${config.memberId}-$label.json"), fields)
                }
                node.error?.let { throw IllegalStateException("bounded HTTP node failed", it) }
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown)
                } catch (_: IllegalStateException) {
                    // JVM 종료 중에는 같은 close 경로를 실행하는 hook이 남는다.
                }
            }
        }
    } catch (error: Throwable) {
        boundedHttpEvent(
            mapOf("kind" to "ERROR", "runId" to config.runId, "memberId" to config.memberId, "failure" to error.toString().take(1_024)),
        )
        throw error
    }
}

private fun boundedHttpCaptureFields(
    config: DevelopmentFundingSettings,
    label: String,
    manifestSha256: String,
    sync: List<Int>,
    capture: BoundedHttpCapture,
): Map<String, Any?> {
    val state = capture.state
    // 전송 위치를 제외한 실제 codec으로 금융 상태를 정규화한다. 역할과 PID는 이 hash에 없다.
    val stateBytes = ByteArrayOutputStream().also { BoundedTradingStateCodec().write(state, 0, it) }.toByteArray()
    return mapOf(
        "runId" to config.runId,
        "label" to label,
        "memberId" to config.memberId,
        "protocol" to "bounded-wire3",
        "position" to capture.position,
        "role" to capture.role,
        "sync" to sync,
        "manifestSha256" to manifestSha256,
        "lifecycle" to state.lifecycle.name,
        "lastAdmissionSequence" to state.lastAdmissionSequence,
        "balances" to state.balances.map { listOf(it.userId.value, it.assetId.value, it.available.value, it.hold.value) },
        "feeRevenue" to (state.feeRevenueByAsset[TradingEngineProbeWorkload.quote]?.toString() ?: "0"),
        "liveOrders" to state.liveOrders.size,
        "pending" to state.markets.sumOf { it.pendingAdmissionSequences.size },
        "activeReservations" to state.liveOrders.count { it.reservation.status == OrderReservationStatus.ACTIVE },
        "bookOrders" to state.matching.markets.sumOf { it.orders.size },
        "retainedReceipts" to state.inbox.channels.sumOf { it.entries.size },
        "receiptFloors" to
            state.inbox.channels.map { listOf(it.userId.value, it.channel, it.epoch, it.retiredThrough, it.processedThrough) },
        "receipts" to
            state.inbox.channels.flatMap { channel ->
                channel.entries.map { entry ->
                    mapOf(
                        "userId" to channel.userId.value,
                        "channel" to channel.channel,
                        "epoch" to channel.epoch,
                        "sequence" to entry.sequence,
                        "code" to entry.receipt.code.name,
                        "orderId" to entry.receipt.orderId?.value,
                        "admissionSequence" to entry.receipt.admissionSequence,
                        "marketAdmissionSequence" to entry.receipt.marketAdmissionSequence,
                        "intent" to boundedHttpIntentFields(entry.intent),
                    )
                }
            },
        "liveOrderDetails" to
            state.liveOrders.map {
                mapOf(
                    "admissionSequence" to it.admissionSequence,
                    "marketAdmissionSequence" to it.marketAdmissionSequence,
                    "userId" to it.order.userId.value,
                    "marketId" to it.order.marketId.value,
                    "orderId" to it.order.orderId.value,
                    "side" to it.order.side.name,
                    "orderType" to it.order.orderType.name,
                    "timeInForce" to it.order.timeInForce.name,
                    "price" to it.order.price.value,
                    "phase" to it.phase.name,
                    "quantity" to it.order.quantity.value,
                    "reservationRemainingQuantity" to it.reservation.remainingQuantity.value,
                    "reservationRemainingAmount" to it.reservation.remainingAmount.value,
                )
            },
        "markets" to
            state.markets.map {
                mapOf(
                    "marketId" to it.marketId.value,
                    "lastAssigned" to it.lastAssigned,
                    "executedThrough" to it.executedThrough,
                    "pending" to it.pendingAdmissionSequences.size,
                )
            },
        "bookDetails" to
            state.matching.markets.map { market ->
                mapOf(
                    "marketId" to market.marketId.value,
                    "lastEngineSequence" to market.lastEngineSequence,
                    "orders" to
                        market.orders.map {
                            mapOf(
                                "orderId" to it.orderId.value,
                                "userId" to it.userId.value,
                                "side" to it.side.name,
                                "price" to it.price.value,
                                "quantity" to it.originalQuantity.value,
                                "remainingQuantity" to it.remainingQuantity.value,
                            )
                        },
                )
            },
        "executions" to
            capture.executions.map {
                mapOf(
                    "admissionSequence" to it.receipt.admissionSequence,
                    "marketAdmissionSequence" to it.receipt.marketAdmissionSequence,
                    "marketId" to it.receipt.marketId.value,
                    "lastEngineSequence" to it.receipt.lastEngineSequence,
                    "fillCount" to it.receipt.fillCount,
                    "resting" to it.receipt.resting,
                    "position" to it.position,
                )
            },
        "observationFailure" to capture.observationFailure,
        "stateSha256" to boundedHttpSha256(stateBytes),
    )
}

private fun boundedHttpIntentFields(intent: OrderIntent): Map<String, Any?> =
    mapOf(
        "marketId" to intent.marketId.value,
        "expectedConfigurationVersion" to intent.expectedConfigurationVersion,
        "side" to intent.side.name,
        "orderType" to intent.orderType.name,
        "timeInForce" to intent.timeInForce.name,
        "price" to intent.price.value,
        "quantity" to intent.quantity.value,
    )

internal fun boundedHttpControlDirectory(): Path =
    Path.of(System.getenv("HTTP_BOUNDED_CONTROL") ?: System.getenv("HA_PROBE_CONTROL") ?: "/control").also {
        require(it.isAbsolute) { "bounded HTTP control directory must be absolute" }
    }

internal fun boundedHttpSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun boundedHttpManifestFields(
    bytes: ByteArray,
    manifest: TradingClusterManifest,
): Map<String, Any?> =
    mapOf(
        "manifestSha256" to boundedHttpSha256(bytes),
        "artifactSha256" to manifest.executionArtifactSha256,
        "schemaSha256" to manifest.schemaSha256,
        "lineage" to mapOf("high" to manifest.lineage.high, "low" to manifest.lineage.low),
        "algorithmId" to manifest.algorithmId,
        "configurationVersion" to manifest.configurationVersion,
        "javaVersion" to System.getProperty("java.version"),
        "javaVendor" to System.getProperty("java.vendor"),
        "markets" to
            fundingMarketFields(
                DevelopmentMarketRegistry(manifest.markets.map { DevelopmentMarketContext(it.market, it.feePolicySnapshot) }),
            ),
    )

internal fun boundedHttpEvent(fields: Map<String, Any?>) {
    println("HTTP_BOUNDED_EVENT ${boundedHttpJson.writeValueAsString(fields)}")
}

/** JSON 파일 작성과 이름 변경은 제어 스레드에서만 호출한다. */
internal fun writeBoundedHttpJson(
    path: Path,
    fields: Map<String, Any?>,
) {
    val bytes = boundedHttpJson.writeValueAsBytes(fields)
    check(bytes.size <= 262_144) { "bounded HTTP evidence exceeds the per-file limit" }
    writeBoundedHttpArtifact(path, bytes, replaceExisting = true)
}

/** 비밀이 없는 테스트 자료는 Docker의 root와 호스트의 CI 사용자 모두 읽을 수 있게 게시한다. */
internal fun writeBoundedHttpArtifact(
    path: Path,
    bytes: ByteArray,
    replaceExisting: Boolean,
) {
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
    try {
        Files.write(temporary, bytes)
        // createTempFile의 기본 0600을 그대로 rename하면 Linux 호스트와 artifact 업로더가 읽지 못한다.
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r--r--"))
        if (replaceExisting) {
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } else {
            Files.move(temporary, path, ATOMIC_MOVE)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

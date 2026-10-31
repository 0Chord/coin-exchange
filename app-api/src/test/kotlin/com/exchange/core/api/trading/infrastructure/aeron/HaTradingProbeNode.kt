package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.OneToOneConcurrentArrayQueue
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Test-source-only compact TCP observation. No financial state or full Completion is retained. */
internal class HaTradingProbeMarkers(
    private val member: Int,
    endpoint: String?,
    private val appliedResult: (FundingObservation) -> Boolean = { it.executionCompletion != null },
) : AutoCloseable {
    private val queue = OneToOneConcurrentArrayQueue<LongArray>(16_384)
    private val failure = AtomicReference<String?>()
    private val boundary = HaProbeServiceBoundary()
    private val offered = AtomicLong()
    private val sent = AtomicLong()
    private val queuePeak = AtomicInteger()
    private val deliverySamples = AtomicLong()
    private val deliveryTotalNs = AtomicLong()
    private val deliveryMaxNs = AtomicLong()

    // The client container may not exist until all nodes are ready. Do not populate DNS negative
    // caches during node construction; resolve only after an execution marker actually exists.
    private val target =
        endpoint?.also {
            require(it.substringBeforeLast(':').isNotBlank() && it.substringAfterLast(':').toInt() in 1..65_535)
        }

    @Volatile private var closing = false

    @Volatile private var activeSocket: Socket? = null
    private val sender =
        target?.let { address ->
            Thread({
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        activeSocket = socket
                        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                        var connected = false
                        while (!closing || !queue.isEmpty()) {
                            val marker = queue.poll()
                            if (marker == null) {
                                java.util.concurrent.locks.LockSupport
                                    .parkNanos(10_000)
                                continue
                            }
                            if (!connected) {
                                val destination =
                                    InetSocketAddress(address.substringBeforeLast(':'), address.substringAfterLast(':').toInt())
                                check(!destination.isUnresolved) { "completion marker client address is unresolved" }
                                socket.connect(destination, 5_000)
                                connected = true
                            }
                            buffer.clear()
                            buffer
                                .putInt(MAGIC)
                                .putInt(member)
                                .putLong(marker[0])
                                .putLong(marker[1])
                                .putInt(marker[2].toInt())
                                .putInt(0)
                            socket.getOutputStream().write(buffer.array(), 0, 32)
                            sent.incrementAndGet()
                            if (marker.size == 4) {
                                val elapsed = System.nanoTime() - marker[3]
                                check(elapsed >= 0) { "marker node-local clock moved backwards" }
                                deliverySamples.incrementAndGet()
                                deliveryTotalNs.addAndGet(elapsed)
                                var previous = deliveryMaxNs.get()
                                while (elapsed > previous && !deliveryMaxNs.compareAndSet(previous, elapsed)) {
                                    previous = deliveryMaxNs.get()
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error.toString())
                } finally {
                    activeSocket = null
                }
            }, "test-ha-marker-$member").also {
                it.isDaemon = true
                it.start()
            }
        }
    val error: String? get() = failure.get()

    fun captureBoundary(timeoutMs: Long): Map<String, Any?> = boundary.capture(timeoutMs)

    fun summary(): Map<String, Any?> =
        mapOf(
            "scope" to "cumulative test marker sender; every marker delivered, timing sampled every 256 offers",
            "capacity" to 16_384,
            "offered" to offered.get(),
            "sent" to sent.get(),
            "queuePeak" to queuePeak.get(),
            "queued" to queue.size,
            "timingSampleEvery" to 256,
            "enqueueToWriteSamples" to deliverySamples.get(),
            "enqueueToWriteTotalNs" to deliveryTotalNs.get(),
            "enqueueToWriteMaxNs" to deliveryMaxNs.get(),
            "failure" to error,
        )

    fun decorate(service: DevelopmentFundingAdapter): ClusteredService =
        object : ClusteredService by service {
            override fun doBackgroundWork(nowNs: Long): Int = service.doBackgroundWork(nowNs) + boundary.completePending()

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
                    if (target != null && current != null && current !== previous && haProbeIsAppliedExecution(current, appliedResult)) {
                        val leader = if (current.appliedRole == "LEADER") 1L else 0L
                        val ordinal = offered.incrementAndGet()
                        // Preserve the original three-long allocation for unsampled markers.
                        // Only one in 256 carries an extra timestamp; no timing wrapper is allocated.
                        val marker =
                            if (ordinal % 256L == 0L) {
                                longArrayOf(checkNotNull(current.executionSequence), current.position, leader, System.nanoTime())
                            } else {
                                longArrayOf(checkNotNull(current.executionSequence), current.position, leader)
                            }
                        if (!queue.offer(marker)) {
                            failure.compareAndSet(null, "completion observation queue overflow")
                        } else {
                            val size = queue.size
                            var observedPeak = queuePeak.get()
                            while (size > observedPeak && !queuePeak.compareAndSet(observedPeak, size)) {
                                observedPeak = queuePeak.get()
                            }
                        }
                    }
                }
            }
        }

    override fun close() {
        closing = true
        sender?.join(2_000)
        if (sender?.isAlive == true) {
            activeSocket?.close()
            sender.join(2_000)
        }
        check(sender?.isAlive != true) { "marker sender did not drain" }
        check(error == null) { error ?: "marker error" }
    }

    companion object {
        const val MAGIC = 0x48413332
    }
}

internal fun haProbeIsAppliedExecution(
    observation: FundingObservation,
    appliedResult: (FundingObservation) -> Boolean,
): Boolean =
    observation.replyStatus == "EXECUTED" && !observation.blocked && observation.failure == null &&
        observation.executionSequence != null && appliedResult(observation)

/** The same node boot settings are used for the original and bounded production assembly. */
internal fun haTradingProbeSettings(environment: Map<String, String> = System.getenv()): DevelopmentFundingSettings {
    fun required(name: String) = requireNotNull(environment[name]) { "missing $name" }
    return DevelopmentFundingSettings(
        runRoot = Path.of(required("FUNDING_RUN_ROOT")),
        basePort = environment["FUNDING_BASE_PORT"]?.toInt() ?: 19700,
        startupTimeoutMs = 60_000,
        resultTimeoutMs = 30_000,
        memberId = environment["FUNDING_MEMBER_ID"]?.toInt() ?: 0,
        advertisedHost = required("FUNDING_HOST"),
        bindHost = environment["FUNDING_BIND_HOST"] ?: "0.0.0.0",
        clusterMembers = required("FUNDING_MEMBERS"),
        runId = required("FUNDING_RUN_ID"),
        archiveFileSyncLevel = 2,
        recoverExisting = environment["FUNDING_RECOVER_EXISTING"]?.toBooleanStrict() ?: false,
        driverRoot = environment["FUNDING_DRIVER_ROOT"]?.let(Path::of),
        markets =
            DevelopmentMarketRegistry(
                TradingEngineProbeWorkload.markets.map {
                    DevelopmentMarketContext(it, TradingEngineProbeWorkload.policy)
                },
            ),
    )
}

internal fun runHaTradingProbeNode(
    config: DevelopmentFundingSettings,
    snapshot: (DevelopmentFundingNode) -> Boolean = { false },
    faultDecorator: (ClusteredService, DevelopmentFundingAdapter) -> ClusteredService = { decorated, _ -> decorated },
    appliedResult: (FundingObservation) -> Boolean = { it.executionCompletion != null },
    capture: (DevelopmentFundingNode) -> Map<String, Any?>,
) {
    val control = Path.of(requireNotNull(System.getenv("HA_PROBE_CONTROL")))
    Files.createDirectories(control)
    HaTradingProbeMarkers(config.memberId, System.getenv("HA_PROBE_MARKER_ENDPOINT"), appliedResult).use { markers ->
        DevelopmentFundingNode.start(config) { service -> faultDecorator(markers.decorate(service), service) }.use { node ->
            val shutdown = Thread { node.close() }
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                fundingEvent(
                    mapOf(
                        "kind" to "HA_READY",
                        "member" to config.memberId,
                        "role" to node.role.name,
                        "sync" to node.storageLevels,
                        "pid" to ProcessHandle.current().pid(),
                        "driver" to config.driverDirectory.toString(),
                    ),
                )
                val command = control.resolve("node-${config.memberId}.command")
                while (!node.awaitClosed(25)) {
                    check(markers.error == null) { markers.error ?: "marker failed" }
                    if (!Files.exists(command)) continue
                    val value = Files.readString(command).trim()
                    Files.delete(command)
                    val action = value.substringBefore(':')
                    val label = value.substringAfter(':', "default")
                    require(label.matches(Regex("[a-zA-Z0-9-]{1,64}")))
                    when (action) {
                        "SNAPSHOT" -> {
                            fundingEvent(
                                mapOf(
                                    "kind" to "HA_SNAPSHOT_REQUEST",
                                    "member" to config.memberId,
                                    "label" to label,
                                    "accepted" to snapshot(node),
                                ),
                            )
                        }

                        "CAPTURE" -> {
                            val consensusField =
                                DevelopmentFundingNode::class.java.getDeclaredField("consensus").apply {
                                    isAccessible =
                                        true
                                }
                            val consensus = consensusField.get(node) as io.aeron.cluster.ConsensusModule
                            val state = capture(node)
                            val serviceBoundary = markers.captureBoundary(config.resultTimeoutMs)
                            // The registry getter drains the accepted metric handoff after the service boundary.
                            val metrics = haTradingProbeMetrics { node.metrics }
                            val fields =
                                state +
                                    mapOf(
                                        "kind" to "HA_CAPTURE",
                                        "member" to config.memberId,
                                        "label" to label,
                                        "position" to node.observation?.position,
                                        "role" to node.role.name,
                                        "sync" to node.storageLevels,
                                        "snapshotCount" to consensus.context().snapshotCounter().get(),
                                        "electionState" to consensus.context().electionStateCounter().get(),
                                        "serviceBoundary" to serviceBoundary,
                                        "metrics" to metrics,
                                        "markerSender" to markers.summary(),
                                    )
                            val json =
                                tools.jackson.module.kotlin
                                    .jacksonObjectMapper()
                                    .writeValueAsString(fields)
                            val temporary = control.resolve("state-${config.memberId}-$label.tmp")
                            Files.writeString(temporary, json)
                            Files.move(
                                temporary,
                                control.resolve("state-${config.memberId}-$label.json"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            )
                            fundingEvent(fields)
                        }

                        "STOP" -> {
                            node.close()
                        }

                        else -> {
                            error("unknown node probe action")
                        }
                    }
                }
                node.error?.let { throw IllegalStateException("node stopped with failure", it) }
            } finally {
                node.close()
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }
}

internal fun haProbeSha(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

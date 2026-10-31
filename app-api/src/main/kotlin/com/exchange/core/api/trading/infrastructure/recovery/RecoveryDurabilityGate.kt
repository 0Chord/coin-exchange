package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.trading.ClusterLineage
import java.util.TreeMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface RecoveryDurabilityGate {
    fun awaitProtected(
        lineage: ClusterLineage,
        position: Long,
    ): CompletionStage<Unit>
}

/** Issued only after the backup's files and execution bundle have been validated and forced. */
@ConsistentCopyVisibility
data class RemoteRecoveryCertificate internal constructor(
    val lineage: ClusterLineage,
    val authorityEpoch: Long,
    val recoverableThrough: Long,
    val recoveryManifestSha256: String,
    val executionManifestSha256: String,
) {
    init {
        require(authorityEpoch > 0 && recoverableThrough >= 0)
        require(recoveryManifestSha256.matches(Regex("[0-9a-f]{64}")))
        require(executionManifestSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** No financial thread waits here; a finite number of public replies await independent durability. */
class BoundedRecoveryDurabilityGate(
    private val expectedLineage: ClusterLineage,
    private val authorityEpoch: Long,
    private val executionManifestSha256: String,
    private val maxPending: Int = 4_096,
    private val timeoutMs: Long = 5_000,
) : RecoveryDurabilityGate,
    AutoCloseable {
    private val lock = Any()
    private val pending = TreeMap<Long, MutableSet<CompletableFuture<Unit>>>()
    private val timeouts =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "remote-recovery-gate-timeouts").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    private var pendingCount = 0
    private var protectedThrough = -1L
    private var closed = false

    init {
        require(authorityEpoch > 0 && maxPending > 0 && timeoutMs > 0)
        require(executionManifestSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun awaitProtected(
        lineage: ClusterLineage,
        position: Long,
    ): CompletionStage<Unit> {
        require(lineage == expectedLineage) { "public response lineage differs" }
        require(position >= 0)
        val future = CompletableFuture<Unit>()
        synchronized(lock) {
            if (closed) return CompletableFuture.failedFuture(IllegalStateException("recovery gate is closed"))
            if (position <= protectedThrough) return CompletableFuture.completedFuture(Unit)
            if (pendingCount >=
                maxPending
            ) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("remote durability pending capacity exceeded; result remains unconfirmed"),
                )
            }
            pending.getOrPut(position) { linkedSetOf() }.add(future)
            pendingCount++
            val timeout =
                timeouts.schedule({
                    future.completeExceptionally(TimeoutException("remote durability unconfirmed"))
                }, timeoutMs, TimeUnit.MILLISECONDS)
            future.whenComplete { _, _ ->
                timeout.cancel(false)
                synchronized(lock) {
                    if (pending[position]?.remove(future) == true) {
                        pendingCount--
                        if (pending[position]?.isEmpty() == true) pending.remove(position)
                    }
                }
            }
        }
        return future
    }

    /** A trusted certificate transport must validate its source; epochs cannot silently change. */
    fun acceptCertificate(certificate: RemoteRecoveryCertificate) {
        require(certificate.lineage == expectedLineage && certificate.authorityEpoch == authorityEpoch) { "recovery authority differs" }
        require(certificate.executionManifestSha256 == executionManifestSha256) { "recovery execution manifest differs" }
        val ready = arrayListOf<CompletableFuture<Unit>>()
        synchronized(lock) {
            check(!closed)
            require(certificate.recoverableThrough >= protectedThrough) { "recovery prefix regressed" }
            protectedThrough = certificate.recoverableThrough
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key > protectedThrough) break
                ready.addAll(entry.value)
                pendingCount -= entry.value.size
                iterator.remove()
            }
        }
        ready.forEach { it.complete(Unit) }
    }

    val waitingCount: Int get() = synchronized(lock) { pendingCount }
    val certifiedThrough: Long get() = synchronized(lock) { protectedThrough }

    override fun close() {
        val failed = arrayListOf<CompletableFuture<Unit>>()
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.forEach { failed.addAll(it) }
            pending.clear()
            pendingCount = 0
        }
        failed.forEach { it.completeExceptionally(IllegalStateException("recovery gate closed; result remains unconfirmed")) }
        timeouts.shutdownNow()
    }
}

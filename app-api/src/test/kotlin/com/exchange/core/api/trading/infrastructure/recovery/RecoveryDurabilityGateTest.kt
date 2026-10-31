package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.trading.ClusterLineage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

class RecoveryDurabilityGateTest {
    private val lineage = ClusterLineage(31, 42)
    private val execution = "a".repeat(64)

    private fun certificate(through: Long) = RemoteRecoveryCertificate(lineage, 2, through, "b".repeat(64), execution)

    @Test fun `a quorum response remains unconfirmed until its exact prefix is protected`() {
        BoundedRecoveryDurabilityGate(lineage, 2, execution).use { gate ->
            val response = gate.awaitProtected(lineage, 640).toCompletableFuture()
            gate.acceptCertificate(certificate(608))
            assertFalse(response.isDone)
            gate.acceptCertificate(certificate(640))
            assertEquals(Unit, response.join())
            assertEquals(0, gate.waitingCount)
            assertEquals(Unit, gate.awaitProtected(lineage, 640).toCompletableFuture().join())
        }
    }

    @Test fun `wrong identity epoch and regressing prefix cannot release a result`() {
        BoundedRecoveryDurabilityGate(lineage, 2, execution).use { gate ->
            val waiting = gate.awaitProtected(lineage, 100).toCompletableFuture()
            assertThrows(
                IllegalArgumentException::class.java,
            ) { gate.acceptCertificate(certificate(100).copy(lineage = ClusterLineage(1, 1))) }
            assertThrows(IllegalArgumentException::class.java) { gate.acceptCertificate(certificate(100).copy(authorityEpoch = 3)) }
            assertThrows(IllegalArgumentException::class.java) {
                gate.acceptCertificate(certificate(100).copy(executionManifestSha256 = "c".repeat(64)))
            }
            gate.acceptCertificate(certificate(80))
            assertThrows(IllegalArgumentException::class.java) { gate.acceptCertificate(certificate(79)) }
            assertFalse(waiting.isDone)
            assertTrue(waiting.cancel(false))
            assertEquals(0, gate.waitingCount)
        }
    }

    @Test fun `finite capacity cancellation timeout and close release every pending slot`() {
        val gate = BoundedRecoveryDurabilityGate(lineage, 2, execution, maxPending = 1, timeoutMs = 25)
        val first = gate.awaitProtected(lineage, 100).toCompletableFuture()
        assertThrows(CompletionException::class.java) { gate.awaitProtected(lineage, 200).toCompletableFuture().join() }
        assertTrue(first.cancel(false))
        assertEquals(0, gate.waitingCount)
        val timed = gate.awaitProtected(lineage, 300).toCompletableFuture()
        assertThrows(java.util.concurrent.ExecutionException::class.java) { timed.get(1, TimeUnit.SECONDS) }
        assertEquals(0, gate.waitingCount)
        val closed = gate.awaitProtected(lineage, 400).toCompletableFuture()
        gate.close()
        assertTrue(closed.isCompletedExceptionally)
        assertEquals(0, gate.waitingCount)
    }

    @Test fun `publishing the next proof slot cannot destroy the currently published proof`(
        @TempDir root: Path,
    ) {
        val first = remoteTestProof(lineage, 2, 640, execution)
        val published = RemoteRecoveryFiles.publish(root, first, lineage, 2, 640, execution)
        // Simulates a crash while writing the other proof slot, before certificate replacement.
        Files.write(root.resolve("proof-b.bin"), byteArrayOf(1, 2))
        assertEquals(published, RemoteRecoveryFiles.read(root))
        val next = RemoteRecoveryFiles.publish(root, remoteTestProof(lineage, 2, 800, execution), lineage, 2, 800, execution)
        assertEquals(next, RemoteRecoveryFiles.read(root))
        Files.write(root.resolve("proof-b.bin"), byteArrayOf(9))
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.read(root) }
    }

    @Test fun `corrupt envelope and checksum-recomputed prefix forgery never open the file gate`(
        @TempDir root: Path,
    ) {
        RemoteRecoveryFiles.publish(root, remoteTestProof(lineage, 2, 640, execution), lineage, 2, 640, execution)
        val file = root.resolve(RemoteRecoveryFiles.CERTIFICATE_FILE)
        val original = Files.readAllBytes(file)
        val corrupted = original.clone().also { it[34] = (it[34].toInt() xor 4).toByte() } // X 640 -> 1664
        Files.write(file, corrupted)
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.read(root) }
        val body = corrupted.copyOf(corrupted.size - 32)
        Files.write(file, body + MessageDigest.getInstance("SHA-256").digest(body))
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.read(root) }
        FileRecoveryDurabilityGate(root, lineage, 2, execution, timeoutMs = 5_000, pollIntervalMs = 1).use { gate ->
            val waiting = gate.awaitProtected(lineage, 800).toCompletableFuture()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (gate.lastReadFailure == null) {
                check(System.nanoTime() < deadline)
                Thread.onSpinWait()
            }
            assertFalse(waiting.isDone)
            assertEquals(-1L, gate.certifiedThrough)
            Files.write(file, original)
            RemoteRecoveryFiles.publish(root, remoteTestProof(lineage, 2, 800, execution), lineage, 2, 800, execution)
            assertEquals(Unit, waiting.get(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `publisher refuses every envelope authority field that differs from its proof`(
        @TempDir root: Path,
    ) {
        val proof = remoteTestProof(lineage, 2, 640, execution)
        assertThrows(
            IllegalArgumentException::class.java,
        ) { RemoteRecoveryFiles.publish(root, proof, ClusterLineage(9, 9), 2, 640, execution) }
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.publish(root, proof, lineage, 3, 640, execution) }
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.publish(root, proof, lineage, 2, 800, execution) }
        assertThrows(IllegalArgumentException::class.java) { RemoteRecoveryFiles.publish(root, proof, lineage, 2, 640, "f".repeat(64)) }
        assertFalse(Files.exists(root.resolve(RemoteRecoveryFiles.CERTIFICATE_FILE)))
    }
}

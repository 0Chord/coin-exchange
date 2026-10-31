package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.trading.ClusterLineage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Atomic publication keeps the current certificate's proof slot intact across a crash. */
internal object RemoteRecoveryFiles {
    private const val MAGIC = 0x52504333
    private const val END = 0x454e4452
    const val CERTIFICATE_FILE = "certificate.bin"
    const val MAX_PROOF_BYTES = 1_048_576

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun writeForced(
        path: Path,
        bytes: ByteArray,
    ) {
        require(bytes.size <= MAX_PROOF_BYTES)
        val temporary = path.resolveSibling(path.fileName.toString() + ".new")
        FileChannel.open(temporary, CREATE, TRUNCATE_EXISTING, WRITE).use { file ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) file.write(buffer)
            file.force(true)
        }
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        FileChannel.open(path.parent, READ).use { it.force(true) }
    }

    fun publish(
        root: Path,
        proof: ByteArray,
        lineage: ClusterLineage,
        epoch: Long,
        position: Long,
        executionManifestSha256: String,
    ): RemoteRecoveryCertificate {
        require(proof.size in 1..MAX_PROOF_BYTES)
        val previousSlot = if (Files.exists(root.resolve(CERTIFICATE_FILE))) readEnvelope(root).second else "proof-b.bin"
        val slot = if (previousSlot == "proof-a.bin") "proof-b.bin" else "proof-a.bin"
        val certificate = RemoteRecoveryCertificate(lineage, epoch, position, sha256(proof), executionManifestSha256)
        requireProofIdentity(certificate, proof)
        writeForced(root.resolve(slot), proof)
        val bytes =
            ByteArrayOutputStream()
                .also { stream ->
                    DataOutputStream(stream).use { out ->
                        out.writeInt(MAGIC)
                        out.writeLong(lineage.high)
                        out.writeLong(lineage.low)
                        out.writeLong(epoch)
                        out.writeLong(position)
                        out.writeUTF(certificate.recoveryManifestSha256)
                        out.writeUTF(executionManifestSha256)
                        out.writeUTF(slot)
                        out.writeInt(END)
                    }
                }.toByteArray()
        writeForced(root.resolve(CERTIFICATE_FILE), bytes + MessageDigest.getInstance("SHA-256").digest(bytes))
        return certificate
    }

    fun read(root: Path): RemoteRecoveryCertificate = readProof(root).first

    fun readProof(root: Path): Pair<RemoteRecoveryCertificate, ByteArray> {
        val (certificate, slot) = readEnvelope(root)
        val file = root.resolve(slot)
        require(Files.isRegularFile(file) && Files.size(file) in 1..MAX_PROOF_BYTES.toLong())
        val proof = Files.readAllBytes(file)
        require(sha256(proof) == certificate.recoveryManifestSha256) { "remote recovery proof digest differs" }
        requireProofIdentity(certificate, proof)
        return certificate to proof
    }

    private fun requireProofIdentity(
        certificate: RemoteRecoveryCertificate,
        bytes: ByteArray,
    ) {
        val proof = RemotePrefixProof.decode(bytes)
        require(
            proof.lineage == certificate.lineage && proof.authorityEpoch == certificate.authorityEpoch &&
                proof.recoverableThrough == certificate.recoverableThrough &&
                proof.executionManifestSha256 == certificate.executionManifestSha256,
        ) {
            "remote certificate authority or prefix differs from its recovery proof"
        }
    }

    private fun readEnvelope(root: Path): Pair<RemoteRecoveryCertificate, String> {
        val path = root.resolve(CERTIFICATE_FILE)
        require(Files.isRegularFile(path) && Files.size(path) in 1..1024)
        val encoded = Files.readAllBytes(path)
        require(encoded.size > 32)
        val body = encoded.copyOf(encoded.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), encoded.copyOfRange(body.size, encoded.size))) {
            "remote certificate envelope checksum differs"
        }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC)
            val lineage = ClusterLineage(input.readLong(), input.readLong())
            val epoch = input.readLong()
            val position = input.readLong()
            val certificate = RemoteRecoveryCertificate(lineage, epoch, position, input.readUTF(), input.readUTF())
            val slot = input.readUTF()
            require(slot == "proof-a.bin" || slot == "proof-b.bin")
            require(input.readInt() == END && input.read() == -1)
            certificate to slot
        }
    }
}

/** Development transport: an independently owned backup volume is mounted read-only here.
 * Production deployments must supply an authenticated transport for the same gate interface.
 */
class FileRecoveryDurabilityGate(
    private val certificateRoot: Path,
    expectedLineage: ClusterLineage,
    authorityEpoch: Long,
    executionManifestSha256: String,
    maxPending: Int = 4_096,
    timeoutMs: Long = 5_000,
    pollIntervalMs: Long = 5,
) : RecoveryDurabilityGate,
    AutoCloseable {
    private val gate = BoundedRecoveryDurabilityGate(expectedLineage, authorityEpoch, executionManifestSha256, maxPending, timeoutMs)
    private val reader =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "remote-recovery-certificate-reader").apply {
                isDaemon =
                    true
            }
        }

    @Volatile var lastReadFailure: String? = null
        private set

    init {
        require(pollIntervalMs > 0 && certificateRoot.isAbsolute)
        reader.scheduleWithFixedDelay({
            try {
                gate.acceptCertificate(RemoteRecoveryFiles.read(certificateRoot))
                lastReadFailure = null
            } catch (failure: Exception) {
                // An absent, partial, stale, or different-authority certificate never releases a reply.
                lastReadFailure = failure.javaClass.simpleName
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    override fun awaitProtected(
        lineage: ClusterLineage,
        position: Long,
    ): CompletionStage<Unit> = gate.awaitProtected(lineage, position)

    val certifiedThrough: Long get() = gate.certifiedThrough

    override fun close() {
        reader.shutdownNow()
        gate.close()
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommand
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Archive와 클러스터의 정체성을 고정하고, 한 기동의 파생 이력만 소유한다. */
internal class DevelopmentFundingRun private constructor(
    private val lockChannel: FileChannel,
    private val lock: FileLock,
    private val generation: Path,
    /** 같은 로컬 기록의 설정 재사용을 확인한다. */
    val identitySha256: String,
    /** 다른 멤버에서 복제한 Snapshot에도 같은 업무 상태와 스키마를 요구한다. */
    val snapshotIdentitySha256: String,
) : AutoCloseable {
    val historyDirectory: Path = generation.resolve("store")

    override fun close() = close(removeHistory = true)

    fun close(removeHistory: Boolean) {
        try {
            // 호출자는 RocksDB를 먼저 닫는다. 다른 기동의 이력과 Archive는 삭제하지 않는다.
            if (removeHistory) {
                Files.walk(generation).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            }
        } finally {
            try {
                lock.release()
            } finally {
                lockChannel.close()
            }
        }
    }

    companion object {
        private const val IDENTITY_FILE = "recovery-identity.bin"
        private const val IDENTITY_LIMIT = 1_048_576L
        private const val FORMAT =
            "exchange-trading-recovery-v1;clock=milliseconds;admission-schema=87;admission-version=2;checkpoint=1;history=1;aeron=1.53.3"

        fun open(config: DevelopmentFundingSettings): DevelopmentFundingRun {
            val root = config.runRoot
            require(root.normalize() == root && !Files.isSymbolicLink(root)) { "실행 루트는 정규 경로여야 합니다" }
            if (config.recoverExisting) {
                require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "복구할 실행 루트가 없습니다" }
            } else {
                Files.createDirectory(root)
            }
            val channel = FileChannel.open(root.resolve("recovery.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
            var lock: FileLock? = null
            try {
                lock = checkNotNull(channel.tryLock()) { "같은 실행 루트를 다른 노드가 사용 중입니다" }
                val bytes = identity(config)
                val identityFile = root.resolve(IDENTITY_FILE)
                if (config.recoverExisting) {
                    require(Files.isRegularFile(identityFile, NOFOLLOW_LINKS) && Files.size(identityFile) in 1..IDENTITY_LIMIT) {
                        "복구 정체성 파일이 없거나 잘못됐습니다"
                    }
                    require(Files.readAllBytes(identityFile).contentEquals(bytes)) {
                        "복구 설정이 원래 클러스터·마켓·수수료 정책과 다릅니다"
                    }
                    require(Files.isDirectory(config.archiveDirectory, NOFOLLOW_LINKS)) { "복구할 Archive가 없습니다" }
                    require(Files.isDirectory(config.clusterDirectory, NOFOLLOW_LINKS)) { "복구할 클러스터 기록이 없습니다" }
                    // 빈 디렉터리를 새 클러스터로 기동하고 복구 성공으로 오인하지 않는다.
                    requireRecordedFile(config.archiveDirectory.resolve("archive.catalog"))
                    requireRecordedFile(config.clusterDirectory.resolve("recording.log"))
                } else {
                    writeIdentity(root, identityFile, bytes)
                }
                val snapshotIdentitySha256 = sha256(snapshotIdentity(config))
                val generation = Files.createTempDirectory(root, "history-generation-")
                return DevelopmentFundingRun(channel, lock, generation, sha256(bytes), snapshotIdentitySha256)
            } catch (failure: Throwable) {
                try {
                    lock?.release()
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                try {
                    channel.close()
                } catch (closing: Throwable) {
                    failure.addSuppressed(closing)
                }
                throw failure
            }
        }

        private fun requireRecordedFile(path: Path) {
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) > 0) { "복구 기록이 없거나 비어 있습니다: $path" }
        }

        private fun writeIdentity(
            root: Path,
            target: Path,
            bytes: ByteArray,
        ) {
            val temporary = Files.createTempFile(root, ".recovery-identity-", ".tmp")
            try {
                FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temporary, target, ATOMIC_MOVE)
                FileChannel.open(root, READ).use { it.force(true) }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun identity(config: DevelopmentFundingSettings): ByteArray =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeSchemaIdentity()
                    output.writeInt(config.clusterId)
                    output.writeInt(config.serviceId)
                    output.writeInt(config.memberId)
                    output.writeUTF(config.members)
                    output.writeUTF(config.advertisedHost)
                    output.writeUTF(config.bindHost)
                    output.writeInt(config.basePort)
                    output.writeInt(config.archiveFileSyncLevel)
                    output.writeBoolean(config.runId != null)
                    config.runId?.let(output::writeUTF)
                    output.writeMarkets(config.markets)
                }
                bytes.toByteArray().also { require(it.size <= IDENTITY_LIMIT) { "복구 설정 크기 한도를 초과했습니다" } }
            }

        /** Aeron은 다른 멤버의 Snapshot recording을 바이트 그대로 복제한다. 로컬 주소는 포함하지 않는다. */
        private fun snapshotIdentity(config: DevelopmentFundingSettings): ByteArray =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeUTF("exchange-trading-snapshot-identity-v1")
                    output.writeSchemaIdentity()
                    output.writeInt(config.clusterId)
                    output.writeInt(config.serviceId)
                    output.writeUTF(config.members)
                    output.writeBoolean(config.runId != null)
                    config.runId?.let(output::writeUTF)
                    output.writeMarkets(config.markets)
                }
                bytes.toByteArray()
            }

        private fun DataOutputStream.writeSchemaIdentity() {
            writeUTF(FORMAT)
            writeUTF(
                sha256(
                    AdmissionCommand
                        .getDescriptor()
                        .file
                        .toProto()
                        .toByteArray(),
                ),
            )
        }

        private fun DataOutputStream.writeMarkets(markets: DevelopmentMarketRegistry) {
            writeBoolean(markets.configurationVersion != null)
            markets.configurationVersion?.let(::writeLong)
            markets.contentSha256?.let(::writeUTF)
            val contexts = markets.all()
            writeInt(contexts.size)
            for ((market, policy) in contexts) {
                writeUTF(market.marketId.value)
                writeUTF(market.baseAssetId.value)
                writeUTF(market.quoteAssetId.value)
                writeInt(market.baseAssetScale)
                writeLong(market.priceTick)
                writeLong(market.quantityStep)
                writeUTF(policy.productType.name)
                writeUTF(policy.feeTier.name)
                writeLong(policy.scheduleVersion)
                writeLong(policy.feeRates.makerFeeRate.partsPerMillion)
                writeLong(policy.feeRates.takerFeeRate.partsPerMillion)
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

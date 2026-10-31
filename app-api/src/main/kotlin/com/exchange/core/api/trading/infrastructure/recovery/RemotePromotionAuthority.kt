package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 교체 노드를 기동 가능한 상태로 만들기 전에 백업 권한을 한 번만 사용한다.
 * 호출자는 권한 확보와 완료 동안 authority.lock을 잡는다. 일부만 생성하다 실패한 대상은 계속 차단하며,
 * 암묵적으로 덮어쓰지 않는다. 같은 백업으로 다른 대상을 선택할 수도 없다.
 */
internal object RemotePromotionAuthority {
    const val CLAIM = "promotion-claim.bin"
    private const val COMPLETE = "promotion-complete.bin"
    const val TARGET_AUTHORITY = "promotion-authority.bin"
    private const val MAGIC = 0x50524d33
    private const val END = 0x50444f4e

    internal data class Claim(
        val lineage: ClusterLineage,
        val previousEpoch: Long,
        val nextEpoch: Long,
        val proofSha256: String,
        val executionManifestSha256: String,
        val targetRoot: String,
        val targetSettingsSha256: String,
    )

    fun claim(
        root: Path,
        certificate: RemoteRecoveryCertificate,
        target: DevelopmentFundingSettings,
    ): Claim {
        val parent = requireNotNull(target.runRoot.parent).toRealPath()
        val targetPath = parent.resolve(target.runRoot.fileName).normalize().toString()
        val expected =
            Claim(
                certificate.lineage,
                certificate.authorityEpoch,
                Math.addExact(certificate.authorityEpoch, 1),
                certificate.recoveryManifestSha256,
                certificate.executionManifestSha256,
                targetPath,
                targetIdentity(target),
            )
        val path = root.resolve(CLAIM)
        if (Files.exists(path)) {
            require(read(path) == expected) { "this recovery authority is already bound to another promotion target" }
        } else {
            RemoteRecoveryFiles.writeForced(path, encode(expected))
        }
        return expected
    }

    fun completed(
        root: Path,
        claim: Claim,
        target: DevelopmentFundingSettings,
    ): Boolean {
        if (!Files.exists(root.resolve(COMPLETE))) return false
        require(read(root.resolve(COMPLETE)) == claim) { "promotion completion differs from its authority claim" }
        require(Files.isDirectory(target.runRoot) && read(target.runRoot.resolve(TARGET_AUTHORITY)) == claim)
        require(Files.isRegularFile(target.runRoot.resolve("recovery-identity.bin")))
        require(Files.isRegularFile(target.clusterDirectory.resolve("recording.log")))
        require(Files.isRegularFile(target.archiveDirectory.resolve("archive.catalog")))
        return true
    }

    fun complete(
        root: Path,
        target: Path,
        claim: Claim,
    ) {
        require(read(root.resolve(CLAIM)) == claim)
        RemoteRecoveryFiles.writeForced(target.resolve(TARGET_AUTHORITY), encode(claim))
        RemoteRecoveryFiles.writeForced(root.resolve(COMPLETE), encode(claim))
    }

    private fun targetIdentity(target: DevelopmentFundingSettings): String {
        val bytes =
            ByteArrayOutputStream()
                .also { stream ->
                    DataOutputStream(stream).use { out ->
                        out.writeInt(target.clusterId)
                        out.writeInt(target.serviceId)
                        out.writeInt(target.memberId)
                        out.writeUTF(target.members)
                        out.writeUTF(target.advertisedHost)
                        out.writeUTF(target.bindHost)
                        out.writeInt(target.basePort)
                        out.writeInt(target.archiveFileSyncLevel)
                        out.writeBoolean(target.runId != null)
                        target.runId?.let(out::writeUTF)
                        out.writeBoolean(target.boundedManifest != null)
                        target.boundedManifest?.let {
                            out.writeUTF(
                                RemoteRecoveryFiles.sha256(BoundedTradingStateCodec().encodeManifest(it)),
                            )
                        }
                    }
                }.toByteArray()
        return RemoteRecoveryFiles.sha256(bytes)
    }

    private fun encode(claim: Claim): ByteArray {
        val body =
            ByteArrayOutputStream()
                .also { stream ->
                    DataOutputStream(stream).use { out ->
                        out.writeInt(MAGIC)
                        out.writeLong(claim.lineage.high)
                        out.writeLong(claim.lineage.low)
                        out.writeLong(claim.previousEpoch)
                        out.writeLong(claim.nextEpoch)
                        out.writeUTF(claim.proofSha256)
                        out.writeUTF(claim.executionManifestSha256)
                        out.writeUTF(claim.targetRoot)
                        out.writeUTF(claim.targetSettingsSha256)
                        out.writeInt(END)
                    }
                }.toByteArray()
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun read(path: Path): Claim {
        require(Files.isRegularFile(path) && Files.size(path) in 33..16_384)
        val bytes = Files.readAllBytes(path)
        val body = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.copyOfRange(body.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC)
            val claim =
                Claim(
                    ClusterLineage(input.readLong(), input.readLong()),
                    input.readLong(),
                    input.readLong(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF(),
                )
            require(claim.previousEpoch > 0 && claim.nextEpoch == Math.addExact(claim.previousEpoch, 1))
            require(input.readInt() == END && input.read() == -1)
            claim
        }
    }
}

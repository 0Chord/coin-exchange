package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingRun
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.ClusterLineage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
    const val TARGET_REQUIRED = "promotion-required.bin"
    private const val TARGET_PROOF = "promoted-recovery-proof.bin"
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
        requireStartupAllowed(target)
        return true
    }

    /** 호출자는 복사 전부터 완료 게시까지 대상의 실행 잠금을 보유한다. 이 표시는 완료 후에도 보존한다. */
    fun beginTarget(
        target: DevelopmentFundingSettings,
        claim: Claim,
    ) {
        requireTargetSettings(target, claim)
        require(!Files.exists(target.runRoot.resolve(TARGET_REQUIRED), NOFOLLOW_LINKS))
        require(!Files.exists(target.runRoot.resolve(TARGET_AUTHORITY), NOFOLLOW_LINKS))
        RemoteRecoveryFiles.writeForced(target.runRoot.resolve(TARGET_REQUIRED), encode(claim))
    }

    /** 일반 재기동도 승격의 최종 허가를 확인하며, 차단 중인 대상의 Archive는 열지 않는다. */
    fun requireStartupAllowed(target: DevelopmentFundingSettings) {
        val root = target.runRoot
        if (listOf(TARGET_REQUIRED, TARGET_AUTHORITY, TARGET_PROOF).none { Files.exists(root.resolve(it), NOFOLLOW_LINKS) }) return
        require(
            Files.isRegularFile(root.resolve(TARGET_REQUIRED), NOFOLLOW_LINKS) &&
                Files.isRegularFile(root.resolve(TARGET_AUTHORITY), NOFOLLOW_LINKS),
        ) { "승격 준비가 완료되지 않아 기동할 수 없습니다" }
        val claim = read(root.resolve(TARGET_REQUIRED))
        require(read(root.resolve(TARGET_AUTHORITY)) == claim) { "승격 완료 권한이 대상과 다릅니다" }
        requireTargetSettings(target, claim)
        requirePreparedTarget(root, claim)
    }

    fun complete(
        root: Path,
        target: Path,
        claim: Claim,
    ) {
        require(read(root.resolve(CLAIM)) == claim)
        requirePreparedTarget(target, claim)
        // 백업의 완료 기록까지 강제 반영한 뒤 대상의 기동 허가를 마지막에 게시한다.
        RemoteRecoveryFiles.writeForced(root.resolve(COMPLETE), encode(claim))
        RemoteRecoveryFiles.writeForced(target.resolve(TARGET_AUTHORITY), encode(claim))
    }

    private fun requireTargetSettings(
        target: DevelopmentFundingSettings,
        claim: Claim,
    ) {
        require(claim.targetRoot == target.runRoot.toRealPath().toString()) { "승격 대상 경로가 다릅니다" }
        require(claim.targetSettingsSha256 == targetIdentity(target)) { "승격 대상의 기동 설정이 다릅니다" }
        val manifest = requireNotNull(target.boundedManifest)
        require(claim.lineage == manifest.lineage)
        require(claim.executionManifestSha256 == RemoteRecoveryFiles.sha256(BoundedTradingStateCodec().encodeManifest(manifest)))
    }

    private fun requirePreparedTarget(
        root: Path,
        claim: Claim,
    ) {
        require(read(root.resolve(TARGET_REQUIRED)) == claim) { "승격 준비 권한이 다릅니다" }
        val identity = root.resolve("recovery-identity.bin")
        require(Files.isRegularFile(identity, NOFOLLOW_LINKS) && Files.size(identity) in 1..1_048_576)
        require(RemoteRecoveryFiles.sha256(Files.readAllBytes(identity)) == claim.targetSettingsSha256)
        val proof = root.resolve(TARGET_PROOF)
        require(Files.isRegularFile(proof, NOFOLLOW_LINKS) && Files.size(proof) in 1..RemoteRecoveryFiles.MAX_PROOF_BYTES.toLong())
        require(RemoteRecoveryFiles.sha256(Files.readAllBytes(proof)) == claim.proofSha256)
        for (file in listOf(root.resolve("archive/archive.catalog"), root.resolve("cluster/recording.log"))) {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) > 0)
        }
    }

    private fun targetIdentity(target: DevelopmentFundingSettings): String = DevelopmentFundingRun.configurationIdentitySha256(target)

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
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 33..16_384)
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

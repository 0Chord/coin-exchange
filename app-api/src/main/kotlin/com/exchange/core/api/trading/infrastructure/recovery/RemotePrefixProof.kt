package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.trading.ClusterLineage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A certificate identifies a reproducible recovery set, rather than just a counter value. */
internal data class RemotePrefixProof(
    val lineage: ClusterLineage,
    val authorityEpoch: Long,
    val executionManifestSha256: String,
    val artifactIndexSha256: String,
    val clusterId: Int,
    val logRecordingId: Long,
    val recoverableThrough: Long,
    val snapshots: List<SnapshotProof>,
    val terms: List<RecoveryTerm>,
    val tailChainSha256: String,
) {
    init {
        require(authorityEpoch > 0 && recoverableThrough >= 0 && logRecordingId >= 0)
        require(snapshots.size == 2 && snapshots.map { it.entry.serviceId }.toSet() == setOf(-1, 0))
        val first = snapshots.first().entry
        require(first.logPosition <= recoverableThrough)
        require(
            snapshots.all {
                it.entry.logPosition == first.logPosition && it.entry.leadershipTermId == first.leadershipTermId &&
                    it.entry.termBaseLogPosition == first.termBaseLogPosition
            },
        )
        require(terms.isNotEmpty() && terms.size <= MAX_TERMS && terms.first().leadershipTermId == first.leadershipTermId)
        require(terms.all { it.recordingId == logRecordingId })
        require(terms.zipWithNext().all { (a, b) -> b.leadershipTermId > a.leadershipTermId && b.basePosition >= a.basePosition })
        require(terms.last().basePosition <= recoverableThrough)
        require(listOf(executionManifestSha256, artifactIndexSha256, tailChainSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(snapshots.all { it.streamSha256.matches(Regex("[0-9a-f]{64}")) })
    }

    fun encode(): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(MAGIC)
                    out.writeUTF("aeron=1.53.3;archiveSync=2;serviceCount=1;boundedSnapshot=2")
                    out.writeLong(lineage.high)
                    out.writeLong(lineage.low)
                    out.writeLong(authorityEpoch)
                    out.writeUTF(executionManifestSha256)
                    out.writeUTF(artifactIndexSha256)
                    out.writeInt(clusterId)
                    out.writeLong(logRecordingId)
                    out.writeLong(recoverableThrough)
                    snapshots.sortedBy { it.entry.serviceId }.forEach { (entry, hash) ->
                        out.writeLong(entry.recordingId)
                        out.writeLong(entry.leadershipTermId)
                        out.writeLong(entry.termBaseLogPosition)
                        out.writeLong(entry.logPosition)
                        out.writeLong(entry.timestamp)
                        out.writeInt(entry.serviceId)
                        out.writeInt(entry.type)
                        out.writeUTF(hash)
                    }
                    out.writeInt(terms.size)
                    for (term in terms) {
                        out.writeLong(term.recordingId)
                        out.writeLong(term.leadershipTermId)
                        out.writeLong(term.basePosition)
                        out.writeLong(term.timestamp)
                    }
                    out.writeUTF(tailChainSha256)
                    out.writeInt(END)
                }
            }.toByteArray()
            .also { require(it.size <= RemoteRecoveryFiles.MAX_PROOF_BYTES) }

    companion object {
        private const val MAGIC = 0x52505032
        private const val END = 0x50524f46
        private const val MAX_TERMS = 16_384
        val EMPTY_TAIL_SHA256: String = RemoteRecoveryFiles.sha256("exchange-remote-tail-v1".toByteArray())

        fun decode(bytes: ByteArray): RemotePrefixProof {
            require(bytes.size in 1..RemoteRecoveryFiles.MAX_PROOF_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC)
                require(input.readUTF() == "aeron=1.53.3;archiveSync=2;serviceCount=1;boundedSnapshot=2")
                val lineage = ClusterLineage(input.readLong(), input.readLong())
                val epoch = input.readLong()
                val execution = input.readUTF()
                val artifacts = input.readUTF()
                val cluster = input.readInt()
                val recording = input.readLong()
                val position = input.readLong()
                val snapshots =
                    List(2) {
                        SnapshotProof(
                            BackupRecordingEntry(
                                input.readLong(),
                                input.readLong(),
                                input.readLong(),
                                input.readLong(),
                                input.readLong(),
                                input.readInt(),
                                input.readInt(),
                            ),
                            input.readUTF(),
                        )
                    }
                val count = input.readInt()
                require(count in 1..MAX_TERMS)
                val terms = List(count) { RecoveryTerm(input.readLong(), input.readLong(), input.readLong(), input.readLong()) }
                val chain = input.readUTF()
                require(input.readInt() == END && input.read() == -1)
                RemotePrefixProof(lineage, epoch, execution, artifacts, cluster, recording, position, snapshots, terms, chain)
            }
        }
    }
}

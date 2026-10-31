package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingRun
import com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingSettings
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.RecordingLog
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 개발용 차단 증거는 원래의 JVM 세 개가 모두 살아 있을 때 수집한다.
 * 장애 이후에 전달한 boolean은 허용하지 않는다. 이 증거가 보장하는 범위는 로컬 프로세스와
 * 볼륨의 격리까지이며, 운영에서는 장애 영역을 가로지르는 인프라 차단이 필요하다.
 */
internal class PrimaryProcessFence private constructor(
    private val primaries: List<Primary>,
) {
    private data class Primary(
        val process: ProcessHandle,
        val startedAt: Instant,
        val root: Path,
    )

    fun verifyStoppedAndStorageAbsent() {
        for (primary in primaries) {
            require(!primary.process.isAlive) { "original primary process is still alive: ${primary.process.pid()}" }
            require(!Files.exists(primary.root, NOFOLLOW_LINKS)) { "original primary storage remains reachable: ${primary.root}" }
            val current = ProcessHandle.of(primary.process.pid())
            require(current.isEmpty || !current.get().isAlive) { "original primary PID has been reused; fencing is ambiguous" }
        }
    }

    companion object {
        fun capture(processesAndRoots: List<Pair<Process, Path>>): PrimaryProcessFence {
            require(processesAndRoots.size == 3)
            val captured =
                processesAndRoots.map { (process, root) ->
                    require(root.isAbsolute && root.normalize() == root && Files.isDirectory(root, NOFOLLOW_LINKS))
                    val handle = process.toHandle()
                    require(handle.isAlive)
                    require(
                        handle
                            .info()
                            .commandLine()
                            .orElse("")
                            .contains(root.toString()),
                    ) { "primary launch is not bound to its expected root" }
                    Primary(handle, handle.info().startInstant().orElseThrow(), root)
                }
            require(captured.map { it.process.pid() }.distinct().size == 3 && captured.map { it.root }.distinct().size == 3)
            return PrimaryProcessFence(captured)
        }
    }
}

/** 명시적으로 수행하는 재해 복구 작업이다. 기존 투표 멤버와 백업 작성자를 모두 차단해야 한다.
 * 네 번째 Archive를 한 번 복사하고 인증된 집합을 다시 검증한 뒤, 인증되지 않은 뒷부분을
 * 잘라 내고 실제 Aeron 복구 계획을 만든다. 금융 명령은 실행하지 않는다.
 */
internal object RemoteRecoveryPromotion {
    fun prepare(
        backupRoot: Path,
        target: DevelopmentFundingSettings,
        fence: PrimaryProcessFence,
    ): DevelopmentFundingSettings {
        require(backupRoot.isAbsolute && !Files.isSymbolicLink(backupRoot) && !Files.isSymbolicLink(target.runRoot))
        require(!target.recoverExisting && target.archiveFileSyncLevel == 2)
        val manifest = requireNotNull(target.boundedManifest)
        fence.verifyStoppedAndStorageAbsent()
        FileChannel.open(backupRoot.resolve("authority.lock"), CREATE, WRITE).use { channel ->
            checkNotNull(channel.tryLock()) { "backup writer must be stopped before promotion" }.use {
                val (certificate, encodedProof) = RemoteRecoveryFiles.readProof(backupRoot)
                val proof = RemotePrefixProof.decode(encodedProof)
                require(proof.lineage == manifest.lineage && proof.authorityEpoch == certificate.authorityEpoch)
                require(
                    proof.recoverableThrough == certificate.recoverableThrough &&
                        proof.executionManifestSha256 == certificate.executionManifestSha256,
                )
                require(proof.clusterId == target.clusterId)
                require(proof.executionManifestSha256 == RemoteRecoveryFiles.sha256(BoundedTradingStateCodec().encodeManifest(manifest))) {
                    "promotion target full execution manifest differs"
                }
                val authority = RemotePromotionAuthority.claim(backupRoot, certificate, target)
                if (RemotePromotionAuthority.completed(backupRoot, authority, target)) return target.copy(recoverExisting = true)
                require(!Files.exists(target.runRoot)) { "incomplete promotion remains fenced; automatic target overwrite is forbidden" }
                val bundle = RetainedExecutionBundle.verify(backupRoot, manifest)
                require(
                    bundle.executionManifestSha256 == proof.executionManifestSha256 &&
                        bundle.artifactIndexSha256 == proof.artifactIndexSha256,
                )
                val sourceEntries =
                    RecordingLog(backupRoot.resolve("cluster").toFile(), false).use { log ->
                        log.entries().filter { entry -> entry.isValid }.map { entry ->
                            BackupRecordingEntry(
                                entry.recordingId,
                                entry.leadershipTermId,
                                entry.termBaseLogPosition,
                                entry.logPosition,
                                entry.timestamp,
                                entry.serviceId,
                                entry.type,
                            )
                        }
                    }
                require(proof.snapshots.all { it.entry in sourceEntries }) { "snapshot recovery metadata differs" }
                require(
                    proof.terms.all { term ->
                        sourceEntries.any { entry ->
                            entry.type == RecordingLog.ENTRY_TYPE_TERM &&
                                entry.recordingId == term.recordingId &&
                                entry.leadershipTermId == term.leadershipTermId &&
                                entry.termBaseLogPosition == term.basePosition
                        }
                    },
                )

                // 유실된 호스트 대신 교체 토폴로지에 맞는 새 로컬 루트 정체성을 만든다.
                DevelopmentFundingRun.open(target).use {
                    RemotePromotionAuthority.beginTarget(target, authority)
                    copyClosedArchive(backupRoot.resolve("archive"), target.archiveDirectory)
                    val driverDirectory = target.runRoot.resolve("promotion-driver").toString()
                    MediaDriver
                        .launch(
                            MediaDriver
                                .Context()
                                .aeronDirectoryName(driverDirectory)
                                .dirDeleteOnStart(true)
                                .dirDeleteOnShutdown(true)
                                .threadingMode(ThreadingMode.SHARED),
                        ).use {
                            Archive.launch(verificationArchiveContext(target.runRoot, driverDirectory)).use {
                                AeronArchive
                                    .connect(
                                        AeronArchive
                                            .Context()
                                            .aeronDirectoryName(driverDirectory)
                                            .controlRequestChannel("aeron:ipc")
                                            .controlResponseChannel("aeron:ipc")
                                            .messageTimeoutNs(TimeUnit.SECONDS.toNanos(20)),
                                    ).use { archive ->
                                        val verifier = ArchiveRecoveryProof(archive, manifest)
                                        require(
                                            proof.snapshots.map { verifier.verifySnapshot(it.entry) } == proof.snapshots,
                                        ) { "copied snapshot bytes differ" }
                                        val snapshotPosition =
                                            proof.snapshots
                                                .first()
                                                .entry.logPosition
                                        val tail =
                                            verifier.verifyTail(
                                                proof.logRecordingId,
                                                snapshotPosition,
                                                proof.recoverableThrough,
                                                proof.terms.first(),
                                                sourceEntries,
                                                RemotePrefixProof.EMPTY_TAIL_SHA256,
                                            )
                                        require(
                                            tail.through == proof.recoverableThrough && tail.terms == proof.terms &&
                                                tail.chainSha256 == proof.tailChainSha256,
                                        ) { "copied log cannot reproduce the certified prefix" }
                                        require(archive.getStopPosition(proof.logRecordingId) >= proof.recoverableThrough)
                                        if (archive.getStopPosition(proof.logRecordingId) >
                                            proof.recoverableThrough
                                        ) {
                                            archive.truncateRecording(proof.logRecordingId, proof.recoverableThrough)
                                        }
                                        require(archive.getStopPosition(proof.logRecordingId) == proof.recoverableThrough)
                                    }
                            }
                        }
                    Files.createDirectories(target.clusterDirectory)
                    RecordingLog(target.clusterDirectory.toFile(), true).use { log ->
                        proof.terms.forEachIndexed { index, term ->
                            log.appendTerm(term.recordingId, term.leadershipTermId, term.basePosition, term.timestamp)
                            val end = proof.terms.getOrNull(index + 1)?.basePosition ?: proof.recoverableThrough
                            log.commitLogPosition(term.leadershipTermId, end)
                        }
                        // Aeron은 RecordingLog에서 서비스 Snapshot이 consensus Snapshot보다 앞에 있어야 한다.
                        for (snapshot in proof.snapshots.sortedByDescending { it.entry.serviceId }) {
                            val entry = snapshot.entry
                            log.appendSnapshot(
                                entry.recordingId,
                                entry.leadershipTermId,
                                entry.termBaseLogPosition,
                                entry.logPosition,
                                entry.timestamp,
                                entry.serviceId,
                            )
                        }
                        log.force(2)
                    }
                    FileChannel.open(target.clusterDirectory, READ).use { it.force(true) }
                    RemoteRecoveryFiles.writeForced(target.runRoot.resolve("promoted-recovery-proof.bin"), encodedProof)
                    fence.verifyStoppedAndStorageAbsent()
                    RemotePromotionAuthority.complete(backupRoot, target.runRoot, authority)
                }
                return target.copy(recoverExisting = true)
            }
        }
    }

    /** UDP control을 비활성화하면 Aeron 1.53.3은 내부 Archive client의 응답 endpoint를 추론할 수 없다.
     * 검증기와 내부 client는 모두 로컬 IPC로 통신한다.
     */
    internal fun verificationArchiveContext(
        targetRoot: Path,
        driverDirectory: String,
    ): Archive.Context =
        Archive
            .Context()
            .aeronDirectoryName(driverDirectory)
            .archiveDir(targetRoot.resolve("archive").toFile())
            .markFileDir(targetRoot.resolve("promotion-runtime").toFile())
            .fileSyncLevel(2)
            .catalogFileSyncLevel(2)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .controlChannelEnabled(false)
            .localControlChannel("aeron:ipc")
            .archiveClientContext(AeronArchive.Context().controlRequestChannel("aeron:ipc").controlResponseChannel("aeron:ipc"))
            .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")

    internal fun copyClosedArchive(
        source: Path,
        destination: Path,
    ) {
        require(Files.isDirectory(source, NOFOLLOW_LINKS) && !Files.exists(destination))
        Files.createDirectory(destination)
        var count = 0
        var total = 0L
        // 고정 버전 Aeron 1.53.3의 영속 recording 파일은 catalog와 recording segment로 구성된 평면 구조다.
        // archive-mark.dat의 heartbeat·오류 버퍼, mark 링크, 실행 잠금은 이전 프로세스에 속하며
        // 복구 권한의 근거가 아니다. 새 검증기는 실행에 필요한 파일을 직접 만든다.
        val recordingSegment = Regex("[0-9]+-[0-9]+\\.rec")
        Files.list(source).use { paths ->
            paths.forEach { path ->
                require(!Files.isSymbolicLink(path))
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "unsupported nested Archive layout" }
                val name = path.fileName.toString()
                if (name != "archive.catalog" && !recordingSegment.matches(name)) return@forEach
                val target = destination.resolve(source.relativize(path))
                count++
                total = Math.addExact(total, Files.size(path))
                require(
                    count <= 100_000 && total <= 64L * 1024 * 1024 * 1024,
                ) { "development recovery copy exceeds its explicit disk budget" }
                Files.copy(path, target)
                FileChannel.open(target, WRITE).use { it.force(true) }
            }
        }
        require(Files.isRegularFile(destination.resolve("archive.catalog"), NOFOLLOW_LINKS))
        Files.walk(destination).use { paths ->
            paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).forEach { directory ->
                FileChannel.open(directory, READ).use { it.force(true) }
            }
        }
    }
}

package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.TradingClusterManifest
import io.aeron.archive.Archive
import io.aeron.archive.ArchiveThreadingMode
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.ClusterBackup
import io.aeron.cluster.ClusterBackupEventsListener
import io.aeron.cluster.ClusterMember
import io.aeron.cluster.RecordingLog
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.ErrorHandler
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class RemoteAeronBackupSettings(
    val runRoot: Path,
    val manifest: TradingClusterManifest,
    val consensusEndpoints: String,
    val advertisedHost: String = "127.0.0.1",
    val basePort: Int = 26_000,
    val clusterId: Int = 97,
    val authorityEpoch: Long = 1,
) {
    init {
        require(runRoot.isAbsolute && runRoot.normalize() == runRoot && !Files.isSymbolicLink(runRoot))
        require(consensusEndpoints.isNotBlank() && advertisedHost.isNotBlank() && advertisedHost != "0.0.0.0")
        require(basePort in 1024..65530 && authorityEpoch > 0 && clusterId >= 0)
    }
}

/** A fourth process owns its own Driver, Archive and ClusterBackup. It is not a voting member.
 * A successful backup counter alone is insufficient: the certifier also validates a complete
 * snapshot set, exact execution bundle, and a committed, forced, message-complete log prefix.
 */
class RemoteAeronBackup private constructor(
    val settings: RemoteAeronBackupSettings,
) : AutoCloseable {
    private val stopping = AtomicBoolean()
    private val fatal = AtomicReference<Throwable?>()
    private val metadata = AtomicReference<List<BackupRecordingEntry>>(emptyList())
    private val progress = AtomicReference<BackupLogProgress?>()
    private val stopped = CountDownLatch(1)
    private lateinit var lockChannel: FileChannel
    private lateinit var lock: FileLock
    private var driver: MediaDriver? = null
    private var archive: Archive? = null
    private var backup: ClusterBackup? = null
    private var certifier: Thread? = null

    @Volatile var certificate: RemoteRecoveryCertificate? = null
        private set

    @Volatile var lastCertificationFailure: String? = null
        private set

    private fun start(): RemoteAeronBackup {
        val root = settings.runRoot
        Files.createDirectories(root)
        lockChannel = FileChannel.open(root.resolve("authority.lock"), CREATE, WRITE)
        lock = checkNotNull(lockChannel.tryLock()) { "backup authority already has a writer" }
        require(
            !Files.exists(root.resolve(RemotePromotionAuthority.CLAIM)),
        ) { "backup authority was consumed by a durable promotion claim" }
        val manifestBytes = BoundedTradingStateCodec().encodeManifest(settings.manifest)
        val authority = "${settings.manifest.lineage.high}:${settings.manifest.lineage.low}:${settings.authorityEpoch}".toByteArray()
        val identity = root.resolve("authority-identity.bin")
        if (Files.exists(identity)) {
            require(Files.readAllBytes(identity).contentEquals(authority)) { "backup lineage or authority epoch differs" }
            // An existing published set must go through fenced promotion, not a fresh writer reusing X.
            require(
                !Files.exists(root.resolve(RemoteRecoveryFiles.CERTIFICATE_FILE)),
            ) { "published backup requires explicit fenced recovery" }
        } else {
            RemoteRecoveryFiles.writeForced(identity, authority)
        }
        val bundle = RetainedExecutionBundle.retain(root, settings.manifest)
        require(bundle.executionManifestSha256 == RemoteRecoveryFiles.sha256(manifestBytes))
        val errors = ErrorHandler { failure -> fatal.compareAndSet(null, failure) }
        val directory = root.resolve("driver").toString()
        driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(directory)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(false)
                    .threadingMode(ThreadingMode.SHARED)
                    .errorHandler(errors),
            )
        archive =
            Archive.launch(
                Archive
                    .Context()
                    .aeronDirectoryName(directory)
                    .archiveDir(root.resolve("archive").toFile())
                    .fileSyncLevel(2)
                    .catalogFileSyncLevel(2)
                    .threadingMode(ArchiveThreadingMode.SHARED)
                    .controlChannel("aeron:udp?endpoint=${settings.advertisedHost}:${settings.basePort + 2}")
                    .localControlChannel("aeron:ipc")
                    .replicationChannel("aeron:udp?endpoint=${settings.advertisedHost}:${settings.basePort + 3}")
                    .errorHandler(errors),
            )
        val listener =
            object : ClusterBackupEventsListener {
                override fun onBackupQuery() = Unit

                override fun onPossibleFailure(ex: Exception) {
                    lastCertificationFailure = ex.javaClass.simpleName
                }

                override fun onBackupResponse(
                    members: Array<ClusterMember>,
                    source: ClusterMember,
                    snapshots: List<RecordingLog.Snapshot>,
                ) = Unit

                override fun onUpdatedRecordingLog(
                    recordingLog: RecordingLog,
                    snapshots: List<RecordingLog.Snapshot>,
                ) {
                    // Called after RecordingLog.force(2), on its owning agent thread.
                    metadata.set(
                        recordingLog.entries().filter { it.isValid }.map {
                            BackupRecordingEntry(
                                it.recordingId,
                                it.leadershipTermId,
                                it.termBaseLogPosition,
                                it.logPosition,
                                it.timestamp,
                                it.serviceId,
                                it.type,
                            )
                        },
                    )
                }

                override fun onLiveLogProgress(
                    recordingId: Long,
                    recordingPosCounterId: Long,
                    logPosition: Long,
                ) {
                    progress.set(BackupLogProgress(recordingId, logPosition))
                }
            }
        val context =
            ClusterBackup
                .Context()
                .clusterId(settings.clusterId)
                .aeronDirectoryName(directory)
                .clusterDir(root.resolve("cluster").toFile())
                .archiveContext(localArchive(directory))
                .clusterArchiveContext(
                    AeronArchive
                        .Context()
                        .controlRequestChannel("aeron:udp")
                        .controlResponseChannel("aeron:udp?endpoint=${settings.advertisedHost}:${settings.basePort + 4}"),
                ).consensusChannel("aeron:udp?endpoint=${settings.advertisedHost}:${settings.basePort}")
                .catchupEndpoint("${settings.advertisedHost}:${settings.basePort + 1}")
                .clusterConsensusEndpoints(settings.consensusEndpoints)
                .sourceType(ClusterBackup.SourceType.LEADER)
                .initialReplayStart(ClusterBackup.Configuration.ReplayStart.BEGINNING)
                .clusterBackupIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                .eventsListener(listener)
                .errorHandler(errors)
        backup = ClusterBackup.launch(context)
        certifier =
            Thread({ certify(directory, bundle) }, "remote-recovery-certifier").apply {
                isDaemon = true
                start()
            }
        return this
    }

    private fun certify(
        directory: String,
        bundle: RetainedBundleProof,
    ) {
        try {
            AeronArchive.connect(localArchive(directory)).use { local ->
                val verifier = ArchiveRecoveryProof(local, settings.manifest)
                var pinnedSnapshots: List<SnapshotProof>? = null
                var recordingId = -1L
                var through = -1L
                var chain = RemotePrefixProof.EMPTY_TAIL_SHA256
                var terms = emptyList<RecoveryTerm>()
                while (!stopping.get() && fatal.get() == null) {
                    try {
                        val actual = requireNotNull(backup).context()
                        if (actual.stateCounter().get() !=
                            ClusterBackup.State.BACKING_UP
                                .code()
                                .toLong()
                        ) {
                            Thread.sleep(5)
                            continue
                        }
                        val entries = metadata.get()
                        if (pinnedSnapshots == null) {
                            val consensus = entries.lastOrNull { it.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && it.serviceId == -1 }
                            if (consensus == null) {
                                Thread.sleep(5)
                                continue
                            }
                            val service =
                                entries.lastOrNull {
                                    it.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && it.serviceId == 0 &&
                                        it.logPosition == consensus.logPosition &&
                                        it.leadershipTermId == consensus.leadershipTermId &&
                                        it.termBaseLogPosition == consensus.termBaseLogPosition
                                }
                            if (service == null) {
                                Thread.sleep(5)
                                continue
                            }
                            val log =
                                entries.lastOrNull {
                                    it.type == RecordingLog.ENTRY_TYPE_TERM &&
                                        it.leadershipTermId == consensus.leadershipTermId &&
                                        it.termBaseLogPosition == consensus.termBaseLogPosition
                                }
                            if (log == null) {
                                Thread.sleep(5)
                                continue
                            }
                            pinnedSnapshots = listOf(verifier.verifySnapshot(service), verifier.verifySnapshot(consensus))
                            recordingId = log.recordingId
                            through = consensus.logPosition
                            terms = listOf(RecoveryTerm(recordingId, log.leadershipTermId, log.termBaseLogPosition, log.timestamp))
                        }
                        // RecordingPos advances only after Archive's configured force(true) completes.
                        val durable =
                            maxOf(
                                progress.get()?.takeIf { it.recordingId == recordingId }?.position ?: -1,
                                actual.liveLogPositionCounter().get(),
                            )
                        if (durable < through) {
                            Thread.sleep(5)
                            continue
                        }
                        if (certificate != null && durable == through) {
                            Thread.sleep(5)
                            continue
                        }
                        val tail = verifier.verifyTail(recordingId, through, durable, terms.last(), entries, chain)
                        if (certificate != null && tail.through == through) {
                            Thread.sleep(5)
                            continue
                        }
                        val nextTerms = terms.dropLast(1) + tail.terms
                        val proof =
                            RemotePrefixProof(
                                settings.manifest.lineage,
                                settings.authorityEpoch,
                                bundle.executionManifestSha256,
                                bundle.artifactIndexSha256,
                                settings.clusterId,
                                recordingId,
                                tail.through,
                                requireNotNull(pinnedSnapshots),
                                nextTerms,
                                tail.chainSha256,
                            )
                        certificate =
                            RemoteRecoveryFiles.publish(
                                settings.runRoot,
                                proof.encode(),
                                settings.manifest.lineage,
                                settings.authorityEpoch,
                                tail.through,
                                bundle.executionManifestSha256,
                            )
                        through = tail.through
                        terms = nextTerms
                        chain = tail.chainSha256
                        lastCertificationFailure = null
                    } catch (
                        interrupted: InterruptedException,
                    ) {
                        if (!stopping.get()) throw interrupted
                    } catch (
                        failure: Exception,
                    ) {
                        lastCertificationFailure = failure.javaClass.simpleName
                        Thread.sleep(10)
                    }
                }
            }
        } catch (failure: Throwable) {
            if (!stopping.get()) fatal.compareAndSet(null, failure)
        } finally {
            stopped.countDown()
        }
    }

    fun checkHealthy() {
        fatal.get()?.let { throw IllegalStateException("remote backup stopped; no new public success is protected", it) }
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        certifier?.interrupt()
        certifier?.join(25_000)
        backup?.close()
        archive?.close()
        driver?.close()
        if (this::lock.isInitialized && lock.isValid) lock.release()
        if (this::lockChannel.isInitialized) lockChannel.close()
    }

    companion object {
        fun launch(settings: RemoteAeronBackupSettings): RemoteAeronBackup {
            val backup = RemoteAeronBackup(settings)
            try {
                return backup.start()
            } catch (failure: Throwable) {
                backup.close()
                throw failure
            }
        }

        private fun localArchive(directory: String) =
            AeronArchive
                .Context()
                .aeronDirectoryName(directory)
                .controlRequestChannel("aeron:ipc")
                .controlResponseChannel("aeron:ipc")
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10))
    }
}

/** Standalone fourth-process entry point. Never starts a primary or deletes existing recordings. */
object RemoteAeronBackupMain {
    @JvmStatic fun main(args: Array<String>) {
        val options =
            args.toList().chunked(2).associate {
                require(it.size == 2 && it[0].startsWith("--"))
                it[0] to it[1]
            }
        val root = Path.of(requireNotNull(options["--run-root"]))
        val manifest = BoundedTradingStateCodec().decodeManifest(Files.readAllBytes(Path.of(requireNotNull(options["--manifest"]))))
        val settings =
            RemoteAeronBackupSettings(
                root,
                manifest,
                requireNotNull(options["--consensus-endpoints"]),
                options["--host"] ?: "127.0.0.1",
                options["--base-port"]?.toInt() ?: 26_000,
                options["--cluster-id"]?.toInt() ?: 97,
                options["--authority-epoch"]?.toLong() ?: 1,
            )
        RemoteAeronBackup.launch(settings).use { backup ->
            val signal = CountDownLatch(1)
            Runtime.getRuntime().addShutdownHook(Thread { signal.countDown() })
            println("BACKUP_STARTED root=$root authority=${settings.authorityEpoch}")
            var published = -1L
            while (!signal.await(100, TimeUnit.MILLISECONDS)) {
                backup.checkHealthy()
                backup.certificate?.let {
                    if (it.recoverableThrough >
                        published
                    ) {
                        published = it.recoverableThrough
                        println("REMOTE_PROTECTED through=$published proof=${it.recoveryManifestSha256}")
                    }
                }
            }
        }
    }
}

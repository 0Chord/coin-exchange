package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingState
import io.aeron.FragmentAssembler
import io.aeron.Subscription
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.NewLeadershipTermEventDecoder
import io.aeron.cluster.codecs.SnapshotMark
import io.aeron.cluster.codecs.SnapshotMarkerDecoder
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

internal data class BackupRecordingEntry(
    val recordingId: Long,
    val leadershipTermId: Long,
    val termBaseLogPosition: Long,
    val logPosition: Long,
    val timestamp: Long,
    val serviceId: Int,
    val type: Int,
)

internal data class BackupLogProgress(
    val recordingId: Long,
    val position: Long,
)

internal data class RecoveryTerm(
    val recordingId: Long,
    val leadershipTermId: Long,
    val basePosition: Long,
    val timestamp: Long,
)

internal data class SnapshotProof(
    val entry: BackupRecordingEntry,
    val streamSha256: String,
)

internal data class TailProof(
    val through: Long,
    val terms: List<RecoveryTerm>,
    val chainSha256: String,
)

/** A finite Archive replay preserves message boundaries and never forwards application commands. */
private class RecoveryReplay(
    private val archive: AeronArchive,
    recordingId: Long,
    start: Long,
    length: Long,
    private val streamId: Int,
) : AutoCloseable {
    private val replayId = archive.startReplay(recordingId, start, length, "aeron:ipc", streamId)
    private val subscription: Subscription = archive.context().aeron().addSubscription("aeron:ipc?session-id=${replayId.toInt()}", streamId)
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    private var image: io.aeron.Image? = null
    var completedThrough: Long = start
        private set
    private val queue = ArrayDeque<Pair<ByteArray, Long>>()
    private val assembler =
        FragmentAssembler { buffer, offset, size, header ->
            require(size in 1..65_536) { "recovery message exceeds bound" }
            queue.addLast(ByteArray(size).also { buffer.getBytes(offset, it) } to header.position())
        }

    fun next(): Pair<ByteArray, Long>? {
        while (queue.isEmpty()) {
            if (image == null) image = subscription.imageBySessionId(replayId.toInt())
            val current = image
            if (current != null) {
                current.poll(assembler, 8)
                if (queue.isNotEmpty()) break
                if (current.isEndOfStream) return null
                check(!current.isClosed) { "recovery replay closed before its bound" }
            }
            archive.checkForErrorResponse()
            check(System.nanoTime() - deadline < 0) { "recovery replay timed out" }
            Thread.onSpinWait()
        }
        return queue.removeFirst().also { completedThrough = it.second }
    }

    override fun close() {
        subscription.close()
        try {
            archive.stopReplay(replayId)
        } catch (
            _: io.aeron.archive.client.ArchiveException,
        ) {
            // A finite replay may already have ended.
        }
    }
}

/** Validates real stopped snapshot recordings, including the app checksum and financial invariants. */
internal class ArchiveRecoveryProof(
    private val archive: AeronArchive,
    private val manifest: TradingClusterManifest,
) {
    private val verifiedSnapshots = hashMapOf<Long, SnapshotProof>()

    fun verifySnapshot(entry: BackupRecordingEntry): SnapshotProof =
        verifiedSnapshots.getOrPut(entry.recordingId) {
            val extent = recordingExtent(entry.recordingId)
            require(extent.first == 0L && extent.second > 0) { "snapshot recording is incomplete" }
            val digest = MessageDigest.getInstance("SHA-256")
            RecoveryReplay(archive, entry.recordingId, 0, extent.second, 31_010 + entry.serviceId + 1).use { replay ->
                var begin = false
                var typeId = -1L
                var frameworkEnd = false

                fun nextHashed(): ByteArray? =
                    replay.next()?.first?.also { bytes ->
                        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                        digest.update(bytes)
                    }
                while (!frameworkEnd) {
                    val bytes = checkNotNull(nextHashed()) { "snapshot marker is missing" }
                    val buffer = org.agrona.concurrent.UnsafeBuffer(bytes)
                    val header = MessageHeaderDecoder().wrap(buffer, 0)
                    require(bytes.size >= MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength())
                    if (header.templateId() == SnapshotMarkerDecoder.TEMPLATE_ID) {
                        val marker =
                            SnapshotMarkerDecoder().wrap(
                                buffer,
                                MessageHeaderDecoder.ENCODED_LENGTH,
                                header.blockLength(),
                                header.version(),
                            )
                        require(marker.logPosition() == entry.logPosition && marker.leadershipTermId() == entry.leadershipTermId)
                        when (marker.mark()) {
                            SnapshotMark.BEGIN -> {
                                require(!begin)
                                begin = true
                                typeId = marker.typeId()
                            }

                            SnapshotMark.END -> {
                                require(begin && marker.typeId() == typeId)
                                frameworkEnd = true
                            }

                            else -> {
                                error("unsupported snapshot marker")
                            }
                        }
                    } else {
                        require(begin) { "snapshot does not begin with a marker" }
                    }
                }
                if (entry.serviceId == 0) {
                    val input =
                        object : InputStream() {
                            private var bytes = ByteArray(0)
                            private var offset = 0

                            override fun read(): Int {
                                while (offset == bytes.size) {
                                    bytes = nextHashed() ?: return -1
                                    offset = 0
                                }
                                return bytes[offset++].toInt() and 0xff
                            }

                            override fun read(
                                target: ByteArray,
                                start: Int,
                                count: Int,
                            ): Int {
                                if (count == 0) return 0
                                while (offset == bytes.size) {
                                    bytes = nextHashed() ?: return -1
                                    offset = 0
                                }
                                val copied = minOf(count, bytes.size - offset)
                                bytes.copyInto(target, start, offset, offset + copied)
                                offset += copied
                                return copied
                            }
                        }
                    val recovered = BoundedTradingStateCodec().read(input, manifest, entry.logPosition)
                    validationOwner().restoreRecoveryState(recovered)
                } else {
                    require(entry.serviceId == -1 && nextHashed() == null) { "unexpected consensus snapshot tail" }
                }
            }
            SnapshotProof(entry, HexFormat.of().formatHex(digest.digest()))
        }

    /** The backup's committed-bounded replay is itself forced before its RecordingPos advances.
     * Parse complete messages, and require every observed leadership term to exist in RecordingLog.
     */
    fun verifyTail(
        recordingId: Long,
        start: Long,
        upper: Long,
        first: RecoveryTerm,
        metadata: List<BackupRecordingEntry>,
        initialChainSha256: String,
    ): TailProof {
        require(upper >= start)
        val extent = recordingExtent(recordingId)
        require(extent.first <= start && extent.first == 0L) { "backup must retain its beginning, including genesis" }
        val available = if (extent.second >= 0) extent.second else archive.getRecordingPosition(recordingId)
        require(available >= upper) { "remote archive has not durably recorded the requested prefix" }
        val terms = arrayListOf(first)
        var chain = initialChainSha256
        var through = start
        if (upper > start) {
            RecoveryReplay(archive, recordingId, start, upper - start, 31_030).use { replay ->
                while (true) {
                    val (bytes, position) = replay.next() ?: break
                    require(position in (through + 1)..upper)
                    val buffer = org.agrona.concurrent.UnsafeBuffer(bytes)
                    val header = MessageHeaderDecoder().wrap(buffer, 0)
                    require(bytes.size >= MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength())
                    if (header.templateId() == NewLeadershipTermEventDecoder.TEMPLATE_ID) {
                        val term =
                            NewLeadershipTermEventDecoder().wrap(
                                buffer,
                                MessageHeaderDecoder.ENCODED_LENGTH,
                                header.blockLength(),
                                header.version(),
                            )
                        val previous = terms.last()
                        require(term.leadershipTermId() > previous.leadershipTermId && term.termBaseLogPosition() >= previous.basePosition)
                        require(term.termBaseLogPosition() <= position)
                        terms.add(RecoveryTerm(recordingId, term.leadershipTermId(), term.termBaseLogPosition(), term.timestamp()))
                    }
                    through = position
                    val hash = MessageDigest.getInstance("SHA-256")
                    hash.update(chain.toByteArray(Charsets.US_ASCII))
                    hash.update(ByteBuffer.allocate(8).putLong(position).array())
                    hash.update(bytes)
                    chain = HexFormat.of().formatHex(hash.digest())
                }
            }
        }
        for (term in terms) {
            require(
                metadata.any {
                    it.type == RecordingLog.ENTRY_TYPE_TERM && it.recordingId == term.recordingId &&
                        it.leadershipTermId == term.leadershipTermId && it.termBaseLogPosition == term.basePosition
                },
            ) {
                "recording log does not yet prove the complete term chain"
            }
        }
        return TailProof(through, terms, chain)
    }

    fun recordingExtent(recordingId: Long): Pair<Long, Long> {
        var extent: Pair<Long, Long>? = null
        val found = archive.listRecording(recordingId) { _, _, _, _, _, start, stop, _, _, _, _, _, _, _, _, _ -> extent = start to stop }
        require(found == 1)
        return requireNotNull(extent)
    }

    private fun validationOwner(): TradingState {
        val fee = TradingFeeReserveCalculator()
        val reserve = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fee))
        return TradingState(
            OrderAdmissionCalculator(reserve),
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), fee),
                reserve,
                fee,
                SettlementLedgerTransactions(),
            ),
            boundedMode = true,
        )
    }
}

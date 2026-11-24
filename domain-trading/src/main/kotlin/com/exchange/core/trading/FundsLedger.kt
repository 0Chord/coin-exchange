package com.exchange.core.trading

import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.OrderReservation
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat

/** 한 자금 명령 또는 정산 결과의 분개다. 원장 순번과 원본 로그 위치를 함께 보존한다. */
class FundsLedgerBatch(
    val lineage: ClusterLineage,
    val sequence: Long,
    val logPosition: Long,
    transactions: List<LedgerTransaction>,
) {
    val transactions: List<LedgerTransaction> = Collections.unmodifiableList(transactions.toList())
    val canonicalBytes: Long

    init {
        require(sequence > 0 && logPosition > 0 && this.transactions.isNotEmpty())
        val kind = this.transactions.first().transactionType
        require(kind in SUPPORTED_TYPES)
        require(this.transactions.size == 1 || kind == LedgerTransactionType.SETTLEMENT)
        var bytes = 64L
        for (transaction in this.transactions) {
            require(transaction.transactionType == kind && transaction.postings.size <= 10)
            bytes += 64L + tradingTextBytes(transaction.ledgerTransactionId) + tradingTextBytes(transaction.sourceEventId)
            for (posting in transaction.postings) {
                bytes += 32L + tradingTextBytes(posting.accountId) + tradingTextBytes(posting.assetId.value)
            }
        }
        require(bytes <= MAX_CANONICAL_BYTES) { "자금 명령의 분개 전송 한도를 초과했습니다" }
        canonicalBytes = bytes
    }

    /** 정산 전송 한 건에 든 결과들은 같은 로그 위치를 원장 순번으로 나눈다. */
    fun followsLogPosition(previous: Long): Boolean {
        val kind = transactions.first().transactionType
        return logPosition > previous ||
            (logPosition == previous && (kind == LedgerTransactionType.SETTLEMENT || kind == LedgerTransactionType.RELEASE))
    }

    /** 저장소에서도 재계산할 수 있도록 값의 순서와 인코딩을 고정한다. */
    fun digestAfter(previous: String): String {
        require(validDigest(previous))
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { output ->
            output.writeByte(1)
            output.write(HEX.parseHex(previous))
            output.writeLong(lineage.high)
            output.writeLong(lineage.low)
            output.writeLong(sequence)
            output.writeLong(logPosition)
            output.writeInt(transactions.size)
            for (transaction in transactions) {
                output.writeText(transaction.ledgerTransactionId)
                output.writeText(transaction.sourceEventId)
                output.writeText(transaction.transactionType.name)
                output.writeLong(transaction.occurredAt.epochSecond)
                output.writeInt(transaction.occurredAt.nano)
                output.writeInt(transaction.postings.size)
                for (posting in transaction.postings) {
                    output.writeText(posting.accountId)
                    output.writeText(posting.assetId.value)
                    output.writeText(posting.side.name)
                    output.writeLong(posting.amount.value)
                }
            }
        }
        return HEX.formatHex(digest.digest())
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        // 체결에는 같은 분개 외에 체결 정보도 실어야 하므로 기존 정산 전송 한도의 여유를 재사용한다.
        const val MAX_CANONICAL_BYTES = 2L * 1024 * 1024 - 64
        const val GENESIS_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        private val HEX = HexFormat.of()
        private val SUPPORTED_TYPES =
            setOf(
                LedgerTransactionType.OPENING,
                LedgerTransactionType.RESERVE,
                LedgerTransactionType.SETTLEMENT,
                LedgerTransactionType.RELEASE,
            )

        internal fun validDigest(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

data class FundsLedgerProgress(
    val issued: Long,
    val retiredThrough: Long,
    val completeFromGenesis: Boolean,
    val digest: String,
) {
    init {
        require(retiredThrough in 0..issued && FundsLedgerBatch.validDigest(digest))
        require(issued > 0L || digest == FundsLedgerBatch.GENESIS_DIGEST)
    }
}

/** 잔고와 활성 예약은 금융 소유자의 콜백 한 번에서 조회한다. */
class FundsRecoveryAccounts internal constructor(
    balances: List<Balance>,
    reservations: List<OrderReservation>,
    val nextOffset: Int?,
) {
    val balances: List<Balance> = Collections.unmodifiableList(balances)
    val reservations: List<OrderReservation> = Collections.unmodifiableList(reservations)
}

class FundsLedgerRecovery(
    val completeFromGenesis: Boolean,
    val retiredThrough: Long,
    val retiredLogPosition: Long,
    val retiredDigest: String,
    val digest: String,
    batches: List<FundsLedgerBatch>,
) {
    val batches: List<FundsLedgerBatch> = Collections.unmodifiableList(batches.toList())

    companion object {
        const val MAX_RETAINED_ENTRIES = 4_096
        const val MAX_RETAINED_BYTES = 4L * 1024 * 1024
        const val SNAPSHOT_BYTES = MAX_RETAINED_BYTES + 1_024
    }
}

/** 커밋을 확인받기 전까지 필요한 분개만 같은 자금 소유 스레드에 보관한다. */
internal class FundsLedger(
    private val lineage: ClusterLineage,
    private val completeFromGenesis: Boolean = true,
) {
    private data class Retained(
        val batch: FundsLedgerBatch,
        val digest: String,
    )

    private var retiredThrough = 0L
    private var retiredLogPosition = 0L
    private var retiredDigest = FundsLedgerBatch.GENESIS_DIGEST
    private var issued = 0L
    private var lastLogPosition = 0L
    private var digest = FundsLedgerBatch.GENESIS_DIGEST
    private var retainedBytes = 0L
    private val retained = linkedMapOf<Long, Retained>()

    fun prepare(
        atLogPosition: Long,
        transactions: List<LedgerTransaction>,
        leaveRoomForResult: Boolean = false,
    ): FundsLedgerBatch? {
        val batch = FundsLedgerBatch(lineage, Math.addExact(issued, 1L), atLogPosition, transactions)
        require(batch.followsLogPosition(lastLogPosition)) { "자금 원장 위치가 올바르지 않습니다" }
        // DB가 밀려도 앞서 접수된 주문의 정산·반환이 먼저 진행될 수 있게 여유를 둔다.
        val spareEntries = if (leaveRoomForResult) 1 else 0
        val spareBytes = if (leaveRoomForResult) FundsLedgerBatch.MAX_CANONICAL_BYTES else 0L
        val entryLimit = FundsLedgerRecovery.MAX_RETAINED_ENTRIES - spareEntries
        val byteLimit = FundsLedgerRecovery.MAX_RETAINED_BYTES - spareBytes
        if (retained.size >= entryLimit || batch.canonicalBytes > byteLimit - retainedBytes) return null
        return batch
    }

    fun record(batch: FundsLedgerBatch) {
        require(batch.lineage == lineage && batch.sequence == Math.addExact(issued, 1L) && batch.followsLogPosition(lastLogPosition))
        require(retained.size < FundsLedgerRecovery.MAX_RETAINED_ENTRIES)
        require(batch.canonicalBytes <= FundsLedgerRecovery.MAX_RETAINED_BYTES - retainedBytes)
        val nextDigest = batch.digestAfter(digest)
        retained[batch.sequence] = Retained(batch, nextDigest)
        issued = batch.sequence
        lastLogPosition = batch.logPosition
        digest = nextDigest
        retainedBytes += batch.canonicalBytes
    }

    fun read(sequence: Long): FundsLedgerBatch? = retained[sequence]?.batch

    fun progress(): FundsLedgerProgress = FundsLedgerProgress(issued, retiredThrough, completeFromGenesis, digest)

    fun acknowledge(through: Long): Boolean {
        if (through !in 0..issued) return false
        val entries = retained.values.iterator()
        while (entries.hasNext()) {
            val (batch, batchDigest) = entries.next()
            if (batch.sequence > through) break
            retiredThrough = batch.sequence
            retiredLogPosition = batch.logPosition
            retiredDigest = batchDigest
            retainedBytes -= batch.canonicalBytes
            entries.remove()
        }
        return true
    }

    fun snapshot(): FundsLedgerRecovery =
        FundsLedgerRecovery(
            completeFromGenesis,
            retiredThrough,
            retiredLogPosition,
            retiredDigest,
            digest,
            retained.values.map { it.batch },
        )

    fun restore(saved: FundsLedgerRecovery) {
        require(issued == 0L && saved.completeFromGenesis == completeFromGenesis)
        require(saved.retiredThrough >= 0 && saved.retiredLogPosition >= 0)
        require(FundsLedgerBatch.validDigest(saved.retiredDigest) && FundsLedgerBatch.validDigest(saved.digest))
        if (saved.retiredThrough == 0L) {
            require(saved.retiredLogPosition == 0L && saved.retiredDigest == FundsLedgerBatch.GENESIS_DIGEST)
        } else {
            require(saved.retiredLogPosition > 0L && saved.retiredDigest != FundsLedgerBatch.GENESIS_DIGEST)
        }
        retiredThrough = saved.retiredThrough
        retiredLogPosition = saved.retiredLogPosition
        retiredDigest = saved.retiredDigest
        issued = retiredThrough
        lastLogPosition = retiredLogPosition
        digest = retiredDigest
        for (batch in saved.batches) record(batch)
        require(digest == saved.digest) { "자금 원장 복구 검증값이 다릅니다" }
    }
}

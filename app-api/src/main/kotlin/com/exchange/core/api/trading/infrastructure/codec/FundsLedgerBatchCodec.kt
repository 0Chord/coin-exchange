package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsLedgerBatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** 확정 분개를 같은 형식으로 스냅샷, 조회 전송, PostgreSQL에서 읽는다. */
internal object FundsLedgerBatchCodec {
    fun encode(batch: FundsLedgerBatch): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { write(it, batch) }
        val payload = bytes.toByteArray()
        require(payload.size.toLong() <= batch.canonicalBytes)
        return payload
    }

    fun decode(bytes: ByteArray): FundsLedgerBatch {
        require(bytes.size.toLong() in 1..FundsLedgerBatch.MAX_CANONICAL_BYTES)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val batch = read(input)
                require(input.read() == -1 && bytes.size.toLong() <= batch.canonicalBytes)
                batch
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("자금 원장 batch를 읽을 수 없다", failure)
        }
    }

    fun write(
        output: DataOutputStream,
        batch: FundsLedgerBatch,
    ) {
        output.writeLong(batch.lineage.high)
        output.writeLong(batch.lineage.low)
        output.writeLong(batch.sequence)
        output.writeLong(batch.logPosition)
        output.writeInt(batch.transactions.size)
        for (transaction in batch.transactions) {
            val code =
                when (transaction.transactionType) {
                    LedgerTransactionType.OPENING -> 1
                    LedgerTransactionType.RESERVE -> 2
                    LedgerTransactionType.SETTLEMENT -> 3
                    LedgerTransactionType.RELEASE -> 4
                    LedgerTransactionType.REVERSAL -> error("지원하지 않는 자금 원장 거래")
                }
            output.writeByte(code)
            SettlementFactCodec.writeTransaction(output, transaction)
        }
    }

    fun read(input: DataInputStream): FundsLedgerBatch {
        val lineage = ClusterLineage(input.readLong(), input.readLong())
        val sequence = input.readLong()
        val position = input.readLong()
        val count = input.readInt()
        require(count.toLong() in 1..FundsLedgerBatch.MAX_CANONICAL_BYTES / MIN_TRANSACTION_BYTES)
        val transactions =
            List(count) {
                val type =
                    when (input.readUnsignedByte()) {
                        1 -> LedgerTransactionType.OPENING
                        2 -> LedgerTransactionType.RESERVE
                        3 -> LedgerTransactionType.SETTLEMENT
                        4 -> LedgerTransactionType.RELEASE
                        else -> throw IllegalArgumentException("지원하지 않는 자금 원장 거래")
                    }
                SettlementFactCodec.readTransaction(input, type)
            }
        return FundsLedgerBatch(lineage, sequence, position, transactions)
    }

    private const val MIN_TRANSACTION_BYTES = 32L
}

package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMarketContext
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsLedgerProgress
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.QueryFactsProgress
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 조회 제어만 schema90으로 구분한다. 큰 원문은 기존 검증된 단일 frame 그대로 반환한다. */
internal sealed interface OrderQuerySourceCommand {
    data object Describe : OrderQuerySourceCommand

    data class Read(
        val consumerId: String,
        val market: String?,
        val from: Long,
    ) : OrderQuerySourceCommand

    data class Acknowledge(
        val consumerId: String,
        val market: String?,
        val through: Long,
    ) : OrderQuerySourceCommand

    sealed interface Settlement : OrderQuerySourceCommand {
        val consumerId: String
        val market: String
    }

    data class ReadSettlements(
        override val consumerId: String,
        override val market: String,
        val fromSequence: Long,
    ) : Settlement

    data class AcknowledgeSettlements(
        override val consumerId: String,
        override val market: String,
        val through: Long,
    ) : Settlement

    sealed interface Recovery : OrderQuerySourceCommand {
        val consumerId: String
    }

    data class ReadLedger(
        override val consumerId: String,
        val sequence: Long,
    ) : Recovery

    data class AckLedger(
        override val consumerId: String,
        val through: Long,
    ) : Recovery

    data class DescribeRecovery(
        override val consumerId: String,
        val offset: Int = 0,
    ) : Recovery
}

internal data class OrderQuerySourceEnvelope(
    val correlationId: Long,
    val command: OrderQuerySourceCommand,
)

internal data class OrderQuerySourceProgress(
    val market: String?,
    val issued: Long,
    val retiredThrough: Long,
    val financialThrough: Long = 0,
    val settlement: QueryFactsProgress? = null,
)

/** 같은 서비스 콜백에서 주문, 잔고, 진행 위치를 함께 읽는다. */
internal data class OrderQueryRecovery(
    val logPosition: Long,
    val issued: Long,
    val matchingAppliedThrough: Long,
    val resultThrough: Long,
    val orders: List<OrderQueryRecoveryOrder>,
    val balances: List<Balance> = emptyList(),
    val nextOffset: Int? = null,
) {
    init {
        require(logPosition >= 0 && matchingAppliedThrough in 0..issued && resultThrough in 0..issued)
        require(nextOffset == null || nextOffset > 0)
    }
}

internal data class OrderQueryRecoveryOrder(
    val orderId: OrderId,
    val userId: UserId,
    val remainingQuantity: Quantity,
    val assetId: AssetId? = null,
    val hold: Amount? = null,
) {
    init {
        require((assetId == null) == (hold == null))
        require(remainingQuantity.value > 0 && (hold == null || hold.value > 0))
    }
}

internal data class OrderQuerySourceReply(
    val correlationId: Long,
    val code: String,
    val role: TradingServiceRole? = null,
    val lineage: ClusterLineage? = null,
    val topologySha256: String = "",
    val consumerId: String = "",
    val streams: Map<String, MatchingResultStream> = emptyMap(),
    val contexts: Map<String, TradingMarketContext> = emptyMap(),
    val progress: List<OrderQuerySourceProgress> = emptyList(),
    val admission: FundsOrderDispatch? = null,
    val result: MatchingResultEnvelope? = null,
    val inputContractVersion: Int = 2,
    val settlement: SettlementFact? = null,
    val capabilityVersion: Int = 1,
    val ledgerProgress: FundsLedgerProgress? = null,
    val ledgerBatch: FundsLedgerBatch? = null,
    val recovery: OrderQueryRecovery? = null,
)

internal class OrderQuerySourceCodec(
    private val inputVersion: Int = 2,
) {
    private val orders = SplitOrderDeliveryCodec(inputVersion)
    private val results = SplitResultDeliveryCodec(inputVersion)

    fun encode(
        correlation: Long,
        command: OrderQuerySourceCommand,
    ): ByteArray {
        val version = if (command is OrderQuerySourceCommand.DescribeRecovery) RECOVERY_VERSION else 1
        return frame(COMMAND, version) {
            require(correlation > 0)
            writeLong(correlation)
            when (command) {
                OrderQuerySourceCommand.Describe -> {
                    writeByte(0)
                }

                is OrderQuerySourceCommand.Read -> {
                    require(command.from > 0)
                    writeByte(1)
                    text(command.consumerId)
                    market(command.market)
                    writeLong(command.from)
                }

                is OrderQuerySourceCommand.Acknowledge -> {
                    require(command.through >= 0)
                    writeByte(2)
                    text(command.consumerId)
                    market(command.market)
                    writeLong(command.through)
                }

                is OrderQuerySourceCommand.ReadSettlements -> {
                    require(command.fromSequence > 0)
                    writeByte(3)
                    text(command.consumerId)
                    text(command.market)
                    writeLong(command.fromSequence)
                }

                is OrderQuerySourceCommand.AcknowledgeSettlements -> {
                    require(command.through >= 0)
                    writeByte(4)
                    text(command.consumerId)
                    text(command.market)
                    writeLong(command.through)
                }

                is OrderQuerySourceCommand.ReadLedger -> {
                    require(command.sequence > 0)
                    writeByte(5)
                    text(command.consumerId)
                    writeLong(command.sequence)
                }

                is OrderQuerySourceCommand.AckLedger -> {
                    require(command.through >= 0)
                    writeByte(6)
                    text(command.consumerId)
                    writeLong(command.through)
                }

                is OrderQuerySourceCommand.DescribeRecovery -> {
                    require(command.offset >= 0)
                    writeByte(7)
                    text(command.consumerId)
                    writeInt(command.offset)
                }
            }
        }
    }

    fun decode(bytes: ByteArray): OrderQuerySourceEnvelope {
        require(bytes.size >= 4)
        val version = bytes[2].toInt()
        require(version == 1 || version == RECOVERY_VERSION)
        return read(bytes, COMMAND, version) {
            val correlation = readLong()
            require(correlation > 0)
            val command =
                when (readUnsignedByte()) {
                    0 -> {
                        OrderQuerySourceCommand.Describe
                    }

                    1 -> {
                        val consumerId = text()
                        val market = market()
                        val from = readLong()
                        require(from > 0)
                        OrderQuerySourceCommand.Read(consumerId = consumerId, market = market, from = from)
                    }

                    2 -> {
                        val consumerId = text()
                        val market = market()
                        val through = readLong()
                        require(through >= 0)
                        OrderQuerySourceCommand.Acknowledge(consumerId = consumerId, market = market, through = through)
                    }

                    3 -> {
                        val consumerId = text()
                        val market = text()
                        val fromSequence = readLong()
                        require(fromSequence > 0)
                        OrderQuerySourceCommand.ReadSettlements(consumerId, market, fromSequence)
                    }

                    4 -> {
                        val consumerId = text()
                        val market = text()
                        val through = readLong()
                        require(through >= 0)
                        OrderQuerySourceCommand.AcknowledgeSettlements(consumerId, market, through)
                    }

                    5 -> {
                        val consumerId = text()
                        val sequence = readLong()
                        require(sequence > 0)
                        OrderQuerySourceCommand.ReadLedger(consumerId, sequence)
                    }

                    6 -> {
                        val consumerId = text()
                        val through = readLong()
                        require(through >= 0)
                        OrderQuerySourceCommand.AckLedger(consumerId, through)
                    }

                    7 -> {
                        val consumerId = text()
                        val offset = readInt()
                        require(offset >= 0)
                        OrderQuerySourceCommand.DescribeRecovery(consumerId, offset)
                    }

                    else -> {
                        throw IllegalArgumentException("unknown query command")
                    }
                }
            require((command is OrderQuerySourceCommand.DescribeRecovery) == (version == RECOVERY_VERSION))
            OrderQuerySourceEnvelope(correlation, command)
        }
    }

    fun encodeReply(reply: OrderQuerySourceReply): ByteArray {
        reply.admission?.let { admission -> return admissionBytes(reply.correlationId, admission) }
        reply.result?.let { result -> return resultBytes(reply.correlationId, result) }
        reply.settlement?.let { fact ->
            require(reply.correlationId > 0)
            val payload = SettlementFactCodec.encode(fact)
            return frame(SETTLEMENT_REPLY, SETTLEMENT_VERSION, SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) {
                writeLong(reply.correlationId)
                write(payload)
            }
        }
        reply.ledgerBatch?.let { batch ->
            require(reply.correlationId > 0)
            return frame(LEDGER_REPLY, RECOVERY_VERSION, SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) {
                writeLong(reply.correlationId)
                FundsLedgerBatchCodec.write(this, batch)
            }
        }
        val version = if (reply.ledgerProgress != null || reply.recovery != null) RECOVERY_VERSION else reply.capabilityVersion
        require(version != RECOVERY_VERSION || reply.capabilityVersion == SETTLEMENT_VERSION)
        val maxBytes = if (reply.recovery != null) SplitResultDeliveryCodec.MAX_MESSAGE_BYTES else MAX_CONTROL_BYTES
        return frame(REPLY, version, maxBytes) {
            require(reply.correlationId > 0)
            writeLong(reply.correlationId)
            text(reply.code)
            text(requireNotNull(reply.role).name)
            lineage(requireNotNull(reply.lineage))
            text(reply.topologySha256)
            writeBoolean(reply.consumerId.isNotEmpty())
            if (reply.consumerId.isNotEmpty()) text(reply.consumerId)
            require(reply.streams.size <= MAX_MARKETS && reply.streams.keys == reply.contexts.keys)
            writeInt(reply.streams.size)
            for ((market, stream) in reply.streams.toSortedMap()) {
                text(market)
                lineage(stream.matchingLineage)
                lineage(stream.fundsLineage)
                writeLong(stream.ownershipEpoch)
                writeLong(stream.configurationVersion)
                val contextBytes = orders.context(reply.contexts.getValue(market)).toByteArray()
                writeInt(contextBytes.size)
                write(contextBytes)
            }
            require(reply.progress.size <= MAX_MARKETS + 1)
            writeInt(reply.progress.size)
            for (progress in reply.progress) {
                require(progress.retiredThrough in 0..progress.issued && progress.financialThrough >= 0)
                market(progress.market)
                writeLong(progress.issued)
                writeLong(progress.retiredThrough)
                writeLong(progress.financialThrough)
                if (version >= SETTLEMENT_VERSION) writeSettlementProgress(progress.settlement)
            }
            require(reply.inputContractVersion in 1..2)
            writeInt(reply.inputContractVersion)
            if (version == RECOVERY_VERSION) {
                writeLedgerProgress(reply.ledgerProgress)
                writeRecovery(reply.recovery)
            }
        }
    }

    fun decodeReply(bytes: ByteArray): OrderQuerySourceReply {
        val controlFrame = bytes.size >= 4 && bytes[0].toInt() == 0 && bytes[1].toInt() == SCHEMA
        val version = if (controlFrame) bytes[2].toInt() else 1
        if (controlFrame && version == SETTLEMENT_VERSION && bytes[3].toInt() == SETTLEMENT_REPLY) {
            return read(bytes, SETTLEMENT_REPLY, SETTLEMENT_VERSION, SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) {
                val correlation = readLong()
                require(correlation > 0)
                val fact = SettlementFactCodec.read(this)
                OrderQuerySourceReply(correlation, "FACT", settlement = fact, capabilityVersion = SETTLEMENT_VERSION)
            }
        }
        if (controlFrame && version == RECOVERY_VERSION && bytes[3].toInt() == LEDGER_REPLY) {
            return read(bytes, LEDGER_REPLY, RECOVERY_VERSION, SplitResultDeliveryCodec.MAX_MESSAGE_BYTES) {
                val correlation = readLong()
                require(correlation > 0)
                val batch = FundsLedgerBatchCodec.read(this)
                require((bytes.size - 4 - Long.SIZE_BYTES).toLong() <= batch.canonicalBytes)
                OrderQuerySourceReply(correlation, "FACT", ledgerBatch = batch, capabilityVersion = SETTLEMENT_VERSION)
            }
        }
        if (!controlFrame && bytes.size >= 12) {
            val peek = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (peek.getShort(0).toInt() == 89) {
                val envelope = results.decode(bytes)
                val command = requireNotNull(envelope.command as? SplitResultDeliveryCommand.Apply) { "query reply requires matching fact" }
                return OrderQuerySourceReply(envelope.correlationId, "FACT", result = command.result, inputContractVersion = inputVersion)
            }
            if (peek.getShort(4).toInt() == 88) {
                val envelope = orders.decode(bytes)
                val command = requireNotNull(envelope.command as? SplitOrderDeliveryCommand.Apply) { "query reply requires admission fact" }
                return OrderQuerySourceReply(envelope.correlationId, "FACT", admission = command.input, inputContractVersion = inputVersion)
            }
        }
        val maxBytes = if (version == RECOVERY_VERSION) SplitResultDeliveryCodec.MAX_MESSAGE_BYTES else MAX_CONTROL_BYTES
        return read(bytes, REPLY, version, maxBytes) {
            val correlation = readLong()
            require(correlation > 0)
            val code = text()
            val role = TradingServiceRole.valueOf(text())
            val lineage = lineage()
            val topology = text()
            require(topology.matches(Regex("[a-f0-9]{64}")))
            val consumerId = if (readBoolean()) text() else ""
            val streams = linkedMapOf<String, MatchingResultStream>()
            val contexts = linkedMapOf<String, TradingMarketContext>()
            repeat(count(MAX_MARKETS)) {
                val market = text()
                val stream =
                    MatchingResultStream(
                        matchingLineage = lineage(),
                        fundsLineage = lineage(),
                        marketId = MarketId(market),
                        ownershipEpoch = readLong(),
                        configurationVersion = readLong(),
                    )
                val previousStream = streams.put(market, stream)
                require(previousStream == null)
                val contextBytes = blob()
                val wireContext = SplitMarketContext.parseFrom(contextBytes)
                val context = orders.context(wireContext)
                require(context.market.marketId.value == market)
                contexts[market] = context
            }
            val progress =
                List(count(MAX_MARKETS + 1)) {
                    val marketProgress =
                        OrderQuerySourceProgress(
                            market = market(),
                            issued = readLong(),
                            retiredThrough = readLong(),
                            financialThrough = readLong(),
                            settlement = if (version >= SETTLEMENT_VERSION) readSettlementProgress() else null,
                        )
                    require(marketProgress.retiredThrough in 0..marketProgress.issued && marketProgress.financialThrough >= 0)
                    marketProgress
                }
            require(progress.map { it.market }.distinct().size == progress.size)
            val inputContractVersion = readInt()
            require(inputContractVersion in 1..2)
            val ledgerProgress = if (version == RECOVERY_VERSION) readLedgerProgress() else null
            val recovery = if (version == RECOVERY_VERSION) readRecovery() else null
            require(ledgerProgress == null || role == TradingServiceRole.FUNDS)
            if (recovery != null) {
                require(code == "RECOVERY" && role != TradingServiceRole.INTEGRATED && streams.size == 1)
                require(recovery.orders.all { (it.assetId != null) == (role == TradingServiceRole.FUNDS) })
                require(if (role == TradingServiceRole.FUNDS) ledgerProgress != null else recovery.balances.isEmpty())
            }
            OrderQuerySourceReply(
                correlationId = correlation,
                code = code,
                role = role,
                lineage = lineage,
                topologySha256 = topology,
                consumerId = consumerId,
                streams = streams,
                contexts = contexts,
                progress = progress,
                inputContractVersion = inputContractVersion,
                capabilityVersion = minOf(version, SETTLEMENT_VERSION),
                ledgerProgress = ledgerProgress,
                recovery = recovery,
            )
        }
    }

    fun admissionBytes(
        correlation: Long,
        input: FundsOrderDispatch,
    ) = orders.encode(correlation, SplitOrderDeliveryCommand.Apply(input))

    fun resultBytes(
        correlation: Long,
        result: MatchingResultEnvelope,
    ) = results.encode(correlation, SplitResultDeliveryCommand.Apply(result))

    private fun frame(
        kind: Int,
        version: Int = 1,
        maxBytes: Int = MAX_CONTROL_BYTES,
        block: DataOutputStream.() -> Unit,
    ): ByteArray {
        require(version in 1..RECOVERY_VERSION)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeShort(SCHEMA)
            output.writeByte(version)
            output.writeByte(kind)
            output.block()
        }
        val encodedBytes = bytes.toByteArray()
        require(encodedBytes.size <= maxBytes)
        return encodedBytes
    }

    private fun <T> read(
        bytes: ByteArray,
        kind: Int,
        version: Int = 1,
        maxBytes: Int = MAX_CONTROL_BYTES,
        block: DataInputStream.() -> T,
    ): T {
        require(version in 1..RECOVERY_VERSION)
        require(bytes.size in 13..maxBytes)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readUnsignedShort() == SCHEMA && input.readUnsignedByte() == version && input.readUnsignedByte() == kind)
                val decoded = input.block()
                require(input.read() == -1)
                decoded
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("invalid query frame", failure)
        }
    }

    private fun DataOutputStream.writeSettlementProgress(progress: QueryFactsProgress?) {
        writeBoolean(progress != null)
        if (progress == null) return
        writeLong(progress.issued)
        writeLong(progress.retiredThrough)
        writeInt(progress.retainedEntries)
        writeLong(progress.retainedBytes)
    }

    private fun DataInputStream.readSettlementProgress(): QueryFactsProgress? {
        if (!readBoolean()) return null
        val progress = QueryFactsProgress(readLong(), readLong(), readInt(), readLong())
        require(progress.retiredThrough in 0..progress.issued)
        require(progress.retainedEntries.toLong() == progress.issued - progress.retiredThrough && progress.retainedBytes >= 0)
        return progress
    }

    private fun DataOutputStream.writeLedgerProgress(progress: FundsLedgerProgress?) {
        writeBoolean(progress != null)
        if (progress == null) return
        writeLong(progress.issued)
        writeLong(progress.retiredThrough)
        writeBoolean(progress.completeFromGenesis)
        text(progress.digest)
    }

    private fun DataInputStream.readLedgerProgress(): FundsLedgerProgress? {
        if (!readBoolean()) return null
        val issued = readLong()
        val retiredThrough = readLong()
        val completeFromGenesis = readBoolean()
        val digest = text()
        require(retiredThrough in 0..issued && digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
        return FundsLedgerProgress(issued, retiredThrough, completeFromGenesis, digest)
    }

    private fun DataOutputStream.writeRecovery(recovery: OrderQueryRecovery?) {
        writeBoolean(recovery != null)
        if (recovery == null) return
        require(recovery.orders.size <= RECOVERY_PAGE_ROWS && recovery.balances.size <= RECOVERY_PAGE_ROWS - recovery.orders.size)
        require(recovery.nextOffset == null || recovery.orders.size + recovery.balances.size == RECOVERY_PAGE_ROWS)
        writeInt(recovery.nextOffset ?: -1)
        writeLong(recovery.logPosition)
        writeLong(recovery.issued)
        writeLong(recovery.matchingAppliedThrough)
        writeLong(recovery.resultThrough)
        writeInt(recovery.orders.size)
        for (order in recovery.orders) {
            text(order.orderId.value)
            text(order.userId.value)
            writeLong(order.remainingQuantity.value)
            writeBoolean(order.assetId != null)
            if (order.assetId != null) {
                text(order.assetId.value)
                writeLong(checkNotNull(order.hold).value)
            }
            require(size() <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        }
        writeInt(recovery.balances.size)
        for (balance in recovery.balances) {
            text(balance.userId.value)
            text(balance.assetId.value)
            writeLong(balance.available.value)
            writeLong(balance.hold.value)
            require(size() <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
        }
    }

    private fun DataInputStream.readRecovery(): OrderQueryRecovery? {
        if (!readBoolean()) return null
        val next = readInt()
        require(next == -1 || next > 0)
        val position = readLong()
        val issued = readLong()
        val matchingAppliedThrough = readLong()
        val resultThrough = readLong()
        val orders =
            List(count(RECOVERY_PAGE_ROWS)) {
                val orderId = OrderId(text())
                val userId = UserId(text())
                val remainingQuantity = Quantity(readLong())
                if (readBoolean()) {
                    val asset = AssetId(text())
                    val hold = Amount(readLong())
                    OrderQueryRecoveryOrder(orderId, userId, remainingQuantity, asset, hold)
                } else {
                    OrderQueryRecoveryOrder(orderId, userId, remainingQuantity)
                }
            }
        val balances =
            List(count(RECOVERY_PAGE_ROWS - orders.size)) {
                Balance(UserId(text()), AssetId(text()), Amount(readLong()), Amount(readLong()))
            }
        require(next == -1 || orders.size + balances.size == RECOVERY_PAGE_ROWS)
        return OrderQueryRecovery(position, issued, matchingAppliedThrough, resultThrough, orders, balances, next.takeIf { it >= 0 })
    }

    private fun DataOutputStream.text(value: String) {
        require(value.isNotBlank() && value.toByteArray().size <= 256)
        writeUTF(value)
    }

    private fun DataInputStream.text(): String {
        val value = readUTF()
        require(value.isNotBlank() && value.toByteArray().size <= 256)
        return value
    }

    private fun DataOutputStream.market(value: String?) {
        writeBoolean(value != null)
        value?.let { text(it) }
    }

    private fun DataInputStream.market(): String? = if (readBoolean()) text() else null

    private fun DataOutputStream.lineage(value: ClusterLineage) {
        writeLong(value.high)
        writeLong(value.low)
    }

    private fun DataInputStream.lineage() = ClusterLineage(high = readLong(), low = readLong())

    private fun DataInputStream.count(maximum: Int): Int {
        val count = readInt()
        require(count in 0..maximum)
        return count
    }

    private fun DataInputStream.blob(): ByteArray {
        val bytes = ByteArray(count(MAX_CONTROL_BYTES))
        readFully(bytes)
        return bytes
    }

    companion object {
        const val SCHEMA = 90
        const val MAX_CONTROL_BYTES = 512 * 1_024
        const val MAX_MARKETS = 128

        const val RECOVERY_PAGE_ROWS = 512
        private const val COMMAND = 1
        private const val REPLY = 2
        private const val SETTLEMENT_REPLY = 3
        private const val LEDGER_REPLY = 4
        private const val SETTLEMENT_VERSION = 2
        private const val RECOVERY_VERSION = 3

        fun isQueryFrame(
            buffer: org.agrona.DirectBuffer,
            offset: Int,
            length: Int,
        ): Boolean =
            length >= 4 && (buffer.getShort(offset, ByteOrder.BIG_ENDIAN).toInt() and 65535) == SCHEMA &&
                (buffer.getByte(offset + 2).toInt() == 1 || buffer.getByte(offset + 2).toInt() == RECOVERY_VERSION) &&
                buffer.getByte(offset + 3).toInt() == COMMAND
    }
}

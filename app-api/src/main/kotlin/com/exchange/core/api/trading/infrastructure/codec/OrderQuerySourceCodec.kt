package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.SplitMarketContext
import com.exchange.core.common.MarketId
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
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
)

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
)

internal class OrderQuerySourceCodec(
    private val inputVersion: Int = 2,
) {
    private val orders = SplitOrderDeliveryCodec(inputVersion)
    private val results = SplitResultDeliveryCodec(inputVersion)

    fun encode(
        correlation: Long,
        command: OrderQuerySourceCommand,
    ): ByteArray =
        frame(COMMAND) {
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
            }
        }

    fun decode(bytes: ByteArray): OrderQuerySourceEnvelope =
        read(bytes, COMMAND) {
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

                    else -> {
                        throw IllegalArgumentException("unknown query command")
                    }
                }
            OrderQuerySourceEnvelope(correlation, command)
        }

    fun encodeReply(reply: OrderQuerySourceReply): ByteArray {
        reply.admission?.let { admission -> return admissionBytes(reply.correlationId, admission) }
        reply.result?.let { result -> return resultBytes(reply.correlationId, result) }
        return frame(REPLY) {
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
            }
            require(reply.inputContractVersion in 1..2)
            writeInt(reply.inputContractVersion)
        }
    }

    fun decodeReply(bytes: ByteArray): OrderQuerySourceReply {
        if (bytes.size >= 12) {
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
        return read(bytes, REPLY) {
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
                        )
                    require(marketProgress.retiredThrough in 0..marketProgress.issued && marketProgress.financialThrough >= 0)
                    marketProgress
                }
            require(progress.map { it.market }.distinct().size == progress.size)
            val inputContractVersion = readInt()
            require(inputContractVersion in 1..2)
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
        block: DataOutputStream.() -> Unit,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeShort(SCHEMA)
            output.writeByte(1)
            output.writeByte(kind)
            output.block()
        }
        val encodedBytes = bytes.toByteArray()
        require(encodedBytes.size <= MAX_CONTROL_BYTES)
        return encodedBytes
    }

    private fun <T> read(
        bytes: ByteArray,
        kind: Int,
        block: DataInputStream.() -> T,
    ): T {
        require(bytes.size in 13..MAX_CONTROL_BYTES)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readUnsignedShort() == SCHEMA && input.readUnsignedByte() == 1 && input.readUnsignedByte() == kind)
                val decoded = input.block()
                require(input.read() == -1)
                decoded
            }
        } catch (failure: IOException) {
            throw IllegalArgumentException("invalid query frame", failure)
        }
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
        private const val COMMAND = 1
        private const val REPLY = 2

        fun isQueryFrame(
            buffer: org.agrona.DirectBuffer,
            offset: Int,
            length: Int,
        ): Boolean =
            length >= 4 && (buffer.getShort(offset, ByteOrder.BIG_ENDIAN).toInt() and 65535) == SCHEMA &&
                buffer.getByte(offset + 2).toInt() == 1 && buffer.getByte(offset + 3).toInt() == COMMAND
    }
}

package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingRoleRecoveryState
import com.exchange.core.trading.MatchingStreamRecovery
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.TradingLifecycle
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 매칭 역할만의 복구 형식이다. 금융 상태나 실행 중인 매칭 후보는 직렬화하지 않는다. */
internal class MatchingRoleSnapshotCodec {
    private val manifests = BoundedTradingStateCodec()
    private val results = MatchingResultSnapshotCodec()

    fun write(
        state: MatchingRoleRecoveryState,
        position: Long,
        target: OutputStream,
    ) {
        require(position >= 0)
        // 저장 가능한 현재 상태의 관계를 검사한 다음에만 바이트를 쓴다.
        ReservedMatchingState(state.configuration).restore(state)
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestOutputStream(LimitedOutput(target, snapshotLimit(state.configuration)), digest)
        val output = DataOutputStream(checked)
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(position)
        output.blob(configurationBytes(state.configuration), MAX_CONFIGURATION_BYTES)
        output.writeInt(state.lifecycle.ordinal)
        output.writeInt(state.resultEntries)
        output.writeLong(state.resultBytes)
        output.writeInt(state.activeOrders)
        output.writeLong(state.activeBytes)
        output.writeInt(state.matching.markets.size)
        for (market in state.matching.markets) {
            output.text(market.marketId.value)
            output.writeLong(market.lastEngineSequence)
            output.writeInt(market.orders.size)
            for (order in market.orders) {
                output.text(order.orderId.value)
                output.text(order.userId.value)
                output.writeInt(order.side.ordinal)
                output.writeLong(order.price.value)
                output.writeLong(order.originalQuantity.value)
                output.writeLong(order.remainingQuantity.value)
            }
        }
        output.writeInt(state.active.size)
        for (entry in state.active) results.writeDispatch(output, entry)
        output.writeInt(state.streams.size)
        for (stream in state.streams) {
            output.text(stream.stream.marketId.value)
            output.writeLong(stream.appliedThrough)
            output.writeLong(stream.lastSourceAdmissionSequence)
            output.writeLong(stream.resultIssued)
            output.writeLong(stream.fundsSettledThrough)
            output.writeLong(stream.settledEngineSequence)
            output.writeLong(stream.resultBytes)
            output.writeInt(stream.activeOrders)
            output.writeLong(stream.activeBytes)
            output.writeInt(stream.outstanding.size)
            for (result in stream.outstanding) results.writeResult(output, result)
        }
        output.flush()
        checked.on(false)
        output.write(digest.digest())
        output.writeInt(END)
        output.flush()
    }

    fun read(
        source: InputStream,
        expected: MatchingIntakeConfiguration,
        expectedPosition: Long,
    ): MatchingRoleRecoveryState {
        require(expectedPosition >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestInputStream(LimitedInput(source, snapshotLimit(expected)), digest)
        val input = DataInputStream(checked)
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "unsupported matching snapshot" }
        require(input.readLong() == expectedPosition) { "matching snapshot framework position differs" }
        require(input.blob(MAX_CONFIGURATION_BYTES).contentEquals(configurationBytes(expected))) {
            "matching snapshot configuration differs"
        }
        val lifecycle = input.enum<TradingLifecycle>()
        val resultEntries = input.readInt()
        val resultBytes = input.readLong()
        val activeOrders = input.readInt()
        val activeBytes = input.readLong()
        val owned = expected.markets.map { it.market.marketId }.toSet()
        var checkpointOrders = 0
        val checkpoint =
            MatchingCheckpoint(
                List(input.count(owned.size)) {
                    val market = MarketId(input.text())
                    require(market in owned) { "unassigned checkpoint market" }
                    val sequence = input.readLong()
                    val count =
                        input.count(
                            minOf(
                                expected.limits.marketLimits
                                    .getValue(market)
                                    .maxActiveOrders,
                                expected.limits.maxActiveOrders - checkpointOrders,
                            ),
                        )
                    checkpointOrders = Math.addExact(checkpointOrders, count)
                    MatchingCheckpoint.Market(
                        market,
                        sequence,
                        List(count) {
                            MatchingCheckpoint.Order(
                                OrderId(input.text()),
                                UserId(input.text()),
                                input.enum(),
                                Price(input.readLong()),
                                Quantity(input.readLong()),
                                Quantity(input.readLong()),
                            )
                        },
                    )
                },
            )
        val active = List(input.count(expected.limits.maxActiveOrders)) { results.readDispatch(input, expected::stream, expected::context) }
        var resultsRead = 0
        val streams =
            List(input.count(owned.size)) {
                val market = MarketId(input.text())
                require(market in owned) { "unassigned matching stream" }
                val applied = input.readLong()
                val source = input.readLong()
                val issued = input.readLong()
                val settled = input.readLong()
                val engine = input.readLong()
                val bytes = input.readLong()
                val localActive = input.readInt()
                val localActiveBytes = input.readLong()
                val count =
                    input.count(
                        minOf(
                            expected.limits.marketLimits
                                .getValue(market)
                                .maxResultEntries,
                            expected.limits.maxResultEntries - resultsRead,
                        ),
                    )
                resultsRead = Math.addExact(resultsRead, count)
                MatchingStreamRecovery(
                    expected.stream(market),
                    applied,
                    source,
                    issued,
                    settled,
                    engine,
                    List(
                        count,
                    ) { results.readResult(input, expected.stream(market), expected.context(market), expected.limits.maxResultEvents) },
                    bytes,
                    localActive,
                    localActiveBytes,
                )
            }
        checked.on(false)
        val checksum = ByteArray(32).also(input::readFully)
        require(MessageDigest.isEqual(digest.digest(), checksum)) { "matching snapshot checksum differs" }
        require(input.readInt() == END && input.read() == -1) { "matching snapshot footer differs" }
        val state =
            MatchingRoleRecoveryState(
                expected,
                lifecycle,
                checkpoint,
                active,
                streams,
                resultEntries,
                resultBytes,
                activeOrders,
                activeBytes,
            )
        // checksum만 맞는 손상 자료도 정상 상태로 공개하지 않는다.
        ReservedMatchingState(expected).restore(state)
        return state
    }

    private fun configurationBytes(configuration: MatchingIntakeConfiguration): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(LimitedOutput(bytes, MAX_CONFIGURATION_BYTES.toLong())).use { output ->
                output.blob(manifests.encodeManifest(configuration.manifest), BoundedTradingStateCodec.MAX_MANIFEST_BYTES)
                output.writeInt(configuration.assignments.size)
                for (assignment in configuration.assignments.sortedBy { it.marketId.value }) {
                    output.text(assignment.marketId.value)
                    output.writeLong(assignment.matchingLineage.high)
                    output.writeLong(assignment.matchingLineage.low)
                    output.writeLong(assignment.ownershipEpoch)
                }
                val declaration = configuration.declaration
                output.writeInt(declaration.serviceRole.ordinal)
                output.writeLong(declaration.lineage.high)
                output.writeLong(declaration.lineage.low)
                output.writeLong(declaration.configurationVersion)
                output.writeInt(declaration.markets.size)
                for (context in declaration.markets) {
                    output.text(context.market.marketId.value)
                    output.writeLong(declaration.ownershipEpochs.getValue(context.market.marketId))
                }
                val limits = configuration.limits
                output.writeInt(limits.maxResultEntries)
                output.writeLong(limits.maxResultBytes)
                output.writeInt(limits.maxActiveOrders)
                output.writeLong(limits.maxActiveBytes)
                output.writeLong(limits.maxSingleResultBytes)
                output.writeInt(limits.maxResultEvents)
                output.writeInt(limits.maxCandidateEvents)
                output.writeInt(limits.marketLimits.size)
                for ((market, limit) in limits.marketLimits.entries.sortedBy { it.key.value }) {
                    output.text(market.value)
                    output.writeInt(limit.maxResultEntries)
                    output.writeLong(limit.maxResultBytes)
                    output.writeInt(limit.maxActiveOrders)
                    output.writeLong(limit.maxActiveBytes)
                }
            }
            bytes.toByteArray()
        }

    private inline fun <reified E : Enum<E>> DataInputStream.enum(): E =
        enumValues<E>().getOrNull(readInt())
            ?: throw IllegalArgumentException("invalid matching snapshot enum")

    private fun DataOutputStream.text(value: String) = blob(value.toByteArray(Charsets.UTF_8), MAX_TEXT_BYTES)

    private fun DataInputStream.text(): String {
        val bytes = blob(MAX_TEXT_BYTES)
        return bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) }
    }

    private fun DataOutputStream.blob(
        bytes: ByteArray,
        maximum: Int,
    ) {
        require(bytes.size in 1..maximum)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.blob(maximum: Int): ByteArray {
        val length = readInt()
        require(length in 1..maximum) { "matching snapshot value length exceeds its bound" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.count(maximum: Int): Int =
        readInt().also {
            require(it in 0..minOf(maximum, MAX_ROWS)) { "matching snapshot row count exceeds its bound" }
        }

    private fun snapshotLimit(configuration: MatchingIntakeConfiguration): Long =
        configuration.snapshotByteUpperBound.also {
            require(it in 1..MAX_SNAPSHOT_BYTES)
        }

    private class LimitedInput(
        source: InputStream,
        private val maximum: Long,
    ) : FilterInputStream(source) {
        private var consumed = 0L

        override fun read(): Int = `in`.read().also { if (it >= 0) consume(1) }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int =
            `in`.read(bytes, offset, length).also {
                if (it >
                    0
                ) {
                    consume(it)
                }
            }

        private fun consume(amount: Int) {
            consumed = Math.addExact(consumed, amount.toLong())
            require(consumed <= maximum) { "matching snapshot exceeds its configured bound" }
        }
    }

    private class LimitedOutput(
        target: OutputStream,
        private val maximum: Long,
    ) : FilterOutputStream(target) {
        private var produced = 0L

        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            reserve(length)
            out.write(bytes, offset, length)
        }

        private fun reserve(amount: Int) {
            produced = Math.addExact(produced, amount.toLong())
            require(produced <= maximum) { "matching snapshot exceeds its configured bound" }
        }
    }

    companion object {
        private const val MAGIC = 0x4D525350
        private const val VERSION = 1
        private const val END = 0x4D524531
        private const val MAX_TEXT_BYTES = 1_024
        private const val MAX_CONFIGURATION_BYTES = 256 * 1_024
        private const val MAX_SNAPSHOT_BYTES = 128L * 1_024 * 1_024
        private const val MAX_ROWS = 1_000_000
    }
}

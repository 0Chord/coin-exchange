package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.BoundedFundingRecoveryState
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchSnapshot
import com.exchange.core.trading.FundsDispatchStreamSnapshot
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.FundsResultReceipt
import com.exchange.core.trading.FundsResultRecoveryState
import com.exchange.core.trading.FundsResultStreamRecovery
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.MarketFundsDispatchLimits
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingRecoveryState
import java.io.ByteArrayInputStream
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
import java.time.Instant

/** 자금 역할의 금융 투영과 전달 의무를 함께 담는다. 노드나 Archive를 시작하지 않는다. */
internal class FundsRoleSnapshotCodec {
    private val financial = BoundedTradingStateCodec()
    private val reservations = TradingHistoryCodec(MAX_RESERVATION_BYTES)
    private val results = MatchingResultSnapshotCodec()

    fun write(
        state: FundsRoleRecoveryState,
        position: Long,
        target: OutputStream,
    ) {
        require(position >= 0)
        val saved = validatedState(state)
        require(saved.financial.manifest == saved.configuration.manifest) { "funds financial manifest differs" }
        require(
            saved.financial.lifecycle != com.exchange.core.trading.TradingLifecycle.UNINITIALIZED,
        ) { "funds snapshot is not initialized" }
        val configuration = configurationBytes(saved.configuration)
        val base =
            ByteArrayOutputStream().use { bytes ->
                financial.write(financialProjection(saved.financial), position, LimitedOutput(bytes, MAX_SNAPSHOT_BYTES))
                bytes.toByteArray()
            }
        val digest = MessageDigest.getInstance("SHA-256")
        val checked =
            DigestOutputStream(LimitedOutput(target, snapshotLimit(saved.configuration, checkNotNull(saved.results).limits)), digest)
        val output = DataOutputStream(checked)
        output.writeInt(MAGIC)
        output.writeInt(VERSION)
        output.writeLong(position)
        output.blob(configuration, MAX_CONFIGURATION_BYTES)
        output.blob(base, MAX_SNAPSHOT_BYTES.toInt())
        val assets =
            saved.configuration.manifest.markets
                .flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }
                .toSet()
        require(saved.assetTotals.keys.all { it in assets } && saved.assetTotals.values.all { it >= 0 })
        output.writeInt(saved.assetTotals.size)
        for ((asset, total) in saved.assetTotals.entries.sortedBy { it.key.value }) {
            output.text(asset.value)
            output.writeLong(total)
        }
        writeDispatch(output, saved.configuration, saved.dispatch)
        writeResults(output, saved.configuration, checkNotNull(saved.results))
        output.flush()
        checked.on(false)
        output.write(digest.digest())
        output.writeInt(END)
        output.flush()
    }

    fun read(
        source: InputStream,
        expected: FundsDispatchConfiguration,
        expectedPosition: Long,
        expectedResultLimits: FundsResultLimits = FundsResultLimits(),
    ): FundsRoleRecoveryState {
        require(expectedPosition >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val limited = LimitedInput(source, snapshotLimit(expected, expectedResultLimits))
        val checked = DigestInputStream(limited, digest)
        val input = DataInputStream(checked)
        require(input.readInt() == MAGIC) { "unsupported funds snapshot" }
        val version = input.readInt()
        require(version == LEGACY_VERSION || version == VERSION) { "unsupported funds snapshot" }
        require(input.readLong() == expectedPosition) { "funds snapshot framework position differs" }
        require(input.blob(MAX_CONFIGURATION_BYTES).contentEquals(configurationBytes(expected))) {
            "funds snapshot configuration differs"
        }
        val saved = financial.read(ByteArrayInputStream(input.blob(MAX_SNAPSHOT_BYTES.toInt())), expected.manifest, expectedPosition)
        require(saved.matching.markets.isEmpty()) { "funds snapshot must not contain a matching checkpoint" }
        require(saved.lifecycle != com.exchange.core.trading.TradingLifecycle.UNINITIALIZED) { "funds snapshot is not initialized" }
        val assets =
            expected.manifest.markets
                .flatMap { listOf(it.market.baseAssetId, it.market.quoteAssetId) }
                .toSet()
        val totals = linkedMapOf<AssetId, Long>()
        repeat(input.count(assets.size)) {
            val asset = AssetId(input.text())
            val total = input.readLong()
            require(asset in assets && total >= 0 && totals.put(asset, total) == null) { "invalid funds snapshot asset total" }
        }
        val dispatch = readDispatch(input, expected)
        val resultState = if (version == VERSION) readResults(input, expected, expectedResultLimits) { limited.remaining } else null
        checked.on(false)
        val expectedDigest = ByteArray(32).also(input::readFully)
        require(MessageDigest.isEqual(digest.digest(), expectedDigest)) { "funds snapshot checksum differs" }
        require(input.readInt() == END && input.read() == -1) { "funds snapshot footer differs" }
        val state = FundsRoleRecoveryState(expected, fundingProjection(saved), dispatch, totals, resultState)
        // 체크섬 뒤에도 금융·전달·정산 경계의 관계를 빈 소유자에서 검사한다.
        validatedState(state, expectedResultLimits)
        return state
    }

    private fun writeResults(
        output: DataOutputStream,
        configuration: FundsDispatchConfiguration,
        state: FundsResultRecoveryState,
    ) {
        val limits = state.limits
        output.writeInt(limits.maxResultEvents)
        output.writeLong(limits.maxSingleResultBytes)
        output.writeInt(limits.maxReceiptEntries)
        output.writeLong(limits.maxReceiptBytes)
        output.writeInt(limits.maxMarketReceiptEntries)
        output.writeLong(limits.maxMarketReceiptBytes)
        output.writeInt(state.streams.size)
        for (saved in state.streams) {
            output.text(saved.stream.marketId.value)
            output.writeLong(saved.resultThrough)
            output.writeLong(saved.settledEngineSequence)
            output.writeLong(saved.retiredThrough)
            output.writeLong(saved.retiredEngineSequence)
            output.writeInt(saved.receiptEntries)
            output.writeLong(saved.receiptBytes)
        }
        output.writeLong(state.receiptBytes)
        output.writeInt(state.receipts.size)
        for (receipt in state.receipts) {
            require(receipt.result.stream.marketId in configuration.assignments.map { it.marketId })
            output.text(receipt.result.stream.marketId.value)
            output.writeLong(receipt.appliedAt.epochSecond)
            output.writeInt(receipt.appliedAt.nano)
            val frameLimit = resultFrameLimit(limits)
            val frame =
                ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(LimitedOutput(bytes, frameLimit.toLong())).use { framed ->
                        results.writeResult(framed, receipt.result)
                    }
                    bytes.toByteArray()
                }
            output.blob(frame, frameLimit)
        }
    }

    private fun readResults(
        input: DataInputStream,
        configuration: FundsDispatchConfiguration,
        expectedLimits: FundsResultLimits,
        remainingBytes: () -> Long,
    ): FundsResultRecoveryState {
        val limits =
            FundsResultLimits(
                input.readInt(),
                input.readLong(),
                input.readInt(),
                input.readLong(),
                input.readInt(),
                input.readLong(),
            )
        require(limits == expectedLimits) { "funds result snapshot limits differ" }
        val markets = configuration.assignments.map { it.marketId }
        require(input.count(markets.size) == markets.size) { "funds result snapshot stream coverage differs" }
        val streams =
            markets.map { market ->
                require(MarketId(input.text()) == market) { "funds result snapshot stream order differs" }
                val dispatch = configuration.stream(market)
                FundsResultStreamRecovery(
                    MatchingResultStream(
                        dispatch.matchingLineage,
                        dispatch.fundsLineage,
                        market,
                        dispatch.ownershipEpoch,
                        dispatch.configurationVersion,
                    ),
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readInt(),
                    input.readLong(),
                )
            }
        val bytes = input.readLong()
        require(bytes in 0..limits.maxReceiptBytes) { "funds receipt bytes exceed their bound" }
        val declaredByMarket = streams.associateBy { it.stream.marketId }
        var declaredBytes = 0L
        var declaredEntries = 0
        for (stream in streams) {
            require(stream.receiptBytes in 0..limits.maxMarketReceiptBytes) { "funds market receipt bytes exceed their bound" }
            require(stream.receiptEntries in 0..limits.maxMarketReceiptEntries) { "funds market receipt count exceeds its bound" }
            declaredBytes = Math.addExact(declaredBytes, stream.receiptBytes)
            declaredEntries = Math.addExact(declaredEntries, stream.receiptEntries)
        }
        require(declaredBytes == bytes) { "funds declared receipt byte totals differ" }
        val receiptCount = input.count(limits.maxReceiptEntries)
        require(declaredEntries == receiptCount) { "funds declared receipt entry totals differ" }
        val marketCounts = mutableMapOf<MarketId, Int>()
        val actualMarketBytes = mutableMapOf<MarketId, Long>()
        var actualBytes = 0L
        val receipts =
            List(receiptCount) {
                val market = MarketId(input.text())
                require(market in markets) { "funds receipt market differs" }
                val count = Math.addExact(marketCounts[market] ?: 0, 1)
                val declared = declaredByMarket.getValue(market)
                require(count <= declared.receiptEntries) { "funds market receipt count exceeds its declared bound" }
                marketCounts[market] = count
                val seconds = input.readLong()
                val nanos = input.readInt()
                require(nanos in 0..999_999_999) { "funds receipt time nanos differ" }
                val at = Instant.ofEpochSecond(seconds, nanos.toLong())
                val frameLength = input.readInt()
                val marketBytes = actualMarketBytes[market] ?: 0L
                val remainingLogicalBytes =
                    minOf(
                        limits.maxSingleResultBytes,
                        limits.maxReceiptBytes - actualBytes,
                        limits.maxMarketReceiptBytes - marketBytes,
                        bytes - actualBytes,
                        declared.receiptBytes - marketBytes,
                    )
                // 다음 원문의 길이만 믿고 할당하지 않는다. 이미 읽은 실제 charge를 모든 잔여 예산에서 뺀다.
                require(remainingLogicalBytes > 0) { "funds receipt logical byte budget exhausted" }
                val maximumFrame =
                    minOf(
                        Math.multiplyExact(remainingLogicalBytes, 4L),
                        remainingBytes() - FOOTER_BYTES,
                    )
                require(frameLength.toLong() in 1..maximumFrame) { "funds result frame exceeds its bound" }
                val frame = ByteArrayInputStream(ByteArray(frameLength).also(input::readFully))
                val result =
                    results.readResult(
                        DataInputStream(frame),
                        configuration.stream(market),
                        configuration.manifest.markets.single { it.market.marketId == market },
                        limits.maxResultEvents,
                    )
                require(frame.available() == 0) { "funds result frame has trailing bytes" }
                val charge = result.canonicalBytes
                require(charge in 1..remainingLogicalBytes) { "funds receipt result exceeds its remaining logical bound" }
                actualBytes = Math.addExact(actualBytes, charge)
                actualMarketBytes[market] = Math.addExact(marketBytes, charge)
                FundsResultReceipt(result, at)
            }
        require(actualBytes == bytes) { "funds receipt byte total differs" }
        for (stream in streams) {
            require((actualMarketBytes[stream.stream.marketId] ?: 0L) == stream.receiptBytes) { "funds market receipt byte total differs" }
            require((marketCounts[stream.stream.marketId] ?: 0) == stream.receiptEntries) { "funds market receipt entry total differs" }
        }
        return FundsResultRecoveryState(limits, streams, receipts, bytes)
    }

    private fun resultFrameLimit(limits: FundsResultLimits): Int =
        Math
            .multiplyExact(limits.maxSingleResultBytes, 4L)
            .also {
                require(it in 1..MAX_SNAPSHOT_BYTES) { "funds result frame bound is unsupported" }
            }.toInt()

    /** 순수 금융 소유자로 검증하며 통합 owner·주문장·파일 기록은 만들지 않는다. */
    private fun validatedState(
        state: FundsRoleRecoveryState,
        resultLimits: FundsResultLimits = state.results?.limits ?: FundsResultLimits(),
    ): FundsRoleRecoveryState {
        val reserve = TradingFeeReserveCalculator()
        val reservation = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(reserve))
        val owner =
            BoundedFundingState(
                OrderAdmissionCalculator(reservation),
                expectedManifest = state.configuration.manifest,
                dispatchConfiguration = state.configuration,
                resultLimits = resultLimits,
            )
        val settlement =
            OrderSettlementCalculator(
                OrderFillSettlementCalculator(TradingFeeCalculator(), reserve),
                reservation,
                reserve,
                SettlementLedgerTransactions(),
            )
        owner.restoreFundsRecoveryState(state, settlement)
        return owner.fundsRecoveryState()
    }

    private fun writeDispatch(
        output: DataOutputStream,
        configuration: FundsDispatchConfiguration,
        snapshot: FundsDispatchSnapshot,
    ) {
        require(snapshot.streams.size == configuration.assignments.size)
        require(
            snapshot.streams
                .map { it.stream.marketId }
                .distinct()
                .size == snapshot.streams.size,
        )
        output.writeInt(snapshot.dispatchEntries)
        output.writeLong(snapshot.dispatchBytes)
        output.writeInt(snapshot.activeOrders)
        output.writeLong(snapshot.activeBytes)
        output.writeInt(snapshot.streams.size)
        val byMarket = snapshot.streams.associateBy { it.stream.marketId }
        require(byMarket.keys == configuration.assignments.map { it.marketId }.toSet()) { "funds snapshot stream coverage differs" }
        for (assignment in configuration.assignments) {
            val saved = byMarket.getValue(assignment.marketId)
            require(saved.stream == configuration.stream(saved.stream.marketId)) { "funds snapshot stream differs" }
            val context = configuration.manifest.markets.single { it.market.marketId == saved.stream.marketId }
            output.text(saved.stream.marketId.value)
            output.writeLong(saved.issued)
            output.writeLong(saved.matchingAppliedThrough)
            output.writeLong(saved.dispatchBytes)
            output.writeInt(saved.activeOrders)
            output.writeLong(saved.activeBytes)
            val limits = configuration.limits.marketLimits.getValue(saved.stream.marketId)
            require(saved.outstanding.size <= limits.maxDispatchEntries)
            require(
                saved.outstanding
                    .map { it.dispatchSequence }
                    .zipWithNext()
                    .all { (first, second) -> first < second },
            )
            output.writeInt(saved.outstanding.size)
            for (entry in saved.outstanding) {
                require(entry.stream == saved.stream && entry.context == context) { "funds snapshot envelope configuration differs" }
                output.writeLong(entry.dispatchSequence)
                output.writeLong(entry.admissionSequence)
                output.writeLong(entry.marketAdmissionSequence)
                output.order(entry.order)
                output.blob(reservations.encodeReservation(entry.reservation), MAX_RESERVATION_BYTES)
            }
        }
    }

    private fun readDispatch(
        input: DataInputStream,
        configuration: FundsDispatchConfiguration,
    ): FundsDispatchSnapshot {
        val dispatchEntries = input.readInt()
        val dispatchBytes = input.readLong()
        val activeOrders = input.readInt()
        val activeBytes = input.readLong()
        val markets = configuration.assignments.map { it.marketId }
        require(input.count(markets.size) == markets.size) { "funds snapshot stream coverage differs" }
        var entryCount = 0
        val streams =
            markets.map { expectedMarket ->
                val market = MarketId(input.text())
                require(market == expectedMarket) { "funds snapshot stream order differs" }
                val stream = configuration.stream(market)
                val context = configuration.manifest.markets.single { it.market.marketId == market }
                val issued = input.readLong()
                val applied = input.readLong()
                val bytes = input.readLong()
                val active = input.readInt()
                val activeCharge = input.readLong()
                val remaining = configuration.limits.maxDispatchEntries - entryCount
                val count =
                    input.count(
                        minOf(
                            configuration.limits.marketLimits
                                .getValue(market)
                                .maxDispatchEntries,
                            remaining,
                        ),
                    )
                entryCount = Math.addExact(entryCount, count)
                val outstanding =
                    List(count) {
                        FundsOrderDispatch(
                            stream,
                            input.readLong(),
                            input.readLong(),
                            input.readLong(),
                            input.order(),
                            reservations.decodeReservation(input.blob(MAX_RESERVATION_BYTES)),
                            context,
                        )
                    }
                FundsDispatchStreamSnapshot(stream, issued, applied, outstanding, bytes, active, activeCharge)
            }
        return FundsDispatchSnapshot(streams, dispatchEntries, dispatchBytes, activeOrders, activeBytes)
    }

    /** 바이트 계약은 재사용하되 통합 owner나 MatchingEngine은 생성하지 않는다. */
    private fun financialProjection(saved: BoundedFundingRecoveryState) =
        TradingRecoveryState(
            saved.manifest,
            saved.lifecycle,
            saved.balances,
            saved.feeRevenueByAsset,
            saved.usedBalances,
            saved.preparations,
            saved.accounts,
            saved.liveOrders,
            saved.markets,
            MatchingCheckpoint(emptyList()),
            saved.inbox,
            saved.lastAdmissionSequence,
            saved.lastPreparationSequence,
            saved.preparationCanonicalBytes,
            saved.incident,
        )

    private fun fundingProjection(saved: TradingRecoveryState) =
        BoundedFundingRecoveryState(
            saved.manifest,
            saved.lifecycle,
            saved.balances,
            saved.feeRevenueByAsset,
            saved.usedBalances,
            saved.preparations,
            saved.accounts,
            saved.liveOrders,
            saved.markets,
            saved.inbox,
            saved.lastAdmissionSequence,
            saved.lastPreparationSequence,
            saved.preparationCanonicalBytes,
            saved.incident,
        )

    internal fun configurationBytes(configuration: FundsDispatchConfiguration): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(LimitedOutput(bytes, MAX_CONFIGURATION_BYTES.toLong())).use { output ->
                output.blob(financial.encodeManifest(configuration.manifest), BoundedTradingStateCodec.MAX_MANIFEST_BYTES)
                output.writeInt(configuration.assignments.size)
                for (assignment in configuration.assignments.sortedBy { it.marketId.value }) {
                    output.text(assignment.marketId.value)
                    output.writeLong(assignment.matchingLineage.high)
                    output.writeLong(assignment.matchingLineage.low)
                    output.writeLong(assignment.ownershipEpoch)
                }
                val limits = configuration.limits
                output.writeInt(limits.maxDispatchEntries)
                output.writeLong(limits.maxDispatchBytes)
                output.writeInt(limits.maxActiveOrders)
                output.writeLong(limits.maxActiveBytes)
                output.writeInt(limits.marketLimits.size)
                for ((market, limit) in limits.marketLimits.entries.sortedBy { it.key.value }) {
                    output.text(market.value)
                    output.limits(limit)
                }
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.limits(limits: MarketFundsDispatchLimits) {
        writeInt(limits.maxDispatchEntries)
        writeLong(limits.maxDispatchBytes)
        writeInt(limits.maxActiveOrders)
        writeLong(limits.maxActiveBytes)
    }

    private fun DataOutputStream.order(order: OrderAdmissionInput) {
        text(order.marketId.value)
        text(order.orderId.value)
        text(order.userId.value)
        text(order.side.name)
        text(order.orderType.name)
        text(order.timeInForce.name)
        writeLong(order.price.value)
        writeLong(order.quantity.value)
    }

    private fun DataInputStream.order() =
        OrderAdmissionInput(
            MarketId(text()),
            OrderId(text()),
            UserId(text()),
            Side.valueOf(text()),
            OrderType.valueOf(text()),
            TimeInForce.valueOf(text()),
            Price(readLong()),
            Quantity(readLong()),
        )

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
        require(length in 1..maximum) { "funds snapshot value length exceeds its bound" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.count(maximum: Int): Int = readInt().also { require(it in 0..minOf(maximum, 1_000_000)) }

    private fun snapshotLimit(
        configuration: FundsDispatchConfiguration,
        limits: FundsResultLimits,
    ): Long = limits.snapshotByteUpperBound(configuration).also { require(it in 1..MAX_SNAPSHOT_BYTES) }

    private class LimitedInput(
        source: InputStream,
        private val maximum: Long,
    ) : FilterInputStream(source) {
        private var consumed = 0L
        val remaining: Long get() = maximum - consumed

        override fun read(): Int =
            `in`.read().also {
                if (it >= 0) consume(1)
            }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int =
            `in`.read(bytes, offset, length).also {
                if (it > 0) consume(it)
            }

        private fun consume(amount: Int) {
            consumed = Math.addExact(consumed, amount.toLong())
            require(consumed <= maximum) { "funds snapshot exceeds its configured bound" }
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
            require(produced <= maximum) { "funds snapshot exceeds its configured bound" }
        }
    }

    companion object {
        private const val MAGIC = 0x46525350
        private const val VERSION = 2
        private const val LEGACY_VERSION = 1
        private const val FOOTER_BYTES = 36L
        private const val END = 0x46524531
        private const val MAX_TEXT_BYTES = 1_024
        private const val MAX_CONFIGURATION_BYTES = 256 * 1_024
        private const val MAX_RESERVATION_BYTES = 64 * 1_024
        private const val MAX_SNAPSHOT_BYTES = 128L * 1_024 * 1_024
    }
}

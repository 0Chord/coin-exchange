package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceResult
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.AdmissionReceipt
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.LiveOrderPhase
import com.exchange.core.trading.OrderAdmissionInput
import com.exchange.core.trading.OrderAdmissionResult
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.TradingBalanceKey
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingInboxChannelSnapshot
import com.exchange.core.trading.TradingInboxEntrySnapshot
import com.exchange.core.trading.TradingInboxSnapshot
import com.exchange.core.trading.TradingIncident
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingLiveOrderSnapshot
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingMarketProgressSnapshot
import com.exchange.core.trading.TradingRecoveryState
import com.exchange.core.trading.TradingResourceLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant

/** 형식 2는 현재 금융 상태만 담으며, 이력 DB나 완료된 원장 객체 그래프는 포함하지 않는다. */
internal class BoundedTradingStateCodec {
    private val reservations = TradingHistoryCodec(64 * 1024)

    fun encodeManifest(manifest: TradingClusterManifest): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { writeManifest(it, manifest) }
            bytes.toByteArray().also { require(it.size <= MAX_MANIFEST_BYTES) }
        }

    fun decodeManifest(bytes: ByteArray): TradingClusterManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            readManifest(input).also {
                require(input.read() == -1)
                require(encodeManifest(it).contentEquals(bytes)) { "noncanonical manifest" }
            }
        }
    }

    fun write(
        state: TradingRecoveryState,
        position: Long,
        target: OutputStream,
    ) {
        require(position >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestOutputStream(target, digest)
        val out = DataOutputStream(checked)
        out.writeInt(MAGIC)
        out.writeInt(2)
        out.writeLong(position)
        out.blob(encodeManifest(state.manifest))
        out.text(state.lifecycle.name)
        out.writeLong(state.lastAdmissionSequence)
        out.writeLong(state.lastPreparationSequence)
        out.writeLong(state.preparationCanonicalBytes)
        out.writeBoolean(state.incident != null)
        state.incident?.let {
            out.writeLong(it.position)
            out.text(it.reason)
        }
        out.rows(state.balances) { b ->
            out.text(b.userId.value)
            out.text(b.assetId.value)
            out.writeLong(b.available.value)
            out.writeLong(b.hold.value)
        }
        out.rows(state.feeRevenueByAsset.entries.sortedBy { it.key.value }) { e ->
            out.text(e.key.value)
            out.blob(e.value.toByteArray())
        }
        out.rows(state.usedBalances) { k ->
            out.text(k.userId.value)
            out.text(k.assetId.value)
        }
        out.rows(state.preparations) { r ->
            out.text(r.opening.preparationId)
            out.text(r.opening.userId.value)
            out.text(r.opening.assetId.value)
            out.writeLong(r.opening.amount.value)
            out.text(r.ledgerTransactionId)
            out.writeLong(r.occurredAt.epochSecond)
            out.writeInt(r.occurredAt.nano)
            out.writeBoolean(r.alreadyPrepared)
        }
        out.rows(state.accounts) { out.text(it.value) }
        out.rows(state.liveOrders) { o ->
            out.writeLong(o.admissionSequence)
            out.writeLong(o.marketAdmissionSequence)
            writeOrder(out, o.order)
            out.blob(reservations.encodeReservation(o.reservation))
            out.text(o.phase.name)
        }
        out.rows(state.markets) { m ->
            out.text(m.marketId.value)
            out.writeLong(m.lastAssigned)
            out.writeLong(m.executedThrough)
            out.rows(m.pendingAdmissionSequences) { out.writeLong(it) }
        }
        out.rows(state.matching.markets) { m ->
            out.text(m.marketId.value)
            out.writeLong(m.lastEngineSequence)
            out.rows(m.orders) { o ->
                out.text(o.orderId.value)
                out.text(o.userId.value)
                out.text(o.side.name)
                out.writeLong(o.price.value)
                out.writeLong(o.originalQuantity.value)
                out.writeLong(o.remainingQuantity.value)
            }
        }
        out.rows(state.inbox.channels) { c ->
            out.text(c.userId.value)
            out.writeInt(c.channel)
            out.writeLong(c.epoch)
            out.writeLong(c.retiredThrough)
            out.writeLong(c.processedThrough)
            out.rows(c.entries) { e ->
                out.writeLong(e.sequence)
                writeIntent(out, e.intent)
                writeReceipt(out, e.receipt)
            }
        }
        out.flush()
        checked.on(false)
        out.write(digest.digest())
        out.writeInt(END)
        out.flush()
    }

    fun read(
        source: InputStream,
        expected: TradingClusterManifest,
        expectedPosition: Long,
    ): TradingRecoveryState {
        val digest = MessageDigest.getInstance("SHA-256")
        val checked = DigestInputStream(LimitedInput(source, MAX_SNAPSHOT_BYTES), digest)
        val input = DataInputStream(checked)
        require(input.readInt() == MAGIC && input.readInt() == 2) { "unsupported bounded snapshot" }
        require(input.readLong() == expectedPosition) { "snapshot framework position differs" }
        val manifest = decodeManifest(input.blob(MAX_MANIFEST_BYTES))
        require(manifest == expected) { "snapshot execution manifest differs" }
        val limits = manifest.limits
        val lifecycle = TradingLifecycle.valueOf(input.text())
        val lastAdmission = input.readLong()
        val lastPreparation = input.readLong()
        val preparationBytes = input.readLong()
        val incident = if (input.readBoolean()) TradingIncident(input.readLong(), input.text()) else null
        val balances =
            input.rows(limits.maxBalanceKeys) {
                Balance(UserId(input.text()), AssetId(input.text()), Amount(input.readLong()), Amount(input.readLong()))
            }
        val feeRows = input.rows(limits.maxAssets) { AssetId(input.text()) to BigInteger(input.blob(64)) }
        require(feeRows.map { it.first }.distinct().size == feeRows.size)
        val used = input.rows(limits.maxBalanceKeys) { TradingBalanceKey(UserId(input.text()), AssetId(input.text())) }
        val preparations =
            input.rows(limits.maxPreparations) {
                val opening = OpeningBalance(input.text(), UserId(input.text()), AssetId(input.text()), Amount(input.readLong()))
                OpeningBalanceResult(
                    opening,
                    input.text(),
                    Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
                    input.readBoolean(),
                )
            }
        val accounts = input.rows(limits.maxAccounts) { UserId(input.text()) }
        val live =
            input.rows(limits.maxActiveOrders) {
                TradingLiveOrderSnapshot(
                    input.readLong(),
                    input.readLong(),
                    readOrder(input),
                    reservations.decodeReservation(
                        input.blob(64 * 1024),
                    ),
                    LiveOrderPhase.valueOf(input.text()),
                )
            }
        val markets =
            input.rows(limits.maxMarkets) {
                TradingMarketProgressSnapshot(
                    MarketId(input.text()),
                    input.readLong(),
                    input.readLong(),
                    input.rows(limits.maxPendingOrders) {
                        input.readLong()
                    },
                )
            }
        val books =
            input.rows(limits.maxMarkets) {
                val marketId = MarketId(input.text())
                val sequence = input.readLong()
                MatchingCheckpoint.Market(
                    marketId,
                    sequence,
                    input.rows(limits.maxActiveOrders) {
                        MatchingCheckpoint.Order(
                            OrderId(input.text()),
                            UserId(input.text()),
                            Side.valueOf(input.text()),
                            Price(input.readLong()),
                            Quantity(input.readLong()),
                            Quantity(input.readLong()),
                        )
                    },
                )
            }
        var entryCount = 0
        val channels =
            input.rows(limits.maxChannels) {
                val user = UserId(input.text())
                val channel = input.readInt()
                val epoch = input.readLong()
                val retired = input.readLong()
                val processed = input.readLong()
                val entries =
                    input.rows(minOf(limits.requestWindow, limits.maxInboxEntries - entryCount)) {
                        TradingInboxEntrySnapshot(input.readLong(), readIntent(input), readReceipt(input))
                    }
                entryCount = Math.addExact(entryCount, entries.size)
                TradingInboxChannelSnapshot(user, channel, epoch, retired, processed, entries)
            }
        checked.on(false)
        val expectedDigest = ByteArray(32).also(input::readFully)
        require(MessageDigest.isEqual(digest.digest(), expectedDigest)) { "bounded snapshot checksum differs" }
        require(input.readInt() == END && input.read() == -1) { "bounded snapshot footer differs" }
        return TradingRecoveryState(
            manifest,
            lifecycle,
            balances,
            feeRows.toMap(),
            used,
            preparations,
            accounts,
            live,
            markets,
            MatchingCheckpoint(books),
            TradingInboxSnapshot(channels),
            lastAdmission,
            lastPreparation,
            preparationBytes,
            incident,
        )
    }

    private fun writeManifest(
        out: DataOutputStream,
        m: TradingClusterManifest,
    ) {
        out.writeInt(MANIFEST_MAGIC)
        out.writeInt(1)
        out.writeLong(m.lineage.high)
        out.writeLong(m.lineage.low)
        out.text(m.algorithmId)
        out.text(m.executionArtifactSha256)
        out.text(m.schemaSha256)
        out.writeLong(m.configurationVersion)
        val l = m.limits
        listOf(l.maxAccounts, l.channelsPerAccount, l.maxChannels, l.requestWindow, l.maxInboxEntries).forEach(out::writeInt)
        out.writeLong(l.maxInboxBytes)
        listOf(
            l.maxActiveOrders,
            l.maxPendingOrders,
            l.maxMarketActiveOrders,
            l.maxMarketPendingOrders,
            l.maxBalanceKeys,
            l.maxPreparations,
        ).forEach(out::writeInt)
        out.writeLong(l.maxPreparationBytes)
        out.writeInt(l.maxMarkets)
        out.writeInt(l.maxAssets)
        out.writeInt(l.maxIdentifierBytes)
        out.rows(m.markets) { c ->
            out.writeInt(c.ordinal)
            val d = c.market
            out.text(d.marketId.value)
            out.text(d.baseAssetId.value)
            out.text(d.quoteAssetId.value)
            out.writeInt(d.baseAssetScale)
            out.writeLong(d.priceTick)
            out.writeLong(d.quantityStep)
            val p = c.feePolicySnapshot
            out.text(p.productType.name)
            out.text(p.feeTier.name)
            out.writeLong(p.scheduleVersion)
            out.writeLong(p.feeRates.makerFeeRate.partsPerMillion)
            out.writeLong(p.feeRates.takerFeeRate.partsPerMillion)
        }
    }

    private fun readManifest(input: DataInputStream): TradingClusterManifest {
        require(input.readInt() == MANIFEST_MAGIC && input.readInt() == 1)
        val lineage = ClusterLineage(input.readLong(), input.readLong())
        val algorithm = input.text()
        val artifact = input.text()
        val schema = input.text()
        val version = input.readLong()
        val limits =
            TradingResourceLimits(
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readLong(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readLong(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
            )
        val markets =
            input.rows(minOf(limits.maxMarkets, 128)) {
                val ordinal = input.readInt()
                val market =
                    MarketDefinition(
                        MarketId(input.text()),
                        AssetId(input.text()),
                        AssetId(input.text()),
                        input.readInt(),
                        input.readLong(),
                        input.readLong(),
                    )
                val policy =
                    TradingFeePolicySnapshot(
                        FeeProductType.valueOf(input.text()),
                        FeeTier.valueOf(input.text()),
                        input.readLong(),
                        MakerTakerFeeRates(FeeRate(input.readLong()), FeeRate(input.readLong())),
                    )
                TradingMarketContext(market, policy, ordinal)
            }
        return TradingClusterManifest(lineage, algorithm, artifact, schema, version, markets, limits)
    }

    private fun writeOrder(
        out: DataOutputStream,
        o: OrderAdmissionInput,
    ) {
        out.text(o.marketId.value)
        out.text(o.orderId.value)
        out.text(o.userId.value)
        out.text(o.side.name)
        out.text(o.orderType.name)
        out.text(o.timeInForce.name)
        out.writeLong(o.price.value)
        out.writeLong(o.quantity.value)
    }

    private fun readOrder(i: DataInputStream) =
        OrderAdmissionInput(
            MarketId(i.text()),
            OrderId(i.text()),
            UserId(i.text()),
            Side.valueOf(i.text()),
            OrderType.valueOf(i.text()),
            TimeInForce.valueOf(i.text()),
            Price(i.readLong()),
            Quantity(i.readLong()),
        )

    private fun writeIntent(
        o: DataOutputStream,
        i: OrderIntent,
    ) {
        o.text(i.marketId.value)
        o.writeLong(i.expectedConfigurationVersion)
        o.text(i.side.name)
        o.text(i.orderType.name)
        o.text(i.timeInForce.name)
        o.writeLong(i.price.value)
        o.writeLong(i.quantity.value)
    }

    private fun readIntent(i: DataInputStream) =
        OrderIntent(
            MarketId(i.text()),
            i.readLong(),
            Side.valueOf(i.text()),
            OrderType.valueOf(i.text()),
            TimeInForce.valueOf(i.text()),
            Price(i.readLong()),
            Quantity(i.readLong()),
        )

    private fun writeReceipt(
        o: DataOutputStream,
        r: AdmissionReceipt,
    ) {
        o.text(r.code.name)
        o.writeBoolean(r.orderId != null)
        r.orderId?.let { o.text(it.value) }
        o.writeBoolean(r.admissionSequence != null)
        r.admissionSequence?.let(o::writeLong)
        o.writeBoolean(r.marketAdmissionSequence != null)
        r.marketAdmissionSequence?.let(o::writeLong)
        o.writeBoolean(r.reason != null)
        r.reason?.let { o.text(it.name) }
        o.writeBoolean(r.insufficientBalance != null)
        r.insufficientBalance?.let { b ->
            o.text(b.assetId.value)
            o.writeLong(b.available.value)
            o.writeLong(b.required.value)
            o.writeLong(b.shortfall.value)
        }
    }

    private fun readReceipt(i: DataInputStream): AdmissionReceipt {
        val code = AdmissionCode.valueOf(i.text())
        val order = if (i.readBoolean()) OrderId(i.text()) else null
        val admission = if (i.readBoolean()) i.readLong() else null
        val market = if (i.readBoolean()) i.readLong() else null
        val reason = if (i.readBoolean()) OrderAdmissionResult.Reason.valueOf(i.text()) else null
        val shortage =
            if (i.readBoolean()) {
                OrderAdmissionResult.InsufficientBalance(
                    AssetId(i.text()),
                    Amount(i.readLong()),
                    Amount(i.readLong()),
                    Amount(i.readLong()),
                )
            } else {
                null
            }
        return AdmissionReceipt(code, order, admission, market, shortage, reason)
    }

    private fun DataOutputStream.text(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        require(b.size in 1..1024)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.text(): String {
        val b = blob(1024)
        val s = b.toString(Charsets.UTF_8)
        require(s.toByteArray(Charsets.UTF_8).contentEquals(b))
        return s
    }

    private fun DataOutputStream.blob(bytes: ByteArray) {
        require(bytes.size in 1..64 * 1024)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.blob(max: Int): ByteArray {
        val n = readInt()
        require(n in 1..max)
        return ByteArray(n).also(::readFully)
    }

    private fun <T> DataOutputStream.rows(
        rows: Collection<T>,
        writeRow: (T) -> Unit,
    ) {
        require(rows.size <= 1_000_000)
        writeInt(rows.size)
        rows.forEach(writeRow)
    }

    private fun <T> DataInputStream.rows(
        max: Int,
        readRow: () -> T,
    ): List<T> {
        val n = readInt()
        require(n in 0..minOf(max, 1_000_000))
        return List(n) { readRow() }
    }

    private class LimitedInput(
        source: InputStream,
        private val max: Long,
    ) : FilterInputStream(source) {
        private var consumed = 0L

        override fun read(): Int {
            val v = super.read()
            if (v >= 0) {
                consumed++
                require(consumed <= max)
            }
            return v
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = `in`.read(b, off, len)
            if (n >
                0
            ) {
                consumed += n
                require(consumed <= max)
            }
            return n
        }
    }

    companion object {
        const val MAX_MANIFEST_BYTES = com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec.MAX_MANIFEST_BYTES
        private const val MAX_SNAPSHOT_BYTES =
            128L * 1024 * 1024
        private const val MAGIC = 0x42545350
        private const val MANIFEST_MAGIC = 0x424d414e
        private const val END = 0x454e4432
    }
}

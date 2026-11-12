package com.exchange.core.trading

import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.Side
import java.util.Collections

/** 자금 역할에서만 적용하는 전달 및 정산 전 원본의 독립 보존 예산이다. */
data class MarketFundsDispatchLimits(
    val maxDispatchEntries: Int = 2_048,
    val maxDispatchBytes: Long = 4L * 1_024 * 1_024,
    val maxActiveOrders: Int = 4_096,
    val maxActiveBytes: Long = 4L * 1_024 * 1_024,
) {
    init {
        require(maxDispatchEntries > 0 && maxDispatchBytes > 0 && maxActiveOrders > 0 && maxActiveBytes > 0)
    }
}

class FundsDispatchLimits(
    marketLimits: Map<MarketId, MarketFundsDispatchLimits>,
    val maxDispatchEntries: Int = 4_096,
    val maxDispatchBytes: Long = 8L * 1_024 * 1_024,
    val maxActiveOrders: Int = 8_192,
    val maxActiveBytes: Long = 8L * 1_024 * 1_024,
) {
    val marketLimits: Map<MarketId, MarketFundsDispatchLimits> = Collections.unmodifiableMap(LinkedHashMap(marketLimits))

    init {
        require(this.marketLimits.isNotEmpty())
        require(maxDispatchEntries > 0 && maxDispatchBytes > 0 && maxActiveOrders > 0 && maxActiveBytes > 0)
    }

    override fun equals(other: Any?): Boolean =
        other is FundsDispatchLimits && marketLimits == other.marketLimits && maxDispatchEntries == other.maxDispatchEntries &&
            maxDispatchBytes == other.maxDispatchBytes && maxActiveOrders == other.maxActiveOrders && maxActiveBytes == other.maxActiveBytes

    override fun hashCode(): Int = listOf(marketLimits, maxDispatchEntries, maxDispatchBytes, maxActiveOrders, maxActiveBytes).hashCode()

    companion object {
        fun defaults(manifest: TradingClusterManifest): FundsDispatchLimits =
            FundsDispatchLimits(
                manifest.markets.associate {
                    it.market.marketId to
                        MarketFundsDispatchLimits(
                            maxDispatchEntries = manifest.limits.maxMarketPendingOrders,
                            maxActiveOrders = manifest.limits.maxMarketActiveOrders,
                        )
                },
                maxDispatchEntries = manifest.limits.maxPendingOrders,
                maxActiveOrders = manifest.limits.maxActiveOrders,
            )
    }
}

enum class FundsDispatchDirection { FUNDS_TO_MATCHING }

data class FundsDispatchStream(
    val fundsLineage: ClusterLineage,
    val matchingLineage: ClusterLineage,
    val marketId: MarketId,
    val ownershipEpoch: Long,
    val configurationVersion: Long,
) {
    val direction: FundsDispatchDirection get() = FundsDispatchDirection.FUNDS_TO_MATCHING

    init {
        require(fundsLineage != matchingLineage && ownershipEpoch > 0 && configurationVersion > 0)
    }
}

/** 기존 금융 manifest와 별도로 결속한다. 통합 manifest-v1의 직렬화는 바꾸지 않는다. */
class FundsDispatchConfiguration(
    topology: TradingRoleTopology,
    val limits: FundsDispatchLimits = FundsDispatchLimits.defaults(topology.fundsManifest),
    val inputContractVersion: Int = 1,
) {
    val manifest: TradingClusterManifest = topology.fundsManifest
    val assignments: List<TradingMarketAssignment> =
        Collections.unmodifiableList(
            manifest.markets.map { context -> topology.assignments.single { it.marketId == context.market.marketId } },
        )
    private val contexts = manifest.markets.associateBy { it.market.marketId }
    private val streams =
        assignments.associate {
            it.marketId to
                FundsDispatchStream(manifest.lineage, it.matchingLineage, it.marketId, it.ownershipEpoch, manifest.configurationVersion)
        }

    /** codec의 고정 128MiB보다 작게 보수적으로 산정한 모든 정상 상태의 저장 상한이다. */
    val snapshotByteUpperBound: Long

    init {
        require(inputContractVersion in 1..2) { "unsupported funds input contract" }
        require(limits.marketLimits.keys == contexts.keys) { "dispatch limits must cover exactly the registered markets" }
        for (context in manifest.markets) {
            val minimum =
                OrderAdmissionInput(
                    context.market.marketId,
                    TradingIdentifiers.order(manifest.lineage, 1),
                    com.exchange.core.common
                        .UserId("u"),
                    Side.BUY,
                    com.exchange.core.order.OrderType.LIMIT,
                    com.exchange.core.order.TimeInForce.GTC,
                    com.exchange.core.common
                        .Price(context.market.priceTick),
                    com.exchange.core.common
                        .Quantity(context.market.quantityStep),
                )
            val local = limits.marketLimits.getValue(context.market.marketId)
            val minimumActive =
                minOf(FundsDispatchCharge.active(minimum, context), FundsDispatchCharge.active(minimum.copy(side = Side.SELL), context))
            val minimumDispatch =
                minOf(FundsDispatchCharge.dispatch(minimum, context), FundsDispatchCharge.dispatch(minimum.copy(side = Side.SELL), context))
            require(minimumActive <= minOf(local.maxActiveBytes, limits.maxActiveBytes)) {
                "active budget cannot retain even one minimum order"
            }
            require(minimumDispatch <= minOf(local.maxDispatchBytes, limits.maxDispatchBytes)) {
                "dispatch budget cannot retain even one minimum order"
            }
        }
        // 자금 전용 포맷이 재사용하는 manifest-v1의 실제 표현 한도를 먼저 결속한다.
        require(manifest.markets.size <= 128 && manifestBytes(manifest) <= 3_072L) {
            "funds manifest exceeds the snapshot-v1 format bound"
        }
        snapshotByteUpperBound =
            try {
                snapshotBound(manifest.limits, limits, inputContractVersion)
            } catch (failure: ArithmeticException) {
                throw IllegalArgumentException("funds limits overflow the snapshot size bound", failure)
            }
        require(snapshotByteUpperBound <= MAX_SNAPSHOT_BYTES) { "funds limits exceed the snapshot size bound" }
    }

    fun stream(marketId: MarketId): FundsDispatchStream = requireNotNull(streams[marketId]) { "unregistered dispatch market" }

    internal fun context(marketId: MarketId): TradingMarketContext = requireNotNull(contexts[marketId]) { "unregistered dispatch market" }

    internal fun matches(stream: FundsDispatchStream): Boolean = streams[stream.marketId] == stream

    override fun equals(other: Any?): Boolean =
        other is FundsDispatchConfiguration && manifest == other.manifest && assignments == other.assignments && limits == other.limits &&
            inputContractVersion == other.inputContractVersion

    override fun hashCode(): Int = listOf(manifest, assignments, limits, inputContractVersion).hashCode()

    companion object {
        const val MAX_SNAPSHOT_BYTES: Long = 128L * 1_024 * 1_024

        private fun manifestBytes(manifest: TradingClusterManifest): Long {
            // magic/버전·lineage·고정 수치·문자열 길이 prefix·목록 수를 합한 v1 고정폭이다.
            var bytes =
                120L + tradingTextBytes(manifest.algorithmId) +
                    tradingTextBytes(manifest.executionArtifactSha256) + tradingTextBytes(manifest.schemaSha256)
            for (context in manifest.markets) {
                bytes =
                    Math.addExact(
                        bytes,
                        68L + tradingTextBytes(context.market.marketId.value) +
                            tradingTextBytes(context.market.baseAssetId.value) + tradingTextBytes(context.market.quoteAssetId.value) +
                            tradingTextBytes(context.feePolicySnapshot.productType.name) +
                            tradingTextBytes(context.feePolicySnapshot.feeTier.name),
                    )
            }
            return bytes
        }

        private fun snapshotBound(
            base: TradingResourceLimits,
            dispatch: FundsDispatchLimits,
            inputContractVersion: Int,
        ): Long {
            fun product(
                count: Int,
                bytes: Long,
            ) = Math.multiplyExact(count.toLong(), bytes)
            var bound = 256L * 1_024
            // Inbox v1은 모든 문자열·숫자와 384-byte 최초 응답 슬롯을 이미 계산한다.
            // 두 배의 여유로 저장 형식의 enum 문자열과 행 구분 비용을 포함한다.
            bound = Math.addExact(bound, Math.multiplyExact(base.maxInboxBytes, 2L))
            bound = Math.addExact(bound, Math.multiplyExact(base.maxPreparationBytes, 4L))
            // 두 보존 예산 모두 주문·예약·정책의 전체 문자열을 포함한다.
            // 네 배의 여유로 enum 문자열·예약 blob·목록 구분 비용을 포함한다.
            bound = Math.addExact(bound, Math.multiplyExact(dispatch.maxActiveBytes, 4L))
            bound = Math.addExact(bound, Math.multiplyExact(dispatch.maxDispatchBytes, 4L))
            if (inputContractVersion == 2) {
                // 활성 최초 원본과 미완료 취소의 종료 근거는 기존 전달/금융 예산과 별도로 보존한다.
                bound = Math.addExact(bound, Math.multiplyExact(dispatch.maxActiveBytes, 2L))
                bound = Math.addExact(bound, Math.multiplyExact(dispatch.maxDispatchBytes, 2L))
            }
            bound = Math.addExact(bound, product(base.maxBalanceKeys, 4L * base.maxIdentifierBytes + 128))
            bound = Math.addExact(bound, product(base.maxAccounts, base.maxIdentifierBytes + 32L))
            bound = Math.addExact(bound, product(base.maxChannels, base.maxIdentifierBytes + 128L))
            bound = Math.addExact(bound, product(base.maxAssets, base.maxIdentifierBytes + 1_024L))
            return Math.addExact(bound, product(base.maxMarkets, 4_096L))
        }
    }
}

/** 보존 중에는 최초 예약 그대로다. 현재 reservation의 변경을 전달 원본에 반영하지 않는다. */
data class FundsOrderDispatch(
    val stream: FundsDispatchStream,
    val dispatchSequence: Long,
    val admissionSequence: Long,
    val marketAdmissionSequence: Long,
    val order: OrderAdmissionInput,
    val reservation: OrderReservation,
    val context: TradingMarketContext,
    val cancel: FundsCancelDispatch? = null,
    val inputContractVersion: Int = 1,
) {
    val formatVersion: Int get() = inputContractVersion
    val isCancellation: Boolean get() = cancel != null
    val reservationId: String get() = TradingIdentifiers.reserve(stream.fundsLineage, admissionSequence)
    val dispatchCanonicalBytes: Long get() =
        Math.addExact(
            FundsDispatchCharge.dispatch(order, context),
            cancel?.let { 96L + tradingTextBytes(it.requestToken.userId.value) } ?: 0L,
        )
    val activeCanonicalBytes: Long get() = FundsDispatchCharge.active(order, context)
}

/**
 * 논리 charge v1. 문자열은 4-byte 길이와 UTF-8 본문, enum은 4-byte slot이다.
 * order는 3문자열+3enum+2long, reservation은 4문자열+2enum+9long+1flag,
 * context는 3문자열+2int+2long+2enum+3long이다. 활성 slot은 version/int,
 * 두 sequence/long와 phase/enum을 더한다. 전달 slot은 version/int, 두 lineage,
 * route market, epoch/version/3sequence/long, direction/enum, reserve ID를 더한다.
 * 실제 wire/JVM 객체 크기가 아니며, 공유하는 원본/정책도 각 예산에서 보수적으로 센다.
 */
object FundsDispatchCharge {
    fun active(
        order: OrderAdmissionInput,
        context: TradingMarketContext,
    ): Long = 24L + payload(order, context)

    fun dispatch(
        order: OrderAdmissionInput,
        context: TradingMarketContext,
    ): Long = 80L + text(order.marketId.value) + text(order.orderId.value) + payload(order, context)

    private fun payload(
        order: OrderAdmissionInput,
        context: TradingMarketContext,
    ): Long {
        val market = context.market
        val reserveAsset = if (order.side == Side.BUY) market.quoteAssetId else market.baseAssetId
        val orderBytes = 28L + text(order.marketId.value) + text(order.orderId.value) + text(order.userId.value)
        val reservationBytes =
            81L + text(order.marketId.value) + text(order.orderId.value) + text(order.userId.value) + text(reserveAsset.value)
        val contextBytes = 56L + text(market.marketId.value) + text(market.baseAssetId.value) + text(market.quoteAssetId.value)
        return orderBytes + reservationBytes + contextBytes
    }

    private fun text(value: String): Long = Int.SIZE_BYTES.toLong() + tradingTextBytes(value)
}

data class FundsMatchingAppliedAck(
    val stream: FundsDispatchStream,
    val through: Long,
    val context: TradingMarketContext,
)

enum class FundsDispatchAckStatus { ACKNOWLEDGED, INVALID_STREAM, INVALID_POLICY, INVALID_SEQUENCE, FUTURE_ACK }

data class FundsDispatchAck(
    val status: FundsDispatchAckStatus,
    val matchingAppliedThrough: Long,
)

enum class FundsDispatchReadStatus { BATCH, PRUNED, INVALID_QUERY, INVALID_STREAM, BATCH_TOO_SMALL }

class FundsDispatchRead(
    val status: FundsDispatchReadStatus,
    entries: List<FundsOrderDispatch> = emptyList(),
    val matchingAppliedThrough: Long = 0,
    val issued: Long = 0,
    val requiredBytes: Long = 0,
    val nextSequence: Long? = null,
) {
    val entries: List<FundsOrderDispatch> = Collections.unmodifiableList(entries.toList())
}

class FundsDispatchStreamSnapshot(
    val stream: FundsDispatchStream,
    val issued: Long,
    val matchingAppliedThrough: Long,
    outstanding: List<FundsOrderDispatch>,
    val dispatchBytes: Long,
    val activeOrders: Int,
    val activeBytes: Long,
) {
    val outstanding: List<FundsOrderDispatch> = Collections.unmodifiableList(outstanding.toList())

    override fun equals(other: Any?): Boolean =
        other is FundsDispatchStreamSnapshot && stream == other.stream && issued == other.issued &&
            matchingAppliedThrough == other.matchingAppliedThrough && outstanding == other.outstanding &&
            dispatchBytes == other.dispatchBytes && activeOrders == other.activeOrders && activeBytes == other.activeBytes

    override fun hashCode(): Int =
        listOf(stream, issued, matchingAppliedThrough, outstanding, dispatchBytes, activeOrders, activeBytes).hashCode()
}

class FundsDispatchSnapshot(
    streams: List<FundsDispatchStreamSnapshot>,
    val dispatchEntries: Int,
    val dispatchBytes: Long,
    val activeOrders: Int,
    val activeBytes: Long,
) {
    val streams: List<FundsDispatchStreamSnapshot> = Collections.unmodifiableList(streams.toList())

    override fun equals(other: Any?): Boolean =
        other is FundsDispatchSnapshot && streams == other.streams && dispatchEntries == other.dispatchEntries &&
            dispatchBytes == other.dispatchBytes && activeOrders == other.activeOrders && activeBytes == other.activeBytes

    override fun hashCode(): Int = listOf(streams, dispatchEntries, dispatchBytes, activeOrders, activeBytes).hashCode()
}

/** 상위 금융 소유자의 단일 서비스 스레드와 mutation 경계 안에서만 쓴다. */
internal class FundsDispatchData(
    private val configuration: FundsDispatchConfiguration,
) {
    private class Progress {
        var issued = 0L
        var matchingAppliedThrough = 0L
        var dispatchBytes = 0L
        var activeOrders = 0
        var activeBytes = 0L
        val outstanding = linkedMapOf<Long, FundsOrderDispatch>()
    }

    private val markets =
        configuration.manifest.markets
            .associate { it.market.marketId to Progress() }
            .toMutableMap()
    private var dispatchEntries = 0
    private var dispatchBytes = 0L
    private var activeOrders = 0
    private var activeBytes = 0L

    fun canAdmit(order: OrderAdmissionInput): Boolean {
        val progress = markets[order.marketId] ?: return false
        val context = configuration.context(order.marketId)
        val limits = configuration.limits
        val local = limits.marketLimits.getValue(order.marketId)
        val dispatchCharge = FundsDispatchCharge.dispatch(order, context)
        val activeCharge = FundsDispatchCharge.active(order, context)
        return dispatchEntries < limits.maxDispatchEntries && progress.outstanding.size < local.maxDispatchEntries &&
            activeOrders < limits.maxActiveOrders && progress.activeOrders < local.maxActiveOrders &&
            dispatchCharge <= limits.maxDispatchBytes - dispatchBytes &&
            dispatchCharge <= local.maxDispatchBytes - progress.dispatchBytes &&
            activeCharge <= limits.maxActiveBytes - activeBytes && activeCharge <= local.maxActiveBytes - progress.activeBytes
    }

    /** 모든 할당/검사는 금융 쓰기 전에 끝낸다. 실제 반영은 record가 같은 mutation에서 한다. */
    fun prepare(live: TradingLiveOrderSnapshot): FundsOrderDispatch {
        requireInitial(live)
        require(canAdmit(live.order)) { "dispatch capacity exceeded" }
        val progress = markets.getValue(live.order.marketId)
        val next = Math.addExact(progress.issued, 1L)
        if (configuration.inputContractVersion ==
            1
        ) {
            require(live.marketAdmissionSequence == next) { "dispatch and market admission sequence differ" }
        }
        return FundsOrderDispatch(
            configuration.stream(live.order.marketId),
            next,
            live.admissionSequence,
            live.marketAdmissionSequence,
            live.order,
            live.reservation,
            configuration.context(live.order.marketId),
            inputContractVersion = configuration.inputContractVersion,
        )
    }

    fun record(prepared: FundsOrderDispatch) {
        val progress = markets.getValue(prepared.order.marketId)
        check(configuration.matches(prepared.stream) && prepared.context == configuration.context(prepared.order.marketId))
        check(prepared.dispatchSequence == Math.addExact(progress.issued, 1L))
        check(!prepared.isCancellation && prepared.formatVersion == configuration.inputContractVersion)
        if (configuration.inputContractVersion == 1) check(prepared.marketAdmissionSequence == prepared.dispatchSequence)
        check(canAdmit(prepared.order)) { "prepared dispatch capacity changed" }
        check(progress.outstanding.put(prepared.dispatchSequence, prepared) == null)
        progress.issued = prepared.dispatchSequence
        progress.dispatchBytes = Math.addExact(progress.dispatchBytes, prepared.dispatchCanonicalBytes)
        progress.activeBytes = Math.addExact(progress.activeBytes, prepared.activeCanonicalBytes)
        progress.activeOrders = Math.addExact(progress.activeOrders, 1)
        dispatchEntries = Math.addExact(dispatchEntries, 1)
        dispatchBytes = Math.addExact(dispatchBytes, prepared.dispatchCanonicalBytes)
        activeBytes = Math.addExact(activeBytes, prepared.activeCanonicalBytes)
        activeOrders = Math.addExact(activeOrders, 1)
    }

    fun nextSequence(market: MarketId): Long = Math.addExact(markets.getValue(market).issued, 1L)

    fun canCancel(input: FundsOrderDispatch): Boolean {
        require(configuration.inputContractVersion == 2 && input.isCancellation && input.formatVersion == 2)
        val progress = markets.getValue(input.stream.marketId)
        val local = configuration.limits.marketLimits.getValue(input.stream.marketId)
        return dispatchEntries < configuration.limits.maxDispatchEntries && progress.outstanding.size < local.maxDispatchEntries &&
            input.dispatchCanonicalBytes <= configuration.limits.maxDispatchBytes - dispatchBytes &&
            input.dispatchCanonicalBytes <= local.maxDispatchBytes - progress.dispatchBytes
    }

    fun recordCancellation(input: FundsOrderDispatch) {
        val progress = markets.getValue(input.stream.marketId)
        check(configuration.inputContractVersion == 2 && input.isCancellation && input.formatVersion == 2)
        check(configuration.matches(input.stream) && input.context == configuration.context(input.stream.marketId))
        check(input.dispatchSequence == nextSequence(input.stream.marketId) && canCancel(input))
        check(progress.outstanding.put(input.dispatchSequence, input) == null)
        progress.issued = input.dispatchSequence
        progress.dispatchBytes = Math.addExact(progress.dispatchBytes, input.dispatchCanonicalBytes)
        dispatchEntries = Math.addExact(dispatchEntries, 1)
        dispatchBytes = Math.addExact(dispatchBytes, input.dispatchCanonicalBytes)
    }

    fun read(
        stream: FundsDispatchStream,
        fromSequence: Long,
        maxEntries: Int,
        maxBytes: Long,
    ): FundsDispatchRead {
        if (fromSequence <= 0 || maxEntries <= 0 || maxBytes <= 0) return FundsDispatchRead(FundsDispatchReadStatus.INVALID_QUERY)
        if (!configuration.matches(stream)) return FundsDispatchRead(FundsDispatchReadStatus.INVALID_STREAM)
        val progress = markets.getValue(stream.marketId)

        fun reply(
            status: FundsDispatchReadStatus,
            entries: List<FundsOrderDispatch> = emptyList(),
            requiredBytes: Long = 0,
        ): FundsDispatchRead {
            val next =
                when {
                    entries.isNotEmpty() -> {
                        entries
                            .last()
                            .dispatchSequence
                            .takeUnless { it == Long.MAX_VALUE }
                            ?.plus(1)
                    }

                    status == FundsDispatchReadStatus.PRUNED -> {
                        progress.matchingAppliedThrough.takeUnless { it == Long.MAX_VALUE }?.plus(1)
                    }

                    status == FundsDispatchReadStatus.BATCH || status == FundsDispatchReadStatus.BATCH_TOO_SMALL -> {
                        fromSequence
                    }

                    else -> {
                        null
                    }
                }
            return FundsDispatchRead(status, entries, progress.matchingAppliedThrough, progress.issued, requiredBytes, next)
        }
        if (fromSequence <= progress.matchingAppliedThrough) return reply(FundsDispatchReadStatus.PRUNED)
        if (fromSequence > progress.issued) {
            return if (progress.issued < Long.MAX_VALUE && fromSequence == progress.issued + 1) {
                reply(FundsDispatchReadStatus.BATCH)
            } else {
                reply(FundsDispatchReadStatus.INVALID_QUERY)
            }
        }
        val result = ArrayList<FundsOrderDispatch>()
        var bytes = 0L
        var sequence = fromSequence
        while (sequence <= progress.issued && result.size < maxEntries) {
            val entry = checkNotNull(progress.outstanding[sequence]) { "dispatch prefix contains a hole" }
            val charge = entry.dispatchCanonicalBytes
            if (charge > maxBytes - bytes) {
                if (result.isEmpty()) return reply(FundsDispatchReadStatus.BATCH_TOO_SMALL, requiredBytes = charge)
                break
            }
            result.add(entry)
            bytes = Math.addExact(bytes, charge)
            if (sequence == Long.MAX_VALUE) break
            sequence++
        }
        return reply(FundsDispatchReadStatus.BATCH, result)
    }

    /** typed ACK는 신뢰된 상대가 확인한 연속 적용 prefix다. 전송 성공을 이 함수에 넣지 않는다. */
    fun acknowledge(ack: FundsMatchingAppliedAck): FundsDispatchAck {
        if (!configuration.matches(ack.stream)) return FundsDispatchAck(FundsDispatchAckStatus.INVALID_STREAM, 0)
        val progress = markets.getValue(ack.stream.marketId)

        fun reply(status: FundsDispatchAckStatus) = FundsDispatchAck(status, progress.matchingAppliedThrough)
        if (ack.context != configuration.context(ack.stream.marketId)) return reply(FundsDispatchAckStatus.INVALID_POLICY)
        if (ack.through < 0) return reply(FundsDispatchAckStatus.INVALID_SEQUENCE)
        if (ack.through > progress.issued) return reply(FundsDispatchAckStatus.FUTURE_ACK)
        if (ack.through <= progress.matchingAppliedThrough) return reply(FundsDispatchAckStatus.ACKNOWLEDGED)
        var removedBytes = 0L
        var removedEntries = 0
        var sequence = progress.matchingAppliedThrough + 1
        while (sequence <= ack.through) {
            val entry = checkNotNull(progress.outstanding[sequence]) { "acknowledgement crossed a dispatch hole" }
            removedBytes = Math.addExact(removedBytes, entry.dispatchCanonicalBytes)
            removedEntries = Math.addExact(removedEntries, 1)
            if (sequence == Long.MAX_VALUE) break
            sequence++
        }
        sequence = progress.matchingAppliedThrough + 1
        while (sequence <= ack.through) {
            progress.outstanding.remove(sequence)
            if (sequence == Long.MAX_VALUE) break
            sequence++
        }
        progress.dispatchBytes -= removedBytes
        dispatchBytes -= removedBytes
        dispatchEntries -= removedEntries
        progress.matchingAppliedThrough = ack.through
        return reply(FundsDispatchAckStatus.ACKNOWLEDGED)
    }

    /** 실제 금융 종료만 활성 예산을 회수한다. 전달 ACK와 별도 수명이다. */
    fun finish(order: OrderAdmissionInput) {
        val progress = markets.getValue(order.marketId)
        val charge = FundsDispatchCharge.active(order, configuration.context(order.marketId))
        check(progress.activeOrders > 0 && activeOrders > 0 && progress.activeBytes >= charge && activeBytes >= charge)
        progress.activeOrders--
        progress.activeBytes -= charge
        activeOrders--
        activeBytes -= charge
    }

    fun snapshot(): FundsDispatchSnapshot =
        FundsDispatchSnapshot(
            configuration.manifest.markets.map { context ->
                val progress = markets.getValue(context.market.marketId)
                FundsDispatchStreamSnapshot(
                    configuration.stream(context.market.marketId),
                    progress.issued,
                    progress.matchingAppliedThrough,
                    progress.outstanding.values.toList(),
                    progress.dispatchBytes,
                    progress.activeOrders,
                    progress.activeBytes,
                )
            },
            dispatchEntries,
            dispatchBytes,
            activeOrders,
            activeBytes,
        )

    fun restore(
        saved: FundsDispatchSnapshot,
        liveOrders: List<TradingLiveOrderSnapshot>,
        financialMarkets: List<TradingMarketProgressSnapshot>,
        approvalInputs: List<FundsOrderDispatch> = emptyList(),
        cancellations: List<FundsCancellationSnapshot> = emptyList(),
    ) {
        check(dispatchEntries == 0 && activeOrders == 0 && markets.values.all { it.issued == 0L })
        val candidate = FundsDispatchData(configuration)
        val liveByAdmission = liveOrders.associateBy { it.admissionSequence }
        val originals = approvalInputs.associateBy { it.admissionSequence }
        val cancelBySequence = cancellations.associateBy { it.input.stream.marketId to it.input.dispatchSequence }
        require(liveByAdmission.size == liveOrders.size) { "duplicate financial admission" }
        for (live in liveOrders) {
            if (live.phase == LiveOrderPhase.PENDING) candidate.requireInitial(live)
            require(live.reservation.status == OrderReservationStatus.ACTIVE)
            val progress = candidate.markets.getValue(live.order.marketId)
            val charge = FundsDispatchCharge.active(live.order, configuration.context(live.order.marketId))
            progress.activeOrders = Math.addExact(progress.activeOrders, 1)
            progress.activeBytes = Math.addExact(progress.activeBytes, charge)
            candidate.activeOrders = Math.addExact(candidate.activeOrders, 1)
            candidate.activeBytes = Math.addExact(candidate.activeBytes, charge)
        }
        val finances = financialMarkets.associateBy { it.marketId }
        require(finances.size == financialMarkets.size && finances.keys == markets.keys)
        require(saved.streams.size == markets.size && saved.streams.map { it.stream.marketId }.toSet() == markets.keys)
        val seenAdmissions = hashSetOf<Long>()
        for (stream in saved.streams) {
            require(configuration.matches(stream.stream)) { "restored stream differs from fixed assignment" }
            val financial = finances.getValue(stream.stream.marketId)
            require(stream.issued >= 0 && stream.matchingAppliedThrough in 0..stream.issued)
            require(financial.lastAssigned <= stream.issued && financial.executedThrough in 0..stream.matchingAppliedThrough)
            if (configuration.inputContractVersion == 1) require(financial.lastAssigned == stream.issued)
            require(financial.pendingAdmissionSequences.size.toLong() == financial.lastAssigned - financial.executedThrough)
            val financialOrders =
                liveOrders
                    .filter {
                        it.order.marketId == stream.stream.marketId && it.phase == LiveOrderPhase.PENDING
                    }.sortedBy { it.marketAdmissionSequence }
            require(financialOrders.map { it.admissionSequence } == financial.pendingAdmissionSequences)
            require(
                financialOrders.withIndex().all { (index, live) ->
                    live.marketAdmissionSequence ==
                        Math.addExact(financial.executedThrough, index.toLong() + 1)
                },
            )
            val progress = candidate.markets.getValue(stream.stream.marketId)
            require(stream.outstanding.size.toLong() == stream.issued - stream.matchingAppliedThrough)
            var previous = stream.matchingAppliedThrough
            for (entry in stream.outstanding) {
                require(previous < Long.MAX_VALUE && entry.dispatchSequence == previous + 1)
                require(entry.stream == stream.stream && entry.marketAdmissionSequence in 1..entry.dispatchSequence)
                require(entry.formatVersion == configuration.inputContractVersion)
                if (configuration.inputContractVersion ==
                    1
                ) {
                    require(!entry.isCancellation && entry.marketAdmissionSequence == entry.dispatchSequence)
                }
                require(entry.context == configuration.context(entry.order.marketId) && entry.order.marketId == stream.stream.marketId)
                if (entry.isCancellation) {
                    require(cancelBySequence[entry.stream.marketId to entry.dispatchSequence]?.input == entry) { "orphan cancel dispatch" }
                } else {
                    val live = requireNotNull(liveByAdmission[entry.admissionSequence]) { "orphan dispatch envelope" }
                    require(
                        live.order == entry.order && live.reservation == entry.reservation &&
                            live.marketAdmissionSequence == entry.marketAdmissionSequence && seenAdmissions.add(entry.admissionSequence),
                    ) { "dispatch differs from initial financial order" }
                    originals[entry.admissionSequence]?.let { require(it == entry) { "dispatch approval differs" } }
                }
                progress.outstanding[entry.dispatchSequence] = entry
                progress.dispatchBytes = Math.addExact(progress.dispatchBytes, entry.dispatchCanonicalBytes)
                candidate.dispatchEntries = Math.addExact(candidate.dispatchEntries, 1)
                candidate.dispatchBytes = Math.addExact(candidate.dispatchBytes, entry.dispatchCanonicalBytes)
                previous = entry.dispatchSequence
            }
            progress.issued = stream.issued
            progress.matchingAppliedThrough = stream.matchingAppliedThrough
            require(
                progress.dispatchBytes == stream.dispatchBytes && progress.activeOrders == stream.activeOrders &&
                    progress.activeBytes == stream.activeBytes,
            ) { "stream retention counters differ" }
            val local = configuration.limits.marketLimits.getValue(stream.stream.marketId)
            require(progress.outstanding.size <= local.maxDispatchEntries && progress.dispatchBytes <= local.maxDispatchBytes)
            require(progress.activeOrders <= local.maxActiveOrders && progress.activeBytes <= local.maxActiveBytes)
        }
        require(
            candidate.dispatchEntries == saved.dispatchEntries && candidate.dispatchBytes == saved.dispatchBytes &&
                candidate.activeOrders == saved.activeOrders && candidate.activeBytes == saved.activeBytes,
        ) { "funds retention counters differ" }
        val limits = configuration.limits
        require(candidate.dispatchEntries <= limits.maxDispatchEntries && candidate.dispatchBytes <= limits.maxDispatchBytes)
        require(candidate.activeOrders <= limits.maxActiveOrders && candidate.activeBytes <= limits.maxActiveBytes)
        for ((market, progress) in candidate.markets) {
            markets[market] = progress
        }
        dispatchEntries = candidate.dispatchEntries
        dispatchBytes = candidate.dispatchBytes
        activeOrders = candidate.activeOrders
        activeBytes = candidate.activeBytes
    }

    private fun requireInitial(live: TradingLiveOrderSnapshot) {
        val order = live.order
        val reservation = live.reservation
        val context = configuration.context(order.marketId)
        require(live.phase == LiveOrderPhase.PENDING && live.admissionSequence > 0 && live.marketAdmissionSequence > 0)
        require(order.orderId == TradingIdentifiers.order(configuration.manifest.lineage, live.admissionSequence))
        require(reservation.marketId == order.marketId && reservation.orderId == order.orderId && reservation.userId == order.userId)
        require(reservation.side == order.side && reservation.limitPrice == order.price && reservation.initialQuantity == order.quantity)
        require(reservation.feePolicySnapshot == context.feePolicySnapshot && reservation.status == OrderReservationStatus.ACTIVE)
        require(reservation.assetId == if (order.side == Side.BUY) context.market.quoteAssetId else context.market.baseAssetId)
        require(reservation.remainingQuantity == reservation.initialQuantity && reservation.remainingAmount == reservation.reservedAmount)
        require(
            reservation.remainingFeeReserveAmount == reservation.initialFeeReserveAmount && reservation.feeRemainder == FeeRemainder.ZERO,
        )
        require(reservation.releasedAmount == null)
    }
}

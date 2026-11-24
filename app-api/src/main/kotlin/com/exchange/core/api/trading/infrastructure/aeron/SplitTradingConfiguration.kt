package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.history.FundsRoleSnapshotCodec
import com.exchange.core.api.trading.infrastructure.history.MatchingRoleSnapshotCodec
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsLedgerRecovery
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.QueryRetentionConfiguration
import com.exchange.core.trading.QueryRetentionLimits
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingRoleAssembly
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** 개발용 고정 배치와 한도를 양쪽 역할 및 복구 기록에 같은 바이트로 결속한다. */
internal class SplitTradingConfiguration(
    val topology: TradingRoleTopology,
    val fundsDeclaration: TradingRoleDeclaration,
    val matchingDeclaration: TradingRoleDeclaration,
    val dispatchLimits: FundsDispatchLimits = FundsDispatchLimits.defaults(topology.fundsManifest),
    val matchingLimits: MatchingIntakeLimits = MatchingIntakeLimits.defaults(topology, matchingDeclaration),
    val resultLimits: FundsResultLimits = FundsResultLimits(),
    val deliveryLimits: FundsOrderDeliveryLimits = FundsOrderDeliveryLimits(),
    val resultDeliveryLimits: MatchingResultDeliveryLimits = MatchingResultDeliveryLimits(),
    val inputContractVersion: Int = 1,
    matchingDeclarations: List<TradingRoleDeclaration> = listOf(matchingDeclaration),
    matchingLimitsByLineage: Map<ClusterLineage, MatchingIntakeLimits> =
        matchingDeclarations.associate { declaration ->
            declaration.lineage to
                if (declaration.lineage ==
                    matchingDeclaration.lineage
                ) {
                    matchingLimits
                } else {
                    MatchingIntakeLimits.defaults(topology, declaration)
                }
        },
    val queryRetention: QueryRetentionConfiguration? = null,
    val transferBatchEnabled: Boolean = false,
) {
    val matchingDeclarations: List<TradingRoleDeclaration> =
        Collections.unmodifiableList(matchingDeclarations.sortedWith(compareBy({ it.lineage.high }, { it.lineage.low })))
    val matchingLimitsByLineage: Map<ClusterLineage, MatchingIntakeLimits> =
        Collections.unmodifiableMap(LinkedHashMap(matchingLimitsByLineage))
    val fundsLineage get() = topology.fundsManifest.lineage
    val matchingLineage get() = matchingDeclaration.lineage
    val dispatchConfiguration = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion, queryRetention)
    val matchingConfiguration =
        MatchingIntakeConfiguration(topology, matchingDeclaration, matchingLimits, inputContractVersion, queryRetention)
    val recoveryLedgerConfigured = queryRetention?.capabilityVersion == 2 && topology.fundsManifest.markets.size == 1
    val topologySha256: String
    val resultTopologySha256: String

    /** 자기 담당만 고르되 전체 배치·한도·공통 지문은 모든 노드에 보존한다. */
    fun forMatching(lineage: ClusterLineage): SplitTradingConfiguration {
        val declaration = matchingDeclarations.single { it.lineage == lineage }
        return SplitTradingConfiguration(
            topology,
            fundsDeclaration,
            declaration,
            dispatchLimits,
            matchingLimitsByLineage.getValue(lineage),
            resultLimits,
            deliveryLimits,
            resultDeliveryLimits,
            inputContractVersion,
            matchingDeclarations,
            matchingLimitsByLineage,
            queryRetention,
            transferBatchEnabled,
        )
    }

    /** 담당별 worker의 합계가 기존 공통 조회·송신 상한을 넘지 않도록 나눈다. */
    fun orderDeliveryLimits(): FundsOrderDeliveryLimits {
        val owners = matchingDeclarations.size
        if (owners == 1) return deliveryLimits
        require(owners in 1..2) { "매칭 담당은 1~2개만 지원합니다" }
        return deliveryLimits.copy(
            maxReadEntries = deliveryLimits.maxReadEntries / owners,
            maxReadBytes = deliveryLimits.maxReadBytes / owners,
            maxInFlightEntries = deliveryLimits.maxInFlightEntries / owners,
            maxInFlightBytes = deliveryLimits.maxInFlightBytes / owners,
        )
    }

    /** 단일 담당의 기존 창은 유지하고 복수 담당 worker는 자기 ACK·연결 제어 공간을 남긴다. */
    fun orderDeliveryControlReserve(): Long = if (matchingDeclarations.size == 1) 0 else orderDeliveryControlReserve(matchingLineage)

    private fun orderDeliveryControlReserve(lineage: ClusterLineage): Long {
        val codec = SplitOrderDeliveryCodec(inputContractVersion)
        val maximumAck =
            topology.fundsManifest.markets
                .filter { dispatchConfiguration.stream(it.market.marketId).matchingLineage == lineage }
                .maxOf { context ->
                    codec
                        .encode(
                            Long.MAX_VALUE,
                            SplitOrderDeliveryCommand.RecordAck(
                                FundsMatchingAppliedAck(dispatchConfiguration.stream(context.market.marketId), Long.MAX_VALUE, context),
                            ),
                        ).size
                }
        return 2L * codec.encode(Long.MAX_VALUE, SplitOrderDeliveryCommand.DescribePeer).size + maximumAck
    }

    init {
        require(!transferBatchEnabled || inputContractVersion == 2) { "전송 배치는 입력 계약 2가 필요합니다" }
        validateRoleAssembly()
        topologySha256 = sha256(orderDeliveryConfigurationBytes())
        resultTopologySha256 = sha256(resultDeliveryConfigurationBytes())
    }

    /** 취소는 결과의 자금 반영까지 이어지는 명시적 양방향 실행에서만 활성화한다. */
    fun requireTransportCompatible(resultDeliveryEnabled: Boolean = false) {
        require(inputContractVersion == 1 || resultDeliveryEnabled) { "취소 입력 계약은 양방향 정산 개발 모드가 필요합니다" }
    }

    /** 기존 한 방향 실행의 한도를 바꾸지 않고 새 양방향 모드만 자원 생성 전에 검사한다. */
    fun requireResultDeliveryCompatible() {
        for (declaration in matchingDeclarations) {
            val selected = if (declaration.lineage == matchingLineage) this else forMatching(declaration.lineage)
            resultLimits.requireCompatible(selected.matchingLimits)
            require(resultDeliveryLimits.maxReadBytes >= selected.matchingLimits.maxSingleResultBytes) { "단일 최대 결과를 조회할 공간이 필요합니다" }
            require(
                SplitResultDeliveryCodec.maximumResultFrameBytes(
                    selected.matchingLimits.maxSingleResultBytes,
                    selected.matchingLimits.maxResultEvents,
                    inputContractVersion,
                ) <= SplitResultDeliveryCodec.MAX_MESSAGE_BYTES,
            ) { "허용된 전체 결과가 전송 한도를 넘습니다" }
            resultDeliveryLimits.requireCompatible(selected)
        }
    }

    fun funds(persistRecoveryLedger: Boolean = false): BoundedFundingState =
        TradingRoleAssembly.funds(
            topology,
            fundsDeclaration,
            OrderAdmissionCalculator(reservations()),
            dispatchLimits,
            resultLimits,
            inputContractVersion,
            queryRetention,
            persistRecoveryLedger = persistRecoveryLedger,
        )

    fun matching(): ReservedMatchingState =
        TradingRoleAssembly.reservedMatching(topology, matchingDeclaration, matchingLimits, inputContractVersion, queryRetention)

    fun settlementCalculator(): OrderSettlementCalculator {
        val fees = TradingFeeReserveCalculator()
        return OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            reservations(),
            fees,
            SettlementLedgerTransactions(),
        )
    }

    private fun validateRoleAssembly() {
        require(fundsDeclaration.serviceRole == TradingServiceRole.FUNDS)
        require(matchingDeclaration.serviceRole == TradingServiceRole.MATCHING)
        require(matchingDeclarations.size in 1..2) { "매칭 담당은 1~2개만 지원합니다" }
        val lineages = matchingDeclarations.map { it.lineage }
        require(lineages.distinct().size == lineages.size) { "중복 매칭 담당 선언입니다" }
        require(lineages.toSet() == topology.assignments.map { it.matchingLineage }.toSet()) { "전체 배치와 매칭 담당 선언이 다릅니다" }
        require(matchingLineage in lineages) { "자기 매칭 담당이 전체 선언에 없습니다" }
        require(matchingLimitsByLineage.keys == lineages.toSet()) { "매칭 한도는 전체 담당을 정확히 포함해야 합니다" }
        require(matchingLimits == matchingLimitsByLineage.getValue(matchingLineage)) { "자기 매칭 한도가 전체 구성과 다릅니다" }
        // declaration의 전체 context와 담당 세대는 기존 역할 조립 검사를 그대로 통과해야 한다.
        funds(persistRecoveryLedger = recoveryLedgerConfigured)
        matching()
        for (declaration in matchingDeclarations) {
            val limits = matchingLimitsByLineage.getValue(declaration.lineage)
            TradingRoleAssembly.reservedMatching(topology, declaration, limits, inputContractVersion, queryRetention)
            resultLimits.requireCompatible(limits)
        }
        // FundsDispatchCharge의 고정245byte와 최대256byte인 문자열12개(길이4byte 포함)의 보수적 상한이다.
        val cancellationBytes = if (inputContractVersion == 2) 96L + 256L else 0L
        val workerLimits = orderDeliveryLimits()
        require(workerLimits.maxReadBytes >= 245L + 12L * (4L + 256L) + cancellationBytes) { "단일 정상 승인 원본을 조회할 공간이 필요합니다" }
        require(SplitOrderDeliveryCodec.maximumDispatchFrameBytes(inputContractVersion) <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        if (matchingDeclarations.size > 1) {
            require(workerLimits.maxInFlightEntries >= 2) { "담당마다 주문 한 건과 ACK 공간이 필요합니다" }
            val controlBytes = matchingDeclarations.maxOf { orderDeliveryControlReserve(it.lineage) }
            require(
                workerLimits.maxInFlightBytes >= SplitOrderDeliveryCodec.maximumDispatchFrameBytes(inputContractVersion) + controlBytes,
            ) {
                "담당마다 단일 정상 주문과 ACK 제어 공간을 함께 보관해야 합니다"
            }
        }
    }

    /** 프로토콜, 두 역할의 구성, 수신 한도, 주문 전달 한도 순서를 기존 지문과 같게 유지한다. */
    private fun orderDeliveryConfigurationBytes(): ByteArray {
        val multipleOwners = matchingDeclarations.size > 1
        val matchingConfigurations =
            if (multipleOwners) {
                matchingDeclarations.map { declaration ->
                    MatchingIntakeConfiguration(
                        topology,
                        declaration,
                        matchingLimitsByLineage.getValue(declaration.lineage),
                        inputContractVersion,
                        queryRetention,
                    )
                }
            } else {
                listOf(matchingConfiguration)
            }
        val roleConfigurations =
            listOf(FundsRoleSnapshotCodec().configurationBytes(dispatchConfiguration)) +
                matchingConfigurations.map { MatchingRoleSnapshotCodec().configurationBytes(it) }
        return encodeConfiguration { out ->
            val prefix = if (multipleOwners) "split-multi-order-delivery" else "split-order-delivery"
            out.writeUTF("$prefix-v$inputContractVersion;schema=88;version=$inputContractVersion;frame=32768;session-limit=16")
            if (multipleOwners) out.writeInt(matchingDeclarations.size)
            for (part in roleConfigurations) {
                out.writeInt(part.size)
                out.write(part)
            }
            out.writeInt(resultLimits.maxResultEvents)
            out.writeLong(resultLimits.maxSingleResultBytes)
            out.writeInt(resultLimits.maxReceiptEntries)
            out.writeLong(resultLimits.maxReceiptBytes)
            out.writeInt(resultLimits.maxMarketReceiptEntries)
            out.writeLong(resultLimits.maxMarketReceiptBytes)
            writeOrderDeliveryLimits(out)
        }
    }

    /** 기존 주문 지문에 결과 프로토콜과 별도 전달 한도를 묶어 결과 모드 지문을 만든다. */
    private fun resultDeliveryConfigurationBytes(): ByteArray =
        encodeConfiguration { out ->
            val prefix = if (matchingDeclarations.size > 1) "split-multi-result-delivery" else "split-result-delivery"
            out.writeUTF("$prefix-v$inputContractVersion;schema=89;version=$inputContractVersion;header=12;session-limit=16")
            out.writeUTF(topologySha256)
            out.writeInt(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
            writeResultDeliveryLimits(out)
            if (recoveryLedgerConfigured) out.writeUTF("funds-recovery-ledger-v1")
        }

    private fun writeOrderDeliveryLimits(out: DataOutputStream) =
        with(deliveryLimits) {
            out.writeInt(maxReadEntries)
            out.writeLong(maxReadBytes)
            out.writeInt(maxInFlightEntries)
            out.writeLong(maxInFlightBytes)
            out.writeInt(ackBatchSize)
            out.writeLong(ackDelayNs)
            out.writeLong(attemptTimeoutNs)
            out.writeLong(reconnectDelayNs)
            out.writeLong(busyMinDelayNs)
            out.writeLong(busyMaxDelayNs)
            out.writeLong(keepAliveNs)
            out.writeLong(shutdownTimeoutMs)
        }

    private fun writeResultDeliveryLimits(out: DataOutputStream) =
        with(resultDeliveryLimits) {
            out.writeInt(maxReadEntries)
            out.writeLong(maxReadBytes)
            out.writeInt(maxInFlightEntries)
            out.writeLong(maxInFlightBytes)
            out.writeInt(ackBatchSize)
            out.writeLong(ackDelayNs)
            out.writeLong(attemptTimeoutNs)
            out.writeLong(reconnectDelayNs)
            out.writeLong(busyMinDelayNs)
            out.writeLong(busyMaxDelayNs)
            out.writeLong(keepAliveNs)
            out.writeLong(shutdownTimeoutMs)
        }

    private fun encodeConfiguration(write: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out -> write(out) }
            bytes.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun reservations() = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
}

/** 고정 consumer를 명시할 때만 원본 보관을 활성화한다. 모든 멤버가 같은 구성 지문을 사용한다. */
internal fun splitQueryRetentionConfiguration(
    topology: TradingRoleTopology,
    environment: Map<String, String>,
    dispatchLimits: FundsDispatchLimits,
    inputContractVersion: Int,
): QueryRetentionConfiguration? {
    val persistSettlements = environment["EXCHANGE_ORDER_QUERY_PERSIST_SETTLEMENTS"]?.toBooleanStrict() ?: false
    require(!persistSettlements || inputContractVersion == 2) { "정산 기록 저장에는 분리 입력 계약 2가 필요합니다" }
    val consumerId = environment["EXCHANGE_ORDER_QUERY_CONSUMER_ID"]
    if (consumerId == null) {
        require(!persistSettlements) { "정산 기록 저장에는 EXCHANGE_ORDER_QUERY_CONSUMER_ID 설정이 필요합니다" }
        return null
    }
    require(consumerId.isNotBlank()) { "EXCHANGE_ORDER_QUERY_CONSUMER_ID 설정이 비어 있습니다" }
    val baseBytes =
        FundsResultLimits().snapshotByteUpperBound(FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion))
    val sourceCount = if (persistSettlements) 1 + topology.fundsManifest.markets.size else 1
    val ledgerBytes = if (persistSettlements && topology.fundsManifest.markets.size == 1) FundsLedgerRecovery.SNAPSHOT_BYTES else 0L
    val room = (FundsDispatchConfiguration.MAX_SNAPSHOT_BYTES - baseBytes - ledgerBytes) / (4L * sourceCount)
    val defaultBytes = minOf(4L * 1_024 * 1_024, room)
    require(defaultBytes > 0) { "자금 snapshot 예산에 조회 원본을 보관할 여유가 없습니다" }
    val marketEntries = environment["EXCHANGE_ORDER_QUERY_MAX_MARKET_ENTRIES"]?.toInt() ?: 2_048
    val marketBytes = environment["EXCHANGE_ORDER_QUERY_MAX_MARKET_BYTES"]?.toLong() ?: minOf(2L * 1_024 * 1_024, defaultBytes)
    return QueryRetentionConfiguration(
        consumerId,
        topology.fundsManifest.markets.associate { it.market.marketId to QueryRetentionLimits(marketEntries, marketBytes) },
        environment["EXCHANGE_ORDER_QUERY_MAX_ENTRIES"]?.toInt() ?: 4_096,
        environment["EXCHANGE_ORDER_QUERY_MAX_BYTES"]?.toLong() ?: defaultBytes,
        capabilityVersion = if (persistSettlements) 2 else 1,
    )
}

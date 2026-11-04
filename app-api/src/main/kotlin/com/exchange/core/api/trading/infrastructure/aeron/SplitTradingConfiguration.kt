package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitResultDeliveryCodec
import com.exchange.core.api.trading.infrastructure.history.FundsRoleSnapshotCodec
import com.exchange.core.api.trading.infrastructure.history.MatchingRoleSnapshotCodec
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsResultLimits
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingRoleAssembly
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

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
) {
    val fundsLineage get() = topology.fundsManifest.lineage
    val matchingLineage get() = matchingDeclaration.lineage
    val dispatchConfiguration = FundsDispatchConfiguration(topology, dispatchLimits)
    val matchingConfiguration = MatchingIntakeConfiguration(topology, matchingDeclaration, matchingLimits)
    val topologySha256: String
    val resultTopologySha256: String

    init {
        validateRoleAssembly()
        topologySha256 = sha256(orderDeliveryConfigurationBytes())
        resultTopologySha256 = sha256(resultDeliveryConfigurationBytes())
    }

    /** 기존 한 방향 실행의 한도를 바꾸지 않고 새 양방향 모드만 자원 생성 전에 검사한다. */
    fun requireResultDeliveryCompatible() {
        resultLimits.requireCompatible(matchingLimits)
        require(resultDeliveryLimits.maxReadBytes >= matchingLimits.maxSingleResultBytes) { "단일 최대 결과를 조회할 공간이 필요합니다" }
        require(
            SplitResultDeliveryCodec.maximumResultFrameBytes(matchingLimits.maxSingleResultBytes, matchingLimits.maxResultEvents) <=
                SplitResultDeliveryCodec.MAX_MESSAGE_BYTES,
        ) { "허용된 전체 결과가 전송 한도를 넘습니다" }
        resultDeliveryLimits.requireCompatible(this)
    }

    fun funds(): BoundedFundingState =
        TradingRoleAssembly.funds(topology, fundsDeclaration, OrderAdmissionCalculator(reservations()), dispatchLimits, resultLimits)

    fun matching(): ReservedMatchingState = TradingRoleAssembly.reservedMatching(topology, matchingDeclaration, matchingLimits)

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
        require(topology.assignments.all { it.matchingLineage == matchingLineage }) {
            "개발 주문 전달은 고정된 한 매칭 클러스터만 연결합니다"
        }
        // declaration의 전체 context와 담당 세대는 기존 역할 조립 검사를 그대로 통과해야 한다.
        funds()
        matching()
        resultLimits.requireCompatible(matchingLimits)
        // 신규 원본의 고정245byte·최대 문자열12개와 취소의 고정80byte·최대 요청자256byte를 포함한다.
        require(deliveryLimits.maxReadBytes >= 245L + 12L * (4L + 256L) + 80L + 256L) { "단일 정상 승인 원본을 조회할 공간이 필요합니다" }
        require(SplitOrderDeliveryCodec.maximumDispatchFrameBytes() <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
    }

    /** 프로토콜, 두 역할의 구성, 수신 한도, 주문 전달 한도 순서를 기존 지문과 같게 유지한다. */
    private fun orderDeliveryConfigurationBytes(): ByteArray {
        val roleConfigurations =
            listOf(
                FundsRoleSnapshotCodec().configurationBytes(dispatchConfiguration),
                MatchingRoleSnapshotCodec().configurationBytes(matchingConfiguration),
            )
        return encodeConfiguration { out ->
            out.writeUTF("split-order-delivery-v2;schema=88;version=2;frame=32768;session-limit=16")
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
            out.writeUTF("split-result-delivery-v2;schema=89;version=2;header=12;session-limit=16")
            out.writeUTF(topologySha256)
            out.writeInt(SplitResultDeliveryCodec.MAX_MESSAGE_BYTES)
            writeResultDeliveryLimits(out)
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

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
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
) {
    val fundsLineage get() = topology.fundsManifest.lineage
    val matchingLineage get() = matchingDeclaration.lineage
    val dispatchConfiguration = FundsDispatchConfiguration(topology, dispatchLimits)
    val matchingConfiguration = MatchingIntakeConfiguration(topology, matchingDeclaration, matchingLimits)
    val topologySha256: String

    init {
        require(fundsDeclaration.serviceRole == TradingServiceRole.FUNDS)
        require(matchingDeclaration.serviceRole == TradingServiceRole.MATCHING)
        require(topology.assignments.all { it.matchingLineage == matchingLineage }) {
            "개발 주문 전달은 고정된 한 매칭 클러스터만 연결합니다"
        }
        // declaration의 전체 context와 담당 세대는 기존 역할 조립 검사를 그대로 통과해야 한다.
        funds()
        matching()
        resultLimits.requireCompatible(matchingLimits)
        // FundsDispatchCharge의 고정245byte와 최대256byte인 문자열12개(길이4byte 포함)의 보수적 상한이다.
        require(deliveryLimits.maxReadBytes >= 245L + 12L * (4L + 256L)) { "단일 정상 승인 원본을 조회할 공간이 필요합니다" }
        require(SplitOrderDeliveryCodec.maximumDispatchFrameBytes() <= SplitOrderDeliveryCodec.MAX_MESSAGE_BYTES)
        topologySha256 =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeUTF("split-order-delivery-v1;schema=88;version=1;frame=32768;session-limit=16")
                    for (part in listOf(
                        FundsRoleSnapshotCodec().configurationBytes(dispatchConfiguration),
                        MatchingRoleSnapshotCodec().configurationBytes(matchingConfiguration),
                    )) {
                        out.writeInt(part.size)
                        out.write(part)
                    }
                    out.writeInt(resultLimits.maxResultEvents)
                    out.writeLong(resultLimits.maxSingleResultBytes)
                    out.writeInt(resultLimits.maxReceiptEntries)
                    out.writeLong(resultLimits.maxReceiptBytes)
                    out.writeInt(resultLimits.maxMarketReceiptEntries)
                    out.writeLong(resultLimits.maxMarketReceiptBytes)
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
                }
                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
            }
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

    private fun reservations() = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))
}

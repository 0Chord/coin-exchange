package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.admissionCalculator
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingState
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.codecs.MessageHeaderEncoder
import io.aeron.cluster.codecs.SessionMessageHeaderEncoder
import org.agrona.concurrent.UnsafeBuffer

internal fun tradingState(): TradingState {
    val fees = TradingFeeReserveCalculator()
    val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
    return TradingState(
        admissionCalculator(),
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
            reservations,
            fees,
            SettlementLedgerTransactions(),
        ),
    )
}

/** 실제 callback처럼 업무 본문 앞에 Cluster의 SBE 세션 헤더를 둔다. */
internal fun clusterFrame(
    bytes: ByteArray,
    clusterSessionId: Long = 1L,
): UnsafeBuffer {
    val buffer = UnsafeBuffer(ByteArray(AeronCluster.SESSION_HEADER_LENGTH + bytes.size))
    SessionMessageHeaderEncoder()
        .wrapAndApplyHeader(buffer, 0, MessageHeaderEncoder())
        .leadershipTermId(1)
        .clusterSessionId(clusterSessionId)
        .timestamp(1_000)
    buffer.putBytes(AeronCluster.SESSION_HEADER_LENGTH, bytes)
    return buffer
}

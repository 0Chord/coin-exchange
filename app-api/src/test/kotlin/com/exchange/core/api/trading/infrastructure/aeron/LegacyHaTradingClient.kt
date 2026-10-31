package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageFormat
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.common.OrderId
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Legacy-only wire implementation; can be copied into the original baseline without v3 references. */
private class LegacyHaProbeWire : HaProbeWire {
    override val mode = "legacy-wire2-rocksdb-rebuildable"
    private val orders = AdmissionCommandCodec()
    private val results = AdmissionResultCodec()
    private val funding = DevelopmentBalanceCommandCodec()
    private val funded = DevelopmentBalanceResultCodec()

    override fun initialize(connection: HaProbeConnection) = Unit

    override fun open(connection: HaProbeConnection) = Unit

    override fun acknowledge(
        connection: HaProbeConnection,
        side: Side,
        through: Long,
    ) = Unit

    override fun fund(
        connection: HaProbeConnection,
        command: DevelopmentBalanceCommandPayload,
    ) {
        connection.request(funding.encode(command)) { bytes ->
            val kind =
                ByteBuffer
                    .wrap(bytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(2)
                    .toInt()
            if (kind != AdmissionMessageFormat.DEVELOPMENT_BALANCE_PREPARED &&
                kind != AdmissionMessageFormat.DEVELOPMENT_BALANCE_REJECTED
            ) {
                false
            } else {
                val response = funded.decode(bytes)
                check(response is DevelopmentBalanceResultPayload.Prepared && response.result.opening == command.opening) {
                    "funding was not confirmed: $response"
                }
                true
            }
        }
    }

    override fun encodeOrder(
        index: Int,
        side: Side,
    ): ByteArray {
        val workload = TradingEngineProbeWorkload
        val market = workload.market(index)
        val id = "${if (side == Side.SELL) "S" else "B"}-$index"
        return orders.encode(
            AdmissionCommandPayload(
                id,
                OrderAdmissionInput(
                    market.marketId,
                    OrderId(id),
                    if (side == Side.SELL) workload.seller else workload.buyer,
                    side,
                    OrderType.LIMIT,
                    TimeInForce.GTC,
                    workload.price,
                    workload.quantity(market),
                ),
                market,
                workload.policy,
                "reserve-$id",
                workload.time,
            ),
        )
    }

    override fun admission(
        bytes: ByteArray,
        side: Side,
    ): HaProbeAdmission? {
        val kind =
            ByteBuffer
                .wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getShort(2)
                .toInt()
        if (kind != AdmissionMessageFormat.ACCEPTED && kind != AdmissionMessageFormat.REJECTED) return null
        val response = results.decode(bytes)
        check(response is AdmissionResultPayload.Accepted) { "unexpected admission: $response" }
        val prefix = if (side == Side.SELL) "S-" else "B-"
        check(response.requestId.startsWith(prefix)) { "response belongs to another phase" }
        return HaProbeAdmission(response.requestId.removePrefix(prefix).toInt(), response.sequence)
    }
}

fun main(args: Array<String>) {
    runHaTradingProbeClient(LegacyHaProbeWire(), tradingEngineProbeOptions(args), System.getenv("HA_PROBE_IN_FLIGHT")?.toInt() ?: 256)
}

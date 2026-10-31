package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionCode
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.RequestToken

private class BoundedHaProbeWire : HaProbeWire {
    override val mode = "bounded-wire3-no-history-index"
    override val requestWindow = haBoundedProbeManifest().limits.requestWindow.toLong()
    private val codec = BoundedTradingCodec()
    private var correlation = 1L
    private lateinit var plan: HaProbeRunPlan

    override fun configure(plan: HaProbeRunPlan) {
        this.plan = plan
    }

    private fun control(
        connection: HaProbeConnection,
        command: BoundedTradingCommand,
        codes: Set<String>,
    ) {
        val expected = correlation++
        connection.request(codec.encode(expected, command)) { frame ->
            val reply = codec.decodeResponse(frame)
            if (reply.correlationId != expected) {
                false
            } else {
                check(reply.code in codes) { "unexpected control reply: ${reply.code}" }
                true
            }
        }
    }

    override fun initialize(connection: HaProbeConnection) =
        control(
            connection,
            BoundedTradingCommand.Initialize(BoundedTradingStateCodec().encodeManifest(haBoundedProbeManifest())),
            setOf("INITIALIZED", "ALREADY_INITIALIZED"),
        )

    override fun open(connection: HaProbeConnection) {
        for (user in listOf(TradingEngineProbeWorkload.buyer, TradingEngineProbeWorkload.seller)) {
            control(connection, BoundedTradingCommand.Rotate(user, 0, 0), setOf("ISSUED", "ALREADY_ISSUED"))
        }
    }

    override fun acknowledge(
        connection: HaProbeConnection,
        side: Side,
        through: Long,
    ) {
        val user = if (side == Side.SELL) TradingEngineProbeWorkload.seller else TradingEngineProbeWorkload.buyer
        control(connection, BoundedTradingCommand.Acknowledge(user, 0, 1, through), setOf("ACKNOWLEDGED"))
    }

    override fun encodeAcknowledgement(
        side: Side,
        through: Long,
    ): HaProbeAcknowledgement {
        val expected = correlation++
        val user = if (side == Side.SELL) TradingEngineProbeWorkload.seller else TradingEngineProbeWorkload.buyer
        return HaProbeAcknowledgement(expected, through, codec.encode(expected, BoundedTradingCommand.Acknowledge(user, 0, 1, through)))
    }

    override fun acknowledgement(
        bytes: ByteArray,
        expected: HaProbeAcknowledgement,
    ): Long? {
        val reply = codec.decodeResponse(bytes)
        if (reply.correlationId != expected.correlation) return null
        check(reply.code == "ACKNOWLEDGED" && reply.epoch == 1L) { "unexpected ACK reply: ${reply.code}" }
        return checkNotNull(reply.retiredThrough)
    }

    override fun fund(
        connection: HaProbeConnection,
        command: DevelopmentBalanceCommandPayload,
    ) = control(connection, BoundedTradingCommand.Funding(command), setOf("PREPARED"))

    override fun encodeOrder(
        index: Int,
        side: Side,
    ): ByteArray {
        val workload = TradingEngineProbeWorkload
        val market = workload.market(index)
        val user = if (side == Side.SELL) workload.seller else workload.buyer
        return codec.encode(
            base(side) + index,
            BoundedTradingCommand.Admit(
                RequestToken(user, 0, 1, index + 1L),
                OrderIntent(market.marketId, 1, side, OrderType.LIMIT, TimeInForce.GTC, workload.price, plan.orderQuantity(index, side)),
            ),
        )
    }

    override fun admission(
        bytes: ByteArray,
        side: Side,
    ): HaProbeAdmission? {
        val reply = codec.decodeResponse(bytes)
        if (reply.correlationId < 1_000_000) return null
        check(reply.code == "ACCEPTED" && reply.admission?.code == AdmissionCode.ACCEPTED) { "unexpected admission reply ${reply.code}" }
        val index = reply.correlationId - base(side)
        val limit = if (side == Side.SELL) plan.sellOrders else plan.capacity
        check(index in 0 until limit.toLong())
        return HaProbeAdmission(index.toInt(), checkNotNull(reply.admission?.admissionSequence))
    }

    private fun base(side: Side): Long =
        if (plan.partialMaker) {
            if (side == Side.SELL) 2L shl 40 else 1L shl 40
        } else if (side == Side.SELL) {
            2_000_000
        } else {
            1_000_000
        }
}

fun main(args: Array<String>) {
    runHaTradingProbeClient(BoundedHaProbeWire(), tradingEngineProbeOptions(args), System.getenv("HA_PROBE_IN_FLIGHT")?.toInt() ?: 256)
}

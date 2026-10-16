package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommandDecoder
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommandEncoder
import com.exchange.core.api.trading.infrastructure.codec.generated.MessageHeaderEncoder
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
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionInput
import org.agrona.concurrent.UnsafeBuffer
import java.time.Instant
import com.exchange.core.api.trading.infrastructure.codec.generated.FeeTier as WireFeeTier
import com.exchange.core.api.trading.infrastructure.codec.generated.OrderType as WireOrderType
import com.exchange.core.api.trading.infrastructure.codec.generated.ProductType as WireProductType
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide
import com.exchange.core.api.trading.infrastructure.codec.generated.TimeInForce as WireTimeInForce

/** 기존 판단 입력을 변환한다. 접수 여부와 재전송 판단 순서는 접수 코어에 맡긴다. */
class AdmissionCommandCodec {
    fun encode(command: AdmissionCommandPayload): ByteArray {
        val texts =
            listOf(
                command.requestId,
                command.order.userId.value,
                command.order.marketId.value,
                command.order.orderId.value,
                command.market.marketId.value,
                command.market.baseAssetId.value,
                command.market.quoteAssetId.value,
                command.ledgerTransactionId,
            ).map(AdmissionMessageFormat::text)
        val bytes = ByteArray(AdmissionMessageFormat.MAX_MESSAGE_LENGTH)
        val encoder = AdmissionCommandEncoder().wrapAndApplyHeader(UnsafeBuffer(bytes), 0, MessageHeaderEncoder())
        encoder
            .side(WireSide.valueOf(command.order.side.name))
            .orderType(WireOrderType.valueOf(command.order.orderType.name))
            .timeInForce(WireTimeInForce.valueOf(command.order.timeInForce.name))
            .price(command.order.price.value)
            .quantity(command.order.quantity.value)
            .baseAssetScale(command.market.baseAssetScale.toShort())
            .productType(WireProductType.valueOf(command.feePolicySnapshot.productType.name))
            .feeTier(WireFeeTier.valueOf(command.feePolicySnapshot.feeTier.name))
            .scheduleVersion(command.feePolicySnapshot.scheduleVersion)
            .makerPpm(
                command.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion
                    .toInt(),
            ).takerPpm(
                command.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion
                    .toInt(),
            ).occurredAtEpochSecond(command.occurredAt.epochSecond)
            .occurredAtNano(command.occurredAt.nano)
        encoder.putRequestId(texts[0], 0, texts[0].size)
        encoder.putUserId(texts[1], 0, texts[1].size)
        encoder.putMarketId(texts[2], 0, texts[2].size)
        encoder.putOrderId(texts[3], 0, texts[3].size)
        encoder.putDefinitionMarketId(texts[4], 0, texts[4].size)
        encoder.putBaseAssetId(texts[5], 0, texts[5].size)
        encoder.putQuoteAssetId(texts[6], 0, texts[6].size)
        encoder.putLedgerTransactionId(texts[7], 0, texts[7].size)
        encoder.checkEncodingIsComplete()
        return bytes.copyOf(AdmissionMessageFormat.HEADER_LENGTH + encoder.encodedLength())
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AdmissionCommandPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            reader.expect(AdmissionCommandDecoder.TEMPLATE_ID, AdmissionCommandDecoder.BLOCK_LENGTH)
            val decoder =
                AdmissionCommandDecoder().wrap(
                    reader.buffer,
                    AdmissionMessageFormat.HEADER_LENGTH,
                    AdmissionCommandDecoder.BLOCK_LENGTH,
                    AdmissionCommandDecoder.SCHEMA_VERSION,
                )
            val side = Side.valueOf(decoder.side().name)
            val orderType = OrderType.valueOf(decoder.orderType().name)
            val timeInForce = TimeInForce.valueOf(decoder.timeInForce().name)
            val price = Price(decoder.price())
            val quantity = Quantity(decoder.quantity())
            val scale = decoder.baseAssetScale().toInt()
            val product = FeeProductType.valueOf(decoder.productType().name)
            val tier = FeeTier.valueOf(decoder.feeTier().name)
            val version = decoder.scheduleVersion()
            val rates = MakerTakerFeeRates(FeeRate(decoder.makerPpm().toLong()), FeeRate(decoder.takerPpm().toLong()))
            val seconds = decoder.occurredAtEpochSecond()
            val nanos = decoder.occurredAtNano()
            AdmissionMessageFormat.verify(nanos in 0..999_999_999, "나노초 범위가 잘못됐습니다")
            val time = Instant.ofEpochSecond(seconds, nanos.toLong())
            val requestId = reader.text(decoder.limit(), decoder::requestIdLength, decoder::getRequestId)
            val userId = reader.text(decoder.limit(), decoder::userIdLength, decoder::getUserId)
            val marketId = reader.text(decoder.limit(), decoder::marketIdLength, decoder::getMarketId)
            val orderId = reader.text(decoder.limit(), decoder::orderIdLength, decoder::getOrderId)
            val definitionMarketId = reader.text(decoder.limit(), decoder::definitionMarketIdLength, decoder::getDefinitionMarketId)
            val baseId = reader.text(decoder.limit(), decoder::baseAssetIdLength, decoder::getBaseAssetId)
            val quoteId = reader.text(decoder.limit(), decoder::quoteAssetIdLength, decoder::getQuoteAssetId)
            val ledgerId = reader.text(decoder.limit(), decoder::ledgerTransactionIdLength, decoder::getLedgerTransactionId)
            reader.finish(decoder.limit())
            AdmissionCommandPayload(
                requestId,
                OrderAdmissionInput(MarketId(marketId), OrderId(orderId), UserId(userId), side, orderType, timeInForce, price, quantity),
                MarketDefinition(MarketId(definitionMarketId), AssetId(baseId), AssetId(quoteId), scale),
                TradingFeePolicySnapshot(product, tier, version, rates),
                ledgerId,
                time,
            )
        }
}

package com.exchange.core.api.trading.infrastructure.codec

import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommand
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
import java.time.Instant
import com.exchange.core.api.trading.infrastructure.codec.generated.FeeTier as WireFeeTier
import com.exchange.core.api.trading.infrastructure.codec.generated.OrderType as WireOrderType
import com.exchange.core.api.trading.infrastructure.codec.generated.ProductType as WireProductType
import com.exchange.core.api.trading.infrastructure.codec.generated.Side as WireSide
import com.exchange.core.api.trading.infrastructure.codec.generated.TimeInForce as WireTimeInForce

/** 기존 판단 입력을 변환한다. 접수 여부와 재전송 판단 순서는 접수 코어에 맡긴다. */
class AdmissionCommandCodec {
    fun encode(command: AdmissionCommandPayload): ByteArray {
        val message =
            AdmissionCommand
                .newBuilder()
                .setSide(WireSide.valueOf(command.order.side.name))
                .setOrderType(WireOrderType.valueOf(command.order.orderType.name))
                .setTimeInForce(WireTimeInForce.valueOf(command.order.timeInForce.name))
                .setPrice(command.order.price.value)
                .setQuantity(command.order.quantity.value)
                .setBaseAssetScale(command.market.baseAssetScale)
                .setProductType(WireProductType.valueOf(command.feePolicySnapshot.productType.name))
                .setFeeTier(WireFeeTier.valueOf(command.feePolicySnapshot.feeTier.name))
                .setScheduleVersion(command.feePolicySnapshot.scheduleVersion)
                .setMakerPpm(
                    command.feePolicySnapshot.feeRates.makerFeeRate.partsPerMillion
                        .toInt(),
                ).setTakerPpm(
                    command.feePolicySnapshot.feeRates.takerFeeRate.partsPerMillion
                        .toInt(),
                ).setOccurredAtEpochSecond(command.occurredAt.epochSecond)
                .setOccurredAtNano(command.occurredAt.nano)
                .setRequestId(AdmissionMessageFormat.text(command.requestId))
                .setUserId(AdmissionMessageFormat.text(command.order.userId.value))
                .setMarketId(AdmissionMessageFormat.text(command.order.marketId.value))
                .setOrderId(AdmissionMessageFormat.text(command.order.orderId.value))
                .setDefinitionMarketId(AdmissionMessageFormat.text(command.market.marketId.value))
                .setBaseAssetId(AdmissionMessageFormat.text(command.market.baseAssetId.value))
                .setQuoteAssetId(AdmissionMessageFormat.text(command.market.quoteAssetId.value))
                .setLedgerTransactionId(AdmissionMessageFormat.text(command.ledgerTransactionId))
                .setPriceTick(command.market.priceTick)
                .setQuantityStep(command.market.quantityStep)
                .build()
        return AdmissionMessageFormat.encode(AdmissionMessageFormat.COMMAND, message)
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): AdmissionCommandPayload =
        AdmissionMessageFormat.readValues {
            val reader = AdmissionMessageReader(bytes, offset, length)
            AdmissionMessageFormat.verify(reader.kind == AdmissionMessageFormat.COMMAND, "명령 메시지가 아닙니다")
            val message = reader.parse(AdmissionCommand.parser())
            // optional 필드의 기본값으로 누락된 입력을 채우면 원래 명령과 다른 판단을 할 수 있다.
            AdmissionMessageFormat.verify(
                message.hasSide() && message.hasOrderType() && message.hasTimeInForce() &&
                    message.hasPrice() && message.hasQuantity() && message.hasBaseAssetScale() &&
                    message.hasProductType() && message.hasFeeTier() && message.hasScheduleVersion() &&
                    message.hasMakerPpm() && message.hasTakerPpm() && message.hasOccurredAtEpochSecond() &&
                    message.hasOccurredAtNano() && message.hasRequestId() && message.hasUserId() &&
                    message.hasMarketId() && message.hasOrderId() && message.hasDefinitionMarketId() &&
                    message.hasBaseAssetId() && message.hasQuoteAssetId() && message.hasLedgerTransactionId(),
                "명령의 필수 필드가 누락됐습니다",
            )
            AdmissionMessageFormat.verify(
                message.hasPriceTick() == message.hasQuantityStep(),
                "가격·수량 단위는 함께 존재하거나 함께 생략해야 합니다",
            )
            AdmissionMessageFormat.verify(message.occurredAtNano in 0..999_999_999, "나노초 범위가 잘못됐습니다")
            AdmissionCommandPayload(
                AdmissionMessageFormat.text(message.requestId),
                OrderAdmissionInput(
                    MarketId(AdmissionMessageFormat.text(message.marketId)),
                    OrderId(AdmissionMessageFormat.text(message.orderId)),
                    UserId(AdmissionMessageFormat.text(message.userId)),
                    Side.valueOf(message.side.name),
                    OrderType.valueOf(message.orderType.name),
                    TimeInForce.valueOf(message.timeInForce.name),
                    Price(message.price),
                    Quantity(message.quantity),
                ),
                marketDefinition(message),
                TradingFeePolicySnapshot(
                    FeeProductType.valueOf(message.productType.name),
                    FeeTier.valueOf(message.feeTier.name),
                    message.scheduleVersion,
                    MakerTakerFeeRates(FeeRate(message.makerPpm.toLong()), FeeRate(message.takerPpm.toLong())),
                ),
                AdmissionMessageFormat.text(message.ledgerTransactionId),
                Instant.ofEpochSecond(message.occurredAtEpochSecond, message.occurredAtNano.toLong()),
            )
        }

    private fun marketDefinition(message: AdmissionCommand): MarketDefinition {
        // 이전 v2 명령은 생성자의 기본 단위로 복원한다. 새 단위도 같은 도메인 불변식으로 검사한다.
        val market =
            MarketDefinition(
                MarketId(AdmissionMessageFormat.text(message.definitionMarketId)),
                AssetId(AdmissionMessageFormat.text(message.baseAssetId)),
                AssetId(AdmissionMessageFormat.text(message.quoteAssetId)),
                message.baseAssetScale,
            )
        return if (message.hasPriceTick()) {
            market.copy(priceTick = message.priceTick, quantityStep = message.quantityStep)
        } else {
            market
        }
    }
}

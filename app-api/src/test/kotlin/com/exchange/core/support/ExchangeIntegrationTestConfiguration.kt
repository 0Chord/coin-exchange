package com.exchange.core.support

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.order.MarketDefinition
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 실제 application Bean을 조립하는 통합 테스트에 DB와 고정 마켓·수수료 정책을 제공한다.
 * 서비스나 저장소를 교체하지 않으며, 테스트에 import했을 때만 적용된다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ExchangeIntegrationTestConfiguration {
    /**
     * Spring이 시작·종료하는 테스트 전용 PostgreSQL이다.
     * ServiceConnection이 접속 정보를 제공하므로 개발용 DB 설정에 연결하지 않는다.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

    /** 금액 계산을 읽기 쉽게 검증하도록 BTC 수량 scale을 0으로 고정한다. */
    @Bean
    fun marketDefinition(): MarketDefinition =
        MarketDefinition(
            marketId = MarketId("BTC-KRW"),
            baseAssetId = AssetId("BTC"),
            quoteAssetId = AssetId("KRW"),
            baseAssetScale = 0,
        )

    /** 현물 NORMAL 등급의 maker 0.5%, taker 1% 정책을 실제 주문 예약에 저장한다. */
    @Bean
    fun tradingFeePolicySnapshot(): TradingFeePolicySnapshot =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates =
                MakerTakerFeeRates(
                    makerFeeRate = FeeRate(5_000),
                    takerFeeRate = FeeRate(10_000),
                ),
        )
}

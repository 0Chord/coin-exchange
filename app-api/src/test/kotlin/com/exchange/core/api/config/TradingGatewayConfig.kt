package com.exchange.core.api.config

import com.exchange.core.api.trading.application.SubmitTradingOrderUseCase
import com.exchange.core.api.trading.application.TradingOrderGateway
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.AeronOrderGateway
import com.exchange.core.api.trading.infrastructure.aeron.GatewayMetrics
import com.exchange.core.api.trading.infrastructure.aeron.GatewayRequestTimingObserver
import com.exchange.core.api.trading.infrastructure.aeron.readDevelopmentMarkets
import io.micrometer.core.instrument.MeterRegistry
import org.apache.coyote.http11.Http11NioProtocol
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** 이전 wire2 HTTP 검증에서 명시적으로 가져오는 구성이다. 제품 WAS에는 등록하지 않는다. */
@TestConfiguration(proxyBeanMethods = false)
@Import(TradingHttpConfig::class)
class TradingGatewayConfig {
    @Bean
    fun tradingHttpProtocolCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            // 명시적으로 선택한 다른 protocol과 테스트 관찰 protocol은 덮어쓰지 않는다.
            if (factory.protocol == Http11NioProtocol::class.java.name) {
                factory.setProtocol(TradingGatewayHttpProtocol::class.java.name)
            }
        }

    @Bean(destroyMethod = "close")
    internal fun aeronOrderGateway(
        meterRegistry: MeterRegistry,
        requestTimings: ObjectProvider<GatewayRequestTimingObserver>,
    ): AeronOrderGateway {
        val environment = System.getenv()
        val settings = AeronGatewaySettings.from(environment)
        val markets = readDevelopmentMarkets(environment)
        require(markets.configurationVersion != null && markets.contentSha256 != null) {
            "Aeron HTTP gateway에는 RDB 마켓 설정 버전과 내용 SHA-256이 필요합니다"
        }
        val gateway = AeronOrderGateway(settings, markets, GatewayMetrics(meterRegistry, requestTimings.getIfAvailable()))
        LoggerFactory.getLogger(TradingGatewayConfig::class.java).info(
            "Aeron HTTP gateway 준비: configVersion={} sha256={} queueCapacity={} maxInFlight={} resultTimeoutMs={}",
            markets.configurationVersion,
            markets.contentSha256,
            settings.queueCapacity,
            settings.maxInFlight,
            settings.resultTimeoutMs,
        )
        return gateway
    }

    @Bean
    fun submitTradingOrderUseCase(gateway: TradingOrderGateway): SubmitTradingOrderUseCase = SubmitTradingOrderUseCase(gateway)
}

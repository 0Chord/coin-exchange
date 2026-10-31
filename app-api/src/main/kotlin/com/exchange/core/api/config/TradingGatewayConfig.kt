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
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.type.LogicalType

/** HTTP 서버의 수명 동안 Aeron 연결 하나를 재사용한다. 마켓 RDB는 시작할 때만 읽는다. */
@Configuration(proxyBeanMethods = false)
@Profile("aeron-gateway")
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

    /** 결과 기한은 gateway가 관리한다. 별도 MVC timeout이 식별자 없는 500 응답을 만들지 않게 한다. */
    @Bean
    fun tradingAsyncSupport(): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
                configurer.setDefaultTimeout(-1)
            }
        }

    /** 소수 가격 절삭·숫자 enum·식별자 문자열 변환으로 주문 원본이 바뀌지 않게 한다. */
    @Bean
    fun tradingJsonMapperBuilderCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            builder.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            builder.withCoercionConfig(LogicalType.Textual) { coercion ->
                // String 대상은 ALLOW_COERCION_OF_SCALARS의 보호 범위에 포함되지 않는다.
                coercion.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                coercion.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }
        }
}

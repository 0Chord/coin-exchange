package com.exchange.core.api.config

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.cfg.EnumFeature
import tools.jackson.databind.type.LogicalType

/** 전송 연결과 무관한 주문 HTTP 입력 및 비동기 응답 설정이다. */
@Configuration(proxyBeanMethods = false)
class TradingHttpConfig {
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

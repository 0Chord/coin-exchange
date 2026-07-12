package com.exchange.core.api.config

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.api.matching.publish.NoOpMatchingEventPublisher
import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MarketCommandProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MatchingConfig {
    /**
     * 같은 market의 command를 한 줄로 처리하는 processor.
     *
     * 서버가 떠 있는 동안 유지되고, 종료 시 close된다.
     */
    @Bean(destroyMethod = "close")
    fun marketCommandProcessor(): MarketCommandProcessor = InMemoryMarketCommandProcessor()

    /**
     * 매칭 결과 event를 외부로 내보내는 자리.
     *
     * 지금은 NoOp이고, 이후 outbox/Kafka/Redis/WS 구현으로 교체한다.
     */
    @Bean
    fun matchingEventPublisher(): MatchingEventPublisher = NoOpMatchingEventPublisher()
}

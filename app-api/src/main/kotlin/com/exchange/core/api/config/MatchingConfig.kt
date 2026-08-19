package com.exchange.core.api.config

import com.exchange.core.api.matching.publish.MatchingEventPublisher
import com.exchange.core.api.matching.publish.NoOpMatchingEventPublisher
import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MarketCommandProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * DB 영속화 여부와 무관하게 필요한 matching 기본 구성.
 *
 * market별 single-writer processor를 항상 등록하고, persistence가 꺼져 있을 때만
 * event를 버리는 publisher를 기본값으로 제공한다.
 */
@Configuration
class MatchingConfig {
    /**
     * 같은 market의 command를 한 줄로 처리하는 processor.
     *
     * 서버가 떠 있는 동안 유지되고, 종료 시 close된다.
     *
     * @return JVM 메모리에서 market별 단일 worker를 관리하는 processor
     */
    @Bean(destroyMethod = "close")
    fun marketCommandProcessor(): MarketCommandProcessor = InMemoryMarketCommandProcessor()

    /**
     * 매칭 결과 event를 외부로 내보내는 자리.
     *
     * 지금은 NoOp이고, 이후 outbox/Kafka/Redis/WS 구현으로 교체한다.
     *
     * @return persistence가 꺼졌을 때 event를 의도적으로 저장하지 않는 publisher
     */
    @Bean
    @ConditionalOnProperty(
        name = ["exchange.matching.persistence.enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun matchingEventPublisher(): MatchingEventPublisher = NoOpMatchingEventPublisher()
}

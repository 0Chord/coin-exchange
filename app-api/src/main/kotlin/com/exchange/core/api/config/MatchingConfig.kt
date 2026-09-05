package com.exchange.core.api.config

import com.exchange.core.api.matching.MatchingApplicationService
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
 * 마켓별 single-writer processor와 application service를 명시적 Bean으로 등록한다.
 * persistence가 꺼져 있을 때만 event를 저장하지 않는 publisher를 기본값으로 제공한다.
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
     * 매칭 이벤트 영속화가 꺼져 있을 때 사용하는 NoOp publisher를 등록한다.
     *
     * 영속화가 켜지면 이 Bean 대신 [MatchingPersistenceConfig]의 PostgreSQL publisher를 쓴다.
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

    /**
     * 마켓별 command 처리와 매칭 이벤트 발행을 연결하는 서비스를 등록한다.
     *
     * @param processor 같은 마켓의 사전 작업·매칭·후속 작업을 직렬 실행하는 processor
     * @param publisher 설정에 따라 선택된 NoOp 또는 영속화 publisher
     * @return HTTP 계층과 matching core 사이의 application service
     */
    @Bean
    fun matchingApplicationService(
        processor: MarketCommandProcessor,
        publisher: MatchingEventPublisher,
    ): MatchingApplicationService =
        MatchingApplicationService(
            processor = processor,
            publisher = publisher,
        )
}

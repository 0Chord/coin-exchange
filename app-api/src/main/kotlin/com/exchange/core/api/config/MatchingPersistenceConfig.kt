package com.exchange.core.api.config

import com.exchange.core.api.matching.persistence.JpaMatchingEventStore
import com.exchange.core.api.matching.persistence.MatchingEventRepository
import com.exchange.core.api.matching.persistence.MatchingEventStore
import com.exchange.core.api.matching.persistence.PersistentMatchingEventPublisher
import com.exchange.core.api.matching.publish.MatchingEventPublisher
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper

@Configuration
@ConditionalOnProperty(
    name = ["exchange.matching.persistence.enabled"],
    havingValue = "true",
)
class MatchingPersistenceConfig {
    /**
     * matching event를 JPA로 저장하는 구현체.
     *
     * persistence.enabled=true일 때만 DB 연결과 함께 사용한다.
     */
    @Bean
    fun matchingEventStore(
        repository: MatchingEventRepository,
        objectMapper: ObjectMapper,
    ): MatchingEventStore =
        JpaMatchingEventStore(
            repository = repository,
            objectMapper = objectMapper,
        )

    /**
     * matching event를 store에 append하는 publisher.
     */
    @Bean
    fun persistentMatchingEventPublisher(
        store: MatchingEventStore,
    ): MatchingEventPublisher = PersistentMatchingEventPublisher(store)
}

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

/**
 * matching event의 JPA 영속화를 조립하는 Spring 구성.
 *
 * `exchange.matching.persistence.enabled=true`일 때만 활성화되며, 기본 NoOp publisher 대신
 * DB에 event를 append하는 publisher를 제공한다.
 */
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
     *
     * @param repository matching_events JPA repository
     * @param objectMapper 원본 event payload를 JSON으로 직렬화하는 mapper
     * @return event 목록을 entity로 변환해 저장하는 store
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
     *
     * @param store event 영속성 포트
     * @return processor의 eventHandler에서 호출할 publisher
     */
    @Bean
    fun persistentMatchingEventPublisher(
        store: MatchingEventStore,
    ): MatchingEventPublisher = PersistentMatchingEventPublisher(store)
}

package com.exchange.core

import com.exchange.core.support.PostgresTestConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** 개발용 테스트 실행에 Kafka와 테스트 공통 PostgreSQL 컨테이너를 제공한다. */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresTestConfiguration::class)
class TestcontainersConfiguration {
    /** 개발용 애플리케이션을 실행하는 Spring context가 Kafka의 생명주기도 관리한다. */
    @Bean
    @ServiceConnection
    fun kafkaContainer(): KafkaContainer =
        KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
}

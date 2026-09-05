package com.exchange.core.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 전체 애플리케이션 테스트와 JPA 테스트가 함께 사용하는 PostgreSQL 연결 설정.
 *
 * 마켓·수수료 정책이나 서비스 Bean은 포함하지 않는다. 설정만 재사용하며, 컨테이너는
 * 각 Spring context에 속한다. 같은 context에서는 DB를 공유하고 context를 닫으면 종료한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    /**
     * Spring이 시작·종료하는 테스트 전용 DB다.
     * ServiceConnection이 접속 정보를 제공하므로 URL·사용자·비밀번호를 따로 등록하지 않는다.
     */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
}

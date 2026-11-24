package com.exchange.core.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/** 조회 테스트만 DB를 준비하며 제품에는 fixture 입력 경로를 추가하지 않는다. */
internal class OrderQueryTestDatabase(
    socketTimeoutSeconds: Int? = null,
) : AutoCloseable {
    private val database =
        PostgreSQLContainer("postgres:16-alpine")
            .withStartupTimeout(Duration.ofSeconds(60))
            .apply {
                if (socketTimeoutSeconds != null) {
                    withUrlParam("sslmode", "disable")
                    withUrlParam("socketTimeout", socketTimeoutSeconds.toString())
                }
            }

    init {
        try {
            database.start()
        } catch (error: Throwable) {
            database.stop()
            throw error
        }
    }

    val properties: Map<String, String>
        get() =
            mapOf(
                "spring.datasource.url" to database.jdbcUrl,
                "spring.datasource.username" to database.username,
                "spring.datasource.password" to database.password,
                "exchange.order-query.funds-lineage-high" to "41",
                "exchange.order-query.funds-lineage-low" to "42",
                "exchange.order-query.supported-markets" to "BTC-USDT,ETH-USDT",
            )

    fun jdbc(): JdbcTemplate = JdbcTemplate(DriverManagerDataSource(database.jdbcUrl, database.username, database.password))

    /** 일시 정지한 컨테이너를 같은 연결 설정과 데이터로 재개한다. */
    fun whilePaused(work: () -> Unit) {
        val id = database.containerId
        database.dockerClient.pauseContainerCmd(id).exec()
        try {
            check(
                database.dockerClient
                    .inspectContainerCmd(id)
                    .exec()
                    .state.paused == true,
            )
            work()
        } finally {
            database.dockerClient.unpauseContainerCmd(id).exec()
        }
    }

    override fun close() = database.stop()
}

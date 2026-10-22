package com.exchange.core.api.trading.infrastructure.aeron

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/** 제품 main은 마이그레이션하지 않는다. 테스트만 실제 DB를 준비하고 전체 문서를 한 INSERT로 등록한다. */
internal class MarketConfigurationDatabase(
    password: String = "test",
) : AutoCloseable {
    private val database =
        PostgreSQLContainer("postgres:17-alpine")
            .withPassword(password)
            .withStartupTimeout(Duration.ofSeconds(60))
    private val versions = AtomicLong()
    val containerId: String get() = database.containerId
    val containerJdbcUrl: String get() = "jdbc:postgresql://market-db:5432/${database.databaseName}"

    // 테스트 준비도 무기한 DB 응답·잠금 대기로 정리 구간에 도달하지 못하면 안 된다.
    private val boundedJdbcUrl: String get() =
        database.jdbcUrl + (if ('?' in database.jdbcUrl) "&" else "?") +
            "connectTimeout=5&socketTimeout=5&loginTimeout=5&cancelSignalTimeout=3&" +
            "options=-c%20statement_timeout%3D3000"

    init {
        try {
            database.start()
            Flyway
                .configure()
                .dataSource(boundedJdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        } catch (error: Throwable) {
            database.stop()
            throw error
        }
    }

    fun connection(): Connection = DriverManager.getConnection(boundedJdbcUrl, database.username, database.password)

    fun register(document: String): Map<String, String> {
        val version = versions.incrementAndGet()
        connection().use { connection ->
            connection.autoCommit = false
            val sql = "insert into market_configuration_release(config_version, markets_document) values (?, ?)"
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 3
                statement.setLong(1, version)
                statement.setString(2, document)
                statement.executeUpdate()
            }
            connection.commit()
        }
        return environment(version, document)
    }

    fun environment(
        version: Long,
        document: String,
    ): Map<String, String> =
        mapOf(
            "FUNDING_MARKET_DB_URL" to database.jdbcUrl,
            "FUNDING_MARKET_DB_USER" to database.username,
            "FUNDING_MARKET_DB_PASSWORD" to database.password,
            "FUNDING_MARKET_CONFIG_VERSION" to version.toString(),
            "FUNDING_MARKET_CONFIG_SHA256" to
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.toByteArray(Charsets.UTF_8))),
        )

    override fun close() = database.stop()
}

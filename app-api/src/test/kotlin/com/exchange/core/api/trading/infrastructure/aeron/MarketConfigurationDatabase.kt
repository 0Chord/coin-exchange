package com.exchange.core.api.trading.infrastructure.aeron

import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong

/** 제품 main은 마이그레이션하지 않는다. 테스트만 실제 DB를 준비하고 전체 문서를 한 INSERT로 등록한다. */
internal class MarketConfigurationDatabase : AutoCloseable {
    private val database = PostgreSQLContainer("postgres:17-alpine")
    private val versions = AtomicLong()

    init {
        database.start()
        try {
            Flyway
                .configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        } catch (error: Throwable) {
            database.stop()
            throw error
        }
    }

    fun connection(): Connection = DriverManager.getConnection(database.jdbcUrl, database.username, database.password)

    fun register(document: String): Map<String, String> {
        val version = versions.incrementAndGet()
        connection().use { connection ->
            connection.autoCommit = false
            val sql = "insert into market_configuration_release(config_version, markets_document) values (?, ?)"
            connection.prepareStatement(sql).use { statement ->
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

package com.exchange.core.api.market.infrastructure.persistence

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.market.application.MarketConfigurationRelease
import com.exchange.core.api.market.application.MarketConfigurationSummary
import com.exchange.core.api.market.application.MarketConfigurationUseCase
import com.exchange.core.api.market.application.port.MarketConfigurationStore
import com.exchange.core.api.trading.infrastructure.aeron.parseDevelopmentMarketsDocument
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.HexFormat

class PostgresMarketConfigurationStore(
    private val settings: MarketConfigurationDatabaseSettings,
) : MarketConfigurationStore {
    override fun register(
        version: Long,
        document: ByteArray,
    ): MarketConfigurationRegistration {
        requireVersion(version)
        val bytes = document.copyOf()
        val raw = validateDocument(bytes, MarketConfigurationError.INVALID_INPUT, "document", version)
        val registration = MarketConfigurationRegistration(version, sha256(bytes))
        var connection: Connection? = null
        var writeAttempted = false
        var commitAttempted = false
        var stage = "connection"
        try {
            connection = settings.connect()
            stage = "transaction"
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.isReadOnly = false
            connection.autoCommit = false
            stage = "insert"
            val insert =
                "insert into market_configuration_release(config_version, markets_document) values (?, ?) " +
                    "on conflict (config_version) do nothing"
            connection.prepareStatement(insert).use { statement ->
                statement.queryTimeout = 3
                statement.setLong(1, version)
                statement.setString(2, raw)
                writeAttempted = true
                statement.executeUpdate()
            }
            // 별도 문장이어야 앞서 PK를 점유한 트랜잭션의 커밋을 새 snapshot에서 볼 수 있다.
            stage = "compare"
            val select = "select markets_document from market_configuration_release where config_version = ?"
            connection.prepareStatement(select).use { statement ->
                statement.queryTimeout = 3
                statement.setLong(1, version)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        throw MarketConfigurationException(MarketConfigurationError.REGISTRATION_FAILED, stage, version)
                    }
                    if (!rows.getString(1).toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                        throw MarketConfigurationException(MarketConfigurationError.CONTENT_CONFLICT, stage, version)
                    }
                }
            }
            stage = "commit"
            commitAttempted = true
            connection.commit()
            return registration
        } catch (error: Exception) {
            val rollbackConfirmed = rollback(connection)
            val uncertain = writeAttempted && (commitAttempted || connectionLost(error) || !rollbackConfirmed)
            val code =
                when {
                    uncertain -> MarketConfigurationError.RESULT_UNCONFIRMED
                    error is MarketConfigurationException && error.code == MarketConfigurationError.CONTENT_CONFLICT -> error.code
                    else -> MarketConfigurationError.REGISTRATION_FAILED
                }
            throw MarketConfigurationException(code, stage, version)
        } finally {
            // 커밋을 확인했다면 정리 오류로 이미 확정된 성공을 뒤집지 않는다.
            try {
                connection?.close()
            } catch (_: Exception) {
                // URL·계정·원문이 들어갈 수 있는 JDBC 정리 예외도 외부에 전달하지 않는다.
            }
        }
    }

    override fun find(version: Long): MarketConfigurationRelease? {
        requireVersion(version)
        return lookup(version) { connection ->
            val sql = "select config_version, markets_document, created_at from market_configuration_release where config_version = ?"
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 3
                statement.setLong(1, version)
                statement.executeQuery().use { rows -> if (rows.next()) release(rows) else null }
            }
        }
    }

    override fun list(
        beforeVersion: Long?,
        limit: Int,
    ): List<MarketConfigurationSummary> {
        beforeVersion?.let(::requireVersion)
        if (limit !in 1..100) throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "limit")
        return lookup(null) { connection ->
            val sql =
                "select config_version, markets_document, created_at from market_configuration_release " +
                    (if (beforeVersion != null) "where config_version < ? " else "") + "order by config_version desc limit ?"
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 3
                if (beforeVersion != null) statement.setLong(1, beforeVersion)
                statement.setInt(if (beforeVersion != null) 2 else 1, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val release = release(rows)
                            add(MarketConfigurationSummary(release.configVersion, release.sha256, release.createdAt))
                        }
                    }
                }
            }
        }
    }

    private fun <T> lookup(
        version: Long?,
        query: (Connection) -> T,
    ): T =
        try {
            settings.connect().use { connection -> query(connection) }
        } catch (error: MarketConfigurationException) {
            // use의 정리 중 추가된 suppressed JDBC 예외도 외부 경계에 남기지 않는다.
            throw MarketConfigurationException(error.code, error.stage, error.configVersion)
        } catch (_: Exception) {
            throw MarketConfigurationException(MarketConfigurationError.LOOKUP_FAILED, "lookup", version)
        }

    private fun release(rows: ResultSet): MarketConfigurationRelease {
        val version = rows.getLong(1)
        val bytes = rows.getString(2).toByteArray(Charsets.UTF_8)
        val raw = validateDocument(bytes, MarketConfigurationError.LOOKUP_FAILED, "stored-document", version)
        return MarketConfigurationRelease(version, sha256(bytes), raw, rows.getTimestamp(3).toInstant())
    }

    private fun validateDocument(
        bytes: ByteArray,
        errorCode: MarketConfigurationError,
        stage: String,
        version: Long,
    ): String =
        try {
            require(bytes.isNotEmpty() && bytes.size <= MarketConfigurationUseCase.MAX_DOCUMENT_BYTES)
            val raw =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            require(!raw.startsWith('\uFEFF') && '\u0000' !in raw)
            parseDevelopmentMarketsDocument(raw)
            raw
        } catch (_: Exception) {
            throw MarketConfigurationException(errorCode, stage, version)
        }

    private fun requireVersion(version: Long) {
        if (version <= 0) throw MarketConfigurationException(MarketConfigurationError.INVALID_INPUT, "version")
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun rollback(connection: Connection?): Boolean =
        try {
            if (connection == null) {
                false
            } else {
                connection.rollback()
                true
            }
        } catch (_: Exception) {
            false
        }

    private fun connectionLost(error: Exception): Boolean =
        error is SQLException && (error.sqlState?.startsWith("08") == true || error.sqlState == "57P01")
}

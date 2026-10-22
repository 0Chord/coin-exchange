package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.testcontainers.DockerClientFactory
import java.sql.SQLException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MarketConfigurationDatabaseTest {
    @Test
    fun `설정 등록이 DB 잠금에 막히면 제한시간에 실패하고 미완료 행과 컨테이너를 남기지 않는다`() {
        val database = MarketConfigurationDatabase()
        val containerId = database.containerId
        database.use {
            database.connection().use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("lock table market_configuration_release in access exclusive mode") }
                try {
                    assertTimeoutPreemptively(Duration.ofSeconds(12)) {
                        val failure = assertFailsWith<SQLException> { database.register(MULTI_MARKET_DOCUMENT) }
                        assertEquals("57014", failure.sqlState) // PostgreSQL이 실제로 실행 중인 문장을 취소했다.
                    }
                } finally {
                    blocker.rollback()
                }
            }
            database.connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from market_configuration_release").use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
            val settings = database.register(MULTI_MARKET_DOCUMENT)
            assertEquals(2L, settings.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong())
        }
        assertFalse(
            DockerClientFactory
                .instance()
                .client()
                .listContainersCmd()
                .withShowAll(true)
                .exec()
                .any { it.id == containerId },
            "실패를 확인한 fixture의 DB 컨테이너가 정리되어야 합니다",
        )
    }
}

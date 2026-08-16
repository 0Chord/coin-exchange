package com.exchange.core.api.ledger.persistence

import com.exchange.core.api.config.LedgerPersistenceConfig
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.BalanceNotFoundException
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.InsufficientBalanceException
import com.exchange.core.ledger.InsufficientHoldException
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "exchange.ledger.persistence.enabled=true",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LedgerPersistenceConfig::class)
@Testcontainers
class PostgresBalanceStoreTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var store: BalanceStore

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("delete from balance_projection")
        jdbcTemplate.update(
            """
            insert into balance_projection (
                user_id,
                asset_id,
                available,
                hold
            ) values (?, ?, ?, ?)
            """.trimIndent(),
            USER_ID.value,
            ASSET_ID.value,
            1_000L,
            0L,
        )
    }

    @Test
    fun `reserve는 available을 줄이고 hold를 늘린다`() {
        val reserved =
            store.reserve(
                userId = USER_ID,
                assetId = ASSET_ID,
                amount = Amount(400),
            )

        assertEquals(Amount(600), reserved.available)
        assertEquals(Amount(400), reserved.hold)
        assertPersistedBalance(
            available = 600,
            hold = 400,
        )
    }

    @Test
    fun `available보다 큰 금액은 reserve할 수 없다`() {
        val error =
            assertFailsWith<InsufficientBalanceException> {
                store.reserve(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(1_100),
                )
            }

        assertEquals(USER_ID, error.userId)
        assertEquals(ASSET_ID, error.assetId)
        assertEquals(Amount(1_000), error.available)
        assertEquals(Amount(1_100), error.requested)
        assertPersistedBalance(
            available = 1_000,
            hold = 0,
        )
    }

    @Test
    fun `존재하지 않는 balance는 reserve할 수 없다`() {
        val missingUserId = UserId("missing-user")

        val error =
            assertFailsWith<BalanceNotFoundException> {
                store.reserve(
                    userId = missingUserId,
                    assetId = ASSET_ID,
                    amount = Amount(100),
                )
            }

        assertEquals(missingUserId, error.userId)
        assertEquals(ASSET_ID, error.assetId)
    }

    @Test
    fun `release는 hold를 줄이고 available을 늘린다`() {
        setBalance(
            available = 600,
            hold = 400,
        )

        val released =
            store.release(
                userId = USER_ID,
                assetId = ASSET_ID,
                amount = Amount(150),
            )

        assertEquals(Amount(750), released.available)
        assertEquals(Amount(250), released.hold)
        assertPersistedBalance(
            available = 750,
            hold = 250,
        )
    }

    @Test
    fun `hold보다 큰 금액은 release할 수 없다`() {
        setBalance(
            available = 900,
            hold = 100,
        )

        val error =
            assertFailsWith<InsufficientHoldException> {
                store.release(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(200),
                )
            }

        assertEquals(USER_ID, error.userId)
        assertEquals(ASSET_ID, error.assetId)
        assertEquals(Amount(100), error.hold)
        assertEquals(Amount(200), error.requested)
        assertPersistedBalance(
            available = 900,
            hold = 100,
        )
    }

    @Test
    fun `존재하지 않는 balance는 release할 수 없다`() {
        val missingUserId = UserId("missing-user")

        val error =
            assertFailsWith<BalanceNotFoundException> {
                store.release(
                    userId = missingUserId,
                    assetId = ASSET_ID,
                    amount = Amount(100),
                )
            }

        assertEquals(missingUserId, error.userId)
        assertEquals(ASSET_ID, error.assetId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 available보다 많은 금액을 reserve하면 하나만 성공한다`() {
        val results =
            runConcurrently {
                store.reserve(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(700),
                )
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(
            1,
            results.count { it.exceptionOrNull() is InsufficientBalanceException },
        )
        assertPersistedBalance(
            available = 300,
            hold = 700,
        )
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 hold보다 많은 금액을 release하면 하나만 성공한다`() {
        setBalance(
            available = 900,
            hold = 100,
        )

        val results =
            runConcurrently {
                store.release(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(70),
                )
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(
            1,
            results.count { it.exceptionOrNull() is InsufficientHoldException },
        )
        assertPersistedBalance(
            available = 970,
            hold = 30,
        )
    }

    @Test
    fun `consumeHold는 hold만 줄인다`() {
        setBalance(
            available = 600,
            hold = 400,
        )

        val consumed =
            store.consumeHold(
                userId = USER_ID,
                assetId = ASSET_ID,
                amount = Amount(150),
            )

        assertEquals(Amount(600), consumed.available)
        assertEquals(Amount(250), consumed.hold)

        assertPersistedBalance(
            available = 600,
            hold = 250,
        )
    }

    @Test
    fun `hold보다 큰 금액은 consumeHold할 수 없다`() {
        setBalance(
            available = 600,
            hold = 100,
        )

        val error =
            assertFailsWith<InsufficientHoldException> {
                store.consumeHold(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(200),
                )
            }

        assertEquals(USER_ID, error.userId)
        assertEquals(ASSET_ID, error.assetId)
        assertEquals(Amount(100), error.hold)
        assertEquals(Amount(200), error.requested)

        assertPersistedBalance(
            available = 600,
            hold = 100,
        )
    }

    @Test
    fun `존재하지 않는 balance는 consumeHold할 수 없다`() {
        val missingUserId = UserId("missing-user")

        val error =
            assertFailsWith<BalanceNotFoundException> {
                store.consumeHold(
                    userId = missingUserId,
                    assetId = ASSET_ID,
                    amount = Amount(100),
                )
            }

        assertEquals(missingUserId, error.userId)
        assertEquals(ASSET_ID, error.assetId)
    }

    @Test
    fun `credit은 available만 늘린다`() {
        setBalance(
            available = 10,
            hold = 5,
        )

        val credited =
            store.credit(
                userId = USER_ID,
                assetId = ASSET_ID,
                amount = Amount(3),
            )

        assertEquals(Amount(13), credited.available)
        assertEquals(Amount(5), credited.hold)

        assertPersistedBalance(
            available = 13,
            hold = 5,
        )
    }

    @Test
    fun `존재하지 않는 balance는 credit할 수 없다`() {
        val missingUserId = UserId("missing-user")

        val error =
            assertFailsWith<BalanceNotFoundException> {
                store.credit(
                    userId = missingUserId,
                    assetId = ASSET_ID,
                    amount = Amount(100),
                )
            }

        assertEquals(missingUserId, error.userId)
        assertEquals(ASSET_ID, error.assetId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 hold보다 많은 금액을 consumeHold하면 하나만 성공한다`() {
        setBalance(
            available = 900,
            hold = 100,
        )

        val results =
            runConcurrently {
                store.consumeHold(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(70),
                )
            }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(
            1,
            results.count {
                it.exceptionOrNull() is InsufficientHoldException
            },
        )

        assertPersistedBalance(
            available = 900,
            hold = 30,
        )
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 credit해도 available 증가분을 잃지 않는다`() {
        setBalance(
            available = 100,
            hold = 0,
        )

        val results =
            runConcurrently {
                store.credit(
                    userId = USER_ID,
                    assetId = ASSET_ID,
                    amount = Amount(100),
                )
            }

        assertEquals(2, results.count { it.isSuccess })

        assertPersistedBalance(
            available = 300,
            hold = 0,
        )
    }

    private fun setBalance(
        available: Long,
        hold: Long,
    ) {
        jdbcTemplate.update(
            """
            update balance_projection
            set available = ?,
                hold = ?
            where user_id = ?
              and asset_id = ?
            """.trimIndent(),
            available,
            hold,
            USER_ID.value,
            ASSET_ID.value,
        )
    }

    private fun assertPersistedBalance(
        available: Long,
        hold: Long,
    ) {
        val saved =
            jdbcTemplate.queryForMap(
                """
                select available, hold
                from balance_projection
                where user_id = ?
                  and asset_id = ?
                """.trimIndent(),
                USER_ID.value,
                ASSET_ID.value,
            )

        assertEquals(available, (saved["available"] as Number).toLong())
        assertEquals(hold, (saved["hold"] as Number).toLong())
    }

    private fun runConcurrently(operation: () -> Balance): List<Result<Balance>> {
        val ready = CountDownLatch(CONCURRENT_TASK_COUNT)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_TASK_COUNT)

        return try {
            val futures =
                List(CONCURRENT_TASK_COUNT) {
                    executor.submit<Result<Balance>> {
                        ready.countDown()
                        start.await()
                        runCatching(operation)
                    }
                }

            assertTrue(
                ready.await(5, TimeUnit.SECONDS),
                "두 작업이 시작 준비를 마치지 못했다",
            )
            start.countDown()

            futures.map { future ->
                future.get(5, TimeUnit.SECONDS)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    companion object {
        private const val CONCURRENT_TASK_COUNT = 2
        private val USER_ID = UserId("user-1")
        private val ASSET_ID = AssetId("KRW")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine"),
            )

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

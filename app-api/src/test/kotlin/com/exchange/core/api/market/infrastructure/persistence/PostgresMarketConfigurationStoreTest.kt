package com.exchange.core.api.market.infrastructure.persistence

import com.exchange.core.api.market.application.MarketConfigurationError
import com.exchange.core.api.market.application.MarketConfigurationException
import com.exchange.core.api.market.application.MarketConfigurationRegistration
import com.exchange.core.api.trading.infrastructure.aeron.MarketConfigurationDatabase
import com.exchange.core.api.trading.infrastructure.aeron.readDevelopmentMarkets
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMarketConfigurationStoreTest {
    private lateinit var database: MarketConfigurationDatabase
    private val versions = AtomicLong(100)

    @BeforeAll
    fun startDatabase() {
        database = MarketConfigurationDatabase()
    }

    @AfterAll
    fun stopDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun `기본 단위를 명시한 재등록도 예전 원문과 지문을 다시 쓰지 않는다`() {
        val version = versions.incrementAndGet()
        val store = store()
        val first = store.register(version, DOCUMENT.toByteArray())
        val original = store.find(version)!!
        val explicitDefaults = DOCUMENT.replace("\"feePolicy\":", "\"priceTick\":1,\"quantityStep\":100000000,\"feePolicy\":")

        assertEquals(first, store.register(version, explicitDefaults.toByteArray()))
        assertEquals(original, store.find(version))
        assertEquals(DOCUMENT, store.find(version)!!.document)
        assertEquals(DOCUMENT_HASH, store.find(version)!!.sha256)
        assertEquals(1, rowCount(version))
    }

    @Test
    fun `가격 수량 단위는 저장 조회와 노드 읽기에서 보존하고 다른 단위는 같은 버전에서 충돌한다`() {
        val version = versions.incrementAndGet()
        val store = store()
        val document = DOCUMENT.replace("\"feePolicy\":", "\"priceTick\":25,\"quantityStep\":4000000,\"feePolicy\":")

        val registration = store.register(version, document.toByteArray())
        val release = store.find(version)!!
        val market = readDevelopmentMarkets(database.environment(version, document)).single().market

        assertEquals(document, release.document)
        assertEquals(sha256(document), registration.sha256)
        assertEquals(registration.sha256, release.sha256)
        assertEquals(25L, market.priceTick)
        assertEquals(4_000_000L, market.quantityStep)
        val changed = document.replace("\"quantityStep\":4000000", "\"quantityStep\":8000000")
        val error = assertFailsWith<MarketConfigurationException> { store.register(version, changed.toByteArray()) }
        assertEquals(MarketConfigurationError.CONTENT_CONFLICT, error.code)
        assertEquals(release, store.find(version))
        assertEquals(1, rowCount(version))
    }

    @Test
    fun `가격 수량 단위의 누락 null 잘못된 형식과 정산 불가능한 조합은 DB에 등록하지 않는다`() {
        val store = store()
        val invalidUnits =
            listOf(
                "\"priceTick\":25,",
                "\"quantityStep\":4000000,",
                "\"priceTick\":null,\"quantityStep\":null,",
                "\"priceTick\":25,\"quantityStep\":null,",
                "\"priceTick\":0,\"quantityStep\":4000000,",
                "\"priceTick\":25,\"quantityStep\":-1,",
                "\"priceTick\":\"25\",\"quantityStep\":4000000,",
                "\"priceTick\":25,\"quantityStep\":4000000.0,",
                "\"priceTick\":9223372036854775808,\"quantityStep\":4000000,",
                "\"priceTick\":2,\"quantityStep\":30000000,",
            )
        invalidUnits.forEach { units ->
            val version = versions.incrementAndGet()
            val document = DOCUMENT.replace("\"feePolicy\":", "$units\"feePolicy\":")

            val error = assertFailsWith<MarketConfigurationException> { store.register(version, document.toByteArray()) }

            assertEquals(MarketConfigurationError.INVALID_INPUT, error.code)
            assertEquals("document", error.stage)
            assertEquals(0, rowCount(version))
        }
    }

    @Test
    fun `원문을 등록하고 같은 입력을 재실행하면 한 행과 등록 시각을 보존한다`() {
        val store = PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(database.environment(7, DOCUMENT)))

        val first = store.register(7, DOCUMENT.toByteArray())
        val created = store.find(7)!!
        val repeated = store.register(7, DOCUMENT.toByteArray())

        assertEquals(7L, first.configVersion)
        assertEquals(DOCUMENT_HASH, first.sha256)
        assertEquals(first, repeated)
        assertEquals(DOCUMENT, created.document)
        assertEquals(created, store.find(7))
        val loaded = readDevelopmentMarkets(database.environment(7, DOCUMENT) + ("FUNDING_MARKET_CONFIG_SHA256" to first.sha256))
        assertEquals(first.configVersion, loaded.configurationVersion)
        assertEquals(first.sha256, loaded.contentSha256)
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select markets_document from market_configuration_release where config_version = 7").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(DOCUMENT, rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `공백과 객체 필드 순서와 마켓 배열 순서가 달라도 최초 원문과 등록 결과를 유지한다`() {
        val version = versions.incrementAndGet()
        val store = store()
        val first = store.register(version, MULTI_MARKET_DOCUMENT.toByteArray())
        val original = store.find(version)!!

        listOf(
            " \n$MULTI_MARKET_DOCUMENT\n",
            REORDERED_FIELDS_DOCUMENT,
            REORDERED_MARKETS_DOCUMENT,
        ).forEach { equivalent ->
            assertEquals(first, store.register(version, equivalent.toByteArray()))
            assertEquals(original, store.find(version))
        }

        assertEquals(MULTI_MARKET_DOCUMENT, original.document)
        assertEquals(sha256(MULTI_MARKET_DOCUMENT), first.sha256)
        assertEquals(1, rowCount(version))
        val loaded =
            readDevelopmentMarkets(
                database.environment(version, MULTI_MARKET_DOCUMENT) + ("FUNDING_MARKET_CONFIG_SHA256" to first.sha256),
            )
        assertEquals(listOf("BTC-USDT", "SUI-USDT"), loaded.all().map { it.market.marketId.value })
        assertEquals(first.sha256, loaded.contentSha256)
    }

    @Test
    fun `같은 버전의 실제 수수료 변경은 충돌하고 새 버전에는 등록된다`() {
        val version = versions.incrementAndGet()
        val nextVersion = versions.incrementAndGet()
        val store = store()
        store.register(version, DOCUMENT.toByteArray())
        val original = store.find(version)

        val error = assertFailsWith<MarketConfigurationException> { store.register(version, CHANGED_DOCUMENT.toByteArray()) }
        assertEquals(MarketConfigurationError.CONTENT_CONFLICT, error.code)
        assertEquals(original, store.find(version))

        val changed = store.register(nextVersion, CHANGED_DOCUMENT.toByteArray())
        assertEquals(CHANGED_HASH, changed.sha256)
        assertEquals(CHANGED_DOCUMENT, store.find(nextVersion)!!.document)
        assertEquals(original, store.find(version))
    }

    @Test
    fun `동등한 설정의 다른 표현을 동시에 등록하면 두 호출이 최초 저장 한 행의 지문을 확인한다`() {
        val version = versions.incrementAndGet()

        val documents = listOf(MULTI_MARKET_DOCUMENT, " \n$REORDERED_MARKETS_DOCUMENT\n")
        val results = concurrentRegistrations(version, documents)

        assertTrue(results.all { it.isSuccess })
        val stored = store().find(version)!!
        assertTrue(stored.document in documents)
        assertEquals(sha256(stored.document), stored.sha256)
        assertEquals(listOf(stored.sha256, stored.sha256), results.map { it.getOrThrow().sha256 })
        assertTrue(results.all { it.getOrThrow().configVersion == version })
        assertEquals(1, rowCount(version))
        val repeated = store().register(version, REORDERED_FIELDS_DOCUMENT.toByteArray())
        assertEquals(stored.sha256, repeated.sha256)
        assertEquals(stored, store().find(version))
    }

    @Test
    fun `다른 원문을 동시에 등록하면 승자의 원문 한 행과 한 충돌을 확인한다`() {
        val version = versions.incrementAndGet()

        val results = concurrentRegistrations(version, listOf(DOCUMENT, CHANGED_DOCUMENT))

        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull() as MarketConfigurationException
        assertEquals(MarketConfigurationError.CONTENT_CONFLICT, failure.code)
        val stored = store().find(version)!!
        val expectedHash = if (stored.document == DOCUMENT) DOCUMENT_HASH else CHANGED_HASH
        assertTrue(stored.document in listOf(DOCUMENT, CHANGED_DOCUMENT))
        assertEquals(expectedHash, results.single { it.isSuccess }.getOrThrow().sha256)
        assertEquals(expectedHash, stored.sha256)
        assertEquals(1, rowCount(version))
    }

    @Test
    fun `PK에서 실제 대기한 뒤 앞 등록이 커밋하면 새 snapshot으로 같은 원문을 확인한다`() {
        blockedRegistration(commitBlocker = true, requested = DOCUMENT)
    }

    @Test
    fun `PK에서 실제 대기한 뒤 앞 등록이 커밋하면 동등한 설정은 최초 원문을 유지한다`() {
        blockedRegistration(commitBlocker = true, requested = " \n$REORDERED_BTC_DOCUMENT\n", equivalentToOriginal = true)
    }

    @Test
    fun `PK에서 실제 대기한 뒤 앞 등록이 커밋하면 다른 원문 충돌을 반환한다`() {
        blockedRegistration(commitBlocker = true, requested = CHANGED_DOCUMENT)
    }

    @Test
    fun `PK에서 실제 대기한 뒤 앞 등록이 롤백하면 뒤의 원문을 등록한다`() {
        blockedRegistration(commitBlocker = false, requested = CHANGED_DOCUMENT)
    }

    @Test
    fun `URL 무제한 옵션이 있어도 잠금 대기는 제한되고 확인된 롤백 뒤 정상 등록할 수 있다`() {
        val version = versions.incrementAndGet()
        val environment = database.environment(version, DOCUMENT)
        val url = environment.getValue("FUNDING_MARKET_DB_URL")
        val unlimited =
            url + (if ('?' in url) "&" else "?") +
                "connectTimeout=0&loginTimeout=0&socketTimeout=0&cancelSignalTimeout=0&" +
                "options=-c%20statement_timeout%3D0&readOnlyMode=always"
        val store = store(environment + ("FUNDING_MARKET_DB_URL" to unlimited))
        database.connection().use { blocker ->
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("lock table market_configuration_release in access exclusive mode") }
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(12)) {
                    val error = assertFailsWith<MarketConfigurationException> { store.register(version, DOCUMENT.toByteArray()) }
                    assertEquals(MarketConfigurationError.REGISTRATION_FAILED, error.code)
                    assertEquals("insert", error.stage)
                    assertNull(error.cause)
                }
            } finally {
                blocker.rollback()
            }
        }
        assertEquals(0, rowCount(version))
        assertEquals(DOCUMENT_HASH, store.register(version, DOCUMENT.toByteArray()).sha256)
    }

    @Test
    fun `INSERT 뒤 원문 SELECT 권한 실패는 등록 실패이며 새 행을 롤백한다`() {
        val version = versions.incrementAndGet()
        val role = "market_registration_limited_$version"
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create role $role login password 'limited-password'")
                statement.execute("grant usage on schema public to $role")
                statement.execute("grant insert, select(config_version) on market_configuration_release to $role")
            }
        }
        try {
            val environment =
                database.environment(version, DOCUMENT) +
                    mapOf("FUNDING_MARKET_DB_USER" to role, "FUNDING_MARKET_DB_PASSWORD" to "limited-password")
            val error = assertFailsWith<MarketConfigurationException> { store(environment).register(version, DOCUMENT.toByteArray()) }

            assertEquals(MarketConfigurationError.REGISTRATION_FAILED, error.code)
            assertEquals("compare", error.stage)
            assertEquals(0, rowCount(version))
            assertNull(error.cause)
            assertFalse(error.toString().contains(role))
            assertFalse(error.toString().contains("limited-password"))
        } finally {
            database.connection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("drop owned by $role")
                    statement.execute("drop role $role")
                }
            }
        }
        assertEquals(DOCUMENT_HASH, store().register(version, DOCUMENT.toByteArray()).sha256)
    }

    @Test
    fun `조회 목록은 버전 내림차순이며 다음 페이지는 마지막 버전 미만만 포함한다`() {
        MarketConfigurationDatabase().use { pageDatabase ->
            val store = PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(pageDatabase.environment(1, DOCUMENT)))
            listOf(20L, 5L, Long.MAX_VALUE, 11L).forEach { store.register(it, DOCUMENT.toByteArray()) }

            val first = store.list(null, 2)
            assertEquals(listOf(Long.MAX_VALUE, 20L), first.map { it.configVersion })
            assertEquals(listOf(11L, 5L), store.list(first.last().configVersion, 2).map { it.configVersion })
            assertTrue(store.list(5, 100).isEmpty())
            assertNull(store.find(6))
            assertTrue(first.all { it.sha256 == DOCUMENT_HASH })
        }
    }

    @Test
    fun `DB에 직접 넣은 손상 문서는 상세와 목록에서 사용 가능한 설정으로 반환하지 않는다`() {
        MarketConfigurationDatabase().use { damagedDatabase ->
            val environment = damagedDatabase.register("[]")
            val store = PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(environment))
            val version = environment.getValue("FUNDING_MARKET_CONFIG_VERSION").toLong()

            listOf<() -> Any?>({ store.find(version) }, { store.list(null, 10) }).forEach { query ->
                val error = assertFailsWith<MarketConfigurationException> { query() }
                assertEquals(MarketConfigurationError.LOOKUP_FAILED, error.code)
                assertEquals("stored-document", error.stage)
                assertNull(error.cause)
            }
        }
    }

    @Test
    fun `엄격한 UTF8와 크기 경계를 검사하고 원문 바이트를 보존한다`() {
        val store = store()
        val atLimit = DOCUMENT + " ".repeat(65_536 - DOCUMENT.toByteArray().size)
        val version = versions.incrementAndGet()
        store.register(version, atLimit.toByteArray())
        assertEquals(atLimit, store.find(version)!!.document)
        val invalid =
            listOf(
                (atLimit + " ").toByteArray(),
                byteArrayOf(0xc3.toByte(), 0x28),
                ("\uFEFF" + DOCUMENT).toByteArray(),
                (DOCUMENT + '\u0000').toByteArray(),
                byteArrayOf(),
                "[]".toByteArray(),
            )
        invalid.forEach { bytes ->
            val invalidVersion = versions.incrementAndGet()
            val error = assertFailsWith<MarketConfigurationException> { store.register(invalidVersion, bytes) }
            assertEquals(MarketConfigurationError.INVALID_INPUT, error.code)
            assertEquals("document", error.stage)
            assertEquals(0, rowCount(invalidVersion))
            assertNull(error.cause)
        }
    }

    @Test
    fun `연결 전 실패는 새 저장이 없음을 나타내며 연결 정보와 문서를 노출하지 않는다`() {
        val version = versions.incrementAndGet()
        val settings =
            mapOf(
                "FUNDING_MARKET_DB_URL" to "jdbc:postgresql://127.0.0.1:1/private-database",
                "FUNDING_MARKET_DB_USER" to "private-user",
                "FUNDING_MARKET_DB_PASSWORD" to "private-password",
            )
        val error = assertFailsWith<MarketConfigurationException> { store(settings).register(version, DOCUMENT.toByteArray()) }
        assertEquals(MarketConfigurationError.REGISTRATION_FAILED, error.code)
        assertEquals("connection", error.stage)
        assertNull(error.cause)
        (settings.values + DOCUMENT).forEach { secret -> assertFalse(error.toString().contains(secret)) }
    }

    private fun store(environment: Map<String, String> = database.environment(1, DOCUMENT)): PostgresMarketConfigurationStore =
        PostgresMarketConfigurationStore(MarketConfigurationDatabaseSettings.from(environment))

    private fun rowCount(version: Long): Int =
        database.connection().use { connection ->
            connection.prepareStatement("select count(*) from market_configuration_release where config_version = ?").use { statement ->
                statement.setLong(1, version)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun concurrentRegistrations(
        version: Long,
        documents: List<String>,
    ): List<Result<MarketConfigurationRegistration>> =
        Executors.newFixedThreadPool(documents.size).use { executor ->
            val ready = CountDownLatch(documents.size)
            val start = CountDownLatch(1)
            val futures =
                documents.map { document ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            runCatching { store().register(version, document.toByteArray()) }
                        },
                    )
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }

    private fun blockedRegistration(
        commitBlocker: Boolean,
        requested: String,
        equivalentToOriginal: Boolean = requested == DOCUMENT,
    ) {
        val version = versions.incrementAndGet()
        val applicationName = "market_registration_wait_$version"
        val environment = database.environment(version, requested)
        val url = environment.getValue("FUNDING_MARKET_DB_URL")
        val waitingStore =
            store(environment + ("FUNDING_MARKET_DB_URL" to "$url${if ('?' in url) "&" else "?"}ApplicationName=$applicationName"))
        database.connection().use { blocker ->
            blocker.autoCommit = false
            insert(blocker, version, DOCUMENT)
            Executors.newSingleThreadExecutor().use { executor ->
                val waiting = executor.submit(Callable { runCatching { waitingStore.register(version, requested.toByteArray()) } })
                try {
                    awaitDatabaseLock(applicationName)
                    assertFalse(waiting.isDone)
                    if (commitBlocker) blocker.commit() else blocker.rollback()
                    val result = waiting.get(8, TimeUnit.SECONDS)
                    if (commitBlocker && !equivalentToOriginal) {
                        val error = result.exceptionOrNull() as MarketConfigurationException
                        assertEquals(MarketConfigurationError.CONTENT_CONFLICT, error.code)
                        assertEquals(DOCUMENT, store().find(version)!!.document)
                    } else {
                        val expectedDocument = if (commitBlocker) DOCUMENT else requested
                        assertEquals(sha256(expectedDocument), result.getOrThrow().sha256)
                        assertEquals(expectedDocument, store().find(version)!!.document)
                    }
                    assertEquals(1, rowCount(version))
                } finally {
                    blocker.rollback()
                }
            }
        }
    }

    private fun awaitDatabaseLock(applicationName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        database.connection().use { connection ->
            val sql = "select count(*) from pg_stat_activity where application_name = ? and wait_event_type = 'Lock'"
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, applicationName)
                while (System.nanoTime() < deadline) {
                    statement.executeQuery().use { rows ->
                        rows.next()
                        if (rows.getInt(1) > 0) return
                    }
                    Thread.sleep(10)
                }
            }
        }
        error("등록 연결이 실제 PostgreSQL PK 잠금 대기에 도달하지 않았습니다")
    }

    private fun insert(
        connection: Connection,
        version: Long,
        document: String,
    ) {
        connection.prepareStatement("insert into market_configuration_release(config_version, markets_document) values (?, ?)").use {
            it.setLong(1, version)
            it.setString(2, document)
            it.executeUpdate()
        }
    }

    private fun sha256(document: String): String =
        MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DOCUMENT =
            "[{\"marketId\":\"BTC-USDT\",\"baseAssetId\":\"BTC\",\"quoteAssetId\":\"USDT\",\"baseQuantityScale\":8," +
                "\"feePolicy\":{\"productType\":\"SPOT\",\"feeTier\":\"NORMAL\",\"scheduleVersion\":1," +
                "\"makerPartsPerMillion\":0,\"takerPartsPerMillion\":0}}]"
        private const val DOCUMENT_HASH = "a01e6f92204d886b5f75f803484333d506ce1155045ef3efe54128e0c62bfcbe"
        private const val SUI_MARKET =
            """{"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,""" +
                """"feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,""" +
                """"makerPartsPerMillion":0,"takerPartsPerMillion":0}}"""
        private const val REORDERED_BTC_DOCUMENT =
            """[{"feePolicy":{"takerPartsPerMillion":0,"makerPartsPerMillion":0,"scheduleVersion":1,""" +
                """"feeTier":"NORMAL","productType":"SPOT"},"baseQuantityScale":8,"quoteAssetId":"USDT",""" +
                """"baseAssetId":"BTC","marketId":"BTC-USDT"}]"""
        private const val REORDERED_SUI_MARKET =
            """{"feePolicy":{"takerPartsPerMillion":0,"makerPartsPerMillion":0,"scheduleVersion":2,""" +
                """"feeTier":"NORMAL","productType":"SPOT"},"baseQuantityScale":9,"quoteAssetId":"USDT",""" +
                """"baseAssetId":"SUI","marketId":"SUI-USDT"}"""
        private val MULTI_MARKET_DOCUMENT = DOCUMENT.dropLast(1) + ",$SUI_MARKET]"
        private val REORDERED_FIELDS_DOCUMENT = REORDERED_BTC_DOCUMENT.dropLast(1) + ",$REORDERED_SUI_MARKET]"
        private val REORDERED_MARKETS_DOCUMENT = "[$REORDERED_SUI_MARKET," + REORDERED_BTC_DOCUMENT.drop(1)
        private val CHANGED_DOCUMENT = DOCUMENT.replace("\"takerPartsPerMillion\":0", "\"takerPartsPerMillion\":1000")
        private const val CHANGED_HASH = "7f7f5276c32410158afe54c90c5d727f5b4d3561308da4fd69fbb818d57bfd0e"
    }
}

package com.exchange.core.api.config

import org.apache.tomcat.util.net.NioEndpoint
import org.apache.tomcat.util.threads.ThreadPoolExecutor
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradingGatewayHttpWorkerPrestartTest {
    @Test
    fun `platform worker 사용 시 기본 준비량은 최대 worker 200을 따른다`() {
        val threads = threads()
        assertEquals(200, threads.max)
        assertEquals(200, threads.minSpare)
    }

    @Test
    fun `설정한 최대 worker를 기본 준비량으로 바인딩하고 HTTP 시작 전에 native pool이 모두 생성한다`() {
        val threads = threads("server.tomcat.threads.max" to "4")
        assertEquals(4, threads.minSpare)
        assertNativePrestart(threads, expectedCore = 4)
    }

    @Test
    fun `명시한 최소 worker 수는 최대값으로 덮어쓰지 않고 해당 수만 미리 생성한다`() {
        val configured =
            threads(
                "server.tomcat.threads.max" to "4",
                "TRADING_GATEWAY_HTTP_MIN_SPARE_THREADS" to "2",
            )
        assertEquals(4, configured.max)
        assertEquals(2, configured.minSpare)
        assertNativePrestart(configured, expectedCore = 2)
        assertEquals(10, threads("TRADING_GATEWAY_HTTP_MIN_SPARE_THREADS" to "10").minSpare)
    }

    private fun threads(vararg overrides: Pair<String, String>): TomcatServerProperties.Threads {
        val environment = MockEnvironment()
        YamlPropertySourceLoader()
            .load("gateway", ClassPathResource("application-aeron-gateway.yaml"))
            .forEach { environment.propertySources.addLast(it) }
        overrides.forEach { (name, value) -> environment.setProperty(name, value) }
        return checkNotNull(
            Binder
                .get(environment)
                .bind("server.tomcat.threads", Bindable.of(TomcatServerProperties.Threads::class.java))
                .orElse(TomcatServerProperties.Threads()),
        )
    }

    private fun assertNativePrestart(
        threads: TomcatServerProperties.Threads,
        expectedCore: Int,
    ) {
        val endpoint =
            NioEndpoint().apply {
                name = "gateway-worker-prestart-test"
                maxThreads = threads.max
                minSpareThreads = threads.minSpare
            }
        var native: ThreadPoolExecutor? = null
        try {
            // NioEndpoint.startInternal이 Poller 생성 전에 호출하는 것과 같은 네이티브 생성 경로다.
            endpoint.createExecutor()
            val pool = endpoint.executor as ThreadPoolExecutor
            native = pool
            assertFalse(endpoint.isRunning)
            assertEquals(expectedCore, pool.corePoolSize)
            assertEquals(expectedCore, pool.poolSize)
            assertEquals(threads.max, pool.maximumPoolSize)
            assertFalse(pool.isShutdown)
        } finally {
            endpoint.shutdownExecutor()
            assertNull(endpoint.executor)
            native?.let {
                assertTrue(it.isShutdown)
                assertTrue(it.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AeronGatewaySettingsTest {
    @Test
    fun `필수 연결 설정과 기본 용량 및 시간 제한을 읽는다`() {
        val settings = AeronGatewaySettings.from(environment())

        assertEquals(Path.of("/tmp/http-gateway/run"), settings.runRoot)
        assertEquals("0=node0:19700,1=node1:19700,2=node2:19700", settings.ingressEndpoints)
        assertEquals("gateway:19800", settings.egressEndpoint)
        assertEquals(1024, settings.queueCapacity)
        assertEquals(256, settings.maxInFlight)
        assertEquals(5_000L, settings.resultTimeoutMs)
        assertEquals(10_000L, settings.startupTimeoutMs)
    }

    @Test
    fun `필수값 누락 빈 값과 상대 실행 경로를 거절한다`() {
        environment().keys.forEach { key ->
            assertFailsWith<IllegalArgumentException>(key) { AeronGatewaySettings.from(environment() - key) }
            assertFailsWith<IllegalArgumentException>(key) { AeronGatewaySettings.from(environment() + (key to " ")) }
        }
        assertFailsWith<IllegalArgumentException> {
            AeronGatewaySettings.from(environment() + ("TRADING_GATEWAY_RUN_ROOT" to "relative/run"))
        }
    }

    @Test
    fun `멤버 중복 음수 ID 잘못된 주소와 포트 범위를 거절한다`() {
        listOf(
            "0=node0:19700,0=node1:19700",
            "0=node0:19700,00=node1:19700",
            "-1=node0:19700",
            "node0:19700",
            "0=node0:0",
            "0=node0:65536",
            "0=node0:19700,",
            "0=node0",
            "0=node 0:19700",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                AeronGatewaySettings.from(environment() + ("TRADING_GATEWAY_INGRESS_ENDPOINTS" to invalid))
            }
        }
        listOf("gateway", "gateway:0", "gateway:65536", "gateway:abc", "gateway:1/path").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                AeronGatewaySettings.from(environment() + ("TRADING_GATEWAY_EGRESS_ENDPOINT" to invalid))
            }
        }
    }

    @Test
    fun `용량과 시간 제한은 정수 및 제품 상한을 검증한다`() {
        mapOf(
            "TRADING_GATEWAY_QUEUE_CAPACITY" to listOf("0", "-1", "65537", "2147483648", "1.5", "many"),
            "TRADING_GATEWAY_MAX_IN_FLIGHT" to listOf("0", "4097", "9223372036854775807"),
            "TRADING_GATEWAY_RESULT_TIMEOUT_MS" to listOf("0", "60001", "-1", "9223372036854775808"),
            "TRADING_GATEWAY_STARTUP_TIMEOUT_MS" to listOf("0", "60001", "-1", "1.5"),
        ).forEach { (name, invalidValues) ->
            invalidValues.forEach { value ->
                assertFailsWith<IllegalArgumentException>("$name=$value") {
                    AeronGatewaySettings.from(environment() + (name to value))
                }
            }
        }
    }

    @Test
    fun `명시한 용량과 시간 제한의 상한을 그대로 사용한다`() {
        val settings =
            AeronGatewaySettings.from(
                environment() +
                    mapOf(
                        "TRADING_GATEWAY_QUEUE_CAPACITY" to "65536",
                        "TRADING_GATEWAY_MAX_IN_FLIGHT" to "4096",
                        "TRADING_GATEWAY_RESULT_TIMEOUT_MS" to "60000",
                        "TRADING_GATEWAY_STARTUP_TIMEOUT_MS" to "60000",
                    ),
            )
        assertEquals(65_536, settings.queueCapacity)
        assertEquals(4096, settings.maxInFlight)
        assertEquals(60_000L, settings.resultTimeoutMs)
        assertEquals(60_000L, settings.startupTimeoutMs)
    }

    private fun environment(): Map<String, String> =
        mapOf(
            "TRADING_GATEWAY_RUN_ROOT" to "/tmp/http-gateway/run",
            "TRADING_GATEWAY_INGRESS_ENDPOINTS" to "0=node0:19700,1=node1:19700,2=node2:19700",
            "TRADING_GATEWAY_EGRESS_ENDPOINT" to "gateway:19800",
        )
}

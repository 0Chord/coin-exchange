package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevelopmentFundingSettingsTest {
    private val members =
        (0..2).joinToString("|") { member ->
            "$member," + (0..4).joinToString(",") { "node$member:${19700 + it}" }
        }

    @Test
    fun `기존 단일 노드의 loopback 연결은 유지한다`() {
        val settings = DevelopmentFundingSettings(Path.of("/tmp/native-funding"))
        assertFalse(settings.clustered)
        assertEquals("127.0.0.1:19704", settings.endpoint(4))
        assertEquals("0=127.0.0.1:19700", settings.ingressEndpoints)
    }

    @Test
    fun `환경 설정은 공통 멤버와 자기 컨테이너의 경로 주소를 구분한다`() {
        val settings = developmentClusterSettings(environment())
        assertTrue(settings.clustered)
        assertEquals(1, settings.memberId)
        assertEquals("node1:19704", settings.endpoint(4))
        assertEquals("0.0.0.0", settings.bindHost)
        assertEquals("0=node0:19700,1=node1:19700,2=node2:19700", settings.ingressEndpoints)
        assertEquals("/records/run1/driver", settings.driverDirectory)
    }

    @Test
    fun `목록에 없는 멤버나 목적지로 쓸 수 없는 주소를 거절한다`() {
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() + ("FUNDING_MEMBER_ID" to "3")) }
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() + ("FUNDING_HOST" to "0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() - "FUNDING_MEMBERS") }
    }

    private fun environment() =
        mapOf(
            "FUNDING_RUN_ID" to "run1",
            "FUNDING_RUN_ROOT" to "/records/run1",
            "FUNDING_MEMBERS" to members,
            "FUNDING_MEMBER_ID" to "1",
            "FUNDING_HOST" to "node1",
            "FUNDING_BIND_HOST" to "0.0.0.0",
        )
}

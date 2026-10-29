package com.exchange.core.api.trading.infrastructure.aeron

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpPerformanceLinuxTcpTest {
    private val netstat = "TcpExt: Extra ListenDrops ListenOverflows\nTcpExt: 88 7 3\n"
    private val snmp = "Ip: Forwarding\nIp: 1\nTcp: OutRsts RetransSegs Other\nTcp: 13 11 99\n"

    @Test
    fun `네이티브 헤더 순서로 실제 카운터를 읽고 다른 그룹과 필드는 섞지 않는다`() {
        assertEquals(
            mapOf("TcpExt.ListenOverflows" to 3L, "TcpExt.ListenDrops" to 7L, "Tcp.RetransSegs" to 11L, "Tcp.OutRsts" to 13L),
            parseHttpPerformanceLinuxTcp(netstat, snmp),
        )
        val paths = mutableListOf<String>()
        val result =
            captureHttpPerformanceLinuxTcp {
                paths.add(it)
                if (it.endsWith("netstat")) netstat else snmp
            }
        assertEquals(listOf("/proc/net/netstat", "/proc/net/snmp"), paths)
        assertEquals("CAPTURED", result["status"])
        assertEquals(parseHttpPerformanceLinuxTcp(netstat, snmp), result["counters"])
    }

    @Test
    fun `누락 길이 불일치 중복 음수 오버플로는 영으로 보충하지 않고 거절한다`() {
        for (invalid in listOf(
            "TcpExt: ListenDrops\nTcpExt: 7\n",
            "TcpExt: ListenDrops ListenOverflows\nTcpExt: 7\n",
            netstat + netstat,
            netstat.replace("88 7 3", "88 -1 3"),
            netstat.replace("88 7 3", "88 9223372036854775808 3"),
            "TcpExt: ListenDrops ListenDrops ListenOverflows\nTcpExt: 1 2 3\n",
            "TcpExt: ListenDrops ListenOverflows\n",
            "x".repeat(65_537),
        )) {
            assertFailsWith<IllegalArgumentException> { parseHttpPerformanceLinuxTcp(invalid, snmp) }
        }
    }

    @Test
    fun `proc 파일이 없거나 읽기 실패하면 관측 불가로 남기며 실행 결과를 만들지 않는다`() {
        val result = captureHttpPerformanceLinuxTcp { throw IOException("fixture unavailable") }
        assertEquals("UNAVAILABLE", result["status"])
        assertTrue(result.getValue("error").toString().contains("fixture unavailable"))
        assertTrue("counters" !in result)
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path

private const val TCP_COUNTER_FILE_LIMIT = 65_536
private val requiredTcpCounters = mapOf("TcpExt" to setOf("ListenOverflows", "ListenDrops"), "Tcp" to setOf("RetransSegs", "OutRsts"))

/** Two boundary snapshots only; these are network-namespace counters, not per-request attribution. */
internal fun captureHttpPerformanceLinuxTcp(
    read: (String) -> String = { file ->
        Files.newInputStream(Path.of(file)).use { input ->
            val bytes = input.readNBytes(TCP_COUNTER_FILE_LIMIT + 1)
            check(bytes.size <= TCP_COUNTER_FILE_LIMIT) { "TCP_COUNTER_FILE_LIMIT" }
            bytes.toString(Charsets.US_ASCII)
        }
    },
): Map<String, Any?> =
    try {
        val counters = parseHttpPerformanceLinuxTcp(read("/proc/net/netstat"), read("/proc/net/snmp"))
        mapOf(
            "status" to "CAPTURED",
            "scope" to "gateway Linux network namespace; cumulative counters; not per-request or pure wire latency",
            "counters" to counters,
        )
    } catch (failure: Exception) {
        mapOf("status" to "UNAVAILABLE", "error" to "${failure.javaClass.simpleName}: ${failure.message}".take(512))
    }

/** Parse paired native headers/values exactly; a missing or malformed counter never becomes zero. */
internal fun parseHttpPerformanceLinuxTcp(
    netstat: String,
    snmp: String,
): Map<String, Long> {
    val selected = linkedMapOf<String, Long>()
    for ((source, expectedGroup) in listOf(netstat to "TcpExt", snmp to "Tcp")) {
        require(source.length <= TCP_COUNTER_FILE_LIMIT) { "TCP_COUNTER_FILE_LIMIT" }
        val lines = source.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size % 2 == 0) { "TCP_COUNTER_UNPAIRED_HEADER" }
        var found = false
        for (pair in lines.chunked(2)) {
            val header = pair[0].trim().split(Regex("\\s+"))
            val values = pair[1].trim().split(Regex("\\s+"))
            require(header.size == values.size && header.first() == values.first()) { "TCP_COUNTER_HEADER_MISMATCH" }
            if (header.first() != "$expectedGroup:") continue
            require(!found && header.drop(1).distinct().size == header.size - 1) { "TCP_COUNTER_DUPLICATE_GROUP_OR_KEY" }
            found = true
            for (name in requiredTcpCounters.getValue(expectedGroup)) {
                val index = header.indexOf(name)
                require(index > 0) { "TCP_COUNTER_MISSING:$expectedGroup.$name" }
                val value = values[index].toLongOrNull()
                require(value != null && value >= 0) { "TCP_COUNTER_INVALID:$expectedGroup.$name" }
                selected["$expectedGroup.$name"] = value
            }
        }
        require(found) { "TCP_COUNTER_MISSING_GROUP:$expectedGroup" }
    }
    return selected
}

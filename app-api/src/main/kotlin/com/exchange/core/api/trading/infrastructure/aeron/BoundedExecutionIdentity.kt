package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.generated.AdmissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.TradingState
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 실제 main 산출물을 jar·디렉터리 배치와 무관하게 일정한 컴포넌트 단위로 묶는다. */
internal object BoundedExecutionIdentity {
    val schemaSha256: String by lazy {
        sha256(
            AdmissionCommand
                .getDescriptor()
                .file
                .toProto()
                .toByteArray(),
        )
    }
    val artifactSha256: String by lazy {
        fun sources(vararg anchors: Class<*>): List<Path> =
            anchors
                .map {
                    Path.of(
                        it.protectionDomain.codeSource.location
                            .toURI(),
                    )
                }.distinct()
        digestComponents(
            mapOf(
                "app-api" to sources(BoundedTradingCodec::class.java, AdmissionCommand::class.java),
                "domain-trading" to sources(TradingState::class.java),
                "domain-order" to sources(OrderReservationCalculator::class.java),
                "domain-matching" to sources(MatchingCheckpoint::class.java),
                "domain-ledger" to sources(Balance::class.java),
                "domain-fee" to sources(TradingFeeCalculator::class.java),
                "domain-common" to sources(Amount::class.java),
                "protobuf" to sources(com.google.protobuf.Message::class.java),
                "kotlin-stdlib" to sources(kotlin.Unit::class.java),
                "agrona" to sources(org.agrona.DirectBuffer::class.java),
                "aeron-client" to sources(io.aeron.Aeron::class.java),
                "aeron-cluster" to sources(io.aeron.cluster.service.Cluster::class.java),
                "aeron-archive" to sources(io.aeron.archive.Archive::class.java),
                "aeron-driver" to sources(io.aeron.driver.MediaDriver::class.java),
                "jackson-databind" to sources(tools.jackson.databind.json.JsonMapper::class.java),
                "validation-api" to sources(jakarta.validation.Validation::class.java),
                "hibernate-validator" to sources(org.hibernate.validator.HibernateValidator::class.java),
            ),
            Runtime.version().toString() + ";" + System.getProperty("java.vendor"),
        )
    }

    /** 분리된 Kotlin·Java 산출물이 하나로 합친 배포 jar와 같음을 검증할 때도 사용한다. */
    internal fun digestComponents(
        components: Map<String, List<Path>>,
        runtimeIdentity: String,
    ): String {
        data class Entry(
            val length: Long,
            val open: () -> InputStream,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.sized("exchange-execution-components-v3".toByteArray(Charsets.UTF_8))
        digest.sized(runtimeIdentity.toByteArray(Charsets.UTF_8))
        for ((component, locations) in components.toSortedMap()) {
            digest.sized(component.toByteArray(Charsets.UTF_8))
            val entries = sortedMapOf<String, Entry>()
            val archives = mutableListOf<ZipFile>()
            try {
                for (location in locations.distinct()) {
                    if (Files.isDirectory(location)) {
                        Files.walk(location).use { paths ->
                            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.forEach { path ->
                                val name = location.relativize(path).toString().replace('\\', '/')
                                check(
                                    entries.put(
                                        name,
                                        Entry(Files.size(path)) {
                                            Files.newInputStream(path)
                                        },
                                    ) == null,
                                ) { "duplicate execution class in $component: $name" }
                            }
                        }
                    } else {
                        val archive = ZipFile(location.toFile()).also(archives::add)
                        archive.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                            require(entry.size >= 0)
                            check(
                                entries.put(
                                    entry.name,
                                    Entry(entry.size) {
                                        archive.getInputStream(entry)
                                    },
                                ) == null,
                            ) { "duplicate execution class in $component: ${entry.name}" }
                        }
                    }
                }
                require(entries.isNotEmpty()) { "execution component is empty: $component" }
                digest.update(ByteBuffer.allocate(4).putInt(entries.size).array())
                for ((name, entry) in entries) {
                    digest.sized(name.toByteArray(Charsets.UTF_8))
                    digest.update(ByteBuffer.allocate(8).putLong(entry.length).array())
                    entry.open().use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                            total += n
                        }
                        check(total == entry.length) { "execution artifact changed while hashing" }
                    }
                }
            } finally {
                archives.asReversed().forEach(ZipFile::close)
            }
        }
        return digest.digest().hex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun MessageDigest.sized(bytes: ByteArray) {
        update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        update(bytes)
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BoundedExecutionIdentityTest {
    @TempDir lateinit var root: Path

    @Test fun `split Kotlin and generated Java classes hash identically to the merged deployment jar`() {
        val kotlin = root.resolve("kotlin-main")
        val java = root.resolve("java-main")
        val files =
            mapOf("com/exchange/Adapter.class" to byteArrayOf(1, 2, 3), "com/exchange/generated/Command.class" to byteArrayOf(4, 5, 6))
        for ((name, bytes) in files) {
            val base =
                if ("generated/" in
                    name
                ) {
                    java
                } else {
                    kotlin
                }
            ;val path = base.resolve(name)
            Files.createDirectories(path.parent)
            Files.write(path, bytes)
        }
        val jar = root.resolve("app.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((name, bytes) in files.entries.reversed()) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        val split = BoundedExecutionIdentity.digestComponents(mapOf("app-api" to listOf(kotlin, java)), "same-jdk")
        val merged = BoundedExecutionIdentity.digestComponents(mapOf("app-api" to listOf(jar)), "same-jdk")
        assertEquals(split, merged)
        Files.write(java.resolve("com/exchange/generated/Command.class"), byteArrayOf(4, 5, 7))
        assertNotEquals(split, BoundedExecutionIdentity.digestComponents(mapOf("app-api" to listOf(kotlin, java)), "same-jdk"))
    }
}

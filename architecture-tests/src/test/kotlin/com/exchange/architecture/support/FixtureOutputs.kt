package com.exchange.architecture.support

import java.nio.file.Files
import java.nio.file.Path

/** Copies actual compiled fixture bytes into isolated output roots; no compiler mocks. */
fun fixtureOutput(root: Path, vararg types: Class<*>): Path {
    Files.createDirectories(root)
    types.forEach { type ->
        val relative = type.name.replace('.', '/') + ".class"
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        val bytes = requireNotNull(type.getResourceAsStream("/$relative")) {
            "Fixture bytecode is missing: $relative"
        }.use { it.readBytes() }
        Files.write(target, bytes)
    }
    return root
}

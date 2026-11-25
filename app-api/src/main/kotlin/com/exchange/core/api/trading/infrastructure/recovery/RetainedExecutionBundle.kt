package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.api.trading.infrastructure.aeron.BoundedExecutionIdentity
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.trading.TradingClusterManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

internal data class RetainedBundleProof(
    val executionManifestSha256: String,
    val artifactIndexSha256: String,
)

/** 기동 시 실행 입력을 한 번 복사한다. 주문 callback에서나 응답마다 파일을 복사하지 않는다. */
internal object RetainedExecutionBundle {
    private const val MAGIC = 0x52414232
    private const val MAX_FILES = 100_000
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val INDEX = "artifact-index.bin"
    const val FROZEN_MANIFEST = "frozen-manifest.bin"

    fun retain(
        root: Path,
        manifest: TradingClusterManifest,
    ): RetainedBundleProof {
        require(manifest.executionArtifactSha256 == BoundedExecutionIdentity.artifactSha256)
        require(manifest.schemaSha256 == BoundedExecutionIdentity.schemaSha256)
        Files.createDirectories(root)
        val frozen = BoundedTradingStateCodec().encodeManifest(manifest)
        if (Files.exists(root.resolve(INDEX))) {
            require(Files.readAllBytes(root.resolve(FROZEN_MANIFEST)).contentEquals(frozen)) { "retained execution manifest differs" }
            return verify(root, manifest)
        }
        val locations = linkedSetOf<Path>()
        System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }.forEach {
            val path = Path.of(it)
            if (Files.exists(path)) locations.add(path.toRealPath())
        }
        var loader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.urLs.filter { it.protocol == "file" }.forEach { url ->
                    val path = Path.of(url.toURI())
                    if (Files.exists(path)) locations.add(path.toRealPath())
                }
            }
            loader = loader.parent
        }
        require(locations.isNotEmpty()) { "runtime classpath is unavailable for retention" }
        val files = arrayListOf<Triple<String, Long, String>>()
        val classpath = arrayListOf<String>()
        val directories = linkedSetOf<Path>()
        var totalBytes = 0L
        for ((index, location) in locations.withIndex()) {
            val directory = Files.isDirectory(location)
            val targetRoot = root.resolve("artifacts/$index")
            val destination = if (directory) targetRoot else targetRoot.resolve(location.fileName.toString())
            classpath.add(root.relativize(destination).toString().replace('\\', '/'))
            val sources =
                if (directory) {
                    Files.walk(location).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.sorted().toList()
                    }
                } else {
                    listOf(location)
                }
            if (directory) {
                Files.createDirectories(destination)
                directories.add(destination)
            }
            for (source in sources) {
                require(!Files.isSymbolicLink(source)) { "symbolic runtime artifacts are unsupported" }
                val target = if (directory) destination.resolve(location.relativize(source)) else destination
                val size = Files.size(source)
                totalBytes = Math.addExact(totalBytes, size)
                require(files.size < MAX_FILES && totalBytes <= MAX_BYTES) { "runtime retention exceeds configured byte/file bound" }
                Files.createDirectories(target.parent)
                var parent: Path? = target.parent
                while (parent != null &&
                    parent.startsWith(root)
                ) {
                    directories.add(parent)
                    if (parent == root) break
                    parent = parent.parent
                }
                Files.newInputStream(source).use { input ->
                    FileChannel.open(target, CREATE, TRUNCATE_EXISTING, WRITE).use { output ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            val count = input.read(bytes)
                            if (count < 0) break
                            val buffer = java.nio.ByteBuffer.wrap(bytes, 0, count)
                            while (buffer.hasRemaining()) output.write(buffer)
                        }
                        output.force(true)
                    }
                }
                val digest = digest(target)
                require(size == Files.size(target) && digest == digest(source)) { "runtime artifact changed during retention" }
                files.add(Triple(root.relativize(target).toString().replace('\\', '/'), size, digest))
            }
        }
        directories.sortedByDescending { it.nameCount }.forEach { FileChannel.open(it, READ).use { file -> file.force(true) } }
        RemoteRecoveryFiles.writeForced(root.resolve(FROZEN_MANIFEST), frozen)
        val indexBytes =
            ByteArrayOutputStream()
                .also { stream ->
                    DataOutputStream(stream).use { out ->
                        out.writeInt(MAGIC)
                        out.writeUTF(manifest.executionArtifactSha256)
                        out.writeUTF(RemoteRecoveryFiles.sha256(frozen))
                        out.writeInt(classpath.size)
                        classpath.forEach(out::writeUTF)
                        out.writeInt(files.size)
                        files.forEach { (name, size, sha) ->
                            out.writeUTF(name)
                            out.writeLong(size)
                            out.writeUTF(sha)
                        }
                    }
                }.toByteArray()
        RemoteRecoveryFiles.writeForced(root.resolve(INDEX), indexBytes)
        return verify(root, manifest)
    }

    fun verify(
        root: Path,
        manifest: TradingClusterManifest,
    ): RetainedBundleProof {
        val frozen = Files.readAllBytes(root.resolve(FROZEN_MANIFEST))
        require(BoundedTradingStateCodec().decodeManifest(frozen) == manifest)
        val index = root.resolve(INDEX)
        require(Files.size(index) in 1..RemoteRecoveryFiles.MAX_PROOF_BYTES.toLong())
        val bytes = Files.readAllBytes(index)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readUTF() == manifest.executionArtifactSha256)
            require(input.readUTF() == RemoteRecoveryFiles.sha256(frozen))
            val roots = input.readInt()
            require(roots in 1..MAX_FILES)
            repeat(roots) { resolveContained(root, input.readUTF()) }
            val count = input.readInt()
            require(count in 1..MAX_FILES)
            var total = 0L
            repeat(count) {
                val file = resolveContained(root, input.readUTF())
                val size = input.readLong()
                val sha = input.readUTF()
                require(size >= 0 && Files.isRegularFile(file) && Files.size(file) == size && digest(file) == sha)
                total = Math.addExact(total, size)
                require(total <= MAX_BYTES)
            }
            require(input.read() == -1)
        }
        return RetainedBundleProof(RemoteRecoveryFiles.sha256(frozen), RemoteRecoveryFiles.sha256(bytes))
    }

    fun classPath(root: Path): String =
        DataInputStream(Files.newInputStream(root.resolve(INDEX))).use { input ->
            require(input.readInt() == MAGIC)
            input.readUTF()
            input.readUTF()
            val size = input.readInt()
            require(size in 1..MAX_FILES)
            List(size) { resolveContained(root, input.readUTF()).toString() }.joinToString(File.pathSeparator)
        }

    private fun resolveContained(
        root: Path,
        name: String,
    ): Path {
        require(name.isNotBlank())
        val file = root.resolve(name).normalize()
        require(file.startsWith(root) && !Path.of(name).isAbsolute && !Files.isSymbolicLink(file))
        return file
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

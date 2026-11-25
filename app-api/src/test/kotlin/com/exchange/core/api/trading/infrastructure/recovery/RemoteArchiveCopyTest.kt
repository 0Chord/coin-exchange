package com.exchange.core.api.trading.infrastructure.recovery

import io.aeron.archive.Archive
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RemoteArchiveCopyTest {
    @Test fun `promotion verifier launches with UDP control disabled and serves an explicit local IPC client`(
        @TempDir root: Path,
    ) {
        val directory = root.resolve("promotion-driver").toString()
        MediaDriver
            .launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(directory)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED),
            ).use {
                val context = RemoteRecoveryPromotion.verificationArchiveContext(root, directory)
                Archive.launch(context).use {
                    assertFalse(context.controlChannelEnabled())
                    assertEquals(2, context.fileSyncLevel())
                    assertEquals(2, context.catalogFileSyncLevel())
                    AeronArchive
                        .connect(
                            AeronArchive
                                .Context()
                                .aeronDirectoryName(directory)
                                .controlRequestChannel("aeron:ipc")
                                .controlResponseChannel("aeron:ipc")
                                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5)),
                        ).use { client ->
                            assertEquals(0, client.listRecordings(0, 10) { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> })
                        }
                }
            }
    }

    @Test fun `promotion copies durable catalog and every segment but no old process runtime files`(
        @TempDir root: Path,
    ) {
        val source = root.resolve("stopped-backup-archive").also { Files.createDirectory(it) }
        val durable =
            mapOf(
                "archive.catalog" to byteArrayOf(1, 2, 3),
                "0-0.rec" to byteArrayOf(4, 5),
                "2-134217728.rec" to byteArrayOf(6, 7, 8),
            )
        durable.forEach { (name, bytes) -> Files.write(source.resolve(name), bytes) }
        val runtime = listOf("archive-mark.dat", "archive-mark.lnk", "archive-error.log", "archive.lock")
        runtime.forEach { name -> Files.writeString(source.resolve(name), "recent heartbeat or runtime ownership") }
        val target = root.resolve("replacement-archive")
        RemoteRecoveryPromotion.copyClosedArchive(source, target)
        durable.forEach { (name, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(target.resolve(name))) }
        runtime.forEach { name -> assertFalse(Files.exists(target.resolve(name))) }
        Files.list(target).use { assertEquals(durable.keys, it.map { file -> file.fileName.toString() }.toList().toSet()) }
    }
}

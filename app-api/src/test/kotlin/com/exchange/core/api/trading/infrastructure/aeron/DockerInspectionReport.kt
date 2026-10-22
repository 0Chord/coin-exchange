package com.exchange.core.api.trading.infrastructure.aeron

import java.nio.file.Files
import java.nio.file.Path

/** 환경값은 원본 stdout에도 남기지 않는다. 기존 단일 컨테이너 배열 JSON 형태를 유지한다. */
internal fun dockerContainerInspection(containerId: String): List<String> =
    listOf(
        "docker",
        "inspect",
        "--format",
        """[{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},""" +
            """"HostConfig":{"NetworkMode":{{json .HostConfig.NetworkMode}},"IpcMode":{{json .HostConfig.IpcMode}}},""" +
            """"Mounts":{{json .Mounts}},"NetworkSettings":{{json .NetworkSettings}}}]""",
        containerId,
    )

/** result.json과 원본 명령 출력뿐 아니라 하위 기록 파일도 검사한다. 진단에는 비밀 대신 경로만 반환한다. */
internal fun credentialLeakFiles(
    reportDirectory: Path,
    credential: String,
): List<Path> {
    require(credential.isNotEmpty()) { "검사할 자격증명이 비어 있습니다" }
    val needle = credential.toByteArray(Charsets.UTF_8)
    return Files.walk(reportDirectory).use { files ->
        files
            .filter { Files.isRegularFile(it) }
            .filter { containsCredential(it, needle) }
            .sorted()
            .toList()
    }
}

private fun containsCredential(
    file: Path,
    needle: ByteArray,
): Boolean =
    Files.newInputStream(file).buffered().use { input ->
        val chunkSize = 64 * 1024
        val buffer = ByteArray(chunkSize + needle.size - 1)
        var overlap = 0
        while (true) {
            val count = input.read(buffer, overlap, chunkSize)
            if (count < 0) break
            val length = overlap + count
            for (start in 0..length - needle.size) {
                if (buffer[start] == needle[0] && needle.indices.all { buffer[start + it] == needle[it] }) return@use true
            }
            overlap = minOf(needle.size - 1, length)
            buffer.copyInto(buffer, destinationOffset = 0, startIndex = length - overlap, endIndex = length)
        }
        false
    }

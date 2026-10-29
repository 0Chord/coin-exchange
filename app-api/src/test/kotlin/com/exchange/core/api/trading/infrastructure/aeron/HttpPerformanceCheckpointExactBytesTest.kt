package com.exchange.core.api.trading.infrastructure.aeron

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Small original-file fixtures only; does not start a node, JVM child, load or large-artifact read. */
class HttpPerformanceCheckpointExactBytesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `UTF8와 parser buffer 경계 앞뒤 공백에서도 두 object의 정확한 바이트만 포착한다`() {
        val state = """{ "requests" : [{"payload":"한글😀","escaped":"\uAC00","brace":"}"}] }"""
        val trading = """{ "matchingResults" : [] }"""
        val text = document(0, state, trading, "가".repeat(3_000), trailing = " \r\n\t")
        val ref = write(0, text.toByteArray(Charsets.UTF_8))
        val verified = scan(ref)
        val bytes = text.toByteArray(Charsets.UTF_8)
        for ((pointer, expected) in listOf("/applied/state" to state, "/trading" to trading)) {
            val span = verified.businessByteSpans.getValue(pointer)
            val expectedStart =
                text
                    .substring(0, text.indexOf(expected))
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong()
            assertEquals(expectedStart, span.startInclusive)
            assertEquals(expectedStart + expected.toByteArray(Charsets.UTF_8).size, span.endExclusive)
            assertContentEquals(
                expected.toByteArray(Charsets.UTF_8),
                bytes.copyOfRange(span.startInclusive.toInt(), span.endExclusive.toInt()),
            )
        }
        assertTrue(
            compareHttpPerformanceCheckpointBusinessBytes(
                (0..2).map { member ->
                    scan(write(member, document(member, state, trading, "가".repeat(3_000)).toByteArray()))
                },
            ),
        )
    }

    @Test
    fun `UTF8 BOM도 실제 파일의 세 바이트를 offset에 포함한다`() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val state = """{"requests":[]}"""
        val scans =
            (0..2).map { member ->
                val text = document(member, state = state, prefix = "한글😀")
                val verified = scan(write(member, bom + text.toByteArray(Charsets.UTF_8)))
                assertEquals(
                    3L + text.substring(0, text.indexOf(state)).toByteArray(Charsets.UTF_8).size,
                    verified.businessByteSpans.getValue("/applied/state").startInclusive,
                )
                verified
            }
        assertTrue(compareHttpPerformanceCheckpointBusinessBytes(scans))
    }

    @Test
    fun `빈 object와 마지막 값의 EOF 경계는 다음 공백이나 root 닫기를 포함하지 않는다`() {
        val text = document(0, "{}", "{}")
        val ref = write(0, text.toByteArray())
        val spans = scan(ref).businessByteSpans
        assertEquals(2L, spans.getValue("/applied/state").length)
        assertEquals(2L, spans.getValue("/trading").length)
        assertEquals(ref.bytes - 1, spans.getValue("/trading").endExclusive)
        assertEquals('}'.code.toByte(), Files.readAllBytes(ref.file)[spans.getValue("/trading").endExclusive.toInt()])
    }

    @Test
    fun `영역 밖의 길이와 공백은 달라도 영역 원문이 같으면 같은 것으로 확인한다`() {
        val scans =
            (0..2).map { member ->
                scan(write(member, document(member, prefix = "한글".repeat(member + 1), trailing = " ".repeat(member)).toByteArray()))
            }
        assertTrue(scans.map { it.reference.bytes }.distinct().size > 1)
        assertTrue(compareHttpPerformanceCheckpointBusinessBytes(scans))
    }

    @Test
    fun `필드 순서 공백 및 두 영역 배치 차이는 EOF SHA를 마친 뒤 false만 반환한다`() {
        val variations =
            listOf(
                document(2, state = """{"requests":[{"other":1,"payload":"A"}]}"""),
                document(2, state = """{ "requests": [{"payload":"A","other":1}] }"""),
                document(2, tradingFirst = true),
                document(2, state = """{"requests":[{"payload":"B","other":1}]}"""),
            )
        for (other in variations) {
            val scans = (0..2).map { member -> scan(write(member, (if (member == 2) other else document(member)).toByteArray())) }
            val counts = LongArray(3)
            val eof = BooleanArray(3)
            assertFalse(
                compareHttpPerformanceCheckpointBusinessBytes(scans) { reference ->
                    observe(reference, { count -> if (count < 0) eof[reference.memberId] = true else counts[reference.memberId] += count })
                },
            )
            assertEquals(scans.map { it.reference.bytes }, counts.toList())
            assertTrue(eof.all { it })
        }
    }

    @Test
    fun `byte location을 제공하지 않는 정상 UTF16 문서는 의미 비교로 돌아간다`() {
        val scans =
            (0..2).map { member ->
                val body = document(member).toByteArray(Charsets.UTF_16LE)
                scan(write(member, byteArrayOf(0xff.toByte(), 0xfe.toByte()) + body))
            }
        assertTrue(scans.all { it.businessByteSpans.isEmpty() })
        assertFalse(compareHttpPerformanceCheckpointBusinessBytes(scans))
    }

    @Test
    fun `짧은 read와 일시적인 zero read도 같은 원문을 손실 없이 EOF까지 비교한다`() {
        val scans = references()
        assertTrue(
            compareHttpPerformanceCheckpointBusinessBytes(scans) { reference ->
                object : FilterInputStream(Files.newInputStream(reference.file)) {
                    private var zeroNext = true

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (zeroNext) {
                            zeroNext = false
                            return 0
                        }
                        zeroNext = true
                        return super.read(buffer, offset, minOf(3, length))
                    }
                }
            },
        )
    }

    @Test
    fun `비교 도중 B를 읽은 뒤 파일을 A로 복원해도 같은 read의 저장 SHA 대조로 실패한다`() {
        val scans = references()
        val reference = scans[2].reference
        val original = Files.readAllBytes(reference.file)
        val text = String(original, Charsets.UTF_8)
        val changedAt = text.indexOf("\"payload\":\"A\"") + "\"payload\":\"".length
        check(changedAt >= "\"payload\":\"".length && original[changedAt] == 'A'.code.toByte())
        val changed = original.copyOf().also { it[changedAt] = 'B'.code.toByte() }
        var restored = false
        val failure =
            assertFailsWith<IllegalArgumentException> {
                compareHttpPerformanceCheckpointBusinessBytes(scans) { ref ->
                    if (ref.memberId != 2) {
                        Files.newInputStream(ref.file)
                    } else {
                        Files.write(ref.file, changed)
                        var observed = 0
                        observe(ref, { count ->
                            if (count > 0) observed += count
                            if (!restored && observed > changedAt) {
                                Files.write(ref.file, original)
                                restored = true
                            }
                        })
                    }
                }
            }
        assertTrue(restored)
        assertContentEquals(original, Files.readAllBytes(reference.file))
        assertTrue(failure.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `같은 영역 비교 뒤 뒤쪽이 달라져도 SHA 오류를 false fallback으로 숨기지 않는다`() {
        val scans = references()
        val reference = scans[2].reference
        val original = Files.readAllBytes(reference.file)
        // Replace the final trailing LF with another legal whitespace byte of the same length.
        val changed = original.copyOf().also { it[it.lastIndex] = ' '.code.toByte() }
        Files.write(reference.file, changed)
        val failure = assertFailsWith<IllegalArgumentException> { compareHttpPerformanceCheckpointBusinessBytes(scans) }
        assertTrue(failure.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun `open 뒤 append된 꼬리는 동일한 업무 영역이어도 bytes 오류로 실패한다`() {
        val scans = references()
        var appended = false
        val failure =
            assertFailsWith<IllegalArgumentException> {
                compareHttpPerformanceCheckpointBusinessBytes(scans) { reference ->
                    observe(reference, { count ->
                        if (reference.memberId == 2 && !appended && count > 0) {
                            Files.writeString(reference.file, " ", StandardOpenOption.APPEND)
                            appended = true
                        }
                    })
                }
            }
        assertTrue(appended)
        assertTrue(failure.message.orEmpty().contains("bytes"))
    }

    @Test
    fun `interrupt와 잘못된 byte 범위는 false 결과를 반환하지 않는다`() {
        val scans = references()
        val broken =
            scans[2].copy(
                businessByteSpans =
                    scans[2].businessByteSpans + ("/trading" to HttpPerformanceCheckpointByteSpan(0, scans[2].reference.bytes + 1)),
            )
        assertFailsWith<IllegalStateException> { compareHttpPerformanceCheckpointBusinessBytes(scans.take(2) + broken) }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<InterruptedException> { compareHttpPerformanceCheckpointBusinessBytes(scans) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    private fun references(): List<HttpPerformanceCheckpointScan> =
        (0..2).map { member -> scan(write(member, document(member, trailing = "\n").toByteArray())) }

    private fun scan(reference: HttpPerformanceCheckpointFileReference): HttpPerformanceCheckpointScan =
        HttpPerformanceCheckpointReader.scanRows(reference, onRow = { _, _ -> })

    private fun observe(
        reference: HttpPerformanceCheckpointFileReference,
        observed: (Int) -> Unit,
    ): InputStream =
        object : FilterInputStream(Files.newInputStream(reference.file)) {
            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int = super.read(buffer, offset, length).also(observed)
        }

    private fun write(
        member: Int,
        bytes: ByteArray,
    ): HttpPerformanceCheckpointFileReference {
        val path = directory.resolve("node$member-checkpoint-final.json")
        Files.write(path, bytes)
        return HttpPerformanceCheckpointFileReference(
            path,
            "exact-test",
            member,
            "final",
            bytes.size.toLong(),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
    }

    private fun document(
        member: Int,
        state: String = """{"requests":[{"payload":"A","other":1}]}""",
        trading: String = """{"matchingResults":[]}""",
        prefix: String = "",
        trailing: String = "",
        tradingFirst: Boolean = false,
    ): String {
        val applied = """"applied":{"position":41,"state":$state}"""
        val business = if (tradingFirst) """"trading":$trading,$applied""" else """$applied,"trading":$trading"""
        return """{"kind":"PERFORMANCE_CHECKPOINT","runId":"exact-test","memberId":$member,"label":"final",""" +
            """"prefix":"$prefix",$business}""" +
            trailing
    }
}

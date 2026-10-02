package com.exchange.architecture

import com.exchange.architecture.support.ArchitectureReportVerifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchitectureReportVerifierTest {
    @TempDir
    lateinit var directory: Path

    private val suite = "com.exchange.architecture.ProductionArchitectureTest"
    private val portCase = """<testcase name="P03 포트 검사()" classname="$suite"/>"""
    private val normal = """
        <testsuite name="$suite" tests="8" failures="0" errors="0" skipped="0">
          <testcase name="P04 테스트 도구 검사()" classname="$suite"/>
          <testcase name="P02 모듈 검사()" classname="$suite"/>
          <testcase name="P05 Bean 검사()" classname="$suite"/>
          <testcase name="P01 도메인 검사()" classname="$suite"/>
          $portCase
          <testcase name="P08 이름과 파일 검사()" classname="$suite"/>
          <testcase name="P06 HTTP 검사()" classname="$suite"/>
          <testcase name="P07 application 검사()" classname="$suite"/>
        </testsuite>
    """.trimIndent()

    private fun report(xml: String?): Path = directory.resolve("report.xml").also {
        if (xml != null) Files.writeString(it, xml)
    }

    private fun reject(xml: String?, reason: String) {
        val error = assertFailsWith<IllegalStateException> {
            ArchitectureReportVerifier.main(arrayOf(report(xml).toString()))
        }
        assertTrue(error.message.orEmpty().contains(reason), error.message)
    }

    @Test
    fun `운영 검사 여덟 개가 성공하면 순서와 무관하게 통과한다`() {
        val path = report(normal)
        assertEquals(emptyList(), ArchitectureReportVerifier.problems(path))
        ArchitectureReportVerifier.main(arrayOf(path.toString()))
    }

    @Test
    fun `XML이 없으면 읽기 실패로 거절한다`() {
        reject(null, "보고서를 읽을 수 없습니다")
    }

    @Test
    fun `손상된 XML은 읽기 실패로 거절한다`() {
        reject("<testsuite>", "보고서를 읽을 수 없습니다")
    }

    @Test
    fun `빈 보고서를 검사 통과로 인정하지 않는다`() {
        reject("""<testsuite name="$suite" tests="0"/>""", "P01")
    }

    @Test
    fun `합계가 여덟 개여도 P03이 빠지면 거절한다`() {
        reject(normal.replace(portCase, ""), "P03")
    }

    @Test
    fun `P01을 중복해 여덟 개를 채워도 거절한다`() {
        val duplicate = """<testcase name="P01 다른 도메인 검사()" classname="$suite"/>"""
        reject(normal.replace(portCase, duplicate), "P01: 정확히 한 번")
    }

    @Test
    fun `필수 검사가 skip이면 거절한다`() {
        reject(normal.replace(portCase, portCase.replace("/>", "><skipped/></testcase>")), "skipped")
    }

    @Test
    fun `필수 검사가 실패하면 거절한다`() {
        reject(normal.replace(portCase, portCase.replace("/>", "><failure/></testcase>")), "failure")
    }

    @Test
    fun `필수 검사에 실행 오류가 있으면 거절한다`() {
        reject(normal.replace(portCase, portCase.replace("/>", "><error/></testcase>")), "error")
    }

    @Test
    fun `보고서 합계에 실패가 남아 있으면 거절한다`() {
        reject(normal.replace("failures=\"0\"", "failures=\"1\""), "failures=1")
    }

    @Test
    fun `다른 테스트 모음의 보고서는 운영 검사로 인정하지 않는다`() {
        reject(normal.replace("name=\"$suite\"", "name=\"OtherTest\""), "필수 운영 보고서가 아닙니다")
    }

    @Test
    fun `같은 검사 이름이어도 다른 클래스의 예제는 P03을 대신하지 못한다`() {
        reject(normal.replace(portCase, portCase.replace(suite, "FixtureTest")), "P03")
    }

    @Test
    fun `이름이 비슷한 P030은 P03을 대신하지 못한다`() {
        reject(normal.replace("P03 포트", "P030 포트"), "P03")
    }

    private val newCases get() = Regex("<testcase name=\"(P0[678]) [^\"]+\" classname=\"$suite\"/>")
        .findAll(normal).map { it.groupValues[1] to it.value }.toList()

    @Test fun `P06부터 P08까지 하나라도 누락되면 거절한다`() {
        newCases.forEach { (id, case) -> reject(normal.replace(case, ""), "$id: 정확히 한 번") }
    }

    @Test fun `P06부터 P08까지 중복 실행을 정상 합계로 덮지 못한다`() {
        newCases.forEach { (id, case) -> reject(normal.replace(case, case + case), "$id: 정확히 한 번") }
    }

    @Test fun `새 운영 검사의 skip 실패 오류를 모두 거절한다`() {
        newCases.forEach { (_, case) -> listOf("skipped", "failure", "error").forEach { status ->
            reject(normal.replace(case, case.replace("/>", "><$status/></testcase>")), status)
        } }
    }

    @Test fun `다른 클래스의 P06부터 P08은 운영 실행을 대신하지 못한다`() {
        newCases.forEach { (id, case) -> reject(normal.replace(case, case.replace(suite, "ExampleTest")), id) }
    }

    @Test fun `새 운영 검사도 비슷한 접두사로 대체하지 못한다`() {
        newCases.forEach { (id, case) -> reject(normal.replace(case, case.replace("$id ", "${id}0 ")), id) }
    }
}

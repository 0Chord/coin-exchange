package com.exchange.architecture.support

import java.io.IOException
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** 기존 운영 검사 XML의 실행·성공 여부만 확인한다. 보고서의 신선도는 Gradle 재실행으로 확보한다. */
object ArchitectureReportVerifier {
    private const val suite = "com.exchange.architecture.ProductionArchitectureTest"
    private val required = listOf("P01", "P02", "P03", "P04", "P05", "P06", "P07", "P08")

    fun problems(report: Path): List<String> {
        val parser = DocumentBuilderFactory.newInstance().apply {
            // 보고서를 읽으며 외부 파일·네트워크를 참조하지 않는다.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(error: SAXParseException) = throw error
                override fun fatalError(error: SAXParseException) = throw error
            })
        }
        val root = try {
            parser.parse(report.toFile()).documentElement
        } catch (error: IOException) {
            return listOf("보고서를 읽을 수 없습니다: $report · ${error.message}")
        } catch (error: SAXException) {
            return listOf("보고서를 읽을 수 없습니다: $report · ${error.message}")
        }
        if (root.tagName != "testsuite" || root.getAttribute("name") != suite) {
            return listOf("필수 운영 보고서가 아닙니다: ${root.getAttribute("name")}")
        }
        val cases = root.children("testcase")
        return buildList {
            // 합계만 믿으면 빠진 검사나 중복된 검사도 통과할 수 있다.
            required.forEach { identifier ->
                val count = cases.count {
                    it.getAttribute("classname") == suite && it.getAttribute("name").startsWith("$identifier ")
                }
                if (count != 1) add("$identifier: 정확히 한 번 실행되어야 합니다. 보고서 항목 ${count}개")
            }
            cases.forEach { case ->
                listOf("skipped", "failure", "error").forEach { status ->
                    if (case.children(status).isNotEmpty()) add("${case.getAttribute("name")}: $status")
                }
            }
            listOf("skipped", "failures", "errors").forEach { status ->
                val value = root.getAttribute(status)
                if (value.isNotEmpty() && value != "0") add("운영 보고서 $status=$value")
            }
        }
    }

    private fun Element.children(tag: String): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "운영 검사 XML 경로가 필요합니다." }
        val errors = problems(Path.of(args.single()))
        check(errors.isEmpty()) { "구조 결과 확인 실패:\n" + errors.joinToString("\n") }
        println("필수 운영 검사 P01~P08 실행·성공 확인. 제품·DB 검사 결과는 별도로 확인하세요.")
    }
}

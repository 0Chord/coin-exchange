package com.exchange.architecture.support

import java.nio.file.Path
import java.util.Base64

data class MainIsolationDependencies(
    val projectPath: String, val usage: String, val configuration: String, val buildFile: String,
    val dependencies: List<IsolationDeclaration> = emptyList(),
)
/** selection은 main/test-fixtures/unsupported 중 하나이며 details에 Gradle의 원래 선택 근거를 보존한다. */
data class IsolationDeclaration(
    val kind: String, val target: String, val declaredIn: String, val selection: String = "main",
    val details: String = "", val category: String = "",
)
data class IsolationDependencySnapshot(val configurations: List<MainIsolationDependencies> = emptyList(), val problems: List<String> = emptyList())

object IsolationInputs {
    fun dependencies(text: String?): IsolationDependencySnapshot = try {
        val records = mutableListOf<MainIsolationDependencies>()
        rows(text, "ARCH08-DEPS/1").forEach { fields ->
            when (fields.first()) {
                "C" -> {
                    require(fields.size == 5 && fields.drop(1).none { it.isBlank() }) { "main 구성 형식 오류" }
                    records += MainIsolationDependencies(fields[1], fields[2], fields[3], fields[4])
                }
                "D" -> {
                    require(fields.size == 7 && records.isNotEmpty()) { "의존 형식 오류" }
                    require(fields.subList(1, 5).none { it.isBlank() }) { "필수 의존 값 누락" }
                    records[records.lastIndex] = records.last().let { it.copy(dependencies = it.dependencies + IsolationDeclaration(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6])) }
                }
                else -> error("알 수 없는 의존 기록")
            }
        }
        require(records.isNotEmpty()) { "main 구성 기록 누락" }
        IsolationDependencySnapshot(records)
    } catch (error: Exception) { IsolationDependencySnapshot(problems = listOf("INVALID_ISOLATION_DEPENDENCIES: ${error.message}")) }

    fun targets(inventoryText: String?, outputsText: String?, inventory: GradleModuleInventory): NonProductionIndex = try {
        val keys = rows(inventoryText, "ARCH08-SOURCES/1").map { f ->
            require(f.size == 3 && f[0] == "S" && f.drop(1).none { it.isBlank() }) { "소스셋 발견 형식 오류" }
            SourceSetKey(f[1], f[2])
        }
        require(keys.size == keys.toSet().size) { "중복 소스셋 발견" }
        val outputs = mutableListOf<SourceSetOutput>()
        rows(outputsText, "ARCH08-OUTPUTS/1").forEach { f ->
            require(f.drop(1).none { it.isBlank() }) { "출력 필수 값 누락" }
            when (f[0]) {
                "S" -> { require(f.size == 3); outputs += SourceSetOutput(SourceSetKey(f[1], f[2]), emptyList()) }
                "P" -> {
                    require(f.size == 2 && outputs.isNotEmpty())
                    outputs[outputs.lastIndex] = outputs.last().let { it.copy(roots = it.roots + listOf(Path.of(f[1]))) }
                }
                else -> error("알 수 없는 출력 기록")
            }
        }
        NonProductionTargets.inspect(outputs, keys.toSet(), inventory)
    } catch (error: Exception) { NonProductionIndex(problems = listOf("INVALID_ISOLATION_OUTPUTS: ${error.message}")) }

    private fun rows(text: String?, version: String): List<List<String>> {
        require(!text.isNullOrBlank()) { "$version 입력 누락" }
        val lines = text.lines()
        require(lines.first() == version) { "지원하지 않는 입력 버전" }
        return lines.drop(1).map { line ->
            val parts = line.split('\t')
            listOf(parts.first()) + parts.drop(1).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
        }
    }
}

package com.exchange.architecture.support

import java.util.Base64

/** main의 컴파일/런타임 구성 한 개. 빈 dependencies도 유효한 입력 기록이다. */
data class MainProjectDependencies(
    val projectPath: String,
    val usage: String,
    val configuration: String,
    val buildFile: String,
    val dependencies: List<ProjectDeclaration> = emptyList(),
)

data class ProjectDeclaration(val targetPath: String, val declaredIn: String)
data class ProjectDependencySnapshot(val configurations: List<MainProjectDependencies> = emptyList(), val problems: List<String> = emptyList())

object ProjectDependencies {
    /**
     * Gradle이 전달한 main 구성 기록을 읽는다. 경로의 탭/한글을 보존하기 위해 각 필드는 Base64 UTF-8이다.
     * 버전·행 구조가 맞지 않거나 입력이 없으면 부분 목록 대신 준비 오류를 반환한다.
     */
    fun read(text: String?): ProjectDependencySnapshot {
        if (text.isNullOrBlank()) return ProjectDependencySnapshot(problems = listOf("MISSING_DEPENDENCY_INPUT: Gradle 입력이 없습니다"))
        return try {
            val lines = text.lines()
            require(lines.first() == "ARCH02/1") { "지원하지 않는 입력 버전" }
            val records = mutableListOf<MainProjectDependencies>()
            lines.drop(1).forEachIndexed { index, line ->
                val fields = line.split('\t')
                val values = fields.drop(1).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                require(values.none { it.isBlank() }) { "${index + 2}행의 필수 값 누락" }
                when (fields.first()) {
                    "C" -> {
                        require(values.size == 4) { "${index + 2}행의 구성 형식 오류" }
                        records += MainProjectDependencies(values[0], values[1], values[2], values[3])
                    }
                    "D" -> {
                        require(values.size == 2 && records.isNotEmpty()) { "${index + 2}행의 의존 형식 오류" }
                        val previous = records.last()
                        records[records.lastIndex] = previous.copy(dependencies = previous.dependencies + ProjectDeclaration(values[0], values[1]))
                    }
                    else -> throw IllegalArgumentException("${index + 2}행의 알 수 없는 기록 종류")
                }
            }
            require(records.isNotEmpty()) { "구성 기록이 없습니다" }
            ProjectDependencySnapshot(records)
        } catch (error: IllegalArgumentException) {
            ProjectDependencySnapshot(problems = listOf("INVALID_DEPENDENCY_INPUT: ${error.message}"))
        }
    }
}

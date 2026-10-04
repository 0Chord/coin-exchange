package com.exchange.architecture.support

import java.nio.file.Path
import java.util.Base64

data class MainSourceSnapshot(
    val roots: List<MainSourceRoot> = emptyList(),
    val problems: List<ScopeProblem> = emptyList(),
) {
    companion object {
        /** 빈 모듈/루트도 기록한다. 전달 누락을 소스가 없는 정상 상황으로 해석하지 않는다. */
        fun read(
            text: String?,
            modules: Set<String>,
        ): MainSourceSnapshot {
            val errors = mutableListOf<ScopeProblem>()
            val directories = linkedMapOf<String, Path>()
            val roots = linkedMapOf<Pair<String, String>, Pair<String?, MutableSet<Path>>>()

            fun invalid(detail: String) {
                errors += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, "main-sources", detail)
            }
            if (text == null || text.lineSequence().firstOrNull() != "ARCH05/1") {
                return MainSourceSnapshot(
                    problems =
                        listOf(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, "main-sources", "전달 형식 또는 헤더 누락")),
                )
            }
            for (line in text.lineSequence().drop(1)) {
                try {
                    val cells = line.split('\t')
                    val values = cells.drop(1).map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
                    when (cells[0]) {
                        "M" -> {
                            require(values.size == 2 && values[0] in modules && values[0] !in directories)
                            val dir = Path.of(values[1])
                            require(dir.isAbsolute)
                            directories[values[0]] = dir
                        }

                        "R" -> {
                            require(
                                values.size == 3 && values[0] in directories &&
                                    com.exchange.architecture.policy.LayoutPolicyValidation
                                        .relativePath(values[1]),
                            )
                            val key = values[0] to values[1]
                            require(key !in roots)
                            roots[key] = values[2].ifBlank { null } to linkedSetOf()
                        }

                        "F" -> {
                            require(values.size == 3)
                            val key = values[0] to values[1]
                            val files = requireNotNull(roots[key]).second
                            val file = Path.of(values[2])
                            require(file.isAbsolute && files.add(file))
                        }

                        else -> {
                            error("알 수 없는 행")
                        }
                    }
                } catch (error: Exception) {
                    invalid("잘못된 입력 행: $line (${error.javaClass.simpleName})")
                }
            }
            (modules - directories.keys).forEach { invalid("모듈 기록 누락: $it") }
            directories.keys.filter { module -> roots.keys.none { it.first == module } }.forEach { invalid("소스 루트 기록 누락: $it") }
            if (errors.isNotEmpty()) return MainSourceSnapshot(problems = errors)
            return MainSourceSnapshot(
                roots.map { (key, data) ->
                    MainSourceRoot(key.first, directories.getValue(key.first), key.second, data.second, data.first)
                },
            )
        }
    }
}

package com.exchange.architecture.rules

import com.exchange.architecture.support.*

/** 타입과 원본을 독립 수집한 뒤 연결한다. 준비 오류가 있으면 부분 통과를 반환하지 않는다. */
object NamingPlacement {
    fun inspect(scope: ScopeImportResult, policy: LayoutPolicy, sources: MainSourceSnapshot, registeredModules: Set<String>): PlacementResult {
        val types = RoleNamingPlacement.inspectTypes(scope, policy, registeredModules)
        val files = SourcePlacement.inspect(sources.roots, policy, registeredModules)
        val problems = (types.problems + files.result.problems + sources.problems).toMutableList()
        val sourceModules = sources.roots.map { it.module }.toSet()
        (scope.classesByModule.keys - sourceModules).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_MODULE, it, "운영 소스 입력 누락") }
        val rootViolations = mutableListOf<PlacementViolation>()
        val entries = scope.classesByModule.flatMap { (module, classes) -> classes.map { it.name to (module to it) } }.toMap()
        if (problems.isEmpty()) {
            val linked = mutableMapOf<String, List<ParsedSource>>()
            fun original(name: String, visiting: Set<String> = emptySet()): List<ParsedSource> {
                linked[name]?.let { return it }
                if (name in visiting) {
                    problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, name, "원본 연결 순환")
                    return emptyList()
                }
                val (module, type) = entries.getValue(name)
                val classification = types.classifications.getValue(name)
                val parts = classification.sourceParts
                val matches = if (parts.isNotEmpty()) parts.sorted().flatMap { original(it, visiting + name) }.distinct()
                else {
                    val fileName = type.source.orElse(null)?.fileName?.orElse(null)
                    files.sources.filter { it.module == module && it.path.fileName.toString() == fileName && it.packageSegments.joinToString(".") == type.packageName }
                }
                if (matches.isEmpty() || parts.isEmpty() && matches.size != 1) problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, name, "컴파일 타입의 원본 연결이 없거나 여러 개입니다")
                linked[name] = matches
                return matches
            }
            entries.keys.sorted().forEach { name ->
                val (module, type) = entries.getValue(name)
                val classified = types.classifications.getValue(name)
                val locations = policy.folders.filter { it.id in classified.locations && it.module == module && it.packageName == type.packageName }
                original(name).forEach { source ->
                    if (locations.isNotEmpty() && locations.none { it.sourceRoot == source.relativeRoot }) rootViolations += PlacementViolation(
                        source.path.toString(), "sourceRoot", source.relativeRoot, locations.map { it.sourceRoot }.toSortedSet().joinToString("; "), module,
                        classified.roles.map { it.name }.sorted().joinToString("+"), source.path.toString())
                }
            }
        }
        if (problems.isNotEmpty()) return PlacementResult(problems = namingProblems(problems))
        val violations = (types.violations + files.result.violations + rootViolations).groupBy { it.subject to it.item }
            .values.map { group -> group.first().copy(expected = group.map { it.expected }.toSortedSet().joinToString("; ")) }
            .sortedWith(compareBy({ it.subject }, { it.item }, { it.actual }))
        return types.copy(violations = violations, evaluatedFiles = files.result.evaluatedFiles)
    }
}

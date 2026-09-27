package com.exchange.architecture.rules

import com.exchange.architecture.support.*

/** 타입 검사와 소스 검사는 독립 입력을 읽지만, 최종 판정은 준비가 모두 온전할 때만 한다. */
object NamingPlacement {
    fun inspect(scope: ScopeImportResult, bindings: List<NamingBinding>, policy: LayoutPolicy,
        sources: MainSourceSnapshot, registeredModules: Set<String>, requiredRoles: Set<NamingRole> = emptySet(),
        applicationRoles: Map<String, ApplicationRole> = emptyMap(), httpRoles: Map<String, HttpRole> = emptyMap(),
    ): PlacementResult {
        val types = RoleNamingPlacement.inspectTypes(scope, bindings, policy, registeredModules, requiredRoles, applicationRoles, httpRoles)
        val files = SourcePlacement.inspect(sources.roots, policy, registeredModules)
        val rootViolations = mutableListOf<PlacementViolation>()
        val bindingByType = bindings.associateBy { it.type }
        val problems = (types.problems + files.result.problems + sources.problems).toMutableList()
        val sourceModules = sources.roots.map { it.module }.toSet()
        (scope.classesByModule.keys - sourceModules).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_MODULE, it, "운영 소스 입력 누락") }
        if (problems.isEmpty()) scope.classesByModule.forEach { (module, classes) ->
            classes.forEach { type ->
                val fileName = type.source.orElse(null)?.fileName?.orElse(null)
                val matches = files.sources.filter { it.module == module && it.path.fileName.toString() == fileName &&
                    it.packageSegments.joinToString(".") == type.packageName }
                if (matches.size != 1) problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, type.name,
                    "컴파일 타입의 원본 연결이 없거나 여러 개입니다: $fileName")
                else {
                    val binding = bindingByType[type.name] ?: types.generatedOwners[type.name]?.let(bindingByType::get)
                    if (binding != null) {
                        val location = policy.folders.single { it.id == binding.location }
                        val source = matches.single()
                        if (source.relativeRoot != location.sourceRoot) rootViolations += PlacementViolation(
                            source.path.toString(), "sourceRoot", source.relativeRoot, location.sourceRoot,
                            module, binding.role.name, source.path.toString())
                    }
                }
            }
        }
        if (problems.isNotEmpty()) return PlacementResult(problems = problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
        // 한 파일의 여러 타입이 같은 루트를 위반해도 파일·항목별 보고는 한 번이다.
        val violations = (types.violations + files.result.violations + rootViolations).groupBy { it.subject to it.item }
            .values.map { group -> group.first().copy(expected = group.map { it.expected }.toSortedSet().joinToString("; ")) }
            .sortedWith(compareBy({ it.subject }, { it.item }, { it.actual }))
        return PlacementResult(violations = violations,
            evaluatedTypes = types.evaluatedTypes, evaluatedFiles = files.result.evaluatedFiles, generatedOwners = types.generatedOwners)
    }
}

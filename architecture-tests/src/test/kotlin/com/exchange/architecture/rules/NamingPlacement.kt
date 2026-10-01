package com.exchange.architecture.rules

import com.exchange.architecture.support.*

/** 이름 규칙과 원본 위치는 따로 검사한다. 입력 오류를 부분 성공으로 바꾸지 않는다. */
object NamingPlacement {
    fun inspect(scope: ScopeImportResult, policy: LayoutPolicy, sources: MainSourceSnapshot, registeredModules: Set<String>): PlacementResult {
        val types = NamingRules.inspectTypes(scope, policy, registeredModules)
        val files = SourcePlacement.inspect(sources.roots, policy, registeredModules)
        val problems = (types.problems + files.result.problems + sources.problems).toMutableList()
        val sourceModules = sources.roots.map { it.module }.toSet()
        (scope.classesByModule.keys - sourceModules).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_MODULE, it, "운영 소스 입력 누락") }
        if (problems.isNotEmpty()) return PlacementResult(problems = namingProblems(problems))
        return types.copy(violations = (types.violations + files.result.violations).distinct().sortedWith(compareBy({ it.subject }, { it.ruleId }, { it.item })),
            evaluatedFiles = files.result.evaluatedFiles)
    }
}

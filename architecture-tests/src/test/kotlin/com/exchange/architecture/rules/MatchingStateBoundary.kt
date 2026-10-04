package com.exchange.architecture.rules

import com.exchange.architecture.support.ScopeImportResult
import com.exchange.architecture.support.ScopeProblem
import com.exchange.architecture.support.ScopeProblemCode

data class MatchingStateBoundaryResult(
    val problems: List<ScopeProblem>,
    val violations: List<ArchitectureViolation>,
    val checkedTypes: Set<String>,
) {
    val evaluated: Boolean get() = problems.isEmpty()
}

/** 운영 app-api의 매칭 상태 직접 참조만 검사한다. 내부 변경·콜백 실행은 동작 테스트의 범위다. */
object MatchingStateBoundary {
    private const val MATCHING = "com.exchange.core.matching."
    private val coreTypes = setOf("MatchingEngine", "BookOrder", "PriceLevel", "OrderBook").map { MATCHING + it }.toSet()
    private const val PROCESSOR_IMPLEMENTATION = MATCHING + "InMemoryMarketCommandProcessor"

    fun inspect(scope: ScopeImportResult): MatchingStateBoundaryResult {
        val problems = scope.problems.toMutableList()
        for (module in listOf("app-api", "domain-matching")) {
            val classes = scope.classesByModule[module]
            if (classes == null) {
                problems += ScopeProblem(ScopeProblemCode.MISSING_MODULE, module)
            } else if (classes.none()) {
                problems += ScopeProblem(ScopeProblemCode.EMPTY_MODULE, module)
            }
        }
        val definitions =
            scope.classesByModule["domain-matching"]
                ?.map { it.name }
                .orEmpty()
                .toSet()
        for (name in coreTypes + PROCESSOR_IMPLEMENTATION + (MATCHING + "MarketCommandProcessor")) {
            if (name !in definitions) problems += ScopeProblem(ScopeProblemCode.MISSING_REQUIRED_TYPE, name)
        }
        if (problems.isNotEmpty()) return MatchingStateBoundaryResult(problems.distinct(), emptyList(), emptySet())

        val sources = scope.classesByModule.getValue("app-api")
        val violations =
            sources
                .flatMap { origin ->
                    val configuration =
                        origin.packageName == "com.exchange.core.api.config" &&
                            origin.isAnnotatedWith("org.springframework.context.annotation.Configuration")
                    origin.directDependenciesFromSelf.mapNotNull { dependency ->
                        val target = dependency.targetClass.baseComponentType.name
                        val forbidden = target in coreTypes || (target == PROCESSOR_IMPLEMENTATION && !configuration)
                        if (!forbidden) {
                            null
                        } else {
                            ArchitectureViolation(
                                "ARCH-07",
                                origin.name,
                                target,
                                "운영 매칭 상태 직접 참조 금지 | ${dependency.description}",
                                origin.source.flatMap { it.fileName }.orElse(null),
                                dependency.sourceCodeLocation.lineNumber.takeIf { it > 0 },
                                "engineering/state-access-boundaries-spec.md",
                            )
                        }
                    }
                }.distinct()
                .sortedWith(compareBy({ it.originType }, { it.targetType }, { it.lineNumber }, { it.description }))
        return MatchingStateBoundaryResult(emptyList(), violations, sources.map { it.name }.toSortedSet())
    }
}

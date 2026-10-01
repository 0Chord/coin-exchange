package com.exchange.architecture.support

import com.exchange.architecture.policy.LayoutPolicyValidation
import com.exchange.architecture.policy.PortPlacementPolicy
import com.exchange.architecture.policy.PortLocations
import com.tngtech.archunit.core.domain.JavaClass

data class AllowedSourceRoot(val module: String, val path: String, val producer: String? = null)
data class AllowedFolder(
    val id: String, val module: String, val sourceRoot: String, val folder: String,
    val reason: String,
) { val packageName: String get() = folder.replace('/', '.') }

data class LayoutPolicy(
    val roots: List<AllowedSourceRoot>, val folders: List<AllowedFolder>,
    val naming: PortLocations = PortPlacementPolicy.common,
)

data class NamingPlacementInput(val classes: Map<String, JavaClass>, val modules: Map<String, String>, val problems: List<ScopeProblem>)
data class PlacementViolation(
    val subject: String, val item: String, val actual: String, val expected: String, val module: String,
    val role: String? = null, val sourceFile: String? = null, val lineNumber: Int? = null,
    val ruleId: String = "ARCH-05", val specification: String = "architecture-check-spec.md · ARCH-05",
)
data class PlacementResult(
    val problems: List<ScopeProblem> = emptyList(), val violations: List<PlacementViolation> = emptyList(),
    val evaluatedTypes: Set<String> = emptySet(), val evaluatedFiles: Set<String> = emptySet(),
    val rules: List<NamingRuleResult> = emptyList(),
) { val evaluated: Boolean get() = problems.isEmpty() }

/** 선택 수 0은 해당 규칙의 대상 없음이며 전체 입력 준비 성공과 구분한다. */
data class NamingRuleResult(val id: String, val targets: Set<String>, val failures: List<String>)

object NamingPlacementScope {
    fun prepare(scope: ScopeImportResult, policy: LayoutPolicy, registeredModules: Set<String>): NamingPlacementInput {
        val errors = (scope.problems + LayoutPolicyValidation.inspect(policy, registeredModules) +
            PortPlacementPolicy.validate(policy)).toMutableList()
        val entries = scope.classesByModule.flatMap { (module, classes) -> classes.map { module to it } }
        val classes = entries.associate { it.second.name to it.second }.toSortedMap()
        val modules = entries.associate { it.second.name to it.first }
        if (classes.isEmpty()) errors += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "naming-placement")
        scope.classesByModule.filterValues { it.isEmpty() }.keys.forEach { errors += ScopeProblem(ScopeProblemCode.EMPTY_MODULE, it) }
        entries.groupBy { it.second.name }.filterValues { it.size > 1 }.keys.forEach { errors += ScopeProblem(ScopeProblemCode.DUPLICATE_TYPE, it) }
        (scope.classesByModule.keys - registeredModules).forEach { errors += ScopeProblem(ScopeProblemCode.UNREGISTERED_MODULE, it) }
        return NamingPlacementInput(classes, modules, namingProblems(errors))
    }
}
internal fun namingProblems(errors: List<ScopeProblem>) = errors.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail }))

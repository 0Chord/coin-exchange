package com.exchange.architecture.support

import com.exchange.architecture.policy.LayoutPolicyValidation
import com.exchange.architecture.policy.NamingClassificationPolicy
import com.exchange.architecture.policy.NamingPolicy
import com.tngtech.archunit.core.domain.JavaClass

/** 발견한 선언의 역할. 업무 의미의 정답표가 아니라 공통 형태를 나타낸다. */
enum class NamingRole(val suffix: String?) {
    USE_CASE("UseCase"), SERVICE("Service"), COORDINATOR("Coordinator"),
    CALCULATOR("Calculator"), RESOLVER("Resolver"), STORE_PORT("Store"),
    STORE_IMPLEMENTATION("Store"), REPOSITORY("Repository"),
    PUBLISHER_PORT("Publisher"), PUBLISHER_IMPLEMENTATION("Publisher"),
    CONTROLLER("Controller"), CONFIGURATION("Config"), BOOT("Application"),
    ADVICE("ExceptionHandler"), ENTITY("Entity"), DATA(null), DOMAIN(null), FILE_FACADE(null),
}
enum class DataNames { ANY, HTTP, ERROR }
data class AllowedSourceRoot(val module: String, val path: String, val producer: String? = null)
data class AllowedFolder(
    val id: String, val module: String, val sourceRoot: String, val folder: String,
    val roles: Set<NamingRole>, val reason: String, val dataNames: DataNames = DataNames.ANY,
) { val packageName: String get() = folder.replace('/', '.') }

data class LayoutPolicy(
    val roots: List<AllowedSourceRoot>, val folders: List<AllowedFolder>,
    val naming: NamingPolicy = NamingClassificationPolicy.common,
)

/** 자동 계산한 근거다. 호출자가 클래스마다 등록하는 입력은 없다. */
data class TypeClassification(
    val roles: Set<NamingRole>, val rules: Set<String>, val evidence: Set<String>,
    val locations: Set<String>, val module: String, val packageName: String, val sourceFile: String?,
    val owner: String? = null, val sourceParts: Set<String> = emptySet(),
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
    val generatedOwners: Map<String, String> = emptyMap(), val classifications: Map<String, TypeClassification> = emptyMap(),
) { val evaluated: Boolean get() = problems.isEmpty() }

object NamingPlacementScope {
    fun prepare(scope: ScopeImportResult, policy: LayoutPolicy, registeredModules: Set<String>): NamingPlacementInput {
        val errors = (scope.problems + LayoutPolicyValidation.inspect(policy, registeredModules) +
            NamingClassificationPolicy.validate(policy)).toMutableList()
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

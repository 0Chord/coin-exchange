package com.exchange.architecture.rules

import com.exchange.architecture.support.*

/** 준비 실패는 검사하지 못한 상태이며, 부분 위반이나 검사 수를 성공 근거로 남기지 않는다. */
data class ApplicationBoundaryResult(
    val problems: List<ScopeProblem>,
    val violations: List<ArchitectureViolation>,
    val applicationTypes: Set<String>,
    val configurationTypes: Set<String>,
    val referenceCount: Int,
) { val evaluated: Boolean get() = problems.isEmpty() }

object ApplicationImplementationIndependence {
    /**
     * 업무 코드의 직접 참조만 판정한다. config의 구현 생성은 허용하고 포트 뒤 실행은 펼치지 않는다.
     * 준비 또는 필요한 상속 해석이 실패하면 위반 목록·검사 수를 모두 비우고 미평가로 반환한다.
     */
    fun inspect(scope: ScopeImportResult, registration: ApplicationRoleRegistration): ApplicationBoundaryResult {
        val input = ApplicationBoundaryScope.prepare(scope, registration)
        if (input.problems.isNotEmpty()) return unevaluated(input.problems)
        val sources = input.classes.values.filter { input.roles[it.name] == ApplicationRole.APPLICATION }
        val configurations = input.classes.values.filter { input.roles[it.name] == ApplicationRole.CONFIGURATION && !it.isAnnotation }
        val policy = ApplicationTechnologyPolicy()
        var referenceCount = 0
        val violations = sources.flatMap { origin ->
            origin.directDependenciesFromSelf.mapNotNull { dependency ->
                referenceCount++
                val target = dependency.targetClass.baseComponentType
                val role = input.roles[target.name]
                val forbidden = role in forbiddenRoles || policy.forbidden(target)
                if (!forbidden) null else ArchitectureViolation(
                    "ARCH-04", origin.name, target.name, "APPLICATION → ${role?.name ?: "외부 기술"} | ${dependency.description}",
                    origin.source.flatMap { it.fileName }.orElse(null), dependency.sourceCodeLocation.lineNumber.takeIf { it > 0 },
                    "engineering/architecture-check-spec.md",
                )
            }
        }.distinct().sortedWith(compareBy({ it.originType }, { it.targetType }, { it.sourceFile }, { it.lineNumber }, { it.description }))
        if (policy.problems.isNotEmpty()) return unevaluated(policy.problems)
        return ApplicationBoundaryResult(emptyList(), violations, sources.map { it.name }.toSortedSet(),
            configurations.map { it.name }.toSortedSet(), referenceCount)
    }

    private fun unevaluated(problems: List<ScopeProblem>) = ApplicationBoundaryResult(
        problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })), emptyList(), emptySet(), emptySet(), 0)
    private val forbiddenRoles = setOf(ApplicationRole.INFRASTRUCTURE, ApplicationRole.ENTITY, ApplicationRole.HTTP, ApplicationRole.CONFIGURATION)
}

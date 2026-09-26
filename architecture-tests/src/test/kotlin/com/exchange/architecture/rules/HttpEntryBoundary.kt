package com.exchange.architecture.rules

import com.exchange.architecture.support.*
import com.tngtech.archunit.core.domain.JavaClass

/** 미평가일 때 위반 목록과 참조 수는 비운다. 실제 운영 적용 여부와는 별개인 검사 결과다. */
data class HttpBoundaryResult(
    val problems: List<ScopeProblem>,
    val violations: List<ArchitectureViolation>,
    val apiTypes: Set<String>,
    val referenceCount: Int,
) {
    val evaluated: Boolean get() = problems.isEmpty()
}

object HttpEntryBoundary {
    /**
     * 준비가 온전할 때만 HTTP 출발 타입의 직접 의존을 검사한다.
     * 유즈케이스 본문은 순회하지 않으며, 실제 업무 호출 횟수·순서나 DB 상태를 검증하지 않는다.
     */
    fun inspect(scope: ScopeImportResult, registration: HttpRoleRegistration): HttpBoundaryResult {
        val input = HttpBoundaryScope.prepare(scope, registration)
        if (input.problems.isNotEmpty()) return HttpBoundaryResult(input.problems, emptyList(), emptySet(), 0)
        val sources = input.classes.values.filter { input.roles[it.name] in HttpBoundaryScope.apiRoles }
        var referenceCount = 0
        val violations = sources.flatMap { origin ->
            origin.directDependenciesFromSelf.mapNotNull { dependency ->
                referenceCount++
                val target = dependency.targetClass.baseComponentType
                val from = input.roles.getValue(origin.name)
                val to = input.roles[target.name]
                val forbidden = forbiddenTechnology(target.name) || (to != null && !allowed(origin, target, from, to, registration))
                if (!forbidden) null else ArchitectureViolation(
                    "ARCH-03", origin.name, target.name, "$from → ${to?.name ?: "외부 기술"} | ${dependency.description}",
                    origin.source.flatMap { it.fileName }.orElse(null),
                    dependency.sourceCodeLocation.lineNumber.takeIf { it > 0 }, "engineering/architecture-check-spec.md",
                )
            }
        }.distinct().sortedWith(compareBy({ it.originType }, { it.targetType }, { it.sourceFile }, { it.lineNumber }, { it.description }))
        return HttpBoundaryResult(emptyList(), violations, sources.map { it.name }.toSortedSet(), referenceCount)
    }

    private fun allowed(origin: JavaClass, target: JavaClass, from: HttpRole, to: HttpRole, registration: HttpRoleRegistration): Boolean {
        if (to in setOf(HttpRole.CONVERSION, HttpRole.DATA)) return true
        if (from != HttpRole.CONTROLLER) return false
        if (to == HttpRole.USE_CASE) return true
        if (to != HttpRole.CONTROLLER) return false
        // 자기 중첩 타입과 공통 컨트롤러 부모는 다른 HTTP 진입점을 호출하는 것과 구분한다.
        val sameOwner = registration.types[HttpRole.CONTROLLER].orEmpty().any {
            belongsToRole(origin, setOf(it)) && belongsToRole(target, setOf(it))
        }
        return sameOwner || target in origin.allRawSuperclasses
    }

    private fun forbiddenTechnology(name: String): Boolean =
        technologyPackages.any { name.startsWith(it) } || clientTypes.any { name == it || name.startsWith("$it$") }

    private val technologyPackages = setOf("java.sql.", "javax.sql.", "org.springframework.jdbc.", "jakarta.persistence.",
        "org.springframework.data.repository.", "org.springframework.data.jpa.repository.", "java.net.http.")
    private val clientTypes = setOf("org.springframework.web.client.RestTemplate", "org.springframework.web.client.RestOperations",
        "org.springframework.web.client.RestClient", "org.springframework.web.reactive.function.client.WebClient")
}

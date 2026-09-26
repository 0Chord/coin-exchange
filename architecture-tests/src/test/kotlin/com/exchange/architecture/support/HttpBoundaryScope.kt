package com.exchange.architecture.support

import com.exchange.architecture.rules.belongsToRole
import com.tngtech.archunit.core.domain.JavaClass

enum class HttpRole {
    CONTROLLER, CONVERSION, USE_CASE, DATA, COLLABORATOR, PORT, INFRASTRUCTURE, ENTITY, CONFIGURATION,
}

/** 역할 목록과 별개인 패키지 범위로 새 API 타입의 등록 누락을 확인한다. */
data class HttpRoleRegistration(
    val types: Map<HttpRole, Set<String>>,
    val apiPackages: Set<String>,
    val projectPackages: Set<String>,
)

data class HttpBoundaryInput(
    val classes: Map<String, JavaClass>,
    val roles: Map<String, HttpRole>,
    val problems: List<ScopeProblem>,
)

object HttpBoundaryScope {
    /**
     * 수집 오류와 역할 오류를 함께 남긴다. 부분 역할이 있어도 오류가 있으면 규칙을 평가하지 않아야 한다.
     * 어노테이션 발견과 패키지 전체를 역할 등록과 대조해, 등록한 타입만 읽는 거짓 통과를 막는다.
     */
    fun prepare(scope: ScopeImportResult, registration: HttpRoleRegistration): HttpBoundaryInput {
        val all = scope.classesByModule.values.flatMap { it.toList() }
        val classes = all.associateBy { it.name }
        val problems = scope.problems.toMutableList()
        if (classes.isEmpty()) problems += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "http-boundary")
        all.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            problems += ScopeProblem(ScopeProblemCode.DUPLICATE_TYPE, it)
        }
        for ((label, packages) in listOf("apiPackages" to registration.apiPackages, "projectPackages" to registration.projectPackages)) {
            if (packages.isEmpty() || packages.any { it.isBlank() || it.startsWith('.') || it.endsWith('.') }) {
                problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, label, "비어 있지 않은 패키지 이름이 필요합니다")
            }
        }
        registration.types.values.flatten().toSet().minus(classes.keys).forEach {
            problems += ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, it)
        }
        registration.types.values.flatten().toSet().filter { name ->
            registration.projectPackages.none { inPackage(name, it) }
        }.forEach {
            problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, it, "프로젝트 외부 타입에는 HTTP 역할을 부여할 수 없습니다")
        }
        val roles = buildMap {
            classes.values.forEach { type ->
                val matches = registration.types.filterValues { belongsToRole(type, it) }.keys
                when (matches.size) {
                    1 -> put(type.name, matches.single())
                    in 2..Int.MAX_VALUE -> problems.add(ScopeProblem(ScopeProblemCode.CONFLICTING_HTTP_ROLE,
                        type.name, matches.map { it.name }.sorted().joinToString()))
                }
            }
        }
        for (role in listOf(HttpRole.CONTROLLER, HttpRole.USE_CASE)) {
            if (roles.values.none { it == role }) problems += ScopeProblem(ScopeProblemCode.EMPTY_ROLE, role.name)
        }
        classes.values.forEach { type ->
            // 합성 어노테이션 선언 자체는 컨트롤러 인스턴스가 아니다.
            if (!type.isAnnotation && (type.isAnnotatedWith("org.springframework.stereotype.Controller") ||
                    type.isMetaAnnotatedWith("org.springframework.stereotype.Controller")) && roles[type.name] != HttpRole.CONTROLLER) {
                problems += ScopeProblem(ScopeProblemCode.UNREGISTERED_CONTROLLER, type.name)
            }
            if (registration.apiPackages.any { inPackage(type.name, it) } && type.name !in roles) {
                problems += ScopeProblem(ScopeProblemCode.UNCLASSIFIED_HTTP_TYPE, type.name)
            }
            if (roles[type.name] in apiRoles) type.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                if (registration.projectPackages.any { inPackage(target, it) }) {
                    if (target !in classes) {
                        problems += ScopeProblem(ScopeProblemCode.UNRESOLVED_PROJECT_TYPE, target, "참조한 HTTP 타입: ${type.name}")
                    } else if (target !in roles) {
                        problems += ScopeProblem(ScopeProblemCode.UNCLASSIFIED_HTTP_TYPE, target, "참조한 HTTP 타입: ${type.name}")
                    }
                }
            }
        }
        return HttpBoundaryInput(classes, roles, problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
    }

    val apiRoles = setOf(HttpRole.CONTROLLER, HttpRole.CONVERSION)

    private fun inPackage(name: String, pkg: String) = name.startsWith("$pkg.")
}

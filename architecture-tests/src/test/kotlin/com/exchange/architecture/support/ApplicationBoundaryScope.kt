package com.exchange.architecture.support

import com.exchange.architecture.rules.belongsToRole
import com.tngtech.archunit.core.domain.JavaClass

enum class ApplicationRole { APPLICATION, DOMAIN, PORT, EXECUTOR, HTTP, INFRASTRUCTURE, ENTITY, CONFIGURATION }

/** 발견 범위는 역할 목록과 별도로 지정하여 새 업무·설정 타입의 등록 누락을 찾는다. */
data class ApplicationRoleRegistration(
    val types: Map<ApplicationRole, Set<String>>,
    val applicationPackages: Set<String>,
    val configurationPackages: Set<String>,
    val projectPackages: Set<String>,
)

data class ApplicationBoundaryInput(
    val classes: Map<String, JavaClass>,
    val roles: Map<String, ApplicationRole>,
    val problems: List<ScopeProblem>,
)

object ApplicationBoundaryScope {
    /** config도 누락 검증은 받는다. 오류가 있으면 뒤의 의존 규칙은 부분 결과를 평가하지 않는다. */
    fun prepare(scope: ScopeImportResult, registration: ApplicationRoleRegistration): ApplicationBoundaryInput {
        val all = scope.classesByModule.values.flatMap { it.toList() }
        val classes = all.associateBy { it.name }
        val problems = scope.problems.toMutableList()
        if (classes.isEmpty()) problems += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "application-boundary")
        all.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            problems += ScopeProblem(ScopeProblemCode.DUPLICATE_TYPE, it)
        }
        for ((label, packages) in listOf("applicationPackages" to registration.applicationPackages,
            "configurationPackages" to registration.configurationPackages, "projectPackages" to registration.projectPackages)) {
            if (packages.isEmpty() || packages.any { !packageName.matches(it) }) {
                problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, label, "비어 있지 않은 패키지 이름이 필요합니다")
            }
            if (label != "projectPackages" && packages.any { pkg -> registration.projectPackages.none { pkg == it || pkg.startsWith("$it.") } }) {
                problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, label, "발견 범위는 프로젝트 범위 안이어야 합니다")
            }
        }
        val registered = registration.types.values.flatten().toSet()
        registered.minus(classes.keys).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, it) }
        registered.filter { name -> registration.projectPackages.none { inPackage(name, it) } }.forEach {
            problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, it, "프로젝트 외부 타입에는 내부 역할을 부여할 수 없습니다")
        }
        val roles = buildMap {
            classes.values.forEach { type ->
                val matches = registration.types.filterValues { belongsToRole(type, it) }.keys
                when (matches.size) {
                    1 -> put(type.name, matches.single())
                    in 2..Int.MAX_VALUE -> problems.add(ScopeProblem(ScopeProblemCode.CONFLICTING_APPLICATION_ROLE,
                        type.name, matches.map { it.name }.sorted().joinToString()))
                }
            }
        }
        if (roles.values.none { it == ApplicationRole.APPLICATION }) {
            problems += ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "APPLICATION")
        }
        classes.values.forEach { type ->
            // 합성 설정 어노테이션 선언 자체는 조립 객체가 아니다.
            if (!type.isAnnotation && (type.isAnnotatedWith(configuration) || type.isMetaAnnotatedWith(configuration)) &&
                roles[type.name] != ApplicationRole.CONFIGURATION) {
                problems += ScopeProblem(ScopeProblemCode.UNREGISTERED_CONFIGURATION, type.name)
            }
            if ((registration.applicationPackages + registration.configurationPackages).any { inPackage(type.name, it) } && type.name !in roles) {
                problems += ScopeProblem(ScopeProblemCode.UNCLASSIFIED_APPLICATION_TYPE, type.name)
            }
            if (roles[type.name] in setOf(ApplicationRole.APPLICATION, ApplicationRole.CONFIGURATION)) {
                type.directDependenciesFromSelf.forEach { dependency ->
                    val target = dependency.targetClass.baseComponentType.name
                    if (registration.projectPackages.any { inPackage(target, it) }) {
                        if (target !in classes) problems += ScopeProblem(ScopeProblemCode.UNRESOLVED_PROJECT_TYPE, target, "참조한 타입: ${type.name}")
                        else if (target !in roles) problems += ScopeProblem(ScopeProblemCode.UNCLASSIFIED_APPLICATION_TYPE, target, "참조한 타입: ${type.name}")
                    }
                }
            }
        }
        return ApplicationBoundaryInput(classes, roles, problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
    }

    private const val configuration = "org.springframework.context.annotation.Configuration"
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private fun inPackage(name: String, pkg: String) = name.startsWith("$pkg.")
}

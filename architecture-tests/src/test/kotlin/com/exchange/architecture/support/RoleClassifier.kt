package com.exchange.architecture.support

import com.exchange.architecture.rules.ArchitectureRoles
import com.exchange.architecture.rules.belongsToRole
import com.tngtech.archunit.core.domain.JavaClasses

data class RoleRegistration(
    val domainModules: Set<String>,
    val externalPorts: Set<String>,
    val executors: Set<String>,
    val reviewedPureInterfaces: Set<String>,
)

data class RoleClassification(val roles: ArchitectureRoles, val problems: List<ScopeProblem>)

object RoleClassifier {
    /**
     * 도메인 타입을 기본 포함하고 등록한 포트·실행기만 별도 역할로 분리한다.
     *
     * 새 인터페이스는 자동 제외하지 않고 미분류 오류를 남긴다.
     * @return 역할 집합과 분류 오류. 오류가 있어도 집합은 반환되므로 호출자가 진행을 막아야 한다.
     */
    fun classify(classesByModule: Map<String, JavaClasses>, registration: RoleRegistration): RoleClassification {
        val all = classesByModule.values.flatMap { it.toList() }
        val domains = classesByModule.filterKeys { it in registration.domainModules }.values.flatMap { it.toList() }
        val ports = all.filter { belongsToRole(it, registration.externalPorts) }.map { it.name }.toSet()
        val executors = all.filter { belongsToRole(it, registration.executors) }.map { it.name }.toSet()
        val pure = domains.filter { it.name !in ports && it.name !in executors }
        val problems = buildList {
            val required = registration.externalPorts + registration.executors + registration.reviewedPureInterfaces
            (required - all.map { it.name }.toSet()).forEach { add(ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, it)) }
            registration.domainModules.forEach { module ->
                val names = classesByModule[module]?.map { it.name }?.toSet().orEmpty()
                if (pure.none { it.name in names }) add(ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "pure-domain:$module"))
            }
            pure.filter { it.isInterface && it.name !in registration.reviewedPureInterfaces }.forEach {
                add(ScopeProblem(ScopeProblemCode.UNCLASSIFIED_INTERFACE, it.name))
            }
        }.sortedWith(compareBy({ it.code.name }, { it.subject }))
        return RoleClassification(ArchitectureRoles(pure.map { it.name }.toSet(), ports, executors), problems)
    }
}

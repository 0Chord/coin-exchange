package com.exchange.architecture.rules

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaClass

data class ArchitectureRoles(
    val pureDomain: Set<String>,
    val externalPorts: Set<String> = emptySet(),
    val executors: Set<String> = emptySet(),
)

/** 위반 위치를 확인할 수 없으면 [sourceFile]·[lineNumber]는 null로 남기며 추측하지 않는다. */
data class ArchitectureViolation(
    val ruleId: String,
    val originType: String,
    val targetType: String,
    val description: String,
    val sourceFile: String?,
    val lineNumber: Int?,
    val specification: String,
)

object DomainTechnologyIndependence {
    /**
     * 순수 도메인의 직접 기술 의존과 등록된 외부 포트 접근을 검사한다.
     *
     * 수집·역할 오류가 없는 입력을 전제로 한다. 빈 입력의 위반 없음은 수집 성공을 뜻하지 않는다.
     * 바이트코드에 남은 참조만 보며 리플렉션·모든 간접 콜백의 실행을 추적하지 않는다.
     * @return 중복을 제거하고 정렬한 위반 목록. 역할 밖 객체의 기술 사용은 이 규칙의 대상이 아니다.
     */
    fun evaluate(classes: JavaClasses, roles: ArchitectureRoles): List<ArchitectureViolation> {
        val violations = mutableListOf<ArchitectureViolation>()
        classes.filter { belongsToRole(it, roles.pureDomain) }.forEach { origin ->
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                if (ExternalTechnologyTypes.contains(target)) {
                    violations += diagnostic(origin, target, dependency.description, dependency.sourceCodeLocation.lineNumber)
                }
            }
            // 포트 타입 보유는 허용하지만 메서드 호출·참조로 외부 작업을 넘기는 것은 금지한다.
            (origin.methodCallsFromSelf + origin.methodReferencesFromSelf).forEach { access ->
                val target = access.target.owner
                if (target.name in roles.externalPorts || target.allRawInterfaces.any { it.name in roles.externalPorts }) {
                    violations += diagnostic(origin, target.name, access.description, access.lineNumber)
                }
            }
        }
        return violations.distinct().sortedWith(compareBy(
            { it.ruleId }, { it.originType }, { it.targetType }, { it.sourceFile }, { it.lineNumber }, { it.description },
        ))
    }

    private fun diagnostic(origin: JavaClass, target: String, description: String, line: Int) = ArchitectureViolation(
        ruleId = "ARCH-01", originType = origin.name, targetType = target, description = description,
        sourceFile = origin.source.flatMap { it.fileName }.orElse(null),
        lineNumber = line.takeIf { it > 0 }, specification = "engineering/architecture-check-spec.md",
    )
}

/** 생성된 이름의 모양 대신 바이트코드의 포함 관계를 따라 중첩 타입의 역할을 확인한다. */
fun belongsToRole(type: JavaClass, roots: Set<String>): Boolean =
    generateSequence(type) { it.enclosingClass.orElse(null) }.any { it.name in roots }

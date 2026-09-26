package com.exchange.architecture.rules

import com.exchange.architecture.support.ScopeImportResult

data class PortContractProblem(val code: String, val subject: String)

data class PortContractViolation(
    val originModule: String,
    val originType: String,
    val declaration: String,
    val exposure: String,
    val targetType: String,
    val reason: String,
    val sourceFile: String?,
    val lineNumber: Int?,
) {
    val ruleId: String = "ARCH-06"
    val specification: String = "engineering/architecture-check-spec.md"
    fun report() = "$ruleId | $originModule | $originType | $declaration | $exposure | $targetType | $reason | " +
        "${sourceFile ?: "파일 정보 없음"}:${lineNumber ?: "행 정보 없음"} | $specification"
}

data class PortInspection(
    val ports: Set<String>,
    val contractCount: Int,
    val problems: List<PortContractProblem>,
    val violations: List<PortContractViolation>,
) {
    val evaluated: Boolean get() = problems.isEmpty()
}

object PortContractIndependence {
    private val persistenceAnnotations = setOf("jakarta.persistence", "javax.persistence").flatMap { namespace ->
        listOf("Entity", "Embeddable", "MappedSuperclass").map { "$namespace.$it" }
    }.toSet()

    /**
     * 등록한 포트의 공개 계약만 읽는다. 준비 문제가 있으면 부분 결과를 정상으로 판정하지 않는다.
     *
     * @param scope 기존 수집·역할 검사 결과. 포함된 문제를 먼저 확인한다.
     * @param ports 이름이나 패키지로 추론하지 않은 명시적 포트 인터페이스 목록.
     * @param persistenceTypes JPA 표식이 없어도 영속 모델로 취급할 등록 타입. 정의 누락은 준비 오류다.
     * @param projectPackagePrefixes 상위 계약을 클래스패스의 대체 정의로 읽으면 안 되는 내부 이름 범위.
     * @return 준비 실패면 미평가이며 위반 목록은 비운다. DB·포트 메서드를 실행하지 않는다.
     */
    fun inspect(scope: ScopeImportResult, ports: Set<String>, persistenceTypes: Set<String> = emptySet(),
        projectPackagePrefixes: Set<String> = emptySet()): PortInspection {
        val problems = scope.problems.map { PortContractProblem(it.code.name, "${it.subject}: ${it.detail.orEmpty()}") }.toMutableList()
        val definitions = scope.classesByModule.flatMap { (module, types) -> types.map { it.name to (module to it) } }
        val types = definitions.toMap()
        definitions.groupBy { it.first }.filterValues { it.size > 1 }.keys.forEach {
            problems += PortContractProblem("AMBIGUOUS_OWNERSHIP", it)
        }
        if (ports.isEmpty()) problems += PortContractProblem("EMPTY_PORTS", "externalPorts")
        ports.forEach { name ->
            val type = types[name]?.second
            when {
                type == null -> problems += PortContractProblem("MISSING_PORT", name)
                !type.isInterface -> problems += PortContractProblem("INVALID_PORT_TYPE", name)
            }
        }
        (persistenceTypes - types.keys).forEach { problems += PortContractProblem("MISSING_PERSISTENCE_TYPE", it) }
        fun result(count: Int, violations: List<PortContractViolation>) = PortInspection(
            ports.toSortedSet(), count, problems.distinct().sortedWith(compareBy({ it.code }, { it.subject })),
            // 브리지는 같은 계약에도 다른 소스 행을 가질 수 있다. 행이 아닌 노출 계약으로 합친다.
            if (problems.isEmpty()) violations.sortedWith(compareBy(
                { it.originType }, { it.declaration }, { it.exposure }, { it.targetType },
                { it.lineNumber ?: Int.MAX_VALUE }, { it.sourceFile },
            )).distinctBy { listOf(it.originType, it.declaration, it.exposure, it.targetType) } else emptyList(),
        )
        if (problems.isNotEmpty()) return result(0, emptyList())
        val persistent = persistenceTypes + types.values.map { it.second }.filter { type ->
            type.annotations.any { it.rawType.name in persistenceAnnotations }
        }.map { it.name }
        val violations = mutableListOf<PortContractViolation>()
        var count = 0
        ports.sorted().forEach { root ->
            val (module, port) = types.getValue(root)
            val reader = PortContractReader(types.mapValues { it.value.second }, projectPackagePrefixes) {
                it in persistent || isTechnology(it)
            }
            try { reader.read(port) } catch (error: RuntimeException) {
                problems += PortContractProblem("CONTRACT_READ_FAILURE", "$root: ${error.javaClass.name}: ${error.message}")
            }
            problems += reader.problems
            if (reader.contractCount == 0 && reader.problems.isEmpty()) problems += PortContractProblem("EMPTY_PORT_CONTRACT", root)
            count += reader.contractCount
            reader.references.forEach referenceLoop@ { reference ->
                val reason = when {
                    reference.target in persistent -> "PERSISTENCE"
                    isTechnology(reference.target) -> "TECHNOLOGY"
                    else -> return@referenceLoop
                }
                violations += with(reference) {
                    PortContractViolation(module, root, declaration, exposure, target, reason,
                        owner.source.flatMap { it.fileName }.orElse(null), line)
                }
            }
        }
        return result(count, violations)
    }

    private fun isTechnology(name: String) = ExternalTechnologyTypes.contains(name) ||
        name.startsWith("jakarta.transaction.") || name.startsWith("javax.transaction.") ||
        name == "tools.jackson.databind.ObjectMapper"
}

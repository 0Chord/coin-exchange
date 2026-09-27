package com.exchange.architecture.policy

import com.exchange.architecture.support.*

/** 이름 규칙만 관리한다. 허용 경로는 LayoutPolicy의 위치 ID를 참조한다. */
data class NamingRule(val id: String, val role: NamingRole, val suffix: String? = role.suffix)
data class NamingPolicy(
    val rules: List<NamingRule>,
    val storeTechnologies: Set<String> = setOf("Postgres", "Jpa"),
    val storeTargets: Map<String, String> = emptyMap(),
    val publisherTargets: Map<String, String> = emptyMap(),
)
object NamingClassificationPolicy {
    val coreModules = setOf("domain-common", "domain-fee", "domain-order", "domain-ledger", "domain-matching")
    val common = NamingPolicy(NamingRole.entries.map { NamingRule("NAME-${it.name}", it) })
    fun validate(policy: LayoutPolicy): List<ScopeProblem> {
        val problems = mutableListOf<ScopeProblem>()
        fun invalid(subject: String, why: String) { problems += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, subject, why) }
        val p = policy.naming
        if (p.rules.isEmpty() || p.storeTechnologies.isEmpty()) invalid("naming-policy", "규칙·저장 기술 목록이 비어 있습니다")
        p.rules.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { invalid(it, "규칙 ID 중복") }
        p.rules.groupBy { it.role }.filterValues { it.size > 1 }.keys.forEach { invalid(it.name, "역할 규칙 중복") }
        p.rules.forEach { if (it.id.isBlank() || it.suffix?.isBlank() == true || (it.role.suffix != null && it.suffix == null)) invalid(it.id, "이름 조건 누락") }
        if (p.storeTechnologies.any { !it.matches(Regex("[A-Z][A-Za-z0-9]*")) }) invalid("store-technologies", "기술 접두사 형식")
        val knownRoles = p.rules.map { it.role }.toSet()
        policy.folders.forEach { if ((it.roles - knownRoles).isNotEmpty()) invalid(it.id, "허용 역할의 명명 규칙 누락") }
        val folders = policy.folders.associateBy { it.id }
        p.storeTargets.forEach { (port, implementation) ->
            if (folders[port]?.roles?.contains(NamingRole.STORE_PORT) != true ||
                folders[implementation]?.roles?.contains(NamingRole.STORE_IMPLEMENTATION) != true) invalid("$port->$implementation", "포트·저장 구현 위치가 필요합니다")
        }
        p.publisherTargets.forEach { (prefix, location) ->
            if (prefix !in setOf("Persistent", "NoOp") || folders[location]?.roles?.contains(NamingRole.PUBLISHER_IMPLEMENTATION) != true)
                invalid(prefix, "발행 구현의 접두사·위치가 필요합니다")
        }
        return problems
    }
}

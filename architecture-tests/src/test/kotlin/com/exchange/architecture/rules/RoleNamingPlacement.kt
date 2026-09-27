package com.exchange.architecture.rules

import com.exchange.architecture.support.*

object RoleNamingPlacement {
    /** 선언 형태나 어노테이션으로 역할을 추측하지 않고 검토한 등록값을 쓴다. */
    fun inspectTypes(scope: ScopeImportResult, bindings: List<NamingBinding>, policy: LayoutPolicy,
        registeredModules: Set<String>, requiredRoles: Set<NamingRole> = emptySet(),
        applicationRoles: Map<String, ApplicationRole> = emptyMap(), httpRoles: Map<String, HttpRole> = emptyMap(),
    ): PlacementResult {
        val input = NamingPlacementScope.prepare(scope, bindings, policy, registeredModules, requiredRoles, applicationRoles, httpRoles)
        if (input.problems.isNotEmpty()) return PlacementResult(problems = input.problems)
        val violations = mutableListOf<PlacementViolation>()
        input.bindings.values.forEach { b ->
            val type = input.classes.getValue(b.type)
            val module = input.modules.getValue(b.type)
            val location = policy.folders.single { it.id == b.location }
            fun compare(item: String, actual: String, expected: String, valid: Boolean = actual == expected) {
                if (!valid) violations += PlacementViolation(b.type, item, actual, expected, module, b.role.name,
                    type.source.orElse(null)?.fileName?.orElse(null))
            }
            val suffix = b.role.suffix
            val expected = b.exactName ?: ((b.technology ?: "") + "대상+" + (suffix ?: "검토한 이름"))
            val prefix = b.technology.orEmpty()
            val nameValid = (b.exactName == null || type.simpleName == b.exactName) &&
                (suffix == null || type.simpleName.endsWith(suffix) && type.simpleName.startsWith(prefix) && type.simpleName.length > prefix.length + suffix.length)
            if (suffix != null || b.exactName != null) compare("name", type.simpleName, expected, nameValid)
            compare("package", type.packageName, location.packageName)
            compare("module", module, location.module)
        }
        return PlacementResult(violations = violations.distinct().sortedWith(compareBy({ it.subject }, { it.item }, { it.actual })),
            evaluatedTypes = input.classes.keys.toSortedSet(), generatedOwners = input.generatedOwners.toSortedMap())
    }
}

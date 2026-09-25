package com.exchange.architecture.support

/**
 * @param discoveredModules Gradle에서 찾은 실제 JVM 모듈. 아래 등록 목록과 별도로 수집한다.
 * @param productionModules 운영 코드를 검사할 모듈로 등록한 목록.
 * @param nonProductionModules 테스트·벤치마크용으로 등록한 목록.
 */
data class GradleModuleInventory(
    val discoveredModules: Set<String>,
    val productionModules: Set<String>,
    val nonProductionModules: Set<String>,
)

object ModuleRegistration {
    fun inspect(inventory: GradleModuleInventory): List<ScopeProblem> {
        val registeredModules = inventory.productionModules + inventory.nonProductionModules
        val conflictingModules = inventory.productionModules intersect inventory.nonProductionModules
        val unregisteredModules = inventory.discoveredModules - registeredModules
        val missingModules = registeredModules - inventory.discoveredModules

        val problems = mutableListOf<ScopeProblem>()
        for (module in conflictingModules) {
            problems.add(ScopeProblem(ScopeProblemCode.CONFLICTING_MODULE_ROLE, module))
        }
        for (module in unregisteredModules) {
            problems.add(ScopeProblem(ScopeProblemCode.UNREGISTERED_MODULE, module))
        }
        for (module in missingModules) {
            problems.add(ScopeProblem(ScopeProblemCode.MISSING_MODULE, module))
        }
        return problems.sortedWith(compareBy({ it.code.name }, { it.subject }))
    }
}

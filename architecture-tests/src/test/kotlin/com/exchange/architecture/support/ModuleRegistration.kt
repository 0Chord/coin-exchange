package com.exchange.architecture.support

/**
 * Gradle에서 발견한 모듈과 사람이 등록한 검사 범위를 비교하기 위한 입력.
 *
 * @property discoveredModules 등록 목록과 별도로 찾은 실제 JVM 모듈 경로. 예: `:domain-order`.
 * @property productionModules 운영 클래스 수집 대상으로 등록한 모듈 경로.
 * @property nonProductionModules 검사 대상에서 제외하기로 등록한 테스트·벤치마크 모듈 경로.
 */
data class GradleModuleInventory(
    val discoveredModules: Set<String>,
    val productionModules: Set<String>,
    val nonProductionModules: Set<String>,
)

object ModuleRegistration {
    /**
     * 미등록·실제 부재·운영/비운영 중복 등록을 한 번에 확인한다.
     *
     * @return 오류 종류와 모듈 이름으로 정렬한 목록. 비어 있으면 등록이 일치한다.
     * 오류가 있을 때 다음 검사를 중단하는 책임은 호출자에게 있다.
     */
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

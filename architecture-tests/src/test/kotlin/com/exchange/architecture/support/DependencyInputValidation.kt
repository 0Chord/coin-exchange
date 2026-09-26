package com.exchange.architecture.support

/** ARCH-02와 ARCH-08이 같은 main 구성/프로젝트 누락 기준을 사용한다. */
object DependencyInputValidation {
    fun problems(snapshot: ProjectDependencySnapshot, inventory: GradleModuleInventory): List<String> {
        val problems = (snapshot.problems + ModuleRegistration.inspect(inventory).map { "${it.code}: ${it.subject}" }).toMutableList()
        if (inventory.productionModules.isEmpty()) problems += "EMPTY_SCOPE: 운영 모듈 등록이 없습니다"
        val expected = inventory.productionModules.flatMap { p -> listOf(p to "compile", p to "runtime") }.toSet()
        val groups = snapshot.configurations.groupBy { it.projectPath to it.usage }
        (expected - groups.keys).forEach { problems += "MISSING_CONFIGURATION: ${it.first} ${it.second}" }
        (groups.keys - expected).forEach { problems += "UNKNOWN_CONFIGURATION: ${it.first} ${it.second}" }
        groups.filterValues { it.size != 1 }.keys.forEach { problems += "DUPLICATE_CONFIGURATION: ${it.first} ${it.second}" }
        snapshot.configurations.groupBy { it.projectPath }.forEach { (path, records) ->
            if (records.map { it.buildFile }.distinct().size != 1) problems += "CONFLICTING_BUILD_FILE: $path"
        }
        snapshot.configurations.forEach { c ->
            if (c.configuration.isBlank() || c.buildFile.isBlank()) problems += "INVALID_CONFIGURATION: ${c.projectPath} ${c.usage}"
            if (c.dependencies.distinct().size != c.dependencies.size) problems += "DUPLICATE_DECLARATION: ${c.projectPath} ${c.usage}"
            c.dependencies.forEach { d ->
                if (d.targetPath !in inventory.discoveredModules) problems += "UNKNOWN_DEPENDENCY_PROJECT: ${d.targetPath}"
                if (d.declaredIn.isBlank()) problems += "MISSING_DECLARATION_CONFIGURATION: ${c.projectPath} → ${d.targetPath}"
            }
        }
        return problems.distinct().sorted()
    }
}

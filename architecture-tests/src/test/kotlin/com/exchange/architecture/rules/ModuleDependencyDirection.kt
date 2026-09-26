package com.exchange.architecture.rules

import com.exchange.architecture.support.*

/** 참조 근거와 허용 방향을 함께 남긴다. 소스 행을 알 수 없으면 null이다. */
data class ModuleDependencyViolation(
    val originModule: String,
    val targetModule: String,
    val evidence: String,
    val description: String,
    val allowedTargets: Set<String>,
    val originType: String? = null,
    val targetType: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
) {
    val ruleId = "ARCH-02"
    val specification = "engineering/architecture-check-spec.md"

    fun report(): String = "$ruleId | $evidence | $originModule → $targetModule | " +
        "${originType.orEmpty()} → ${targetType.orEmpty()} | $description | 허용 대상: ${allowedTargets.sorted()} | " +
        "${sourceFile ?: "파일 정보 없음"}:${lineNumber ?: "행 정보 없음"} | $specification"
}

/** 준비 오류가 있으면 부분 입력을 통과/위반 판정에 사용하지 않는다. */
data class ModuleDirectionResult(
    val problems: List<String> = emptyList(),
    val violations: List<ModuleDependencyViolation> = emptyList(),
) {
    val evaluated: Boolean get() = problems.isEmpty()
}

object ModuleDependencyDirection {
    val allowedTargets = mapOf(
        "domain-common" to emptySet<String>(),
        "domain-fee" to setOf("domain-common"),
        "domain-order" to setOf("domain-common", "domain-fee"),
        "domain-ledger" to setOf("domain-common"),
        "domain-matching" to setOf("domain-common", "domain-order"),
        "app-api" to setOf("domain-common", "domain-fee", "domain-order", "domain-ledger", "domain-matching"),
    )

    /**
     * 실제 출력 소속을 기준으로 직접 참조만 검사한다. 포트·실행기를 역할로 제외하지 않는다.
     * 수집 또는 정책 오류가 있으면 부분 결과를 준수 판정에 사용하지 않는다.
     */
    fun inspectBytecode(
        scope: ScopeImportResult,
        policy: Map<String, Set<String>> = allowedTargets,
        projectPackagePrefixes: Set<String> = setOf("com.exchange.core.", "com.exchange.architecture."),
    ): ModuleDirectionResult {
        val problems = scope.problems.map { "${it.code}: ${it.subject} ${it.detail.orEmpty()}" }.toMutableList()
        val modules = scope.classesByModule
        if (modules.isEmpty()) problems += "EMPTY_SCOPE: 운영 클래스가 없습니다"
        problems += policyProblems(modules.keys, policy)
        modules.filterValues { it.isEmpty() }.keys.forEach { problems += "EMPTY_MODULE: $it" }
        val definitions = modules.flatMap { (module, classes) -> classes.map { it.name to module } }
        definitions.groupBy { it.first }.filterValues { it.size > 1 }.keys.forEach {
            problems += "AMBIGUOUS_OWNERSHIP: $it"
        }
        if (problems.isNotEmpty()) return ModuleDirectionResult(problems.distinct().sorted())
        val owners = definitions.toMap()
        val violations = mutableListOf<ModuleDependencyViolation>()
        modules.forEach { (module, classes) -> classes.forEach { origin ->
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                val targetModule = owners[target]
                if (targetModule == null) {
                    if (projectPackagePrefixes.any { target.startsWith(it) }) {
                        problems += "UNRESOLVED_PROJECT_TYPE: ${origin.name} → $target"
                    }
                } else if (module != targetModule && targetModule !in policy.getValue(module)) {
                    violations += ModuleDependencyViolation(
                        module, targetModule, "BYTECODE", dependency.description, policy.getValue(module),
                        origin.name, target, origin.source.flatMap { it.fileName }.orElse(null),
                        dependency.sourceCodeLocation.lineNumber.takeIf { it > 0 },
                    )
                }
            }
        } }
        return if (problems.isNotEmpty()) ModuleDirectionResult(problems.distinct().sorted())
        else ModuleDirectionResult(violations = sortedViolations(violations))
    }

    /** main 구성별 직접 선언을 검증한다. 전이 의존은 입력에 펼치지 않으며 비운영 목적지는 ARCH-08에 남긴다. */
    fun inspectGradle(
        snapshot: ProjectDependencySnapshot,
        inventory: GradleModuleInventory,
        policy: Map<String, Set<String>> = allowedTargets,
    ): ModuleDirectionResult {
        val problems = (snapshot.problems + ModuleRegistration.inspect(inventory).map { "${it.code}: ${it.subject}" }).toMutableList()
        val modules = inventory.productionModules.map { it.removePrefix(":") }.toSet()
        if (modules.isEmpty()) problems += "EMPTY_SCOPE: 운영 모듈 등록이 없습니다"
        problems += policyProblems(modules, policy)
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
        if (problems.isNotEmpty()) return ModuleDirectionResult(problems.distinct().sorted())
        // 한 선언이 compile/runtime에 함께 보여도 한 위반에 양쪽 근거를 보존한다.
        val declarations = snapshot.configurations.flatMap { c -> c.dependencies.map { c to it } }
            .filter { (_, d) -> d.targetPath in inventory.productionModules }
            .groupBy { (c, d) -> listOf(c.projectPath, d.targetPath, d.declaredIn, c.buildFile) }
        val violations = declarations.mapNotNull { (key, occurrences) ->
            val (source, target, declaredIn, buildFile) = key
            val originModule = source.removePrefix(":")
            val targetModule = target.removePrefix(":")
            if (originModule == targetModule || targetModule in policy.getValue(originModule)) null
            else ModuleDependencyViolation(
                originModule, targetModule, "GRADLE",
                "$source → $target | 선언: $declaredIn | main: ${occurrences.map { (c, _) -> "${c.usage}/${c.configuration}" }.distinct().sorted().joinToString()}",
                policy.getValue(originModule), sourceFile = buildFile,
            )
        }
        return ModuleDirectionResult(violations = sortedViolations(violations))
    }

    /** 두 입력 중 어느 하나라도 불완전하면 ARCH-02 전체를 미평가로 남긴다. */
    fun inspect(
        scope: ScopeImportResult,
        snapshot: ProjectDependencySnapshot,
        inventory: GradleModuleInventory,
        policy: Map<String, Set<String>> = allowedTargets,
    ): ModuleDirectionResult {
        val bytecode = inspectBytecode(scope, policy)
        val gradle = inspectGradle(snapshot, inventory, policy)
        val problems = (bytecode.problems + gradle.problems).distinct().sorted()
        return if (problems.isNotEmpty()) ModuleDirectionResult(problems)
        else ModuleDirectionResult(violations = sortedViolations(bytecode.violations + gradle.violations))
    }

    private fun policyProblems(modules: Set<String>, policy: Map<String, Set<String>>): List<String> = buildList {
        (modules - policy.keys).forEach { add("MISSING_POLICY: $it") }
        (policy.keys - modules).forEach { add("UNKNOWN_POLICY_MODULE: $it") }
        policy.forEach { (origin, targets) -> (targets - modules).forEach { add("UNKNOWN_POLICY_TARGET: $origin → $it") } }
    }.sorted()

    private fun sortedViolations(violations: List<ModuleDependencyViolation>) = violations.distinct().sortedWith(compareBy(
        { it.evidence }, { it.originModule }, { it.targetModule }, { it.originType }, { it.targetType },
        { it.sourceFile }, { it.lineNumber }, { it.description },
    ))
}

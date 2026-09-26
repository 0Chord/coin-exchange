package com.exchange.architecture.rules

import com.exchange.architecture.support.*

data class IsolationViolation(
    val evidence: String, val origin: String, val target: String, val reason: String,
    val description: String, val sourceFile: String? = null, val line: Int? = null,
) {
    fun report() = "ARCH-08 | $evidence | $origin → $target | $reason | $description | ${sourceFile ?: "파일 정보 없음"}:${line ?: "행 정보 없음"} | engineering/architecture-check-spec.md"
}
data class IsolationResult(val problems: List<String> = emptyList(), val violations: List<IsolationViolation> = emptyList()) {
    val evaluated: Boolean get() = problems.isEmpty()
}

object ProductionDependencyIsolation {
    /** 비운영 타입은 목적지로만 사용한다. 준비 오류가 하나라도 있으면 위반 목록을 판정에 쓰지 않는다. */
    fun inspectBytecode(scope: ScopeImportResult, index: NonProductionIndex): IsolationResult {
        val problems = (scope.problems.map { "${it.code}: ${it.subject} ${it.detail.orEmpty()}" } + index.problems).toMutableList()
        val modules = scope.classesByModule
        if (modules.isEmpty()) problems += "EMPTY_SCOPE"
        modules.filterValues { it.isEmpty() }.keys.forEach { problems += "EMPTY_MODULE: $it" }
        val definitions = modules.values.flatMap { classes -> classes.map { it.name } }
        definitions.groupingBy { it }.eachCount().filter { (name, count) -> count > 1 || name in index.knownTypes }.keys.forEach {
            problems += "AMBIGUOUS_OWNERSHIP: $it"
        }
        val known = definitions.toSet() + index.knownTypes
        val violations = mutableListOf<IsolationViolation>()
        modules.forEach { (module, classes) -> classes.forEach { origin ->
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                if ((target.startsWith("com.exchange.core.") || target.startsWith("com.exchange.architecture.")) && target !in known) {
                    problems += "UNRESOLVED_PROJECT_TYPE: ${origin.name} → $target"
                }
                val owner = index.owners[target]
                val reason = if (owner != null) "비운영 출력 ${owner.projectPath}/${owner.sourceSet}"
                    else TestToolPolicy.type(target)?.let { "외부 테스트 도구 $it" }
                if (reason != null) violations += IsolationViolation(
                    "BYTECODE", "$module/${origin.name}", target, reason, dependency.description,
                    origin.source.flatMap { it.fileName }.orElse(null), dependency.sourceCodeLocation.lineNumber.takeIf { it > 0 },
                )
            }
        } }
        return finish(problems, violations)
    }

    fun inspectGradle(snapshot: IsolationDependencySnapshot, inventory: GradleModuleInventory): IsolationResult {
        val projectRecords = snapshot.configurations.map { c -> MainProjectDependencies(c.projectPath, c.usage, c.configuration, c.buildFile,
            c.dependencies.filter { it.kind == "project" }.map { ProjectDeclaration(it.target, it.declaredIn) }.distinct()) }
        val problems = DependencyInputValidation.problems(ProjectDependencySnapshot(projectRecords, snapshot.problems), inventory).toMutableList()
        snapshot.configurations.forEach { c ->
            if (c.dependencies.size != c.dependencies.distinct().size) problems += "DUPLICATE_DECLARATION: ${c.projectPath}/${c.usage}"
            c.dependencies.forEach { d ->
                if (d.kind !in setOf("project", "external") || d.declaredIn.isBlank()) problems += "INVALID_DECLARATION: $d"
                if (d.kind == "external" && (d.target.split(':').size != 2 || d.target.split(':').any { it.isBlank() })) problems += "INVALID_COORDINATES: ${d.target}"
                if (d.selection !in setOf("main", "test-fixtures")) problems += "UNSUPPORTED_SELECTION: ${c.projectPath} → ${d.target} ${d.details}"
                if (d.selection == "test-fixtures" && d.category in setOf("platform", "enforced-platform")) problems += "CONFLICTING_SELECTION: $d"
            }
        }
        if (problems.isNotEmpty()) return finish(problems, emptyList())
        val violations = snapshot.configurations.flatMap { c -> c.dependencies.map { c to it } }
            .groupBy { (c, d) -> Triple(c.projectPath, c.buildFile, d) }.mapNotNull { (key, occurrences) ->
                val (path, file, d) = key
                val reason = when {
                    d.selection == "test-fixtures" -> "테스트 fixture 선택"
                    d.category in setOf("platform", "enforced-platform") -> null
                    d.kind == "project" && d.target in inventory.nonProductionModules -> "비운영 프로젝트"
                    d.kind == "external" -> TestToolPolicy.module(d.target.substringBefore(':'), d.target.substringAfter(':'))?.let { "외부 테스트 도구 $it" }
                    else -> null
                }
                reason?.let { IsolationViolation("GRADLE", path, d.target, it,
                    "선언: ${d.declaredIn} | main: ${occurrences.map { (c, _) -> "${c.usage}/${c.configuration}" }.distinct().sorted().joinToString()} | ${d.details}", file) }
            }
        return finish(problems, violations)
    }

    /** 코드나 Gradle 중 하나라도 준비되지 않았으면 전체를 미평가로 반환한다. */
    fun inspect(scope: ScopeImportResult, index: NonProductionIndex, snapshot: IsolationDependencySnapshot, inventory: GradleModuleInventory): IsolationResult {
        val code = inspectBytecode(scope, index)
        val gradle = inspectGradle(snapshot, inventory)
        val modulesMatch = scope.classesByModule.keys == inventory.productionModules.map { it.removePrefix(":") }.toSet()
        val problems = code.problems + gradle.problems + if (modulesMatch) emptyList() else listOf("SCOPE_REGISTRATION_MISMATCH")
        return finish(problems, code.violations + gradle.violations)
    }

    private fun finish(problems: List<String>, violations: List<IsolationViolation>): IsolationResult =
        if (problems.isNotEmpty()) IsolationResult(problems.distinct().sorted())
        else IsolationResult(violations = violations.distinct().sortedBy { it.report() })
}

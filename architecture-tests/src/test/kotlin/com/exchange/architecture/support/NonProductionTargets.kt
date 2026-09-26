package com.exchange.architecture.support

import java.lang.classfile.ClassFile
import java.nio.file.Files
import java.nio.file.Path

data class SourceSetKey(val projectPath: String, val sourceSet: String)
data class SourceSetOutput(val key: SourceSetKey, val roots: List<Path>)
data class NonProductionOwner(val projectPath: String, val sourceSet: String, val file: Path)
data class OutputStatus(val key: SourceSetKey, val path: Path, val state: String)

/** 준비 오류가 있는 목록은 내부 참조의 소속 확인에 사용하지 않는다. */
data class NonProductionIndex(
    val owners: Map<String, NonProductionOwner> = emptyMap(),
    val outputs: List<OutputStatus> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    val knownTypes: Set<String> get() = if (problems.isEmpty()) owners.keys else emptySet()
}

object NonProductionTargets {
    /** 미생성 테스트 출력은 허용하고, 누락된 출력 기록과 읽지 못한 정의는 준비 실패로 구분한다. */
    fun inspect(
        outputs: List<SourceSetOutput>,
        expected: Set<SourceSetKey>,
        inventory: GradleModuleInventory,
    ): NonProductionIndex {
        val problems = ModuleRegistration.inspect(inventory).map { "${it.code}: ${it.subject}" }.toMutableList()
        if (expected.isEmpty()) problems += "EMPTY_SOURCE_SET_INVENTORY"
        inventory.discoveredModules.forEach {
            if (SourceSetKey(it, "main") !in expected) problems += "MISSING_MAIN_SOURCE_SET: $it"
        }
        expected.forEach {
            if (it.projectPath !in inventory.discoveredModules || it.sourceSet.isBlank()) problems += "UNKNOWN_SOURCE_SET: $it"
        }
        val groups = outputs.groupBy { it.key }
        (expected - groups.keys).forEach { problems += "MISSING_SOURCE_SET: ${it.projectPath}/${it.sourceSet}" }
        (groups.keys - expected).forEach { problems += "UNKNOWN_SOURCE_SET: $it" }
        groups.filterValues { it.size != 1 }.keys.forEach { problems += "DUPLICATE_SOURCE_SET: $it" }
        outputs.filter { it.roots.isEmpty() }.forEach { problems += "MISSING_OUTPUT_PATH: ${it.key}" }
        if (problems.isNotEmpty()) return NonProductionIndex(problems = problems.distinct().sorted())

        val owners = linkedMapOf<String, NonProductionOwner>()
        val statuses = mutableListOf<OutputStatus>()
        outputs.filter { it.key.projectPath in inventory.nonProductionModules || it.key.sourceSet != "main" }.forEach { output ->
            val visited = mutableSetOf<Path>()
            output.roots.forEach { input ->
                try {
                    val root = canonical(input)
                    if (visited.add(root)) {
                        val files = classFiles(root)
                        files.forEach { file ->
                            val name = className(file)
                            val owner = NonProductionOwner(output.key.projectPath, output.key.sourceSet, file)
                            val previous = owners.putIfAbsent(name, owner)
                            if (previous != null && previous != owner) {
                                problems += "AMBIGUOUS_OWNERSHIP: $name ($previous / $owner)"
                            }
                        }
                        val state = when {
                            !Files.exists(root) -> "ABSENT"
                            files.isEmpty() -> "EMPTY"
                            else -> "PRESENT"
                        }
                        statuses += OutputStatus(output.key, root, state)
                    }
                } catch (error: Exception) {
                    problems += "READ_FAILURE: $input: ${error.message}"
                }
            }
        }
        return NonProductionIndex(
            owners.toSortedMap(),
            statuses.sortedWith(compareBy({ it.key.projectPath }, { it.key.sourceSet }, { it.path.toString() })),
            problems.distinct().sorted(),
        )
    }

    private fun classFiles(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        require(Files.isDirectory(root)) { "출력 경로가 폴더가 아닙니다" }
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().map { path ->
                path.toRealPath().also { require(it.startsWith(root)) { "출력 밖으로 연결된 클래스: $path" } }
            }.toList()
        }
    }

    private fun className(file: Path): String = try {
        ClassFile.of().parse(file).thisClass().asInternalName().replace('/', '.')
    } catch (error: Exception) {
        throw IllegalArgumentException("$file: ${error.message}", error)
    }

    private fun canonical(path: Path): Path = if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
}

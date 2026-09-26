package com.exchange.architecture.support

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.lang.classfile.ClassFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * 클래스 파일 자체에서 만든 목록을 ArchUnit의 읽기 결과와 대조한다.
 * 읽기 도구의 결과만 정답으로 삼으면 그 도구가 누락한 클래스를 발견할 수 없기 때문이다.
 */
class ProductionScopeImporter(
    private val reader: BytecodeReader = BytecodeReader { ClassFileImporter().importPaths(it) },
) {
    /**
     * 운영 출력에서 가능한 오류를 모아 반환한다. 첫 오류만으로 즉시 중단하지 않는다.
     *
     * @param outputs 모듈별 운영 컴파일 출력 폴더.
     * @param expectations 필수 타입·역할과 금지 경로·이름 범위.
     * @param nonProduction 소속 확인을 마친 비운영 목적지. 운영 목록에 섞지 않으며 미확인 내부 타입은 계속 거절한다.
     * @return 모듈별 클래스와 정렬된 오류 목록. 오류가 있으면 부분 결과로 준수를 판정하지 않는다.
     * @throws java.io.IOException 경로 정규화 중 실제 경로를 확인하지 못한 경우.
     * 폴더 탐색·클래스 해석·읽기 도구 호출에서 잡은 예외는 결과의 읽기 오류로 남긴다.
     */
    fun load(
        outputs: List<ModuleOutput>,
        expectations: ScopeExpectations,
        nonProduction: NonProductionIndex = NonProductionIndex(),
    ): ScopeImportResult {
        val problems = nonProduction.problems.map { ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, "non-production", it) }.toMutableList()
        val modules = outputs.groupBy { it.module }
        val expectedModules = expectations.requiredTypesByModule.keys
        if (outputs.isEmpty()) problems += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "production")
        (expectedModules - modules.keys).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_MODULE, it) }
        (modules.keys - expectedModules).forEach { problems += ScopeProblem(ScopeProblemCode.UNREGISTERED_MODULE, it) }

        val forbidden = expectations.forbiddenRoots.map { canonical(it) }
        val inventory = modules.mapValues { (module, entries) ->
            val files = linkedSetOf<Path>()
            entries.flatMap { it.roots }.distinctBy { canonical(it) }.forEach { inputRoot ->
                val root = canonical(inputRoot)
                // 테스트·벤치마크 폴더를 품은 상위 폴더도 오염된 입력이므로 양방향 포함 관계를 막는다.
                if (forbidden.any { root.startsWith(it) || it.startsWith(root) }) {
                    problems += ScopeProblem(ScopeProblemCode.FORBIDDEN_OUTPUT, inputRoot.toString())
                } else {
                    try {
                        require(Files.isDirectory(root)) { "Compiler output is not a directory" }
                        Files.walk(root).use { paths ->
                            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                                .forEach { path ->
                                    val file = path.toRealPath()
                                    if (!file.startsWith(root) || forbidden.any { file.startsWith(it) }) {
                                        problems += ScopeProblem(ScopeProblemCode.FORBIDDEN_OUTPUT, file.toString())
                                    } else files.add(file)
                                }
                        }
                    } catch (error: Exception) {
                        problems += ScopeProblem(ScopeProblemCode.READ_FAILURE, root.toString(), error.toString())
                    }
                }
            }
            if (files.isEmpty()) problems += ScopeProblem(ScopeProblemCode.EMPTY_MODULE, module)
            files.mapNotNull { file ->
                try {
                    file to ClassFile.of().parse(file).thisClass().asInternalName().replace('/', '.')
                } catch (error: Exception) {
                    problems += ScopeProblem(ScopeProblemCode.READ_FAILURE, file.toString(), error.toString())
                    null
                }
            }.toMap()
        }

        val definitions = inventory.flatMap { (module, files) -> files.map { (path, name) -> Triple(name, path, module) } }
        definitions.groupBy { it.first }.forEach { (name, entries) ->
            if (name in nonProduction.knownTypes) problems += ScopeProblem(ScopeProblemCode.AMBIGUOUS_OWNERSHIP, name, "운영/비운영 출력에 중복 정의")
            if (entries.map { it.second }.distinct().size > 1) problems += ScopeProblem(ScopeProblemCode.DUPLICATE_TYPE, name)
            if (entries.map { it.third }.distinct().size > 1 && entries.map { it.second }.distinct().size == 1) {
                problems += ScopeProblem(ScopeProblemCode.AMBIGUOUS_OWNERSHIP, name)
            }
            if (expectations.forbiddenTypePrefixes.any { name.startsWith(it) }) {
                problems += ScopeProblem(ScopeProblemCode.FORBIDDEN_OUTPUT, name)
            }
        }

        val files = definitions.map { it.second }.distinct().sorted()
        val classes = if (files.isEmpty()) null else try {
            reader.read(files)
        } catch (error: Exception) {
            problems += ScopeProblem(ScopeProblemCode.READ_FAILURE, "bytecode-reader", error.toString())
            null
        }
        val observed = classes?.map { it.name }?.toSet().orEmpty()
        val expected = definitions.map { it.first }.toSet()
        (expected - observed).forEach { problems += ScopeProblem(ScopeProblemCode.INCOMPLETE_IMPORT, it) }
        (observed - expected).forEach { problems += ScopeProblem(ScopeProblemCode.UNEXPECTED_IMPORTED_TYPE, it) }

        val byModule = linkedMapOf<String, JavaClasses>()
        if (classes != null) inventory.forEach { (module, definitionsInModule) ->
            val names = definitionsInModule.values.toSet()
            byModule[module] = classes.that(DescribedPredicate.describe("belongs to $module") { it.name in names })
        }
        expectations.requiredTypesByModule.forEach { (module, required) ->
            val actual = byModule[module]?.map { it.name }?.toSet().orEmpty()
            (required - actual).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_REQUIRED_TYPE, it) }
        }
        expectations.requiredRoles.forEach { (role, names) ->
            if (names.isEmpty()) problems += ScopeProblem(ScopeProblemCode.EMPTY_ROLE, role)
            (names - observed).forEach { problems += ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, it) }
        }
        classes?.forEach { origin ->
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.baseComponentType.name
                if (expectations.projectPackagePrefixes.any { target.startsWith(it) } && target !in expected && target !in nonProduction.knownTypes) {
                    problems += ScopeProblem(ScopeProblemCode.UNRESOLVED_PROJECT_TYPE, target, "Referenced by ${origin.name}")
                }
            }
        }
        return ScopeImportResult(byModule, problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
    }

    private fun canonical(path: Path): Path = if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
}

package com.exchange.architecture.support

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.lang.classfile.ClassFile
import java.nio.file.Files
import java.nio.file.Path

/** Inventory comes from class-file bytes, independently of ArchUnit's dependency resolver. */
class ProductionScopeImporter(
    private val reader: BytecodeReader = BytecodeReader { ClassFileImporter().importPaths(it) },
) {
    fun load(outputs: List<ModuleOutput>, expectations: ScopeExpectations): ScopeImportResult {
        val problems = mutableListOf<ScopeProblem>()
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
                // A parent output directory can contain test/JMH children; reject both overlap directions.
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
                if (expectations.projectPackagePrefixes.any { target.startsWith(it) } && target !in expected) {
                    problems += ScopeProblem(ScopeProblemCode.UNRESOLVED_PROJECT_TYPE, target, "Referenced by ${origin.name}")
                }
            }
        }
        return ScopeImportResult(byModule, problems.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
    }

    private fun canonical(path: Path): Path = if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
}

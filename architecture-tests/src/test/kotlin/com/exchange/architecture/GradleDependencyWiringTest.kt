package com.exchange.architecture

import com.exchange.architecture.rules.ModuleDependencyDirection
import com.exchange.architecture.support.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 실제 적용하는 Gradle 스크립트를 최소 프로젝트에서도 실행한다. 의존 목록을 모조로 만들지 않는다. */
class GradleDependencyWiringTest {
    @TempDir lateinit var root: Path
    private val inventory = GradleModuleInventory(
        setOf(":common", ":fee", ":order", ":matching", ":test-only"),
        setOf(":common", ":fee", ":order", ":matching"), setOf(":test-only"),
    )
    private val policy = mapOf("common" to emptySet<String>(), "fee" to setOf("common"), "order" to setOf("common", "fee"), "matching" to setOf("common", "order"))

    @Test
    fun `실제 main 구성은 전이와 테스트 의존을 제외하고 상속된 직접 선언을 전달한다`() {
        prepare()
        val first = run()
        assertEquals(TaskOutcome.SUCCESS, first.task(":snapshot")?.outcome)
        val initial = read()
        assertEquals(8, initial.configurations.size)
        val matching = initial.configurations.filter { it.projectPath == ":matching" }
        assertEquals(2, matching.size)
        assertTrue(matching.all { c -> c.dependencies == listOf(ProjectDeclaration(":order", "api")) })
        assertTrue(initial.configurations.filter { it.projectPath == ":common" }.all { it.dependencies.isEmpty() })
        val initialResult = ModuleDependencyDirection.inspectGradle(initial, inventory, policy)
        assertTrue(initialResult.evaluated, initialResult.problems.toString())
        assertTrue(initialResult.violations.isEmpty())
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)

        Files.writeString(root.resolve("matching/build.gradle.kts"), """
            plugins { `java-library` }
            val extraMain by configurations.creating
            configurations.named("compileClasspath") { extendsFrom(extraMain) }
            dependencies {
                api(project(":order"))
                compileOnly(project(":fee"))
                runtimeOnly(project(":fee"))
                add(extraMain.name, project(":fee"))
                testImplementation(project(":test-only"))
            }
        """.trimIndent())
        val changed = run()
        assertEquals(TaskOutcome.SUCCESS, changed.task(":snapshot")?.outcome, "선언 변경이 입력 변경으로 추적되어야 한다")
        val snapshot = read()
        val compile = snapshot.configurations.single { it.projectPath == ":matching" && it.usage == "compile" }
        val runtime = snapshot.configurations.single { it.projectPath == ":matching" && it.usage == "runtime" }
        assertTrue(ProjectDeclaration(":fee", "compileOnly") in compile.dependencies)
        assertTrue(ProjectDeclaration(":fee", "extraMain") in compile.dependencies)
        assertFalse(runtime.dependencies.any { it.declaredIn in setOf("compileOnly", "extraMain") })
        assertTrue(ProjectDeclaration(":fee", "runtimeOnly") in runtime.dependencies)
        assertTrue(snapshot.configurations.none { c -> c.dependencies.any { it.targetPath == ":test-only" } })
        val result = ModuleDependencyDirection.inspectGradle(snapshot, inventory, policy)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(3, result.violations.size)
        assertTrue(result.violations.all { it.originModule == "matching" && it.targetModule == "fee" && it.evidence == "GRADLE" })
    }

    @Test
    fun `프로젝트 경로가 중첩되면 마지막 이름으로 소속을 합치지 않는다`() {
        prepare()
        Files.writeString(root.resolve("settings.gradle.kts"), "\ninclude(\":nested:fee\")\n", java.nio.file.StandardOpenOption.APPEND)
        Files.createDirectories(root.resolve("nested/fee"))
        Files.writeString(root.resolve("nested/fee/build.gradle.kts"), "plugins { `java-library` }")
        Files.writeString(root.resolve("matching/build.gradle.kts"), "plugins { `java-library` }; dependencies { implementation(project(\":nested:fee\")) }")
        run()
        val snapshot = read()
        assertTrue(snapshot.configurations.any { c -> c.dependencies.any { it.targetPath == ":nested:fee" } })
        val actual = ModuleDependencyDirection.inspectGradle(snapshot, inventory, policy)
        assertFalse(actual.evaluated)
        assertTrue(actual.problems.any { it.contains("UNKNOWN_DEPENDENCY_PROJECT: :nested:fee") })
    }

    @Test
    fun `지연 runtimeOnly 선언은 해석 후 입력에 반영되어 금지 방향으로 보고된다`() {
        prepareDeferredRuntime()
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        val before = read()
        assertEquals(8, before.configurations.size)
        assertTrue(before.configurations.filter { it.projectPath == ":matching" }
            .all { it.dependencies == listOf(ProjectDeclaration(":order", "api")) })

        // 해석 전 목록을 준수 판정의 정답으로 쓰지 않는다. 실제 Gradle 해석을 거쳐 같은 입력을 다시 읽는다.
        val resolved = run("-PresolveMatchingRuntime=true")
        assertEquals(TaskOutcome.SUCCESS, resolved.task(":resolveMatchingRuntime")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, resolved.task(":snapshot")?.outcome,
            "지연 선언이 나타나면 이전 snapshot을 재사용해서는 안 된다")
        val after = read()
        assertEquals(8, after.configurations.size)
        assertEquals(before.configurations.filterNot { it.projectPath == ":matching" && it.usage == "runtime" },
            after.configurations.filterNot { it.projectPath == ":matching" && it.usage == "runtime" })
        val runtime = after.configurations.single { it.projectPath == ":matching" && it.usage == "runtime" }
        assertEquals(setOf(ProjectDeclaration(":order", "api"), ProjectDeclaration(":fee", "runtimeOnly")),
            runtime.dependencies.toSet())
        assertEquals(2, runtime.dependencies.size)

        val result = ModuleDependencyDirection.inspectGradle(after, inventory, policy)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(1, result.violations.size)
        val violation = result.violations.single()
        assertEquals("ARCH-02", violation.ruleId)
        assertEquals("GRADLE", violation.evidence)
        assertEquals("matching", violation.originModule)
        assertEquals("fee", violation.targetModule)
        assertEquals(setOf("common", "order"), violation.allowedTargets)
        assertEquals(":matching → :fee | 선언: runtimeOnly | main: runtime/runtimeClasspath", violation.description)
        assertEquals(root.resolve("matching/build.gradle.kts").toRealPath().toString(), violation.sourceFile)
        assertNull(violation.lineNumber)
        assertEquals(TaskOutcome.UP_TO_DATE, run("-PresolveMatchingRuntime=true").task(":snapshot")?.outcome)
    }

    @Test
    fun `명시한 runtimeOnly 의존이 있으면 지연 기본 의존을 추가하거나 위반으로 보고하지 않는다`() {
        prepareDeferredRuntime(explicitRuntime = true)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        val before = read()
        val runtime = before.configurations.single { it.projectPath == ":matching" && it.usage == "runtime" }
        assertEquals(setOf(ProjectDeclaration(":order", "api"), ProjectDeclaration(":common", "runtimeOnly")),
            runtime.dependencies.toSet())
        assertEquals(2, runtime.dependencies.size)

        val resolved = run("-PresolveMatchingRuntime=true")
        assertEquals(TaskOutcome.SUCCESS, resolved.task(":resolveMatchingRuntime")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, resolved.task(":snapshot")?.outcome)
        // snapshot 작업의 이전 출력만 비교하지 않고, 해석 직후 다시 수집한 별도 기록도 확인한다.
        val after = read("build/after-runtime-resolution.txt")
        assertEquals(before, after)
        val result = ModuleDependencyDirection.inspectGradle(after, inventory, policy)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    private fun prepareDeferredRuntime(explicitRuntime: Boolean = false) {
        prepare()
        Files.writeString(root.resolve("matching/build.gradle.kts"), """
            plugins { `java-library` }
            dependencies {
                api(project(":order"))
                ${if (explicitRuntime) "runtimeOnly(project(\":common\"))" else ""}
                testImplementation(project(":test-only"))
            }
            configurations.named("runtimeOnly") {
                defaultDependencies { add(project.dependencies.project(mapOf("path" to ":fee"))) }
            }
        """.trimIndent())
        Files.writeString(root.resolve("build.gradle.kts"), """

            val resolveMatchingRuntime = tasks.register("resolveMatchingRuntime") {
                doLast {
                    project(":matching").configurations.getByName("runtimeClasspath").incoming.resolutionResult.allDependencies
                    val current = tasks.named<Test>("test").get().inputs.properties.getValue("mainProjectDependencies") as String
                    layout.buildDirectory.file("after-runtime-resolution.txt").get().asFile.apply {
                        parentFile.mkdirs()
                        writeText(current)
                    }
                }
            }
            tasks.named("snapshot") {
                if (providers.gradleProperty("resolveMatchingRuntime").isPresent) dependsOn(resolveMatchingRuntime)
            }
        """.trimIndent(), java.nio.file.StandardOpenOption.APPEND)
    }

    private fun prepare() {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"dependency-contract\"\ninclude(\":common\", \":fee\", \":order\", \":matching\", \":test-only\")")
        inventory.discoveredModules.forEach { path ->
            val dir = root.resolve(path.removePrefix(":"))
            Files.createDirectories(dir)
            val deps = when (path) {
                ":fee" -> "api(project(\":common\"))"
                ":order" -> "api(project(\":fee\"))"
                ":matching" -> "api(project(\":order\")); testImplementation(project(\":test-only\"))"
                else -> ""
            }
            Files.writeString(dir.resolve("build.gradle.kts"), "plugins { `java-library` }; dependencies { $deps }")
        }
        Files.copy(Path.of(System.getProperty("architecture.dependencyScript")), root.resolve("project-dependencies.gradle.kts"))
        Files.writeString(root.resolve("build.gradle.kts"), """
            plugins { java }
            extra["architecture.productionProjects"] = listOf(":common", ":fee", ":order", ":matching")
            apply(from = "project-dependencies.gradle.kts")
            // 실제 Test 작업에 등록된 입력을 읽는다. 테스트 전용 수집기를 따로 만들지 않는다.
            val snapshot = providers.provider { tasks.named<Test>("test").get().inputs.properties.getValue("mainProjectDependencies") as String }
            tasks.register("snapshot") {
                inputs.property("declarations", snapshot)
                val output = layout.buildDirectory.file("dependencies.txt")
                outputs.file(output)
                doLast { output.get().asFile.apply { parentFile.mkdirs(); writeText(snapshot.get()) } }
            }
        """.trimIndent())
    }

    private fun run(vararg arguments: String) = GradleRunner.create().withProjectDir(root.toFile())
        .withGradleInstallation(File(System.getProperty("architecture.gradleHome")))
        .withTestKitDir(root.resolve(".test-kit").toFile())
        .withArguments(listOf("snapshot", "--offline", "--console=plain", "--max-workers=1", "--stacktrace") + arguments).build()

    private fun read(path: String = "build/dependencies.txt") = ProjectDependencies.read(Files.readString(root.resolve(path))).also {
        assertEquals(emptyList(), it.problems, "실제 Gradle 전달 형식을 읽을 수 있어야 한다")
    }
}

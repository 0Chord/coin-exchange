package com.exchange.architecture

import com.exchange.architecture.support.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Base64
import kotlin.test.*

class NamingPlacementGradleWiringTest {
    @TempDir lateinit var root: Path
    private val folder = AllowedFolder("order", "app-api", "src/main/java", "com/example/order", "예제 업무")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/java")), listOf(folder))
    private fun write(path: String, content: String): Path = root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, content) }
    private fun prepare(generated: Boolean = false) {
        write("settings.gradle.kts", "rootProject.name = \"source-contract\"\ninclude(\":app-api\")")
        write("app-api/build.gradle.kts", """
            plugins { java }
            sourceSets.create("jmh")
        """.trimIndent() + if (!generated) "" else """

            val generateSources = tasks.register("generateSources") {
                val destination = layout.buildDirectory.dir("generated/main")
                outputs.dir(destination)
                doLast {
                    destination.get().file("com/example/generated/Generated.java").asFile.apply {
                        parentFile.mkdirs(); writeText("package com.example.generated; public class Generated {}")
                    }
                }
            }
            sourceSets.named("main") { java.srcDir(generateSources) }
        """.trimIndent())
        write("app-api/src/main/java/com/example/order/Submit.java", "package com.example.order; public class Submit {}")
        write("app-api/src/test/java/com/example/OnlyTest.java", "package com.example; class OnlyTest {}")
        write("app-api/src/jmh/java/com/example/OnlyBenchmark.java", "package com.example; class OnlyBenchmark {}")
        write("app-api/src/main/resources/Ignore.kt", "not Kotlin source")
        val script = root.resolve("gradle/main-sources.gradle.kts"); Files.createDirectories(script.parent)
        Files.copy(Path.of(System.getProperty("architecture.mainSourcesScript")), script)
        write("src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt", "// 정책 입력 예제\n")
        write("build.gradle.kts", """
            plugins { java }
            extra["architecture.productionProjects"] = listOf(":app-api")
            apply(from = "gradle/main-sources.gradle.kts")
            val actualTest = tasks.named<Test>("test")
            val snapshot = providers.provider { actualTest.get().inputs.properties.getValue("mainSourceLayout") as String }
            tasks.register("snapshot") {
                dependsOn(":app-api:classes")
                inputs.property("layout", snapshot)
                inputs.files(providers.provider { actualTest.get().inputs.files })
                val output = layout.buildDirectory.file("sources.txt")
                outputs.file(output)
                doLast { output.get().asFile.apply { parentFile.mkdirs(); writeText(snapshot.get()) } }
            }
        """.trimIndent())
    }
    private fun run() = GradleRunner.create().withProjectDir(root.toFile())
        .withGradleInstallation(File(System.getProperty("architecture.gradleHome")))
        .withTestKitDir(root.resolve(".test-kit").toFile())
        .withArguments("snapshot", "--offline", "--console=plain", "--max-workers=1", "--stacktrace").build()
    private fun read() = MainSourceSnapshot.read(Files.readString(root.resolve("build/sources.txt")), setOf("app-api"))
        .also { assertEquals(emptyList(), it.problems) }

    @Test fun `NAME-23 main만 전달하며 파일 이동 추가 삭제와 정책 변경이 재실행을 일으킨다`() {
        prepare()
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        val before = read()
        assertEquals(setOf(root.resolve("app-api/src/main/java/com/example/order/Submit.java").toRealPath()), before.roots.flatMap { it.files }.toSet())
        assertEquals(emptyList(), SourcePlacement.inspect(before.roots, policy, setOf("app-api")).result.violations)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)
        val original = before.roots.flatMap { it.files }.single()
        val moved = original.parent.parent.resolve(original.fileName); Files.move(original, moved)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        val movedResult = SourcePlacement.inspect(read().roots, policy, setOf("app-api")).result
        assertTrue(movedResult.evaluated, movedResult.problems.toString())
        assertEquals(setOf("allowedFolder", "sourceFolder"), movedResult.violations.map { it.item }.toSet())
        Files.writeString(root.resolve("src/test/kotlin/com/exchange/architecture/policy/ProjectLayoutPolicy.kt"), "// 새 경로 정책\n", APPEND)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)
        Files.delete(moved)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertFalse(SourcePlacement.inspect(read().roots, policy, setOf("app-api")).result.evaluated)
        val added = write("app-api/src/main/java/com/example/order/Cancel.java", "package com.example.order; class Cancel {}")
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertEquals(setOf(added.toRealPath()), read().roots.flatMap { it.files }.toSet())
    }
    @Test fun `NAME-23 생성 main의 생산 작업을 전달하고 허용된 루트만 통과시킨다`() {
        prepare(generated = true)
        val build = run()
        assertEquals(TaskOutcome.SUCCESS, build.task(":app-api:generateSources")?.outcome)
        val snapshot = read()
        val generated = snapshot.roots.single { it.relativeRoot == "build/generated/main" }
        assertEquals(":app-api:generateSources", generated.producer)
        assertEquals(1, generated.files.size)
        assertTrue(SourcePlacement.inspect(snapshot.roots, policy, setOf("app-api")).result.violations.any { it.item == "sourceRoot" })
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "build/generated/main", ":app-api:generateSources"),
            folders = policy.folders + folder.copy(id = "generated", sourceRoot = "build/generated/main", folder = "com/example/generated"))
        val r = SourcePlacement.inspect(snapshot.roots, p, setOf("app-api")).result
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
    }
    @Test fun `NAME-23 이 프로젝트의 실제 Kotlin main 입력도 누락 없이 전달된다`() {
        val snapshot = MainSourceSnapshot.read(System.getProperty("architecture.mainSources"), ProductionScope.requiredTypes.keys)
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(ProductionScope.requiredTypes.keys, snapshot.roots.map { it.module }.toSet())
        val all = snapshot.roots.flatMap { it.files }
        assertTrue(all.any { it.fileName.toString() == "OrderSubmissionService.kt" })
        assertTrue(all.any { it.fileName.toString() == "Balance.kt" })
        assertTrue(all.none { it.toString().contains("/src/test/") || it.toString().contains("benchmark-jmh") })
        assertTrue(snapshot.roots.filter { it.relativeRoot == "src/main/kotlin" }.all { it.files.isNotEmpty() })
        // 수집 연결을 확인할 뿐 목표 ARCH-05 정책을 운영 코드에 활성화하지 않는다.
    }
    @Test fun `NAME-22 전달 모듈 루트 파일 행이 누락되거나 충돌하면 준비 오류다`() {
        fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") { Base64.getUrlEncoder().encodeToString(it.toByteArray()) }
        val m = row("M", "app-api", root.toString())
        val r = row("R", "app-api", "src/main/java", "")
        val f = row("F", "app-api", "src/main/java", root.resolve("A.java").toString())
        for (text in listOf(null, "", "ARCH05/1", "ARCH05/1\n$m", "ARCH05/1\n$m\n$m\n$r", "ARCH05/1\n$m\n$r\n$f\n$f", "ARCH05/1\n$m\n$f")) {
            val snapshot = MainSourceSnapshot.read(text, setOf("app-api"))
            assertTrue(snapshot.problems.isNotEmpty()); assertEquals(emptyList(), snapshot.roots)
        }
    }
}

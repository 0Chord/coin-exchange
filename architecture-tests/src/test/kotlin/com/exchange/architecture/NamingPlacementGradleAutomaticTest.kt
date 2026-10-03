package com.exchange.architecture

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NamingPlacementGradleAutomaticTest {
    @TempDir lateinit var root: Path
    private val policyDir = "src/test/kotlin/com/exchange/architecture/policy"
    private fun write(path: String, text: String) = root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
    private fun prepare() {
        write("settings.gradle.kts", "rootProject.name = \"automatic-naming\"\ninclude(\":app-api\")")
        write("app-api/build.gradle.kts", "plugins { java }")
        write("app-api/src/main/java/com/example/order/SubmitOrderUseCase.java", "package com.example.order; public class SubmitOrderUseCase {}")
        write("$policyDir/ProjectLayoutPolicy.kt", "const val allowedFolder = \"com/example/order\"")
        write("$policyDir/PortPlacementPolicy.kt", "const val reason = \"업무 진입점\"")
        write("runtime.txt", System.getProperty("architecture.testRuntime"))
        val script = root.resolve("gradle/main-sources.gradle.kts"); Files.createDirectories(script.parent)
        Files.copy(Path.of(System.getProperty("architecture.mainSourcesScript")), script)
        write("src/test/java/example/AutomaticNamingTest.java", """
            package example;
            import java.nio.file.Path;
            import org.junit.jupiter.api.Test;
            public class AutomaticNamingTest {
                @Test void checkEntireOutput() {
                    com.exchange.architecture.NamingPlacementGradleScenario.verify(
                        Path.of(System.getProperty("user.dir")), System.getProperty("architecture.mainSources"));
                }
            }
        """.trimIndent())
        write("build.gradle.kts", """
            import java.io.File
            plugins { java }
            extra["architecture.productionProjects"] = listOf(":app-api")
            apply(from = "gradle/main-sources.gradle.kts")
            dependencies { testImplementation(files(file("runtime.txt").readText().split(File.pathSeparator))) }
            tasks.withType<Test> { useJUnitPlatform() }
        """.trimIndent())
    }
    private fun run(fail: Boolean = false) = GradleRunner.create().withProjectDir(root.toFile())
        .withGradleInstallation(File(System.getProperty("architecture.gradleHome")))
        .withArguments("test", "--offline", "--console=plain", "--max-workers=1", "--stacktrace")
        .let { if (fail) it.buildAndFail() else it.build() }
    private fun report() = Files.readString(root.resolve("build/naming-result.txt"))

    @Test fun `AUTO-25 소스 추가만으로 실제 Test 재실행과 선택 대상 포함을 확인한다`() {
        prepare()
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":test")?.outcome)
        write("app-api/src/main/java/com/example/order/AmendOrderUseCase.java", "package com.example.order; public class AmendOrderUseCase {}")
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        assertTrue(report().contains("type=com.example.order.AmendOrderUseCase"))
        assertTrue(report().contains("rule=UseCase:targets=com.example.order.AmendOrderUseCase"))
        write("app-api/src/main/java/com/example/order/OrderManager.java", "package com.example.order; public class OrderManager {}")
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        assertFalse(report().contains("type=com.example.order.OrderManager"))
        write("app-api/src/main/java/com/example/order/UseCase.java", "package com.example.order; public class UseCase {}")
        assertEquals(TaskOutcome.FAILED, run(true).task(":test")?.outcome)
        assertTrue(report().contains("evaluated=true"))
        assertTrue(report().contains("violation=com.example.order.UseCase:name:대상+UseCase"))
    }
    @Test fun `AUTO-26 폴더 정책과 명명 정책만 바꿔도 실제 Test와 결과가 바뀐다`() {
        prepare()
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":test")?.outcome)
        write("$policyDir/ProjectLayoutPolicy.kt", "const val allowedFolder = \"com/example/other\"")
        assertEquals(TaskOutcome.FAILED, run(true).task(":test")?.outcome)
        assertTrue(report().contains("violation=com.example.order.SubmitOrderUseCase:package:com.example.other"))
        write("$policyDir/ProjectLayoutPolicy.kt", "const val allowedFolder = \"com/example/order\"")
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        write("$policyDir/PortPlacementPolicy.kt", "const val reason = \"변경된 정책 이유\"")
        assertEquals(TaskOutcome.SUCCESS, run().task(":test")?.outcome)
        assertTrue(report().contains("policyReason=변경된 정책 이유"))
        assertFalse(report().contains("violation="))
    }
}

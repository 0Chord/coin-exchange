package com.exchange.architecture

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureJvmInputsGradleTest {
    @TempDir lateinit var root: Path

    private fun write(
        name: String,
        text: String,
    ) = root.resolve(name).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    @Test fun `큰 검사 입력을 워커 클래스 경로를 덮지 않고 원문 그대로 전달한다`() {
        write("settings.gradle.kts", "rootProject.name = \"architecture-jvm-inputs\"")
        val payload = "한글 \"인용\" \\경로\t열\r\n다음 줄\n".repeat(8_000)
        assertTrue(payload.length > 131_072)
        write("expected.txt", payload)
        write("runtime.txt", System.getProperty("architecture.testRuntime"))
        val script = root.resolve("gradle/test-jvm-inputs.gradle.kts")
        Files.createDirectories(script.parent)
        Files.copy(Path.of(System.getProperty("architecture.jvmInputsScript")), script)
        write(
            "build.gradle.kts",
            """
            import java.io.File
            plugins { java }
            apply(from = "gradle/test-jvm-inputs.gradle.kts")
            dependencies { testImplementation(files(file("runtime.txt").readText().split(File.pathSeparator))) }
            tasks.withType<Test> {
                useJUnitPlatform()
                inputs.file("expected.txt")
                systemProperty("unrelated.input", "unchanged")
                doFirst { systemProperty("architecture.largeInput", file("expected.txt").readText()) }
            }
            """.trimIndent(),
        )
        write(
            "src/test/java/example/ArchitectureInputTest.java",
            """
            package example;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class ArchitectureInputTest {
                @Test void preservesInput() throws Exception {
                    assertEquals(Files.readString(Path.of("expected.txt")), System.getProperty("architecture.largeInput"));
                    assertEquals("unchanged", System.getProperty("unrelated.input"));
                }
            }
            """.trimIndent(),
        )
        val runner =
            GradleRunner
                .create()
                .withProjectDir(root.toFile())
                .withGradleInstallation(File(System.getProperty("architecture.gradleHome")))
                .withArguments(
                    "test",
                    "--offline",
                    "--console=plain",
                    "--max-workers=1",
                    "-Dorg.gradle.internal.cmdline.max.length=131072",
                    "--stacktrace",
                )
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":test")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, runner.build().task(":test")?.outcome)
        write("expected.txt", "변경된 검사 입력\n$payload")
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":test")?.outcome)
    }
}

package com.exchange.architecture

import com.exchange.architecture.rules.ProductionDependencyIsolation
import com.exchange.architecture.support.*
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.jar.JarFile
import kotlin.test.*

/** 실제 운영 Test에 적용하는 수집 스크립트와 입력 파일 집합을 최소 Gradle 프로젝트에서 확인한다. */
class IsolationGradleWiringTest {
    @TempDir lateinit var root: Path
    private val inventory = GradleModuleInventory(setOf(":app", ":lib", ":bench"), setOf(":app", ":lib"), setOf(":bench"))

    @Test fun `main 상속 compileOnly runtimeOnly와 테스트 전용 선언을 구분한다`() {
        prepare()
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertEquals(emptyList(), evaluate().violations)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)
        append("app/build.gradle", """
            configurations { inheritedTests }
            configurations.compileClasspath.extendsFrom(configurations.inheritedTests)
            dependencies {
                compileOnly 'org.junit.jupiter:junit-jupiter-api:1'
                runtimeOnly 'org.testcontainers:postgresql:1'
                inheritedTests project(':bench')
                implementation platform('org.junit.jupiter:example-bom:1')
                constraints { implementation 'org.openjdk.jmh:jmh-core:1' }
            }
        """)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        val result = evaluate()
        assertEquals(setOf("org.junit.jupiter:junit-jupiter-api", "org.testcontainers:postgresql", ":bench"), result.violations.map { it.target }.toSet())
        assertTrue(result.violations.single { it.target == "org.junit.jupiter:junit-jupiter-api" }.description.contains("compileOnly"))
        assertTrue(result.violations.single { it.target == "org.testcontainers:postgresql" }.description.contains("runtimeOnly"))
        assertTrue(result.violations.single { it.target == ":bench" }.description.contains("inheritedTests"))
        // 테스트 전용 구성도 main이 상속하는 순간 운영 의존이 된다.
        append("app/build.gradle", "configurations.runtimeClasspath.extendsFrom(configurations.testImplementation)")
        run()
        val inherited = evaluate()
        assertTrue(inherited.violations.any { it.target == "com.tngtech.archunit:archunit" })
        assertTrue(inherited.violations.any { it.target == ":lib" && it.reason == "테스트 fixture 선택" })
    }

    @Test fun `실제 project 외부 fixture 선택과 미지원 variant를 구분한다`() {
        prepare()
        append("app/build.gradle", """
            dependencies {
                implementation testFixtures(project(':lib'))
                runtimeOnly testFixtures('example:helper:1')
                compileOnly(project(':lib')) {
                    attributes { attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, project.objects.named(org.gradle.api.attributes.Usage, 'java-api')) }
                }
            }
        """)
        run()
        val result = evaluate()
        assertEquals(setOf(":lib", "example:helper"), result.violations.map { it.target }.toSet())
        assertTrue(result.violations.all { it.reason == "테스트 fixture 선택" })
        assertTrue(result.violations.single { it.target == ":lib" }.description.contains("lib-test-fixtures"))
        append("app/build.gradle", "dependencies { compileOnly project(path: ':lib', configuration: 'special') }")
        run()
        val unsupported = ProductionDependencyIsolation.inspectGradle(read(), inventory)
        assertFalse(unsupported.evaluated)
        assertEquals(emptyList(), unsupported.violations)
        assertTrue(unsupported.problems.any { it.contains("UNSUPPORTED_SELECTION") && it.contains("special") })
        val build = root.resolve("app/build.gradle")
        Files.writeString(build, Files.readString(build).replace("dependencies { compileOnly project(path: ':lib', configuration: 'special') }", ""))
        append("app/build.gradle", """
            dependencies {
                compileOnly(project(':lib')) {
                    attributes { attribute(org.gradle.api.attributes.Attribute.of('example.flavor', String), 'testing') }
                }
            }
        """)
        run()
        val customAttribute = ProductionDependencyIsolation.inspectGradle(read(), inventory)
        assertFalse(customAttribute.evaluated)
        assertEquals(emptyList(), customAttribute.violations)
        assertTrue(customAttribute.problems.any { it.contains("UNSUPPORTED_SELECTION") && it.contains("example.flavor=testing") })
    }

    @Test fun `소비자 runtime 속성으로 실제 테스트 jar를 선택하면 준비 실패다`() {
        prepare()
        Files.writeString(root.resolve("app/build.gradle"), """
            plugins { id 'java-library' }
            configurations.runtimeClasspath.attributes {
                attribute(Attribute.of('example.kind', String), 'testing')
            }
            dependencies { runtimeOnly project(':lib') }
        """.trimIndent())
        append("lib/build.gradle", """
            def kind = Attribute.of('example.kind', String)
            configurations.runtimeElements.attributes.attribute(kind, 'production')
            configurations { testElements { canBeConsumed = true; canBeResolved = false } }
            configurations.runtimeElements.attributes.keySet().each { key ->
                configurations.testElements.attributes.attribute(key, configurations.runtimeElements.attributes.getAttribute(key))
            }
            configurations.testElements.attributes.attribute(kind, 'testing')
            def testJar = tasks.register('testJar', Jar) { archiveClassifier = 'tests'; from sourceSets.test.output }
            artifacts { testElements(testJar) }
        """)
        val source = root.resolve("lib/src/test/java/example/Helper.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package example; public class Helper {}")
        append("build.gradle", """
            tasks.register('selectedRuntime') {
                dependsOn(':lib:testJar')
                doLast {
                    def artifacts = project(':app').configurations.runtimeClasspath.incoming.artifacts.artifacts
                    assert artifacts.size() == 1
                    def artifact = artifacts.iterator().next()
                    println('SELECTED_VARIANT=' + artifact.variant.displayName)
                    println('SELECTED_JAR=' + artifact.file.name)
                }
            }
        """)
        val execution = run("selectedRuntime")
        assertTrue(execution.output.contains("SELECTED_VARIANT=configuration ':lib:testElements'"))
        assertTrue(execution.output.contains("SELECTED_JAR=lib-1-tests.jar"))
        JarFile(root.resolve("lib/build/libs/lib-1-tests.jar").toFile()).use {
            assertNotNull(it.getJarEntry("example/Helper.class"))
        }
        val input = read()
        val runtime = input.configurations.single { it.projectPath == ":app" && it.usage == "runtime" }
        assertEquals("unsupported", runtime.dependencies.single().selection)
        val result = ProductionDependencyIsolation.inspectGradle(input, inventory)
        assertFalse(result.evaluated)
        assertEquals(emptyList(), result.violations)
        assertTrue(result.problems.any { it.contains("UNSUPPORTED_SELECTION: :app → :lib") && it.contains("example.kind=testing") })
    }

    @Test fun `소비자 compile 속성만 바뀌어도 재수집하고 runtime 정상 선택은 유지한다`() {
        prepare()
        append("app/build.gradle", """
            if (providers.gradleProperty('customCompile').isPresent()) {
                configurations.compileClasspath.attributes { attribute(Attribute.of('example.kind', String), 'testing') }
            }
        """)
        run()
        assertEquals(emptyList(), evaluate().violations)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)
        val changed = run("-PcustomCompile=true")
        val input = read()
        val declarations = input.configurations.filter { it.projectPath == ":app" }.associate { it.usage to it.dependencies.single() }
        assertEquals("unsupported", declarations.getValue("compile").selection)
        assertEquals("main", declarations.getValue("runtime").selection)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":snapshot")?.outcome)
        val result = ProductionDependencyIsolation.inspectGradle(input, inventory)
        assertFalse(result.evaluated)
        assertTrue(result.problems.single().contains("example.kind=testing"))
        assertEquals(emptyList(), result.violations)
        assertEquals(TaskOutcome.UP_TO_DATE, run("-PcustomCompile=true").task(":snapshot")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertEquals(emptyList(), evaluate().violations)
    }

    @Test fun `의존 속성이 소비자 속성을 덮어쓰면 실제 정상 variant 선택을 허용한다`() {
        prepare()
        Files.writeString(root.resolve("app/build.gradle"), """
            plugins { id 'java-library' }
            configurations.runtimeClasspath.attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage, 'test-runtime'))
            }
            dependencies {
                runtimeOnly(project(':lib')) {
                    attributes { attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, 'java-api')) }
                }
            }
        """.trimIndent())
        append("build.gradle", """
            tasks.register('selectedRuntime') {
                dependsOn(':lib:jar')
                doLast {
                    def artifacts = project(':app').configurations.runtimeClasspath.incoming.artifacts.artifacts
                    assert artifacts.size() == 1
                    def artifact = artifacts.iterator().next()
                    println('SELECTED_VARIANT=' + artifact.variant.displayName)
                }
            }
        """)
        assertTrue(run("selectedRuntime").output.contains("SELECTED_VARIANT=configuration ':lib:apiElements'"))
        val result = evaluate()
        assertEquals(emptyList(), result.violations)
        assertEquals("main", read().configurations.single { it.projectPath == ":app" && it.usage == "runtime" }.dependencies.single().selection)
    }

    @Test fun `소스셋과 출력 내용 생성 삭제를 작업 입력으로 추적하며 비운영 컴파일은 요구하지 않는다`() {
        prepare()
        val first = run()
        assertTrue(first.tasks.none { it.path.contains("compile") || it.path.endsWith(":test") })
        val before = targets()
        assertEquals(emptyList(), before.problems)
        assertTrue(before.outputs.all { it.state == "ABSENT" })
        append("lib/build.gradle", "sourceSets { qualityExamples }")
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertTrue(targets().outputs.any { it.key == SourceSetKey(":lib", "qualityExamples") && it.state == "ABSENT" })
        val output = root.resolve("lib/build/classes/java/qualityExamples")
        val file = output.resolve("com/exchange/core/isolationfixture/Helper.class")
        fixtureOutput(output, com.exchange.core.isolationfixture.Helper::class.java)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertEquals(1, targets().knownTypes.size)
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":snapshot")?.outcome)
        // 파일 수/이름을 그대로 두고 바이트만 바꿔도 재실행해야 한다.
        Files.write(file, byteArrayOf(1, 2, 3))
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertTrue(targets().problems.any { it.contains("READ_FAILURE") })
        Files.delete(file)
        assertEquals(TaskOutcome.SUCCESS, run().task(":snapshot")?.outcome)
        assertTrue(targets().outputs.any { it.key.sourceSet == "qualityExamples" && it.state == "EMPTY" })
        assertEquals(emptyList(), targets().problems)
        // 함께 요청된 컴파일만 먼저 끝낸다. CLI에 snapshot을 먼저 적어도 이전 출력을 읽으면 안 된다.
        val source = root.resolve("lib/src/test/java/example/TestHelper.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package example; public class TestHelper {}")
        val together = run(":lib:compileTestJava")
        assertEquals(TaskOutcome.SUCCESS, together.task(":lib:compileTestJava")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, together.task(":snapshot")?.outcome)
        assertTrue(together.tasks.indexOf(together.task(":lib:compileTestJava")) < together.tasks.indexOf(together.task(":snapshot")))
        assertTrue("example.TestHelper" in targets().knownTypes)
    }

    @Test fun `지연 기본 의존은 해석 후 나타나며 명시 의존이 있으면 추가하지 않는다`() {
        prepare()
        append("app/build.gradle", """
            configurations.runtimeOnly.defaultDependencies { add(project.dependencies.project(path: ':bench')) }
            if (providers.gradleProperty('explicitRuntime').isPresent()) { dependencies { runtimeOnly project(':lib') } }
        """)
        run()
        assertEquals(emptyList(), evaluate().violations)
        assertEquals(TaskOutcome.SUCCESS, run("-PresolveRuntime=true").task(":snapshot")?.outcome)
        assertEquals(setOf(":bench"), evaluate().violations.map { it.target }.toSet())
        run("-PresolveRuntime=true", "-PexplicitRuntime=true")
        assertEquals(emptyList(), evaluate().violations)
        assertEquals(TaskOutcome.UP_TO_DATE, run("-PresolveRuntime=true", "-PexplicitRuntime=true").task(":snapshot")?.outcome)
    }

    private fun prepare() {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'isolation-contract'\ninclude ':app', ':lib', ':bench'")
        listOf("app", "lib", "bench").forEach { name ->
            Files.createDirectories(root.resolve(name))
            Files.writeString(root.resolve("$name/build.gradle"), "plugins { id 'java-library'; id 'java-test-fixtures' }\ngroup = 'example'\nversion = '1'\n")
        }
        append("app/build.gradle", """
            dependencies {
                implementation project(':lib')
                testImplementation 'com.tngtech.archunit:archunit:1'
                testImplementation project(':bench')
                testImplementation testFixtures(project(':lib'))
            }
        """)
        Files.copy(Path.of(System.getProperty("architecture.isolationScript")), root.resolve("isolation-inputs.gradle.kts"))
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java' }
            ext['architecture.productionProjects'] = [':app', ':lib']
            apply from: 'isolation-inputs.gradle.kts'
            tasks.register('resolveRuntime') {
                doLast { project(':app').configurations.runtimeClasspath.incoming.resolutionResult.allDependencies }
            }
            def observed = providers.provider {
                def input = tasks.test.inputs.properties
                ['isolationDependencies', 'sourceInventory', 'sourceOutputs'].collectEntries { [(it): input[it]] }
            }
            tasks.register('snapshot') {
                if (providers.gradleProperty('resolveRuntime').isPresent()) dependsOn('resolveRuntime')
                inputs.property('observed', observed)
                // 실제 Test의 속성과 파일 입력을 함께 추적한다.
                inputs.files(providers.provider { tasks.test.inputs.files.files }).withPropertyName('observedFiles')
                mustRunAfter(providers.provider { tasks.test.mustRunAfter.getDependencies(null) })
                outputs.dir(layout.buildDirectory.dir('observed'))
                doLast {
                    def out = layout.buildDirectory.dir('observed').get().asFile
                    out.mkdirs()
                    observed.get().each { name, value -> new File(out, name + '.txt').text = value }
                }
            }
        """.trimIndent())
    }
    private fun append(path: String, text: String) { Files.writeString(root.resolve(path), "\n${text.trimIndent()}\n", APPEND) }
    private fun run(vararg args: String) = GradleRunner.create().withProjectDir(root.toFile())
        .withGradleInstallation(File(System.getProperty("architecture.gradleHome")))
        .withTestKitDir(root.resolve(".test-kit").toFile())
        .withArguments(listOf("snapshot", "--offline", "--console=plain", "--max-workers=1", "--stacktrace") + args).build()
    private fun read() = IsolationInputs.dependencies(Files.readString(root.resolve("build/observed/isolationDependencies.txt"))).also { assertEquals(emptyList(), it.problems) }
    private fun evaluate() = ProductionDependencyIsolation.inspectGradle(read(), inventory).also { assertTrue(it.evaluated, it.problems.toString()) }
    private fun targets() = IsolationInputs.targets(
        Files.readString(root.resolve("build/observed/sourceInventory.txt")), Files.readString(root.resolve("build/observed/sourceOutputs.txt")), inventory,
    )
}

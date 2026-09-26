package com.exchange.architecture

import com.exchange.architecture.rules.*
import com.exchange.architecture.support.*
import java.util.Base64
import kotlin.test.*

class IsolationDependencyContractTest {
    private val inventory = GradleModuleInventory(setOf(":app", ":lib", ":bench"), setOf(":app", ":lib"), setOf(":bench"))
    private fun snapshot(vararg declarations: IsolationDeclaration) = IsolationDependencySnapshot(
        inventory.productionModules.sorted().flatMap { path -> listOf("compile", "runtime").map { usage ->
            MainIsolationDependencies(path, usage, "${usage}Classpath", "/$path/build.gradle.kts", if (path == ":app") declarations.toList() else emptyList())
        } },
    )
    private fun external(target: String, category: String = "", selection: String = "main") = IsolationDeclaration("external", target, "implementation", selection, category = category)

    @Test fun `비운영 프로젝트 선언은 compile runtime 근거를 한 진단으로 남긴다`() {
        val result = ProductionDependencyIsolation.inspectGradle(snapshot(IsolationDeclaration("project", ":bench", "implementation")), inventory)
        assertTrue(result.evaluated, result.problems.toString())
        val v = result.violations.single()
        assertEquals(":app", v.origin); assertEquals(":bench", v.target)
        assertEquals("비운영 프로젝트", v.reason); assertEquals("GRADLE", v.evidence)
        assertTrue(v.description.contains("compile/compileClasspath") && v.description.contains("runtime/runtimeClasspath"))
        assertEquals("/:app/build.gradle.kts", v.sourceFile); assertNull(v.line)
    }

    @Test fun `명시 도구 좌표와 일반 라이브러리 BOM을 구분한다`() {
        val blocked = listOf("org.junit.jupiter:junit-jupiter-api", "org.junit.platform:junit-platform-launcher", "org.junit.vintage:junit-vintage-engine", "junit:junit",
            "org.jetbrains.kotlin:kotlin-test", "org.jetbrains.kotlin:kotlin-test-junit5", "org.testcontainers:postgresql", "org.openjdk.jmh:jmh-core",
            "com.tngtech.archunit:archunit", "org.springframework:spring-test", "org.springframework.boot:spring-boot-test", "org.springframework.boot:spring-boot-starter-data-jpa-test")
        val normal = listOf("org.jetbrains.kotlin:kotlin-stdlib", "org.springframework:spring-context", "org.springframework.boot:spring-boot-starter-webmvc", "tools.jackson.core:jackson-databind", "example:contest")
        val input = snapshot(*(blocked + normal).map { external(it) }.toTypedArray())
        val result = ProductionDependencyIsolation.inspectGradle(input, inventory)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(blocked.toSet(), result.violations.map { it.target }.toSet())
        val bom = ProductionDependencyIsolation.inspectGradle(snapshot(external("org.junit.jupiter:example-bom", "platform"), external("org.testcontainers:testcontainers-bom", "enforced-platform")), inventory)
        assertTrue(bom.evaluated); assertEquals(emptyList(), bom.violations)
        assertEquals(result, ProductionDependencyIsolation.inspectGradle(input.copy(configurations = input.configurations.reversed().map { it.copy(dependencies = it.dependencies.reversed()) }), inventory))
    }

    @Test fun `운영 프로젝트와 외부 라이브러리도 fixture를 선택하면 금지한다`() {
        val main = IsolationDeclaration("project", ":lib", "implementation")
        val fixture = main.copy(selection = "test-fixtures", details = "capability=lib-test-fixtures")
        val external = external("example:helper", selection = "test-fixtures").copy(details = "classifier=test-fixtures")
        val result = ProductionDependencyIsolation.inspectGradle(snapshot(main, fixture, external), inventory)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(2, result.violations.size)
        assertEquals(setOf(":lib", "example:helper"), result.violations.map { it.target }.toSet())
        assertTrue(result.violations.all { it.reason == "테스트 fixture 선택" })
        assertTrue(result.violations.any { it.description.contains("capability=lib-test-fixtures") })
    }

    @Test fun `빈 구성은 정상이고 구성 누락 중복 좌표 누락 미등록 대상 미지원 선택은 미평가다`() {
        val valid = snapshot()
        assertEquals(IsolationResult(), ProductionDependencyIsolation.inspectGradle(valid, inventory))
        val invalid = listOf(
            valid.copy(configurations = valid.configurations.drop(1)),
            valid.copy(configurations = valid.configurations + valid.configurations.first()),
            snapshot(external(":missing-group")),
            snapshot(IsolationDeclaration("project", ":nested:unknown", "implementation")),
            snapshot(IsolationDeclaration("project", ":lib", "implementation", "unsupported", "configuration=special")),
            snapshot(external("org.junit.jupiter:junit-jupiter-api"), external("org.junit.jupiter:junit-jupiter-api")),
            valid.copy(problems = listOf("MISSING_INPUT")),
        )
        invalid.forEach { input ->
            val result = ProductionDependencyIsolation.inspectGradle(input, inventory)
            assertFalse(result.evaluated, input.toString()); assertEquals(emptyList(), result.violations)
        }
    }

    @Test fun `손상된 전달 형식과 빈 main 구성을 구분한다`() {
        fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") { Base64.getUrlEncoder().encodeToString(it.toByteArray()) }
        val valid = "ARCH08-DEPS/1\n" + row("C", ":app", "compile", "compileClasspath", "/build.gradle.kts")
        val read = IsolationInputs.dependencies(valid)
        assertEquals(emptyList(), read.problems); assertEquals(emptyList(), read.configurations.single().dependencies)
        listOf(null, "", "ARCH08-DEPS/1", "$valid\nunknown", "ARCH08-DEPS/1\nD\t%%", valid + "\n" + row("D", "external", "x:y", "", "main", "", "")).forEach {
            val result = IsolationInputs.dependencies(it)
            assertTrue(result.problems.isNotEmpty()); assertEquals(emptyList(), result.configurations)
        }
    }

    @Test fun `어느 한쪽 준비 실패면 코드와 Gradle의 부분 위반도 최종 결과로 내지 않는다`() {
        val result = ProductionDependencyIsolation.inspect(ScopeImportResult(), NonProductionIndex(), snapshot(external("junit:junit")), inventory)
        assertFalse(result.evaluated)
        assertEquals(emptyList(), result.violations)
    }
}

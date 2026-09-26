package com.exchange.architecture

import com.exchange.architecture.rules.ModuleDependencyDirection
import com.exchange.architecture.support.*
import java.util.Base64
import kotlin.test.*

class ProjectDependencyContractTest {
    private val inventory = GradleModuleInventory(setOf(":matching", ":order", ":fee", ":tests"), setOf(":matching", ":order", ":fee"), setOf(":tests"))
    private val policy = mapOf("matching" to setOf("order"), "order" to setOf("fee"), "fee" to emptySet<String>())
    private fun records() = inventory.productionModules.sorted().flatMap { path -> listOf("compile", "runtime").map { usage ->
        MainProjectDependencies(path, usage, "${usage}Classpath", "$path/build.gradle.kts", when (path) {
            ":matching" -> listOf(ProjectDeclaration(":order", "api"))
            ":order" -> listOf(ProjectDeclaration(":fee", "api"))
            else -> emptyList()
        })
    } }
    private fun inspect(records: List<MainProjectDependencies>) = ModuleDependencyDirection.inspectGradle(ProjectDependencySnapshot(records), inventory, policy)

    @Test
    fun `선언된 두 허용 방향은 전이 수수료를 직접 의존으로 만들지 않는다`() {
        val result = inspect(records())
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `코드 사용 없이 추가한 금지 선언도 근거와 함께 보고한다`() {
        val records = records().map { if (it.projectPath == ":matching") it.copy(dependencies = it.dependencies + ProjectDeclaration(":fee", "implementation")) else it }
        val result = inspect(records)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(1, result.violations.size, "금지 선언을 한 위반으로 묶고 두 구성 근거를 보존해야 한다")
        val violation = result.violations.single()
        assertEquals("ARCH-02", violation.ruleId)
        assertEquals("GRADLE", violation.evidence)
        assertEquals("matching", violation.originModule)
        assertEquals("fee", violation.targetModule)
        assertEquals(setOf("order"), violation.allowedTargets)
        assertEquals(":matching/build.gradle.kts", violation.sourceFile)
        assertNull(violation.lineNumber)
        listOf(":matching", ":fee", "compileClasspath", "runtimeClasspath", "implementation").forEach { assertContains(violation.description, it) }
        assertEquals("engineering/architecture-check-spec.md", violation.specification)
        assertEquals(result, inspect(records.reversed()))
    }

    @Test
    fun `빈 구성 기록은 정상이고 빠진 구성 기록은 미평가이다`() {
        val complete = records()
        assertTrue(inspect(complete).evaluated)
        assertTrue(complete.filter { it.projectPath == ":fee" }.all { it.dependencies.isEmpty() })
        val result = inspect(complete.filterNot { it.projectPath == ":fee" && it.usage == "runtime" })
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.contains("MISSING_CONFIGURATION") && it.contains(":fee") && it.contains("runtime") })
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `중복 미등록 잘못된 구성 입력은 통과하지 않는다`() {
        val complete = records()
        val invalid = listOf(
            complete + complete.first(),
            complete.map { if (it.usage == "runtime") it.copy(buildFile = "/different/build.gradle.kts") else it },
            complete.map { if (it.projectPath == ":matching") it.copy(dependencies = listOf(ProjectDeclaration(":unknown", "api"))) else it },
            complete.map { if (it.projectPath == ":matching") it.copy(usage = "test") else it },
            complete.map { if (it.projectPath == ":matching") it.copy(configuration = "") else it },
            complete.map { if (it.projectPath == ":matching") it.copy(dependencies = it.dependencies + it.dependencies) else it },
        )
        invalid.forEach {
            val result = inspect(it)
            assertFalse(result.evaluated, "손상 입력이 통과함: $it")
            assertTrue(result.problems.isNotEmpty())
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `등록된 비운영 목적지는 ARCH-08 대상으로 보존하되 방향 위반은 아니다`() {
        val result = inspect(records().map { if (it.projectPath == ":matching") it.copy(dependencies = it.dependencies + ProjectDeclaration(":tests", "runtimeOnly")) else it })
        assertTrue(result.evaluated)
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `새 프로젝트의 등록 누락 또는 정책 누락은 미평가이다`() {
        val unknown = inventory.copy(discoveredModules = inventory.discoveredModules + ":new")
        val unregistered = ModuleDependencyDirection.inspectGradle(ProjectDependencySnapshot(records()), unknown, policy)
        assertFalse(unregistered.evaluated)
        assertTrue(unregistered.problems.any { it.contains("UNREGISTERED_MODULE") && it.contains(":new") })
        val missingPolicy = ModuleDependencyDirection.inspectGradle(ProjectDependencySnapshot(records()), inventory, policy - "matching")
        assertFalse(missingPolicy.evaluated)
        assertTrue(missingPolicy.problems.any { it.contains("MISSING_POLICY") })
    }

    @Test
    fun `전달 형식은 빈 구성과 특수문자 경로를 손실 없이 읽는다`() {
        val inputs = records().map { it.copy(buildFile = "/tmp/한글 경로\t/build.gradle.kts") }
        val result = ProjectDependencies.read(encode(inputs))
        assertEquals(emptyList(), result.problems)
        assertEquals(inputs, result.configurations)
    }

    @Test
    fun `전달 안 됨과 손상된 형식을 빈 의존으로 바꾸지 않는다`() {
        listOf(null, "", "ARCH02/2", "ARCH02/1\nD\tx\ty", "ARCH02/1\nC\t!bad", encode(records()) + "\n?\tunknown").forEach {
            val read = ProjectDependencies.read(it)
            assertTrue(read.problems.isNotEmpty(), "잘못된 입력을 무시함: $it")
            val result = ModuleDependencyDirection.inspectGradle(read, inventory, policy)
            assertFalse(result.evaluated)
            assertEquals(emptyList(), result.violations)
        }
    }

    private fun encode(records: List<MainProjectDependencies>): String = buildList {
        fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") { Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        add("ARCH02/1")
        records.forEach { c ->
            add(row("C", c.projectPath, c.usage, c.configuration, c.buildFile))
            c.dependencies.forEach { add(row("D", it.targetPath, it.declaredIn)) }
        }
    }.joinToString("\n")
}

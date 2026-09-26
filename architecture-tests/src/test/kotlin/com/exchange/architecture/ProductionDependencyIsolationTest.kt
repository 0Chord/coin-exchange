package com.exchange.architecture

import com.exchange.architecture.rules.*
import com.exchange.architecture.support.*
import com.exchange.core.isolationfixture.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ProductionDependencyIsolationTest {
    @TempDir lateinit var root: Path
    private val inventory = GradleModuleInventory(setOf(":app", ":bench"), setOf(":app"), setOf(":bench"))
    private fun index(vararg helper: Class<*>): NonProductionIndex {
        val outputs = listOf(
            SourceSetOutput(SourceSetKey(":app", "main"), listOf(root.resolve("main"))),
            SourceSetOutput(SourceSetKey(":app", "test"), listOf(fixtureOutput(root.resolve("test"), *helper))),
            SourceSetOutput(SourceSetKey(":bench", "main"), listOf(root.resolve("bench"))),
        )
        return NonProductionTargets.inspect(outputs, outputs.map { it.key }.toSet(), inventory)
    }
    private fun load(index: NonProductionIndex, vararg types: Class<*>): ScopeImportResult = ProductionScopeImporter().load(
        listOf(ModuleOutput("app", listOf(fixtureOutput(root.resolve("main"), *types)))),
        ScopeExpectations(mapOf("app" to setOf(types.first().name)), projectPackagePrefixes = setOf("com.exchange.core.")), index,
    )

    @Test fun `확인된 test 도우미는 운영 목록에 합치지 않고 ARCH-08 위반으로 보낸다`() {
        val index = index(Helper::class.java)
        val scope = load(index, UsesHelper::class.java)
        assertEquals(emptyList(), scope.problems)
        assertEquals(setOf(UsesHelper::class.java.name), scope.classesByModule.getValue("app").map { it.name }.toSet())
        val direction = ModuleDependencyDirection.inspectBytecode(scope, mapOf("app" to emptySet()), nonProduction = index)
        assertTrue(direction.evaluated, direction.problems.toString())
        assertEquals(emptyList(), direction.violations)
        val result = ProductionDependencyIsolation.inspectBytecode(scope, index)
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue(result.violations.isNotEmpty())
        assertTrue(result.violations.all { it.target == Helper::class.java.name && it.origin == "app/${UsesHelper::class.java.name}" && it.reason == "비운영 출력 :app/test" })
        assertTrue(result.violations.any { it.sourceFile == "IsolationFixtures.kt" && it.line != null })
    }

    @Test fun `도우미 정의를 못 찾으면 알려진 접두사라도 준비 실패다`() {
        val index = index()
        val scope = load(index, UsesHelper::class.java)
        assertTrue(scope.problems.any { it.code == ScopeProblemCode.UNRESOLVED_PROJECT_TYPE && it.subject == Helper::class.java.name })
        val result = ProductionDependencyIsolation.inspectBytecode(scope, index)
        assertFalse(result.evaluated)
        assertEquals(emptyList(), result.violations)
    }

    @Test fun `main과 test가 같은 이름을 정의하면 어느 쪽으로도 면제하지 않는다`() {
        val index = index(Helper::class.java)
        val scope = load(index, Helper::class.java)
        assertTrue(scope.problems.any { it.code == ScopeProblemCode.AMBIGUOUS_OWNERSHIP && it.subject == Helper::class.java.name })
        assertFalse(ModuleDependencyDirection.inspectBytecode(scope, mapOf("app" to emptySet()), nonProduction = index).evaluated)
        assertFalse(ProductionDependencyIsolation.inspectBytecode(scope, index).evaluated)
    }

    @Test fun `보조 자료가 불완전하면 내부 누락과 전체 미평가를 유지한다`() {
        val broken = index(Helper::class.java).copy(problems = listOf("READ_FAILURE: broken.class"))
        val scope = load(broken, UsesHelper::class.java)
        assertTrue(scope.problems.any { it.code == ScopeProblemCode.UNRESOLVED_PROJECT_TYPE })
        val result = ProductionDependencyIsolation.inspectBytecode(scope, broken)
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.contains("broken.class") })
        assertEquals(emptyList(), result.violations)
    }

    @Test fun `TestValue라는 main 값과 운영 Spring은 이름 때문에 금지하지 않는다`() {
        val index = index(Helper::class.java)
        val scope = load(index, TestValue::class.java, Normal::class.java, SpringProduction::class.java)
        val result = ProductionDependencyIsolation.inspectBytecode(scope, index)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test fun `상속 배열 제네릭 파일 파사드 람다 메서드 참조의 실제 의존을 검사한다`() {
        val index = index(Helper::class.java)
        val facade = Class.forName("com.exchange.core.isolationfixture.IsolationFixturesKt")
        val reference = Class.forName(facade.name + "\$helperReference\$1")
        val scope = load(index, ExtendsHelper::class.java, Signatures::class.java, facade, reference)
        assertEquals(emptyList(), scope.problems)
        val dependencies = scope.classesByModule.getValue("app").flatMap { it.directDependenciesFromSelf }.filter { it.targetClass.baseComponentType.name == Helper::class.java.name }
        assertTrue(dependencies.any { it.description.contains("extends") })
        assertTrue(dependencies.any { it.description.contains("generic") })
        assertTrue(dependencies.any { it.description.contains("references method") || it.description.contains("calls method") })
        val result = ProductionDependencyIsolation.inspectBytecode(scope, index)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(dependencies.size, result.violations.size)
        assertEquals(setOf(ExtendsHelper::class.java.name, Signatures::class.java.name, facade.name, reference.name), result.violations.map { it.origin.removePrefix("app/") }.toSet())
    }

    @Test fun `실제 외부 테스트 도구 타입은 본문 실행 없이 모두 탐지한다`() {
        val index = index()
        val cases = mapOf(
            JunitAnnotated::class.java to "org.junit.jupiter.api.Tag",
            KotlinAssertion::class.java to "kotlin.test.AssertionsKt",
            ContainerField::class.java to "org.testcontainers.containers.GenericContainer",
            BenchmarkMethod::class.java to "org.openjdk.jmh.annotations.Benchmark",
            ArchitectureField::class.java to "com.tngtech.archunit.core.domain.JavaClass",
            SpringTestAnnotated::class.java to "org.springframework.test.context.ContextConfiguration",
        )
        val scope = load(index, *cases.keys.toTypedArray())
        val result = ProductionDependencyIsolation.inspectBytecode(scope, index)
        assertTrue(result.evaluated, result.problems.toString())
        cases.forEach { (origin, target) -> assertTrue(result.violations.any { it.origin == "app/${origin.name}" && it.target == target }, "$origin → $target 누락") }
        assertTrue(result.violations.all { it.evidence == "BYTECODE" && it.reason.startsWith("외부 테스트 도구") })
    }

    @Test fun `벤치마크 소속과 출력 역순에도 진단이 같다`() {
        val testIndex = index(Helper::class.java)
        val benchmark = testIndex.copy(owners = testIndex.owners.mapValues { (_, owner) -> owner.copy(projectPath = ":bench", sourceSet = "main") })
        val scope = load(benchmark, UsesHelper::class.java, JunitAnnotated::class.java)
        val first = ProductionDependencyIsolation.inspectBytecode(scope, benchmark)
        assertTrue(first.evaluated)
        assertTrue(first.violations.any { it.reason == "비운영 출력 :bench/main" })
        assertEquals(first, ProductionDependencyIsolation.inspectBytecode(scope.copy(classesByModule = scope.classesByModule.toList().reversed().toMap()), benchmark))
    }
    @Test fun `코드와 선언 근거를 함께 보고하고 한쪽 누락이면 부분 위반을 내지 않는다`() {
        val index = index(Helper::class.java)
        val scope = load(index, UsesHelper::class.java)
        val snapshot = IsolationDependencySnapshot(listOf("compile", "runtime").map {
            MainIsolationDependencies(":app", it, "${it}Classpath", "/app/build.gradle.kts", listOf(IsolationDeclaration("project", ":bench", "implementation")))
        })
        val result = ProductionDependencyIsolation.inspect(scope, index, snapshot, inventory)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf("BYTECODE", "GRADLE"), result.violations.map { it.evidence }.toSet())
        assertEquals(1, result.violations.count { it.evidence == "GRADLE" })
        assertEquals(result, ProductionDependencyIsolation.inspect(scope, index, snapshot.copy(configurations = snapshot.configurations.reversed()), inventory))
        val missing = ProductionDependencyIsolation.inspect(scope, index, snapshot.copy(configurations = snapshot.configurations.drop(1)), inventory)
        assertFalse(missing.evaluated)
        assertTrue(missing.problems.any { it.contains("MISSING_CONFIGURATION") })
        assertEquals(emptyList(), missing.violations)
    }

}

package com.exchange.architecture

import com.exchange.architecture.fixtures.moduledeps.*
import com.exchange.architecture.rules.ModuleDependencyDirection
import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ModuleDependencyContractTest {
    @TempDir lateinit var root: Path

    private val policy = mapOf(
        "domain-matching" to setOf("domain-order"),
        "domain-order" to setOf("domain-fee"),
        "domain-fee" to emptySet<String>(),
    )

    @Test
    fun `직접 허용 방향만 있으면 주문을 통한 간접 수수료 연결도 통과한다`() {
        val scope = scope(MatchingUsesOrder::class.java)
        val actual = ModuleDependencyDirection.inspectBytecode(scope, policy)
        assertTrue(actual.evaluated, actual.problems.toString())
        assertEquals(emptyList(), actual.violations)
    }

    @Test
    fun `컴파일 가능해도 매칭의 수수료 직접 참조는 위반이다`() {
        val scope = scope(MatchingUsesFee::class.java)
        val origin = MatchingUsesFee::class.java.name
        val target = FeeValue::class.java.name
        assertTrue(scope.classesByModule.getValue("domain-matching").get(origin)
            .directDependenciesFromSelf.any { it.targetClass.name == target })
        val actual = ModuleDependencyDirection.inspectBytecode(scope, policy)
        assertTrue(actual.evaluated, actual.problems.toString())
        assertTrue(actual.violations.isNotEmpty(), "매칭의 수수료 직접 참조를 놓쳤다")
        assertTrue(actual.violations.all {
            it.ruleId == "ARCH-02" && it.evidence == "BYTECODE" &&
                it.originModule == "domain-matching" && it.targetModule == "domain-fee" &&
                it.originType == origin && it.targetType == target &&
                it.allowedTargets == setOf("domain-order") &&
                it.sourceFile == "ModuleDependencyFixtures.kt" &&
                it.specification == "engineering/architecture-check-spec.md"
        })
    }

    @Test
    fun `같은 패키지여도 실제 출력 소속으로 매칭과 수수료를 구분한다`() {
        assertEquals(MatchingUsesFee::class.java.packageName, FeeValue::class.java.packageName)
        val scope = scope(MatchingUsesFee::class.java)
        val actual = ModuleDependencyDirection.inspectBytecode(scope, policy)
        assertTrue(actual.evaluated)
        assertEquals(setOf("domain-matching" to "domain-fee"),
            actual.violations.map { it.originModule to it.targetModule }.toSet())
    }

    @Test
    fun `여섯 운영 모듈의 모든 직접 방향을 명세의 허용 쌍과 대조한다`() {
        // 구현의 허용표에서 정답을 만들지 않고 명세의 허용 쌍을 별도로 적는다.
        val permitted = setOf(
            "domain-fee" to "domain-common", "domain-order" to "domain-common", "domain-order" to "domain-fee",
            "domain-ledger" to "domain-common", "domain-matching" to "domain-common", "domain-matching" to "domain-order",
            "app-api" to "domain-common", "app-api" to "domain-fee", "app-api" to "domain-order",
            "app-api" to "domain-ledger", "app-api" to "domain-matching",
        )
        val markers = mapOf(
            "domain-common" to CommonMarker::class.java, "domain-fee" to FeeMarker::class.java,
            "domain-order" to OrderMarker::class.java, "domain-ledger" to LedgerMarker::class.java,
            "domain-matching" to MatchingMarker::class.java, "app-api" to ApiMarker::class.java,
        )
        val all = ClassFileImporter().importClasses(*(markers.values + listOf(MatchingUsesFee::class.java, FeeValue::class.java)).toTypedArray())
        var checked = 0
        markers.keys.forEach { origin -> markers.keys.forEach { target ->
            val groups = markers.mapValues { (module, marker) ->
                val names = mutableSetOf(marker.name)
                if (module == origin) names += MatchingUsesFee::class.java.name
                if (module == target) names += FeeValue::class.java.name
                all.that(DescribedPredicate.describe("실제 배정 모듈 $module") { it.name in names })
            }
            val actual = ModuleDependencyDirection.inspectBytecode(ScopeImportResult(groups))
            assertTrue(actual.evaluated, actual.problems.toString())
            val expected = if (origin == target || origin to target in permitted) emptySet() else setOf(origin to target)
            assertEquals(expected, actual.violations.map { it.originModule to it.targetModule }.toSet(), "$origin → $target")
            checked++
        } }
        assertEquals(36, checked, "빈 반복으로 허용표 검증을 대신하지 않는다")
    }

    @Test
    fun `포트 실행기 생성 클래스도 모듈 방향 검사에 포함한다`() {
        val generated = GeneratedAction().task().javaClass
        val origins = arrayOf(FeeReadingPort::class.java, FeeReadingExecutor::class.java, GeneratedAction::class.java, generated)
        val scope = imported(mapOf("matching" to origins, "fee" to arrayOf(FeeValue::class.java)))
        val result = ModuleDependencyDirection.inspectBytecode(scope, mapOf("matching" to emptySet(), "fee" to emptySet()))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(FeeReadingPort::class.java.name, FeeReadingExecutor::class.java.name, generated.name),
            result.violations.map { it.originType }.toSet())
        assertTrue(result.violations.all { it.targetType == FeeValue::class.java.name })
        // 예제의 생성자 호출은 각각 17행과 20행이다. 진단 결과에서 기대 위치를 역산하지 않는다.
        assertTrue(result.violations.any { it.originType == FeeReadingExecutor::class.java.name && it.lineNumber == 17 })
        assertTrue(result.violations.any { it.originType == generated.name && it.lineNumber == 20 })
    }

    @Test
    fun `필드 배열 제네릭 상속 어노테이션과 인자 반환의 직접 참조를 보고한다`() {
        val scope = imported(mapOf("matching" to arrayOf(ReferenceShapes::class.java),
            "fee" to arrayOf(FeeValue::class.java, FeeBase::class.java, FeeAnnotation::class.java)))
        val deps = scope.classesByModule.getValue("matching").get(ReferenceShapes::class.java).directDependenciesFromSelf
        listOf("field", "parameter", "return", "extends", "annotation", "generic").forEach { shape ->
            assertTrue(deps.any { it.description.contains(shape, ignoreCase = true) }, "예제에 $shape 참조가 있어야 한다")
        }
        assertTrue(deps.any { it.targetClass.isArray && it.targetClass.baseComponentType.name == FeeValue::class.java.name })
        val result = ModuleDependencyDirection.inspectBytecode(scope, mapOf("matching" to emptySet(), "fee" to emptySet()))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(FeeValue::class.java.name, FeeBase::class.java.name, FeeAnnotation::class.java.name), result.violations.map { it.targetType }.toSet())
        assertTrue(result.violations.any { it.lineNumber == null }, "메타데이터 참조의 행 번호를 지어내지 않는다")
        assertTrue(result.violations.all { it.sourceFile == "ModuleDependencyFixtures.kt" })
        val ordered = result.violations
        assertEquals(ordered, ModuleDependencyDirection.inspectBytecode(scope.copy(classesByModule = scope.classesByModule.entries.reversed().associate { it.toPair() }), mapOf("fee" to emptySet(), "matching" to emptySet())).violations)
    }

    @Test
    fun `일반 외부 타입은 모듈 방향 위반으로 오인하지 않는다`() {
        val result = ModuleDependencyDirection.inspectBytecode(imported(mapOf("one" to arrayOf(ExternalOnly::class.java))), mapOf("one" to emptySet()))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `빈 대상 수집 오류와 중복 소속은 미평가로 보고한다`() {
        val good = scope(MatchingUsesFee::class.java)
        val invalid = listOf(
            ScopeImportResult(),
            good.copy(problems = listOf(ScopeProblem(ScopeProblemCode.READ_FAILURE, "broken.class"))),
            good.copy(classesByModule = good.classesByModule + ("domain-order" to good.classesByModule.getValue("domain-matching"))),
            good.copy(classesByModule = good.classesByModule.mapValues { (_, classes) -> classes.that(DescribedPredicate.describe("빈 모듈") { false }) }),
        )
        invalid.forEach {
            val result = ModuleDependencyDirection.inspectBytecode(it, policy)
            assertFalse(result.evaluated)
            assertTrue(result.problems.isNotEmpty())
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `허용표 누락과 알 수 없는 대상은 조용히 허용하지 않는다`() {
        val input = scope(MatchingUsesFee::class.java)
        listOf(policy - "domain-matching", policy + ("domain-fee" to setOf("unknown")), policy + ("extra" to emptySet())).forEach {
            val result = ModuleDependencyDirection.inspectBytecode(input, it)
            assertFalse(result.evaluated)
            assertTrue(result.problems.any { p -> p.contains("POLICY") })
            assertEquals(emptyList(), result.violations)
        }
    }

    @Test
    fun `내부 참조의 소속을 못 찾으면 외부 라이브러리로 간주하지 않는다`() {
        val result = ModuleDependencyDirection.inspectBytecode(imported(mapOf("matching" to arrayOf(MatchingUsesFee::class.java))), mapOf("matching" to emptySet()))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.startsWith("UNRESOLVED_PROJECT_TYPE:") && it.contains(FeeValue::class.java.name) })
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `코드와 Gradle 양쪽의 위반을 함께 보고하되 한쪽 준비 오류면 미평가한다`() {
        val scope = scope(MatchingUsesFee::class.java)
        val paths = policy.keys.map { ":$it" }.toSet()
        val inventory = GradleModuleInventory(paths, paths, emptySet())
        val snapshot = ProjectDependencySnapshot(paths.flatMap { path -> listOf("compile", "runtime").map { usage ->
            MainProjectDependencies(path, usage, "${usage}Classpath", "$path/build.gradle.kts",
                if (path == ":domain-matching") listOf(ProjectDeclaration(":domain-fee", "api")) else emptyList())
        } })
        val result = ModuleDependencyDirection.inspect(scope, snapshot, inventory, policy)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf("BYTECODE", "GRADLE"), result.violations.map { it.evidence }.toSet())
        assertTrue(result.violations.all { it.originModule == "domain-matching" && it.targetModule == "domain-fee" })
        assertTrue(result.violations.all { it.report().contains("ARCH-02") && it.report().contains(it.specification) })
        val brokenInputs = listOf(
            scope to snapshot.copy(problems = listOf("INVALID_DEPENDENCY_INPUT: broken")),
            scope.copy(problems = listOf(ScopeProblem(ScopeProblemCode.READ_FAILURE, "broken.class"))) to snapshot,
        )
        brokenInputs.forEach { (code, declarations) ->
            val failed = ModuleDependencyDirection.inspect(code, declarations, inventory, policy)
            assertFalse(failed.evaluated)
            assertEquals(emptyList(), failed.violations)
        }
    }

    private fun imported(groups: Map<String, Array<Class<*>>>): ScopeImportResult {
        val all = ClassFileImporter().importClasses(*groups.values.flatMap { it.toList() }.toTypedArray())
        return ScopeImportResult(groups.mapValues { (_, types) ->
            val names = types.map { it.name }.toSet()
            all.that(DescribedPredicate.describe("예제의 지정된 출력 소속") { it.name in names })
        })
    }

    private fun scope(matching: Class<*>): ScopeImportResult {
        val types = mapOf(
            "domain-matching" to arrayOf(matching),
            "domain-order" to arrayOf(OrderValue::class.java),
            "domain-fee" to arrayOf(FeeValue::class.java),
        )
        val outputs = types.map { (module, classes) -> ModuleOutput(module, listOf(fixtureOutput(root.resolve(module), *classes))) }
        return ProductionScopeImporter().load(outputs, ScopeExpectations(
            types.mapValues { (_, classes) -> classes.map { it.name }.toSet() },
            projectPackagePrefixes = setOf("com.exchange.architecture.fixtures.moduledeps."),
        )).also { assertEquals(emptyList(), it.problems, "예제 준비 오류를 규칙의 Red로 세지 않는다") }
    }
}

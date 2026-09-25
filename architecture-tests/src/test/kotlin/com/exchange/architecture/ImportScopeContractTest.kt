package com.exchange.architecture

import com.exchange.architecture.fixtures.AnotherScopeValue
import com.exchange.architecture.fixtures.ForeignTestHelper
import com.exchange.architecture.fixtures.ScopeContainer
import com.exchange.architecture.fixtures.ScopePort
import com.exchange.architecture.fixtures.ScopeRule
import com.exchange.architecture.fixtures.ScopeValue
import com.exchange.architecture.support.BytecodeReader
import com.exchange.architecture.support.ModuleOutput
import com.exchange.architecture.support.ProductionScopeImporter
import com.exchange.architecture.support.ScopeExpectations
import com.exchange.architecture.support.ScopeImportResult
import com.exchange.architecture.support.ScopeProblem
import com.exchange.architecture.support.ScopeProblemCode
import com.exchange.architecture.support.fixtureOutput
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportScopeContractTest {
    @TempDir
    lateinit var temporary: Path

    private val valueName = ScopeValue::class.java.name
    private val ruleName = ScopeRule::class.java.name
    private val importer = ProductionScopeImporter()

    @Test
    fun `S01 정상 출력은 모듈별 클래스와 포트 역할을 빠짐없이 수집한다`() {
        val valueRoot = fixtureOutput(temporary.resolve("common"), ScopeValue::class.java)
        val ruleRoot = fixtureOutput(temporary.resolve("rules"), ScopeRule::class.java, ScopePort::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(valueRoot)), ModuleOutput("rules", listOf(ruleRoot))),
            ScopeExpectations(
                mapOf("common" to setOf(valueName), "rules" to setOf(ruleName)),
                requiredRoles = mapOf("ports" to setOf(ScopePort::class.java.name)),
            ),
        )

        assertEquals(emptyList(), result.problems)
        assertEquals(setOf("common", "rules"), result.classesByModule.keys, "S01: module outputs were not collected")
        assertEquals(setOf(valueName), result.names("common"))
        assertEquals(setOf(ruleName, ScopePort::class.java.name), result.names("rules"))
    }

    @Test
    fun `S02 대표 타입 외의 새 클래스와 중첩 타입도 자동 수집한다`() {
        val root = fixtureOutput(
            temporary.resolve("main"), ScopeValue::class.java, AnotherScopeValue::class.java,
            ScopeContainer::class.java, ScopeContainer.Member::class.java,
        )
        val result = importer.load(listOf(ModuleOutput("common", listOf(root))), commonContract())

        assertEquals(emptyList(), result.problems)
        assertEquals(
            setOf(valueName, AnotherScopeValue::class.java.name, ScopeContainer::class.java.name, ScopeContainer.Member::class.java.name),
            result.names("common"), "S02: representative types must not limit the imported inventory",
        )
    }

    @Test
    fun `S03 필수 모듈 하나가 빠지면 나머지가 있어도 실패한다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root))),
            ScopeExpectations(mapOf("common" to setOf(valueName), "rules" to setOf(ruleName))),
        )

        assertProblem(result, ScopeProblemCode.MISSING_MODULE, "rules")
    }

    @Test
    fun `S04 입력 전체가 비어 있으면 준수로 판정하지 않는다`() {
        assertProblem(importer.load(emptyList(), commonContract()), ScopeProblemCode.EMPTY_SCOPE, "production")
    }

    @Test
    fun `S05 필수 모듈에 클래스 파일이 없으면 실패한다`() {
        val root = Files.createDirectories(temporary.resolve("empty"))
        val result = importer.load(listOf(ModuleOutput("common", listOf(root))), commonContract())

        assertProblem(result, ScopeProblemCode.EMPTY_MODULE, "common")
    }

    @Test
    fun `S06 모듈이 비어 있지 않아도 필수 대표 타입이 없으면 실패한다`() {
        val root = fixtureOutput(temporary.resolve("main"), AnotherScopeValue::class.java)
        val result = importer.load(listOf(ModuleOutput("common", listOf(root))), commonContract())

        assertProblem(result, ScopeProblemCode.MISSING_REQUIRED_TYPE, valueName)
    }

    @Test
    fun `S07 역할 목록에 등록한 포트를 실제 출력에서 못 찾으면 실패한다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root))),
            commonContract().copy(requiredRoles = mapOf("ports" to setOf(ScopePort::class.java.name))),
        )

        assertProblem(result, ScopeProblemCode.MISSING_ROLE_TYPE, ScopePort::class.java.name)
    }

    @Test
    fun `S08 검사에 필요한 역할 자체가 빈 집합이면 실패한다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root))),
            commonContract().copy(requiredRoles = mapOf("pure-domain" to emptySet())),
        )

        assertProblem(result, ScopeProblemCode.EMPTY_ROLE, "pure-domain")
    }

    @Test
    fun `S09 바이트코드 reader가 파일 하나를 누락하면 대표 타입이 있어도 실패한다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java, AnotherScopeValue::class.java)
        val valueFile = root.resolve(valueName.replace('.', '/') + ".class")
        val partialReader = BytecodeReader { ClassFileImporter().importPath(valueFile) }
        val observed = partialReader.read(listOf(root)).map { it.name }.toSet()
        assertEquals(setOf(valueName), observed, "The fault fixture must omit exactly the non-representative type")

        val result = ProductionScopeImporter(partialReader).load(
            listOf(ModuleOutput("common", listOf(root))), commonContract(),
        )

        assertProblem(result, ScopeProblemCode.INCOMPLETE_IMPORT, AnotherScopeValue::class.java.name)
    }

    @Test
    fun `S10 서로 다른 출력 파일이 같은 전체 이름을 정의하면 실패한다`() {
        val first = fixtureOutput(temporary.resolve("first"), ScopeValue::class.java)
        val second = fixtureOutput(temporary.resolve("second"), ScopeValue::class.java)
        val result = importer.load(listOf(ModuleOutput("common", listOf(first, second))), commonContract())

        assertProblem(result, ScopeProblemCode.DUPLICATE_TYPE, valueName)
    }

    @Test
    fun `S11 같은 출력 경로를 두 번 전달한 것은 이름 충돌이 아니다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val result = importer.load(listOf(ModuleOutput("common", listOf(root, root.resolve(".")))), commonContract())

        assertEquals(emptyList(), result.problems, "Repeated path aliases should be normalized")
        assertEquals(setOf(valueName), result.names("common"))
    }

    @Test
    fun `S12 같은 패키지의 서로 다른 타입은 서로 다른 모듈 출력에서도 구분한다`() {
        val first = fixtureOutput(temporary.resolve("common"), ScopeValue::class.java)
        val second = fixtureOutput(temporary.resolve("rules"), ScopeRule::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(first)), ModuleOutput("rules", listOf(second))),
            ScopeExpectations(mapOf("common" to setOf(valueName), "rules" to setOf(ruleName))),
        )

        assertEquals(emptyList(), result.problems, "Sharing a package does not mean sharing a class definition")
        assertEquals(setOf(valueName), result.names("common"))
        assertEquals(setOf(ruleName), result.names("rules"))
    }

    @Test
    fun `S13 테스트 출력이 운영 수집 입력에 섞이면 실패한다`() {
        val main = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val tests = fixtureOutput(temporary.resolve("test"), ForeignTestHelper::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(main, tests))),
            commonContract().copy(forbiddenRoots = setOf(tests)),
        )

        assertProblem(result, ScopeProblemCode.FORBIDDEN_OUTPUT, tests.toString())
    }

    @Test
    fun `S14 등록하지 않은 새 운영 모듈을 조용히 건너뛰지 않는다`() {
        val main = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val unexpected = fixtureOutput(temporary.resolve("new-domain"), ScopeRule::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(main)), ModuleOutput("new-domain", listOf(unexpected))),
            commonContract(),
        )

        assertProblem(result, ScopeProblemCode.UNREGISTERED_MODULE, "new-domain")
    }

    @Test
    fun `S15 서로 다른 모듈의 별도 파일이 같은 전체 이름이면 충돌이다`() {
        val first = fixtureOutput(temporary.resolve("common"), ScopeValue::class.java)
        val second = fixtureOutput(temporary.resolve("rules"), ScopeValue::class.java)
        assertTrue(first.toRealPath() != second.toRealPath(), "Use distinct outputs, not aliases of the same directory")

        val result = importer.load(
            listOf(ModuleOutput("common", listOf(first)), ModuleOutput("rules", listOf(second))),
            ScopeExpectations(mapOf("common" to setOf(valueName), "rules" to setOf(valueName))),
        )

        assertProblem(result, ScopeProblemCode.DUPLICATE_TYPE, valueName)
    }

    private fun commonContract() = ScopeExpectations(mapOf("common" to setOf(valueName)))

    @Test
    fun `S16 테스트 출력의 부모 디렉터리를 전달해도 오염을 거절한다`() {
        val parent = temporary.resolve("classes")
        fixtureOutput(parent.resolve("main"), ScopeValue::class.java)
        val tests = fixtureOutput(parent.resolve("test"), ForeignTestHelper::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(parent))), commonContract().copy(forbiddenRoots = setOf(tests)),
        )
        assertProblem(result, ScopeProblemCode.FORBIDDEN_OUTPUT, parent.toString())
    }

    @Test
    fun `S17 잘못된 클래스 파일은 읽기 실패이며 정상 준수가 아니다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        Files.write(root.resolve("Broken.class"), byteArrayOf(0, 1, 2))
        val result = importer.load(listOf(ModuleOutput("common", listOf(root))), commonContract())
        assertTrue(result.problems.any { it.code == ScopeProblemCode.READ_FAILURE && it.subject.endsWith("Broken.class") })
    }

    @Test
    fun `S18 다른 파일 이름으로 숨긴 동일 바이트코드도 중복이다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        Files.copy(root.resolve(valueName.replace('.', '/') + ".class"), root.resolve("Disguised.class"))
        val result = importer.load(listOf(ModuleOutput("common", listOf(root))), commonContract())
        assertProblem(result, ScopeProblemCode.DUPLICATE_TYPE, valueName)
    }

    @Test
    fun `S19 프로젝트 내부 참조는 클래스패스에서 해석돼도 운영 출력에서 빠지면 실패한다`() {
        val caller = com.exchange.architecture.fixtures.ScopeCaller::class.java
        val root = fixtureOutput(temporary.resolve("main"), caller)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root))),
            ScopeExpectations(mapOf("common" to setOf(caller.name)), projectPackagePrefixes = setOf("com.exchange.architecture.fixtures.")),
        )
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNRESOLVED_PROJECT_TYPE && it.subject == valueName })
    }

    @Test
    fun `S20 reader가 외부 원본을 추가해도 정상 수집이 아니다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val contaminated = ProductionScopeImporter { ClassFileImporter().importClasses(ScopeValue::class.java, ForeignTestHelper::class.java) }
        val result = contaminated.load(listOf(ModuleOutput("common", listOf(root))), commonContract())
        assertProblem(result, ScopeProblemCode.UNEXPECTED_IMPORTED_TYPE, ForeignTestHelper::class.java.name)
    }

    @Test
    fun `S21 같은 파일을 두 모듈에 소속시키면 모듈 경계를 판정할 수 없다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root)), ModuleOutput("rules", listOf(root))),
            ScopeExpectations(mapOf("common" to setOf(valueName), "rules" to setOf(valueName))),
        )
        assertProblem(result, ScopeProblemCode.AMBIGUOUS_OWNERSHIP, valueName)
    }

    @Test
    fun `S22 금지 타입을 운영 출력에 복사해도 오염을 검출한다`() {
        val root = fixtureOutput(temporary.resolve("main"), ScopeValue::class.java, ForeignTestHelper::class.java)
        val result = importer.load(
            listOf(ModuleOutput("common", listOf(root))),
            commonContract().copy(forbiddenTypePrefixes = setOf(ForeignTestHelper::class.java.name)),
        )
        assertProblem(result, ScopeProblemCode.FORBIDDEN_OUTPUT, ForeignTestHelper::class.java.name)
    }

    private fun ScopeImportResult.names(module: String): Set<String> =
        classesByModule[module]?.map { it.name }?.toSet().orEmpty()

    private fun assertProblem(result: ScopeImportResult, code: ScopeProblemCode, subject: String) {
        assertTrue(
            ScopeProblem(code, subject) in result.problems,
            "Expected collection problem $code for $subject; actual=${result.problems}",
        )
    }
}

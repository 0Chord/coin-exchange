package com.exchange.architecture

import com.exchange.architecture.fixtures.applicationboundary.*
import com.exchange.architecture.fixtures.applicationboundary.application.*
import com.exchange.architecture.fixtures.applicationboundary.config.*
import com.exchange.architecture.rules.ApplicationImplementationIndependence
import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationBoundaryImportTest {
    @TempDir lateinit var directory: Path
    private val types = applicationNormalTypes + Assembly.NestedAssembly::class.java

    @Test fun `APP-01과07 실제 출력 수집부터 정상 조립과 업무 참조 판정까지 연결한다`() {
        val result = inspect(load(fixtureOutput(directory.resolve("normal"), *types)))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(setOf(SubmissionService::class.java.name, FundingService::class.java.name), result.applicationTypes)
        assertEquals(setOf(Assembly::class.java.name, Assembly.NestedAssembly::class.java.name), result.configurationTypes)
        assertTrue(result.referenceCount > 0)
        println("ARCH-04: 예제 검증 / 운영 미적용 · 업무 타입 ${result.applicationTypes.size}개 · 조립 타입 ${result.configurationTypes.size}개 · 직접 참조 ${result.referenceCount}개 · 준비 오류 0 · 위반 0")
    }
    @Test fun `APP-13 전체 출력에서 미등록 업무와 설정 타입을 발견한다`() {
        val scope = load(fixtureOutput(directory.resolve("new"), *types, NewHelper::class.java, NewAssembly::class.java, ComposedAssembly::class.java))
        assertFailure(scope, ScopeProblemCode.UNCLASSIFIED_APPLICATION_TYPE, NewHelper::class.java.name)
        assertFailure(scope, ScopeProblemCode.UNREGISTERED_CONFIGURATION, NewAssembly::class.java.name)
    }
    @Test fun `APP-14 가져온 목록에서 파일이 빠지거나 더해지면 준비 실패다`() {
        val root = fixtureOutput(directory.resolve("inventory"), *types)
        val missing = ProductionScopeImporter(BytecodeReader { paths -> ClassFileImporter().importPaths(paths)
            .that(DescribedPredicate.describe("의도한 목록 누락") { it.name != FundingService::class.java.name }) })
        assertFailure(load(root, missing), ScopeProblemCode.INCOMPLETE_IMPORT, FundingService::class.java.name)
        val extra = ProductionScopeImporter(BytecodeReader { ClassFileImporter().importClasses(*types, NewHelper::class.java) })
        assertFailure(load(root, extra), ScopeProblemCode.UNEXPECTED_IMPORTED_TYPE, NewHelper::class.java.name)
    }
    @Test fun `APP-14 필수 타입 파일 누락과 빈 출력은 통과하지 않는다`() {
        assertFailure(load(fixtureOutput(directory.resolve("missing"), *types.filter { it != SubmissionService::class.java }.toTypedArray())),
            ScopeProblemCode.MISSING_REQUIRED_TYPE, SubmissionService::class.java.name)
        assertFailure(load(Files.createDirectories(directory.resolve("empty"))), ScopeProblemCode.EMPTY_MODULE, "application-example")
    }
    @Test fun `APP-14 다른 경로의 같은 클래스 정의를 거절한다`() {
        val first = fixtureOutput(directory.resolve("first"), *types)
        val second = fixtureOutput(directory.resolve("second"), FundingService::class.java)
        assertFailure(loadRoots(listOf(first, second)), ScopeProblemCode.DUPLICATE_TYPE, FundingService::class.java.name)
    }
    @Test fun `APP-14 실제 손상 파일과 읽기 도구 오류를 준비 실패로 전달한다`() {
        val root = fixtureOutput(directory.resolve("broken"), *types)
        val file = root.resolve("broken.class")
        Files.write(file, byteArrayOf(1, 2, 3))
        assertFailure(load(root), ScopeProblemCode.READ_FAILURE, file.toRealPath().toString())
        Files.delete(file)
        assertFailure(load(root, ProductionScopeImporter(BytecodeReader { error("읽기 실패 예제") })), ScopeProblemCode.READ_FAILURE, "bytecode-reader")
    }
    @Test fun `APP-08 상속 정의가 없으면 컨테이너가 아니라고 추정하지 않는다`() {
        val name = "$APPLICATION_FIXTURES.application.UnresolvedWork"
        val bytes = ClassFile.of().build(ClassDesc.of(name)) { builder ->
            builder.withSuperclass(ClassDesc.of("outside.library.MissingContainerParent"))
        }
        val file = directory.resolve("UnresolvedWork.class")
        Files.write(file, bytes)
        val imported = ClassFileImporter().importPaths(file)
        val scope = applicationScope(*types).let { it.copy(classesByModule = it.classesByModule + ("unresolved" to imported)) }
        val registration = applicationRegistration().let { it.copy(types = it.types +
            (ApplicationRole.APPLICATION to (it.types.getValue(ApplicationRole.APPLICATION) + name))) }
        val result = ApplicationImplementationIndependence.inspect(scope, registration)
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNRESOLVED_TYPE_HIERARCHY && it.subject == "outside.library.MissingContainerParent" }, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
    }
    @Test fun `APP-04 실제 출력에서 찾은 구체 저장 참조를 위반으로 보고한다`() {
        val root = fixtureOutput(directory.resolve("violation"), *types, FieldWork::class.java)
        val result = ApplicationImplementationIndependence.inspect(load(root),
            applicationRegistration().with(ApplicationRole.APPLICATION, FieldWork::class.java))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(FieldWork::class.java.name to PostgresFundsStore::class.java.name), result.pairs())
        assertTrue(result.violations.all { it.ruleId == "ARCH-04" && it.sourceFile == "ReferenceExamples.kt" })
    }
    @Test fun `APP-14 config만 수집하고 업무 대상이 없으면 통과하지 않는다`() {
        val name = "$APPLICATION_FIXTURES.config.OnlyAssembly"
        val file = directory.resolve("OnlyAssembly.class")
        Files.write(file, ClassFile.of().build(ClassDesc.of(name)) { })
        val scope = ScopeImportResult(mapOf("config-only" to ClassFileImporter().importPaths(file)))
        val registration = applicationRegistration().copy(types = mapOf(ApplicationRole.CONFIGURATION to setOf(name)))
        val result = ApplicationImplementationIndependence.inspect(scope, registration)
        assertFalse(result.evaluated)
        assertEquals(listOf(ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "APPLICATION")), result.problems)
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
    }
    private fun load(root: Path, importer: ProductionScopeImporter = ProductionScopeImporter()) = loadRoots(listOf(root), importer)
    private fun loadRoots(roots: List<Path>, importer: ProductionScopeImporter = ProductionScopeImporter()) = importer.load(
        listOf(ModuleOutput("application-example", roots)), ScopeExpectations(
            requiredTypesByModule = mapOf("application-example" to types.map { it.name }.toSet()), projectPackagePrefixes = setOf("$APPLICATION_FIXTURES.")))
    private fun inspect(scope: ScopeImportResult) = ApplicationImplementationIndependence.inspect(scope, applicationRegistration())
    private fun assertFailure(scope: ScopeImportResult, code: ScopeProblemCode, subject: String) {
        val result = inspect(scope)
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == code && it.subject == subject }, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
        assertEquals(emptySet(), result.applicationTypes)
    }
}

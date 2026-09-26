package com.exchange.architecture

import com.exchange.architecture.fixtures.httpboundary.web.*
import com.exchange.architecture.rules.HttpEntryBoundary
import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 운영 출력은 건드리지 않고 실제 예제 바이트를 임시 폴더에서 누락·손상시킨다. */
class HttpBoundaryImportTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `HTTP-14 출력 전체를 읽어 역할에 등록하지 않은 매퍼도 발견한다`() {
        val root = fixtureOutput(directory.resolve("examples"), *normalTypes, UnclassifiedMapper::class.java)
        val scope = load(listOf(root))
        assertEquals(emptyList(), scope.problems)
        assertTrue(scope.classesByModule.getValue("http-example").any { it.name == UnclassifiedMapper::class.java.name })
        val result = HttpEntryBoundary.inspect(scope, normalRegistration())
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNCLASSIFIED_HTTP_TYPE && it.subject == UnclassifiedMapper::class.java.name })
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `HTTP-14 출력과 가져온 목록이 다르면 누락 타입을 보고한다`() {
        val root = fixtureOutput(directory.resolve("examples"), *normalTypes)
        val importer = ProductionScopeImporter(BytecodeReader { paths ->
            ClassFileImporter().importPaths(paths).that(DescribedPredicate.describe("고의로 컨트롤러 누락") {
                it.name != NormalController::class.java.name
            })
        })
        val result = HttpEntryBoundary.inspect(load(listOf(root), importer), normalRegistration())
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.INCOMPLETE_IMPORT && it.subject == NormalController::class.java.name })
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `HTTP-12 필수 컨트롤러 파일이 없으면 통과하지 않는다`() {
        val root = fixtureOutput(directory.resolve("examples"), *normalTypes.filter { it != NormalController::class.java }.toTypedArray())
        assertPreparationFailure(load(listOf(root)), ScopeProblemCode.MISSING_REQUIRED_TYPE, NormalController::class.java.name)
    }

    @Test
    fun `HTTP-14 서로 다른 파일의 중복 정의를 거절한다`() {
        val root = fixtureOutput(directory.resolve("examples"), *normalTypes)
        val duplicate = fixtureOutput(directory.resolve("duplicate"), NormalController::class.java)
        assertPreparationFailure(load(listOf(root, duplicate)), ScopeProblemCode.DUPLICATE_TYPE, NormalController::class.java.name)
    }

    @Test
    fun `HTTP-14 클래스 손상과 읽기 도구 오류를 준비 실패로 전파한다`() {
        val root = fixtureOutput(directory.resolve("examples"), *normalTypes)
        val broken = root.resolve("broken.class")
        Files.write(broken, byteArrayOf(1, 2, 3))
        assertPreparationFailure(load(listOf(root)), ScopeProblemCode.READ_FAILURE, broken.toRealPath().toString())
        Files.delete(broken)
        val importer = ProductionScopeImporter(BytecodeReader { error("예제 읽기 실패") })
        assertPreparationFailure(load(listOf(root), importer), ScopeProblemCode.READ_FAILURE, "bytecode-reader")
    }

    @Test
    fun `HTTP-12 비어 있는 출력 디렉터리를 성공으로 처리하지 않는다`() {
        val root = Files.createDirectories(directory.resolve("empty"))
        assertPreparationFailure(load(listOf(root)), ScopeProblemCode.EMPTY_MODULE, "http-example")
    }

    @Test
    fun `HTTP-01 실제 출력 수집부터 정상 예제 판정까지 연결된다`() {
        val result = HttpEntryBoundary.inspect(load(listOf(fixtureOutput(directory.resolve("examples"), *normalTypes))), normalRegistration())
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertTrue(result.apiTypes.contains(NormalController::class.java.name))
        assertTrue(result.referenceCount > 0)
        println("ARCH-03: 예제 검증 / 운영 미적용 · API 타입 ${result.apiTypes.size}개 · 직접 참조 ${result.referenceCount}개 · 준비 오류 0 · 위반 0")
    }

    private fun load(roots: List<Path>, importer: ProductionScopeImporter = ProductionScopeImporter()) = importer.load(
        listOf(ModuleOutput("http-example", roots)),
        ScopeExpectations(requiredTypesByModule = mapOf("http-example" to normalTypes.map { it.name }.toSet()),
            projectPackagePrefixes = setOf("$HTTP_FIXTURE_PACKAGE.")),
    )

    private fun assertPreparationFailure(scope: ScopeImportResult, code: ScopeProblemCode, subject: String) {
        val result = HttpEntryBoundary.inspect(scope, normalRegistration())
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == code && it.subject == subject }, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
    }
}

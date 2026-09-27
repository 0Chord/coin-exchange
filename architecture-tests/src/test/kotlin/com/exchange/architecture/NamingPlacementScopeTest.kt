package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.policy.*
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NamingPlacementScopeTest {
    @TempDir lateinit var root: Path
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private val folder = AllowedFolder("orders", "app-api", "src/main/kotlin", pkg.replace('.', '/'), setOf(NamingRole.USE_CASE, NamingRole.SERVICE, NamingRole.DATA, NamingRole.FILE_FACADE), "예제")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private fun scope(vararg types: Class<*>) = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types)))
    private fun inspect(s: ScopeImportResult, p: LayoutPolicy = policy) = RoleNamingPlacement.inspectTypes(s, p, setOf("app-api"))
    @Test fun `AUTO-17 중첩 데이터는 자기 역할 companion과 익명 타입은 소유자로 연결한다`() {
        val anonymous = SubmitOrderUseCase().callback().javaClass
        val r = inspect(scope(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java, SubmitOrderUseCase.Companion::class.java, anonymous))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(mapOf(SubmitOrderUseCase.Companion::class.java.name to SubmitOrderUseCase::class.java.name, anonymous.name to SubmitOrderUseCase::class.java.name), r.generatedOwners)
        assertEquals(setOf(NamingRole.DATA), r.classifications.getValue(SubmitOrderUseCase.Result::class.java.name).roles)
    }
    @Test fun `AUTO-17 소유자가 없으면 생성 타입을 면제하지 않는다`() {
        val r = inspect(scope(SubmitOrderUseCase.Companion::class.java))
        assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedTypes)
    }
    @Test fun `AUTO-17 기본과 JvmName 함수 운반 타입은 메타데이터로 발견한다`() {
        for (simple in listOf("NamingFixturesKt", "NamedQuoteFunctions")) {
            val carrier = Class.forName("$pkg.$simple", false, javaClass.classLoader)
            val r = inspect(scope(SubmitOrderUseCase::class.java, carrier))
            assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
            assertEquals(setOf(NamingRole.FILE_FACADE), r.classifications.getValue(carrier.name).roles)
            val denied = inspect(scope(SubmitOrderUseCase::class.java, carrier), policy.copy(folders = listOf(folder.copy(roles = setOf(NamingRole.USE_CASE)))))
            assertTrue(denied.violations.any { it.subject == carrier.name && it.item == "package" })
        }
    }
    @Test fun `AUTO-22 같은 타입의 복수 모듈 소속은 미평가다`() {
        val classes = ClassFileImporter().importClasses(SubmitOrderUseCase::class.java)
        val r = RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to classes, "domain-order" to classes)), policy, setOf("app-api", "domain-order"))
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.code == ScopeProblemCode.DUPLICATE_TYPE })
    }
    @Test fun `AUTO-23 중복 규칙과 빈 기술 및 없는 위치 정책은 준비 오류다`() {
        val s = scope(SubmitOrderUseCase::class.java)
        for (naming in listOf(policy.naming.copy(rules = policy.naming.rules + policy.naming.rules.first()),
            policy.naming.copy(storeTechnologies = emptySet()),
            policy.naming.copy(rules = policy.naming.rules.filter { it.role != NamingRole.USE_CASE }), policy.naming.copy(storeTargets = mapOf("missing" to "unknown")))) {
            val r = inspect(s, policy.copy(naming = naming))
            assertFalse(r.evaluated); assertEquals(emptyList(), r.violations); assertEquals(emptySet(), r.evaluatedTypes)
        }
    }
    @Test fun `NAME-15 삭제 손상 읽기 누락은 기존 수집 오류를 유지한다`() {
        val output = fixtureOutput(root.resolve("classes"), SubmitOrderUseCase::class.java, NewHelper::class.java)
        val expectations = ScopeExpectations(mapOf("app-api" to setOf(SubmitOrderUseCase::class.java.name)))
        val helper = output.resolve(NewHelper::class.java.name.replace('.', '/') + ".class")
        val missingRead = ProductionScopeImporter(BytecodeReader { ClassFileImporter().importClasses(SubmitOrderUseCase::class.java) })
            .load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(missingRead.problems.any { it.code == ScopeProblemCode.INCOMPLETE_IMPORT })
        assertFalse(inspect(missingRead).evaluated)
        Files.write(helper, byteArrayOf(1, 2, 3))
        val damaged = ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(damaged.problems.any { it.code == ScopeProblemCode.READ_FAILURE }); assertFalse(inspect(damaged).evaluated)
        Files.delete(output.resolve(SubmitOrderUseCase::class.java.name.replace('.', '/') + ".class"))
        assertFalse(inspect(ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)).evaluated)
    }
    @Test fun `AUTO-28 목표 정책은 개별 타입 목록 없이 16개 위치의 역할을 제한한다`() {
        val p = ProjectLayoutPolicy.target
        assertEquals(emptyList(), LayoutPolicyValidation.inspect(p, ProductionScope.requiredTypes.keys))
        assertEquals(emptyList(), NamingClassificationPolicy.validate(p)); assertEquals(16, p.folders.size)
        assertEquals("com.exchange.core.ledger", p.folders.single { it.id == "domain-ledger" }.packageName)
        assertEquals("com.exchange.core.api.matching.application.port", p.folders.single { it.id == "matching-ports" }.packageName)
        assertTrue(p.folders.filter { it.module.startsWith("domain-") }.none { NamingRole.USE_CASE in it.roles })
        assertEquals(setOf(NamingRole.CONFIGURATION), p.folders.single { it.id == "config" }.roles)
        assertEquals(DataNames.ERROR, p.folders.single { it.id == "http-errors" }.dataNames)
        assertFalse(NamingRole.CALCULATOR in p.folders.single { it.id == "domain-ledger" }.roles)
        assertFalse(NamingRole.STORE_PORT in p.folders.single { it.id == "domain-matching" }.roles)
        assertFalse(NamingRole.FILE_FACADE in p.folders.single { it.id == "order-application" }.roles)
    }
}

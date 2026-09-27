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
    private val folder = AllowedFolder("orders", "app-api", "src/main/kotlin", pkg.replace('.', '/'), NamingRole.entries.toSet(), "예제")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private val good = NamingBinding(SubmitOrderUseCase::class.java.name, NamingRole.USE_CASE, "orders")
    private fun scope(vararg types: Class<*>) = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types)))
    private fun inspect(s: ScopeImportResult, b: List<NamingBinding> = listOf(good), p: LayoutPolicy = policy) =
        RoleNamingPlacement.inspectTypes(s, b, p, setOf("app-api"))

    @Test fun `NAME-11 중첩 데이터에는 자기 역할을 쓰고 companion과 익명 타입은 확인한 소유자에 귀속한다`() {
        val anonymous = SubmitOrderUseCase().callback().javaClass
        val s = scope(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java, SubmitOrderUseCase.Companion::class.java, anonymous)
        val r = inspect(s, listOf(good, NamingBinding(SubmitOrderUseCase.Result::class.java.name, NamingRole.DATA, "orders", reason = "실행 결과")))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(mapOf(SubmitOrderUseCase.Companion::class.java.name to good.type, anonymous.name to good.type), r.generatedOwners)
        assertEquals(4, r.evaluatedTypes.size)
    }
    @Test fun `NAME-12 이름 있는 중첩 타입을 자동 상속 역할로 숨기지 않는다`() {
        val r = inspect(scope(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java))
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.subject == SubmitOrderUseCase.Result::class.java.name })
    }
    @Test fun `NAME-11 최상위 함수 운반 타입은 명시한 지원 역할로 위치를 검사한다`() {
        val carrier = Class.forName("$pkg.NamingFixturesKt", false, javaClass.classLoader)
        val support = NamingBinding(carrier.name, NamingRole.SUPPORT, "orders", reason = "최상위 계산 함수")
        val r = inspect(scope(SubmitOrderUseCase::class.java, carrier), listOf(good, support))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        val moved = policy.copy(folders = listOf(folder.copy(folder = "com/elsewhere")))
        assertTrue(inspect(scope(SubmitOrderUseCase::class.java, carrier), listOf(good, support), moved).violations.any { it.subject == carrier.name && it.item == "package" })
    }
    @Test fun `NAME-11 JvmName 운반 타입도 실제 이름으로 등록한다`() {
        val carrier = Class.forName("$pkg.NamedQuoteFunctions", false, javaClass.classLoader)
        val support = NamingBinding(carrier.name, NamingRole.SUPPORT, "orders", reason = "JvmName 최상위 계산")
        val r = inspect(scope(SubmitOrderUseCase::class.java, carrier), listOf(good, support))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertFalse(inspect(scope(SubmitOrderUseCase::class.java, carrier)).evaluated)
    }
    @Test fun `NAME-14 같은 타입의 복수 모듈 소속은 검사하지 않는다`() {
        val classes = ClassFileImporter().importClasses(SubmitOrderUseCase::class.java)
        val r = RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to classes, "domain-order" to classes)),
            listOf(good), policy, setOf("app-api", "domain-order"))
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.code == ScopeProblemCode.DUPLICATE_TYPE })
    }
    @Test fun `NAME-14 존재하지 않는 위치와 저장 기술 등록을 거절한다`() {
        for (binding in listOf(good.copy(location = "missing"),
            NamingBinding(PostgresBalanceStore::class.java.name, NamingRole.STORE_IMPLEMENTATION, "orders", technology = "Unknown"))) {
            val type = if (binding.role == NamingRole.USE_CASE) SubmitOrderUseCase::class.java else PostgresBalanceStore::class.java
            assertFalse(inspect(scope(type), listOf(binding)).evaluated)
        }
    }
    @Test fun `NAME-14 필수 역할 빈 목록과 기존 의존 역할 충돌을 검출한다`() {
        val s = scope(SubmitOrderUseCase::class.java)
        val missing = RoleNamingPlacement.inspectTypes(s, listOf(good), policy, setOf("app-api"), setOf(NamingRole.STORE_PORT))
        assertFalse(missing.evaluated); assertTrue(missing.problems.any { it.code == ScopeProblemCode.EMPTY_ROLE })
        val conflicting = RoleNamingPlacement.inspectTypes(s, listOf(good), policy, setOf("app-api"),
            applicationRoles = mapOf(good.type to ApplicationRole.CONFIGURATION), httpRoles = mapOf(good.type to HttpRole.PORT))
        assertFalse(conflicting.evaluated); assertEquals(2, conflicting.problems.size)
    }
    @Test fun `NAME-15 삭제 손상 읽기 누락은 기존 수집 오류를 유지한다`() {
        val output = fixtureOutput(root.resolve("classes"), SubmitOrderUseCase::class.java, NewHelper::class.java)
        val expectations = ScopeExpectations(mapOf("app-api" to setOf(good.type)))
        val helper = output.resolve(NewHelper::class.java.name.replace('.', '/') + ".class")
        val missingRead = ProductionScopeImporter(BytecodeReader { ClassFileImporter().importClasses(SubmitOrderUseCase::class.java) })
            .load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(missingRead.problems.any { it.code == ScopeProblemCode.INCOMPLETE_IMPORT })
        assertFalse(inspect(missingRead).evaluated)
        Files.write(helper, byteArrayOf(1, 2, 3))
        val damaged = ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(damaged.problems.any { it.code == ScopeProblemCode.READ_FAILURE }); assertFalse(inspect(damaged).evaluated)
        Files.delete(output.resolve(good.type.replace('.', '/') + ".class"))
        assertFalse(inspect(ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)).evaluated)
    }
    @Test fun `NAME-08과09 실제 목표 정책은 도메인 포트와 앱 포트 및 조립 위치를 구분한다`() {
        val p = ProjectLayoutPolicy.target
        assertEquals(emptyList(), LayoutPolicyValidation.inspect(p, ProductionScope.requiredTypes.keys))
        assertEquals(16, p.folders.size)
        assertEquals("com.exchange.core.ledger", p.folders.single { it.id == "domain-ledger" }.packageName)
        assertEquals("com.exchange.core.api.matching.application.port", p.folders.single { it.id == "matching-ports" }.packageName)
        assertTrue(p.folders.filter { it.module.startsWith("domain-") }.none { NamingRole.USE_CASE in it.roles })
        assertEquals(setOf(NamingRole.CONFIGURATION), p.folders.single { it.id == "config" }.roles)
        assertEquals(2, p.folders.single { it.id == "http-errors" }.onlyTypes!!.size)
        assertTrue(NamingRole.CALCULATOR in p.folders.single { it.id == "domain-fee" }.roles)
        assertFalse(NamingRole.CALCULATOR in p.folders.single { it.id == "domain-ledger" }.roles)
        assertFalse(NamingRole.STORE_PORT in p.folders.single { it.id == "domain-matching" }.roles)
    }
    @Test fun `NAME-10 입력 순서를 바꿔도 대상 항목별 보고는 같다`() {
        val types = arrayOf(OrderSubmissionService::class.java, ReserveOrderUseCase::class.java)
        val bindings = types.map { good.copy(type = it.name, exactName = "SubmitOrderUseCase") }
        assertEquals(inspect(scope(*types), bindings), inspect(scope(*types.reversedArray()), bindings.reversed()))
    }
    @Test fun `NAME-16 지원 코드 이유 누락과 모든 역할 면제를 거절한다`() {
        val r = inspect(scope(SubmitOrderUseCase::class.java, Request::class.java), listOf(good, NamingBinding(Request::class.java.name, NamingRole.SUPPORT, "orders")))
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.subject == Request::class.java.name })
    }
    @Test fun `NAME-26과28 새 경로 허용은 다른 역할의 위치 허용으로 번지지 않는다`() {
        val p = policy.copy(folders = listOf(folder.copy(roles = setOf(NamingRole.USE_CASE))))
        val r = inspect(scope(OrderFundingService::class.java), listOf(NamingBinding(OrderFundingService::class.java.name, NamingRole.SERVICE, "orders")), p)
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.detail?.contains("역할·허용 위치") == true })
    }
}

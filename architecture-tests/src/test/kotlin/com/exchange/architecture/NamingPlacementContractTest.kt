package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class NamingPlacementContractTest {
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private val folder = AllowedFolder("orders", "app-api", "src/main/kotlin", pkg.replace('.', '/'), setOf(NamingRole.USE_CASE, NamingRole.SERVICE, NamingRole.DATA), "명명 예제")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private fun scope(vararg types: Class<*>) = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types)))
    private fun inspect(vararg types: Class<*>, p: LayoutPolicy = policy) = RoleNamingPlacement.inspectTypes(scope(*types), p, p.roots.map { it.module }.toSet())

    @Test fun `AUTO-02 접미사만 있는 UseCase는 이름 위반이다`() {
        val r = inspect(UseCase::class.java)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(listOf("name"), r.violations.map { it.item })
        assertEquals("대상+UseCase", r.violations.single().expected)
    }
    @Test fun `AUTO-27 허용된 Service와 UseCase 사이 업무 의미는 리뷰한다`() {
        val r = inspect(OrderSubmissionService::class.java, ReserveOrderUseCase::class.java)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(NamingRole.SERVICE), r.classifications.getValue(OrderSubmissionService::class.java.name).roles)
        assertEquals(setOf(NamingRole.USE_CASE), r.classifications.getValue(ReserveOrderUseCase::class.java.name).roles)
    }
    @Test fun `AUTO-03 이름 패키지 모듈의 틀린 항목을 각각 보고한다`() {
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("domain-ledger", "src/main/kotlin"),
            folders = listOf(folder.copy(module = "domain-ledger", folder = "com/exchange/core/ledger")))
        val r = inspect(UseCase::class.java, p = p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(setOf("name", "package", "module"), r.violations.map { it.item }.toSet())
        assertTrue(r.violations.all { it.ruleId == "ARCH-05" && it.lineNumber == null })
    }
    @Test fun `AUTO-18 Kt Helper 달러 이름만으로 검사에서 제외하지 않는다`() {
        val r = inspect(NewHelper::class.java, ForgedKt::class.java, `Forged$Helper`::class.java)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(3, r.violations.size); assertEquals(setOf("unclassified"), r.violations.map { it.item }.toSet())
        assertEquals(3, r.evaluatedTypes.size); assertEquals(emptyMap(), r.generatedOwners)
    }
    @Test fun `AUTO-22 빈 대상과 데이터만 있는 업무 범위는 미평가다`() {
        for (r in listOf(RoleNamingPlacement.inspectTypes(ScopeImportResult(), policy, setOf("app-api")), inspect(AmendOrderRequest::class.java))) {
            assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedTypes); assertEquals(emptyList(), r.violations)
        }
    }
    @Test fun `AUTO-07 HTTP 데이터의 구조와 이름을 함께 검사한다`() {
        val p = policy.copy(folders = listOf(folder.copy(dataNames = DataNames.HTTP)))
        val r = inspect(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java, AmendOrderRequest::class.java, OrderState::class.java, p = p)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(listOf(SubmitOrderUseCase.Result::class.java.name to "name"), r.violations.map { it.subject to it.item })
    }
    @Test fun `AUTO-16 공통 오류 데이터도 ErrorResponse 형식을 지켜야 한다`() {
        val p = policy.copy(folders = listOf(folder.copy(dataNames = DataNames.ERROR)))
        val r = inspect(SubmitOrderUseCase::class.java, ApiErrorResponse::class.java, AmendOrderRequest::class.java, OrderState::class.java, p = p)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(setOf(AmendOrderRequest::class.java.name, OrderState::class.java.name), r.violations.map { it.subject }.toSet())
        assertEquals(setOf("name"), r.violations.map { it.item }.toSet())
    }
    @Test fun `AUTO-23 규칙 순서와 폴더 순서는 결과를 바꾸지 않는다`() {
        val other = folder.copy(id = "other", folder = "com/other")
        val p = policy.copy(folders = listOf(folder, other))
        val types = arrayOf(UseCase::class.java, OrderManager::class.java, AmendOrderUseCase::class.java)
        assertEquals(inspect(*types, p = p), inspect(*types.reversedArray(), p = p.copy(folders = p.folders.reversed(), naming = p.naming.copy(rules = p.naming.rules.reversed()))))
    }
}

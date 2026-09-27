package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class NamingPlacementContractTest {
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private val folder = AllowedFolder("orders", "app-api", "src/main/kotlin", pkg.replace('.', '/'),
        NamingRole.entries.toSet(), "명명 역할별 독립 예제")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private val good = NamingBinding(SubmitOrderUseCase::class.java.name, NamingRole.USE_CASE, "orders", "SubmitOrderUseCase")
    private fun scope(vararg types: Class<*>) = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types)))
    private fun check(type: Class<*>, binding: NamingBinding = good.copy(type = type.name), p: LayoutPolicy = policy) =
        RoleNamingPlacement.inspectTypes(scope(type), listOf(binding), p, setOf("app-api"))

    @Test fun `NAME-01 역할마다 합의한 접미사와 위치를 사용한다`() {
        val cases = listOf(SubmitOrderUseCase::class.java to NamingRole.USE_CASE,
            OrderFundingService::class.java to NamingRole.SERVICE, MatchingCoordinator::class.java to NamingRole.COORDINATOR,
            TradingFeeCalculator::class.java to NamingRole.CALCULATOR, FeeTierResolver::class.java to NamingRole.RESOLVER,
            BalanceStore::class.java to NamingRole.STORE_PORT, PostgresBalanceStore::class.java to NamingRole.STORE_IMPLEMENTATION,
            MatchingEventRepository::class.java to NamingRole.REPOSITORY, MatchingEventPublisher::class.java to NamingRole.PUBLISHER,
            PersistentMatchingEventPublisher::class.java to NamingRole.PUBLISHER, NoOpMatchingEventPublisher::class.java to NamingRole.PUBLISHER,
            OrderController::class.java to NamingRole.CONTROLLER, OrderConfig::class.java to NamingRole.CONFIGURATION)
        for ((type, role) in cases) {
            val r = check(type, NamingBinding(type.name, role, "orders", technology = if (role == NamingRole.STORE_IMPLEMENTATION) "Postgres" else null,
                reason = if (role.suffix == null) "합의한 데이터 이름" else null))
            assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
            assertEquals(setOf(type.name), r.evaluatedTypes)
        }
    }
    @Test fun `NAME-02 제출 진입점의 기존 이름과 의미 없는 UseCase를 거절한다`() {
        for (type in listOf(OrderSubmissionService::class.java, UseCase::class.java)) {
            val r = check(type)
            assertTrue(r.evaluated); assertEquals(listOf("name"), r.violations.map { it.item })
            assertEquals(type.name, r.violations.single().subject)
            assertEquals("SubmitOrderUseCase", r.violations.single().expected)
        }
    }
    @Test fun `NAME-03 이름을 UseCase로 바꿔도 내부 작업의 역할은 바뀌지 않는다`() {
        val r = check(ReserveOrderUseCase::class.java, good.copy(type = ReserveOrderUseCase::class.java.name,
            role = NamingRole.SERVICE, exactName = null))
        assertEquals(listOf("name"), r.violations.map { it.item }); assertEquals("대상+Service", r.violations.single().expected)
    }
    @Test fun `NAME-04 계산기와 기준 선택기를 서로 바꾸면 이름 위반이다`() {
        for ((type, role) in listOf(TradingFeeCalculator::class.java to NamingRole.RESOLVER, FeeTierResolver::class.java to NamingRole.CALCULATOR)) {
            assertEquals(listOf("name"), check(type, NamingBinding(type.name, role, "orders")).violations.map { it.item })
        }
    }
    @Test fun `NAME-05 저장 포트 구현과 Repository의 이름을 구분한다`() {
        for ((type, role, tech) in listOf(Triple(BalanceStore::class.java, NamingRole.REPOSITORY, null),
            Triple(MatchingEventRepository::class.java, NamingRole.STORE_PORT, null),
            Triple(BalanceStore::class.java, NamingRole.STORE_IMPLEMENTATION, "Postgres"))) {
            assertEquals(listOf("name"), check(type, NamingBinding(type.name, role, "orders", technology = tech)).violations.map { it.item })
        }
    }
    @Test fun `NAME-06과07과10 이름 패키지 모듈의 틀린 항목을 각각 안정적으로 보고한다`() {
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("domain-ledger", "src/main/kotlin"),
            folders = listOf(folder.copy(module = "domain-ledger", folder = "com/exchange/core/ledger")))
        val r = RoleNamingPlacement.inspectTypes(scope(OrderSubmissionService::class.java),
            listOf(good.copy(type = OrderSubmissionService::class.java.name)), p, setOf("app-api", "domain-ledger"))
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(setOf("name", "package", "module"), r.violations.map { it.item }.toSet())
        assertEquals(3, r.violations.size); assertTrue(r.violations.all { it.ruleId == "ARCH-05" && it.lineNumber == null })
    }
    @Test fun `NAME-13 새 타입을 이름이나 위치로 자동 등록하지 않는다`() {
        for (type in listOf(NewHelper::class.java, ForgedKt::class.java, `Forged$Helper`::class.java)) {
            val r = RoleNamingPlacement.inspectTypes(scope(SubmitOrderUseCase::class.java, type), listOf(good), policy, setOf("app-api"))
            assertFalse(r.evaluated); assertTrue(r.problems.any { it.subject == type.name })
            assertEquals(emptySet(), r.evaluatedTypes); assertEquals(emptyList(), r.violations)
        }
    }
    @Test fun `NAME-14 빈 대상 역할 누락과 역할 충돌은 평가하지 않는다`() {
        val inputs = listOf(ScopeImportResult() to listOf(good), scope(SubmitOrderUseCase::class.java) to emptyList(),
            scope(SubmitOrderUseCase::class.java) to listOf(good, good.copy(role = NamingRole.SERVICE)))
        for ((s, b) in inputs) {
            val r = RoleNamingPlacement.inspectTypes(s, b, policy, setOf("app-api"))
            assertFalse(r.evaluated); assertEquals(emptyList(), r.violations); assertEquals(emptySet(), r.evaluatedTypes)
        }
    }
    @Test fun `NAME-16 위치만 검사하는 지원 코드도 이유와 업무 대상이 필요하다`() {
        val binding = NamingBinding(Request::class.java.name, NamingRole.SUPPORT, "orders", reason = "HTTP 데이터")
        val r = check(Request::class.java, binding)
        assertFalse(r.evaluated)
    }
}

package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class NamingPlacementAutoRolesTest {
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private fun inspect(vararg types: Class<*>, module: String = "app-api", roles: Set<NamingRole> = NamingRole.entries.toSet()): PlacementResult {
        val policy = LayoutPolicy(listOf(AllowedSourceRoot(module, "src/main/kotlin")),
            listOf(AllowedFolder("example", module, "src/main/kotlin", pkg.replace('.', '/'), roles, "역할 판별 예제")))
        return RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf(module to ClassFileImporter().importClasses(*types))), policy, setOf(module))
    }
    private fun items(type: Class<*>) = inspect(type).also { assertTrue(it.evaluated, it.problems.toString()) }.violations.map { it.item }

    @Test fun `AUTO-04 interface와 data는 업무 실행 클래스 형태가 아니다`() {
        assertEquals(listOf("roleShape"), items(WrongUseCase::class.java))
        assertEquals(listOf("roleShape"), items(WrongService::class.java))
        assertEquals(listOf("roleShape"), items(AbstractUseCase::class.java))
        assertEquals(listOf("roleShape"), items(EnumUseCase::class.java))
        assertEquals(emptyList(), items(SweepUseCase::class.java))
    }
    @Test fun `AUTO-05 Service 어노테이션은 이름 규칙을 대체하지 않는다`() {
        assertEquals(emptyList(), items(CancelOrderUseCase::class.java))
        assertEquals(listOf("unclassified"), items(AnnotatedOrderManager::class.java))
    }
    @Test fun `AUTO-06 기술 역할은 이름도 지키며 충돌은 임의 선택하지 않는다`() {
        assertEquals(listOf("name"), items(OrderEndpoint::class.java))
        assertEquals(listOf("name"), items(Wiring::class.java))
        assertEquals(listOf("roleConflict"), items(HttpOrderUseCase::class.java))
        assertEquals(emptyList(), items(HttpOrderController::class.java))
        assertEquals(emptyList(), items(ExplicitOrderConfig::class.java))
    }
    @Test fun `AUTO-07 이름 있는 중첩 데이터와 새 DTO는 자동 분류한다`() {
        val r = inspect(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java, AmendOrderRequest::class.java)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(SubmitOrderUseCase::class.java.name, SubmitOrderUseCase.Result::class.java.name, AmendOrderRequest::class.java.name), r.evaluatedTypes)
    }
    @Test fun `AUTO-08 데이터처럼 보이는 이름과 실제 역할 충돌을 구분한다`() {
        assertEquals(listOf("unclassified"), items(FakeRequest::class.java))
        assertEquals(listOf("roleShape"), items(DataUseCase::class.java))
        assertEquals(listOf("roleConflict"), items(EntityService::class.java))
    }
    @Test fun `AUTO-09 도메인 일반 코어는 접미사를 강제하지 않는다`() {
        val r = inspect(BalancePolicy::class.java, CoreEvent::class.java, CoreAmount::class.java, module = "domain-ledger")
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        val bad = inspect(AmendOrderUseCase::class.java, module = "domain-ledger", roles = setOf(NamingRole.DOMAIN, NamingRole.DATA))
        assertTrue(bad.violations.isNotEmpty())
    }
    @Test fun `AUTO-10 의미가 다른 계산 선택 이름의 형식은 모두 허용된다`() {
        assertEquals(emptyList(), inspect(TradingFeeCalculator::class.java, FeeTierResolver::class.java, module = "domain-fee").violations)
    }
    @Test fun `AUTO-27 Calculator와 Resolver를 다른 허용 도메인으로 옮겨도 의미는 자동 판단하지 않는다`() {
        for (module in listOf("domain-fee", "domain-order")) {
            val r = inspect(TradingFeeCalculator::class.java, FeeTierResolver::class.java, module = module)
            assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        }
    }
    @Test fun `AUTO-14 실제 JpaRepository 계층과 이름뿐인 Repository를 구분한다`() {
        assertEquals(emptyList(), items(ActualEventRepository::class.java))
        assertEquals(listOf("roleConflict"), items(WrongEventStore::class.java))
        assertEquals(listOf("roleShape"), items(FakeRepository::class.java))
    }
    @Test fun `AUTO-16 Boot Advice Entity는 이름별 등록 없이 기술 역할로 분류한다`() {
        val r = inspect(TestApplication::class.java, ApiExceptionHandler::class.java, ApiErrorResponse::class.java, JournalEntity::class.java)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
    }
}

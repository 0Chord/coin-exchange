package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import kotlin.test.*

class NamingPlacementRuleTest {
    @Test fun `UseCase Service Coordinator는 구체 선언과 각각의 위치를 검사한다`() {
        val p = namingPolicy("order-application" to namingPkg)
        val r = namingInspect(p, CancelOrderUseCase::class.java, WrongService::class.java, DataUseCase::class.java, SweepUseCase::class.java,
            WrongUseCase::class.java, AbstractUseCase::class.java, EnumUseCase::class.java, UseCase::class.java)
        r.assertReady()
        for (t in listOf(CancelOrderUseCase::class.java, WrongService::class.java, DataUseCase::class.java, SweepUseCase::class.java)) assertEquals(emptySet(), r.items(t))
        for (t in listOf(WrongUseCase::class.java, AbstractUseCase::class.java, EnumUseCase::class.java)) assertEquals(setOf("declaration"), r.items(t))
        assertEquals(setOf("name"), r.items(UseCase::class.java))
        val normal = namingInspect(namingPolicy("matching-application" to namingPkg), MatchingCoordinator::class.java)
        normal.assertReady(); assertEquals(emptyList(), normal.violations)
        assertEquals(setOf("package"), namingInspect(p, MatchingCoordinator::class.java).items(MatchingCoordinator::class.java))
    }
    @Test fun `UseCase와 Service의 업무상 정확한 이름은 형식 검사로 결정하지 않는다`() {
        val r = namingInspect(namingPolicy("order-application" to namingPkg), OrderSubmissionService::class.java, ReserveOrderUseCase::class.java)
        r.assertReady(); assertEquals(emptyList(), r.violations)
    }
    @Test fun `코어 모듈의 UseCase는 허용하지 않는다`() {
        val r = namingInspect(namingPolicy("domain-order" to namingPkg), CancelOrderUseCase::class.java, module = "domain-order")
        r.assertReady(); assertTrue("module" in r.items(CancelOrderUseCase::class.java))
    }
    @Test fun `Calculator와 Resolver는 fee order의 일반 클래스나 인터페이스다`() {
        for (module in listOf("domain-fee", "domain-order")) {
            val p = namingPolicy(module to namingPkg)
            val r = namingInspect(p, TradingFeeCalculator::class.java, FeeTierResolver::class.java, RuleCalculator::class.java, module = module)
            r.assertReady(); assertEquals(emptyList(), r.violations)
        }
        val r = namingInspect(namingPolicy("domain-ledger" to namingPkg), TradingFeeCalculator::class.java, EnumResolver::class.java, module = "domain-ledger")
        r.assertReady(); assertTrue("module" in r.items(TradingFeeCalculator::class.java)); assertTrue("declaration" in r.items(EnumResolver::class.java))
    }
    @Test fun `Config는 이름 어노테이션 위치 세 조건을 각각 잡는다`() {
        val p = namingPolicy("config" to namingPkg)
        val r = namingInspect(p, ExplicitOrderConfig::class.java, Wiring::class.java, OrderConfig::class.java, Config::class.java)
        r.assertReady(); assertEquals(emptySet(), r.items(ExplicitOrderConfig::class.java))
        assertEquals(setOf("name"), r.items(Wiring::class.java)); assertEquals(setOf("declaration"), r.items(OrderConfig::class.java))
        assertEquals(setOf("name"), r.items(Config::class.java))
        assertEquals(setOf("package"), namingInspect(namingPolicy(), ExplicitOrderConfig::class.java).items(ExplicitOrderConfig::class.java))
    }
    @Test fun `Boot의 표준 Configuration 의미만으로 Config 규칙에 선택하지 않는다`() {
        val r = namingInspect(namingPolicy("bootstrap" to namingPkg), TestApplication::class.java)
        r.assertReady(); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(TestApplication::class.java.name), r.selected("bootstrap")); assertTrue(r.selected("config").isEmpty())
        val wrong = namingInspect(namingPolicy(), TestApplication::class.java); assertEquals(setOf("package"), wrong.items(TestApplication::class.java))
        assertEquals(setOf("name"), namingInspect(namingPolicy("bootstrap" to namingPkg), BootEntry::class.java).items(BootEntry::class.java))
    }
    @Test fun `Advice와 Entity는 직접 표준 어노테이션이 있을 때 이름과 위치를 검사한다`() {
        for ((area, good, bad) in listOf(Triple("http-errors", ApiExceptionHandler::class.java, WrongAdvice::class.java),
            Triple("matching-persistence", JournalEntity::class.java, EntityRow::class.java))) {
            val r = namingInspect(namingPolicy(area to namingPkg), good, bad)
            r.assertReady(); assertEquals(emptySet(), r.items(good)); assertEquals(setOf("name"), r.items(bad))
            assertEquals(setOf("package"), namingInspect(namingPolicy(), good).items(good))
        }
    }
    @Test fun `Request Response ErrorResponse는 접미사와 자기 위치만 검사한다`() {
        val http = namingInspect(namingPolicy("order-http" to namingPkg), AmendOrderRequest::class.java, FakeRequest::class.java, Request::class.java, OrderResponse::class.java)
        http.assertReady(); assertEquals(setOf(Request::class.java.name), http.violations.map { it.subject }.toSet())
        assertEquals(setOf("name"), http.items(Request::class.java))
        val error = namingInspect(namingPolicy("http-errors" to namingPkg), ApiErrorResponse::class.java, ErrorResponse::class.java)
        error.assertReady(); assertEquals(setOf("name"), error.items(ErrorResponse::class.java)); assertTrue(error.selected("http-data").isEmpty())
        assertEquals(setOf("package"), namingInspect(namingPolicy(), AmendOrderRequest::class.java).items(AmendOrderRequest::class.java))
    }
    @Test fun `코어 일반 이름과 중첩 데이터는 공통 역할 추론을 하지 않는다`() {
        val r = namingInspect(namingPolicy("domain-order" to namingPkg), BalancePolicy::class.java, CoreEvent::class.java, CoreAmount::class.java,
            SubmitOrderUseCase.Result::class.java, OrderState::class.java, module = "domain-order")
        r.assertReady(); assertEquals(emptyList(), r.violations); assertTrue(r.rules.all { it.targets.isEmpty() })
    }
}

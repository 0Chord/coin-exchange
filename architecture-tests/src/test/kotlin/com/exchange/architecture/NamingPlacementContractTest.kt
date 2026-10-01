package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.policy.ProjectLayoutPolicy
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

internal val namingPkg = "com.exchange.architecture.fixtures.naming"
internal fun namingPolicy(vararg positions: Pair<String, String>): LayoutPolicy {
    val overrides = positions.toMap()
    return ProjectLayoutPolicy.target.let { p -> p.copy(folders = p.folders.map { f -> overrides[f.id]?.let { f.copy(folder = it.replace('.', '/')) } ?: f }) }
}
internal fun namingScope(vararg groups: Pair<String, List<Class<*>>>) = ScopeImportResult(groups.associate { (module, types) -> module to ClassFileImporter().importClasses(types) })
internal fun namingInspect(policy: LayoutPolicy, vararg types: Class<*>, module: String = "app-api") = NamingRules.inspectTypes(
    namingScope(module to types.toList()), policy, policy.roots.map { it.module }.toSet())
internal fun PlacementResult.assertReady() = assertTrue(evaluated, problems.toString())
internal fun PlacementResult.items(type: Class<*>) = violations.filter { it.subject == type.name }.map { it.item }.toSet()
internal fun PlacementResult.selected(id: String) = rules.single { it.id == id }.targets

class NamingPlacementContractTest {
    private val policy = namingPolicy("order-http" to namingPkg)
    @Test fun `Controller의 정상 이름 어노테이션 HTTP 위치가 모두 맞으면 통과한다`() {
        val r = namingInspect(policy, HttpOrderController::class.java)
        r.assertReady(); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(HttpOrderController::class.java.name), r.selected("controller"))
    }
    @Test fun `어노테이션만 있는 Endpoint도 선택해서 Controller 이름 위반을 잡는다`() {
        val r = namingInspect(policy, OrderEndpoint::class.java)
        r.assertReady(); assertEquals(setOf("name"), r.items(OrderEndpoint::class.java))
        assertEquals(setOf(OrderEndpoint::class.java.name), r.selected("controller"))
        assertTrue(r.rules.single { it.id == "controller" }.failures.single().contains(OrderEndpoint::class.java.name))
    }
    @Test fun `Controller 이름만 있고 표준 어노테이션이 없으면 선언 위반이다`() {
        val r = namingInspect(policy, OrderController::class.java)
        r.assertReady(); assertEquals(setOf("declaration"), r.items(OrderController::class.java))
    }
    @Test fun `접두사가 없는 Controller도 선택하고 이름 위반을 잡는다`() {
        val r = namingInspect(policy, Controller::class.java)
        r.assertReady(); assertEquals(setOf("name"), r.items(Controller::class.java))
        assertEquals(setOf(Controller::class.java.name), r.selected("controller"))
    }
    @Test fun `다른 허용 폴더라도 Controller HTTP 위치가 아니면 위반이다`() {
        val p = namingPolicy("order-persistence" to namingPkg)
        val r = namingInspect(p, HttpOrderController::class.java)
        r.assertReady(); assertEquals(setOf("package"), r.items(HttpOrderController::class.java))
    }
    @Test fun `같은 package라도 다른 모듈에 Controller가 있으면 모듈 위반이다`() {
        val r = namingInspect(policy, HttpOrderController::class.java, module = "domain-order")
        r.assertReady(); assertEquals(setOf("module"), r.items(HttpOrderController::class.java))
    }
    @Test fun `단서 없는 클래스는 이름 검사 대상 없음으로 표시한다`() {
        val r = namingInspect(policy, NewHelper::class.java, ForgedKt::class.java, `Forged$Helper`::class.java, AnnotatedOrderManager::class.java)
        r.assertReady(); assertEquals(emptyList(), r.violations); assertTrue(r.rules.all { it.targets.isEmpty() })
    }
    @Test fun `extra 루트가 추가되어도 Controller 이름 조건을 완화하지 않는다`() {
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "src/extra/kotlin"),
            folders = policy.folders + policy.folders.single { it.id == "order-http" }.copy(id = "http-extra", sourceRoot = "src/extra/kotlin"))
        assertEquals(namingInspect(policy, OrderEndpoint::class.java).violations, namingInspect(p, OrderEndpoint::class.java).violations)
    }
    @Test fun `Config와 Controller 단서가 겹쳐도 둘의 규칙을 모두 평가한다`() {
        val r = namingInspect(policy, DualController::class.java)
        r.assertReady(); assertEquals(setOf(DualController::class.java.name), r.selected("controller"))
        assertEquals(setOf(DualController::class.java.name), r.selected("config"))
        assertTrue(r.violations.any { it.ruleId == "ARCH-05/config" && it.item == "name" })
        assertFalse(r.violations.any { it.item == "roleConflict" })
    }
    @Test fun `표준 Controller로 구성한 프로젝트 전용 어노테이션을 재귀 해석하지 않는다`() {
        val r = namingInspect(policy, ComposedEndpoint::class.java)
        r.assertReady(); assertTrue(r.selected("controller").isEmpty())
    }
    @Test fun `개별 Controller 규칙은 전체 역할 분류 없이 ArchUnit check로 실행된다`() {
        val scope = namingScope("app-api" to listOf(HttpOrderController::class.java))
        val input = NamingPlacementScope.prepare(scope, policy, policy.roots.map { it.module }.toSet())
        NamingRules.controllers(input, policy).check(scope.classesByModule.getValue("app-api"))
        val bad = namingScope("app-api" to listOf(OrderEndpoint::class.java))
        val badInput = NamingPlacementScope.prepare(bad, policy, policy.roots.map { it.module }.toSet())
        val e = assertFailsWith<AssertionError> { NamingRules.controllers(badInput, policy).check(bad.classesByModule.getValue("app-api")) }
        assertTrue(e.message!!.contains("OrderEndpoint: name"))
    }
}

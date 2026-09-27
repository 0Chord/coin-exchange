package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.AmendOrderUseCase
import com.exchange.architecture.fixtures.naming.OrderManager
import com.exchange.architecture.fixtures.naming.SubmitOrderUseCase
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class NamingPlacementAutoClassificationTest {
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private val folder = AllowedFolder("order-application", "app-api", "src/main/kotlin",
        pkg.replace('.', '/'), setOf(NamingRole.USE_CASE, NamingRole.SERVICE, NamingRole.DATA), "주문 업무")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private fun inspect(vararg types: Class<*>, layout: LayoutPolicy = policy): PlacementResult =
        RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types))),
            layout, setOf("app-api"))

    @Test fun `AUTO-01 새 유즈케이스를 클래스별 등록 없이 포함한다`() {
        val result = inspect(SubmitOrderUseCase::class.java, AmendOrderUseCase::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(setOf(SubmitOrderUseCase::class.java.name, AmendOrderUseCase::class.java.name), result.evaluatedTypes)
    }
    @Test fun `AUTO-02 읽은 미분류 타입은 준비 오류가 아닌 규칙 위반이다`() {
        val result = inspect(SubmitOrderUseCase::class.java, OrderManager::class.java)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf(OrderManager::class.java.name to "unclassified"), result.violations.map { it.subject to it.item })
        assertTrue(OrderManager::class.java.name in result.evaluatedTypes)
    }
    @Test fun `AUTO-03 정답 폴더 밖 유즈케이스도 발견하고 위치 위반을 보고한다`() {
        val result = inspect(AmendOrderUseCase::class.java, layout = policy.copy(folders = listOf(folder.copy(folder = "com/example/order/application"))))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(listOf("package"), result.violations.map { it.item })
        assertEquals("com.example.order.application", result.violations.single().expected)
        assertEquals(AmendOrderUseCase::class.java.name, result.violations.single().subject)
    }
    @Test fun `AUTO-24 입력 순서가 자동 분류와 위반 결과를 바꾸지 않는다`() {
        val forward = inspect(SubmitOrderUseCase::class.java, OrderManager::class.java)
        assertTrue(forward.evaluated, forward.problems.toString())
        assertEquals(forward, inspect(OrderManager::class.java, SubmitOrderUseCase::class.java))
    }
}

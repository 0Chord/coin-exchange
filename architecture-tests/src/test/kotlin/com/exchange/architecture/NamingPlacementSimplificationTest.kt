package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.NewHelper
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.support.AllowedFolder
import com.exchange.architecture.support.AllowedSourceRoot
import com.exchange.architecture.support.LayoutPolicy
import com.exchange.architecture.support.ScopeImportResult
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingPlacementSimplificationTest {
    @Test fun `허용 폴더의 단서 없는 Helper를 미분류 위반으로 거절하지 않는다`() {
        val pkg = NewHelper::class.java.packageName
        val policy =
            LayoutPolicy(
                listOf(AllowedSourceRoot("app-api", "src/main/kotlin")),
                listOf(
                    AllowedFolder(
                        "order-application",
                        "app-api",
                        "src/main/kotlin",
                        pkg.replace('.', '/'),
                        "주문 애플리케이션 예제",
                    ),
                ),
            )
        val scope = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(NewHelper::class.java)))
        val result = NamingRules.inspectTypes(scope, policy, setOf("app-api"))
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations, "역할 단서가 없으면 이름 검사의 대상이 아니다")
    }
}

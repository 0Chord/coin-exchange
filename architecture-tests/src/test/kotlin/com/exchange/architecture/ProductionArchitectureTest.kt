package com.exchange.architecture

import com.exchange.architecture.rules.DomainTechnologyIndependence
import com.exchange.architecture.support.ModuleRegistration
import com.exchange.architecture.support.ProductionScope
import com.exchange.architecture.support.ProductionScopeImporter
import com.exchange.architecture.support.RoleClassifier
import kotlin.test.Test
import kotlin.test.assertTrue

/** 등록·수집·역할 오류가 없는 경우에만 실제 운영 코드에 ARCH-01을 적용한다. */
class ProductionArchitectureTest {
    @Test
    fun `P01 전체 운영 출력과 역할을 검증한 뒤 동일 ARCH-01 규칙을 적용한다`() {
        val registration = ModuleRegistration.inspect(ProductionScope.inventory())
        assertTrue(registration.isEmpty(), "Module registration failed: $registration")

        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionScope.expectations())
        assertTrue(scope.problems.isEmpty(), "Scope collection failed; ARCH-01 not evaluated:\n${scope.problems.joinToString("\n")}")
        val classified = RoleClassifier.classify(scope.classesByModule, ProductionScope.roles)
        assertTrue(classified.problems.isEmpty(), "Role classification failed; ARCH-01 not evaluated:\n${classified.problems.joinToString("\n")}")

        val violations = scope.classesByModule.toSortedMap().flatMap { (module, classes) ->
            DomainTechnologyIndependence.evaluate(classes, classified.roles).map { violation ->
                with(violation) {
                    "$ruleId | $module | $originType | $description | $targetType | " +
                        "${sourceFile ?: "source unavailable"}:${lineNumber ?: "line unavailable"} | $specification"
                }
            }
        }
        println("Active: ARCH-01. Not evaluated: ARCH-02/03/04/05/06/07/08.")
        println("Inventory: ${scope.classesByModule.mapValues { it.value.size }}")
        println("Roles: pure=${classified.roles.pureDomain.size}, ports=${classified.roles.externalPorts.size}, executors=${classified.roles.executors.size}")
        assertTrue(violations.isEmpty(), "Production violations:\n${violations.joinToString("\n")}")
    }
}

package com.exchange.architecture

import com.exchange.architecture.support.GradleModuleInventory
import com.exchange.architecture.support.ModuleRegistration
import com.tngtech.archunit.core.importer.ClassFileImporter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Gradle이 실제 목록·컴파일 출력을 전달했는지 확인한다. ARCH-01 준수 판정은 P01이 맡는다. */
class ProductionOutputWiringTest {
    @Test
    fun `W02 Gradle의 JVM 발견 목록과 운영 및 비운영 등록을 검사 경계로 전달한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = projectPaths("architecture.discoveredJvmProjects"),
            productionModules = projectPaths("architecture.registration.production"),
            nonProductionModules = projectPaths("architecture.registration.nonProduction"),
        )
        val outputProjects = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("architecture.outputs.") }
            .map { ":" + it.removePrefix("architecture.outputs.") }.toSet()
        assertEquals(outputProjects, inventory.productionModules)
        assertEquals(setOf(":architecture-tests", ":benchmark-jmh"), inventory.nonProductionModules)
        assertTrue(inventory.discoveredModules.containsAll(outputProjects + inventory.nonProductionModules))

        assertEquals(emptyList(), ModuleRegistration.inspect(inventory), "Every discovered JVM project must have an explicit role")
    }

    @Test
    fun `W01 여섯 운영 모듈의 실제 main 출력을 컴파일해 전달한다`() {
        val required = mapOf(
            "domain-common" to "com.exchange.core.common.Amount",
            "domain-fee" to "com.exchange.core.fee.TradingFeeCalculator",
            "domain-order" to "com.exchange.core.order.OrderReservation",
            "domain-ledger" to "com.exchange.core.ledger.Balance",
            "domain-matching" to "com.exchange.core.matching.MatchingEngine",
            "app-api" to "com.exchange.core.ExchangeCoreApplication",
        )
        val passedModules = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("architecture.outputs.") }
            .map { it.removePrefix("architecture.outputs.") }.toSet()
        assertEquals(required.keys, passedModules, "Use all six production outputs, not a transitive package scan")

        required.forEach { (module, representative) ->
            val roots = assertNotNull(System.getProperty("architecture.outputs.$module"))
                .split(File.pathSeparator).filter { it.isNotBlank() }.map(Path::of)
            assertTrue(roots.isNotEmpty(), "$module: missing compiler output")
            assertTrue(roots.all { Files.isDirectory(it) && it.fileName.toString() == "main" }, "$module: expected main class output: $roots")
            val classes = ClassFileImporter().importPaths(roots)
            assertTrue(classes.any { it.name == representative }, "$module: cannot read $representative from $roots")
            assertTrue(classes.none { it.name.startsWith("com.exchange.architecture.") }, "$module: fixture contamination")
        }
    }

    private fun projectPaths(property: String): Set<String> =
        assertNotNull(System.getProperty(property), "Gradle must pass $property independently of compiled outputs")
            .split(',').filter { it.isNotBlank() }.toSet().also {
                assertTrue(it.isNotEmpty(), "$property must not be empty")
            }
}

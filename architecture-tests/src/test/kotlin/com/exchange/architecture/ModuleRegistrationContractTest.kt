package com.exchange.architecture

import com.exchange.architecture.support.GradleModuleInventory
import com.exchange.architecture.support.ModuleRegistration
import com.exchange.architecture.support.ScopeProblem
import com.exchange.architecture.support.ScopeProblemCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleRegistrationContractTest {
    private val productionModules = setOf(":domain-common", ":app-api")
    private val nonProductionModules = setOf(":architecture-tests", ":benchmark-jmh")

    @Test
    fun `M01 실제 모듈이 모두 운영 또는 비운영으로 등록되어 있으면 통과한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = productionModules + nonProductionModules,
            productionModules = productionModules,
            nonProductionModules = nonProductionModules,
        )

        assertEquals(emptyList(), ModuleRegistration.inspect(inventory))
    }

    @Test
    fun `M02 새로 추가한 모듈을 등록하지 않으면 오류를 보고한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = productionModules + nonProductionModules + ":new-domain",
            productionModules = productionModules,
            nonProductionModules = nonProductionModules,
        )

        assertProblem(inventory, ScopeProblemCode.UNREGISTERED_MODULE, ":new-domain")
    }

    @Test
    fun `M03 등록한 모듈이 실제로 없으면 오류를 보고한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = productionModules - ":app-api" + nonProductionModules,
            productionModules = productionModules,
            nonProductionModules = nonProductionModules,
        )

        assertProblem(inventory, ScopeProblemCode.MISSING_MODULE, ":app-api")
    }

    @Test
    fun `M04 벤치마크라는 이름이어도 등록하지 않으면 오류를 보고한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = productionModules + nonProductionModules,
            productionModules = productionModules,
            nonProductionModules = setOf(":architecture-tests"),
        )

        assertProblem(inventory, ScopeProblemCode.UNREGISTERED_MODULE, ":benchmark-jmh")
    }

    @Test
    fun `M05 같은 모듈을 운영과 비운영에 동시에 등록하면 오류를 보고한다`() {
        val inventory = GradleModuleInventory(
            discoveredModules = productionModules + nonProductionModules,
            productionModules = productionModules,
            nonProductionModules = nonProductionModules + ":app-api",
        )
        assertProblem(inventory, ScopeProblemCode.CONFLICTING_MODULE_ROLE, ":app-api")
    }

    private fun assertProblem(inventory: GradleModuleInventory, code: ScopeProblemCode, module: String) {
        val problems = ModuleRegistration.inspect(inventory)
        assertTrue(ScopeProblem(code, module) in problems, "Expected $code for $module; actual=$problems")
    }
}

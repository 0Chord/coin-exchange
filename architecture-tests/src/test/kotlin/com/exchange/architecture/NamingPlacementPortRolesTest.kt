package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.fixtures.naming.ports.EventPublisher
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.exchange.architecture.support.NamingRole.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.*

class NamingPlacementPortRolesTest {
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private fun folder(id: String, module: String, path: String, vararg roles: NamingRole) =
        AllowedFolder(id, module, "src/main/kotlin", path.replace('.', '/'), roles.toSet(), "포트 소속 예제")
    private val policy = LayoutPolicy(
        listOf("domain-ledger", "domain-order", "app-api").map { AllowedSourceRoot(it, "src/main/kotlin") },
        listOf(folder("ledger", "domain-ledger", pkg, STORE_PORT), folder("order", "domain-order", pkg, STORE_PORT),
            folder("ledger-db", "app-api", pkg, STORE_IMPLEMENTATION), folder("order-db", "app-api", "com.example.order.persistence", STORE_IMPLEMENTATION)),
    ).let { it.copy(naming = it.naming.copy(storeTargets = mapOf("ledger" to "ledger-db", "order" to "order-db"))) }
    private fun inspect(implementation: Class<*>, ledger: Array<Class<*>> = arrayOf(WalletStore::class.java),
        order: Array<Class<*>> = emptyArray(), p: LayoutPolicy = policy): PlacementResult {
        val inputs = mapOf("app-api" to arrayOf(implementation), "domain-ledger" to ledger, "domain-order" to order).filterValues { it.isNotEmpty() }
        return RoleNamingPlacement.inspectTypes(ScopeImportResult(inputs.mapValues { ClassFileImporter().importClasses(*it.value) }), p, setOf("app-api", "domain-ledger", "domain-order"))
    }
    @Test fun `AUTO-11 포트 모듈로 저장 구현의 영역을 정한다`() {
        val r = inspect(PostgresWalletStore::class.java)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(STORE_PORT), r.classifications.getValue(WalletStore::class.java.name).roles)
        assertEquals(setOf("ledger-db"), r.classifications.getValue(PostgresWalletStore::class.java.name).locations)
    }
    @Test fun `AUTO-19 저장 구현도 같은 역할 package의 추가 루트를 허용한다`() {
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "src/extra/kotlin"),
            folders = policy.folders + policy.folders.single { it.id == "ledger-db" }.copy(id = "ledger-extra", sourceRoot = "src/extra/kotlin"))
        val r = inspect(PostgresWalletStore::class.java, p = p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf("ledger-db", "ledger-extra"), r.classifications.getValue(PostgresWalletStore::class.java.name).locations)
    }
    @Test fun `AUTO-12 저장 기술과 구현 관계 그리고 영역을 따로 검사한다`() {
        assertEquals(listOf("name"), inspect(FakeWalletStore::class.java).violations.map { it.item })
        assertEquals(listOf("roleShape"), inspect(PostgresBalanceStore::class.java).violations.map { it.item })
        val swapped = policy.copy(folders = policy.folders.map { if (it.id == "ledger-db") it.copy(folder = "com/example/ledger/persistence") else it })
        val r = inspect(PostgresWalletStore::class.java, p = swapped)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(listOf("package"), r.violations.map { it.item })
        assertEquals("com.example.ledger.persistence", r.violations.single().expected)
    }
    @Test fun `AUTO-13 같은 영역 복수 포트는 합치고 다른 영역은 충돌이다`() {
        val same = inspect(JpaWalletStore::class.java, arrayOf(WalletStore::class.java, AuditStore::class.java))
        assertTrue(same.evaluated, same.problems.toString()); assertEquals(emptyList(), same.violations)
        val mixed = inspect(PostgresMixedStore::class.java, order = arrayOf(ReservationStore::class.java))
        assertTrue(mixed.evaluated, mixed.problems.toString()); assertEquals(listOf("roleConflict"), mixed.violations.map { it.item })
    }
    @Test fun `AUTO-13 필요한 내부 포트 정의가 누락되면 준비 오류다`() {
        val r = inspect(PostgresWalletStore::class.java, ledger = emptyArray())
        assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedTypes); assertEquals(emptyList(), r.violations)
        assertTrue(r.problems.any { it.detail?.contains("WalletStore") == true })
    }
    @Test fun `AUTO-11 잘못 놓인 포트를 없던 것으로 만들지 않는다`() {
        val moved = policy.copy(folders = policy.folders.map { if (it.id == "ledger") it.copy(folder = "com/example/ledger") else it })
        val r = inspect(PostgresWalletStore::class.java, p = moved)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(listOf(WalletStore::class.java.name to "package"), r.violations.map { it.subject to it.item })
        assertEquals(setOf("ledger-db"), r.classifications.getValue(PostgresWalletStore::class.java.name).locations)
    }
    @Test fun `AUTO-15 발행 구현 접두사에 맞는 위치와 포트 구현을 확인한다`() {
        fun check(type: Class<*>, persistentHere: Boolean): PlacementResult {
            val p = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(
                folder("ports", "app-api", "$pkg.ports", PUBLISHER_PORT),
                folder("persistent", "app-api", if (persistentHere) pkg else "com/example/persistent", PUBLISHER_IMPLEMENTATION),
                folder("noop", "app-api", if (!persistentHere) pkg else "com/example/noop", PUBLISHER_IMPLEMENTATION),
            )).let { it.copy(naming = it.naming.copy(publisherTargets = mapOf("Persistent" to "persistent", "NoOp" to "noop"))) }
            return RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(type, EventPublisher::class.java))), p, setOf("app-api"))
        }
        for ((type, here) in listOf(PersistentEventPublisher::class.java to true, NoOpEventPublisher::class.java to false)) {
            val good = check(type, here)
            assertTrue(good.evaluated, good.problems.toString())
            assertEquals(emptyList(), good.violations)
            assertEquals(listOf("package"), check(type, !here).violations.filter { it.subject == type.name }.map { it.item })
        }
        val unknown = check(UnknownEventPublisher::class.java, true)
        assertTrue(unknown.evaluated, unknown.problems.toString()); assertTrue(unknown.violations.any { it.subject == UnknownEventPublisher::class.java.name && it.item == "name" })
    }
}

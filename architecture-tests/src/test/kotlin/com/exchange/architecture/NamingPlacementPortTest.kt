package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.ActualEventRepository
import com.exchange.architecture.fixtures.naming.AuditStore
import com.exchange.architecture.fixtures.naming.FakeRepository
import com.exchange.architecture.fixtures.naming.FakeWalletStore
import com.exchange.architecture.fixtures.naming.JpaEvents
import com.exchange.architecture.fixtures.naming.JpaWalletStore
import com.exchange.architecture.fixtures.naming.NoOpEventPublisher
import com.exchange.architecture.fixtures.naming.PersistentEventPublisher
import com.exchange.architecture.fixtures.naming.PersistentMatchingEventPublisher
import com.exchange.architecture.fixtures.naming.PostgresBalanceStore
import com.exchange.architecture.fixtures.naming.PostgresInheritedStore
import com.exchange.architecture.fixtures.naming.PostgresMixedStore
import com.exchange.architecture.fixtures.naming.PostgresWalletStore
import com.exchange.architecture.fixtures.naming.ReservationStore
import com.exchange.architecture.fixtures.naming.UnknownEventPublisher
import com.exchange.architecture.fixtures.naming.WalletContract
import com.exchange.architecture.fixtures.naming.WalletStore
import com.exchange.architecture.fixtures.naming.ports.EventPublisher
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.support.AllowedSourceRoot
import com.exchange.architecture.support.LayoutPolicy
import com.exchange.architecture.support.PlacementResult
import com.exchange.architecture.support.ScopeImportResult
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamingPlacementPortTest {
    @TempDir lateinit var root: Path
    private val policy = namingPolicy("domain-ledger" to namingPkg, "domain-order" to namingPkg, "ledger-persistence" to namingPkg)

    private fun inspect(
        impl: Class<*>,
        ledger: List<Class<*>> = listOf(WalletStore::class.java),
        order: List<Class<*>> = emptyList(),
        p: LayoutPolicy = policy,
    ): PlacementResult {
        val groups = listOf("app-api" to listOf(impl), "domain-ledger" to ledger, "domain-order" to order).filter { it.second.isNotEmpty() }
        return NamingRules.inspectTypes(namingScope(*groups.toTypedArray()), p, p.roots.map { it.module }.toSet())
    }

    @Test fun `실제 포트 소속으로 Store 구현 위치를 정한다`() {
        val r = inspect(PostgresWalletStore::class.java)
        r.assertReady()
        assertEquals(emptyList(), r.violations)
        assertEquals(setOf(WalletStore::class.java.name), r.selected("store-port"))
        assertEquals(setOf(PostgresWalletStore::class.java.name), r.selected("store-implementation"))
    }

    @Test fun `동등한 포트 위치 ID와 추가 루트는 한 영역으로 합친다`() {
        val p =
            policy.copy(
                roots =
                    policy.roots +
                        AllowedSourceRoot(
                            "domain-ledger",
                            "src/extra/kotlin",
                        ) + AllowedSourceRoot("app-api", "src/extra/kotlin"),
                folders =
                    policy.folders +
                        policy.folders.single { it.id == "domain-ledger" }.copy(id = "ledger-extra", sourceRoot = "src/extra/kotlin") +
                        policy.folders.single { it.id == "ledger-persistence" }.copy(
                            id = "ledger-db-extra",
                            sourceRoot = "src/extra/kotlin",
                        ),
                naming = policy.naming.copy(storeTargets = policy.naming.storeTargets + ("ledger-extra" to "ledger-db-extra")),
            )
        val r = inspect(PostgresWalletStore::class.java, p = p)
        r.assertReady()
        assertEquals(emptyList(), r.violations)
    }

    @Test fun `다른 위치 ID가 실제로 다른 영역을 가리키면 Store 영역 위반이다`() {
        val p =
            policy.copy(
                roots = policy.roots + AllowedSourceRoot("domain-ledger", "src/extra/kotlin"),
                folders =
                    policy.folders +
                        policy.folders.single { it.id == "domain-ledger" }.copy(id = "ledger-extra", sourceRoot = "src/extra/kotlin"),
                naming = policy.naming.copy(storeTargets = policy.naming.storeTargets + ("ledger-extra" to "order-persistence")),
            )
        val r = inspect(PostgresWalletStore::class.java, p = p)
        r.assertReady()
        assertEquals(setOf("storeArea"), r.items(PostgresWalletStore::class.java))
    }

    @Test fun `Store 기술 이름 구현 관계 위치를 각각 검사한다`() {
        assertEquals(setOf("name"), inspect(FakeWalletStore::class.java).items(FakeWalletStore::class.java))
        assertEquals(setOf("declaration"), inspect(PostgresBalanceStore::class.java).items(PostgresBalanceStore::class.java))
        val p =
            policy.copy(
                folders =
                    policy.folders.map {
                        if (it.id ==
                            "ledger-persistence"
                        ) {
                            it.copy(folder = "com/example/ledger/persistence")
                        } else {
                            it
                        }
                    },
            )
        val r = inspect(PostgresWalletStore::class.java, p = p)
        r.assertReady()
        assertEquals(setOf("package"), r.items(PostgresWalletStore::class.java))
    }

    @Test fun `같은 영역의 복수 포트는 정상이고 다른 영역의 복수 포트는 위반이다`() {
        val same = inspect(JpaWalletStore::class.java, listOf(WalletStore::class.java, AuditStore::class.java))
        same.assertReady()
        assertEquals(emptyList(), same.violations)
        val mixed = inspect(PostgresMixedStore::class.java, order = listOf(ReservationStore::class.java))
        mixed.assertReady()
        assertEquals(setOf("storeArea"), mixed.items(PostgresMixedStore::class.java))
    }

    @Test fun `간접 Store 상속도 실제 포트와 연결한다`() {
        val r = inspect(PostgresInheritedStore::class.java, listOf(WalletStore::class.java, WalletContract::class.java))
        r.assertReady()
        assertEquals(emptyList(), r.violations)
    }

    @Test fun `필요한 내부 포트나 매핑이 없으면 준비 오류다`() {
        val missing = inspect(PostgresWalletStore::class.java, ledger = emptyList())
        assertFalse(missing.evaluated)
        assertTrue(missing.problems.any { it.detail?.contains("WalletStore") == true })
        assertEquals(emptyList(), missing.violations)
        val unmapped =
            inspect(
                PostgresWalletStore::class.java,
                p =
                    policy.copy(
                        naming =
                            policy.naming.copy(
                                storeTargets =
                                    policy.naming.storeTargets - "domain-ledger",
                            ),
                    ),
            )
        assertFalse(unmapped.evaluated)
        assertTrue(unmapped.problems.any { it.detail?.contains("매핑") == true })
    }

    @Test fun `잘못 놓인 포트는 위치 위반이며 구현 영역을 추측하지 않는다`() {
        val moved =
            policy.copy(
                folders =
                    policy.folders.map {
                        if (it.id ==
                            "domain-ledger"
                        ) {
                            it.copy(folder = "com/example/ledger")
                        } else {
                            it
                        }
                    },
            )
        val r = inspect(PostgresWalletStore::class.java, p = moved)
        r.assertReady()
        assertEquals(setOf("package"), r.items(WalletStore::class.java))
        assertEquals(setOf("storeArea"), r.items(PostgresWalletStore::class.java))
    }

    @Test fun `Publisher의 실제 구현 접두사와 각각의 위치를 검사한다`() {
        for ((impl, area) in listOf(
            PersistentEventPublisher::class.java to "matching-persistence",
            NoOpEventPublisher::class.java to "matching-publish",
        )) {
            val p = namingPolicy("matching-ports" to EventPublisher::class.java.packageName, area to namingPkg)
            val r = namingInspect(p, impl, EventPublisher::class.java)
            r.assertReady()
            assertEquals(emptyList(), r.violations)
            val wrong =
                namingInspect(
                    p.copy(
                        folders =
                            p.folders.map {
                                if (it.id ==
                                    area
                                ) {
                                    it.copy(folder = "com/example/wrong")
                                } else {
                                    it
                                }
                            },
                    ),
                    impl,
                    EventPublisher::class.java,
                )
            wrong.assertReady()
            assertEquals(setOf("package"), wrong.items(impl))
        }
        val p = namingPolicy("matching-ports" to EventPublisher::class.java.packageName, "matching-persistence" to namingPkg)
        assertEquals(
            setOf("name"),
            namingInspect(p, UnknownEventPublisher::class.java, EventPublisher::class.java).items(UnknownEventPublisher::class.java),
        )
        assertTrue(
            "declaration" in
                namingInspect(p, PersistentMatchingEventPublisher::class.java).items(PersistentMatchingEventPublisher::class.java),
        )
    }

    @Test fun `Repository는 접미사와 실제 Spring Data 상속을 둘 다 확인한다`() {
        val p = namingPolicy("matching-persistence" to namingPkg)
        val r = namingInspect(p, ActualEventRepository::class.java, FakeRepository::class.java, JpaEvents::class.java)
        r.assertReady()
        assertEquals(emptySet(), r.items(ActualEventRepository::class.java))
        assertEquals(setOf("declaration"), r.items(FakeRepository::class.java))
        assertEquals(setOf("name"), r.items(JpaEvents::class.java))
        assertEquals(
            setOf("package"),
            namingInspect(namingPolicy(), ActualEventRepository::class.java).items(ActualEventRepository::class.java),
        )
    }

    @Test fun `외부 라이브러리의 Store Publisher는 내부 포트 구현의 선택 단서가 아니다`() {
        val definitions =
            listOf(
                "com/vendor/LibraryStore.java" to "package com.vendor; public interface LibraryStore {}",
                "com/vendor/LibraryPublisher.java" to "package com.vendor; public interface LibraryPublisher {}",
                "com/example/LibraryAdapter.java" to
                    "package com.example; public class LibraryAdapter implements com.vendor.LibraryStore, com.vendor.LibraryPublisher {}",
            )
        val files =
            definitions.map { (path, body) ->
                root.resolve(path).also {
                    Files.createDirectories(it.parent)
                    Files.writeString(it, body)
                }
            }
        val output = root.resolve("classes")
        Files.createDirectories(output)
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-d",
                output.toString(),
                *files
                    .map {
                        it.toString()
                    }.toTypedArray(),
            ),
        )
        val classes =
            ClassFileImporter().importPath(output).that(
                DescribedPredicate.describe("운영 Adapter만") {
                    it.name ==
                        "com.example.LibraryAdapter"
                },
            )
        val r = NamingRules.inspectTypes(ScopeImportResult(mapOf("app-api" to classes)), policy, policy.roots.map { it.module }.toSet())
        r.assertReady()
        assertEquals(emptyList(), r.violations)
        assertTrue(r.selected("store-implementation").isEmpty())
        assertTrue(r.selected("publisher-implementation").isEmpty())
    }
}

package com.exchange.architecture

import com.exchange.architecture.rules.ApplicationImplementationIndependence
import com.exchange.architecture.rules.HttpEntryBoundary
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.rules.MatchingStateBoundary
import com.exchange.architecture.rules.PortContractIndependence
import com.exchange.architecture.policy.ProjectLayoutPolicy
import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 운영 출력에 새 예제 출력을 더한다. 운영 소스·클래스 파일은 변경하지 않는다. */
class ProductionBoundaryInputsTest {
    @TempDir lateinit var directory: Path
    private val app = "com.exchange.core.api.order.application"
    private val http = "com.exchange.core.api.order.api"

    private fun scope(vararg sources: Pair<String, String>): ScopeImportResult {
        val outputs = ProductionScope.outputs()
        val extra = directory.resolve("classes")
        Files.createDirectories(extra)
        if (sources.isNotEmpty()) {
            val files = sources.map { (name, body) ->
                source(name).also {
                    Files.createDirectories(it.parent)
                    Files.writeString(it, "package ${name.substringBeforeLast('.')};\n$body")
                }
            }
            val classpath = System.getProperty("architecture.testRuntime") + File.pathSeparator +
                outputs.flatMap { it.roots }.joinToString(File.pathSeparator)
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-classpath", classpath, "-d", extra.toString(), *files.map { it.toString() }.toTypedArray()))
        }
        return ProductionScopeImporter().load(outputs.map {
            if (it.module == "app-api") it.copy(roots = it.roots + listOf(extra)) else it
        }, ProductionScope.expectations(), ProductionScope.nonProductionTargets())
    }

    private fun source(name: String) = directory.resolve("src/main/kotlin/${name.replace('.', '/')}.java")


    @Test fun `ledger UseCase를 실제 application과 명명 검사에 포함하고 기술 구현 직접 참조를 거절한다`() {
        val pkg = "com.exchange.core.api.ledger.application"
        val extra = "$pkg.DirectLedgerUseCase"
        val input = scope(extra to "public class DirectLedgerUseCase { private com.exchange.core.api.ledger.infrastructure.persistence.PostgresBalanceStore store; }")
        val result = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue(extra in result.applicationTypes)
        assertTrue(result.violations.any { it.originType == extra && it.targetType.endsWith("PostgresBalanceStore") })
        val names = NamingRules.inspectTypes(input, ProjectLayoutPolicy.target, ProductionScope.requiredTypes.keys)
        assertTrue(names.evaluated, names.problems.toString())
        assertTrue(names.violations.none { it.subject == "$pkg.PrepareDevelopmentBalanceUseCase" || it.subject == extra })
    }

    @Test fun `개시 저장 포트 공개 계약은 실제 ARCH06 대상이다`() {
        val result = PortContractIndependence.inspect(scope(), ProductionScope.roles.externalPorts,
            ProductionScope.persistenceTypes, ProductionScope.expectations().projectPackagePrefixes)
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue("com.exchange.core.ledger.DevelopmentBalanceStore" in ProductionScope.roles.externalPorts)
        assertEquals(emptyList(), result.violations)
    }

    @Test fun `매칭 접근은 업무의 processor 계약과 허용 config의 구현 참조를 통과시킨다`() {
        val input = scope(
            "$app.QueuedMatchingWorker" to "public class QueuedMatchingWorker { private com.exchange.core.matching.MarketCommandProcessor processor; }",
            "com.exchange.core.api.config.ExtraMatchingConfiguration" to """
                @org.springframework.context.annotation.Configuration
                public class ExtraMatchingConfiguration { private com.exchange.core.matching.InMemoryMarketCommandProcessor processor; }
            """.trimIndent(),
        )
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(input.classesByModule.getValue("app-api").map { it.name }.toSet(), result.checkedTypes)
        assertTrue("$app.QueuedMatchingWorker" in result.checkedTypes)
    }

    @Test fun `매칭 접근은 새 업무의 엔진 내부 주문과 실행기 구현 직접 참조를 거절한다`() {
        val targets = listOf("MatchingEngine", "BookOrder", "PriceLevel", "OrderBook", "InMemoryMarketCommandProcessor")
            .map { "com.exchange.core.matching.$it" }
        val fields = targets.mapIndexed { i, target -> "private $target value$i;" }.joinToString("\n")
        val origin = "$app.DirectMatchingWorker"
        val input = scope(origin to "public class DirectMatchingWorker {\n$fields\n}")
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(targets.map { origin to it }.toSet(), result.violations.map { it.originType to it.targetType }.toSet())
        assertTrue(result.violations.all { it.ruleId == "ARCH-07" && it.sourceFile == "DirectMatchingWorker.java" })
    }

    @Test fun `매칭 접근의 엔진 직접 호출은 확인 가능한 실제 소스 행을 남긴다`() {
        val origin = "$app.DirectEngineCaller"
        val input = scope(origin to """
            public class DirectEngineCaller {
                public void run(com.exchange.core.matching.MatchingCommand command) {
                    new com.exchange.core.matching.MatchingEngine().process(command);
                }
            }
        """.trimIndent())
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue(result.violations.any {
            it.originType == origin && it.targetType == "com.exchange.core.matching.MatchingEngine" &&
                it.description.contains("process(") && it.lineNumber != null && it.lineNumber > 0
        }, result.violations.toString())
    }

    @Test fun `매칭 접근은 config에서도 엔진과 내부 주문 직접 참조를 허용하지 않는다`() {
        val origin = "com.exchange.core.api.config.EngineConfiguration"
        val input = scope(origin to """
            @org.springframework.context.annotation.Configuration
            public class EngineConfiguration {
                private com.exchange.core.matching.MatchingEngine engine;
                private com.exchange.core.matching.OrderBook book;
            }
        """.trimIndent())
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf("com.exchange.core.matching.MatchingEngine", "com.exchange.core.matching.OrderBook"), result.violations.map { it.targetType }.toSet())
    }

    @Test fun `매칭 접근의 구현 예외는 정확한 config 위치와 Configuration 선언을 모두 요구한다`() {
        val target = "com.exchange.core.matching.InMemoryMarketCommandProcessor"
        val origins = listOf("$app.FakeConfiguration", "com.exchange.core.api.config.PlainConfiguration", "com.exchange.core.api.config.extra.NestedConfiguration")
        val input = scope(
            origins[0] to "@org.springframework.context.annotation.Configuration public class FakeConfiguration { private $target value; }",
            origins[1] to "public class PlainConfiguration { private $target value; }",
            origins[2] to "@org.springframework.context.annotation.Configuration public class NestedConfiguration { private $target value; }",
        )
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(origins.map { it to target }.toSet(), result.violations.map { it.originType to it.targetType }.toSet())
    }

    @Test fun `매칭 접근은 빠진 운영 모듈과 수집 오류를 위반 없는 통과로 바꾸지 않는다`() {
        val input = scope()
        val missing = input.copy(classesByModule = input.classesByModule - "app-api")
        val existingProblem = ScopeProblem(ScopeProblemCode.READ_FAILURE, "damaged.class")
        for (bad in listOf(missing, input.copy(problems = listOf(existingProblem)))) {
            val result = MatchingStateBoundary.inspect(bad)
            assertTrue(!result.evaluated)
            assertTrue(result.problems.isNotEmpty())
            assertEquals(emptyList(), result.violations)
            assertEquals(emptySet(), result.checkedTypes)
            if (bad.problems.isNotEmpty()) assertTrue(existingProblem in result.problems)
        }
    }

    @Test fun `매칭 접근 대상 코드가 실제 출력에서 빠지면 준비 오류로 중단한다`() {
        val outputs = ProductionScope.outputs().filterNot { it.module == "app-api" }
        val input = ProductionScopeImporter().load(outputs, ProductionScope.expectations(), ProductionScope.nonProductionTargets())
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(!result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.MISSING_MODULE && it.subject == "app-api" })
        assertEquals(emptyList(), result.violations)
    }

    @Test fun `매칭 접근 금지는 이름과 무관하게 HTTP 보조 코드에도 적용한다`() {
        val origin = "$http.LocalMatchingMapper"
        val input = scope(origin to "public class LocalMatchingMapper { private com.exchange.core.matching.MatchingEngine engine; }")
        val result = MatchingStateBoundary.inspect(input)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(setOf(origin to "com.exchange.core.matching.MatchingEngine"), result.violations.map { it.originType to it.targetType }.toSet())
    }

    @Test fun `전체 운영 HTTP와 application을 기존 검사에 연결한다`() {
        val input = scope()
        val http = HttpEntryBoundary.inspect(input, ProductionBoundaryInputs.http(input))
        val application = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(http.evaluated, http.problems.toString())
        assertTrue(application.evaluated, application.problems.toString())
        assertEquals(emptyList(), http.violations)
        assertEquals(emptyList(), application.violations)
        assertTrue("com.exchange.core.api.order.api.MatchingEventResponseMapperKt" in http.apiTypes)
        assertTrue("$app.OrderFundingService" in application.applicationTypes)
        assertTrue(application.configurationTypes.isNotEmpty())
    }

    @Test fun `새 Controller와 UseCase는 이름 등록 없이 포함한다`() {
        val input = scope(
            "$app.AmendOrderUseCase" to "public class AmendOrderUseCase { public void amend() {} }",
            "$http.AmendOrderController" to """
                @org.springframework.web.bind.annotation.RestController
                public class AmendOrderController {
                    private $app.AmendOrderUseCase useCase;
                    public void amend() { useCase.amend(); }
                }
            """.trimIndent(),
        )
        val h = HttpEntryBoundary.inspect(input, ProductionBoundaryInputs.http(input))
        val a = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(h.evaluated, h.problems.toString()); assertTrue(a.evaluated, a.problems.toString())
        assertTrue("$http.AmendOrderController" in h.apiTypes)
        assertTrue("$app.AmendOrderUseCase" in a.applicationTypes)
        assertEquals(emptyList(), h.violations); assertEquals(emptyList(), a.violations)
    }

    @Test fun `일반 OrderManager도 application 금지 구현 참조를 검사한다`() {
        val target = "com.exchange.core.api.ledger.infrastructure.persistence.PostgresBalanceStore"
        val input = scope("$app.OrderManager" to "public class OrderManager { private $target store; }")
        val result = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue("$app.OrderManager" in result.applicationTypes)
        assertTrue(result.violations.any { it.originType == "$app.OrderManager" && it.targetType == target }, result.toString())
    }

    @Test fun `HTTP에서 순수 도메인 엔진을 데이터로 허용하지 않는다`() {
        val target = "com.exchange.core.matching.MatchingEngine"
        val input = scope("$http.EngineController" to """
            @org.springframework.web.bind.annotation.RestController
            public class EngineController { private $target engine; }
        """.trimIndent())
        val result = HttpEntryBoundary.inspect(input, ProductionBoundaryInputs.http(input))
        assertTrue(result.evaluated, result.problems.toString())
        assertTrue(result.violations.any { it.originType == "$http.EngineController" && it.targetType == target }, result.toString())
    }

    @Test fun `UseCase는 잔고 포트와 실행기 계약을 사용하고 config는 구현을 조립한다`() {
        val input = scope("$app.ReserveOrderUseCase" to """
            public class ReserveOrderUseCase {
                private com.exchange.core.ledger.BalanceStore balance;
                private com.exchange.core.matching.MarketCommandProcessor processor;
            }
        """.trimIndent())
        val r = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertTrue("$app.ReserveOrderUseCase" in r.applicationTypes)
        assertTrue("com.exchange.core.api.config.MatchingConfig" in r.configurationTypes)
        assertTrue("com.exchange.core.api.config.LedgerPersistenceConfig" in r.configurationTypes)
    }

    @Test fun `Controller가 예약 협력자 조율자 저장 발행 포트와 구현을 직접 참조하면 위반이다`() {
        val targets = listOf("$app.OrderFundingService", "com.exchange.core.api.matching.application.MatchingCoordinator",
            "com.exchange.core.ledger.BalanceStore", "com.exchange.core.api.matching.application.port.MatchingEventStore",
            "com.exchange.core.api.matching.application.port.MatchingEventPublisher",
            "com.exchange.core.api.matching.infrastructure.persistence.JpaMatchingEventStore")
        val fields = targets.mapIndexed { i, name -> "private $name value$i;" }.joinToString("\n")
        val input = scope("$http.BypassController" to """
            @org.springframework.web.bind.annotation.RestController
            public class BypassController { $fields }
        """.trimIndent())
        val r = HttpEntryBoundary.inspect(input, ProductionBoundaryInputs.http(input))
        assertTrue(r.evaluated, r.problems.toString())
        assertTrue(r.violations.filter { it.originType == "$http.BypassController" }.map { it.targetType }.containsAll(targets), r.toString())
    }

    @Test fun `새 HTTP 변환 보조 코드도 저장 포트 우회를 검사한다`() {
        val input = scope("$http.InvalidMapper" to """
            public class InvalidMapper {
                public static com.exchange.core.ledger.BalanceStore map(com.exchange.core.ledger.BalanceStore store) { return store; }
            }
        """.trimIndent())
        val r = HttpEntryBoundary.inspect(input, ProductionBoundaryInputs.http(input))
        assertTrue(r.evaluated, r.problems.toString())
        assertTrue("$http.InvalidMapper" in r.apiTypes)
        assertTrue(r.violations.any { it.originType == "$http.InvalidMapper" && it.targetType == "com.exchange.core.ledger.BalanceStore" }, r.toString())
    }

    @Test fun `일반 OrderManager의 이름 자체는 거절하지 않는다`() {
        val input = scope("$app.OrderManager" to "public class OrderManager {}")
        val r = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        val names = NamingRules.inspectTypes(input, ProjectLayoutPolicy.target, ProductionScope.requiredTypes.keys)
        assertTrue(r.evaluated, r.problems.toString()); assertTrue(names.evaluated, names.problems.toString())
        assertTrue("$app.OrderManager" in r.applicationTypes)
        assertEquals(emptyList(), r.violations); assertEquals(emptyList(), names.violations)
    }

    @Test fun `일반 업무 보조 코드의 HTTP 컨테이너 저장 모델 발행 구현 실행기 구현 의존을 금지한다`() {
        val targets = listOf("org.springframework.http.ResponseEntity", "org.springframework.context.ApplicationContext",
            "com.exchange.core.api.matching.infrastructure.persistence.PersistentMatchingEventPublisher",
            "com.exchange.core.api.matching.infrastructure.persistence.MatchingEventRepository",
            "com.exchange.core.api.matching.infrastructure.persistence.MatchingEventEntity",
            "com.exchange.core.matching.InMemoryMarketCommandProcessor")
        val fields = targets.mapIndexed { i, name -> "private $name value$i;" }.joinToString("\n")
        val input = scope("$app.OrderManager" to "public class OrderManager { $fields }")
        val r = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(r.evaluated, r.problems.toString())
        assertTrue(r.violations.filter { it.originType == "$app.OrderManager" }.map { it.targetType }.containsAll(targets), r.toString())
    }

    @Test fun `UseCase로 개명한 포트 구현도 허용 역할로 숨기지 않는다`() {
        val input = scope("$app.BadUseCase" to """
            public class BadUseCase implements com.exchange.core.api.matching.application.port.MatchingEventPublisher {
                public void publish(java.util.List<? extends com.exchange.core.matching.MatchingEvent> events) {}
            }
        """.trimIndent())
        val r = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(!r.evaluated)
        assertTrue(r.problems.any { it.code == ScopeProblemCode.CONFLICTING_APPLICATION_ROLE && it.subject == "$app.BadUseCase" }, r.toString())
        assertEquals(emptyList(), r.violations)
    }

    @Test fun `핵심 진입점 하나라도 빠지면 HTTP와 application 모두 준비 실패다`() {
        val input = scope()
        ProductionBoundaryInputs.requiredTypes.forEach { missing ->
            val filtered = input.copy(classesByModule = input.classesByModule.mapValues { (_, classes) ->
                classes.that(DescribedPredicate.describe("누락 사례") { it.name != missing })
            })
            val h = HttpEntryBoundary.inspect(filtered, ProductionBoundaryInputs.http(filtered))
            val a = ApplicationImplementationIndependence.inspect(filtered, ProductionBoundaryInputs.application(filtered))
            assertTrue(!h.evaluated && !a.evaluated, missing)
            assertTrue(h.problems.any { it.code == ScopeProblemCode.MISSING_ROLE_TYPE && it.subject == missing }, h.toString())
            assertTrue(a.problems.any { it.code == ScopeProblemCode.MISSING_ROLE_TYPE && it.subject == missing }, a.toString())
        }
    }

    @Test fun `업무에서 참조한 내부 포트 정의 누락은 통과가 아닌 미평가다`() {
        val missing = "com.exchange.core.ledger.BalanceStore"
        val input = scope().let { s -> s.copy(classesByModule = s.classesByModule.mapValues { (_, classes) ->
            classes.that(DescribedPredicate.describe("참조 정의 누락") { it.name != missing })
        }) }
        val r = ApplicationImplementationIndependence.inspect(input, ProductionBoundaryInputs.application(input))
        assertTrue(!r.evaluated)
        assertTrue(r.problems.any { it.code == ScopeProblemCode.UNRESOLVED_PROJECT_TYPE && it.subject == missing }, r.toString())
        assertEquals(emptyList(), r.violations); assertEquals(0, r.referenceCount)
    }

    @Test fun `옮긴 저장 발행 포트와 영속 모델의 정의 누락은 ARCH-06 미평가다`() {
        val input = scope()
        val missingCases = mapOf(
            "com.exchange.core.api.matching.application.port.MatchingEventStore" to "MISSING_PORT",
            "com.exchange.core.api.matching.application.port.MatchingEventPublisher" to "MISSING_PORT",
            "com.exchange.core.api.matching.infrastructure.persistence.MatchingEventEntity" to "MISSING_PERSISTENCE_TYPE",
        )
        missingCases.forEach { (missing, code) ->
            val filtered = input.copy(classesByModule = input.classesByModule.mapValues { (_, classes) ->
                classes.that(DescribedPredicate.describe("이동한 정의 누락") { it.name != missing })
            })
            val result = PortContractIndependence.inspect(filtered, ProductionScope.roles.externalPorts,
                ProductionScope.persistenceTypes, ProductionScope.expectations().projectPackagePrefixes)
            assertTrue(!result.evaluated, missing)
            assertTrue(result.problems.any { it.code == code && it.subject == missing }, result.toString())
            assertEquals(emptyList(), result.violations)
            assertEquals(0, result.contractCount)
        }
    }

    @Test fun `최종 저장 폴더의 Controller는 원본 위치 허용과 Controller 위치 위반을 구분한다`() {
        val type = "com.exchange.core.api.ledger.infrastructure.persistence.MisplacedController"
        val input = scope(type to "@org.springframework.web.bind.annotation.RestController public class MisplacedController {}")
        val policy = ProjectLayoutPolicy.target
        val files = SourcePlacement.inspect(listOf(MainSourceRoot("app-api", directory, "src/main/kotlin", setOf(source(type)))),
            policy, ProductionScope.requiredTypes.keys).result
        val names = NamingRules.inspectTypes(input, policy, ProductionScope.requiredTypes.keys)
        assertTrue(files.evaluated, files.problems.toString()); assertEquals(emptyList(), files.violations)
        assertTrue(names.evaluated, names.problems.toString())
        assertTrue(names.violations.any { it.subject == type && it.item == "package" }, names.toString())
    }

    @Test fun `원본과 package를 함께 네 옛 저장 폴더로 되돌려도 최종 정책은 거절한다`() {
        val oldFolders = listOf("order/persistence", "ledger/persistence", "matching/persistence", "matching/publish")
        val currentFolders = listOf("order/infrastructure/persistence", "ledger/infrastructure/persistence",
            "matching/infrastructure/persistence", "matching/infrastructure/publish")
        fun helper(folder: String, name: String = "LegacyHelper") = "com.exchange.core.api.${folder.replace('/', '.')}.${name}"
        fun write(name: String) = source(name).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "package ${name.substringBeforeLast('.')}; class ${name.substringAfterLast('.')} {}")
        }
        fun inspect(files: Set<Path>) = SourcePlacement.inspect(
            listOf(MainSourceRoot("app-api", directory, "src/main/kotlin", files)),
            ProjectLayoutPolicy.target, ProductionScope.requiredTypes.keys).result

        val current = currentFolders.map { write(helper(it)) }.toSet()
        val normal = inspect(current)
        assertTrue(normal.evaluated, normal.problems.toString())
        assertEquals(emptyList(), normal.violations)
        assertEquals(current.map { it.toString() }.toSet(), normal.evaluatedFiles)

        val moved = currentFolders.zip(oldFolders).map { (from, to) ->
            val name = helper(to)
            val destination = source(name)
            Files.createDirectories(destination.parent)
            Files.move(source(helper(from)), destination)
            Files.writeString(destination, "package ${name.substringBeforeLast('.')}; class LegacyHelper {}")
            destination
        }.toSet()
        val oldRoot = write(helper("order", "OldOrderHelper"))
        val invalidFiles = moved + setOf(oldRoot)
        val result = inspect(invalidFiles)
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(invalidFiles.map { it.toString() }.toSet(), result.evaluatedFiles)
        assertEquals(invalidFiles.map { it.toString() }.toSet(), result.violations.map { it.subject }.toSet())
        assertEquals(setOf("allowedFolder"), result.violations.map { it.item }.toSet())
        assertEquals(5, result.violations.size)
        assertEquals((oldFolders + "order").map { "com/exchange/core/api/$it" }.toSet(), result.violations.map { it.actual }.toSet())
    }
}

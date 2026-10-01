package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.policy.*
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import javax.tools.ToolProvider

class NamingPlacementScopeTest {
    @TempDir lateinit var root: Path
    private val policy = namingPolicy("order-application" to namingPkg)
    private fun inspect(s: ScopeImportResult, p: LayoutPolicy = policy) = NamingRules.inspectTypes(s, p, p.roots.map { it.module }.toSet())
    @Test fun `익명 로컬 synthetic 파일 운반 타입은 이름 검사만 제외한다`() {
        val anonymous = SubmitOrderUseCase().callback().javaClass
        class LocalUseCase
        val carrier = Class.forName("$namingPkg.NamedQuoteFunctions", false, javaClass.classLoader)
        val imported = namingScope("app-api" to listOf(SubmitOrderUseCase::class.java, SubmitOrderUseCase.Result::class.java, SubmitOrderUseCase.Companion::class.java, anonymous, LocalUseCase::class.java, carrier))
        val r = inspect(imported); r.assertReady(); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(SubmitOrderUseCase::class.java.name), r.evaluatedTypes)
        assertTrue(imported.classesByModule.getValue("app-api").any { it.name == anonymous.name })
        assertTrue(imported.classesByModule.getValue("app-api").any { it.name == carrier.name })
    }
    @Test fun `companion에는 소유자의 역할과 원본 연결을 요구하지 않는다`() {
        val r = namingInspect(policy, SubmitOrderUseCase.Companion::class.java)
        r.assertReady(); assertEquals(emptyList(), r.violations); assertTrue(r.evaluatedTypes.isEmpty())
    }
    @Test fun `익명 또는 local이라는 문자열만으로 직접 선언 타입을 제외하지 않는다`() {
        val r = namingInspect(policy, ForgedKt::class.java, `Forged$Helper`::class.java)
        r.assertReady(); assertTrue(r.evaluatedTypes.isEmpty())
        assertTrue(ClassFileImporter().importClasses(ForgedKt::class.java).all(NamingRules::namedDeclaration))
    }
    @Test fun `빈 전체 수집 빈 모듈 누락 모듈 중복 타입은 준비 오류를 유지한다`() {
        val normal = ClassFileImporter().importClasses(SubmitOrderUseCase::class.java)
        val cases = listOf(ScopeImportResult() to ScopeProblemCode.EMPTY_SCOPE,
            ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses())) to ScopeProblemCode.EMPTY_MODULE,
            ScopeImportResult(mapOf("unknown" to normal)) to ScopeProblemCode.UNREGISTERED_MODULE,
            ScopeImportResult(mapOf("app-api" to normal, "domain-order" to normal)) to ScopeProblemCode.DUPLICATE_TYPE,
            ScopeImportResult(mapOf("app-api" to normal), listOf(ScopeProblem(ScopeProblemCode.MISSING_MODULE, "domain-order"))) to ScopeProblemCode.MISSING_MODULE)
        for ((scope, code) in cases) {
            val r = inspect(scope); assertFalse(r.evaluated); assertTrue(r.problems.any { it.code == code }); assertTrue(r.rules.isEmpty())
        }
    }
    @Test fun `없는 위치나 잘못된 매핑 정책은 부분 성공으로 보고하지 않는다`() {
        val s = namingScope("app-api" to listOf(SubmitOrderUseCase::class.java))
        for (p in listOf(policy.copy(folders = policy.folders.filter { it.id != "order-application" }),
            policy.copy(naming = policy.naming.copy(storeTargets = mapOf("missing" to "unknown"))))) {
            val r = inspect(s, p); assertFalse(r.evaluated); assertTrue(r.problems.isNotEmpty()); assertEquals(emptyList(), r.violations)
        }
    }
    @Test fun `삭제 손상 실제 읽기 누락은 기존 수집 오류를 유지한다`() {
        val output = fixtureOutput(root.resolve("classes"), SubmitOrderUseCase::class.java, NewHelper::class.java)
        val expectations = ScopeExpectations(mapOf("app-api" to setOf(SubmitOrderUseCase::class.java.name)))
        val helper = output.resolve(NewHelper::class.java.name.replace('.', '/') + ".class")
        val missingRead = ProductionScopeImporter(BytecodeReader { ClassFileImporter().importClasses(SubmitOrderUseCase::class.java) })
            .load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(missingRead.problems.any { it.code == ScopeProblemCode.INCOMPLETE_IMPORT }); assertFalse(inspect(missingRead).evaluated)
        Files.write(helper, byteArrayOf(1, 2, 3))
        val damaged = ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)
        assertTrue(damaged.problems.any { it.code == ScopeProblemCode.READ_FAILURE }); assertFalse(inspect(damaged).evaluated)
        Files.delete(output.resolve(SubmitOrderUseCase::class.java.name.replace('.', '/') + ".class"))
        assertFalse(inspect(ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), expectations)).evaluated)
    }
    @Test fun `프로젝트 정책은 이유를 가진 정확한 16개 폴더다`() {
        val p = ProjectLayoutPolicy.target
        assertEquals(emptyList(), LayoutPolicyValidation.inspect(p, ProductionScope.requiredTypes.keys)); assertEquals(emptyList(), PortPlacementPolicy.validate(p))
        assertEquals(16, p.folders.size); assertEquals("com.exchange.core.ledger", p.folders.single { it.id == "domain-ledger" }.packageName)
        assertEquals("com.exchange.core.api.matching.application.port", p.folders.single { it.id == "matching-ports" }.packageName)
        assertTrue(p.folders.all { it.reason.isNotBlank() }); assertTrue(p.folders.none { it.folder.endsWith("/internal") })
    }
    @Test fun `필요한 간접 상위 정의가 없으면 단서 없는 이름도 거짓 통과하지 않는다`() {
        val parent = root.resolve("com/example/UnreadableParent.java")
        val worker = root.resolve("com/example/OrderWorker.java")
        Files.createDirectories(parent.parent)
        Files.writeString(parent, "package com.example; public class UnreadableParent {}")
        Files.writeString(worker, "package com.example; public class OrderWorker extends UnreadableParent {}")
        val output = root.resolve("classes"); Files.createDirectories(output)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), parent.toString(), worker.toString()))
        Files.delete(output.resolve("com/example/UnreadableParent.class"))
        val scope = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importPath(output)))
        val r = inspect(scope)
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.detail?.contains("UnreadableParent") == true }); assertTrue(r.rules.isEmpty())
    }
    @Test fun `named nested와 companion은 자신의 이름 단서로 검사한다`() {
        val t = NamedContainer.RefreshUseCase::class.java
        val r = namingInspect(policy, t)
        r.assertReady(); assertEquals(emptyList(), r.violations); assertEquals(setOf(t.name), r.selected("UseCase"))
    }
    @Test fun `JvmMultifile의 kind만으로 이름 검사를 제외하고 원본 그래프를 만들지 않는다`() {
        val names = listOf("NamingOperations", "NamingOperations__MultiOneKt", "NamingOperations__MultiTwoKt")
        val carriers = names.map { Class.forName("$namingPkg.$it", false, javaClass.classLoader) }
        val r = namingInspect(policy, *carriers.toTypedArray())
        r.assertReady(); assertTrue(r.evaluatedTypes.isEmpty()); assertEquals(emptyList(), r.violations)
    }

}

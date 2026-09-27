package com.exchange.architecture

import com.exchange.architecture.rules.NamingPlacement
import com.exchange.architecture.support.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*

class NamingPlacementIntegrationTest {
    @TempDir lateinit var root: Path
    private val packageName = "com.example.order.application"
    private val type = "$packageName.SubmitOrderUseCase"
    private val folder = AllowedFolder("order", "app-api", "src/main/java", "com/example/order/application", setOf(NamingRole.USE_CASE), "업무 진입점")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/java")), listOf(folder))
    private fun prepare(): Pair<ScopeImportResult, MainSourceSnapshot> {
        val source = root.resolve("src/main/java/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package $packageName; public class SubmitOrderUseCase {}")
        val output = root.resolve("classes"); Files.createDirectories(output)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", output.toString(), source.toString()))
        return ProductionScopeImporter().load(listOf(ModuleOutput("app-api", listOf(output))), ScopeExpectations(mapOf("app-api" to setOf(type)))) to
            MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/main/java", setOf(source))))
    }
    private fun inspect(scope: ScopeImportResult, sources: MainSourceSnapshot, p: LayoutPolicy = policy) =
        NamingPlacement.inspect(scope, p, sources, setOf("app-api"))

    @Test fun `NAME-17 컴파일된 실제 클래스와 원본을 연결해 정상 결과를 낸다`() {
        val (scope, sources) = prepare(); val r = inspect(scope, sources)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(type), r.evaluatedTypes); assertEquals(sources.roots.single().files.map { it.toString() }.toSet(), r.evaluatedFiles)
    }
    @Test fun `NAME-18 컴파일 출력은 같아도 원본만 옮기면 실제 폴더 위반이다`() {
        val (scope, sources) = prepare(); val input = sources.roots.single(); val source = input.files.single()
        val moved = source.parent.parent.resolve(source.fileName); Files.move(source, moved)
        val r = inspect(scope, MainSourceSnapshot(listOf(input.copy(files = setOf(moved)))))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(setOf("sourceFolder", "allowedFolder"), r.violations.map { it.item }.toSet())
    }
    @Test fun `NAME-19와26 허용 폴더의 역할이 다르면 타입 package 위반을 보고한다`() {
        val (scope, sources) = prepare()
        val p = policy.copy(folders = listOf(folder.copy(id = "other", roles = setOf(NamingRole.STORE_IMPLEMENTATION)),
            folder.copy(folder = "com/example/order/other")))
        val r = inspect(scope, sources, p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(listOf("package"), r.violations.map { it.item })
    }
    @Test fun `NAME-26 같은 package의 다른 역할 루트는 업무 역할을 허용하지 않는다`() {
        val (scope, sources) = prepare()
        val source = sources.roots.single().files.single()
        val moved = root.resolve("src/alternate/java/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(moved.parent); Files.move(source, moved)
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "src/alternate/java"),
            folders = policy.folders + folder.copy(id = "internal", sourceRoot = "src/alternate/java", roles = setOf(NamingRole.SERVICE)))
        val r = inspect(scope, MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/alternate/java", setOf(moved)))), p)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(listOf("sourceRoot"), r.violations.map { it.item })
        assertEquals("src/main/java", r.violations.single().expected)
        assertEquals(moved.toString(), r.violations.single().subject)
    }
    @Test fun `AUTO-19 같은 역할에 명시한 두 루트는 모두 허용한다`() {
        val (scope, sources) = prepare(); val source = sources.roots.single().files.single()
        val moved = root.resolve("src/alternate/java/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(moved.parent); Files.move(source, moved)
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "src/alternate/java"),
            folders = policy.folders + folder.copy(id = "alternate", sourceRoot = "src/alternate/java"))
        val r = inspect(scope, MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/alternate/java", setOf(moved)))), p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
    }
    @Test fun `NAME-22 원본의 package와 클래스 package 불일치 및 소스 누락은 미평가다`() {
        val (scope, sources) = prepare(); val source = sources.roots.single().files.single()
        Files.writeString(source, "package com.wrong; class SubmitOrderUseCase {}")
        val mismatch = inspect(scope, sources)
        assertFalse(mismatch.evaluated); assertTrue(mismatch.problems.any { it.subject == type })
        assertEquals(emptyList(), mismatch.violations); assertEquals(emptySet(), mismatch.evaluatedTypes)
        assertFalse(inspect(scope, MainSourceSnapshot()).evaluated)
    }
    @Test fun `NAME-22 소스 후보가 둘이면 이름으로 임의 선택하지 않는다`() {
        val (scope, sources) = prepare(); val other = root.resolve("generated/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(other.parent); Files.copy(sources.roots.single().files.single(), other)
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "generated", ":app-api:generate"),
            folders = policy.folders + folder.copy(id = "generated-order", sourceRoot = "generated"))
        val r = inspect(scope, MainSourceSnapshot(sources.roots + MainSourceRoot("app-api", root, "generated", setOf(other), ":app-api:generate")), p)
        assertFalse(r.evaluated); assertTrue(r.problems.any { it.subject == type }); assertEquals(emptyList(), r.violations)
    }
}

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
    private val folder = AllowedFolder("order-application", "app-api", "src/main/java", "com/example/order/application", "업무 진입점")
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

    @Test fun `NAME-17 실제 클래스 규칙과 원본 폴더 검사를 함께 실행한다`() {
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
    @Test fun `파일 폴더가 허용되어도 UseCase 허용 위치는 따로 확인한다`() {
        val (scope, sources) = prepare()
        val p = policy.copy(folders = listOf(folder.copy(id = "file-folder"), folder.copy(folder = "com/example/order/other")))
        val r = inspect(scope, sources, p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(listOf("package"), r.violations.map { it.item })
        assertEquals(sources.roots.single().files.map { it.toString() }.toSet(), r.evaluatedFiles)
    }
    @Test fun `AUTO-19 같은 영역에 명시한 두 루트는 모두 허용한다`() {
        val (scope, sources) = prepare(); val source = sources.roots.single().files.single()
        val moved = root.resolve("src/alternate/java/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(moved.parent); Files.move(source, moved)
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "src/alternate/java"),
            folders = policy.folders + folder.copy(id = "alternate", sourceRoot = "src/alternate/java"))
        val r = inspect(scope, MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/alternate/java", setOf(moved)))), p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
    }
    @Test fun `원본과 바이트코드를 일대일 연결하지 않아도 실제 package 폴더 불일치는 잡는다`() {
        val (scope, sources) = prepare(); val source = sources.roots.single().files.single()
        Files.writeString(source, "package com.wrong; class SubmitOrderUseCase {}")
        val mismatch = inspect(scope, sources)
        assertTrue(mismatch.evaluated, mismatch.problems.toString()); assertEquals(setOf("sourceFolder"), mismatch.violations.map { it.item }.toSet())
        assertFalse(inspect(scope, MainSourceSnapshot()).evaluated)
    }
    @Test fun `같은 파일명도 각 허용 루트의 실제 원본을 따로 검사한다`() {
        val (scope, sources) = prepare(); val other = root.resolve("generated/com/example/order/application/SubmitOrderUseCase.java")
        Files.createDirectories(other.parent); Files.copy(sources.roots.single().files.single(), other)
        val p = policy.copy(roots = policy.roots + AllowedSourceRoot("app-api", "generated", ":app-api:generate"),
            folders = policy.folders + folder.copy(id = "generated-order", sourceRoot = "generated"))
        val r = inspect(scope, MainSourceSnapshot(sources.roots + MainSourceRoot("app-api", root, "generated", setOf(other), ":app-api:generate")), p)
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations); assertEquals(2, r.evaluatedFiles.size)
    }
    @Test fun `package까지 persistence로 옮긴 Controller는 파일 통과 위치 위반이다`() {
        val type = com.exchange.architecture.fixtures.naming.HttpOrderController::class.java
        val p = namingPolicy("order-persistence" to type.packageName)
        val file = root.resolve("src/main/kotlin/${type.packageName.replace('.', '/')}/HttpOrderController.kt")
        Files.createDirectories(file.parent); Files.writeString(file, "package ${type.packageName}\nclass HttpOrderController")
        val source = MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/main/kotlin", setOf(file))))
        val r = NamingPlacement.inspect(namingScope("app-api" to listOf(type)), p, source, p.roots.map { it.module }.toSet())
        r.assertReady(); assertEquals(setOf("package"), r.items(type)); assertEquals(setOf(file.toString()), r.evaluatedFiles)
        assertFalse(r.violations.any { it.item in setOf("allowedFolder", "sourceFolder") })
    }
}

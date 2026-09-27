package com.exchange.architecture

import com.exchange.architecture.fixtures.naming.*
import com.exchange.architecture.rules.NamingPlacement
import com.exchange.architecture.rules.RoleNamingPlacement
import com.exchange.architecture.support.*
import com.exchange.architecture.support.NamingRole.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.*

class NamingPlacementMetadataTest {
    @TempDir lateinit var root: Path
    private val pkg = "com.exchange.architecture.fixtures.naming"
    private val folder = AllowedFolder("example", "app-api", "src/main/kotlin", pkg.replace('.', '/'), setOf(USE_CASE, FILE_FACADE, DATA, CONTROLLER), "메타데이터 예제")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private fun type(simple: String) = Class.forName("$pkg.$simple", false, javaClass.classLoader)
    private fun scope(vararg types: Class<*>) = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importClasses(*types)))
    private fun inspect(vararg types: Class<*>, p: LayoutPolicy = policy) = RoleNamingPlacement.inspectTypes(scope(*types), p, setOf("app-api"))

    @Test fun `AUTO-17 실제 Kotlin lambda 클래스의 소유자를 메타데이터로 확인한다`() {
        val source = root.resolve("LambdaUseCase.kt")
        Files.writeString(source, "package $pkg\nclass LambdaUseCase { fun task(): () -> Unit = { println(1) } }")
        val output = root.resolve("classes")
        val diagnostics = java.io.ByteArrayOutputStream()
        val code = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(java.io.PrintStream(diagnostics),
            "-no-stdlib", "-no-reflect", "-classpath", Metadata::class.java.protectionDomain.codeSource.location.toURI().path,
            "-jvm-target", "25", "-Xlambdas=class", "-d", output.toString(), source.toString())
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, code, diagnostics.toString())
        val imported = ScopeImportResult(mapOf("app-api" to ClassFileImporter().importPath(output)))
        val r = RoleNamingPlacement.inspectTypes(imported, policy, setOf("app-api"))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf("$pkg.LambdaUseCase"), r.generatedOwners.values.toSet())
        assertEquals(1, r.generatedOwners.size); assertEquals(2, r.evaluatedTypes.size)
    }
    @Test fun `AUTO-17 다중 파일 facade의 모든 part와 원본을 연결한다`() {
        val types = arrayOf(AmendOrderUseCase::class.java, type("NamingOperations"), type("NamingOperations__MultiOneKt"), type("NamingOperations__MultiTwoKt"))
        val sourceRoot = root.resolve("src/main/kotlin/${pkg.replace('.', '/')}")
        Files.createDirectories(sourceRoot)
        val files = listOf("NamingFixtures.kt", "MultiOne.kt", "MultiTwo.kt").map { name ->
            sourceRoot.resolve(name).also { Files.writeString(it, "package $pkg\n" + if (name == "NamingFixtures.kt") "class AmendOrderUseCase" else "fun ${name.substringBefore('.').lowercase()}() = 1") }
        }.toSet()
        val r = NamingPlacement.inspect(scope(*types), policy, MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/main/kotlin", files))), setOf("app-api"))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(files.map { it.toString() }.toSet(), r.evaluatedFiles)
        assertEquals(setOf("$pkg.NamingOperations__MultiOneKt", "$pkg.NamingOperations__MultiTwoKt"), r.classifications.getValue("$pkg.NamingOperations").sourceParts)
        Files.delete(sourceRoot.resolve("MultiTwo.kt"))
        val missing = NamingPlacement.inspect(scope(*types), policy, MainSourceSnapshot(listOf(MainSourceRoot("app-api", root, "src/main/kotlin", files))), setOf("app-api"))
        assertFalse(missing.evaluated); assertEquals(emptySet(), missing.evaluatedTypes)
    }
    @Test fun `AUTO-17 part 또는 facade 한쪽만 수집되면 미평가다`() {
        for (types in listOf(arrayOf(type("NamingOperations"), type("NamingOperations__MultiOneKt")), arrayOf(type("NamingOperations__MultiOneKt")))) {
            val r = inspect(AmendOrderUseCase::class.java, *types)
            assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedTypes)
        }
    }
    @Test fun `AUTO-16 Boot 위치의 운반 타입은 같은 원본 시작점이 필요하다`() {
        val p = policy.copy(folders = listOf(folder.copy(roles = setOf(BOOT, FILE_FACADE, USE_CASE))))
        val carrier = type("AutomaticRoleFixturesKt")
        val good = inspect(TestApplication::class.java, carrier, p = p)
        assertTrue(good.evaluated, good.problems.toString()); assertEquals(emptyList(), good.violations)
        val bad = inspect(AmendOrderUseCase::class.java, carrier, p = p)
        assertTrue(bad.evaluated, bad.problems.toString()); assertTrue(bad.violations.any { it.subject == carrier.name && it.item == "package" })
    }
    @Test fun `AUTO-06 내부 합성 어노테이션을 읽고 누락을 추측하지 않는다`() {
        val r = inspect(ComposedController::class.java, LocalHttp::class.java)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(emptyList(), r.violations.filter { it.subject == ComposedController::class.java.name })
        assertEquals(setOf(CONTROLLER), r.classifications.getValue(ComposedController::class.java.name).roles)
        val missing = inspect(ComposedController::class.java)
        assertFalse(missing.evaluated); assertTrue(missing.problems.any { it.detail?.contains("LocalHttp") == true })
    }
    @Test fun `AUTO-06 메타 어노테이션 순환은 방문한 정의에서 멈춘다`() {
        val r = inspect(CyclicUseCase::class.java, FirstMarker::class.java, SecondMarker::class.java)
        assertTrue(r.evaluated, r.problems.toString())
        assertEquals(emptyList(), r.violations.filter { it.subject == CyclicUseCase::class.java.name })
    }
    @Test fun `AUTO-22 잘못된 Kotlin 메타데이터는 일반 클래스로 통과시키지 않는다`() {
        val source = root.resolve("BrokenUseCase.java")
        Files.writeString(source, "package $pkg; @kotlin.Metadata(k=1, mv={2,3,0}, d1={\"broken\"}) public class BrokenUseCase {}")
        val output = root.resolve("classes"); Files.createDirectories(output)
        val kotlinJar = Metadata::class.java.protectionDomain.codeSource.location.toURI().path
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-classpath", kotlinJar, "-d", output.toString(), source.toString()))
        val classes = ClassFileImporter().importPath(output)
        val r = RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to classes)), policy, setOf("app-api"))
        assertFalse(r.evaluated); assertEquals(emptyList(), r.violations); assertEquals(emptySet(), r.evaluatedTypes)
    }
    @Test fun `AUTO-07 Java record도 내부 데이터로 자동 분류한다`() {
        val source = root.resolve("Result.java")
        Files.writeString(source, "package $pkg; public record Result(long value) {}")
        val output = root.resolve("classes"); Files.createDirectories(output)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", output.toString(), source.toString()))
        val classes = ClassFileImporter().importPaths(output, Path.of(AmendOrderUseCase::class.java.protectionDomain.codeSource.location.toURI()).resolve(AmendOrderUseCase::class.java.name.replace('.', '/') + ".class"))
        val r = RoleNamingPlacement.inspectTypes(ScopeImportResult(mapOf("app-api" to classes)), policy, setOf("app-api"))
        assertTrue(r.evaluated, r.problems.toString()); assertEquals(emptyList(), r.violations)
        assertEquals(setOf(DATA), r.classifications.getValue("$pkg.Result").roles)
    }
}

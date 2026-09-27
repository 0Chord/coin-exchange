package com.exchange.architecture

import com.exchange.architecture.support.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SourcePlacementContractTest {
    @TempDir lateinit var root: Path
    private val folder = AllowedFolder("orders", "app-api", "src/main/kotlin", "com/example/order/application",
        setOf(NamingRole.USE_CASE), "주문 실행")
    private val policy = LayoutPolicy(listOf(AllowedSourceRoot("app-api", "src/main/kotlin")), listOf(folder))
    private fun file(path: String = "com/example/order/application/Submit.kt", code: String = "package com.example.order.application\nclass SubmitOrderUseCase", sourceRoot: String = "src/main/kotlin"): Path =
        root.resolve("$sourceRoot/$path").also { Files.createDirectories(it.parent); Files.writeString(it, code) }
    private fun input(vararg files: Path, dir: String = "src/main/kotlin") = MainSourceRoot("app-api", root, dir, files.toSet())
    private fun inspect(vararg files: Path, p: LayoutPolicy = policy, dir: String = "src/main/kotlin") =
        SourcePlacement.inspect(listOf(input(*files, dir = dir)), p, setOf("app-api"))

    @Test fun `NAME-17 정상 Kotlin 폴더와 package는 일치한다`() {
        val source = file()
        val r = inspect(source)
        assertTrue(r.result.evaluated, r.result.problems.toString()); assertEquals(emptyList(), r.result.violations)
        assertEquals(setOf(source.toString()), r.result.evaluatedFiles)
        assertEquals(listOf("com", "example", "order", "application"), r.sources.single().packageSegments)
    }
    @Test fun `NAME-18 파일만 위로 옮기면 클래스가 없어도 폴더 불일치를 보고한다`() {
        val source = file("com/example/order/Types.kt", "package com.example.order.application\ntypealias Id = String")
        val r = inspect(source).result
        assertTrue(r.evaluated); assertEquals(setOf("sourceFolder", "allowedFolder"), r.violations.map { it.item }.toSet())
        assertEquals("com/example/order/application", r.violations.single { it.item == "sourceFolder" }.expected)
    }
    @Test fun `NAME-18 대소문자가 다른 폴더도 불일치다`() {
        val r = inspect(file("com/example/order/Application/Submit.kt")).result
        assertTrue(r.violations.any { it.item == "sourceFolder" })
    }
    @Test fun `NAME-20 여러 선언과 package만 있는 파일을 각각 한번 평가한다`() {
        val a = file("com/example/order/application/Many.kt", "package com.example.order.application\nclass A\nclass B\nfun c() = 1")
        val b = file("com/example/order/application/Package.kt", "package com.example.order.application")
        val r = inspect(a, b).result
        assertTrue(r.evaluated); assertEquals(2, r.evaluatedFiles.size); assertEquals(emptyList(), r.violations)
    }
    @Test fun `NAME-21 주석 문자열 파일 어노테이션 속 가짜 package는 선언이 아니다`() {
        val source = file(code = """
            /* package wrong.path */
            @file:Suppress("package wrong.path")
            package com.example.order.application
            val text = "package also.wrong"
        """.trimIndent())
        val r = inspect(source)
        assertTrue(r.result.evaluated, r.result.problems.toString()); assertEquals(emptyList(), r.result.violations)
        assertEquals(listOf("com", "example", "order", "application"), r.sources.single().packageSegments)
    }
    @Test fun `NAME-21 이스케이프 식별자와 package 없는 파일을 구문대로 읽는다`() {
        val escaped = inspect(file("com/when/Types.kt", "package com.`when`\ntypealias Id = String"))
        assertTrue(escaped.result.evaluated); assertEquals(listOf("com", "when"), escaped.sources.single().packageSegments)
        assertFalse(escaped.result.violations.any { it.item == "sourceFolder" })
        val noPackage = inspect(file("Plain.kt", "class Plain"))
        assertTrue(noPackage.result.evaluated); assertEquals(emptyList(), noPackage.sources.single().packageSegments)
        assertEquals(listOf("allowedFolder"), noPackage.result.violations.map { it.item })
    }
    @Test fun `NAME-21 구문 오류를 package 없음으로 바꾸지 않는다`() {
        for (code in listOf("package a..b\nclass X", "package com.example.order.application\nclass {")) {
            val r = inspect(file(code = code)).result
            assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedFiles); assertEquals(emptyList(), r.violations)
        }
    }
    @Test fun `NAME-22 파일 삭제와 루트 밖 경로는 준비 오류다`() {
        val deleted = file(); Files.delete(deleted)
        for (source in listOf(deleted, root.resolve("Else.kt").also { Files.writeString(it, "class X") })) {
            assertFalse(inspect(source).result.evaluated)
        }
    }
    @Test fun `NAME-22 중첩 루트와 심볼릭 링크 탈출로 소속을 숨길 수 없다`() {
        val source = file()
        val ambiguous = SourcePlacement.inspect(listOf(input(source), input(source, dir = "src/main/kotlin/com")), policy, setOf("app-api"))
        assertFalse(ambiguous.result.evaluated)
        val outside = root.resolve("Outside.kt").also { Files.writeString(it, "class Outside") }
        val link = source.parent.resolve("Link.kt"); Files.createSymbolicLink(link, outside)
        assertFalse(inspect(link).result.evaluated)
    }
    @Test fun `NAME-24와27 package와 폴더가 일치해도 미등록 경로는 위반이다`() {
        for (path in listOf("com/example/order/utils", "com/example/order/application/service", "com/example/order/applicationBackup")) {
            val r = inspect(file("$path/Types.kt", "package ${path.replace('/', '.')}\ntypealias Id = String")).result
            assertTrue(r.evaluated); assertEquals(listOf("allowedFolder"), r.violations.map { it.item })
        }
    }
    @Test fun `NAME-25 새 루트는 자동 허용하지 않으며 빈 기본 Java 루트만 비활성이다`() {
        val custom = file(sourceRoot = "custom/kotlin")
        assertTrue(inspect(custom, dir = "custom/kotlin").result.violations.any { it.item == "sourceRoot" })
        val normal = file()
        val emptyJava = SourcePlacement.inspect(listOf(input(normal), input(dir = "src/main/java")), policy, setOf("app-api"))
        assertEquals(emptyList(), emptyJava.result.violations)
        val java = file("com/example/order/application/Submit.java", "package com.example.order.application; class Submit {}", "src/main/java")
        val r = SourcePlacement.inspect(listOf(input(normal), input(java, dir = "src/main/java")), policy, setOf("app-api"))
        assertTrue(r.result.violations.any { it.item == "sourceRoot" })
    }
    @Test fun `NAME-17 Java 구문도 JDK 파서로 읽고 오류는 준비 실패로 남긴다`() {
        val p = policy.copy(roots = listOf(AllowedSourceRoot("app-api", "src/main/java")), folders = listOf(folder.copy(sourceRoot = "src/main/java")))
        val source = file("com/example/order/application/A.java", "/* package wrong; */ package com.example.order.application; class A {}", "src/main/java")
        val r = inspect(source, p = p, dir = "src/main/java")
        assertTrue(r.result.evaluated, r.result.problems.toString()); assertEquals(emptyList(), r.result.violations)
        Files.writeString(source, "package com..bad; class {")
        assertFalse(inspect(source, p = p, dir = "src/main/java").result.evaluated)
    }
    @Test fun `NAME-28 정책 추가는 정확한 경로에만 반영되고 제거하면 다시 위반이다`() {
        val source = file("com/example/order/application/cancel/Cancel.kt", "package com.example.order.application.cancel\nclass CancelOrderUseCase")
        assertEquals(listOf("allowedFolder"), inspect(source).result.violations.map { it.item })
        val p = policy.copy(folders = policy.folders + folder.copy(id = "cancel", folder = "com/example/order/application/cancel"))
        assertEquals(emptyList(), inspect(source, p = p).result.violations)
        val child = file("com/example/order/application/cancel/internal/A.kt", "package com.example.order.application.cancel.internal\nclass A")
        assertEquals(listOf("allowedFolder"), inspect(child, p = p).result.violations.map { it.item })
        assertEquals(listOf("allowedFolder"), inspect(source).result.violations.map { it.item })
    }
    @Test fun `NAME-28 중복 빈 역할 미등록 모듈과 잘못된 상대 경로 정책은 준비 오류다`() {
        for (p in listOf(policy.copy(folders = listOf(folder, folder.copy(id = "duplicate"))),
            policy.copy(folders = listOf(folder.copy(roles = emptySet()))),
            policy.copy(folders = listOf(folder.copy(module = "unknown"))),
            policy.copy(folders = listOf(folder.copy(folder = "com/../utils"))),
            policy.copy(folders = listOf(folder.copy(sourceRoot = "missing/root"))))) {
            val r = inspect(file(), p = p).result
            assertFalse(r.evaluated); assertEquals(emptySet(), r.evaluatedFiles); assertEquals(emptyList(), r.violations)
        }
    }
}

package com.exchange.architecture

import com.exchange.architecture.fixtures.ScopeValue
import com.exchange.architecture.fixtures.ForeignTestHelper
import com.exchange.architecture.support.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NonProductionTargetsTest {
    @TempDir lateinit var root: Path
    private val inventory = GradleModuleInventory(setOf(":app", ":bench"), setOf(":app"), setOf(":bench"))
    private val app = SourceSetKey(":app", "main")
    private val test = SourceSetKey(":app", "test")
    private val bench = SourceSetKey(":bench", "main")
    private val expected = setOf(app, test, bench)
    private fun records() = listOf(
        SourceSetOutput(app, listOf(fixtureOutput(root.resolve("main"), ScopeValue::class.java))),
        SourceSetOutput(test, listOf(fixtureOutput(root.resolve("test"), ForeignTestHelper::class.java))),
        SourceSetOutput(bench, listOf(root.resolve("bench"))),
    )

    @Test fun `실제 test 출력의 도우미만 금지 목적지로 모으고 main 값은 섞지 않는다`() {
        val result = NonProductionTargets.inspect(records(), expected, inventory)
        assertEquals(emptyList(), result.problems)
        assertEquals(setOf(ForeignTestHelper::class.java.name), result.knownTypes)
        val owner = result.owners.getValue(ForeignTestHelper::class.java.name)
        assertEquals(":app", owner.projectPath)
        assertEquals("test", owner.sourceSet)
        assertEquals(root.resolve("test").resolve(ForeignTestHelper::class.java.name.replace('.', '/') + ".class").toRealPath(), owner.file)
    }

    @Test fun `아직 컴파일하지 않은 비운영 출력은 정상 상태로 기록한다`() {
        val result = NonProductionTargets.inspect(records().map { if (it.key == test) it.copy(roots = listOf(root.resolve("absent"))) else it }, expected, inventory)
        assertEquals(emptyList(), result.problems)
        assertEquals(emptySet(), result.knownTypes)
        assertEquals(setOf(test, bench), result.outputs.filter { it.state == "ABSENT" }.map { it.key }.toSet())
    }

    @Test fun `미생성 폴더와 출력 기록 자체의 누락을 구분한다`() {
        val result = NonProductionTargets.inspect(records().filterNot { it.key == test }, expected, inventory)
        assertTrue(result.problems.any { it.contains("MISSING_SOURCE_SET") && it.contains(":app/test") })
        assertEquals(emptySet(), result.knownTypes)
    }

    @Test fun `손상된 클래스 파일을 읽기 실패로 보고 부분 목록을 사용하지 않는다`() {
        val input = records()
        Files.write(root.resolve("test/Broken.class"), byteArrayOf(1,2,3))
        val result = NonProductionTargets.inspect(input, expected, inventory)
        assertTrue(result.problems.any { it.contains("READ_FAILURE") && it.contains("Broken.class") })
        assertEquals(emptySet(), result.knownTypes)
    }

    @Test fun `같은 경로 별칭은 허용하고 다른 출력의 같은 타입은 소속 충돌이다`() {
        val input = records()
        val aliases = input.map { if (it.key == test) it.copy(roots = it.roots + listOf(it.roots.single().resolve("."))) else it }
        val aliasResult = NonProductionTargets.inspect(aliases, expected, inventory)
        assertEquals(emptyList(), aliasResult.problems)
        assertEquals(1, aliasResult.owners.size)
        fixtureOutput(root.resolve("bench"), ForeignTestHelper::class.java)
        val collision = NonProductionTargets.inspect(input, expected, inventory)
        assertTrue(collision.problems.any { it.contains("AMBIGUOUS_OWNERSHIP") && it.contains(ForeignTestHelper::class.java.name) })
        assertEquals(emptySet(), collision.knownTypes)
    }

    @Test fun `빈 발견 목록 중복 소스셋 미등록 프로젝트 빈 경로를 정상으로 보지 않는다`() {
        val input = records()
        val invalid = listOf(
            NonProductionTargets.inspect(input, emptySet(), inventory),
            NonProductionTargets.inspect(input + input.first(), expected, inventory),
            NonProductionTargets.inspect(input + SourceSetOutput(SourceSetKey(":unknown", "test"), listOf(root)), expected, inventory),
            NonProductionTargets.inspect(input.map { if (it.key == test) it.copy(roots = emptyList()) else it }, expected, inventory),
        )
        invalid.forEach { assertTrue(it.problems.isNotEmpty()); assertEquals(emptySet(), it.knownTypes) }
    }
    @Test fun `소스셋 전달 자료의 누락 중복 손상을 준비 실패로 구분한다`() {
        fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") {
            java.util.Base64.getUrlEncoder().encodeToString(it.toByteArray())
        }
        val input = records()
        val discovered = "ARCH08-SOURCES/1\n" + expected.joinToString("\n") { row("S", it.projectPath, it.sourceSet) }
        val outputs = "ARCH08-OUTPUTS/1\n" + input.joinToString("\n") { entry ->
            row("S", entry.key.projectPath, entry.key.sourceSet) + "\n" + entry.roots.joinToString("\n") { row("P", it.toString()) }
        }
        val valid = IsolationInputs.targets(discovered, outputs, inventory)
        assertEquals(emptyList(), valid.problems)
        assertEquals(setOf(ForeignTestHelper::class.java.name), valid.knownTypes)
        val invalid = listOf(
            IsolationInputs.targets(null, outputs, inventory),
            IsolationInputs.targets(discovered, null, inventory),
            IsolationInputs.targets(discovered + "\n" + row("S", ":app", "test"), outputs, inventory),
            IsolationInputs.targets(discovered, "ARCH08-OUTPUTS/1\n" + row("S", ":app", "main"), inventory),
            IsolationInputs.targets(discovered, outputs + "\nP\t%%", inventory),
        )
        invalid.forEach { assertTrue(it.problems.isNotEmpty()); assertEquals(emptySet(), it.knownTypes) }
    }

}

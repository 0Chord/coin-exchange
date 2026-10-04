package com.exchange.architecture

import com.exchange.architecture.support.ProductionScope
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 실제 main 출력을 별도 Kotlin 소비자로 컴파일한다. JVM 공개 modifier는 internal 판정에 쓰지 않는다. */
class MatchingStateAccessCompilationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `외부 소비자는 공개 엔진과 processor 계약을 사용할 수 있다`() {
        assertSuccessful(
            compile(
                """
            class Consumer {
                fun throughEngine(command: com.exchange.core.matching.MatchingCommand): List<com.exchange.core.matching.MatchingEvent> =
                    com.exchange.core.matching.MatchingEngine().process(command)

                fun throughProcessor(command: com.exchange.core.matching.MatchingCommand) {
                    val processor: com.exchange.core.matching.MarketCommandProcessor =
                        com.exchange.core.matching.InMemoryMarketCommandProcessor()
                    processor.submit(command)
                    processor.close()
                }
            }
        """,
            ),
        )
    }

    @Test
    fun `외부 소비자는 BookOrder 타입에 접근할 수 없다`() {
        assertExternalTypeRejected("BookOrder")
    }

    @Test
    fun `외부 소비자는 PriceLevel 타입에 접근할 수 없다`() {
        assertExternalTypeRejected("PriceLevel")
    }

    @Test
    fun `외부 소비자는 OrderBook 타입에 접근할 수 없다`() {
        assertExternalTypeRejected("OrderBook")
    }

    @Test
    fun `friend 소비자는 내부 주문을 생성하고 fill 뒤 잔량을 읽을 수 있다`() {
        assertSuccessful(compile(orderConsumer(), friend = true))
    }

    @Test
    fun `friend 소비자도 주문 잔량에 직접 대입할 수 없다`() {
        // 타입 접근과 동일 생성자가 먼저 성공해야 setter 오류를 internal 접근 오류와 구분할 수 있다.
        assertSuccessful(compile(orderConsumer(), friend = true))
        assertVisibilityRejected(
            compile(orderConsumer("order.remainingQuantity = com.exchange.core.common.Quantity(6)"), friend = true),
            diagnostic = "INVISIBLE_SETTER",
            symbol = "remainingQuantity",
        )
    }

    private fun assertExternalTypeRejected(type: String) {
        val source = """
            class Consumer {
                fun useType() {
                    com.exchange.core.matching.$type::class
                }
            }
        """
        // 같은 실제 타입이 friend 설정에서는 해석되어야 누락된 클래스나 classpath를 거절로 오인하지 않는다.
        assertSuccessful(compile(source, friend = true))
        assertVisibilityRejected(compile(source), diagnostic = "INVISIBLE_REFERENCE", symbol = type)
    }

    private fun orderConsumer(change: String = "order.fill(com.exchange.core.common.Quantity(4))") =
        """
        class Consumer {
            fun remaining(): com.exchange.core.common.Quantity {
                val order = com.exchange.core.matching.BookOrder(
                    orderId = com.exchange.core.common.OrderId("access-order"),
                    userId = com.exchange.core.common.UserId("access-user"),
                    side = com.exchange.core.order.Side.BUY,
                    price = com.exchange.core.common.Price(100),
                    originalQuantity = com.exchange.core.common.Quantity(10),
                    remainingQuantity = com.exchange.core.common.Quantity(10),
                )
                $change
                return order.remainingQuantity
            }
        }
    """

    private fun assertSuccessful(result: Compilation) {
        assertEquals(ExitCode.OK, result.exitCode, "정상 소비자 컴파일 실패 · 접근 거절의 근거로 사용할 수 없음:\n${result.diagnostics}")
        assertTrue(result.errors.isEmpty(), "정상 소비자에 오류 진단이 남음: ${result.diagnostics}")
        assertTrue(Files.isRegularFile(result.output.resolve("com/exchange/accessfixture/Consumer.class")), "정상 소비자 클래스 출력이 없음")
    }

    private fun assertVisibilityRejected(
        result: Compilation,
        diagnostic: String,
        symbol: String,
    ) {
        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "$symbol 접근 거절 계약 불충족. 내부 컴파일러/환경 실패는 접근성 거절이 아님:\n${result.diagnostics}",
        )
        assertTrue(result.errors.isNotEmpty(), "$symbol 접근성 오류 진단이 없음")
        result.errors.forEach { error ->
            assertEquals(CompilerMessageSeverity.ERROR, error.severity, "컴파일러/환경 오류는 접근 거절이 아님: $error")
            assertEquals(result.source.toString(), error.path, "접근 거절이 소비자 소스에서 발생해야 함: $error")
            assertTrue(error.line != null && error.line > 0, "접근 거절의 소스 위치가 없음: $error")
            assertTrue(
                error.message.contains("[$diagnostic]") && error.message.contains(symbol),
                "의도한 $symbol 접근성 진단만 허용. 누락 의존성/미해석 타입 오류는 준비 실패: $error",
            )
        }
    }

    private fun compile(
        body: String,
        friend: Boolean = false,
    ): Compilation {
        val outputs = ProductionScope.outputs().associateBy { it.module }
        val required =
            mapOf(
                "domain-common" to "com/exchange/core/common/Quantity.class",
                "domain-fee" to "com/exchange/core/fee/TradingFeeCalculator.class",
                "domain-order" to "com/exchange/core/order/Side.class",
                "domain-matching" to "com/exchange/core/matching/MatchingEngine.class",
            )
        val roots =
            required.flatMap { (module, representative) ->
                val output = assertNotNull(outputs[module], "컴파일 준비 실패: $module main 출력 속성이 없음")
                assertTrue(output.roots.isNotEmpty(), "컴파일 준비 실패: $module main 출력이 비어 있음")
                assertTrue(output.roots.all { Files.isDirectory(it) }, "컴파일 준비 실패: $module main 출력 폴더가 없음")
                assertTrue(
                    output.roots.any { Files.isRegularFile(it.resolve(representative)) },
                    "컴파일 준비 실패: $module 실제 운영 클래스 $representative 없음",
                )
                output.roots
            }
        val runtimePaths =
            assertNotNull(System.getProperty("architecture.testRuntime"), "컴파일 준비 실패: 테스트 runtime classpath가 없음")
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map(Path::of)
        assertTrue(
            runtimePaths.none { it.toString().endsWith(".jar") && !Files.isRegularFile(it) },
            "컴파일 준비 실패: 테스트 runtime 의존 jar가 없음",
        )
        // Java 소스/리소스가 없는 source set의 출력 폴더는 Gradle runtime 경로에만 남을 수 있다.
        val runtime = runtimePaths.filter { Files.exists(it) }
        val stdlib =
            Path.of(
                assertNotNull(
                    Unit::class.java.protectionDomain.codeSource,
                    "컴파일 준비 실패: Kotlin stdlib 위치가 없음",
                ).location.toURI(),
            )
        assertTrue(
            runtime.any { it.toAbsolutePath().normalize() == stdlib.toAbsolutePath().normalize() },
            "컴파일 준비 실패: Kotlin stdlib가 테스트 runtime classpath에 없음",
        )

        val fixture = Files.createTempDirectory(directory, "matching-access-")
        val source = fixture.resolve("Consumer.kt")
        val destination = Files.createDirectory(fixture.resolve("classes"))
        Files.writeString(source, "package com.exchange.accessfixture\n\n${body.trimIndent()}\n")
        val messages = mutableListOf<Diagnostic>()
        val collector =
            object : MessageCollector {
                override fun clear() = messages.clear()

                override fun hasErrors() = messages.any { it.severity.isError }

                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    if (severity !=
                        CompilerMessageSeverity.LOGGING
                    ) {
                        messages += Diagnostic(severity, message, location?.path, location?.line)
                    }
                }
            }
        val arguments =
            K2JVMCompilerArguments().apply {
                freeArgs = listOf(source.toString())
                classpath = (roots + runtime).distinct().joinToString(File.pathSeparator)
                this.destination = destination.toString()
                moduleName = "matching-state-access-consumer"
                jvmTarget = "25"
                jdkHome = System.getProperty("java.home")
                noStdlib = true
                noReflect = true
                renderInternalDiagnosticNames = true
                if (friend) {
                    friendPaths =
                        outputs
                            .getValue("domain-matching")
                            .roots
                            .map { it.toString() }
                            .toTypedArray()
                }
            }
        val compiler = K2JVMCompiler().apply { isReadingSettingsFromEnvironmentAllowed = false }
        val exitCode = compiler.exec(collector, Services.EMPTY, arguments)
        return Compilation(exitCode, messages.toList(), source, destination)
    }

    private data class Diagnostic(
        val severity: CompilerMessageSeverity,
        val message: String,
        val path: String?,
        val line: Int?,
    )

    private data class Compilation(
        val exitCode: ExitCode,
        val diagnostics: List<Diagnostic>,
        val source: Path,
        val output: Path,
    ) {
        val errors get() = diagnostics.filter { it.severity.isError }
    }
}

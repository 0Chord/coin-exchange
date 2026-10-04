package com.exchange.architecture.support

import com.sun.source.util.JavacTask
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/** 타입 해석이나 코드 실행 없이 실제 package 선언과 구문 오류를 읽는다. */
class SourcePackageParser : AutoCloseable {
    private val disposable = Disposer.newDisposable("ARCH-05 package parser")

    // PSI 환경 생성 API에만 한정한다. 컴파일러 버전 변경 시 구문 계약 테스트로 호환성을 확인한다.
    @OptIn(org.jetbrains.kotlin.K1Deprecation::class)
    private val environment by lazy {
        KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    fun read(file: Path): List<String> {
        val code = Files.readString(file)
        return when (file.fileName.toString().substringAfterLast('.')) {
            "kt" -> {
                val parsed = KtPsiFactory(environment.project, false).createFile(file.fileName.toString(), code)
                val errors = PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java)
                require(errors.isEmpty()) { errors.joinToString { it.errorDescription } }
                parsed.packageFqName.pathSegments().map { it.asString() }
            }

            "java" -> {
                val diagnostics = DiagnosticCollector<JavaFileObject>()
                val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK 구문 파서가 필요합니다" }
                compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
                    val source =
                        object : SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
                            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
                        }
                    val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(source)) as JavacTask
                    val unit = task.parse().single()
                    require(diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR }) { diagnostics.diagnostics.joinToString() }
                    unit.packageName?.toString()?.split('.') ?: emptyList()
                }
            }

            else -> {
                error("지원하지 않는 원본 확장자: $file")
            }
        }
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}

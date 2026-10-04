import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    base
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    kotlin("plugin.jpa") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

allprojects {
    group = "com.exchange"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

allprojects {
    configure<KtlintExtension> {
        version.set("1.8.0")
        kotlinScriptAdditionalPaths {
            include(fileTree("gradle") { include("**/*.kts") })
        }
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
        }
        filter {
            exclude("**/build/**")
        }
    }

    val lintProjectDirectory = layout.projectDirectory.asFile
    tasks.withType<BaseKtLintCheckTask>().configureEach {
        // 플러그인이 삭제된 파일의 오류를 남기지 않도록 목록 변경은 전체 재검사한다.
        inputs.property(
            "ktlintSourcePaths",
            providers.provider {
                source.files.map { it.relativeTo(lintProjectDirectory).invariantSeparatorsPath }.sorted()
            },
        )
    }
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}

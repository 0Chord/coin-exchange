import org.gradle.api.tasks.SourceSetContainer
import java.io.File

plugins {
	kotlin("jvm")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

dependencies {
	testImplementation(gradleTestKit())
	testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
	testImplementation(kotlin("test-junit5"))
	testImplementation("com.tngtech.archunit:archunit:1.4.2")
	// 위반 예제에 실제 애너테이션을 붙이기 위한 의존성이다. Spring 컨텍스트는 띄우지 않는다.
	testImplementation("org.springframework:spring-context")
	// HTTP 경계 예제의 실제 컨트롤러·응답 타입만 읽으며 서버는 시작하지 않는다.
	testImplementation("org.springframework:spring-web")
	testImplementation("org.springframework:spring-jdbc")
	// 업무 계층의 트랜잭션 메타데이터 허용 예제에 사용한다. 실제 트랜잭션은 실행하지 않는다.
	testImplementation("org.springframework:spring-tx")
	testImplementation("org.springframework:spring-test")
	testImplementation("org.testcontainers:testcontainers")
	testImplementation("org.openjdk.jmh:jmh-core:1.37")
	testImplementation("jakarta.persistence:jakarta.persistence-api")
	// 포트 계약 위반 예제에서 실제 기술 타입을 사용한다. 이 의존성으로 서버를 시작하지 않는다.
	testImplementation("jakarta.transaction:jakarta.transaction-api")
	testImplementation("tools.jackson.core:jackson-databind")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val productionModules = listOf(
	"domain-common", "domain-fee", "domain-order",
	"domain-ledger", "domain-matching", "app-api",
)
val nonProductionModules = listOf("architecture-tests", "benchmark-jmh")
val productionOutputs = productionModules.associateWith { module ->
	evaluationDependsOn(":$module")
	project(":$module").extensions.getByType<SourceSetContainer>()
		.named("main").get().output.classesDirs
}
extra["architecture.productionProjects"] = productionModules.map { ":$it" }
apply(from = "gradle/project-dependencies.gradle.kts")
apply(from = "gradle/isolation-inputs.gradle.kts")

val discoveredJvmProjects = providers.provider {
	rootProject.subprojects.filter {
		it.extensions.findByType<SourceSetContainer>()?.findByName("main") != null
	}.map { it.path }.sorted()
}
val forbiddenOutputs = providers.provider {
	rootProject.subprojects.flatMap { module ->
		module.extensions.findByType<SourceSetContainer>()?.filter {
			it.name != "main" || module.name in nonProductionModules
		}?.flatMap { it.output.classesDirs.files } ?: emptyList()
	}.map { it.absolutePath }.distinct().sorted()
}

tasks.withType<Test> {
	useJUnitPlatform()
	inputs.file("gradle/project-dependencies.gradle.kts")
	inputs.file("gradle/isolation-inputs.gradle.kts")
	systemProperty("architecture.isolationScript", file("gradle/isolation-inputs.gradle.kts").absolutePath)
	systemProperty("architecture.dependencyScript", file("gradle/project-dependencies.gradle.kts").absolutePath)
	systemProperty("architecture.gradleHome", requireNotNull(gradle.gradleHomeDir).absolutePath)
	productionModules.forEach { dependsOn(":$it:classes") }
	productionOutputs.forEach { (module, output) ->
		inputs.files(output).withPropertyName("productionClasses.$module")
	}
	inputs.property("discoveredJvmProjects", discoveredJvmProjects)
	inputs.property("registeredProductionModules", productionModules)
	inputs.property("registeredNonProductionModules", nonProductionModules)
	inputs.property("forbiddenOutputPaths", forbiddenOutputs)
	doFirst {
		// 등록 목록과 별도로 찾아야 새 모듈의 등록 누락을 잡을 수 있다. 모든 프로젝트 설정이 끝난 뒤 읽는다.
		systemProperty("architecture.discoveredJvmProjects", discoveredJvmProjects.get().joinToString(","))
		systemProperty("architecture.registration.production", productionModules.joinToString(",") { ":$it" })
		systemProperty("architecture.registration.nonProduction", nonProductionModules.joinToString(",") { ":$it" })
		systemProperty("architecture.forbiddenOutputs", forbiddenOutputs.get().joinToString(File.pathSeparator))
		productionOutputs.forEach { (module, output) ->
			// Java 소스가 없는 모듈에는 Java 출력 폴더가 생기지 않을 수 있다.
			systemProperty("architecture.outputs.$module", output.filter { it.exists() }.asPath)
		}
	}
	testLogging {
		events("failed", "skipped")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}

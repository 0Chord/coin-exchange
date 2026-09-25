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
	testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
	testImplementation(kotlin("test-junit5"))
	testImplementation("com.tngtech.archunit:archunit:1.4.2")
	// 위반 예제에 실제 애너테이션을 붙이기 위한 의존성이다. Spring 컨텍스트는 띄우지 않는다.
	testImplementation("org.springframework:spring-context")
	testImplementation("jakarta.persistence:jakarta.persistence-api")
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

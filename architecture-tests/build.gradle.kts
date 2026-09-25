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
	// Actual annotations in negative fixtures; no Spring context is started.
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
		// At execution time all projects are configured. Discovery must not reuse productionModules.
		systemProperty("architecture.discoveredJvmProjects", discoveredJvmProjects.get().joinToString(","))
		systemProperty("architecture.registration.production", productionModules.joinToString(",") { ":$it" })
		systemProperty("architecture.registration.nonProduction", nonProductionModules.joinToString(",") { ":$it" })
		systemProperty("architecture.forbiddenOutputs", forbiddenOutputs.get().joinToString(File.pathSeparator))
		productionOutputs.forEach { (module, output) ->
			// Source sets without Java sources need not create a Java output directory.
			systemProperty("architecture.outputs.$module", output.filter { it.exists() }.asPath)
		}
	}
	testLogging {
		events("failed", "skipped")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}

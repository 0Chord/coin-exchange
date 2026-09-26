import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Category
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import java.util.Base64

val productionPaths = (extra["architecture.productionProjects"] as List<*>).map { it as String }
fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") {
    Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
}
val isolationDependencies = providers.provider {
    buildList {
        add("ARCH08-DEPS/1")
        productionPaths.sorted().forEach { path ->
            val source = project(path)
            val main = source.extensions.getByType<SourceSetContainer>().named("main").get()
            listOf("compile" to main.compileClasspathConfigurationName, "runtime" to main.runtimeClasspathConfigurationName).forEach { (usage, name) ->
                add(row("C", path, usage, name, source.buildFile.absolutePath))
                val consumer = source.configurations.getByName(name)
                val consumerAttributes = consumer.attributes.keySet().associate { it.name to consumer.attributes.getAttribute(it).toString() }.toSortedMap()
                consumer.hierarchy.sortedBy { it.name }.forEach { declared ->
                    // 현재 모델에 나타난 직접 선언만 읽는다. 이 수집 때문에 구성을 강제로 해석하지 않는다.
                    declared.dependencies.withType(ModuleDependency::class.java).map { dependency ->
                        val projectDependency = dependency as? ProjectDependency
                        val kind = if (projectDependency == null) "external" else "project"
                        val target = projectDependency?.path ?: "${dependency.group.orEmpty()}:${dependency.name}"
                        val capabilities = dependency.requestedCapabilities
                        val fixture = capabilities.any { it.group == dependency.group && it.name == "${dependency.name}-test-fixtures" }
                        val configuration = dependency.targetConfiguration
                        val dependencyAttributes = dependency.attributes.keySet().associate { it.name to dependency.attributes.getAttribute(it).toString() }.toSortedMap()
                        // 실제 선택은 main 구성의 속성을 기본으로 쓰고, 같은 키의 개별 의존 속성이 우선한다.
                        val attributes = (consumerAttributes + dependencyAttributes).toSortedMap()
                        val supportedAttributes = mapOf(
                            "org.gradle.category" to setOf("library", "platform", "enforced-platform"),
                            "org.gradle.usage" to setOf("java-api", "java-runtime"),
                            "org.gradle.libraryelements" to setOf("jar", "classes"),
                            "org.gradle.jvm.environment" to setOf("standard-jvm"),
                            "org.gradle.dependency.bundling" to setOf("external"),
                            "org.jetbrains.kotlin.platform.type" to setOf("jvm"),
                        )
                        val unknownAttributes = attributes.any { (key, value) ->
                            if (key == "org.gradle.jvm.version") value.toIntOrNull()?.let { it > 0 } != true
                            else value !in supportedAttributes[key].orEmpty()
                        }
                        val artifacts = dependency.artifacts.map { "${it.name}:${it.type}:${it.extension}:${it.classifier.orEmpty()}" }.sorted()
                        val standardFixture = fixture || configuration in setOf("testFixturesApiElements", "testFixturesRuntimeElements") || dependency.artifacts.any { it.classifier == "test-fixtures" }
                        val unsupportedProject = projectDependency != null && (
                            configuration !in listOf(null, "default", "apiElements", "runtimeElements") ||
                            capabilities.isNotEmpty() || dependency.capabilitySelectors.isNotEmpty() || artifacts.isNotEmpty() || unknownAttributes
                        )
                        val selection = when { standardFixture -> "test-fixtures"; unsupportedProject -> "unsupported"; else -> "main" }
                        val details = "configuration=${configuration.orEmpty()}; capabilities=${capabilities.map { "${it.group}:${it.name}:${it.version}" }.sorted()}; artifacts=$artifacts; attributes=$dependencyAttributes; consumerAttributes=$consumerAttributes; effectiveAttributes=$attributes; selectors=${dependency.capabilitySelectors.map { it.displayName }.sorted()}"
                        row("D", kind, target, declared.name, selection, details, dependency.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name.orEmpty())
                    }.distinct().sorted().forEach { add(it) }
                }
            }
        }
    }.joinToString("\n")
}
val sourceInventory = providers.provider {
    buildList {
        add("ARCH08-SOURCES/1")
        rootProject.subprojects.sortedBy { it.path }.forEach { source ->
            source.extensions.findByType<SourceSetContainer>()?.sortedBy { it.name }?.forEach {
                add(row("S", source.path, it.name))
            }
        }
    }.joinToString("\n")
}
val sourceOutputs = providers.provider {
    buildList {
        add("ARCH08-OUTPUTS/1")
        rootProject.subprojects.sortedBy { it.path }.forEach { source ->
            source.extensions.findByType<SourceSetContainer>()?.sortedBy { it.name }?.forEach {
                add(row("S", source.path, it.name))
                it.output.classesDirs.files.sortedBy { file -> file.absolutePath }.forEach { file -> add(row("P", file.absolutePath)) }
            }
        }
    }.joinToString("\n")
}
val nonProductionClasses = providers.provider {
    rootProject.subprojects.flatMap { source ->
        source.extensions.findByType<SourceSetContainer>()?.filter { source.path !in productionPaths || it.name != "main" }
            ?.flatMap { it.output.classesDirs.files } ?: emptyList()
    }.map { root -> fileTree(root).matching { include("**/*.class") } }
}

val nonProductionCompilers = providers.provider {
    rootProject.subprojects.flatMap { source ->
        source.extensions.findByType<SourceSetContainer>()?.filter { source.path !in productionPaths || it.name != "main" }
            ?.flatMap { it.output.classesDirs.buildDependencies.getDependencies(null) } ?: emptyList()
    }.distinct()
}

tasks.withType<Test>().configureEach {
    // 두 작업이 함께 선택됐을 때만 생산자를 먼저 실행한다. 독립 검사에 test/JMH 컴파일을 추가하지 않는다.
    mustRunAfter(nonProductionCompilers)
    inputs.property("isolationDependencies", isolationDependencies)
    inputs.property("sourceInventory", sourceInventory)
    inputs.property("sourceOutputs", sourceOutputs)
    // 출력 경로만 읽으며, 전체 test/JMH 컴파일을 선행 작업으로 추가하지 않는다.
    inputs.files(nonProductionClasses).withPropertyName("nonProductionClasses").withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        systemProperty("architecture.isolationDependencies", isolationDependencies.get())
        systemProperty("architecture.sourceInventory", sourceInventory.get())
        systemProperty("architecture.sourceOutputs", sourceOutputs.get())
    }
}

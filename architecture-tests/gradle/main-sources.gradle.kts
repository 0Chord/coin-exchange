import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import java.util.Base64

val productionPaths = (extra["architecture.productionProjects"] as List<*>).map { it as String }
productionPaths.forEach { evaluationDependsOn(it) }
fun mainSets(path: String): List<SourceDirectorySet> {
    val main = project(path).extensions.getByType<SourceSetContainer>().named("main").get()
    return listOfNotNull(main.java, main.extensions.findByName("kotlin") as? SourceDirectorySet)
}
val mainSources = providers.provider {
    fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") {
        Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
    buildList {
        add("ARCH05/1")
        productionPaths.sorted().forEach { path ->
            val module = path.removePrefix(":")
            val sourceProject = project(path)
            add(row("M", module, sourceProject.projectDir.absolutePath))
            val sets = mainSets(path)
            val allFiles = sets.flatMap { it.files }.filter { it.extension in setOf("kt", "java") }.distinct()
            val producers = sets.flatMap { it.sourceDirectories.buildDependencies.getDependencies(null) }.distinct()
            sets.flatMap { it.srcDirs }.distinct().sortedBy { it.path }.forEach { root ->
                val relative = sourceProject.projectDir.toPath().relativize(root.toPath()).toString().replace('\\', '/')
                val ownerTasks = producers.filter { task -> task.outputs.files.files.any { output -> root.toPath().startsWith(output.toPath()) } }
                add(row("R", module, relative, ownerTasks.map { it.path }.sorted().joinToString(",")))
                allFiles.filter { it.toPath().startsWith(root.toPath()) }.sortedBy { it.path }.forEach {
                    add(row("F", module, relative, it.absolutePath))
                }
            }
            // 설정된 소스 루트 밖 파일을 조용히 빠뜨리지 않는다.
            check(allFiles.all { file -> sets.flatMap { it.srcDirs }.any { file.toPath().startsWith(it.toPath()) } }) {
                "main 소스의 루트 소속을 찾지 못했습니다: $path"
            }
        }
    }.joinToString("\n")
}
val policyFiles = fileTree("src/test/kotlin/com/exchange/architecture/policy") { include("**/*.kt") }
tasks.withType<Test>().configureEach {
    productionPaths.forEach { dependsOn("$it:classes") }
    inputs.property("mainSourceLayout", mainSources)
    inputs.files(providers.provider { productionPaths.flatMap(::mainSets) }).withPropertyName("mainSourceContents").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(policyFiles).withPropertyName("layoutPolicies").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("gradle/main-sources.gradle.kts")
    systemProperty("architecture.mainSourcesScript", file("gradle/main-sources.gradle.kts").absolutePath)
    doFirst { systemProperty("architecture.mainSources", mainSources.get()) }
}

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import java.util.Base64

// 운영 대상 목록은 기존 등록을 사용한다. 실제 프로젝트 발견과 누락 판정은 별도로 유지한다.
val productionPaths = (extra["architecture.productionProjects"] as List<*>).map { it as String }
val mainProjectDependencies = providers.provider {
    fun row(kind: String, vararg values: String) = kind + "\t" + values.joinToString("\t") {
        Base64.getUrlEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
    buildList {
        add("ARCH02/1")
        productionPaths.sorted().forEach { path ->
            val sourceProject = project(path)
            val main = sourceProject.extensions.getByType<SourceSetContainer>().named("main").get()
            listOf("compile" to main.compileClasspathConfigurationName, "runtime" to main.runtimeClasspathConfigurationName).forEach { (usage, name) ->
                // 의존 0개인 구성도 기록한다. 기록 누락을 빈 목록으로 오해하면 거짓 통과가 생긴다.
                add(row("C", path, usage, name, sourceProject.buildFile.absolutePath))
                // 구성 상속은 포함하지만 목적지 프로젝트의 전이 의존까지 펼치지는 않는다.
                sourceProject.configurations.getByName(name).hierarchy.sortedBy { it.name }.forEach { declared ->
                    declared.dependencies.withType<ProjectDependency>().sortedBy { it.path }.forEach { dependency ->
                        add(row("D", dependency.path, declared.name))
                    }
                }
            }
        }
    }.joinToString("\n")
}

tasks.withType<Test>().configureEach {
    inputs.property("mainProjectDependencies", mainProjectDependencies)
    doFirst { systemProperty("architecture.projectDependencies", mainProjectDependencies.get()) }
}

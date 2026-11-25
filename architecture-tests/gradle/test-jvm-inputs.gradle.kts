import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

tasks.withType<Test>().configureEach {
    inputs.file("gradle/test-jvm-inputs.gradle.kts")
    val argumentsFile = temporaryDir.resolve("architecture-jvm-arguments.txt")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("@${argumentsFile.absolutePath}") })
    // 모든 입력 수집 doFirst 뒤에 실행되도록 이 스크립트를 먼저 적용한다.
    doFirst {
        val architectureProperties = systemProperties.filterKeys { it.startsWith("architecture.") }
        argumentsFile.parentFile.mkdirs()
        argumentsFile.writeText(
            architectureProperties.entries.joinToString("\n", postfix = "\n") { (key, value) ->
                val argument = "-D$key=$value"
                "\"" +
                    argument
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t") + "\""
            },
            Charsets.UTF_8,
        )
        architectureProperties.keys.forEach { systemProperties.remove(it) }
    }
}

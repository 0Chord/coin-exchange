package com.exchange.architecture.rules

/** 현재 합의한 테스트 도구만 식별한다. test라는 단어나 Spring/Kotlin 전체를 금지하지 않는다. */
object TestToolPolicy {
    private val prefixes = mapOf(
        "JUnit" to listOf("org.junit.", "junit."),
        "Kotlin Test" to listOf("kotlin.test."),
        "Testcontainers" to listOf("org.testcontainers."),
        "JMH" to listOf("org.openjdk.jmh."),
        "ArchUnit" to listOf("com.tngtech.archunit."),
        "Spring Test" to listOf("org.springframework.test.", "org.springframework.boot.test.",
            "org.springframework.boot.testcontainers.", "org.springframework.boot.data.jpa.test.", "org.springframework.boot.jdbc.test."),
    )
    private val bootArtifacts = setOf(
        "spring-boot-test", "spring-boot-test-autoconfigure", "spring-boot-testcontainers",
        "spring-boot-starter-actuator-test", "spring-boot-starter-data-jpa-test", "spring-boot-starter-kafka-test",
        "spring-boot-starter-validation-test", "spring-boot-starter-webmvc-test", "spring-boot-starter-websocket-test",
    )
    fun type(name: String): String? = prefixes.entries.firstOrNull { (_, values) -> values.any { name.startsWith(it) } }?.key
    fun module(group: String, name: String): String? = when {
        group in setOf("org.junit.jupiter", "org.junit.platform", "org.junit.vintage") || group == "junit" && name == "junit" -> "JUnit"
        group == "org.jetbrains.kotlin" && (name == "kotlin-test" || name.startsWith("kotlin-test-")) -> "Kotlin Test"
        group == "org.testcontainers" -> "Testcontainers"
        group == "org.openjdk.jmh" -> "JMH"
        group == "com.tngtech.archunit" -> "ArchUnit"
        group == "org.springframework" && name == "spring-test" || group == "org.springframework.boot" && name in bootArtifacts -> "Spring Test"
        else -> null
    }
}

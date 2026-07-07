plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
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
    implementation(project(":domain-common"))
    implementation(project(":domain-order"))
    implementation(project(":domain-matching"))

    add("jmh", project(":domain-common"))
    add("jmh", project(":domain-order"))
    add("jmh", project(":domain-matching"))
}

jmh {
    benchmarkMode = listOf("thrpt")
    fork = 1
    warmupIterations = 1
    iterations = 3
    warmup = "500ms"
    timeOnIteration = "500ms"
    timeUnit = "s"
    resultFormat = "JSON"
    jmhVersion = "1.37"
}
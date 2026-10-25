plugins {
    kotlin("jvm")
    `java-library`
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
    api(project(":domain-common"))
    api(project(":domain-fee"))
    api(project(":domain-order"))
    api(project(":domain-ledger"))
    api(project(":domain-matching"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

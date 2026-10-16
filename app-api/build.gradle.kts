plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

val sbeGenerator by configurations.creating
val admissionSchema = layout.projectDirectory.file("src/main/sbe/order-admission.xml")
val admissionGeneratedSources = layout.buildDirectory.dir("generated/sources/sbe/main/java")
val generateAdmissionCodecs by tasks.registering(JavaExec::class) {
    group = "build"
    description = "주문 접수 SBE 스키마에서 Java 변환 코드를 생성한다."
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    classpath = sbeGenerator
    mainClass.set("uk.co.real_logic.sbe.SbeTool")
    inputs.file(admissionSchema)
    outputs.dir(admissionGeneratedSources)
    systemProperty("sbe.target.language", "Java")
    systemProperty("sbe.output.dir", admissionGeneratedSources.get().asFile.absolutePath)
    systemProperty("sbe.validation.stop.on.error", "true")
    systemProperty("sbe.generate.precedence.checks", "true")
    args(admissionSchema.asFile.absolutePath)
    doFirst {
        // 스키마에서 제거한 타입도 남지 않도록 이 작업이 소유한 출력만 지운다.
        delete(admissionGeneratedSources)
    }
}

sourceSets.named("main") {
    java.srcDir(generateAdmissionCodecs)
}

tasks.named("compileKotlin") {
    dependsOn(generateAdmissionCodecs)
}

tasks.named("compileJava") {
    dependsOn(generateAdmissionCodecs)
}

dependencies {
    sbeGenerator("uk.co.real-logic:sbe-tool:1.40.2")

    implementation(project(":domain-common"))
    implementation(project(":domain-order"))
    implementation(project(":domain-matching"))
    implementation(project(":domain-ledger"))
    implementation(project(":domain-trading"))
    implementation("org.agrona:agrona:2.6.1")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Agrona 2.6.1의 UnsafeApi가 사용하는 JDK 내부 패키지를 테스트 JVM에 연다.
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED")
    systemProperty("sbe.enable.precedence.checks", "true")
}

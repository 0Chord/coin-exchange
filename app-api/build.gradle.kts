import java.io.File

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.protobuf")
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

val protobufVersion = "4.33.2"
val aeronVersion = "1.53.3"
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    generateProtoTasks {
        all().configureEach {
            // Boot가 자동 등록한 gRPC 생성기는 접수 메시지 변환에 사용하지 않는다.
            plugins.remove(plugins.getByName("grpc"))
        }
    }
}

dependencies {
    implementation(project(":domain-common"))
    implementation(project(":domain-order"))
    implementation(project(":domain-matching"))
    implementation(project(":domain-ledger"))
    implementation(project(":domain-trading"))
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("io.aeron:aeron-cluster:$aeronVersion")

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

val fundingDistributionDirectory = layout.buildDirectory.dir("aeron-development")
val stageDevelopmentFundingRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "개발용 Aeron 노드와 클라이언트의 클래스·리소스·실행 의존성을 한 번 모은다."
    into(fundingDistributionDirectory)
    from(sourceSets["main"].output) {
        into("classes")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
}

val fundingVerificationDirectory = layout.buildDirectory.dir("aeron-verification")
val stageFundingVerificationRuntime by tasks.registering(Sync::class) {
    group = "verification"
    description = "Docker 검사용 진입점은 test 출력에만 두고 실제 main 실행 코드와 함께 준비한다."
    into(fundingVerificationDirectory)
    from(sourceSets["main"].output) {
        into("classes")
    }
    from(sourceSets["test"].output) {
        into("test-classes")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
}

tasks.withType<Test> {
    dependsOn(stageDevelopmentFundingRuntime, stageFundingVerificationRuntime)
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
    systemProperty("developmentFundingRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
    systemProperty("marketConfigurationHttpTestClasspath", sourceSets["test"].runtimeClasspath.asPath)
    systemProperty(
        "fundingVerificationRuntimeClasspath",
        sourceSets["test"].output.asPath + File.pathSeparator + sourceSets["main"].runtimeClasspath.asPath,
    )
    systemProperty("fundingProjectDirectory", projectDir.absolutePath)
    systemProperty("fundingDistributionDirectory", fundingVerificationDirectory.get().asFile.absolutePath)
}

springBoot {
    mainClass.set("com.exchange.core.ExchangeCoreApplicationKt")
}

listOf("Node", "Client").forEach { entry ->
    tasks.register<JavaExec>("runDevelopmentFunding$entry") {
        group = "application"
        description = "시작 설정을 준비한 뒤 개발용 자금 준비 ${if (entry == "Node") "노드" else "클라이언트"}를 실행한다."
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFunding${entry}Kt")
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
    }
}

tasks.register<JavaExec>("runDevelopmentOrderClient") {
    group = "application"
    description = "같은 개발 노드에 LIMIT+GTC 주문을 보내 접수 승인·거절을 확인한다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentOrderClientKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
}

tasks.register<JavaExec>("runDevelopmentMarketRegistration") {
    group = "application"
    description = "전체 마켓 설정 원문을 검사한 뒤 명시한 DB 버전에 등록하거나 같은 등록 결과를 확인한다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentMarketRegistrationKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

tasks.register<JavaExec>("runTradingGateway") {
    group = "application"
    description = "RDB 마켓 설정을 확인하고 Aeron 주문 접수 전용 HTTP 서버를 실행한다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.exchange.core.TradingGatewayApplicationKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
}

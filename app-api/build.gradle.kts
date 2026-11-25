import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

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
    implementation("org.rocksdb:rocksdbjni:10.1.3")

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

val ciTestGroup = providers.gradleProperty("ciTestGroup").orNull
val ciTestGroupsFile = rootProject.layout.projectDirectory.file(".github/ci-test-groups.json")
val ciTestGroups: Map<String, List<String>> by lazy {
    val document = ciTestGroupsFile.asFile.readText()
    val parsed = JsonSlurper().parseText(document)
    require(parsed is Map<*, *> && parsed.isNotEmpty()) { "CI 테스트 그룹 목록은 비어 있지 않은 객체여야 합니다" }
    // 이 스키마는 최상위 객체 하나뿐이다. 파서가 덮어쓴 중복 키도 실패시킨다.
    require(Regex("\"(?:\\\\.|[^\"\\\\])*\"\\s*:").findAll(document).count() == parsed.size) { "중복된 CI 테스트 그룹 키가 있습니다" }
    parsed.entries
        .associate { (key, value) ->
            require(key is String && key != "general" && key != "all" && key.matches(Regex("[a-z][a-z0-9-]*"))) { "잘못된 CI 테스트 그룹: $key" }
            require(value is List<*> && value.isNotEmpty()) { "$key 그룹의 테스트 클래스가 비어 있습니다" }
            val classes =
                value.map {
                    require(it is String && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
                        "$key 그룹은 완전한 최상위 클래스 이름만 지정할 수 있습니다: $it"
                    }
                    it
                }
            key to classes
        }.also { groups ->
            val classes = groups.values.flatten()
            require(classes.size == classes.distinct().size) { "하나의 CI 테스트 클래스를 중복 배정할 수 없습니다" }
        }
}

val verifyCiTestPartition by tasks.registering {
    group = "verification"
    description = "컴파일된 테스트 후보를 CI 그룹에 빠짐없이 한 번씩 배정하고 기록한다."
    dependsOn(tasks.named("testClasses"))
    inputs.file(ciTestGroupsFile)
    inputs.files(sourceSets["test"].output.classesDirs)
    inputs.property("selectedGroup", ciTestGroup ?: "all")
    val inventory = layout.buildDirectory.file("reports/ci-test-partition/inventory.json")
    outputs.file(inventory)
    doLast {
        val groups = ciTestGroups
        val owners = groups.flatMap { (group, classes) -> classes.map { it to group } }.toMap()
        val roots =
            sourceSets["test"]
                .output.classesDirs.files
                .filter(File::isDirectory)
        val candidates =
            roots
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .toList()
                }.sorted()
        require(candidates.isNotEmpty() && candidates.distinct().size == candidates.size) { "CI 테스트 후보가 비었거나 클래스 출력이 중복됐습니다" }
        val topLevelClasses = candidates.filterNot { '$' in it }.map { it.removeSuffix(".class").replace('/', '.') }.toSet()
        require(owners.keys.all { it in topLevelClasses }) { "컴파일 결과에 없는 CI 테스트 클래스: ${owners.keys - topLevelClasses}" }
        val partition =
            (listOf("general") + groups.keys).associateWith { group ->
                candidates.filter { path ->
                    val topLevel = path.substringBefore('$').removeSuffix(".class").replace('/', '.')
                    (owners[topLevel] ?: "general") == group
                }
            }
        require(partition.values.all { it.isNotEmpty() } && partition.values.flatten().sorted() == candidates) {
            "CI 그룹에 누락·중복 또는 빈 후보 목록이 있습니다"
        }
        val selected = if (ciTestGroup == null) candidates else partition.getValue(ciTestGroup)
        val actual =
            tasks
                .named<Test>("test")
                .get()
                .candidateClassFiles.files
                .filter { it.isFile && it.extension == "class" }
                .map { candidate ->
                    val path = candidate.toPath().toAbsolutePath().normalize()
                    val root =
                        roots.singleOrNull { path.startsWith(it.toPath().toAbsolutePath().normalize()) }
                            ?: error("Gradle 테스트 후보가 테스트 출력 경로 밖에 있습니다: $candidate")
                    root
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .relativize(path)
                        .toString()
                        .replace(File.separatorChar, '/')
                }.sorted()
        require(actual == selected) { "실제 Gradle 테스트 후보와 CI 그룹 배정이 다릅니다: 누락=${selected - actual.toSet()}, 추가=${actual - selected.toSet()}" }
        val sourceSha =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(ciTestGroupsFile.asFile.readBytes())
                .joinToString("") { "%02x".format(it) }
        inventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                JsonOutput.prettyPrint(
                    JsonOutput.toJson(
                        mapOf(
                            "group" to (ciTestGroup ?: "all"),
                            "catalogSha256" to sourceSha,
                            "groups" to (listOf("general") + groups.keys),
                            "allCandidates" to candidates.map { it.removeSuffix(".class").replace('/', '.') },
                            "selectedCandidates" to actual.map { it.removeSuffix(".class").replace('/', '.') },
                        ),
                    ),
                ),
            )
        }
    }
}

tasks.named<Test>("test") {
    if (ciTestGroup != null) {
        require(ciTestGroup == "general" || ciTestGroup in ciTestGroups) { "알 수 없는 CI 테스트 그룹: $ciTestGroup" }
        dependsOn(verifyCiTestPartition)
        inputs.property("ciTestGroup", ciTestGroup)
        // 원래 클래스와 그 내부 클래스만 선택한다. runtimeClasspath의 공용 helper는 그대로 둔다.
        val selected = if (ciTestGroup == "general") ciTestGroups.values.flatten() else ciTestGroups.getValue(ciTestGroup)
        val patterns =
            selected.flatMap { className ->
                val path = className.replace('.', '/')
                listOf("$path.class", "$path\$*.class")
            }
        if (ciTestGroup == "general") exclude(patterns) else include(patterns)
        failOnNoDiscoveredTests.set(true)
        filter.isFailOnNoMatchingTests = true
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

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
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
    description = "자금 엔진 주문 접수와 공용 RDB 조회를 제공하는 단일 HTTP 서버를 실행한다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.exchange.core.ExchangeCoreApplicationKt")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs("-XX:+UseG1GC", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED")
}

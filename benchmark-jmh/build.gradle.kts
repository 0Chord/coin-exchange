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

// 빠른 실행 확인용 기본값이다. 운영 성능을 검증하는 설정은 아니다.
// 기록한 측정 결과의 별도 CLI 옵션과 포함·제외 범위는 benchmark-jmh/README.md를 참고한다.
// 벤치마크 클래스의 @Fork, @Warmup, @Measurement에도 같은 짧은 기본값이 있다.
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

pluginManagement {
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        repositories {
            // CI의 빌드 플러그인 의존성은 Maven Central 미러를 먼저 조회한다.
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
            gradlePluginPortal()
        }
    }
}

rootProject.name = "exchange-core"

include(
    "domain-common",
    "domain-order",
    "domain-matching",
    "domain-ledger",
    "domain-fee",
    "domain-trading",
    "benchmark-jmh",
    "app-api",
    "architecture-tests",
)

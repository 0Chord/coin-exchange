pluginManagement {
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        repositories {
            // CI에서 Plugin Portal이 Maven Central로 넘기는 BOM 요청의 429를 피한다.
            maven("https://maven-central.storage-download.googleapis.com/maven2/") {
                content {
                    includeModule("org.jetbrains.kotlin", "kotlin-gradle-plugins-bom")
                }
            }
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

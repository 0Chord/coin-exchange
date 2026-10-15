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

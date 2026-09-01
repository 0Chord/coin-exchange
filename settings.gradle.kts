rootProject.name = "exchange-core"

include(
	"domain-common",
	"domain-order",
	"domain-matching",
	"domain-ledger",
	"domain-fee",
	"benchmark-jmh",
	"app-api",
)

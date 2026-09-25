package com.exchange.architecture.support

import java.io.File
import java.nio.file.Path

/** Explicit policy registration; it is deliberately not used to discover Gradle projects. */
object ProductionScope {
    val requiredTypes = mapOf(
        "domain-common" to setOf("com.exchange.core.common.Amount"),
        "domain-fee" to setOf("com.exchange.core.fee.TradingFeeCalculator"),
        "domain-order" to setOf("com.exchange.core.order.OrderReservation"),
        "domain-ledger" to setOf("com.exchange.core.ledger.Balance"),
        "domain-matching" to setOf("com.exchange.core.matching.MatchingEngine"),
        "app-api" to setOf("com.exchange.core.ExchangeCoreApplication"),
    )
    val roles = RoleRegistration(
        domainModules = requiredTypes.keys - "app-api",
        externalPorts = setOf(
            "com.exchange.core.order.OrderReservationStore",
            "com.exchange.core.ledger.BalanceStore",
            "com.exchange.core.ledger.LedgerTransactionStore",
            "com.exchange.core.api.matching.persistence.MatchingEventStore",
            "com.exchange.core.api.matching.publish.MatchingEventPublisher",
        ),
        executors = setOf(
            "com.exchange.core.matching.MarketCommandProcessor",
            "com.exchange.core.matching.InMemoryMarketCommandProcessor",
            "com.exchange.core.matching.MarketWorker",
            // This facade owns only the executor's top-level failedFuture helper.
            "com.exchange.core.matching.MarketCommandProcessorKt",
        ),
        reviewedPureInterfaces = setOf(
            "com.exchange.core.matching.MatchingCommand", "com.exchange.core.matching.MatchingEvent",
        ),
    )

    fun inventory() = GradleModuleInventory(
        discoveredModules = csv("architecture.discoveredJvmProjects"),
        productionModules = csv("architecture.registration.production"),
        nonProductionModules = csv("architecture.registration.nonProduction"),
    )

    fun outputs(): List<ModuleOutput> = System.getProperties().stringPropertyNames()
        .filter { it.startsWith("architecture.outputs.") }.sorted().map {
            ModuleOutput(it.removePrefix("architecture.outputs."), paths(it))
        }

    fun expectations() = ScopeExpectations(
        requiredTypesByModule = requiredTypes,
        requiredRoles = mapOf("ports" to roles.externalPorts, "executors" to roles.executors),
        forbiddenRoots = paths("architecture.forbiddenOutputs").toSet(),
        projectPackagePrefixes = setOf("com.exchange.core.", "com.exchange.architecture."),
        forbiddenTypePrefixes = setOf("com.exchange.architecture."),
    )

    private fun csv(property: String) = required(property).split(',').filter { it.isNotBlank() }.toSet()
    private fun paths(property: String) = required(property).split(File.pathSeparator).filter { it.isNotBlank() }.map(Path::of)
    private fun required(property: String) = requireNotNull(System.getProperty(property)) { "Gradle must provide $property" }
}

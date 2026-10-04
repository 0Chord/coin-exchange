package com.exchange.architecture.fixtures.applicationboundary.config

import com.exchange.architecture.fixtures.applicationboundary.FundsCalculator
import com.exchange.architecture.fixtures.applicationboundary.FundsPort
import com.exchange.architecture.fixtures.applicationboundary.PostgresFundsStore
import com.exchange.architecture.fixtures.applicationboundary.application.FundingService
import com.exchange.architecture.fixtures.applicationboundary.application.SubmissionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class Assembly {
    @Bean fun store(): FundsPort = PostgresFundsStore()

    @Bean fun funding(port: FundsPort) = FundingService(port, FundsCalculator())

    @Bean fun entry(funding: FundingService) = SubmissionService(funding)

    class NestedAssembly
}

@Configuration(proxyBeanMethods = false)
annotation class ComposedAssembly

@ComposedAssembly
class NewAssembly

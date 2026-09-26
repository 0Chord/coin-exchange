package com.exchange.architecture.fixtures.applicationboundary.application

import com.exchange.architecture.fixtures.applicationboundary.*

class SubmissionService(private val funding: FundingService) {
    fun submit(command: ReserveCommand) = funding.reserve(command)
}
class FundingService(private val port: FundsPort, private val calculator: FundsCalculator) {
    fun reserve(command: ReserveCommand): Reserved = calculator.reserve(command).also(port::save)
}
class NewHelper

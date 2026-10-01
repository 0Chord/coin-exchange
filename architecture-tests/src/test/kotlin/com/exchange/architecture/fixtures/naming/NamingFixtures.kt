package com.exchange.architecture.fixtures.naming

class SubmitOrderUseCase {
    data class Result(val accepted: Boolean)
    companion object { fun empty() = Result(false) }
    fun callback(): Runnable = object : Runnable { override fun run() = Unit }
}
class OrderSubmissionService
class UseCase
class OrderFundingService
class ReserveOrderUseCase
class MatchingCoordinator
class TradingFeeCalculator
class FeeTierResolver
interface BalanceStore
class PostgresBalanceStore
interface MatchingEventRepository
interface MatchingEventPublisher
class PersistentMatchingEventPublisher
class NoOpMatchingEventPublisher
class OrderController
class OrderConfig
class Request
class NewHelper
class `Forged$Helper`
class ForgedKt
fun quoteAmount() = 1

class AmendOrderUseCase
class OrderManager

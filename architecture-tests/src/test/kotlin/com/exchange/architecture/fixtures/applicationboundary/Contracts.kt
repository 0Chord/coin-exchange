package com.exchange.architecture.fixtures.applicationboundary

// 구조를 읽는 테스트용 예제다. 아래 업무·저장 메서드는 테스트에서 실행하지 않는다.
data class Funds(val amount: Long)
class ReserveCommand(val funds: Funds)
class Reserved(val funds: Funds)
class FundsCalculator { fun reserve(command: ReserveCommand) = Reserved(command.funds) }
interface FundsPort { fun save(result: Reserved) }
interface EventPort { fun publish(result: Reserved) }
interface ExecutorEntry { fun submit(command: ReserveCommand): java.util.concurrent.CompletableFuture<Reserved> }
open class PostgresFundsStore : FundsPort { override fun save(result: Reserved) = Unit }
class JpaFundsStore : FundsPort { override fun save(result: Reserved) = Unit }
class NoOpPublisher : EventPort { override fun publish(result: Reserved) = Unit }
interface StoredRepository { fun save(value: StoredFunds) }
class StoredFunds
class FakeUseCase
class RequestDto
class ResponseDto
class WebController
class ResponseMapper
class ErrorHandler
class SqlFundsStore(private val jdbc: org.springframework.jdbc.core.JdbcTemplate) : FundsPort {
    override fun save(result: Reserved) { jdbc.update("SELECT ?", result.funds.amount) }
}

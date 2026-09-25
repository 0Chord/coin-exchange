package com.exchange.architecture.fixtures

import jakarta.persistence.Entity
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.sql.Connection
import java.util.concurrent.CompletableFuture

@JvmInline
value class DomainAmount(val value: Long)

data class DomainBalance(val amount: DomainAmount)

class PureCalculator {
    fun sum(first: DomainAmount, second: DomainAmount): DomainAmount =
        DomainAmount(first.value + second.value)
}

interface DomainBalancePort {
    fun save(balance: DomainBalance)
}

class CallbackExecutor {
    fun execute(task: Runnable): CompletableFuture<Void> = CompletableFuture.runAsync(task)
}

@Component
class SpringAnnotatedDomain

@Entity
class JpaAnnotatedDomain

class JdbcFieldDomain(val connection: Connection)

class JdbcParameterDomain {
    @Suppress("UNUSED_PARAMETER")
    fun accept(connection: Connection) = Unit
}

class JdbcReturnDomain {
    fun connection(): Connection? = null
}

class GenericJdbcDomain(val connections: List<Connection>)

class HttpCallingDomain {
    fun createClient(): HttpClient = HttpClient.newHttpClient()
}

class PortCallingDomain {
    fun persist(port: DomainBalancePort, balance: DomainBalance) = port.save(balance)
}

class NestedDomain {
    class JdbcCollaborator(val connection: Connection)
}

@Component
class FrameworkApplication

class JdbcAdapter(val connection: Connection)

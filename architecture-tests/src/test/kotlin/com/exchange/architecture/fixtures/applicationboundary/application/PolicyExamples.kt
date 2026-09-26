package com.exchange.architecture.fixtures.applicationboundary.application

import com.exchange.architecture.fixtures.applicationboundary.*
import com.exchange.architecture.fixtures.applicationboundary.config.Assembly
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.support.GenericApplicationContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.util.concurrent.TimeUnit

@Service
class TransactionalWork(@param:Qualifier("funds") private val port: FundsPort) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun reserve(result: Reserved): List<URI> { port.save(result); return listOf(URI.create("urn:funds")) }
}
class MatchingCoordinator(private val executor: ExecutorEntry, private val events: EventPort) {
    fun match(command: ReserveCommand): Reserved = executor.submit(command).get(3, TimeUnit.SECONDS).also(events::publish)
}
class ConcreteDependencies(private val postgres: PostgresFundsStore, private val jpa: JpaFundsStore,
    private val publisher: NoOpPublisher, private val repository: StoredRepository, private val entity: StoredFunds,
    private val fake: FakeUseCase)
class HttpDependencies(private val input: RequestDto, private val output: ResponseDto,
    private val controller: WebController, private val mapper: ResponseMapper, private val errors: ErrorHandler)
class DatabaseDependencies(private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    private val entityManager: jakarta.persistence.EntityManager, private val connection: java.sql.Connection,
    private val dataSource: javax.sql.DataSource)
class WebDependencies(private val response: org.springframework.http.ResponseEntity<String>,
    private val client: java.net.http.HttpClient, private val rest: org.springframework.web.client.RestClient)
class ConfigCaller(private val config: Assembly) {
    fun lookup() = config.store()
    fun reference(): () -> FundsPort = config::store
}
class ContainerCaller(private val context: ApplicationContext, private val factory: BeanFactory,
    private val generic: GenericApplicationContext, private val custom: CustomContainer) {
    fun lookup(): Any = context.getBean("funds")
}
interface CustomContainer : ApplicationContext
class BeanInBusiness {
    @Bean fun helper() = "업무 코드가 Bean을 조립하지 않는다"
}
class PortOnly(private val port: FundsPort) { fun save(value: Reserved) = port.save(value) }

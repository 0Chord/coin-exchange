package com.exchange.architecture.fixtures.naming

import jakarta.persistence.Entity
import com.exchange.architecture.fixtures.naming.ports.EventPublisher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.data.jpa.repository.JpaRepository

interface WrongUseCase
data class WrongService(val value: Int)
@Service class CancelOrderUseCase
@Service class AnnotatedOrderManager
@RestController class OrderEndpoint
@RestController class HttpOrderController
@RestController class HttpOrderUseCase
@Configuration class Wiring
@Configuration class ExplicitOrderConfig
data class AmendOrderRequest(val amount: Long)
class FakeRequest
data class DataUseCase(val value: Int)
@Entity class EntityService
@Entity class JournalEntity
class BalancePolicy
sealed interface CoreEvent
@JvmInline value class CoreAmount(val value: Long)
interface WalletStore
interface AuditStore
interface ReservationStore
class PostgresWalletStore : WalletStore
class FakeWalletStore : WalletStore
class JpaWalletStore : WalletStore, AuditStore
class PostgresMixedStore : WalletStore, ReservationStore
interface ActualEventRepository : JpaRepository<JournalEntity, Long>
interface WrongEventStore : JpaRepository<JournalEntity, Long>
interface FakeRepository
class PersistentEventPublisher : EventPublisher
class NoOpEventPublisher : EventPublisher
class UnknownEventPublisher : EventPublisher
@Configuration @SpringBootApplication class TestApplication
@RestControllerAdvice class ApiExceptionHandler
data class ApiErrorResponse(val message: String)
enum class OrderState { OPEN, CLOSED }

@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@RestController
annotation class LocalHttp
@LocalHttp class ComposedController
@SecondMarker annotation class FirstMarker
@FirstMarker annotation class SecondMarker
@FirstMarker class CyclicUseCase
fun bootstrapName() = "example"

object SweepUseCase
abstract class AbstractUseCase
enum class EnumUseCase { ONE }

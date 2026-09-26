package com.exchange.architecture.fixtures.httpboundary.web

import com.exchange.architecture.fixtures.httpboundary.*
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class InputDto(val amount: Long)
data class OutputDto(val amount: Long)

@RestController
class NormalController(private val entry: SubmitEntry) {
    @PostMapping
    fun submit(@RequestBody request: InputDto): ResponseEntity<OutputDto> {
        val event = entry.submit(HttpCommand(HttpAmount(request.amount)))
        return ResponseEntity.ok(OutputDto(event.amount.value))
    }
}

@Controller
class ServiceNamedController(private val entry: SubmissionService) {
    fun submit(command: HttpCommand) = entry.submit(command)
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@RestController
annotation class ComposedController

@ComposedController
class NewController

class UnclassifiedMapper

@RestController
class StoreController(private val store: ExampleStore) {
    fun save() = store.save()
}

@RestController
class StorageTypesController(
    val store: ExampleStoreImpl,
    val repository: ExampleRepository,
    val entity: StoredEntity,
)

@RestController
class InternalWorkController(
    val funding: FundingTaskUseCase,
    val settlement: SettlementTask,
    val coordinator: MatchingCoordinator,
    val engine: ExampleEngine,
    val executor: ExampleExecutor,
    val book: ExampleBook,
)

@RestController
class WorkflowController(private val entry: WorkflowEntry) {
    fun submit() = entry.submit()
}


@RestController
class OnlyFieldController(val store: ExampleStore)

@RestController
class ParameterController {
    fun accept(store: ExampleStore) = Unit
}

@RestController
class ReturnController {
    fun result(): ExampleStore? = null
}

@RestController
class ArrayController {
    fun result(): Array<ExampleStore>? = null
}

@RestController
class GenericController {
    fun result(): List<ExampleStore>? = null
}

@RestController
class PortImplementingController : ExampleStore {
    override fun save() = Unit
}

@Target(AnnotationTarget.CLASS)
annotation class ApiType(val value: kotlin.reflect.KClass<*>)

@RestController
@ApiType(StoredEntity::class)
class AnnotatedEntityController

@RestController
class ReferenceController {
    fun portTask(store: ExampleStore): () -> Unit = store::save
    fun implementationTask(store: ExampleStoreImpl): () -> Unit = store::save
}

class StoringMapper(private val store: ExampleStore) {
    fun map(): String { store.save(); return "stored" }
}

class StoringDto {
    fun value(): String { ExampleStoreImpl().save(); return "stored" }
}

@RestController
class MapperController(private val mapper: StoringMapper) {
    fun submit() = mapper.map()
}

@RestController
class ExtensionController {
    fun submit(dto: InputDto) = dto.toStored()
}

open class StoringControllerParent {
    fun store() = ExampleStoreImpl().save()
}

@RestController
class ChildController : StoringControllerParent()

class CallingEntryMapper(private val entry: SubmitEntry) {
    fun convert(command: HttpCommand) = entry.submit(command)
}

@RestController
class OtherControllerCaller(val other: NormalController, val configuration: ExampleConfiguration)

@RestController
class ExternalWorkController {
    fun jdbc(value: java.sql.Connection) = Unit
    fun template(value: org.springframework.jdbc.core.JdbcTemplate) = Unit
    fun dataSource(value: javax.sql.DataSource) = Unit
    fun jpa(value: jakarta.persistence.EntityManager) = Unit
    fun http() = java.net.http.HttpClient.newHttpClient()
    fun client(value: org.springframework.web.client.RestClient) = Unit
    fun builder(value: org.springframework.web.client.RestClient.Builder) = Unit
    fun restTemplate(value: org.springframework.web.client.RestTemplate) = Unit
    fun restOperations(value: org.springframework.web.client.RestOperations) = Unit
}

@RestController
class NestedController {
    class Worker {
        fun save() = ExampleStoreImpl().save()
    }
    fun deferred(): () -> Unit = { ExampleStoreImpl().save() }
    fun anonymous(): Runnable = object : Runnable {
        override fun run() = ExampleStoreImpl().save()
    }
}

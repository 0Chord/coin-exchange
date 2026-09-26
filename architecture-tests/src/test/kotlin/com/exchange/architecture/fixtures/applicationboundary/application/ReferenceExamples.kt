package com.exchange.architecture.fixtures.applicationboundary.application

import com.exchange.architecture.fixtures.applicationboundary.*
import kotlin.reflect.KClass

// 호출하지 않아도 선언에 구체 기술이 노출되면 위반인 예제들이다.
class FieldWork(private val stored: PostgresFundsStore)
class ParameterWork { fun accept(stored: PostgresFundsStore) = stored.hashCode() }
interface ReturnWork { fun store(): PostgresFundsStore }
interface ArrayWork { fun stores(): Array<PostgresFundsStore> }
interface GenericWork { fun stores(): List<PostgresFundsStore> }
class InheritingWork : PostgresFundsStore()
annotation class UsesType(val value: KClass<*>)
@UsesType(StoredFunds::class)
class AnnotatedWork

class CallerWork(private val helper: SavingHelper) { fun save(value: Reserved) = helper.save(value) }
class SavingHelper { fun save(value: Reserved) = PostgresFundsStore().save(value) }
open class SavingParent { fun save(value: Reserved) = PostgresFundsStore().save(value) }
class ChildWork : SavingParent()
class ExtensionWork { fun save(value: Reserved) = value.storeDirectly() }

class ReferenceWork(private val concrete: PostgresFundsStore, private val port: FundsPort) {
    fun forbidden(): (Reserved) -> Unit = concrete::save
    fun allowed(): (Reserved) -> Unit = port::save
    fun construct(): () -> PostgresFundsStore = ::PostgresFundsStore
}
class NestedWork {
    class Worker { fun save(value: Reserved) = PostgresFundsStore().save(value) }
    fun deferred(value: Reserved): () -> Unit = { PostgresFundsStore().save(value) }
    fun anonymous(value: Reserved): Runnable = object : Runnable {
        override fun run() { PostgresFundsStore().save(value) }
    }
}

package com.exchange.architecture.fixtures.portcontracts

import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.springframework.core.env.Environment
import java.net.URI
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.CompletableFuture

data class BalanceValue(val amount: Long)
class DomainEntity
class ManualRow
@Entity class JpaRow
@Embeddable class EmbeddedRow
@MappedSuperclass open class BaseRow

interface ValuePort {
    fun find(id: String): BalanceValue?
    fun append(values: List<BalanceValue>)
    fun describe(uri: URI, instant: Instant): Map<String, DomainEntity>
    fun later(): CompletableFuture<BalanceValue>
}
interface ConnectionResultPort { fun load(): Connection }
interface ConnectionArgumentPort { fun save(connection: Connection) }
interface SpringResultPort { fun environment(): Environment }
interface ManualRowPort { fun load(): ManualRow }
interface JpaRowPort { fun load(): JpaRow }
interface EmbeddedRowPort { fun load(): EmbeddedRow }
interface BaseRowPort { fun load(): BaseRow }
interface EmptyPort
class NotAnInterface

/** DB 메서드는 호출하지 않는다. 기술 의존이 구현체에만 있다는 구조를 읽는 예제다. */
class JdbcValueAdapter(private val connection: Connection) : ValuePort {
    override fun find(id: String): BalanceValue? = error("구조 예제")
    override fun append(values: List<BalanceValue>) { connection.commit() }
    override fun describe(uri: URI, instant: Instant): Map<String, DomainEntity> = emptyMap()
    override fun later(): CompletableFuture<BalanceValue> = error("구조 예제")
}

interface NestedListPort { fun load(): Map<String, List<JpaRow>> }
interface ArrayPort { fun load(): Array<Connection> }
interface FutureRowPort { fun later(): CompletableFuture<JpaRow> }
interface PropertyPort { val connection: Connection; var entity: JpaRow }
interface ParentPort { fun inherited(): Connection }
interface ChildPort : ParentPort
interface GenericParent<T> { fun load(): T }
interface GenericChildPort : GenericParent<JpaRow>
interface TechnologyParentPort : Environment
interface MethodBoundPort { fun <T : Connection> load(): T }
@jakarta.transaction.Transactional
interface AnnotatedPort { fun load(): BalanceValue }
interface AnnotationMemberPort {
    @get:jakarta.transaction.Transactional
    val balance: BalanceValue
    fun save(@org.springframework.beans.factory.annotation.Qualifier("balance") value: BalanceValue)
}
interface ThrowsPort { @Throws(java.sql.SQLException::class) fun load(): BalanceValue }
interface MapperPort { fun mapper(): tools.jackson.databind.ObjectMapper }
interface NestedContractPort {
    fun load(): BalanceValue
    interface Exposed { fun connection(): Connection }
    companion object { fun connection(): Connection = error("구조 예제") }
}
interface DefaultBodyPort {
    fun allowed(): String = java.net.URL("https://example.invalid").protocol
    private fun hidden(): Connection = error("구조 예제")
}
class WrappedRow(val value: JpaRow)
interface OpaquePort { fun raw(): Any; fun wrapped(): WrappedRow }

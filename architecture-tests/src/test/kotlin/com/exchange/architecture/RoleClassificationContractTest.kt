package com.exchange.architecture

import com.exchange.architecture.fixtures.*
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleClassificationContractTest {
    private val registration = RoleRegistration(
        domainModules = setOf("domain"), externalPorts = setOf(DomainBalancePort::class.java.name),
        executors = setOf(CallbackExecutor::class.java.name), reviewedPureInterfaces = emptySet(),
    )

    @Test
    fun `C01 도메인은 기본 포함하고 포트와 실행기만 명시적으로 분리한다`() {
        val classes = ClassFileImporter().importClasses(
            DomainAmount::class.java, DomainBalance::class.java, DomainBalancePort::class.java,
            CallbackExecutor::class.java, NestedDomain::class.java, NestedDomain.JdbcCollaborator::class.java,
        )
        val result = RoleClassifier.classify(mapOf("domain" to classes), registration)
        assertEquals(emptyList(), result.problems)
        assertEquals(setOf(DomainAmount::class.java.name, DomainBalance::class.java.name, NestedDomain::class.java.name, NestedDomain.JdbcCollaborator::class.java.name), result.roles.pureDomain)
        assertEquals(registration.externalPorts, result.roles.externalPorts)
        assertEquals(registration.executors, result.roles.executors)
    }

    @Test
    fun `C02 신규 도메인 인터페이스는 포트 분류 검토를 요구한다`() {
        val classes = ClassFileImporter().importClasses(ScopeValue::class.java, ScopePort::class.java)
        val result = RoleClassifier.classify(mapOf("domain" to classes), registration.copy(externalPorts = emptySet(), executors = emptySet()))
        assertTrue(ScopeProblem(ScopeProblemCode.UNCLASSIFIED_INTERFACE, ScopePort::class.java.name) in result.problems)
        assertTrue(ScopePort::class.java.name in result.roles.pureDomain, "A new interface must not silently disappear")
    }

    @Test
    fun `C03 등록한 역할이 출력에 없거나 순수 집합이 비면 실패한다`() {
        val classes = ClassFileImporter().importClasses(DomainBalancePort::class.java)
        val result = RoleClassifier.classify(mapOf("domain" to classes), registration)
        assertTrue(ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, CallbackExecutor::class.java.name) in result.problems)
        assertTrue(ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "pure-domain:domain") in result.problems)
    }

    @Test
    fun `C04 포트와 같은 파일의 값 객체를 포트로 일괄 분류하지 않는다`() {
        val classes = ClassFileImporter().importClasses(DomainBalance::class.java, DomainBalancePort::class.java)
        val result = RoleClassifier.classify(mapOf("domain" to classes), registration.copy(executors = emptySet()))
        assertEquals(emptyList(), result.problems)
        assertEquals(setOf(DomainBalance::class.java.name), result.roles.pureDomain)
        assertEquals(setOf(DomainBalancePort::class.java.name), result.roles.externalPorts)
    }
}

package com.exchange.architecture

import com.exchange.architecture.fixtures.applicationboundary.*
import com.exchange.architecture.fixtures.applicationboundary.application.*
import com.exchange.architecture.rules.*
import com.exchange.architecture.support.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ApplicationBoundaryReferenceTest {
    @Test fun `APP-09 필드 인자 반환 배열 제네릭 상속만 있어도 구현 의존을 찾는다`() {
        val types = arrayOf(FieldWork::class.java, ParameterWork::class.java, ReturnWork::class.java,
            ArrayWork::class.java, GenericWork::class.java, InheritingWork::class.java)
        val result = applicationCheck(ApplicationRole.APPLICATION to types)
        assertEquals(types.map { it.name to PostgresFundsStore::class.java.name }.toSet(), result.pairs())
        assertTrue(result.violations.any { it.originType == GenericWork::class.java.name && "generic" in it.description.lowercase() })
    }
    @Test fun `APP-09 어노테이션 값으로 쓰는 영속 타입도 찾는다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(AnnotatedWork::class.java),
            ApplicationRole.DOMAIN to arrayOf(UsesType::class.java), ApplicationRole.ENTITY to arrayOf(StoredFunds::class.java))
        assertEquals(setOf(AnnotatedWork::class.java.name to StoredFunds::class.java.name), result.pairs())
    }
    @Test fun `APP-10 유즈케이스가 호출한 helper와 부모 본문의 실제 위반을 찾는다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(CallerWork::class.java, SavingHelper::class.java,
            ChildWork::class.java, SavingParent::class.java))
        assertEquals(setOf(SavingHelper::class.java.name to PostgresFundsStore::class.java.name,
            SavingParent::class.java.name to PostgresFundsStore::class.java.name), result.pairs())
    }
    @Test fun `APP-10 최상위 확장 함수도 역할에 등록하여 본문 위반을 찾는다`() {
        val facade = Class.forName("$APPLICATION_FIXTURES.application.WorkflowFunctionsKt")
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ExtensionWork::class.java, facade))
        assertEquals(setOf(facade.name to PostgresFundsStore::class.java.name), result.pairs())
        assertTrue(result.violations.any { it.sourceFile == "WorkflowFunctions.kt" && it.lineNumber == 7 })
    }
    @Test fun `APP-11 Kotlin 메서드 참조는 포트 허용과 구현 금지를 구분한다`() {
        val scope = applicationScope(*applicationNormalTypes, ReferenceWork::class.java)
        val accesses = scope.classesByModule.values.flatMap { it.toList() }.flatMap { it.methodCallsFromSelf + it.methodReferencesFromSelf }
            .filter { it.target.name == "save" && it.target.owner.name in setOf(FundsPort::class.java.name, PostgresFundsStore::class.java.name) }
        assertEquals(setOf(FundsPort::class.java.name, PostgresFundsStore::class.java.name), accesses.map { it.target.owner.name }.toSet())
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ReferenceWork::class.java))
        assertTrue(result.violations.isNotEmpty())
        assertEquals(setOf(PostgresFundsStore::class.java.name), result.violations.map { it.targetType }.toSet())
        for (access in accesses.filter { it.target.owner.name == PostgresFundsStore::class.java.name }) {
            assertTrue(result.violations.any { it.originType == access.origin.owner.name && it.lineNumber == access.lineNumber })
        }
    }
    @Test fun `APP-11 JVM 메서드 생성자 참조의 실제 줄과 대상이 일치한다`() {
        val type = Class.forName("$APPLICATION_FIXTURES.application.JavaReferences")
        val scope = applicationScope(*applicationNormalTypes, type)
        val origin = scope.classesByModule.getValue("application-example").get(type)
        assertEquals(setOf(12, 15), origin.methodReferencesFromSelf.map { it.lineNumber }.toSet())
        assertEquals(setOf(18), origin.constructorReferencesFromSelf.map { it.lineNumber }.toSet())
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(type))
        assertEquals(setOf(type.name to PostgresFundsStore::class.java.name), result.pairs())
        for (reference in origin.codeUnitReferencesFromSelf.filter { it.target.owner.name == PostgresFundsStore::class.java.name }) {
            assertTrue(result.violations.any { it.originType == type.name && it.targetType == reference.target.owner.name &&
                it.lineNumber == reference.lineNumber && "references" in it.description }, result.violations.toString())
        }
    }
    @Test fun `APP-11 중첩 익명 타입과 람다 본문을 전부 제외하지 않는다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(NestedWork::class.java))
        assertEquals(setOf(PostgresFundsStore::class.java.name), result.violations.map { it.targetType }.toSet())
        assertTrue(result.violations.any { it.originType == NestedWork.Worker::class.java.name })
        assertTrue(result.violations.any { "anonymous" in it.originType })
        assertTrue(result.violations.any { "deferred" in it.description })
    }
    @Test fun `APP-15 행 없는 서명은 위치를 추측하지 않는다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ReturnWork::class.java))
        val signature = assertNotNull(result.violations.singleOrNull { "return type" in it.description })
        assertEquals(null, signature.lineNumber)
        assertEquals("ReferenceExamples.kt", signature.sourceFile)
        assertEquals("ARCH-04", signature.ruleId)
        assertTrue("APPLICATION → INFRASTRUCTURE" in signature.description)
        assertEquals("engineering/architecture-check-spec.md", signature.specification)
    }
    @Test fun `APP-15 입력 순서와 반복 선택에 따라 결과가 바뀌지 않는다`() {
        val types = applicationNormalTypes.toList() + ReferenceWork::class.java
        val registration = applicationRegistration().with(ApplicationRole.APPLICATION, ReferenceWork::class.java)
        val a = ApplicationImplementationIndependence.inspect(applicationScope(*types.toTypedArray()), registration)
        val b = ApplicationImplementationIndependence.inspect(applicationScope(*(types.reversed() + types).toTypedArray()),
            registration.copy(types = registration.types.toList().reversed().toMap()))
        assertTrue(a.evaluated && b.evaluated)
        assertTrue(a.violations.isNotEmpty())
        assertEquals(a, b)
        assertEquals(a.violations.distinct(), a.violations)
        assertEquals(a.violations.sortedWith(compareBy({ it.originType }, { it.targetType }, { it.sourceFile }, { it.lineNumber }, { it.description })), a.violations)
    }
}

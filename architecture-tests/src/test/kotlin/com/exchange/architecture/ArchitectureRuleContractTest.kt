package com.exchange.architecture

import com.exchange.architecture.fixtures.*
import com.exchange.architecture.rules.ArchitectureRoles
import com.exchange.architecture.rules.ArchitectureViolation
import com.exchange.architecture.rules.DomainTechnologyIndependence
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureRuleContractTest {
    @Test
    fun `A01 순수 값과 계산기의 협력은 허용한다`() {
        val types = arrayOf(DomainAmount::class.java, PureCalculator::class.java)
        val classes = importFixtures(*types)

        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(classes, pure(*types)))
    }

    @Test
    fun `A02 data class의 생성 멤버와 새 값 반환은 허용한다`() {
        val types = arrayOf(DomainAmount::class.java, DomainBalance::class.java)
        val classes = importFixtures(*types)
        assertTrue(classes.get(DomainBalance::class.java).methods.any { it.name.startsWith("copy") })

        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(classes, pure(*types)))
    }

    @Test
    fun `A03 도메인 타입으로 선언한 저장 포트는 외부 호출이 아니다`() {
        val classes = importFixtures(DomainAmount::class.java, DomainBalance::class.java, DomainBalancePort::class.java)
        val roles = ArchitectureRoles(
            pureDomain = setOf(DomainAmount::class.java.name, DomainBalance::class.java.name),
            externalPorts = setOf(DomainBalancePort::class.java.name),
        )

        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(classes, roles))
    }

    @Test
    fun `A04 실행기의 동시성 제어를 순수 도메인 위반으로 오인하지 않는다`() {
        val classes = importFixtures(DomainAmount::class.java, CallbackExecutor::class.java)
        assertDependency(classes, CallbackExecutor::class.java, "java.util.concurrent.CompletableFuture")
        val roles = ArchitectureRoles(
            pureDomain = setOf(DomainAmount::class.java.name),
            executors = setOf(CallbackExecutor::class.java.name),
        )

        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(classes, roles))
    }

    @Test
    fun `A05 순수 도메인의 Spring 어노테이션을 검출한다`() {
        assertViolation(SpringAnnotatedDomain::class.java, "org.springframework.stereotype.Component")
    }

    @Test
    fun `A06 순수 도메인의 JPA 어노테이션을 검출한다`() {
        assertViolation(JpaAnnotatedDomain::class.java, "jakarta.persistence.Entity")
    }

    @Test
    fun `A07 JDBC 프로퍼티와 생성자 의존을 검출한다`() {
        assertViolation(JdbcFieldDomain::class.java, "java.sql.Connection")
    }

    @Test
    fun `A08 필드 없이 메서드 인자에만 있는 JDBC 의존도 검출한다`() {
        assertViolation(JdbcParameterDomain::class.java, "java.sql.Connection")
    }

    @Test
    fun `A09 null을 반환해도 JDBC 반환 타입은 기술 의존이다`() {
        assertViolation(JdbcReturnDomain::class.java, "java.sql.Connection")
    }

    @Test
    fun `A10 제네릭 내부의 JDBC 타입도 검출한다`() {
        assertViolation(GenericJdbcDomain::class.java, "java.sql.Connection")
    }

    @Test
    fun `A11 HTTP 클라이언트 호출의 규칙과 타입 및 소스 근거를 보고한다`() {
        val origin = HttpCallingDomain::class.java
        val target = "java.net.http.HttpClient"
        val classes = importFixtures(origin)
        val call = classes.get(origin).methodCallsFromSelf.single {
            it.origin.name == "createClient" && it.target.owner.name == target && it.target.name == "newHttpClient"
        }
        // The fixed fixture calls newHttpClient on line 47. Do not derive the answer from the rule.
        assertEquals(47, call.sourceCodeLocation.lineNumber, "Fixture call site changed; review the source and expected line together")
        val violations = DomainTechnologyIndependence.evaluate(classes, pure(origin))
        val violation = assertNotNull(
            violations.find {
                it.ruleId == "ARCH-01" && it.originType == origin.name && it.targetType == target &&
                    it.description.contains("createClient") && it.description.contains("newHttpClient")
            },
            "Expected the createClient -> newHttpClient call, not the return-type dependency; actual=$violations",
        )

        assertEquals("DomainFixtures.kt", violation.sourceFile)
        assertEquals(47, violation.lineNumber, "Report the actual call location, not an arbitrary positive line")
        assertTrue(violation.specification.contains("architecture-check-spec.md"), "Link the diagnostic to the convention")
    }

    @Test
    fun `A12 순수 도메인이 저장 포트 메서드를 호출하는 우회를 검출한다`() {
        val caller = PortCallingDomain::class.java
        val port = DomainBalancePort::class.java
        val classes = importFixtures(caller, port, DomainBalance::class.java, DomainAmount::class.java)
        assertTrue(classes.get(caller).methodCallsFromSelf.any { it.target.owner.name == port.name && it.target.name == "save" })
        val roles = ArchitectureRoles(
            pureDomain = setOf(caller.name, DomainBalance::class.java.name, DomainAmount::class.java.name),
            externalPorts = setOf(port.name),
        )

        findViolation(DomainTechnologyIndependence.evaluate(classes, roles), caller.name, port.name)
    }

    @Test
    fun `A13 순수 타입 안의 중첩 클래스도 통째로 제외하지 않는다`() {
        val parent = NestedDomain::class.java
        val nested = NestedDomain.JdbcCollaborator::class.java
        val classes = importFixtures(parent, nested)
        assertDependency(classes, nested, "java.sql.Connection")

        findViolation(
            DomainTechnologyIndependence.evaluate(classes, pure(parent)),
            nested.name, "java.sql.Connection",
        )
    }

    @Test
    fun `A14 같은 입력에서도 순수 도메인의 위반만 보고하고 역할 밖 객체는 제외한다`() {
        val springDomain = SpringAnnotatedDomain::class.java
        val jdbcDomain = JdbcFieldDomain::class.java
        val application = FrameworkApplication::class.java
        val adapter = JdbcAdapter::class.java
        val classes = importFixtures(springDomain, jdbcDomain, application, adapter)
        assertDependency(classes, springDomain, "org.springframework.stereotype.Component")
        assertDependency(classes, application, "org.springframework.stereotype.Component")
        assertDependency(classes, jdbcDomain, "java.sql.Connection")
        assertDependency(classes, adapter, "java.sql.Connection")

        val violations = DomainTechnologyIndependence.evaluate(classes, pure(springDomain, jdbcDomain))

        assertEquals(
            setOf(
                Triple("ARCH-01", springDomain.name, "org.springframework.stereotype.Component"),
                Triple("ARCH-01", jdbcDomain.name, "java.sql.Connection"),
            ),
            violations.map { Triple(it.ruleId, it.originType, it.targetType) }.toSet(),
            "Find both pure-domain violations and no violations from the application or adapter",
        )
    }

    @Test
    fun `A15 기술 의존이 역할 밖에만 있으면 순수 값과 함께 읽어도 허용한다`() {
        val classes = importFixtures(DomainAmount::class.java, FrameworkApplication::class.java, JdbcAdapter::class.java)
        assertDependency(classes, FrameworkApplication::class.java, "org.springframework.stereotype.Component")
        assertDependency(classes, JdbcAdapter::class.java, "java.sql.Connection")

        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(classes, pure(DomainAmount::class.java)))
    }

    @Test
    fun `A16 행 정보가 없는 반환 타입 의존은 멤버를 보고하고 행 번호를 만들지 않는다`() {
        val origin = JdbcReturnDomain::class.java
        val classes = importFixtures(origin)
        val dependency = classes.get(origin).directDependenciesFromSelf.single {
            it.targetClass.name == "java.sql.Connection" && it.description.contains("has return type")
        }
        assertEquals(0, dependency.sourceCodeLocation.lineNumber, "This fixture's declaration has no bytecode source line")
        val violations = DomainTechnologyIndependence.evaluate(classes, pure(origin))
        val violation = findViolation(violations, origin.name, "java.sql.Connection")

        assertTrue(violation.description.contains("connection"), "Identify the member even when a line is unavailable")
        assertEquals("DomainFixtures.kt", violation.sourceFile)
        assertEquals(null, violation.lineNumber, "Represent unavailable line information explicitly, without guessing")
        assertTrue(violation.specification.contains("architecture-check-spec.md"))
    }

    @Test
    fun `A17 포트를 타입으로 보유하는 것만으로 외부 호출이라고 판정하지 않는다`() {
        val types = arrayOf(PortTypedDomain::class.java, DomainBalancePort::class.java)
        val roles = ArchitectureRoles(setOf(types[0].name), externalPorts = setOf(types[1].name))
        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(importFixtures(*types), roles))
    }

    @Test
    fun `A18 소켓 I O는 거절하지만 URI 값 타입은 허용한다`() {
        assertViolation(SocketDomain::class.java, "java.net.Socket")
        assertEquals(emptyList(), DomainTechnologyIndependence.evaluate(importFixtures(UriValueDomain::class.java), pure(UriValueDomain::class.java)))
    }

    @Test
    fun `A19 람다 본문과 파일 파사드의 기술 호출도 검사한다`() {
        val facade = Class.forName("com.exchange.architecture.fixtures.GeneratedDomainFixturesKt")
        val lambda = LambdaHttpDomain::class.java
        val classes = importFixtures(facade, lambda)
        val violations = DomainTechnologyIndependence.evaluate(classes, pure(facade, lambda))
        listOf(facade, lambda).forEach { origin ->
            assertTrue(classes.get(origin).methodCallsFromSelf.any { it.target.name == "newHttpClient" }, "Fixture must expose the actual generated call")
            assertTrue(violations.any { it.originType == origin.name && it.description.contains("newHttpClient") })
        }
    }

    @Test
    fun `A20 입력 클래스 순서가 바뀌어도 진단 순서와 내용은 같다`() {
        val types = arrayOf(SpringAnnotatedDomain::class.java, JdbcFieldDomain::class.java, HttpCallingDomain::class.java)
        val forward = DomainTechnologyIndependence.evaluate(importFixtures(*types), pure(*types))
        val reverse = DomainTechnologyIndependence.evaluate(importFixtures(*types.reversedArray()), pure(*types))
        assertTrue(forward.size >= 3)
        assertEquals(forward, reverse)
    }

    private fun assertViolation(origin: Class<*>, target: String): ArchitectureViolation {
        val classes = importFixtures(origin)
        // A malformed fixture or unsupported import must not masquerade as a rule's Red.
        assertDependency(classes, origin, target)
        return findViolation(DomainTechnologyIndependence.evaluate(classes, pure(origin)), origin.name, target)
    }

    private fun findViolation(violations: List<ArchitectureViolation>, origin: String, target: String): ArchitectureViolation =
        assertNotNull(
            violations.find { it.ruleId == "ARCH-01" && it.originType == origin && it.targetType == target },
            "Expected ARCH-01: $origin -> $target; actual=$violations",
        )

    private fun assertDependency(classes: JavaClasses, origin: Class<*>, target: String) {
        assertTrue(
            classes.get(origin).directDependenciesFromSelf.any { it.targetClass.name == target },
            "Fixture precondition: ${origin.name} bytecode must actually reference $target",
        )
    }

    private fun importFixtures(vararg types: Class<*>): JavaClasses =
        ClassFileImporter().importClasses(*types).also { classes ->
            assertEquals(types.map { it.name }.toSet(), classes.map { it.name }.toSet(), "Fixture input must not be empty or incomplete")
        }

    private fun pure(vararg types: Class<*>) = ArchitectureRoles(pureDomain = types.map { it.name }.toSet())
}

package com.exchange.architecture

import com.exchange.architecture.fixtures.applicationboundary.*
import com.exchange.architecture.fixtures.applicationboundary.application.*
import com.exchange.architecture.fixtures.applicationboundary.config.*
import com.exchange.architecture.rules.*
import com.exchange.architecture.support.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationBoundaryContractTest {
    @Test fun `APP-01과07 업무 포트 조립은 통과하고 각각 검사한 타입 수를 보고한다`() {
        val result = applicationCheck()
        assertEquals(emptySet(), result.pairs())
        assertEquals(setOf(SubmissionService::class.java.name, FundingService::class.java.name), result.applicationTypes)
        assertEquals(setOf(Assembly::class.java.name, Assembly.NestedAssembly::class.java.name), result.configurationTypes)
        assertTrue(result.referenceCount > 0)
    }
    @Test fun `APP-02 Transactional과 DI 및 일반 값은 허용한다`() {
        assertEquals(emptySet(), applicationCheck(ApplicationRole.APPLICATION to arrayOf(TransactionalWork::class.java)).pairs())
    }
    @Test fun `APP-02 실행기 진입 계약과 Future 조율 및 발행 포트는 허용한다`() {
        assertEquals(emptySet(), applicationCheck(ApplicationRole.APPLICATION to arrayOf(MatchingCoordinator::class.java),
            ApplicationRole.EXECUTOR to arrayOf(ExecutorEntry::class.java), ApplicationRole.PORT to arrayOf(EventPort::class.java)).pairs())
    }
    @Test fun `APP-03과04 이름과 무관하게 모든 구체 저장 역할을 금지한다`() {
        val targets = arrayOf(PostgresFundsStore::class.java, JpaFundsStore::class.java, NoOpPublisher::class.java,
            StoredRepository::class.java, StoredFunds::class.java, FakeUseCase::class.java)
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ConcreteDependencies::class.java),
            ApplicationRole.INFRASTRUCTURE to targets.filter { it != StoredFunds::class.java }.toTypedArray(),
            ApplicationRole.ENTITY to arrayOf(StoredFunds::class.java), ApplicationRole.PORT to arrayOf(EventPort::class.java))
        assertEquals(targets.map { ConcreteDependencies::class.java.name to it.name }.toSet(), result.pairs())
        assertTrue(result.violations.all { it.ruleId == "ARCH-04" })
    }
    @Test fun `APP-05 HTTP 입력 응답 컨트롤러 매퍼 예외 처리기 모두 금지한다`() {
        val targets = arrayOf(RequestDto::class.java, ResponseDto::class.java, WebController::class.java, ResponseMapper::class.java, ErrorHandler::class.java)
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(HttpDependencies::class.java), ApplicationRole.HTTP to targets)
        assertEquals(targets.map { HttpDependencies::class.java.name to it.name }.toSet(), result.pairs())
    }
    @Test fun `APP-06 실제 JDBC JPA HTTP 라이브러리 참조를 금지한다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(DatabaseDependencies::class.java, WebDependencies::class.java))
        val expected = setOf("org.springframework.jdbc.core.JdbcTemplate", "jakarta.persistence.EntityManager", "java.sql.Connection", "javax.sql.DataSource")
            .map { DatabaseDependencies::class.java.name to it }.toSet() +
            setOf("org.springframework.http.ResponseEntity", "java.net.http.HttpClient", "org.springframework.web.client.RestClient")
                .map { WebDependencies::class.java.name to it }
        assertEquals(expected, result.pairs())
    }
    @Test fun `APP-08 업무가 config Bean을 직접 호출하거나 참조하면 금지한다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ConfigCaller::class.java))
        assertTrue(result.violations.isNotEmpty())
        assertEquals(setOf(Assembly::class.java.name), result.violations.map { it.targetType }.toSet())
        assertTrue(result.violations.any { it.originType == ConfigCaller::class.java.name && it.lineNumber != null })
    }
    @Test fun `APP-08 컨테이너 계약과 실제 구현 및 사용자 하위 타입도 금지한다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(ContainerCaller::class.java),
            ApplicationRole.PORT to arrayOf(CustomContainer::class.java))
        assertEquals(setOf("org.springframework.context.ApplicationContext", "org.springframework.beans.factory.BeanFactory",
            "org.springframework.context.support.GenericApplicationContext", CustomContainer::class.java.name)
            .map { ContainerCaller::class.java.name to it }.toSet(), result.pairs())
    }
    @Test fun `APP-08 업무 안의 Bean 선언을 금지하되 정상 config Bean은 허용한다`() {
        assertEquals(setOf(BeanInBusiness::class.java.name to "org.springframework.context.annotation.Bean"),
            applicationCheck(ApplicationRole.APPLICATION to arrayOf(BeanInBusiness::class.java)).pairs())
    }
    @Test fun `APP-12 포트 뒤 구현의 DB 작업을 호출자에게 전파하지 않는다`() {
        val result = applicationCheck(ApplicationRole.APPLICATION to arrayOf(PortOnly::class.java),
            ApplicationRole.INFRASTRUCTURE to arrayOf(SqlFundsStore::class.java))
        assertEquals(emptySet(), result.pairs())
        assertTrue(SqlFundsStore::class.java.name !in result.applicationTypes)
    }
    @Test fun `APP-15 준비 오류와 위반이 함께 있으면 부분 위반 없이 미평가다`() {
        val scope = applicationScope(*applicationNormalTypes, ConfigCaller::class.java)
            .copy(problems = listOf(ScopeProblem(ScopeProblemCode.READ_FAILURE, "broken.class")))
        val result = ApplicationImplementationIndependence.inspect(scope,
            applicationRegistration().with(ApplicationRole.APPLICATION, ConfigCaller::class.java))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.READ_FAILURE && it.subject == "broken.class" })
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
    }
}

internal fun applicationCheck(vararg groups: Pair<ApplicationRole, Array<Class<*>>>) = ApplicationImplementationIndependence.inspect(
    applicationScope(*(applicationNormalTypes.toList() + groups.flatMap { it.second.toList() }).distinct().toTypedArray()),
    groups.fold(applicationRegistration()) { registration, (role, types) -> registration.with(role, *types) },
).also { assertTrue(it.evaluated, it.problems.toString()) }
internal fun ApplicationBoundaryResult.pairs() = violations.map { it.originType to it.targetType }.toSet()

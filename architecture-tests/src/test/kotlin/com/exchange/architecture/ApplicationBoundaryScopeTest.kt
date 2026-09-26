package com.exchange.architecture

import com.exchange.architecture.fixtures.applicationboundary.*
import com.exchange.architecture.fixtures.applicationboundary.application.*
import com.exchange.architecture.fixtures.applicationboundary.config.*
import com.exchange.architecture.rules.belongsToRole
import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ClassFileImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationBoundaryScopeTest {
    @Test fun `APP-01과07 정상 업무와 포트 및 조립 역할을 준비한다`() {
        val result = prepare()
        assertEquals(emptyList(), result.problems)
        assertEquals(ApplicationRole.APPLICATION, result.roles[SubmissionService::class.java.name])
        assertEquals(ApplicationRole.APPLICATION, result.roles[FundingService::class.java.name])
        assertEquals(ApplicationRole.CONFIGURATION, result.roles[Assembly.NestedAssembly::class.java.name])
        assertEquals(ApplicationRole.PORT, result.roles[FundsPort::class.java.name])
    }
    @Test fun `APP-13 새 업무 보조 타입과 설정 타입의 등록 누락을 찾는다`() {
        val result = prepare(applicationScope(*applicationNormalTypes, NewHelper::class.java, NewAssembly::class.java, ComposedAssembly::class.java))
        assertProblem(result, ScopeProblemCode.UNCLASSIFIED_APPLICATION_TYPE, NewHelper::class.java.name)
        assertProblem(result, ScopeProblemCode.UNREGISTERED_CONFIGURATION, NewAssembly::class.java.name)
    }
    @Test fun `APP-13 발견 패키지 밖의 Configuration도 역할과 대조한다`() {
        assertProblem(prepare(applicationScope(*applicationNormalTypes, OutsideConfiguration::class.java)),
            ScopeProblemCode.UNREGISTERED_CONFIGURATION, OutsideConfiguration::class.java.name)
    }
    @Test fun `APP-13 합성 설정 어노테이션 선언은 조립 인스턴스로 오인하지 않는다`() {
        val result = prepare(applicationScope(*applicationNormalTypes, ComposedAssembly::class.java, NewAssembly::class.java),
            applicationRegistration().with(ApplicationRole.CONFIGURATION, ComposedAssembly::class.java, NewAssembly::class.java))
        assertEquals(emptyList(), result.problems)
    }
    @Test fun `APP-13 업무와 설정 이중 역할 및 중첩 역할 충돌을 거절한다`() {
        for (type in listOf(Assembly::class.java, Assembly.NestedAssembly::class.java)) {
            assertProblem(prepare(registration = applicationRegistration().with(ApplicationRole.APPLICATION, type)),
                ScopeProblemCode.CONFLICTING_APPLICATION_ROLE, type.name)
        }
    }
    @Test fun `APP-13 업무로 등록한 설정 어노테이션 타입을 자동 면제하지 않는다`() {
        val registration = applicationRegistration().copy(types = applicationRegistration().types - ApplicationRole.CONFIGURATION)
            .with(ApplicationRole.APPLICATION, Assembly::class.java)
        assertProblem(prepare(registration = registration), ScopeProblemCode.UNREGISTERED_CONFIGURATION, Assembly::class.java.name)
    }
    @Test fun `APP-14 업무 역할이 비거나 config만 있으면 준비 실패다`() {
        assertProblem(prepare(registration = applicationRegistration().copy(types = applicationRegistration().types - ApplicationRole.APPLICATION)),
            ScopeProblemCode.EMPTY_ROLE, "APPLICATION")
    }
    @Test fun `APP-14 빈 출력과 실제 없는 역할 등록은 실패다`() {
        val result = prepare(ScopeImportResult())
        assertProblem(result, ScopeProblemCode.EMPTY_SCOPE, "application-boundary")
        assertProblem(result, ScopeProblemCode.MISSING_ROLE_TYPE, SubmissionService::class.java.name)
    }
    @Test fun `APP-14 업무와 config가 참조한 내부 정의와 역할 누락을 각각 찾는다`() {
        for (target in listOf(FundsPort::class.java, PostgresFundsStore::class.java)) {
            val missing = prepare(applicationScope(*applicationNormalTypes.filter { it != target }.toTypedArray()))
            assertProblem(missing, ScopeProblemCode.UNRESOLVED_PROJECT_TYPE, target.name)
            val reg = applicationRegistration().copy(types = applicationRegistration().types.mapValues { (_, names) -> names - target.name })
            assertProblem(prepare(registration = reg), ScopeProblemCode.UNCLASSIFIED_APPLICATION_TYPE, target.name)
        }
    }
    @Test fun `APP-14 범위를 비우거나 잘못 지정해서 발견 검사를 끌 수 없다`() {
        val normal = applicationRegistration()
        for (reg in listOf(normal.copy(applicationPackages = emptySet()), normal.copy(configurationPackages = emptySet()),
            normal.copy(projectPackages = emptySet()), normal.copy(applicationPackages = setOf("broken..package")),
            normal.copy(configurationPackages = setOf("outside.project")))) {
            assertTrue(prepare(registration = reg).problems.any { it.code == ScopeProblemCode.INVALID_TARGET_INPUT })
        }
    }
    @Test fun `APP-13 외부 기술을 도메인 역할로 등록해도 입력 오류다`() {
        val scope = applicationScope(*applicationNormalTypes).let { it.copy(classesByModule = it.classesByModule +
            ("external" to ClassFileImporter().importClasses(java.sql.Connection::class.java))) }
        assertProblem(prepare(scope, applicationRegistration().with(ApplicationRole.DOMAIN, java.sql.Connection::class.java)),
            ScopeProblemCode.INVALID_TARGET_INPUT, "java.sql.Connection")
    }
    @Test fun `APP-14 모듈 사이 중복 정의와 기존 수집 오류를 보존한다`() {
        val scope = applicationScope(*applicationNormalTypes)
        val result = prepare(scope.copy(classesByModule = scope.classesByModule + ("duplicate" to scope.classesByModule.getValue("application-example")),
            problems = listOf(ScopeProblem(ScopeProblemCode.READ_FAILURE, "broken.class"))))
        assertProblem(result, ScopeProblemCode.DUPLICATE_TYPE, SubmissionService::class.java.name)
        assertProblem(result, ScopeProblemCode.READ_FAILURE, "broken.class")
    }
    private fun prepare(scope: ScopeImportResult = applicationScope(*applicationNormalTypes),
                        registration: ApplicationRoleRegistration = applicationRegistration()) = ApplicationBoundaryScope.prepare(scope, registration)
    private fun assertProblem(result: ApplicationBoundaryInput, code: ScopeProblemCode, subject: String) =
        assertTrue(result.problems.any { it.code == code && it.subject == subject }, result.problems.toString())
}

internal const val APPLICATION_FIXTURES = "com.exchange.architecture.fixtures.applicationboundary"
internal val applicationNormalTypes = arrayOf(SubmissionService::class.java, FundingService::class.java,
    Funds::class.java, ReserveCommand::class.java, Reserved::class.java, FundsCalculator::class.java,
    FundsPort::class.java, PostgresFundsStore::class.java, Assembly::class.java)
private val allApplicationFixtures by lazy { ClassFileImporter().importPackages(APPLICATION_FIXTURES) }
internal fun applicationScope(vararg types: Class<*>) = ScopeImportResult(mapOf("application-example" to
    allApplicationFixtures.that(DescribedPredicate.describe("명시한 예제와 실제 포함 타입") { belongsToRole(it, types.map { t -> t.name }.toSet()) })))
internal fun applicationRegistration() = ApplicationRoleRegistration(mapOf(
    ApplicationRole.APPLICATION to setOf(SubmissionService::class.java.name, FundingService::class.java.name),
    ApplicationRole.DOMAIN to setOf(Funds::class.java.name, ReserveCommand::class.java.name, Reserved::class.java.name, FundsCalculator::class.java.name),
    ApplicationRole.PORT to setOf(FundsPort::class.java.name),
    ApplicationRole.INFRASTRUCTURE to setOf(PostgresFundsStore::class.java.name),
    ApplicationRole.CONFIGURATION to setOf(Assembly::class.java.name)),
    setOf("$APPLICATION_FIXTURES.application"), setOf("$APPLICATION_FIXTURES.config"), setOf(APPLICATION_FIXTURES))
internal fun ApplicationRoleRegistration.with(role: ApplicationRole, vararg classes: Class<*>) =
    copy(types = types + (role to (types[role].orEmpty() + classes.map { it.name })))

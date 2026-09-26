package com.exchange.architecture

import com.exchange.architecture.fixtures.httpboundary.*
import com.exchange.architecture.fixtures.httpboundary.web.*
import com.exchange.architecture.support.*
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.base.DescribedPredicate
import com.exchange.architecture.rules.belongsToRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpBoundaryScopeTest {
    @Test
    fun `HTTP-01 등록한 컨트롤러와 변환 데이터의 역할을 준비한다`() {
        val result = HttpBoundaryScope.prepare(httpScope(*normalTypes), normalRegistration())
        assertEquals(emptyList(), result.problems)
        assertEquals(HttpRole.CONTROLLER, result.roles[NormalController::class.java.name])
        assertEquals(HttpRole.USE_CASE, result.roles[SubmitEntry::class.java.name])
        assertEquals(HttpRole.CONVERSION, result.roles[InputDto::class.java.name])
    }

    @Test
    fun `HTTP-02 Service라는 이름도 등록한 진입점이면 허용한다`() {
        val types = arrayOf(ServiceNamedController::class.java, SubmissionService::class.java, HttpAmount::class.java, HttpCommand::class.java, HttpEvent::class.java)
        val registration = httpRegistration(
            HttpRole.CONTROLLER to setOf(ServiceNamedController::class.java.name),
            HttpRole.USE_CASE to setOf(SubmissionService::class.java.name),
            HttpRole.DATA to types.drop(2).map { it.name }.toSet(),
        )
        val result = HttpBoundaryScope.prepare(httpScope(*types), registration)
        assertEquals(emptyList(), result.problems)
        assertEquals(HttpRole.USE_CASE, result.roles[SubmissionService::class.java.name])
    }

    @Test
    fun `HTTP-12 빈 입력은 빈 위반 목록의 통과가 아니다`() {
        val result = HttpBoundaryScope.prepare(ScopeImportResult(), normalRegistration())
        assertTrue(result.problems.any { it.code == ScopeProblemCode.EMPTY_SCOPE })
        assertTrue(result.problems.any { it.code == ScopeProblemCode.MISSING_ROLE_TYPE && it.subject == NormalController::class.java.name })
    }

    @Test
    fun `HTTP-12 컨트롤러 또는 유즈케이스 역할이 없으면 준비 실패다`() {
        for (role in listOf(HttpRole.CONTROLLER, HttpRole.USE_CASE)) {
            val result = HttpBoundaryScope.prepare(httpScope(*normalTypes), normalRegistration().copy(types = normalRegistration().types - role))
            assertTrue(result.problems.any { it.code == ScopeProblemCode.EMPTY_ROLE && it.subject == role.name }, result.problems.toString())
        }
    }

    @Test
    fun `HTTP-13 새 메타 어노테이션 컨트롤러를 등록하지 않으면 실패한다`() {
        val result = HttpBoundaryScope.prepare(httpScope(*normalTypes, NewController::class.java, ComposedController::class.java),
            normalRegistration().with(HttpRole.CONVERSION, ComposedController::class.java))
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNREGISTERED_CONTROLLER && it.subject == NewController::class.java.name }, result.problems.toString())
    }

    @Test
    fun `HTTP-13 역할이 없거나 두 역할로 겹친 API 타입을 거절한다`() {
        val missing = HttpBoundaryScope.prepare(httpScope(*normalTypes, UnclassifiedMapper::class.java), normalRegistration())
        assertTrue(missing.problems.any { it.code == ScopeProblemCode.UNCLASSIFIED_HTTP_TYPE && it.subject == UnclassifiedMapper::class.java.name })
        val conflict = HttpBoundaryScope.prepare(httpScope(*normalTypes), normalRegistration().with(HttpRole.PORT, InputDto::class.java))
        assertTrue(conflict.problems.any { it.code == ScopeProblemCode.CONFLICTING_HTTP_ROLE && it.subject == InputDto::class.java.name })
    }

    @Test
    fun `HTTP-12 실제로 없는 등록 타입과 역할을 정확히 보고한다`() {
        val missing = NormalController::class.java.name
        val result = HttpBoundaryScope.prepare(httpScope(*normalTypes.filter { it.name != missing }.toTypedArray()), normalRegistration())
        assertTrue(result.problems.any { it.code == ScopeProblemCode.MISSING_ROLE_TYPE && it.subject == missing })
        assertTrue(result.problems.any { it.code == ScopeProblemCode.EMPTY_ROLE && it.subject == "CONTROLLER" })
    }

    @Test
    fun `HTTP-13 API 패키지 밖의 새 컨트롤러도 어노테이션으로 발견한다`() {
        val result = HttpBoundaryScope.prepare(httpScope(*normalTypes, OutsidePackageController::class.java), normalRegistration())
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNREGISTERED_CONTROLLER && it.subject == OutsidePackageController::class.java.name })
    }

    @Test
    fun `HTTP-13 컨트롤러를 변환 역할로 잘못 등록해도 발견한다`() {
        val registration = normalRegistration().copy(types = normalRegistration().types - HttpRole.CONTROLLER)
            .with(HttpRole.CONVERSION, NormalController::class.java)
        val result = HttpBoundaryScope.prepare(httpScope(*normalTypes), registration)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.UNREGISTERED_CONTROLLER && it.subject == NormalController::class.java.name })
    }

    @Test
    fun `HTTP-14 내부 대상 정의 또는 대상 역할이 없으면 실패한다`() {
        val absent = HttpBoundaryScope.prepare(httpScope(*normalTypes.filter { it != HttpCommand::class.java }.toTypedArray()), normalRegistration())
        assertTrue(absent.problems.any { it.code == ScopeProblemCode.UNRESOLVED_PROJECT_TYPE && it.subject == HttpCommand::class.java.name })
        val registration = normalRegistration().copy(types = normalRegistration().types +
            (HttpRole.DATA to (normalRegistration().types.getValue(HttpRole.DATA) - HttpCommand::class.java.name)))
        val unknown = HttpBoundaryScope.prepare(httpScope(*normalTypes), registration)
        assertTrue(unknown.problems.any { it.code == ScopeProblemCode.UNCLASSIFIED_HTTP_TYPE && it.subject == HttpCommand::class.java.name })
    }

    @Test
    fun `HTTP-14 발견 범위를 비워 등록 누락 검사를 끌 수 없다`() {
        for (registration in listOf(normalRegistration().copy(apiPackages = emptySet()), normalRegistration().copy(projectPackages = emptySet()))) {
            val result = HttpBoundaryScope.prepare(httpScope(*normalTypes), registration)
            assertTrue(result.problems.any { it.code == ScopeProblemCode.INVALID_TARGET_INPUT })
        }
    }

    @Test
    fun `HTTP-14 외부 기술을 데이터 역할로 등록해 허용시킬 수 없다`() {
        val scope = httpScope(*normalTypes).copy(classesByModule = httpScope(*normalTypes).classesByModule +
            ("foreign" to ClassFileImporter().importClasses(java.sql.Connection::class.java)))
        val result = HttpBoundaryScope.prepare(scope, normalRegistration().with(HttpRole.DATA, java.sql.Connection::class.java))
        assertTrue(result.problems.any { it.code == ScopeProblemCode.INVALID_TARGET_INPUT && it.subject == "java.sql.Connection" })
    }

}

internal const val HTTP_FIXTURE_PACKAGE = "com.exchange.architecture.fixtures.httpboundary"
internal val normalTypes = arrayOf(NormalController::class.java, SubmitEntry::class.java,
    InputDto::class.java, OutputDto::class.java, HttpAmount::class.java, HttpCommand::class.java, HttpEvent::class.java)

internal fun httpScope(vararg types: Class<*>) = ScopeImportResult(
    mapOf("http-example" to allHttpFixtures.that(DescribedPredicate.describe("명시한 예제와 실제 중첩 타입") {
        belongsToRole(it, types.map { type -> type.name }.toSet())
    })),
)

internal fun httpRegistration(vararg groups: Pair<HttpRole, Set<String>>) = HttpRoleRegistration(
    groups.toMap(), setOf("$HTTP_FIXTURE_PACKAGE.web"), setOf(HTTP_FIXTURE_PACKAGE),
)

internal fun normalRegistration() = httpRegistration(
    HttpRole.CONTROLLER to setOf(NormalController::class.java.name),
    HttpRole.USE_CASE to setOf(SubmitEntry::class.java.name),
    HttpRole.CONVERSION to setOf(InputDto::class.java.name, OutputDto::class.java.name),
    HttpRole.DATA to setOf(HttpAmount::class.java.name, HttpCommand::class.java.name, HttpEvent::class.java.name),
)

internal fun HttpRoleRegistration.with(role: HttpRole, vararg types: Class<*>) =
    copy(types = this.types + (role to (this.types[role].orEmpty() + types.map { it.name })))

private val allHttpFixtures by lazy { ClassFileImporter().importPackages(HTTP_FIXTURE_PACKAGE) }

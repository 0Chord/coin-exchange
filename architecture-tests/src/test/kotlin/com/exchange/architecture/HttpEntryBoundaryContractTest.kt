package com.exchange.architecture

import com.exchange.architecture.fixtures.httpboundary.*
import com.exchange.architecture.fixtures.httpboundary.web.*
import com.exchange.architecture.rules.HttpEntryBoundary
import com.exchange.architecture.support.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HttpEntryBoundaryContractTest {
    @Test
    fun `HTTP-01과03 명령 구성과 유즈케이스 호출 및 응답 변환은 허용한다`() {
        val result = HttpEntryBoundary.inspect(httpScope(*normalTypes), normalRegistration())
        assertTrue(result.evaluated, result.problems.toString())
        assertEquals(emptyList(), result.violations)
        assertEquals(setOf(NormalController::class.java.name, InputDto::class.java.name, OutputDto::class.java.name), result.apiTypes)
        assertTrue(result.referenceCount > 0)
    }

    @Test
    fun `HTTP-04 저장 포트와 구현 Repository 엔티티를 직접 사용하면 금지한다`() {
        val result = checkExtra(
            HttpRole.CONTROLLER to arrayOf(StoreController::class.java, StorageTypesController::class.java),
            HttpRole.PORT to arrayOf(ExampleStore::class.java),
            HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java, ExampleRepository::class.java),
            HttpRole.ENTITY to arrayOf(StoredEntity::class.java),
        )
        assertEquals(setOf(
            StoreController::class.java.name to ExampleStore::class.java.name,
            StorageTypesController::class.java.name to ExampleStoreImpl::class.java.name,
            StorageTypesController::class.java.name to ExampleRepository::class.java.name,
            StorageTypesController::class.java.name to StoredEntity::class.java.name,
        ), result.violations.map { it.originType to it.targetType }.toSet())
        assertTrue(result.violations.all { it.ruleId == "ARCH-03" })
    }

    @Test
    fun `HTTP-05 이름이 UseCase인 내부 작업도 엔진과 동일하게 금지한다`() {
        val internal = arrayOf(FundingTaskUseCase::class.java, SettlementTask::class.java,
            MatchingCoordinator::class.java, ExampleEngine::class.java, ExampleExecutor::class.java, ExampleBook::class.java)
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(InternalWorkController::class.java), HttpRole.COLLABORATOR to internal)
        assertEquals(internal.map { InternalWorkController::class.java.name to it.name }.toSet(),
            result.violations.map { it.originType to it.targetType }.toSet())
    }

    @Test
    fun `HTTP-09 유즈케이스 내부의 정당한 저장 호출까지 HTTP 위반으로 보지 않는다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(WorkflowController::class.java),
            HttpRole.USE_CASE to arrayOf(WorkflowEntry::class.java), HttpRole.PORT to arrayOf(ExampleStore::class.java))
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `HTTP-02과13 이름에 의존하지 않고 합성 컨트롤러도 정상 등록한다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(NewController::class.java, ServiceNamedController::class.java),
            HttpRole.CONVERSION to arrayOf(ComposedController::class.java), HttpRole.USE_CASE to arrayOf(SubmissionService::class.java))
        assertEquals(emptyList(), result.violations)
    }

    @Test
    fun `HTTP-06 주입 인자 반환 배열 제네릭 상속에만 남은 저장 타입도 금지한다`() {
        val controllers = arrayOf(OnlyFieldController::class.java, ParameterController::class.java, ReturnController::class.java,
            ArrayController::class.java, GenericController::class.java, PortImplementingController::class.java)
        val result = checkExtra(HttpRole.CONTROLLER to controllers, HttpRole.PORT to arrayOf(ExampleStore::class.java))
        assertEquals(controllers.map { it.name to ExampleStore::class.java.name }.toSet(), result.pairs())
        assertTrue(result.violations.any { it.originType == GenericController::class.java.name && "generic" in it.description.lowercase() })
    }

    @Test
    fun `HTTP-06 어노테이션의 영속 타입 값도 금지 의존이다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(AnnotatedEntityController::class.java),
            HttpRole.CONVERSION to arrayOf(ApiType::class.java), HttpRole.ENTITY to arrayOf(StoredEntity::class.java))
        assertEquals(setOf(AnnotatedEntityController::class.java.name to StoredEntity::class.java.name), result.pairs())
    }

    @Test
    fun `HTTP-07 저장 포트와 구현체의 메서드 참조 근거를 남긴다`() {
        val scope = httpScope(*normalTypes, ReferenceController::class.java, ExampleStore::class.java, ExampleStoreImpl::class.java)
        val references = scope.classesByModule.values.flatMap { it.toList() }.flatMap { it.methodReferencesFromSelf }
        val relevant = references.filter { it.target.owner.name in setOf(ExampleStore::class.java.name, ExampleStoreImpl::class.java.name) }
        // Kotlin이 참조를 호출로 바꾼 경우도 실제 컴파일된 호출을 근거로 확인한다.
        val calls = scope.classesByModule.values.flatMap { it.toList() }.flatMap { it.methodCallsFromSelf }
            .filter { it.target.name == "save" && it.target.owner.name in setOf(ExampleStore::class.java.name, ExampleStoreImpl::class.java.name) }
        assertEquals(setOf(ExampleStore::class.java.name, ExampleStoreImpl::class.java.name),
            (relevant.map { it.target.owner.name } + calls.map { it.target.owner.name }).toSet())
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(ReferenceController::class.java),
            HttpRole.PORT to arrayOf(ExampleStore::class.java), HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        for (access in relevant + calls) {
            assertTrue(result.violations.any { it.originType == access.origin.owner.name && it.targetType == access.target.owner.name && it.lineNumber == access.lineNumber }, result.violations.toString())
        }
    }

    @Test
    fun `HTTP-08 매퍼와 DTO 속 저장 호출의 실제 출발 타입을 보고한다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(MapperController::class.java),
            HttpRole.CONVERSION to arrayOf(StoringMapper::class.java, StoringDto::class.java),
            HttpRole.PORT to arrayOf(ExampleStore::class.java), HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        assertEquals(setOf(StoringMapper::class.java.name to ExampleStore::class.java.name,
            StoringDto::class.java.name to ExampleStoreImpl::class.java.name), result.pairs())
    }

    @Test
    fun `HTTP-08 최상위 확장 함수의 파일 파사드도 검사한다`() {
        val facade = Class.forName("$HTTP_FIXTURE_PACKAGE.web.MappingFunctionsKt")
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(ExtensionController::class.java),
            HttpRole.CONVERSION to arrayOf(facade), HttpRole.PORT to arrayOf(ExampleStore::class.java),
            HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        assertEquals(setOf(facade.name to ExampleStoreImpl::class.java.name), result.pairs())
        assertTrue(result.violations.any { it.sourceFile == "MappingFunctions.kt" && it.lineNumber == 7 })
    }

    @Test
    fun `HTTP-08 컨트롤러 부모를 허용하되 그 본문의 저장 호출은 금지한다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(ChildController::class.java, StoringControllerParent::class.java),
            HttpRole.PORT to arrayOf(ExampleStore::class.java), HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        assertEquals(setOf(StoringControllerParent::class.java.name to ExampleStoreImpl::class.java.name), result.pairs())
    }

    @Test
    fun `HTTP-10 변환 코드의 유즈케이스 호출과 다른 컨트롤러 및 설정 사용을 금지한다`() {
        val result = checkExtra(HttpRole.CONVERSION to arrayOf(CallingEntryMapper::class.java),
            HttpRole.CONTROLLER to arrayOf(OtherControllerCaller::class.java), HttpRole.CONFIGURATION to arrayOf(ExampleConfiguration::class.java))
        assertEquals(setOf(CallingEntryMapper::class.java.name to SubmitEntry::class.java.name,
            OtherControllerCaller::class.java.name to NormalController::class.java.name,
            OtherControllerCaller::class.java.name to ExampleConfiguration::class.java.name), result.pairs())
    }

    @Test
    fun `HTTP-11 웹 지원은 허용하지만 직접 DB와 외부 요청 기술은 금지한다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(ExternalWorkController::class.java))
        val targets = setOf("java.sql.Connection", "jakarta.persistence.EntityManager", "java.net.http.HttpClient",
            "org.springframework.web.client.RestClient", "org.springframework.web.client.RestClient\$Builder",
            "org.springframework.jdbc.core.JdbcTemplate", "javax.sql.DataSource",
            "org.springframework.web.client.RestTemplate", "org.springframework.web.client.RestOperations")
        assertEquals(targets.map { ExternalWorkController::class.java.name to it }.toSet(), result.pairs())
    }

    @Test
    fun `HTTP-15 실제 중첩 타입과 람다 및 익명 클래스의 저장 호출을 놓치지 않는다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(NestedController::class.java),
            HttpRole.PORT to arrayOf(ExampleStore::class.java), HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        val origins = result.violations.map { it.originType }.toSet()
        assertTrue(NestedController.Worker::class.java.name in origins)
        assertTrue(origins.any { "anonymous" in it }, origins.toString())
        assertTrue(result.violations.any { "deferred" in it.description }, result.violations.toString())
        assertEquals(setOf(ExampleStoreImpl::class.java.name), result.violations.map { it.targetType }.toSet())
    }

    @Test
    fun `HTTP-15 입력 순서와 중복 입력에 따라 진단이 달라지지 않는다`() {
        val types = normalTypes.toList() + StoreController::class.java + ExampleStore::class.java
        val registration = normalRegistration().with(HttpRole.CONTROLLER, StoreController::class.java).with(HttpRole.PORT, ExampleStore::class.java)
        val a = HttpEntryBoundary.inspect(httpScope(*types.toTypedArray()), registration)
        val b = HttpEntryBoundary.inspect(httpScope(*(types.reversed() + types).toTypedArray()), registration.copy(types = registration.types.toList().reversed().toMap()))
        assertTrue(a.evaluated && b.evaluated)
        assertTrue(a.violations.isNotEmpty())
        assertEquals(a, b)
        assertEquals(a.violations.distinct(), a.violations)
    }

    @Test
    fun `HTTP-16 위반과 누락이 함께 있으면 전체 미평가이고 부분 위반을 내지 않는다`() {
        val scope = httpScope(*normalTypes, StoreController::class.java, ExampleStore::class.java)
            .copy(problems = listOf(ScopeProblem(ScopeProblemCode.READ_FAILURE, "missing.class")))
        val result = HttpEntryBoundary.inspect(scope, normalRegistration()
            .with(HttpRole.CONTROLLER, StoreController::class.java).with(HttpRole.PORT, ExampleStore::class.java))
        assertFalse(result.evaluated)
        assertTrue(result.problems.any { it.code == ScopeProblemCode.READ_FAILURE && it.subject == "missing.class" })
        assertEquals(emptyList(), result.violations)
        assertEquals(0, result.referenceCount)
    }

    @Test
    fun `HTTP-16 반환 타입의 행을 추측하지 않고 규칙과 역할을 표시한다`() {
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(ReturnController::class.java), HttpRole.PORT to arrayOf(ExampleStore::class.java))
        val signature = assertNotNull(result.violations.singleOrNull { "return type" in it.description })
        assertEquals(null, signature.lineNumber)
        assertEquals("HttpFixtures.kt", signature.sourceFile)
        assertEquals("ARCH-03", signature.ruleId)
        assertTrue("CONTROLLER → PORT" in signature.description)
        assertEquals("engineering/architecture-check-spec.md", signature.specification)
    }


    @Test
    fun `HTTP-07 JVM 메서드 및 생성자 참조의 위치와 근거를 보고한다`() {
        // Java 테스트 소스는 Kotlin 뒤에 컴파일되므로 직접 타입 의존 없이 실제 결과를 가져온다.
        val controller = Class.forName("$HTTP_FIXTURE_PACKAGE.web.JavaReferenceController")
        val scope = httpScope(*normalTypes, controller, ExampleStore::class.java, ExampleStoreImpl::class.java)
        val origin = scope.classesByModule.getValue("http-example").get(controller)
        assertEquals(setOf(12, 16), origin.methodReferencesFromSelf.map { it.lineNumber }.toSet())
        assertEquals(setOf(20), origin.constructorReferencesFromSelf.map { it.lineNumber }.toSet())
        val result = checkExtra(HttpRole.CONTROLLER to arrayOf(controller), HttpRole.PORT to arrayOf(ExampleStore::class.java),
            HttpRole.INFRASTRUCTURE to arrayOf(ExampleStoreImpl::class.java))
        for (reference in origin.codeUnitReferencesFromSelf) {
            assertTrue(result.violations.any { it.originType == controller.name && it.targetType == reference.target.owner.name &&
                it.lineNumber == reference.lineNumber && "references" in it.description }, result.violations.toString())
        }
    }

}

internal fun checkExtra(vararg groups: Pair<HttpRole, Array<Class<*>>>) = HttpEntryBoundary.inspect(
    httpScope(*(normalTypes.toList() + groups.flatMap { it.second.toList() }).distinct().toTypedArray()),
    groups.fold(normalRegistration()) { registration, (role, types) -> registration.with(role, *types) },
).also { assertTrue(it.evaluated, it.problems.toString()) }

private fun com.exchange.architecture.rules.HttpBoundaryResult.pairs() = violations.map { it.originType to it.targetType }.toSet()

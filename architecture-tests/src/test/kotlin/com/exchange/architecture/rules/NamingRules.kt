package com.exchange.architecture.rules

import com.exchange.architecture.support.*
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.lang.*
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/** 규칙의 선택과 판단은 ArchUnit에 맡긴다. 서로 다른 단서가 있으면 두 규칙 모두 실행한다. */
object NamingRules {
    private val controllerAnnotations = setOf("org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController")
    private val adviceAnnotations = setOf("org.springframework.web.bind.annotation.ControllerAdvice", "org.springframework.web.bind.annotation.RestControllerAdvice")
    private const val configuration = "org.springframework.context.annotation.Configuration"
    private const val boot = "org.springframework.boot.autoconfigure.SpringBootApplication"
    private const val entity = "jakarta.persistence.Entity"
    private const val repository = "org.springframework.data.repository.Repository"

    // 컴파일러의 운반 타입만 제외한다. Kt·달러 문자는 직접 선언한 이름에도 쓰일 수 있다.
    fun namedDeclaration(type: JavaClass): Boolean = !type.isAnonymousClass && !type.isLocalClass &&
        JavaModifier.SYNTHETIC !in type.modifiers &&
        (type.tryGetAnnotationOfType("kotlin.Metadata").orElse(null)?.get("k")?.orElse(1) !in setOf(2, 4, 5))
    private fun tagged(type: JavaClass, tags: Set<String>) = tags.any { type.isAnnotatedWith(it) }
    private fun target(description: String, matches: (JavaClass) -> Boolean) = object : DescribedPredicate<JavaClass>(description) {
        override fun test(input: JavaClass) = namedDeclaration(input) && matches(input)
    }
    val controllerTargets = target("Controller 이름 또는 표준 어노테이션") { it.simpleName.endsWith("Controller") || tagged(it, controllerAnnotations) }
    private fun suffixTargets(suffix: String) = target("$suffix 접미사") { it.simpleName.endsWith(suffix) }
    private val storePorts = target("Store 인터페이스") { it.isInterface && it.simpleName.endsWith("Store") }
    private fun storeImplementationTargets(input: NamingPlacementInput) = target("Store 이름 또는 내부 포트 구현") {
        !it.isInterface && (it.simpleName.endsWith("Store") || it.allRawInterfaces.any { p -> p.simpleName.endsWith("Store") && p.name in input.classes })
    }
    private val publisherPorts = target("Publisher 인터페이스") { it.isInterface && it.simpleName.endsWith("Publisher") }
    private fun publisherImplementationTargets(input: NamingPlacementInput) = target("Publisher 이름 또는 내부 포트 구현") {
        !it.isInterface && (it.simpleName.endsWith("Publisher") || it.allRawInterfaces.any { p -> p.simpleName.endsWith("Publisher") && p.name in input.classes })
    }
    private val repositoryTargets = target("Repository 이름 또는 Spring Data 상속") { it.simpleName.endsWith("Repository") || it.isAssignableTo(repository) }
    private val configTargets = target("Config 이름 또는 표준 Configuration") { it.simpleName.endsWith("Config") || it.isAnnotatedWith(configuration) && !it.isAnnotatedWith(boot) }
    private val bootTargets = target("SpringBootApplication") { it.isAnnotatedWith(boot) }
    private val adviceTargets = target("표준 ControllerAdvice") { tagged(it, adviceAnnotations) }
    private val entityTargets = target("표준 Entity") { it.isAnnotatedWith(entity) }
    private val httpDataTargets = target("Request 또는 Response 접미사") { !it.simpleName.endsWith("ErrorResponse") && listOf("Request", "Response").any(it.simpleName::endsWith) }
    private val errorTargets = suffixTargets("ErrorResponse")

    private class PolicyInputError(val subject: String, message: String) : IllegalArgumentException(message)
    private fun area(policy: LayoutPolicy, id: String): AllowedFolder = policy.folders.singleOrNull { it.id == id }
        ?: throw PolicyInputError(id, "규칙에 필요한 허용 위치 누락")
    private fun violation(type: JavaClass, input: NamingPlacementInput, id: String, item: String, actual: String, expected: String) =
        PlacementViolation(type.name, item, actual, expected, input.modules.getValue(type.name), ruleId = "ARCH-05/$id")
    private fun name(type: JavaClass, input: NamingPlacementInput, id: String, suffix: String, prefixes: Set<String> = emptySet()): List<PlacementViolation> {
        val n = type.simpleName
        val valid = if (prefixes.isEmpty()) n.endsWith(suffix) && n.length > suffix.length
            else prefixes.any { n.startsWith(it) && n.endsWith(suffix) && n.length > it.length + suffix.length }
        return if (valid) emptyList() else listOf(violation(type, input, id, "name", n, if (prefixes.isEmpty()) "대상+$suffix" else "$prefixes+대상+$suffix"))
    }
    private fun shape(type: JavaClass, input: NamingPlacementInput, id: String, valid: Boolean, expected: String) =
        if (valid) emptyList() else listOf(violation(type, input, id, "declaration", type.description, expected))
    private fun position(type: JavaClass, input: NamingPlacementInput, id: String, locations: List<AllowedFolder>): List<PlacementViolation> = buildList {
        val module = input.modules.getValue(type.name)
        if (locations.none { it.module == module }) add(violation(type, input, id, "module", module, locations.map { it.module }.distinct().sorted().joinToString()))
        val local = locations.filter { it.module == module }.ifEmpty { locations }
        if (local.none { it.packageName == type.packageName }) add(violation(type, input, id, "package", type.packageName, local.map { it.packageName }.distinct().sorted().joinToString()))
    }
    private fun condition(description: String, inspect: (JavaClass) -> List<PlacementViolation>) = object : ArchCondition<JavaClass>(description) {
        override fun check(item: JavaClass, events: ConditionEvents) {
            inspect(item).forEach { v -> events.add(SimpleConditionEvent.violated(v, "${v.subject}: ${v.item}=${v.actual}; 필요한 조건: ${v.expected}")) }
        }
    }
    fun controllers(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule =
        classes()
            .that(controllerTargets)
            .should(condition("Controller 이름·어노테이션·HTTP 위치를 지킨다") { type ->
                name(type, input, "controller", "Controller") +
                    shape(type, input, "controller", tagged(type, controllerAnnotations), "표준 Controller 또는 RestController") +
                    position(type, input, "controller", listOf(area(policy, "order-http")))
            })
            .allowEmptyShould(true)

    private fun application(input: NamingPlacementInput, policy: LayoutPolicy, suffix: String, vararg locations: String): ArchRule = classes().that(suffixTargets(suffix)).should(condition("$suffix 선언·이름·애플리케이션 위치를 지킨다") { t ->
        name(t, input, suffix, suffix) + shape(t, input, suffix, !t.isInterface && !t.isEnum && !t.isAnnotation && JavaModifier.ABSTRACT !in t.modifiers, "구체 클래스 또는 object") +
            position(t, input, suffix, locations.map { area(policy, it) })
    }).allowEmptyShould(true)
    fun useCases(input: NamingPlacementInput, policy: LayoutPolicy) = application(input, policy, "UseCase", "order-application", "ledger-application")
    fun services(input: NamingPlacementInput, policy: LayoutPolicy) = application(input, policy, "Service", "order-application")
    fun coordinators(input: NamingPlacementInput, policy: LayoutPolicy) = application(input, policy, "Coordinator", "matching-application")
    private fun coreRule(input: NamingPlacementInput, policy: LayoutPolicy, suffix: String): ArchRule = classes().that(suffixTargets(suffix)).should(condition("$suffix 이름·선언·코어 위치를 지킨다") { t ->
        name(t, input, suffix, suffix) + shape(t, input, suffix, !t.isEnum && !t.isAnnotation, "클래스 또는 일반 인터페이스") +
            position(t, input, suffix, listOf(area(policy, "domain-fee"), area(policy, "domain-order")))
    }).allowEmptyShould(true)
    fun calculators(input: NamingPlacementInput, policy: LayoutPolicy) = coreRule(input, policy, "Calculator")
    fun resolvers(input: NamingPlacementInput, policy: LayoutPolicy) = coreRule(input, policy, "Resolver")
    fun stores(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(storePorts).should(condition("Store 포트 이름·선언·위치를 지킨다") { t ->
        name(t, input, "store-port", "Store") + shape(t, input, "store-port", !t.isAnnotation, "일반 인터페이스") +
            position(t, input, "store-port", listOf(area(policy, "domain-order"), area(policy, "domain-ledger"), area(policy, "matching-ports")))
    }).allowEmptyShould(true)

    // 실제 필요한 내부 상위 정의만 확인한다. 전체 타입의 역할·소유자를 재탐색하지 않는다.
    private fun parents(type: JavaClass, input: NamingPlacementInput): Set<JavaClass> {
        val parents = (type.classHierarchy.drop(1) + type.allRawInterfaces).toSet()
        parents.forEach { p ->
            if (!p.isFullyImported) throw PolicyInputError(type.name, "상위 정의를 읽지 못했습니다: ${p.name}")
            if (p.name !in input.classes && p.packageName.startsWith("com.exchange."))
                throw PolicyInputError(type.name, "내부 상위 정의 수집 누락: ${p.name}")
        }
        return parents
    }
    private fun actualPorts(type: JavaClass, input: NamingPlacementInput, suffix: String) = parents(type, input).filter { it.isInterface && it.simpleName.endsWith(suffix) && it.name in input.classes }
    fun storeImplementations(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(storeImplementationTargets(input)).should(condition("실제 Store 포트와 저장 영역을 지킨다") { t ->
        val ports = actualPorts(t, input, "Store")
        val failures = (name(t, input, "store-implementation", "Store", setOf("Postgres", "Jpa")) +
            shape(t, input, "store-implementation", ports.isNotEmpty() && !t.isEnum && !t.isAnnotation, "내부 Store 포트 구현" )).toMutableList()
        val mappedAreas = mutableSetOf<Pair<String, String>>()
        for (port in ports) {
            val positions = policy.folders.filter { it.module == input.modules.getValue(port.name) && it.packageName == port.packageName && it.id in policy.naming.storeTargets }
            if (positions.isEmpty()) {
                val allowedPort = listOf("domain-order", "domain-ledger", "matching-ports").map { area(policy, it) }
                if (allowedPort.any { it.module == input.modules.getValue(port.name) && it.packageName == port.packageName })
                    throw PolicyInputError(port.name, "필요한 Store 영역 매핑 누락")
                failures += violation(t, input, "store-implementation", "storeArea", port.name, "허용된 Store 포트 영역")
            }
            positions.forEach { mappedAreas += area(policy, policy.naming.storeTargets.getValue(it.id)).let { f -> f.module to f.packageName } }
        }
        if (mappedAreas.size > 1) failures += violation(t, input, "store-implementation", "storeArea", mappedAreas.sortedBy { it.toString() }.toString(), "한 저장 영역")
        if (mappedAreas.size == 1) failures += position(t, input, "store-implementation", policy.folders.filter { (it.module to it.packageName) in mappedAreas })
        failures
    }).allowEmptyShould(true)
    fun publishers(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(publisherPorts).should(condition("Publisher 포트 이름·위치를 지킨다") { t ->
        name(t, input, "publisher-port", "Publisher") + shape(t, input, "publisher-port", !t.isAnnotation, "일반 인터페이스") + position(t, input, "publisher-port", listOf(area(policy, "matching-ports")))
    }).allowEmptyShould(true)
    fun publisherImplementations(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(publisherImplementationTargets(input)).should(condition("실제 Publisher 구현과 저장·미저장 위치를 지킨다") { t ->
        val prefix = policy.naming.publisherTargets.keys.singleOrNull(t.simpleName::startsWith)
        name(t, input, "publisher-implementation", "Publisher", setOf("Persistent", "NoOp")) +
            shape(t, input, "publisher-implementation", actualPorts(t, input, "Publisher").isNotEmpty() && !t.isEnum && !t.isAnnotation, "내부 Publisher 포트 구현") +
            if (prefix == null) emptyList() else position(t, input, "publisher-implementation", listOf(area(policy, policy.naming.publisherTargets.getValue(prefix))))
    }).allowEmptyShould(true)
    fun repositories(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(repositoryTargets).should(condition("Repository 이름·Spring Data 인터페이스·영속 위치를 지킨다") { t ->
        parents(t, input)
        name(t, input, "repository", "Repository") + shape(t, input, "repository", t.isInterface && !t.isAnnotation && t.isAssignableTo(repository), "Spring Data Repository를 상속한 일반 인터페이스") +
            position(t, input, "repository", listOf(area(policy, "matching-persistence")))
    }).allowEmptyShould(true)
    fun configurations(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(configTargets).should(condition("Config 이름·어노테이션·조립 위치를 지킨다") { t ->
        name(t, input, "config", "Config") + shape(t, input, "config", t.isAnnotatedWith(configuration), "표준 Configuration") + position(t, input, "config", listOf(area(policy, "config")))
    }).allowEmptyShould(true)
    fun bootstrap(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(bootTargets).should(condition("앱 시작점 이름·위치를 지킨다") { t ->
        name(t, input, "bootstrap", "Application") + position(t, input, "bootstrap", listOf(area(policy, "bootstrap")))
    }).allowEmptyShould(true)
    fun advice(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(adviceTargets).should(condition("HTTP 오류 처리 이름·위치를 지킨다") { t ->
        name(t, input, "advice", "ExceptionHandler") + position(t, input, "advice", listOf(area(policy, "http-errors")))
    }).allowEmptyShould(true)
    fun entities(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(entityTargets).should(condition("Entity 이름·영속 위치를 지킨다") { t ->
        name(t, input, "entity", "Entity") + position(t, input, "entity", listOf("order-persistence", "ledger-persistence", "matching-persistence").map { area(policy, it) })
    }).allowEmptyShould(true)
    fun httpData(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(httpDataTargets).should(condition("Request·Response 이름·HTTP 위치를 지킨다") { t ->
        name(t, input, "http-data", if (t.simpleName.endsWith("Request")) "Request" else "Response") + position(t, input, "http-data", listOf(area(policy, "order-http")))
    }).allowEmptyShould(true)
    fun errorResponses(input: NamingPlacementInput, policy: LayoutPolicy): ArchRule = classes().that(errorTargets).should(condition("ErrorResponse 이름·공통 오류 위치를 지킨다") { t ->
        name(t, input, "error-response", "ErrorResponse") + position(t, input, "error-response", listOf(area(policy, "http-errors")))
    }).allowEmptyShould(true)

    fun inspectTypes(scope: ScopeImportResult, policy: LayoutPolicy, registeredModules: Set<String>): PlacementResult {
        val input = NamingPlacementScope.prepare(scope, policy, registeredModules)
        if (input.problems.isNotEmpty()) return PlacementResult(problems = input.problems)
        val reports = mutableListOf<NamingRuleResult>()
        val violations = mutableListOf<PlacementViolation>()
        try {
            // 간접 상속 단서를 고르기 전에 필요한 상위 정의의 읽기 성공을 확인한다.
            input.classes.values.filter(::namedDeclaration).forEach { parents(it, input) }
            fun evaluate(id: String, selector: DescribedPredicate<JavaClass>, rule: ArchRule) {
                val targets = input.classes.values.filter(selector::test).map { it.name }.toSortedSet()
                val failures = mutableListOf<String>()
                for (classes in scope.classesByModule.values) {
                    val result = rule.evaluate(classes)
                    failures += result.failureReport.details
                    result.handleViolations(ViolationHandler<PlacementViolation> { objects, _ -> violations += objects })
                }
                reports += NamingRuleResult(id, targets, failures.distinct().sorted())
            }
            evaluate("controller", controllerTargets, controllers(input, policy))
            evaluate("UseCase", suffixTargets("UseCase"), useCases(input, policy))
            evaluate("Service", suffixTargets("Service"), services(input, policy))
            evaluate("Coordinator", suffixTargets("Coordinator"), coordinators(input, policy))
            evaluate("Calculator", suffixTargets("Calculator"), calculators(input, policy))
            evaluate("Resolver", suffixTargets("Resolver"), resolvers(input, policy))
            evaluate("store-port", storePorts, stores(input, policy))
            evaluate("store-implementation", storeImplementationTargets(input), storeImplementations(input, policy))
            evaluate("publisher-port", publisherPorts, publishers(input, policy))
            evaluate("publisher-implementation", publisherImplementationTargets(input), publisherImplementations(input, policy))
            evaluate("repository", repositoryTargets, repositories(input, policy))
            evaluate("config", configTargets, configurations(input, policy))
            evaluate("bootstrap", bootTargets, bootstrap(input, policy))
            evaluate("advice", adviceTargets, advice(input, policy))
            evaluate("entity", entityTargets, entities(input, policy))
            evaluate("http-data", httpDataTargets, httpData(input, policy))
            evaluate("error-response", errorTargets, errorResponses(input, policy))
        } catch (error: PolicyInputError) {
            return PlacementResult(problems = listOf(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, error.subject, error.message)))
        }
        return PlacementResult(violations = violations.distinct().sortedWith(compareBy({ it.subject }, { it.ruleId }, { it.item })),
            evaluatedTypes = reports.flatMap { it.targets }.toSortedSet(), rules = reports)
    }
}

package com.exchange.architecture.support

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier

/**
 * 전체 운영 수집 결과를 기존 HTTP·application 검사 입력으로 연결한다.
 * 클래스별 등록표 대신 패키지·어노테이션·현재 포트 계약을 사용한다. 업무 이름의 의미는 판정하지 않는다.
 */
object ProductionBoundaryInputs {
    private const val BASE = "com.exchange.core"
    private const val ORDER_APPLICATION = "$BASE.api.order.application"
    private const val LEDGER_APPLICATION = "$BASE.api.ledger.application"
    private val useCasePackages = setOf(ORDER_APPLICATION, LEDGER_APPLICATION)
    private val applicationPackages = useCasePackages + "$BASE.api.matching.application"
    private val apiPackages = setOf("$BASE.api.order.api", "$BASE.api.common")
    private const val CONFIGURATION_PACKAGE = "$BASE.api.config"
    private const val EXECUTOR_CONTRACT = "$BASE.matching.MarketCommandProcessor"
    private val httpContracts = setOf("$BASE.matching.MatchingCommand", "$BASE.matching.MatchingEvent")
    private val httpEnums = setOf("$BASE.order.Side", "$BASE.order.OrderType", "$BASE.order.TimeInForce")

    // 기존 공개 진입점의 삭제를 잡는 최소 기대이며, 전체 검사 대상의 등록표가 아니다.
    val requiredTypes =
        setOf(
            "$BASE.api.order.api.OrderController",
            "${ORDER_APPLICATION}.SubmitOrderUseCase",
            "${ORDER_APPLICATION}.CancelOrderUseCase",
            "${LEDGER_APPLICATION}.PrepareDevelopmentBalanceUseCase",
            "$BASE.api.matching.application.MatchingCoordinator",
        )

    fun expectations() =
        ProductionScope.expectations().let {
            it.copy(
                requiredTypesByModule =
                    it.requiredTypesByModule +
                        ("app-api" to (it.requiredTypesByModule.getValue("app-api") + requiredTypes)),
            )
        }

    fun http(scope: ScopeImportResult) = HttpRoleRegistration(select(scope), apiPackages, setOf(BASE))

    fun application(scope: ScopeImportResult): ApplicationRoleRegistration {
        val modules = scope.classesByModule.flatMap { (module, classes) -> classes.map { it.name to module } }.toMap()
        val groups = mutableMapOf<ApplicationRole, MutableSet<String>>()
        select(scope).forEach { (role, names) ->
            names.forEach { name ->
                val applicationRole =
                    when (role) {
                        HttpRole.CONTROLLER, HttpRole.CONVERSION -> {
                            ApplicationRole.HTTP
                        }

                        HttpRole.USE_CASE -> {
                            ApplicationRole.APPLICATION
                        }

                        HttpRole.DATA -> {
                            ApplicationRole.DOMAIN
                        }

                        HttpRole.PORT -> {
                            ApplicationRole.PORT
                        }

                        HttpRole.ENTITY -> {
                            ApplicationRole.ENTITY
                        }

                        HttpRole.INFRASTRUCTURE -> {
                            ApplicationRole.INFRASTRUCTURE
                        }

                        HttpRole.CONFIGURATION -> {
                            ApplicationRole.CONFIGURATION
                        }

                        HttpRole.COLLABORATOR -> {
                            when {
                                applicationPackages.any { inPackage(name, it) } -> ApplicationRole.APPLICATION
                                name == EXECUTOR_CONTRACT -> ApplicationRole.EXECUTOR
                                modules[name] in ProductionScope.roles.domainModules -> ApplicationRole.DOMAIN
                                else -> ApplicationRole.INFRASTRUCTURE
                            }
                        }
                    }
                groups.getOrPut(applicationRole) { linkedSetOf() }.add(name)
            }
        }
        return ApplicationRoleRegistration(groups, applicationPackages, setOf(CONFIGURATION_PACKAGE), setOf(BASE))
    }

    private fun select(scope: ScopeImportResult): Map<HttpRole, Set<String>> {
        val groups = mutableMapOf<HttpRole, MutableSet<String>>()
        scope.classesByModule.forEach { (module, classes) ->
            classes.forEach { type ->
                // 중첩·합성 타입은 기존 검사기가 실제 포함 관계로 따라간다. 독립 역할 단서가 있으면 함께 남긴다.
                val explicit = explicitRoles(type)
                val business = applicationPackages.any { inPackage(type.name, it) }
                val selected = explicit.toMutableSet()
                if (!type.enclosingClass.isPresent && (selected.isEmpty() || (business && !type.isInterface))) {
                    selected +=
                        when {
                            business -> {
                                if (type.packageName in useCasePackages &&
                                    concreteUseCase(type)
                                ) {
                                    HttpRole.USE_CASE
                                } else {
                                    HttpRole.COLLABORATOR
                                }
                            }

                            apiPackages.any { inPackage(type.name, it) } -> {
                                HttpRole.CONVERSION
                            }

                            inPackage(type.name, CONFIGURATION_PACKAGE) -> {
                                HttpRole.CONFIGURATION
                            }

                            module in ProductionScope.roles.domainModules -> {
                                if (httpData(type, module)) HttpRole.DATA else HttpRole.COLLABORATOR
                            }

                            else -> {
                                HttpRole.INFRASTRUCTURE
                            }
                        }
                }
                selected.forEach { groups.getOrPut(it) { linkedSetOf() }.add(type.name) }
            }
        }
        // 자동 선택과 별도로 핵심 타입의 존재를 확인하므로 삭제를 빈 검사 통과로 바꾸지 않는다.
        groups.getOrPut(HttpRole.CONTROLLER) { linkedSetOf() }.add("$BASE.api.order.api.OrderController")
        groups.getOrPut(HttpRole.USE_CASE) { linkedSetOf() }.addAll(requiredTypes.filter { it.endsWith("UseCase") })
        groups.getOrPut(HttpRole.COLLABORATOR) { linkedSetOf() }.add("$BASE.api.matching.application.MatchingCoordinator")
        return groups
    }

    private fun explicitRoles(type: JavaClass): Set<HttpRole> =
        buildSet {
            if (type.name in ProductionScope.roles.externalPorts) add(HttpRole.PORT)
            if (!type.isAnnotation && tagged(type, "org.springframework.stereotype.Controller")) add(HttpRole.CONTROLLER)
            if (!type.isAnnotation && tagged(type, "org.springframework.context.annotation.Configuration")) add(HttpRole.CONFIGURATION)
            if (type.isAnnotatedWith("jakarta.persistence.Entity")) add(HttpRole.ENTITY)
            val implementation = !type.isInterface && type.allRawInterfaces.any { it.name in ProductionScope.roles.externalPorts }
            val executorImplementation = type.name in (ProductionScope.roles.executors - EXECUTOR_CONTRACT)
            val technologyFolder = listOf(".persistence", ".publish", ".infrastructure").any { type.packageName.contains(it) }
            if (implementation || executorImplementation || (
                    technologyFolder && !type.enclosingClass.isPresent &&
                        type.name !in ProductionScope.roles.externalPorts && HttpRole.ENTITY !in this
                )
            ) {
                add(HttpRole.INFRASTRUCTURE)
            }
        }

    private fun httpData(
        type: JavaClass,
        module: String,
    ): Boolean =
        module == "domain-common" ||
            type.name in httpContracts || type.allRawInterfaces.any { it.name in httpContracts } ||
            type.name in httpEnums || type.isAssignableTo("java.lang.Throwable")

    private fun concreteUseCase(type: JavaClass) =
        type.simpleName.endsWith("UseCase") &&
            !type.isInterface && !type.isEnum && !type.isAnnotation && JavaModifier.ABSTRACT !in type.modifiers

    private fun tagged(
        type: JavaClass,
        annotation: String,
    ) = type.isAnnotatedWith(annotation) || type.isMetaAnnotatedWith(annotation)

    private fun inPackage(
        name: String,
        pkg: String,
    ) = name.startsWith("$pkg.")
}

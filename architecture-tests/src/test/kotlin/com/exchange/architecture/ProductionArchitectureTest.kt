package com.exchange.architecture

import com.exchange.architecture.rules.ProductionDependencyIsolation
import com.exchange.architecture.rules.BeanAssemblyRules
import com.exchange.architecture.rules.HttpEntryBoundary
import com.exchange.architecture.rules.ApplicationImplementationIndependence
import com.exchange.architecture.rules.NamingRules
import com.exchange.architecture.policy.ProjectLayoutPolicy
import com.exchange.architecture.support.NamingPlacementScope
import com.exchange.architecture.support.ProductionBoundaryInputs
import com.exchange.architecture.support.MainSourceSnapshot
import com.exchange.architecture.support.SourcePlacement
import com.exchange.architecture.support.IsolationInputs
import com.exchange.architecture.rules.DomainTechnologyIndependence
import com.exchange.architecture.rules.ModuleDependencyDirection
import com.exchange.architecture.rules.PortContractIndependence
import com.exchange.architecture.support.ProjectDependencies
import com.exchange.architecture.support.ModuleRegistration
import com.exchange.architecture.support.ProductionScope
import com.exchange.architecture.support.ProductionScopeImporter
import com.exchange.architecture.support.RoleClassifier
import kotlin.test.Test
import kotlin.test.assertTrue

/** 실제 운영 출력에 규칙을 적용한다. 각 검사는 준비 조건을 확인하며 실행 순서에 의존하지 않는다. */
class ProductionArchitectureTest {
    @Test
    fun `P07 실제 application 진입점과 협력자에 ARCH-04를 적용한다`() {
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionBoundaryInputs.expectations(), ProductionScope.nonProductionTargets())
        val result = ApplicationImplementationIndependence.inspect(scope, ProductionBoundaryInputs.application(scope))
        assertTrue(result.evaluated, "ARCH-04 미평가 · 준비 오류: ${result.problems}")
        println("P07 평가: ARCH-04 · 실제 application ${result.applicationTypes.size}개: ${result.applicationTypes}")
        println("config 조립 ${result.configurationTypes.size}개: ${result.configurationTypes} · 직접 참조 ${result.referenceCount}개")
        assertTrue(result.violations.isEmpty(), "ARCH-04 위반:\n${result.violations.joinToString("\n")}")
        println("ARCH-04 통과. 업무는 계약을 사용하고 구체 구현 생성은 config에서 허용합니다. 실제 Spring 조립·트랜잭션은 app-api 테스트로 확인합니다.")
    }

    @Test
    fun `P01 전체 운영 출력과 역할을 검증한 뒤 동일 ARCH-01 규칙을 적용한다`() {
        val registration = ModuleRegistration.inspect(ProductionScope.inventory())
        assertTrue(registration.isEmpty(), "Module registration failed: $registration")

        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionScope.expectations(), ProductionScope.nonProductionTargets())
        assertTrue(scope.problems.isEmpty(), "Scope collection failed; ARCH-01 not evaluated:\n${scope.problems.joinToString("\n")}")
        val classified = RoleClassifier.classify(scope.classesByModule, ProductionScope.roles)
        assertTrue(classified.problems.isEmpty(), "Role classification failed; ARCH-01 not evaluated:\n${classified.problems.joinToString("\n")}")

        val violations = scope.classesByModule.toSortedMap().flatMap { (module, classes) ->
            DomainTechnologyIndependence.evaluate(classes, classified.roles).map { violation ->
                with(violation) {
                    "$ruleId | $module | $originType | $description | $targetType | " +
                        "${sourceFile ?: "source unavailable"}:${lineNumber ?: "line unavailable"} | $specification"
                }
            }
        }
        println("P01 평가: ARCH-01. ARCH-02는 독립된 P02에서 평가합니다.")
        println("Inventory: ${scope.classesByModule.mapValues { it.value.size }}")
        println("Roles: pure=${classified.roles.pureDomain.size}, ports=${classified.roles.externalPorts.size}, executors=${classified.roles.executors.size}")
        assertTrue(violations.isEmpty(), "Production violations:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `P02 전체 운영 코드와 Gradle 직접 선언에 ARCH-02를 적용한다`() {
        val inventory = ProductionScope.inventory()
        val index = ProductionScope.nonProductionTargets()
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionScope.expectations(), index)
        val snapshot = ProjectDependencies.read(System.getProperty("architecture.projectDependencies"))
        val result = ModuleDependencyDirection.inspect(scope, snapshot, inventory, nonProduction = index)
        assertTrue(result.evaluated, "검사 준비 실패 · ARCH-02 미평가:\n${result.problems.joinToString("\n")}")

        val declarations = snapshot.configurations.flatMap { c -> c.dependencies.map { Triple(c.projectPath, it.targetPath, it.declaredIn) } }.toSet()
        val excluded = declarations.filter { it.second in inventory.nonProductionModules }.sortedBy { it.toString() }
        val typeNames = scope.classesByModule.values.flatMap { it.map { type -> type.name } }.toSet()
        val internalReferences = scope.classesByModule.values.sumOf { classes -> classes.sumOf { type ->
            type.directDependenciesFromSelf.count { it.targetClass.baseComponentType.name in typeNames }
        } }
        println("P02 평가: ARCH-02 · 코드 직접 참조 + Gradle 직접 선언")
        println("운영 모듈별 클래스: ${scope.classesByModule.mapValues { it.value.size }}")
        println("내부 직접 타입 참조: $internalReferences / main 구성: ${snapshot.configurations.size} / 직접 프로젝트 선언: ${declarations.size}")
        println("ARCH-08에 남기는 비운영 목적지: $excluded")
        println("ARCH-06은 P03, Bean 조립은 P05, HTTP·application은 P06~P07, 주문 이름·전체 파일 폴더는 P08에서 평가합니다. ARCH-07·계산·DB·실행 순서 검증은 포함하지 않습니다.")
        assertTrue(result.violations.isEmpty(), "ARCH-02 위반:\n${result.violations.joinToString("\n") { it.report() }}")
        println("ARCH-02 통과 · 준비 오류 0, 위반 0")
    }

    @Test
    fun `P03 실제 저장 발행 포트의 공개 계약에 ARCH-06을 적용한다`() {
        val registration = ModuleRegistration.inspect(ProductionScope.inventory())
        assertTrue(registration.isEmpty(), "검사 준비 실패 · ARCH-06 미평가: $registration")
        val expectations = ProductionScope.expectations()
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), expectations, ProductionScope.nonProductionTargets())
        assertTrue(scope.problems.isEmpty(), "검사 준비 실패 · ARCH-06 미평가: ${scope.problems}")
        val classified = RoleClassifier.classify(scope.classesByModule, ProductionScope.roles)
        assertTrue(classified.problems.isEmpty(), "역할 준비 실패 · ARCH-06 미평가: ${classified.problems}")

        val result = PortContractIndependence.inspect(scope, ProductionScope.roles.externalPorts,
            ProductionScope.persistenceTypes, expectations.projectPackagePrefixes)
        assertTrue(result.evaluated, "검사 준비 실패 · ARCH-06 미평가: ${result.problems}")
        println("P03 평가: ARCH-06 · 공개 포트 계약")
        println("검사한 포트 ${result.ports.size}개: ${result.ports.joinToString()}")
        println("공개 계약 ${result.contractCount}개 · 등록 영속 모델: ${ProductionScope.persistenceTypes}")
        assertTrue(result.violations.isEmpty(), "ARCH-06 위반:\n${result.violations.joinToString("\n") { it.report() }}")
        println("ARCH-06 통과 · 준비 오류 0, 위반 0. 포트 메서드·DB는 실행하지 않았습니다.")
        println("한계: 미등록 포트의 의미, 임의 DTO 내부, 런타임 값·동작. Bean 조립·HTTP·application·이번 이름·파일 폴더는 P05~P08에서 평가합니다. ARCH-07은 후속 범위입니다.")
    }

    @Test
    fun `P04 전체 운영 코드와 Gradle 선언에 ARCH-08을 독립 적용한다`() {
        val inventory = ProductionScope.inventory()
        val index = ProductionScope.nonProductionTargets()
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionScope.expectations(), index)
        val snapshot = IsolationInputs.dependencies(System.getProperty("architecture.isolationDependencies"))
        val result = ProductionDependencyIsolation.inspect(scope, index, snapshot, inventory)
        assertTrue(result.evaluated, "검사 준비 실패 · ARCH-08 미평가:\n${result.problems.joinToString("\n")}")
        println("P04 평가: ARCH-08 · 운영 코드 직접 참조 + main Gradle 직접 선언")
        println("운영 모듈별 클래스: ${scope.classesByModule.mapValues { it.value.size }}")
        println("비운영 출력: ${index.outputs.size}개 · 상태 ${index.outputs.groupingBy { it.state }.eachCount()} · 확인한 비운영 타입 ${index.knownTypes.size}개")
        println("출력 소속: ${index.outputs.groupBy { it.key }.mapValues { (_, values) -> values.map { it.state }.sorted() }}")
        println("main 구성 ${snapshot.configurations.size}개 · 구성별 직접 선언 ${snapshot.configurations.sumOf { it.dependencies.size }}개 · 코드 참조 ${scope.classesByModule.values.sumOf { classes -> classes.sumOf { it.directDependenciesFromSelf.size } }}개")
        assertTrue(result.violations.isEmpty(), "ARCH-08 위반:\n${result.violations.joinToString("\n") { it.report() }}")
        println("ARCH-08 통과 · 준비 오류 0, 위반 0")
        println("한계: 현재 모델에 나타난 직접 선언/바이트코드 참조와 명시 도구 목록. 전이·동적 로딩·임의 복사본·파일 의존 전체 감사, 테스트 품질·DB 동작은 포함하지 않습니다.")
    }

    @Test
    fun `P05 실제 운영 코드의 업무 자동 등록 금지와 config Bean 선언을 검사한다`() {
        val registration = ModuleRegistration.inspect(ProductionScope.inventory())
        assertTrue(registration.isEmpty(), "Bean 조립 검사 준비 실패: $registration")
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionScope.expectations(), ProductionScope.nonProductionTargets())
        val policy = ProjectLayoutPolicy.target
        val input = NamingPlacementScope.prepare(scope, policy, ProductionScope.requiredTypes.keys)
        assertTrue(input.problems.isEmpty(), "Bean 조립 미평가 · 준비 오류: ${input.problems}")
        val config = policy.folders.single { it.id == "config" }
        val registrationRule = BeanAssemblyRules.noAutomaticBusinessRegistration()
        val factoryRule = BeanAssemblyRules.configFactories(input.modules, config)
        scope.classesByModule.toSortedMap().forEach { (_, classes) ->
            registrationRule.check(classes)
            factoryRule.check(classes)
        }
        println("P05 통과 · 운영 ${input.classes.size}개 타입에 업무 자동 등록 금지와 config Bean 선언 검사 적용")
        println("HTTP Controller·Advice 자동 등록은 허용합니다. P08은 주문 이름·전체 파일 폴더를 평가하고 저장 이름·역할 위치는 #21로 남깁니다. 실제 Spring 주입 성공을 검증한 것은 아닙니다.")
    }

    @Test
    fun `P06 실제 전체 HTTP 진입점에 ARCH-03을 적용한다`() {
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionBoundaryInputs.expectations(), ProductionScope.nonProductionTargets())
        val result = HttpEntryBoundary.inspect(scope, ProductionBoundaryInputs.http(scope))
        assertTrue(result.evaluated, "ARCH-03 미평가 · 준비 오류: ${result.problems}")
        println("P06 평가: ARCH-03 · 실제 HTTP ${result.apiTypes.size}개: ${result.apiTypes} · 직접 참조 ${result.referenceCount}개")
        assertTrue(result.violations.isEmpty(), "ARCH-03 위반:\n${result.violations.joinToString("\n")}")
        println("ARCH-03 통과. UseCase 호출·데이터 변환을 허용하고 저장·엔진·협력자 직접 참조를 금지합니다. 업무 실행·JSON·HTTP 상태 코드는 app-api 테스트로 확인합니다.")
    }

    @Test
    fun `P08 주문 이름과 전체 main 원본의 실제 폴더에 ARCH-05를 적용한다`() {
        val modules = ProductionScope.requiredTypes.keys
        val scope = ProductionScopeImporter().load(ProductionScope.outputs(), ProductionBoundaryInputs.expectations(), ProductionScope.nonProductionTargets())
        val policy = ProjectLayoutPolicy.duringOrderMigration
        val names = NamingRules.inspectOrderTypes(scope, policy, modules)
        assertTrue(names.evaluated, "ARCH-05 이름 미평가 · 준비 오류: ${names.problems}")
        val sources = MainSourceSnapshot.read(System.getProperty("architecture.mainSources"), modules)
        assertTrue(sources.problems.isEmpty(), "ARCH-05 원본 미평가 · 준비 오류: ${sources.problems}")
        val placement = SourcePlacement.inspect(sources.roots, policy, modules).result
        assertTrue(placement.evaluated, "ARCH-05 원본 미평가 · 준비 오류: ${placement.problems}")
        println("P08 평가: ARCH-05 · 이름 ${names.evaluatedTypes.size}개, 전체 main 원본 ${placement.evaluatedFiles.size}개")
        names.rules.forEach { println("이름 규칙 ${it.id}: ${it.targets.size}개 ${it.targets}") }
        placement.evaluatedFiles.forEach { println("원본 검사: $it") }
        assertTrue(names.violations.isEmpty() && placement.violations.isEmpty(),
            "ARCH-05 위반:\n${(names.violations + placement.violations).joinToString("\n")}")
        println("ARCH-05 통과. #21 이행 폴더: ${policy.folders.filter { it.id.startsWith("legacy-") }}")
        println("저장 포트·구현·Repository·Entity의 이름·역할 위치 규칙은 #21 미활성. 네 이행 폴더도 원본 읽기·package 일치와 P06~P07 금지 의존 검사를 받습니다. 일반 클래스 이름의 업무 의미는 리뷰합니다.")
    }
}

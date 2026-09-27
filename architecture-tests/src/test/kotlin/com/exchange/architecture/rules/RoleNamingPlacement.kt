package com.exchange.architecture.rules

import com.exchange.architecture.support.*
import com.exchange.architecture.policy.NamingClassificationPolicy
import com.exchange.architecture.support.NamingRole.*

/** 전체 수집 뒤 이름·형태·기술 근거를 함께 비교한다. 정책의 나열 순서로 역할을 선택하지 않는다. */
object RoleNamingPlacement {
    fun inspectTypes(scope: ScopeImportResult, policy: LayoutPolicy, registeredModules: Set<String>): PlacementResult {
        val input = NamingPlacementScope.prepare(scope, policy, registeredModules)
        if (input.problems.isNotEmpty()) return PlacementResult(problems = input.problems)
        return try { Classifier(input, policy).inspect() }
        catch (e: Exception) {
            PlacementResult(problems = listOf(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, "naming-metadata", e.message ?: e.javaClass.simpleName)))
        }
    }

    private class Classifier(val input: NamingPlacementInput, val policy: LayoutPolicy) {
        val reader = NamingTypeFacts(input, policy)
        val rules = policy.naming.rules.associateBy { it.role }
        val reports = sortedMapOf<String, TypeClassification>()
        val violations = mutableListOf<PlacementViolation>()
        val owners = sortedMapOf<String, String>()
        val annotations = reader.facts.keys.associateWith(reader::annotations)
        val ancestors = reader.facts.keys.associateWith(reader::ancestors)
        val bootAnnotation = "org.springframework.boot.autoconfigure.SpringBootApplication"
        val controllerAnnotations = setOf("org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController")
        val adviceAnnotations = setOf("org.springframework.web.bind.annotation.ControllerAdvice", "org.springframework.web.bind.annotation.RestControllerAdvice")
        val configurationAnnotation = "org.springframework.context.annotation.Configuration"
        val entityAnnotation = "jakarta.persistence.Entity"
        val repositoryType = "org.springframework.data.repository.Repository"

        fun inspect(): PlacementResult {
            reader.facts.filterValues { !it.generated }.keys.forEach { classify(it) }
            reader.facts.filterValues { it.generated }.keys.forEach { generated(it, emptySet()) }
            // 데이터만 있거나 운반 타입만 들어온 빈 업무 검사를 정상 통과로 만들지 않는다.
            // 미분류 타입은 실제 진단할 대상이므로 EMPTY_ROLE로 위반을 가리지 않는다.
            if (reports.values.all { it.roles.isNotEmpty() && it.roles.all { role -> role in setOf(DATA, FILE_FACADE) } }) {
                return PlacementResult(problems = listOf(ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "naming-business")))
            }
            return PlacementResult(
                violations = violations.distinct().sortedWith(compareBy({ it.subject }, { it.item }, { it.actual })),
                evaluatedTypes = input.classes.keys.toSortedSet(), generatedOwners = owners, classifications = reports,
            )
        }
        fun suffix(role: NamingRole) = rules[role]?.suffix ?: role.suffix
        fun matches(name: String, role: NamingRole) = setOfNotNull(role.suffix, suffix(role)).any { name.endsWith(it) }
        fun violation(name: String, item: String, actual: String, expected: String, roles: Set<NamingRole>) {
            violations += PlacementViolation(name, item, actual, expected, input.modules.getValue(name),
                roles.map { it.name }.sorted().joinToString("+"), reader.facts.getValue(name).sourceFile)
        }
        fun classify(name: String) {
            val facts = reader.facts.getValue(name); val type = facts.type; val module = input.modules.getValue(name)
            val found = linkedMapOf<NamingRole, MutableSet<String>>()
            fun signal(role: NamingRole, evidence: String) { found.getOrPut(role) { sortedSetOf() }.add(evidence) }
            val tags = annotations.getValue(name)
            val parents = ancestors.getValue(name)
            val ports = parents.mapNotNull { input.classes[it] }.filter { it.isInterface }
            val stores = ports.filter { matches(it.simpleName, STORE_PORT) }
            val publishers = ports.filter { matches(it.simpleName, PUBLISHER_PORT) }
            if (facts.kind in setOf(2, 4, 5)) signal(FILE_FACADE, "Kotlin 파일 메타데이터 k=${facts.kind}")
            else {
                listOf(USE_CASE, SERVICE, COORDINATOR, CALCULATOR, RESOLVER).forEach {
                    if (matches(type.simpleName, it)) signal(it, "이름: ${type.simpleName}")
                }
                if (matches(type.simpleName, STORE_PORT)) signal(if (facts.isInterface) STORE_PORT else STORE_IMPLEMENTATION, "Store 이름·선언 형태")
                if (!facts.isInterface && stores.isNotEmpty()) signal(STORE_IMPLEMENTATION, "포트 구현: ${stores.map { it.name }.sorted()}")
                if (matches(type.simpleName, REPOSITORY) || repositoryType in parents) signal(REPOSITORY, "Repository 이름 또는 Spring Data 상속")
                if (matches(type.simpleName, PUBLISHER_PORT)) signal(if (facts.isInterface) PUBLISHER_PORT else PUBLISHER_IMPLEMENTATION, "Publisher 이름·선언 형태")
                if (!facts.isInterface && publishers.isNotEmpty()) signal(PUBLISHER_IMPLEMENTATION, "발행 포트 구현: ${publishers.map { it.name }.sorted()}")
                if (matches(type.simpleName, CONTROLLER) || tags.any { it in controllerAnnotations }) signal(CONTROLLER, "Controller 이름 또는 어노테이션")
                if (bootAnnotation in tags) signal(BOOT, "@SpringBootApplication")
                if (matches(type.simpleName, CONFIGURATION) || configurationAnnotation in tags && bootAnnotation !in tags) signal(CONFIGURATION, "Config 이름 또는 @Configuration")
                if (tags.any { it in adviceAnnotations }) signal(ADVICE, "ControllerAdvice 어노테이션")
                if (entityAnnotation in tags) signal(ENTITY, "@Entity")
                if (found.isEmpty()) {
                    if (facts.isData) signal(DATA, "data=${facts.data}, value=${facts.value}, record=${facts.isRecord}, enum=${facts.isEnum}")
                    else if (module in NamingClassificationPolicy.coreModules) signal(DOMAIN, "명시한 코어 모듈: $module")
                }
            }
            val roles = found.keys.toSortedSet(compareBy { it.name })
            val evidence = found.values.flatten().toSortedSet()
            val ruleIds = roles.mapNotNull { rules[it]?.id }.toSortedSet()
            var locations = policy.folders.filter { folder -> roles.any { it in folder.roles } }
            if (roles.size != 1 || roles.any { it !in rules }) {
                val item = if (roles.size > 1) "roleConflict" else "unclassified"
                violation(name, item, evidence.joinToString("; ").ifEmpty { type.simpleName }, "서로 충돌하지 않는 공통 역할 규칙", roles)
            } else {
                val role = roles.single()
                if (role == DOMAIN || role in setOf(DATA, FILE_FACADE) && module in NamingClassificationPolicy.coreModules)
                    locations = locations.filter { it.module == module }
                if (role == STORE_IMPLEMENTATION && stores.isNotEmpty()) {
                    val targets = stores.map { port ->
                        val portModule = input.modules.getValue(port.name)
                        val candidates = policy.folders.filter { it.module == portModule && STORE_PORT in it.roles }
                        val atPackage = candidates.filter { it.packageName == port.packageName }
                        val positions = atPackage.ifEmpty { candidates }
                        val mapped = positions.map { position ->
                            requireNotNull(policy.naming.storeTargets[position.id]) { "포트 영역의 저장 구현 정책 누락: ${position.id}" }
                        }.toSet()
                        require(mapped.size == 1) { "포트 영역을 결정할 수 없습니다: ${port.name}" }
                        mapped.single()
                    }.toSortedSet()
                    if (targets.size > 1) violation(name, "roleConflict", targets.joinToString("; "), "한 영역의 Store 포트", roles)
                    locations = equivalentLocations(locations, targets)
                }
                if (role == PUBLISHER_IMPLEMENTATION) {
                    val prefix = policy.naming.publisherTargets.keys.singleOrNull { type.simpleName.startsWith(it) }
                    locations = equivalentLocations(locations, setOfNotNull(policy.naming.publisherTargets[prefix]))
                }
                if (role == FILE_FACADE) {
                    validateParts(name)
                    locations = locations.filter { folder ->
                        BOOT !in folder.roles || reader.facts.values.any {
                            bootAnnotation in annotations.getValue(it.type.name) && input.modules[it.type.name] == module &&
                                it.type.packageName == type.packageName && it.sourceFile != null && it.sourceFile == facts.sourceFile
                        }
                    }
                }
                val expectedSuffix = suffix(role)
                var validName = expectedSuffix == null || type.simpleName.endsWith(expectedSuffix) && type.simpleName.length > expectedSuffix.length
                if (role == STORE_IMPLEMENTATION) validName = validName && policy.naming.storeTechnologies.any {
                    type.simpleName.startsWith(it) && type.simpleName.length > it.length + expectedSuffix!!.length
                }
                if (role == PUBLISHER_IMPLEMENTATION) validName = validName && policy.naming.publisherTargets.keys.any {
                    type.simpleName.startsWith(it) && type.simpleName.length > it.length + expectedSuffix!!.length
                }
                if (!validName) violation(name, "name", type.simpleName,
                    when (role) {
                        STORE_IMPLEMENTATION -> "${policy.naming.storeTechnologies.sorted()}+대상+$expectedSuffix"
                        PUBLISHER_IMPLEMENTATION -> "${policy.naming.publisherTargets.keys.sorted()}+대상+$expectedSuffix"
                        else -> "대상+$expectedSuffix"
                    }, roles)
                val shape = when (role) {
                    USE_CASE, SERVICE, COORDINATOR -> facts.concrete
                    CALCULATOR, RESOLVER -> !facts.isAnnotation && !facts.isData
                    STORE_PORT, PUBLISHER_PORT -> facts.isInterface && !facts.isAnnotation
                    STORE_IMPLEMENTATION -> !facts.isInterface && stores.isNotEmpty()
                    REPOSITORY -> facts.isInterface && repositoryType in parents
                    PUBLISHER_IMPLEMENTATION -> !facts.isInterface && publishers.isNotEmpty()
                    CONTROLLER -> tags.any { it in controllerAnnotations }
                    CONFIGURATION -> configurationAnnotation in tags
                    else -> true
                }
                if (!shape) violation(name, "roleShape", "${type.simpleName} 선언·상속·메타 정보", "$role 역할의 선언 조건", roles)
                if (role == DATA) {
                    val current = locations.filter { it.module == module && it.packageName == type.packageName }
                    if (current.isNotEmpty() && current.none { folder -> when (folder.dataNames) {
                        DataNames.ANY -> true
                        DataNames.HTTP -> facts.isEnum || listOf("Request", "Response").any { type.simpleName.endsWith(it) && type.simpleName.length > it.length }
                        DataNames.ERROR -> !facts.isEnum && type.simpleName.endsWith("ErrorResponse") && type.simpleName.length > "ErrorResponse".length
                    } }) violation(name, "name", type.simpleName, current.map { it.dataNames.name }.sorted().joinToString("; "), roles)
                }
                if (locations.none { it.module == module }) violation(name, "module", module, locations.map { it.module }.toSortedSet().joinToString("; ").ifEmpty { "허용 위치 없음" }, roles)
                val withinModule = locations.filter { it.module == module }.ifEmpty { locations }
                if (withinModule.none { it.packageName == type.packageName }) violation(name, "package", type.packageName,
                    withinModule.map { it.packageName }.toSortedSet().joinToString("; ").ifEmpty { "허용 위치 없음" }, roles)
            }
            reports[name] = TypeClassification(roles, ruleIds, evidence, locations.map { it.id }.toSortedSet(), module, type.packageName, facts.sourceFile, sourceParts = facts.parts)
        }
        // 같은 역할·package의 추가 루트는 개별 타입의 단일 위치로 다시 고정하지 않는다.
        fun equivalentLocations(locations: List<AllowedFolder>, ids: Set<String>): List<AllowedFolder> {
            val positions = policy.folders.filter { it.id in ids }.map { it.module to it.packageName }.toSet()
            return locations.filter { (it.module to it.packageName) in positions }
        }
        fun validateParts(name: String) {
            val f = reader.facts.getValue(name)
            f.parts.forEach { part ->
                val p = requireNotNull(reader.facts[part]) { "다중 파일 part 누락: $name -> $part" }
                require(p.kind == 5 && p.facade == name && input.modules[part] == input.modules[name] && p.type.packageName == f.type.packageName) { "다중 파일 part 관계 불일치: $name -> $part" }
            }
            f.facade?.let { facade ->
                require(reader.facts[facade]?.kind == 4 && name in reader.facts.getValue(facade).parts && input.modules[facade] == input.modules[name]) { "다중 파일 facade 누락·관계 불일치: $name -> $facade" }
            }
        }
        fun generated(name: String, visiting: Set<String>): TypeClassification {
            reports[name]?.let { return it }
            require(name !in visiting) { "생성 타입 소유 관계 순환: $name" }
            val facts = reader.facts.getValue(name)
            val owner = requireNotNull(facts.owner) { "생성 타입 소유자 누락: $name" }
            require(owner in input.classes && input.modules[name] == input.modules[owner] && input.classes.getValue(owner).packageName == facts.type.packageName) { "생성 타입 소유자 수집 누락: $name -> $owner" }
            val parent = reports[owner] ?: generated(owner, visiting + name)
            owners[name] = owner
            return parent.copy(owner = owner, sourceFile = facts.sourceFile, evidence = (parent.evidence + "확인한 생성 관계: $name -> $owner").toSortedSet(), sourceParts = emptySet()).also { reports[name] = it }
        }
    }
}

package com.exchange.architecture.support

import com.tngtech.archunit.core.domain.JavaClass

/** 이름 역할은 ARCH-04의 넓은 APPLICATION 역할보다 세분화한다. */
enum class NamingRole(val suffix: String?) {
    USE_CASE("UseCase"), SERVICE("Service"), COORDINATOR("Coordinator"),
    CALCULATOR("Calculator"), RESOLVER("Resolver"), STORE_PORT("Store"),
    STORE_IMPLEMENTATION("Store"), REPOSITORY("Repository"), PUBLISHER("Publisher"),
    CONTROLLER("Controller"), CONFIGURATION("Config"), DATA(null), DOMAIN(null), SUPPORT(null),
}

data class AllowedSourceRoot(val module: String, val path: String, val producer: String? = null)

data class AllowedFolder(
    val id: String,
    val module: String,
    val sourceRoot: String,
    val folder: String,
    val roles: Set<NamingRole>,
    val reason: String,
    val onlyTypes: Set<String>? = null,
) {
    val packageName: String get() = folder.replace('/', '.')
}

/** 타입은 검토한 위치 하나에 연결한다. 허용 폴더 발견으로 역할을 추측하지 않는다. */
data class NamingBinding(
    val type: String,
    val role: NamingRole,
    val location: String,
    val exactName: String? = null,
    val technology: String? = null,
    val reason: String? = null,
)

data class LayoutPolicy(val roots: List<AllowedSourceRoot>, val folders: List<AllowedFolder>)

data class NamingPlacementInput(
    val classes: Map<String, JavaClass>,
    val modules: Map<String, String>,
    val bindings: Map<String, NamingBinding>,
    val generatedOwners: Map<String, String>,
    val problems: List<ScopeProblem>,
)

data class PlacementViolation(
    val subject: String,
    val item: String,
    val actual: String,
    val expected: String,
    val module: String,
    val role: String? = null,
    val sourceFile: String? = null,
    val lineNumber: Int? = null,
    val ruleId: String = "ARCH-05",
    val specification: String = "architecture-check-spec.md · ARCH-05",
)

data class PlacementResult(
    val problems: List<ScopeProblem> = emptyList(),
    val violations: List<PlacementViolation> = emptyList(),
    val evaluatedTypes: Set<String> = emptySet(),
    val evaluatedFiles: Set<String> = emptySet(),
    val generatedOwners: Map<String, String> = emptyMap(),
) {
    val evaluated: Boolean get() = problems.isEmpty()
}

/** 등록 목록과 별도로 발견한 모든 타입을 분류한다. 준비 오류가 있으면 부분 성공을 반환하지 않는다. */
object NamingPlacementScope {
    fun prepare(scope: ScopeImportResult, bindings: List<NamingBinding>, policy: LayoutPolicy,
        registeredModules: Set<String>, requiredRoles: Set<NamingRole> = emptySet(),
        applicationRoles: Map<String, ApplicationRole> = emptyMap(), httpRoles: Map<String, HttpRole> = emptyMap(),
    ): NamingPlacementInput {
        val errors = (scope.problems + com.exchange.architecture.policy.LayoutPolicyValidation.inspect(policy, registeredModules)).toMutableList()
        fun invalid(subject: String, detail: String) { errors += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, subject, detail) }
        val entries = scope.classesByModule.flatMap { (module, classes) -> classes.map { module to it } }
        val classes = entries.associate { it.second.name to it.second }
        val modules = entries.associate { it.second.name to it.first }
        if (classes.isEmpty()) errors += ScopeProblem(ScopeProblemCode.EMPTY_SCOPE, "naming-placement")
        entries.groupBy { it.second.name }.filterValues { it.size > 1 }.keys.forEach { errors += ScopeProblem(ScopeProblemCode.DUPLICATE_TYPE, it) }
        (scope.classesByModule.keys - registeredModules).forEach { errors += ScopeProblem(ScopeProblemCode.UNREGISTERED_MODULE, it) }
        bindings.groupBy { it.type }.filterValues { it.size > 1 }.keys.forEach { invalid(it, "역할 중복") }
        val registered = bindings.associateBy { it.type }
        val locations = policy.folders.associateBy { it.id }
        bindings.forEach { b ->
            if (b.type !in classes) errors += ScopeProblem(ScopeProblemCode.MISSING_ROLE_TYPE, b.type)
            val location = locations[b.location]
            if (location == null || b.role !in location.roles || location.onlyTypes?.contains(b.type) == false) invalid(b.type, "역할·허용 위치·타입 제한 충돌")
            if (b.exactName?.isBlank() == true || b.exactName?.contains('*') == true ||
                b.role.suffix == null && b.reason.isNullOrBlank()) invalid(b.type, "이름 기준 또는 접미사 면제 이유가 필요합니다")
            if (b.role == NamingRole.STORE_IMPLEMENTATION && b.technology !in setOf("Postgres", "Jpa")) invalid(b.type, "등록하지 않은 저장 기술")
            if (b.role != NamingRole.STORE_IMPLEMENTATION && b.technology != null) invalid(b.type, "저장 구현 외 기술 접두사")
            applicationRoles[b.type]?.let { old ->
                if (old !in compatibleApplicationRoles(b.role)) invalid(b.type, "ARCH-04 역할과 충돌: $old")
            }
            httpRoles[b.type]?.let { old ->
                if (old !in compatibleHttpRoles(b.role)) invalid(b.type, "ARCH-03 역할과 충돌: $old")
            }
        }
        if (bindings.none { it.role != NamingRole.SUPPORT && it.role != NamingRole.DATA }) errors += ScopeProblem(ScopeProblemCode.EMPTY_ROLE, "naming-business")
        (requiredRoles - bindings.map { it.role }.toSet()).forEach { errors += ScopeProblem(ScopeProblemCode.EMPTY_ROLE, it.name) }
        val owners = linkedMapOf<String, String>()
        fun owner(type: JavaClass, visited: Set<String> = emptySet()): String? {
            if (type.name in visited || !isGeneratedChild(type)) return null
            val enclosing = type.enclosingClass.orElse(null) ?: return null
            if (enclosing.name !in classes || modules[enclosing.name] != modules[type.name]) return null
            return if (enclosing.name in registered) enclosing.name else owner(enclosing, visited + type.name)
        }
        classes.values.filter { it.name !in registered }.forEach { type ->
            val parent = owner(type)
            if (parent == null) invalid(type.name, "발견한 타입의 역할이 등록되지 않았습니다") else owners[type.name] = parent
        }
        return NamingPlacementInput(classes, modules, registered, owners, errors.distinct().sortedWith(compareBy({ it.code.name }, { it.subject }, { it.detail })))
    }

    private fun isGeneratedChild(type: JavaClass): Boolean {
        if (type.isAnonymousClass) return true
        if (!type.isAnnotatedWith(Metadata::class.java)) return false
        return try {
            val metadata = type.getAnnotationOfType(Metadata::class.java)
            if (metadata.kind == 3) true
            else metadata.kind == 1 && org.jetbrains.kotlin.metadata.deserialization.Flags.CLASS_KIND.get(
                org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil.readClassDataFrom(metadata.data1, metadata.data2).second.flags
            ) == org.jetbrains.kotlin.metadata.ProtoBuf.Class.Kind.COMPANION_OBJECT
        } catch (_: Exception) { false }
    }

    private fun compatibleApplicationRoles(role: NamingRole): Set<ApplicationRole> = when (role) {
        NamingRole.USE_CASE, NamingRole.SERVICE, NamingRole.COORDINATOR -> setOf(ApplicationRole.APPLICATION)
        NamingRole.STORE_PORT -> setOf(ApplicationRole.PORT)
        NamingRole.STORE_IMPLEMENTATION, NamingRole.REPOSITORY -> setOf(ApplicationRole.INFRASTRUCTURE)
        NamingRole.PUBLISHER -> setOf(ApplicationRole.PORT, ApplicationRole.INFRASTRUCTURE)
        NamingRole.CONTROLLER -> setOf(ApplicationRole.HTTP)
        NamingRole.CONFIGURATION -> setOf(ApplicationRole.CONFIGURATION)
        NamingRole.CALCULATOR, NamingRole.RESOLVER, NamingRole.DOMAIN -> setOf(ApplicationRole.DOMAIN, ApplicationRole.EXECUTOR)
        NamingRole.DATA, NamingRole.SUPPORT -> ApplicationRole.entries.toSet()
    }
    private fun compatibleHttpRoles(role: NamingRole): Set<HttpRole> = when (role) {
        NamingRole.USE_CASE -> setOf(HttpRole.USE_CASE)
        NamingRole.SERVICE, NamingRole.COORDINATOR -> setOf(HttpRole.COLLABORATOR)
        NamingRole.CONTROLLER -> setOf(HttpRole.CONTROLLER)
        NamingRole.CONFIGURATION -> setOf(HttpRole.CONFIGURATION)
        NamingRole.STORE_PORT -> setOf(HttpRole.PORT)
        NamingRole.STORE_IMPLEMENTATION, NamingRole.REPOSITORY -> setOf(HttpRole.INFRASTRUCTURE)
        NamingRole.PUBLISHER -> setOf(HttpRole.PORT, HttpRole.INFRASTRUCTURE)
        else -> HttpRole.entries.toSet()
    }
}

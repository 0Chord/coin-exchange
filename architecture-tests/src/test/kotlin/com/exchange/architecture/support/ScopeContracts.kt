package com.exchange.architecture.support

import com.tngtech.archunit.core.domain.JavaClasses
import java.nio.file.Path

data class ModuleOutput(val module: String, val roots: List<Path>)

data class ScopeExpectations(
    val requiredTypesByModule: Map<String, Set<String>>,
    val requiredRoles: Map<String, Set<String>> = emptyMap(),
    val forbiddenRoots: Set<Path> = emptySet(),
    val projectPackagePrefixes: Set<String> = emptySet(),
    val forbiddenTypePrefixes: Set<String> = emptySet(),
)

enum class ScopeProblemCode {
    EMPTY_SCOPE, MISSING_MODULE, EMPTY_MODULE, UNREGISTERED_MODULE,
    MISSING_REQUIRED_TYPE, EMPTY_ROLE, MISSING_ROLE_TYPE,
    INCOMPLETE_IMPORT, DUPLICATE_TYPE, FORBIDDEN_OUTPUT,
    READ_FAILURE, UNEXPECTED_IMPORTED_TYPE, AMBIGUOUS_OWNERSHIP,
    UNRESOLVED_PROJECT_TYPE, UNCLASSIFIED_INTERFACE, CONFLICTING_MODULE_ROLE,
}

data class ScopeProblem(
    val code: ScopeProblemCode,
    val subject: String,
    val detail: String? = null,
)

data class ScopeImportResult(
    val classesByModule: Map<String, JavaClasses> = emptyMap(),
    val problems: List<ScopeProblem> = emptyList(),
)

fun interface BytecodeReader {
    fun read(roots: List<Path>): JavaClasses
}

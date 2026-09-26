package com.exchange.architecture.support

import com.tngtech.archunit.core.domain.JavaClasses
import java.nio.file.Path

/** 한 모듈의 운영 컴파일 출력 폴더. 테스트·벤치마크 출력은 포함하지 않는다. */
data class ModuleOutput(val module: String, val roots: List<Path>)

/**
 * 수집 결과가 빠지거나 오염되지 않았는지 대조할 기준.
 *
 * [requiredTypesByModule]은 모듈별 최소 확인 타입이며 전체 수집 대상을 제한하지 않는다.
 * [projectPackagePrefixes]에 속한 직접 참조는 운영 출력 또는 검증된 비운영 목적지에 실제 정의가 있어야 한다.
 * [forbiddenTypePrefixes]는 금지 경로 밖으로 복사된 예제도 찾기 위한 이름 접두사다.
 */
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
    UNRESOLVED_PROJECT_TYPE, UNCLASSIFIED_INTERFACE, CONFLICTING_MODULE_ROLE, INVALID_TARGET_INPUT,
}

data class ScopeProblem(
    val code: ScopeProblemCode,
    val subject: String,
    val detail: String? = null,
)

/**
 * 읽을 수 있었던 클래스와 수집 오류. 오류가 있어도 부분 결과가 남을 수 있다.
 * [problems]가 비어 있는지 확인한 뒤에만 구조 규칙 검사에 사용한다.
 */
data class ScopeImportResult(
    val classesByModule: Map<String, JavaClasses> = emptyMap(),
    val problems: List<ScopeProblem> = emptyList(),
)

fun interface BytecodeReader {
    /**
     * @param roots 읽을 바이트코드 경로. 운영 수집기는 확인한 `.class` 파일 목록을 전달한다.
     * @return 파일에서 읽은 클래스. 반환 목록의 누락·추가는 수집기가 별도로 대조한다.
     */
    fun read(roots: List<Path>): JavaClasses
}

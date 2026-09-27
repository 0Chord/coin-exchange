package com.exchange.architecture.policy

import com.exchange.architecture.support.*
import com.exchange.architecture.support.NamingRole.*

/** #20~21에서 운영 이름·배치를 이행할 때 사용할 목표 목록. 현재 운영 검사에는 연결하지 않는다. */
object ProjectLayoutPolicy {
    val target: LayoutPolicy by lazy {
        val root = "src/main/kotlin"
        val base = "com/exchange/core"
        fun app(id: String, path: String, roles: Set<NamingRole>, reason: String) =
            AllowedFolder(id, "app-api", root, path, roles, reason)
        LayoutPolicy(
            ProductionScope.requiredTypes.keys.sorted().map { AllowedSourceRoot(it, root) },
            listOf(
                app("bootstrap", base, setOf(BOOT, FILE_FACADE), "앱 시작점"),
                app("config", "$base/api/config", setOf(CONFIGURATION), "명시한 Bean 조립"),
                app("http-errors", "$base/api/common", setOf(ADVICE, DATA), "공통 HTTP 오류").copy(dataNames = DataNames.ERROR),
                app("order-http", "$base/api/order/api", setOf(CONTROLLER, DATA, FILE_FACADE), "주문 HTTP 변환").copy(dataNames = DataNames.HTTP),
                app("order-application", "$base/api/order/application", setOf(USE_CASE, SERVICE, DATA), "주문 실행과 내부 작업"),
                app("order-persistence", "$base/api/order/infrastructure/persistence", setOf(STORE_IMPLEMENTATION, ENTITY, DATA, FILE_FACADE), "주문 예약 저장"),
                app("ledger-persistence", "$base/api/ledger/infrastructure/persistence", setOf(STORE_IMPLEMENTATION, ENTITY, DATA, FILE_FACADE), "잔고·원장 저장"),
                app("matching-application", "$base/api/matching/application", setOf(COORDINATOR, DATA), "매칭 전후 작업 연결"),
                app("matching-ports", "$base/api/matching/application/port", setOf(STORE_PORT, PUBLISHER_PORT), "매칭 저장·발행 계약"),
                app("matching-persistence", "$base/api/matching/infrastructure/persistence", setOf(STORE_IMPLEMENTATION, REPOSITORY, ENTITY, DATA, PUBLISHER_IMPLEMENTATION, FILE_FACADE), "매칭 영속화"),
                app("matching-publish", "$base/api/matching/infrastructure/publish", setOf(PUBLISHER_IMPLEMENTATION), "명시한 미저장 발행 구현"),
            ) + listOf("common", "fee", "order", "ledger", "matching").map {
                AllowedFolder("domain-$it", "domain-$it", root, "$base/$it",
                    setOf(DOMAIN, DATA, FILE_FACADE) + when (it) {
                        "fee" -> setOf(CALCULATOR, RESOLVER)
                        "order" -> setOf(STORE_PORT, CALCULATOR, RESOLVER)
                        "ledger" -> setOf(STORE_PORT)
                        else -> emptySet()
                    }, "기술 독립 코어와 명시한 역할")
            },
            NamingClassificationPolicy.common.copy(
                storeTargets = mapOf("domain-order" to "order-persistence", "domain-ledger" to "ledger-persistence", "matching-ports" to "matching-persistence"),
                publisherTargets = mapOf("Persistent" to "matching-persistence", "NoOp" to "matching-publish"),
            ),
        )
    }
}

/** 경로 문자열을 관대하게 보정하면 정책의 오타가 새 허용 범위로 바뀔 수 있어 거절한다. */
object LayoutPolicyValidation {
    fun relativePath(value: String): Boolean = value.isNotBlank() && !value.startsWith('/') &&
        !value.contains('\\') && value.split('/').all { it.isNotBlank() && it != "." && it != ".." && '*' !in it }

    fun inspect(policy: LayoutPolicy, modules: Set<String>): List<ScopeProblem> {
        val errors = mutableListOf<ScopeProblem>()
        fun invalid(subject: String, detail: String) { errors += ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, subject, detail) }
        if (policy.roots.isEmpty() || policy.folders.isEmpty()) invalid("layout-policy", "허용 루트·폴더가 비어 있습니다")
        policy.roots.groupBy { it.module to it.path }.filterValues { it.size != 1 }.keys.forEach { invalid(it.toString(), "루트 중복") }
        policy.roots.forEach {
            if (it.module !in modules || !relativePath(it.path) || it.producer?.isBlank() == true) invalid(it.toString(), "모듈·루트·생산 작업을 확인하세요")
        }
        policy.folders.groupBy { it.id }.filterValues { it.size != 1 }.keys.forEach { invalid(it, "위치 식별자 중복") }
        policy.folders.groupBy { Triple(it.module, it.sourceRoot, it.folder) }.filterValues { it.size != 1 }.keys.forEach { invalid(it.toString(), "폴더 중복") }
        policy.folders.forEach {
            if (it.id.isBlank() || it.module !in modules || !relativePath(it.sourceRoot) || !relativePath(it.folder) ||
                it.roles.isEmpty() || it.reason.isBlank() ||
                policy.roots.none { root -> root.module == it.module && root.path == it.sourceRoot }) invalid(it.id, "유효한 모듈·루트·폴더·역할이 필요합니다")
        }
        return errors
    }
}

package com.exchange.architecture.policy

import com.exchange.architecture.support.LayoutPolicy
import com.exchange.architecture.support.ScopeProblem
import com.exchange.architecture.support.ScopeProblemCode

/** 포트가 속한 영역과 외부 구현 위치의 대응만 둔다. 타입별 역할 등록은 하지 않는다. */
data class PortLocations(
    val storeTargets: Map<String, String> = emptyMap(),
    val publisherTargets: Map<String, String> = mapOf("Persistent" to "matching-persistence", "NoOp" to "matching-publish"),
)

object PortPlacementPolicy {
    val common = PortLocations()

    fun validate(policy: LayoutPolicy): List<ScopeProblem> =
        buildList {
            val ids = policy.folders.map { it.id }.toSet()
            policy.naming.storeTargets.forEach { (port, implementation) ->
                if (port !in ids ||
                    implementation !in ids
                ) {
                    add(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, port, "Store 영역 매핑의 위치가 없습니다"))
                }
            }
            if (policy.naming.publisherTargets.keys !=
                setOf("Persistent", "NoOp")
            ) {
                add(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, "publisher-policy", "Persistent·NoOp 위치 매핑이 필요합니다"))
            }
            policy.naming.publisherTargets.forEach { (prefix, location) ->
                if (prefix !in
                    setOf("Persistent", "NoOp")
                ) {
                    add(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, prefix, "알 수 없는 Publisher 접두사"))
                }
                // 이 규칙의 대상이 있을 때 필요한 위치의 존재 여부를 확인한다.
                if (location.isBlank()) add(ScopeProblem(ScopeProblemCode.INVALID_TARGET_INPUT, prefix, "Publisher 위치가 비어 있습니다"))
            }
        }
}

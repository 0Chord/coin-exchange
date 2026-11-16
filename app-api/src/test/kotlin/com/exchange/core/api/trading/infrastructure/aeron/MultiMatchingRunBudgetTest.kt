package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.test.Test
import kotlin.test.assertFailsWith

class MultiMatchingRunBudgetTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun `기존 한도의 9노드는 Docker 실제9GiB와 호스트 여유20GiB부터 허용한다`() {
        MultiMatchingRunBudget.requireCapacity(9 * gib, 20 * gib)
    }

    @Test
    fun `기존 8GiB 환경과 최소값 미만은 작은 프로필로 우회하지 않는다`() {
        for (memory in listOf(0L, 8 * gib, 9 * gib - 1)) {
            assertFailsWith<IllegalStateException> { MultiMatchingRunBudget.requireCapacity(memory, 20 * gib) }
        }
    }

    @Test
    fun `메모리가 충분해도 기록 디스크 여유가 부족하면 거절한다`() {
        for (disk in listOf(0L, 20 * gib - 1)) {
            assertFailsWith<IllegalStateException> { MultiMatchingRunBudget.requireCapacity(10 * gib, disk) }
        }
    }
}

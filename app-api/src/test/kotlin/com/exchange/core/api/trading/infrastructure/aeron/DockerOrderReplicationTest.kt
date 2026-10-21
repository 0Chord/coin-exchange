package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.test.Test
import kotlin.test.assertTrue

class DockerOrderReplicationTest {
    @Test
    fun `매수 두 건의 순서와 매도와 부족 거절을 세 독립 클러스터 실행에서 대조한다`() {
        var image: String? = null
        val failures = mutableListOf<String>()
        for (scenario in OrderReplicationScenario.entries) {
            val verification = DockerFundingVerification(scenario, image)
            val result = verification.run()
            image = verification.image
            if (!result.succeeded) failures.add("$scenario: ${result.issues}\n보고서: ${verification.reportDirectory}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}

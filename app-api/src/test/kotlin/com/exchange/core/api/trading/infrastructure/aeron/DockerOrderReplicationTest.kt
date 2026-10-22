package com.exchange.core.api.trading.infrastructure.aeron

import kotlin.test.Test
import kotlin.test.assertTrue

class DockerOrderReplicationTest {
    @Test
    fun `접수와 재전송 충돌 및 RDB 다중 마켓 공유 자금을 각각 빈 세 노드 클러스터에서 대조한다`() {
        var image: String? = null
        val failures = mutableListOf<String>()
        for (scenario in OrderReplicationScenario.entries) {
            val verification = DockerFundingVerification(scenario, image)
            val result = verification.run()
            image = verification.image
            if (!result.succeeded) failures.add("$scenario: ${result.issues}\n보고서: ${verification.reportDirectory}")
        }
        for (scenario in ConcurrentOrderReplicationScenario.entries) {
            val concurrent = DockerFundingVerification(sharedImage = image, concurrentScenario = scenario)
            val result = concurrent.run()
            if (!result.succeeded) failures.add("CONCURRENT_$scenario: ${result.issues}\n보고서: ${concurrent.reportDirectory}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}

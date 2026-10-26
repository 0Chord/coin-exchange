package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.OneToOneConcurrentArrayQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 서비스 한 생산자와 테스트 main 한 소비자만 사용한다. 수집 실패는 업무 서비스에 전파하지 않는다. */
internal class FundingVerificationCollector(
    val capacity: Int = 16,
) {
    private val observations =
        run {
            require(capacity in 1..1024 && Integer.bitCount(capacity) == 1) {
                "검증 관찰 용량은 1..1024 범위의 2의 거듭제곱이어야 합니다"
            }
            OneToOneConcurrentArrayQueue<CollectedFundingObservation>(capacity)
        }
    private val failure = AtomicReference<String?>()

    @Volatile
    private var terminalObservation: CollectedFundingObservation? = null
    private var deliveredTerminal: CollectedFundingObservation? = null

    val verificationFailure: String? get() = failure.get()

    fun decorate(service: DevelopmentFundingAdapter): ClusteredService =
        object : ClusteredService by service {
            override fun onSessionMessage(
                session: ClientSession?,
                timestamp: Long,
                buffer: DirectBuffer,
                offset: Int,
                length: Int,
                header: Header,
            ) {
                val previous = service.observation
                try {
                    service.onSessionMessage(session, timestamp, buffer, offset, length, header)
                } finally {
                    service.observation?.takeIf { it !== previous }?.let { retain(it, service, timestamp) }
                }
            }
        }

    private data class CollectedFundingObservation(
        val observation: FundingObservation,
        val evidence: Map<String, Any?>,
    )

    private fun retain(
        observation: FundingObservation,
        service: DevelopmentFundingAdapter,
        timestamp: Long,
    ) {
        val collected =
            CollectedFundingObservation(
                observation,
                if (observation.snapshot ==
                    null
                ) {
                    emptyMap()
                } else {
                    tradingVerificationEvidence(service) + ("callbackTimestamp" to timestamp)
                },
            )
        if (observation.failure != null) {
            terminalObservation = collected
            failure.compareAndSet(null, "서비스 적용 실패: ${observation.failure}")
        } else if (!observations.offer(collected)) {
            failure.compareAndSet(null, "검증 관찰 버퍼 초과: capacity=$capacity, position=${observation.position}")
        }
    }

    /** 큐가 가득 차도 실제 서비스의 종료 오류는 별도 슬롯에서 한 번 배출한다. 실패 상태는 지우지 않는다. */
    fun drainObservations(): List<FundingObservation> = drainCollected().map { it.observation }

    fun drainFields(config: DevelopmentFundingSettings): List<Map<String, Any?>> =
        drainCollected().map {
            fundingVerificationAppliedFields(config, it.observation) + ("trading" to it.evidence)
        }

    private fun drainCollected(): List<CollectedFundingObservation> =
        buildList {
            observations.drainTo(this, capacity)
            terminalObservation?.let { terminal ->
                if (terminal !== deliveredTerminal) {
                    add(terminal)
                    deliveredTerminal = terminal
                }
            }
        }
}

internal fun fundingVerificationReadyFields(
    config: DevelopmentFundingSettings,
    node: DevelopmentFundingNode,
    collector: FundingVerificationCollector,
): Map<String, Any?> {
    val fields = fundingReadyFields(config, node)
    val settings = fields.getValue("settings") as Map<*, *>
    return fields + ("settings" to (settings + ("observationCapacity" to collector.capacity)))
}

internal fun fundingVerificationAppliedFields(
    config: DevelopmentFundingSettings,
    observation: FundingObservation,
): Map<String, Any?> = fundingAppliedFields(config, observation) + ("malformedMessages" to observation.malformedMessages)

/** Docker 검증용 실행이다. 일반 개발 노드의 실행 경로에는 수집 버퍼와 종료 배출 계약이 없다. */
fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("--cluster-env"))) { "검증 노드 사용법: --cluster-env" }
    val config = developmentClusterSettings()
    val collector = FundingVerificationCollector()
    try {
        DevelopmentFundingNode.start(config, collector::decorate).use { node ->
            val observationsDrained = CountDownLatch(1)
            val drainSucceeded = AtomicBoolean()
            val shutdown =
                Thread({
                    node.close()
                    val drained = observationsDrained.await(5_000, TimeUnit.MILLISECONDS) && drainSucceeded.get()
                    if (!drained) {
                        fundingEvent(
                            mapOf(
                                "kind" to "ERROR",
                                "runId" to config.runId,
                                "memberId" to config.memberId,
                                "failure" to "종료 시 남은 관찰 배출을 확인하지 못했습니다",
                            ),
                        )
                    }
                    fundingEvent(
                        mapOf(
                            "kind" to "STOPPED",
                            "runId" to config.runId,
                            "memberId" to config.memberId,
                            "closed" to node.isClosed,
                            "failure" to
                                (node.error?.toString() ?: collector.verificationFailure ?: if (drained) null else "관찰 배출 미완료"),
                            "observationDrained" to drained,
                        ),
                    )
                }, "funding-verification-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                fundingEvent(fundingVerificationReadyFields(config, node, collector))
                do {
                    collector.drainFields(config).forEach(::fundingEvent)
                    collector.verificationFailure?.let { error(it) }
                } while (!node.awaitClosed(100))
                node.error?.let { throw IllegalStateException("개발 노드가 오류로 중단됐습니다", it) }
            } finally {
                try {
                    node.close()
                    check(node.awaitClosed(5_000)) { "관찰 생산자의 종료를 확인하지 못했습니다" }
                    collector.drainFields(config).forEach(::fundingEvent)
                    drainSucceeded.set(true)
                } finally {
                    observationsDrained.countDown()
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdown)
                    } catch (_: IllegalStateException) {
                        // JVM 종료 중에도 main만 최종 관찰을 소비하고 hook은 완료를 기다린다.
                    }
                }
            }
            collector.verificationFailure?.let { error(it) }
        }
    } catch (error: Throwable) {
        fundingEvent(
            mapOf(
                "kind" to "ERROR",
                "runId" to config.runId,
                "memberId" to config.memberId,
                "failure" to error.toString(),
            ),
        )
        throw error
    }
}

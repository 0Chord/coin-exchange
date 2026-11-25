package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** 실제 callback을 테스트 decorator에서 보존한다. 제품의 최신 관찰 슬롯을 큐로 바꾸지 않는다. */
private data class NodeObservations(
    val collector: FundingVerificationCollector,
    val values: MutableList<FundingObservation> = mutableListOf(),
)

private val observedNodes = WeakHashMap<DevelopmentFundingNode, NodeObservations>()

internal fun startObservedTradingNode(config: DevelopmentFundingSettings): DevelopmentFundingNode {
    val collector = FundingVerificationCollector(128)
    val node = DevelopmentFundingNode.start(config, collector::decorate)
    observedNodes[node] = NodeObservations(collector)
    return node
}

internal fun capturedTradingObservations(node: DevelopmentFundingNode): List<FundingObservation> {
    val source = checkNotNull(observedNodes[node]) { "테스트 관찰 decorator로 시작한 노드가 아닙니다" }
    source.values.addAll(source.collector.drainObservations())
    check(source.collector.verificationFailure == null) { source.collector.verificationFailure!! }
    return source.values.toList()
}

internal fun lastExternalObservation(node: DevelopmentFundingNode): FundingObservation? =
    capturedTradingObservations(node).lastOrNull { it.result != null || it.admissionResult != null }

internal fun awaitExternalObservation(
    node: DevelopmentFundingNode,
    after: Long,
): FundingObservation {
    val start = System.nanoTime()
    while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
        check(node.error == null) { "노드 오류: ${node.error}" }
        capturedTradingObservations(node)
            .firstOrNull {
                it.position > after && (it.result != null || it.admissionResult != null)
            }?.let { return it }
        Thread.sleep(1)
    }
    error("새 외부 업무 callback 관찰이 없습니다")
}

internal fun awaitAutomaticCompletion(
    node: DevelopmentFundingNode,
    result: AdmissionResultPayload,
) {
    if (result !is AdmissionResultPayload.Accepted) return
    val start = System.nanoTime()
    while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
        check(node.error == null) { "자동 실행 중 노드 오류: ${node.error}" }
        val completion = capturedTradingObservations(node).lastOrNull { it.executionSequence == result.sequence }
        if (completion != null) {
            check(completion.snapshot!!.pendingOrders.none { it.sequence == result.sequence }) { "완료 후에도 처리 대기가 남았습니다" }
            check(completion.snapshot.completions.count { it.admission.sequence == result.sequence } == 1) { "전체 완료가 없거나 중복됐습니다" }
            return
        }
        Thread.sleep(1)
    }
    error("승인 ${result.sequence} 자동 실행을 확인하지 못했습니다")
}

internal fun originalApprovalRecords(snapshot: TradingState.Snapshot): List<OrderAdmissionState.Record> =
    snapshot.requests.mapNotNull { (it.result as? OrderAdmissionState.Result.Accepted)?.record }.distinctBy { it.sequence }

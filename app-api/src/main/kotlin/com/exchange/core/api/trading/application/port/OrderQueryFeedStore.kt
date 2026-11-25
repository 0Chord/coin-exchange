package com.exchange.core.api.trading.application.port

import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerReconciliationReport
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.ReservationHold
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsLedgerProgress
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingMarketContext

internal const val MAX_LEDGER_PAGE_ENTRIES = 32

/** 소스가 보존한 범위다. genesis 증명 없이 빈 DB를 과거 전체 조회로 활성화하지 않는다. */
data class OrderQuerySourceBounds(
    val issued: Long,
    val retiredThrough: Long,
    val completeFromGenesis: Boolean,
) {
    init {
        require(retiredThrough >= 0 && issued >= retiredThrough)
    }
}

data class OrderQueryFeedProgress(
    val consumerId: String,
    val admissionThrough: Long,
    val matchingThrough: Map<String, Long>,
    val financialThrough: Map<String, Long>,
    val lifecycle: OrderQueryLifecycle,
    val appliedRevision: Long,
    val settlementThrough: Map<String, Long> = emptyMap(),
)

/** 같은 WAS의 확정 사실 수집 전용이며 사용자 HTTP에 노출하지 않는다. */
interface OrderQueryFeedStore {
    fun initialize(
        consumerId: String,
        streams: Map<String, MatchingResultStream>,
        contexts: Map<String, TradingMarketContext>,
        sourceIdentity: String? = null,
        settlementEnabled: Boolean = false,
    )

    fun progress(): OrderQueryFeedProgress

    /** 초기 전체 수집이 끝나야 READY다. 이미 READY인 범위의 backlog는 LAGGING으로 표현한다. */
    fun verifySources(
        admission: OrderQuerySourceBounds,
        matching: Map<String, OrderQuerySourceBounds>,
        financial: Map<String, Long> = emptyMap(),
        expectedBeforeRead: OrderQueryFeedProgress? = null,
    ): OrderQueryFeedProgress

    /** canonical 원문 중복을 먼저 판정한 뒤 행·revision·cursor와 함께 커밋한다. */
    fun applyAdmission(
        fact: FundsOrderDispatch,
        canonicalPayload: ByteArray,
    ): Long

    fun applyMatching(
        fact: MatchingResultEnvelope,
        canonicalPayload: ByteArray,
    ): Long

    /** 기존 미정산 결과는 한 순번씩 반영한다. 매칭보다 앞선 watermark는 원문 없이 보존할 수 있다. */
    fun applyFinancial(watermark: MatchingFundsSettledAck): Long

    /** 정산 분개와 마켓별 저장 위치를 한 트랜잭션에 저장하고 해당 결과 순번을 돌려준다. */
    fun applySettlement(fact: SettlementFact): Long

    fun ledgerThrough(): Long

    fun verifyLedgerPrefix(through: Long)

    /** 연속된 분개 묶음을 한 트랜잭션으로 검증·저장하고 커밋 뒤 마지막 순번을 반환한다. */
    fun applyLedgerPage(batches: List<FundsLedgerBatch>): Long

    fun reconcileLedger(
        scope: LedgerReconciliationScope,
        logPosition: Long,
        ledger: FundsLedgerProgress,
        balances: List<Balance>,
        reservations: List<ReservationHold>,
        onReadProgress: () -> Unit = {},
    ): LedgerReconciliationReport

    fun suspendProjection()

    fun block(reason: String)
}

class OrderQueryLedgerException(
    val reason: OrderQueryRecoveryFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 접수 원문을 먼저 가져온 뒤 같은 매칭 원문을 다시 적용한다. 조회 주문을 추정해 만들지 않는다. */
class OrderQueryOriginsPendingException(
    val admissionSequence: Long,
) : RuntimeException("matching result requires admission $admissionSequence")

package com.exchange.core.ledger

/**
 * 검증된 원장 거래와 항목을 추가하는 영속성 포트.
 * 기존 기록을 수정하거나 삭제하지 않으며, 사용자 잔고 변경은 담당하지 않는다.
 */
interface LedgerTransactionStore {
    /**
     * 거래 정보와 모든 항목을 하나의 DB 트랜잭션으로 저장한다.
     *
     * 구현체는 저장 중 실패하면 일부 기록만 남기지 않아야 하며, 원장 거래 식별자나
     * 원본 이벤트 식별자가 이미 저장되어 있으면 중복 추가를 거절해야 한다.
     *
     * @param transaction 자산별 차변·대변 균형 검증을 마친 원장 거래
     */
    fun append(transaction: LedgerTransaction)
}

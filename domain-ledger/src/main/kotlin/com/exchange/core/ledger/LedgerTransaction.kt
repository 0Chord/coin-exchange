package com.exchange.core.ledger

import java.math.BigInteger
import java.time.Instant
import java.util.Collections

/**
 * 하나의 회계 사건에 속한 원장 항목을 묶고 자산별 차변·대변 균형을 검증하는 거래.
 *
 * 항목은 하나 이상이어야 하며, 같은 자산의 차변 합계와 대변 합계가 같아야 한다.
 * KRW의 차이를 BTC로 상쇄할 수는 없다. 합산에는 [BigInteger]를 사용해 여러 항목의
 * 합계가 Long 범위를 넘어도 정확하게 비교한다.
 *
 * 생성 시 항목 목록을 복사하고 외부 수정을 막아 검증한 내용을 유지한다.
 * 이 객체 자체는 DB 저장, 잔고 변경 또는 원본 이벤트의 중복 처리를 수행하지 않는다.
 *
 * @property ledgerTransactionId 이 원장 거래를 식별하는 값
 * @property sourceEventId 이 거래를 발생시킨 주문 예약·체결 등 원본 이벤트의 식별자
 * @property transactionType 예약, 해제, 정산 또는 역분개 등 회계 사건의 종류
 * @property occurredAt 원본 회계 사건이 발생한 시각. DB에 저장한 시각과는 다르다
 * @param postings 이 거래에 포함할 원장 항목 목록
 * @throws IllegalArgumentException 항목이 비어 있거나 자산별 차변·대변 합계가 다른 경우
 */
class LedgerTransaction(
    val ledgerTransactionId: String,
    val sourceEventId: String,
    val transactionType: LedgerTransactionType,
    val occurredAt: Instant,
    postings: List<LedgerPosting>,
) {
    /** 입력 목록과 분리하여 보관하는, 외부에서 수정할 수 없는 원장 항목 목록. */
    val postings: List<LedgerPosting> = Collections.unmodifiableList(postings.toList())

    init {
        require(this.postings.isNotEmpty()) {
            "ledger transaction postings must not be empty"
        }

        val postingsByAsset = this.postings.groupBy { posting -> posting.assetId }

        for ((assetId, assetPostings) in postingsByAsset) {
            var debitTotal = BigInteger.ZERO
            var creditTotal = BigInteger.ZERO

            for (posting in assetPostings) {
                val amount = BigInteger.valueOf(posting.amount.value)

                when (posting.side) {
                    LedgerPostingSide.DEBIT -> debitTotal = debitTotal.add(amount)
                    LedgerPostingSide.CREDIT -> creditTotal = creditTotal.add(amount)
                }
            }

            require(debitTotal == creditTotal) {
                "ledger transaction must balance for asset ${assetId.value}"
            }
        }
    }
}

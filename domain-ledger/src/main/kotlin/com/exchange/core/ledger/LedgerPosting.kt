package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId

/**
 * 한 계정에 기록할 자산, 차변·대변 방향과 금액을 표현하는 원장 항목.
 *
 * 객체를 생성해도 DB나 잔고는 변경되지 않는다. 여러 항목의 자산별 차변·대변 균형은
 * 별도의 원장 거래 단위에서 검증해야 한다. 금액이 0인 항목은 만들지 않는다.
 *
 * @property accountId 사용자 잔고 계정 또는 거래소 수익 계정 등을 식별하는 문자열. 사용자 ID와는 다르다
 * @property assetId 기록할 자산
 * @property side 원장 기록의 차변 또는 대변 구분
 * @property amount 해당 자산의 최소 단위 기준 양수 금액
 * @throws IllegalArgumentException 계정 식별자가 비어 있거나 공백뿐인 경우, 또는 금액이 0 이하인 경우
 */
data class LedgerPosting(
    val accountId: String,
    val assetId: AssetId,
    val side: LedgerPostingSide,
    val amount: Amount,
) {
    init {
        require(accountId.isNotBlank()) {
            "ledger posting accountId must not be blank"
        }

        require(amount.value > 0) {
            "ledger posting amount must be positive"
        }
    }
}

package com.exchange.core.matching

/** 고정된 이벤트 예산 안에서 완결 후보를 만들었는지 나타낸다. 실패에는 적용할 후보가 없다. */
sealed class BoundedSubmitPreparation {
    data class Prepared(
        val plan: SubmitOrderPlan,
    ) : BoundedSubmitPreparation()

    data object TooLarge : BoundedSubmitPreparation()

    data object SequenceExhausted : BoundedSubmitPreparation()
}

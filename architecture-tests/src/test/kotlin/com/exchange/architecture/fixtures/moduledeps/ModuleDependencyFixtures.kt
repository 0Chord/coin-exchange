package com.exchange.architecture.fixtures.moduledeps

// 같은 패키지의 예제를 서로 다른 임시 모듈 출력에 배치해 패키지 추측을 막는다.
class FeeValue
class OrderValue(val fee: FeeValue)
class MatchingUsesOrder(val order: OrderValue)
class MatchingUsesFee(val fee: FeeValue)

class CommonMarker
class FeeMarker
class OrderMarker
class LedgerMarker
class MatchingMarker
class ApiMarker

interface FeeReadingPort { fun read(): FeeValue }
class FeeReadingExecutor { fun execute(): FeeValue = FeeValue() }
class GeneratedAction {
    fun task(): Runnable = object : Runnable {
        override fun run() { FeeValue() }
    }
}
open class FeeBase
annotation class FeeAnnotation
@FeeAnnotation
class ReferenceShapes(val fee: FeeValue, val fees: Array<FeeValue>, val generic: List<FeeValue>) : FeeBase() {
    fun accept(value: FeeValue): FeeValue = value
}
class ExternalOnly(val timestamp: java.time.Instant)

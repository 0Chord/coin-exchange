package com.exchange.core.api.matching

import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce

/**
 * 주문 접수 요청 body.
 *
 * @property orderId 클라이언트가 부여한 주문 식별자
 * @property userId 주문 소유자
 * @property side BUY 또는 SELL 방향
 * @property orderType 지정가 또는 시장가 방식
 * @property timeInForce 미체결 잔량 처리 방식
 * @property price base 자산 1단위의 quote 자산 가격
 * @property quantity base 자산의 최소 단위 기준 주문 수량
 */
data class SubmitOrderRequest(
    val orderId: String,
    val userId: String,
    val side: Side,
    val orderType: OrderType,
    val timeInForce: TimeInForce,
    val price: Long,
    val quantity: Long,
)

/**
 * matching API 공통 응답.
 *
 * command 하나가 여러 event를 만들 수 있으므로 list로 반환한다.
 *
 * @property events 엔진이 발생시킨 순서대로 변환된 event 목록
 */
data class MatchingResponse(
    val events: List<MatchingEventResponse>,
)

/**
 * MatchingEvent를 HTTP 응답으로 내보내기 위한 납작한 DTO.
 *
 * event 종류마다 쓰는 필드가 달라서, 사용하지 않는 값은 null로 둔다.
 * 클라이언트는 먼저 [type]을 확인한 뒤 해당 event에 필요한 nullable 필드만 읽어야 한다.
 *
 * @property type `TRADE_EXECUTED`, `ORDER_ENTERED_BOOK`, `ORDER_CANCELLED`,
 * `ORDER_CANCEL_REJECTED` 중 하나
 * @property marketId event가 발생한 마켓
 * @property engineSequence 마켓 안에서 단조 증가하는 event 순번
 * @property tradeId 향후 거래 원장을 연결하기 위해 예약된 값. 현재는 항상 `null`
 * @property makerOrderId 체결 event의 기존 book 주문
 * @property takerOrderId 체결 event를 일으킨 새 주문
 * @property orderId book 진입 또는 취소 event의 대상 주문
 * @property userId book 진입 또는 취소 event의 사용자
 * @property side 체결에서는 taker 방향, book 진입에서는 대기 주문 방향
 * @property price 체결 가격 또는 book 대기 가격
 * @property quantity 체결된 base 자산 최소 단위 수량
 * @property remainingQuantity book에 들어가거나 취소된 미체결 잔량
 * @property reason 취소 거절 이유
 */
data class MatchingEventResponse(
    val type: String,
    val marketId: String,
    val engineSequence: Long,
    val tradeId: String? = null,
    val makerOrderId: String? = null,
    val takerOrderId: String? = null,
    val orderId: String? = null,
    val userId: String? = null,
    val side: String? = null,
    val price: Long? = null,
    val quantity: Long? = null,
    val remainingQuantity: Long? = null,
    val reason: String? = null,
)

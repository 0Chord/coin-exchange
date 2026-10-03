package com.exchange.core.api.order.application

import com.exchange.core.common.OrderId
import com.exchange.core.fee.LiquidityRole
import com.exchange.core.ledger.BalanceNotFoundException
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.InsufficientHoldException
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderFillSettlementPlan
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStore
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 확정된 한 체결을 maker·taker 예약, 잔고와 원장에 반영한다.
 *
 * Spring Bean을 통해 호출하면 이 변경들은 한 체결의 트랜잭션에 참여하며,
 * 뒤쪽 잔고 지급 실패 시 먼저 저장한 원장과 양쪽 주문의 변경도 함께 롤백된다.
 *
 * 실행 순서와 회계 배경은 저장소 engineering/flow-and-scope-contract.md의
 * ‘정산 순서와 원장 분개’에서 설명한다.
 */
open class TradeSettlementService(
    private val calculator: OrderFillSettlementCalculator,
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
    private val ledgerTransactionStore: LedgerTransactionStore,
) {
    /**
     * 한 체결의 원장 기록과 maker·taker 양쪽 예약 및 잔고 변경을 원자적으로 실행한다.
     *
     * [TradeExecuted.side]는 taker 방향이며 maker 예약은 반대 방향이어야 한다.
     * 양쪽 분개를 합친 자산별 차변·대변 균형을 검증한 뒤 원장과 예약·잔고를 반영한다.
     *
     * 원장의 `sourceEventId`는 마켓 ID와 엔진 순번으로 구성한다. [TradeExecuted]에 발생
     * 시각이 없으므로 `occurredAt`에는 매칭 시각이 아닌 현재 정산 처리 시각을 기록한다.
     *
     * @param market 체결이 발생한 마켓의 자산과 수량 scale 정보
     * @param trade 매칭 엔진이 확정한 maker/taker 주문, 체결 가격과 수량
     * @throws IllegalArgumentException 마켓, 주문 소유자 또는 주문 방향이 체결 정보와 다른 경우
     * @throws OrderReservationNotFoundException maker 또는 taker 주문 예약을 찾을 수 없는 경우
     * @throws BalanceNotFoundException 지급 또는 소비할 잔고가 없는 경우
     * @throws InsufficientHoldException 체결에 소비할 hold가 부족한 경우
     */
    @Transactional
    open fun settle(
        market: MarketDefinition,
        trade: TradeExecuted,
    ) {
        require(market.marketId == trade.marketId) {
            "trade market must match settlement market"
        }

        val makerReservation =
            findReservationForUpdate(
                market = market,
                orderId = trade.makerOrderId,
            )

        val takerReservation =
            findReservationForUpdate(
                market = market,
                orderId = trade.takerOrderId,
            )

        require(makerReservation.userId == trade.makerUserId) {
            "maker reservation owner must match trade maker"
        }

        require(takerReservation.userId == trade.takerUserId) {
            "taker reservation owner must match trade taker"
        }

        require(takerReservation.side == trade.side) {
            "taker reservation side must match trade side"
        }

        require(makerReservation.side != trade.side) {
            "maker and taker reservations must have opposite sides"
        }

        val makerPlan =
            calculator.calculate(
                market = market,
                reservation = makerReservation,
                executionPrice = trade.price,
                filledQuantity = trade.quantity,
                liquidityRole = LiquidityRole.MAKER,
            )

        val takerPlan =
            calculator.calculate(
                market = market,
                reservation = takerReservation,
                executionPrice = trade.price,
                filledQuantity = trade.quantity,
                liquidityRole = LiquidityRole.TAKER,
            )

        val makerPostings =
            createSettlementPostings(
                reservation = makerReservation,
                plan = makerPlan,
            )

        val takerPostings =
            createSettlementPostings(
                reservation = takerReservation,
                plan = takerPlan,
            )

        val ledgerTransaction =
            LedgerTransaction(
                ledgerTransactionId = UUID.randomUUID().toString(),
                sourceEventId = "MATCHING:${trade.marketId.value}:${trade.engineSequence}",
                transactionType = LedgerTransactionType.SETTLEMENT,
                occurredAt = Instant.now(),
                postings = makerPostings + takerPostings,
            )

        ledgerTransactionStore.append(ledgerTransaction)

        applySettlement(
            reservation = makerReservation,
            plan = makerPlan,
        )

        applySettlement(
            reservation = takerReservation,
            plan = takerPlan,
        )
    }

    /**
     * 갱신 중 다른 체결이나 취소가 같은 예약을 변경하지 못하도록 row lock과 함께
     * 조회한다.
     *
     * @param market 주문 예약을 찾을 마켓
     * @param orderId 잠금 조회할 주문 식별자
     * @return 잠금이 적용된 주문 예약
     * @throws OrderReservationNotFoundException 주문 예약이 존재하지 않는 경우
     */
    private fun findReservationForUpdate(
        market: MarketDefinition,
        orderId: OrderId,
    ): OrderReservation =
        reservationStore.findForUpdate(
            marketId = market.marketId,
            orderId = orderId,
        ) ?: throw OrderReservationNotFoundException(
            marketId = market.marketId,
            orderId = orderId,
        )

    /**
     * 한 주문의 정산 계획을 주문 예약과 사용자 Balance에 적용한다.
     *
     * BUY의 hold 소비액에는 실제 수수료가 포함되어 있고 SELL의 지급액은 수수료 차감 후
     * 금액이므로 여기서 수수료를 다시 차감하지 않는다.
     *
     * @param reservation 체결 직전 주문 예약
     * @param plan [calculator]가 계산한 주문별 정산 계획
     */
    private fun applySettlement(
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ) {
        reservationStore.update(plan.updatedReservation)

        balanceStore.consumeHold(
            userId = reservation.userId,
            assetId = reservation.assetId,
            amount = plan.holdAmountToConsume,
        )

        if (!plan.holdAmountToRelease.isZero()) {
            balanceStore.release(
                userId = reservation.userId,
                assetId = reservation.assetId,
                amount = plan.holdAmountToRelease,
            )
        }

        balanceStore.credit(
            userId = reservation.userId,
            assetId = plan.creditAssetId,
            amount = plan.creditAmount,
        )
    }

    /**
     * 정산 계획을 사용자·수수료 수익 계정의 분개로 변환하며 잔고를 추가 변경하지 않는다.
     *
     * 금액이 0인 분개는 제외한다. 자산별 균형은 양쪽 주문의 분개를 합친 [LedgerTransaction]에서
     * 검증하며, 한 주문의 분개만으로 균형이 맞는 것은 아니다.
     *
     * @param reservation 사용자 ID와 예약 자산을 담은 체결 전 주문 예약
     * @param plan hold 소비액·반환액, 지급 자산·금액과 실제 수수료를 담은 정산 계획
     * @return DB 저장이나 잔고 변경 없이 생성한 해당 주문의 분개 목록
     */
    private fun createSettlementPostings(
        reservation: OrderReservation,
        plan: OrderFillSettlementPlan,
    ): List<LedgerPosting> {
        val postings = mutableListOf<LedgerPosting>()
        val userId = reservation.userId.value
        val reservedAssetId = reservation.assetId
        val holdAccountId = "USER:$userId:${reservedAssetId.value}:HOLD"

        if (!plan.holdAmountToConsume.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = holdAccountId,
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.DEBIT,
                    amount = plan.holdAmountToConsume,
                ),
            )
        }

        if (!plan.holdAmountToRelease.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = holdAccountId,
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.DEBIT,
                    amount = plan.holdAmountToRelease,
                ),
            )

            postings.add(
                LedgerPosting(
                    accountId = "USER:$userId:${reservedAssetId.value}:AVAILABLE",
                    assetId = reservedAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.holdAmountToRelease,
                ),
            )
        }

        if (!plan.creditAmount.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = "USER:$userId:${plan.creditAssetId.value}:AVAILABLE",
                    assetId = plan.creditAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.creditAmount,
                ),
            )
        }

        if (!plan.actualFeeAmount.isZero()) {
            postings.add(
                LedgerPosting(
                    accountId = "SYSTEM:${plan.feeAssetId.value}:FEE_REVENUE",
                    assetId = plan.feeAssetId,
                    side = LedgerPostingSide.CREDIT,
                    amount = plan.actualFeeAmount,
                ),
            )
        }

        return postings
    }
}

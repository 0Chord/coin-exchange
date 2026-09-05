package com.exchange.core.api.order

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
 * 매칭 엔진이 만든 한 체결을 양쪽 주문 예약, 잔고와 원장에 반영하는 서비스.
 *
 * 양쪽 [OrderReservation]을 잠금 조회한 뒤 [OrderFillSettlementCalculator]로 각 주문의
 * 예약 감소액, hold 소비·반환액, 지급액과 실제 수수료를 계산한다. 양쪽 분개를 하나의
 * [LedgerTransaction]으로 저장하고 maker, taker 순서로 예약과 잔고를 변경한다.
 * Spring Bean을 통해 호출하면 원장 저장과 양쪽 정산이 같은 트랜잭션에 참여하므로
 * 뒤쪽 잔고 지급이 실패해도 먼저 저장한 원장과 앞쪽 주문의 변경까지 함께 롤백된다.
 *
 * 이 서비스는 주문을 매칭하거나 체결 가격을 결정하지 않는다. 이미 확정된 [TradeExecuted]
 * 결과를 영속 장부에 반영하는 역할만 담당한다.
 *
 * @property calculator 주문 방향별 정산 금액을 계산하는 도메인 계산기
 * @property balanceStore 체결에 따른 hold 소비·반환과 반대편 자산 지급 포트
 * @property reservationStore maker와 taker의 주문별 예약을 잠금·갱신하는 포트
 * @property ledgerTransactionStore 체결의 자산 이동과 거래소 수수료 수익을 함께 기록하는 포트
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
     * [TradeExecuted.side]는 taker 주문의 방향이다. taker 예약은 같은 방향이고 maker 예약은
     * 반대 방향인지 확인한다. 두 정산 계획의 분개를 합쳐 자산별 차변·대변 합계가 같은지
     * 검증한 뒤 원장을 먼저 저장하고 양쪽 예약·잔고를 갱신한다.
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
     * 주문 예약을 저장하고, 예약 자산 hold에서 실제 사용액을 소비한다. BUY의 가격 개선액과
     * 남은 수수료 예약금처럼 사용하지 않은 금액은 available로 반환하고, 마지막으로 체결로
     * 받은 자산을 available에 지급한다. BUY의 소비액에는 실제 수수료가 이미 포함되어 있고,
     * SELL의 지급액은 수수료 차감 후 금액이므로 여기서 수수료를 다시 차감하지 않는다.
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
     * 한 주문의 정산 계획을 사용자 계정과 거래소 수수료 수익 계정의 분개로 변환한다.
     *
     * 거래소 관점에서 사용자 잔고는 부채이므로 hold 소비·반환은 HOLD 계정의 DEBIT,
     * 반환금·체결 자산 지급은 AVAILABLE 계정의 CREDIT으로 기록한다. 실제 수수료는
     * `SYSTEM:{자산}:FEE_REVENUE` 수익 계정에 CREDIT으로 기록하며 잔고를 추가 차감하지 않는다.
     *
     * 금액이 0인 분개는 만들지 않는다. 한 주문의 분개만으로 자산별 균형이 맞는 것은 아니며,
     * 상대 주문의 분개와 합친 뒤 [LedgerTransaction]에서 차변·대변 균형을 검증한다.
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

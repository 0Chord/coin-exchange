package com.exchange.core.api.order.application

import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStore
import com.exchange.core.order.Side
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * 주문 자금을 예약하고 잔고 이동과 RESERVE 원장을 같은 트랜잭션에 기록한다.
 * 저장 오류면 이번 호출의 예약·잔고·원장을 함께 롤백한다. 이후 매칭·정산 실패가
 * 이미 커밋한 예약까지 되돌린다는 뜻은 아니다.
 *
 * @property calculator 방향·마켓·수수료 정책으로 예약액을 검증하는 도메인 계산기
 * @property balanceStore 사용자 잔고의 조건부 변경 포트
 * @property reservationStore 주문별 예약 저장 포트
 * @property ledgerTransactionStore 검증된 자금 이동을 기록하는 원장 저장 포트
 */
open class OrderFundingService(
    private val calculator: OrderReservationCalculator,
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
    private val ledgerTransactionStore: LedgerTransactionStore,
) {
    /**
     * 주문 하나의 필요 자금을 계산하고 예약·잔고 hold·RESERVE를 함께 저장한다.
     *
     * BUY는 quote 자산에서 지정가 기준 거래 대금과 최대 수수료를 함께 예약한다.
     * SELL은 base 자산을 주문 수량만큼 예약하고 수수료는 이후 체결 대금에서 차감한다.
     * 독립 Bean 호출이 커밋된 뒤에만 매칭을 시작한다. 외부 트랜잭션에 참여하면
     * 커밋 시점은 외부 경계다. 동일 주문은 성공으로 재반환하지 않고 중복 오류로 거절한다.
     *
     * @param market base/quote 자산과 base 수량 scale 정보
     * @param orderId 자금을 예약할 주문 식별자
     * @param userId 주문 소유자이자 Balance 소유자
     * @param side BUY 또는 SELL 방향
     * @param limitPrice 주문 지정가
     * @param quantity base 자산 최소 단위 기준 주문 수량
     * @param feePolicySnapshot 주문 접수 시점에 확정한 maker/taker 수수료 정책
     * @return 예약·잔고·원장 저장을 마친 ACTIVE 예약. 외부 트랜잭션이면 아직 커밋 전일 수 있다
     * @throws com.exchange.core.order.OrderReservationAlreadyExistsException 동일 주문 예약이
     * 이미 존재하는 경우
     * @throws com.exchange.core.ledger.BalanceNotFoundException 사용자·자산 잔고가 없는 경우
     * @throws com.exchange.core.ledger.InsufficientBalanceException available이 부족한 경우
     */
    @Transactional
    open fun reserve(
        market: MarketDefinition,
        orderId: OrderId,
        userId: UserId,
        side: Side,
        limitPrice: Price,
        quantity: Quantity,
        feePolicySnapshot: TradingFeePolicySnapshot,
    ): OrderReservation {
        // requirement는 side에 따른 hold 자산과 거래·수수료 예약액을 함께 표현한다.
        val requirement =
            calculator.calculate(
                market = market,
                side = side,
                price = limitPrice,
                quantity = quantity,
                feePolicySnapshot = feePolicySnapshot,
            )

        // reservation은 전체 Balance hold 중 이 주문이 책임지는 몫을 별도로 추적한다.
        val reservation =
            OrderReservation.create(
                marketId = market.marketId,
                orderId = orderId,
                userId = userId,
                side = side,
                limitPrice = limitPrice,
                quantity = quantity,
                requirement = requirement,
                feePolicySnapshot = feePolicySnapshot,
            )

        val reserveTransaction = reserveTransaction(reservation)
        reservationStore.create(reservation)

        // 거래 예약액과 수수료 예약액의 합을 hold한다. 이 호출이 실패하면 위 insert도
        // 같은 트랜잭션에서 rollback된다.
        balanceStore.reserve(
            userId = userId,
            assetId = requirement.assetId,
            amount = requirement.totalReserveAmount,
        )

        ledgerTransactionStore.append(reserveTransaction)

        return reservation
    }

    private fun reserveTransaction(reservation: OrderReservation): LedgerTransaction =
        LedgerTransaction(
            ledgerTransactionId = UUID.randomUUID().toString(),
            sourceEventId = reserveSourceId(reservation),
            transactionType = LedgerTransactionType.RESERVE,
            occurredAt = Instant.now(),
            postings =
                listOf(
                    LedgerPosting(
                        accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                        assetId = reservation.assetId,
                        side = LedgerPostingSide.DEBIT,
                        amount = reservation.reservedAmount,
                    ),
                    LedgerPosting(
                        accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
                        assetId = reservation.assetId,
                        side = LedgerPostingSide.CREDIT,
                        amount = reservation.reservedAmount,
                    ),
                ),
        )

    /** 문자열마다 UTF-8 바이트 길이를 붙여 콜론 포함 키를 구분하고, DB의 128자 제한 안에 저장한다. */
    private fun reserveSourceId(reservation: OrderReservation): String {
        val market = reservation.marketId.value.toByteArray(Charsets.UTF_8)
        val order = reservation.orderId.value.toByteArray(Charsets.UTF_8)
        val input =
            ByteBuffer
                .allocate(Int.SIZE_BYTES * 2 + market.size + order.size)
                .putInt(market.size)
                .put(market)
                .putInt(order.size)
                .put(order)
                .array()
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return "RESERVE:v1:${digest.joinToString("") { "%02x".format(it) }}"
    }
}

package com.exchange.core.api.order.application

import com.exchange.core.common.Amount
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionStore
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationNotFoundException
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderReservationStore
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * 취소 반환액·예약 상태·잔고 이동·RELEASE 기록을 같은 트랜잭션에서 저장한다.
 * 예약 잠금으로 체결·반복 해제를 직렬화하며, 완료 기록이 모순되면 임의로 보정하지 않는다.
 * 주문장 취소와 이벤트 저장은 앞선 별도 경계이므로 이 트랜잭션의 롤백 대상이 아니다.
 *
 * @property balanceStore 실제 hold를 available로 이동하는 포트
 * @property reservationStore 주문별 예약을 잠금·갱신하는 포트
 * @property ledgerTransactionStore 완료 근거를 읽고 반환 원장을 추가하는 포트
 */
open class OrderReservationReleaseService(
    private val balanceStore: BalanceStore,
    private val reservationStore: OrderReservationStore,
    private val ledgerTransactionStore: LedgerTransactionStore,
) {
    /**
     * ACTIVE 예약의 잔액을 한 번 반환한다. RELEASED는 금액·계정·자산·방향까지 대조한다.
     * 외부 트랜잭션이 있으면 참여하므로 메서드 반환이 커밋을 의미하지 않는다.
     *
     * @return 최초 해제 또는 정확한 완료 근거가 있는 원본 예약
     * @throws OrderReservationNotFoundException 예약이 없는 경우
     * @throws IllegalStateException 전량 체결된 예약이거나 완료 근거가 없거나 모순인 경우
     */
    @Transactional
    open fun release(
        marketId: MarketId,
        orderId: OrderId,
    ): OrderReservation {
        val reservation =
            reservationStore.findForUpdate(marketId, orderId)
                ?: throw OrderReservationNotFoundException(marketId, orderId)
        val sourceId = releaseSourceId(reservation)
        val existing = ledgerTransactionStore.findBySourceEventId(sourceId)

        if (reservation.status == OrderReservationStatus.RELEASED) {
            val amount = checkNotNull(reservation.releasedAmount) { "released amount is unknown: $sourceId" }
            checkNotNull(existing) { "release ledger is missing: $sourceId" }
            check(existing.sourceEventId == sourceId && existing.transactionType == LedgerTransactionType.RELEASE) {
                "release ledger source or type differs: $sourceId"
            }
            // 합계 균형만으로는 다른 계정·금액의 기록을 완료로 볼 수 없다. 행 순서는 의미가 없다.
            check(existing.postings.size == 2 && existing.postings.toSet() == releasePostings(reservation, amount).toSet()) {
                "release ledger account, asset, side or amount differs: $sourceId"
            }
            return reservation
        }

        check(existing == null) { "release ledger exists for ${reservation.status} reservation: $sourceId" }
        val released = reservation.release()
        val amount = checkNotNull(released.releasedAmount)
        reservationStore.update(released)
        balanceStore.release(reservation.userId, reservation.assetId, amount)
        // UNIQUE 충돌도 오류로 전달한다. 앞선 예약 갱신·잔고 이동과 부분 원장을 함께 롤백한다.
        ledgerTransactionStore.append(
            LedgerTransaction(
                ledgerTransactionId = UUID.randomUUID().toString(),
                sourceEventId = sourceId,
                transactionType = LedgerTransactionType.RELEASE,
                occurredAt = Instant.now(),
                postings = releasePostings(reservation, amount),
            ),
        )
        return released
    }

    private fun releasePostings(
        reservation: OrderReservation,
        amount: Amount,
    ): List<LedgerPosting> =
        listOf(
            LedgerPosting(
                accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:HOLD",
                assetId = reservation.assetId,
                side = LedgerPostingSide.DEBIT,
                amount = amount,
            ),
            LedgerPosting(
                accountId = "USER:${reservation.userId.value}:${reservation.assetId.value}:AVAILABLE",
                assetId = reservation.assetId,
                side = LedgerPostingSide.CREDIT,
                amount = amount,
            ),
        )

    /** 원본 UTF-8 길이를 붙여 콜론 포함 식별자를 구분하고, 기존 컬럼의 길이 제한 안에 저장한다. */
    private fun releaseSourceId(reservation: OrderReservation): String {
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
        return "RELEASE:v1:${digest.joinToString("") { "%02x".format(it) }}"
    }
}

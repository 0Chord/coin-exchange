package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 실제 도메인으로 순차 계산과 실패 후 입력 보존을 대조하며, 저장이나 실제 상태 적용은 하지 않는다. */
class OrderSettlementContractTest {
    private val btc = AssetId("BTC")
    private val usdt = AssetId("USDT")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 0)
    private val time = Instant.parse("2026-10-25T01:02:03Z")
    private val free = policy(0, 0)
    private val calculator =
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator()),
            OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator())),
            TradingFeeReserveCalculator(),
            SettlementLedgerTransactions(),
        )

    @Test
    fun `최초 수량이 그대로여도 수수료 나머지가 있으면 최초 예약액을 그대로 재사용하지 않는다`() {
        val original = fixture()
        val invalid =
            original.copy(
                reservations =
                    original.reservations.map {
                        if (it.side == Side.BUY) it.copy(feeRemainder = FeeRemainder(1)) else it
                    },
            )

        assertInvalid(invalid)
    }

    @Test
    fun `최초 예약 요구액 재사용은 한 계산 안에서만 하고 다음 입력도 완전히 검증한다`() {
        val f = fixture()
        val first = calculate(f)
        val buyer = f.reservations.first()
        val seller = f.reservations.last()

        // 현재 잔량과 예약은 맞아도 최초 예약 금액만 손상되면 거절한다.
        assertInvalid(f.copy(reservations = listOf(buyer.copy(reservedAmount = Amount(301))) + f.reservations.drop(1)))
        assertInvalid(f.copy(reservations = f.reservations.dropLast(1) + seller.copy(reservedAmount = Amount(2))))
        assertInvalid(f.copy(reservations = listOf(buyer.copy(remainingAmount = Amount(299))) + f.reservations.drop(1)))
        assertFailsWith<IllegalArgumentException> {
            calculator.validateReservation(market, buyer.copy(reservedAmount = Amount(301)))
        }

        assertBalance(first, "buyer", usdt, available = 715, hold = 100)
        assertInputsUnchanged(f)
        val repeated = calculate(f)
        assertEquals(first.balanceChanges, repeated.balanceChanges)
        assertEquals(first.reservationChanges, repeated.reservationChanges)
    }

    @Test
    fun `initial reservation failure remains an argument error before any input mutation for single and multiple trades`() {
        for (tradeCount in 1..2) {
            val source = fixture()
            val invalid =
                source.copy(
                    executions = source.executions.take(tradeCount),
                    reservations =
                        source.reservations.map {
                            if (it.orderId == OrderId("buy")) it.copy(reservedAmount = Amount(301)) else it
                        },
                )
            val failure = assertFailsWith<IllegalArgumentException> { calculate(invalid) }
            assertTrue(failure is InitialReservationValidationFailure)
            assertEquals("initial settlement reservation amount mismatch", failure.message)
            assertEquals(source.balances, invalid.balances)
            assertEquals(Amount(301), invalid.reservations.first().reservedAmount)
            assertEquals(Amount(300), source.reservations.first().reservedAmount)
        }
    }

    @Test
    fun `같은 주문 ID도 다음 계산의 정책과 최초 예약 요구액을 새로 계산한다`() {
        val freeFixture = fixture()
        calculate(freeFixture)
        val paid = policy(10_000, 20_000)
        val f =
            freeFixture.copy(
                reservations =
                    freeFixture.reservations.map {
                        if (it.side == Side.BUY) {
                            it.copy(
                                reservedAmount = Amount(306),
                                remainingAmount = Amount(306),
                                initialFeeReserveAmount = Amount(6),
                                remainingFeeReserveAmount = Amount(6),
                                feePolicySnapshot = paid,
                            )
                        } else {
                            it.copy(feePolicySnapshot = paid)
                        }
                    },
                balances =
                    freeFixture.balances.map {
                        if (it.userId == UserId("buyer") && it.assetId == usdt) it.copy(available = Amount(694), hold = Amount(306)) else it
                    },
            )

        val candidate = calculate(f)

        assertEquals(listOf(Amount(1), Amount(2)), candidate.settlements.map { it.takerPlan.actualFeeAmount })
        assertEquals(Amount(6), reservation(candidate, "buy").initialFeeReserveAmount)
        assertEquals(Amount(3), reservation(candidate, "buy").remainingFeeReserveAmount)
        assertBalance(candidate, "buyer", usdt, available = 709, hold = 103)
        assertInputsUnchanged(f)
    }

    @Test
    fun `같은 판매자의 서로 다른 주문은 지급을 합하고 예약은 각각 끝낸다`() {
        val f = fixture(sameSeller = true)

        val candidate = calculate(f)

        assertBalance(candidate, "seller-1", usdt, available = 185, hold = 0)
        assertBalance(candidate, "seller-1", btc, available = 0, hold = 0)
        assertEquals(3, candidate.reservationChanges.size)
        assertEquals(OrderReservationStatus.SETTLED, reservation(candidate, "sell-1").status)
        assertEquals(OrderReservationStatus.SETTLED, reservation(candidate, "sell-2").status)
        assertInputsUnchanged(f)
    }

    @Test
    fun `같은 사용자가 양쪽이면 지급과 소비가 겹친 잔고를 덮어쓰지 않는다`() {
        val owner = "same-user"
        val f =
            fixture().let { initial ->
                initial.copy(
                    order = initial.order.copy(userId = UserId(owner), quantity = Quantity(2)),
                    executions = initial.executions.map { it.copy(makerUserId = UserId(owner), takerUserId = UserId(owner)) },
                    reservations =
                        initial.reservations.map {
                            if (it.side == Side.BUY) {
                                reservation("buy", owner, Side.BUY, 100, 2)
                            } else {
                                it.copy(userId = UserId(owner))
                            }
                        },
                    balances = listOf(balance(owner, usdt, 800, 200), balance(owner, btc, 0, 2)),
                )
            }

        val candidate = calculate(f)

        assertBalance(candidate, owner, usdt, available = 1_000, hold = 0)
        assertBalance(candidate, owner, btc, available = 2, hold = 0)
        assertEquals(2, candidate.balanceChanges.size)
        assertTrue(candidate.reservationChanges.all { it.next.status == OrderReservationStatus.SETTLED })
        assertInputsUnchanged(f)
    }

    @Test
    fun `다른 주문에 묶인 50은 두 체결 뒤에도 남긴다`() {
        val f = fixture().withBalance("buyer", usdt) { it.copy(hold = Amount(350)) }

        val candidate = calculate(f)

        assertBalance(candidate, "buyer", usdt, available = 715, hold = 150)
        assertEquals(Amount(100), reservation(candidate, "buy").remainingAmount)
        assertInputsUnchanged(f)
    }

    @Test
    fun `처음 받는 자산의 잔고 항목이 없어도 0에서 수취 후보를 만든다`() {
        val f = fixture()

        val candidate = calculate(f)

        val change = candidate.balanceChanges.single { it.next.userId == UserId("buyer") && it.next.assetId == btc }
        assertEquals(balance("buyer", btc, 0, 0), change.previous)
        assertEquals(balance("buyer", btc, 2, 0), change.next)
        assertTrue(f.balances.none { it.userId == UserId("buyer") && it.assetId == btc })
        assertInputsUnchanged(f)
    }

    @Test
    fun `수수료 나머지는 BUY의 다음 체결로 넘기고 서로 다른 SELL 주문끼리는 합치지 않는다`() {
        val paid = policy(5_000, 10_000)
        val buyer = reservation("buy", "buyer", Side.BUY, 51, 2, paid, feeReserve = 2)
        val makers =
            listOf(reservation("sell-1", "seller", Side.SELL, 51, 1, paid), reservation("sell-2", "seller", Side.SELL, 51, 1, paid))
        val f =
            fixture().copy(
                order = order("buy", "buyer", Side.BUY, 51, 2),
                executions = listOf(execution(10, makers[0], buyer, 51), execution(11, makers[1], buyer, 51)),
                reservations = listOf(buyer) + makers,
                balances = listOf(balance("buyer", usdt, 896, 104), balance("seller", btc, 0, 2)),
            )

        val candidate = calculate(f)

        assertEquals(listOf(Amount.ZERO, Amount(1)), candidate.settlements.map { it.takerPlan.actualFeeAmount })
        assertEquals(
            listOf(FeeRemainder(510_000), FeeRemainder(20_000)),
            candidate.settlements.map {
                it.takerPlan.updatedReservation.feeRemainder
            },
        )
        assertEquals(
            listOf(Amount(2), Amount.ZERO),
            candidate.settlements.map { it.takerPlan.updatedReservation.remainingFeeReserveAmount },
        )
        assertEquals(listOf(Amount.ZERO, Amount.ZERO), candidate.settlements.map { it.makerPlan.actualFeeAmount })
        assertEquals(FeeRemainder(255_000), reservation(candidate, "sell-1").feeRemainder)
        assertEquals(FeeRemainder(255_000), reservation(candidate, "sell-2").feeRemainder)
        assertBalance(candidate, "buyer", usdt, available = 897, hold = 0)
        assertBalance(candidate, "buyer", btc, available = 2, hold = 0)
        assertBalance(candidate, "seller", usdt, available = 102, hold = 0)
        assertEquals(1L, feeRevenue(candidate))
        assertEquals(OrderReservationStatus.SETTLED, reservation(candidate, "buy").status)
        assertInputsUnchanged(f)
    }

    @Test
    fun `이전에 taker였던 BUY가 maker가 되면 저장된 나머지와 이번 maker 요율을 쓴다`() {
        val paid = policy(5_000, 10_000)
        val buyer =
            reservation("old-buy", "buyer", Side.BUY, 51, 5, paid, feeReserve = 3).copy(
                remainingQuantity = Quantity(4),
                remainingAmount = Amount(207),
                feeRemainder = FeeRemainder(510_000),
            )
        val seller = reservation("new-sell", "seller", Side.SELL, 51, 4, paid)
        val f =
            Fixture(
                order = order("new-sell", "seller", Side.SELL, 51, 4),
                executions = listOf(execution(20, buyer, seller, 51, quantity = 4)),
                reservations = listOf(buyer, seller),
                balances = listOf(balance("buyer", usdt, 742, 207), balance("seller", btc, 0, 4)),
            )

        val candidate = calculate(f)

        assertEquals(
            Amount(1),
            candidate.settlements
                .single()
                .makerPlan.actualFeeAmount,
        )
        assertEquals(
            Amount(2),
            candidate.settlements
                .single()
                .makerPlan.holdAmountToRelease,
        )
        assertEquals(FeeRemainder(530_000), reservation(candidate, "old-buy").feeRemainder)
        assertEquals(
            Amount(2),
            candidate.settlements
                .single()
                .takerPlan.actualFeeAmount,
        )
        assertEquals(FeeRemainder(40_000), reservation(candidate, "new-sell").feeRemainder)
        assertBalance(candidate, "buyer", usdt, available = 744, hold = 0)
        assertBalance(candidate, "seller", usdt, available = 202, hold = 0)
        assertEquals(3L, feeRevenue(candidate))
        assertInputsUnchanged(f)
    }

    @Test
    fun `SELL taker는 두 BUY maker 가격의 대금을 더하고 남은 base 예약을 보존한다`() {
        val seller = reservation("sell", "seller", Side.SELL, 95, 3)
        val firstBuyer = reservation("buy-1", "buyer-1", Side.BUY, 105, 1)
        val secondBuyer = reservation("buy-2", "buyer-2", Side.BUY, 100, 1)
        val f =
            Fixture(
                order = order("sell", "seller", Side.SELL, 95, 3),
                executions =
                    listOf(
                        execution(10, firstBuyer, seller, 105),
                        execution(11, secondBuyer, seller, 100),
                    ),
                reservations = listOf(seller, firstBuyer, secondBuyer),
                balances =
                    listOf(
                        balance("seller", btc, 0, 3),
                        balance("buyer-1", usdt, 895, 105),
                        balance("buyer-2", usdt, 900, 100),
                    ),
            )

        val candidate = calculate(f)

        assertBalance(candidate, "seller", usdt, available = 205, hold = 0)
        assertBalance(candidate, "seller", btc, available = 0, hold = 1)
        assertEquals(Quantity(1), reservation(candidate, "sell").remainingQuantity)
        assertEquals(Amount(1), reservation(candidate, "sell").remainingAmount)
        assertEquals(OrderReservationStatus.ACTIVE, reservation(candidate, "sell").status)
        assertBalance(candidate, "buyer-1", btc, available = 1, hold = 0)
        assertBalance(candidate, "buyer-2", btc, available = 1, hold = 0)
        assertBalance(candidate, "buyer-1", usdt, available = 895, hold = 0)
        assertBalance(candidate, "buyer-2", usdt, available = 900, hold = 0)
        assertEquals(OrderReservationStatus.SETTLED, reservation(candidate, "buy-1").status)
        assertEquals(OrderReservationStatus.SETTLED, reservation(candidate, "buy-2").status)
        assertInputsUnchanged(f)
    }

    @Test
    fun `빈 체결은 주문과 마켓만 보존하고 변경과 원장은 만들지 않는다`() {
        val f = fixture().copy(executions = emptyList())

        val candidate = calculate(f)

        assertEquals(f.order, candidate.order)
        assertEquals(market, candidate.market)
        assertTrue(candidate.settlements.isEmpty())
        assertTrue(candidate.balanceChanges.isEmpty())
        assertTrue(candidate.reservationChanges.isEmpty())
        assertInputsUnchanged(f)
    }

    @Test
    fun `public empty calculation allows unique extra inputs and still rejects unused duplicate keys`() {
        val source = fixture().copy(executions = emptyList())
        val extraReservation = source.reservations.first().copy(orderId = OrderId("unused"))
        val extraBalance = balance("unused", usdt, 123, 0)
        val extended =
            source.copy(
                reservations = source.reservations + extraReservation,
                balances = source.balances + extraBalance,
            )
        val actual = calculate(extended)
        val ownedEmpty = calculator.calculateEmpty(source.order, market)
        assertEquals(source.order, actual.order)
        assertEquals(market, actual.market)
        assertEquals(emptyList(), actual.settlements)
        assertEquals(emptyList(), actual.balanceChanges)
        assertEquals(emptyList(), actual.reservationChanges)
        assertEquals(actual.settlements, ownedEmpty.settlements)
        assertEquals(actual.balanceChanges, ownedEmpty.balanceChanges)
        assertEquals(actual.reservationChanges, ownedEmpty.reservationChanges)
        assertInputsUnchanged(extended)
        assertInvalid(extended.copy(reservations = extended.reservations + extraReservation))
        assertInvalid(extended.copy(balances = extended.balances + extraBalance))
        assertFailsWith<IllegalArgumentException> { calculator.calculateEmpty(source.order.copy(quantity = Quantity.ZERO), market) }
        assertFailsWith<IllegalArgumentException> { calculator.calculateEmpty(source.order.copy(timeInForce = TimeInForce.IOC), market) }
        assertFailsWith<IllegalArgumentException> { calculator.calculateEmpty(source.order, market.copy(marketId = MarketId("other"))) }
    }

    @Test
    fun `같은 입력은 원장 객체의 참조와 무관하게 같은 필드의 후보를 만든다`() {
        val f = fixture()

        val first = calculate(f)
        val repeated = calculate(f)

        assertEquals(first.order, repeated.order)
        assertEquals(first.market, repeated.market)
        assertEquals(first.balanceChanges, repeated.balanceChanges)
        assertEquals(first.reservationChanges, repeated.reservationChanges)
        assertEquals(first.settlements.map { it.execution }, repeated.settlements.map { it.execution })
        assertEquals(first.settlements.map { it.makerPlan }, repeated.settlements.map { it.makerPlan })
        assertEquals(first.settlements.map { it.takerPlan }, repeated.settlements.map { it.takerPlan })
        for ((a, b) in first.settlements.zip(repeated.settlements)) {
            assertEquals(a.transaction.ledgerTransactionId, b.transaction.ledgerTransactionId)
            assertEquals(a.transaction.sourceEventId, b.transaction.sourceEventId)
            assertEquals(a.transaction.transactionType, b.transaction.transactionType)
            assertEquals(a.transaction.occurredAt, b.transaction.occurredAt)
            assertEquals(a.transaction.postings, b.transaction.postings)
        }
        assertInputsUnchanged(f)
    }

    @Test
    fun `호출자 목록을 비우거나 결과 목록 수정을 시도해도 계산한 후보는 유지된다`() {
        val f = fixture()
        val executions = f.executions.toMutableList()
        val reservations = f.reservations.toMutableList()
        val balances = f.balances.toMutableList()
        val candidate = calculator.calculate(f.order, market, executions, reservations, balances)

        executions.clear()
        reservations.clear()
        balances.clear()

        assertEquals(2, candidate.settlements.size)
        assertEquals(3, candidate.reservationChanges.size)
        assertBalance(candidate, "buyer", usdt, 715, 100)
        assertFailsWith<UnsupportedOperationException> { (candidate.settlements as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (candidate.balanceChanges as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (candidate.reservationChanges as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (
                candidate.settlements
                    .first()
                    .transaction.postings as MutableList<*>
            ).clear()
        }
        assertEquals(2, candidate.settlements.size)
        assertBalance(candidate, "buyer", usdt, 715, 100)
    }

    @Test
    fun `중복 예약과 중복 잔고 키는 마지막 값으로 덮어쓰지 않고 거절한다`() {
        val f = fixture()
        assertInvalid(f.copy(reservations = f.reservations + f.reservations.first()))
        assertInvalid(f.copy(balances = f.balances + f.balances.first()))
    }

    @Test
    fun `다른 taker와 마켓과 방향이 섞인 체결은 거절한다`() {
        val f = fixture()
        val wrongExecutions =
            listOf(
                f.executions[1].copy(takerOrderId = OrderId("other-order")),
                f.executions[1].copy(takerUserId = UserId("other-user")),
                f.executions[1].copy(marketId = MarketId("SUI-USDT")),
                f.executions[1].copy(side = Side.SELL),
            )
        for (execution in wrongExecutions) {
            assertInvalid(f.copy(executions = listOf(f.executions[0], execution)))
        }
        assertInvalid(f.copy(order = f.order.copy(marketId = MarketId("SUI-USDT"))))
    }

    @Test
    fun `체결 순번은 양수이며 주어진 순서대로 연속이어야 한다`() {
        val f = fixture()
        val invalidOrders =
            listOf(
                listOf(f.executions[0].copy(engineSequence = 0)),
                listOf(f.executions[0].copy(engineSequence = -1)),
                f.executions.reversed(),
                listOf(f.executions[0], f.executions[1].copy(engineSequence = 12)),
                listOf(f.executions[0], f.executions[1].copy(engineSequence = 10)),
                listOf(f.executions[0], f.executions[0]),
            )
        for (executions in invalidOrders) assertInvalid(f.copy(executions = executions))
    }

    @Test
    fun `빈 원장 ID와 한 묶음의 중복 원장 ID는 거절한다`() {
        val f = fixture()
        assertInvalid(f.copy(executions = listOf(f.executions.first().copy(ledgerTransactionId = " "))))
        assertInvalid(
            f.copy(executions = listOf(f.executions[0], f.executions[1].copy(ledgerTransactionId = f.executions[0].ledgerTransactionId))),
        )
    }

    @Test
    fun `빠진 예약과 다른 사용자와 자산을 연결한 예약은 거절한다`() {
        val f = fixture()
        assertInvalid(f.copy(reservations = f.reservations.dropLast(1)))
        val maker = f.reservations.last()
        val wrongReservations =
            listOf(
                maker.copy(userId = UserId("not-the-maker")),
                maker.copy(marketId = MarketId("SUI-USDT")),
                maker.copy(orderId = OrderId("not-the-order")),
                maker.copy(side = Side.BUY),
                maker.copy(assetId = usdt),
                maker.copy(limitPrice = Price(94)),
                maker.release(),
            )
        for (replacement in wrongReservations) {
            assertInvalid(f.copy(reservations = f.reservations.dropLast(1) + replacement))
        }
    }

    @Test
    fun `원본과 다른 지정가 또는 최초 수량의 taker 예약은 거절한다`() {
        val f = fixture()
        assertInvalid(f.copy(order = f.order.copy(price = Price(101))))
        assertInvalid(f.copy(order = f.order.copy(quantity = Quantity(4))))
        assertInvalid(f.copy(order = f.order.copy(userId = UserId("not-owner"))))
        assertInvalid(f.copy(order = f.order.copy(side = Side.SELL)))
        assertInvalid(f.copy(order = f.order.copy(orderType = OrderType.MARKET)))
        assertInvalid(f.copy(order = f.order.copy(timeInForce = TimeInForce.IOC)))
    }

    @Test
    fun `생성자를 통과해도 BUY 거래 예약과 SELL 수량 예약의 의미가 다르면 거절한다`() {
        val f = fixture()
        val buyer = f.reservations.first()
        val seller = f.reservations.last()
        assertInvalid(f.copy(reservations = listOf(buyer.copy(remainingAmount = Amount(299))) + f.reservations.drop(1)))
        assertInvalid(
            f.copy(
                reservations =
                    f.reservations.dropLast(1) + seller.copy(reservedAmount = Amount(2), remainingAmount = Amount(2)),
            ),
        )
        val wrongFeeSeller =
            seller.copy(
                reservedAmount = Amount(2),
                remainingAmount = Amount(2),
                initialFeeReserveAmount = Amount(1),
                remainingFeeReserveAmount = Amount(1),
            )
        assertInvalid(f.copy(reservations = f.reservations.dropLast(1) + wrongFeeSeller))
        val futures = buyer.copy(feePolicySnapshot = free.copy(productType = FeeProductType.PERPETUAL_FUTURES))
        assertInvalid(f.copy(reservations = listOf(futures) + f.reservations.drop(1)))
    }

    @Test
    fun `수수료 예약은 현재 잔량과 나머지에 필요한 금액이어야 한다`() {
        val paid = policy(5_000, 10_000)
        val buyer = reservation("buy", "buyer", Side.BUY, 51, 2, paid, feeReserve = 2)
        val seller = reservation("sell-1", "seller", Side.SELL, 51, 1, paid)
        val f =
            Fixture(
                order("buy", "buyer", Side.BUY, 51, 2),
                listOf(execution(10, seller, buyer, 51)),
                listOf(buyer, seller),
                listOf(balance("buyer", usdt, 896, 104), balance("seller", btc, 0, 1)),
            )
        assertInvalid(
            f.copy(reservations = listOf(buyer.copy(remainingAmount = Amount(103), remainingFeeReserveAmount = Amount(1)), seller)),
        )
        // 최초 5개 주문의 수수료 예약 3은 정상이다. 현재 잔량 2개에는 2만 필요하므로
        // 최초 예약 검증이 아니라 현재 수수료 예약의 의미가 틀린 사례다.
        val overReserved =
            reservation("buy", "buyer", Side.BUY, 51, 5, paid, feeReserve = 3).copy(
                remainingQuantity = Quantity(2),
                remainingAmount = Amount(105),
            )
        assertInvalid(
            f.copy(
                order = order("buy", "buyer", Side.BUY, 51, 5),
                reservations = listOf(overReserved, seller),
                balances = listOf(balance("buyer", usdt, 895, 105), balance("seller", btc, 0, 1)),
            ),
        )
    }

    @Test
    fun `한 체결 대금은 감당해도 대상 예약 합보다 hold가 적으면 거절한다`() {
        val f = fixture().copy(executions = fixture().executions.take(1)).withBalance("buyer", usdt) { it.copy(hold = Amount(100)) }
        assertInvalid(f)
    }

    @Test
    fun `같은 사용자의 두 예약 합이 hold를 넘으면 각각 소비 가능해 보여도 거절한다`() {
        val f = fixture(sameSeller = true).withBalance("seller-1", btc) { it.copy(hold = Amount(1)) }
        assertInvalid(f)
    }

    @Test
    fun `첫 체결만 계산하면 성공하지만 둘째 지급이 넘치면 전체 후보 없이 입력을 보존한다`() {
        val f = fixture().copy(balances = fixture().balances + balance("seller-2", usdt, Long.MAX_VALUE, 0))

        val firstOnly = calculate(f.copy(executions = f.executions.take(1)))
        assertBalance(firstOnly, "buyer", usdt, 710, 200)
        assertBalance(firstOnly, "seller-1", usdt, 90, 0)
        assertFailsWith<ArithmeticException> { calculate(f) }

        assertInputsUnchanged(f)
        assertEquals(balance("buyer", usdt, 700, 300), f.balances.first())
        assertEquals(Amount(300), f.reservations.first().remainingAmount)
        assertBalance(firstOnly, "buyer", usdt, 710, 200)
        assertBalance(calculate(fixture()), "buyer", usdt, 715, 100)
    }

    @Test
    fun `없는 소비 잔고와 체결 수량 초과는 실패하며 입력을 바꾸지 않는다`() {
        val f = fixture()
        assertInvalid(f.copy(balances = f.balances.filterNot { it.userId == UserId("seller-2") && it.assetId == btc }))
        assertInvalid(f.copy(executions = listOf(f.executions[0], f.executions[1].copy(quantity = Quantity(2)))))
        assertInvalid(f.copy(executions = listOf(f.executions[0].copy(quantity = Quantity(0)))))
    }

    @Test
    fun `소수 대금을 정수로 정확히 표현할 수 없으면 반올림하지 않는다`() {
        val fractionalMarket = market.copy(baseAssetScale = 2, priceTick = 100)
        val buyer = reservation("buy", "buyer", Side.BUY, 200, 2).copy(reservedAmount = Amount(4), remainingAmount = Amount(4))
        val seller = reservation("sell-1", "seller", Side.SELL, 150, 1)
        val f =
            Fixture(
                order("buy", "buyer", Side.BUY, 200, 2),
                listOf(execution(10, seller, buyer, 150)),
                listOf(buyer, seller),
                listOf(balance("buyer", usdt, 996, 4), balance("seller", btc, 0, 1)),
            )

        assertFailsWith<IllegalArgumentException> { calculate(f, fractionalMarket) }
        assertInputsUnchanged(f)
    }

    @Test
    fun `거래 대금이 Long 범위를 넘으면 작은 값으로 감싸지 않는다`() {
        val buyer =
            reservation(
                "buy",
                "buyer",
                Side.BUY,
                1,
                2,
            ).copy(limitPrice = Price(Long.MAX_VALUE), reservedAmount = Amount(Long.MAX_VALUE), remainingAmount = Amount(Long.MAX_VALUE))
        val seller = reservation("sell-1", "seller", Side.SELL, Long.MAX_VALUE, 2)
        val f =
            Fixture(
                order("buy", "buyer", Side.BUY, Long.MAX_VALUE, 2),
                listOf(execution(10, seller, buyer, Long.MAX_VALUE, quantity = 2)),
                listOf(buyer, seller),
                listOf(balance("buyer", usdt, 0, Long.MAX_VALUE), balance("seller", btc, 0, 2)),
            )

        assertFailsWith<IllegalArgumentException> { calculate(f) }
        assertInputsUnchanged(f)
    }

    @Test
    fun `단일 BUY 체결은 가격차와 다른 예약을 보존하고 첫 변경 순서와 재계산 결과를 유지한다`() {
        val f = fixture().let { it.copy(executions = it.executions.take(1)) }.withBalance("buyer", usdt) { it.copy(hold = Amount(350)) }

        val candidate = calculate(f)
        val repeated = calculate(f)

        assertEquals(
            listOf(
                balance("seller-1", btc, 0, 0),
                balance("seller-1", usdt, 90, 0),
                balance("buyer", usdt, 710, 250),
                balance("buyer", btc, 1, 0),
            ),
            candidate.balanceChanges.map { it.next },
        )
        assertEquals(listOf(OrderId("sell-1"), OrderId("buy")), candidate.reservationChanges.map { it.next.orderId })
        assertEquals(Quantity(2), reservation(candidate, "buy").remainingQuantity)
        assertEquals(Amount(200), reservation(candidate, "buy").remainingAmount)
        assertEquals(balance("buyer", btc, 0, 0), candidate.balanceChanges.last().previous)
        assertEquals(candidate.balanceChanges, repeated.balanceChanges)
        assertEquals(candidate.reservationChanges, repeated.reservationChanges)
        assertEquals(
            candidate.settlements
                .single()
                .transaction.postings,
            repeated.settlements
                .single()
                .transaction.postings,
        )
        assertFailsWith<UnsupportedOperationException> { (candidate.balanceChanges as MutableList<*>).clear() }
        assertInputsUnchanged(f)
    }

    @Test
    fun `단일 SELL 부분 체결은 maker 가격으로 지급하고 양쪽 잔여 예약을 보존한다`() {
        val seller = reservation("sell", "seller", Side.SELL, 95, 3)
        val buyer = reservation("buy", "buyer", Side.BUY, 105, 2)
        val f =
            Fixture(
                order("sell", "seller", Side.SELL, 95, 3),
                listOf(execution(10, buyer, seller, 105)),
                listOf(seller, buyer),
                listOf(balance("seller", btc, 0, 3), balance("buyer", usdt, 790, 210)),
            )

        val candidate = calculate(f)

        assertEquals(
            listOf(
                balance("buyer", usdt, 790, 105),
                balance("buyer", btc, 1, 0),
                balance("seller", btc, 0, 2),
                balance("seller", usdt, 105, 0),
            ),
            candidate.balanceChanges.map { it.next },
        )
        assertEquals(Quantity(1), reservation(candidate, "buy").remainingQuantity)
        assertEquals(Quantity(2), reservation(candidate, "sell").remainingQuantity)
        assertTrue(candidate.reservationChanges.all { it.next.status == OrderReservationStatus.ACTIVE })
        assertInputsUnchanged(f)
    }

    @Test
    fun `단일 체결의 양쪽 사용자가 같으면 지급과 소비를 같은 슬롯에 누적한다`() {
        for (side in listOf(Side.BUY, Side.SELL)) {
            val buyer = reservation("buy", "owner", Side.BUY, 100, 2)
            val seller = reservation("sell", "owner", Side.SELL, 90, 2)
            val maker = if (side == Side.BUY) seller else buyer
            val taker = if (side == Side.BUY) buyer else seller
            val f =
                Fixture(
                    order(taker.orderId.value, "owner", side, taker.limitPrice.value, 2),
                    listOf(execution(10, maker, taker, maker.limitPrice.value)),
                    listOf(buyer, seller),
                    listOf(balance("owner", usdt, 800, 250), balance("owner", btc, 5, 5)),
                )

            val candidate = calculate(f)

            assertBalance(candidate, "owner", usdt, 900, 150)
            assertBalance(candidate, "owner", btc, 6, 4)
            assertEquals(2, candidate.balanceChanges.size)
            assertEquals(listOf(maker.assetId, taker.assetId), candidate.balanceChanges.map { it.next.assetId })
            assertTrue(candidate.reservationChanges.all { it.next.status == OrderReservationStatus.ACTIVE })
            assertInputsUnchanged(f)
        }
    }

    @Test
    fun `단일 체결도 빠진 입력과 쓰지 않는 입력의 중복 및 손상된 현재 예약을 거절한다`() {
        val f = fixture().let { it.copy(executions = it.executions.take(1)) }
        val maker = f.reservations[1]
        val invalid =
            listOf(
                f.copy(reservations = listOf(f.reservations.first(), f.reservations.last())),
                f.copy(balances = f.balances.filterNot { it.userId == maker.userId && it.assetId == btc }),
                f.copy(reservations = f.reservations + f.reservations.last()),
                f.copy(balances = f.balances + f.balances.last()),
                f.copy(reservations = listOf(f.reservations.first(), maker.copy(reservedAmount = Amount(2)), f.reservations.last())),
                f.copy(reservations = listOf(f.reservations.first(), maker.copy(userId = UserId("other")), f.reservations.last())),
                f.copy(executions = listOf(f.executions.single().copy(quantity = Quantity(2)))),
            )

        invalid.forEach { assertInvalid(it) }
        assertBalance(calculate(f), "buyer", usdt, 710, 200)
        assertInputsUnchanged(f)
    }

    @Test
    fun `단일 체결 지급이 Long 범위를 넘으면 후보 없이 입력을 보존한다`() {
        val f =
            fixture().let {
                it.copy(
                    executions = it.executions.take(1),
                    balances =
                        it.balances + balance("seller-1", usdt, Long.MAX_VALUE, 0),
                )
            }

        assertFailsWith<ArithmeticException> { calculate(f) }

        assertInputsUnchanged(f)
        assertBalance(calculate(fixture().let { it.copy(executions = it.executions.take(1)) }), "buyer", usdt, 710, 200)
    }

    private fun fixture(sameSeller: Boolean = false): Fixture {
        val buyer = reservation("buy", "buyer", Side.BUY, 100, 3)
        val first = reservation("sell-1", "seller-1", Side.SELL, 90, 1)
        val second = reservation("sell-2", if (sameSeller) "seller-1" else "seller-2", Side.SELL, 95, 1)
        return Fixture(
            order("buy", "buyer", Side.BUY, 100, 3),
            listOf(execution(10, first, buyer, 90), execution(11, second, buyer, 95)),
            listOf(buyer, first, second),
            if (sameSeller) {
                listOf(balance("buyer", usdt, 700, 300), balance("seller-1", btc, 0, 2))
            } else {
                listOf(balance("buyer", usdt, 700, 300), balance("seller-1", btc, 0, 1), balance("seller-2", btc, 0, 1))
            },
        )
    }

    private fun calculate(
        f: Fixture,
        definition: MarketDefinition = market,
    ): OrderSettlementCandidate = calculator.calculate(f.order, definition, f.executions, f.reservations, f.balances)

    private fun assertInvalid(f: Fixture) {
        val balancesBefore = f.balances.toList()
        val reservationsBefore = f.reservations.toList()
        assertFailsWith<IllegalArgumentException> { calculate(f) }
        assertEquals(balancesBefore, f.balances)
        assertEquals(reservationsBefore, f.reservations)
    }

    private fun assertInputsUnchanged(f: Fixture) {
        assertEquals(f.initialExecutions, f.executions)
        assertEquals(f.initialReservations, f.reservations)
        assertEquals(f.initialBalances, f.balances)
    }

    private fun assertBalance(
        candidate: OrderSettlementCandidate,
        user: String,
        asset: AssetId,
        available: Long,
        hold: Long,
    ) {
        val next = candidate.balanceChanges.single { it.next.userId == UserId(user) && it.next.assetId == asset }.next
        assertEquals(balance(user, asset, available, hold), next)
    }

    private fun reservation(
        candidate: OrderSettlementCandidate,
        id: String,
    ): OrderReservation = candidate.reservationChanges.single { it.next.orderId == OrderId(id) }.next

    private fun feeRevenue(candidate: OrderSettlementCandidate): Long =
        candidate.settlements.sumOf { settlement ->
            settlement.transaction.postings
                .filter { it.accountId == "SYSTEM:USDT:FEE_REVENUE" && it.side == LedgerPostingSide.CREDIT }
                .sumOf { it.amount.value }
        }

    private fun order(
        id: String,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
    ): OrderAdmissionInput =
        OrderAdmissionInput(
            market.marketId,
            OrderId(id),
            UserId(user),
            side,
            OrderType.LIMIT,
            TimeInForce.GTC,
            Price(price),
            Quantity(quantity),
        )

    private fun execution(
        sequence: Long,
        maker: OrderReservation,
        taker: OrderReservation,
        price: Long,
        quantity: Long = 1,
    ): SettlementExecution =
        SettlementExecution(
            market.marketId,
            sequence,
            maker.orderId,
            taker.orderId,
            maker.userId,
            taker.userId,
            taker.side,
            Price(price),
            Quantity(quantity),
            "ledger-$sequence",
            time.plusSeconds(sequence),
        )

    private fun reservation(
        id: String,
        user: String,
        side: Side,
        price: Long,
        quantity: Long,
        policy: TradingFeePolicySnapshot = free,
        feeReserve: Long = 0,
    ): OrderReservation {
        val amount = if (side == Side.BUY) Math.addExact(Math.multiplyExact(price, quantity), feeReserve) else quantity
        return OrderReservation(
            market.marketId,
            OrderId(id),
            UserId(user),
            side,
            if (side == Side.BUY) usdt else btc,
            Price(price),
            Quantity(quantity),
            Quantity(quantity),
            Amount(amount),
            Amount(amount),
            policy,
            Amount(feeReserve),
            Amount(feeReserve),
            OrderReservationStatus.ACTIVE,
        )
    }

    private fun balance(
        user: String,
        asset: AssetId,
        available: Long,
        hold: Long,
    ): Balance = Balance(UserId(user), asset, Amount(available), Amount(hold))

    private fun policy(
        maker: Long,
        taker: Long,
    ): TradingFeePolicySnapshot =
        TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate(maker), FeeRate(taker)))

    private data class Fixture(
        val order: OrderAdmissionInput,
        val executions: List<SettlementExecution>,
        val reservations: List<OrderReservation>,
        val balances: List<Balance>,
    ) {
        val initialExecutions = executions.toList()
        val initialReservations = reservations.toList()
        val initialBalances = balances.toList()

        fun withBalance(
            user: String,
            asset: AssetId,
            transform: (Balance) -> Balance,
        ): Fixture = copy(balances = balances.map { if (it.userId == UserId(user) && it.assetId == asset) transform(it) else it })
    }
}

package com.exchange.core.trading

import com.exchange.core.common.Amount
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRemainder
import com.exchange.core.matching.MatchingCheckpoint
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReservedMatchingStateTest {
    private val f = MatchingIntakeTestFixture()

    @Test
    fun `빈 주문장에 대기 결과를 남기고 같은 입력은 최초 결과를 돌려준다`() {
        val owner = f.owner()
        val input = f.input(1)
        val first = owner.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, first.status)
        val result = assertNotNull(first.result)
        assertEquals(
            listOf(OrderEnteredBook(f.a.marketId, 1, input.order.orderId, UserId("buyer"), Side.BUY, Price(100), Quantity(3))),
            result.events,
        )
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(listOf(Quantity(3)), owner.restingOrders(f.a.marketId).map { it.remainingQuantity })
        val before = owner.recoveryState()
        val repeated = owner.apply(input)
        assertEquals(MatchingIntakeStatus.DUPLICATE, repeated.status)
        assertSame(result, repeated.result)
        assertEquals(first.ack, repeated.ack)
        assertStateSame(before, owner.recoveryState())
        assertEquals(1L, first.ack?.through)
    }

    @Test
    fun `보존 중인 입력의 원문 충돌을 신규 예약 검사보다 먼저 구분한다`() {
        val owner = f.owner()
        val original = f.input(1)
        owner.apply(original)
        val before = owner.recoveryState()
        for (changed in listOf(
            original.copy(order = original.order.copy(price = Price(99))),
            original.copy(reservation = original.reservation.copy(remainingAmount = Amount(299))),
            original.copy(context = original.context.copy(ordinal = 2)),
        )) {
            assertEquals(MatchingIntakeStatus.CONTENT_CONFLICT, owner.apply(changed).status)
            assertStateSame(before, owner.recoveryState())
        }
    }

    @Test
    fun `앞선 입력 없이 다음 순번이 오면 건너뛰지 않고 gap을 돌려준다`() {
        val owner = f.owner()
        val before = owner.recoveryState()
        val gap = owner.apply(f.input(2))
        assertEquals(MatchingIntakeStatus.GAP, gap.status)
        assertEquals(1L, gap.expectedSequence)
        assertNull(gap.ack)
        assertStateSame(before, owner.recoveryState())
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(1)).status)
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(2)).status)
        assertEquals(2L, owner.lastEngineSequence(f.a.marketId))
    }

    @Test
    fun `다른 출처 배치 정책의 입력은 상태를 바꾸거나 성공 확인하지 않는다`() {
        val owner = f.owner()
        val input = f.input(1)
        val invalid =
            listOf(
                input.copy(stream = input.stream.copy(fundsLineage = ClusterLineage(9, 10))),
                input.copy(stream = input.stream.copy(matchingLineage = ClusterLineage(9, 10))),
                input.copy(stream = input.stream.copy(ownershipEpoch = 2)),
                input.copy(stream = input.stream.copy(configurationVersion = 8)),
                input.copy(stream = input.stream.copy(marketId = f.b.marketId)),
                input.copy(context = input.context.copy(ordinal = 2)),
                input.copy(context = input.context.copy(feePolicySnapshot = f.fees.copy(scheduleVersion = 2))),
            )
        val before = owner.recoveryState()
        for (next in invalid) {
            val reply = owner.apply(next)
            assertTrue(reply.status in setOf(MatchingIntakeStatus.INVALID_STREAM, MatchingIntakeStatus.INVALID_INPUT))
            assertNull(reply.ack)
            assertFalse(owner.isBlocked)
            assertStateSame(before, owner.recoveryState())
        }
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(input).status)
    }

    @Test
    fun `초기 예약의 금액 상태 식별자와 수량을 검증한다`() {
        val owner = f.owner()
        val input = f.input(1)
        val invalid =
            listOf(
                input.copy(reservation = input.reservation.copy(reservedAmount = Amount(299), remainingAmount = Amount(299))),
                input.copy(reservation = input.reservation.copy(remainingAmount = Amount(299))),
                input.copy(reservation = input.reservation.copy(remainingQuantity = Quantity(2))),
                input.copy(
                    reservation =
                        input.reservation.copy(
                            status = OrderReservationStatus.SETTLED,
                            remainingQuantity = Quantity.ZERO,
                            remainingAmount = Amount.ZERO,
                        ),
                ),
                input.copy(reservation = input.reservation.copy(feeRemainder = FeeRemainder(1))),
                input.copy(reservation = input.reservation.release()),
                input.copy(reservation = input.reservation.copy(feePolicySnapshot = f.fees.copy(scheduleVersion = 2))),
                input.copy(reservation = input.reservation.copy(assetId = f.a.baseAssetId)),
                input.copy(order = input.order.copy(orderId = OrderId("arbitrary"))),
                input.copy(order = input.order.copy(userId = UserId("different"))),
                input.copy(order = input.order.copy(quantity = Quantity(0))),
                input.copy(order = input.order.copy(orderType = OrderType.MARKET)),
                input.copy(order = input.order.copy(timeInForce = TimeInForce.IOC)),
                input.copy(order = input.order.copy(price = Price(Long.MAX_VALUE))),
                input.copy(marketAdmissionSequence = 2),
                input.copy(admissionSequence = 0),
            )
        val before = owner.recoveryState()
        for (next in invalid) {
            assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(next).status)
            assertStateSame(before, owner.recoveryState())
            assertFalse(owner.isBlocked)
        }
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(input).status)
    }

    @Test
    fun `고정한 가격 수량 단위를 벗어난 입력은 무변경 거절하고 정상 다음 입력을 받는다`() {
        val units = MatchingIntakeTestFixture(priceTick = 10, quantityStep = 2)
        val owner = units.owner()
        val input = units.input(1, price = 100, quantity = 2)
        val before = owner.recoveryState()
        for (changed in listOf(
            input.copy(order = input.order.copy(price = Price(99))),
            input.copy(order = input.order.copy(quantity = Quantity(3))),
        )) {
            assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(changed).status)
            assertStateSame(before, owner.recoveryState())
        }
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(input).status)
        assertEquals(listOf(Quantity(2)), owner.restingOrders(units.a.marketId).map { it.remainingQuantity })
    }

    @Test
    fun `여러 체결과 남은 대기를 하나의 완결 결과로 만들고 maker마다 자기 원본을 보존한다`() {
        val owner = f.owner()
        val sell1 = f.input(1, Side.SELL, 90, 1, user = "seller")
        val sell2 = f.input(2, Side.SELL, 95, 1, user = "seller")
        val buy = f.input(3)
        owner.apply(sell1)
        owner.apply(sell2)
        val result = assertNotNull(owner.apply(buy).result)
        assertEquals(
            listOf(
                TradeExecuted(
                    f.a.marketId,
                    3,
                    sell1.order.orderId,
                    buy.order.orderId,
                    UserId("seller"),
                    UserId("buyer"),
                    Side.BUY,
                    Price(90),
                    Quantity(1),
                ),
                TradeExecuted(
                    f.a.marketId,
                    4,
                    sell2.order.orderId,
                    buy.order.orderId,
                    UserId("seller"),
                    UserId("buyer"),
                    Side.BUY,
                    Price(95),
                    Quantity(1),
                ),
                OrderEnteredBook(f.a.marketId, 5, buy.order.orderId, UserId("buyer"), Side.BUY, Price(100), Quantity(1)),
            ),
            result.events,
        )
        assertEquals(5L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(listOf(buy.order.orderId), owner.restingOrders(f.a.marketId).map { it.orderId })
        assertEquals(listOf(Quantity(1)), owner.restingOrders(f.a.marketId).map { it.remainingQuantity })
        assertEquals(3, result.effects.size)
        assertEquals(setOf(sell1, sell2, buy), result.effects.map { it.origin }.toSet())
        assertEquals(listOf(1L, 2L, 3L), result.effects.map { it.origin.admissionSequence }.sorted())
        assertEquals(3L, result.resultSequence)
        assertEquals(2L, result.engineSequenceBefore)
        assertEquals(5L, result.engineSequenceAfter)
        assertEquals(3, result.eventCount)
    }

    @Test
    fun `동일 사용자의 매수 매도도 기존 엔진처럼 체결하고 종료 원본은 활성 목록에서 정리한다`() {
        val owner = f.owner()
        val buy = f.input(1, quantity = 1, user = "same")
        val sell = f.input(2, Side.SELL, 100, 1, user = "same")
        owner.apply(buy)
        val reply = owner.apply(sell)
        val event = assertIs<TradeExecuted>(assertNotNull(reply.result).events.single())
        assertEquals(UserId("same"), event.makerUserId)
        assertEquals(UserId("same"), event.takerUserId)
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
        assertEquals(0, owner.recoveryState().activeOrders)
        assertEquals(2, owner.recoveryState().resultEntries)
        assertSame(reply.result, owner.apply(sell).result)
    }

    @Test
    fun `활성 한도는 적용 후 주문 수를 기준으로 최종 거절한다`() {
        val owner = f.owner(f.limits(activeOrders = 1))
        owner.apply(f.input(1, quantity = 1))
        val rejected = owner.apply(f.input(2, price = 99, quantity = 1))
        assertEquals(MatchingIntakeStatus.APPLIED, rejected.status)
        val result = assertNotNull(rejected.result)
        assertEquals(MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT, result.reason)
        assertTrue(result.events.isEmpty())
        assertEquals(1L, result.engineSequenceBefore)
        assertEquals(1L, result.engineSequenceAfter)
        assertEquals(1, owner.restingOrders(f.a.marketId).size)
        assertSame(result, owner.apply(f.input(2, price = 99, quantity = 1)).result)
        val fill = owner.apply(f.input(3, Side.SELL, 100, 1))
        assertEquals(MatchingIntakeStatus.APPLIED, fill.status)
        assertIs<TradeExecuted>(assertNotNull(fill.result).events.single())
        assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
    }

    @Test
    fun `임시 결과 포화는 입력을 소비하지 않고 ACK 이후 같은 입력을 처리한다`() {
        val owner = f.owner(f.limits(resultEntries = 1))
        owner.apply(f.input(1))
        val before = owner.recoveryState()
        val second = f.input(2, price = 99)
        val busy = owner.apply(second)
        assertEquals(MatchingIntakeStatus.BUSY, busy.status)
        assertNull(busy.result)
        assertNull(busy.ack)
        assertStateSame(before, owner.recoveryState())
        owner.acknowledgeFundsSettled(ack(1))
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(second).status)
        assertEquals(2L, owner.lastEngineSequence(f.a.marketId))
    }

    @Test
    fun `영구 이벤트 한도 초과는 maker를 전혀 바꾸지 않고 영 이벤트 최종 거절을 남긴다`() {
        val owner = f.owner(f.limits(maxResultEvents = 2))
        owner.apply(f.input(1, Side.SELL, 90, 1, user = "seller1"))
        owner.apply(f.input(2, Side.SELL, 95, 1, user = "seller2"))
        val beforeBook = owner.restingOrders(f.a.marketId)
        val rejected = assertNotNull(owner.apply(f.input(3)).result)
        assertEquals(MatchingNotExecutedReason.RESULT_TOO_LARGE, rejected.reason)
        assertEquals(0, rejected.eventCount)
        assertEquals(2L, rejected.engineSequenceBefore)
        assertEquals(2L, rejected.engineSequenceAfter)
        assertEquals(beforeBook, owner.restingOrders(f.a.marketId))
        assertEquals(2L, owner.lastEngineSequence(f.a.marketId))
        val next = assertNotNull(owner.apply(f.input(4, price = 80, quantity = 1)).result)
        assertEquals(3L, next.events.single().engineSequence)
    }

    @Test
    fun `최종 거절도 임시 보존 공간이 없으면 BUSY이며 확인 뒤 한 번만 확정한다`() {
        val owner = f.owner(f.limits(resultEntries = 1, activeOrders = 1))
        owner.apply(f.input(1))
        val before = owner.recoveryState()
        val second = f.input(2)
        assertEquals(MatchingIntakeStatus.BUSY, owner.apply(second).status)
        assertStateSame(before, owner.recoveryState())
        owner.acknowledgeFundsSettled(ack(1))
        val rejected = assertNotNull(owner.apply(second).result)
        assertEquals(MatchingNotExecutedReason.ACTIVE_ORDER_LIMIT, rejected.reason)
        assertEquals(2L, rejected.resultSequence)
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
    }

    @Test
    fun `마켓 사이의 global 승인 역도착은 각 stream 다음 순번이면 허용한다`() {
        val owner = f.owner()
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(1, admission = 100)).status)
        assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(1, admission = 99, market = f.b)).status)
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
        assertEquals(1L, owner.lastEngineSequence(f.b.marketId))
        val before = owner.recoveryState()
        assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(f.input(2, admission = 100)).status)
        assertStateSame(before, owner.recoveryState())
    }

    @Test
    fun `정산 확인이 원문 결과만 회수하며 대기 maker 원본은 다음 체결에 남는다`() {
        val owner = f.owner()
        val buy = f.input(1, quantity = 1)
        owner.apply(buy)
        owner.acknowledgeFundsSettled(ack(1))
        val afterAck = owner.recoveryState()
        assertEquals(0, afterAck.resultEntries)
        assertEquals(1, afterAck.activeOrders)
        val staleChanged = buy.copy(order = buy.order.copy(price = Price(98)))
        val pruned = owner.apply(staleChanged)
        assertEquals(MatchingIntakeStatus.PRUNED, pruned.status)
        assertNull(pruned.result)
        assertEquals(1L, pruned.ack?.through)
        assertStateSame(afterAck, owner.recoveryState())
        val sell = f.input(2, Side.SELL, 100, 1)
        val result = assertNotNull(owner.apply(sell).result)
        assertEquals(setOf(buy, sell), result.effects.map { it.origin }.toSet())
        assertEquals(0, owner.recoveryState().activeOrders)
        assertEquals(1, owner.recoveryState().resultEntries)
        assertSame(result, owner.apply(sell).result)
    }

    @Test
    fun `결과 읽기는 비소비적이고 작은 batch와 tail과 회수 구간을 구분한다`() {
        val owner = f.owner()
        val first = assertNotNull(owner.apply(f.input(1)).result)
        val second = assertNotNull(owner.apply(f.input(2, price = 99)).result)
        val stream = f.configuration().resultStream(f.a.marketId)
        val before = owner.recoveryState()
        repeat(2) {
            val batch = owner.readResults(stream, 1, 2, 10_000)
            assertEquals(MatchingResultReadStatus.BATCH, batch.status)
            assertEquals(listOf(first, second), batch.entries)
            assertEquals(3L, batch.nextSequence)
            val single = owner.readResults(stream, 1, 1, 10_000)
            assertEquals(listOf(first), single.entries)
            val small = owner.readResults(stream, 1, 2, 1)
            assertEquals(MatchingResultReadStatus.BATCH_TOO_SMALL, small.status)
            assertEquals(1_141L, small.requiredBytes)
            assertEquals(1L, small.nextSequence)
            assertTrue(small.entries.isEmpty())
            val tail = owner.readResults(stream, 3, 1, 10_000)
            assertEquals(MatchingResultReadStatus.BATCH, tail.status)
            assertTrue(tail.entries.isEmpty())
            assertEquals(3L, tail.nextSequence)
            assertEquals(MatchingResultReadStatus.INVALID_QUERY, owner.readResults(stream, 4, 1, 10_000).status)
            assertEquals(MatchingResultReadStatus.INVALID_QUERY, owner.readResults(stream, 0, 1, 10_000).status)
            assertEquals(MatchingResultReadStatus.INVALID_QUERY, owner.readResults(stream, 1, 0, 10_000).status)
            assertEquals(MatchingResultReadStatus.INVALID_QUERY, owner.readResults(stream, 1, 1, 0).status)
            assertEquals(MatchingResultReadStatus.INVALID_STREAM, owner.readResults(stream.copy(ownershipEpoch = 2), 1, 1, 10_000).status)
            assertStateSame(before, owner.recoveryState())
        }
        owner.acknowledgeFundsSettled(ack(1))
        val pruned = owner.readResults(stream, 1, 2, 10_000)
        assertEquals(MatchingResultReadStatus.PRUNED, pruned.status)
        assertEquals(1L, pruned.fundsSettledThrough)
        assertEquals(2L, pruned.nextSequence)
        assertEquals(listOf(second), owner.readResults(stream, 2, 2, 10_000).entries)
    }

    @Test
    fun `정산 ACK는 연속 suffix만 정리하고 다른 마켓과 대기 원본은 유지한다`() {
        val owner = f.owner()
        for (sequence in 1L..7L) owner.apply(f.input(sequence, price = 100 - sequence))
        owner.apply(f.input(1, admission = 8, market = f.b))
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(1)).status)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(3)).status)
        var saved = owner.recoveryState()
        var a = saved.streams.single { it.stream.marketId == f.a.marketId }
        assertEquals(3L, a.fundsSettledThrough)
        assertEquals(3L, a.settledEngineSequence)
        assertEquals(listOf(4L, 5L, 6L, 7L), a.outstanding.map { it.resultSequence })
        assertEquals(
            1,
            saved.streams
                .single { it.stream.marketId == f.b.marketId }
                .outstanding.size,
        )
        assertEquals(8, saved.activeOrders)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(7)).status)
        saved = owner.recoveryState()
        a = saved.streams.single { it.stream.marketId == f.a.marketId }
        assertEquals(7L, a.fundsSettledThrough)
        assertEquals(7L, a.settledEngineSequence)
        assertTrue(a.outstanding.isEmpty())
        assertEquals(0L, a.resultBytes)
        assertEquals(1, saved.resultEntries)
        assertEquals(8, saved.activeOrders)
    }

    @Test
    fun `잘못된 ACK는 중복 숫자여도 거절하고 미래 확인을 기억하지 않는다`() {
        val owner = f.owner()
        owner.apply(f.input(1))
        val valid = ack(1)
        val invalid =
            listOf(
                valid.copy(stream = valid.stream.copy(matchingLineage = ClusterLineage(9, 10))) to MatchingResultAckStatus.INVALID_STREAM,
                valid.copy(stream = valid.stream.copy(fundsLineage = ClusterLineage(9, 10))) to MatchingResultAckStatus.INVALID_STREAM,
                valid.copy(stream = valid.stream.copy(ownershipEpoch = 2)) to MatchingResultAckStatus.INVALID_STREAM,
                valid.copy(context = f.contexts[0].copy(ordinal = 2)) to MatchingResultAckStatus.INVALID_POLICY,
                valid.copy(through = -1) to MatchingResultAckStatus.INVALID_SEQUENCE,
                valid.copy(through = 2) to MatchingResultAckStatus.FUTURE_ACK,
            )
        val before = owner.recoveryState()
        for ((next, status) in invalid) {
            assertEquals(status, owner.acknowledgeFundsSettled(next).status)
            assertStateSame(before, owner.recoveryState())
            assertFalse(owner.isBlocked)
        }
        owner.acknowledgeFundsSettled(valid)
        val after = owner.recoveryState()
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(valid).status)
        assertEquals(MatchingResultAckStatus.INVALID_POLICY, owner.acknowledgeFundsSettled(valid.copy(context = f.contexts[1])).status)
        assertStateSame(after, owner.recoveryState())
        owner.apply(f.input(2))
        assertEquals(1, owner.recoveryState().resultEntries)
        assertEquals(
            1L,
            owner
                .recoveryState()
                .streams
                .single { it.stream.marketId == f.a.marketId }
                .fundsSettledThrough,
        )
    }

    @Test
    fun `수기 논리 byte와 한 byte 부족 경계를 검사한다`() {
        val small = MatchingIntakeTestFixture(identifierBytes = 34)
        // user34: 원본502 + effect522 + book119 + 고정104 + market11 + resultId28 = 1286.
        // 최대 순번의 거절도 원본502 + effect522 + 고정104 + market11 + resultId46 = 1185로 들어간다.
        for (budget in listOf(2_572L, 2_571L)) {
            val owner = small.owner(small.limits(resultBytes = budget, maxSingleResultBytes = 1_286))
            val first = assertNotNull(owner.apply(small.input(1, user = "u".repeat(34))).result)
            assertEquals(1_286L, first.canonicalBytes)
            assertEquals(502L, owner.recoveryState().activeBytes)
            val second = owner.apply(small.input(2, user = "u".repeat(34)))
            if (budget == 2_572L) {
                assertEquals(MatchingIntakeStatus.APPLIED, second.status)
                assertEquals(2_572L, owner.recoveryState().resultBytes)
            } else {
                assertEquals(MatchingIntakeStatus.BUSY, second.status)
                assertEquals(1_286L, owner.recoveryState().resultBytes)
                assertEquals(1L, owner.lastEngineSequence(small.a.marketId))
            }
        }
        val owner = small.owner()
        val unicode = assertNotNull(owner.apply(small.input(1, user = "매수")).result)
        // 두 한글은 UTF-8 6byte다. buyer5보다 원본 두 곳/effect 두 곳/book 한 곳에서 총5byte 증가한다.
        assertEquals(1_146L, unicode.canonicalBytes)
        assertEquals(446L, owner.recoveryState().activeBytes)
    }

    @Test
    fun `최대 원본의 거절 한 건을 저장하지 못하는 설정은 미리 거절한다`() {
        val small = MatchingIntakeTestFixture(identifierBytes = 34)
        assertFailsWith<IllegalArgumentException> {
            small.configuration(small.limits(resultBytes = 1_185, maxSingleResultBytes = 1_184))
        }
        assertFailsWith<IllegalArgumentException> {
            small.configuration(small.limits(resultBytes = 1_184, maxSingleResultBytes = 1_185))
        }
        assertFailsWith<IllegalArgumentException> {
            small.configuration(small.limits(resultBytes = 16_777_216, activeBytes = 16_777_216))
        }
        small.configuration(small.limits(resultBytes = 1_185, maxSingleResultBytes = 1_185))
    }

    @Test
    fun `완료와 정산 확인을 반복해도 완료 결과와 종료 식별자가 쌓이지 않는다`() {
        val owner = f.owner(f.limits(resultEntries = 2, activeOrders = 1))
        repeat(32) { round ->
            val first = 2L * round + 1
            assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(first, quantity = 1)).status)
            assertEquals(MatchingIntakeStatus.APPLIED, owner.apply(f.input(first + 1, Side.SELL, 100, 1)).status)
            owner.acknowledgeFundsSettled(ack(first + 1))
            val saved = owner.recoveryState()
            assertEquals(0, saved.activeOrders)
            assertEquals(0L, saved.activeBytes)
            assertEquals(0, saved.resultEntries)
            assertEquals(0L, saved.resultBytes)
            assertTrue(saved.active.isEmpty())
            assertTrue(saved.streams.all { it.outstanding.isEmpty() })
            assertTrue(owner.restingOrders(f.a.marketId).isEmpty())
        }
        val before = owner.recoveryState()
        assertEquals(MatchingIntakeStatus.INVALID_INPUT, owner.apply(f.input(65, admission = 1)).status)
        assertStateSame(before, owner.recoveryState())
        assertEquals(MatchingIntakeStatus.PRUNED, owner.apply(f.input(1)).status)
        // 메모리 안의 엔진 보유 ID 수를 직접 대조해 완료 원본을 숨은 seen 집합에 남기지 않음을 확인한다.
        val dataField = owner.javaClass.getDeclaredField("data").also { it.isAccessible = true }
        val data = dataField.get(owner)
        val engineField = data.javaClass.getDeclaredField("engine").also { it.isAccessible = true }
        val engine = engineField.get(data)
        val seenField = engine.javaClass.getDeclaredField("seenOrderIds").also { it.isAccessible = true }
        val seen = seenField.get(engine) as Map<*, *>
        assertTrue(seen.values.all { (it as Set<*>).isEmpty() })
    }

    @Test
    fun `입력과 결과의 마지막 Long 순번을 허용하고 MAX 뒤 cursor를 만들지 않는다`() {
        val configuration = f.configuration()
        val owner = ReservedMatchingState(configuration)
        owner.restore(boundaryState(applied = Long.MAX_VALUE - 1, engineSequence = 0))
        val last = f.input(Long.MAX_VALUE, quantity = 1)
        val first = owner.apply(last)
        assertEquals(MatchingIntakeStatus.APPLIED, first.status)
        assertEquals(Long.MAX_VALUE, first.result?.resultSequence)
        assertEquals(Long.MAX_VALUE, first.ack?.through)
        assertEquals(1L, owner.lastEngineSequence(f.a.marketId))
        val duplicate = owner.apply(last)
        assertEquals(MatchingIntakeStatus.DUPLICATE, duplicate.status)
        val read = owner.readResults(configuration.resultStream(f.a.marketId), Long.MAX_VALUE, 1, 10_000)
        assertEquals(listOf(first.result), read.entries)
        assertNull(read.nextSequence)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(Long.MAX_VALUE)).status)
        val pruned = owner.readResults(configuration.resultStream(f.a.marketId), Long.MAX_VALUE, 1, 10_000)
        assertEquals(MatchingResultReadStatus.PRUNED, pruned.status)
        assertNull(pruned.nextSequence)
        assertEquals(MatchingIntakeStatus.PRUNED, owner.apply(last).status)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(Long.MAX_VALUE)).status)
        assertFalse(owner.isBlocked)
    }

    @Test
    fun `엔진의 마지막 사건은 허용하지만 이후 영 이벤트 거절도 신규 발급하지 않는다`() {
        val configuration = f.configuration(f.limits(activeOrders = 1))
        val owner = ReservedMatchingState(configuration)
        owner.restore(boundaryState(applied = Long.MAX_VALUE - 2, engineSequence = Long.MAX_VALUE - 1, configuration = configuration))
        val input = f.input(Long.MAX_VALUE - 1, quantity = 1)
        val last = owner.apply(input)
        assertEquals(MatchingIntakeStatus.APPLIED, last.status)
        assertEquals(
            Long.MAX_VALUE,
            last.result
                ?.events
                ?.single()
                ?.engineSequence,
        )
        val before = owner.recoveryState()
        // 활성 한도를 넘어서 event0 거절이 가능한 입력이어도 engine MAX가 우선한다.
        val exhausted = owner.apply(f.input(Long.MAX_VALUE, quantity = 1))
        assertEquals(MatchingIntakeStatus.SEQUENCE_EXHAUSTED, exhausted.status)
        assertNull(exhausted.result)
        assertNull(exhausted.ack)
        assertStateSame(before, owner.recoveryState())
        assertEquals(MatchingIntakeStatus.DUPLICATE, owner.apply(input).status)
        assertEquals(MatchingResultAckStatus.ACKNOWLEDGED, owner.acknowledgeFundsSettled(ack(Long.MAX_VALUE - 1)).status)
        assertEquals(
            Long.MAX_VALUE,
            owner
                .recoveryState()
                .streams
                .single { it.stream.marketId == f.a.marketId }
                .settledEngineSequence,
        )
        assertFalse(owner.isBlocked)
    }

    private fun boundaryState(
        applied: Long,
        engineSequence: Long,
        configuration: MatchingIntakeConfiguration = f.configuration(),
    ): MatchingRoleRecoveryState =
        MatchingRoleRecoveryState(
            configuration,
            TradingLifecycle.RUNNING,
            MatchingCheckpoint(
                if (engineSequence == 0L) emptyList() else listOf(MatchingCheckpoint.Market(f.a.marketId, engineSequence, emptyList())),
            ),
            emptyList(),
            configuration.markets.map {
                val a = it.market.marketId == f.a.marketId
                MatchingStreamRecovery(
                    configuration.stream(it.market.marketId),
                    if (a) applied else 0,
                    if (a) applied else 0,
                    if (a) applied else 0,
                    if (a) applied else 0,
                    if (a) engineSequence else 0,
                    emptyList(),
                    0,
                    0,
                    0,
                )
            },
            0,
            0,
            0,
            0,
        )

    private fun ack(through: Long) = MatchingFundsSettledAck(f.configuration().resultStream(f.a.marketId), through, f.contexts[0])

    private fun assertStateSame(
        expected: MatchingRoleRecoveryState,
        actual: MatchingRoleRecoveryState,
    ) {
        assertEquals(expected.configuration, actual.configuration)
        assertEquals(expected.lifecycle, actual.lifecycle)
        assertEquals(
            expected.streams.map {
                listOf(
                    it.stream,
                    it.appliedThrough,
                    it.lastSourceAdmissionSequence,
                    it.resultIssued,
                    it.fundsSettledThrough,
                    it.settledEngineSequence,
                    it.outstanding,
                    it.resultBytes,
                    it.activeOrders,
                    it.activeBytes,
                )
            },
            actual.streams.map {
                listOf(
                    it.stream,
                    it.appliedThrough,
                    it.lastSourceAdmissionSequence,
                    it.resultIssued,
                    it.fundsSettledThrough,
                    it.settledEngineSequence,
                    it.outstanding,
                    it.resultBytes,
                    it.activeOrders,
                    it.activeBytes,
                )
            },
        )
        assertEquals(expected.active, actual.active)
        assertEquals(expected.resultEntries, actual.resultEntries)
        assertEquals(expected.resultBytes, actual.resultBytes)
        assertEquals(expected.activeOrders, actual.activeOrders)
        assertEquals(expected.activeBytes, actual.activeBytes)
        assertEquals(
            expected.matching.markets.map { it.marketId to (it.lastEngineSequence to it.orders) },
            actual.matching.markets.map {
                it.marketId to
                    (it.lastEngineSequence to it.orders)
            },
        )
    }
}

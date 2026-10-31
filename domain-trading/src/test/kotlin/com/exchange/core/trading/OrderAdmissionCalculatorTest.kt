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
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.LedgerPosting
import com.exchange.core.ledger.LedgerPostingSide
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderReservation
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderReservationStatus
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.OrderAdmissionResult.Reason
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실제 기존 도메인을 연결한다. 계산 후보이며 상태 반영·접수 중복·외부 저장은 검증 범위가 아니다. */
class OrderAdmissionCalculatorTest {
    private val user = UserId("user-1")
    private val usdt = AssetId("USDT")
    private val btc = AssetId("BTC")
    private val market = MarketDefinition(MarketId("BTC-USDT"), btc, usdt, 8)
    private val policy =
        TradingFeePolicySnapshot(
            productType = FeeProductType.SPOT,
            feeTier = FeeTier.NORMAL,
            scheduleVersion = 1,
            feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO),
        )
    private val order =
        OrderAdmissionInput(
            marketId = market.marketId,
            orderId = OrderId("order-1"),
            userId = user,
            side = Side.BUY,
            orderType = OrderType.LIMIT,
            timeInForce = TimeInForce.GTC,
            price = Price(100_000_000),
            quantity = Quantity(300_000_000),
        )
    private val ledgerId = "ledger-1"
    private val time = Instant.parse("2026-10-15T00:00:00Z")
    private val calculator =
        OrderAdmissionCalculator(
            OrderReservationCalculator(
                BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()),
            ),
        )

    @Test
    fun `BUY 300은 원본 1000을 바꾸지 않고 잔고 700 300과 정확한 예약 원장을 후보로 만든다`() {
        val originalBalance = balance()
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(balance = originalBalance)).candidate

        assertCandidate(candidate, originalBalance, balance(700_000_000, 300_000_000))
        assertEquals(balance(), originalBalance)
    }

    @Test
    fun `SELL 2개는 원본 BTC 3개를 바꾸지 않고 BTC 1개 2개의 예약 후보를 만든다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(200_000_000))
        val original = Balance(user, btc, Amount(300_000_000), Amount.ZERO)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = sell, balance = original)).candidate

        assertCandidate(
            candidate,
            original,
            Balance(user, btc, Amount(100_000_000), Amount(200_000_000)),
            expectedOrder = sell,
            expectedAsset = btc,
            reservedAmount = 200_000_000,
        )
        assertEquals(Balance(user, btc, Amount(300_000_000), Amount.ZERO), original)
    }

    @Test
    fun `SELL 2개에 BTC 1개만 있으면 BTC 부족 1개를 반환하고 원본을 유지한다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(200_000_000))
        val original = Balance(user, btc, Amount(100_000_000), Amount.ZERO)

        assertEquals(
            OrderAdmissionResult.Rejected(
                Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(btc, Amount(100_000_000), Amount(200_000_000), Amount(100_000_000)),
            ),
            calculate(order = sell, balance = original),
        )
        assertEquals(Balance(user, btc, Amount(100_000_000), Amount.ZERO), original)
    }

    @Test
    fun `SELL은 양수 수수료 정책을 보관해도 팔 BTC만 예약한다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(200_000_000))
        listOf(1_000L to 2_000L, 2_000L to 1_000L).forEach { (maker, taker) ->
            val applied =
                policy.copy(
                    feeTier = FeeTier.VIP,
                    scheduleVersion = 7,
                    feeRates = MakerTakerFeeRates(FeeRate(maker), FeeRate(taker)),
                )
            val original = btcBalance(300_000_000)
            val candidate =
                assertIs<OrderAdmissionResult.Calculated>(
                    calculate(order = sell, feePolicy = applied, balance = original),
                ).candidate

            assertCandidate(
                candidate,
                original,
                btcBalance(100_000_000, 200_000_000),
                expectedOrder = sell,
                expectedPolicy = applied,
                expectedAsset = btc,
                reservedAmount = 200_000_000,
            )
            assertEquals(btcBalance(300_000_000), original)
        }
    }

    @Test
    fun `SELL은 정확히 필요한 수량도 허용하고 기존 BTC hold를 유지한다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(200_000_000))
        listOf(
            btcBalance(200_000_000) to btcBalance(0, 200_000_000),
            btcBalance(300_000_000, 50_000_000) to btcBalance(100_000_000, 250_000_000),
        ).forEach { (original, expected) ->
            val preserved = original.copy()
            val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = sell, balance = original)).candidate

            assertCandidate(candidate, original, expected, expectedOrder = sell, expectedAsset = btc, reservedAmount = 200_000_000)
            assertEquals(preserved, original)
        }
    }

    @Test
    fun `SELL에 quote 잔고나 다른 사용자의 BTC를 연결하면 내부 오류다`() {
        val sell = order.copy(side = Side.SELL)
        listOf(balance(), btcBalance().copy(userId = UserId("other-user"))).forEach { wrong ->
            val preserved = wrong.copy()

            assertFailsWith<IllegalArgumentException> { calculate(order = sell, balance = wrong) }
            assertEquals(preserved, wrong)
        }
    }

    @Test
    fun `SELL의 잘못된 마켓 정책 자산 ID 원장 ID는 업무 거절로 숨기지 않는다`() {
        val sell = order.copy(side = Side.SELL)
        val original = btcBalance()
        assertFailsWith<IllegalArgumentException> {
            calculate(order = sell, balance = original, market = market.copy(marketId = MarketId("OTHER-USDT")))
        }
        assertFailsWith<IllegalArgumentException> {
            calculate(order = sell, balance = original, feePolicy = policy.copy(productType = FeeProductType.PERPETUAL_FUTURES))
        }
        (invalidIdentifiers() + listOf("", " ")).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { calculate(order = sell, balance = original, ledgerTransactionId = invalid) }
        }
        (invalidIdentifiers() + "asset:other").forEach { invalid ->
            val invalidAsset = AssetId(invalid)
            assertFailsWith<IllegalArgumentException> {
                calculate(order = sell, balance = original, market = market.copy(quoteAssetId = invalidAsset))
            }
            assertFailsWith<IllegalArgumentException> {
                calculate(order = sell, balance = original.copy(assetId = invalidAsset), market = market.copy(baseAssetId = invalidAsset))
            }
        }
        assertEquals(btcBalance(), original)
    }

    @Test
    fun `SELL의 잘못된 주문 ID도 기존 식별자 거절을 유지한다`() {
        val sell = order.copy(side = Side.SELL)
        val original = btcBalance()
        invalidIdentifiers().forEach { invalid ->
            val badMarket = MarketId(invalid)
            val badUser = UserId(invalid)
            listOf(
                Triple(sell.copy(orderId = OrderId(invalid)), market, original),
                Triple(sell.copy(marketId = badMarket), market.copy(marketId = badMarket), original),
                Triple(sell.copy(userId = badUser), market, original.copy(userId = badUser)),
            ).forEach { (input, definition, source) ->
                val preserved = source.copy()
                assertEquals(
                    OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER),
                    calculate(order = input, market = definition, balance = source),
                )
                assertEquals(preserved, source)
            }
        }
        val colonUser = UserId("user:other")
        assertEquals(
            OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER),
            calculate(order = sell.copy(userId = colonUser), balance = original.copy(userId = colonUser)),
        )
        assertEquals(btcBalance(), original)
    }

    @Test
    fun `SELL도 수량 단위 위반과 quote 대금 초과는 예약 후보를 만들지 않는다`() {
        val maximumFee = policy.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000_000), FeeRate(1_000_000)))
        listOf(
            Pair(order.copy(side = Side.SELL, price = Price(1), quantity = Quantity(1)), btcBalance(1)),
            Pair(
                order.copy(side = Side.SELL, price = Price(Long.MAX_VALUE), quantity = Quantity(200_000_000)),
                btcBalance(300_000_000),
            ),
            Pair(
                order.copy(side = Side.SELL, price = Price(Long.MAX_VALUE), quantity = Quantity(Long.MAX_VALUE)),
                btcBalance(Long.MAX_VALUE),
            ),
        ).forEach { (sell, original) ->
            val preserved = original.copy()
            assertEquals(
                OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION),
                calculate(order = sell, balance = original, feePolicy = maximumFee),
            )
            assertEquals(preserved, original)
        }

        // 최소 수량까지 허용하는 설정에서는 최대 수량도 대금 범위 안이면 여전히 허용한다.
        val fine = market.copy(priceTick = 100_000_000, quantityStep = 1)
        val maximum = order.copy(side = Side.SELL, quantity = Quantity(Long.MAX_VALUE))
        val original = btcBalance(Long.MAX_VALUE)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = maximum, market = fine, balance = original)).candidate
        assertCandidate(
            candidate,
            original,
            btcBalance(0, Long.MAX_VALUE),
            expectedOrder = maximum,
            expectedMarket = fine,
            expectedAsset = btc,
            reservedAmount = Long.MAX_VALUE,
        )
    }

    @Test
    fun `SELL도 hold 합산 초과만 중단하고 원본 총합에 새 상한을 만들지 않는다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(1))
        val fine = market.copy(priceTick = 100_000_000, quantityStep = 1)
        val overflowing = btcBalance(1, Long.MAX_VALUE)

        assertFailsWith<ArithmeticException> { calculate(order = sell, market = fine, balance = overflowing) }
        assertEquals(btcBalance(1, Long.MAX_VALUE), overflowing)

        val original = btcBalance(Long.MAX_VALUE, 1)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = sell, market = fine, balance = original)).candidate
        assertCandidate(
            candidate,
            original,
            btcBalance(Long.MAX_VALUE - 1, 2),
            expectedOrder = sell,
            expectedMarket = fine,
            expectedAsset = btc,
            reservedAmount = 1,
        )
        assertEquals(btcBalance(Long.MAX_VALUE, 1), original)
    }

    @Test
    fun `SELL을 반복 계산해도 원본과 이전 후보는 유지하고 명시 ID 시각을 보존한다`() {
        val sell = order.copy(side = Side.SELL, quantity = Quantity(200_000_000))
        val original = btcBalance(300_000_000)
        val preservedOrder = sell.copy()
        val preservedMarket = market.copy()
        val preservedPolicy = policy.copy()
        val first = assertIs<OrderAdmissionResult.Calculated>(calculate(order = sell, balance = original)).candidate
        val second = assertIs<OrderAdmissionResult.Calculated>(calculate(order = sell, balance = original)).candidate
        val laterTime = time.plusSeconds(60)
        val later =
            assertIs<OrderAdmissionResult.Calculated>(
                calculate(order = sell, balance = original, ledgerTransactionId = "ledger:later", occurredAt = laterTime),
            ).candidate

        listOf(first, second).forEach { candidate ->
            assertCandidate(
                candidate,
                original,
                btcBalance(100_000_000, 200_000_000),
                expectedOrder = sell,
                expectedAsset = btc,
                reservedAmount = 200_000_000,
            )
        }
        assertEquals("ledger:later", later.reserveTransaction.ledgerTransactionId)
        assertEquals(laterTime, later.reserveTransaction.occurredAt)
        assertEquals(first.reserveTransaction.sourceEventId, later.reserveTransaction.sourceEventId)
        assertEquals(first.reservation, later.reservation)
        assertEquals(first.nextBalance, later.nextBalance)
        assertEquals(first.reserveTransaction.postings, later.reserveTransaction.postings)
        assertEquals(btcBalance(300_000_000), original)
        assertEquals(preservedOrder, sell)
        assertEquals(preservedMarket, market)
        assertEquals(preservedPolicy, policy)
    }

    @Test
    fun `기존 hold 20은 유지하고 이번 주문의 300만 새 예약으로 설명한다`() {
        val original = balance(1_000_000_000, 20_000_000)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(balance = original)).candidate

        assertCandidate(candidate, original, balance(700_000_000, 320_000_000))
        assertEquals(balance(1_000_000_000, 20_000_000), original)
    }

    @Test
    fun `잔고 200으로 300이 필요하면 현재 필요 부족 금액을 담은 거절이다`() {
        val original = balance(200_000_000, 0)

        assertEquals(
            OrderAdmissionResult.Rejected(
                Reason.INSUFFICIENT_BALANCE,
                OrderAdmissionResult.InsufficientBalance(
                    usdt,
                    Amount(200_000_000),
                    Amount(300_000_000),
                    Amount(100_000_000),
                ),
            ),
            calculate(balance = original),
        )
        assertEquals(balance(200_000_000, 0), original)
    }

    @Test
    fun `정확히 필요한 300 전액을 예약한 후보는 가용액 0을 허용한다`() {
        val original = balance(300_000_000, 0)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(balance = original)).candidate

        assertCandidate(candidate, original, balance(0, 300_000_000))
    }

    @Test
    fun `maker taker 중 최대 0점2퍼센트 수수료 0점6을 예약하고 정책 전체를 보존한다`() {
        listOf(1_000L to 2_000L, 2_000L to 1_000L).forEach { (maker, taker) ->
            val applied =
                policy.copy(
                    feeTier = FeeTier.VIP,
                    scheduleVersion = 7,
                    feeRates = MakerTakerFeeRates(FeeRate(maker), FeeRate(taker)),
                )
            val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(feePolicy = applied)).candidate

            assertCandidate(
                candidate,
                balance(),
                balance(699_400_000, 300_600_000),
                expectedPolicy = applied,
                reservedAmount = 300_600_000,
                feeAmount = 600_000,
            )
        }
    }

    @Test
    fun `내부 대금 1의 1ppm 수수료도 올림해 총 2를 예약한다`() {
        val tinyOrder = order.copy(price = Price(1), quantity = Quantity(100_000_000))
        val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate(1)))
        val original = balance(2, 0)
        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(
                calculate(order = tinyOrder, feePolicy = applied, balance = original),
            ).candidate

        assertCandidate(
            candidate,
            original,
            balance(0, 2),
            expectedOrder = tinyOrder,
            expectedPolicy = applied,
            reservedAmount = 2,
            feeAmount = 1,
        )
    }

    @Test
    fun `BUY SELL 주문 수량 0은 값을 보정하지 않고 수량 거절이다`() {
        Side.entries.forEach { side ->
            val invalid = order.copy(side = side, quantity = Quantity.ZERO)

            assertEquals(OrderAdmissionResult.Rejected(Reason.INVALID_QUANTITY), calculate(order = invalid))
            assertEquals(Quantity.ZERO, invalid.quantity)
        }
    }

    @Test
    fun `BUY SELL의 MARKET IOC는 각각 미지원 조건 거절이다`() {
        Side.entries
            .flatMap { side ->
                listOf(
                    order.copy(side = side, orderType = OrderType.MARKET),
                    order.copy(side = side, timeInForce = TimeInForce.IOC),
                )
            }.forEach { unsupported ->
                assertEquals(
                    OrderAdmissionResult.Rejected(Reason.UNSUPPORTED_ORDER_CONDITION),
                    calculate(order = unsupported),
                )
            }
    }

    @Test
    fun `quote 최소 단위로 표현할 수 없는 대금은 반올림하거나 버리지 않는다`() {
        val fractional = order.copy(price = Price(1), quantity = Quantity(1))

        assertEquals(
            OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION),
            calculate(order = fractional),
        )
    }

    @Test
    fun `최종 대금이 정확히 Long 최대의 두 배면 정상 금액 거절이다`() {
        val tooLarge = order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(200_000_000))
        val original = balance(Long.MAX_VALUE, 0)

        assertEquals(
            OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION),
            calculate(order = tooLarge, balance = original),
        )
        assertEquals(balance(Long.MAX_VALUE, 0), original)
    }

    @Test
    fun `대금과 수수료가 각각 표현돼도 합계가 넘으면 정상 금액 거절이다`() {
        val maximum = order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(100_000_000))
        val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate.ZERO, FeeRate(1_000_000)))

        assertEquals(
            OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION),
            calculate(order = maximum, feePolicy = applied, balance = balance(Long.MAX_VALUE, 0)),
        )
    }

    @Test
    fun `중간 곱셈만 Long을 넘고 최종 대금이 최대값이면 후보를 만들 수 있다`() {
        val maximum = order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(100_000_000))
        val original = balance(Long.MAX_VALUE, 0)
        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(calculate(order = maximum, balance = original)).candidate

        assertCandidate(
            candidate,
            original,
            balance(0, Long.MAX_VALUE),
            expectedOrder = maximum,
            reservedAmount = Long.MAX_VALUE,
        )
    }

    @Test
    fun `유효한 필요액을 기존 hold에 더하다 넘치면 거절로 숨기지 않고 중단한다`() {
        val one = order.copy(price = Price(1), quantity = Quantity(100_000_000))
        val original = balance(1, Long.MAX_VALUE)

        assertFailsWith<ArithmeticException> { calculate(order = one, balance = original) }
        assertEquals(balance(1, Long.MAX_VALUE), original)
    }

    @Test
    fun `원본 총합이 Long을 넘어도 각 잔고 칸이 표현되면 후보를 허용한다`() {
        val one = order.copy(price = Price(1), quantity = Quantity(100_000_000))
        val original = balance(Long.MAX_VALUE, 1)
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = one, balance = original)).candidate

        assertCandidate(
            candidate,
            original,
            balance(Long.MAX_VALUE - 1, 2),
            expectedOrder = one,
            reservedAmount = 1,
        )
        assertEquals(balance(Long.MAX_VALUE, 1), original)
    }

    @Test
    fun `다른 사용자나 base 자산의 잔고를 연결하면 내부 오류다`() {
        listOf(balance().copy(userId = UserId("other-user")), balance().copy(assetId = btc)).forEach { wrong ->
            val original = wrong.copy()

            assertFailsWith<RuntimeException> { calculate(balance = wrong) }
            assertEquals(original, wrong)
        }
    }

    @Test
    fun `원본과 다른 마켓이나 비현물 서버 정책은 내부 오류다`() {
        assertFailsWith<RuntimeException> {
            calculate(market = market.copy(marketId = MarketId("OTHER-USDT")))
        }
        assertFailsWith<RuntimeException> {
            calculate(feePolicy = policy.copy(productType = FeeProductType.PERPETUAL_FUTURES))
        }
    }

    @Test
    fun `주문 원본의 세 ID는 길이 NUL 잘못된 서로게이트를 거절한다`() {
        invalidIdentifiers().forEach { invalid ->
            val marketId = MarketId(invalid)
            val userId = UserId(invalid)
            val cases =
                listOf(
                    Triple(order.copy(orderId = OrderId(invalid)), market, balance()),
                    Triple(order.copy(marketId = marketId), market.copy(marketId = marketId), balance()),
                    Triple(order.copy(userId = userId), market, balance().copy(userId = userId)),
                )
            cases.forEach { (original, definition, sourceBalance) ->
                assertEquals(
                    OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER),
                    calculate(order = original, market = definition, balance = sourceBalance),
                )
            }
        }
    }

    @Test
    fun `계정 키에 들어가는 사용자 ID의 콜론은 주문 식별자 거절이다`() {
        val invalidUser = UserId("user:other")

        assertEquals(
            OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER),
            calculate(order = order.copy(userId = invalidUser), balance = balance().copy(userId = invalidUser)),
        )
    }

    @Test
    fun `마켓이 가진 base quote 자산 ID의 잘못된 표현은 내부 오류다`() {
        (invalidIdentifiers() + "asset:other").forEach { invalid ->
            val asset = AssetId(invalid)
            assertFailsWith<RuntimeException> { calculate(market = market.copy(baseAssetId = asset)) }
            assertFailsWith<RuntimeException> {
                calculate(market = market.copy(quoteAssetId = asset), balance = balance().copy(assetId = asset))
            }
        }
    }

    @Test
    fun `서버 원장 ID의 빈값 길이 NUL 잘못된 서로게이트는 내부 오류다`() {
        (invalidIdentifiers() + listOf("", " ")).forEach { invalid ->
            assertFailsWith<RuntimeException> { calculate(ledgerTransactionId = invalid) }
        }
    }

    @Test
    fun `유효 Unicode 64 code point는 UTF16 길이가 길어도 모든 ID에 보존된다`() {
        val supplementary = "😀".repeat(64)
        val boundaryOrder =
            order.copy(marketId = MarketId(supplementary), orderId = OrderId(supplementary), userId = UserId(supplementary))
        val definition =
            market.copy(marketId = boundaryOrder.marketId, baseAssetId = AssetId("🟠".repeat(64)), quoteAssetId = AssetId("🪙".repeat(64)))
        val original = balance().copy(userId = boundaryOrder.userId, assetId = definition.quoteAssetId)

        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(
                calculate(order = boundaryOrder, market = definition, balance = original, ledgerTransactionId = supplementary),
            ).candidate

        assertEquals(boundaryOrder, candidate.order)
        assertEquals(definition, candidate.market)
        assertEquals(policy, candidate.feePolicySnapshot)
        assertEquals(original, candidate.previousBalance)
        assertEquals(original.copy(available = Amount(700_000_000), hold = Amount(300_000_000)), candidate.nextBalance)
        assertEquals(boundaryOrder.marketId, candidate.reservation.marketId)
        assertEquals(boundaryOrder.orderId, candidate.reservation.orderId)
        assertEquals(boundaryOrder.userId, candidate.reservation.userId)
        assertEquals(definition.quoteAssetId, candidate.reservation.assetId)
        assertEquals(supplementary, candidate.reserveTransaction.ledgerTransactionId)
        assertEquals(
            listOf(
                LedgerPosting(
                    "USER:$supplementary:${definition.quoteAssetId.value}:AVAILABLE",
                    definition.quoteAssetId,
                    LedgerPostingSide.DEBIT,
                    Amount(300_000_000),
                ),
                LedgerPosting(
                    "USER:$supplementary:${definition.quoteAssetId.value}:HOLD",
                    definition.quoteAssetId,
                    LedgerPostingSide.CREDIT,
                    Amount(300_000_000),
                ),
            ),
            candidate.reserveTransaction.postings,
        )
    }

    @Test
    fun `원본 사건 ID는 글자 수가 아닌 UTF8 바이트 길이를 사용한다`() {
        val unicodeOrder = order.copy(marketId = MarketId("가"), orderId = OrderId("😀"))
        val candidate =
            assertIs<OrderAdmissionResult.Calculated>(
                calculate(order = unicodeOrder, market = market.copy(marketId = unicodeOrder.marketId)),
            ).candidate

        // 독립 고정 바이트: 00000003 eab080 00000004 f09f9880.
        assertEquals(
            "RESERVE:v1:f74dc04a3a228e5d8c4d2388ddd3631f062eda740e2125fab790fdb3de7d3e4d",
            candidate.reserveTransaction.sourceEventId,
        )
        assertEquals(unicodeOrder, candidate.order)
    }

    @Test
    fun `콜론을 포함한 마켓 주문 튜플과 다른 마켓의 같은 주문 ID는 구별한다`() {
        val tuples = listOf("a:b" to "c", "a" to "b:c", "other-market" to "c")
        val sourceIds =
            tuples.map { (marketId, orderId) ->
                val original = order.copy(marketId = MarketId(marketId), orderId = OrderId(orderId))
                val candidate =
                    assertIs<OrderAdmissionResult.Calculated>(
                        calculate(order = original, market = market.copy(marketId = original.marketId)),
                    ).candidate
                assertEquals(original, candidate.order)
                candidate.reserveTransaction.sourceEventId
            }

        assertEquals(3, sourceIds.toSet().size)
    }

    @Test
    fun `주문 ID를 trim하거나 Unicode 정규화해서 다른 원본을 합치지 않는다`() {
        val identifiers = listOf(" order-1", "order-1 ", "é", "e\u0301")
        val sourceIds =
            identifiers.map { identifier ->
                val original = order.copy(orderId = OrderId(identifier))
                val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate(order = original)).candidate
                assertEquals(original, candidate.order)
                assertEquals(original.orderId, candidate.reservation.orderId)
                candidate.reserveTransaction.sourceEventId
            }

        assertEquals(4, sourceIds.toSet().size)
    }

    @Test
    fun `같은 명시 입력은 같은 후보이며 뒤 호출도 이전 후보와 원본을 바꾸지 않는다`() {
        val originalBalance = balance()
        val originalOrder = order.copy()
        val originalMarket = market.copy()
        val originalPolicy = policy.copy()
        val first = assertIs<OrderAdmissionResult.Calculated>(calculate(balance = originalBalance)).candidate
        val second = assertIs<OrderAdmissionResult.Calculated>(calculate(balance = originalBalance)).candidate
        val laterTime = time.plusSeconds(60)
        val later =
            assertIs<OrderAdmissionResult.Calculated>(
                calculate(ledgerTransactionId = "ledger:later", occurredAt = laterTime),
            ).candidate

        assertCandidate(first, balance(), balance(700_000_000, 300_000_000))
        assertCandidate(second, balance(), balance(700_000_000, 300_000_000))
        assertEquals("ledger:later", later.reserveTransaction.ledgerTransactionId)
        assertEquals(laterTime, later.reserveTransaction.occurredAt)
        assertEquals(first.reserveTransaction.sourceEventId, later.reserveTransaction.sourceEventId)
        assertEquals(balance(), originalBalance)
        assertEquals(originalOrder, order)
        assertEquals(originalMarket, market)
        assertEquals(originalPolicy, policy)
    }

    @Test
    fun `반환 원장의 분개 목록을 외부에서 바꿔 검증된 후보를 훼손하지 못한다`() {
        val candidate = assertIs<OrderAdmissionResult.Calculated>(calculate()).candidate
        val expectedPostings = candidate.reserveTransaction.postings.toList()

        assertFailsWith<UnsupportedOperationException> {
            (candidate.reserveTransaction.postings as MutableList<LedgerPosting>).clear()
        }
        assertEquals(expectedPostings, candidate.reserveTransaction.postings)
        assertCandidate(candidate, balance(), balance(700_000_000, 300_000_000))
    }

    @Test
    fun `bounded transition keeps exact BUY SELL reserve values without a reserve ledger`() {
        for (side in Side.entries) {
            for (fee in listOf(0L, 20_000L)) {
                val input = order.copy(side = side)
                val applied = policy.copy(feeRates = MakerTakerFeeRates(FeeRate(10_000L.coerceAtMost(fee)), FeeRate(fee)))
                val source = if (side == Side.BUY) balance(1_000_000_000, 20_000_000) else btcBalance(500_000_000, 20_000_000)
                val reserved = if (side == Side.BUY) 300_000_000 + 300 * fee else 300_000_000
                val transition =
                    assertIs<AdmissionTransitionResult.Calculated>(calculator.calculateTransition(input, market, applied, source))
                val reference =
                    assertIs<OrderAdmissionResult.Calculated>(calculate(input, market, applied, source)).candidate

                assertEquals(Amount(reserved), transition.reservation.reservedAmount)
                assertEquals(Amount(if (side == Side.BUY) 300 * fee else 0), transition.reservation.initialFeeReserveAmount)
                assertEquals(
                    source.copy(available = Amount(source.available.value - reserved), hold = Amount(20_000_000 + reserved)),
                    transition.nextBalance,
                )
                assertEquals(reference.nextBalance, transition.nextBalance)
                assertEquals(reference.reservation, transition.reservation)
                assertTrue(transition.hasPreviousBalance(source))
                assertFalse(transition.hasPreviousBalance(source.copy(available = Amount(source.available.value - 1))))
                assertFalse(transition.hasPreviousBalance(source.copy(userId = UserId("other"))))
                assertEquals(Amount(20_000_000), source.hold)
            }
        }
    }

    @Test
    fun `bounded transition preserves normal rejection and exact shortage before any source change`() {
        val inputs =
            listOf(
                order.copy(orderId = OrderId("bad\u0000id")) to Reason.INVALID_ORDER_IDENTIFIER,
                order.copy(orderType = OrderType.MARKET) to Reason.UNSUPPORTED_ORDER_CONDITION,
                order.copy(quantity = Quantity.ZERO) to Reason.INVALID_QUANTITY,
                order.copy(price = Price(1), quantity = Quantity(1)) to Reason.UNREPRESENTABLE_RESERVATION,
                order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(200_000_000)) to Reason.UNREPRESENTABLE_RESERVATION,
                order to Reason.INSUFFICIENT_BALANCE,
            )
        val source = balance(299_999_999, 20)
        for ((input, expected) in inputs) {
            val transition = assertIs<AdmissionTransitionResult.Rejected>(calculator.calculateTransition(input, market, policy, source))
            val reference = assertIs<OrderAdmissionResult.Rejected>(calculate(order = input, balance = source))
            assertEquals(expected, transition.reason)
            assertEquals(reference.reason, transition.reason)
            assertEquals(reference.insufficientBalance, transition.insufficientBalance)
        }
        val shortage = assertIs<AdmissionTransitionResult.Rejected>(calculator.calculateTransition(order, market, policy, source))
        assertEquals(
            OrderAdmissionResult.InsufficientBalance(usdt, Amount(299_999_999), Amount(300_000_000), Amount(1)),
            shortage.insufficientBalance,
        )
        assertEquals(balance(299_999_999, 20), source)
    }

    @Test
    fun `public calculation keeps static context ledger representation and shortage error precedence`() {
        val badMarket = market.copy(marketId = MarketId("different"))
        val invalid = order.copy(orderId = OrderId("bad\u0000id"))
        assertEquals(
            OrderAdmissionResult.Rejected(Reason.INVALID_ORDER_IDENTIFIER),
            calculate(order = invalid, market = badMarket, ledgerTransactionId = ""),
        )
        assertEquals(
            "주문과 마켓 정의가 다릅니다",
            assertFailsWith<IllegalArgumentException> { calculate(market = badMarket, ledgerTransactionId = "") }.message,
        )
        for (input in listOf(order, order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(200_000_000)))) {
            assertEquals(
                "원장 거래 식별자가 잘못됐습니다",
                assertFailsWith<IllegalArgumentException> {
                    calculate(
                        order = input,
                        balance = balance(0),
                        ledgerTransactionId = "",
                    )
                }.message,
            )
        }
        assertEquals(
            OrderAdmissionResult.Rejected(Reason.UNREPRESENTABLE_RESERVATION),
            calculate(order = order.copy(price = Price(Long.MAX_VALUE), quantity = Quantity(200_000_000)), balance = balance(0)),
        )
        assertIs<OrderAdmissionResult.Rejected>(
            calculate(balance = balance(0)),
        ).also { assertEquals(Reason.INSUFFICIENT_BALANCE, it.reason) }
    }

    private fun invalidIdentifiers() = listOf("x".repeat(65), "bad\u0000id", "\uD800", "\uDC00")

    private fun calculate(
        order: OrderAdmissionInput = this.order,
        market: MarketDefinition = this.market,
        feePolicy: TradingFeePolicySnapshot = policy,
        balance: Balance = balance(),
        ledgerTransactionId: String = ledgerId,
        occurredAt: Instant = time,
    ) = calculator.calculate(order, market, feePolicy, balance, ledgerTransactionId, occurredAt)

    private fun balance(
        available: Long = 1_000_000_000,
        hold: Long = 0,
    ) = Balance(user, usdt, Amount(available), Amount(hold))

    private fun btcBalance(
        available: Long = 300_000_000,
        hold: Long = 0,
    ) = Balance(user, btc, Amount(available), Amount(hold))

    private fun assertCandidate(
        candidate: OrderAdmissionCandidate,
        previous: Balance,
        next: Balance,
        expectedOrder: OrderAdmissionInput = order,
        expectedMarket: MarketDefinition = market,
        expectedPolicy: TradingFeePolicySnapshot = policy,
        expectedAsset: AssetId = usdt,
        reservedAmount: Long = 300_000_000,
        feeAmount: Long = 0,
    ) {
        assertEquals(expectedOrder, candidate.order)
        assertEquals(expectedMarket, candidate.market)
        assertEquals(expectedPolicy, candidate.feePolicySnapshot)
        assertEquals(previous, candidate.previousBalance)
        assertEquals(next, candidate.nextBalance)
        assertEquals(
            OrderReservation(
                marketId = expectedOrder.marketId,
                orderId = expectedOrder.orderId,
                userId = expectedOrder.userId,
                side = expectedOrder.side,
                assetId = expectedAsset,
                limitPrice = expectedOrder.price,
                initialQuantity = expectedOrder.quantity,
                remainingQuantity = expectedOrder.quantity,
                reservedAmount = Amount(reservedAmount),
                remainingAmount = Amount(reservedAmount),
                feePolicySnapshot = expectedPolicy,
                initialFeeReserveAmount = Amount(feeAmount),
                remainingFeeReserveAmount = Amount(feeAmount),
                status = OrderReservationStatus.ACTIVE,
                feeRemainder = FeeRemainder.ZERO,
                releasedAmount = null,
            ),
            candidate.reservation,
        )
        val transaction = candidate.reserveTransaction
        assertEquals(ledgerId, transaction.ledgerTransactionId)
        assertEquals(time, transaction.occurredAt)
        assertEquals(LedgerTransactionType.RESERVE, transaction.transactionType)
        // 독립 고정 바이트: 00000008 + BTC-USDT + 00000007 + order-1.
        assertEquals(
            "RESERVE:v1:93da2d89e22f67a13d2b1026ef4c70c430d7e2dcab84c86c606007ff42cdd355",
            transaction.sourceEventId,
        )
        assertEquals(
            listOf(
                LedgerPosting(
                    "USER:user-1:${expectedAsset.value}:AVAILABLE",
                    expectedAsset,
                    LedgerPostingSide.DEBIT,
                    Amount(reservedAmount),
                ),
                LedgerPosting("USER:user-1:${expectedAsset.value}:HOLD", expectedAsset, LedgerPostingSide.CREDIT, Amount(reservedAmount)),
            ),
            transaction.postings,
        )
        // 원본 총액 자체에 새 Long 상한을 만들지 않고 수학적 보존만 확인한다.
        assertEquals(
            BigInteger.valueOf(previous.available.value).add(BigInteger.valueOf(previous.hold.value)),
            BigInteger.valueOf(next.available.value).add(BigInteger.valueOf(next.hold.value)),
        )
    }
}

package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.Side
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import org.agrona.concurrent.UnsafeBuffer
import java.net.DatagramSocket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 같은 실제 UDP 노드의 자금 준비와 주문 접수를 연결한다. HTTP·DB·전송 대역은 없다. */
class DevelopmentOrderNodeTest {
    @Test
    fun `실제로 준비한 1000 USDT에서 BUY 300을 접수하면 700은 사용 가능하고 300은 예약된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()

            val result = assertIs<AdmissionResultPayload.Accepted>(request(config, command))

            assertEquals(1L, result.sequence)
            assertEquals(command.requestId, result.requestId)
            val snapshot = snapshot(node)
            assertEquals(
                700_000_000L,
                snapshot.balances
                    .single()
                    .available.value,
            )
            assertEquals(
                300_000_000L,
                snapshot.balances
                    .single()
                    .hold.value,
            )
            assertEquals(1L, snapshot.lastAdmissionSequence)
            val candidate =
                snapshot.completions
                    .single()
                    .admission.candidate
            assertEquals(command.order, candidate.order)
            assertEquals(300_000_000L, candidate.reservation.remainingAmount.value)
            val transaction = candidate.reserveTransaction
            assertEquals(command.ledgerTransactionId, transaction.ledgerTransactionId)
            assertEquals(command.occurredAt, transaction.occurredAt)
            assertEquals(listOf("USER:U1:USDT:AVAILABLE", "USER:U1:USDT:HOLD"), transaction.postings.map { it.accountId })
            assertEquals(listOf(300_000_000L, 300_000_000L), transaction.postings.map { it.amount.value })
            assertEquals(listOf("DEBIT", "CREDIT"), transaction.postings.map { it.side.name })
            assertEquals(1, snapshot.preparations.size)
            assertEquals(1, snapshot.requests.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `실제 최초 승인은 마켓 수수료 원장 시각을 바꾼 같은 원본의 재전송에도 그대로 남는다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()
            val first = assertIs<AdmissionResultPayload.Accepted>(request(config, command))
            val before = snapshotValues(snapshot(node))
            val changedContexts =
                contextChanges(command).map { it.second } +
                    command.copy(ledgerTransactionId = "ignored-ledger") +
                    command.copy(occurredAt = Instant.parse("2040-01-01T00:00:00.987654321Z"))

            (listOf(command) + changedContexts).forEach { repeated ->
                assertEquals(first, request(config, repeated))
                assertEquals(before, snapshotValues(snapshot(node)))
                assertEquals(first, recordedResult(snapshot(node), command))
                assertNull(node.error)
            }

            val current = snapshot(node)
            assertEquals(1L, current.lastAdmissionSequence)
            assertEquals(1, current.requests.size)
            assertEquals(1, current.completions.size)
            assertBalance(current, available = 700_000_000, hold = 300_000_000)
            assertEquals(command.market, current.requests.single().market)
            assertEquals(command.feePolicySnapshot, current.requests.single().feePolicySnapshot)
            assertEquals(command.ledgerTransactionId, current.requests.single().ledgerTransactionId)
            assertEquals(command.occurredAt, current.requests.single().occurredAt)
        }
    }

    @Test
    fun `빈 잔고의 최초 부족 거절은 준비와 문맥 변경 뒤에도 유지되고 새 요청만 승인된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            val command = order(requestId = "R2", orderId = "O2")
            val first = assertIs<AdmissionResultPayload.Rejected>(request(config, command))
            assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, first.reason)
            val shortage = assertNotNull(first.insufficientBalance)
            assertEquals(AssetId("USDT"), shortage.assetId)
            assertEquals(Amount.ZERO, shortage.available)
            assertEquals(Amount(300_000_000), shortage.required)
            assertEquals(Amount(300_000_000), shortage.shortfall)
            assertTrue(snapshot(node).balances.isEmpty())
            assertTrue(snapshot(node).pendingOrders.isEmpty())
            assertEquals(0L, snapshot(node).lastAdmissionSequence)

            prepare(config)
            val before = snapshotValues(snapshot(node))
            val changedContexts =
                contextChanges(command).map { it.second } +
                    command.copy(ledgerTransactionId = "ignored-rejected-ledger") +
                    command.copy(occurredAt = Instant.parse("2040-01-01T00:00:00.987654321Z"))
            (listOf(command) + changedContexts).forEach { repeated ->
                assertEquals(first, request(config, repeated))
                assertEquals(before, snapshotValues(snapshot(node)))
            }

            val accepted =
                assertIs<AdmissionResultPayload.Accepted>(
                    request(config, command.copy(requestId = "R3")),
                )
            assertEquals(1L, accepted.sequence)
            val current = snapshot(node)
            assertBalance(current, available = 700_000_000, hold = 300_000_000)
            assertEquals(listOf("R2", "R3"), current.requests.map { it.requestId })
            val recorded = assertIs<OrderAdmissionState.Result.Rejected>(current.requests.first().result)
            assertEquals(shortage, recorded.insufficientBalance)
            assertEquals(command.market, current.requests.first().market)
            assertEquals(command.feePolicySnapshot, current.requests.first().feePolicySnapshot)
            assertEquals(command.ledgerTransactionId, current.requests.first().ledgerTransactionId)
            assertEquals(command.occurredAt, current.requests.first().occurredAt)
            assertEquals(1, current.completions.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `신규 주문의 설정 불일치는 각각 사유 9이고 고친 같은 요청은 처음 접수된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config, amount = 2_000_000_000)
            val prototype = order().let { it.copy(order = it.order.copy(quantity = Quantity(100_000_000))) }
            val mismatches =
                listOf("주문 marketId" to prototype.copy(order = prototype.order.copy(marketId = MarketId("ETH-USDT")))) +
                    contextChanges(prototype)

            assertEquals(12, mismatches.size)
            mismatches.forEachIndexed { index, (field, mismatch) ->
                val correct =
                    prototype.copy(
                        requestId = "context-R$index",
                        order = prototype.order.copy(orderId = OrderId("context-O$index")),
                        ledgerTransactionId = "context-ledger-$index",
                    )
                val incorrect =
                    mismatch.copy(
                        requestId = correct.requestId,
                        order = mismatch.order.copy(orderId = correct.order.orderId),
                        ledgerTransactionId = correct.ledgerTransactionId,
                    )
                val before = snapshotValues(snapshot(node))

                val rejected = assertIs<AdmissionResultPayload.Rejected>(request(config, incorrect), field)
                assertEquals(AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH, rejected.reason, field)
                assertNull(rejected.insufficientBalance, field)
                assertEquals(incorrect.order.marketId.value, rejected.marketId, field)
                assertEquals(before, snapshotValues(snapshot(node)), field)
                assertTrue(snapshot(node).requests.none { it.requestId == correct.requestId }, field)
                assertEquals(0L, node.observation?.malformedMessages, field)
                assertEquals(rejected, node.observation?.admissionResult, field)

                val accepted = assertIs<AdmissionResultPayload.Accepted>(request(config, correct), field)
                assertEquals(index + 1L, accepted.sequence, field)
                val current = snapshot(node)
                assertBalance(current, available = 1_900_000_000L - index * 100_000_000L, hold = (index + 1L) * 100_000_000L)
                assertEquals(index + 1, current.completions.size, field)
                assertEquals(index + 1, current.requests.size, field)
                assertNull(node.error, field)
            }
        }
    }

    @Test
    fun `기존 요청의 내용 충돌이 설정 거절보다 먼저이고 새 요청의 주문 중복은 첫 거절로 남는다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()
            assertIs<AdmissionResultPayload.Accepted>(request(config, command))
            val before = snapshotValues(snapshot(node))

            val conflict =
                command.copy(
                    order = command.order.copy(price = Price(200_000_000)),
                    feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 2),
                )
            val changed = assertIs<AdmissionResultPayload.Rejected>(request(config, conflict))
            assertEquals(AdmissionResultPayload.Reason.REQUEST_CONTENT_CONFLICT, changed.reason)
            assertNull(changed.insufficientBalance)
            assertEquals(before, snapshotValues(snapshot(node)))

            val duplicate = command.copy(requestId = "R4", ledgerTransactionId = "duplicate-ledger")
            val mismatched = duplicate.copy(feePolicySnapshot = duplicate.feePolicySnapshot.copy(scheduleVersion = 2))
            val contextRejection = assertIs<AdmissionResultPayload.Rejected>(request(config, mismatched))
            assertEquals(AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH, contextRejection.reason)
            assertEquals(before, snapshotValues(snapshot(node)))

            val duplicateRejection = assertIs<AdmissionResultPayload.Rejected>(request(config, duplicate))
            assertEquals(AdmissionResultPayload.Reason.DUPLICATE_ORDER, duplicateRejection.reason)
            assertNull(duplicateRejection.insufficientBalance)
            val current = snapshot(node)
            assertBalance(current, available = 700_000_000, hold = 300_000_000)
            assertEquals(1L, current.lastAdmissionSequence)
            assertEquals(1, current.completions.size)
            assertEquals(listOf("R1", "R4"), current.requests.map { it.requestId })
            assertEquals(
                com.exchange.core.trading.OrderAdmissionResult.Reason.DUPLICATE_ORDER,
                assertIs<OrderAdmissionState.Result.Rejected>(current.requests.last().result).reason,
            )
            val afterDuplicate = snapshotValues(current)
            assertEquals(duplicateRejection, request(config, mismatched))
            assertEquals(afterDuplicate, snapshotValues(snapshot(node)))
            assertNull(node.error)
        }
    }

    @Test
    fun `1100이 필요한 첫 거절 뒤에도 300 주문은 순번 1로 승인된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val opening = snapshot(node).preparations.map { transactionValues(it.transaction) }
            val large = order().let { it.copy(order = it.order.copy(quantity = Quantity(1_100_000_000))) }
            val rejected = assertIs<AdmissionResultPayload.Rejected>(request(config, large))
            assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, rejected.reason)
            val shortage = assertNotNull(rejected.insufficientBalance)
            assertEquals(Amount(1_000_000_000), shortage.available)
            assertEquals(Amount(1_100_000_000), shortage.required)
            assertEquals(Amount(100_000_000), shortage.shortfall)
            assertBalance(snapshot(node), available = 1_000_000_000, hold = 0)
            assertEquals(0L, snapshot(node).lastAdmissionSequence)
            assertTrue(snapshot(node).pendingOrders.isEmpty())
            assertEquals(opening, snapshot(node).preparations.map { transactionValues(it.transaction) })

            val accepted = assertIs<AdmissionResultPayload.Accepted>(request(config, order("R2", "O2")))
            assertEquals(1L, accepted.sequence)
            assertBalance(snapshot(node), available = 700_000_000, hold = 300_000_000)
            assertEquals(2, snapshot(node).requests.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `다른 사용자의 같은 요청 ID는 각자의 잔고와 최초 결과로 판단한다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            prepare(config, preparation = "P2", user = "U2", ledger = "OPENING-2")
            val first = assertIs<AdmissionResultPayload.Accepted>(request(config, order()))
            val secondCommand = order(orderId = "O2", user = "U2").let { it.copy(order = it.order.copy(quantity = Quantity(200_000_000))) }
            val second = assertIs<AdmissionResultPayload.Accepted>(request(config, secondCommand))

            assertEquals(1L, first.sequence)
            assertEquals(2L, second.sequence)
            assertEquals("U2", second.userId)
            assertEquals("R1", second.requestId)
            assertEquals("O2", second.orderId)
            val current = snapshot(node)
            assertBalance(current, available = 700_000_000, hold = 300_000_000)
            assertBalance(current, available = 800_000_000, hold = 200_000_000, user = "U2")
            assertEquals(listOf("U1", "U2"), current.requests.map { it.order.userId.value })
            assertEquals(listOf("R1", "R1"), current.requests.map { it.requestId })
            assertEquals(2, current.preparations.size)
            assertEquals(2, current.completions.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `신규 식별자 오류보다 설정 거절이 먼저이고 기존 키는 코어 식별자 검사를 먼저 따른다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()
            val before = snapshotValues(snapshot(node))
            val invalidNew =
                command.copy(
                    requestId = "",
                    feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 2),
                )
            val mismatch = assertIs<AdmissionResultPayload.Rejected>(request(config, invalidNew))
            assertEquals(AdmissionResultPayload.Reason.EXECUTION_CONTEXT_MISMATCH, mismatch.reason)
            assertEquals(before, snapshotValues(snapshot(node)))

            val invalidIdentifier =
                assertIs<AdmissionResultPayload.Rejected>(
                    request(config, invalidNew.copy(feePolicySnapshot = command.feePolicySnapshot)),
                )
            assertEquals(AdmissionResultPayload.Reason.INVALID_REQUEST_IDENTIFIER, invalidIdentifier.reason)
            assertEquals(before, snapshotValues(snapshot(node)))
            assertIs<AdmissionResultPayload.Accepted>(request(config, command))
            val accepted = snapshotValues(snapshot(node))

            val invalidExisting =
                command.copy(
                    order = command.order.copy(orderId = OrderId("x".repeat(65))),
                    feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 2),
                )
            val rejected = assertIs<AdmissionResultPayload.Rejected>(request(config, invalidExisting))
            assertEquals(AdmissionResultPayload.Reason.INVALID_ORDER_IDENTIFIER, rejected.reason)
            assertNull(rejected.insufficientBalance)
            assertEquals(accepted, snapshotValues(snapshot(node)))
            assertNull(node.error)
        }
    }

    @Test
    fun `비영점 개발 요율의 BUY는 300 USDT와 최대 수수료 0점6을 함께 예약한다`() {
        val config = nonzeroConfig()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order().copy(feePolicySnapshot = config.feePolicySnapshot)
            val result = assertIs<AdmissionResultPayload.Accepted>(request(config, command))

            assertEquals(1L, result.sequence)
            val current = snapshot(node)
            assertBalance(current, available = 699_400_000, hold = 300_600_000)
            val candidate =
                current.completions
                    .single()
                    .admission.candidate
            assertEquals(config.feePolicySnapshot, candidate.feePolicySnapshot)
            assertEquals(config.feePolicySnapshot, candidate.reservation.feePolicySnapshot)
            assertEquals(Amount(300_600_000), candidate.reservation.reservedAmount)
            assertEquals(Amount(300_600_000), candidate.reservation.remainingAmount)
            assertEquals(Amount(600_000), candidate.reservation.initialFeeReserveAmount)
            assertEquals(listOf(300_600_000L, 300_600_000L), candidate.reserveTransaction.postings.map { it.amount.value })
            assertEquals(command.ledgerTransactionId, candidate.reserveTransaction.ledgerTransactionId)
            assertEquals(command.occurredAt, candidate.reserveTransaction.occurredAt)
            assertEquals(
                "OPENING",
                current.preparations
                    .single()
                    .transaction.transactionType.name,
            )
            assertEquals(1, current.requests.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `비영점 개발 요율의 SELL도 USDT 없이 준비한 BTC 세 개에서 두 개만 예약한다`() {
        val config = nonzeroConfig()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config, asset = "BTC", amount = 300_000_000)
            val command =
                order().let {
                    it.copy(
                        order = it.order.copy(side = Side.SELL, quantity = Quantity(200_000_000)),
                        feePolicySnapshot = config.feePolicySnapshot,
                    )
                }
            val result = assertIs<AdmissionResultPayload.Accepted>(request(config, command))

            assertEquals(1L, result.sequence)
            val current = snapshot(node)
            assertBalance(current, available = 100_000_000, hold = 200_000_000, asset = "BTC")
            assertEquals(listOf(AssetId("BTC")), current.balances.map { it.assetId })
            val candidate =
                current.completions
                    .single()
                    .admission.candidate
            assertEquals(command.order, candidate.order)
            assertEquals(config.feePolicySnapshot, candidate.reservation.feePolicySnapshot)
            assertEquals(Amount(200_000_000), candidate.reservation.reservedAmount)
            assertEquals(Amount.ZERO, candidate.reservation.initialFeeReserveAmount)
            val transaction = candidate.reserveTransaction
            assertEquals("RESERVE", transaction.transactionType.name)
            assertEquals(command.ledgerTransactionId, transaction.ledgerTransactionId)
            assertEquals(command.occurredAt, transaction.occurredAt)
            assertEquals(listOf("USER:U1:BTC:AVAILABLE", "USER:U1:BTC:HOLD"), transaction.postings.map { it.accountId })
            assertEquals(listOf(AssetId("BTC"), AssetId("BTC")), transaction.postings.map { it.assetId })
            assertEquals(listOf(200_000_000L, 200_000_000L), transaction.postings.map { it.amount.value })
            assertEquals(listOf("DEBIT", "CREDIT"), transaction.postings.map { it.side.name })
            assertEquals(1, current.preparations.size)
            assertEquals(1, current.requests.size)
            assertNull(node.error)
        }
    }

    @Test
    fun `실제 승인 응답을 수용하지 않아 대기가 끝나도 같은 요청은 첫 예약과 순번을 돌려준다`() {
        val config = config().copy(resultTimeoutMs = 250)
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()
            var acceptResponse = false
            // 수신 결과를 버리는 지점만 통제한다. 요청·업무 적용·응답 전송은 실제 UDP 경로다.
            DevelopmentOrderClient(config, acceptResponse = { acceptResponse }).use { client ->
                val unknown = assertIs<OrderRequestOutcome.Unconfirmed>(client.request(command))
                assertTrue(unknown.transportAccepted)
                assertTrue(unknown.reason.contains("결과 미확인"))
                assertTrue(unknown.reason.contains("같은 사용자·요청 ID"))
                eventually {
                    assertNull(node.error, "응답을 폐기한 주문을 처리하던 노드가 실패했다")
                    node.observation
                        ?.snapshot
                        ?.requests
                        ?.any { it.requestId == command.requestId } == true
                }
                val first = assertIs<AdmissionResultPayload.Accepted>(recordedResult(snapshot(node), command))
                val before = snapshotValues(snapshot(node))
                assertBalance(snapshot(node), available = 700_000_000, hold = 300_000_000)

                acceptResponse = true
                val confirmed = assertIs<OrderRequestOutcome.Confirmed>(client.request(command))
                assertEquals(first, confirmed.result)
                assertEquals(1L, first.sequence)
                assertEquals(before, snapshotValues(snapshot(node)))
                assertEquals(1, snapshot(node).completions.size)
                assertEquals(1, snapshot(node).requests.size)
                assertNull(node.error)
            }
        }
    }

    @Test
    fun `실제 체결 뒤 BUY 응답을 잃어도 같은 요청 재전송은 잔고 원장 호가와 완료를 바꾸지 않는다`() {
        val config = config().copy(resultTimeoutMs = 250)
        val collector = FundingVerificationCollector()
        DevelopmentFundingNode.start(config, collector::decorate).use { node ->
            prepare(config, preparation = "P-BUYER", user = "U1", ledger = "OPENING-BUYER")
            prepare(config, preparation = "P-SELLER", user = "U2", asset = "BTC", amount = 100_000_000, ledger = "OPENING-SELLER")
            val sell =
                order(
                    "R-SELL",
                    "O-SELL",
                    "U2",
                ).let { it.copy(order = it.order.copy(side = Side.SELL, quantity = Quantity(100_000_000))) }
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(request(config, sell)).sequence)
            assertEquals(1, snapshot(node).completions.size)
            val buy = order("R-BUY", "O-BUY").let { it.copy(order = it.order.copy(quantity = Quantity(100_000_000))) }
            var acceptResponse = false

            // 응답 수신만 폐기한다. 예약·내부 실행·체결·정산은 실제 UDP 노드가 수행한다.
            DevelopmentOrderClient(config, acceptResponse = { acceptResponse }).use { client ->
                val unconfirmed = assertIs<OrderRequestOutcome.Unconfirmed>(client.request(buy))
                assertTrue(unconfirmed.transportAccepted)
                assertTrue(unconfirmed.reason.contains("결과 미확인"))
                eventually {
                    assertNull(node.error, "응답을 폐기한 BUY를 처리하던 노드가 실패했다")
                    node.observation
                        ?.snapshot
                        ?.completions
                        ?.size == 2
                }
                val settled = snapshot(node)
                val first = assertIs<AdmissionResultPayload.Accepted>(recordedResult(settled, buy))
                assertEquals(2L, first.sequence)
                assertBalance(settled, available = 900_000_000, hold = 0, user = "U1", asset = "USDT")
                assertBalance(settled, available = 100_000_000, hold = 0, user = "U1", asset = "BTC")
                assertBalance(settled, available = 100_000_000, hold = 0, user = "U2", asset = "USDT")
                assertBalance(settled, available = 0, hold = 0, user = "U2", asset = "BTC")
                assertEquals(5, settled.ledgerTransactions.size)
                assertEquals(2, settled.ledgerTransactions.count { it.transactionType == LedgerTransactionType.OPENING })
                assertEquals(2, settled.ledgerTransactions.count { it.transactionType == LedgerTransactionType.RESERVE })
                assertEquals(1, settled.ledgerTransactions.count { it.transactionType == LedgerTransactionType.SETTLEMENT })
                assertTrue(settled.reservations.all { it.remainingAmount == Amount.ZERO })
                val stateBeforeReplay = snapshotValues(settled)

                // 호가는 서비스 스레드의 callback 직후 수집한 실제 증거에서 읽는다.
                val observations = mutableListOf<Map<String, Any?>>()
                eventually {
                    observations += collector.drainFields(config)
                    observations.any { it["inputKind"] == "EXECUTION" && it["executionSequence"] == 2L }
                }
                val execution = observations.single { it["inputKind"] == "EXECUTION" && it["executionSequence"] == 2L }
                val tradingBeforeReplay = execution["trading"] as Map<*, *>
                val book = (tradingBeforeReplay["books"] as List<*>).single() as Map<*, *>
                assertEquals("BTC-USDT", book["marketId"])
                assertEquals(2L, book["lastEngineSequence"])
                assertEquals(emptyList<Any>(), book["orders"])

                acceptResponse = true
                val confirmed = assertIs<OrderRequestOutcome.Confirmed>(client.request(buy))

                assertEquals(first, confirmed.result)
                assertEquals(stateBeforeReplay, snapshotValues(snapshot(node)))
                val replayObservations = mutableListOf<Map<String, Any?>>()
                eventually {
                    replayObservations += collector.drainFields(config)
                    replayObservations.any { it["inputKind"] == "ORDER" && it["requestId"] == buy.requestId }
                }
                val replay = replayObservations.single { it["inputKind"] == "ORDER" && it["requestId"] == buy.requestId }
                // 확정 callback 자체의 시각은 새 입력마다 다르다. 업무 완료·정산의 최초 시각은 그대로여야 한다.
                val tradingAfterReplay = replay["trading"] as Map<*, *>
                assertEquals(
                    tradingBeforeReplay.filterKeys { it != "callbackTimestamp" },
                    tradingAfterReplay.filterKeys { it != "callbackTimestamp" },
                    "최초 완료·호가·현재 예약의 전체 업무 증거가 유지된다",
                )
                assertEquals(settled.completions, snapshot(node).completions, "최초 완료의 시각·전체 정산도 유지된다")
                assertTrue(replayObservations.none { it["inputKind"] == "EXECUTION" }, "최초 승인의 재전송은 새 실행을 만들지 않는다")
                assertNull(collector.verificationFailure)
                assertNull(node.error)
            }
        }
    }

    @Test
    fun `잘린 주문과 미지원 명령 뒤의 같은 정상 주문은 최초 순번으로 접수된다`() {
        val config = config()
        DevelopmentFundingNode.start(config).use { node ->
            prepare(config)
            val command = order()
            val bytes = AdmissionCommandCodec().encode(command)
            val unknown =
                bytes.copyOf().apply {
                    this[2] = 99
                    this[3] = 0
                }
            sendRaw(config, listOf(bytes.copyOf(bytes.size - 1), unknown))
            eventually { node.observation?.malformedMessages == 2L }
            assertNull(node.observation?.admissionResult)
            assertNull(node.observation?.failure)
            assertNull(node.error)

            val result = assertIs<AdmissionResultPayload.Accepted>(request(config, command))
            assertEquals(1L, result.sequence)
            val current = snapshot(node)
            assertBalance(current, available = 700_000_000, hold = 300_000_000)
            assertEquals(1, current.preparations.size)
            assertEquals(1, current.completions.size)
            assertEquals(1, current.requests.size)
            assertEquals(2L, node.observation?.malformedMessages)
            assertNull(node.error)
        }
    }

    @Test
    fun `독립 실제 노드 둘에 같은 입력을 순차 적용하면 업무 의미 필드가 같다`() {
        val observations =
            listOf(config(), config()).map { config ->
                DevelopmentFundingNode.start(config).use { node ->
                    val initiallyRejected = order("R0", "O0")
                    val firstRejection = assertIs<AdmissionResultPayload.Rejected>(request(config, initiallyRejected))
                    prepare(config)
                    val acceptedCommand = order()
                    val accepted = assertIs<AdmissionResultPayload.Accepted>(request(config, acceptedCommand))
                    assertEquals(accepted, request(config, acceptedCommand))
                    val large = order("R2", "O2").let { it.copy(order = it.order.copy(quantity = Quantity(800_000_000))) }
                    val shortage = assertIs<AdmissionResultPayload.Rejected>(request(config, large))
                    assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, shortage.reason)
                    assertEquals(Amount(100_000_000), assertNotNull(shortage.insufficientBalance).shortfall)
                    assertEquals(firstRejection, request(config, initiallyRejected))

                    val current = snapshot(node)
                    assertBalance(current, available = 700_000_000, hold = 300_000_000)
                    assertEquals(1L, current.lastAdmissionSequence)
                    assertEquals(1, current.completions.size)
                    assertEquals(1, current.preparations.size)
                    assertEquals(listOf("R0", "R1", "R2"), current.requests.map { it.requestId })
                    assertNull(node.error)
                    snapshotValues(current)
                }
            }

        // 서로 독립된 상태에 같은 입력을 적용한 검사이며, 같은 클러스터의 복제·HA 검사는 아니다.
        assertEquals(observations[0], observations[1])
    }

    private fun order(
        requestId: String = "R1",
        orderId: String = "O1",
        user: String = "U1",
    ): AdmissionCommandPayload = admissionCommand(requestId, orderId).let { it.copy(order = it.order.copy(userId = UserId(user))) }

    private fun prepare(
        config: DevelopmentFundingSettings,
        preparation: String = "P1",
        user: String = "U1",
        asset: String = "USDT",
        amount: Long = 1_000_000_000,
        ledger: String = "OPENING-1",
    ) {
        DevelopmentFundingClient(config).use { client ->
            val opening = OpeningBalance(preparation, UserId(user), AssetId(asset), Amount(amount))
            val outcome =
                assertIs<FundingRequestOutcome.Confirmed>(
                    client.request(
                        DevelopmentBalanceCommandPayload(opening, ledger, Instant.parse("2026-10-20T00:00:00Z")),
                    ),
                )
            assertEquals(
                opening,
                assertIs<DevelopmentBalanceResultPayload.Prepared>(outcome.result).result.opening,
            )
        }
    }

    private fun request(
        config: DevelopmentFundingSettings,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload =
        DevelopmentOrderClient(config).use { client ->
            assertIs<OrderRequestOutcome.Confirmed>(client.request(command)).result
        }

    private fun contextChanges(command: AdmissionCommandPayload): List<Pair<String, AdmissionCommandPayload>> =
        listOf(
            "마켓 정의의 ID" to command.copy(market = command.market.copy(marketId = MarketId("ETH-USDT"))),
            "기준 자산" to command.copy(market = command.market.copy(baseAssetId = AssetId("ETH"))),
            "표시 자산" to command.copy(market = command.market.copy(quoteAssetId = AssetId("KRW"))),
            "수량 scale" to command.copy(market = command.market.copy(baseAssetScale = 7)),
            "가격 단위" to command.copy(market = command.market.copy(priceTick = 2)),
            "수량 단위" to command.copy(market = command.market.copy(quantityStep = 200_000_000)),
            "상품" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES)),
            "수수료 등급" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(feeTier = FeeTier.VIP)),
            "수수료 버전" to command.copy(feePolicySnapshot = command.feePolicySnapshot.copy(scheduleVersion = 2)),
            "maker 요율" to
                command.copy(
                    feePolicySnapshot =
                        command.feePolicySnapshot.copy(
                            feeRates = command.feePolicySnapshot.feeRates.copy(makerFeeRate = FeeRate(1_000)),
                        ),
                ),
            "taker 요율" to
                command.copy(
                    feePolicySnapshot =
                        command.feePolicySnapshot.copy(
                            feeRates = command.feePolicySnapshot.feeRates.copy(takerFeeRate = FeeRate(2_000)),
                        ),
                ),
        )

    private fun snapshot(node: DevelopmentFundingNode): TradingState.Snapshot {
        eventually {
            assertNull(node.error, "자동 실행 중 노드가 실패했습니다")
            node.observation?.snapshot?.let { it.pendingOrders.isEmpty() && it.completions.size.toLong() == it.lastAdmissionSequence } ==
                true
        }
        val current = assertNotNull(node.observation?.snapshot)
        assertTrue(current.pendingOrders.isEmpty(), "승인된 주문은 자동 실행 뒤 처리 대기에 남지 않는다")
        assertEquals(current.lastAdmissionSequence, current.completions.size.toLong())
        return current
    }

    private fun recordedResult(
        snapshot: TradingState.Snapshot,
        command: AdmissionCommandPayload,
    ): AdmissionResultPayload =
        AdmissionResultPayload.from(
            command,
            snapshot.requests
                .single {
                    it.requestId == command.requestId &&
                        it.order.userId == command.order.userId
                }.result,
        )

    private fun assertBalance(
        snapshot: TradingState.Snapshot,
        available: Long,
        hold: Long,
        user: String = "U1",
        asset: String = "USDT",
    ) {
        val balance = snapshot.balances.single { it.userId == UserId(user) && it.assetId == AssetId(asset) }
        assertEquals(Amount(available), balance.available)
        assertEquals(Amount(hold), balance.hold)
    }

    private fun snapshotValues(snapshot: TradingState.Snapshot): List<Any?> =
        listOf(
            snapshot.balances,
            snapshot.lastAdmissionSequence,
            snapshot.reservations,
            snapshot.ledgerTransactions.map(::transactionValues),
            snapshot.completions.map {
                // 서로 독립된 노드는 확정 콜백 시각이 다르므로 시각은 동등성 대상이 아니다.
                listOf(
                    it.admission.sequence,
                    it.executionId,
                    it.matchingResult,
                    it.settlement.balanceChanges,
                    it.settlement.reservationChanges,
                )
            },
            snapshot.pendingOrders.map { recordValues(it) },
            snapshot.preparations.map { listOf(it.result, transactionValues(it.transaction)) },
            snapshot.requests.map {
                listOf(
                    it.requestId,
                    it.order,
                    it.market,
                    it.feePolicySnapshot,
                    it.ledgerTransactionId,
                    it.occurredAt,
                    resultValues(it.result),
                )
            },
        )

    private fun recordValues(record: OrderAdmissionState.Record): List<Any?> =
        record.candidate.let {
            listOf(
                record.sequence,
                it.order,
                it.market,
                it.feePolicySnapshot,
                it.previousBalance,
                it.nextBalance,
                it.reservation,
                transactionValues(it.reserveTransaction),
            )
        }

    private fun resultValues(result: OrderAdmissionState.Result): List<Any?> =
        when (result) {
            is OrderAdmissionState.Result.Accepted -> listOf("승인", recordValues(result.record))
            is OrderAdmissionState.Result.Rejected -> listOf("거절", result.reason, result.insufficientBalance)
        }

    private fun transactionValues(transaction: LedgerTransaction): List<Any?> =
        listOf(
            transaction.ledgerTransactionId,
            transaction.sourceEventId,
            transaction.transactionType,
            transaction.occurredAt,
            transaction.postings,
        )

    private fun nonzeroConfig(): DevelopmentFundingSettings {
        val config = config()
        return config.copy(
            markets =
                DevelopmentMarketRegistry(
                    listOf(
                        DevelopmentMarketContext(
                            config.market,
                            config.feePolicySnapshot.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000))),
                        ),
                    ),
                ),
        )
    }

    private fun sendRaw(
        config: DevelopmentFundingSettings,
        messages: List<ByteArray>,
    ) {
        val transportFailure = AtomicReference<Throwable?>()
        val context =
            AeronCluster
                .Context()
                .errorHandler { transportFailure.compareAndSet(null, it) }
                .aeronDirectoryName(config.driverDirectory)
                .ingressChannel("aeron:udp?term-length=64k")
                .ingressEndpoints("0=${config.endpoint(0)}")
                .egressChannel(config.udp(7))
                .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs))
                .egressListener { _, _, _, _, _, _ -> }
        try {
            AeronCluster.connect(context).use { connection ->
                messages.forEach { bytes ->
                    val buffer = UnsafeBuffer(bytes)
                    val start = System.nanoTime()
                    var position = connection.offer(buffer, 0, bytes.size)
                    while (position <= 0) {
                        assertNull(transportFailure.get(), "손상 입력 전송 중 연결이 실패했습니다")
                        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "원본 바이트 전송 대기 초과")
                        assertTrue(
                            position == Publication.BACK_PRESSURED || position == Publication.ADMIN_ACTION,
                            "원본 바이트 전송이 종단 반환값으로 끝났습니다: $position",
                        )
                        connection.pollEgress()
                        connection.sendKeepAlive()
                        Thread.sleep(1)
                        position = connection.offer(buffer, 0, bytes.size)
                    }
                }
                assertNull(transportFailure.get())
            }
        } finally {
            context.close()
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val start = System.nanoTime()
        while (!condition()) {
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "실제 노드 관찰 대기 초과")
            Thread.sleep(1)
        }
    }

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(Files.createTempDirectory("admission98-").resolve("run"), base)
            } catch (_: java.net.BindException) {
                // 다른 실행과 포트가 겹치면 테스트용 묶음만 다시 선택한다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트를 확보하지 못했습니다")
    }
}

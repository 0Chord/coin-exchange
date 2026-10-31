package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.AdmissionCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionCodec
import com.exchange.core.api.trading.infrastructure.codec.ExecuteAdmissionPayload
import com.exchange.core.api.trading.infrastructure.codec.admissionCommand
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.OrderId
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.MarketDefinition
import com.exchange.core.trading.OrderAdmissionState
import com.exchange.core.trading.TradingState
import io.aeron.ChannelUri
import java.net.DatagramSocket
import java.nio.file.Files
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 같은 실행의 실제 UDP 전송 원본과 Archive 기록을 대조한다. 코어 재적용·복구는 하지 않는다. */
class AeronRecordedInputTest {
    @Test
    fun `자금 준비와 주문의 실제 Archive 내용과 끝 위치가 원본과 같고 읽기는 돈을 바꾸지 않는다`() {
        val config = config()
        startObservedTradingNode(config).use { node ->
            val expected = listOf(fund(node), send(node, order()))
            assertEquals(1L, assertIs<AdmissionResultPayload.Accepted>(lastExternalObservation(node)?.admissionResult).sequence)
            val before = snapshotValues(assertNotNull(node.captureObservation()?.snapshot))
            val read = completed(reader(node).read(assertNotNull(node.observation).position))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            assertEquals(expected.map { it.position }, read.inputs.filter { it.clusterSessionId >= 0 }.map { it.position })
            assertTrue(read.controls.isNotEmpty(), "실제 Cluster 제어 메시지와 업무 입력을 구분한다")
            assertEquals(before, snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
            val balance = assertNotNull(node.captureObservation()?.snapshot).balances.single()
            assertEquals(Amount(700_000_000), balance.available)
            assertEquals(Amount(300_000_000), balance.hold)
            println(
                "Archive MATCH recording=${read.recording} S=${read.startPosition} L=${read.limitPosition} consumed=${read.consumedPosition} inputs=${read.inputs.size} sync=${node.storageLevels}",
            )
        }
    }

    @Test
    fun `입력 네 건과 반복 R1 두 위치를 읽어도 예약과 RESERVE는 한 번뿐이다`() {
        val config = config()
        startObservedTradingNode(config).use { node ->
            val expected = mutableListOf(fund(node))
            val first = order()
            expected.add(send(node, first))
            val accepted = assertIs<AdmissionResultPayload.Accepted>(lastExternalObservation(node)?.admissionResult)
            val shortage = order("R2", "O2").let { it.copy(order = it.order.copy(quantity = Quantity(800_000_000))) }
            expected.add(send(node, shortage))
            val rejected = assertIs<AdmissionResultPayload.Rejected>(lastExternalObservation(node)?.admissionResult)
            assertEquals(AdmissionResultPayload.Reason.INSUFFICIENT_BALANCE, rejected.reason)
            assertEquals(Amount(700_000_000), assertNotNull(rejected.insufficientBalance).available)
            assertEquals(Amount(800_000_000), assertNotNull(rejected.insufficientBalance).required)
            assertEquals(Amount(100_000_000), assertNotNull(rejected.insufficientBalance).shortfall)
            expected.add(send(node, first))
            assertEquals(accepted, lastExternalObservation(node)?.admissionResult)

            val before = assertNotNull(node.captureObservation()?.snapshot)
            assertRepresentativeState(before)
            val read = completed(reader(node).read(assertNotNull(node.observation).position))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            assertEquals(5, read.inputs.size)
            assertEquals(expected.map { it.position }, read.inputs.filter { it.clusterSessionId >= 0 }.map { it.position })
            assertTrue(read.inputs.zipWithNext().all { (left, right) -> left.position < right.position })
            assertContentEquals(
                read.inputs.filter { it.clusterSessionId >= 0 }[1].bytes,
                read.inputs.filter { it.clusterSessionId >= 0 }[3].bytes,
            )
            assertEquals(snapshotValues(before), snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
            assertRepresentativeState(assertNotNull(node.captureObservation()?.snapshot))
        }
    }

    @Test
    fun `완전히 읽은 실물 기록에서 바이트 의미 필드 위치 순서와 건수의 차이를 각각 발견한다`() {
        startObservedTradingNode(config()).use { node ->
            val expected = listOf(fund(node), send(node, order()))
            val read = completed(reader(node).read(assertNotNull(node.observation).position))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            val allExpected = withExecutions(node, expected, read.limitPosition)
            val original = expected.last()

            fun replacingOriginal(changed: ExpectedRecordedInput) = allExpected.map { if (it === original) changed else it }
            val changedBytes = original.bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val bytesOnly = compareRecordedInputs(replacingOriginal(original.copy(bytes = changedBytes)), read)
            assertEquals(RecordingComparisonStatus.MISMATCH, bytesOnly.status)
            assertTrue(bytesOnly.reasons.any { it.contains("원본 바이트") })
            // 실제 기록과 캡처 바이트는 그대로 두고 기대 업무 값만 바꾼다. byte-only 검사는 이 반례를 놓친다.
            original.values.keys.forEach { field ->
                val changed = original.copy(values = original.values + (field to "다른 기대값:$field"))
                val result = compareRecordedInputs(replacingOriginal(changed), read)
                assertEquals(RecordingComparisonStatus.MISMATCH, result.status, field)
                assertTrue(result.reasons.any { it.contains(" $field:") }, field)
                assertTrue(result.reasons.none { it.contains("원본 바이트") }, field)
            }
            listOf(allExpected.reversed(), allExpected.dropLast(1), allExpected + original).forEach { wrong ->
                assertEquals(RecordingComparisonStatus.MISMATCH, compareRecordedInputs(wrong, read).status)
            }
            val wrongPosition = original.copy(position = original.position + 32)
            val positionResult = compareRecordedInputs(replacingOriginal(wrongPosition), read)
            assertEquals(RecordingComparisonStatus.MISMATCH, positionResult.status)
            assertTrue(positionResult.reasons.any { it.contains("처리 위치") })
            assertEquals(RecordingComparisonStatus.UNVERIFIABLE, compareRecordedInputs(emptyList(), read).status)
        }
    }

    @Test
    fun `L 뒤에 새 주문이 기록돼도 고정한 끝까지만 읽고 현재 자금은 그대로 둔다`() {
        startObservedTradingNode(config()).use { node ->
            val first = order()
            val expected =
                listOf(
                    fund(node),
                    send(node, first),
                    send(node, order("R2", "O2").let { it.copy(order = it.order.copy(quantity = Quantity(800_000_000))) }),
                    send(node, first),
                )
            val limit = expected.last().position
            val later = send(node, order("R3", "O3").let { it.copy(order = it.order.copy(quantity = Quantity(100_000_000))) })
            assertTrue(later.position > limit)
            val beforeReading = snapshotValues(assertNotNull(node.captureObservation()?.snapshot))
            val read = completed(reader(node).read(limit))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            assertEquals(limit, read.consumedPosition)
            assertEquals(5, read.inputs.size)
            assertTrue(read.inputs.none { it.position == later.position })
            assertEquals(beforeReading, snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
            val current = assertNotNull(node.captureObservation()?.snapshot)
            assertEquals(Amount(600_000_000), current.balances.single().available)
            assertEquals(Amount(400_000_000), current.balances.single().hold)
            assertEquals(2L, current.lastAdmissionSequence)
        }
    }

    @Test
    fun `변경한 정책 원장 시각의 재전송은 실제 입력대로 남고 엔진의 첫 승인은 유지된다`() {
        startObservedTradingNode(config()).use { node ->
            val expected = mutableListOf(fund(node))
            val first = order()
            expected.add(send(node, first))
            val accepted = assertIs<AdmissionResultPayload.Accepted>(lastExternalObservation(node)?.admissionResult)
            val before = snapshotValues(assertNotNull(node.captureObservation()?.snapshot))
            val changed =
                first.copy(
                    feePolicySnapshot =
                        first.feePolicySnapshot.copy(
                            scheduleVersion = 7,
                            feeRates = MakerTakerFeeRates(FeeRate(333), FeeRate(777)),
                        ),
                    ledgerTransactionId = "재전송-원장",
                    occurredAt = Instant.parse("2040-01-01T00:00:00.987654321Z"),
                )
            expected.add(send(node, changed))
            assertEquals(accepted, lastExternalObservation(node)?.admissionResult)
            assertEquals(before, snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
            val read = completed(reader(node).read(assertNotNull(node.observation).position))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            assertEquals(changed, AdmissionCommandCodec().decode(read.inputs.last { it.clusterSessionId >= 0 }.bytes))
            val request = assertNotNull(node.captureObservation()?.snapshot).requests.single()
            assertEquals(first.feePolicySnapshot, request.feePolicySnapshot)
            assertEquals(first.ledgerTransactionId, request.ledgerTransactionId)
            assertEquals(first.occurredAt, request.occurredAt)
            assertEquals(before, snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
        }
    }

    @Test
    fun `한글 나노초 비영점 수수료의 큰 주문이 실제 UDP에서 나뉘어도 원문과 끝 위치를 복원한다`() {
        val market = MarketDefinition(MarketId("장".repeat(64)), AssetId("비".repeat(64)), AssetId("유".repeat(64)), 8)
        val basic = config()
        val config =
            basic.copy(
                markets =
                    DevelopmentMarketRegistry(
                        listOf(
                            DevelopmentMarketContext(
                                market,
                                basic.feePolicySnapshot.copy(feeRates = MakerTakerFeeRates(FeeRate(1_000), FeeRate(2_000))),
                            ),
                        ),
                    ),
            )
        val user = UserId("사".repeat(64))
        startObservedTradingNode(config).use { node ->
            val opening = opening().copy(opening = OpeningBalance("P1", user, market.quoteAssetId, Amount(1_000_000_000)))
            val expected = mutableListOf(fund(node, opening))
            val command =
                order().let {
                    it.copy(
                        requestId = "청".repeat(64),
                        order = it.order.copy(marketId = market.marketId, userId = user, orderId = OrderId("주".repeat(64))),
                        market = market,
                        feePolicySnapshot = config.feePolicySnapshot,
                        ledgerTransactionId = "원".repeat(64),
                        occurredAt = Instant.parse("2031-02-03T04:05:06.987654321Z"),
                    )
                }
            var maximumPayload = 0
            expected.add(send(node, command) { maximumPayload = it.connection.ingressPublication().maxPayloadLength() })
            assertTrue(expected.last().bytes.size > maximumPayload, "실제 UDP publication의 MTU payload를 넘어야 한다")
            assertTrue(expected.last().bytes.size <= 4096)
            val before = snapshotValues(assertNotNull(node.captureObservation()?.snapshot))
            val read = completed(reader(node).read(assertNotNull(node.observation).position))
            assertMatch(withExecutions(node, expected, read.limitPosition), read)
            assertTrue(read.inputs.last { it.clusterSessionId >= 0 }.fragmentCount > 1, "실제 Archive의 여러 조각이 합쳐졌는지 확인한다")
            assertEquals(command, AdmissionCommandCodec().decode(read.inputs.last { it.clusterSessionId >= 0 }.bytes))
            assertEquals(before, snapshotValues(assertNotNull(node.captureObservation()?.snapshot)))
            val current = assertNotNull(node.captureObservation()?.snapshot)
            assertEquals(Amount(699_400_000), current.balances.single().available)
            assertEquals(Amount(300_600_000), current.balances.single().hold)
            assertEquals(
                Amount(600_000),
                originalApprovalRecords(current)
                    .single()
                    .candidate.reservation.initialFeeReserveAmount,
            )
            println(
                "실제 fragment=${read.inputs.last().fragmentCount}, frame=${expected.last().bytes.size}, ingress maxPayload=$maximumPayload, end=${read.inputs.last().position}",
            )
        }
    }

    @Test
    fun `다른 기록 세션 Archive stream과 기록 미도달은 일치가 아니라 검증 불가다`() {
        startObservedTradingNode(config()).use { node ->
            val expected = listOf(fund(node), send(node, order()))
            val limit = expected.last().position
            val actual = completed(reader(node).read(limit))
            val identity = actual.recording.let { FundingRecording(it.archiveId, it.recordingId, it.logSessionId) }
            listOf(
                identity.copy(archiveId = identity.archiveId + 1),
                identity.copy(recordingId = identity.recordingId + 1),
                identity.copy(logSessionId = identity.logSessionId xor 1),
            ).forEach { wrong ->
                val read = assertIs<RecordedArchiveRead.Unavailable>(reader(node).read(limit, wrong))
                assertTrue(read.reason.contains("다릅니다"), read.reason)
                assertEquals(RecordingComparisonStatus.UNVERIFIABLE, compareRecordedInputs(expected, read).status)
            }
            val wrongStream = RecordedArchiveReader(node.config, node.logStreamId + 1, node.logChannel).read(limit)
            assertIs<RecordedArchiveRead.Unavailable>(wrongStream)
            assertEquals(RecordingComparisonStatus.UNVERIFIABLE, compareRecordedInputs(expected, wrongStream).status)
            val wrongChannels =
                listOf(
                    ChannelUri.parse(node.logChannel).apply { put("control", "127.0.0.1:1") }.toString(),
                    node.logChannel.replace("control-mode=manual", "control-mode=dynamic"),
                    ChannelUri.parse(node.logChannel).apply { put("term-length", "32k") }.toString(),
                    node.logChannel + "|tags=999999999",
                    node.logChannel + "|session-id=0",
                    node.logChannel + "|endpoint=127.0.0.1:2",
                    "aeron:udp",
                    "aeron-spy:${node.logChannel}",
                )
            wrongChannels.forEach { channel ->
                val read = RecordedArchiveReader(node.config, node.logStreamId, channel).read(limit)
                assertIs<RecordedArchiveRead.Unavailable>(read, "다른 실제 log publication 채널: $channel")
                assertEquals(RecordingComparisonStatus.UNVERIFIABLE, compareRecordedInputs(expected, read).status)
            }
            val notRecorded = RecordedArchiveReader(node.config, node.logStreamId, node.logChannel, timeoutMs = 200).read(limit + 65_536)
            assertTrue(assertIs<RecordedArchiveRead.Unavailable>(notRecorded).reason.contains("기한"))
            assertEquals(RecordingComparisonStatus.UNVERIFIABLE, compareRecordedInputs(expected, notRecorded).status)
            assertNull(node.error)
        }
    }

    @Test
    fun `이전 관찰값이나 실패 차단 상태 및 결과 누락의 위치를 완료 상한으로 쓰지 않는다`() {
        startObservedTradingNode(config()).use { node ->
            fund(node)
            val valid = assertNotNull(node.captureObservation())
            assertEquals(valid, normalObservation(valid, 0))
            assertNull(normalObservation(null, 0))
            assertNull(normalObservation(valid, valid.position))
            // 실제 정상 관찰값의 진단 필드만 통제한다. 코어 장애·복구 실험은 아니다.
            listOf(
                valid.copy(failure = "코어 내부 오류"),
                valid.copy(blocked = true),
                valid.copy(snapshot = null),
                valid.copy(result = null, admissionResult = null),
            ).forEach { invalid ->
                assertFailsWith<IllegalStateException> { normalObservation(invalid, 0) }
            }
        }
    }

    private fun fund(
        node: DevelopmentFundingNode,
        command: DevelopmentBalanceCommandPayload = opening(),
    ): ExpectedRecordedInput {
        val previous = node.observation?.position ?: 0
        var bytes: ByteArray? = null
        var offerPosition = 0L
        val response =
            DevelopmentFundingClient(node.config).use { client ->
                assertTrue(
                    client.send(command) { copy, position ->
                        assertNull(bytes, "한 입력의 실제 전송 수용은 한 번만 관찰한다")
                        assertTrue(position > 0)
                        bytes = copy
                        offerPosition = position
                    },
                )
                assertIs<FundingRequestOutcome.Confirmed>(client.awaitResult()).result
            }
        val observation = applied(node, previous)
        assertEquals(response, observation.result)
        return ExpectedRecordedInput(assertNotNull(bytes), observation.position, fundingValues(command), offerPosition)
    }

    private fun send(
        node: DevelopmentFundingNode,
        command: AdmissionCommandPayload,
        inspect: (DevelopmentOrderClient) -> Unit = {},
    ): ExpectedRecordedInput {
        val previous = node.observation?.position ?: 0
        var bytes: ByteArray? = null
        var offerPosition = 0L
        val response =
            DevelopmentOrderClient(node.config).use { client ->
                inspect(client)
                assertTrue(
                    client.send(command) { copy, position ->
                        assertNull(bytes, "한 입력의 실제 전송 수용은 한 번만 관찰한다")
                        assertTrue(position > 0)
                        bytes = copy
                        offerPosition = position
                    },
                )
                assertIs<OrderRequestOutcome.Confirmed>(client.awaitResult()).result
            }
        val observation = applied(node, previous)
        assertEquals(response, observation.admissionResult)
        awaitAutomaticCompletion(node, response)
        return ExpectedRecordedInput(assertNotNull(bytes), observation.position, orderValues(command), offerPosition)
    }

    private fun opening(): DevelopmentBalanceCommandPayload =
        DevelopmentBalanceCommandPayload(
            OpeningBalance("P1", UserId("U1"), AssetId("USDT"), Amount(1_000_000_000)),
            "OPENING-1",
            Instant.parse("2026-10-20T00:00:00.123456789Z"),
        )

    private fun order(
        request: String = "R1",
        id: String = "O1",
    ): AdmissionCommandPayload = admissionCommand(request, id).let { it.copy(order = it.order.copy(userId = UserId("U1"))) }

    private fun assertMatch(
        expected: List<ExpectedRecordedInput>,
        read: RecordedArchiveRead.Complete,
    ) {
        val comparison = compareRecordedInputs(expected, read)
        assertEquals(RecordingComparisonStatus.MATCH, comparison.status, comparison.reasons.joinToString("\n"))
        assertEquals(read.limitPosition, read.consumedPosition)
        assertEquals(read.limitPosition, read.inputs.last().position)
        println("Archive MATCH recording=${read.recording}")
        println("S=${read.startPosition} L=${read.limitPosition} consumed=${read.consumedPosition}")
        expected.zip(read.inputs).forEachIndexed { index, (sent, recorded) ->
            println(
                "input=${index + 1} transportAccepted=${sent.offerPosition} serviceEnd=${sent.position} archiveEnd=${recorded.position}",
            )
            println("fields=${sent.values}")
            println("originalFrame=${HexFormat.of().formatHex(sent.bytes)}")
        }
    }

    private fun withExecutions(
        node: DevelopmentFundingNode,
        external: List<ExpectedRecordedInput>,
        limit: Long,
    ): List<ExpectedRecordedInput> =
        (
            external +
                capturedTradingObservations(node).filter { it.executionSequence != null && it.position <= limit }.map {
                    val sequence = checkNotNull(it.executionSequence)
                    ExpectedRecordedInput(
                        ExecuteAdmissionCodec().encode(ExecuteAdmissionPayload(sequence)),
                        it.position,
                        mapOf("kind" to "EXECUTION", "admissionSequence" to sequence),
                        null,
                    )
                }
        ).sortedBy { it.position }

    private fun assertRepresentativeState(snapshot: TradingState.Snapshot) {
        assertEquals(Amount(700_000_000), snapshot.balances.single().available)
        assertEquals(Amount(300_000_000), snapshot.balances.single().hold)
        assertEquals(1L, snapshot.lastAdmissionSequence)
        assertEquals(
            "OPENING",
            snapshot.preparations
                .single()
                .transaction.transactionType.name,
        )
        assertTrue(snapshot.pendingOrders.isEmpty(), "자동 실행을 마친 주문은 호가창에 있고 처리 대기는 비어 있다")
        assertEquals(1, snapshot.completions.size)
        val pending = originalApprovalRecords(snapshot).single()
        assertEquals("RESERVE", pending.candidate.reserveTransaction.transactionType.name)
        assertEquals(Amount(300_000_000), pending.candidate.reservation.remainingAmount)
        assertEquals(listOf("R1", "R2"), snapshot.requests.map { it.requestId })
        assertIs<OrderAdmissionState.Result.Accepted>(snapshot.requests[0].result)
        assertIs<OrderAdmissionState.Result.Rejected>(snapshot.requests[1].result)
    }

    private fun completed(read: RecordedArchiveRead): RecordedArchiveRead.Complete = assertIs(read, read.toString())

    private fun reader(node: DevelopmentFundingNode): RecordedArchiveReader =
        RecordedArchiveReader(node.config, node.logStreamId, node.logChannel)

    private fun applied(
        node: DevelopmentFundingNode,
        after: Long,
    ): FundingObservation {
        val start = System.nanoTime()
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
            assertNull(node.error)
            normalObservation(lastExternalObservation(node), after)?.let { return it }
            Thread.sleep(1)
        }
        error("입력에 대응하는 정상 처리 관찰값이 없습니다")
    }

    private fun normalObservation(
        value: FundingObservation?,
        after: Long,
    ): FundingObservation? {
        if (value == null || value.position <= after) return null
        check(value.failure == null && !value.blocked && value.snapshot != null) { "실패·차단·상태 누락의 위치는 완료 상한이 아닙니다" }
        check(value.result != null || value.admissionResult != null) { "결과가 없는 관찰 위치는 완료 상한이 아닙니다" }
        return value
    }

    private fun snapshotValues(snapshot: TradingState.Snapshot): List<Any?> =
        listOf(
            snapshot.balances,
            snapshot.lastAdmissionSequence,
            snapshot.reservations,
            snapshot.ledgerTransactions.map(::transactionValues),
            snapshot.completions.map { listOf(it.executionId, it.occurredAt, it.matchingResult, it.settlement) },
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

    private fun config(): DevelopmentFundingSettings {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }.coerceAtMost(65528)
            val sockets = mutableListOf<DatagramSocket>()
            try {
                (0..7).forEach { sockets.add(DatagramSocket(base + it)) }
                return DevelopmentFundingSettings(Files.createTempDirectory("recorded99-").resolve("run"), base)
            } catch (_: java.net.BindException) {
                // 테스트마다 별도 포트 묶음과 새 실행 폴더를 사용한다.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("격리 테스트용 포트를 확보하지 못했습니다")
    }
}

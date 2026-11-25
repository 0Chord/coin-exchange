package com.exchange.core.api.trading.infrastructure.history

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.common.Price
import com.exchange.core.common.Quantity
import com.exchange.core.common.UserId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.fee.FeeRate
import com.exchange.core.fee.FeeTier
import com.exchange.core.fee.MakerTakerFeeRates
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeePolicySnapshot
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.MarketDefinition
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.order.OrderType
import com.exchange.core.order.Side
import com.exchange.core.order.TimeInForce
import com.exchange.core.trading.AdmissionReply
import com.exchange.core.trading.BoundedFundingState
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsDispatchConfiguration
import com.exchange.core.trading.FundsDispatchLimits
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsMatchingAppliedAck
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.FundsRoleRecoveryState
import com.exchange.core.trading.MarketFundsDispatchLimits
import com.exchange.core.trading.MarketMatchingIntakeLimits
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingIntakeConfiguration
import com.exchange.core.trading.MatchingIntakeLimits
import com.exchange.core.trading.MatchingRoleRecoveryState
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderIntent
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.QueryFactsAckStatus
import com.exchange.core.trading.QueryFactsReadStatus
import com.exchange.core.trading.QueryRetentionConfiguration
import com.exchange.core.trading.QueryRetentionLimits
import com.exchange.core.trading.RequestToken
import com.exchange.core.trading.ReservedMatchingState
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingMarketAssignment
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingRoleAssembly
import com.exchange.core.trading.TradingRoleDeclaration
import com.exchange.core.trading.TradingRoleTopology
import com.exchange.core.trading.TradingServiceRole
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderQueryRoleSnapshotCodecTest {
    private val user = UserId("buyer")
    private val quote = AssetId("QUOTE")
    private val market = MarketDefinition(MarketId("A-QUOTE"), AssetId("A"), quote, 0)
    private val policy = TradingFeePolicySnapshot(FeeProductType.SPOT, FeeTier.NORMAL, 1, MakerTakerFeeRates(FeeRate.ZERO, FeeRate.ZERO))
    private val context = TradingMarketContext(market, policy, 0)
    private val manifest =
        TradingClusterManifest(
            ClusterLineage(101, 102),
            "order-query-snapshot-v1",
            "0".repeat(64),
            "1".repeat(64),
            7,
            listOf(context),
            TradingResourceLimits(maxIdentifierBytes = 34),
        )
    private val matchingLineage = ClusterLineage(201, 202)
    private val topology = TradingRoleTopology(manifest, listOf(TradingMarketAssignment(market.marketId, matchingLineage, 1)))
    private val retention = retention()
    private val dispatchLimits =
        FundsDispatchLimits(
            mapOf(market.marketId to MarketFundsDispatchLimits(16, 65_536, 16, 65_536)),
            16,
            65_536,
            16,
            65_536,
        )
    private val fundsConfiguration = FundsDispatchConfiguration(topology, dispatchLimits, queryRetention = retention)
    private val matchingConfiguration = matchingConfiguration(retention)
    private val fundsCodec = FundsRoleSnapshotCodec()
    private val matchingCodec = MatchingRoleSnapshotCodec()
    private val intent = OrderIntent(market.marketId, 7, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, Price(100), Quantity(3))

    @Test
    fun `자금 업무 ACK 뒤에도 조회 원문을 snapshot에 보존하고 조회 ACK prefix에서 재개한다`() {
        val original = prepared()
        val first = admit(original, 1)
        original.acknowledgeMatchingApplied(FundsMatchingAppliedAck(fundsConfiguration.stream(market.marketId), 1, context))
        val saved = original.fundsRecoveryState()
        assertEquals(0, saved.dispatch.dispatchEntries)
        assertEquals(listOf(first), assertNotNull(saved.queryAdmissions).entries)
        val encoded = fundsBytes(saved)
        assertEquals(4, ByteBuffer.wrap(encoded).getInt(4))
        val restored = funds().also { it.restoreFundsRecoveryState(readFunds(encoded), settlement()) }
        assertContentEquals(encoded, fundsBytes(restored.fundsRecoveryState()))
        assertEquals(listOf(first), restored.readQueryAdmissions(1, 4, 65_536).entries)

        for (owner in listOf(original, restored)) {
            assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, owner.acknowledgeQueryAdmissions(retention.consumerId, 1).status)
            admit(owner, 2)
        }
        assertContentEquals(fundsBytes(original.fundsRecoveryState()), fundsBytes(restored.fundsRecoveryState()))
        val afterAck = funds().also { it.restoreFundsRecoveryState(readFunds(fundsBytes(restored.fundsRecoveryState())), settlement()) }
        assertEquals(QueryFactsReadStatus.PRUNED, afterAck.readQueryAdmissions(1, 4, 65_536).status)
        val remaining = afterAck.readQueryAdmissions(2, 4, 65_536)
        assertEquals(listOf(2L), remaining.entries.map { it.admissionSequence })
        assertEquals(2L, remaining.progress.issued)
        assertEquals(1L, remaining.progress.retiredThrough)
    }

    @Test
    fun `매칭 정산 ACK와 독립인 조회 원문 및 회수 위치를 실제 codec에서 복원한다`() {
        val fundOwner = prepared()
        val first = admit(fundOwner, 1)
        val original = matching()
        val result = assertNotNull(original.apply(first).result)
        val stream = matchingConfiguration.resultStream(market.marketId)
        original.acknowledgeFundsSettled(MatchingFundsSettledAck(stream, 1, context))
        assertEquals(0, original.recoveryState().resultEntries)
        assertEquals(
            listOf(result),
            original
                .recoveryState()
                .queryResults
                .getValue(market.marketId)
                .entries,
        )
        val encoded = matchingBytes(original.recoveryState())
        assertEquals(3, ByteBuffer.wrap(encoded).getInt(4))
        val restored = ReservedMatchingState(matchingConfiguration).also { it.restore(readMatching(encoded)) }
        assertContentEquals(encoded, matchingBytes(restored.recoveryState()))
        assertEquals(listOf(result), restored.readQueryResults(stream, 1, 4, 65_536).entries)

        for (owner in listOf(original, restored)) {
            assertEquals(QueryFactsAckStatus.ACKNOWLEDGED, owner.acknowledgeQueryResults(retention.consumerId, stream, 1).status)
        }
        val retired =
            ReservedMatchingState(
                matchingConfiguration,
            ).also { it.restore(readMatching(matchingBytes(restored.recoveryState()))) }
        val read = retired.readQueryResults(stream, 1, 4, 65_536)
        assertEquals(QueryFactsReadStatus.PRUNED, read.status)
        assertEquals(1L, read.progress.issued)
        assertEquals(1L, read.progress.retiredThrough)
        assertEquals(0, read.progress.retainedEntries)
        assertEquals(0L, read.progress.retainedBytes)
        assertContentEquals(matchingBytes(original.recoveryState()), matchingBytes(retired.recoveryState()))
    }

    @Test
    fun `조회 snapshot 버전은 취소 입력 계약의 원본 구분과 기존 취소 복구 필드도 보존한다`() {
        val funds = prepared(inputContractVersion = 2)
        val first = admit(funds, 1)
        assertEquals(2, first.inputContractVersion)
        val expectedFunds = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion = 2, queryRetention = retention)
        val encodedFunds = fundsBytes(funds.fundsRecoveryState())
        assertEquals(listOf(first), assertNotNull(readFunds(encodedFunds, expectedFunds).queryAdmissions).entries)
        assertContentEquals(encodedFunds, fundsBytes(readFunds(encodedFunds, expectedFunds)))
        val expectedMatching = matchingConfiguration(retention, inputContractVersion = 2)
        val matching =
            ReservedMatchingState(expectedMatching).also {
                it.initialize()
                it.apply(first)
            }
        val encodedMatching = matchingBytes(matching.recoveryState())
        assertContentEquals(encodedMatching, matchingBytes(readMatching(encodedMatching, expectedMatching)))
        assertEquals(
            2,
            readMatching(encodedMatching, expectedMatching)
                .queryResults
                .getValue(market.marketId)
                .entries
                .single()
                .formatVersion,
        )
    }

    @Test
    fun `조회 capability 소비자와 한도를 결속하고 원본 없는 이전 snapshot을 활성화하지 않는다`() {
        val original = prepared()
        val first = admit(original, 1)
        val fundsBytes = fundsBytes(original.fundsRecoveryState())
        val matching = matching().also { it.apply(first) }
        val matchingBytes = matchingBytes(matching.recoveryState())
        for (different in listOf(retention("other-was"), retention(bytes = retention.maxBytes - 1))) {
            assertFails { readFunds(fundsBytes, FundsDispatchConfiguration(topology, dispatchLimits, queryRetention = different)) }
            assertFails { readMatching(matchingBytes, matchingConfiguration(different)) }
        }
        assertFails { readFunds(fundsBytes, FundsDispatchConfiguration(topology, dispatchLimits)) }
        assertFails { readMatching(matchingBytes, matchingConfiguration(null)) }
        val oldFunds = funds(null).also { it.initialize(manifest) }
        val oldMatching = matching(null)
        assertFails { readFunds(fundsBytes(oldFunds.fundsRecoveryState())) }
        assertFails { readMatching(matchingBytes(oldMatching.recoveryState())) }
    }

    @Test
    fun `스냅샷은 회수한 원장과 남은 원장을 연결하고 구형 기록의 과거 분개를 보증하지 않는다`() {
        val query = QueryRetentionConfiguration(retention.consumerId, retention.marketLimits, capabilityVersion = 2)
        val configuration = FundsDispatchConfiguration(topology, dispatchLimits, inputContractVersion = 2, queryRetention = query)

        fun owner(persistLedger: Boolean = true): BoundedFundingState =
            BoundedFundingState(
                OrderAdmissionCalculator(reservations()),
                expectedManifest = manifest,
                dispatchConfiguration = configuration,
                persistRecoveryLedger = persistLedger,
            )

        val opening = OpeningBalance("P1", user, quote, Amount(1_000))
        val original = owner()
        original.initialize(manifest)
        original.prepareDevelopmentBalance(opening, Instant.EPOCH, atLogPosition = 32)
        original.rotateEpoch(user, 0, 0)
        original.admit(RequestToken(user, 0, 1, 1), intent, Instant.EPOCH, atLogPosition = 64)
        val openingBatch = assertNotNull(original.readLedgerBatch(1))
        val reserveBatch = assertNotNull(original.readLedgerBatch(2))
        val expectedDigest = reserveBatch.digestAfter(openingBatch.digestAfter(FundsLedgerBatch.GENESIS_DIGEST))
        assertTrue(original.acknowledgeLedger(1))
        val saved = readFunds(fundsBytes(original.fundsRecoveryState()), configuration)
        val restored = owner()
        restored.restoreFundsRecoveryState(saved, settlement())

        assertNull(restored.readLedgerBatch(1))
        assertEquals(LedgerTransactionType.RESERVE, assertNotNull(restored.readLedgerBatch(2)).transactions.single().transactionType)
        val progress = assertNotNull(restored.ledgerProgress())
        assertTrue(progress.completeFromGenesis)
        assertEquals(1L, progress.retiredThrough)
        assertEquals(2L, progress.issued)
        assertEquals(expectedDigest, progress.digest)

        val old = owner(persistLedger = false)
        old.initialize(manifest)
        old.prepareDevelopmentBalance(opening, Instant.EPOCH)
        val oldSaved = readFunds(fundsBytes(old.fundsRecoveryState()), configuration)
        val upgraded = owner()
        upgraded.restoreFundsRecoveryState(oldSaved, settlement())
        assertFalse(assertNotNull(upgraded.ledgerProgress()).completeFromGenesis)
    }

    @Test
    fun `체크섬을 다시 계산해도 조회 prefix 건수 바이트와 frame 손상을 거절한다`() {
        val original = prepared()
        val first = admit(original, 1)
        val fundsSaved = original.fundsRecoveryState()
        val fundsEncoded = fundsBytes(fundsSaved)
        val fundsBlock =
            ByteArrayOutputStream()
                .also { bytes ->
                    QueryFactsSnapshotCodec.write(DataOutputStream(bytes), assertNotNull(fundsSaved.queryAdmissions)) { out, entry ->
                        MatchingResultSnapshotCodec().writeDispatch(out, entry)
                    }
                }.toByteArray()
        val matchingSaved = matching().also { it.apply(first) }.recoveryState()
        val matchingEncoded = matchingBytes(matchingSaved)
        val matchingBlock =
            ByteArrayOutputStream()
                .also { bytes ->
                    QueryFactsSnapshotCodec.write(
                        DataOutputStream(bytes),
                        matchingSaved.queryResults.getValue(market.marketId),
                    ) { out, result ->
                        MatchingResultSnapshotCodec().writeResult(out, result)
                    }
                }.toByteArray()
        for ((encoded, block, read) in listOf(
            Triple(fundsEncoded, fundsBlock, { bytes: ByteArray ->
                readFunds(bytes)
                Unit
            }),
            Triple(matchingEncoded, matchingBlock, { bytes: ByteArray ->
                readMatching(bytes)
                Unit
            }),
        )) {
            val start = encoded.size - 36 - block.size
            assertContentEquals(block, encoded.copyOfRange(start, encoded.size - 36))
            for (relative in listOf(0, 8, 16)) {
                val damaged = encoded.copyOf()
                ByteBuffer.wrap(damaged).putLong(start + relative, Long.MAX_VALUE)
                resign(damaged)
                assertFails("query long $relative") { read(damaged) }
            }
            for (relative in listOf(24, 28)) {
                val damaged = encoded.copyOf()
                ByteBuffer.wrap(damaged).putInt(start + relative, Int.MAX_VALUE)
                resign(damaged)
                assertFails("query count/frame $relative") { read(damaged) }
            }
        }
    }

    private fun retention(
        consumer: String = "same-was",
        bytes: Long = 524_288,
    ) = QueryRetentionConfiguration(consumer, mapOf(market.marketId to QueryRetentionLimits(8, 262_144)), 16, bytes)

    private fun declaration(role: TradingServiceRole) =
        TradingRoleDeclaration(
            role,
            if (role == TradingServiceRole.FUNDS) manifest.lineage else matchingLineage,
            manifest.configurationVersion,
            manifest.markets,
            mapOf(market.marketId to 1L),
        )

    private fun matchingConfiguration(
        query: QueryRetentionConfiguration?,
        inputContractVersion: Int = 1,
    ) = MatchingIntakeConfiguration(
        topology,
        declaration(TradingServiceRole.MATCHING),
        MatchingIntakeLimits(
            mapOf(market.marketId to MarketMatchingIntakeLimits(16, 65_536, 16, 65_536)),
            maxResultEntries = 16,
            maxResultBytes = 65_536,
            maxActiveOrders = 16,
            maxActiveBytes = 65_536,
            maxSingleResultBytes = 4_096,
            maxResultEvents = 8,
            maxCandidateEvents = 12,
        ),
        inputContractVersion = inputContractVersion,
        queryRetention = query,
    )

    private fun funds(
        query: QueryRetentionConfiguration? = retention,
        inputContractVersion: Int = 1,
    ) = TradingRoleAssembly.funds(
        topology,
        declaration(TradingServiceRole.FUNDS),
        OrderAdmissionCalculator(reservations()),
        dispatchLimits = dispatchLimits,
        inputContractVersion = inputContractVersion,
        queryRetention = query,
    )

    private fun prepared(inputContractVersion: Int = 1) =
        funds(inputContractVersion = inputContractVersion).also {
            it.initialize(manifest)
            it.prepareDevelopmentBalance(OpeningBalance("P1", user, quote, Amount(1_000)), Instant.EPOCH)
            it.rotateEpoch(user, 0, 0)
        }

    private fun matching(query: QueryRetentionConfiguration? = retention) =
        ReservedMatchingState(matchingConfiguration(query)).also {
            it.initialize()
        }

    private fun admit(
        owner: BoundedFundingState,
        sequence: Long,
    ): FundsOrderDispatch {
        assertIs<AdmissionReply.Recorded>(owner.admit(RequestToken(user, 0, 1, sequence), intent, Instant.EPOCH))
        return owner.readQueryAdmissions(sequence, 1, 65_536).entries.single()
    }

    private fun reservations() = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(TradingFeeReserveCalculator()))

    private fun settlement() =
        OrderSettlementCalculator(
            OrderFillSettlementCalculator(TradingFeeCalculator(), TradingFeeReserveCalculator()),
            reservations(),
            TradingFeeReserveCalculator(),
            SettlementLedgerTransactions(),
        )

    private fun fundsBytes(state: FundsRoleRecoveryState): ByteArray =
        ByteArrayOutputStream()
            .also {
                fundsCodec.write(state, POSITION, it)
            }.toByteArray()

    private fun matchingBytes(state: MatchingRoleRecoveryState): ByteArray =
        ByteArrayOutputStream().also { matchingCodec.write(state, POSITION, it) }.toByteArray()

    private fun readFunds(
        bytes: ByteArray,
        expected: FundsDispatchConfiguration = fundsConfiguration,
    ): FundsRoleRecoveryState = fundsCodec.read(ByteArrayInputStream(bytes), expected, POSITION)

    private fun readMatching(
        bytes: ByteArray,
        expected: MatchingIntakeConfiguration = matchingConfiguration,
    ): MatchingRoleRecoveryState = matchingCodec.read(ByteArrayInputStream(bytes), expected, POSITION)

    private fun resign(bytes: ByteArray) {
        val digestOffset = bytes.size - 36
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(digestOffset)).copyInto(bytes, digestOffset)
    }

    companion object {
        private const val POSITION = 4_096L
    }
}

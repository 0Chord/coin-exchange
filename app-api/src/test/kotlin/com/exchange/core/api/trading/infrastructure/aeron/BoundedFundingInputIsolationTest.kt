package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.fee.TradingFeeCalculator
import com.exchange.core.fee.TradingFeeReserveCalculator
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.order.BuyOrderFundingQuoteCalculator
import com.exchange.core.order.OrderFillSettlementCalculator
import com.exchange.core.order.OrderReservationCalculator
import com.exchange.core.trading.OrderAdmissionCalculator
import com.exchange.core.trading.OrderSettlementCalculator
import com.exchange.core.trading.SettlementLedgerTransactions
import com.exchange.core.trading.TradingClusterManifest
import com.exchange.core.trading.TradingLifecycle
import com.exchange.core.trading.TradingResourceLimits
import com.exchange.core.trading.TradingState
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.AgentTerminationException
import org.agrona.concurrent.IdleStrategy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedFundingInputIsolationTest {
    @Test fun `unknown funding asset and identifier limit are request rejections and later funding succeeds`() {
        val limits = TradingResourceLimits(maxIdentifierBytes = 8)
        val fixture = fixture(limits)
        assertEquals("UNKNOWN_ASSET", fixture.send(fund(asset = "DOGE")).code)
        assertEquals("INVALID_IDENTIFIER", fixture.send(fund(id = "P".repeat(9))).code)
        assertEquals(
            0,
            fixture.state
                .recoveryState()
                .preparations.size,
        )
        assertEquals("PREPARED", fixture.send(fund()).code)
        assertEquals(
            1,
            fixture.state
                .recoveryState()
                .preparations.size,
        )
        assertTrue(fixture.service.started)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun `funding capacity rejection preserves first result and continues processing`() {
        val fixture = fixture(TradingResourceLimits(maxPreparations = 1))
        val first = fixture.send(fund())
        assertEquals("PREPARED", first.code)
        assertEquals("RETRYABLE_CAPACITY", fixture.send(fund(id = "P2", asset = "BTC")).code)
        val repeated = fixture.send(fund())
        assertEquals("PREPARED", repeated.code)
        assertEquals(first.funding?.ledgerTransactionId, repeated.funding?.ledgerTransactionId)
        assertTrue(repeated.funding!!.alreadyPrepared)
        assertEquals(
            1,
            fixture.state
                .recoveryState()
                .preparations.size,
        )
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun `funding without trusted development control does not change funds`() {
        val fixture = fixture(trusted = false)
        assertEquals("CONTROL_DISABLED", fixture.send(fund()).code)
        assertTrue(
            fixture.state
                .recoveryState()
                .balances
                .isEmpty(),
        )
        assertEquals("PROGRESS", fixture.send(BoundedTradingCommand.Progress).code)
        assertTrue(fixture.service.started)
    }

    @Test fun `invalid quarantine incidents are request rejections and leave the owner running`() {
        val fixture = fixture()
        for (command in listOf(
            BoundedTradingCommand.Quarantine(-1, "STATE_MISMATCH"),
            BoundedTradingCommand.Quarantine(0, "R".repeat(129)),
        )) {
            assertEquals("INVALID_INCIDENT", fixture.send(command).code)
            assertEquals(TradingLifecycle.RUNNING, fixture.state.lifecycle)
        }
        assertEquals("PREPARED", fixture.send(fund()).code)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun `unexpected funding IllegalStateException and Error still stop the node`() {
        for (failure in listOf(IllegalStateException("unexpected internal bug"), AssertionError("internal error"))) {
            val fixture = fixture()
            val command = (fund() as BoundedTradingCommand.Funding).command
            doThrow(
                failure,
            ).`when`(fixture.state).prepareDevelopmentBalance(command.opening, command.ledgerTransactionId, command.occurredAt)
            val thrown = assertFailsWith<AgentTerminationException> { fixture.send(BoundedTradingCommand.Funding(command)) }
            assertSame(failure, thrown.cause)
            assertSame(failure, fixture.failures.single())
            assertFalse(fixture.service.started)
        }
    }

    private fun fund(
        id: String = "P1",
        asset: String = "USDT",
    ) = BoundedTradingCommand.Funding(
        DevelopmentBalanceCommandPayload(OpeningBalance(id, UserId("U1"), AssetId(asset), Amount(1_000)), "L1", Instant.EPOCH),
    )

    private fun fixture(
        limits: TradingResourceLimits = TradingResourceLimits(),
        trusted: Boolean = true,
    ): Fixture {
        val base = haBoundedProbeManifest()
        val manifest =
            TradingClusterManifest(
                base.lineage,
                base.algorithmId,
                base.executionArtifactSha256,
                base.schemaSha256,
                base.configurationVersion,
                base.markets,
                limits,
            )
        val fees = TradingFeeReserveCalculator()
        val reservations = OrderReservationCalculator(BuyOrderFundingQuoteCalculator(fees))
        val state =
            spy(
                TradingState(
                    OrderAdmissionCalculator(reservations),
                    OrderSettlementCalculator(
                        OrderFillSettlementCalculator(TradingFeeCalculator(), fees),
                        reservations,
                        fees,
                        SettlementLedgerTransactions(),
                    ),
                    boundedMode = true,
                ),
            )
        state.initialize(manifest)
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        `when`(cluster.timeUnit()).thenReturn(TimeUnit.MILLISECONDS)
        `when`(cluster.role()).thenReturn(Cluster.Role.LEADER)
        `when`(cluster.logPosition()).thenReturn(1024L)
        `when`(cluster.idleStrategy()).thenReturn(mock(IdleStrategy::class.java))
        val replies = mutableListOf<ByteArray>()
        val failures = mutableListOf<Throwable>()
        doAnswer { invocation ->
            val bytes = ByteArray(invocation.getArgument<Int>(2))
            invocation.getArgument<DirectBuffer>(0).getBytes(invocation.getArgument<Int>(1), bytes)
            replies.add(bytes)
            1L
        }.`when`(session).offer(any(DirectBuffer::class.java), anyInt(), anyInt())
        val service =
            DevelopmentFundingAdapter(state, boundedManifest = manifest, trustedBoundedControl = trusted, onFatal = { failures.add(it) })
        service.onStart(cluster, null)
        return Fixture(state, service, session, replies, failures)
    }

    private class Fixture(
        val state: TradingState,
        val service: DevelopmentFundingAdapter,
        val session: ClientSession,
        val replies: MutableList<ByteArray>,
        val failures: MutableList<Throwable>,
    ) {
        private val codec = BoundedTradingCodec()

        fun send(command: BoundedTradingCommand): BoundedTradingReply {
            val bytes = codec.encode(1, command)
            service.onSessionMessage(session, 0, clusterFrame(bytes), AeronCluster.SESSION_HEADER_LENGTH, bytes.size, Header(0, 0))
            return codec.decodeResponse(replies.last())
        }
    }
}

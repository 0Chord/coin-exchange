package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.trading.FundsArchiveFacts
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingServiceRole

internal data class ArchiveProjectionFacts(
    val admissions: List<FundsOrderDispatch>,
    val matching: List<MatchingResultEnvelope>,
    val settlements: List<SettlementFact>,
    val ledger: List<FundsLedgerBatch>,
)

/** 조회 전용 상태에서 확정 명령을 적용하고 그 프레임이 새로 만든 사실만 돌려준다. */
internal class SplitArchiveProjection(
    role: TradingServiceRole,
    configuration: SplitTradingConfiguration,
) {
    private val admissions = ArrayList<FundsOrderDispatch>()
    private val matching = ArrayList<MatchingResultEnvelope>()
    private val settlements = ArrayList<SettlementFact>()
    private val ledger = ArrayList<FundsLedgerBatch>()
    private var ledgerSequence = 0L
    private val service: SplitTradingClusterAdapter

    val isReady: Boolean get() = service.tradingReady

    init {
        require(configuration.archiveQueryEnabled && configuration.queryRetention == null)
        val fundsFacts =
            if (role == TradingServiceRole.FUNDS) {
                object : FundsArchiveFacts {
                    override fun admission(fact: FundsOrderDispatch) {
                        admissions.add(fact)
                    }

                    override fun settlement(fact: SettlementFact) {
                        settlements.add(fact)
                    }

                    override fun ledger(
                        position: Long,
                        transactions: List<LedgerTransaction>,
                    ) {
                        val sequence = Math.addExact(ledgerSequence, 1)
                        ledger.add(FundsLedgerBatch(configuration.fundsLineage, sequence, position, transactions))
                        ledgerSequence = sequence
                    }
                }
            } else {
                null
            }
        service =
            SplitTradingClusterAdapter(
                role,
                configuration,
                resultDeliveryEnabled = true,
                fundsArchiveFacts = fundsFacts,
                onMatchingArchiveResult = if (role == TradingServiceRole.MATCHING) ({ matching.add(it) }) else null,
            )
    }

    fun apply(frame: RecordedApplicationFrame): ArchiveProjectionFacts {
        if (!service.started) service.startArchiveReplay()
        service.replayArchiveInput(frame)
        val facts = ArchiveProjectionFacts(admissions.toList(), matching.toList(), settlements.toList(), ledger.toList())
        admissions.clear()
        matching.clear()
        settlements.clear()
        ledger.clear()
        return facts
    }
}

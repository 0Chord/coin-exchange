package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.BalanceArchiveObservation
import com.exchange.core.api.trading.application.BalanceQueryCode
import com.exchange.core.api.trading.application.BalanceSourceStatus
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.port.MAX_LEDGER_PAGE_ENTRIES
import com.exchange.core.api.trading.application.port.OrderQueryFeedProgress
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryLedgerException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.FundsLedgerRecovery
import com.exchange.core.trading.FundsOrderDispatch
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultEnvelope
import com.exchange.core.trading.SettlementFact
import com.exchange.core.trading.TradingMarketContext
import com.exchange.core.trading.TradingServiceRole
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 확정 로그를 독립 상태에 재생한다. 저장 확인은 이 작업의 진행에만 쓰인다. */
internal class ArchiveOrderQueryFeedWorker(
    private val store: OrderQueryFeedStore,
    namespace: OrderQueryNamespace,
    private val consumerId: String,
    private val configuration: SplitTradingConfiguration,
    private val fundsSource: SplitArchiveSource,
    matchingSources: Map<ClusterLineage, SplitArchiveSource>,
    private val applyBalances: ((TradingMarketContext) -> Boolean)? = null,
) : AutoCloseable {
    private val context =
        configuration.topology.fundsManifest.markets
            .single()
    private val market = context.market.marketId.value
    private val stream = configuration.matchingConfiguration.resultStream(context.market.marketId)
    private val matchingSource = matchingSources.getValue(stream.matchingLineage)
    private var funds = SplitArchiveProjection(TradingServiceRole.FUNDS, configuration)
    private var matching = SplitArchiveProjection(TradingServiceRole.MATCHING, configuration)
    private val codec = OrderQuerySourceCodec(configuration.inputContractVersion)
    private val running = AtomicBoolean(true)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val thread: Thread

    @Volatile
    var balanceStatus = BalanceSourceStatus()
        private set

    private var balanceProjectionError: BalanceQueryCode? = null
    private var fundsRead = SplitArchiveRead(0, false)
    private var matchingRead = SplitArchiveRead(0, false)
    private var admitted = 0L
    private var matched = 0L
    private var settled = 0L
    private var ledgerThrough = 0L
    private val admissions = ArrayDeque<FundsOrderDispatch>()
    private val matches = ArrayDeque<MatchingResultEnvelope>()
    private val settlements = ArrayDeque<SettlementFact>()
    private val ledger = ArrayDeque<FundsLedgerBatch>()
    private var saved = SavedSequences(0L, 0L, 0L, 0L, 0L)

    private data class SavedSequences(
        val admissions: Long,
        val matches: Long,
        val settlements: Long,
        val financial: Long,
        val ledger: Long,
    ) {
        fun wentBackFrom(previous: SavedSequences): Boolean =
            admissions < previous.admissions || matches < previous.matches || settlements < previous.settlements ||
                financial < previous.financial || ledger < previous.ledger
    }

    init {
        require(configuration.archiveQueryEnabled && namespace.fundsLineage == configuration.fundsLineage)
        require(namespace.markets == setOf(market) && matchingSources.keys == setOf(stream.matchingLineage))
        thread =
            Thread(::run, "order-query-archive").apply {
                isDaemon = true
                start()
            }
    }

    private fun run() {
        try {
            projectUntilClosed()
        } finally {
            if (balanceStatus.error != BalanceQueryCode.QUERY_INCONSISTENT) balanceStatus = BalanceSourceStatus()
            for (source in listOf(fundsSource, matchingSource)) {
                runCatching(source::close).onFailure { logger.warn("Archive 조회 원본을 닫지 못했습니다", it) }
            }
        }
    }

    private fun projectUntilClosed() {
        var initialized = false
        var verified = false
        while (running.get()) {
            try {
                if (!initialized) {
                    store.initialize(
                        consumerId,
                        mapOf(market to stream),
                        mapOf(market to context),
                        configuration.topologySha256,
                        settlementEnabled = true,
                    )
                    checkRunning()
                    store.suspendProjection()
                    initialized = true
                }
                checkRunning()
                val progress = store.progress()
                val durable = savedSequences(progress)
                if (durable.wentBackFrom(saved)) {
                    checkRunning()
                    balanceStatus = BalanceSourceStatus()
                    store.suspendProjection()
                    rewind()
                    verified = false
                }
                saved = durable
                val advanced = collect()
                if (!verified && fundsRead.caughtUp && matchingRead.caughtUp && pendingEmpty() && funds.isReady && matching.isReady) {
                    verified = verifyCatchUp()
                }
                val balancesAdvanced = projectBalances()
                if (!advanced && !balancesAdvanced) Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (failure: Exception) {
                if (!running.get()) break
                balanceStatus = BalanceSourceStatus(error = BalanceQueryCode.QUERY_UNAVAILABLE)
                if (failure is IllegalArgumentException || failure is OrderQueryWriteConflictException ||
                    failure is OrderQueryInconsistentException || failure is OrderQueryLedgerException
                ) {
                    // 진행 위치를 읽은 직후 DB가 복원됐다면, 다음 쓰기에서 순번 공백이 먼저 보일 수 있다.
                    val restored = runCatching { savedSequences(store.progress()) }.getOrNull()
                    if (!running.get()) break
                    if (restored != null && restored.wentBackFrom(saved)) {
                        runCatching { store.suspendProjection() }
                        rewind()
                        saved = restored
                        verified = false
                        continue
                    }
                    balanceStatus = BalanceSourceStatus(error = BalanceQueryCode.QUERY_INCONSISTENT)
                    runCatching { store.block(failure.message ?: "Archive 조회 원본 불일치") }
                    logger.error("Archive 조회 반영을 중단합니다", failure)
                    break
                }
                if (verified) runCatching { store.suspendProjection() }
                verified = false
                logger.warn("Archive 조회 반영을 다시 시도합니다: {}", failure.message)
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun collect(): Boolean {
        val previousFunds = fundsRead.position
        val previousMatching = matchingRead.position
        if (admissions.isEmpty() && ledger.isEmpty() && settlements.isEmpty()) {
            checkRunning()
            fundsRead =
                fundsSource.read(previousFunds) { frame ->
                    val facts = funds.apply(frame)
                    admissions.addAll(facts.admissions)
                    ledger.addAll(facts.ledger)
                    settlements.addAll(facts.settlements)
                    facts.admissions.lastOrNull()?.let { admitted = it.admissionSequence }
                    facts.ledger.lastOrNull()?.let { ledgerThrough = it.sequence }
                    facts.settlements.lastOrNull()?.let { settled = it.resultSequence }
                }
            if (applyBalances != null) {
                balanceStatus =
                    balanceStatus.copy(
                        observation =
                            BalanceArchiveObservation(
                                ledgerThrough = ledgerThrough,
                                caughtUp = fundsRead.caughtUp,
                                expiresAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SPLIT_ARCHIVE_READ_TIMEOUT_MS),
                            ),
                        error = balanceProjectionError,
                    )
            }
        }
        while (admissions.isNotEmpty()) {
            checkRunning()
            val fact = admissions.first()
            store.applyAdmission(fact, codec.admissionBytes(0, fact))
            saved = saved.copy(admissions = maxOf(saved.admissions, fact.admissionSequence))
            admissions.removeFirst()
        }
        while (ledger.isNotEmpty()) {
            checkRunning()
            val page = ArrayList<FundsLedgerBatch>(MAX_LEDGER_PAGE_ENTRIES)
            var remainingBytes = FundsLedgerRecovery.MAX_RETAINED_BYTES
            for (batch in ledger) {
                if (page.size == MAX_LEDGER_PAGE_ENTRIES || batch.canonicalBytes > remainingBytes) break
                page.add(batch)
                remainingBytes -= batch.canonicalBytes
            }
            require(page.isNotEmpty()) { "Archive 원장 분개가 DB 쓰기 한도를 초과했습니다" }
            val through = store.applyLedgerPage(page)
            check(through == page.last().sequence)
            saved = saved.copy(ledger = maxOf(saved.ledger, through))
            repeat(page.size) { ledger.removeFirst() }
        }

        if (matches.isEmpty()) {
            checkRunning()
            matchingRead =
                matchingSource.read(previousMatching) { frame ->
                    val facts = matching.apply(frame)
                    matches.addAll(facts.matching)
                    facts.matching.lastOrNull()?.let { matched = it.resultSequence }
                }
        }
        while (matches.isNotEmpty()) {
            val fact = matches.first()
            val missingAdmission =
                fact.input.admissionSequence > saved.admissions ||
                    fact.effects.any { it.origin.admissionSequence > saved.admissions }
            if (missingAdmission) break
            checkRunning()
            store.applyMatching(fact, codec.resultBytes(0, fact))
            saved = saved.copy(matches = maxOf(saved.matches, fact.resultSequence))
            matches.removeFirst()
        }

        while (settlements.isNotEmpty()) {
            val fact = settlements.first()
            val through = fact.resultSequence
            if (through > saved.matches) break
            checkRunning()
            check(store.applySettlement(fact) == through)
            saved = saved.copy(settlements = maxOf(saved.settlements, through))
            // 분개가 커밋된 뒤에만 주문에 금융 완료를 표시한다. 재시도에서는 이미 쓴 원문을 대조한다.
            checkRunning()
            store.applyFinancial(MatchingFundsSettledAck(stream, through, context))
            saved = saved.copy(financial = maxOf(saved.financial, through))
            settlements.removeFirst()
        }
        return previousFunds != fundsRead.position || previousMatching != matchingRead.position
    }

    private fun pendingEmpty(): Boolean = admissions.isEmpty() && matches.isEmpty() && settlements.isEmpty() && ledger.isEmpty()

    private fun projectBalances(): Boolean {
        val applyPage = applyBalances ?: return false
        if (balanceProjectionError == BalanceQueryCode.QUERY_INCONSISTENT) return false
        checkRunning()
        return try {
            val advanced = applyPage(context)
            if (balanceProjectionError != null) {
                balanceProjectionError = null
                balanceStatus = balanceStatus.copy(error = null)
            }
            advanced
        } catch (failure: RuntimeException) {
            val code =
                when (failure) {
                    is OrderQueryInconsistentException, is OrderQueryLedgerException, is DataIntegrityViolationException -> {
                        BalanceQueryCode.QUERY_INCONSISTENT
                    }

                    is DataAccessException, is TransactionException, is OrderQueryUnavailableException -> {
                        BalanceQueryCode.QUERY_UNAVAILABLE
                    }

                    else -> {
                        throw failure
                    }
                }
            if (balanceProjectionError != code) logger.warn("PostgreSQL 잔고 반영 실패", failure)
            balanceProjectionError = code
            balanceStatus = balanceStatus.copy(error = code, session = Any())
            false
        }
    }

    private fun savedSequences(progress: OrderQueryFeedProgress): SavedSequences {
        checkRunning()
        return SavedSequences(
            progress.admissionThrough,
            progress.matchingThrough.getValue(market),
            progress.settlementThrough.getValue(market),
            progress.financialThrough.getValue(market),
            store.ledgerThrough(),
        )
    }

    private fun rewind() {
        // DB 백업의 순번이 뒤로 이동하면 남아 있는 로그 처음부터 원문과 커밋 순번을 다시 대조한다.
        funds = SplitArchiveProjection(TradingServiceRole.FUNDS, configuration)
        matching = SplitArchiveProjection(TradingServiceRole.MATCHING, configuration)
        fundsRead = SplitArchiveRead(0, false)
        matchingRead = SplitArchiveRead(0, false)
        admitted = 0
        matched = 0
        settled = 0
        ledgerThrough = 0
        balanceProjectionError = null
        admissions.clear()
        matches.clear()
        settlements.clear()
        ledger.clear()
    }

    private fun verifyCatchUp(): Boolean {
        checkRunning()
        val progress = store.progress()
        require(progress.lifecycle != OrderQueryLifecycle.BLOCKED)
        val storedMatching = progress.matchingThrough.getValue(market)
        val storedSettlement = progress.settlementThrough.getValue(market)
        val storedFinancial = progress.financialThrough.getValue(market)
        checkRunning()
        val storedLedger = store.ledgerThrough()
        if (progress.admissionThrough > admitted || storedMatching > matched || storedSettlement > settled ||
            storedFinancial > settled || storedLedger > ledgerThrough
        ) {
            return false
        }
        require(progress.admissionThrough == admitted && storedMatching == matched)
        require(storedSettlement == settled && storedFinancial == settled && storedLedger == ledgerThrough)
        checkRunning()
        store.verifyLedgerPrefix(ledgerThrough)
        checkRunning()
        store.verifySources(
            OrderQuerySourceBounds(admitted, 0, completeFromGenesis = true),
            mapOf(market to OrderQuerySourceBounds(matched, 0, completeFromGenesis = true)),
            mapOf(market to settled),
            progress,
        )
        return true
    }

    private fun checkRunning() {
        if (!running.get()) throw InterruptedException()
    }

    override fun close() {
        running.set(false)
        thread.interrupt()
        thread.join(10_000)
        check(!thread.isAlive) { "Archive 조회 작업을 종료하지 못했습니다" }
    }
}

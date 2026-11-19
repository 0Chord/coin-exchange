package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.OrderQueryRecoveryStatus
import com.exchange.core.api.trading.application.OrderQueryRecoveryVerdict
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryLedgerException
import com.exchange.core.api.trading.application.port.OrderQueryOriginsPendingException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.api.trading.infrastructure.codec.OrderQueryRecovery
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceReply
import com.exchange.core.ledger.LedgerReconciliationScope
import com.exchange.core.ledger.ReconciliationStatus
import com.exchange.core.ledger.ReservationHold
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.FundsLedgerProgress
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.QueryFactsProgress
import com.exchange.core.trading.TradingServiceRole
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.transaction.TransactionException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

/** 한 WAS의 전용 작업이다. 엔진 스레드는 DB를 기다리지 않으며 조회 ACK는 커밋한 cursor만 보낸다. */
internal class OrderQueryFeedWorker(
    private val store: OrderQueryFeedStore,
    private val namespace: OrderQueryNamespace,
    private val consumerId: String,
    private val settings: AeronGatewaySettings,
    private val matchingIngress: Map<String, String>,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val thread: Thread
    private val codec = OrderQuerySourceCodec()

    @Volatile
    var recoveryVerdict = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING)
        private set

    init {
        require(consumerId.isNotBlank() && matchingIngress.keys.containsAll(namespace.markets))
        store.suspendProjection()
        thread =
            Thread(::run, "order-query-feed").apply {
                isDaemon = true
                start()
            }
    }

    private fun run() {
        while (running.get()) {
            try {
                collectFromSources()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (failure: Exception) {
                val recoveryFailure =
                    when (failure) {
                        is DataAccessException, is OrderQueryUnavailableException, is TransactionException -> {
                            OrderQueryRecoveryFailure.DB_UNAVAILABLE
                        }

                        else -> {
                            OrderQueryRecoveryFailure.SOURCE_UNAVAILABLE
                        }
                    }
                recoveryVerdict = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.BLOCKED, recoveryFailure)
                val mustBlockProjection =
                    failure is IllegalArgumentException || failure is SourceConflict ||
                        failure is OrderQueryInconsistentException || failure is OrderQueryWriteConflictException
                if (mustBlockProjection) {
                    persistBlock(failure)
                    break
                }
                try {
                    store.suspendProjection()
                } catch (unavailable: Exception) {
                    failure.addSuppressed(unavailable)
                }
                if (!running.get()) break
                logger.warn("주문 조회 반영 재연결: {}", failure.message)
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /** 영구 충돌은 DB 장애 때문에 잊지 않는다. 기록될 때까지 원본 수집도 재개하지 않는다. */
    private fun persistBlock(failure: Exception) {
        while (running.get()) {
            try {
                store.block(failure.message ?: "query source conflict")
                logger.warn("주문 조회 반영 차단: {}", failure.message)
                return
            } catch (unavailable: Exception) {
                logger.warn("주문 조회 차단 기록 재시도: {}", unavailable.message)
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun collectFromSources() {
        // 주문 Gateway의 새 실행 폴더를 먼저 만들거나 재사용하지 않는다.
        val parent = checkNotNull(settings.runRoot.parent)
        Files.createDirectories(parent)
        val directory = Files.createTempDirectory(parent, "order-query-")
        try {
            MediaDriver
                .launch(
                    MediaDriver
                        .Context()
                        .aeronDirectoryName(directory.resolve("driver").toString())
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED),
                ).use { driver ->
                    val clients = linkedMapOf<String, OrderQuerySourceClient>()

                    fun client(ingress: String): OrderQuerySourceClient =
                        clients.getOrPut(ingress) {
                            val egress = settings.egressEndpoint.substringBeforeLast(':') + ":0"
                            OrderQuerySourceClient(
                                SplitOrderDeliveryEndpoint(
                                    driverDirectory = driver.aeronDirectoryName(),
                                    ingressEndpoints = ingress,
                                    egressEndpoint = egress,
                                    clusterId = 0,
                                    inputContractVersion = 2,
                                ),
                                settings.resultTimeoutMs,
                            )
                        }
                    try {
                        val fundsClient = client(settings.ingressEndpoints)
                        val initialFundsDescription = describeSource(fundsClient, TradingServiceRole.FUNDS)
                        val settlementEnabled = initialFundsDescription.capabilityVersion == 2
                        val recoveryMarket =
                            if (initialFundsDescription.ledgerProgress != null) {
                                val context = initialFundsDescription.contexts.values.single()
                                context.market
                            } else {
                                null
                            }
                        val recoveryMatchingClient = recoveryMarket?.let { client(matchingIngress.getValue(it.marketId.value)) }
                        require(
                            initialFundsDescription.lineage == namespace.fundsLineage &&
                                initialFundsDescription.streams.keys == matchingIngress.keys,
                        ) {
                            "query funds or market assignment differs"
                        }
                        store.initialize(
                            consumerId = consumerId,
                            streams = initialFundsDescription.streams,
                            contexts = initialFundsDescription.contexts,
                            sourceIdentity = initialFundsDescription.topologySha256,
                            settlementEnabled = settlementEnabled,
                        )
                        if (recoveryMatchingClient != null) {
                            // DB를 다시 읽기 전에는 직전 연결의 읽기 실패를 해제하지 않는다.
                            store.ledgerThrough()
                            recoveryVerdict = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING)
                        } else {
                            recoveryVerdict =
                                OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.BLOCKED, OrderQueryRecoveryFailure.SCOPE_UNSUPPORTED)
                        }
                        var reconciliationTerms: Pair<Long, Long>? = null
                        var retiredLedgerVerified = false
                        while (running.get()) {
                            val progressBeforeSourceRead = store.progress()
                            val currentFundsDescription = describeSource(fundsClient, TradingServiceRole.FUNDS)
                            require(
                                currentFundsDescription.streams == initialFundsDescription.streams &&
                                    currentFundsDescription.contexts == initialFundsDescription.contexts &&
                                    currentFundsDescription.topologySha256 == initialFundsDescription.topologySha256 &&
                                    currentFundsDescription.capabilityVersion == initialFundsDescription.capabilityVersion &&
                                    (currentFundsDescription.ledgerProgress != null) == (recoveryMatchingClient != null),
                            )
                            val matchingDescriptionByIngress =
                                matchingIngress.values.distinct().associateWith { ingress ->
                                    describeSource(client(ingress), TradingServiceRole.MATCHING)
                                }
                            val matchingDescriptionByMarket =
                                matchingIngress.mapValues { (market, ingress) ->
                                    val matchingDescription = matchingDescriptionByIngress.getValue(ingress)
                                    require(
                                        matchingDescription.topologySha256 == initialFundsDescription.topologySha256 &&
                                            matchingDescription.capabilityVersion == initialFundsDescription.capabilityVersion &&
                                            matchingDescription.lineage == initialFundsDescription.streams.getValue(market).matchingLineage,
                                    )
                                    require(
                                        matchingDescription.streams[market] == initialFundsDescription.streams.getValue(market) &&
                                            matchingDescription.contexts[market] == initialFundsDescription.contexts.getValue(market),
                                    )
                                    matchingDescription
                                }
                            if (recoveryMatchingClient != null) {
                                val terms = fundsClient.leadershipTermId to recoveryMatchingClient.leadershipTermId
                                if (terms != reconciliationTerms) {
                                    reconciliationTerms = terms
                                    retiredLedgerVerified = false
                                    recoveryVerdict = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.RECOVERING)
                                }
                            }
                            val admissionBounds = currentFundsDescription.progress.single { it.market == null }
                            val matchingBounds =
                                matchingDescriptionByMarket.mapValues { (market, description) ->
                                    description.progress.single { sourceProgress -> sourceProgress.market == market }
                                }
                            val fundsProgressByMarket =
                                currentFundsDescription.progress.filter { it.market != null }.associate { sourceProgress ->
                                    checkNotNull(sourceProgress.market) to sourceProgress
                                }
                            val financialThroughByMarket = fundsProgressByMarket.mapValues { (_, progress) -> progress.financialThrough }
                            val settlementsByMarket =
                                if (settlementEnabled) {
                                    val settlementCursorsBeforeRead = progressBeforeSourceRead.settlementThrough
                                    require(fundsProgressByMarket.keys == settlementCursorsBeforeRead.keys)
                                    fundsProgressByMarket.mapValues { (market, progress) ->
                                        val bounds = requireNotNull(progress.settlement) { "settlement progress is missing for $market" }
                                        require(bounds.issued == progress.financialThrough && bounds.retiredThrough in 0..bounds.issued)
                                        val beforeRead = settlementCursorsBeforeRead.getValue(market)
                                        require(beforeRead <= bounds.issued) { "settlement source prefix regressed for $market" }
                                        // 다른 WAS가 소스를 조회하는 사이에 저장하고 ACK했을 수 있다.
                                        if (bounds.retiredThrough > beforeRead) {
                                            val latest = store.progress().settlementThrough.getValue(market)
                                            require(bounds.retiredThrough <= latest) { "settlement source prefix is missing for $market" }
                                        }
                                        bounds
                                    }
                                } else {
                                    emptyMap()
                                }
                            val verifiedProgress =
                                store.verifySources(
                                    admission =
                                        OrderQuerySourceBounds(
                                            issued = admissionBounds.issued,
                                            retiredThrough = admissionBounds.retiredThrough,
                                            completeFromGenesis = true,
                                        ),
                                    matching =
                                        matchingBounds.mapValues { (_, bounds) ->
                                            OrderQuerySourceBounds(
                                                issued = bounds.issued,
                                                retiredThrough = bounds.retiredThrough,
                                                completeFromGenesis = true,
                                            )
                                        },
                                    financial = financialThroughByMarket,
                                    expectedBeforeRead = progressBeforeSourceRead,
                                )
                            if (verifiedProgress.lifecycle == OrderQueryLifecycle.BLOCKED) {
                                throw SourceConflict("query checkpoint is blocked")
                            }
                            var appliedFactCount = 0
                            val ledgerBounds = currentFundsDescription.ledgerProgress
                            val ledgerFailure = recoveryVerdict.reason
                            if (ledgerBounds != null && ledgerFailure != OrderQueryRecoveryFailure.LEDGER_INCOMPLETE &&
                                ledgerFailure != OrderQueryRecoveryFailure.LEDGER_MISMATCH &&
                                ledgerFailure != OrderQueryRecoveryFailure.SOURCE_PROGRESS_MISMATCH
                            ) {
                                try {
                                    if (!retiredLedgerVerified) {
                                        store.verifyLedgerPrefix(ledgerBounds.retiredThrough)
                                        retiredLedgerVerified = true
                                    }
                                    appliedFactCount += collectLedger(fundsClient, ledgerBounds)
                                } catch (failure: OrderQueryLedgerException) {
                                    blockLedgerRecovery(failure)
                                }
                            }
                            for (attempt in 0 until 32) {
                                val admissionThrough = store.progress().admissionThrough
                                if (admissionThrough >= admissionBounds.issued) break
                                val admissionReply =
                                    fundsClient.request(
                                        OrderQuerySourceCommand.Read(consumerId, null, Math.addExact(admissionThrough, 1)),
                                    )
                                val admissionFact = admissionReply.admission
                                if (admissionFact == null) {
                                    if (admissionReply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
                                    if (admissionReply.code == "PRUNED" && store.progress().admissionThrough > admissionThrough) continue
                                    throw SourceConflict("admission source returned ${admissionReply.code}")
                                }
                                require(
                                    admissionFact.stream.fundsLineage == namespace.fundsLineage &&
                                        admissionFact.admissionSequence == admissionThrough + 1,
                                )
                                val admissionPayload = codec.admissionBytes(0, admissionFact)
                                store.applyAdmission(admissionFact, admissionPayload)
                                appliedFactCount++
                            }
                            for ((market, bounds) in matchingBounds) {
                                val matchingClient = client(matchingIngress.getValue(market))
                                for (attempt in 0 until 32) {
                                    val matchingThrough = store.progress().matchingThrough.getValue(market)
                                    if (matchingThrough >= bounds.issued) break
                                    val matchingReply =
                                        matchingClient.request(
                                            OrderQuerySourceCommand.Read(consumerId, market, Math.addExact(matchingThrough, 1)),
                                        )
                                    val matchingFact = matchingReply.result
                                    if (matchingFact == null) {
                                        if (matchingReply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
                                        if (
                                            matchingReply.code == "PRUNED" &&
                                            store.progress().matchingThrough.getValue(market) > matchingThrough
                                        ) {
                                            continue
                                        }
                                        throw SourceConflict("matching source returned ${matchingReply.code}")
                                    }
                                    require(
                                        matchingFact.stream == initialFundsDescription.streams.getValue(market) &&
                                            matchingFact.resultSequence == matchingThrough + 1,
                                    )
                                    try {
                                        val matchingPayload = codec.resultBytes(0, matchingFact)
                                        store.applyMatching(matchingFact, matchingPayload)
                                        appliedFactCount++
                                    } catch (
                                        _: OrderQueryOriginsPendingException,
                                    ) {
                                        break
                                    }
                                }
                                for (attempt in 0 until 32) {
                                    val progress = store.progress()
                                    val financialThrough = progress.financialThrough.getValue(market)
                                    if (
                                        financialThrough >=
                                        minOf(financialThroughByMarket.getValue(market), progress.matchingThrough.getValue(market))
                                    ) {
                                        break
                                    }
                                    store.applyFinancial(
                                        MatchingFundsSettledAck(
                                            stream = initialFundsDescription.streams.getValue(market),
                                            through = Math.addExact(financialThrough, 1),
                                            context = initialFundsDescription.contexts.getValue(market),
                                        ),
                                    )
                                    appliedFactCount++
                                }
                                val settlementBounds = settlementsByMarket[market]
                                if (settlementBounds != null &&
                                    verifiedProgress.settlementThrough.getValue(market) < settlementBounds.issued
                                ) {
                                    appliedFactCount +=
                                        collectSettlements(fundsClient, initialFundsDescription.streams.getValue(market), settlementBounds)
                                }
                            }
                            // 결과를 모르는 커밋에는 도달하지 않는다. 재연결 시 DB cursor부터 먼저 읽는다.
                            val committedProgress = store.progress()
                            if (committedProgress.admissionThrough > admissionBounds.retiredThrough) {
                                val admissionAck =
                                    fundsClient.request(
                                        OrderQuerySourceCommand.Acknowledge(consumerId, null, committedProgress.admissionThrough),
                                    )
                                requireAcknowledged(admissionAck)
                            }
                            for ((market, matchingThrough) in committedProgress.matchingThrough) {
                                val settlementBounds = settlementsByMarket[market]
                                var through = matchingThrough
                                if (settlementBounds != null) {
                                    // 매칭의 보관 공간은 자금 클러스터도 정산 ACK를 확정한 뒤에만 돌려준다.
                                    val acknowledged =
                                        minOf(committedProgress.settlementThrough.getValue(market), settlementBounds.issued)
                                    if (acknowledged > settlementBounds.retiredThrough) {
                                        val settlementAck =
                                            fundsClient.request(
                                                OrderQuerySourceCommand.AcknowledgeSettlements(consumerId, market, acknowledged),
                                            )
                                        requireAcknowledged(settlementAck)
                                    }
                                    through = minOf(matchingThrough, acknowledged)
                                }
                                if (through <= matchingBounds.getValue(market).retiredThrough) continue
                                val matchingClient = client(matchingIngress.getValue(market))
                                val matchingAck =
                                    matchingClient.request(
                                        OrderQuerySourceCommand.Acknowledge(consumerId, market, through),
                                    )
                                requireAcknowledged(matchingAck)
                            }
                            if (recoveryMarket != null && recoveryMatchingClient != null &&
                                recoveryVerdict.status == OrderQueryRecoveryStatus.RECOVERING
                            ) {
                                val stream = initialFundsDescription.streams.getValue(recoveryMarket.marketId.value)
                                val scope =
                                    LedgerReconciliationScope(
                                        recoveryMarket.marketId,
                                        recoveryMarket.baseAssetId,
                                        recoveryMarket.quoteAssetId,
                                    )
                                try {
                                    reconcileRecovery(
                                        fundsClient,
                                        recoveryMatchingClient,
                                        initialFundsDescription.topologySha256,
                                        stream,
                                        scope,
                                    )
                                } catch (failure: OrderQueryLedgerException) {
                                    blockLedgerRecovery(failure)
                                }
                            }
                            clients.values.forEach { it.poll() }
                            if (appliedFactCount == 0) Thread.sleep(25)
                        }
                    } finally {
                        clients.values.forEach { it.close() }
                    }
                }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun collectLedger(
        client: OrderQuerySourceClient,
        bounds: FundsLedgerProgress,
    ): Int {
        if (!bounds.completeFromGenesis) {
            throw OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, "초기 분개부터 보존됐는지 확인할 수 없다")
        }
        if (store.ledgerThrough() < bounds.retiredThrough) {
            throw OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, "원본에서 이미 정리한 분개가 DB에 없다")
        }
        var verifiedThrough = bounds.retiredThrough
        var applied = 0
        while (applied < 32 && verifiedThrough < bounds.issued) {
            val next = Math.addExact(verifiedThrough, 1L)
            val reply = client.request(OrderQuerySourceCommand.ReadLedger(consumerId, next))
            val batch = reply.ledgerBatch
            if (batch == null) {
                if (reply.code == "PRUNED" && store.ledgerThrough() >= next) {
                    // 다른 WAS가 확인한 새 회수 위치를 다음 Describe에서 다시 읽는다.
                    return applied
                }
                if (reply.code == "NOT_READY") throw IllegalStateException("query ledger source is not ready")
                throw OrderQueryLedgerException(
                    OrderQueryRecoveryFailure.LEDGER_INCOMPLETE,
                    "원본에서 분개 묶음 ${next}을 확인할 수 없다 (${reply.code})",
                )
            }
            if (batch.lineage != namespace.fundsLineage || batch.sequence != next) {
                throw OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_MISMATCH, "분개 묶음의 출처나 순번이 다르다")
            }
            // ACK가 유실된 구간도 DB의 마지막 번호만 믿지 않고 남아 있는 원본과 행을 다시 대조한다.
            verifiedThrough = store.applyLedger(batch)
            applied++
        }
        if (verifiedThrough > bounds.retiredThrough) {
            requireAcknowledged(client.request(OrderQuerySourceCommand.AckLedger(consumerId, verifiedThrough)))
        }
        return applied
    }

    private fun reconcileRecovery(
        fundsClient: OrderQuerySourceClient,
        matchingClient: OrderQuerySourceClient,
        topology: String,
        stream: MatchingResultStream,
        scope: LedgerReconciliationScope,
    ) {
        val initialTerms = fundsClient.leadershipTermId to matchingClient.leadershipTermId
        val fundsReply = describeRecovery(fundsClient, TradingServiceRole.FUNDS, topology, stream.fundsLineage) ?: return
        val funds = checkNotNull(fundsReply.recovery)
        val ledger = checkNotNull(fundsReply.ledgerProgress)
        val matchingReply = describeRecovery(matchingClient, TradingServiceRole.MATCHING, topology, stream.matchingLineage) ?: return
        val matching = checkNotNull(matchingReply.recovery)
        val terms = fundsClient.leadershipTermId to matchingClient.leadershipTermId
        if (initialTerms != terms) return
        val storedThrough = store.ledgerThrough()
        if (funds.issued < matching.matchingAppliedThrough ||
            funds.matchingAppliedThrough > matching.matchingAppliedThrough ||
            funds.resultThrough > matching.resultThrough ||
            storedThrough > ledger.issued
        ) {
            val latest = describeRecovery(fundsClient, TradingServiceRole.FUNDS, topology, stream.fundsLineage) ?: return
            matchingClient.poll()
            if (terms == (fundsClient.leadershipTermId to matchingClient.leadershipTermId) &&
                sameFinancialState(funds, checkNotNull(latest.recovery)) && latest.ledgerProgress == ledger
            ) {
                throw OrderQueryLedgerException(OrderQueryRecoveryFailure.SOURCE_PROGRESS_MISMATCH, "자금·매칭·DB의 확정 순서가 모순된다")
            }
            return
        }
        if (funds.issued != funds.matchingAppliedThrough ||
            funds.issued != matching.matchingAppliedThrough ||
            funds.resultThrough != matching.resultThrough ||
            storedThrough < ledger.issued
        ) {
            return
        }

        val expectedOrders = funds.orders.associate { it.orderId to (it.userId to it.remainingQuantity) }
        val matchingOrders = matching.orders.associate { it.orderId to (it.userId to it.remainingQuantity) }
        val ordersMatch =
            expectedOrders.size == funds.orders.size && matchingOrders.size == matching.orders.size && expectedOrders == matchingOrders
        val reservations =
            funds.orders.map { order ->
                ReservationHold(
                    scope.marketId.value,
                    order.orderId.value,
                    order.userId,
                    checkNotNull(order.assetId),
                    "ACTIVE",
                    checkNotNull(order.hold).value,
                )
            }
        val report =
            store.reconcileLedger(
                scope = scope,
                logPosition = funds.logPosition,
                ledger = ledger,
                balances = funds.balances,
                reservations = reservations,
                onReadProgress = {
                    fundsClient.poll()
                    matchingClient.poll()
                },
            )
        val currentReply = describeRecovery(fundsClient, TradingServiceRole.FUNDS, topology, stream.fundsLineage) ?: return
        val currentFunds = checkNotNull(currentReply.recovery)
        matchingClient.poll()
        if (terms != (fundsClient.leadershipTermId to matchingClient.leadershipTermId) ||
            !sameFinancialState(funds, currentFunds) || currentReply.ledgerProgress != ledger
        ) {
            return
        }

        val failure =
            when {
                !ordersMatch -> OrderQueryRecoveryFailure.ORDERS_MISMATCH
                report.status == ReconciliationStatus.MATCHED -> null
                report.status == ReconciliationStatus.EMPTY && ledger.issued == 0L && funds.orders.isEmpty() -> null
                else -> OrderQueryRecoveryFailure.BALANCE_MISMATCH
            }
        recoveryVerdict =
            OrderQueryRecoveryVerdict(
                status = if (failure == null) OrderQueryRecoveryStatus.READY else OrderQueryRecoveryStatus.BLOCKED,
                reason = failure,
                logPosition = funds.logPosition,
                ledgerThrough = ledger.issued,
                differences = report.differences,
            )
    }

    private fun sameFinancialState(
        first: OrderQueryRecovery,
        current: OrderQueryRecovery,
    ): Boolean = first.copy(logPosition = current.logPosition) == current

    private fun describeRecovery(
        client: OrderQuerySourceClient,
        role: TradingServiceRole,
        topology: String,
        lineage: ClusterLineage,
    ): OrderQuerySourceReply? {
        val term = client.leadershipTermId
        val firstReply = readRecoveryPage(client, role, topology, lineage, offset = 0) ?: return null
        val first = checkNotNull(firstReply.recovery)
        var offset = first.nextOffset ?: return firstReply
        val orders = first.orders.toMutableList()
        val balances = first.balances.toMutableList()
        while (true) {
            if (offset != orders.size + balances.size || offset == 0) {
                throw OrderQueryLedgerException(OrderQueryRecoveryFailure.SOURCE_UNAVAILABLE, "복구 상태 페이지 위치가 다르다")
            }
            val pageReply = readRecoveryPage(client, role, topology, lineage, offset) ?: return null
            val page = checkNotNull(pageReply.recovery)
            if (client.leadershipTermId != term || page.issued != first.issued ||
                page.matchingAppliedThrough != first.matchingAppliedThrough || page.resultThrough != first.resultThrough ||
                pageReply.ledgerProgress != firstReply.ledgerProgress
            ) {
                return null
            }
            orders.addAll(page.orders)
            balances.addAll(page.balances)
            offset = page.nextOffset ?: break
        }
        return firstReply.copy(recovery = first.copy(orders = orders, balances = balances, nextOffset = null))
    }

    private fun readRecoveryPage(
        client: OrderQuerySourceClient,
        role: TradingServiceRole,
        topology: String,
        lineage: ClusterLineage,
        offset: Int,
    ): OrderQuerySourceReply? {
        val reply = client.request(OrderQuerySourceCommand.DescribeRecovery(consumerId, offset))
        if (reply.code == "RECOVERY_CHANGED") return null
        if (reply.code == "NOT_READY") throw IllegalStateException("query recovery source is not ready")
        if (reply.code != "RECOVERY" || reply.role != role || reply.lineage != lineage ||
            reply.topologySha256 != topology || reply.recovery == null
        ) {
            throw OrderQueryLedgerException(OrderQueryRecoveryFailure.SOURCE_UNAVAILABLE, "복구 상태를 읽을 수 없다 (${reply.code})")
        }
        return reply
    }

    private fun blockLedgerRecovery(failure: OrderQueryLedgerException) {
        recoveryVerdict = OrderQueryRecoveryVerdict(OrderQueryRecoveryStatus.BLOCKED, failure.reason)
        logger.warn("자금 원장 대사 차단: {}", failure.message)
    }

    private fun collectSettlements(
        fundsClient: OrderQuerySourceClient,
        stream: MatchingResultStream,
        bounds: QueryFactsProgress,
    ): Int {
        val market = stream.marketId.value
        var applied = 0
        repeat(32) {
            val settlementThrough = store.progress().settlementThrough.getValue(market)
            if (settlementThrough >= bounds.issued) return applied
            val next = Math.addExact(settlementThrough, 1L)
            val reply = fundsClient.request(OrderQuerySourceCommand.ReadSettlements(consumerId, market, next))
            val fact = reply.settlement
            if (fact == null) {
                if (reply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
                if (reply.code == "PRUNED" && store.progress().settlementThrough.getValue(market) >= next) return@repeat
                throw SourceConflict("settlement source returned ${reply.code}")
            }
            require(fact.stream == stream && fact.resultSequence == next) { "settlement source result differs" }
            store.applySettlement(fact)
            applied++
        }
        return applied
    }

    private fun describeSource(
        client: OrderQuerySourceClient,
        role: TradingServiceRole,
    ): OrderQuerySourceReply {
        val reply = client.request(OrderQuerySourceCommand.Describe)
        if (reply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
        require(
            reply.code == "DESCRIBED" && reply.role == role && reply.consumerId == consumerId,
        ) { "query source capability or owner differs" }
        // 제품 WAS의 주문 Gateway와 같은 입력 계약을 준비 완료 전에 확인한다.
        require(reply.inputContractVersion == 2) { "order query WAS requires source input contract 2" }
        require(reply.capabilityVersion in 1..2) { "unsupported query source capability" }
        return reply
    }

    private fun requireAcknowledged(reply: OrderQuerySourceReply) {
        if (reply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
        require(reply.code == "ACKNOWLEDGED") { "query source acknowledgement differs" }
    }

    override fun close() {
        running.set(false)
        thread.interrupt()
        thread.join(settings.resultTimeoutMs + 1_500)
        check(!thread.isAlive) { "query feed shutdown timed out" }
    }

    private class SourceConflict(
        message: String,
    ) : RuntimeException(message)
}

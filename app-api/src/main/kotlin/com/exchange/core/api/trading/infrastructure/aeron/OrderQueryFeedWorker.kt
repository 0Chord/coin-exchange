package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryOriginsPendingException
import com.exchange.core.api.trading.application.port.OrderQuerySourceBounds
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCodec
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceCommand
import com.exchange.core.api.trading.infrastructure.codec.OrderQuerySourceReply
import com.exchange.core.trading.MatchingFundsSettledAck
import com.exchange.core.trading.MatchingResultStream
import com.exchange.core.trading.QueryFactsProgress
import com.exchange.core.trading.TradingServiceRole
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.slf4j.LoggerFactory
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
                        while (running.get()) {
                            val progressBeforeSourceRead = store.progress()
                            val currentFundsDescription = describeSource(fundsClient, TradingServiceRole.FUNDS)
                            require(
                                currentFundsDescription.streams == initialFundsDescription.streams &&
                                    currentFundsDescription.contexts == initialFundsDescription.contexts &&
                                    currentFundsDescription.topologySha256 == initialFundsDescription.topologySha256 &&
                                    currentFundsDescription.capabilityVersion == initialFundsDescription.capabilityVersion,
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

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
                connected()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (failure: Exception) {
                val permanent =
                    failure is IllegalArgumentException || failure is SourceConflict ||
                        failure is OrderQueryInconsistentException || failure is OrderQueryWriteConflictException
                if (permanent) {
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

    private fun connected() {
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
                                SplitOrderDeliveryEndpoint(driver.aeronDirectoryName(), ingress, egress, 0, 2),
                                settings.resultTimeoutMs,
                            )
                        }
                    try {
                        val funds = client(settings.ingressEndpoints)
                        val first = described(funds, TradingServiceRole.FUNDS)
                        require(first.lineage == namespace.fundsLineage && first.streams.keys == matchingIngress.keys) {
                            "query funds or market assignment differs"
                        }
                        store.initialize(consumerId, first.streams, first.contexts, first.topologySha256)
                        while (running.get()) {
                            val beforeRead = store.progress()
                            val financial = described(funds, TradingServiceRole.FUNDS)
                            require(
                                financial.streams == first.streams && financial.contexts == first.contexts &&
                                    financial.topologySha256 == first.topologySha256,
                            )
                            val matchingDescriptions =
                                matchingIngress.values.distinct().associateWith { ingress ->
                                    described(client(ingress), TradingServiceRole.MATCHING)
                                }
                            val descriptions =
                                matchingIngress.mapValues { (market, ingress) ->
                                    matchingDescriptions.getValue(ingress).also { matching ->
                                        require(
                                            matching.topologySha256 == first.topologySha256 &&
                                                matching.lineage == first.streams.getValue(market).matchingLineage,
                                        )
                                        require(
                                            matching.streams[market] == first.streams.getValue(market) &&
                                                matching.contexts[market] == first.contexts.getValue(market),
                                        )
                                    }
                                }
                            val admissionBounds = financial.progress.single { it.market == null }
                            val matchingBounds = descriptions.mapValues { (market, reply) -> reply.progress.single { it.market == market } }
                            val financialHeads =
                                financial.progress.filter { it.market != null }.associate {
                                    checkNotNull(it.market) to
                                        it.financialThrough
                                }
                            val checked =
                                store.verifySources(
                                    OrderQuerySourceBounds(admissionBounds.issued, admissionBounds.retiredThrough, true),
                                    matchingBounds.mapValues { (_, value) ->
                                        OrderQuerySourceBounds(value.issued, value.retiredThrough, true)
                                    },
                                    financialHeads,
                                    beforeRead,
                                )
                            if (checked.lifecycle == OrderQueryLifecycle.BLOCKED) throw SourceConflict("query checkpoint is blocked")
                            var work = 0
                            for (attempt in 0 until 32) {
                                val current = store.progress().admissionThrough
                                if (current >= admissionBounds.issued) break
                                val reply = funds.request(OrderQuerySourceCommand.Read(consumerId, null, Math.addExact(current, 1)))
                                val fact = reply.admission
                                if (fact == null) {
                                    if (reply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
                                    if (reply.code == "PRUNED" && store.progress().admissionThrough > current) continue
                                    throw SourceConflict("admission source returned ${reply.code}")
                                }
                                require(fact.stream.fundsLineage == namespace.fundsLineage && fact.admissionSequence == current + 1)
                                store.applyAdmission(fact, codec.admissionBytes(0, fact))
                                work++
                            }
                            for ((market, bound) in matchingBounds) {
                                val peer = client(matchingIngress.getValue(market))
                                for (attempt in 0 until 32) {
                                    val current = store.progress().matchingThrough.getValue(market)
                                    if (current >= bound.issued) break
                                    val reply =
                                        peer.request(
                                            OrderQuerySourceCommand.Read(consumerId, market, Math.addExact(current, 1)),
                                        )
                                    val fact = reply.result
                                    if (fact == null) {
                                        if (reply.code == "NOT_READY") throw IllegalStateException("query source is not ready")
                                        if (reply.code == "PRUNED" && store.progress().matchingThrough.getValue(market) > current) continue
                                        throw SourceConflict("matching source returned ${reply.code}")
                                    }
                                    require(fact.stream == first.streams.getValue(market) && fact.resultSequence == current + 1)
                                    try {
                                        store.applyMatching(fact, codec.resultBytes(0, fact))
                                        work++
                                    } catch (
                                        _: OrderQueryOriginsPendingException,
                                    ) {
                                        break
                                    }
                                }
                                for (attempt in 0 until 32) {
                                    val progress = store.progress()
                                    val next = progress.financialThrough.getValue(market)
                                    if (next >= minOf(financialHeads.getValue(market), progress.matchingThrough.getValue(market))) break
                                    store.applyFinancial(
                                        MatchingFundsSettledAck(
                                            first.streams.getValue(market),
                                            Math.addExact(next, 1),
                                            first.contexts.getValue(market),
                                        ),
                                    )
                                    work++
                                }
                            }
                            // 결과를 모르는 커밋에는 도달하지 않는다. 재연결 시 DB cursor부터 먼저 읽는다.
                            val committed = store.progress()
                            if (committed.admissionThrough > admissionBounds.retiredThrough) {
                                acknowledged(
                                    funds.request(OrderQuerySourceCommand.Acknowledge(consumerId, null, committed.admissionThrough)),
                                )
                            }
                            for ((market, through) in committed.matchingThrough) {
                                if (through <= matchingBounds.getValue(market).retiredThrough) continue
                                acknowledged(
                                    client(
                                        matchingIngress.getValue(market),
                                    ).request(OrderQuerySourceCommand.Acknowledge(consumerId, market, through)),
                                )
                            }
                            clients.values.forEach { it.poll() }
                            if (work == 0) Thread.sleep(25)
                        }
                    } finally {
                        clients.values.forEach { it.close() }
                    }
                }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun described(
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
        return reply
    }

    private fun acknowledged(reply: OrderQuerySourceReply) {
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

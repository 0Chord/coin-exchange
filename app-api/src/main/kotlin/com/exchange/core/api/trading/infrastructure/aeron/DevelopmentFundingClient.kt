package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionMessageException
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultCodec
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import io.aeron.Publication
import io.aeron.cluster.client.AeronCluster
import io.aeron.exceptions.AeronException
import org.agrona.concurrent.SleepingMillisIdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface FundingRequestOutcome {
    data class Confirmed(
        val result: DevelopmentBalanceResultPayload,
    ) : FundingRequestOutcome

    data class Unconfirmed(
        val reason: String,
        val transportAccepted: Boolean,
    ) : FundingRequestOutcome
}

/** 한 번에 준비 요청 하나를 다룬다. 결과 대기 종료는 자금 취소가 아니다. */
internal class DevelopmentFundingClient(
    private val config: DevelopmentFundingSettings,
    private val acceptResponse: (DevelopmentBalanceResultPayload) -> Boolean = { true },
) : AutoCloseable {
    private val commandCodec = DevelopmentBalanceCommandCodec()
    private val resultCodec = DevelopmentBalanceResultCodec()
    private val idle = SleepingMillisIdleStrategy(1)
    private val transportFailure = AtomicReference<Throwable?>()
    private var pending: DevelopmentBalanceCommandPayload? = null
    private var received: DevelopmentBalanceResultPayload? = null
    private var accepted = false
    private var lastKeepAlive = System.nanoTime()
    var diagnostic: String? = null
        private set
    internal val connection: AeronCluster =
        AeronCluster.connect(
            AeronCluster
                .Context()
                // conductor는 실패를 알리기만 한다. 결과 판단과 정리는 요청 스레드가 맡는다.
                .errorHandler { transportFailure.compareAndSet(null, it) }
                .aeronDirectoryName(config.driverDirectory)
                .ingressChannel("aeron:udp?term-length=64k")
                .ingressEndpoints("0=${config.endpoint(0)}")
                .egressChannel(config.udp(7))
                .messageTimeoutNs(TimeUnit.MILLISECONDS.toNanos(config.startupTimeoutMs))
                .egressListener { _, _, buffer, offset, length, _ ->
                    val command = pending
                    if (command != null) {
                        try {
                            if (length !in 8..4096) throw AdmissionMessageException("결과 길이가 잘못됐습니다")
                            val bytes = ByteArray(length)
                            buffer.getBytes(offset, bytes)
                            val result = resultCodec.decode(bytes)
                            val matches =
                                when (result) {
                                    is DevelopmentBalanceResultPayload.Prepared -> {
                                        result.result.opening == command.opening && (
                                            result.result.alreadyPrepared ||
                                                (
                                                    result.result.ledgerTransactionId == command.ledgerTransactionId &&
                                                        result.result.occurredAt == command.occurredAt
                                                )
                                        )
                                    }

                                    is DevelopmentBalanceResultPayload.Rejected -> {
                                        result.opening == command.opening
                                    }
                                }
                            if (matches) {
                                if (acceptResponse(result)) received = result
                            } else {
                                diagnostic = "대기 중인 준비 원본과 다른 응답입니다"
                            }
                        } catch (error: AdmissionMessageException) {
                            diagnostic = "응답 복원 실패: ${error.message}"
                        }
                    }
                },
        )

    /** 양수 반환은 전송 수용이다. 이 메서드가 끝나도 결과를 수신한 것은 아니다. */
    fun send(command: DevelopmentBalanceCommandPayload): Boolean {
        check(pending == null) { "기존 요청의 결과 대기를 먼저 끝내세요" }
        val bytes = commandCodec.encode(command)
        pending = command
        received = null
        accepted = false
        diagnostic = null
        val buffer = UnsafeBuffer(bytes)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.resultTimeoutMs)
        var attempts = 0
        while (attempts++ < config.maxReplyAttempts && System.nanoTime() < deadline) {
            if (transportFailure.get() != null) {
                diagnostic = "연결 오류가 기록된 채널입니다"
                return false
            }
            val position = connection.offer(buffer, 0, bytes.size)
            if (position > 0) {
                accepted = true
                return true
            }
            if (position != Publication.BACK_PRESSURED && position != Publication.ADMIN_ACTION) {
                diagnostic =
                    when (position) {
                        Publication.NOT_CONNECTED -> "연결 문제: NOT_CONNECTED, 전송 수용을 확인하지 못했습니다"
                        Publication.CLOSED -> "연결 문제: CLOSED, 닫힌 전송 채널입니다"
                        Publication.MAX_POSITION_EXCEEDED -> "전송 채널 종단 오류: MAX_POSITION_EXCEEDED, 같은 publication을 재시도하지 않습니다"
                        else -> "지원하지 않는 전송 반환값: $position"
                    }
                return false
            }
            poll()
            idle.idle()
        }
        diagnostic = "전송 재시도 상한에 도달했습니다"
        return false
    }

    fun awaitResult(): FundingRequestOutcome {
        check(pending != null) { "보낸 준비 요청이 없습니다" }
        try {
            if (!accepted) return unconfirmed()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.resultTimeoutMs)
            while (System.nanoTime() < deadline) {
                received?.let { return FundingRequestOutcome.Confirmed(it) }
                if (transportFailure.get() != null || connection.isClosed) break
                poll()
                received?.let { return FundingRequestOutcome.Confirmed(it) }
                idle.idle()
            }
            return received?.let { FundingRequestOutcome.Confirmed(it) } ?: unconfirmed()
        } catch (error: AeronException) {
            return received?.let { FundingRequestOutcome.Confirmed(it) } ?: unconfirmed(error)
        } finally {
            pending = null
        }
    }

    fun request(
        command: DevelopmentBalanceCommandPayload,
        onTransportAccepted: () -> Unit = {},
    ): FundingRequestOutcome =
        try {
            if (send(command)) onTransportAccepted()
            awaitResult()
        } catch (error: AeronException) {
            pending = null
            unconfirmed(error)
        }

    private fun unconfirmed(error: Throwable? = transportFailure.get()): FundingRequestOutcome.Unconfirmed {
        val reason =
            if (error != null) {
                "연결 문제로 결과 미확인: ${error.javaClass.simpleName}: ${error.message}"
            } else {
                diagnostic ?: "결과 미확인"
            }
        return FundingRequestOutcome.Unconfirmed("$reason. 같은 준비 ID와 원본으로 다시 확인하세요", accepted)
    }

    private fun poll() {
        connection.pollEgress()
        val now = System.nanoTime()
        if (now - lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(config.keepAliveMs)) {
            connection.sendKeepAlive()
            lastKeepAlive = now
        }
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            connection.context().close()
        }
    }
}

/** 금액은 Amount의 최소 단위로 입력한다. 이 실행은 지급 완료와 현재 잔고를 혼동하지 않는다. */
fun main(args: Array<String>) {
    require(args.size == 8) { "사용법: 노드루트 기준포트 준비ID 사용자 자산 금액최소단위 원장ID 업무Instant" }
    val config = DevelopmentFundingSettings(Path.of(args[0]), args[1].toInt())
    val command =
        DevelopmentBalanceCommandPayload(
            OpeningBalance(args[2], UserId(args[3]), AssetId(args[4]), Amount(args[5].toLong())),
            args[6],
            Instant.parse(args[7]),
        )
    val client =
        try {
            DevelopmentFundingClient(config)
        } catch (error: AeronException) {
            println("UNCONFIRMED transportAccepted=false 연결 문제: ${error.message}")
            throw IllegalStateException("미지급으로 단정하지 마세요. 같은 준비 ID와 원본으로 재확인해야 합니다", error)
        }
    client.use {
        when (val outcome = client.request(command) { println("TRANSPORT_ACCEPTED 전송 수용 · 결과 대기. 아직 지급 완료를 확인하지 못했습니다") }) {
            is FundingRequestOutcome.Confirmed -> {
                println("RESULT_RECEIVED ${outcome.result}")
            }

            is FundingRequestOutcome.Unconfirmed -> {
                println("UNCONFIRMED transportAccepted=${outcome.transportAccepted} ${outcome.reason}")
                throw IllegalStateException("미지급으로 단정하지 마세요. 같은 준비 ID로 재확인해야 합니다")
            }
        }
    }
}

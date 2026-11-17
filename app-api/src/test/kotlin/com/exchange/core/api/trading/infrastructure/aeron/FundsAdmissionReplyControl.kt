package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCodec
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryCommand
import com.exchange.core.api.trading.infrastructure.codec.SplitOrderDeliveryReply
import java.security.MessageDigest

/** 실제 접수 응답만 관찰한다. 유실 시험에서는 client 내부 응답 설정 전 callback을 전달하지 않는다. */
internal class FundsAdmissionReplyControl(
    private val command: SplitOrderDeliveryCommand,
    private val hidden: Boolean,
    private val delegate: SplitOrderDeliveryConnectionFactory = AeronSplitOrderDeliveryConnectionFactory,
    private val onObserved: (Map<String, Any?>) -> Unit,
) {
    private val codec = SplitOrderDeliveryCodec(2)
    private var correlation: Long? = null
    private var wireSha256: String? = null
    private var positiveOfferPosition: Long? = null
    private var actualReply: SplitOrderDeliveryReply? = null
    private var forwarded = false

    fun factory(): SplitOrderDeliveryConnectionFactory =
        SplitOrderDeliveryConnectionFactory { endpoint, timeoutNs, onReply ->
            val attempt =
                delegate.begin(endpoint, timeoutNs) { reply ->
                    if (reply.correlationId == correlation && reply.control
                            ?.admission
                            ?.code
                            ?.name == "ACCEPTED"
                    ) {
                        check(positiveOfferPosition != null && actualReply == null) { "최초 응답은 실제 수용한 한 요청과 대응해야 합니다" }
                        actualReply = reply
                        if (hidden) {
                            onObserved(evidence("ACCEPTED_OBSERVED", requestReturned = false, unconfirmed = false))
                        } else {
                            onReply(reply)
                            forwarded = true
                        }
                    } else {
                        onReply(reply)
                    }
                }
            object : SplitOrderDeliveryConnectAttempt by attempt {
                override fun poll(): SplitOrderDeliveryConnection? =
                    attempt.poll()?.let { connection ->
                        object : SplitOrderDeliveryConnection by connection {
                            override fun offer(bytes: ByteArray): Long {
                                val envelope = codec.decode(bytes)
                                check(envelope.command == command) { "최초 접수 전용 연결의 업무 원문이 바뀌었습니다" }
                                check(correlation == null || correlation == envelope.correlationId)
                                correlation = envelope.correlationId
                                wireSha256 = sha(bytes)
                                return connection.offer(bytes).also { if (it > 0) positiveOfferPosition = it }
                            }
                        }
                    }
            }
        }

    fun completed(reply: SplitOrderDeliveryReply): Map<String, Any?> {
        check(!hidden && forwarded && reply == actualReply) { "실제 최초 응답을 반환한 경우만 성공으로 기록합니다" }
        return evidence("ACCEPTED", requestReturned = true, unconfirmed = false).also(onObserved)
    }

    fun unconfirmed(failure: SplitOrderDeliveryUnconfirmedException): Map<String, Any?> {
        check(hidden && !forwarded && actualReply != null && failure.transportAccepted) {
            "실제 응답을 숨기고 client가 수용 후 미확인으로 끝난 경우만 유실로 기록합니다"
        }
        return evidence("UNCONFIRMED", requestReturned = false, unconfirmed = true)
    }

    private fun evidence(
        code: String,
        requestReturned: Boolean,
        unconfirmed: Boolean,
    ): Map<String, Any?> {
        val reply = checkNotNull(actualReply)
        return mapOf(
            "code" to code,
            "hidden" to hidden,
            "forwarded" to forwarded,
            "transportAccepted" to (checkNotNull(positiveOfferPosition) > 0),
            "requestReturned" to requestReturned,
            "unconfirmed" to unconfirmed,
            "positiveOfferPosition" to positiveOfferPosition,
            "correlationId" to correlation,
            "requestSha256" to sha(codec.encode(0, command)),
            "wireSha256" to checkNotNull(wireSha256),
            "appliedLogPosition" to reply.appliedLogPosition,
            "reply" to multiReply(reply),
        )
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

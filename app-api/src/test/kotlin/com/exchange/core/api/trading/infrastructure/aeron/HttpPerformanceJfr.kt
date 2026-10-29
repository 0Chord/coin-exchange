package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.AdmissionResultPayload
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceResultPayload
import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.EventType
import jdk.jfr.Label
import jdk.jfr.Name
import jdk.jfr.StackTrace

/** Test-only callback boundary. Its JFR times join events in this JVM's recording only. */
@Name("exchange.HttpPerformanceCallback")
@Label("Trading callback request")
@Category("Exchange performance test")
@StackTrace(false)
internal class HttpPerformanceJfrEvent : Event() {
    @JvmField var runId: String = ""

    @JvmField var memberId: Int = -1

    @JvmField var kind: String = "UNKNOWN"

    @JvmField var userId: String? = null

    @JvmField var requestId: String? = null

    @JvmField var admissionSequence: Long = -1

    @JvmField var logPosition: Long = -1

    @JvmField var role: String = "UNKNOWN"

    @JvmField var callbackStartedNs: Long = 0

    @JvmField var callbackCompletedNs: Long = 0

    @JvmField var failed: Boolean = false
}

@Name("exchange.HttpPerformanceMarkerDelivery")
@Label("Trading completion marker delivery")
@Category("Exchange performance test")
@StackTrace(false)
internal class HttpPerformanceJfrDeliveryEvent : Event() {
    @JvmField var runId: String = ""

    @JvmField var memberId: Int = -1

    @JvmField var requestId: String = ""

    @JvmField var admissionSequence: Long = -1

    @JvmField var logPosition: Long = -1

    @JvmField var enqueuedNs: Long = 0

    @JvmField var dequeuedNs: Long = 0

    @JvmField var firstOfferNs: Long = 0

    @JvmField var acceptedNs: Long = 0

    @JvmField var localQueueNs: Long = 0

    @JvmField var offerNs: Long = 0
}

/** No recording control, buffering, polling or threads. Disabled recordings allocate no per-callback event. */
internal open class HttpPerformanceJfr(
    private val runId: String,
    private val memberId: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    // Register before the coordinator starts JFR so its explicit event settings can be resolved.
    private val eventType = EventType.getEventType(HttpPerformanceJfrEvent::class.java)
    private val deliveryEventType = EventType.getEventType(HttpPerformanceJfrDeliveryEvent::class.java)

    open fun beginMarkerDelivery(marker: HttpPerformanceCompletion): HttpPerformanceJfrDeliveryEvent? {
        if (!deliveryEventType.isEnabled) return null
        return HttpPerformanceJfrDeliveryEvent().also {
            require(marker.runId == runId && marker.memberId == memberId)
            it.runId = marker.runId
            it.memberId = marker.memberId
            it.requestId = marker.requestId
            it.admissionSequence = marker.admissionSequence
            it.logPosition = marker.logPosition
            it.begin()
            it.enqueuedNs = nanoTime()
        }
    }

    open fun markerDequeued(event: HttpPerformanceJfrDeliveryEvent) {
        event.dequeuedNs = nanoTime()
    }

    open fun markerFirstOffer(event: HttpPerformanceJfrDeliveryEvent) {
        event.firstOfferNs = nanoTime()
    }

    open fun completeMarkerDelivery(event: HttpPerformanceJfrDeliveryEvent) {
        event.acceptedNs = nanoTime()
        event.end()
        require(event.dequeuedNs >= event.enqueuedNs && event.firstOfferNs >= event.dequeuedNs && event.acceptedNs >= event.firstOfferNs) {
            "JFR 완료 표식 로컬 시계 순서가 잘못됐습니다"
        }
        event.localQueueNs = event.dequeuedNs - event.enqueuedNs
        event.offerNs = event.acceptedNs - event.firstOfferNs
        event.commit()
    }

    open fun begin(): HttpPerformanceJfrEvent? {
        if (!eventType.isEnabled) return null
        return HttpPerformanceJfrEvent().also {
            it.runId = runId
            it.memberId = memberId
            it.begin()
            it.callbackStartedNs = nanoTime()
        }
    }

    open fun end(event: HttpPerformanceJfrEvent) {
        event.callbackCompletedNs = nanoTime()
        event.end()
    }

    open fun commit(
        event: HttpPerformanceJfrEvent,
        observation: FundingObservation?,
        executionIdentity: AdmissionResultPayload.Accepted?,
        fallbackPosition: Long,
        fallbackRole: String,
        productFailed: Boolean,
    ) {
        val admission = observation?.admissionResult
        val opening =
            when (val result = observation?.result) {
                is DevelopmentBalanceResultPayload.Prepared -> result.result.opening
                is DevelopmentBalanceResultPayload.Rejected -> result.opening
                null -> null
            }
        val identity = admission ?: executionIdentity
        event.kind =
            when {
                admission != null -> "ADMISSION"
                observation?.executionSequence != null -> "EXECUTION"
                opening != null -> "FUNDING"
                else -> "UNKNOWN"
            }
        event.userId = identity?.userId ?: opening?.userId?.value
        event.requestId = identity?.requestId ?: opening?.preparationId
        event.admissionSequence = (admission as? AdmissionResultPayload.Accepted)?.sequence ?: observation?.executionSequence ?: -1
        event.logPosition = observation?.position ?: fallbackPosition
        event.role = observation?.appliedRole ?: fallbackRole
        event.failed = productFailed || observation?.failure != null
        if (observation?.executionSequence != null) {
            requireNotNull(executionIdentity) { "JFR 실행 callback의 원래 요청 식별자가 없습니다" }
        }
        event.commit()
    }
}

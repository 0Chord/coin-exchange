package com.exchange.core.api.trading.infrastructure.aeron

import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** 같은 부하 JVM 안에서만 비교하는 시각이다. 노드의 절대 시각을 이 기록에 섞지 않는다. */
internal data class HttpPerformanceClientStage(
    val kind: String,
    val userId: String,
    val requestId: String,
    val memberId: Int?,
    val callbackEnteredNs: Long,
    val lockAcquiredNs: Long,
    val interpretationFinishedNs: Long?,
    val bodyPublishStartedNs: Long? = null,
    val bodyPublishCompletedNs: Long? = null,
    val bodySubscriptions: Int? = null,
    val responseHeadersEnteredNs: Long? = null,
    val responseBodyFirstNextNs: Long? = null,
    val responseBodyLastNextNs: Long? = null,
    val responseBodyOnCompleteNs: Long? = null,
    val responseBodyCompletedNs: Long? = null,
    val responseBodyGetBodyEnteredNs: Long? = null,
) {
    val observationLockWaitNs: Long get() = lockAcquiredNs - callbackEnteredNs
    val interpretationNs: Long? get() = interpretationFinishedNs?.minus(lockAcquiredNs)
}

/**
 * Public response-consumption boundaries, not socket-read timestamps. Retains the JDK ofString decoder.
 * A public subscriber wrapper may make JDK getBody scheduling asynchronous; that entry is captured too.
 */
internal class HttpPerformanceResponseTrace(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Volatile
    var headersEnteredNs: Long? = null
        private set

    @Volatile
    var firstNextNs: Long? = null
        private set

    @Volatile
    var lastNextNs: Long? = null
        private set

    @Volatile
    var onCompleteNs: Long? = null
        private set

    @Volatile
    var bodyCompletedNs: Long? = null
        private set

    @Volatile
    var getBodyEnteredNs: Long? = null
        private set

    fun handler(): HttpResponse.BodyHandler<String> =
        HttpResponse.BodyHandler { info ->
            headersEnteredNs = nanoTime()
            val delegate = HttpResponse.BodyHandlers.ofString().apply(info)
            // ofString.getBody only returns its future. Observe it before onComplete can complete it,
            // even if the JDK's later call to the wrapper's getBody is delayed by executor scheduling.
            val observedBody =
                delegate.body.thenApply { body ->
                    bodyCompletedNs = nanoTime()
                    body
                }
            object : HttpResponse.BodySubscriber<String> {
                override fun getBody(): CompletionStage<String> {
                    getBodyEnteredNs = nanoTime()
                    return observedBody
                }

                override fun onSubscribe(subscription: Flow.Subscription) = delegate.onSubscribe(subscription)

                override fun onNext(item: List<ByteBuffer>) {
                    val enteredNs = nanoTime()
                    if (firstNextNs == null) firstNextNs = enteredNs
                    lastNextNs = enteredNs
                    delegate.onNext(item)
                }

                override fun onError(throwable: Throwable) = delegate.onError(throwable)

                override fun onComplete() {
                    // The delegate may complete its body future synchronously inside this call.
                    onCompleteNs = nanoTime()
                    delegate.onComplete()
                }
            }
        }
}

/** 테스트 상세 기록만 소유한다. 정합성 검사나 기존 HTTP/완료 시각을 대체하지 않는다. */
internal class HttpPerformanceClientStages(
    val enabled: Boolean,
    private val capacity: Int = 8192,
) {
    private val samples = ArrayList<HttpPerformanceClientStage>()
    private val identities = HashSet<Triple<String, String, Int?>>()
    private val issues = linkedSetOf<String>()
    private var overflow = 0L

    init {
        require(capacity in 1..8192)
    }

    @Synchronized
    fun record(sample: HttpPerformanceClientStage) {
        if (!enabled) return
        if (sample.observationLockWaitNs < 0 || (sample.interpretationNs ?: 0) < 0) {
            issues.add("CLIENT_STAGE_NEGATIVE_DURATION")
        }
        if (!identities.add(Triple(sample.kind + ":" + sample.userId, sample.requestId, sample.memberId))) {
            issues.add("CLIENT_STAGE_DUPLICATE")
            return
        }
        if (samples.size >= capacity) {
            overflow++
            // identity 추적도 같은 상한을 지킨다.
            identities.remove(Triple(sample.kind + ":" + sample.userId, sample.requestId, sample.memberId))
            return
        }
        samples.add(sample)
    }

    @Synchronized
    fun count(kind: String): Int = samples.count { it.kind == kind }

    @Synchronized
    fun snapshot(): Map<String, Any> =
        mapOf(
            "enabled" to enabled,
            "capacity" to capacity,
            "samples" to samples.toList(),
            "overflow" to overflow,
            "errors" to issues.toList(),
        )
}

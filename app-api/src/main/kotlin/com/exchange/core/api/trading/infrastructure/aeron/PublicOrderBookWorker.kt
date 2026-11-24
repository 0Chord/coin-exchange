package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.application.OrderQueryCode
import com.exchange.core.api.trading.application.PublicOrderBook
import com.exchange.core.api.trading.application.PublicOrderBookQueryResult
import com.exchange.core.trading.TradingServiceRole
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 재생과 잔량 계산을 한 스레드에서 끝내고 완성된 호가만 공개한다. */
internal class PublicOrderBookWorker(
    private val configuration: SplitTradingConfiguration,
    private val newSource: () -> SplitArchiveSource,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Publication(
        val result: PublicOrderBookQueryResult,
        val caughtUpAt: Long? = null,
    )

    private data class PublishedBoundary(
        val position: Long,
        val applicationPosition: Long,
        val sequence: Long,
        val fingerprint: ByteArray,
    )

    private class ArchivePrefix {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val header = ByteBuffer.allocate(4 * Long.SIZE_BYTES + Int.SIZE_BYTES)
        private val fingerprint = ByteArray(digest.digestLength)

        var applicationPosition = 0L
            private set

        fun include(frame: RecordedApplicationFrame) {
            header.clear()
            header.putLong(frame.position)
            header.putLong(frame.leadershipTermId)
            header.putLong(frame.clusterSessionId)
            header.putLong(frame.timestamp)
            header.putInt(frame.bytes.size)
            // 복구 때 마지막 입력뿐 아니라 앞서 적용한 모든 입력이 같은지 대조한다.
            digest.update(fingerprint)
            digest.update(header.array())
            digest.update(frame.bytes)
            digest.digest(fingerprint, 0, fingerprint.size)
            applicationPosition = frame.position
        }

        fun boundary(
            position: Long,
            sequence: Long,
        ): PublishedBoundary =
            PublishedBoundary(
                position = position,
                applicationPosition = applicationPosition,
                sequence = sequence,
                fingerprint = fingerprint.copyOf(),
            )

        fun reached(
            boundary: PublishedBoundary,
            sequence: Long,
        ): Boolean {
            if (applicationPosition < boundary.applicationPosition) return false
            require(
                applicationPosition == boundary.applicationPosition && sequence == boundary.sequence &&
                    fingerprint.contentEquals(boundary.fingerprint),
            ) { "다시 재생한 Archive가 공개했던 호가의 경계와 다릅니다" }
            return true
        }
    }

    private val market =
        configuration.matchingConfiguration.markets
            .single()
            .market.marketId
    private val stream = configuration.matchingConfiguration.resultStream(market)
    private val logger = LoggerFactory.getLogger(javaClass)
    private val thread = Thread(::run, "public-order-book").apply { isDaemon = true }

    @Volatile
    private var closed = false

    @Volatile
    private var published = Publication(PublicOrderBookQueryResult(OrderQueryCode.QUERY_NOT_READY))

    fun start() = thread.start()

    fun read(): PublicOrderBookQueryResult {
        if (closed) return PublicOrderBookQueryResult(OrderQueryCode.QUERY_NOT_READY)
        val current = published
        val caughtUpAt = current.caughtUpAt ?: return current.result
        if (nanoTime() - caughtUpAt >= MAX_AGE_NS) return PublicOrderBookQueryResult(OrderQueryCode.QUERY_UNAVAILABLE)
        return current.result
    }

    private fun run() {
        var source: SplitArchiveSource? = null
        var replay = SplitArchiveProjection(TradingServiceRole.MATCHING, configuration)
        var book = PublicOrderBook(stream)
        var position = 0L
        var prefix = ArchivePrefix()
        var lastPublished: PublishedBoundary? = null
        var recoveryBoundary: PublishedBoundary? = null
        var caughtUpAt: Long? = null
        try {
            while (!closed) {
                var replayStarted = false
                val delay =
                    try {
                        val reader = source ?: newSource().also { source = it }
                        val previousPosition = position
                        val page =
                            reader.read(previousPosition) { frame ->
                                replayStarted = true
                                for (result in replay.apply(frame).matching) book.apply(result)
                                prefix.include(frame)
                                val boundary = recoveryBoundary
                                if (boundary != null && prefix.reached(boundary, book.appliedThrough)) recoveryBoundary = null
                            }
                        require(page.position >= previousPosition) { "호가 Archive 위치가 이전보다 작습니다" }
                        position = page.position
                        val minimumPosition = lastPublished?.position ?: 0L
                        val restored = recoveryBoundary == null && position >= minimumPosition
                        if (page.caughtUp && !restored) {
                            if (position != previousPosition) {
                                logger.warn("다시 재생한 공개 호가 Archive 위치가 짧습니다: {} < {}", position, minimumPosition)
                            }
                            caughtUpAt = null
                            published = Publication(PublicOrderBookQueryResult(OrderQueryCode.QUERY_INCONSISTENT))
                        }
                        if (page.caughtUp && restored && replay.isReady) caughtUpAt = nanoTime()
                        val verifiedAt = caughtUpAt
                        if (verifiedAt != null) {
                            if (lastPublished?.position != position) lastPublished = prefix.boundary(position, book.appliedThrough)
                            publish(book, verifiedAt)
                        }
                        if (page.caughtUp || position == previousPosition) POLL_DELAY_MS else 0L
                    } catch (failure: InterruptedException) {
                        throw failure
                    } catch (failure: Exception) {
                        if (closed) break
                        val code =
                            when (failure) {
                                is IllegalArgumentException, is ArithmeticException, is SplitArchiveContinuityException -> {
                                    OrderQueryCode.QUERY_INCONSISTENT
                                }

                                else -> {
                                    OrderQueryCode.QUERY_UNAVAILABLE
                                }
                            }
                        if (published.result.code != code) logger.warn("공개 호가 원본을 다시 확인합니다: market={}", market, failure)
                        published = Publication(PublicOrderBookQueryResult(code))
                        caughtUpAt = null
                        // 페이지 중간에 재생이 실패하면 읽기 위치와 두 가변 상태를 함께 초기화한다.
                        if (replayStarted) {
                            closeSource(source)
                            source = null
                            replay = SplitArchiveProjection(TradingServiceRole.MATCHING, configuration)
                            book = PublicOrderBook(stream)
                            position = 0
                            prefix = ArchivePrefix()
                            recoveryBoundary = lastPublished
                        }
                        RETRY_DELAY_MS
                    }
                if (delay > 0) Thread.sleep(delay)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closeSource(source)
        }
    }

    private fun publish(
        book: PublicOrderBook,
        caughtUpAt: Long,
    ) {
        val previous = published.result
        val result =
            if (previous.asOfMatchingResultSequence == book.appliedThrough) {
                previous
            } else {
                val snapshot = book.snapshot()
                PublicOrderBookQueryResult(
                    code = OrderQueryCode.ORDER_BOOK_FOUND,
                    marketId = market.value,
                    asOfMatchingResultSequence = snapshot.asOfMatchingResultSequence,
                    bids = snapshot.bids,
                    asks = snapshot.asks,
                )
            }
        published = Publication(result, caughtUpAt)
    }

    private fun closeSource(source: SplitArchiveSource?) {
        if (source == null) return
        runCatching(source::close).onFailure { logger.warn("공개 호가 Archive를 닫지 못했습니다", it) }
    }

    override fun close() {
        closed = true
        thread.interrupt()
        try {
            thread.join(5_000)
            check(!thread.isAlive) { "공개 호가 작업을 종료하지 못했습니다" }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val POLL_DELAY_MS = 20L
        const val RETRY_DELAY_MS = 250L
        val MAX_AGE_NS = TimeUnit.SECONDS.toNanos(1)
    }
}

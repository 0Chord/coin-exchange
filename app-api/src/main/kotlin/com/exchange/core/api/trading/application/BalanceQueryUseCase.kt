package com.exchange.core.api.trading.application

import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance

internal enum class BalanceQueryCode(
    val status: Int,
) {
    BALANCE_FOUND(200),
    BALANCE_NOT_FOUND(404),
    QUERY_CATCHING_UP(202),
    QUERY_NOT_READY(503),
    QUERY_UNAVAILABLE(503),
    QUERY_INCONSISTENT(503),
}

internal data class BalanceQuerySnapshot(
    val lifecycle: OrderQueryLifecycle?,
    val ledgerThrough: Long,
    val projectedThrough: Long,
    val available: Long? = null,
    val reserved: Long? = null,
)

internal data class BalanceArchiveObservation(
    val ledgerThrough: Long,
    val caughtUp: Boolean,
    val expiresAtNs: Long,
)

internal data class BalanceSourceStatus(
    val observation: BalanceArchiveObservation? = null,
    val error: BalanceQueryCode? = null,
    val session: Any = Any(),
)

internal data class BalanceQueryResult(
    val code: BalanceQueryCode,
    val projectedThrough: Long? = null,
    val observedThrough: Long? = null,
    val available: Long? = null,
    val reserved: Long? = null,
)

internal class BalanceQueryUseCase(
    private val sourceStatus: () -> BalanceSourceStatus?,
    private val read: (userId: String, assetId: String) -> BalanceQuerySnapshot,
) {
    fun get(
        userId: String,
        assetId: String,
    ): BalanceQueryResult {
        OpeningBalance.validateAccount(UserId(userId), AssetId(assetId))

        val before = sourceStatus() ?: return BalanceQueryResult(BalanceQueryCode.QUERY_NOT_READY)
        readFailure(before)?.let { return BalanceQueryResult(it) }
        val snapshot =
            try {
                read(userId, assetId)
            } catch (_: OrderQueryUnavailableException) {
                return BalanceQueryResult(BalanceQueryCode.QUERY_UNAVAILABLE)
            } catch (_: OrderQueryInconsistentException) {
                return BalanceQueryResult(BalanceQueryCode.QUERY_INCONSISTENT)
            }
        if (snapshot.lifecycle == OrderQueryLifecycle.BLOCKED || snapshot.projectedThrough > snapshot.ledgerThrough) {
            return BalanceQueryResult(BalanceQueryCode.QUERY_INCONSISTENT)
        }
        if (snapshot.lifecycle != OrderQueryLifecycle.READY) {
            return BalanceQueryResult(BalanceQueryCode.QUERY_NOT_READY)
        }

        val after = sourceStatus() ?: return BalanceQueryResult(BalanceQueryCode.QUERY_NOT_READY)
        readFailure(after)?.let { return BalanceQueryResult(it) }
        if (before.session !== after.session) return BalanceQueryResult(BalanceQueryCode.QUERY_NOT_READY)

        val observation = checkNotNull(after.observation)
        val observedThrough = maxOf(observation.ledgerThrough, snapshot.ledgerThrough)
        if (!observation.caughtUp || snapshot.projectedThrough < observedThrough) {
            return BalanceQueryResult(
                code = BalanceQueryCode.QUERY_CATCHING_UP,
                projectedThrough = snapshot.projectedThrough,
                observedThrough = observedThrough,
            )
        }
        val code = if (snapshot.available == null) BalanceQueryCode.BALANCE_NOT_FOUND else BalanceQueryCode.BALANCE_FOUND
        return BalanceQueryResult(
            code = code,
            projectedThrough = snapshot.projectedThrough,
            available = snapshot.available,
            reserved = snapshot.reserved,
        )
    }

    private fun readFailure(source: BalanceSourceStatus): BalanceQueryCode? {
        source.error?.let { return it }
        val observation = source.observation ?: return BalanceQueryCode.QUERY_NOT_READY
        return if (System.nanoTime() - observation.expiresAtNs >= 0) BalanceQueryCode.QUERY_UNAVAILABLE else null
    }
}

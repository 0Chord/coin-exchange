package com.exchange.core.ledger

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId

data class Balance(
    val userId: UserId,
    val assetId: AssetId,
    val available: Amount,
    val hold: Amount,
) {
    fun reserve(
        amount: Amount,
    ): Balance {
        if (available < amount) {
            throw InsufficientBalanceException(
                userId = userId,
                assetId = assetId,
                available = available,
                requested = amount,
            )
        }

        val nextAvailable =
            Amount(
                Math.subtractExact(
                    available.value,
                    amount.value,
                ),
            )

        val nextHold =
            Amount(
                Math.addExact(
                    hold.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
            hold = nextHold,
        )
    }

    fun release(
        amount: Amount,
    ): Balance {
        if (hold < amount) {
            throw InsufficientHoldException(
                userId = userId,
                assetId = assetId,
                hold = hold,
                requested = amount,
            )
        }

        val nextHold =
            Amount(
                Math.subtractExact(
                    hold.value,
                    amount.value,
                ),
            )

        val nextAvailable =
            Amount(
                Math.addExact(
                    available.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
            hold = nextHold,
        )
    }

    fun consumeHold(
        amount: Amount,
    ): Balance {
        if (hold < amount) {
            throw InsufficientHoldException(
                userId = userId,
                assetId = assetId,
                hold = hold,
                requested = amount,
            )
        }

        val nextHold =
            Amount(
                Math.subtractExact(
                    hold.value,
                    amount.value,
                ),
            )

        return copy(
            hold = nextHold,
        )
    }

    fun credit(
        amount: Amount,
    ): Balance {
        val nextAvailable =
            Amount(
                Math.addExact(
                    available.value,
                    amount.value,
                ),
            )

        return copy(
            available = nextAvailable,
        )
    }
}

class InsufficientBalanceException(
    val userId: UserId,
    val assetId: AssetId,
    val available: Amount,
    val requested: Amount,
) : IllegalStateException(
    "insufficient balance: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}, " +
        "available=${available.value}, " +
        "requested=${requested.value}",
)

class InsufficientHoldException(
    val userId: UserId,
    val assetId: AssetId,
    val hold: Amount,
    val requested: Amount,
) : IllegalStateException(
    "insufficient hold: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}, " +
        "hold=${hold.value}, " +
        "requested=${requested.value}",
)

class BalanceNotFoundException(
    val userId: UserId,
    val assetId: AssetId,
) : IllegalStateException(
    "balance not found: " +
        "userId=${userId.value}, " +
        "assetId=${assetId.value}",
)

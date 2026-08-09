package com.exchange.core.api.ledger.persistence

import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.Balance
import com.exchange.core.ledger.BalanceNotFoundException
import com.exchange.core.ledger.BalanceStore
import com.exchange.core.ledger.InsufficientBalanceException
import com.exchange.core.ledger.InsufficientHoldException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Transactional

open class PostgresBalanceStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : BalanceStore {
    @Transactional
    override fun reserve(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance {
        val updatedBalance =
            jdbcTemplate
                .query(
                    """
                    update balance_projection
                    set available = available - :amount,
                        hold = hold + :amount,
                        updated_at = current_timestamp
                    where user_id = :userId
                      and asset_id = :assetId
                      and available >= :amount
                    returning user_id, asset_id, available, hold
                    """.trimIndent(),
                    mutationParameters(userId, assetId, amount),
                    balanceRowMapper,
                ).singleOrNull()

        if (updatedBalance != null) return updatedBalance

        val currentBalance = findRequiredBalance(userId, assetId)

        throw InsufficientBalanceException(
            userId = userId,
            assetId = assetId,
            available = currentBalance.available,
            requested = amount,
        )
    }

    @Transactional
    override fun release(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance {
        val updatedBalance =
            jdbcTemplate
                .query(
                    """
                    update balance_projection
                    set available = available + :amount,
                        hold = hold - :amount,
                        updated_at = current_timestamp
                    where user_id = :userId
                      and asset_id = :assetId
                      and hold >= :amount
                    returning user_id, asset_id, available, hold
                    """.trimIndent(),
                    mutationParameters(userId, assetId, amount),
                    balanceRowMapper,
                ).singleOrNull()

        if (updatedBalance != null) return updatedBalance

        val currentBalance = findRequiredBalance(userId, assetId)

        throw InsufficientHoldException(
            userId = userId,
            assetId = assetId,
            hold = currentBalance.hold,
            requested = amount,
        )
    }

    private fun findRequiredBalance(
        userId: UserId,
        assetId: AssetId,
    ): Balance =
        jdbcTemplate
            .query(
                """
                select user_id, asset_id, available, hold
                from balance_projection
                where user_id = :userId
                  and asset_id = :assetId
                """.trimIndent(),
                mapOf(
                    "userId" to userId.value,
                    "assetId" to assetId.value,
                ),
                balanceRowMapper,
            ).singleOrNull()
            ?: throw BalanceNotFoundException(
                userId = userId,
                assetId = assetId,
            )

    private fun mutationParameters(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Map<String, Any> =
        mapOf(
            "userId" to userId.value,
            "assetId" to assetId.value,
            "amount" to amount.value,
        )

    private val balanceRowMapper =
        RowMapper<Balance> { resultSet, _ ->
            Balance(
                userId = UserId(resultSet.getString("user_id")),
                assetId = AssetId(resultSet.getString("asset_id")),
                available = Amount(resultSet.getLong("available")),
                hold = Amount(resultSet.getLong("hold")),
            )
        }
}

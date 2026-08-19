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

/**
 * `balance_projection` 테이블을 직접 갱신하는 [BalanceStore] PostgreSQL 구현체.
 *
 * 잔고를 먼저 읽고 나중에 쓰는 방식은 동시 요청에서 금액을 잃을 수 있다. 그래서
 * reserve, release, consumeHold는 잔액 조건이 포함된 단일 UPDATE를 실행한다.
 * 조건을 만족한 row만 변경하고 PostgreSQL `returning`으로 변경 직후 Balance를 받는다.
 *
 * UPDATE 결과가 없으면 [findRequiredBalance]로 원인을 구분한다.
 * - row 자체가 없음: [BalanceNotFoundException]
 * - row는 있지만 available 또는 hold 부족: 잔고 부족 예외
 *
 * @property jdbcTemplate 이름 기반 SQL parameter와 row mapping을 제공하는 Spring JDBC 도구
 */
open class PostgresBalanceStore(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : BalanceStore {
    /**
     * available이 충분할 때만 `available -= amount`, `hold += amount`를 원자적으로 수행한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 주문에 예약할 자산
     * @param amount available에서 hold로 이동할 최소 단위 금액
     * @return UPDATE 직후의 잔고
     * @throws BalanceNotFoundException 사용자·자산 row가 없는 경우
     * @throws InsufficientBalanceException row는 있지만 available이 부족한 경우
     */
    @Transactional
    override fun reserve(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance {
        // available 조건과 차감을 한 SQL에 묶어 동시 reserve에서도 음수 잔고를 막는다.
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

        // UPDATE가 0건이면 row 부재와 available 부족을 현재 snapshot 조회로 구분한다.
        val currentBalance = findRequiredBalance(userId, assetId)

        throw InsufficientBalanceException(
            userId = userId,
            assetId = assetId,
            available = currentBalance.available,
            requested = amount,
        )
    }

    /**
     * hold가 충분할 때만 `hold -= amount`, `available += amount`를 원자적으로 수행한다.
     *
     * 주문 취소 또는 BUY 체결의 가격 개선 금액을 돌려줄 때 사용한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 동결을 해제할 자산
     * @param amount hold에서 available로 이동할 최소 단위 금액
     * @return UPDATE 직후의 잔고
     * @throws BalanceNotFoundException 사용자·자산 row가 없는 경우
     * @throws InsufficientHoldException row는 있지만 hold가 부족한 경우
     */
    @Transactional
    override fun release(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance {
        // hold 조건과 반환을 한 SQL에 묶어 동시에 같은 hold를 중복 반환하지 못하게 한다.
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

        // UPDATE가 0건이면 row 부재와 hold 부족을 구분한다.
        val currentBalance = findRequiredBalance(userId, assetId)

        throw InsufficientHoldException(
            userId = userId,
            assetId = assetId,
            hold = currentBalance.hold,
            requested = amount,
        )
    }

    /**
     * hold가 충분할 때 실제 체결에 사용된 금액만 원자적으로 제거한다.
     *
     * release와 달리 available은 늘리지 않는다. 체결 상대 자산의 증가는 별도 [credit]으로
     * 처리된다.
     *
     * @param userId 잔고 소유자
     * @param assetId 소비할 hold 자산
     * @param amount 체결에 사용된 최소 단위 금액
     * @return UPDATE 직후의 잔고
     * @throws BalanceNotFoundException 사용자·자산 row가 없는 경우
     * @throws InsufficientHoldException row는 있지만 hold가 부족한 경우
     */
    @Transactional
    override fun consumeHold(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance {
        // 조건부 UPDATE 하나로 같은 hold를 두 체결이 동시에 소비하는 것을 막는다.
        val updatedBalance =
            jdbcTemplate
                .query(
                    """
                    update balance_projection
                    set hold = hold - :amount,
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

        val currentBalance =
            findRequiredBalance(
                userId = userId,
                assetId = assetId,
            )

        throw InsufficientHoldException(
            userId = userId,
            assetId = assetId,
            hold = currentBalance.hold,
            requested = amount,
        )
    }

    /**
     * 지급받은 자산을 `available += amount`로 원자적으로 반영한다.
     *
     * @param userId 자산을 지급받을 사용자
     * @param assetId 지급할 자산
     * @param amount available에 더할 최소 단위 금액
     * @return UPDATE 직후의 잔고
     * @throws BalanceNotFoundException 사용자·자산 row가 없는 경우
     */
    @Transactional
    override fun credit(
        userId: UserId,
        assetId: AssetId,
        amount: Amount,
    ): Balance =
        jdbcTemplate
            .query(
                """
                update balance_projection
                set available = available + :amount,
                    updated_at = current_timestamp
                where user_id = :userId
                  and asset_id = :assetId
                returning user_id, asset_id, available, hold
                """.trimIndent(),
                mutationParameters(userId, assetId, amount),
                balanceRowMapper,
            ).singleOrNull()
            ?: throw BalanceNotFoundException(
                userId = userId,
                assetId = assetId,
            )

    /**
     * 실패 원인 판별에 사용할 현재 Balance를 조회한다.
     *
     * @param userId 잔고 소유자
     * @param assetId 조회할 자산
     * @return 현재 저장된 Balance
     * @throws BalanceNotFoundException 사용자·자산 row가 없는 경우
     */
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

    /**
     * 모든 잔고 변경 SQL이 공유하는 named parameter map을 만든다.
     *
     * @param userId `:userId`에 넣을 사용자
     * @param assetId `:assetId`에 넣을 자산
     * @param amount `:amount`에 넣을 최소 단위 금액
     * @return value class를 JDBC 원시값으로 푼 parameter map
     */
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

    /** SQL 결과의 최소 단위 정수 값을 domain value class로 복원하는 mapper. */
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

package com.exchange.core.api.trading.infrastructure.persistence

import com.exchange.core.api.trading.application.BalanceQuerySnapshot
import com.exchange.core.api.trading.application.OrderQueryLifecycle
import com.exchange.core.api.trading.application.OrderQueryRecoveryFailure
import com.exchange.core.api.trading.application.port.OrderQueryInconsistentException
import com.exchange.core.api.trading.application.port.OrderQueryLedgerException
import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import com.exchange.core.api.trading.application.port.OrderQueryWriteConflictException
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.LedgerPostingSide.CREDIT
import com.exchange.core.ledger.LedgerPostingSide.DEBIT
import com.exchange.core.ledger.LedgerReconciliation
import com.exchange.core.ledger.LedgerTransaction
import com.exchange.core.ledger.LedgerTransactionType
import com.exchange.core.ledger.OpeningBalance
import com.exchange.core.ledger.OpeningBalanceStateException
import com.exchange.core.ledger.ReconciliationItem
import com.exchange.core.trading.FundsLedgerBatch
import com.exchange.core.trading.TradingMarketContext
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigInteger

/** 자금 원장의 연속 범위를 사용자·자산별 잔고로 반영한다. */
internal class PostgresBalanceProjection(
    private val query: PostgresOrderQueryStore,
    private val ledger: PostgresOrderQueryFeedStore,
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val namespace = mapOf("namespace" to query.namespaceKey)

    fun read(
        userId: String,
        assetId: String,
    ): BalanceQuerySnapshot =
        try {
            jdbc
                .query(
                    """
                    select p.lifecycle, coalesce(b.projected_through, 0) as projected_through,
                        coalesce((select max(sequence) from order_query.funds_ledger_batch where namespace = p.namespace), 0) as ledger_through,
                        a.available, a.reserved
                    from order_query.query_progress p
                    left join order_query.balance_progress b on b.namespace = p.namespace
                    left join order_query.balance_projection a on a.namespace = p.namespace
                        and a.user_id = :userId and a.asset_id = :assetId
                    where p.namespace = :namespace
                    """.trimIndent(),
                    namespace + mapOf("userId" to userId, "assetId" to assetId),
                ) { row, _ ->
                    val ledgerThrough = row.getLong("ledger_through")
                    val projectedThrough = row.getLong("projected_through")
                    val available = row.getObject("available", Long::class.javaObjectType)
                    val reserved = row.getObject("reserved", Long::class.javaObjectType)
                    if (ledgerThrough < 0L || projectedThrough < 0L || (available == null) != (reserved == null) ||
                        (available != null && (projectedThrough == 0L || available < 0L || checkNotNull(reserved) < 0L))
                    ) {
                        throw OrderQueryInconsistentException("저장된 잔고 또는 원장 진행 순번이 올바르지 않습니다")
                    }
                    BalanceQuerySnapshot(
                        lifecycle = query.sourceLifecycle(OrderQueryLifecycle.valueOf(row.getString("lifecycle"))),
                        ledgerThrough = ledgerThrough,
                        projectedThrough = projectedThrough,
                        available = available,
                        reserved = reserved,
                    )
                }.singleOrNull() ?: BalanceQuerySnapshot(lifecycle = null, ledgerThrough = 0, projectedThrough = 0)
        } catch (error: DataAccessException) {
            throw OrderQueryUnavailableException("잔고 조회 DB에 연결할 수 없습니다", error)
        } catch (error: IllegalArgumentException) {
            throw OrderQueryInconsistentException("저장된 잔고 조회 상태를 해석할 수 없습니다", error)
        }

    fun applyNextPage(context: TradingMarketContext): Boolean {
        require(query.namespace.markets == setOf(context.market.marketId.value)) { "잔고 반영에는 단일 마켓이 필요합니다" }
        if (isCaughtUp()) return false
        return try {
            query.withTransaction {
                // 잔고 행 잠금이 길어지면 원래 Archive 반영 작업으로 돌아간다.
                jdbc.jdbcTemplate.execute("set local lock_timeout = '2s'")
                val progress = query.lockedProgress()
                if (progress.lifecycle == OrderQueryLifecycle.BLOCKED) return@withTransaction false
                val projectedThrough = projectedThrough()
                val ledgerThrough = ledger.ledgerThrough()
                if (projectedThrough > ledgerThrough) missingLedger(projectedThrough)
                if (projectedThrough == ledgerThrough) return@withTransaction false

                val batches = ledger.readLedgerPage(projectedThrough)
                if (batches.isEmpty()) missingLedger(projectedThrough + 1)
                var expectedSequence = projectedThrough + 1
                var previousPosition = ledgerPosition(projectedThrough)
                val changes = ArrayList<Map<BalanceAccount, BalanceDelta>>(batches.size)
                for (batch in batches) {
                    if (batch.sequence != expectedSequence) missingLedger(expectedSequence)
                    require(batch.followsLogPosition(previousPosition)) { "원장 로그 위치가 올바르지 않습니다" }
                    changes.add(balanceChanges(batch, context))
                    expectedSequence = batch.sequence + 1
                    previousPosition = batch.logPosition
                }

                val balances = readAffectedBalances(changes.flatMapTo(linkedSetOf()) { it.keys })
                for (batchChanges in changes) {
                    for ((account, delta) in batchChanges) {
                        val previous = balances[account] ?: BalanceAmounts(0, 0)
                        val available = (BigInteger.valueOf(previous.available) + delta.available).longValueExact()
                        val reserved = (BigInteger.valueOf(previous.reserved) + delta.reserved).longValueExact()
                        require(available >= 0 && reserved >= 0) { "반영한 잔고가 음수입니다" }
                        balances[account] = BalanceAmounts(available, reserved)
                    }
                }
                writeBalances(balances)
                jdbc.update(
                    """
                    insert into order_query.balance_progress (namespace, projected_through) values (:namespace, :through)
                    on conflict (namespace) do update set projected_through = excluded.projected_through
                    """.trimIndent(),
                    namespace + ("through" to batches.last().sequence),
                )
                true
            }
        } catch (error: OrderQueryWriteConflictException) {
            // 주문·원장 자체에 대한 충돌과 구분해 잔고 조회만 차단한다.
            throw OrderQueryInconsistentException("원장에서 잔고를 반영할 수 없습니다", error)
        } catch (error: OpeningBalanceStateException) {
            throw OrderQueryInconsistentException("개시 원장을 해석할 수 없습니다", error)
        }
    }

    private fun isCaughtUp(): Boolean =
        jdbc.queryForObject(
            """
            select coalesce((select max(sequence) from order_query.funds_ledger_batch where namespace = :namespace), 0) =
                coalesce((select projected_through from order_query.balance_progress where namespace = :namespace), 0)
            """.trimIndent(),
            namespace,
            Boolean::class.java,
        ) == true

    private fun projectedThrough(): Long =
        jdbc
            .query(
                "select projected_through from order_query.balance_progress where namespace = :namespace",
                namespace,
            ) { row, _ -> row.getLong("projected_through") }
            .singleOrNull() ?: 0

    private fun ledgerPosition(sequence: Long): Long {
        if (sequence == 0L) return 0L
        return jdbc
            .query(
                "select log_position from order_query.funds_ledger_batch where namespace = :namespace and sequence = :sequence",
                namespace + ("sequence" to sequence),
            ) { row, _ -> row.getLong("log_position") }
            .singleOrNull() ?: missingLedger(sequence)
    }

    private fun balanceChanges(
        batch: FundsLedgerBatch,
        context: TradingMarketContext,
    ): Map<BalanceAccount, BalanceDelta> {
        val baseAsset = context.market.baseAssetId
        val quoteAsset = context.market.quoteAssetId
        val supportedAssets = setOf(baseAsset, quoteAsset)
        val changes = linkedMapOf<BalanceAccount, BalanceDelta>()
        for (transaction in batch.transactions) {
            when (transaction.transactionType) {
                LedgerTransactionType.OPENING -> {
                    val opening = OpeningBalance.fromTransaction(transaction)
                    require(opening.assetId in supportedAssets) { "마켓 밖의 지급 자산입니다" }
                    val delta = changes.getOrPut(BalanceAccount(opening.userId, opening.assetId), ::BalanceDelta)
                    delta.available += BigInteger.valueOf(opening.amount.value)
                }

                LedgerTransactionType.RESERVE, LedgerTransactionType.RELEASE -> {
                    val reserve = transaction.transactionType == LedgerTransactionType.RESERVE
                    val debitBalance = if (reserve) ReconciliationItem.AVAILABLE else ReconciliationItem.HOLD
                    val creditBalance = if (reserve) ReconciliationItem.HOLD else ReconciliationItem.AVAILABLE
                    val owner = transferAccount(transaction, debitBalance, creditBalance)
                    require(owner.asset in supportedAssets) { "마켓 밖의 예약·반환 자산입니다" }
                    OpeningBalance.validateAccount(owner.user, owner.asset)
                    val amount = BigInteger.valueOf(transaction.postings[0].amount.value)
                    val held = if (reserve) amount else -amount
                    val delta = changes.getOrPut(owner, ::BalanceDelta)
                    delta.available -= held
                    delta.reserved += held
                    if (reserve) {
                        val counterpart = if (owner.asset == baseAsset) quoteAsset else baseAsset
                        // 자금 엔진이 성공한 예약에서 만드는 상대 자산 행이다.
                        changes.getOrPut(BalanceAccount(owner.user, counterpart), ::BalanceDelta)
                    }
                }

                LedgerTransactionType.SETTLEMENT -> {
                    applySettlementDeltas(transaction, supportedAssets, changes)
                }

                else -> {
                    throw OrderQueryInconsistentException("잔고에 반영할 수 없는 원장 유형입니다")
                }
            }
        }
        require(changes.isNotEmpty()) { "원장의 사용자 잔고 변경이 없습니다" }
        return changes
    }

    private fun applySettlementDeltas(
        transaction: LedgerTransaction,
        assets: Set<AssetId>,
        changes: MutableMap<BalanceAccount, BalanceDelta>,
    ) {
        for (posting in transaction.postings) {
            val asset = posting.assetId
            require(asset in assets) { "마켓 밖의 정산 자산입니다" }
            val account = LedgerReconciliation.parseUserAccount(posting.accountId, asset)
            if (account == null) {
                require(posting.accountId == "SYSTEM:${asset.value}:FEE_REVENUE" && posting.side == CREDIT) {
                    "정산 원장 계정을 해석할 수 없습니다"
                }
                continue
            }
            val (user, balance) = account
            OpeningBalance.validateAccount(user, asset)
            val amount = BigInteger.valueOf(posting.amount.value)
            val delta = changes.getOrPut(BalanceAccount(user, asset), ::BalanceDelta)
            if (balance == ReconciliationItem.AVAILABLE) {
                require(posting.side == CREDIT) { "정산 원장의 사용 가능액 방향이 올바르지 않습니다" }
                delta.available += amount
            } else {
                require(posting.side == DEBIT) { "정산 원장의 예약액 방향이 올바르지 않습니다" }
                delta.reserved -= amount
            }
        }
    }

    private fun transferAccount(
        transaction: LedgerTransaction,
        debitBalance: ReconciliationItem,
        creditBalance: ReconciliationItem,
    ): BalanceAccount {
        require(transaction.postings.size == 2) { "예약·반환 원장에는 한 사용자 계정 사이의 이동만 허용됩니다" }
        val debit = transaction.postings[0]
        val credit = transaction.postings[1]
        val source = LedgerReconciliation.parseUserAccount(debit.accountId, debit.assetId)
        val target = LedgerReconciliation.parseUserAccount(credit.accountId, credit.assetId)
        require(
            source != null && source.second == debitBalance && debit.side == DEBIT &&
                target != null && target.second == creditBalance && credit.side == CREDIT &&
                source.first == target.first && debit.assetId == credit.assetId && debit.amount == credit.amount,
        ) { "예약·반환 원장에 올바르지 않은 계정이 있습니다" }
        return BalanceAccount(source.first, debit.assetId)
    }

    private fun readAffectedBalances(accounts: Set<BalanceAccount>): MutableMap<BalanceAccount, BalanceAmounts> {
        val keys = accounts.map { arrayOf(it.user.value, it.asset.value) }
        return jdbc
            .query(
                """
                select user_id, asset_id, available, reserved from order_query.balance_projection
                where namespace = :namespace and (user_id, asset_id) in (:keys)
                """.trimIndent(),
                namespace + ("keys" to keys),
            ) { row, _ ->
                BalanceAccount(UserId(row.getString("user_id")), AssetId(row.getString("asset_id"))) to
                    BalanceAmounts(row.getLong("available"), row.getLong("reserved"))
            }.toMap(LinkedHashMap())
    }

    private fun writeBalances(balances: Map<BalanceAccount, BalanceAmounts>) {
        val rows =
            balances.map { (account, amounts) ->
                namespace +
                    mapOf(
                        "userId" to account.user.value,
                        "assetId" to account.asset.value,
                        "available" to amounts.available,
                        "reserved" to amounts.reserved,
                    )
            }
        jdbc.batchUpdate(
            """
            insert into order_query.balance_projection(namespace, user_id, asset_id, available, reserved)
            values (:namespace, :userId, :assetId, :available, :reserved)
            on conflict (namespace, user_id, asset_id) do update set available = excluded.available, reserved = excluded.reserved
            """.trimIndent(),
            rows.toTypedArray(),
        )
    }

    private fun missingLedger(sequence: Long): Nothing =
        throw OrderQueryLedgerException(OrderQueryRecoveryFailure.LEDGER_INCOMPLETE, "잔고 반영 원장 묶음 $sequence 누락")

    private data class BalanceAccount(
        val user: UserId,
        val asset: AssetId,
    )

    private data class BalanceAmounts(
        val available: Long,
        val reserved: Long,
    )

    private class BalanceDelta {
        var available: BigInteger = BigInteger.ZERO
        var reserved: BigInteger = BigInteger.ZERO
    }
}

package com.exchange.core.api.config

import com.exchange.core.api.trading.application.BalanceQueryUseCase
import com.exchange.core.api.trading.application.OrderQueryNamespace
import com.exchange.core.api.trading.application.OrderQueryUseCase
import com.exchange.core.api.trading.application.port.OrderQueryFeedStore
import com.exchange.core.api.trading.application.port.OrderQueryStore
import com.exchange.core.api.trading.infrastructure.aeron.AeronGatewaySettings
import com.exchange.core.api.trading.infrastructure.aeron.AeronSplitArchiveSource
import com.exchange.core.api.trading.infrastructure.aeron.ArchiveOrderQueryFeedWorker
import com.exchange.core.api.trading.infrastructure.aeron.OrderQueryFeedWorker
import com.exchange.core.api.trading.infrastructure.aeron.SplitArchiveMember
import com.exchange.core.api.trading.infrastructure.aeron.SplitTradingNodeSettings
import com.exchange.core.api.trading.infrastructure.aeron.splitTradingSettings
import com.exchange.core.api.trading.infrastructure.persistence.PostgresBalanceProjection
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryFeedStore
import com.exchange.core.api.trading.infrastructure.persistence.PostgresOrderQueryStore
import com.exchange.core.trading.ClusterLineage
import com.exchange.core.trading.TradingServiceRole
import io.aeron.cluster.ClusterMember
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** 주문 접수와 같은 WAS에서 공용 RDB 연결과 마이그레이션을 사용하는 조회 구성이다. */
@Configuration(proxyBeanMethods = false)
class OrderQueryConfig {
    @Bean
    fun orderQueryNamespace(environment: Environment): OrderQueryNamespace {
        val prefix = "exchange.order-query."
        require(environment.getProperty(prefix + "source", "live") in setOf("live", "archive")) { "잘못된 주문 조회 원본 설정입니다" }
        val markets = environment.getRequiredProperty(prefix + "supported-markets").split(',').map(String::trim)
        require(markets.none(String::isBlank) && markets.distinct().size == markets.size) { "invalid order query markets" }
        return OrderQueryNamespace(
            ClusterLineage(
                environment.getRequiredProperty(prefix + "funds-lineage-high").toLong(),
                environment.getRequiredProperty(prefix + "funds-lineage-low").toLong(),
            ),
            markets.toSet(),
        )
    }

    @Bean
    fun orderQueryStore(
        namespace: OrderQueryNamespace,
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
    ): PostgresOrderQueryStore {
        val store = PostgresOrderQueryStore(namespace, jdbc, transactionManager)
        store.initialize()
        // 기존 DB가 READY여도 이 WAS의 원본 재검증 전에는 조회를 열지 않는다.
        store.suspendSourceReads()
        return store
    }

    @Bean
    fun orderQueryUseCase(store: OrderQueryStore): OrderQueryUseCase = OrderQueryUseCase(store)

    @Bean
    fun orderQueryFeedStore(
        store: PostgresOrderQueryStore,
        jdbc: NamedParameterJdbcTemplate,
    ): PostgresOrderQueryFeedStore = PostgresOrderQueryFeedStore(store, jdbc)

    @Bean
    @ConditionalOnProperty(prefix = "exchange.order-query", name = ["source"], havingValue = "archive")
    internal fun balanceProjection(
        store: PostgresOrderQueryStore,
        ledger: PostgresOrderQueryFeedStore,
        jdbc: NamedParameterJdbcTemplate,
    ): PostgresBalanceProjection = PostgresBalanceProjection(store, ledger, jdbc)

    @Bean
    internal fun balanceQueryUseCase(
        balances: ObjectProvider<PostgresBalanceProjection>,
        archive: ObjectProvider<ArchiveOrderQueryFeedWorker>,
    ): BalanceQueryUseCase =
        BalanceQueryUseCase({ archive.ifAvailable?.balanceStatus }) { userId, assetId ->
            balances.getObject().read(userId, assetId)
        }

    /** 원본 보존 capability와 같은 consumer 설정이 공급되면 동일 WAS에서 반영을 시작한다. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "exchange.order-query", name = ["consumer-id"])
    @ConditionalOnProperty(prefix = "exchange.order-query", name = ["source"], havingValue = "live", matchIfMissing = true)
    internal fun orderQueryFeedWorker(
        feed: OrderQueryFeedStore,
        namespace: OrderQueryNamespace,
        environment: Environment,
    ): OrderQueryFeedWorker {
        val matchingIngressEntries = environment.getRequiredProperty("exchange.order-query.matching-ingress").split(';')
        val matchingIngressByMarket =
            matchingIngressEntries.associate { entry ->
                require(entry.count { character -> character == '@' } == 1) { "matching query address must be MARKET@ingress" }
                val market = entry.substringBefore('@')
                val ingress = entry.substringAfter('@')
                market to ingress
            }
        require(matchingIngressByMarket.size == matchingIngressEntries.size) { "duplicate query market" }
        return OrderQueryFeedWorker(
            store = feed,
            namespace = namespace,
            consumerId = environment.getRequiredProperty("exchange.order-query.consumer-id"),
            settings = AeronGatewaySettings.from(boundedGatewayEnvironment(environment)),
            matchingIngress = matchingIngressByMarket,
        )
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "exchange.order-query", name = ["source"], havingValue = "archive")
    internal fun archiveOrderQueryFeedWorker(
        feed: OrderQueryFeedStore,
        namespace: OrderQueryNamespace,
        environment: Environment,
        balances: PostgresBalanceProjection,
    ): ArchiveOrderQueryFeedWorker {
        val configFiles =
            environment.getRequiredProperty("exchange.order-query.archive-node-configs").split(',').map { Path.of(it.trim()).normalize() }
        require(configFiles.size in 2..6 && configFiles.distinct().size == configFiles.size && configFiles.all(Path::isAbsolute))
        val nodes =
            configFiles.map { file ->
                val values = Properties()
                Files.newInputStream(file).use(values::load)
                splitTradingSettings(values.stringPropertyNames().associateWith(values::getProperty))
            }
        require(nodes.all { it.resultDeliveryEnabled && it.configuration.archiveQueryEnabled })
        require(nodes.map { it.configuration.resultTopologySha256 }.distinct().size == 1) { "Archive 역할의 배치 설정이 다릅니다" }
        require(nodes.map { it.transport.driverDirectory }.distinct().size == nodes.size) { "Archive 멤버 드라이버 경로가 겹칩니다" }
        require(nodes.map { it.transport.clusterDirectory }.distinct().size == nodes.size) { "Archive 멤버 기록 경로가 겹칩니다" }
        val funds = nodes.filter { it.role == TradingServiceRole.FUNDS }
        val matching = nodes.filter { it.role == TradingServiceRole.MATCHING }
        require(funds.isNotEmpty() && matching.isNotEmpty()) { "자금·매칭 Archive 설정을 모두 지정해야 합니다" }
        val configuration = funds.first().configuration
        return ArchiveOrderQueryFeedWorker(
            store = feed,
            namespace = namespace,
            consumerId = environment.getRequiredProperty("exchange.order-query.consumer-id"),
            configuration = configuration,
            fundsSource = archiveSource(funds),
            matchingSources = mapOf(configuration.matchingLineage to archiveSource(matching)),
            applyBalances = balances::applyNextPage,
        )
    }

    private fun archiveSource(nodes: List<SplitTradingNodeSettings>): AeronSplitArchiveSource {
        val first = nodes.first()
        require(nodes.all { it.clusterId == first.clusterId && it.transport.members == first.transport.members })
        val memberIds = ClusterMember.parse(first.transport.members).map { it.id() }.toSet()
        require(nodes.size == memberIds.size && nodes.map { it.transport.memberId }.toSet() == memberIds) { "Archive 읽기에는 모든 멤버가 필요합니다" }
        val members =
            nodes.map { node ->
                val transport = node.transport
                SplitArchiveMember(transport.memberId, transport.driverDirectory, transport.clusterDirectory)
            }
        return AeronSplitArchiveSource(members, first.clusterId, first.configuration.resultTopologySha256)
    }
}

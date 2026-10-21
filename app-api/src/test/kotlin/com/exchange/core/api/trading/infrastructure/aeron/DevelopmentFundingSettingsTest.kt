package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.AssetId
import com.exchange.core.common.MarketId
import com.exchange.core.fee.FeeProductType
import com.exchange.core.order.MarketDefinition
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevelopmentFundingSettingsTest {
    private val btc = DevelopmentMarketContext.BTC_USDT
    private val sui =
        DevelopmentMarketContext(
            MarketDefinition(MarketId("SUI-USDT"), AssetId("SUI"), AssetId("USDT"), 9),
            btc.feePolicySnapshot.copy(scheduleVersion = 2),
        )
    private val members =
        (0..2).joinToString("|") { member ->
            "$member," + (0..4).joinToString(",") { "node$member:${19700 + it}" }
        }

    @Test
    fun `기존 단일 노드의 loopback 연결은 유지한다`() {
        val settings = DevelopmentFundingSettings(Path.of("/tmp/native-funding"))
        assertFalse(settings.clustered)
        assertEquals("127.0.0.1:19704", settings.endpoint(4))
        assertEquals("0=127.0.0.1:19700", settings.ingressEndpoints)
    }

    @Test
    fun `환경 설정은 공통 멤버와 자기 컨테이너의 경로 주소를 구분한다`() {
        val settings = developmentClusterSettings(environment())
        assertTrue(settings.clustered)
        assertEquals(1, settings.memberId)
        assertEquals("node1:19704", settings.endpoint(4))
        assertEquals("0.0.0.0", settings.bindHost)
        assertEquals("0=node0:19700,1=node1:19700,2=node2:19700", settings.ingressEndpoints)
        assertEquals("/records/run1/driver", settings.driverDirectory)
    }

    @Test
    fun `목록에 없는 멤버나 목적지로 쓸 수 없는 주소를 거절한다`() {
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() + ("FUNDING_MEMBER_ID" to "3")) }
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() + ("FUNDING_HOST" to "0.0.0.0")) }
        assertFailsWith<IllegalArgumentException> { developmentClusterSettings(environment() - "FUNDING_MEMBERS") }
    }

    @Test
    fun `등록된 마켓은 목록 순서와 관계없이 자기 자산과 정책으로 찾는다`() {
        listOf(listOf(btc, sui), listOf(sui, btc)).forEach { contexts ->
            val markets = DevelopmentMarketRegistry(contexts)
            assertEquals(btc, markets[btc.market.marketId])
            assertEquals(sui, markets[sui.market.marketId])
            assertNull(markets[MarketId("ETH-USDT")])
        }
    }

    @Test
    fun `등록 뒤 원본 목록과 조회값을 바꿔도 등록 설정은 바뀌지 않는다`() {
        val source = mutableListOf(btc, sui)
        val markets = DevelopmentMarketRegistry(source)
        source.clear()
        source += btc.copy(feePolicySnapshot = btc.feePolicySnapshot.copy(scheduleVersion = 3))
        val changed = requireNotNull(markets[sui.market.marketId]).copy(market = btc.market)
        assertEquals(btc.market, changed.market)
        assertEquals(btc, markets[btc.market.marketId])
        assertEquals(sui, markets[sui.market.marketId])
    }

    @Test
    fun `빈 등록과 같은 ID 중복은 같은 내용이어도 거절한다`() {
        assertFailsWith<IllegalArgumentException> { DevelopmentMarketRegistry(emptyList()) }
        assertFailsWith<IllegalArgumentException> { DevelopmentMarketRegistry(listOf(btc, btc)) }
        assertFailsWith<IllegalArgumentException> {
            DevelopmentMarketRegistry(listOf(btc, btc.copy(feePolicySnapshot = sui.feePolicySnapshot)))
        }
    }

    @Test
    fun `기본 BTC와 사용자 지정 단일 마켓 접근은 등록값에서 파생된다`() {
        val defaults = DevelopmentFundingSettings(Path.of("/tmp/default-market"))
        assertEquals(btc.market, defaults.market)
        assertEquals(btc.feePolicySnapshot, defaults.feePolicySnapshot)
        val custom = defaults.copy(markets = DevelopmentMarketRegistry(listOf(sui)))
        assertEquals(sui.market, custom.market)
        assertEquals(sui.feePolicySnapshot, custom.feePolicySnapshot)
    }

    @Test
    fun `단일 마켓 전용 접근은 여러 등록 중 첫 항목을 임의로 고르지 않는다`() {
        val settings =
            DevelopmentFundingSettings(Path.of("/tmp/multiple-markets"), markets = DevelopmentMarketRegistry(listOf(btc, sui)))
        assertFailsWith<IllegalArgumentException> { settings.market }
        assertFailsWith<IllegalArgumentException> { settings.feePolicySnapshot }
    }

    @Test
    fun `두 번째 마켓에도 기존 식별자와 현물 정책 제약을 적용한다`() {
        listOf("x".repeat(65), "SUI\u0000", "\uD800").forEach { invalid ->
            listOf(
                sui.market.copy(marketId = MarketId(invalid)),
                sui.market.copy(baseAssetId = AssetId(invalid)),
                sui.market.copy(quoteAssetId = AssetId(invalid)),
            ).forEach { market ->
                assertFailsWith<IllegalArgumentException> {
                    DevelopmentMarketRegistry(listOf(btc, sui.copy(market = market)))
                }
            }
        }
        listOf(
            sui.market.copy(baseAssetId = AssetId("S:UI")),
            sui.market.copy(quoteAssetId = AssetId("US:DT")),
        ).forEach { market ->
            assertFailsWith<IllegalArgumentException> { sui.copy(market = market) }
        }
        assertFailsWith<IllegalArgumentException> {
            sui.copy(feePolicySnapshot = sui.feePolicySnapshot.copy(productType = FeeProductType.PERPETUAL_FUTURES))
        }
    }

    @Test
    fun `전송 한계 안의 64개 유니코드 코드포인트와 스케일 경계를 허용한다`() {
        val identifier = "\uD83D\uDE80".repeat(64)
        listOf(0, 18).forEach { scale ->
            val context =
                sui.copy(market = sui.market.copy(marketId = MarketId(identifier), baseAssetScale = scale))
            assertEquals(context, DevelopmentMarketRegistry(listOf(context)).single())
        }
    }

    private fun environment() =
        mapOf(
            "FUNDING_RUN_ID" to "run1",
            "FUNDING_RUN_ROOT" to "/records/run1",
            "FUNDING_MEMBERS" to members,
            "FUNDING_MEMBER_ID" to "1",
            "FUNDING_HOST" to "node1",
            "FUNDING_BIND_HOST" to "0.0.0.0",
        )
}

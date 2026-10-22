package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.order.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevelopmentMarketSelectionTest {
    @Test
    fun `두 실행 모드에서 선택한 SUI의 정의 정책 수량을 함께 보낸다`() {
        listOf(false, true).forEach { structured ->
            listOf("BUY", "SELL").forEach { side ->
                val invocation = parseDevelopmentOrderInvocation(arguments(structured, side) + arrayOf("--market", "SUI-USDT"), environment)
                val command = invocation.command
                assertEquals(MarketId("SUI-USDT"), command.order.marketId)
                assertEquals(command.order.marketId, command.market.marketId)
                assertEquals("SUI", command.market.baseAssetId.value)
                assertEquals("USDT", command.market.quoteAssetId.value)
                assertEquals(9, command.market.baseAssetScale)
                assertEquals(2L, command.feePolicySnapshot.scheduleVersion)
                assertEquals(25_000_000_000L, command.order.quantity.value)
                assertEquals(20_000_000L, command.order.price.value)
                assertEquals(Side.valueOf(side), command.order.side)
            }
        }
    }

    @Test
    fun `잘못된 JSON은 로컬과 환경 입력 모두에서 기본 BTC로 대체하지 않는다`() {
        listOf(false, true).forEach { structured ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    parseDevelopmentOrderInvocation(arguments(structured), environment + ("FUNDING_MARKETS_JSON" to "[]"))
                }
            assertTrue(error.message.orEmpty().contains("FUNDING_MARKETS_JSON"))
        }
    }

    @Test
    fun `선택 생략은 BTC이고 단일 SUI 등록에서도 명시 선택이 필요하다`() {
        listOf(false, true).forEach { structured ->
            assertEquals(
                "BTC-USDT",
                parseDevelopmentOrderInvocation(arguments(structured), environment)
                    .command.order.marketId.value,
            )
            assertEquals(
                "BTC-USDT",
                parseDevelopmentOrderInvocation(arguments(structured), environment - "FUNDING_MARKETS_JSON")
                    .command.order.marketId.value,
            )
            val suiOnly = environment.getValue("FUNDING_MARKETS_JSON").substringAfter("},\n")
            val suiEnvironment = environment + ("FUNDING_MARKETS_JSON" to "[$suiOnly")
            assertFailsWith<IllegalArgumentException> { parseDevelopmentOrderInvocation(arguments(structured), suiEnvironment) }
            assertEquals(
                "SUI-USDT",
                parseDevelopmentOrderInvocation(
                    arguments(structured) + arrayOf("--market", "SUI-USDT"),
                    suiEnvironment,
                ).command.order.marketId.value,
            )
        }
    }

    @Test
    fun `선택 정책 버전만 덮어쓰고 등록된 SUI 정책은 바꾸지 않는다`() {
        listOf(false, true).forEach { structured ->
            val invocation = parseDevelopmentOrderInvocation(arguments(structured) + arrayOf("3", "--market", "SUI-USDT"), environment)
            assertEquals(3L, invocation.command.feePolicySnapshot.scheduleVersion)
            assertEquals(
                2L,
                invocation.config.markets[MarketId("SUI-USDT")]
                    ?.feePolicySnapshot
                    ?.scheduleVersion,
            )
            assertEquals(9, invocation.command.market.baseAssetScale)
        }
    }

    @Test
    fun `미등록 선택 누락 중복 오위치 옵션은 거절한다`() {
        listOf(false, true).forEach { structured ->
            val args = arguments(structured)
            listOf(
                args + arrayOf("--market", "ETH-USDT"),
                args + "--market",
                args + arrayOf("--market", ""),
                args + arrayOf("--market", "--unknown"),
                args + arrayOf("--market", "SUI-USDT", "2"),
                args + arrayOf("--market", "SUI-USDT", "--market", "BTC-USDT"),
                args + "--unknown",
                arrayOf("--market", "SUI-USDT") + args,
            ).forEach { invalid -> assertFailsWith<IllegalArgumentException> { parseDevelopmentOrderInvocation(invalid, environment) } }
        }
    }

    private fun arguments(
        structured: Boolean,
        side: String = "BUY",
    ): Array<String> =
        (if (structured) arrayOf("--cluster-env") else arrayOf("/tmp/market-selection", "19700")) +
            arrayOf("R1", "U1", "O1", side, "20000000", "25000000000", "L1", "2026-10-22T00:00:00Z")

    private val environment =
        mapOf(
            "FUNDING_RUN_ROOT" to "/tmp/market-selection",
            "FUNDING_HOST" to "127.0.0.1",
            "FUNDING_MEMBERS" to "0,127.0.0.1:19700,127.0.0.1:19701,127.0.0.1:19702,127.0.0.1:19703,127.0.0.1:19704",
            "FUNDING_RUN_ID" to "selection",
            "FUNDING_MARKETS_JSON" to
                """
                [{"marketId":"BTC-USDT","baseAssetId":"BTC","quoteAssetId":"USDT","baseQuantityScale":8,
                  "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":1,"makerPartsPerMillion":0,"takerPartsPerMillion":0}},
                 {"marketId":"SUI-USDT","baseAssetId":"SUI","quoteAssetId":"USDT","baseQuantityScale":9,
                  "feePolicy":{"productType":"SPOT","feeTier":"NORMAL","scheduleVersion":2,"makerPartsPerMillion":0,"takerPartsPerMillion":0}}]
                """.trimIndent(),
        )
}

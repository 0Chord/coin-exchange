package com.exchange.core.api.trading.application

import com.exchange.core.api.trading.application.port.OrderQueryUnavailableException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BalanceQueryUseCaseTest {
    private val stored =
        BalanceQuerySnapshot(OrderQueryLifecycle.READY, ledgerThrough = 3, projectedThrough = 3, available = 700, reserved = 300)

    @Test
    fun `잔고 행을 읽는 동안 확인된 원장이 앞서면 반영 중이다`() {
        var source = currentSource()
        val useCase =
            BalanceQueryUseCase({ source }) { _, _ ->
                source = source.copy(observation = observation(ledgerThrough = 4))
                stored
            }

        val result = useCase.get("buyer", "USDT")
        assertEquals(BalanceQueryCode.QUERY_CATCHING_UP, result.code)
        assertEquals(3L, result.projectedThrough)
        assertEquals(4L, result.observedThrough)
        assertNull(result.available)
        assertNull(result.reserved)
    }

    @Test
    fun `원본을 재검증하는 동안 읽은 DB 잔고를 보내지 않는다`() {
        var source = currentSource()
        val useCase =
            BalanceQueryUseCase({ source }) { _, _ ->
                source = currentSource()
                stored
            }

        val result = useCase.get("buyer", "USDT")
        assertEquals(BalanceQueryCode.QUERY_NOT_READY, result.code)
        assertNull(result.projectedThrough)
        assertNull(result.available)
    }

    @Test
    fun `재생 범위를 덜 읽었으면 두 순번이 같아도 반영 중이다`() {
        val source = BalanceSourceStatus(observation(ledgerThrough = 3).copy(caughtUp = false))
        val useCase = BalanceQueryUseCase({ source }) { _, _ -> stored }

        assertEquals(BalanceQueryCode.QUERY_CATCHING_UP, useCase.get("buyer", "USDT").code)
    }

    @Test
    fun `관측 만료와 DB 연결 실패를 503으로 판단한다`() {
        val expired = BalanceSourceStatus(observation(ledgerThrough = 3).copy(expiresAtNs = System.nanoTime()))
        val expiredUseCase = BalanceQueryUseCase({ expired }) { _, _ -> error("만료된 원본으로 DB를 읽었다") }
        val unavailableUseCase = BalanceQueryUseCase(::currentSource) { _, _ -> throw OrderQueryUnavailableException("DB 읽기 실패") }

        assertEquals(BalanceQueryCode.QUERY_UNAVAILABLE, expiredUseCase.get("buyer", "USDT").code)
        assertEquals(BalanceQueryCode.QUERY_UNAVAILABLE, unavailableUseCase.get("buyer", "USDT").code)
    }

    @Test
    fun `초기 지급에 허용된 사용자 식별자를 잔고에서도 읽는다`() {
        val source = currentSource()
        val fundedUser = "desk\u0085A"
        val useCase =
            BalanceQueryUseCase({ source }) { userId, assetId ->
                assertEquals(fundedUser, userId)
                assertEquals("USDT", assetId)
                stored
            }

        assertEquals(BalanceQueryCode.BALANCE_FOUND, useCase.get(fundedUser, "USDT").code)
    }

    private fun currentSource() = BalanceSourceStatus(observation(ledgerThrough = 3))

    private fun observation(ledgerThrough: Long) =
        BalanceArchiveObservation(
            ledgerThrough = ledgerThrough,
            caughtUp = true,
            expiresAtNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10),
        )
}

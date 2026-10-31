package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.ErrorCode
import io.aeron.cluster.client.AeronCluster
import io.aeron.exceptions.RegistrationException
import io.aeron.exceptions.TimeoutException
import io.aeron.security.AuthenticationException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BoundedInitialClusterConnectTest {
    @Test fun `stale initial leader attempt closes then discovers a healthy leader within the original budget`() {
        val clock = AtomicLong()
        val first = mock(AeronCluster.AsyncConnect::class.java)
        val second = mock(AeronCluster.AsyncConnect::class.java)
        val connection = mock(AeronCluster::class.java)
        `when`(first.poll()).thenAnswer {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2))
            null
        }
        `when`(second.poll()).thenReturn(connection)
        val contexts = mutableListOf<AeronCluster.Context>()
        var opened = 0
        val timeout = TimeUnit.SECONDS.toNanos(5)
        val result =
            connectInitialBoundedCluster(
                timeout,
                contextFactory = { AeronCluster.Context().messageTimeoutNs(timeout).also(contexts::add) },
                nanoTime = clock::get,
                open = { if (opened++ == 0) first else second },
                idle = {},
            )
        assertSame(connection, result)
        assertEquals(2, opened)
        assertEquals(listOf(timeout, timeout), contexts.map { it.messageTimeoutNs() })
        verify(first).close()
        verify(second).close()
        verify(connection, never()).close()
    }

    @Test fun `repeated discovery timeouts stop at one fixed startup deadline and release every attempt`() {
        val timeout = TimeUnit.SECONDS.toNanos(5)
        val clock = AtomicLong()
        val attempts = mutableListOf<AeronCluster.AsyncConnect>()
        assertFailsWith<TimeoutException> {
            connectInitialBoundedCluster(
                timeout,
                contextFactory = { AeronCluster.Context().messageTimeoutNs(timeout) },
                nanoTime = clock::get,
                open = {
                    mock(AeronCluster.AsyncConnect::class.java).also { attempt ->
                        attempts.add(attempt)
                        `when`(attempt.poll()).thenAnswer {
                            clock.set(minOf(timeout, clock.get() + TimeUnit.SECONDS.toNanos(2)))
                            null
                        }
                    }
                },
                idle = {},
            )
        }
        assertEquals(timeout, clock.get())
        assertEquals(3, attempts.size)
        attempts.forEach { verify(it).close() }
    }

    @Test fun `authentication registration unknown failures and Error propagate without fresh attempts`() {
        val failures =
            listOf(
                AuthenticationException("denied"),
                RegistrationException(1, 1, ErrorCode.GENERIC_ERROR, "registration failed"),
                IllegalStateException("internal failure"),
                AssertionError("fatal error"),
            )
        for (failure in failures) {
            val attempt = mock(AeronCluster.AsyncConnect::class.java)
            `when`(attempt.poll()).thenAnswer { throw failure }
            var opened = 0
            val thrown =
                assertFailsWith<Throwable> {
                    connectInitialBoundedCluster(
                        TimeUnit.SECONDS.toNanos(5),
                        contextFactory = { AeronCluster.Context() },
                        nanoTime = { 0 },
                        open = {
                            opened++
                            attempt
                        },
                        idle = {},
                    )
                }
            assertSame(failure, thrown)
            assertEquals(1, opened)
            verify(attempt).close()
        }
    }

    @Test fun `failure before AsyncConnect exists closes its context without retrying`() {
        val context = mock(AeronCluster.Context::class.java)
        val failure = AuthenticationException("context failure")
        val thrown =
            assertFailsWith<AuthenticationException> {
                connectInitialBoundedCluster(
                    TimeUnit.SECONDS.toNanos(5),
                    contextFactory = { context },
                    nanoTime = { 0 },
                    open = { throw failure },
                    idle = {},
                )
            }
        assertSame(failure, thrown)
        verify(context).close()
    }
}

package com.exchange.core.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
open class CommandGenerationBenchmark {
    private val batchSequence = AtomicLong()

    @Benchmark
    open fun generateSingleMarketReplayCommandStream(blackhole: Blackhole) {
        blackhole.consume(
            BenchmarkCommands.singleMarketReplayCommands(nextPrefix("single-replay")),
        )
    }

    @Benchmark
    open fun generateMultiMarketReplayCommandStream(blackhole: Blackhole) {
        blackhole.consume(
            BenchmarkCommands.multiMarketReplayCommands(nextPrefix("multi-replay")),
        )
    }

    @Benchmark
    open fun generateBalancedSingleMarketCommandStream(blackhole: Blackhole) {
        blackhole.consume(
            BenchmarkCommands.balancedSingleMarketReplayCommands(nextPrefix("single-balanced")),
        )
    }

    @Benchmark
    open fun generateBalancedMultiMarketCommandStream(blackhole: Blackhole) {
        blackhole.consume(
            BenchmarkCommands.balancedMultiMarketReplayCommands(nextPrefix("multi-balanced")),
        )
    }

    private fun nextPrefix(label: String): String =
        "generation-$label-${batchSequence.incrementAndGet()}"
}

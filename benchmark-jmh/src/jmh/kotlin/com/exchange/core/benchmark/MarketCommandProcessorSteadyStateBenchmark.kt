package com.exchange.core.benchmark

import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
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
open class MarketCommandProcessorSteadyStateBenchmark {
    private lateinit var processor: InMemoryMarketCommandProcessor
    private val batchSequence = AtomicLong()

    @Setup(Level.Iteration)
    fun setUp() {
        processor = InMemoryMarketCommandProcessor()
        batchSequence.set(0)

        submitAll(BenchmarkCommands.balancedSingleMarketReplayCommands("warmup-single"))
        submitAll(BenchmarkCommands.balancedMultiMarketReplayCommands("warmup-multi"))
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        processor.close()
    }

    @Benchmark
    open fun submitSingleMarketCommandStream(blackhole: Blackhole) {
        val commands = BenchmarkCommands.balancedSingleMarketReplayCommands(nextPrefix("single"))

        blackhole.consume(submitAll(commands))
    }

    @Benchmark
    open fun submitMultiMarketCommandStream(blackhole: Blackhole) {
        val commands = BenchmarkCommands.balancedMultiMarketReplayCommands(nextPrefix("multi"))

        blackhole.consume(submitAll(commands))
    }

    private fun nextPrefix(label: String): String =
        "steady-$label-${batchSequence.incrementAndGet()}"

    private fun submitAll(commands: List<MatchingCommand>): Int {
        val futures = commands.map { command ->
            processor.submit(command)
        }

        return futures.sumOf { future ->
            future.get(10, TimeUnit.SECONDS).size
        }
    }
}

package com.exchange.core.benchmark

import com.exchange.core.matching.InMemoryMarketCommandProcessor
import com.exchange.core.matching.MatchingCommand
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
open class MarketCommandProcessorReplayBenchmark {
    private lateinit var singleMarketCommands: List<MatchingCommand>
    private lateinit var multiMarketCommands: List<MatchingCommand>

    @Setup(Level.Trial)
    fun setUp() {
        singleMarketCommands = BenchmarkCommands.singleMarketReplayCommands()
        multiMarketCommands = BenchmarkCommands.multiMarketReplayCommands()
    }

    @Benchmark
    open fun replaySingleMarketCommandStreamThroughProcessor(blackhole: Blackhole) {
        blackhole.consume(replayThroughProcessor(singleMarketCommands))
    }

    @Benchmark
    open fun replayMultiMarketCommandStreamThroughProcessor(blackhole: Blackhole) {
        blackhole.consume(replayThroughProcessor(multiMarketCommands))
    }

    private fun replayThroughProcessor(commands: List<MatchingCommand>): Int {
        val processor = InMemoryMarketCommandProcessor()

        return try {
            val futures = commands.map { command ->
                processor.submit(command)
            }

            futures.sumOf { future ->
                future.get(10, TimeUnit.SECONDS).size
            }
        } finally {
            processor.close()
        }
    }
}
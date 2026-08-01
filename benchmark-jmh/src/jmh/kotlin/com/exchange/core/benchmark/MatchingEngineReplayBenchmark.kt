package com.exchange.core.benchmark

import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEngine
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit


@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
open class MatchingEngineReplayBenchmark {
    private lateinit var singleMarketCommands: List<MatchingCommand>
    private lateinit var multiMarketCommands: List<MatchingCommand>

    @Setup(Level.Trial)
    fun setUp() {
        singleMarketCommands = BenchmarkCommands.singleMarketReplayCommands()
        multiMarketCommands = BenchmarkCommands.multiMarketReplayCommands()
    }

    @Benchmark
    open fun replaySingleMarketCommandStream(blackhole: Blackhole) {
        blackhole.consume(replay(singleMarketCommands))
    }

    @Benchmark
    open fun replayMultiMarketCommandStream(blackhole: Blackhole) {
        blackhole.consume(replay(multiMarketCommands))
    }

    private fun replay(commands: List<MatchingCommand>): Int {
        val engine = MatchingEngine()
        var eventCount = 0

        commands.forEach { command ->
            eventCount += engine.process(command).size
        }

        return eventCount
    }
}
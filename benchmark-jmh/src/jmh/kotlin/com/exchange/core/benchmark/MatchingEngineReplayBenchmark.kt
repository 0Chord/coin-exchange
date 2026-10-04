package com.exchange.core.benchmark

import com.exchange.core.matching.MatchingCommand
import com.exchange.core.matching.MatchingEngine
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
import org.openjdk.jmh.annotations.Warmup
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

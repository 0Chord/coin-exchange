package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCodec
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingCommand
import com.exchange.core.api.trading.infrastructure.codec.BoundedTradingReply
import com.exchange.core.api.trading.infrastructure.codec.DevelopmentBalanceCommandPayload
import com.exchange.core.api.trading.infrastructure.history.BoundedTradingStateCodec
import com.exchange.core.common.Amount
import com.exchange.core.common.AssetId
import com.exchange.core.common.UserId
import com.exchange.core.ledger.OpeningBalance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 같은 이미지와 JDK 안에서 manifest를 만들고, 준비 단계에만 직접 wire3 제어를 사용한다. */
fun main(args: Array<String>) {
    require(args.size == 1 && args[0] in setOf("manifest", "prepare")) { "usage: manifest | prepare" }
    val control = boundedHttpControlDirectory()
    Files.createDirectories(control)
    when (args.single()) {
        "manifest" -> {
            val manifest = haBoundedProbeManifest()
            val bytes = BoundedTradingStateCodec().encodeManifest(manifest)
            val target = control.resolve("manifest.bin")
            check(!Files.exists(target)) { "manifest must be generated once for a fresh run" }
            writeBoundedHttpArtifact(target, bytes, replaceExisting = false)
            val fields = boundedHttpManifestFields(bytes, manifest)
            writeBoundedHttpJson(control.resolve("manifest.json"), fields)
            boundedHttpEvent(fields + ("kind" to "MANIFEST"))
        }

        "prepare" -> {
            prepareBoundedHttpCluster(control)
        }
    }
}

private fun prepareBoundedHttpCluster(control: Path) {
    val config = developmentClusterSettings()
    val manifest = requireNotNull(config.boundedManifest) { "FUNDING_BOUNDED_MANIFEST is required" }
    check(config.trustedBoundedControl) { "the test preparation control must be explicitly enabled" }
    val manifestBytes = Files.readAllBytes(Path.of(requireNotNull(System.getenv("FUNDING_BOUNDED_MANIFEST"))))
    check(BoundedTradingStateCodec().decodeManifest(manifestBytes) == manifest) { "manifest changed while loading preparation settings" }
    val codec = BoundedTradingCodec()
    var correlation = 1L
    val applied = ArrayList<Map<String, Any?>>(3)
    Files.createDirectories(config.runRoot)
    HaProbeConnection(config).use { connection ->
        fun request(
            command: BoundedTradingCommand,
            expectedCode: String,
        ): BoundedTradingReply {
            val expectedCorrelation = correlation++
            var response: BoundedTradingReply? = null
            connection.request(codec.encode(expectedCorrelation, command)) { bytes ->
                val reply = codec.decodeResponse(bytes)
                if (reply.correlationId != expectedCorrelation) {
                    false
                } else {
                    check(reply.code == expectedCode) { "preparation expected $expectedCode, got ${reply.code}" }
                    check(reply.appliedLogPosition > 0) { "preparation omitted its applied log position" }
                    check(reply.lineage == manifest.lineage) { "preparation response lineage differs from manifest" }
                    response = reply
                    true
                }
            }
            return checkNotNull(response)
        }

        val initialized = request(BoundedTradingCommand.Initialize(manifestBytes), "INITIALIZED")
        applied.add(mapOf("kind" to "INITIALIZED", "position" to initialized.appliedLogPosition))
        val time = Instant.parse("2026-10-27T00:00:00Z")
        for ((user, asset, amount) in listOf(Triple("BUYER", "USDT", 11_020L), Triple("SELLER", "BTC", 100_000_000L))) {
            val command =
                DevelopmentBalanceCommandPayload(
                    OpeningBalance("http-bounded-$user-$asset", UserId(user), AssetId(asset), Amount(amount)),
                    "http-bounded-opening-$user-$asset",
                    time,
                )
            val funded = request(BoundedTradingCommand.Funding(command), "PREPARED")
            val result = checkNotNull(funded.funding) { "preparation omitted its actual funding result" }
            check(!result.alreadyPrepared && result.opening == command.opening) { "funding result differs from input" }
            applied.add(
                mapOf(
                    "kind" to "FUNDED",
                    "userId" to user,
                    "assetId" to asset,
                    "amount" to amount,
                    "position" to funded.appliedLogPosition,
                ),
            )
        }
    }
    // 사용자 epoch, 주문, ACK는 준비 도구가 전송하지 않는다. 이후 HTTP 시나리오가 직접 수행한다.
    val fields =
        boundedHttpManifestFields(manifestBytes, manifest) +
            mapOf("kind" to "PREPARED", "runId" to config.runId, "applied" to applied)
    writeBoundedHttpJson(control.resolve("prepared.json"), fields)
    boundedHttpEvent(fields)
}

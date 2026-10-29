package com.exchange.core.api.trading.infrastructure.aeron

import com.exchange.core.common.MarketId
import com.exchange.core.matching.MatchingCommandResult
import com.exchange.core.matching.OrderEnteredBook
import com.exchange.core.matching.RestingOrderSnapshot
import com.exchange.core.matching.SubmitOrderCommand
import com.exchange.core.matching.TradeExecuted
import com.exchange.core.trading.TradingState
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationFeature
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.Collections

/** Captured only on the service thread. The writer never reads a live book or service. */
internal class HttpPerformanceCapturedBook(
    val marketId: MarketId,
    val lastEngineSequence: Long,
    orders: List<RestingOrderSnapshot>,
) {
    val orders: List<RestingOrderSnapshot> = Collections.unmodifiableList(ArrayList(orders))
}

/** Domain records are already immutable; only their snapshot reference lists are retained here. */
internal class HttpPerformanceCheckpointCapture(
    val runId: String,
    val memberId: Int,
    val label: String,
    appliedMetadata: Map<String, Any?>?,
    val appliedState: TradingState.Snapshot?,
    val tradingSnapshot: TradingState.Snapshot,
    val tradingBlocked: Boolean,
    books: List<HttpPerformanceCapturedBook>,
    val emittedMarkerCount: Int,
    callbackTimings: Map<String, Any?>,
    val collectorFailure: String?,
    val roleChangeCount: Long,
) {
    val appliedMetadata: Map<String, Any?>? = appliedMetadata?.let(::freezeCheckpointMap)
    val books: List<HttpPerformanceCapturedBook> = Collections.unmodifiableList(ArrayList(books))
    val callbackTimings: Map<String, Any?> = freezeCheckpointMap(callbackTimings)
}

/** Runs inside the existing explicit checkpoint callback, never on an order callback. */
internal fun captureHttpPerformanceCheckpoint(
    runId: String,
    memberId: Int,
    config: DevelopmentFundingSettings,
    service: DevelopmentFundingAdapter,
    label: String,
    observation: FundingObservation?,
    snapshot: TradingState.Snapshot,
    emittedMarkerCount: Int,
    callbackTimings: Map<String, Any?>,
    collectorFailure: String?,
    roleChangeCount: Long,
): HttpPerformanceCheckpointCapture {
    val blocked = isVerificationTradingBlocked(service)
    return HttpPerformanceCheckpointCapture(
        runId,
        memberId,
        label,
        // A null snapshot avoids calling the production full-state projector.
        observation?.let { fundingAppliedFields(config, it.copy(snapshot = null)) - "state" },
        observation?.snapshot,
        snapshot,
        blocked,
        if (blocked) emptyList() else captureVerificationBooks(service, snapshot),
        emittedMarkerCount,
        callbackTimings,
        collectorFailure,
        roleChangeCount,
    )
}

/** Preserve the old metadata schema while detaching mutable timing rows from the collector. */
private fun freezeCheckpointValue(value: Any?): Any? =
    when (value) {
        is Map<*, *> -> {
            val copy = linkedMapOf<String, Any?>()
            value.forEach { (key, item) -> copy[requireNotNull(key as? String)] = freezeCheckpointValue(item) }
            Collections.unmodifiableMap(copy)
        }

        is List<*> -> {
            Collections.unmodifiableList(value.map(::freezeCheckpointValue))
        }

        is HttpPerformanceCallbackTiming -> {
            freezeCheckpointMap(
                mapOf(
                    "kind" to value.kind,
                    "userId" to value.userId,
                    "requestId" to value.requestId,
                    "admissionSequence" to value.admissionSequence,
                    "position" to value.position,
                    "productCallbackNs" to value.productCallbackNs,
                    "observerNs" to value.observerNs,
                    "admissionToExecutionGapNs" to value.admissionToExecutionGapNs,
                    "phases" to value.phases,
                    "phaseDurationsNs" to value.phaseDurationsNs.toList(),
                    "executionOfferAttempts" to value.executionOfferAttempts,
                    "executionBackPressured" to value.executionBackPressured,
                    "executionAdminActions" to value.executionAdminActions,
                    "executionOfferPosition" to value.executionOfferPosition,
                    "replyAttempts" to value.replyAttempts,
                    "replyBackPressured" to value.replyBackPressured,
                    "replyAdminActions" to value.replyAdminActions,
                    "replyOfferPosition" to value.replyOfferPosition,
                    "replyStatus" to value.replyStatus,
                    "callbackStartedNs" to value.callbackStartedNs,
                    "callbackCompletedNs" to value.callbackCompletedNs,
                ),
            )
        }

        else -> {
            value
        }
    }

@Suppress("UNCHECKED_CAST")
private fun freezeCheckpointMap(value: Map<String, Any?>): Map<String, Any?> = freezeCheckpointValue(value) as Map<String, Any?>

/**
 * A test-only bridge to the existing production presentation schema. No production visibility is changed.
 * A detached DTO slice contains one original record; no TradingState is created, cleared or modified.
 * Both reflection targets are cached, and any source-schema change fails the parity tests explicitly.
 */
internal object HttpPerformanceAdmissionRowProjection {
    private val snapshotType = TradingState.Snapshot::class.java
    private val constructor =
        snapshotType
            .getDeclaredConstructor(
                List::class.java,
                List::class.java,
                List::class.java,
                List::class.java,
                List::class.java,
                List::class.java,
                java.lang.Long.TYPE,
                List::class.java,
                List::class.java,
            ).apply { isAccessible = true }
    private val projector =
        Class
            .forName("com.exchange.core.api.trading.infrastructure.aeron.DevelopmentFundingEventsKt")
            .getDeclaredMethod("admissionStateFields", snapshotType)
            .apply { isAccessible = true }
    private val constructorIndexes = mapOf("balances" to 0, "pendingOrders" to 2, "transactions" to 4, "requests" to 7, "preparations" to 8)

    @Suppress("UNCHECKED_CAST")
    fun row(
        section: String,
        value: Any,
    ): Map<String, Any?> {
        val index = requireNotNull(constructorIndexes[section]) { "unsupported admission array: $section" }
        val arguments = Array<Any>(9) { if (it == 6) 0L else emptyList<Any>() }
        arguments[index] = listOf(value)
        return try {
            val slice = constructor.newInstance(*arguments)
            val fields = projector.invoke(null, slice) as Map<String, Any?>
            (fields.getValue(section) as List<Map<String, Any?>>).single()
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}

/** Same JSON shape as the old checkpoint; only one admission/completion/result row Map is built at a time. */
internal object HttpPerformanceCheckpointStreaming {
    private val mapper = jacksonObjectMapper()
    private val values = mapper.writer().without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)

    fun write(
        output: OutputStream,
        capture: HttpPerformanceCheckpointCapture,
        markerSummary: Map<String, Any?>,
        role: String,
        runtimeCounters: Any? = null,
    ) {
        mapper.createGenerator(output).use { json ->
            json.writeStartObject()
            field(json, "kind", "PERFORMANCE_CHECKPOINT")
            field(json, "runId", capture.runId)
            field(json, "memberId", capture.memberId)
            field(json, "label", capture.label)
            json.writeName("applied")
            if (capture.appliedMetadata == null) {
                json.writeNull()
            } else {
                json.writeStartObject()
                capture.appliedMetadata.forEach { (name, value) -> field(json, name, value) }
                json.writeName("state")
                capture.appliedState?.let { writeAdmission(json, it) } ?: json.writeNull()
                json.writeEndObject()
            }
            json.writeName("trading")
            writeTrading(json, capture)
            field(json, "emittedMarkerCount", capture.emittedMarkerCount)
            field(json, "callbackTimings", capture.callbackTimings)
            field(json, "collectorFailure", capture.collectorFailure)
            field(json, "roleChangeCount", capture.roleChangeCount)
            field(json, "markerSummary", markerSummary)
            field(json, "role", role)
            field(json, "runtimeCounters", runtimeCounters)
            json.writeEndObject()
        }
    }

    private fun field(
        json: JsonGenerator,
        name: String,
        value: Any?,
    ) {
        json.writeName(name)
        values.writeValue(json, value)
    }

    private fun <T> rows(
        json: JsonGenerator,
        name: String,
        source: List<T>,
        project: (T) -> Map<String, Any?>,
    ) {
        json.writeName(name)
        json.writeStartArray()
        for (value in source) values.writeValue(json, project(value))
        json.writeEndArray()
    }

    private fun writeAdmission(
        json: JsonGenerator,
        snapshot: TradingState.Snapshot,
    ) {
        json.writeStartObject()
        rows(json, "balances", snapshot.balances) { HttpPerformanceAdmissionRowProjection.row("balances", it) }
        field(json, "preparationCount", snapshot.preparations.size)
        field(json, "pendingOrderCount", snapshot.pendingOrders.size)
        field(json, "requestRecordCount", snapshot.requests.size)
        field(json, "lastSequence", snapshot.lastAdmissionSequence)
        rows(json, "preparations", snapshot.preparations) { HttpPerformanceAdmissionRowProjection.row("preparations", it) }
        rows(json, "pendingOrders", snapshot.pendingOrders) { HttpPerformanceAdmissionRowProjection.row("pendingOrders", it) }
        rows(json, "requests", snapshot.requests) { HttpPerformanceAdmissionRowProjection.row("requests", it) }
        rows(json, "transactions", snapshot.ledgerTransactions) { HttpPerformanceAdmissionRowProjection.row("transactions", it) }
        json.writeEndObject()
    }

    private fun writeTrading(
        json: JsonGenerator,
        capture: HttpPerformanceCheckpointCapture,
    ) {
        val snapshot = capture.tradingSnapshot
        json.writeStartObject()
        if (capture.tradingBlocked) {
            field(json, "blocked", true)
        } else {
            rows(json, "currentReservations", snapshot.reservations, ::verificationReservation)
            json.writeName("books")
            json.writeStartArray()
            for (book in capture.books) {
                json.writeStartObject()
                field(json, "marketId", book.marketId.value)
                field(json, "lastEngineSequence", book.lastEngineSequence)
                // A whole book is deliberately never converted to Map: its orders may span the cohort.
                rows(json, "orders", book.orders, ::verificationRestingOrderFields)
                json.writeEndObject()
            }
            json.writeEndArray()
            rows(json, "completions", snapshot.completions, ::verificationCompletionFields)
        }
        field(json, "matchingResultCount", snapshot.matchingResults.size)
        rows(json, "matchingResults", snapshot.matchingResults, ::httpPerformanceMatchingResultFields)
        json.writeEndObject()
    }
}

/** Keep this projection distinct from completion events, whose schema contains more participant fields. */
private fun httpPerformanceMatchingResultFields(result: MatchingCommandResult): Map<String, Any?> {
    val command = result.command as SubmitOrderCommand
    return mapOf(
        "commandId" to result.commandId,
        "marketId" to result.command.marketId.value,
        "orderId" to command.orderId.value,
        "userId" to command.userId.value,
        "expectedEventCount" to result.expectedEventCount,
        "firstEngineSequence" to result.firstEngineSequence,
        "lastEngineSequence" to result.lastEngineSequence,
        "events" to
            result.events.map { event ->
                mapOf("marketId" to event.marketId.value, "engineSequence" to event.engineSequence) +
                    when (event) {
                        is TradeExecuted -> {
                            mapOf(
                                "kind" to "TRADE_EXECUTED",
                                "makerOrderId" to event.makerOrderId.value,
                                "takerOrderId" to event.takerOrderId.value,
                                "price" to event.price.value,
                                "quantity" to event.quantity.value,
                            )
                        }

                        is OrderEnteredBook -> {
                            mapOf(
                                "kind" to "ORDER_ENTERED_BOOK",
                                "orderId" to event.orderId.value,
                                "remainingQuantity" to event.remainingQuantity.value,
                            )
                        }

                        else -> {
                            error("unexpected checkpoint matching event: $event")
                        }
                    }
            },
    )
}

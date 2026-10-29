package com.exchange.core.api.trading.infrastructure.aeron

import io.aeron.AeronCounters
import io.aeron.CncFileDescriptor
import org.agrona.IoUtil
import org.agrona.concurrent.status.CountersReader
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class HttpPerformanceRuntimeCounterDefinition(
    val counterId: Int,
    val registrationId: Long,
    val typeId: Int,
    val label: String,
    val ownerId: Long,
    val referenceId: Long,
)

internal data class HttpPerformanceRuntimeCounterValue(
    val definitionIndex: Int,
    val value: Long,
)

internal data class HttpPerformanceRuntimeCounterFrame(
    val elapsedNs: Long,
    val readDurationNs: Long,
    val values: List<HttpPerformanceRuntimeCounterValue>,
)

/** 각 frame은 순차 volatile 조회이며, 원자적 cluster snapshot이나 JVM 간 지연 측정이 아니다. */
internal data class HttpPerformanceRuntimeCounterSnapshot(
    val intervalMs: Long,
    val capacity: Int,
    val definitions: List<HttpPerformanceRuntimeCounterDefinition>,
    val frames: List<HttpPerformanceRuntimeCounterFrame>,
    val overflowCount: Long,
    val definitionOverflowCount: Long,
    val skippedCounterReads: Long,
    val errors: List<String>,
    /** 이 노드 JVM의 nanoTime 원점이다. 다른 JVM 원점과 빼지 않는다. */
    val originNs: Long? = null,
)

/** 테스트 전용 CnC 관찰기다. 새 Aeron client/Driver를 등록하거나 업무 agent를 호출하지 않는다. */
internal class HttpPerformanceRuntimeCounters internal constructor(
    private val buffer: HttpPerformanceRuntimeCounterBuffer,
    private val release: () -> Unit,
) : AutoCloseable {
    private val stop = CountDownLatch(1)
    private val started = CountDownLatch(1)
    private val closed = AtomicBoolean()
    private val thread =
        Thread({
            try {
                buffer.capture(forceMetadataRefresh = true)
                started.countDown()
                while (!stop.await(INTERVAL_MS, TimeUnit.MILLISECONDS)) buffer.capture()
                buffer.capture(forceMetadataRefresh = true)
            } catch (error: Throwable) {
                buffer.recordFailure(error)
            } finally {
                started.countDown()
            }
        }, "http-performance-runtime-counters").apply { isDaemon = true }

    /** 체크포인트에서 현재 frame을 추가해 실제 콜백 종료 뒤의 카운터 경계도 보존한다. */
    @Synchronized
    fun snapshot(): HttpPerformanceRuntimeCounterSnapshot {
        if (!closed.get()) {
            try {
                buffer.capture(forceMetadataRefresh = true)
            } catch (error: Exception) {
                // 체크포인트 관측 실패는 보고서에 남기고 거래 노드의 실행 흐름으로 전파하지 않는다.
                buffer.recordFailure(error)
            }
        }
        return buffer.snapshot()
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop.countDown()
        thread.join(5_000)
        check(!thread.isAlive) { "runtime counter 관찰 스레드가 종료되지 않았습니다" }
        release()
    }

    companion object {
        private const val INTERVAL_MS = 250L

        fun start(driverDirectory: String): HttpPerformanceRuntimeCounters {
            val mapping =
                FileChannel.open(Path.of(driverDirectory).resolve(CncFileDescriptor.CNC_FILE), StandardOpenOption.READ).use { channel ->
                    require(channel.size() in CncFileDescriptor.META_DATA_LENGTH.toLong()..Int.MAX_VALUE.toLong())
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                }
            val sampler =
                try {
                    val metadata = CncFileDescriptor.createMetaDataBuffer(mapping)
                    val version = metadata.getIntVolatile(CncFileDescriptor.CNC_VERSION_FIELD_OFFSET)
                    check(version != 0) { "Aeron CnC가 아직 준비되지 않았습니다" }
                    CncFileDescriptor.checkVersion(version)
                    check(CncFileDescriptor.isCncFileLengthSufficient(metadata, mapping.capacity())) { "Aeron CnC 길이가 잘못됐습니다" }
                    val reader =
                        CountersReader(
                            CncFileDescriptor.createCountersMetaDataBuffer(mapping, metadata),
                            CncFileDescriptor.createCountersValuesBuffer(mapping, metadata),
                        )
                    HttpPerformanceRuntimeCounters(HttpPerformanceRuntimeCounterBuffer(reader), { IoUtil.unmap(mapping) })
                } catch (error: Throwable) {
                    IoUtil.unmap(mapping)
                    throw error
                }
            try {
                sampler.thread.start()
                check(sampler.started.await(5_000, TimeUnit.MILLISECONDS)) { "runtime counter 관찰기가 시작되지 않았습니다" }
                return sampler
            } catch (error: Throwable) {
                sampler.close()
                throw error
            }
        }
    }
}

/** 메타데이터 문자열은 1초마다 갱신하고 frame에는 정의 인덱스와 숫자만 둔다. */
internal class HttpPerformanceRuntimeCounterBuffer(
    private val reader: CountersReader,
    private val capacity: Int = 4096,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val originNs = nanoTime()
    private var lastMetadataRefreshNs: Long? = null
    private val definitions = linkedMapOf<HttpPerformanceRuntimeCounterDefinition, Int>()
    private var active = emptyList<Pair<HttpPerformanceRuntimeCounterDefinition, Int>>()
    private val frames = ArrayList<HttpPerformanceRuntimeCounterFrame>()
    private var overflowCount = 0L
    private var definitionOverflowCount = 0L
    private var skippedCounterReads = 0L
    private val errors = mutableListOf<String>()

    init {
        require(capacity in 1..4096)
    }

    @Synchronized
    fun capture(forceMetadataRefresh: Boolean = false) {
        if (frames.size == capacity) {
            overflowCount++
            return
        }
        val startNs = nanoTime()
        if (forceMetadataRefresh || lastMetadataRefreshNs == null || startNs - requireNotNull(lastMetadataRefreshNs) >= 1_000_000_000L) {
            refreshDefinitions()
            lastMetadataRefreshNs = startNs
        }
        val values =
            buildList {
                active.forEach { (definition, index) ->
                    if (isCurrent(definition)) {
                        val value = reader.getCounterValue(definition.counterId)
                        if (isCurrent(definition)) add(HttpPerformanceRuntimeCounterValue(index, value)) else skippedCounterReads++
                    } else {
                        skippedCounterReads++
                    }
                }
            }
        frames.add(
            HttpPerformanceRuntimeCounterFrame(startNs - originNs, nanoTime() - startNs, java.util.List.copyOf(values)),
        )
    }

    @Synchronized
    fun recordFailure(error: Throwable) {
        if (errors.size < 16) errors.add("${error.javaClass.simpleName}: ${error.message}".take(1024))
    }

    @Synchronized
    fun snapshot(): HttpPerformanceRuntimeCounterSnapshot =
        HttpPerformanceRuntimeCounterSnapshot(
            intervalMs = 250,
            capacity = capacity,
            definitions = java.util.List.copyOf(definitions.keys),
            frames = java.util.List.copyOf(frames),
            overflowCount = overflowCount,
            definitionOverflowCount = definitionOverflowCount,
            skippedCounterReads = skippedCounterReads,
            errors = java.util.List.copyOf(errors),
            originNs = originNs,
        )

    private fun refreshDefinitions() {
        active =
            buildList {
                reader.forEach(
                    CountersReader.MetaData { id, _, _, _ ->
                        // forEach가 전달한 type/label은 callback 진입 전에 읽힌 값이다.
                        // 같은 ID가 그 사이 재사용될 수 있으므로 현재 등록 ID부터 다시 읽는다.
                        val definition = readDefinition(id)
                        if (definition != null && interesting(definition.typeId)) {
                            val index = definitions[definition]
                            if (index != null) {
                                add(definition to index)
                            } else if (definitions.size < 1024) {
                                val next = definitions.size
                                definitions[definition] = next
                                add(definition to next)
                            } else {
                                definitionOverflowCount++
                            }
                        }
                    },
                )
            }
    }

    private fun readDefinition(id: Int): HttpPerformanceRuntimeCounterDefinition? {
        if (reader.getCounterState(id) != CountersReader.RECORD_ALLOCATED) {
            skippedCounterReads++
            return null
        }
        val registrationId = reader.getCounterRegistrationId(id)
        val definition =
            HttpPerformanceRuntimeCounterDefinition(
                id,
                registrationId,
                reader.getCounterTypeId(id),
                reader.getCounterLabel(id),
                reader.getCounterOwnerId(id),
                reader.getCounterReferenceId(id),
            )
        // 메타데이터를 모두 읽는 동안의 재사용과 같은 등록의 label 갱신도 확인한다.
        if (reader.getCounterTypeId(id) != definition.typeId ||
            reader.getCounterLabel(id) != definition.label ||
            reader.getCounterOwnerId(id) != definition.ownerId ||
            reader.getCounterReferenceId(id) != definition.referenceId ||
            reader.getCounterRegistrationId(id) != registrationId ||
            reader.getCounterState(id) != CountersReader.RECORD_ALLOCATED
        ) {
            skippedCounterReads++
            return null
        }
        return definition
    }

    private fun isCurrent(definition: HttpPerformanceRuntimeCounterDefinition): Boolean =
        reader.getCounterState(definition.counterId) == CountersReader.RECORD_ALLOCATED &&
            reader.getCounterRegistrationId(definition.counterId) == definition.registrationId &&
            reader.getCounterTypeId(definition.counterId) == definition.typeId

    private fun interesting(typeId: Int): Boolean =
        typeId == AeronCounters.DRIVER_SYSTEM_COUNTER_TYPE_ID ||
            typeId == AeronCounters.DRIVER_PUBLISHER_LIMIT_TYPE_ID ||
            typeId == AeronCounters.DRIVER_SENDER_POSITION_TYPE_ID ||
            typeId == AeronCounters.DRIVER_RECEIVER_HWM_TYPE_ID ||
            typeId == AeronCounters.DRIVER_SUBSCRIBER_POSITION_TYPE_ID ||
            typeId == AeronCounters.DRIVER_RECEIVER_POS_TYPE_ID ||
            typeId == AeronCounters.DRIVER_SENDER_LIMIT_TYPE_ID ||
            typeId == AeronCounters.DRIVER_PUBLISHER_POS_TYPE_ID ||
            typeId == AeronCounters.DRIVER_SENDER_BPE_TYPE_ID ||
            typeId == AeronCounters.DRIVER_SENDER_NAKS_RECEIVED_TYPE_ID ||
            typeId == AeronCounters.DRIVER_RECEIVER_NAKS_SENT_TYPE_ID ||
            typeId in AeronCounters.ARCHIVE_RECORDING_POSITION_TYPE_ID..AeronCounters.ARCHIVE_CONTROL_SESSION_TYPE_ID ||
            typeId in AeronCounters.CLUSTER_CONSENSUS_MODULE_STATE_TYPE_ID..AeronCounters.CLUSTER_SESSION_TYPE_ID
}

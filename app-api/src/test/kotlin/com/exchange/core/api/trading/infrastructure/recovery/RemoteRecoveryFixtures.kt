package com.exchange.core.api.trading.infrastructure.recovery

import com.exchange.core.trading.ClusterLineage
import io.aeron.cluster.RecordingLog

/** 증명의 구조만 검사하는 픽스처다. 실제 Archive 바이트는 RemoteTradingRecoveryTest에서 검사한다. */
internal fun remoteTestProof(
    lineage: ClusterLineage,
    epoch: Long,
    through: Long,
    execution: String,
): ByteArray =
    RemotePrefixProof(
        lineage,
        epoch,
        execution,
        "c".repeat(64),
        97,
        2,
        through,
        listOf(
            SnapshotProof(BackupRecordingEntry(0, 7, 0, 64, 1, 0, RecordingLog.ENTRY_TYPE_SNAPSHOT), "d".repeat(64)),
            SnapshotProof(BackupRecordingEntry(1, 7, 0, 64, 1, -1, RecordingLog.ENTRY_TYPE_SNAPSHOT), "e".repeat(64)),
        ),
        listOf(RecoveryTerm(2, 7, 0, 1)),
        RemotePrefixProof.EMPTY_TAIL_SHA256,
    ).encode()

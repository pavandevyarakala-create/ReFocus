package com.reeltrak.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Authoritative local restore journal for Phase 2 local-first restore protocol.
 *
 * Persisted atomically inside the Room transaction alongside restored habit records.
 * The presence of a row in this table is the SOLE authoritative evidence that a restore
 * operation successfully committed locally.
 */
@Entity(tableName = "local_restore_journal")
data class LocalRestoreJournal(
    @PrimaryKey
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "backup_id")
    val backupId: String,

    @ColumnInfo(name = "session_date_ist")
    val sessionDateIst: String,

    @ColumnInfo(name = "applied_at_ms")
    val appliedAtMs: Long,

    @ColumnInfo(name = "records_restored")
    val recordsRestored: Int,

    @ColumnInfo(name = "restored_daily_limit")
    val restoredDailyLimit: Int?,

    @ColumnInfo(name = "ack_status")
    val ackStatus: String, // PENDING_ACK | ACKNOWLEDGED

    @ColumnInfo(name = "datastore_synced")
    val datastoreSynced: Int = 0 // 0 = Pending DataStore catch-up, 1 = Synced
) {
    companion object {
        const val STATUS_PENDING_ACK = "PENDING_ACK"
        const val STATUS_ACKNOWLEDGED = "ACKNOWLEDGED"
    }
}

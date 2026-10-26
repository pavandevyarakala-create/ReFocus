package com.reeltrak.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reeltrak.app.data.local.entity.LocalRestoreJournal

@Dao
interface LocalRestoreJournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(journal: LocalRestoreJournal): Long

    @Query("SELECT * FROM local_restore_journal WHERE idempotency_key = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): LocalRestoreJournal?

    @Query("SELECT * FROM local_restore_journal WHERE ack_status = 'PENDING_ACK' ORDER BY applied_at_ms ASC")
    suspend fun getPendingAckEntries(): List<LocalRestoreJournal>

    @Query("SELECT * FROM local_restore_journal WHERE datastore_synced = 0 ORDER BY applied_at_ms ASC")
    suspend fun getPendingDataStoreSyncEntries(): List<LocalRestoreJournal>

    @Query("UPDATE local_restore_journal SET datastore_synced = 1 WHERE idempotency_key = :idempotencyKey")
    suspend fun markDataStoreSynced(idempotencyKey: String)

    @Query("UPDATE local_restore_journal SET ack_status = 'ACKNOWLEDGED' WHERE idempotency_key = :idempotencyKey")
    suspend fun markAcknowledged(idempotencyKey: String)

    @Query("SELECT * FROM local_restore_journal ORDER BY applied_at_ms DESC LIMIT 1")
    suspend fun getLatestJournalEntry(): LocalRestoreJournal?

    @Query("DELETE FROM local_restore_journal WHERE ack_status = 'ACKNOWLEDGED' AND applied_at_ms < :cutoffMs")
    suspend fun pruneAcknowledgedJournals(cutoffMs: Long)

    @Query("DELETE FROM local_restore_journal")
    suspend fun clearAll()
}

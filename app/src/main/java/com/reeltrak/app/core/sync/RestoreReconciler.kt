package com.reeltrak.app.core.sync

import android.content.Context
import android.util.Log
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localRestoreJournalDao: LocalRestoreJournalDao,
    private val preferencesRepository: PreferencesRepository
) {
    companion object {
        private const val TAG = "RestoreReconciler"
    }

    /**
     * Executes crash-safe startup self-healing on application cold start.
     *
     * Invariants guaranteed:
     * 1. Habit history is NEVER re-merged (prevents clobbering reels tracked since crash).
     * 2. Staged DataStore preferences are flushed to disk and latched (datastore_synced = 1).
     * 3. Pending ACKs are enqueued to WorkManager with network constraints.
     */
    suspend fun reconcilePendingLocalRestores() = withContext(Dispatchers.IO) {
        try {
            // Step 1: Reconcile unlatched DataStore preferences (Crash Points 2 & 3)
            val pendingDataStoreSync = localRestoreJournalDao.getPendingDataStoreSyncEntries()
            for (entry in pendingDataStoreSync) {
                Log.i(TAG, "Reconciling unlatched restore journal for ${entry.idempotencyKey} (DataStore sync pending)")
                val limit = entry.restoredDailyLimit
                if (limit != null && limit > 0) {
                    preferencesRepository.setDefaultDailyLimit(limit)
                }
                localRestoreJournalDao.markDataStoreSynced(entry.idempotencyKey)
                RestoreAckWorker.enqueue(context, entry.idempotencyKey)
            }

            // Step 2: Reconcile pending network ACKs (Crash Point 4)
            val pendingAcks = localRestoreJournalDao.getPendingAckEntries()
            for (entry in pendingAcks) {
                Log.i(TAG, "Re-enqueuing pending restore ACK for ${entry.idempotencyKey}")
                RestoreAckWorker.enqueue(context, entry.idempotencyKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Non-fatal: Restore self-healing encountered an exception: ${e.message}", e)
        }
    }
}

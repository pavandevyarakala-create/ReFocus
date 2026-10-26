package com.reeltrak.app.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.remote.dto.CloudDailyReelData
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.repository.AccountRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * DEPRECATED: Automatic background sync is permanently disabled per strict manual-only policy.
 * Only user-initiated backup may upload user data to the server.
 * Any legacy work request executing this worker immediately succeeds as a no-op without network writes.
 */
@Deprecated("Automatic background sync is disabled. Use LocalBackupRepository for manual backups.")
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val firestore: FirebaseFirestore,
    private val dailyReelDataDao: DailyReelDataDao,
    private val accountRepository: AccountRepository,
    private val subscriptionManager: com.reeltrak.app.domain.managers.SubscriptionManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "SyncWorker invoked: Automatic background sync is permanently disabled. No data uploaded.")
        Result.success()
    }
}

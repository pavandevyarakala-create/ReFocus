package com.reeltrak.app.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.core.network.NetworkConstants
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@HiltWorker
class RestoreAckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localRestoreJournalDao: LocalRestoreJournalDao,
    private val httpClient: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "RestoreAckWorker"
        const val KEY_IDEMPOTENCY_KEY = "idempotency_key"
        private const val MAX_RETRIES = 5

        fun enqueue(context: Context, idempotencyKey: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<RestoreAckWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_IDEMPOTENCY_KEY to idempotencyKey))
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "RestoreAckWorker_$idempotencyKey",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    override suspend fun doWork(): Result {
        val idempotencyKey = inputData.getString(KEY_IDEMPOTENCY_KEY) ?: run {
            // Self-healing: if no key in input, pick first pending ACK entry
            val pending = localRestoreJournalDao.getPendingAckEntries().firstOrNull()
            pending?.idempotencyKey ?: return Result.success()
        }

        val journal = localRestoreJournalDao.getByIdempotencyKey(idempotencyKey)
        if (journal == null || journal.ackStatus == "ACKNOWLEDGED") {
            Log.d(TAG, "Restore journal for $idempotencyKey is already acknowledged or not found. Done.")
            return Result.success()
        }

        val user = Firebase.auth.currentUser
        if (user == null) {
            Log.w(TAG, "User signed out during RestoreAckWorker execution. Retrying later.")
            return Result.retry()
        }

        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get auth token: ${e.message}")
            null
        }

        if (token.isNullOrBlank()) {
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        val workerBaseUrl = NetworkConstants.WORKER_BASE_URL
        val payload = JSONObject().apply {
            put("idempotencyKey", idempotencyKey)
        }

        val request = Request.Builder()
            .url("$workerBaseUrl/api/restore/ack")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            when (code) {
                200 -> {
                    Log.i(TAG, "Restore ACK succeeded for $idempotencyKey. Marking journal ACKNOWLEDGED.")
                    localRestoreJournalDao.markAcknowledged(idempotencyKey)
                    Result.success()
                }
                404 -> {
                    // Claim not found remotely or session already purged. Latch locally to avoid endless retries.
                    Log.w(TAG, "Restore claim $idempotencyKey not found remotely (404). Latching as acknowledged.")
                    localRestoreJournalDao.markAcknowledged(idempotencyKey)
                    Result.success()
                }
                401, 403 -> {
                    Log.e(TAG, "Permanent auth/entitlement rejection ($code) delivering ACK for $idempotencyKey: $body")
                    Result.failure()
                }
                else -> {
                    Log.w(TAG, "Transient error ($code) delivering ACK for $idempotencyKey: $body")
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network exception delivering ACK for $idempotencyKey: ${e.message}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }
}

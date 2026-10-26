package com.reeltrak.app.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.core.di.IoDispatcher
import com.reeltrak.app.core.network.NetworkConstants
import com.reeltrak.app.core.sync.RestoreAckWorker
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.dao.LocalRestoreJournalDao
import com.reeltrak.app.data.local.database.ReelTrakDatabase
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.LocalRestoreJournal
import com.reeltrak.app.data.remote.dto.CloudSnapshotRecordDto
import com.reeltrak.app.data.remote.dto.RestoreResult
import com.reeltrak.app.domain.managers.SubscriptionManager
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.LocalRestoreRepository
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRestoreRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ReelTrakDatabase,
    private val dailyReelDataDao: DailyReelDataDao,
    private val localRestoreJournalDao: LocalRestoreJournalDao,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val subscriptionManagerLazy: Lazy<SubscriptionManager>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val httpClient: OkHttpClient
) : LocalRestoreRepository {

    companion object {
        private const val TAG = "LocalRestoreRepo"
        private val DATE_REGEX = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        private val IST_ZONE = ZoneId.of("Asia/Kolkata")
    }

    private val restoreMutex = Mutex()

    private fun getTodayIstDateString(nowMs: Long = System.currentTimeMillis()): String {
        return Instant.ofEpochMilli(nowMs).atZone(IST_ZONE).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    override suspend fun performRestore(targetBackupId: String?): RestoreResult = withContext(ioDispatcher) {
        if (!restoreMutex.tryLock()) {
            Log.w(TAG, "Restore aborted: Another restore operation is already in progress.")
            return@withContext RestoreResult.InProgress("A restore operation is already in progress.")
        }
        try {
            val user = Firebase.auth.currentUser
        if (user == null) {
            Log.e(TAG, "Restore aborted: User not logged in.")
            return@withContext RestoreResult.Ineligible("User is not logged in.")
        }

        val uid = user.uid
        if (!subscriptionManagerLazy.get().hasActiveEntitlementNow()) {
            Log.w(TAG, "Restore aborted: User does not have active subscription or trial.")
            return@withContext RestoreResult.Ineligible("Cloud Restore requires an active Trial or Premium subscription.")
        }

        val accountStatus = accountRepository.checkAccountStatus(uid).getOrDefault(AccountStatus.ACTIVE)
        if (accountStatus == AccountStatus.PENDING_DELETION || accountStatus == AccountStatus.DELETED || accountStatus == AccountStatus.RESTRICTED) {
            Log.w(TAG, "Restore aborted: Account is $accountStatus.")
            return@withContext RestoreResult.Ineligible("Account is in $accountStatus status.")
        }

        // Reuse existing in-flight restore requestId for retry, or generate a fresh one
        var idempotencyKey = preferencesRepository.currentRestoreRequestId.first()
        if (idempotencyKey.isNullOrBlank()) {
            idempotencyKey = "rst_" + UUID.randomUUID().toString()
            preferencesRepository.setCurrentRestoreRequestId(idempotencyKey)
        }

        // In-flight retry check
        val existingJournal = localRestoreJournalDao.getByIdempotencyKey(idempotencyKey)
        if (existingJournal != null) {
            Log.i(TAG, "Restore attempt $idempotencyKey was already committed locally. Skipping Room merging.")
            if (existingJournal.datastoreSynced == 0) {
                existingJournal.restoredDailyLimit?.let { limit ->
                    if (limit > 0) preferencesRepository.setDefaultDailyLimit(limit)
                }
                localRestoreJournalDao.markDataStoreSynced(idempotencyKey)
            }
            if (existingJournal.ackStatus == LocalRestoreJournal.STATUS_PENDING_ACK) {
                RestoreAckWorker.enqueue(context, idempotencyKey)
            }
            preferencesRepository.setCurrentRestoreRequestId(null)
            return@withContext RestoreResult.Success(
                backupId = existingJournal.backupId,
                recordsRestored = existingJournal.recordsRestored,
                restoredDailyLimit = existingJournal.restoredDailyLimit,
                isIdempotent = true
            )
        }

        // Stage 1: Request restore reservation (POST /api/restore)
        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Firebase ID token: ${e.message}")
            return@withContext RestoreResult.Error("Authentication error: Could not fetch auth token", e)
        }

        if (token.isNullOrBlank()) {
            return@withContext RestoreResult.Error("Authentication error: Missing token")
        }

        val workerBaseUrl = NetworkConstants.WORKER_BASE_URL
        val reservationPayload = JSONObject().apply {
            put("idempotencyKey", idempotencyKey)
            if (!targetBackupId.isNullOrBlank()) {
                put("backupId", targetBackupId.trim())
            }
        }

        val request = Request.Builder()
            .url("$workerBaseUrl/api/restore")
            .header("Authorization", "Bearer $token")
            .post(reservationPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        val reservationResponse = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Network failure calling POST /api/restore: ${e.message}")
            return@withContext RestoreResult.Error("Network failure connecting to backup server", e, isNetworkError = true)
        }

        val responseCode = reservationResponse.code
        val responseBody = reservationResponse.body?.string() ?: ""

        if (responseCode != 200) {
            val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
            val errorCode = json.optString("code", "")
            val errorMsg = json.optString("error", "Restore failed with HTTP $responseCode")

            return@withContext when (responseCode) {
                409 -> when (errorCode) {
                    "RESTORE_IN_PROGRESS" -> RestoreResult.InProgress(errorMsg)
                    "RESTORE_LIMIT_EXCEEDED" -> {
                        preferencesRepository.setCurrentRestoreRequestId(null)
                        RestoreResult.LimitExceeded(errorMsg)
                    }
                    else -> RestoreResult.Error(errorMsg)
                }
                429 -> {
                    preferencesRepository.setCurrentRestoreRequestId(null)
                    RestoreResult.AttemptsExhausted(errorMsg)
                }
                404 -> {
                    preferencesRepository.setCurrentRestoreRequestId(null)
                    RestoreResult.NotFound(errorMsg)
                }
                403 -> {
                    preferencesRepository.setCurrentRestoreRequestId(null)
                    RestoreResult.Ineligible(errorMsg)
                }
                else -> RestoreResult.Error(errorMsg)
            }
        }

        // Parse successful reservation
        val reservationJson = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reservation JSON: ${e.message}")
            return@withContext RestoreResult.Error("Malformed server response", e)
        }

        val backupId = reservationJson.optString("backupId", targetBackupId ?: "")
        val restoredDailyLimit = if (reservationJson.has("defaultDailyLimit")) {
            reservationJson.optInt("defaultDailyLimit")
        } else {
            null
        }

        // Stage 2: Download & In-Memory Verification
        val rawDailyRecords = reservationJson.optJSONArray("dailyRecords") ?: JSONArray()
        val parsedRecords = mutableListOf<CloudSnapshotRecordDto>()

        try {
            for (i in 0 until rawDailyRecords.length()) {
                val obj = rawDailyRecords.getJSONObject(i)
                val dateStr = obj.optString("dateString")
                if (!DATE_REGEX.matches(dateStr)) {
                    throw IllegalArgumentException("Invalid date format in snapshot record: $dateStr")
                }
                val count = obj.optInt("count", obj.optInt("totalReelsCount", 0)).coerceAtLeast(0)
                val dailyGoal = obj.optInt("dailyGoal", 0).coerceAtLeast(0)
                val isSkullDay = obj.optBoolean("isSkullDay", false)
                val isStrictModeOn = obj.optBoolean("isStrictModeOn", true)
                val isStrictModeLocked = obj.optBoolean("isStrictModeLocked", false)
                val isLimitLocked = obj.optBoolean("isLimitLocked", false)
                val timeSpent = obj.optLong("totalTimeSpentMillis", 0L).coerceAtLeast(0L)

                parsedRecords.add(
                    CloudSnapshotRecordDto(
                        dateString = dateStr,
                        count = count,
                        totalReelsCount = count,
                        dailyGoal = dailyGoal,
                        isSkullDay = isSkullDay,
                        isStrictModeOn = isStrictModeOn,
                        isStrictModeLocked = isStrictModeLocked,
                        isLimitLocked = isLimitLocked,
                        totalTimeSpentMillis = timeSpent
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot record validation failed: ${e.message}. Executing pre-commit abort.")
            abortPendingRestore(idempotencyKey, backupId, "CORRUPT_PAYLOAD_VALIDATION_FAILED")
            return@withContext RestoreResult.Error("Downloaded backup payload was corrupted or invalid.", e)
        }

        // Stage 3: Local SQLite Room ACID Commit (Point of No Return)
        val todayStr = getTodayIstDateString()
        var commitSuccess = false
        var recordsRestoredCount = 0

        try {
            database.withTransaction {
                // Keep the accepted-transition marker across restore. The restore can preserve
                // or merge today's locally counted reels; deleting the marker here would let a
                // replay of the active transition increment that restored daily count again.
                for (cloudRec in parsedRecords) {
                    val date = cloudRec.dateString
                    val localRec = dailyReelDataDao.getDailyDataSync(date)

                    if (date == todayStr) {
                        // Section 5.5: Today's record -> LOCAL WINS if local has tracked reels
                        if (localRec != null && localRec.totalReelsCount > 0) {
                            Log.d(TAG, "Merge policy: Today's tracking preserved locally ($date, local=${localRec.totalReelsCount})")
                            continue
                        } else {
                            // If local is absent or count == 0, adopt cloud today
                            val newToday = DailyReelData(
                                dateString = date,
                                totalReelsCount = cloudRec.effectiveCount,
                                totalTimeSpentMillis = cloudRec.totalTimeSpentMillis,
                                dailyGoal = cloudRec.dailyGoal,
                                isSkullDay = cloudRec.isSkullDay,
                                isStrictModeOn = cloudRec.isStrictModeOn,
                                isStrictModeLocked = cloudRec.isStrictModeLocked,
                                isLimitLocked = cloudRec.isLimitLocked,
                                syncStatus = 0
                            )
                            dailyReelDataDao.insertOrReplace(newToday)
                            recordsRestoredCount++
                        }
                    } else {
                        // Historical record (date < todayStr)
                        if (localRec == null) {
                            // Fresh record insertion
                            val newHistorical = DailyReelData(
                                dateString = date,
                                totalReelsCount = cloudRec.effectiveCount,
                                totalTimeSpentMillis = cloudRec.totalTimeSpentMillis,
                                dailyGoal = cloudRec.dailyGoal,
                                isSkullDay = cloudRec.isSkullDay,
                                isStrictModeOn = cloudRec.isStrictModeOn,
                                isStrictModeLocked = cloudRec.isStrictModeLocked,
                                isLimitLocked = cloudRec.isLimitLocked,
                                syncStatus = 0
                            )
                            dailyReelDataDao.insertOrReplace(newHistorical)
                            recordsRestoredCount++
                        } else {
                            if (localRec.syncStatus == 0) {
                                // Synced historical -> CLOUD WINS
                                val updatedHistorical = localRec.copy(
                                    totalReelsCount = cloudRec.effectiveCount,
                                    totalTimeSpentMillis = cloudRec.totalTimeSpentMillis,
                                    dailyGoal = cloudRec.dailyGoal,
                                    isSkullDay = cloudRec.isSkullDay,
                                    isStrictModeOn = cloudRec.isStrictModeOn,
                                    isStrictModeLocked = cloudRec.isStrictModeLocked,
                                    isLimitLocked = cloudRec.isLimitLocked,
                                    syncStatus = 0
                                )
                                dailyReelDataDao.insertOrReplace(updatedHistorical)
                                recordsRestoredCount++
                            } else {
                                // Unsynced offline historical -> maxOf(local, cloud), local || cloud
                                val mergedHistorical = localRec.copy(
                                    totalReelsCount = maxOf(localRec.totalReelsCount, cloudRec.effectiveCount),
                                    totalTimeSpentMillis = maxOf(localRec.totalTimeSpentMillis, cloudRec.totalTimeSpentMillis),
                                    isStrictModeOn = localRec.isStrictModeOn || cloudRec.isStrictModeOn,
                                    syncStatus = 1
                                )
                                dailyReelDataDao.insertOrReplace(mergedHistorical)
                                recordsRestoredCount++
                            }
                        }
                    }
                }

                // Insert the authoritative restore journal row
                val journal = LocalRestoreJournal(
                    idempotencyKey = idempotencyKey,
                    backupId = backupId,
                    sessionDateIst = todayStr,
                    appliedAtMs = System.currentTimeMillis(),
                    recordsRestored = recordsRestoredCount,
                    restoredDailyLimit = restoredDailyLimit,
                    ackStatus = LocalRestoreJournal.STATUS_PENDING_ACK,
                    datastoreSynced = 0
                )
                localRestoreJournalDao.insert(journal)
            }
            commitSuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "Room transaction failed during restore: ${e.message}. Executing pre-commit abort.", e)
            abortPendingRestore(idempotencyKey, backupId, "ROOM_TRANSACTION_ROLLBACK")
            return@withContext RestoreResult.Error("Local database failed to apply restore transaction.", e)
        }

        // POINT OF NO RETURN: Room transaction has committed successfully!

        // Stage 4: Post-Commit DataStore Outbox Flush & Journal Latch
        try {
            if (restoredDailyLimit != null && restoredDailyLimit > 0) {
                preferencesRepository.setDefaultDailyLimit(restoredDailyLimit)
            }
            preferencesRepository.setLastRestoreTime(System.currentTimeMillis())
            preferencesRepository.setCurrentRestoreRequestId(null)
            localRestoreJournalDao.markDataStoreSynced(idempotencyKey)
        } catch (e: Exception) {
            Log.w(TAG, "DataStore preference flush failed post-commit: ${e.message}. Will self-heal on startup.")
        }

        // Stage 5: Network Acknowledgment Delivery
        try {
            val ackPayload = JSONObject().apply {
                put("idempotencyKey", idempotencyKey)
            }
            val ackReq = Request.Builder()
                .url("$workerBaseUrl/api/restore/ack")
                .header("Authorization", "Bearer $token")
                .post(ackPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            val ackResponse = httpClient.newCall(ackReq).execute()
            if (ackResponse.isSuccessful) {
                localRestoreJournalDao.markAcknowledged(idempotencyKey)
                Log.i(TAG, "Restore successfully committed and acknowledged synchronously for $idempotencyKey.")
            } else {
                Log.w(TAG, "Synchronous ACK returned HTTP ${ackResponse.code}. Enqueueing RestoreAckWorker.")
                RestoreAckWorker.enqueue(context, idempotencyKey)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Synchronous ACK failed due to network exception: ${e.message}. Enqueueing RestoreAckWorker.")
            RestoreAckWorker.enqueue(context, idempotencyKey)
        }

        RestoreResult.Success(
            backupId = backupId,
            recordsRestored = recordsRestoredCount,
            restoredDailyLimit = restoredDailyLimit,
            isIdempotent = false
        )
        } finally {
            restoreMutex.unlock()
        }
    }

    override suspend fun abortPendingRestore(idempotencyKey: String, backupId: String, reason: String): Boolean = withContext(ioDispatcher) {
        preferencesRepository.setCurrentRestoreRequestId(null)
        val user = Firebase.auth.currentUser ?: return@withContext false
        val token = try {
            user.getIdToken(false).await().token
        } catch (_: Exception) {
            null
        } ?: return@withContext false

        val workerBaseUrl = NetworkConstants.WORKER_BASE_URL
        val payload = JSONObject().apply {
            put("idempotencyKey", idempotencyKey)
            put("backupId", backupId)
            put("reason", reason.take(200))
        }

        val request = Request.Builder()
            .url("$workerBaseUrl/api/restore/abort")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val ok = response.isSuccessful
            Log.i(TAG, "Pre-commit abort sent for $idempotencyKey: HTTP ${response.code}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send pre-commit abort: ${e.message}")
            false
        }
    }
}

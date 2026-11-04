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
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
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
        val accountScope = ReelCountAccountScope.fromUid(uid)
        if (ReelCountAccountScope.current != accountScope) {
            return@withContext RestoreResult.Ineligible("Local account data is still switching. Please retry after sign-in completes.")
        }
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
                    if (limit > 0) preferencesRepository.setDefaultDailyLimitForScope(existingJournal.accountScope, limit)
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
        val totalChunks = reservationJson.optInt("totalChunks", 1)
        val expectedTotalRecords = reservationJson.optInt("totalRecords", -1)
        val allRecordObjects = mutableListOf<JSONObject>()

        try {
            if (totalChunks > 1) {
                // Multi-Chunk Download
                Log.d(TAG, "Initiating multi-chunk restore download: totalChunks=$totalChunks, expectedRecords=$expectedTotalRecords")
                for (chunkIdx in 0 until totalChunks) {
                    val chunkUrl = "$workerBaseUrl/api/restore/chunk?idempotencyKey=$idempotencyKey&backupId=$backupId&chunkIndex=$chunkIdx"
                    val chunkReq = Request.Builder()
                        .url(chunkUrl)
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                    val chunkRes = httpClient.newCall(chunkReq).execute()
                    if (!chunkRes.isSuccessful) {
                        val chunkCode = chunkRes.code
                        val chunkBody = chunkRes.body?.string() ?: ""
                        Log.e(TAG, "Chunk $chunkIdx download failed (HTTP $chunkCode): $chunkBody")
                        abortPendingRestore(idempotencyKey, backupId, "CHUNK_DOWNLOAD_FAILED_HTTP_$chunkCode")
                        return@withContext RestoreResult.Error("Failed downloading restore chunk $chunkIdx (HTTP $chunkCode)")
                    }
                    val chunkJson = JSONObject(chunkRes.body?.string() ?: "{}")
                    val reportedChunkIdx = chunkJson.optInt("chunkIndex", -1)
                    if (reportedChunkIdx != chunkIdx) {
                        Log.e(TAG, "Chunk index mismatch: expected $chunkIdx, got $reportedChunkIdx")
                        abortPendingRestore(idempotencyKey, backupId, "CHUNK_INDEX_MISMATCH")
                        return@withContext RestoreResult.Error("Restore chunk index mismatch: expected $chunkIdx, got $reportedChunkIdx")
                    }
                    val chunkArray = chunkJson.optJSONArray("records") ?: chunkJson.optJSONArray("dailyRecords")
                    if (chunkArray == null) {
                        Log.e(TAG, "Chunk $chunkIdx missing records array")
                        abortPendingRestore(idempotencyKey, backupId, "CHUNK_RECORDS_MISSING")
                        return@withContext RestoreResult.Error("Restore chunk $chunkIdx missing records array")
                    }
                    for (j in 0 until chunkArray.length()) {
                        allRecordObjects.add(chunkArray.getJSONObject(j))
                    }
                }

                if (expectedTotalRecords >= 0 && allRecordObjects.size != expectedTotalRecords) {
                    Log.e(TAG, "Total record count mismatch in multi-chunk restore: expected $expectedTotalRecords, fetched ${allRecordObjects.size}")
                    abortPendingRestore(idempotencyKey, backupId, "CHUNK_RECORD_COUNT_MISMATCH")
                    return@withContext RestoreResult.Error("Multi-chunk restore record count mismatch: expected $expectedTotalRecords, fetched ${allRecordObjects.size}")
                }
            } else {
                // Single-chunk / legacy payload from reservation response
                val rawDailyRecords = reservationJson.optJSONArray("dailyRecords")
                    ?: reservationJson.optJSONArray("records")
                    ?: JSONArray()
                for (i in 0 until rawDailyRecords.length()) {
                    allRecordObjects.add(rawDailyRecords.getJSONObject(i))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Multi-chunk download exception: ${e.message}")
            abortPendingRestore(idempotencyKey, backupId, "CHUNK_DOWNLOAD_EXCEPTION")
            return@withContext RestoreResult.Error("Failed downloading restore chunks", e)
        }

        val parsedRecords = mutableListOf<CloudSnapshotRecordDto>()
        val seenDates = mutableSetOf<String>()

        try {
            for (obj in allRecordObjects) {
                val dateStr = obj.optString("dateString")
                if (!DATE_REGEX.matches(dateStr)) {
                    throw IllegalArgumentException("Invalid date format in snapshot record: $dateStr")
                }
                if (!seenDates.add(dateStr)) {
                    throw IllegalArgumentException("Duplicate date record in snapshot: $dateStr")
                }

                // Priority 3: Count normalization with positive precedence
                val rawCount = if (obj.has("count") && !obj.isNull("count")) obj.optInt("count") else null
                val rawTotal = if (obj.has("totalReelsCount") && !obj.isNull("totalReelsCount")) obj.optInt("totalReelsCount") else null

                val resolvedCount = com.reeltrak.app.data.remote.dto.normalizeReelCount(rawTotal, rawCount)

                val dailyGoal = obj.optInt("dailyGoal", 0).coerceAtLeast(0)
                val isSkullDay = obj.optBoolean("isSkullDay", false)
                val isStrictModeOn = obj.optBoolean("isStrictModeOn", true)
                val isStrictModeLocked = obj.optBoolean("isStrictModeLocked", false)
                val isLimitLocked = obj.optBoolean("isLimitLocked", false)
                val timeSpent = obj.optLong("totalTimeSpentMillis", 0L).coerceAtLeast(0L)

                parsedRecords.add(
                    CloudSnapshotRecordDto(
                        dateString = dateStr,
                        count = resolvedCount,
                        totalReelsCount = resolvedCount,
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
                check(ReelCountAccountScope.current == accountScope) {
                    "Authenticated account changed while restore was in progress."
                }
                // Keep the accepted-transition marker across restore. The restore can preserve
                // or merge today's locally counted reels; deleting the marker here would let a
                // replay of the active transition increment that restored daily count again.
                // NON-DESTRUCTIVE MERGE SEMANTICS:
                // Historical local dates absent from the backup are intentionally preserved in Room,
                // guaranteeing user data is never destroyed by a partial or segmented cloud snapshot.
                for (cloudRec in parsedRecords) {
                    val date = cloudRec.dateString
                    val localRec = dailyReelDataDao.getDailyDataSync(date)

                    if (date == todayStr) {
                        // Section 5.5: Today's record -> Protect local reels tracked today,
                        // but adopt higher cloud count if cloud > local.
                        val localCount = localRec?.totalReelsCount ?: 0
                        val cloudCount = cloudRec.effectiveCount
                        val resolvedTodayCount = maxOf(localCount, cloudCount)
                        val resolvedTodayTime = maxOf(localRec?.totalTimeSpentMillis ?: 0L, cloudRec.totalTimeSpentMillis)
                        val resolvedGoal = if (cloudRec.dailyGoal > 0) cloudRec.dailyGoal else (localRec?.dailyGoal ?: 0)

                        val newToday = DailyReelData(
                            dateString = date,
                            totalReelsCount = resolvedTodayCount,
                            totalTimeSpentMillis = resolvedTodayTime,
                            dailyGoal = resolvedGoal,
                            isSkullDay = (localRec?.isSkullDay == true) || cloudRec.isSkullDay,
                            isStrictModeOn = cloudRec.isStrictModeOn,
                            isStrictModeLocked = (localRec?.isStrictModeLocked == true) || cloudRec.isStrictModeLocked,
                            isLimitLocked = (localRec?.isLimitLocked == true) || cloudRec.isLimitLocked,
                            syncStatus = if (localRec != null && localCount > cloudCount) 1 else 0,
                            accountScope = accountScope
                        )
                        dailyReelDataDao.insertOrReplace(newToday)
                        recordsRestoredCount++
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
                                syncStatus = 0,
                                accountScope = accountScope
                            )
                            dailyReelDataDao.insertOrReplace(newHistorical)
                            recordsRestoredCount++
                        } else {
                            if (localRec.syncStatus == 0) {
                                // Synced historical -> CLOUD WINS, EXCEPT NEVER overwrite valid local reel count with zero
                                val effectiveCloudCount = cloudRec.effectiveCount
                                val resolvedCount = if (effectiveCloudCount > 0) {
                                    effectiveCloudCount
                                } else if (localRec.totalReelsCount > 0) {
                                    Log.w(TAG, "Prevented zero-wiping local count for $date: local=${localRec.totalReelsCount}, cloud=$effectiveCloudCount")
                                    localRec.totalReelsCount
                                } else {
                                    0
                                }
                                val resolvedTimeSpent = if (cloudRec.totalTimeSpentMillis > 0L) {
                                    cloudRec.totalTimeSpentMillis
                                } else {
                                    localRec.totalTimeSpentMillis
                                }
                                val updatedHistorical = localRec.copy(
                                    totalReelsCount = resolvedCount,
                                    totalTimeSpentMillis = resolvedTimeSpent,
                                    dailyGoal = if (cloudRec.dailyGoal > 0) cloudRec.dailyGoal else localRec.dailyGoal,
                                    isSkullDay = cloudRec.isSkullDay || localRec.isSkullDay,
                                    isStrictModeOn = cloudRec.isStrictModeOn,
                                    isStrictModeLocked = cloudRec.isStrictModeLocked,
                                    isLimitLocked = cloudRec.isLimitLocked,
                                    syncStatus = 0,
                                    accountScope = accountScope
                                )
                                dailyReelDataDao.insertOrReplace(updatedHistorical)
                                recordsRestoredCount++
                            } else {
                                // Unsynced offline historical -> maxOf(local, cloud), local || cloud
                                val mergedHistorical = localRec.copy(
                                    totalReelsCount = maxOf(localRec.totalReelsCount, cloudRec.effectiveCount),
                                    totalTimeSpentMillis = maxOf(localRec.totalTimeSpentMillis, cloudRec.totalTimeSpentMillis),
                                    dailyGoal = if (cloudRec.dailyGoal > 0) cloudRec.dailyGoal else localRec.dailyGoal,
                                    isSkullDay = localRec.isSkullDay || cloudRec.isSkullDay,
                                    isStrictModeOn = localRec.isStrictModeOn || cloudRec.isStrictModeOn,
                                    isStrictModeLocked = localRec.isStrictModeLocked || cloudRec.isStrictModeLocked,
                                    isLimitLocked = localRec.isLimitLocked || cloudRec.isLimitLocked,
                                    syncStatus = 1,
                                    accountScope = accountScope
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
                    datastoreSynced = 0,
                    accountScope = accountScope
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
                preferencesRepository.setDefaultDailyLimitForScope(accountScope, restoredDailyLimit)
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

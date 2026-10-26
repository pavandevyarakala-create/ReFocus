package com.reeltrak.app.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.core.di.IoDispatcher
import com.reeltrak.app.core.network.NetworkConstants
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.remote.dto.BackupResult
import com.reeltrak.app.data.remote.dto.BackupStatusResponse
import com.reeltrak.app.domain.managers.SubscriptionManager
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.domain.repository.LocalBackupRepository
import com.reeltrak.app.domain.repository.PreferencesRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalBackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailyReelDataDao: DailyReelDataDao,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val subscriptionManagerLazy: Lazy<SubscriptionManager>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val httpClient: OkHttpClient
) : LocalBackupRepository {

    companion object {
        private const val TAG = "LocalBackupRepo"
        const val BACKUP_COOLDOWN_MS = 24 * 60 * 60 * 1000L
        const val CHUNK_SIZE = 200
    }

    private val backupMutex = Mutex()

    private val _isBackupRestricted = MutableStateFlow(false)
    override val isBackupRestricted: StateFlow<Boolean> = _isBackupRestricted.asStateFlow()

    private val _lastBackupServerTimeMs = MutableStateFlow(0L)
    override val lastBackupServerTimeMs: StateFlow<Long> = _lastBackupServerTimeMs.asStateFlow()

    private val _nextEligibleBackupTimeMs = MutableStateFlow(0L)
    override val nextEligibleBackupTimeMs: StateFlow<Long> = _nextEligibleBackupTimeMs.asStateFlow()

    private var lastStatusCheckElapsedRealtime = 0L

    override suspend fun checkServerBackupStatus(force: Boolean): BackupStatusResponse = withContext(ioDispatcher) {
        val user = Firebase.auth.currentUser ?: run {
            _isBackupRestricted.value = false
            return@withContext BackupStatusResponse(error = "User not logged in")
        }

        if (!subscriptionManagerLazy.get().hasActiveEntitlementNow()) {
            _isBackupRestricted.value = false
            return@withContext BackupStatusResponse(error = "Subscription required")
        }

        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && lastStatusCheckElapsedRealtime > 0L && (nowElapsed - lastStatusCheckElapsedRealtime < 5 * 60_000L)) {
            return@withContext BackupStatusResponse(
                success = true,
                isRestricted = _isBackupRestricted.value,
                lastBackupTimeMs = _lastBackupServerTimeMs.value,
                nextEligibleTimeMs = _nextEligibleBackupTimeMs.value
            )
        }

        lastStatusCheckElapsedRealtime = nowElapsed

        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            return@withContext BackupStatusResponse(error = e.message)
        }

        if (token.isNullOrBlank()) {
            return@withContext BackupStatusResponse(error = "Missing token")
        }

        val workerBaseUrl = NetworkConstants.WORKER_BASE_URL
        val request = Request.Builder()
            .url("$workerBaseUrl/api/backup/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = JSONObject(body)
                val isRestricted = json.optBoolean("isRestricted", json.optBoolean("backedUpToday", false))
                val lastTime = json.optLong("lastBackupTimeMs", 0L)
                val nextEligible = json.optLong("nextEligibleTimeMs", 0L)
                val serverTime = json.optLong("serverTimeMs", 0L)
                val nextIstMidnight = json.optLong("nextIstMidnightMs", 0L)
                val activeBackupId = json.optString("activeBackupId", "").ifBlank { null }
                val totalRecords = json.optInt("totalRecords", 0)

                _isBackupRestricted.value = isRestricted
                if (lastTime > 0L) {
                    _lastBackupServerTimeMs.value = lastTime
                    preferencesRepository.setLastBackupTime(lastTime)
                }
                if (nextEligible > 0L) {
                    _nextEligibleBackupTimeMs.value = nextEligible
                }

                BackupStatusResponse(
                    success = true,
                    isRestricted = isRestricted,
                    backedUpToday = isRestricted,
                    lastBackupTimeMs = lastTime,
                    nextEligibleTimeMs = nextEligible,
                    serverTimeMs = serverTime,
                    nextIstMidnightMs = nextIstMidnight,
                    activeBackupId = activeBackupId,
                    totalRecords = totalRecords
                )
            } else {
                BackupStatusResponse(error = "HTTP ${response.code}")
            }
        } catch (e: Exception) {
            BackupStatusResponse(error = e.message)
        }
    }

    override suspend fun performBackup(): BackupResult = withContext(ioDispatcher) {
        if (!backupMutex.tryLock()) {
            Log.w(TAG, "Backup aborted: Another backup operation is already in progress.")
            return@withContext BackupResult.Ineligible("A backup operation is already in progress.")
        }
        try {
            val user = Firebase.auth.currentUser
        if (user == null) {
            Log.e(TAG, "Backup aborted: User not logged in.")
            return@withContext BackupResult.Ineligible("User is not logged in.")
        }

        val uid = user.uid
        if (!subscriptionManagerLazy.get().hasActiveEntitlementNow()) {
            Log.w(TAG, "Backup aborted: No active subscription or trial.")
            return@withContext BackupResult.Ineligible("Cloud Backup requires an active Trial or Premium subscription.")
        }

        val accountStatus = accountRepository.checkAccountStatus(uid).getOrDefault(AccountStatus.ACTIVE)
        if (accountStatus == AccountStatus.PENDING_DELETION || accountStatus == AccountStatus.DELETED || accountStatus == AccountStatus.RESTRICTED) {
            Log.w(TAG, "Backup aborted: Account is $accountStatus.")
            return@withContext BackupResult.Ineligible("Account is in $accountStatus status.")
        }

        val allRecords = dailyReelDataDao.getAllDailyDataSync()
        val defaultDailyLimit = preferencesRepository.defaultDailyLimit.first()

        // Reuse existing in-flight requestId for retry, or generate a fresh one
        var requestId = preferencesRepository.currentBackupRequestId.first()
        if (requestId.isNullOrBlank()) {
            requestId = "req_" + UUID.randomUUID().toString()
            preferencesRepository.setCurrentBackupRequestId(requestId)
        }

        val token = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Firebase ID token: ${e.message}")
            return@withContext BackupResult.Error("Authentication error: Could not fetch auth token", e)
        }

        if (token.isNullOrBlank()) {
            return@withContext BackupResult.Error("Authentication error: Missing token")
        }

        val workerBaseUrl = NetworkConstants.WORKER_BASE_URL

        if (allRecords.size <= CHUNK_SIZE) {
            // Fast-Path: single atomic request
            val recordsArray = JSONArray()
            for (rec in allRecords) {
                recordsArray.put(JSONObject().apply {
                    put("dateString", rec.dateString)
                    put("count", rec.totalReelsCount.coerceIn(0, 100000))
                    put("dailyGoal", rec.dailyGoal.coerceIn(0, 100000))
                    put("isSkullDay", rec.isSkullDay)
                    put("isStrictModeOn", rec.isStrictModeOn)
                    put("isStrictModeLocked", rec.isStrictModeLocked)
                    put("isLimitLocked", rec.isLimitLocked)
                    put("totalTimeSpentMillis", rec.totalTimeSpentMillis.coerceAtLeast(0L))
                })
            }

            val payload = JSONObject().apply {
                put("requestId", requestId)
                put("defaultDailyLimit", defaultDailyLimit.coerceAtLeast(0))
                put("dailyRecords", recordsArray)
            }

            val request = Request.Builder()
                .url("$workerBaseUrl/api/backup")
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            try {
                val response = httpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string() ?: ""

                when (code) {
                    200 -> {
                        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                        val backupId = json.optString("backupId", "")
                        val serverTimeMs = json.optLong("lastBackupTimeMs", System.currentTimeMillis())
                        val nextEligible = json.optLong("nextEligibleTimeMs", serverTimeMs + BACKUP_COOLDOWN_MS)

                        // Clear pending requestId on success
                        preferencesRepository.setCurrentBackupRequestId(null)
                        preferencesRepository.setLastBackupTime(serverTimeMs)
                        _lastBackupServerTimeMs.value = serverTimeMs
                        _nextEligibleBackupTimeMs.value = nextEligible
                        _isBackupRestricted.value = true

                        // Mark records synced locally
                        for (rec in allRecords) {
                            dailyReelDataDao.markAsSynced(rec.dateString, rec.updatedAt)
                        }

                        Log.i(TAG, "Fast-path backup succeeded: $backupId at $serverTimeMs")
                        BackupResult.Success(
                            backupId = backupId,
                            lastBackupTimeMs = serverTimeMs,
                            nextEligibleTimeMs = nextEligible,
                            isIdempotent = json.optBoolean("idempotent", false)
                        )
                    }
                    409 -> {
                        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                        val errorMsg = json.optString("error", "You can back up your data once every 24 hours.")
                        val serverTimeMs = json.optLong("lastBackupTimeMs", 0L)
                        val nextEligible = json.optLong("nextEligibleTimeMs", if (serverTimeMs > 0L) serverTimeMs + BACKUP_COOLDOWN_MS else 0L)

                        preferencesRepository.setCurrentBackupRequestId(null)
                        if (serverTimeMs > 0L) {
                            preferencesRepository.setLastBackupTime(serverTimeMs)
                            _lastBackupServerTimeMs.value = serverTimeMs
                        }
                        if (nextEligible > 0L) {
                            _nextEligibleBackupTimeMs.value = nextEligible
                        }
                        _isBackupRestricted.value = true

                        Log.w(TAG, "Fast-path backup restricted by quota (409): $errorMsg")
                        BackupResult.Cooldown(nextEligible, errorMsg)
                    }
                    403 -> {
                        BackupResult.Ineligible("Unauthorized: Backup requires active Trial or Premium entitlement")
                    }
                    429 -> {
                        BackupResult.Error("Too many requests. Please try again later.")
                    }
                    else -> {
                        Log.e(TAG, "Fast-path backup failed with HTTP $code: $body")
                        BackupResult.Error("Backup operation failed (HTTP $code)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network exception during fast-path backup: ${e.message}")
                BackupResult.Error("Network failure connecting to backup server", e, isNetworkError = true)
            }
        } else {
            // Multi-Part Chunked Backup Protocol
            try {
                // 1. Init
                val initPayload = JSONObject().apply {
                    put("requestId", requestId)
                    put("recordCount", allRecords.size)
                    put("defaultDailyLimit", defaultDailyLimit.coerceAtLeast(0))
                }
                val initReq = Request.Builder()
                    .url("$workerBaseUrl/api/backup/init")
                    .header("Authorization", "Bearer $token")
                    .post(initPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                val initRes = httpClient.newCall(initReq).execute()
                if (!initRes.isSuccessful) {
                    val code = initRes.code
                    val body = initRes.body?.string() ?: ""
                    if (code == 409) {
                        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                        val nextEligible = json.optLong("nextEligibleTimeMs", 0L)
                        preferencesRepository.setCurrentBackupRequestId(null)
                        _isBackupRestricted.value = true
                        return@withContext BackupResult.Cooldown(nextEligible, json.optString("error", "Backup restricted"))
                    }
                    return@withContext BackupResult.Error("Chunked backup init failed (HTTP $code)")
                }

                val initJson = JSONObject(initRes.body?.string() ?: "{}")
                val backupId = initJson.optString("backupId", "")
                if (backupId.isBlank()) {
                    return@withContext BackupResult.Error("Missing backupId from backup init response")
                }

                // 2. Chunks
                val chunks = allRecords.chunked(CHUNK_SIZE)
                for ((idx, chunkSlice) in chunks.withIndex()) {
                    val sliceArray = JSONArray()
                    for (rec in chunkSlice) {
                        sliceArray.put(JSONObject().apply {
                            put("dateString", rec.dateString)
                            put("count", rec.totalReelsCount.coerceIn(0, 100000))
                            put("dailyGoal", rec.dailyGoal.coerceIn(0, 100000))
                            put("isSkullDay", rec.isSkullDay)
                            put("isStrictModeOn", rec.isStrictModeOn)
                            put("isStrictModeLocked", rec.isStrictModeLocked)
                            put("isLimitLocked", rec.isLimitLocked)
                            put("totalTimeSpentMillis", rec.totalTimeSpentMillis.coerceAtLeast(0L))
                        })
                    }

                    val chunkPayload = JSONObject().apply {
                        put("backupId", backupId)
                        put("chunkIndex", idx)
                        put("records", sliceArray)
                    }

                    val chunkReq = Request.Builder()
                        .url("$workerBaseUrl/api/backup/chunk")
                        .header("Authorization", "Bearer $token")
                        .post(chunkPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                        .build()

                    val chunkRes = httpClient.newCall(chunkReq).execute()
                    if (!chunkRes.isSuccessful) {
                        return@withContext BackupResult.Error("Chunk $idx upload failed (HTTP ${chunkRes.code})")
                    }
                }

                // 3. Finalize
                val finPayload = JSONObject().apply {
                    put("backupId", backupId)
                    put("requestId", requestId)
                    put("totalRecords", allRecords.size)
                }

                val finReq = Request.Builder()
                    .url("$workerBaseUrl/api/backup/finalize")
                    .header("Authorization", "Bearer $token")
                    .post(finPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                val finRes = httpClient.newCall(finReq).execute()
                if (!finRes.isSuccessful) {
                    return@withContext BackupResult.Error("Backup finalization failed (HTTP ${finRes.code})")
                }

                val finJson = JSONObject(finRes.body?.string() ?: "{}")
                val serverTimeMs = finJson.optLong("lastBackupTimeMs", System.currentTimeMillis())
                val nextEligible = finJson.optLong("nextEligibleTimeMs", serverTimeMs + BACKUP_COOLDOWN_MS)

                preferencesRepository.setCurrentBackupRequestId(null)
                preferencesRepository.setLastBackupTime(serverTimeMs)
                _lastBackupServerTimeMs.value = serverTimeMs
                _nextEligibleBackupTimeMs.value = nextEligible
                _isBackupRestricted.value = true

                for (rec in allRecords) {
                    dailyReelDataDao.markAsSynced(rec.dateString, rec.updatedAt)
                }

                Log.i(TAG, "Chunked backup completed successfully: $backupId at $serverTimeMs")
                BackupResult.Success(
                    backupId = backupId,
                    lastBackupTimeMs = serverTimeMs,
                    nextEligibleTimeMs = nextEligible
                )
            } catch (e: Exception) {
                Log.e(TAG, "Network exception during chunked backup: ${e.message}")
                BackupResult.Error("Network failure connecting to backup server", e, isNetworkError = true)
            }
        }
        } finally {
        backupMutex.unlock()
    }
}
}

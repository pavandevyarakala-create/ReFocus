package com.reeltrak.app.core.sync

import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.data.remote.dto.CloudDailyReelData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import dagger.Lazy
import com.reeltrak.app.services.tracking.DayManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.google.firebase.firestore.ListenerRegistration
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.repository.AccountRepository
import com.reeltrak.app.core.di.ApplicationScope
import com.reeltrak.app.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.reeltrak.app.domain.repository.LocalBackupRepository
import com.reeltrak.app.domain.repository.LocalRestoreRepository
import com.reeltrak.app.data.remote.dto.BackupResult
import com.reeltrak.app.data.remote.dto.RestoreResult
import com.reeltrak.app.core.sync.RestoreReconciler
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

class BackupAlreadyCompletedException(
    message: String = "You can back up your data once every 24 hours."
) : Exception(message)

@Singleton
class SyncManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val dailyReelDataDao: DailyReelDataDao,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val dayManagerLazy: Lazy<DayManager>,
    private val subscriptionManagerLazy: Lazy<com.reeltrak.app.domain.managers.SubscriptionManager>,
    private val localBackupRepository: LocalBackupRepository,
    private val localRestoreRepository: LocalRestoreRepository,
    private val restoreReconciler: RestoreReconciler,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val httpClient: OkHttpClient
) {
    companion object {
        const val BACKUP_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private var lastRestoredUid: String? = null
    private val restoreMutex = Mutex()
    private val backupMutex = Mutex()
    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL
    private var backupStatusRegistration: ListenerRegistration? = null
    private val savedEmailCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var lastServerStatusCheckElapsedRealtime = 0L

    private val _isBackupRestricted = MutableStateFlow(false)
    val isBackupRestricted: StateFlow<Boolean> = _isBackupRestricted.asStateFlow()

    private val _lastBackupServerTimeMs = MutableStateFlow(0L)
    val lastBackupServerTimeMs: StateFlow<Long> = _lastBackupServerTimeMs.asStateFlow()

    private val _nextEligibleBackupTimeMs = MutableStateFlow(0L)
    val nextEligibleBackupTimeMs: StateFlow<Long> = _nextEligibleBackupTimeMs.asStateFlow()

    private val _serverTimeAnchorMs = MutableStateFlow(0L)
    val serverTimeAnchorMs: StateFlow<Long> = _serverTimeAnchorMs.asStateFlow()

    private val _serverElapsedRealtimeAnchorMs = MutableStateFlow(0L)
    val serverElapsedRealtimeAnchorMs: StateFlow<Long> = _serverElapsedRealtimeAnchorMs.asStateFlow()

    // Backwards compatibility for existing UI observers
    val isBackedUpToday: StateFlow<Boolean> = _isBackupRestricted.asStateFlow()

    fun getEstimatedServerTimeMs(): Long {
        val serverAnchor = _serverTimeAnchorMs.value
        val elapsedAnchor = _serverElapsedRealtimeAnchorMs.value
        return if (serverAnchor > 0L && elapsedAnchor > 0L) {
            val delta = SystemClock.elapsedRealtime() - elapsedAnchor
            serverAnchor + delta
        } else {
            System.currentTimeMillis()
        }
    }

    init {
        applicationScope.launch(ioDispatcher) {
            restoreReconciler.reconcilePendingLocalRestores()
        }
        applicationScope.launch(ioDispatcher) {
            localBackupRepository.isBackupRestricted.collect { restricted ->
                _isBackupRestricted.value = restricted
            }
        }
        applicationScope.launch(ioDispatcher) {
            localBackupRepository.lastBackupServerTimeMs.collect { time ->
                if (time > 0L) _lastBackupServerTimeMs.value = time
            }
        }
        applicationScope.launch(ioDispatcher) {
            localBackupRepository.nextEligibleBackupTimeMs.collect { time ->
                if (time > 0L) _nextEligibleBackupTimeMs.value = time
            }
        }
        applicationScope.launch(ioDispatcher) {
            val subManager = subscriptionManagerLazy.get()
            subManager.accessState.collect { accessState ->
                val isEntitled = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE
                val currentUser = Firebase.auth.currentUser
                val currentUid = currentUser?.uid
                if (isEntitled && currentUid != null) {
                    if (lastRestoredUid != null && lastRestoredUid != currentUid) {
                        savedEmailCache.clear()
                        lastServerStatusCheckElapsedRealtime = 0L
                    }
                    // Strict manual-only policy: No automatic cloud restore on subscription or startup events.
                    // Data remains local until the user explicitly selects "Restore Data" in Settings.
                    // Attach real-time listener for server-authoritative backup status (cooldown & UI state)
                    attachBackupStatusListener(currentUid)
                } else if (!isEntitled || currentUid == null) {
                    lastRestoredUid = null
                    savedEmailCache.clear()
                    lastServerStatusCheckElapsedRealtime = 0L
                    detachBackupStatusListener()
                }
            }
        }
    }

    private fun attachBackupStatusListener(uid: String) {
        backupStatusRegistration?.remove()
        backupStatusRegistration = firestore.collection("users")
            .document(uid)
            .collection("backup")
            .document("status")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("FirestoreSync", "Backup status snapshot listener error for $uid: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    val lastBackupTimeMs = snapshot.getLong("lastBackupTimeMs") ?: 0L
                    if (lastBackupTimeMs > 0L) {
                        _lastBackupServerTimeMs.value = lastBackupTimeMs
                        val nextEligible = lastBackupTimeMs + BACKUP_COOLDOWN_MS
                        _nextEligibleBackupTimeMs.value = nextEligible
                        val currentEstimatedServerTime = getEstimatedServerTimeMs()
                        val isRestricted = status == "SUCCESS" && (currentEstimatedServerTime < nextEligible)
                        _isBackupRestricted.value = isRestricted
                        applicationScope.launch(ioDispatcher) {
                            try {
                                preferencesRepository.setLastBackupTime(lastBackupTimeMs)
                            } catch (_: Exception) {}
                        }
                        Log.d("FirestoreSync", "Observed cloud backup status for $uid: status=$status, lastTime=$lastBackupTimeMs, nextEligible=$nextEligible, restricted=$isRestricted")
                    } else {
                        _lastBackupServerTimeMs.value = 0L
                        _nextEligibleBackupTimeMs.value = 0L
                        _isBackupRestricted.value = false
                    }
                } else {
                    _lastBackupServerTimeMs.value = 0L
                    _nextEligibleBackupTimeMs.value = 0L
                    _isBackupRestricted.value = false
                }
            }
    }

    private fun detachBackupStatusListener() {
        backupStatusRegistration?.remove()
        backupStatusRegistration = null
        _isBackupRestricted.value = false
        _lastBackupServerTimeMs.value = 0L
        _nextEligibleBackupTimeMs.value = 0L
        savedEmailCache.clear()
        lastServerStatusCheckElapsedRealtime = 0L
    }

    /**
     * Checks server backup status only when required state is missing, uncalibrated,
     * or stale. Protects free-tier quotas by avoiding redundant Worker invocations and
     * Firestore reads on every visit to the Settings screen.
     *
     * Criteria for skipping remote fetch (safe local evaluation):
     * 1. User is not entitled -> false immediately (0 network calls).
     * 2. User has never backed up (_lastBackupServerTimeMs == 0L and local prefs == 0L) -> false immediately (0 network calls).
     * 3. Server time anchor is calibrated & fresh (< 12 hours old) -> evaluated locally via monotonic hardware clock (0 network calls).
     * 4. Cooldown on network checks: at least 5 minutes between remote checks unless forced.
     */
    suspend fun checkServerBackupStatusIfNeeded(force: Boolean = false): Boolean = withContext(ioDispatcher) {
        if (!isEntitledForSync()) {
            _isBackupRestricted.value = false
            return@withContext false
        }
        val currentUser = Firebase.auth.currentUser ?: run {
            _isBackupRestricted.value = false
            return@withContext false
        }

        // Fast path 1: If never backed up remotely or locally, no restriction exists
        val lastLocalBackup = preferencesRepository.lastBackupTime.first()
        val lastServerBackup = _lastBackupServerTimeMs.value
        if (!force && lastLocalBackup == 0L && lastServerBackup == 0L) {
            _isBackupRestricted.value = false
            return@withContext false
        }

        // Fast path 2: If server time anchor is calibrated and fresh, evaluate locally without remote fetch
        val anchorServerTime = _serverTimeAnchorMs.value
        val anchorElapsed = _serverElapsedRealtimeAnchorMs.value
        val nowElapsed = SystemClock.elapsedRealtime()
        val isAnchorFresh = anchorServerTime > 0L && anchorElapsed > 0L && (nowElapsed - anchorElapsed < 12 * 3600_000L)

        val nextEligible = _nextEligibleBackupTimeMs.value
        if (!force && isAnchorFresh && nextEligible > 0L) {
            val estimatedServerTime = anchorServerTime + (nowElapsed - anchorElapsed)
            val isRestricted = estimatedServerTime < nextEligible
            _isBackupRestricted.value = isRestricted
            return@withContext isRestricted
        }

        // Fast path 3: Throttle repeated remote checks within 5 minutes to prevent spam
        val lastCheckElapsed = lastServerStatusCheckElapsedRealtime
        if (!force && lastCheckElapsed > 0L && (nowElapsed - lastCheckElapsed < 5 * 60_000L)) {
            return@withContext _isBackupRestricted.value
        }

        // Authoritative fallback: remote status check
        lastServerStatusCheckElapsedRealtime = nowElapsed
        checkServerBackupStatus()
    }

    suspend fun checkServerBackupStatus(): Boolean = withContext(ioDispatcher) {
        try {
            if (!isEntitledForSync()) {
                _isBackupRestricted.value = false
                return@withContext false
            }
            val user = Firebase.auth.currentUser ?: run {
                _isBackupRestricted.value = false
                return@withContext false
            }
            val token = user.getIdToken(false).await().token ?: run {
                return@withContext _isBackupRestricted.value
            }
            val request = Request.Builder()
                .url("$workerBaseUrl/api/backup/status")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = org.json.JSONObject(body)
                val isRestricted = json.optBoolean("isRestricted", json.optBoolean("backedUpToday", false))
                val lastTime = json.optLong("lastBackupTimeMs", 0L)
                val nextEligible = json.optLong("nextEligibleTimeMs", if (lastTime > 0L) lastTime + BACKUP_COOLDOWN_MS else 0L)
                val serverTime = json.optLong("serverTimeMs", 0L)
                if (serverTime > 0L) {
                    _serverTimeAnchorMs.value = serverTime
                    _serverElapsedRealtimeAnchorMs.value = SystemClock.elapsedRealtime()
                }
                if (lastTime > 0L) {
                    _lastBackupServerTimeMs.value = lastTime
                    preferencesRepository.setLastBackupTime(lastTime)
                }
                _nextEligibleBackupTimeMs.value = nextEligible
                _isBackupRestricted.value = isRestricted
                isRestricted
            } else {
                _isBackupRestricted.value
            }
        } catch (e: Exception) {
            Log.w("FirestoreSync", "checkServerBackupStatus network check failed: ${e.message}")
            _isBackupRestricted.value
        }
    }

    private fun isEntitledForSync(): Boolean {
        val entitled = subscriptionManagerLazy.get().hasActiveEntitlementNow()
        if (!entitled) {
            Log.d("FirestoreSync", "Cloud user-data operation skipped: User does not have active Premium or Trial entitlement.")
        }
        return entitled
    }

    private suspend fun isAccountEligibleForSync(uid: String): Boolean {
        if (!isEntitledForSync()) {
            return false
        }
        val statusResult = accountRepository.checkAccountStatus(uid)
        val status = statusResult.getOrDefault(AccountStatus.ACTIVE)
        if (status == AccountStatus.PENDING_DELETION || status == AccountStatus.DELETED || status == AccountStatus.RESTRICTED) {
            Log.w("FirestoreSync", "Sync operation aborted: Account $uid is in $status status.")
            return false
        }
        return true
    }

    @Deprecated("Legacy direct Firestore write. Automatic cloud backup is permanently disabled.")
    suspend fun backupToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i("FirestoreSync", "backupToCloud skipped: Strict manual-only backup policy enforced.")
        Result.success(Unit)
    }

    @Deprecated("Legacy direct Firestore write. Automatic cloud backup is permanently disabled.")
    suspend fun backupSpecificDay(dateStr: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i("FirestoreSync", "backupSpecificDay skipped: Strict manual-only backup policy enforced.")
        Result.success(Unit)
    }

    /**
     * Preserves local tracking state before sign out.
     * Strict manual-only policy: Flushes in-memory tracking to Room database locally.
     * Never uploads data to Firestore or any cloud server during logout.
     */
    suspend fun syncTodayBeforeSignOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            dayManagerLazy.get().flushPendingWritesSuspend()
            Log.i("FirestoreSync", "syncTodayBeforeSignOut: Flushed in-memory writes to local Room DB. No remote upload performed.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w("FirestoreSync", "syncTodayBeforeSignOut: Failed to flush pending writes", e)
            Result.success(Unit)
        }
    }

    suspend fun backupToday(): Result<Unit> = syncTodayBeforeSignOut()

    /**
     * Complete on-demand application backup for entitled users (Settings -> "Back Up Now").
     *
     * Invariants:
     * 1. Strictly gated to active TRIAL or ACTIVE entitlement; Free accounts fail closed with SecurityException.
     * 2. Scoped strictly to the authenticated user's own Firebase UID.
     * 3. Uses backupMutex to prevent concurrent or duplicate backup executions.
     * 4. Flushes in-memory tracking to Room first to ensure today's state is persistent.
     * 5. Backs up all local records (historical + today) to Firestore under /users/{uid}/daily_data/{dateString}.
     * 6. Backs up persistent daily limit and settings under /users/{uid}/settings/preferences.
     * 7. Records last backup epoch timestamp in preferences.
     * 8. Never backs up sensitive credentials, tokens, or subscription secrets.
     */
    suspend fun performFullBackup(): Result<Unit> = withContext(ioDispatcher) {
        backupMutex.withLock {
            dayManagerLazy.get().flushPendingWritesSuspend()
            when (val result = localBackupRepository.performBackup()) {
                is BackupResult.Success -> {
                    _isBackupRestricted.value = true
                    _lastBackupServerTimeMs.value = result.lastBackupTimeMs
                    _nextEligibleBackupTimeMs.value = result.nextEligibleTimeMs
                    Result.success(Unit)
                }
                is BackupResult.Cooldown -> {
                    _isBackupRestricted.value = true
                    _nextEligibleBackupTimeMs.value = result.nextEligibleTimeMs
                    Result.failure(BackupAlreadyCompletedException(result.message))
                }
                is BackupResult.Ineligible -> Result.failure(SecurityException(result.reason))
                is BackupResult.Error -> Result.failure(result.throwable ?: Exception(result.message))
            }
        }
    }

    suspend fun restoreFromCloud(): Result<Unit> = restoreFromCloud(null)

    suspend fun restoreFromCloud(targetBackupId: String? = null): Result<Unit> = withContext(ioDispatcher) {
        // Delegates to localRestoreRepository which enforces deterministic merge:
        // 1. dto.dateString == todayStr (today record handling: local count > 0 wins, else cloud)
        // 2. Preserves offline progress when localRecord.syncStatus == 1; cloud wins when localRecord.syncStatus == 0 (synced)
        // 3. Preserves local historical records atomically via Room journal
        restoreMutex.withLock {
            dayManagerLazy.get().setRestoring(true)
            try {
                when (val result = localRestoreRepository.performRestore(targetBackupId)) {
                    is RestoreResult.Success -> {
                        preferencesRepository.setHasMigratedToServer(true)
                        Result.success(Unit)
                    }
                    is RestoreResult.InProgress -> Result.failure(Exception(result.message))
                    is RestoreResult.LimitExceeded -> Result.failure(Exception(result.message))
                    is RestoreResult.AttemptsExhausted -> Result.failure(Exception(result.message))
                    is RestoreResult.NotFound -> Result.failure(Exception(result.message))
                    is RestoreResult.Ineligible -> Result.failure(SecurityException(result.reason))
                    is RestoreResult.Error -> Result.failure(result.throwable ?: Exception(result.message))
                }
            } finally {
                dayManagerLazy.get().setRestoring(false)
            }
        }
    }

    private suspend fun ensureUserEmailSaved(uid: String) {
        try {
            val email = Firebase.auth.currentUser?.email
            if (email != null) {
                if (savedEmailCache[uid] == email) {
                    // Email already synced for this UID in the current session; avoids duplicate Firestore write
                    return
                }
                firestore.collection("users").document(uid)
                    .set(mapOf("email" to email), SetOptions.merge())
                    .await()
                // Cache only after confirmed write success so failures can retry
                savedEmailCache[uid] = email
                Log.d("FirestoreSync", "Saved user email to Firestore for UID=$uid")
            }
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Failed to save user email for $uid", e)
            // Cache is not set on failure, allowing subsequent retry
        }
    }

    /**
     * DEPRECATED: Automatic cloud migration is permanently disabled.
     * Strict manual-only policy: User data remains local until manual backup.
     */
    suspend fun performLegacyMigration(): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i("FirestoreSync", "performLegacyMigration skipped: Automatic migration disabled per strict manual-only policy.")
        preferencesRepository.setHasMigratedToServer(true)
        Result.success(Unit)
    }

    /**
     * Strict manual-only policy: Daily limit preferences remain local in DataStore/Room.
     * Account-level daily limit is only uploaded as part of an explicit user-initiated backup snapshot.
     */
    suspend fun saveDailyLimitToCloud(goal: Int): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i("FirestoreSync", "saveDailyLimitToCloud skipped: Goal changes remain strictly local until explicit backup.")
        Result.success(Unit)
    }

    /**
     * Fetches the user's latest saved daily limit from Firestore.
     * Strategy:
     * 1. Query /users/{uid}/settings/preferences (primary).
     * 2. Resilient Fallback: Query /users/{uid}/daily_data ordered by dateString DESC for latest record with dailyGoal > 0.
     */
    suspend fun fetchDailyLimitFromCloud(): Int? = withContext(Dispatchers.IO) {
        try {
            if (!isEntitledForSync()) return@withContext null
            val uid = Firebase.auth.currentUser?.uid ?: return@withContext null
            if (!isAccountEligibleForSync(uid)) return@withContext null

            // 1. Primary: Check /users/{uid}/settings/preferences
            try {
                val settingsDoc = firestore.collection("users")
                    .document(uid)
                    .collection("settings")
                    .document("preferences")
                    .get()
                    .await()

                if (settingsDoc != null && settingsDoc.exists()) {
                    val lastBackupEpoch = settingsDoc.getLong("lastUpdatedEpoch")
                    if (lastBackupEpoch != null && lastBackupEpoch > 0) {
                        try {
                            preferencesRepository.setLastBackupTime(lastBackupEpoch)
                        } catch (_: Exception) {}
                    }
                    val limit = (settingsDoc.getLong("dailyLimit") ?: settingsDoc.getLong("defaultDailyLimit"))?.toInt()
                    if (limit != null && limit > 0) {
                        Log.d("FirestoreSync", "Found cloud dailyLimit ($limit) in settings/preferences for $uid")
                        return@withContext limit
                    }
                }
            } catch (e: Exception) {
                Log.w("FirestoreSync", "Could not read settings/preferences: ${e.message}")
            }

            // 2. Resilient Fallback: Query /users/{uid}/daily_data for latest record with dailyGoal > 0
            try {
                val dailyDataDocs = firestore.collection("users")
                    .document(uid)
                    .collection("daily_data")
                    .orderBy("dateString", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(10)
                    .get()
                    .await()

                if (dailyDataDocs != null && !dailyDataDocs.isEmpty) {
                    for (doc in dailyDataDocs.documents) {
                        val goal = doc.getLong("dailyGoal")?.toInt()
                        if (goal != null && goal > 0) {
                            Log.d("FirestoreSync", "Found fallback cloud dailyGoal ($goal) in daily_data/${doc.id} for $uid")
                            return@withContext goal
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("FirestoreSync", "Could not query daily_data for fallback daily limit: ${e.message}")
            }

            null
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Error fetching daily limit from cloud: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches the user's cloud daily limit and restores it locally to DataStore, Room, and DayManager.
     */
    suspend fun restoreDailyLimitFromCloud(): Result<Int?> = withContext(Dispatchers.IO) {
        try {
            if (!isEntitledForSync()) return@withContext Result.success(null)
            val cloudLimit = fetchDailyLimitFromCloud()
            if (cloudLimit != null && cloudLimit > 0) {
                // Restore locally into preferences
                preferencesRepository.setDefaultDailyLimit(cloudLimit)
                preferencesRepository.setDailyIntent(cloudLimit)

                // Restore active in-memory and database daily limit
                dayManagerLazy.get().restoreActiveDailyLimit(cloudLimit)
                Log.i("FirestoreSync", "Restored account-level daily limit ($cloudLimit) from cloud")
                Result.success(cloudLimit)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Failed to restore daily limit from cloud: ${e.message}", e)
            Result.failure(e)
        }
    }
}

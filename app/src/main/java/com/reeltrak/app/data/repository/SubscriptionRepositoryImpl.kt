package com.reeltrak.app.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.data.security.authorization.AccessMode
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.repository.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SubscriptionRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val subscriptionTimeAuthority: SubscriptionTimeAuthority,
    private val httpClient: OkHttpClient
) : SubscriptionRepository {

    private val TAG = "SubscriptionRepo"
    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL
    internal var lastServerSyncTimestampMs: Long = 0L


    /**
     * Flow tracking Firebase Auth state transitions (login/logout/user changes).
     */
    private val authStateFlow: Flow<String?> = callbackFlow {
        val auth = Firebase.auth
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            Log.d("SUB_DEBUG_REPOSITORY", "authStateFlow listener triggered: UID=$uid")
            Log.d("STARTUP_DEBUG", "SubscriptionRepo: authStateFlow listener triggered: UID=$uid")
            trySend(uid)
        }
        auth.addAuthStateListener(listener)
        val initialUid = auth.currentUser?.uid
        Log.d("SUB_DEBUG_REPOSITORY", "authStateFlow initial UID=$initialUid")
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: authStateFlow initial UID=$initialUid")
        trySend(initialUid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    /**
     * Safe subscription state flow providing Loading, Success, and Error representations.
     */
    override val subscriptionState: Flow<SubscriptionState> = authStateFlow.flatMapLatest { uid ->
        Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState flatMapLatest switching for UID=$uid")
        if (uid.isNullOrBlank()) {
            Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState emitting LOCKED_DEFAULT because UID is blank")
            flowOf<SubscriptionState>(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
        } else {
            callbackFlow<SubscriptionState> {
                Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState emitting Loading for UID=$uid")
                trySend(SubscriptionState.Loading)

                val docRef = firestore.collection("users")
                    .document(uid)
                    .collection("subscription")
                    .document("status")

                val registration = docRef.addSnapshotListener { snapshot: DocumentSnapshot?, error ->
                    if (error != null) {
                        Log.w("SUB_DEBUG_REPOSITORY", "SnapshotListener failed for user: $uid", error)
                        trySend(SubscriptionState.Error(error.localizedMessage ?: "Failed to load subscription"))
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        try {
                            val dto = CloudSubscriptionDto.fromSnapshot(snapshot)
                            val isFromCache = snapshot.metadata.isFromCache
                            
                            // If snapshot is from server, record server sync timestamp
                            if (!isFromCache) {
                                val serverTimeFromDoc = dto.updatedAt?.toDate()?.time ?: 0L
                                if (serverTimeFromDoc > 0L) {
                                    subscriptionTimeAuthority.recordServerTimeAnchor(serverTimeFromDoc)
                                }
                                lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                            }

                            val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
                            val isLeaseExpired = if (isFromCache) {
                                !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)
                            } else false

                            val info = dto.toDomain(
                                authoritativeNow = authoritativeNow,
                                isOfflineLeaseExpired = isLeaseExpired,
                                lastServerVerificationMs = lastServerSyncTimestampMs
                            )
                            if (com.reeltrak.app.BuildConfig.DEBUG) {
                                val serverTimeFromDoc = dto.updatedAt?.toDate()?.time ?: 0L
                                Log.d("ENTITLEMENT", """
                                    [ENTITLEMENT]
                                    UID: $uid
                                    ServerTime: $serverTimeFromDoc
                                    SubscriptionStatus: ${dto.subscriptionStatus}
                                    TrialStart: ${dto.trialStart ?: dto.trialStartDate}
                                    TrialEnd: ${dto.trialEnd ?: dto.trialEndDate}
                                    CurrentPeriodEnd: ${dto.currentPeriodEnd}
                                    AuthoritativeNow: $authoritativeNow
                                    ComputedState: ${info.accessState}
                                    SourceSnapshot: ${if (isFromCache) "CACHE" else "SERVER"}
                                    ReconciledStatus: ${info.status}
                                """.trimIndent())
                            }
                            Log.d("SUB_DEBUG_REPOSITORY", "SubscriptionState -> Success for UID=$uid (fromCache=$isFromCache): status=${info.status}, plan=${info.plan}, accessState=${info.accessState}, currentPeriodEnd=${info.currentPeriodEnd}, trialEnd=${info.trialEnd}, isUnlocked=${info.isUnlocked}")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: SubscriptionState -> Success for UID=$uid: accessState=${info.accessState}, status=${info.status}, isUnlocked=${info.isUnlocked}")
                            trySend(SubscriptionState.Success(info))
                        } catch (e: Exception) {
                            Log.e("SUB_DEBUG_REPOSITORY", "Error deserializing subscription for user: $uid", e)
                            trySend(SubscriptionState.Error(e.localizedMessage ?: "Invalid subscription format"))
                        }
                    } else {
                        val isFromCache = snapshot?.metadata?.isFromCache ?: false
                        if (isFromCache) {
                            Log.d("SUB_DEBUG_REPOSITORY", "Subscription document not present in cache for $uid; awaiting server fetch...")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: Document not in cache for $uid -> Loading")
                            trySend(SubscriptionState.Loading)
                        } else {
                            Log.d("SUB_DEBUG_REPOSITORY", "Subscription document confirmed absent on server for $uid -> LOCKED")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: Document confirmed absent on server for $uid -> LOCKED")
                            trySend(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
                        }
                    }
                }

                awaitClose { registration.remove() }
            }
        }
    }.distinctUntilChanged()

    /**
     * Observable subscription info mapped directly from the state flow.
     */
    override val subscriptionInfo: Flow<SubscriptionInfo> = subscriptionState.map { state ->
        when (state) {
            is SubscriptionState.Success -> state.info
            is SubscriptionState.Error -> state.fallbackInfo
            is SubscriptionState.Loading -> SubscriptionInfo.INITIALIZING_DEFAULT
        }
    }.distinctUntilChanged()

    /**
     * Real-time observable AccessState (TRIAL, ACTIVE, LOCKED).
     */
    override val accessState: Flow<AccessState> = subscriptionInfo.map { it.accessState }.distinctUntilChanged()

    /**
     * Observable boolean indicating if app is unlocked.
     */
    override val isAppUnlocked: Flow<Boolean> = subscriptionInfo.map { it.isUnlocked }.distinctUntilChanged()

    /**
     * Legacy alias for isAppUnlocked.
     */
    override val isPremium: Flow<Boolean> = isAppUnlocked

    override suspend fun ensureSubscription(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("Not logged in"))
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] ensureSubscription starting")
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: ensureSubscription() starting for UID=${user.uid}")
        try {
            val token = user.getIdToken(false).await().token ?: throw Exception("Failed to get ID token")
            val request = Request.Builder()
                .url("$workerBaseUrl/api/ensure-subscription")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            val tHttp = android.os.SystemClock.elapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] POST /api/ensure-subscription completed in ${tHttp - t0}ms, code=${response.code}")
            Log.d("STARTUP_DEBUG", "SubscriptionRepo: ensureSubscription() response code=${response.code}, success=${response.isSuccessful}")
            if (response.isSuccessful) {
                // Calibrate server time anchor directly from HTTP Date header to avoid extra /api/time roundtrip
                val serverDateHeader = response.headers.getDate("Date")?.time ?: 0L
                if (serverDateHeader > 0L) {
                    subscriptionTimeAuthority.recordServerTimeAnchor(serverDateHeader)
                    Log.d("SUB_PERF", "[SUB_PERF] Calibrated server time anchor from ensure-subscription Date header")
                } else if (!subscriptionTimeAuthority.isCalibrated.value) {
                    subscriptionTimeAuthority.syncWithServer()
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server returned ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure subscription", e)
            Log.e("STARTUP_DEBUG", "SubscriptionRepo: ensureSubscription() failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * One-shot fetch of the current subscription info using authoritative server time.
     */
    override suspend fun getSubscriptionInfoSync(): SubscriptionInfo = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid ?: return@withContext SubscriptionInfo.LOCKED_DEFAULT
        try {
            val snapshot = firestore.collection("users")
                .document(uid)
                .collection("subscription")
                .document("status")
                .get()
                .await()

            if (snapshot != null && snapshot.exists()) {
                val dto = CloudSubscriptionDto.fromSnapshot(snapshot)
                val isFromCache = snapshot.metadata.isFromCache
                if (!isFromCache) {
                    val docUpdatedAt = dto.updatedAt?.toDate()?.time ?: 0L
                    if (docUpdatedAt > 0L) {
                        subscriptionTimeAuthority.recordServerTimeAnchor(docUpdatedAt)
                    }
                    lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                }
                val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
                val isLeaseExpired = if (isFromCache) {
                    !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)
                } else false

                dto.toDomain(
                    authoritativeNow = authoritativeNow,
                    isOfflineLeaseExpired = isLeaseExpired,
                    lastServerVerificationMs = lastServerSyncTimestampMs
                )
            } else {
                SubscriptionInfo.LOCKED_DEFAULT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get subscription info synchronously", e)
            SubscriptionInfo.LOCKED_DEFAULT
        }
    }

    private var lastSyncElapsedRealtime: Long = 0L
    private val refreshMutex = Mutex()
    private var lastRefreshedInfo: SubscriptionInfo? = null

    /**
     * Explicitly forces a server fetch to refresh subscription data and re-anchor subscription time.
     */
    override suspend fun refreshSubscription(): Result<SubscriptionInfo> = refreshSubscription(forceServer = true)

    /**
     * Explicitly forces a server fetch to refresh subscription data and re-anchor subscription time.
     * Guarded by refreshMutex to deduplicate concurrent startup/lifecycle calls.
     * When [forceServer] is true, the offline lease has expired, or no server sync has occurred,
     * requests [Source.SERVER]. Otherwise queries [Source.CACHE] with fallback to [Source.SERVER].
     */
    override suspend fun refreshSubscription(forceServer: Boolean): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid
            ?: return@withContext Result.failure(Exception("User is not signed in"))

        refreshMutex.withLock {
            val currentElapsed = android.os.SystemClock.elapsedRealtime()
            if (!forceServer && lastSyncElapsedRealtime > 0L && (currentElapsed - lastSyncElapsedRealtime) < 2000L && lastRefreshedInfo != null) {
                Log.d("SUB_PERF", "[SUB_PERF] Reusing recent in-flight subscription sync result (elapsed: ${currentElapsed - lastSyncElapsedRealtime}ms)")
                Log.d(TAG, "Reusing recent in-flight subscription sync result (elapsed: ${currentElapsed - lastSyncElapsedRealtime}ms)")
                return@withContext Result.success(lastRefreshedInfo!!)
            }

            val t0 = android.os.SystemClock.elapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] refreshSubscription starting for server fetch (forceServer=$forceServer)")
            try {
                // Only sync with /api/time if the authority is not yet calibrated
                if (!subscriptionTimeAuthority.isCalibrated.value) {
                    val tTime = android.os.SystemClock.elapsedRealtime()
                    subscriptionTimeAuthority.syncWithServer()
                    Log.d("SUB_PERF", "[SUB_PERF] Cold time calibration via /api/time took ${android.os.SystemClock.elapsedRealtime() - tTime}ms")
                }

                val tFirestore = android.os.SystemClock.elapsedRealtime()
                val isLeaseExpiredOrUnset = lastServerSyncTimestampMs <= 0L || !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)
                val shouldQueryServer = forceServer || isLeaseExpiredOrUnset

                val snapshot = if (!shouldQueryServer) {
                    try {
                        val cached = firestore.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.CACHE)
                            .await()
                        if (cached != null && cached.exists()) {
                            cached
                        } else {
                            firestore.collection("users")
                                .document(uid)
                                .collection("subscription")
                                .document("status")
                                .get(Source.SERVER)
                                .await()
                        }
                    } catch (e: Exception) {
                        firestore.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.SERVER)
                            .await()
                    }
                } else {
                    try {
                        firestore.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.SERVER)
                            .await()
                    } catch (serverErr: Exception) {
                        // Fall back to cache on network failure so offline users within lease can still operate
                        try {
                            val cached = firestore.collection("users")
                                .document(uid)
                                .collection("subscription")
                                .document("status")
                                .get(Source.CACHE)
                                .await()
                            if (cached != null && cached.exists()) {
                                cached
                            } else {
                                throw serverErr
                            }
                        } catch (cacheErr: Exception) {
                            throw serverErr
                        }
                    }
                }
                Log.d("SUB_PERF", "[SUB_PERF] Firestore subscription get completed in ${android.os.SystemClock.elapsedRealtime() - tFirestore}ms (fromCache=${snapshot?.metadata?.isFromCache})")

                lastSyncElapsedRealtime = android.os.SystemClock.elapsedRealtime()

                val info = if (snapshot != null && snapshot.exists()) {
                    val dto = CloudSubscriptionDto.fromSnapshot(snapshot)
                    val isFromCache = snapshot.metadata.isFromCache

                    // BUG-SUB-01 FIX: Update lastServerSyncTimestampMs ONLY after confirmed server response, NEVER after cache read
                    if (!isFromCache) {
                        val docUpdatedAt = dto.updatedAt?.toDate()?.time ?: 0L
                        if (docUpdatedAt > 0L) {
                            subscriptionTimeAuthority.recordServerTimeAnchor(docUpdatedAt)
                        }
                        lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                    }

                    val (authoritativeNow, _) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
                    val isLeaseExpired = if (isFromCache) {
                        !subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)
                    } else {
                        false
                    }

                    dto.toDomain(
                        authoritativeNow = authoritativeNow,
                        isOfflineLeaseExpired = isLeaseExpired,
                        lastServerVerificationMs = lastServerSyncTimestampMs
                    )
                } else {
                    SubscriptionInfo.LOCKED_DEFAULT
                }
                lastRefreshedInfo = info
                Log.d("SUB_PERF", "[SUB_PERF] refreshSubscription completed in ${android.os.SystemClock.elapsedRealtime() - t0}ms, accessState=${info.accessState}")
                Log.d(TAG, "Refreshed subscription from server: $info")
                Result.success(info)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing subscription from server", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Cancels the active recurring subscription on the server at billing cycle end.
     */
    override suspend fun cancelSubscription(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in"))
        try {
            val token = user.getIdToken(false).await().token ?: throw Exception("Failed to get ID token")
            val request = Request.Builder()
                .url("$workerBaseUrl/api/cancel-subscription")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // Refresh subscription from server to update cancelAtPeriodEnd state immediately
                refreshSubscription(forceServer = true)
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string()
                val errorMsg = try {
                    org.json.JSONObject(errorBody ?: "{}").optString("error", "Cancellation failed")
                } catch (e: Exception) {
                    "Server returned ${response.code}"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel subscription", e)
            Result.failure(e)
        }
    }

    /**
     * Low-frequency sync that only hits the server if last sync was older than minIntervalMs.
     */
    override suspend fun syncIfStale(minIntervalMs: Long): Result<SubscriptionInfo>? = withContext(Dispatchers.IO) {
        val currentElapsed = android.os.SystemClock.elapsedRealtime()
        if (lastSyncElapsedRealtime > 0L && (currentElapsed - lastSyncElapsedRealtime) < minIntervalMs) {
            Log.d(TAG, "Skipping server subscription sync (not stale, elapsed: ${currentElapsed - lastSyncElapsedRealtime}ms < ${minIntervalMs}ms)")
            return@withContext null
        }
        return@withContext refreshSubscription(forceServer = true)
    }

    /**
     * Fetches an authoritative cryptographic authorization lease from the Cloudflare Worker.
     * Note: Server lease route is not deployed on the production Worker; the app relies authoritatively
     * on real-time Firestore subscription entitlement (/users/{uid}/subscription/status).
     */
    override suspend fun fetchAuthorizationLease(): Result<AuthorizationToken> = withContext(Dispatchers.IO) {
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: fetchAuthorizationLease() bypassed; app uses authoritative Firestore subscription state.")
        Result.failure(UnsupportedOperationException("Cryptographic lease endpoint disabled; authoritative Firestore subscription used."))
    }
}

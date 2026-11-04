package com.reeltrak.app.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.reeltrak.app.core.di.ApplicationScope
import com.reeltrak.app.data.remote.dto.CloudSubscriptionDto
import com.reeltrak.app.data.security.authorization.AccessMode
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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
    private val firestore: FirebaseFirestore?,
    private val subscriptionTimeAuthority: SubscriptionTimeAuthority,
    private val httpClient: OkHttpClient,
    @ApplicationScope externalScope: CoroutineScope
) : SubscriptionRepository {

    internal var repositoryScope: CoroutineScope = externalScope

    constructor(
        subscriptionTimeAuthority: SubscriptionTimeAuthority,
        httpClient: OkHttpClient,
        repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    ) : this(null, subscriptionTimeAuthority, httpClient, repositoryScope)

    constructor(
        firestore: FirebaseFirestore?,
        subscriptionTimeAuthority: SubscriptionTimeAuthority,
        httpClient: OkHttpClient
    ) : this(firestore, subscriptionTimeAuthority, httpClient, CoroutineScope(SupervisorJob() + Dispatchers.IO))

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
            Log.d("SUB_DEBUG_REPOSITORY", "authStateFlow listener triggered: hasUser=${!uid.isNullOrBlank()}")
            Log.d("STARTUP_DEBUG", "SubscriptionRepo: authStateFlow listener triggered: hasUser=${!uid.isNullOrBlank()}")
            trySend(uid)
        }
        auth.addAuthStateListener(listener)
        val initialUid = auth.currentUser?.uid
        Log.d("SUB_DEBUG_REPOSITORY", "authStateFlow initial hasUser=${!initialUid.isNullOrBlank()}")
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: authStateFlow initial hasUser=${!initialUid.isNullOrBlank()}")
        trySend(initialUid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    /**
     * Safe subscription state flow providing Loading, Success, and Error representations.
     */
    override val subscriptionState: Flow<SubscriptionState> = authStateFlow.flatMapLatest { uid ->
        Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState flatMapLatest switching (hasUser=${!uid.isNullOrBlank()})")
        // Reset in-memory cached subscription to prevent cross-account entitlement bleeding
        lastRefreshedInfo = null
        lastRefreshedUid = null
        lastSyncElapsedRealtime = 0L
        lastServerSyncTimestampMs = 0L

        if (uid.isNullOrBlank()) {
            Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState emitting LOCKED_DEFAULT because UID is blank")
            flowOf<SubscriptionState>(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
        } else {
            callbackFlow<SubscriptionState> {
                Log.d("SUB_DEBUG_REPOSITORY", "subscriptionState emitting Loading")
                trySend(SubscriptionState.Loading)

                val persistedSync = subscriptionTimeAuthority.getLastServerSyncTime(uid)
                if (persistedSync > 0L) {
                    lastServerSyncTimestampMs = persistedSync
                }

                val docRef = firestore?.collection("users")
                    ?.document(uid)
                    ?.collection("subscription")
                    ?.document("status")
                if (docRef == null) {
                    trySend(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
                    awaitClose { }
                    return@callbackFlow
                }

                val registration = docRef.addSnapshotListener { snapshot: DocumentSnapshot?, error ->
                    if (error != null) {
                        Log.w("SUB_DEBUG_REPOSITORY", "SnapshotListener failed for subscription document", error)
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
                                if (serverTimeFromDoc > 0L && !subscriptionTimeAuthority.isCalibrated.value) {
                                    subscriptionTimeAuthority.recordServerTimeAnchor(serverTimeFromDoc)
                                }
                                lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                                if (lastServerSyncTimestampMs <= 0L) {
                                    lastServerSyncTimestampMs = System.currentTimeMillis()
                                }
                                subscriptionTimeAuthority.recordLastServerSyncTime(uid, lastServerSyncTimestampMs)
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
                            if (!isFromCache) {
                                lastRefreshedInfo = info
                                lastRefreshedUid = uid
                                lastSyncElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                            }
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
                            Log.d("SUB_DEBUG_REPOSITORY", "SubscriptionState -> Success (fromCache=$isFromCache): status=${info.status}, plan=${info.plan}, accessState=${info.accessState}, currentPeriodEnd=${info.currentPeriodEnd}, trialEnd=${info.trialEnd}, isUnlocked=${info.isUnlocked}")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: SubscriptionState -> Success: accessState=${info.accessState}, status=${info.status}, isUnlocked=${info.isUnlocked}")
                            trySend(SubscriptionState.Success(info))
                        } catch (e: Exception) {
                            Log.e("SUB_DEBUG_REPOSITORY", "Error deserializing subscription", e)
                            trySend(SubscriptionState.Error(e.localizedMessage ?: "Invalid subscription format"))
                        }
                    } else {
                        val isFromCache = snapshot?.metadata?.isFromCache ?: false
                        if (isFromCache) {
                            Log.d("SUB_DEBUG_REPOSITORY", "Subscription document not present in cache; awaiting server fetch...")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: Document not in cache -> Loading")
                            trySend(SubscriptionState.Loading)
                        } else {
                            Log.d("SUB_DEBUG_REPOSITORY", "Subscription document confirmed absent on server -> LOCKED")
                            Log.d("STARTUP_DEBUG", "SubscriptionRepo: Document confirmed absent on server -> LOCKED")
                            trySend(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
                        }
                    }
                }

                awaitClose { registration.remove() }
            }
        }
    }.distinctUntilChanged()
    .shareIn(
        scope = repositoryScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0),
        replay = 1
    )

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

    private val inFlightEnsureJobs = mutableMapOf<String, CompletableDeferred<Result<Unit>>>()
    private val ensureJobsMutex = Mutex()

    override suspend fun ensureSubscription(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("Not logged in"))
        val targetUid = user.uid
        ensureSubscriptionForUser(targetUid) {
            val currentUser = Firebase.auth.currentUser
            if (currentUser == null || currentUser.uid != targetUid) {
                throw IllegalStateException("User account changed or logged out during token acquisition")
            }
            currentUser.getIdToken(false).await().token ?: throw Exception("Failed to get ID token")
        }
    }

    /**
     * Coalesces concurrent in-flight ensureSubscription calls for the same UID into a single flight.
     * Callers with identical UID and request inputs share the single leader HTTP request result.
     * Distinct UIDs operate on independent execution paths.
     * Cancellation of any individual caller coroutine does not abort the shared request for other callers.
     */
    internal suspend fun ensureSubscriptionForUser(
        uid: String,
        tokenProvider: suspend () -> String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (uid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("UID cannot be blank"))
        }

        val deferred = ensureJobsMutex.withLock {
            val existing = inFlightEnsureJobs[uid]
            if (existing != null) {
                Log.d("STARTUP_DEBUG", "SubscriptionRepo: Coalescing concurrent ensureSubscription call to in-flight request")
                Log.d("SUB_PERF", "[SUB_PERF] Coalescing concurrent ensureSubscription call")
                existing
            } else {
                val newDeferred = CompletableDeferred<Result<Unit>>()
                inFlightEnsureJobs[uid] = newDeferred

                repositoryScope.launch {
                    try {
                        val result = executeEnsureSubscriptionNetwork(uid, tokenProvider)
                        newDeferred.complete(result)
                    } catch (e: Exception) {
                        newDeferred.complete(Result.failure(e))
                    } catch (t: Throwable) {
                        newDeferred.completeExceptionally(t)
                    } finally {
                        withContext(NonCancellable) {
                            ensureJobsMutex.withLock {
                                inFlightEnsureJobs.remove(uid)
                            }
                        }
                    }
                }
                newDeferred
            }
        }

        return@withContext deferred.await()
    }

    private suspend fun executeEnsureSubscriptionNetwork(
        uid: String,
        tokenProvider: suspend () -> String
    ): Result<Unit> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] ensureSubscription starting")
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: ensureSubscription() starting")
        try {
            val token = tokenProvider()
            val request = Request.Builder()
                .url("$workerBaseUrl/api/ensure-subscription")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            val tHttp = android.os.SystemClock.elapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] POST /api/ensure-subscription completed in ${tHttp - t0}ms, code=${response.code}")
            Log.d("STARTUP_DEBUG", "SubscriptionRepo: ensureSubscription() response code=${response.code}, success=${response.isSuccessful}")
            return if (response.isSuccessful) {
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
            return Result.failure(e)
        }
    }

    /**
     * One-shot fetch of the current subscription info using authoritative server time.
     */
    override suspend fun getSubscriptionInfoSync(): SubscriptionInfo = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid ?: return@withContext SubscriptionInfo.LOCKED_DEFAULT
        val f = firestore ?: return@withContext SubscriptionInfo.LOCKED_DEFAULT
        try {
            val snapshot = f.collection("users")
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
                    if (docUpdatedAt > 0L && !subscriptionTimeAuthority.isCalibrated.value) {
                        subscriptionTimeAuthority.recordServerTimeAnchor(docUpdatedAt)
                    }
                    lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                    if (lastServerSyncTimestampMs <= 0L) {
                        lastServerSyncTimestampMs = System.currentTimeMillis()
                    }
                    subscriptionTimeAuthority.recordLastServerSyncTime(uid, lastServerSyncTimestampMs)
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
    private var lastRefreshedUid: String? = null

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
            val isRecentServerSyncFresh = lastRefreshedUid == uid &&
                lastRefreshedInfo != null &&
                lastSyncElapsedRealtime > 0L &&
                (currentElapsed - lastSyncElapsedRealtime) < 15_000L &&
                lastServerSyncTimestampMs > 0L &&
                subscriptionTimeAuthority.isWithinOfflineLease(lastServerSyncTimestampMs)

            if ((!forceServer && lastRefreshedUid == uid && lastSyncElapsedRealtime > 0L && (currentElapsed - lastSyncElapsedRealtime) < 2000L && lastRefreshedInfo != null) ||
                (isRecentServerSyncFresh)) {
                Log.d("SUB_PERF", "[SUB_PERF] Reusing fresh server-backed subscription sync result (elapsed: ${currentElapsed - lastSyncElapsedRealtime}ms)")
                Log.d(TAG, "Reusing fresh server-backed subscription sync result (elapsed: ${currentElapsed - lastSyncElapsedRealtime}ms)")
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

                val f = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore not configured"))
                val snapshot = if (!shouldQueryServer) {
                    try {
                        val cached = f.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.CACHE)
                            .await()
                        if (cached != null && cached.exists()) {
                            cached
                        } else {
                            f.collection("users")
                                .document(uid)
                                .collection("subscription")
                                .document("status")
                                .get(Source.SERVER)
                                .await()
                        }
                    } catch (e: Exception) {
                        f.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.SERVER)
                            .await()
                    }
                } else {
                    try {
                        f.collection("users")
                            .document(uid)
                            .collection("subscription")
                            .document("status")
                            .get(Source.SERVER)
                            .await()
                    } catch (serverErr: Exception) {
                        // Fall back to cache on network failure so offline users within lease can still operate
                        try {
                            val cached = f.collection("users")
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
                        if (docUpdatedAt > 0L && !subscriptionTimeAuthority.isCalibrated.value) {
                            subscriptionTimeAuthority.recordServerTimeAnchor(docUpdatedAt)
                        }
                        lastServerSyncTimestampMs = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime().first
                        if (lastServerSyncTimestampMs <= 0L) {
                            lastServerSyncTimestampMs = System.currentTimeMillis()
                        }
                        subscriptionTimeAuthority.recordLastServerSyncTime(uid, lastServerSyncTimestampMs)
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
                lastRefreshedUid = uid
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
     * Queries the backend worker to reconcile and restore active recurring subscriptions from Razorpay.
     */
    override suspend fun restoreSubscription(): Result<SubscriptionInfo> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in"))
        try {
            val token = user.getIdToken(false).await().token ?: throw Exception("Failed to get ID token")
            val request = Request.Builder()
                .url("$workerBaseUrl/api/restore-subscription")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val serverDateHeader = response.headers.getDate("Date")?.time ?: 0L
                if (serverDateHeader > 0L) {
                    subscriptionTimeAuthority.recordServerTimeAnchor(serverDateHeader)
                }
            } else {
                Log.w(TAG, "restoreSubscription backend call returned ${response.code}")
            }
            refreshSubscription(forceServer = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore subscription from backend, falling back to direct server refresh", e)
            refreshSubscription(forceServer = true)
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

    private val inFlightLeaseJobs = mutableMapOf<String, CompletableDeferred<Result<AuthorizationToken>>>()
    private val leaseJobsMutex = Mutex()

    /**
     * Fetches an authoritative cryptographic authorization lease from the Cloudflare Worker.
     * Uses single-flight deduplication to coalesce concurrent calls for the same UID.
     */
    override suspend fun fetchAuthorizationLease(): Result<AuthorizationToken> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("Not logged in"))
        val targetUid = user.uid
        fetchAuthorizationLeaseForUser(targetUid) {
            val currentUser = Firebase.auth.currentUser
            if (currentUser == null || currentUser.uid != targetUid) {
                throw IllegalStateException("User account changed or logged out during token acquisition")
            }
            currentUser.getIdToken(false).await().token ?: throw Exception("Failed to get ID token")
        }
    }

    /**
     * Coalesces concurrent in-flight fetchAuthorizationLease calls for the same UID into a single flight.
     * Distinct UIDs operate on independent execution paths.
     * Cancellation of any individual caller coroutine does not abort the shared request for other callers.
     */
    internal suspend fun fetchAuthorizationLeaseForUser(
        uid: String,
        tokenProvider: suspend () -> String
    ): Result<AuthorizationToken> = withContext(Dispatchers.IO) {
        if (uid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("UID cannot be blank"))
        }

        val deferred = leaseJobsMutex.withLock {
            val existing = inFlightLeaseJobs[uid]
            if (existing != null) {
                Log.d("STARTUP_DEBUG", "SubscriptionRepo: Coalescing concurrent fetchAuthorizationLease call to in-flight request")
                Log.d("SUB_PERF", "[SUB_PERF] Coalescing concurrent fetchAuthorizationLease call")
                existing
            } else {
                val newDeferred = CompletableDeferred<Result<AuthorizationToken>>()
                inFlightLeaseJobs[uid] = newDeferred

                repositoryScope.launch {
                    try {
                        val result = executeFetchAuthorizationLeaseNetwork(uid, tokenProvider)
                        newDeferred.complete(result)
                    } catch (e: Exception) {
                        newDeferred.complete(Result.failure(e))
                    } catch (t: Throwable) {
                        newDeferred.completeExceptionally(t)
                    } finally {
                        withContext(NonCancellable) {
                            leaseJobsMutex.withLock {
                                inFlightLeaseJobs.remove(uid)
                            }
                        }
                    }
                }
                newDeferred
            }
        }

        return@withContext deferred.await()
    }

    private suspend fun executeFetchAuthorizationLeaseNetwork(
        uid: String,
        tokenProvider: suspend () -> String
    ): Result<AuthorizationToken> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        Log.d("SUB_PERF", "[SUB_PERF] fetchAuthorizationLease starting")
        Log.d("STARTUP_DEBUG", "SubscriptionRepo: fetchAuthorizationLease() starting")
        try {
            val token = tokenProvider()
            val request = Request.Builder()
                .url("$workerBaseUrl/api/subscription/lease")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody())
                .build()

            val response = httpClient.newCall(request).execute()
            val tHttp = android.os.SystemClock.elapsedRealtime()
            Log.d("SUB_PERF", "[SUB_PERF] POST /api/subscription/lease completed in ${tHttp - t0}ms, code=${response.code}")
            Log.d("STARTUP_DEBUG", "SubscriptionRepo: fetchAuthorizationLease() response code=${response.code}, success=${response.isSuccessful}")

            // Calibrate server time anchor directly from HTTP Date header to avoid extra /api/time roundtrip
            val serverDateHeader = response.headers.getDate("Date")?.time ?: 0L
            if (serverDateHeader > 0L) {
                subscriptionTimeAuthority.recordServerTimeAnchor(serverDateHeader)
                Log.d("SUB_PERF", "[SUB_PERF] Calibrated server time anchor from subscription/lease Date header")
            } else if (!subscriptionTimeAuthority.isCalibrated.value) {
                subscriptionTimeAuthority.syncWithServer()
            }

            if (response.isSuccessful) {
                val bodyString = response.body?.string() ?: throw Exception("Empty lease response body")
                val jsonObject = com.google.gson.JsonParser.parseString(bodyString).asJsonObject
                val leaseUid = jsonObject.get("uid")?.asString ?: ""
                val planId = jsonObject.get("planId")?.asString ?: ""
                val accessModeStr = jsonObject.get("accessMode")?.asString ?: ""
                val accessMode = try { AccessMode.valueOf(accessModeStr) } catch (e: Exception) { AccessMode.ACTIVE }
                val issuedAtMs = jsonObject.get("issuedAtMs")?.asLong ?: 0L
                val expiresAtMs = jsonObject.get("expiresAtMs")?.asLong ?: 0L
                val leaseValidUntilMs = jsonObject.get("leaseValidUntilMs")?.asLong ?: 0L
                val integrityDigest = jsonObject.get("integrityDigest")?.asString ?: ""
                val leaseVersion = jsonObject.get("leaseVersion")?.asInt ?: 1
                val nonce = jsonObject.get("nonce")?.asString ?: ""
                val subIdElem = jsonObject.get("subscriptionId")
                val subscriptionId = if (subIdElem != null && !subIdElem.isJsonNull) subIdElem.asString.ifBlank { null } else null
                val signature = jsonObject.get("signature")?.asString ?: ""

                val authToken = AuthorizationToken(
                    uid = leaseUid,
                    planId = planId,
                    accessMode = accessMode,
                    issuedAtMs = issuedAtMs,
                    expiresAtMs = expiresAtMs,
                    leaseValidUntilMs = leaseValidUntilMs,
                    integrityDigest = integrityDigest,
                    leaseVersion = leaseVersion,
                    nonce = nonce,
                    subscriptionId = subscriptionId,
                    signature = signature
                )
                return Result.success(authToken)
            } else {
                val errorBody = response.body?.string()
                val errorMsg = try {
                    org.json.JSONObject(errorBody ?: "{}").optString("error", "Server returned ${response.code}")
                } catch (e: Exception) {
                    "Server returned ${response.code}"
                }
                return Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch authorization lease", e)
            Log.e("STARTUP_DEBUG", "SubscriptionRepo: fetchAuthorizationLease() failed: ${e.message}", e)
            return Result.failure(e)
        }
    }
}

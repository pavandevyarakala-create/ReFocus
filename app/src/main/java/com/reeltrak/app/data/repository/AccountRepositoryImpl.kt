package com.reeltrak.app.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.reeltrak.app.core.di.ApplicationScope
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import com.reeltrak.app.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val httpClient: okhttp3.OkHttpClient,
    @ApplicationScope externalScope: CoroutineScope
) : AccountRepository {

    internal var repositoryScope: CoroutineScope = externalScope

    constructor(
        firestore: FirebaseFirestore,
        httpClient: okhttp3.OkHttpClient
    ) : this(firestore, httpClient, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val TAG = "AccountRepo"

    @Volatile
    private var lastServerVerifiedInfo: AccountAccessInfo? = null
    @Volatile
    private var lastServerVerifiedUid: String? = null
    @Volatile
    private var lastServerVerifiedElapsedRealtime: Long = 0L

    /**
     * Flow tracking Firebase Auth state transitions (login/logout/user changes).
     */
    private val authStateFlow: Flow<String?> = callbackFlow {
        val auth = Firebase.auth
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.uid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    /**
     * Real-time observable master account access info.
     * Listens to Firestore at /users/{uid}/account/status.
     * Parses accountStatus and accountType (TESTER vs NORMAL).
     */
    override val accountAccessInfo: Flow<AccountAccessInfo> = authStateFlow.flatMapLatest { uid ->
        if (uid.isNullOrBlank()) {
            lastServerVerifiedInfo = null
            lastServerVerifiedUid = null
            lastServerVerifiedElapsedRealtime = 0L
            flowOf(AccountAccessInfo.DEFAULT)
        } else {
            callbackFlow<AccountAccessInfo> {
                val docRef = firestore.collection("users")
                    .document(uid)
                    .collection("account")
                    .document("status")

                val registration = docRef.addSnapshotListener { snapshot: DocumentSnapshot?, error ->
                    if (error != null) {
                        Log.w(TAG, "Account access info snapshot error for $uid: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val statusStr = snapshot.getString("accountStatus")
                        val typeStr = snapshot.getString("accountType")
                        val status = AccountStatus.fromString(statusStr)
                        val type = AccountType.fromString(typeStr)
                        val scheduledFor = try {
                            snapshot.getDate("deletionScheduledFor")?.time
                                ?: snapshot.getLong("deletionScheduledFor")
                        } catch (e: Exception) {
                            snapshot.getLong("deletionScheduledFor")
                        }
                        val requestedAt = try {
                            snapshot.getDate("deletionRequestedAt")?.time
                                ?: snapshot.getLong("deletionRequestedAt")
                        } catch (e: Exception) {
                            snapshot.getLong("deletionRequestedAt")
                        }
                        val info = AccountAccessInfo(
                            accountStatus = status,
                            accountType = type,
                            deletionScheduledFor = scheduledFor,
                            deletionRequestedAt = requestedAt,
                            isLoading = false
                        )
                        val isFromCache = snapshot.metadata.isFromCache
                        if (!isFromCache) {
                            lastServerVerifiedInfo = info
                            lastServerVerifiedUid = uid
                            lastServerVerifiedElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                        }
                        Log.d(TAG, "Real-time accountAccessInfo update for $uid: status=$status, type=$type, scheduledFor=$scheduledFor, isFromCache=$isFromCache")
                        trySend(info)
                    } else {
                        val isFromCache = snapshot?.metadata?.isFromCache ?: false
                        if (!isFromCache) {
                            lastServerVerifiedInfo = AccountAccessInfo.DEFAULT
                            lastServerVerifiedUid = uid
                            lastServerVerifiedElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                        }
                        // Default to ACTIVE and NORMAL when no record exists
                        trySend(AccountAccessInfo.DEFAULT)
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

    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL


    /**
     * Real-time observable master account access status.
     * Derived from accountAccessInfo for backward compatibility.
     */
    override val accountStatus: Flow<AccountStatus> = accountAccessInfo.map { it.accountStatus }.distinctUntilChanged()

    override suspend fun checkAccountAccessInfo(userId: String): Result<AccountAccessInfo> = withContext(Dispatchers.IO) {
        val currentElapsed = android.os.SystemClock.elapsedRealtime()
        if (lastServerVerifiedUid == userId && lastServerVerifiedInfo != null && (currentElapsed - lastServerVerifiedElapsedRealtime) < 15_000L) {
            Log.d(TAG, "Reusing fresh server-verified accountAccessInfo for $userId (elapsed: ${currentElapsed - lastServerVerifiedElapsedRealtime}ms)")
            return@withContext Result.success(lastServerVerifiedInfo!!)
        }

        try {
            val snapshot = withTimeout(15000L) {
                firestore.collection("users")
                    .document(userId)
                    .collection("account")
                    .document("status")
                    .get(Source.SERVER)
                    .await()
            }

            if (snapshot.exists()) {
                val statusStr = snapshot.getString("accountStatus")
                val typeStr = snapshot.getString("accountType")
                val status = AccountStatus.fromString(statusStr)
                val type = AccountType.fromString(typeStr)
                val scheduledFor = try {
                    snapshot.getDate("deletionScheduledFor")?.time
                        ?: snapshot.getLong("deletionScheduledFor")
                } catch (e: Exception) {
                    snapshot.getLong("deletionScheduledFor")
                }
                val requestedAt = try {
                    snapshot.getDate("deletionRequestedAt")?.time
                        ?: snapshot.getLong("deletionRequestedAt")
                } catch (e: Exception) {
                    snapshot.getLong("deletionRequestedAt")
                }
                val info = AccountAccessInfo(
                    accountStatus = status,
                    accountType = type,
                    deletionScheduledFor = scheduledFor,
                    deletionRequestedAt = requestedAt,
                    isLoading = false
                )
                val isFromCache = snapshot.metadata.isFromCache
                if (!isFromCache) {
                    lastServerVerifiedInfo = info
                    lastServerVerifiedUid = userId
                    lastServerVerifiedElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                }
                Result.success(info)
            } else {
                val isFromCache = snapshot.metadata.isFromCache
                if (!isFromCache) {
                    lastServerVerifiedInfo = AccountAccessInfo.DEFAULT
                    lastServerVerifiedUid = userId
                    lastServerVerifiedElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                }
                Result.success(AccountAccessInfo.DEFAULT)
            }
        } catch (e: Exception) {
            val projectId = try { firestore.app.options.projectId } catch (_: Exception) { "unknown" }
            val firestoreCode = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "N/A"
            val sanitizedUid = if (userId.length > 6) "${userId.take(3)}...${userId.takeLast(3)}" else "***"
            Log.e(TAG, "checkAccountAccessInfo failed: projectId=$projectId, firestoreCode=$firestoreCode, exception=[${e.javaClass.simpleName}] ${e.message}, uid=$sanitizedUid", e)
            Result.failure(e)
        }
    }

    override suspend fun checkAccountStatus(userId: String): Result<AccountStatus> = withContext(Dispatchers.IO) {
        checkAccountAccessInfo(userId).map { it.accountStatus }
    }

    override suspend fun requestAccountDeletion(): Result<Long> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("User not authenticated"))
        try {
            val token = user.getIdToken(false).await().token ?: throw Exception("Failed to get Firebase ID token")
            val request = okhttp3.Request.Builder()
                .url("$workerBaseUrl/api/account/request-deletion")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Server returned ${response.code}: $responseBody"))
            }

            val json = org.json.JSONObject(responseBody)
            val scheduledForMs = json.optLong("scheduledForMs", 0L).takeIf { it > 0 }
                ?: json.optString("scheduledFor").let { iso ->
                    if (iso.isNotBlank()) {
                        try {
                            java.time.Instant.parse(iso).toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }

            if (scheduledForMs != null && scheduledForMs > 0L) {
                Result.success(scheduledForMs)
            } else {
                Result.failure(Exception("Server did not return a valid deletion schedule timestamp"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request account deletion", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelAccountDeletion(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = Firebase.auth.currentUser ?: return@withContext Result.failure(Exception("User not authenticated"))
        try {
            val token = user.getIdToken(false).await().token ?: throw Exception("Failed to get Firebase ID token")
            val request = okhttp3.Request.Builder()
                .url("$workerBaseUrl/api/account/cancel-deletion")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Server returned ${response.code}: $responseBody"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel account deletion", e)
            Result.failure(e)
        }
    }
}

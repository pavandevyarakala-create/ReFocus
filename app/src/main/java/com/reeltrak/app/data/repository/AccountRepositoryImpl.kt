package com.reeltrak.app.data.repository

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import com.reeltrak.app.domain.repository.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val httpClient: okhttp3.OkHttpClient
) : AccountRepository {

    private val TAG = "AccountRepo"

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
                        Log.d(TAG, "Real-time accountAccessInfo update for $uid: status=$status, type=$type, scheduledFor=$scheduledFor")
                        trySend(info)
                    } else {
                        // Default to ACTIVE and NORMAL when no record exists
                        trySend(AccountAccessInfo.DEFAULT)
                    }
                }

                awaitClose { registration.remove() }
            }
        }
    }.distinctUntilChanged()

    private val workerBaseUrl = com.reeltrak.app.core.network.NetworkConstants.WORKER_BASE_URL


    /**
     * Real-time observable master account access status.
     * Derived from accountAccessInfo for backward compatibility.
     */
    override val accountStatus: Flow<AccountStatus> = accountAccessInfo.map { it.accountStatus }.distinctUntilChanged()

    override suspend fun checkAccountAccessInfo(userId: String): Result<AccountAccessInfo> = withContext(Dispatchers.IO) {
        try {
            val snapshot = withTimeout(5000L) {
                try {
                    firestore.collection("users")
                        .document(userId)
                        .collection("account")
                        .document("status")
                        .get(Source.CACHE)
                        .await()
                } catch (e: Exception) {
                    firestore.collection("users")
                        .document(userId)
                        .collection("account")
                        .document("status")
                        .get(Source.SERVER)
                        .await()
                }
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
                Result.success(
                    AccountAccessInfo(
                        accountStatus = status,
                        accountType = type,
                        deletionScheduledFor = scheduledFor,
                        deletionRequestedAt = requestedAt,
                        isLoading = false
                    )
                )
            } else {
                Result.success(AccountAccessInfo.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accountAccessInfo from Firestore for $userId", e)
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

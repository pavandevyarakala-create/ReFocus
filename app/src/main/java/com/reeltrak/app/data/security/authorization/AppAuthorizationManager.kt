package com.reeltrak.app.data.security.authorization

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central Local Authorization Authority for Re:Focus.
 *
 * Implements Asymmetric Server-Signed Authorization:
 * 1. Coordinates Authenticated Identity (UID).
 * 2. Coordinates App Integrity (Package & SHA-256 Release Certificate Digest).
 * 3. Ingests and independently verifies ECDSA P-256 / SHA-256 server-signed leases.
 * 4. Android holds PUBLIC KEY ONLY; zero symmetric secrets or private keys in the APK.
 * 5. Client cannot manufacture valid authorization tokens locally (God-Hook Defeat).
 * 6. Coordinates Monotonic Server-Anchored Time & 24-hour offline leases.
 */
@Singleton
open class AppAuthorizationManager @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionTimeAuthority: SubscriptionTimeAuthority,
    private val integrityManager: AppIntegrityManager,
    @ApplicationContext private val context: Context? = null
) {
    private val TAG = "AppAuthManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /**
         * Server Public Key (NIST P-256 / secp256r1 in X.509 SubjectPublicKeyInfo Base64 format).
         * Used to verify ECDSA P-256 signatures issued exclusively by the Cloudflare Worker.
         */
        const val SERVER_PUBLIC_KEY_X509_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFl+NKhWRqS0nCcACEs4fXL5yBdD7WgfTLll2QuHkAuKkv1KN3qSiRwAERJ0yv+ky8CUgPmUzSCyPDXgmxL2muw=="

        private const val PREFS_NAME = "refocus_auth_lease_cache"
        private const val KEY_CACHED_LEASE = "cached_server_lease_json"
    }

    /**
     * Optional provider for server public key. Defaults to [serverPublicKey] (derived from [SERVER_PUBLIC_KEY_X509_BASE64]).
     * Can be overridden for unit testing with a separate test-only keypair.
     */
    var serverPublicKeyOverride: PublicKey? = null

    private val effectivePublicKey: PublicKey
        get() = serverPublicKeyOverride ?: serverPublicKey

    private val serverPublicKey: PublicKey by lazy {
        val keyBytes = Base64.getDecoder().decode(SERVER_PUBLIC_KEY_X509_BASE64)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private val prefs by lazy {
        try {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    private val _authorizationState = MutableStateFlow<AuthorizationState>(AuthorizationState.Initializing)
    open val authorizationState: StateFlow<AuthorizationState> = _authorizationState.asStateFlow()

    @Volatile
    private var currentActiveToken: AuthorizationToken? = null

    @Volatile
    private var latestSubscriptionInfo: SubscriptionInfo? = null

    open val activeToken: AuthorizationToken?
        get() = currentActiveToken

    /**
     * Optional provider for current authenticated UID. Defaults to FirebaseAuth.
     * Can be overridden in unit tests.
     */
    var currentUidProvider: () -> String? = {
        try {
            Firebase.auth.currentUser?.uid
        } catch (e: Exception) {
            null
        }
    }


    init {
        // Attempt restoring unexpired server lease from secure local cache asynchronously off the main thread
        scope.launch {
            val restored = loadCachedLease()
            android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: async cached lease restored=$restored, activeToken=${currentActiveToken?.uid}")
        }

        scope.launch {
            subscriptionRepository.subscriptionInfo.collectLatest { info ->
                android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: subscriptionInfo collected: accessState=${info.accessState}, status=${info.status}, plan=${info.plan}")
                latestSubscriptionInfo = info
                recalculateAuthorizationState(info)
            }
        }
    }

    /**
     * Asynchronously requests a cryptographically signed authorization lease from the server.
     * Invoked when active account lacks a valid local lease or on entitlement state transition.
     */
    open fun launchServerLeaseFetch() {
        val uid = currentUidProvider()
        if (uid.isNullOrBlank()) {
            android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: launchServerLeaseFetch skipped (no authenticated user)")
            return
        }
        scope.launch {
            try {
                android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: launchServerLeaseFetch executing for UID=$uid")
                val result = subscriptionRepository.fetchAuthorizationLease()
                if (result.isSuccess) {
                    val token = result.getOrThrow()
                    updateServerLease(token)
                    android.util.Log.i("STARTUP_DEBUG", "AppAuthorizationManager: launchServerLeaseFetch successfully updated lease for UID=${token.uid}")
                } else {
                    android.util.Log.w("STARTUP_DEBUG", "AppAuthorizationManager: launchServerLeaseFetch failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch server lease: ${e.message}")
            }
        }
    }

    /**
     * Suspending call to refresh authorization lease from server and update local cache.
     */
    open suspend fun refreshLeaseFromServer(): Result<AuthorizationToken> {
        val result = subscriptionRepository.fetchAuthorizationLease()
        if (result.isSuccess) {
            val token = result.getOrThrow()
            updateServerLease(token)
        }
        return result
    }

    /**
     * Ingests an asymmetric server-signed authorization lease issued by the Cloudflare Worker.
     * Cryptographically verifies the signature against the server's public key before accepting.
     *
     * @return true if the lease is authentic, unexpired, and bound to the current UID/package.
     */
    @Synchronized
    open fun updateServerLease(lease: AuthorizationToken): Boolean {
        // First verify server signature against public key
        if (!verifyServerSignature(lease)) {
            Log.w(TAG, "Ingested server lease signature verification failed.")
            android.util.Log.w("STARTUP_DEBUG", "AppAuthorizationManager: updateServerLease signature verification failed.")
            return false
        }

        // Anchor authoritative time with the verified server-signed timestamp
        if (lease.issuedAtMs > 0L) {
            subscriptionTimeAuthority.recordServerTimeAnchor(lease.issuedAtMs)
        }

        val verified = verifyToken(lease)
        Log.d("SUB_DEBUG_AUTHORIZATION", "updateServerLease: leaseUID=${lease.uid}, accessMode=${lease.accessMode}, expiresAtMs=${lease.expiresAtMs}, validUntilMs=${lease.leaseValidUntilMs}, integrityDigest=${lease.integrityDigest}, verified=$verified")
        android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: updateServerLease verifyToken=$verified for UID=${lease.uid}")
        if (!verified) {
            Log.w(TAG, "Ingested server lease failed structural verification.")
            return false
        }

        currentActiveToken = lease
        persistLease(lease)
        val state = AuthorizationState.Authorized(lease)
        _authorizationState.value = state
        Log.i(TAG, "Valid server lease accepted for user ${lease.uid}, valid until ${lease.leaseValidUntilMs}")
        android.util.Log.i("STARTUP_DEBUG", "AppAuthorizationManager: Valid server lease accepted and active! UID=${lease.uid}, validUntil=${lease.leaseValidUntilMs}")
        return true
    }

    /**
     * Forces immediate recalculation of the authorization state against the latest authoritative context.
     */
    @Synchronized
    open fun refreshAuthorization(): AuthorizationState {
        return recalculateAuthorizationState(latestSubscriptionInfo)
    }

    /**
     * Authoritative calculation of operational tracking mode.
     * Prevents implicit null-token fallthrough by explicitly distinguishing:
     * - UNAUTHENTICATED_FREE (Local tracking allowed, cloud blocked)
     * - AUTHENTICATED_FREE (Local tracking allowed, cloud blocked)
     * - TRIAL (Local tracking + cloud sync allowed)
     * - PREMIUM (Local tracking + cloud sync allowed)
     * - LOCKED_OR_EXPIRED (Tampered, restricted, or blocked)
     */
    open fun getOperationalMode(info: SubscriptionInfo? = null): OperationalMode {
        if (!integrityManager.isAppIntegrityValid()) {
            return OperationalMode.LOCKED_OR_EXPIRED
        }

        val uid = currentUidProvider()
        if (uid.isNullOrBlank()) {
            return OperationalMode.UNAUTHENTICATED_FREE
        }

        val currentInfo = info ?: latestSubscriptionInfo

        if (currentInfo == null) {
            return OperationalMode.AUTHENTICATED_FREE
        }

        if (currentInfo.status == SubscriptionStatus.HALTED) {
            return OperationalMode.LOCKED_OR_EXPIRED
        }

        val (authoritativeNow, _) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
        val effectiveNow = if (authoritativeNow > 0L) authoritativeNow else System.currentTimeMillis()

        // 1. Premium Check
        val hasFuturePaid = currentInfo.currentPeriodEnd != null && currentInfo.currentPeriodEnd > 0L && effectiveNow < currentInfo.currentPeriodEnd
        if (currentInfo.accessState == AccessState.ACTIVE || (hasFuturePaid && currentInfo.status != SubscriptionStatus.HALTED)) {
            return OperationalMode.PREMIUM
        }

        // 2. Trial Check
        val hasFutureTrial = currentInfo.trialEnd != null && currentInfo.trialEnd > 0L && effectiveNow < currentInfo.trialEnd
        if (currentInfo.accessState == AccessState.TRIAL || (currentInfo.status == SubscriptionStatus.TRIAL && hasFutureTrial)) {
            return OperationalMode.TRIAL
        }

        // 3. Authenticated Free (expired trial or non-paying account)
        return OperationalMode.AUTHENTICATED_FREE
    }

    /**
     * Evaluates current authorization status.
     *
     * IMPORTANT: The client CANNOT mint tokens.
     * If there is no valid server-signed lease, or if the server lease has expired,
     * this method fails closed to Locked or Tampered, even if an attacker modifies
     * local SubscriptionInfo in memory.
     */
    @Synchronized
    open fun recalculateAuthorizationState(info: SubscriptionInfo?): AuthorizationState {
        // 1. Application Integrity Check
        if (!integrityManager.isAppIntegrityValid()) {
            val details = integrityManager.getTamperDetails() ?: "App signature or package verification failed"
            val state = AuthorizationState.Tampered(details)
            currentActiveToken = null
            _authorizationState.value = state
            Log.e(TAG, "Integrity check failed: $details")
            return state
        }

        // 2. Authenticated Account Check
        val uid = currentUidProvider()
        if (uid.isNullOrBlank()) {
            val state = AuthorizationState.Locked(AuthorizationLockReason.NO_AUTHENTICATED_ACCOUNT)
            currentActiveToken = null
            clearPersistedLease()
            _authorizationState.value = state
            Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState: No authenticated account -> state=$state")
            return state
        }

        // 3. Awaiting subscription info from server/cache: do NOT lock prematurely
        if (info == null || info.accessState == AccessState.INITIALIZING) {
            val currentState = _authorizationState.value
            val state = if (currentState is AuthorizationState.Authorized) {
                currentState
            } else {
                AuthorizationState.Initializing
            }
            _authorizationState.value = state
            val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
            Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [INITIALIZING]: UID=$uid, serverTime=$authoritativeNow (calibrated=$isCalibrated) -> state=$state")
            return state
        }

        // 4. Status Gate: Explicit server halt or expiration revokes immediately
        when (info.status) {
            SubscriptionStatus.HALTED -> {
                val state = AuthorizationState.Locked(AuthorizationLockReason.SUBSCRIPTION_HALTED)
                currentActiveToken = null
                clearPersistedLease()
                _authorizationState.value = state
                Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [HALTED]: UID=$uid -> state=$state")
                return state
            }
            SubscriptionStatus.EXPIRED -> {
                // Authoritative check: do not lock if trial or paid period is still active
                val (authoritativeNow, _) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
                val effectiveNow = if (authoritativeNow > 0L) authoritativeNow else System.currentTimeMillis()
                val hasFutureTrial = info.trialEnd != null && info.trialEnd > 0L && effectiveNow < info.trialEnd
                val hasFuturePaid = info.currentPeriodEnd != null && info.currentPeriodEnd > 0L && effectiveNow < info.currentPeriodEnd
                val isAccessActive = info.accessState == AccessState.ACTIVE || info.accessState == AccessState.TRIAL

                if (!hasFutureTrial && !hasFuturePaid && !isAccessActive) {
                    val state = AuthorizationState.Locked(AuthorizationLockReason.SUBSCRIPTION_EXPIRED)
                    currentActiveToken = null
                    clearPersistedLease()
                    _authorizationState.value = state
                    Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [EXPIRED]: UID=$uid -> state=$state")
                    return state
                } else {
                    Log.w("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState: Suppressing EXPIRED lock because trial/paid date is still in future (trialEnd=${info.trialEnd}, paidEnd=${info.currentPeriodEnd}, now=$effectiveNow, accessState=${info.accessState})")
                }
            }
            else -> {}
        }

        // 5. Server-Signed Lease Entitlement Check
        val activeLease = currentActiveToken ?: run {
            if (loadCachedLease()) currentActiveToken else null
        }
        if (activeLease != null && verifyToken(activeLease)) {
            val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
            val effectiveNow = if (authoritativeNow > 0L) authoritativeNow else System.currentTimeMillis()

            // Check if within final sync grace window (post period expiration)
            val finalSyncGraceWindowMs = SubscriptionTimeAuthority.FINAL_SYNC_GRACE_WINDOW_MS
            val isWithinGrace = activeLease.expiresAtMs in (effectiveNow - finalSyncGraceWindowMs)..effectiveNow

            if (isWithinGrace) {
                currentActiveToken = null
                val state = AuthorizationState.FinalSyncGrace(activeLease)
                _authorizationState.value = state
                Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [GRACE]: UID=$uid, serverTime=$effectiveNow (calibrated=$isCalibrated) -> state=$state")
                return state
            }

            val state = AuthorizationState.Authorized(activeLease)
            _authorizationState.value = state
            Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [AUTHORIZED]: UID=$uid, serverTime=$effectiveNow (calibrated=$isCalibrated), leaseExpiry=${activeLease.expiresAtMs} -> state=$state")
            return state
        } else {
            // No valid server-signed lease available
            currentActiveToken = null
            val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
            val effectiveNow = if (authoritativeNow > 0L) authoritativeNow else System.currentTimeMillis()

            val lockReason = when (info.accessState) {
                AccessState.ACTIVE -> {
                    if (info.currentPeriodEnd != null && effectiveNow >= info.currentPeriodEnd) {
                        AuthorizationLockReason.SUBSCRIPTION_EXPIRED
                    } else {
                        AuthorizationLockReason.NO_SERVER_LEASE
                    }
                }
                AccessState.TRIAL -> {
                    if (info.trialEnd != null && effectiveNow >= info.trialEnd) {
                        AuthorizationLockReason.TRIAL_EXPIRED
                    } else {
                        AuthorizationLockReason.NO_SERVER_LEASE
                    }
                }
                AccessState.INITIALIZING -> AuthorizationLockReason.NO_SERVER_LEASE
                AccessState.FINAL_SYNC_GRACE, AccessState.LOCKED -> {
                    val hasFuturePaid = info.currentPeriodEnd != null && info.currentPeriodEnd > 0L && effectiveNow < info.currentPeriodEnd
                    val hasFutureTrial = info.trialEnd != null && info.trialEnd > 0L && effectiveNow < info.trialEnd
                    if (info.status == SubscriptionStatus.HALTED) {
                        AuthorizationLockReason.SUBSCRIPTION_HALTED
                    } else if (info.currentPeriodEnd != null && effectiveNow >= info.currentPeriodEnd) {
                        AuthorizationLockReason.SUBSCRIPTION_EXPIRED
                    } else if (info.trialEnd != null && effectiveNow >= info.trialEnd) {
                        AuthorizationLockReason.TRIAL_EXPIRED
                    } else if (info.status == SubscriptionStatus.EXPIRED && !hasFuturePaid && !hasFutureTrial) {
                        AuthorizationLockReason.SUBSCRIPTION_EXPIRED
                    } else {
                        AuthorizationLockReason.NO_SERVER_LEASE
                    }
                }
            }
            val state = AuthorizationState.Locked(lockReason)
            _authorizationState.value = state
            Log.d("SUB_DEBUG_AUTHORIZATION", "recalculateAuthorizationState [NO_LEASE]: UID=$uid, infoAccessState=${info.accessState}, status=${info.status}, periodEnd=${info.currentPeriodEnd}, trialEnd=${info.trialEnd}, serverTime=$effectiveNow (calibrated=$isCalibrated) -> state=$state")
            android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: recalculateAuthorizationState [NO_LEASE]: UID=$uid, lockReason=$lockReason, accessState=${info.accessState}")
            return state
        }
    }

    /**
     * Independently validates any supplied authorization token against the server's public key
     * and the current runtime context.
     *
     * Used by ReelTrackerService, SessionManager, and DayManager at execution boundaries.
     */
    open fun verifyToken(token: AuthorizationToken?): Boolean {
        if (token == null) {
            android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: verifyToken - token is NULL")
            return false
        }

        // Check app integrity
        if (!integrityManager.isAppIntegrityValid()) {
            android.util.Log.w("STARTUP_DEBUG", "AppAuthorizationManager: verifyToken - app integrity invalid")
            return false
        }

        val (authoritativeNow, _) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
        val nowMs = if (authoritativeNow > 0L) authoritativeNow else System.currentTimeMillis()
        val currentUid = currentUidProvider()
        val currentDigest = integrityManager.getIntegrityDigest()

        val isValid = token.isValid(
            nowMs = nowMs,
            currentUid = currentUid,
            currentIntegrityDigest = currentDigest,
            signatureVerifier = { candidate -> verifyServerSignature(candidate) }
        )
        android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: verifyToken=$isValid (uid=${token.uid}, expiresAtMs=${token.expiresAtMs}, nowMs=$nowMs)")
        return isValid
    }

    /**
     * Explicitly revokes active authorization (e.g. on logout or account switch).
     */
    @Synchronized
    open fun revokeAuthorization(reason: AuthorizationLockReason) {
        currentActiveToken = null
        clearPersistedLease()
        _authorizationState.value = AuthorizationState.Locked(reason)
        Log.i(TAG, "Authorization explicitly revoked. Reason: $reason")
    }

    /**
     * Verifies the cryptographic ECDSA P-256 signature using the server's public key.
     */
    open fun verifyServerSignature(token: AuthorizationToken): Boolean {
        if (token.signature.isBlank()) return false
        return try {
            val derBytes = Base64.getDecoder().decode(token.signature)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(effectivePublicKey)
            verifier.update(token.computePayload().toByteArray(Charsets.UTF_8))
            verifier.verify(derBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Server signature verification failed", e)
            false
        }
    }

    @Synchronized
    open fun loadCachedLease(): Boolean {
        try {
            val rawStr = prefs?.getString(KEY_CACHED_LEASE, null)
            if (rawStr == null) {
                android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: loadCachedLease - No cached lease in SharedPreferences")
                return false
            }
            val jsonStr = AndroidKeystoreLeaseCrypto.decrypt(rawStr)
            if (jsonStr.isBlank()) {
                android.util.Log.w("STARTUP_DEBUG", "AppAuthorizationManager: loadCachedLease - Decrypted lease json is blank")
                return false
            }
            val jsonObject = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            val lease = AuthorizationToken(
                uid = jsonObject.get("uid")?.asString ?: "",
                planId = jsonObject.get("planId")?.asString ?: "",
                accessMode = try { AccessMode.valueOf(jsonObject.get("accessMode")?.asString ?: "") } catch (e: Exception) { AccessMode.ACTIVE },
                issuedAtMs = jsonObject.get("issuedAtMs")?.asLong ?: 0L,
                expiresAtMs = jsonObject.get("expiresAtMs")?.asLong ?: 0L,
                leaseValidUntilMs = jsonObject.get("leaseValidUntilMs")?.asLong ?: 0L,
                integrityDigest = jsonObject.get("integrityDigest")?.asString ?: "",
                leaseVersion = jsonObject.get("leaseVersion")?.asInt ?: 1,
                nonce = jsonObject.get("nonce")?.asString ?: "",
                subscriptionId = jsonObject.get("subscriptionId")?.let { if (!it.isJsonNull) it.asString.ifBlank { null } else null },
                signature = jsonObject.get("signature")?.asString ?: ""
            )
            val verified = verifyToken(lease)
            Log.d("SUB_DEBUG_AUTHORIZATION", "loadCachedLease: cachedUID=${lease.uid}, accessMode=${lease.accessMode}, expiresAtMs=${lease.expiresAtMs}, validUntilMs=${lease.leaseValidUntilMs}, verified=$verified")
            android.util.Log.d("STARTUP_DEBUG", "AppAuthorizationManager: loadCachedLease verified=$verified for UID=${lease.uid}")
            if (verified) {
                currentActiveToken = lease
                _authorizationState.value = AuthorizationState.Authorized(lease)
                Log.d(TAG, "Restored unexpired server lease from cache for user ${lease.uid}")
                android.util.Log.i("STARTUP_DEBUG", "AppAuthorizationManager: Successfully restored valid cached lease for user ${lease.uid}!")
                return true
            } else {
                val (authoritativeNow, isCalibrated) = subscriptionTimeAuthority.getAuthoritativeSubscriptionTime()
                if (isCalibrated || authoritativeNow > 0L) {
                    clearPersistedLease()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached server lease", e)
            android.util.Log.w("STARTUP_DEBUG", "AppAuthorizationManager: Failed to load cached server lease: ${e.message}")
        }
        return false
    }

    private fun persistLease(lease: AuthorizationToken) {
        try {
            val json = org.json.JSONObject().apply {
                put("uid", lease.uid)
                put("planId", lease.planId)
                put("accessMode", lease.accessMode.name)
                put("issuedAtMs", lease.issuedAtMs)
                put("expiresAtMs", lease.expiresAtMs)
                put("leaseValidUntilMs", lease.leaseValidUntilMs)
                put("integrityDigest", lease.integrityDigest)
                put("leaseVersion", lease.leaseVersion)
                put("nonce", lease.nonce)
                put("subscriptionId", lease.subscriptionId ?: "")
                put("signature", lease.signature)
            }
            val encryptedPayload = AndroidKeystoreLeaseCrypto.encrypt(json.toString())
            prefs?.edit()?.putString(KEY_CACHED_LEASE, encryptedPayload)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist server lease to cache", e)
        }
    }

    @Synchronized
    open fun clearPersistedLease() {
        try {
            prefs?.edit()?.remove(KEY_CACHED_LEASE)?.apply()
            Log.d("SUB_DEBUG_AUTHORIZATION", "clearPersistedLease executed")
        } catch (e: Exception) {
            // Ignore cache clear error
        }
    }
}

/**
 * Keystore-backed AES-256-GCM encryption helper for cached authorization leases.
 * Protects cached leases at rest using hardware-backed AndroidKeyStore master key.
 * Transparently falls back to private SharedPreferences if AndroidKeyStore is unavailable (e.g. JVM unit tests).
 */
internal object AndroidKeystoreLeaseCrypto {
    private const val KEY_ALIAS = "refocus_auth_lease_keystore_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    fun encrypt(plaintext: String): String {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            "enc:" + Base64.getEncoder().encodeToString(combined)
        } catch (t: Throwable) {
            // Fall back to plaintext if AndroidKeyStore is unavailable (e.g. JVM unit test)
            plaintext
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith("enc:")) {
            // Plaintext JSON format (backward compatibility or test mode)
            return stored
        }
        return try {
            val combined = Base64.getDecoder().decode(stored.removePrefix("enc:"))
            if (combined.size <= IV_LENGTH_BYTES) return ""
            val iv = ByteArray(IV_LENGTH_BYTES)
            val ciphertext = ByteArray(combined.size - IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES)
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("AppAuthManager", "Keystore lease decryption failed: ${t.message}")
            ""
        }
    }
}

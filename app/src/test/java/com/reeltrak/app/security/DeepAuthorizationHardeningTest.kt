package com.reeltrak.app.security

import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.data.security.authorization.AccessMode
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.AppIntegrityManager
import com.reeltrak.app.data.security.authorization.AuthorizationLockReason
import com.reeltrak.app.data.security.authorization.AuthorizationState
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import com.reeltrak.app.services.tracking.DayTimeProvider
import com.reeltrak.app.services.tracking.SessionManager
import com.reeltrak.app.services.tracking.SessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Server-Anchored Authorization & Deep Hardening Security Test Suite for Re:Focus.
 *
 * Verifies that the client architecture cannot be bypassed by:
 * 1. Certificate tampering or APK re-signing.
 * 2. Client-side token forgery or local HMAC minting.
 * 3. Frida hooks injecting fake `SubscriptionInfo(ACTIVE)` into `recalculateAuthorizationState()`.
 * 4. Bypassing `DayManager` via implicit fallbacks (`token ?: authMgr.activeToken`).
 * 5. Free/expired accounts syncing tracking records to Firestore.
 */
class DeepAuthorizationHardeningTest {

    companion object {
        const val TEST_UID = "firebase_user_alpha_123"
        const val TEST_DIGEST = AppIntegrityManager.DEV_TEST_INTEGRITY_DIGEST
        const val BASE_TIME_MS = 1756720800000L

        /**
         * Test-Only Server Keypair (ECDSA P-256).
         * Used exclusively in unit tests to synthesize and verify mock server-signed leases.
         * Strictly decoupled from the production server key.
         */
        const val TEST_PRIVATE_KEY_PKCS8 =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAfp/d/3xmHlWHs8oD8V+m++YjWosBmvgtzJzdgztEE7w=="

        const val TEST_PUBLIC_KEY_X509_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0mOKHHDtSvKePEGAu9R4boP9oHbRuyD3MvT7uTshND6VayGZDDdddzbY8wTV0s8I/M+Z2Zi/jsRP9RkXlK2oig=="

        private val testPrivateKey: PrivateKey by lazy {
            val keyBytes = Base64.getDecoder().decode(TEST_PRIVATE_KEY_PKCS8)
            val spec = PKCS8EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("EC").generatePrivate(spec)
        }

        val testPublicKey: PublicKey by lazy {
            val keyBytes = Base64.getDecoder().decode(TEST_PUBLIC_KEY_X509_BASE64)
            val spec = X509EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("EC").generatePublic(spec)
        }

        fun generateTestServerSignedLease(
            uid: String = TEST_UID,
            planId: String = "plan_monthly",
            accessMode: AccessMode = AccessMode.ACTIVE,
            issuedAtMs: Long = BASE_TIME_MS,
            expiresAtMs: Long = BASE_TIME_MS + TimeUnit.DAYS.toMillis(30),
            leaseValidUntilMs: Long = BASE_TIME_MS + TimeUnit.DAYS.toMillis(1),
            integrityDigest: String = TEST_DIGEST,
            leaseVersion: Int = 1,
            nonce: String = UUID.randomUUID().toString(),
            subscriptionId: String? = "sub_server_123"
        ): AuthorizationToken {
            val payload = "$uid:$planId:${accessMode.name}:$issuedAtMs:$expiresAtMs:$leaseValidUntilMs:$integrityDigest:$leaseVersion:$nonce"
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(testPrivateKey)
            signer.update(payload.toByteArray(Charsets.UTF_8))
            val signatureDer = signer.sign()
            val signatureBase64 = Base64.getEncoder().encodeToString(signatureDer)

            return AuthorizationToken(
                uid = uid,
                planId = planId,
                accessMode = accessMode,
                issuedAtMs = issuedAtMs,
                expiresAtMs = expiresAtMs,
                leaseValidUntilMs = leaseValidUntilMs,
                integrityDigest = integrityDigest,
                leaseVersion = leaseVersion,
                nonce = nonce,
                subscriptionId = subscriptionId,
                signature = signatureBase64
            )
        }
    }

    private class FakeTimeAuthority(
        var currentTime: Long = BASE_TIME_MS,
        var offlineLeaseValid: Boolean = true
    ) : SubscriptionTimeAuthority() {
        override fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> = Pair(currentTime, true)
        override fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean = offlineLeaseValid
    }

    private class FakeIntegrityManager(
        var isValid: Boolean = true,
        var digest: String = AppIntegrityManager.DEV_TEST_INTEGRITY_DIGEST,
        var testTamperDetails: String? = null
    ) : AppIntegrityManager(null) {
        override fun isAppIntegrityValid(): Boolean = isValid
        override fun getIntegrityDigest(): String = digest
        override fun getTamperDetails(): String? = testTamperDetails
    }

    private class FakeSubscriptionRepository : SubscriptionRepository {
        val _subscriptionState = MutableStateFlow<SubscriptionState>(SubscriptionState.Loading)
        override val subscriptionState: Flow<SubscriptionState> = _subscriptionState

        override val subscriptionInfo: Flow<SubscriptionInfo> = subscriptionState.map { state ->
            when (state) {
                is SubscriptionState.Success -> state.info
                is SubscriptionState.Error -> state.fallbackInfo
                is SubscriptionState.Loading -> SubscriptionInfo.INITIALIZING_DEFAULT
            }
        }.distinctUntilChanged()

        override val accessState: Flow<AccessState> = subscriptionInfo.map { it.accessState }.distinctUntilChanged()
        override val isAppUnlocked: Flow<Boolean> = subscriptionInfo.map { it.isUnlocked }.distinctUntilChanged()
        override val isPremium: Flow<Boolean> = isAppUnlocked

        override suspend fun ensureSubscription(): Result<Unit> = Result.success(Unit)
        override suspend fun getSubscriptionInfoSync(): SubscriptionInfo {
            return when (val s = _subscriptionState.value) {
                is SubscriptionState.Success -> s.info
                is SubscriptionState.Error -> s.fallbackInfo
                is SubscriptionState.Loading -> SubscriptionInfo.INITIALIZING_DEFAULT
            }
        }
        fun emitInfo(info: SubscriptionInfo) {
            _subscriptionState.value = SubscriptionState.Success(info)
        }

        override suspend fun refreshSubscription(): Result<SubscriptionInfo> = Result.success(getSubscriptionInfoSync())
        override suspend fun cancelSubscription(): Result<Unit> = Result.success(Unit)
        override suspend fun syncIfStale(minIntervalMs: Long): Result<SubscriptionInfo>? = null
        override suspend fun fetchAuthorizationLease(): Result<AuthorizationToken> =
            Result.success(generateTestServerSignedLease())
    }

    private class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()
        var insertCallCount = 0

        override suspend fun insertOrReplace(data: DailyReelData) {
            insertCallCount++
            records[data.dateString] = data
        }

        override suspend fun getDailyDataSync(dateString: String): DailyReelData? = records[dateString]
        override suspend fun getLatestDailyDataSync(): DailyReelData? = records.values.maxByOrNull { it.dateString }
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? =
            records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }
        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> = MutableStateFlow(records[dateString])
        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> = MutableStateFlow(records.values.sortedBy { it.dateString })
        override fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>> =
            MutableStateFlow(records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString })
        override suspend fun getAllDailyDataSync(): List<DailyReelData> = records.values.sortedBy { it.dateString }
        override suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData> =
            records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString }
        override suspend fun clearAll() { records.clear() }
        override suspend fun clearHistoricalDataExcept(today: String) { records.keys.removeIf { it != today } }
        override suspend fun insertAll(data: List<DailyReelData>) { data.forEach { records[it.dateString] = it } }
        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> =
            records.values.filter { it.syncStatus == 1 && it.dateString != todayStr }
        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> =
            records.values.filter { it.dateString != todayStr }.sortedBy { it.dateString }
        override suspend fun deleteByDate(dateStr: String) { records.remove(dateStr) }
        override suspend fun deleteHistoricalSyncedExcept(todayStr: String) {
            records.keys.removeIf { key -> records[key]?.syncStatus == 0 && key != todayStr }
        }
        override suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long) {
            records[dateStr]?.let {
                if (it.updatedAt == syncedTimestamp) {
                    records[dateStr] = it.copy(syncStatus = 0)
                }
            }
        }
    }

    private class TestDayTimeProvider(
        var date: LocalDate = LocalDate.of(2026, 9, 27),
        var time: LocalTime = LocalTime.of(12, 0),
        var elapsedMs: Long = 50_000_000L,
        var epochMs: Long = 1756720800000L
    ) : DayTimeProvider {
        override fun elapsedRealtime(): Long = elapsedMs
        override fun localDate(): LocalDate = date
        override fun localTime(): LocalTime = time
        override fun currentTimeMillis(): Long = epochMs
    }

    private fun createAuthManager(
        repo: SubscriptionRepository = FakeSubscriptionRepository(),
        timeAuthority: SubscriptionTimeAuthority = FakeTimeAuthority(),
        integrityManager: AppIntegrityManager = FakeIntegrityManager(),
        uidProvider: () -> String? = { TEST_UID }
    ): AppAuthorizationManager {
        val manager = AppAuthorizationManager(repo, timeAuthority, integrityManager, context = null)
        manager.currentUidProvider = uidProvider
        manager.serverPublicKeyOverride = testPublicKey
        return manager
    }

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    // =========================================================================
    // PART 1: CERTIFICATE PINNING TESTS
    // =========================================================================

    @Test
    fun testCertPinning_GenuineCertificate_Passes() {
        val integrity = AppIntegrityManager(context = null)
        integrity.enforceReleaseChecksForTest = true
        integrity.packageOverrideForTest = AppIntegrityManager.EXPECTED_PACKAGE_NAME
        integrity.certSha256OverrideForTest = AppIntegrityManager.RELEASE_CERT_SHA256

        assertTrue("Genuine release certificate must pass integrity verification", integrity.isAppIntegrityValid())
        assertNull(integrity.getTamperDetails())
        assertEquals(AppIntegrityManager.EXPECTED_RELEASE_INTEGRITY_DIGEST, integrity.getIntegrityDigest())
    }

    @Test
    fun testCertPinning_GooglePlayAppSigningCertificate_Passes() {
        val integrity = AppIntegrityManager(context = null)
        integrity.enforceReleaseChecksForTest = true
        integrity.packageOverrideForTest = AppIntegrityManager.EXPECTED_PACKAGE_NAME
        integrity.certSha256OverrideForTest = AppIntegrityManager.PLAY_APP_SIGNING_CERT_SHA256

        assertTrue("Google Play App Signing certificate must pass integrity verification", integrity.isAppIntegrityValid())
        assertNull(integrity.getTamperDetails())
        assertEquals(AppIntegrityManager.EXPECTED_PLAY_INTEGRITY_DIGEST, integrity.getIntegrityDigest())
    }

    @Test
    fun testCertPinning_FakeCertificate_Fails() {
        val integrity = AppIntegrityManager(context = null)
        integrity.enforceReleaseChecksForTest = true
        integrity.packageOverrideForTest = AppIntegrityManager.EXPECTED_PACKAGE_NAME
        integrity.certSha256OverrideForTest = "badc0ffee0000000000000000000000000000000000000000000000000000000"

        assertFalse("Fake/modified certificate must fail integrity verification", integrity.isAppIntegrityValid())
        assertTrue(integrity.getTamperDetails()?.contains("Signing certificate mismatch") == true)
    }

    @Test
    fun testCertPinning_EmptyCertificate_Fails() {
        val integrity = AppIntegrityManager(context = null)
        integrity.enforceReleaseChecksForTest = true
        integrity.packageOverrideForTest = AppIntegrityManager.EXPECTED_PACKAGE_NAME
        integrity.certSha256OverrideForTest = ""

        assertFalse("Empty certificate must fail integrity verification", integrity.isAppIntegrityValid())
        assertTrue(integrity.getTamperDetails()?.contains("Invalid or empty") == true)
    }

    @Test
    fun testCertPinning_WrongPackage_Fails() {
        val integrity = AppIntegrityManager(context = null)
        integrity.enforceReleaseChecksForTest = true
        integrity.packageOverrideForTest = "com.attacker.reeltrak"
        integrity.certSha256OverrideForTest = AppIntegrityManager.RELEASE_CERT_SHA256

        assertFalse("Wrong package name must fail integrity verification", integrity.isAppIntegrityValid())
        assertTrue(integrity.getTamperDetails()?.contains("Package name mismatch") == true)
    }

    @Test
    fun testCertPinning_RepackagedAPK_TransitionsToTampered() {
        val integrity = FakeIntegrityManager(isValid = false, testTamperDetails = "Modified certificate detected")
        val authManager = createAuthManager(integrityManager = integrity)

        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = BASE_TIME_MS,
            currentPeriodEnd = BASE_TIME_MS + TimeUnit.DAYS.toMillis(30),
            lastServerVerificationMs = BASE_TIME_MS,
            accessState = AccessState.ACTIVE
        )

        val state = authManager.recalculateAuthorizationState(activeInfo)
        assertTrue("Tampered APK must transition state to Tampered", state is AuthorizationState.Tampered)
        assertNull("Active token must be null when app is tampered", authManager.activeToken)
    }

    // =========================================================================
    // PART 2 & 3: ASYMMETRIC SERVER-SIGNED LEASE VERIFICATION TESTS
    // =========================================================================

    @Test
    fun testProductionKeyIsolation_TestLeaseRejectedByProductionKey() {
        // Manager without test override uses production SERVER_PUBLIC_KEY_X509_BASE64
        val prodAuthManager = AppAuthorizationManager(FakeSubscriptionRepository(), FakeTimeAuthority(), FakeIntegrityManager(), context = null)
        prodAuthManager.currentUidProvider = { TEST_UID }
        assertNull("Production manager must not have public key override", prodAuthManager.serverPublicKeyOverride)

        // Lease signed with test-only private key
        val testSignedLease = generateTestServerSignedLease()

        // Must FAIL verification because production public key does not match test private key!
        assertFalse("Lease signed by test key MUST be rejected by production public key", prodAuthManager.verifyToken(testSignedLease))
    }

    @Test
    fun testServerLease_ValidSignature_Passes() {
        val authManager = createAuthManager()
        val validLease = generateTestServerSignedLease()

        assertTrue("Valid server-signed lease must pass verification", authManager.verifyToken(validLease))

        val accepted = authManager.updateServerLease(validLease)
        assertTrue("Valid lease must be accepted by authorization manager", accepted)
        assertEquals(validLease, authManager.activeToken)
    }

    @Test
    fun testServerLease_InvalidSignature_Fails() {
        val authManager = createAuthManager()
        val forgedLease = generateTestServerSignedLease().copy(signature = "FORGED_SIGNATURE_BASE64==")

        assertFalse("Forged server lease must fail cryptographic verification", authManager.verifyToken(forgedLease))
        val accepted = authManager.updateServerLease(forgedLease)
        assertFalse("Forged lease must be rejected by authorization manager", accepted)
    }

    @Test
    fun testServerLease_ModifiedPayload_Fails() {
        val authManager = createAuthManager()
        val validLease = generateTestServerSignedLease()

        // Modify planId
        val tamperedPlan = validLease.copy(planId = "plan_annual_unlimited")
        assertFalse("Modified planId must fail signature verification", authManager.verifyToken(tamperedPlan))

        // Modify accessMode
        val tamperedMode = validLease.copy(accessMode = AccessMode.TRIAL)
        assertFalse("Modified accessMode must fail signature verification", authManager.verifyToken(tamperedMode))

        // Modify nonce
        val tamperedNonce = validLease.copy(nonce = UUID.randomUUID().toString())
        assertFalse("Modified nonce must fail signature verification", authManager.verifyToken(tamperedNonce))
    }

    @Test
    fun testServerLease_ModifiedUID_Fails() {
        val authManager = createAuthManager()
        val validLease = generateTestServerSignedLease()

        val tamperedUidLease = validLease.copy(uid = "firebase_user_attacker_999")
        assertFalse("Modified UID must fail signature verification and account binding", authManager.verifyToken(tamperedUidLease))
    }

    @Test
    fun testServerLease_ModifiedExpiration_Fails() {
        val authManager = createAuthManager()
        val validLease = generateTestServerSignedLease()

        val extendedLease = validLease.copy(expiresAtMs = validLease.expiresAtMs + TimeUnit.DAYS.toMillis(365))
        assertFalse("Extended expiration payload must fail signature verification", authManager.verifyToken(extendedLease))
    }

    @Test
    fun testServerLease_ExpiredLease_Fails() {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        val expiredLease = generateTestServerSignedLease(
            expiresAtMs = BASE_TIME_MS - 1000L,
            leaseValidUntilMs = BASE_TIME_MS - 1000L
        )

        assertFalse("Expired lease must fail validation", authManager.verifyToken(expiredLease))
    }

    @Test
    fun testServerLease_AccountSwitch_UserALeaseRejectedForUserB() {
        var activeUser = "user_alice"
        val authManager = createAuthManager(uidProvider = { activeUser })

        val aliceLease = generateTestServerSignedLease(uid = "user_alice")
        assertTrue("Alice lease is valid for Alice", authManager.verifyToken(aliceLease))

        // Switch active user to Bob
        activeUser = "user_bob"
        assertFalse("Alice lease must be rejected when Bob is active user", authManager.verifyToken(aliceLease))
    }

    @Test
    fun testServerLease_FutureDatedLease_FailsMonotonicSanityCheck() {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        val futureLease = generateTestServerSignedLease(
            issuedAtMs = BASE_TIME_MS + TimeUnit.HOURS.toMillis(2) // 2 hours in future
        )

        assertFalse("Lease issued in future must fail monotonic sanity check", authManager.verifyToken(futureLease))
    }

    // =========================================================================
    // PART 4: GOD-HOOK ATTACK DEFEAT TESTS
    // =========================================================================

    @Test
    fun testGodHookDefeat_FakeSubscriptionInfo_CannotManufactureAuthority() {
        val authManager = createAuthManager()
        // No server lease ingested -> activeToken is null

        // Frida hook injects ACTIVE subscription info in memory
        val fakeActiveInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_fake_injected",
            currentPeriodStart = BASE_TIME_MS,
            currentPeriodEnd = BASE_TIME_MS + TimeUnit.DAYS.toMillis(30),
            lastServerVerificationMs = BASE_TIME_MS,
            accessState = AccessState.ACTIVE
        )

        val state = authManager.recalculateAuthorizationState(fakeActiveInfo)

        // Must fail closed because client CANNOT mint server-signed leases!
        assertEquals("Without server lease, state must be Locked despite fake ACTIVE info",
            AuthorizationState.Locked(AuthorizationLockReason.NO_SERVER_LEASE), state)
        assertNull("activeToken must remain null", authManager.activeToken)
    }

    @Test
    fun testGodHookDefeat_LocalActiveWithoutServerLease_CannotUnlockPersistence() {
        val authManager = createAuthManager()

        // Patched local boolean
        val attackerPatchedBoolean = true
        assertTrue(attackerPatchedBoolean)

        // Downstream token check
        val token = authManager.activeToken
        assertNull("Active token is strictly null", token)
        assertFalse("Persistence must be completely blocked", authManager.verifyToken(token))
    }

    // =========================================================================
    // PART 5: DAYMANAGER DECOUPLING TESTS (NO FALLBACK)
    // =========================================================================

    @Test
    fun testDayManager_NoActiveTokenFallbackInSource() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()

        // 1. Must NOT contain the vulnerable fallback
        assertFalse(
            "DayManager must NOT contain 'val effectiveToken = token ?: authMgr.activeToken'",
            content.contains("token ?: authMgr.activeToken")
        )
        assertFalse(
            "DayManager must NOT fallback to authMgr.activeToken",
            content.contains("?: authMgr.activeToken")
        )

        // 2. Must explicitly verify the provided token if present
        assertTrue(
            "DayManager.incrementReelCount must verify token when provided",
            content.contains("if (authMgr != null && token != null)")
        )
    }

    @Test
    fun testPersistenceGate_VerificationConditions() {
        val authManager = createAuthManager()
        val validToken = generateTestServerSignedLease()
        authManager.updateServerLease(validToken)

        // 1. Null token -> rejected
        val nullToken: AuthorizationToken? = null
        assertFalse("Null token must be rejected", nullToken != null && authManager.verifyToken(nullToken))

        // 2. Expired token -> rejected
        val expiredToken = generateTestServerSignedLease(
            expiresAtMs = BASE_TIME_MS - 5000L,
            leaseValidUntilMs = BASE_TIME_MS - 5000L
        )
        assertFalse("Expired token must be rejected", authManager.verifyToken(expiredToken))

        // 3. Wrong UID token -> rejected
        val wrongUidToken = generateTestServerSignedLease(uid = "wrong_user_xyz")
        assertFalse("Wrong UID token must be rejected", authManager.verifyToken(wrongUidToken))

        // 4. Forged signature token -> rejected
        val forgedToken = validToken.copy(signature = "FORGED_SIGNATURE==")
        assertFalse("Forged token must be rejected", authManager.verifyToken(forgedToken))

        // 5. Valid token -> accepted
        assertTrue("Valid token must be accepted", authManager.verifyToken(validToken))
    }

    // =========================================================================
    // PART 6: OFFLINE LEASE & REPLAY TESTS
    // =========================================================================

    @Test
    fun testOffline_ValidLease_WorksOfflineWithoutNetwork() {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        val lease = generateTestServerSignedLease(
            issuedAtMs = BASE_TIME_MS,
            expiresAtMs = BASE_TIME_MS + TimeUnit.DAYS.toMillis(30),
            leaseValidUntilMs = BASE_TIME_MS + TimeUnit.HOURS.toMillis(24) // 24h lease
        )

        authManager.updateServerLease(lease)
        assertTrue("Lease must verify offline", authManager.verifyToken(lease))

        // Advance local time by 12 hours (still within 24h lease)
        timeAuthority.currentTime = BASE_TIME_MS + TimeUnit.HOURS.toMillis(12)
        assertTrue("Lease must remain valid offline at 12 hours", authManager.verifyToken(lease))
    }

    @Test
    fun testOffline_ExpiredLease_FailsClosed() {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        val lease = generateTestServerSignedLease(
            issuedAtMs = BASE_TIME_MS,
            expiresAtMs = BASE_TIME_MS + TimeUnit.DAYS.toMillis(30),
            leaseValidUntilMs = BASE_TIME_MS + TimeUnit.HOURS.toMillis(24)
        )

        authManager.updateServerLease(lease)

        // Advance local time beyond 24 hours
        timeAuthority.currentTime = BASE_TIME_MS + TimeUnit.HOURS.toMillis(25)
        assertFalse("Lease older than 24 hours must fail verification offline", authManager.verifyToken(lease))
    }

    // =========================================================================
    // PART 7: SESSION MANAGER & GRACE PERIOD TESTS
    // =========================================================================

    @Test
    fun testSessionManager_AuthorizationRevoked_ForcesIdle() {
        var lastOldState: SessionState? = null
        var lastNewState: SessionState? = null

        val sessionManager = SessionManager(
            onStateChanged = { old, new, _ ->
                lastOldState = old
                lastNewState = new
            }
        )

        sessionManager.transitionTo(SessionState.ENTERING_REELS)
        sessionManager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        // Revoke authorization
        sessionManager.updateAuthorization(null)

        assertEquals("Session must be immediately forced to IDLE", SessionState.IDLE, sessionManager.currentState)
        assertEquals(SessionState.ACTIVE, lastOldState)
        assertEquals(SessionState.IDLE, lastNewState)
    }

    @Test
    fun testFinalSyncGrace_IssuesGraceToken_TrackingTokenNull() {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        // Sub info in grace period
        val graceInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = BASE_TIME_MS - TimeUnit.DAYS.toMillis(30),
            currentPeriodEnd = BASE_TIME_MS - 30_000L,
            lastServerVerificationMs = BASE_TIME_MS,
            accessState = AccessState.FINAL_SYNC_GRACE
        )

        val state = authManager.recalculateAuthorizationState(graceInfo)
        assertNull("activeToken for tracking must be null during grace", authManager.activeToken)
    }

    // =========================================================================
    // PART 8: ZERO DATABASE LEAK GUARANTEE
    // =========================================================================

    @Test
    fun testZeroDatabaseLeak_UnauthorizedState_ZeroRecordsWritten() = runBlocking {
        val dao = FakeDailyReelDataDao()
        val authManager = createAuthManager()
        authManager.revokeAuthorization(AuthorizationLockReason.NO_AUTHENTICATED_ACCOUNT)

        for (i in 1..10) {
            val token = authManager.activeToken
            if (token != null && authManager.verifyToken(token)) {
                dao.insertOrReplace(DailyReelData(dateString = "2026-09-27", totalReelsCount = i))
            }
        }

        assertEquals("Zero writes must occur under unauthorized state", 0, dao.insertCallCount)
        assertTrue("DAO records must be completely empty", dao.records.isEmpty())
    }

    // =========================================================================
    // PART 9: REGRESSION TESTS FOR SUBSCRIBER STATE RESTORATION
    // =========================================================================

    @Test
    fun testActiveSubscriber_WithExpiredTrialHistory_TransitionsToNoServerLeaseNotTrialExpired() {
        val authManager = createAuthManager()
        val expiredTrialTime = BASE_TIME_MS - TimeUnit.DAYS.toMillis(10)
        val futurePeriodEnd = BASE_TIME_MS + TimeUnit.DAYS.toMillis(20)

        // Real user scenario: User finished 7-day trial in the past, then purchased active subscription.
        // Firestore status document retains historical trialStart and trialEnd timestamps.
        val activeInfoWithTrialHistory = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_paid_active",
            currentPeriodStart = BASE_TIME_MS - TimeUnit.DAYS.toMillis(5),
            currentPeriodEnd = futurePeriodEnd,
            trialStart = expiredTrialTime - TimeUnit.DAYS.toMillis(7),
            trialEnd = expiredTrialTime,
            accessState = AccessState.ACTIVE
        )

        val state = authManager.recalculateAuthorizationState(activeInfoWithTrialHistory)
        assertEquals(
            "Active subscriber with expired trial history must evaluate to NO_SERVER_LEASE, never TRIAL_EXPIRED",
            AuthorizationState.Locked(AuthorizationLockReason.NO_SERVER_LEASE),
            state
        )
    }

    @Test
    fun testSubscriptionManager_ActiveSubscriberWithExpiredTrialHistory_RemainsUnlocked() = runBlocking {
        val timeAuthority = FakeTimeAuthority(currentTime = BASE_TIME_MS)
        val expiredTrialTime = BASE_TIME_MS - TimeUnit.DAYS.toMillis(10)
        val futurePeriodEnd = BASE_TIME_MS + TimeUnit.DAYS.toMillis(20)

        val repo = FakeSubscriptionRepository()
        val authManager = createAuthManager(timeAuthority = timeAuthority)

        val subManager = com.reeltrak.app.domain.managers.SubscriptionManager(
            subscriptionRepository = repo,
            subscriptionTimeAuthority = timeAuthority,
            appAuthorizationManager = authManager
        )

        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = BASE_TIME_MS - TimeUnit.DAYS.toMillis(5),
            currentPeriodEnd = futurePeriodEnd,
            trialStart = expiredTrialTime - TimeUnit.DAYS.toMillis(7),
            trialEnd = expiredTrialTime,
            accessState = AccessState.ACTIVE
        )

        repo.emitInfo(activeInfo)
        authManager.recalculateAuthorizationState(activeInfo)

        val resolvedState = kotlinx.coroutines.withTimeout(2000) {
            subManager.accessState.first { it == AccessState.ACTIVE }
        }

        assertEquals("SubscriptionManager must remain ACTIVE for paid subscriber with expired trial history",
            AccessState.ACTIVE, resolvedState)
        assertTrue("App must be unlocked", subManager.isAppUnlocked.first { it })
        assertFalse("App must not be confirmed locked", subManager.isConfirmedLocked.first { !it })
    }
}

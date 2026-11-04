package com.reeltrak.app.security

import android.content.Context
import androidx.room.Room
import com.reeltrak.app.data.local.database.ReelTrakDatabase
import com.reeltrak.app.data.local.entity.ReelCountAccountScope
import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.data.security.authorization.AccessMode
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.AppIntegrityManager
import com.reeltrak.app.data.security.authorization.AuthorizationToken
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.ThemeMode
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.domain.repository.SubscriptionRepository
import com.reeltrak.app.services.notifications.ReelNotificationManager
import com.reeltrak.app.services.tracking.DayManager
import com.reeltrak.app.services.tracking.ReelCountAcceptance
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayManagerAuthorizationBehaviorTest {

    companion object {
        const val TEST_UID = "user_auth_behavior_123"
        const val TEST_DIGEST = AppIntegrityManager.DEV_TEST_INTEGRITY_DIGEST
        const val BASE_TIME_MS = 1756720800000L // 2025-09-01T10:00:00Z

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
        override suspend fun getSubscriptionInfoSync(): SubscriptionInfo = SubscriptionInfo.INITIALIZING_DEFAULT
        override suspend fun refreshSubscription(): Result<SubscriptionInfo> = Result.success(SubscriptionInfo.INITIALIZING_DEFAULT)
        override suspend fun cancelSubscription(): Result<Unit> = Result.success(Unit)
        override suspend fun syncIfStale(minIntervalMs: Long): Result<SubscriptionInfo>? = null
        override suspend fun fetchAuthorizationLease(): Result<AuthorizationToken> =
            Result.success(generateTestServerSignedLease())
    }

    private class FakeTimeAuthority(
        var currentTime: Long = BASE_TIME_MS,
        var offlineLeaseValid: Boolean = true
    ) : SubscriptionTimeAuthority() {
        override fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> = Pair(currentTime, true)
        override fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean = offlineLeaseValid
    }

    private class FakeIntegrityManager : AppIntegrityManager(null) {
        override fun isAppIntegrityValid(): Boolean = true
        override fun getIntegrityDigest(): String = TEST_DIGEST
        override fun getTamperDetails(): String? = null
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val _defaultDailyLimit = MutableStateFlow(0)
        override val defaultDailyLimit: Flow<Int> = _defaultDailyLimit
        override suspend fun setDefaultDailyLimit(limit: Int) { _defaultDailyLimit.value = limit }
        override val lastBackupTime: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setLastBackupTime(time: Long) {}
        override val currentBackupRequestId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setCurrentBackupRequestId(requestId: String?) {}
        override val currentRestoreRequestId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setCurrentRestoreRequestId(requestId: String?) {}
        override val lastRestoreTime: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setLastRestoreTime(time: Long) {}
        override val lastBackupStatus: Flow<String?> = MutableStateFlow(null)
        override suspend fun setLastBackupStatus(status: String?) {}
        override val dailyIntent: Flow<Int> = MutableStateFlow(0)
        override val firstRunDate: Flow<Long> = MutableStateFlow(0L)
        override val goalSetDate: Flow<Long> = MutableStateFlow(0L)
        override val isStrictModeLocked: Flow<Boolean> = MutableStateFlow(false)
        override val lastStrictModeOffDate: Flow<String?> = MutableStateFlow(null)
        override val isDailyLimitLocked: Flow<Boolean> = MutableStateFlow(false)
        override val lastLockEpochDay: Flow<Long> = MutableStateFlow(0L)
        override val lastLimitChangeDate: Flow<Long> = MutableStateFlow(0L)
        override val lastDailySummaryDate: Flow<Long> = MutableStateFlow(0L)
        override val notificationsEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val badge7UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge30UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge50UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge100UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge365UnlockTime: Flow<Long> = MutableStateFlow(0L)
        override val badge7DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge30DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge50DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge100DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override val badge365DialogShown: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setBadgeUnlockTime(milestone: Int, time: Long) {}
        override suspend fun setBadgeDialogShown(milestone: Int, shown: Boolean) {}
        override suspend fun setLastLimitChangeDate(date: Long) {}
        override suspend fun setLastDailySummaryDate(date: Long) {}
        override suspend fun setDailyIntent(intent: Int) {}
        override suspend fun setFirstRunDate(date: Long) {}
        override suspend fun setGoalSetDate(date: Long) {}
        override suspend fun setStrictModeLocked(locked: Boolean) {}
        override suspend fun setLastStrictModeOffDate(date: String) {}
        override suspend fun setDailyLimitLocked(locked: Boolean) {}
        override suspend fun setLastLockEpochDay(day: Long) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
        override val counterThemeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.DARK)
        override suspend fun setCounterThemeMode(mode: ThemeMode) {}
        override val authSession: Flow<com.reeltrak.app.core.auth.UserAuthData?> = MutableStateFlow(null)
        override suspend fun saveAuthSession(userData: com.reeltrak.app.core.auth.UserAuthData) {}
        override suspend fun clearAuthSession() {}
        override suspend fun clearAllPreferences() {}
        override val isFreeMode: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setFreeMode(enabled: Boolean) {}
        override val hasMigratedToServer: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setHasMigratedToServer(migrated: Boolean) {}
        override val hasCompletedTour: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setHasCompletedTour(completed: Boolean) {}
        override suspend fun getAllPreferencesSync(): Map<String, Any> = emptyMap()
        override suspend fun restorePreferences(cloudData: Map<String, Any>) {}
    }

    private lateinit var context: Context
    private lateinit var database: ReelTrakDatabase
    private lateinit var preferencesRepo: FakePreferencesRepository
    private lateinit var notificationMgr: ReelNotificationManager
    private val testFingerprint = SemanticFingerprint("reel_test_1", "creator_a", "music_a", platform = "Instagram")

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, ReelTrakDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferencesRepo = FakePreferencesRepository()
        notificationMgr = ReelNotificationManager(context, preferencesRepo)
        ReelCountAccountScope.setCurrent("local")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createAuthManager(
        uid: String? = TEST_UID,
        operationalModeOverride: OperationalMode? = null
    ): AppAuthorizationManager {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeTimeAuthority()
        val integrity = FakeIntegrityManager()
        val manager = object : AppAuthorizationManager(repo, timeAuthority, integrity, context = null) {
            override fun getOperationalMode(info: SubscriptionInfo?): OperationalMode {
                return operationalModeOverride ?: super.getOperationalMode(info)
            }
        }
        manager.currentUidProvider = { uid }
        manager.serverPublicKeyOverride = testPublicKey
        return manager
    }

    private fun createDayManager(
        authManager: AppAuthorizationManager? = null,
        allowUnconfiguredAuthForTesting: Boolean = false
    ): DayManager {
        val dm = DayManager(
            context = context,
            dailyReelDataDao = database.dailyReelDataDao,
            activeAccountScopeDao = database.activeAccountScopeDao,
            preferencesRepository = preferencesRepo,
            notificationManager = notificationMgr,
            syncManager = null,
            appAuthorizationManager = authManager
        )
        dm.allowUnconfiguredAuthForTesting = allowUnconfiguredAuthForTesting
        return dm
    }

    // =========================================================================
    // 1. MISSING AUTHORIZATION MANAGER IN PRODUCTION (FAILS CLOSED)
    // =========================================================================

    @Test
    fun testMissingAuthorizationManager_FailsClosedInProduction() {
        // Default production instance without auth manager wiring
        val dayManager = createDayManager(authManager = null, allowUnconfiguredAuthForTesting = false)

        val validToken = generateTestServerSignedLease()

        // 1. isCountAuthorized must return false for null token
        assertFalse("Missing auth manager in production must reject null token",
            dayManager.isCountAuthorizedForTesting(null))

        // 2. isCountAuthorized must return false for valid token
        assertFalse("Missing auth manager in production must reject even valid tokens",
            dayManager.isCountAuthorizedForTesting(validToken))

        // 3. acceptReelTransition must strictly return REJECTED
        val result = dayManager.acceptReelTransition(testFingerprint, validToken)
        assertEquals("Production missing-manager instance must reject transitions",
            ReelCountAcceptance.REJECTED, result)
    }

    // =========================================================================
    // 2. EXPLICIT UNCONFIGURED TEST MODE ALLOWANCE
    // =========================================================================

    @Test
    fun testMissingAuthorizationManager_AllowsUnconfiguredTestModeWhenExplicitlyOptedIn() {
        // Isolated test harness explicitly opting into unconfigured test mode
        val dayManager = createDayManager(authManager = null, allowUnconfiguredAuthForTesting = true)

        assertTrue("Explicit test mode must permit unconfigured testing",
            dayManager.isCountAuthorizedForTesting(null))
        assertTrue("Explicit test mode must permit local account scope",
            dayManager.isAccountScopeCurrentForTesting())
    }

    // =========================================================================
    // 3. LOCKED OR EXPIRED OPERATIONAL MODE (FAILS CLOSED)
    // =========================================================================

    @Test
    fun testLockedOrExpiredMode_RejectsAllIncrements() {
        val authManager = createAuthManager(operationalModeOverride = OperationalMode.LOCKED_OR_EXPIRED)
        val dayManager = createDayManager(authManager = authManager)

        val validToken = generateTestServerSignedLease()

        // 1. Null token in locked mode -> rejected
        assertFalse("Locked mode must reject null token",
            dayManager.isCountAuthorizedForTesting(null))

        // 2. Valid token in locked mode -> rejected
        assertFalse("Locked mode must reject valid token",
            dayManager.isCountAuthorizedForTesting(validToken))

        // 3. acceptReelTransition -> REJECTED
        val result = dayManager.acceptReelTransition(testFingerprint, validToken)
        assertEquals("Locked mode must reject transition acceptance",
            ReelCountAcceptance.REJECTED, result)
    }

    // =========================================================================
    // 4. VALID SERVER-SIGNED TOKEN (ACCEPTED)
    // =========================================================================

    @Test
    fun testValidToken_AcceptedInProduction() = kotlinx.coroutines.runBlocking {
        val authManager = createAuthManager(operationalModeOverride = OperationalMode.PREMIUM)
        val dayManager = createDayManager(authManager = authManager)
        dayManager.switchAccountScope(TEST_UID)

        val validToken = generateTestServerSignedLease()

        assertTrue("Valid server-signed token must be authorized",
            dayManager.isCountAuthorizedForTesting(validToken))

        val result = dayManager.acceptReelTransition(testFingerprint, validToken)
        assertEquals("Valid server-signed token must be counted in Room",
            ReelCountAcceptance.COUNTED, result)
    }

    // =========================================================================
    // 5. INVALID OR FORGED SIGNATURE TOKEN (REJECTED)
    // =========================================================================

    @Test
    fun testInvalidOrForgedToken_Rejected() {
        val authManager = createAuthManager(operationalModeOverride = OperationalMode.PREMIUM)
        ReelCountAccountScope.setCurrent(ReelCountAccountScope.fromUid(TEST_UID))
        val dayManager = createDayManager(authManager = authManager)

        val validToken = generateTestServerSignedLease()
        val forgedToken = validToken.copy(signature = "MEYCIQCfakeSignatureDERRawBytesForTestingTamper123456==")

        assertFalse("Forged token signature must fail verification",
            dayManager.isCountAuthorizedForTesting(forgedToken))

        val result = dayManager.acceptReelTransition(testFingerprint, forgedToken)
        assertEquals("Forged token must be rejected at transition gate",
            ReelCountAcceptance.REJECTED, result)
    }

    // =========================================================================
    // 6. EXPIRED TOKEN (REJECTED)
    // =========================================================================

    @Test
    fun testExpiredToken_Rejected() {
        val authManager = createAuthManager(operationalModeOverride = OperationalMode.PREMIUM)
        ReelCountAccountScope.setCurrent(ReelCountAccountScope.fromUid(TEST_UID))
        val dayManager = createDayManager(authManager = authManager)

        // Lease expired 1 hour before BASE_TIME_MS
        val expiredToken = generateTestServerSignedLease(
            issuedAtMs = BASE_TIME_MS - TimeUnit.DAYS.toMillis(31),
            expiresAtMs = BASE_TIME_MS - TimeUnit.HOURS.toMillis(1),
            leaseValidUntilMs = BASE_TIME_MS - TimeUnit.HOURS.toMillis(1)
        )

        assertFalse("Expired token must fail verification",
            dayManager.isCountAuthorizedForTesting(expiredToken))

        val result = dayManager.acceptReelTransition(testFingerprint, expiredToken)
        assertEquals("Expired token must be rejected at transition gate",
            ReelCountAcceptance.REJECTED, result)
    }

    // =========================================================================
    // 7. FREE-MODE NULL TOKEN (ACCEPTED IN NORMAL OPERATIONAL MODE)
    // =========================================================================

    @Test
    fun testFreeModeNullToken_AcceptedWhenOperationalModeIsNormal() {
        val authManager = createAuthManager(uid = null, operationalModeOverride = OperationalMode.UNAUTHENTICATED_FREE)
        ReelCountAccountScope.setCurrent("local")
        val dayManager = createDayManager(authManager = authManager)

        // Free mode uses null token
        assertTrue("Null token in normal unauthenticated free mode must be authorized",
            dayManager.isCountAuthorizedForTesting(null))

        val result = dayManager.acceptReelTransition(testFingerprint, null)
        assertEquals("Free mode transition with null token must be counted in Room",
            ReelCountAcceptance.COUNTED, result)
    }

    // =========================================================================
    // 8. ACCOUNT SWITCH DURING ACTIVE SESSION: OLD ACCOUNT TOKEN REJECTED
    // =========================================================================

    @Test
    fun testAccountSwitch_OldAccountTokenRejectedUnderNewAccountScope() = kotlinx.coroutines.runBlocking {
        // User A setup
        val authManagerA = createAuthManager(uid = "user_A", operationalModeOverride = OperationalMode.PREMIUM)
        val dayManager = createDayManager(authManager = authManagerA)
        dayManager.switchAccountScope("user_A")

        val tokenUserA = generateTestServerSignedLease(uid = "user_A")
        assertTrue("Token for user_A must be authorized under user_A scope",
            dayManager.isCountAuthorizedForTesting(tokenUserA))
        assertEquals("User A transition must be accepted under user_A scope",
            ReelCountAcceptance.COUNTED,
            dayManager.acceptReelTransition(testFingerprint, tokenUserA))

        // Account switches to User B (e.g. user_A logs out, user_B logs in)
        dayManager.switchAccountScope("user_B")
        assertEquals(ReelCountAccountScope.fromUid("user_B"), ReelCountAccountScope.current)

        // Present User A's token under User B's scope -> must be rejected
        val acceptanceWithOldToken = dayManager.acceptReelTransition(testFingerprint, tokenUserA)
        assertEquals("Token from prior account must be rejected after account switch",
            ReelCountAcceptance.REJECTED, acceptanceWithOldToken)
    }
}

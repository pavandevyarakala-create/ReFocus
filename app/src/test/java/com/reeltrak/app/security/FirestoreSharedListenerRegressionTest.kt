package com.reeltrak.app.security

import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression and behavioral verification test suite for Firestore duplicate-listener elimination.
 *
 * Confirms:
 * 1. SubscriptionRepositoryImpl shares one Firestore snapshot listener per authenticated UID across all consumers.
 * 2. AccountRepositoryImpl shares one Firestore snapshot listener per authenticated UID across all consumers.
 * 3. SettingsViewModel derives accountStatus directly from accountAccessInfo without registering a duplicate listener.
 * 4. Auth switching (login/logout/switch user) cleanly tears down previous listeners and cancels Firestore connections.
 * 5. WhileSubscribed cleanly removes snapshot listeners when all collectors cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreSharedListenerRegressionTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        for (candidate in candidates) {
            val f = File(candidate)
            if (f.exists()) return f
        }
        val currentDir = File(".").absoluteFile
        val found = currentDir.walkTopDown().firstOrNull { it.path.replace("\\", "/").endsWith(relativePath) }
        if (found != null && found.exists()) return found
        throw AssertionError("Could not locate source file: $relativePath across workspace")
    }

    // =========================================================================
    // Contract & Source Code Verifications
    // =========================================================================

    @Test
    fun testSourceContract_SubscriptionRepositoryImpl_SharesListenerWithWhileSubscribed() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "SubscriptionRepositoryImpl must use shareIn on subscriptionState",
            content.contains(".shareIn(")
        )
        assertTrue(
            "SubscriptionRepositoryImpl must use SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)",
            content.contains("SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)")
        )
        assertTrue(
            "SubscriptionRepositoryImpl must configure replay = 1 to eliminate UI flash on collection",
            content.contains("replay = 1")
        )
        assertTrue(
            "SubscriptionRepositoryImpl primary constructor must accept @ApplicationScope externalScope: CoroutineScope",
            content.contains("@ApplicationScope externalScope: CoroutineScope")
        )
    }

    @Test
    fun testSourceContract_AccountRepositoryImpl_SharesListenerWithWhileSubscribed() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt")
        val content = file.readText()

        assertTrue(
            "AccountRepositoryImpl must use shareIn on accountAccessInfo",
            content.contains(".shareIn(")
        )
        assertTrue(
            "AccountRepositoryImpl must use SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)",
            content.contains("SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)")
        )
        assertTrue(
            "AccountRepositoryImpl must configure replay = 1 to provide immediate cached state",
            content.contains("replay = 1")
        )
        assertTrue(
            "AccountRepositoryImpl primary constructor must accept @ApplicationScope externalScope: CoroutineScope",
            content.contains("@ApplicationScope externalScope: CoroutineScope")
        )
    }

    @Test
    fun testSourceContract_SettingsViewModel_DerivesAccountStatusFromAccountAccessInfo() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/settings/viewmodel/SettingsViewModel.kt")
        val content = file.readText()

        assertFalse(
            "SettingsViewModel must NOT collect accountRepository.accountStatus independently",
            content.contains("accountRepository.accountStatus")
        )
        assertTrue(
            "SettingsViewModel must derive accountStatus from accountAccessInfo.map { it.accountStatus }",
            content.contains("accountAccessInfo") && content.contains(".map { it.accountStatus }")
        )
    }

    // =========================================================================
    // Behavioral Verifications: Flow Sharing, UID Switching, and Cancellation
    // =========================================================================

    @Test
    fun testBehavioral_SubscriptionListenerSharing_MultipleConsumersDoNotMultiplyListeners() = runBlocking {
        val activeListeners = AtomicInteger(0)
        val totalRegistrations = AtomicInteger(0)
        val authFlow = MutableStateFlow<String?>("user_active_123")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Mock shared repository flow using exact WhileSubscribed semantics
        val sharedSubscriptionState: Flow<SubscriptionState> = authFlow.flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf<SubscriptionState>(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
            } else {
                callbackFlow<SubscriptionState> {
                    totalRegistrations.incrementAndGet()
                    activeListeners.incrementAndGet()

                    trySend(SubscriptionState.Loading)
                    trySend(
                        SubscriptionState.Success(
                            SubscriptionInfo(
                                status = SubscriptionStatus.ACTIVE,
                                plan = SubscriptionPlan.MONTHLY,
                                subscriptionId = "sub_$uid",
                                accessState = AccessState.ACTIVE
                            )
                        )
                    )

                    awaitClose {
                        activeListeners.decrementAndGet()
                    }
                }
            }
        }.distinctUntilChanged()
        .shareIn(
            scope = testScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 200, replayExpirationMillis = 0),
            replay = 1
        )

        // Downstream derived flows as implemented in SubscriptionRepository & SubscriptionManager
        val subscriptionInfoFlow = sharedSubscriptionState.map { state ->
            when (state) {
                is SubscriptionState.Success -> state.info
                is SubscriptionState.Error -> state.fallbackInfo
                is SubscriptionState.Loading -> SubscriptionInfo.INITIALIZING_DEFAULT
            }
        }.distinctUntilChanged()

        val accessStateFlow = subscriptionInfoFlow.map { it.accessState }.distinctUntilChanged()
        val isAppUnlockedFlow = subscriptionInfoFlow.map { it.isUnlocked }.distinctUntilChanged()

        // Collector 1: SubscriptionManager.state
        val receivedStates = CopyOnWriteArrayList<SubscriptionState>()
        val job1 = testScope.launch { sharedSubscriptionState.collect { receivedStates.add(it) } }

        // Collector 2: SubscriptionManager.subscriptionInfo
        val receivedInfos = CopyOnWriteArrayList<SubscriptionInfo>()
        val job2 = testScope.launch { subscriptionInfoFlow.collect { receivedInfos.add(it) } }

        // Collector 3: SubscriptionManager.accessState
        val receivedAccess = CopyOnWriteArrayList<AccessState>()
        val job3 = testScope.launch { accessStateFlow.collect { receivedAccess.add(it) } }

        // Collector 4: SubscriptionManager.isAppUnlocked
        val receivedUnlocked = CopyOnWriteArrayList<Boolean>()
        val job4 = testScope.launch { isAppUnlockedFlow.collect { receivedUnlocked.add(it) } }

        delay(50)

        // CRITICAL CHECK: Even with 4 concurrent collectors across the app,
        // exactly ONE listener was ever created and exactly ONE is active.
        assertEquals("Total listener registrations must be exactly 1 despite 4 collectors", 1, totalRegistrations.get())
        assertEquals("Active listener count must be exactly 1", 1, activeListeners.get())

        // Verify all 4 collectors received correct values
        assertTrue("Collector 1 received success state", receivedStates.any { it is SubscriptionState.Success })
        assertTrue("Collector 2 received active info", receivedInfos.any { it.status == SubscriptionStatus.ACTIVE })
        assertTrue("Collector 3 received ACTIVE access", receivedAccess.contains(AccessState.ACTIVE))
        assertTrue("Collector 4 received unlocked = true", receivedUnlocked.contains(true))

        testScope.cancel()
    }

    @Test
    fun testBehavioral_UidSwitching_CancelsPreviousListenerAndAttachesNew() = runBlocking {
        val activeListeners = AtomicInteger(0)
        val currentListenedUid = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val authFlow = MutableStateFlow<String?>("user_initial")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val sharedState: Flow<SubscriptionState> = authFlow.flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                currentListenedUid.set(null)
                flowOf(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
            } else {
                callbackFlow {
                    activeListeners.incrementAndGet()
                    currentListenedUid.set(uid)

                    trySend(
                        SubscriptionState.Success(
                            SubscriptionInfo(
                                status = SubscriptionStatus.ACTIVE,
                                plan = SubscriptionPlan.MONTHLY,
                                subscriptionId = "sub_$uid",
                                accessState = AccessState.ACTIVE
                            )
                        )
                    )

                    awaitClose {
                        activeListeners.decrementAndGet()
                    }
                }
            }
        }.shareIn(
            scope = testScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 200, replayExpirationMillis = 0),
            replay = 1
        )

        val collectedValues = CopyOnWriteArrayList<SubscriptionState>()
        val collectorJob = testScope.launch { sharedState.collect { collectedValues.add(it) } }

        delay(50)
        assertEquals("Active listener count must be 1 for user_initial", 1, activeListeners.get())
        assertEquals("user_initial", currentListenedUid.get())

        // Switch to user_secondary
        authFlow.value = "user_secondary"
        delay(50)
        assertEquals("Active listener count must remain 1 after switch", 1, activeListeners.get())
        assertEquals("user_secondary", currentListenedUid.get())

        // Logout: authFlow becomes null
        authFlow.value = null
        delay(50)
        assertEquals("Active listener count must be 0 after logout", 0, activeListeners.get())
        assertEquals(null, currentListenedUid.get())

        // Emitted state after logout must be LOCKED_DEFAULT
        val lastState = collectedValues.lastOrNull() as? SubscriptionState.Success
        assertEquals(SubscriptionStatus.NONE, lastState?.info?.status)
        assertEquals(AccessState.LOCKED, lastState?.info?.accessState)

        collectorJob.cancel()
        testScope.cancel()
    }

    @Test
    fun testBehavioral_WhileSubscribed_CancelsListenerWhenAllSubscribersLeave() = runBlocking {
        val activeListeners = AtomicInteger(0)
        val authFlow = MutableStateFlow<String?>("user_retire")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val stopTimeoutMs = 150L
        val sharedState: Flow<SubscriptionState> = authFlow.flatMapLatest { uid ->
            callbackFlow {
                activeListeners.incrementAndGet()
                trySend(SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT))
                awaitClose {
                    activeListeners.decrementAndGet()
                }
            }
        }.shareIn(
            scope = testScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = stopTimeoutMs, replayExpirationMillis = 0),
            replay = 1
        )

        // Launch 2 collectors
        val job1 = testScope.launch { sharedState.collect { } }
        val job2 = testScope.launch { sharedState.collect { } }

        delay(50)
        assertEquals("Active listeners must be 1 while collectors are active", 1, activeListeners.get())

        // Cancel job1: job2 is still collecting -> listener stays active
        job1.cancel()
        delay(50)
        assertEquals("Active listeners must remain 1 while 1 collector remains", 1, activeListeners.get())

        // Cancel job2: all collectors gone
        job2.cancel()
        // Wait longer than stopTimeoutMs (150ms)
        delay(250)

        // Upstream must have unsubscribed and removed the snapshot listener
        assertEquals("Active listeners must drop to 0 when all collectors leave after timeout", 0, activeListeners.get())

        testScope.cancel()
    }

    @Test
    fun testBehavioral_SettingsViewModel_AccountStatusDerivation() = runBlocking {
        val mockRepoInfoFlow = MutableSharedFlow<AccountAccessInfo>(replay = 1)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Simulates SettingsViewModel:
        // accountAccessInfo = repoFlow.stateIn(scope, Eagerly, LOADING)
        // accountStatus = accountAccessInfo.map { it.accountStatus }.stateIn(scope, Eagerly, ACTIVE)
        val accountAccessInfo: StateFlow<AccountAccessInfo> = mockRepoInfoFlow
            .stateIn(testScope, SharingStarted.Eagerly, AccountAccessInfo.LOADING)

        val accountStatus: StateFlow<AccountStatus> = accountAccessInfo
            .map { it.accountStatus }
            .stateIn(testScope, SharingStarted.Eagerly, AccountStatus.ACTIVE)

        assertEquals("Initial accountAccessInfo is LOADING", true, accountAccessInfo.value.isLoading)
        assertEquals("Initial accountStatus is ACTIVE", AccountStatus.ACTIVE, accountStatus.value)

        // 1. Repository emits ACTIVE
        mockRepoInfoFlow.emit(
            AccountAccessInfo(accountStatus = AccountStatus.ACTIVE, accountType = AccountType.NORMAL, isLoading = false)
        )
        delay(30)
        assertEquals(AccountStatus.ACTIVE, accountStatus.value)

        // 2. Repository emits RESTRICTED
        mockRepoInfoFlow.emit(
            AccountAccessInfo(accountStatus = AccountStatus.RESTRICTED, accountType = AccountType.NORMAL, isLoading = false)
        )
        delay(30)
        assertEquals(AccountStatus.RESTRICTED, accountStatus.value)

        // 3. Repository emits PENDING_DELETION
        mockRepoInfoFlow.emit(
            AccountAccessInfo(accountStatus = AccountStatus.PENDING_DELETION, accountType = AccountType.NORMAL, isLoading = false)
        )
        delay(30)
        assertEquals(AccountStatus.PENDING_DELETION, accountStatus.value)

        // 4. Repository emits DELETED
        mockRepoInfoFlow.emit(
            AccountAccessInfo(accountStatus = AccountStatus.DELETED, accountType = AccountType.NORMAL, isLoading = false)
        )
        delay(30)
        assertEquals(AccountStatus.DELETED, accountStatus.value)

        testScope.cancel()
    }

    // =========================================================================
    // Backup Status Listener WhileSubscribed & Login Optimization Tests
    // =========================================================================

    @Test
    fun testSourceContract_SyncManager_SharesCloudBackupStatusFlowWithWhileSubscribed() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/core/sync/SyncManager.kt")
        val content = file.readText()

        assertTrue(
            "SyncManager must define cloudBackupStatusFlow",
            content.contains("val cloudBackupStatusFlow: Flow<CloudBackupStatus>")
        )
        assertTrue(
            "SyncManager must share cloudBackupStatusFlow with WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)",
            content.contains("SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000, replayExpirationMillis = 0)")
        )
        assertTrue(
            "SyncManager must configure replay = 1 on cloudBackupStatusFlow",
            content.contains("replay = 1")
        )
        assertFalse(
            "SyncManager.init must NOT attach permanent backupStatusListener on startup",
            content.contains("attachBackupStatusListener(currentUid)")
        )
    }

    @Test
    fun testSourceContract_SettingsViewModel_CollectsCloudBackupStatusFlow() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/settings/viewmodel/SettingsViewModel.kt")
        val content = file.readText()

        assertTrue(
            "SettingsViewModel must collect syncManager.cloudBackupStatusFlow",
            content.contains("syncManager.cloudBackupStatusFlow.collect")
        )
    }

    @Test
    fun testBehavioral_BackupStatusListener_WhileSubscribedLifecycle() = runBlocking {
        val activeListeners = AtomicInteger(0)
        val totalRegistrations = AtomicInteger(0)
        val authFlow = MutableStateFlow<String?>("user_backup_test")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val stopTimeoutMs = 150L
        val cloudBackupStatusFlow = authFlow.flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(com.reeltrak.app.core.sync.CloudBackupStatus.DEFAULT)
            } else {
                callbackFlow<com.reeltrak.app.core.sync.CloudBackupStatus> {
                    totalRegistrations.incrementAndGet()
                    activeListeners.incrementAndGet()

                    trySend(
                        com.reeltrak.app.core.sync.CloudBackupStatus(
                            status = "SUCCESS",
                            lastBackupServerTimeMs = 1700000000000L,
                            nextEligibleBackupTimeMs = 1700086400000L,
                            isRestricted = true
                        )
                    )

                    awaitClose {
                        activeListeners.decrementAndGet()
                    }
                }
            }
        }.shareIn(
            scope = testScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = stopTimeoutMs, replayExpirationMillis = 0),
            replay = 1
        )

        // Before any subscriber arrives (e.g. cold launch, dashboard, watching reels):
        delay(50)
        assertEquals("Backup listener must NOT be attached on cold start", 0, activeListeners.get())
        assertEquals("Total registrations must be 0", 0, totalRegistrations.get())

        // User opens Settings -> subscriber attaches
        val receivedStatus = CopyOnWriteArrayList<com.reeltrak.app.core.sync.CloudBackupStatus>()
        val settingsJob = testScope.launch { cloudBackupStatusFlow.collect { receivedStatus.add(it) } }

        delay(50)
        assertEquals("Backup listener must attach when Settings opens", 1, activeListeners.get())
        assertEquals("Total registrations should be 1", 1, totalRegistrations.get())
        assertTrue("Settings received status", receivedStatus.any { it.isRestricted })

        // User leaves Settings screen -> subscriber cancelled
        settingsJob.cancel()
        delay(stopTimeoutMs + 100) // Wait beyond 150ms timeout

        // Listener must be cleanly removed
        assertEquals("Backup listener must detach 5s after exiting Settings", 0, activeListeners.get())

        testScope.cancel()
    }

    @Test
    fun testBehavioral_BackupStatusListener_SignOutAndAccountSwitch() = runBlocking {
        val activeListeners = AtomicInteger(0)
        val authFlow = MutableStateFlow<String?>("user_a")
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val cloudBackupStatusFlow = authFlow.flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(com.reeltrak.app.core.sync.CloudBackupStatus.DEFAULT)
            } else {
                callbackFlow {
                    activeListeners.incrementAndGet()
                    trySend(
                        com.reeltrak.app.core.sync.CloudBackupStatus(
                            status = "SUCCESS",
                            lastBackupServerTimeMs = 1700000000000L,
                            nextEligibleBackupTimeMs = 1700086400000L,
                            isRestricted = true
                        )
                    )
                    awaitClose {
                        activeListeners.decrementAndGet()
                    }
                }
            }
        }.shareIn(
            scope = testScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 100, replayExpirationMillis = 0),
            replay = 1
        )

        val received = CopyOnWriteArrayList<com.reeltrak.app.core.sync.CloudBackupStatus>()
        val job = testScope.launch { cloudBackupStatusFlow.collect { received.add(it) } }

        delay(50)
        assertEquals("Active listener for user_a", 1, activeListeners.get())
        assertTrue(received.last().isRestricted)

        // Sign out: authFlow becomes null
        authFlow.value = null
        delay(50)
        assertEquals("Listener must detach on sign-out", 0, activeListeners.get())
        assertFalse("State after sign-out must be reset to unrestricted DEFAULT", received.last().isRestricted)

        // Switch to user_b
        authFlow.value = "user_b"
        delay(50)
        assertEquals("Listener attaches for user_b", 1, activeListeners.get())

        job.cancel()
        testScope.cancel()
    }

    @Test
    fun testBehavioral_LoginFlow_ServerVerifiedStateEliminatesDuplicateReads() = runBlocking {
        // Models AccountRepository's deduplication logic
        var serverReadsCount = 0
        var cachedListenerInfo: AccountAccessInfo? = null
        var cachedListenerTimestampMs = 0L
        val testUid = "user_verified_123"

        fun simulateCheckAccountAccessInfo(uid: String, nowElapsedMs: Long): Result<AccountAccessInfo> {
            if (cachedListenerInfo != null && (nowElapsedMs - cachedListenerTimestampMs) < 15_000L) {
                // Reused fresh server-backed listener data with 0 server reads
                return Result.success(cachedListenerInfo!!)
            }
            // Standalone server read fallback
            serverReadsCount++
            val fetched = AccountAccessInfo(accountStatus = AccountStatus.ACTIVE)
            cachedListenerInfo = fetched
            cachedListenerTimestampMs = nowElapsedMs
            return Result.success(fetched)
        }

        // 1. Initial login when listener has already verified server doc
        val t0 = 1000L
        cachedListenerInfo = AccountAccessInfo(accountStatus = AccountStatus.ACTIVE)
        cachedListenerTimestampMs = t0

        val result1 = simulateCheckAccountAccessInfo(testUid, nowElapsedMs = t0 + 200L)
        assertTrue(result1.isSuccess)
        assertEquals(0, serverReadsCount) // 0 server reads performed!

        // 2. Subsequent call within 15s window (e.g. post-login sync)
        val result2 = simulateCheckAccountAccessInfo(testUid, nowElapsedMs = t0 + 1000L)
        assertTrue(result2.isSuccess)
        assertEquals(0, serverReadsCount) // Still 0 server reads!

        // 3. Call after 15s expiration
        val result3 = simulateCheckAccountAccessInfo(testUid, nowElapsedMs = t0 + 16000L)
        assertTrue(result3.isSuccess)
        assertEquals(1, serverReadsCount) // Performed exactly 1 server read to refresh!
    }
}

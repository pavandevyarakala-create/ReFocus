package com.reeltrak.app.domain.managers

import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Verification test suite for eliminating the subscription lock flicker in Re:Focus.
 *
 * Verifies scenarios A through I:
 * A. ACTIVE subscription at startup -> must NOT emit false LOCKED state before duration initialization.
 * B. TRIAL subscription at startup -> must NOT falsely lock.
 * C. Actually expired subscription -> must become LOCKED.
 * D. Subscription expiring during runtime -> must transition to LOCKED correctly.
 * E. Background for >5 seconds and resume -> must not produce false LOCKED transition.
 * F. Missing/invalid subscription state -> must preserve intended security behavior (fail closed).
 * G. ReelTrackerService gating -> must only force IDLE when genuinely confirmed LOCKED, not during initialization.
 * H. Flow resubscription -> restarting collection does not reset state to incorrect LOCKED value.
 * I. Already-expired subscription -> cannot remain ACTIVE indefinitely.
 */
class SubscriptionLockFlickerTest {

    private class FakeSubscriptionTimeAuthority(
        var currentTimeMs: Long = 1756720800000L
    ) : SubscriptionTimeAuthority() {
        override fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> = Pair(currentTimeMs, true)
        override fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean = true
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
        override suspend fun refreshSubscription(): Result<SubscriptionInfo> = Result.success(getSubscriptionInfoSync())
        override suspend fun cancelSubscription(): Result<Unit> = Result.success(Unit)
        override suspend fun syncIfStale(minIntervalMs: Long): Result<SubscriptionInfo>? = null
        override suspend fun fetchAuthorizationLease(): Result<com.reeltrak.app.data.security.authorization.AuthorizationToken> =
            Result.failure(Exception("Not implemented"))
    }

    @Test
    fun testA_ActiveSubscriptionAtStartup_DoesNotEmitFalseLockedState() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = 1756720800000L)
        val manager = SubscriptionManager(repo, timeAuthority)

        val accessStates = CopyOnWriteArrayList<AccessState>()
        val confirmedLocks = CopyOnWriteArrayList<Boolean>()

        val collectScope = CoroutineScope(Dispatchers.Default)
        val job1 = collectScope.launch { manager.accessState.collect { accessStates.add(it) } }
        val job2 = collectScope.launch { manager.isConfirmedLocked.collect { confirmedLocks.add(it) } }

        Thread.sleep(50)

        // Repository now finishes loading from Firestore cache with active subscription
        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_active_123",
            currentPeriodStart = timeAuthority.currentTimeMs,
            currentPeriodEnd = timeAuthority.currentTimeMs + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)

        Thread.sleep(100)

        job1.cancel()
        job2.cancel()
        collectScope.cancel()

        // Verify: Never emitted LOCKED during startup
        assertFalse("AccessState must NEVER emit LOCKED for an active subscription at startup", accessStates.contains(AccessState.LOCKED))
        assertFalse("isConfirmedLocked must NEVER be true for an active subscription at startup", confirmedLocks.contains(true))

        // Final state is ACTIVE and unlocked
        assertEquals(AccessState.ACTIVE, manager.accessState.value)
        assertTrue(manager.isAppUnlockedNow())
        assertFalse(manager.isConfirmedLockedNow())
    }

    @Test
    fun testB_TrialSubscriptionAtStartup_DoesNotFalselyLock() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = 1756720800000L)
        val manager = SubscriptionManager(repo, timeAuthority)

        val accessStates = CopyOnWriteArrayList<AccessState>()
        val collectScope = CoroutineScope(Dispatchers.Default)
        val job = collectScope.launch { manager.accessState.collect { accessStates.add(it) } }

        Thread.sleep(50)

        val trialInfo = SubscriptionInfo(
            status = SubscriptionStatus.TRIAL,
            plan = SubscriptionPlan.FREE,
            trialStart = timeAuthority.currentTimeMs,
            trialEnd = timeAuthority.currentTimeMs + TimeUnit.DAYS.toMillis(7),
            accessState = AccessState.TRIAL
        )
        repo._subscriptionState.value = SubscriptionState.Success(trialInfo)

        Thread.sleep(100)

        job.cancel()
        collectScope.cancel()

        assertFalse("AccessState must NEVER emit LOCKED for an active trial at startup", accessStates.contains(AccessState.LOCKED))
        assertEquals(AccessState.TRIAL, manager.accessState.value)
        assertTrue(manager.isAppUnlockedNow())
        assertFalse(manager.isConfirmedLockedNow())
    }

    @Test
    fun testC_ActuallyExpiredSubscription_TransitionsToLocked() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = 1756720800000L)
        val manager = SubscriptionManager(repo, timeAuthority)

        // Expired subscription document loaded from server
        val expiredInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_expired_123",
            currentPeriodStart = timeAuthority.currentTimeMs - TimeUnit.DAYS.toMillis(60),
            currentPeriodEnd = timeAuthority.currentTimeMs - TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.LOCKED
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiredInfo)

        Thread.sleep(100)

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertFalse("Expired subscription must NOT be unlocked", manager.isAppUnlockedNow())
        assertTrue("Expired subscription must be confirmed locked", manager.isConfirmedLockedNow())
    }

    @Test
    fun testD_SubscriptionExpiringDuringRuntime_TransitionsToLockedCorrectly() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        // Expires in 1 second
        val expiringInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_expiring_123",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(30),
            currentPeriodEnd = now + 1000L,
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiringInfo)

        Thread.sleep(50)
        assertEquals(AccessState.ACTIVE, manager.accessState.value)

        // Advance authoritative time past expiration and past the 2-minute final sync grace window
        timeAuthority.currentTimeMs = now + 1000L + (3 * 60 * 1000L)

        // Wait for countdown ticker to re-evaluate combine
        Thread.sleep(1200)

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertFalse(manager.isAppUnlockedNow())
        assertTrue(manager.isConfirmedLockedNow())
    }

    @Test
    fun testE_BackgroundFor5SecondsAndResume_DoesNotProduceFalseLockedTransition() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = now,
            currentPeriodEnd = now + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)

        Thread.sleep(100)
        assertEquals(AccessState.ACTIVE, manager.accessState.value)

        // User backgrounds the app: all collectors stop
        // Wait simulated background interval
        Thread.sleep(100)

        // User returns from background: new UI collector subscribes
        val resumeEmissions = CopyOnWriteArrayList<AccessState>()
        val resumeScope = CoroutineScope(Dispatchers.Default)
        val resumeJob = resumeScope.launch {
            manager.accessState.collect { resumeEmissions.add(it) }
        }

        Thread.sleep(100)
        resumeJob.cancel()
        resumeScope.cancel()

        assertFalse("Resuming from background must not emit false LOCKED transition", resumeEmissions.contains(AccessState.LOCKED))
        assertTrue("Must contain ACTIVE", resumeEmissions.contains(AccessState.ACTIVE))
    }

    @Test
    fun testF_MissingOrInvalidSubscriptionState_FailsClosedToLocked() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority()
        val manager = SubscriptionManager(repo, timeAuthority)

        // Case 1: Unauthenticated / missing user
        repo._subscriptionState.value = SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT)
        Thread.sleep(50)

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertFalse(manager.isAppUnlockedNow())
        assertTrue(manager.isConfirmedLockedNow())

        // Case 2: Repository error fallback
        repo._subscriptionState.value = SubscriptionState.Error("Network error", SubscriptionInfo.LOCKED_DEFAULT)
        Thread.sleep(50)

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertFalse(manager.isAppUnlockedNow())
        assertTrue(manager.isConfirmedLockedNow())
    }

    @Test
    fun testG_ReelTrackerServiceGating_OnlyLocksWhenConfirmedLocked() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority()
        val manager = SubscriptionManager(repo, timeAuthority)

        var forcedIdleCount = 0
        val serviceScope = CoroutineScope(Dispatchers.Default)
        val lockObserver = serviceScope.launch {
            manager.isConfirmedLocked.collect { isLocked ->
                if (isLocked) {
                    forcedIdleCount++
                }
            }
        }

        // 1. Initial UNINITIALIZED/LOADING phase
        Thread.sleep(50)
        assertEquals("Must NOT force IDLE during initialization", 0, forcedIdleCount)
        assertFalse("Must NOT drop events during initialization", manager.isConfirmedLockedNow())

        // 2. ACTIVE subscription arrives
        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = timeAuthority.currentTimeMs,
            currentPeriodEnd = timeAuthority.currentTimeMs + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)
        Thread.sleep(50)

        assertEquals("Must NOT force IDLE for active subscription", 0, forcedIdleCount)
        assertFalse("Must NOT drop events for active subscription", manager.isConfirmedLockedNow())

        // 3. Subscription becomes confirmed LOCKED
        val expiredInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            plan = SubscriptionPlan.MONTHLY,
            accessState = AccessState.LOCKED
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiredInfo)
        Thread.sleep(50)

        assertEquals("Must force IDLE once confirmed LOCKED", 1, forcedIdleCount)
        assertTrue("Must reject events once confirmed LOCKED", manager.isConfirmedLockedNow())

        lockObserver.cancel()
        serviceScope.cancel()
    }

    @Test
    fun testH_FlowResubscription_DoesNotResetStateToIncorrectLockedValue() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority()
        val manager = SubscriptionManager(repo, timeAuthority)

        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = timeAuthority.currentTimeMs,
            currentPeriodEnd = timeAuthority.currentTimeMs + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)
        Thread.sleep(50)

        // Start and stop collector 1
        val scope1 = CoroutineScope(Dispatchers.Default)
        val job1 = scope1.launch { manager.accessState.collect {} }
        Thread.sleep(50)
        job1.cancel()
        scope1.cancel()

        // Restart collection (resubscription)
        val emissions = CopyOnWriteArrayList<AccessState>()
        val scope2 = CoroutineScope(Dispatchers.Default)
        val job2 = scope2.launch { manager.accessState.collect { emissions.add(it) } }
        Thread.sleep(50)
        job2.cancel()
        scope2.cancel()

        assertFalse("Restarting collection must NOT emit LOCKED", emissions.contains(AccessState.LOCKED))
        assertEquals(AccessState.ACTIVE, emissions.firstOrNull())
    }

    @Test
    fun testI_AlreadyExpiredSubscriptionCannotRemainActiveIndefinitely() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        // Expired 1 hour ago
        val expiredInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(30),
            currentPeriodEnd = now - TimeUnit.HOURS.toMillis(1),
            accessState = AccessState.LOCKED
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiredInfo)

        Thread.sleep(50)

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertFalse(manager.isAppUnlockedNow())
        assertTrue(manager.isConfirmedLockedNow())
    }

    @Test
    fun testJ_ColdStartInitializing_DoesNotEmitLockedBeforeServerVerification() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        val accessStates = CopyOnWriteArrayList<AccessState>()
        val collectScope = CoroutineScope(Dispatchers.Default)
        val job = collectScope.launch { manager.accessState.collect { accessStates.add(it) } }

        // While repo is in Loading (cache miss / awaiting server fetch)
        Thread.sleep(50)
        assertEquals("Initial state must be INITIALIZING while loading", AccessState.INITIALIZING, manager.accessState.value)
        assertFalse("App must not be confirmed locked while initializing", manager.isConfirmedLockedNow())

        // Server returns active subscription
        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            currentPeriodStart = now,
            currentPeriodEnd = now + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)
        Thread.sleep(50)

        job.cancel()
        collectScope.cancel()

        // Verify: NEVER traversed through LOCKED!
        assertFalse("AccessState must NEVER emit LOCKED when transitioning from INITIALIZING to ACTIVE", accessStates.contains(AccessState.LOCKED))
        assertEquals(AccessState.ACTIVE, manager.accessState.value)
        assertTrue(manager.isAppUnlockedNow())
    }

    @Test
    fun testK_NonSubscriber_TransitionsCleanlyFromInitializingToLocked() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        val accessStates = CopyOnWriteArrayList<AccessState>()
        val collectScope = CoroutineScope(Dispatchers.Default)
        val job = collectScope.launch { manager.accessState.collect { accessStates.add(it) } }

        Thread.sleep(50)
        assertEquals(AccessState.INITIALIZING, manager.accessState.value)

        // Server confirms document does not exist -> LOCKED_DEFAULT
        repo._subscriptionState.value = SubscriptionState.Success(SubscriptionInfo.LOCKED_DEFAULT)
        Thread.sleep(50)

        job.cancel()
        collectScope.cancel()

        assertEquals(AccessState.LOCKED, manager.accessState.value)
        assertTrue(manager.isConfirmedLockedNow())
        assertFalse(manager.isAppUnlockedNow())
    }
}


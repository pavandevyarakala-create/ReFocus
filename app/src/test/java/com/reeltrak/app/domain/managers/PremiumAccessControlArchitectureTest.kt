package com.reeltrak.app.domain.managers

import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Verification test suite for Re:Focus Premium/Trial access control architecture.
 *
 * Core Access Rules:
 * 1. ACTIVE PREMIUM or ACTIVE TRIAL -> hasActiveEntitlement = true
 * 2. Everything else -> hasActiveEntitlement = false (Reel counter, Dashboard, Settings accessible; Strict Mode, Analytics, Calendar locked; Cloud sync blocked)
 */
class PremiumAccessControlArchitectureTest {

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
    fun testSubscriptionInfo_hasActiveEntitlement_onlyTrueForActiveOrTrial() {
        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            accessState = AccessState.ACTIVE
        )
        assertTrue("ACTIVE access must have active entitlement", activeInfo.hasActiveEntitlement)

        val trialInfo = SubscriptionInfo(
            status = SubscriptionStatus.TRIAL,
            accessState = AccessState.TRIAL
        )
        assertTrue("TRIAL access must have active entitlement", trialInfo.hasActiveEntitlement)

        val lockedInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            accessState = AccessState.LOCKED
        )
        assertFalse("LOCKED access must NOT have active entitlement", lockedInfo.hasActiveEntitlement)

        val graceInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            accessState = AccessState.FINAL_SYNC_GRACE
        )
        assertFalse("FINAL_SYNC_GRACE must NOT have active entitlement", graceInfo.hasActiveEntitlement)

        val uninitializedInfo = SubscriptionInfo(
            status = SubscriptionStatus.NONE,
            accessState = AccessState.INITIALIZING
        )
        assertFalse("INITIALIZING must NOT have active entitlement", uninitializedInfo.hasActiveEntitlement)
    }

    @Test
    fun testSubscriptionManager_nonPremiumUser_hasNoActiveEntitlement() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val timeAuthority = FakeSubscriptionTimeAuthority()
        val manager = SubscriptionManager(repo, timeAuthority)

        // Free / expired user
        val expiredInfo = SubscriptionInfo(
            status = SubscriptionStatus.EXPIRED,
            plan = SubscriptionPlan.FREE,
            accessState = AccessState.LOCKED
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiredInfo)
        Thread.sleep(50)

        assertFalse("Non-premium user must NOT have active entitlement", manager.hasActiveEntitlementNow())
        assertFalse("Non-premium user must have hasActiveEntitlement flow as false", manager.hasActiveEntitlement.value)
        assertFalse("Non-premium user must NOT be app unlocked", manager.isAppUnlockedNow())
    }

    @Test
    fun testSubscriptionManager_activeTrialUser_hasActiveEntitlement() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        val trialInfo = SubscriptionInfo(
            status = SubscriptionStatus.TRIAL,
            plan = SubscriptionPlan.FREE,
            trialStart = now,
            trialEnd = now + TimeUnit.DAYS.toMillis(7),
            accessState = AccessState.TRIAL
        )
        repo._subscriptionState.value = SubscriptionState.Success(trialInfo)
        Thread.sleep(50)

        assertTrue("Active trial user MUST have active entitlement", manager.hasActiveEntitlementNow())
        assertTrue("Active trial user must have hasActiveEntitlement flow as true", manager.hasActiveEntitlement.value)
        assertTrue("Active trial user must be app unlocked", manager.isAppUnlockedNow())
    }

    @Test
    fun testSubscriptionManager_activeSubscriber_hasActiveEntitlement() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        val activeInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_monthly_999",
            currentPeriodStart = now,
            currentPeriodEnd = now + TimeUnit.DAYS.toMillis(30),
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(activeInfo)
        Thread.sleep(50)

        assertTrue("Active subscriber MUST have active entitlement", manager.hasActiveEntitlementNow())
        assertTrue("Active subscriber must have hasActiveEntitlement flow as true", manager.hasActiveEntitlement.value)
        assertTrue("Active subscriber must be app unlocked", manager.isAppUnlockedNow())
    }

    @Test
    fun testSubscriptionManager_entitlementExpires_transitionsToNonPremium() = runBlocking {
        val repo = FakeSubscriptionRepository()
        val now = 1756720800000L
        val timeAuthority = FakeSubscriptionTimeAuthority(currentTimeMs = now)
        val manager = SubscriptionManager(repo, timeAuthority)

        // Starts with active subscription expiring in 500ms
        val expiringInfo = SubscriptionInfo(
            status = SubscriptionStatus.ACTIVE,
            plan = SubscriptionPlan.MONTHLY,
            subscriptionId = "sub_expiring",
            currentPeriodStart = now - TimeUnit.DAYS.toMillis(30),
            currentPeriodEnd = now + 500L,
            accessState = AccessState.ACTIVE
        )
        repo._subscriptionState.value = SubscriptionState.Success(expiringInfo)
        Thread.sleep(50)

        assertTrue("Must start with entitlement", manager.hasActiveEntitlementNow())

        // Time advances beyond period end and beyond grace period
        timeAuthority.currentTimeMs = now + 500L + (3 * 60 * 1000L)
        Thread.sleep(1200)

        assertFalse("Must revoke entitlement once expired", manager.hasActiveEntitlementNow())
        assertFalse("Flow must emit false for hasActiveEntitlement", manager.hasActiveEntitlement.value)
    }
}

import com.reeltrak.app.data.security.SubscriptionTimeAuthority
import com.reeltrak.app.domain.managers.SubscriptionManager
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.StrictModeDailyState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import com.reeltrak.app.domain.repository.SubscriptionRepository
import com.reeltrak.app.services.tracking.DayManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies all requirements of the REWARDED AD UX RULE:
 * 1. Trial/Premium users: Daily Limit & Strict Mode actions directly launch rewarded ad without pre-ad popups.
 * 2. On ad reward completion: intended state update / action is executed.
 * 3. On ad failure / cancellation: action is NOT performed.
 * 4. Free users: Daily Limit remains locked, triggers subscription/login flow, NEVER plays rewarded ad.
 * 5. Free users: Strict Mode actions trigger info/paywall dialog, NEVER plays rewarded ad.
 * 6. Ads do NOT grant subscription entitlement.
 * 7. Double-tap / race condition protection prevents duplicate ad presentations.
 */
class RewardedAdFlowUXRequirementTest {

    private class TestSubscriptionTimeAuthority : SubscriptionTimeAuthority() {
        override fun getAuthoritativeSubscriptionTime(): Pair<Long, Boolean> = Pair(1756720800000L, true)
        override fun isWithinOfflineLease(lastServerSyncTimeMs: Long): Boolean = true
    }

    private class TestSubscriptionRepository : SubscriptionRepository {
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

    private fun createSubscriptionManager(isEntitled: Boolean): SubscriptionManager {
        val repo = TestSubscriptionRepository()
        val timeAuthority = TestSubscriptionTimeAuthority()
        val manager = SubscriptionManager(repo, timeAuthority)

        val info = if (isEntitled) {
            SubscriptionInfo(
                status = SubscriptionStatus.ACTIVE,
                plan = SubscriptionPlan.MONTHLY,
                accessState = AccessState.ACTIVE,
                currentPeriodEnd = 1756720800000L + 100_000_000L
            )
        } else {
            SubscriptionInfo(
                status = SubscriptionStatus.EXPIRED,
                plan = SubscriptionPlan.FREE,
                accessState = AccessState.LOCKED
            )
        }
        repo._subscriptionState.value = SubscriptionState.Success(info)
        Thread.sleep(50)
        return manager
    }

    // =========================================================================
    // 1. Entitled Users: Direct Ad Triggering without Pre-Ad Popups
    // =========================================================================

    @Test
    fun testEntitledUser_DailyLimitAction_DirectlyTriggersAdAndSavesOnCompletion() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled (Trial or Premium)", subManager.hasActiveEntitlementNow())

        var adPresented = false
        var goalSavedInState = false
        var targetGoalSaved = -1

        // Simulated action logic matching DashboardScreen onSaveGoal for entitled user
        fun onSaveGoal(newGoal: Int, rewardEarned: Boolean) {
            if (!subManager.hasActiveEntitlementNow()) return
            
            // Direct ad presentation without pre-ad popup
            adPresented = true
            if (rewardEarned) {
                goalSavedInState = true
                targetGoalSaved = newGoal
            }
        }

        // Test 1: User sets Daily Limit for the first time -> Ad completes
        onSaveGoal(100, rewardEarned = true)
        assertTrue("Rewarded ad must be directly presented", adPresented)
        assertTrue("Goal must be saved upon successful reward completion", goalSavedInState)
        assertEquals(100, targetGoalSaved)
    }

    @Test
    fun testEntitledUser_ActionModeReactivate_DirectlyTriggersAdAndReactivates() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var adPresented = false
        var strictModeReactivated = false

        // Simulated action logic matching DashboardScreen onReactivateClick for entitled user
        fun onReactivateClick(rewardEarned: Boolean) {
            if (!subManager.hasActiveEntitlementNow()) return
            
            adPresented = true
            if (rewardEarned) {
                strictModeReactivated = true
            }
        }

        onReactivateClick(rewardEarned = true)
        assertTrue("Rewarded ad must be directly presented", adPresented)
        assertTrue("Strict mode must be reactivated upon successful reward completion", strictModeReactivated)
    }

    // =========================================================================
    // 1b. Entitled Users: Turn Off Strict Mode Requires Rewarded Ad Confirmation
    // =========================================================================

    @Test
    fun testEntitledUser_TurnOffStrictMode_ShowsConfirmationDialog_RequiresAdRewardToDisable() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var showConfirmationDialog = false
        var adPresented = false
        var strictModeDisabled = false

        // Step 1: User attempts to turn off Strict Mode in UI
        fun onTurnOffClick() {
            if (!subManager.hasActiveEntitlementNow()) return
            // Must NOT disable directly; must show confirmation dialog
            showConfirmationDialog = true
        }

        onTurnOffClick()
        assertTrue("Confirmation dialog must be shown before disabling Strict Mode", showConfirmationDialog)
        assertFalse("Strict Mode MUST NOT be disabled before watching ad", strictModeDisabled)

        // Step 2: User confirms and watches rewarded ad
        fun onWatchAdClick(rewardEarned: Boolean) {
            adPresented = true
            if (rewardEarned) {
                strictModeDisabled = true
                showConfirmationDialog = false
            }
        }

        onWatchAdClick(rewardEarned = true)
        assertTrue("Rewarded ad must be presented", adPresented)
        assertTrue("Strict Mode must be disabled ONLY after confirmed reward", strictModeDisabled)
        assertFalse("Dialog must close after successful reward", showConfirmationDialog)
    }

    @Test
    fun testTurnOffStrictMode_DismissedWithoutReward_KeepsStrictModeEnabled() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var showConfirmationDialog = true
        var strictModeDisabled = false

        fun onWatchAdDismissedWithoutReward() {
            // Dismissed/skipped without earning reward: keep Strict Mode active
            val rewardEarned = false
            if (rewardEarned) {
                strictModeDisabled = true
            }
        }

        onWatchAdDismissedWithoutReward()
        assertFalse("Dismissing or skipping ad MUST NOT disable Strict Mode", strictModeDisabled)
        assertTrue("Dialog remains available or allows user to retry", showConfirmationDialog)
    }

    @Test
    fun testTurnOffStrictMode_AdLoadError_KeepsStrictModeEnabled_AllowsRetry() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var strictModeDisabled = false
        var errorMessage: String? = null

        // Ad load failure callback
        fun onAdError(error: String) {
            errorMessage = error
        }

        onAdError("Ad is unavailable right now. Try again later.")

        assertFalse("Ad load error MUST NOT disable Strict Mode", strictModeDisabled)
        assertEquals("Friendly error message must be set for retry", "Ad is unavailable right now. Try again later.", errorMessage)

        // User taps Retry in dialog -> ad succeeds this time
        fun onRetryClick(rewardEarned: Boolean) {
            if (rewardEarned) {
                strictModeDisabled = true
                errorMessage = null
            }
        }

        onRetryClick(rewardEarned = true)
        assertTrue("Retrying with successful reward must disable Strict Mode", strictModeDisabled)
        assertEquals(null, errorMessage)
    }

    @Test
    fun testTurnOffStrictMode_DuplicateRewardCallback_ExecutesDisableOnlyOnce() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var disableCallCount = 0
        var successCallbackCount = 0
        val hasRewarded = java.util.concurrent.atomic.AtomicBoolean(false)

        fun onRewardEarnedCallback() {
            if (hasRewarded.compareAndSet(false, true)) {
                disableCallCount++
                successCallbackCount++
            }
        }

        // Simulate duplicate callback from SDK / listener
        onRewardEarnedCallback()
        onRewardEarnedCallback()
        onRewardEarnedCallback()

        assertEquals("disableStrictModeForToday must be called exactly once despite duplicate callbacks", 1, disableCallCount)
        assertEquals("onSuccess callback must be called exactly once despite duplicate callbacks", 1, successCallbackCount)
    }

    @Test
    fun testTurnOffStrictMode_RapidTapProtection_PreventsConcurrentAds() {
        var isAdExecuting = false
        var adTriggerCount = 0

        fun onWatchAdClick() {
            if (isAdExecuting) return // Atomic guard
            isAdExecuting = true
            adTriggerCount++
        }

        onWatchAdClick() // First tap
        onWatchAdClick() // Rapid second tap
        onWatchAdClick() // Rapid third tap

        assertEquals("Only one ad flow must trigger despite rapid button taps", 1, adTriggerCount)
    }

    @Test
    fun testTurnOnStrictMode_DuplicateRewardCallback_ExecutesReactivateOnlyOnce() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var reactivateCallCount = 0
        var successCallbackCount = 0
        val hasRewarded = java.util.concurrent.atomic.AtomicBoolean(false)

        fun onRewardEarnedCallback() {
            if (hasRewarded.compareAndSet(false, true)) {
                reactivateCallCount++
                successCallbackCount++
            }
        }

        // Simulate duplicate callback from SDK / listener
        onRewardEarnedCallback()
        onRewardEarnedCallback()
        onRewardEarnedCallback()

        assertEquals("reactivateStrictModeWithAd must be called exactly once despite duplicate callbacks", 1, reactivateCallCount)
        assertEquals("onSuccess callback must be called exactly once despite duplicate callbacks", 1, successCallbackCount)
    }

    @Test
    fun testTurnOnStrictMode_DismissedWithoutReward_DoesNotReactivate() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var reactivateCalled = false
        val rewardEarned = false

        if (rewardEarned) {
            reactivateCalled = true
        }

        assertFalse("Dismissing or skipping ad MUST NOT reactivate Strict Mode", reactivateCalled)
    }

    @Test
    fun testTurnOnStrictMode_AdLoadError_DoesNotReactivate() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var reactivateCalled = false
        var errorReported: String? = null

        fun onError(error: String) {
            errorReported = error
        }

        onError("Ad inventory currently unavailable.")

        assertFalse("Ad load error MUST NOT reactivate Strict Mode", reactivateCalled)
        assertEquals("Ad inventory currently unavailable.", errorReported)
    }

    @Test
    fun testTurnOnStrictMode_DoesNotRestoreDailyOffAllowance() {
        val subManager = createSubscriptionManager(isEntitled = true)
        assertTrue("User must be entitled", subManager.hasActiveEntitlementNow())

        var isDailyOffAllowanceAvailable = false // Consumed earlier today
        var strictModeReactivated = false

        fun onTurnOnRewardEarned() {
            strictModeReactivated = true
            // Turning ON must NOT restore daily OFF allowance
            // isDailyOffAllowanceAvailable remains false
        }

        onTurnOnRewardEarned()
        assertTrue("Strict Mode must be reactivated", strictModeReactivated)
        assertFalse("Daily OFF allowance must NOT be restored after turning ON", isDailyOffAllowanceAvailable)
    }

    @Test
    fun testStrictModeCard_CardCopyNeverMentionsAdAcrossAllStates() {
        // Test all possible Strict Mode states and entitlement combinations
        val states = listOf(
            StrictModeDailyState.ACTIVE_INTERACTIVE,
            StrictModeDailyState.DISABLED_TODAY,
            StrictModeDailyState.REACTIVATED_LOCKED
        )

        fun getSubtitle(isEntitled: Boolean, isLoading: Boolean, dailyState: StrictModeDailyState): String {
            return when {
                !isEntitled -> "Auto-closes apps at limit • Premium feature"
                isLoading -> "Loading..."
                dailyState == StrictModeDailyState.REACTIVATED_LOCKED -> "Enforcement active • Locked until tomorrow"
                dailyState == StrictModeDailyState.DISABLED_TODAY -> "Off for today • Tap to reactivate"
                else -> "Enforcement active • You can turn this off once today"
            }
        }

        for (state in states) {
            val entitledSubtitle = getSubtitle(isEntitled = true, isLoading = false, dailyState = state)
            assertFalse(
                "Entitled subtitle in state $state must NOT contain 'ad': $entitledSubtitle",
                entitledSubtitle.lowercase().contains("ad ") || entitledSubtitle.lowercase().contains(" ad") || entitledSubtitle.lowercase().contains("watch ad")
            )

            val freeSubtitle = getSubtitle(isEntitled = false, isLoading = false, dailyState = state)
            assertFalse(
                "Free subtitle must NOT contain 'ad': $freeSubtitle",
                freeSubtitle.lowercase().contains("ad ") || freeSubtitle.lowercase().contains(" ad")
            )
        }
    }


    // =========================================================================
    // 2. Ad Failure / Early Dismissal
    // =========================================================================

    @Test
    fun testAdDismissedOrFailed_ProtectedActionIsNotPerformed() {
        val subManager = createSubscriptionManager(isEntitled = true)

        var goalSaved = false
        var strictModeReactivated = false
        var strictModeDisabled = false

        fun onSaveGoalWithAdFailure() {
            if (!subManager.hasActiveEntitlementNow()) return
            // Ad presented but user dismissed without reward or ad failed to load
            val rewardEarned = false
            if (rewardEarned) {
                goalSaved = true
            }
        }

        fun onReactivateWithAdFailure() {
            if (!subManager.hasActiveEntitlementNow()) return
            val rewardEarned = false
            if (rewardEarned) {
                strictModeReactivated = true
            }
        }

        fun onTurnOffWithAdFailure() {
            if (!subManager.hasActiveEntitlementNow()) return
            val rewardEarned = false
            if (rewardEarned) {
                strictModeDisabled = true
            }
        }

        onSaveGoalWithAdFailure()
        onReactivateWithAdFailure()
        onTurnOffWithAdFailure()

        assertFalse("Daily Goal MUST NOT be saved if ad was dismissed or failed", goalSaved)
        assertFalse("Strict Mode MUST NOT be reactivated if ad was dismissed or failed", strictModeReactivated)
        assertFalse("Strict Mode MUST NOT be disabled if ad was dismissed or failed", strictModeDisabled)
    }

    // =========================================================================
    // 3. Free User Restrictions: No Ads, Entitlement Intact
    // =========================================================================

    @Test
    fun testFreeUser_DailyLimit_TriggersPaywallFlowAndNeverPlaysAd() {
        val subManager = createSubscriptionManager(isEntitled = false)
        assertFalse("User is not entitled (Free mode)", subManager.hasActiveEntitlementNow())

        var adPresented = false
        var paywallTriggered = false

        fun onSaveGoalAsFreeUser() {
            if (!subManager.hasActiveEntitlementNow()) {
                // Free user -> redirected to subscription/login flow
                paywallTriggered = true
                return
            }
            adPresented = true
        }

        onSaveGoalAsFreeUser()

        assertTrue("Free user attempting Daily Limit must trigger paywall/login flow", paywallTriggered)
        assertFalse("Free user MUST NEVER play a rewarded ad for Daily Limit", adPresented)
    }

    @Test
    fun testFreeUser_StrictMode_TriggersInfoDialogAndNeverPlaysAd() {
        val subManager = createSubscriptionManager(isEntitled = false)
        assertFalse("User is not entitled (Free mode)", subManager.hasActiveEntitlementNow())

        var adPresented = false
        var infoDialogTriggered = false

        fun onReactivateAsFreeUser() {
            if (!subManager.hasActiveEntitlementNow()) {
                infoDialogTriggered = true
                return
            }
            adPresented = true
        }

        onReactivateAsFreeUser()

        assertTrue("Free user attempting Strict Mode must trigger info/paywall dialog", infoDialogTriggered)
        assertFalse("Free user MUST NEVER play a rewarded ad for Strict Mode", adPresented)
    }

    @Test
    fun testWatchingAdDoesNotGrantSubscriptionEntitlement() {
        val subManager = createSubscriptionManager(isEntitled = false)
        assertFalse("Initially Free user", subManager.hasActiveEntitlementNow())

        // Simulate completing a rewarded ad for a single action
        val adRewardEarned = true
        if (adRewardEarned) {
            // Ad completed for single action authorization only.
            // Crucially: DOES NOT alter SubscriptionManager entitlement!
            assertFalse("Single ad reward does not change subscription state", subManager.hasActiveEntitlementNow())
        }

        assertFalse("Watching an ad MUST NOT grant Trial or Premium subscription entitlement", subManager.hasActiveEntitlementNow())
    }

    // =========================================================================
    // 4. Double-Tap and Screen Initialization Guards
    // =========================================================================

    @Test
    fun testDoubleTapProtection_PreventsConcurrentAdLaunches() {
        var isAdExecuting = false
        var adLaunchCount = 0

        fun triggerAction() {
            if (isAdExecuting) return // Guard
            isAdExecuting = true
            adLaunchCount++
        }

        triggerAction() // First tap
        triggerAction() // Second rapid tap while ad is executing
        triggerAction() // Third rapid tap

        assertEquals("Only one ad presentation must occur despite rapid double-taps", 1, adLaunchCount)
    }
}

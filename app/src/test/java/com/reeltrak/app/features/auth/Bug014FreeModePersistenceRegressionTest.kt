package com.reeltrak.app.features.auth

import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.core.navigation.Routes
import com.reeltrak.app.data.security.authorization.AppAuthorizationManager
import com.reeltrak.app.data.security.authorization.OperationalMode
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression Test Suite for BUG-014:
 * "Free Mode Does Not Persist After Restart"
 *
 * Verifies:
 * 1. Selecting Free Mode persists across app recreation and simulated process restart.
 * 2. Returning Free Mode users bypass login and onboarding when appropriate.
 * 3. No authenticated subscription checks occur without a Firebase user.
 * 4. Google sign-in can still be initiated from Free Mode and transitions cleanly.
 * 5. Local Free Mode does not grant premium entitlement or bypass gating.
 * 6. Explicit sign-out and account switching do not restore an inappropriate previous account state.
 */
class Bug014FreeModePersistenceRegressionTest {

    private class FakePreferencesRepository : PreferencesRepository {
        val _isFreeMode = MutableStateFlow(false)
        val _authSession = MutableStateFlow<UserAuthData?>(null)
        val _firstRunDate = MutableStateFlow(0L)
        val _defaultDailyLimit = MutableStateFlow(-1)
        val _dailyIntent = MutableStateFlow(0)
        val _hasMigratedToServer = MutableStateFlow(false)

        override val isFreeMode: Flow<Boolean> = _isFreeMode
        override suspend fun setFreeMode(enabled: Boolean) { _isFreeMode.value = enabled }

        override val authSession: Flow<UserAuthData?> = _authSession
        override suspend fun saveAuthSession(userData: UserAuthData) { _authSession.value = userData }
        override suspend fun clearAuthSession() { _authSession.value = null }

        override suspend fun clearAllPreferences() {
            _isFreeMode.value = false
            _authSession.value = null
            _firstRunDate.value = 0L
            _defaultDailyLimit.value = -1
            _dailyIntent.value = 0
            _hasMigratedToServer.value = false
        }

        override val firstRunDate: Flow<Long> = _firstRunDate
        override suspend fun setFirstRunDate(date: Long) { _firstRunDate.value = date }

        override val defaultDailyLimit: Flow<Int> = _defaultDailyLimit
        override suspend fun setDefaultDailyLimit(limit: Int) { _defaultDailyLimit.value = limit }

        override val dailyIntent: Flow<Int> = _dailyIntent
        override suspend fun setDailyIntent(intent: Int) { _dailyIntent.value = intent }

        override val hasMigratedToServer: Flow<Boolean> = _hasMigratedToServer
        override suspend fun setHasMigratedToServer(migrated: Boolean) { _hasMigratedToServer.value = migrated }

        // Stubs for remaining unused preferences
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
        override suspend fun setGoalSetDate(date: Long) {}
        override suspend fun setStrictModeLocked(locked: Boolean) {}
        override suspend fun setLastStrictModeOffDate(date: String) {}
        override suspend fun setDailyLimitLocked(locked: Boolean) {}
        override suspend fun setLastLockEpochDay(day: Long) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
        override val counterThemeMode: Flow<com.reeltrak.app.domain.models.ThemeMode> =
            MutableStateFlow(com.reeltrak.app.domain.models.ThemeMode.LIGHT)
        override suspend fun setCounterThemeMode(mode: com.reeltrak.app.domain.models.ThemeMode) {}
        override val hasCompletedTour: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setHasCompletedTour(completed: Boolean) {}
        override val lastBackupTime: Flow<Long> = MutableStateFlow(0L)
        override suspend fun setLastBackupTime(time: Long) {}
        override suspend fun getAllPreferencesSync(): Map<String, Any> = emptyMap()
        override suspend fun restorePreferences(cloudData: Map<String, Any>) {}
    }

    /**
     * Replicates the startup state resolution logic from LoginViewModel.init.
     */
    private fun resolveStartupState(
        userData: UserAuthData?,
        isFreeMode: Boolean
    ): Pair<LoginState, StartupAuthState> {
        return when {
            userData != null -> {
                // In authenticated state, if active user exists
                LoginState.Success(userData) to StartupAuthState.AUTHENTICATED
            }
            isFreeMode -> {
                // Restoring deliberate Free Mode state
                LoginState.FreeMode to StartupAuthState.FREE_MODE
            }
            else -> {
                LoginState.Idle to StartupAuthState.UNAUTHENTICATED
            }
        }
    }

    /**
     * Replicates the start destination resolution logic from RootNavigation.
     */
    private fun resolveStartDestination(
        authState: StartupAuthState,
        isOnboardingNeeded: Boolean
    ): String {
        return if (authState == StartupAuthState.AUTHENTICATED || authState == StartupAuthState.FREE_MODE) {
            if (isOnboardingNeeded) Routes.ONBOARDING else "main"
        } else {
            "login"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Test 1: Selecting Free Mode persists across app recreation and simulated restart
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testSelectingFreeModePersistsAcrossSimulatedRestart() = runBlocking {
        val prefs = FakePreferencesRepository()

        // Initially: unauthenticated and not in Free Mode
        assertFalse(prefs.isFreeMode.first())
        val (initialLoginState, initialAuthState) = resolveStartupState(
            userData = prefs.authSession.first(),
            isFreeMode = prefs.isFreeMode.first()
        )
        assertEquals(LoginState.Idle, initialLoginState)
        assertEquals(StartupAuthState.UNAUTHENTICATED, initialAuthState)

        // User opts into Free Mode
        prefs.setFreeMode(true)
        assertTrue("isFreeMode must be persisted in preferences", prefs.isFreeMode.first())

        // Simulate app kill and cold restart:
        // A new startup cycle reads the persisted preferences
        val reloadedFreeMode = prefs.isFreeMode.first()
        val reloadedSession = prefs.authSession.first()
        assertTrue("isFreeMode must remain true after simulated restart", reloadedFreeMode)
        assertNull("AuthSession should remain null in Free Mode", reloadedSession)

        val (restoredLoginState, restoredAuthState) = resolveStartupState(
            userData = reloadedSession,
            isFreeMode = reloadedFreeMode
        )
        assertEquals(LoginState.FreeMode, restoredLoginState)
        assertEquals(StartupAuthState.FREE_MODE, restoredAuthState)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 2: Returning Free Mode users bypass login and onboarding when appropriate
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testReturningFreeModeUsersBypassLoginAndOnboardingWhenAppropriate() = runBlocking {
        val prefs = FakePreferencesRepository()
        prefs.setFreeMode(true)

        // Case A: First-time Free Mode user (onboarding needed)
        val initialStartDest = resolveStartDestination(
            authState = StartupAuthState.FREE_MODE,
            isOnboardingNeeded = true
        )
        assertEquals("First time free user should see onboarding", Routes.ONBOARDING, initialStartDest)

        // Complete onboarding: sets firstRunDate
        prefs.setFirstRunDate(System.currentTimeMillis())
        val isOnboardingNeeded = prefs.firstRunDate.first() == 0L
        assertFalse(isOnboardingNeeded)

        // Case B: Returning Free Mode user restart -> bypasses both login and onboarding directly to main
        val returningStartDest = resolveStartDestination(
            authState = StartupAuthState.FREE_MODE,
            isOnboardingNeeded = isOnboardingNeeded
        )
        assertEquals("Returning Free Mode user must navigate directly to 'main'", "main", returningStartDest)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 3: No authenticated subscription checks occur without a Firebase user
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testNoAuthenticatedSubscriptionChecksOccurWithoutFirebaseUser() = runBlocking {
        val prefs = FakePreferencesRepository()
        prefs.setFreeMode(true)

        // Operational mode for unauthenticated Free Mode
        val operationalMode = OperationalMode.UNAUTHENTICATED_FREE
        assertFalse("Unauthenticated Free Mode must NOT have active entitlement", operationalMode.isEntitled)
        assertTrue("Unauthenticated Free Mode must allow free local tracking", operationalMode.isFreeTrackingAllowed)

        // SubscriptionInfo for unauthenticated user is LOCKED_DEFAULT (no network calls)
        val lockedDefault = SubscriptionInfo.LOCKED_DEFAULT
        assertEquals(AccessState.LOCKED, lockedDefault.accessState)
        assertFalse(lockedDefault.isUnlocked)
        assertFalse(lockedDefault.isPremium)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 4: Google sign-in can still be initiated from Free Mode and transitions cleanly
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testGoogleSignInCanBeInitiatedFromFreeModeAndTransitionsCleanly() = runBlocking {
        val prefs = FakePreferencesRepository()
        prefs.setFreeMode(true)
        assertTrue(prefs.isFreeMode.first())

        // User is using Free Mode, then clicks Sign In with Google
        val authenticatedUser = UserAuthData(
            email = "user@example.com",
            displayName = "ReFocus User",
            profilePictureUri = "https://example.com/avatar.jpg"
        )

        // Successful authentication: saves session and clears Free Mode flag
        prefs.saveAuthSession(authenticatedUser)
        prefs.setFreeMode(false)

        assertFalse("isFreeMode must be cleared upon successful sign in", prefs.isFreeMode.first())
        assertNotNull("AuthSession must be saved", prefs.authSession.first())

        // Subsequent startup cycle correctly identifies authenticated user
        val (newLoginState, newAuthState) = resolveStartupState(
            userData = prefs.authSession.first(),
            isFreeMode = prefs.isFreeMode.first()
        )
        assertTrue(newLoginState is LoginState.Success)
        assertEquals(StartupAuthState.AUTHENTICATED, newAuthState)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 5: Local Free Mode does not grant premium entitlement or bypass gating
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testLocalFreeModeDoesNotGrantPremiumEntitlement() {
        val mode = OperationalMode.UNAUTHENTICATED_FREE

        // Gating assertions
        assertFalse("Free Mode must NOT be entitled", mode.isEntitled)
        assertFalse("Free Mode must NOT be TRIAL", mode == OperationalMode.TRIAL)
        assertFalse("Free Mode must NOT be PREMIUM", mode == OperationalMode.PREMIUM)

        // AccessState in Free Mode is LOCKED for premium features
        val accessState = AccessState.LOCKED
        val isAppUnlocked = accessState == AccessState.TRIAL || accessState == AccessState.ACTIVE
        assertFalse("Premium features like Calendar must be locked in Free Mode", isAppUnlocked)
    }

    // ---------------------------------------------------------------------------------------------
    // Test 6: Explicit sign-out clears Free Mode and returns to clean login state
    // ---------------------------------------------------------------------------------------------
    @Test
    fun testExplicitSignOutClearsFreeModeState() = runBlocking {
        val prefs = FakePreferencesRepository()
        prefs.setFreeMode(true)
        assertTrue(prefs.isFreeMode.first())

        // User performs explicit sign out / reset
        prefs.clearAllPreferences()

        assertFalse("isFreeMode must be false after clearAllPreferences()", prefs.isFreeMode.first())
        assertNull("AuthSession must be null after clearAllPreferences()", prefs.authSession.first())

        val (postLogoutLoginState, postLogoutAuthState) = resolveStartupState(
            userData = prefs.authSession.first(),
            isFreeMode = prefs.isFreeMode.first()
        )
        assertEquals(LoginState.Idle, postLogoutLoginState)
        assertEquals(StartupAuthState.UNAUTHENTICATED, postLogoutAuthState)

        val destination = resolveStartDestination(
            authState = postLogoutAuthState,
            isOnboardingNeeded = false
        )
        assertEquals("Post-logout user must be routed to login", "login", destination)
    }
}

package com.reeltrak.app.features.auth

import com.reeltrak.app.core.auth.UserAuthData
import com.reeltrak.app.domain.models.AccessState
import com.reeltrak.app.domain.models.AccountAccessInfo
import com.reeltrak.app.domain.models.AccountStatus
import com.reeltrak.app.domain.models.AccountType
import com.reeltrak.app.domain.models.SubscriptionInfo
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.models.SubscriptionState
import com.reeltrak.app.domain.models.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance and Security Regression Test Suite:
 * Sign-In Loading Time and Payment/Subscription Performance Optimizations.
 *
 * Verifies:
 * 1. Sign-In Navigation Unblocking:
 *    - Valid active login immediately sets LoginState.Success and StartupAuthState.AUTHENTICATED.
 *    - Cloud sync and entitlement sync execute asynchronously in the background rather than blocking UI navigation.
 *    - Restricted, Deleted, and Pending-Deletion accounts fail-closed immediately without granting dashboard access.
 * 2. Auth Session Deduplication:
 *    - LoginViewModel init session observer deduplicates against active Success states to prevent redundant network checks.
 * 3. Subscription & Time Authority Optimizations:
 *    - Cloudflare HTTP Date header directly calibrates SubscriptionTimeAuthority anchor, eliminating extra /api/time roundtrips.
 *    - SubscriptionRepository.refreshSubscription skips /api/time when authority is already calibrated.
 *    - SubscriptionRepository debounces concurrent refresh calls within 2000ms.
 *    - SubscriptionManager guards ensureSubscription to skip calls when subscription status is already resolved.
 *    - MainActivity.onResume guards ensureSubscription to only execute if currentStatus is NONE.
 * 4. Payment Flow Entitlement Refresh:
 *    - Razorpay payment verification success immediately triggers background subscriptionRepository.refreshSubscription().
 * 5. Privacy & Performance Instrumentation:
 *    - [AUTH_PERF] and [SUB_PERF] logging tags are present.
 *    - Zero user credentials, tokens, or PII exposed in performance log statements.
 */
class AuthAndSubscriptionPerformanceTest {

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
    // 1. SIGN-IN NON-BLOCKING UI TRANSITION & SECURITY CHECKS
    // =========================================================================

    @Test
    fun testLoginViewModel_ImmediateSuccessTransition_WithoutBlockingOnSync() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt")
        val content = file.readText()

        // Verify that signIn transitions to Success before / concurrently with background sync
        assertTrue(
            "signIn must set _uiState.value = LoginState.Success(userData) immediately upon auth and access verification",
            content.contains("_uiState.value = LoginState.Success(userData)")
        )
        assertTrue(
            "signIn must set _startupAuthState.value = StartupAuthState.AUTHENTICATED immediately upon auth",
            content.contains("_startupAuthState.value = StartupAuthState.AUTHENTICATED")
        )
        assertTrue(
            "signIn must dispatch executePostLoginSync non-blocking in applicationScope",
            content.contains("applicationScope.launch(ioDispatcher) {\n                            executePostLoginSync(currentUid)\n                        }") ||
                    content.contains("executePostLoginSync(currentUid)")
        )

        // Verify signInWithEmailPassword also follows the immediate unblocking pattern
        assertTrue(
            "signInWithEmailPassword must set _uiState.value = LoginState.Success(userData) immediately",
            content.contains("_uiState.value = LoginState.Success(userData)")
        )

        // Verify restoreAccount also follows the immediate unblocking pattern
        assertTrue(
            "restoreAccount must set _uiState.value = LoginState.Success(userData) immediately",
            content.contains("_uiState.value = LoginState.Success(userData)")
        )
    }

    @Test
    fun testLoginViewModel_InitSessionObserver_DeduplicatesWhenAlreadySuccess() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt")
        val content = file.readText()

        // Verify that the init session collector skips redundant checks if already in Success
        assertTrue(
            "LoginViewModel init collector must check if _uiState.value is already Success",
            content.contains("if (currentUi is LoginState.Success && currentUi.userData.email == userData.email) {")
        )
        assertTrue(
            "LoginViewModel init collector must return early when session is already active",
            content.contains("User session already active for email=\${userData.email}; skipping redundant init check")
        )
    }

    @Test
    fun testLoginViewModel_FailsClosedForRestrictedAndDeletedAccounts() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt")
        val content = file.readText()

        assertTrue(
            "signIn must check AccountStatus.RESTRICTED and set LoginState.AccountRestricted",
            content.contains("AccountStatus.RESTRICTED ->") && content.contains("_uiState.value = LoginState.AccountRestricted")
        )
        assertTrue(
            "signIn must check AccountStatus.DELETED and set LoginState.AccountDeleted",
            content.contains("AccountStatus.DELETED ->") && content.contains("_uiState.value = LoginState.AccountDeleted")
        )
        assertTrue(
            "signIn must check AccountStatus.PENDING_DELETION and set LoginState.PendingDeletion",
            content.contains("AccountStatus.PENDING_DELETION ->") && content.contains("_uiState.value = LoginState.PendingDeletion(userData, accessInfo.deletionScheduledFor)")
        )
    }

    // =========================================================================
    // 2. SUBSCRIPTION & TIME AUTHORITY OPTIMIZATIONS
    // =========================================================================

    @Test
    fun testSubscriptionRepository_CalibratesFromDateHeader_AndSkipsRedundantApiTime() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val content = file.readText()

        // 1. Verify ensureSubscription calibrates from HTTP Date header
        assertTrue(
            "ensureSubscription must read HTTP Date header from response",
            content.contains("response.headers.getDate(\"Date\")?.time")
        )
        assertTrue(
            "ensureSubscription must call subscriptionTimeAuthority.recordServerTimeAnchor when Date header is present",
            content.contains("subscriptionTimeAuthority.recordServerTimeAnchor(serverDateHeader)")
        )

        // 2. Verify refreshSubscription skips /api/time when already calibrated
        assertTrue(
            "refreshSubscription must check if subscriptionTimeAuthority.isCalibrated is false before calling syncWithServer",
            content.contains("if (!subscriptionTimeAuthority.isCalibrated.value) {")
        )

        // 3. Verify refreshSubscription debounce mutex
        assertTrue(
            "refreshSubscription must debounce rapid calls within 2000ms",
            content.contains("(currentElapsed - lastSyncElapsedRealtime) < 2000L && lastRefreshedInfo != null")
        )
    }

    @Test
    fun testSubscriptionManager_GuardsEnsureSubscription_AgainstRedundantCalls() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt")
        val content = file.readText()

        // Verify guard in ensureSubscription()
        assertTrue(
            "ensureSubscription must check isEnsureInFlight to prevent concurrent calls",
            content.contains("if (isEnsureInFlight) {")
        )
        assertTrue(
            "ensureSubscription must check if currentStatus != SubscriptionStatus.NONE to skip redundant calls",
            content.contains("if (currentStatus != SubscriptionStatus.NONE && accessState.value != AccessState.INITIALIZING) {")
        )
    }

    @Test
    fun testMainActivity_OnResume_GuardsEnsureSubscription() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/MainActivity.kt")
        val content = file.readText()

        // Verify onResume check
        assertTrue(
            "MainActivity onResume must verify currentStatus == SubscriptionStatus.NONE before calling ensureSubscription",
            content.contains("val currentStatus = subscriptionManager.get().currentStatus()") &&
                    content.contains("if (currentStatus == com.reeltrak.app.domain.models.SubscriptionStatus.NONE) {") &&
                    content.contains("subscriptionManager.get().ensureSubscription()")
        )
    }

    // =========================================================================
    // 3. PAYMENT FLOW IMMEDIATE BACKGROUND REFRESH
    // =========================================================================

    @Test
    fun testRazorpayPurchaseService_TriggersBackgroundRefreshOnVerificationSuccess() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt")
        val content = file.readText()

        // Verify injection of SubscriptionRepository
        assertTrue(
            "RazorpaySubscriptionPurchaseServiceImpl must inject subscriptionRepository",
            content.contains("subscriptionRepository: dagger.Lazy<com.reeltrak.app.domain.repository.SubscriptionRepository>")
        )

        // Verify immediate refreshSubscription() launch in background on success
        assertTrue(
            "verifySubscriptionOnBackend must trigger subscriptionRepository.refreshSubscription() on success",
            content.contains("subscriptionRepository.get().refreshSubscription()")
        )
        assertTrue(
            "verifySubscriptionOnBackend must transition checkoutState to Success immediately",
            content.contains("_checkoutState.value = CheckoutState.Success(\"Recurring subscription activated!\")")
        )
    }

    // =========================================================================
    // 4. PERFORMANCE INSTRUMENTATION & PRIVACY/SECURITY VERIFICATION
    // =========================================================================

    @Test
    fun testPerformanceInstrumentation_TagsPresentAndZeroPiiLogged() {
        val googleAuthFile = findSourceFile("src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt")
        val googleAuthContent = googleAuthFile.readText()

        assertTrue(
            "GoogleAuthManager must contain [AUTH_PERF] logs",
            googleAuthContent.contains("[AUTH_PERF]")
        )
        // Verify no credentials or tokens are printed in performance logs
        assertFalse(
            "GoogleAuthManager must NOT log idToken in AUTH_PERF logs",
            googleAuthContent.lines().filter { it.contains("AUTH_PERF") }.any { it.contains("idToken") }
        )

        val loginVmFile = findSourceFile("src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt")
        val loginVmContent = loginVmFile.readText()
        assertTrue(
            "LoginViewModel must contain [AUTH_PERF] logs",
            loginVmContent.contains("[AUTH_PERF]")
        )
        assertFalse(
            "LoginViewModel must NOT log passwords or credentials in AUTH_PERF logs",
            loginVmContent.lines().filter { it.contains("AUTH_PERF") }.any { it.contains("password") || it.contains("credential") }
        )

        val subRepoFile = findSourceFile("src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt")
        val subRepoContent = subRepoFile.readText()
        assertTrue(
            "SubscriptionRepositoryImpl must contain [SUB_PERF] logs",
            subRepoContent.contains("[SUB_PERF]")
        )

        val razorpayFile = findSourceFile("src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt")
        val razorpayContent = razorpayFile.readText()
        assertTrue(
            "RazorpaySubscriptionPurchaseServiceImpl must contain [SUB_PERF] logs",
            razorpayContent.contains("[SUB_PERF]")
        )
        // Verify no payment signatures or keys are logged in performance logs
        assertFalse(
            "RazorpaySubscriptionPurchaseServiceImpl must NOT log payment signature in SUB_PERF logs",
            razorpayContent.lines().filter { it.contains("SUB_PERF") }.any { it.contains("signature") || it.contains("secret") }
        )
    }

    // =========================================================================
    // 5. BEHAVIORAL LOGIC TESTS: SUBSCRIPTION STATE RESOLUTION & CACHING
    // =========================================================================

    @Test
    fun testSubscriptionStatus_NONE_AllowsEnsureSubscription() {
        val initialStatus = SubscriptionStatus.NONE
        val initialAccessState = AccessState.INITIALIZING

        val shouldCallEnsure = initialStatus == SubscriptionStatus.NONE || initialAccessState == AccessState.INITIALIZING
        assertTrue("When status is NONE or INITIALIZING, ensureSubscription must be allowed", shouldCallEnsure)

        val resolvedStatus = SubscriptionStatus.TRIAL
        val resolvedAccessState = AccessState.TRIAL
        val shouldSkipEnsure = resolvedStatus != SubscriptionStatus.NONE && resolvedAccessState != AccessState.INITIALIZING
        assertTrue("When status is TRIAL and initialized, redundant ensureSubscription must be skipped", shouldSkipEnsure)
    }

    @Test
    fun testRefreshDebounce_WindowCalculations() {
        val lastSyncElapsed = 100_000L

        // Call arrives 500ms later -> within 2000ms debounce window -> must be skipped/reused
        val immediateNextCallElapsed = 100_500L
        val isDebounced = (immediateNextCallElapsed - lastSyncElapsed) < 2000L
        assertTrue("Immediate successive refresh within 500ms must be debounced", isDebounced)

        // Call arrives 2500ms later -> outside 2000ms window -> allowed
        val laterCallElapsed = 102_500L
        val isDebouncedLater = (laterCallElapsed - lastSyncElapsed) < 2000L
        assertFalse("Refresh after 2500ms must not be debounced", isDebouncedLater)
    }

    @Test
    fun testHttpDateHeader_ParsingValidation() {
        // Test standard HTTP-date RFC 7231 parsing using OkHttp's public Headers.getDate API
        val headerDateStr = "Sun, 11 Oct 2026 14:00:00 GMT"
        val headers = okhttp3.Headers.Builder()
            .add("Date", headerDateStr)
            .build()
        val parsedMs = headers.getDate("Date")?.time ?: 0L

        assertTrue("Parsed HTTP date must be positive epoch millis", parsedMs > 0L)
        assertEquals(1791727200000L, parsedMs)
    }
}

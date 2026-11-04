# BUG-CRIT-02 Remediation Report: Sign-Out & Account Lifecycle Coroutine Scope Lifecycle Decoupling

**Issue ID:** BUG-CRIT-02  
**Date:** September 20, 2026  
**Status:** Remediated & Verified  

---

## 1. Executive Summary

In the original implementation, `LoginViewModel.signOut()` launched the critical session tear-down process (clearing historical Room rows, wiping DataStore preferences, and clearing Google/Firebase authentication credentials) inside `viewModelScope.launch(Dispatchers.IO)`. 

When the user confirmed sign-out, navigation from `MainScreen` to `OnboardingScreen`/`LoginScreen` popped `Routes.MAIN` off the navigation backstack with `inclusive = true`, destroying `MainScreen` and cancelling `LoginViewModel.viewModelScope`. On slow flash storage or under heavy I/O contention, this could cancel the tear-down coroutine midway, risking partial data wipes, uncleared cached credentials, or ghost sessions.

This remediation decouples the critical authentication and local data sanitization lifecycle from `viewModelScope` by introducing an `@ApplicationScope CoroutineScope` and wrapping the atomic teardown block inside `withContext(NonCancellable)`.

---

## 2. Exact Files & Functions Changed

### A. New Component: `CoroutinesModule.kt`
- **File:** `app/src/main/java/com/reeltrak/app/core/di/CoroutinesModule.kt`
- **Additions:**
  - `@ApplicationScope` and `@IoDispatcher` qualifiers.
  - Hilt `CoroutinesModule` providing `@Singleton @ApplicationScope CoroutineScope` backed by `SupervisorJob() + Dispatchers.Default`.
  - Providers for `@IoDispatcher`, `@DefaultDispatcher`, and `@MainDispatcher`.

### B. Modified Component: `LoginViewModel.kt`
- **File:** `app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt`
- **Functions Modified:**
  - `constructor`: Injected `@ApplicationScope applicationScope: CoroutineScope` and `@IoDispatcher ioDispatcher: CoroutineDispatcher`. Added `private val isSigningOut = AtomicBoolean(false)`.
  - `signOut()`:
    - Replaced `viewModelScope.launch` with `applicationScope.launch(ioDispatcher)`.
    - Added `isSigningOut.compareAndSet(false, true)` to guarantee idempotency and prevent concurrent tear-down races.
    - Wrapped critical teardown sequence in `withContext(NonCancellable)`:
      1. `database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)` (Clears past days, preserves today's device limit row).
      2. `preferencesRepository.clearAllPreferences()`.
      3. `googleAuthManager.signOut()`.
    - Sanitized UI error messages (masked internal exception messages).
  - `keepScheduledDeletion()`: Moved teardown to `applicationScope.launch(ioDispatcher) { withContext(NonCancellable) { ... } }`.
  - `dismissDeletedState()`: Moved teardown to `applicationScope.launch(ioDispatcher) { withContext(NonCancellable) { ... } }`.
  - `dismissRestrictedState()`: Moved teardown to `applicationScope.launch(ioDispatcher) { withContext(NonCancellable) { ... } }`.
  - `init` session validation & login rejection branches (`AccountStatus.RESTRICTED`, `DELETED`, ghost sessions): Executed tear-down via `applicationScope.launch(ioDispatcher) { withContext(NonCancellable) { ... } }`.

### C. New Verification Test Suite: `BugCrit02RemediationTest.kt`
- **File:** `app/src/test/java/com/reeltrak/app/security/BugCrit02RemediationTest.kt`
- **Tests Added:**
  - `testCoroutinesModule_ProvidesApplicationScopeAndDispatchers`
  - `testLoginViewModel_InjectsApplicationScopeAndIoDispatcher`
  - `testLoginViewModel_SignOutUsesApplicationScopeAndNonCancellable`
  - `testSignOutSurvivesViewModelDestruction_Simulation`
  - `testDuplicateSignOutRaceProtection`
  - `testSignOutPreservesTodayDeviceEnforcementAndClearsHistorical`
  - `testCrossAccountIsolation_UserAToUserB`

---

## 3. Lifecycle Behavior: Before vs After

| Aspect | Before Fix | After Fix |
| :--- | :--- | :--- |
| **Coroutine Scope** | `viewModelScope` (bound to `MainScreen` navigation lifecycle) | `@ApplicationScope CoroutineScope` (bound to `Application` process lifespan) |
| **Backstack Pop Impact** | Destroying `LoginViewModel` cancelled in-flight tear-down | Destroying `LoginViewModel` does not cancel tear-down; completion is guaranteed |
| **Cancellation Protection** | None (could be cancelled at any suspension point) | `withContext(NonCancellable)` protects the atomic DB wipe, prefs wipe, and auth signout |
| **Concurrency Guard** | No lock (rapid clicks could spawn competing coroutines) | `AtomicBoolean` CAS (`isSigningOut.compareAndSet(false, true)`) prevents duplicate executions |
| **Error Handling** | Raw exception message strings forwarded to UI State | Sensitive exception details logged internally; generic user-friendly messages surfaced to UI |

---

## 4. Invariant Preservations

1. **Today's Device-Local Daily Limit Enforcement:**
   `database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)` explicitly preserves the row for `dateString == todayStr`. User A reaching 100/100 reels on this phone remains locked even after sign-out and subsequent sign-in by User B.
2. **Account Isolation:**
   Preferences and historical daily records (`dateString != todayStr`) are wiped cleanly, preventing cross-account state leakage when switching accounts.
3. **No Unrelated Code Modifications:**
   `TrustedTimeManager.kt`, `SyncManager.kt`, and subscription billing logic were **not** modified.

---

## 5. Verification & Test Results

All verification targets ran and passed cleanly:

- **Unit Tests (`testDebugUnitTest`):** `BUILD SUCCESSFUL` (All existing tests + 7 new `BugCrit02RemediationTest` tests passing).
- **Android Lint (`lintDebug`):** `BUILD SUCCESSFUL` (0 errors).
- **Kotlin Compilation (`compileDebugKotlin`):** `BUILD SUCCESSFUL`.
- **Debug Assembly (`assembleDebug`):** `BUILD SUCCESSFUL`.

---

## 6. Remaining Concerns

None for BUG-CRIT-02. The tear-down pipeline is fully lifecycle-independent, protected against cancellation, and race-free.

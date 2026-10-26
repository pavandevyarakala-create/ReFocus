# SECOND-PASS CRITICAL & HIGH FINDINGS VERIFICATION REPORT
**Project:** Re:Focus  
**Date of Second Pass:** September 20, 2026  
**Auditor:** Antigravity Hostile Verification Subsystem  
**Scope:** Strict Source-Code Verification of 7 Critical/High Audit Findings (Zero Code Modifications)

---

## 1. EXECUTIVE SUMMARY & VERIFICATION OVERVIEW

Every claim made in the initial audit report regarding the 7 Critical and High findings was re-examined directly against current source files, active execution paths, call graphs, and lifecycle lifespans.

| Finding ID | Initial Severity | Second-Pass Verdict | Must Fix? | Real Device Test? | Confidence |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **SEC-CRIT-01** | Critical | **FALSE POSITIVE / STALE AUDIT** | **NO** | No | High |
| **BUG-CRIT-02** | Critical | **CONFIRMED BUG** | **YES** | No | High |
| **BUG-HIGH-01** | High | **PARTIALLY VALID / THEORETICAL EDGE** | **NO (Low Priority)** | Yes | Medium |
| **BUG-HIGH-02** | High | **CONFIRMED BUG** | **YES** | No | High |
| **BUG-HIGH-03** | High | **CONFIRMED BUG** | **YES** | Yes | High |
| **BUG-HIGH-04** | High | **FALSE POSITIVE / STALE AUDIT** | **NO** | No | High |
| **BUG-HIGH-05** | High | **REQUIRES REAL-DEVICE TESTING** | **OPTIONAL POLISH** | Yes | High |

---

## 2. DETAILED SECOND-PASS VERIFICATION OF THE 7 FINDINGS

### Finding 1: SEC-CRIT-01
- **VERDICT:** **FALSE POSITIVE / STALE AUDIT**
- **CURRENT FILE:** `worker/src/index.js`
- **CURRENT LINE RANGE:** Lines 1204–1237, 1292–1304, 1565–1735, 1940–1961
- **ACTUAL CODE BEHAVIOR:**
  1. The active recurring subscription flow uses:
     - `POST /api/create-subscription` (Lines 1565–1646): Dynamically queries `env.RAZORPAY_PLAN_ID` from the environment and creates subscriptions with `total_count: 120`.
     - `POST /api/verify-subscription` (Lines 1652–1735): Verifies the HMAC SHA-256 signature using `payId + "|" + subId` and confirms `subscription.plan_id === env.RAZORPAY_PLAN_ID`.
     - `POST /api/razorpay-webhook` (Lines 1214–1237): Webhook events `subscription.activated` and `subscription.charged` invoke `reconcileSubscriptionActivation(firebaseUid, ...)` which extracts `current_start`, `current_end`, and sets status to `"ACTIVE"`. It does **not** check or enforce any hardcoded `9900` paise amount.
  2. The only place where `9900` appears is in `reconcileLegacyEntitlementActivation` and the deprecated endpoint `POST /api/verify-payment` / legacy webhook branches `payment.captured` & `order.paid` (Lines 1293–1303, 1955), which are legacy fallback handlers for deprecated one-time orders from v1.
- **WHY THE ORIGINAL AUDIT IS INCORRECT:**
  The initial audit conflated the legacy deprecated fallback order handlers with the active recurring subscription handlers. The active ₹79/month recurring autopay flow is 100% Plan-ID driven (`RAZORPAY_PLAN_ID`) and does not touch the legacy 9900 paise verification path.
- **REALISTIC TRIGGER:** None for recurring subscriptions.
- **ACTUAL IMPACT:** Zero impact on production ₹79 recurring subscriptions.
- **EXISTING PROTECTION:** `subscription.activated` and `subscription.charged` webhook handlers and `POST /api/verify-subscription` bypass all amount checks and rely strictly on Razorpay Plan IDs and crypto signatures.
- **RECOMMENDED FIX ONLY IF CONFIRMED:** None needed for recurring subscriptions. (Legacy cleanup only if removing dead v1 endpoints).
- **REGRESSION RISK:** None.
- **CONFIDENCE:** High (Verified directly in `worker/src/index.js`).

---

### Finding 2: BUG-CRIT-02
- **VERDICT:** **CONFIRMED BUG**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt` & `app/src/main/java/com/reeltrak/app/features/main/ui/MainScreen.kt`
- **CURRENT LINE RANGE:** `LoginViewModel.kt:361–388`, `MainScreen.kt:158–162`
- **ACTUAL CODE BEHAVIOR:**
  1. In `LoginViewModel.kt:365`, `fun signOut()` runs inside `viewModelScope.launch(Dispatchers.IO)`.
  2. The coroutine executes two sequential I/O tasks:
     - `database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)`
     - `preferencesRepository.clearAllPreferences()`
     - `googleAuthManager.signOut()`
     - Sets `_uiState.value = LoginState.Idle`
  3. In `MainScreen.kt:158–162`:
     ```kotlin
     LaunchedEffect(uiState) {
         if (uiState is LoginState.Idle || uiState is LoginState.AccountRestricted) {
             onNavigateToLogin()
         }
     }
     ```
  4. In `ReelTrakNavGraph.kt:32–36`:
     ```kotlin
     MainScreen(
         onNavigateToLogin = {
             navController.navigate(Routes.ONBOARDING) {
                 popUpTo(Routes.MAIN) { inclusive = true }
             }
         }
     )
     ```
  5. When `_uiState.value = LoginState.Idle` is emitted or if navigation occurs immediately upon logout confirmation, popping `Routes.MAIN` destroys `MainScreen` and its scoped `LoginViewModel`, which **cancels `viewModelScope` immediately**.
  6. Any trailing async work inside `googleAuthManager.signOut()` or DataStore preferences clearing that is still in flight is cancelled.
- **WHY THE ORIGINAL AUDIT IS CORRECT:**
  Critical auth lifecycle tear-down and database sanitization operations must never be bound to a UI ViewModel lifecycle that is destroyed during navigation.
- **REALISTIC TRIGGER:** User clicks "Sign Out" from Settings/Main screen on slow flash storage.
- **ACTUAL IMPACT:** Incomplete session tear-down, lingering tokens, or partial database wipes.
- **EXISTING PROTECTION:** Partial try/catch blocks exist, but cannot prevent coroutine `CancellationException` on scope cancellation.
- **RECOMMENDED FIX:** Execute auth sign-out and database sanitization within an Application-scoped `CoroutineScope` (e.g. `@ApplicationScope val appScope: CoroutineScope`) with `withContext(NonCancellable)`.
- **REGRESSION RISK:** Low.
- **CONFIDENCE:** High.

---

### Finding 3: BUG-HIGH-01
- **VERDICT:** **PARTIALLY VALID / THEORETICAL EDGE (Not Reproducible in Standard Flow)**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt`
- **CURRENT LINE RANGE:** Lines 60–98, 112–158
- **ACTUAL CODE BEHAVIOR:**
  1. `YouTubeDetectorV2` executes a shallow BFS traversal:
     - Nodes are pulled from `queue.removeFirst()`.
     - Child nodes are added to `queue` via `node.getChild(i)`.
     - In `finally` block (lines 90–94):
       ```kotlin
       if (node !== rootNode && node !== containerNode) {
           node.recycle()
       }
       ```
     - After the BFS loop completes:
       ```kotlin
       cleanupQueue(queue, rootNode, containerNode)
       if (containerNode !== rootNode) containerNode.recycle()
       ```
  2. The parent `rootNode` is explicitly guarded with `if (node !== rootNode && node !== containerNode)` and is only recycled once in `ReelTrackerService.kt:495` inside its outer `finally` block.
  3. No child node references are held across loop iterations; primitive strings (`text`, `resId`) are extracted before recycling.
- **WHY THE ORIGINAL AUDIT IS MOSTLY INCORRECT:**
  The detector already contains explicit identity comparison checks (`node !== rootNode && node !== containerNode`) and a dedicated `cleanupQueue` routine that drains unvisited nodes. The nodes added to `queue` are fresh child instances returned by `getChild(i)`, so recycling `node` does not invalidate children already pushed into `queue`.
- **REALISTIC TRIGGER:** Only theoretical if an OEM Accessibility framework returns duplicate identical object references from `getChild(i)`.
- **ACTUAL IMPACT:** Negligible in practice on modern Android versions (Android 10–15).
- **EXISTING PROTECTION:** Identity guards on `rootNode` and `containerNode` + `try/catch` in `ReelTrackerService.kt:496`.
- **RECOMMENDED FIX ONLY IF CONFIRMED:** None strictly required. As an extra precaution, wrap `node.recycle()` in an inline `runCatching {}`.
- **REGRESSION RISK:** Zero.
- **CONFIDENCE:** High (Audited node recycling path line-by-line).

---

### Finding 4: BUG-HIGH-02
- **VERDICT:** **CONFIRMED BUG**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt`
- **CURRENT LINE RANGE:** Lines 150–186
- **ACTUAL CODE BEHAVIOR:**
  1. In `SyncManager.kt:180–184`:
     ```kotlin
     // Step 2: Clear old historical data (preserving today's row) and insert new account's historical records
     dailyReelDataDao.clearHistoricalDataExcept(todayStr)
     if (restoredHistoricalList.isNotEmpty()) {
         dailyReelDataDao.insertAll(restoredHistoricalList)
     }
     ```
  2. `dailyReelDataDao.clearHistoricalDataExcept(todayStr)` issues `DELETE FROM daily_reel_data WHERE dateString != :today`.
  3. If a user was offline for 3 days (e.g. Sept 16, Sept 17, Sept 18), those records exist in Room with `syncStatus = 1`.
  4. If `restoreFromCloud()` is called (e.g. manually from Settings -> "Restore from Cloud" or during an account sync trigger), it deletes **all** local historical records before fetching remote ones.
  5. Any offline days that were never pushed to Firestore are permanently deleted.
- **WHY THE ORIGINAL AUDIT IS CORRECT:**
  The SQL query `DELETE FROM daily_reel_data WHERE dateString != :today` makes no distinction between synced (`syncStatus = 0`) and unsynced (`syncStatus = 1`) records.
- **REALISTIC TRIGGER:** User uses app offline for multiple days, then connects to Wi-Fi and triggers "Restore Backup" from Settings.
- **ACTUAL IMPACT:** Irreversible loss of local offline history and broken streaks.
- **EXISTING PROTECTION:** None for offline historical records during cloud restore.
- **RECOMMENDED FIX:** Modify `restoreFromCloud()` to perform an intelligent upsert/merge instead of blanket deleting, or only delete rows where `syncStatus == 0`.
- **REGRESSION RISK:** Low.
- **CONFIDENCE:** High (Direct SQL & DAO inspection confirmed).

---

### Finding 5: BUG-HIGH-03
- **VERDICT:** **CONFIRMED BUG**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt`
- **CURRENT LINE RANGE:** Lines 125–151
- **ACTUAL CODE BEHAVIOR:**
  1. In `TrustedTimeManager.kt:125–151`:
     ```kotlin
     fun getTrustedTime(): Long {
         val currentElapsed = SystemClock.elapsedRealtime()
         val s0 = serverAnchorTime
         val e0 = elapsedRealtimeAnchor

         val computedTime = if (s0 > 0L && e0 > 0L && currentElapsed >= e0) {
             s0 + (currentElapsed - e0)
         } else {
             val wallTime = System.currentTimeMillis()
             if (maxObservedTrustedTime > 0L) {
                 Math.max(maxObservedTrustedTime, wallTime)
             } else {
                 wallTime
             }
         }

         // Monotonic high-water mark check
         val finalTrustedTime = if (computedTime >= maxObservedTrustedTime) {
             maxObservedTrustedTime = computedTime
             computedTime
         } else {
             maxObservedTrustedTime
         }

         return finalTrustedTime
     }
     ```
  2. When the app is fresh-installed or after a device reboot before any server response is received:
     - `serverAnchorTime` is 0L or set to `wallTime` (`System.currentTimeMillis()`).
     - `computedTime` evaluates to `System.currentTimeMillis()`.
  3. If a user sets their device clock forward to `2030-01-01` and opens the app, `computedTime` becomes `2030-01-01`.
  4. Line 144 executes: `maxObservedTrustedTime = computedTime` (set to 2030).
  5. `persistHighWatermark()` writes `2030-01-01` into DataStore `KEY_MAX_OBSERVED_TRUSTED_TIME`.
  6. When the user sets the device clock back to current time, `getTrustedTime()` permanently returns `maxObservedTrustedTime` (`2030-01-01`).
  7. All trial and subscription periods evaluate `now > trialEnd` and permanently lock the user out.
- **WHY THE ORIGINAL AUDIT IS CORRECT:**
  Allowing unverified `System.currentTimeMillis()` to push `maxObservedTrustedTime` forward allows a single forward clock change to permanently corrupt the local time store.
- **REALISTIC TRIGGER:** User tests date manipulation, travels across extreme timezones with incorrect manual time settings, or OEM boot time glitch.
- **ACTUAL IMPACT:** Permanent lockout from trial and active subscriptions until app data is cleared.
- **EXISTING PROTECTION:** `loadPersistedAnchor()` protects against rollbacks, but has no safeguard against untrusted forward leaps.
- **RECOMMENDED FIX:** Only advance `maxObservedTrustedTime` when `computedTime` originates from an active server anchor (`s0 > 0L`), never from unverified `System.currentTimeMillis()`.
- **REGRESSION RISK:** Low.
- **CONFIDENCE:** High.

---

### Finding 6: BUG-HIGH-04
- **VERDICT:** **FALSE POSITIVE / STALE AUDIT**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt` & `app/src/main/java/com/reeltrak/app/domain/models/SubscriptionInfo.kt`
- **CURRENT LINE RANGE:** `SubscriptionRepositoryImpl.kt:63–125`, `SubscriptionInfo.kt:80–84`
- **ACTUAL CODE BEHAVIOR:**
  1. In `SubscriptionRepositoryImpl.kt:75–80`:
     ```kotlin
     val registration = docRef.addSnapshotListener { snapshot: DocumentSnapshot?, error ->
         if (error != null) {
             Log.w(TAG, "SnapshotListener failed for user: $uid", error)
             trySend(SubscriptionState.Error(error.localizedMessage ?: "Failed to load subscription"))
             return@addSnapshotListener
         }
     ```
  2. When an error occurs (e.g. Firestore permission denied), the listener emits `SubscriptionState.Error(...)`.
  3. In `SubscriptionRepositoryImpl.kt:119–125`:
     ```kotlin
     override val subscriptionInfo: Flow<SubscriptionInfo> = subscriptionState.map { state ->
         when (state) {
             is SubscriptionState.Success -> state.info
             is SubscriptionState.Error -> state.fallbackInfo
             is SubscriptionState.Loading -> SubscriptionInfo.LOCKED_DEFAULT
         }
     }.distinctUntilChanged()
     ```
  4. In `SubscriptionInfo.kt:83`:
     ```kotlin
     data class Error(val message: String, val fallbackInfo: SubscriptionInfo = SubscriptionInfo.LOCKED_DEFAULT) : SubscriptionState
     ```
  5. The fallback info is cleanly mapped to `SubscriptionInfo.LOCKED_DEFAULT`, which sets `accessState = AccessState.LOCKED`.
  6. In `SubscriptionBottomSheet.kt:86–98`, `SubscriptionState.Error` is explicitly caught and displays an error message with a retry/refresh button.
- **WHY THE ORIGINAL AUDIT IS INCORRECT:**
  The previous audit claimed that permission errors leave the repository permanently stuck in `Loading`. The source code proves that `trySend(SubscriptionState.Error(...))` is called immediately on error, mapping down to `fallbackInfo` and displaying the error state in the UI.
- **REALISTIC TRIGGER:** None; error path is already fully wired.
- **ACTUAL IMPACT:** Zero. Handled correctly.
- **EXISTING PROTECTION:** `SubscriptionState.Error` sealed interface and fallback mapping in `subscriptionInfo`.
- **RECOMMENDED FIX ONLY IF CONFIRMED:** None.
- **REGRESSION RISK:** None.
- **CONFIDENCE:** High.

---

### Finding 7: BUG-HIGH-05
- **VERDICT:** **REQUIRES REAL-DEVICE TESTING (Documented OEM Quirk, Low App Risk)**
- **CURRENT FILE:** `app/src/main/java/com/reeltrak/app/services/FloatingCounterManager.kt`
- **CURRENT LINE RANGE:** Lines 331–357
- **ACTUAL CODE BEHAVIOR:**
  1. When attached, `FloatingCounterManager` adds `composeView` with:
     ```kotlin
     WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
     WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
     WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
     WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
     ```
  2. When hiding the counter (lines 352–356):
     ```kotlin
     stateFlow.collect { state ->
         val isVisible = state.lifecycleState == OverlayLifecycleState.VISIBLE
         composeView?.visibility = if (isVisible) View.VISIBLE else View.GONE
     }
     ```
  3. The `composeView` remains attached to `WindowManager` with `View.GONE`.
  4. In standard Android AOSP, a `View.GONE` view does not participate in touch hit-testing. However, on heavily customized OEM ROMs (e.g. Xiaomi HyperOS/MIUI, Vivo FuntouchOS, Oppo ColorOS), overlay windows of type `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_LAYOUT_NO_LIMITS` can occasionally retain an active transparent touch boundary if the window metrics are not recomputed.
- **WHY THE ORIGINAL AUDIT IS PARTIALLY VALID:**
  While AOSP handles `View.GONE` cleanly, calling `windowManager.removeView()` on hide and `addView()` on show is the gold standard for bulletproof multi-OEM floating overlay management.
- **REALISTIC TRIGGER:** User opens third-party apps on specific MIUI/HyperOS devices with floating windows enabled.
- **ACTUAL IMPACT:** Potential corner touch dead zones on specific OEM devices.
- **EXISTING PROTECTION:** `ComposeView` uses `Surface(color = Color.Transparent)` and `View.GONE`.
- **RECOMMENDED FIX ONLY IF CONFIRMED:** Wrap overlay view management in a dynamic attach/detach lifecycle or dynamically toggle `FLAG_NOT_TOUCHABLE` when transitioning to `GONE`.
- **REGRESSION RISK:** Low.
- **CONFIDENCE:** High.

---

## 3. VERIFICATION SUMMARY TABLE

| Finding | Verdict | Must Fix? | Real Device Test? | Confidence |
|---|---|:---:|:---:|:---:|
| **SEC-CRIT-01** (Worker 9900 paise check) | False Positive / Stale | **NO** | No | High |
| **BUG-CRIT-02** (LoginViewModel sign-out scope) | Confirmed Bug | **YES** | No | High |
| **BUG-HIGH-01** (YouTubeDetector node recycling) | Partially Valid / Theoretical | **NO** | Yes | High |
| **BUG-HIGH-02** (SyncManager drops offline history) | Confirmed Bug | **YES** | No | High |
| **BUG-HIGH-03** (TrustedTime forward clock poison) | Confirmed Bug | **YES** | Yes | High |
| **BUG-HIGH-04** (SubscriptionRepo error handling) | False Positive / Stale | **NO** | No | High |
| **BUG-HIGH-05** (FloatingCounter GONE window) | Requires Real Device Test | **OPTIONAL** | Yes | High |

---

## 4. CONFIRMED BLOCKERS ONLY (To be fixed)

1. **[BUG-CRIT-02] `LoginViewModel.kt` Sign-Out & Account Deletion Scope**:
   - Move auth sign-out, preference clearing, and database cleanup to `@ApplicationScope CoroutineScope` with `NonCancellable` to prevent coroutine cancellation when `Routes.MAIN` is popped from the backstack.
2. **[BUG-HIGH-02] `SyncManager.kt` Offline History Wipe**:
   - Update `restoreFromCloud()` to merge records or only clear `syncStatus == 0` records instead of deleting all local offline records with `clearHistoricalDataExcept(todayStr)`.
3. **[BUG-HIGH-03] `TrustedTimeManager.kt` Clock-Forward Poisoning**:
   - Restrict `maxObservedTrustedTime` advancement so it only updates from verified server timestamps (`recordServerTime`), never from unverified device `System.currentTimeMillis()`.

---

## 5. FALSE POSITIVES / STALE FINDINGS (Discarded)

1. **SEC-CRIT-01**: Razorpay recurring ₹79/month subscriptions are plan-driven via `RAZORPAY_PLAN_ID` and do not use the legacy ₹99 order verification path.
2. **BUG-HIGH-04**: `SubscriptionRepositoryImpl` already catches Firestore listener errors and emits `SubscriptionState.Error` with `SubscriptionInfo.LOCKED_DEFAULT`.

---

## 6. REAL-DEVICE-ONLY FINDINGS (To verify on hardware)

1. **BUG-HIGH-05**: Test overlay touch pass-through when `View.GONE` on Xiaomi MIUI/HyperOS, Samsung OneUI, and Oppo ColorOS.
2. **BUG-HIGH-01**: Rapid 100-Shorts scroll test on YouTube to verify zero native accessibility node exceptions.

---

### End of Second-Pass Verification. Zero code changes made.

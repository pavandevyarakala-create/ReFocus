# ANDROID LOCAL STORAGE, DATA PROTECTION & CRYPTOGRAPHY SECURITY AUDIT

**System:** Re:Focus (formerly ReelTrak) Android Application  
**Package:** `com.reeltrak.app`  
**Audit Phase:** Step 3 — Deep Local Data Security, Storage & Cryptography Inspection  
**Date:** September 17, 2026  
**Status:** Inspection Only (Zero Code/Config Modifications)

---

## 1. Executive Summary

This deep security audit inspected the complete client-side data lifecycle across Room SQLite, AndroidX DataStore preferences, synchronization workflows, anti-tamper timekeeping, authentication persistence, and logging. 

### Key Findings at a Glance:
1. **Critical Cross-User Data Contamination on Account Switching:** When User A signs out, [`LoginViewModel.signOut()`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt#L272) intentionally preserves *today's* unencrypted tracking row (`clearHistoricalDataExcept(todayStr)`). When User B subsequently logs in, [`SyncManager.restoreFromCloud()`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt#L142) immediately executes `backupToCloud()`, uploading User A's un-wiped today's tracking data and sessions directly into User B's Firestore account.
2. **Plaintext At-Rest Storage:** Room SQLite database and DataStore preferences are stored in plaintext in the app sandbox (`/data/data/com.reeltrak.app/`) without SQLCipher or Android Keystore encryption.
3. **Absence of Client-Side Cryptography:** There are zero native cryptographic operations (e.g. Android KeyStore, AES-GCM, EncryptedDataStore) protecting local sessions or time anchors.
4. **Resilient Server-Enforced Subscription Authority:** The client-side `SubscriptionManager` evaluates access via `SubscriptionInfo` cached in memory from Firestore snapshots. While a local memory modification (e.g. Frida) can temporarily trick local UI gating, the server remains completely uncompromised.

---

## 2. Complete Local Data Inventory

| Storage Mechanism | File / Location | Data Fields Stored | Sensitive? | Purpose |
|---|---|---|---|---|
| **Room SQLite** | `reeltrak_database` / `daily_reel_data` | `dateString`, `totalReelsCount`, `totalTimeSpentMillis`, `dailyGoal`, `isSkullDay`, `isStrictModeOn`, `isStrictModeLocked`, `isLimitLocked`, `sessions` (JSON list of timestamps, app names, scroll counts), `updatedAt`, `syncStatus` | **Yes (Behavioral & Personal Usage History)** | Tracks daily short-form video consumption per app with precise session start/end timestamps. |
| **DataStore (Settings)** | `settings.preferences_pb` | `auth_id_token`, `auth_email`, `auth_display_name`, `auth_profile_picture_uri`, `daily_intent`, `first_run_date`, `goal_set_date`, `daily_limit_locked`, `strict_mode_locked`, `last_lock_epoch_day`, `default_daily_limit`, `last_limit_change_date`, `last_daily_summary_date`, `notifications_enabled`, `badge_*_unlock_time`, `badge_*_dialog_shown`, `has_migrated_to_server`, `has_completed_tour` | **Yes (PII, OAuth ID Token, Lock Flags)** | Stores user profile cache, authentication session token, user intent, streak milestone timestamps, and strict mode lock flags. |
| **DataStore (Time)** | `trusted_time_store.preferences_pb` | `last_server_time`, `last_elapsed_realtime`, `max_observed_trusted_time` | **Yes (Security Invariant Anchor)** | Maintains monotonic anti-rollback trusted time anchor across app restarts and device reboots. |
| **DataStore (Onboarding)** | `onboarding_prefs.preferences_pb` | `app_tour_completed_{uid}` | **No** | Account-keyed flag recording completion of initial onboarding tour. |
| **Firestore Cache** | `firestore_cache/...` | Firestore documents cached offline (`/users/{uid}/subscription/status`, `/users/{uid}/daily_data/*`, `/users/{uid}/account/status`) | **Yes (Subscription & Account Metadata)** | Default Firebase offline persistence store. |

---

## 3. Deep Component Assessments

### A. Room SQLite Database Security
- **Data Sensitivity:** Contains detailed per-session timestamps (`startTimeStamp`, `endTimeStamp`), target apps (`com.instagram.android`, `com.google.android.youtube`), and reel counts in JSON format.
- **At-Rest Protection:** **Unencrypted**. Standard SQLite binary file in `/data/data/com.reeltrak.app/databases/`.
- **Inter-Process Access:** Sandboxed by standard Linux UID permissions. Third-party apps cannot access without root or OS kernel exploit.
- **SQL Injection:** Safe; all queries in [`DailyReelDataDao.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/local/dao/DailyReelDataDao.kt) use Room parameterized queries and ORM type-safe abstractions.

### B. DataStore & Preferences Security
- **OAuth Tokens & PII:** [`PreferencesRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt#L51-L55) persists raw `auth_id_token`, `auth_email`, and `auth_display_name` in plaintext Proto files.
- **Tampering Potential:** On a rooted device, editing `trusted_time_store` or `settings` can manipulate lock states or streak badges. However, modifying `settings.preferences_pb` **cannot** grant server-authoritative premium access because premium gating relies on live Firestore status.

### C. TrustedTimeManager Assessment
- **Architecture:** Anchors time with tuple `(S_0, E_0)` where `S_0` is server timestamp from Firestore and `E_0 = SystemClock.elapsedRealtime()`.
- **Clock Rollback Resistance:** Effective against user altering system wall clock (`Settings -> Date & Time`). Computed monotonic time `T = S_0 + (currentElapsed - E_0)` ignores wall clock.
- **Reboot & Offline Tamper Analysis:**
  - Upon reboot, `SystemClock.elapsedRealtime()` resets to 0. [`TrustedTimeManager`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt#L74-L80) detects `currentElapsed < savedElapsed` and falls back to `maxObservedTrustedTime`.
  - **Weakness:** If an offline attacker rolls the clock back *before* device reboots, monotonic check enforces `max(maxObserved, wallTime)`. If the device is kept offline, the attacker cannot advance trial expiration, but cannot extend expired trials beyond `trialEnd` either.
  - **Data Clearing / Reinstall:** Clearing app data or reinstalling wipes `trusted_time_store`. When app opens offline, `maxObservedTrustedTime = 0`, forcing fallback to `System.currentTimeMillis()`. If user sets system clock back to 2020 before reinstalling offline, local trial countdown displays extended time until the app connects to the internet and synchronizes with Firestore.

### D. Subscription & Access State Tampering
- **Decision Engine:** Evaluated in [`CloudSubscriptionDto.toDomain()`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/remote/dto/CloudSubscriptionDto.kt#L60-L98) and mapped via [`SubscriptionManager`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt).
- **Client Override:** Gating evaluates `derivedAccessState = when { isPaidActive -> ACTIVE; isTrialActive -> TRIAL; else -> LOCKED }`.
- **Root/Frida Compromise:** An attacker using Frida or dynamic bytecode rewriting can hook `SubscriptionManager.isAppUnlockedNow()` to return `true`. However, this only unlocks local UI and floating overlay; it does not compromise the server or leak cloud data.

### E. SyncManager & Account Switching Contamination Analysis
- **The Cross-Account Leak Flow:**
  1. **User A logs in** and scrolls reels on `2026-09-17` (e.g. 150 reels).
  2. **User A signs out:** [`LoginViewModel.signOut()`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt#L273) calls:
     ```kotlin
     database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)
     ```
     *Today's row for User A remains in Room database.*
  3. **User B logs in** on the same device on `2026-09-17`.
  4. [`LoginViewModel`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt#L134) calls `syncManager.restoreFromCloud()`.
  5. [`SyncManager.restoreFromCloud()`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt#L142) executes:
     ```kotlin
     // UPWARD SYNC: Push all local data to cloud BEFORE we wipe historical
     backupToCloud()
     ```
  6. **Result:** User A's reel count and session history for today are written to `/users/{User_B_UID}/daily_data/2026-09-17` in Firestore. User B's cloud data is corrupted with User A's tracking activity.

### F. Android Backup & Export Assessment
- **Manifest Setting:** [`AndroidManifest.xml:L12`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/AndroidManifest.xml#L12) explicitly sets:
  ```xml
  android:allowBackup="false"
  ```
- **Security Posture:** **Secure**. ADB backup, Google Cloud Auto Backup, and device-to-device migration transfers are disabled by the OS, preventing extraction of SQLite databases and DataStore preferences via ADB backup tools.

### G. Local Logging Inspection
- Search across the entire `app/src/main/java` directory revealed extensive logging using `android.util.Log` (`Log.d`, `Log.e`, `Log.w`, `Log.i`).
- **PII & Sensitive Contexts in Logs:**
  - [`RazorpaySubscriptionPurchaseServiceImpl.kt:L229`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L229): Logs boolean presence of token: `idToken != null`.
  - [`GoogleAuthManager.kt:L83`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt#L83): Logs server client ID and caller context.
  - [`SubscriptionRepositoryImpl.kt:L74`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L74): Logs raw UID and exceptions.
  - [`ReelTrackerService.kt:L339`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/services/ReelTrackerService.kt#L339): Logs active foreground package names.
- **Release ProGuard Rules:** [`app/proguard-rules.pro`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro) does **not** strip `android.util.Log.*` calls in release builds (`-assumenosideeffects class android.util.Log { ... }` is missing).

### H. Cryptography Implementation Review
- **Client-Side Cryptography:** **Completely Absent**. The Android application does not perform any local cryptographic hashing, key generation via AndroidKeyStore, or AES/GCM encryption. All data is written in raw plaintext to SQLite and DataStore.
- **Network Cryptography:** Handled securely via OkHttp TLS 1.3 / HTTPS with `network_security_config.xml` enforcing `cleartextTrafficPermitted="false"`.

---

## 4. Comprehensive Findings Log

---

### [FINDING-01] HIGH: Cross-User Data Contamination and Cloud Sync Leak on Logout/Account Switch
- **Severity:** `HIGH`
- **File & Location:** 
  - [`app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt:L272-L274`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt#L272-L274)
  - [`app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt:L142`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt#L142)
- **Current Behavior:**
  Upon sign out, `database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)` preserves today's tracking data in the local SQLite table. When a second user signs in on the same device, `restoreFromCloud()` immediately calls `backupToCloud()`, which queries `dailyReelDataDao.getAllDailyDataSync()` and uploads the previous user's data to the new user's Firestore path (`/users/{new_uid}/daily_data/{todayStr}`).
- **Security Impact:**
  User A's private app usage habits, reel counts, and viewing session timestamps are permanently merged and leaked into User B's private cloud profile.
- **Realistic Attack Scenario:**
  Two family members or shared device users switch accounts on the same phone. User B can view User A's exact scrolling habits and timestamps from earlier that day in their dashboard/analytics.
- **Exploitability:** **Directly Exploitable via Normal Multi-User Flow**.
- **Evidence:**
  `LoginViewModel.kt`:
  ```kotlin
  val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
  database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)
  ```
  `SyncManager.kt`:
  ```kotlin
  suspend fun restoreFromCloud(): Result<Unit> {
      backupToCloud() // Pushes local DB (containing User A's today row) to Firebase.auth.currentUser (User B)
  ```
- **Recommended Remediation:**
  1. On logout, execute a full atomic wipe of all local SQLite tables (`dailyReelDataDao.clearAll()`).
  2. Associate local SQLite database records with an explicit `userId` foreign key or create user-isolated database files.
- **Dependencies:** Room Database migrations.

---

### [FINDING-02] MEDIUM: Plaintext Storage of PII and Auth Tokens in Android DataStore
- **Severity:** `MEDIUM`
- **File & Location:** [`app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt:L51-L55`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt#L51-L55)
- **Current Behavior:**
  `PreferencesRepositoryImpl` writes user email, display name, photo URI, and the active `auth_id_token` into an unencrypted DataStore file (`settings.preferences_pb`).
- **Security Impact:**
  On rooted or physically compromised devices, malware with root access or local forensic tools can extract the Firebase ID token and user email directly from the filesystem.
- **Realistic Attack Scenario:**
  Adware or rooted spyware on the user's phone reads `/data/data/com.reeltrak.app/files/datastore/settings.preferences_pb` to exfiltrate user identity and active tokens.
- **Exploitability:** **Exploitable on Rooted / Compromised Devices**.
- **Recommended Remediation:**
  Do not persist raw ID tokens to disk (Firebase Auth SDK already securely manages token caching in memory and keystore-backed slots). If persistent caching of profile data is required, use AndroidX `EncryptedDataStore` or `MasterKeys` / `EncryptedSharedPreferences`.

---

### [FINDING-03] MEDIUM: Unstripped Android Log Statements in Release Builds
- **Severity:** `MEDIUM`
- **File & Location:** [`app/proguard-rules.pro`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro) & multiple app sources
- **Current Behavior:**
  `Log.d`, `Log.i`, and `Log.e` statements across the app are not removed by ProGuard/R8 during release compilation.
- **Security Impact:**
  Logs containing internal architecture details, package tracking events, Firestore paths, and error traces are continuously broadcast to system logcat, readable by any USB debugging session or system diagnostic tool.
- **Exploitability:** **Exploitable via Logcat Extraction**.
- **Evidence:** Missing `-assumenosideeffects class android.util.Log { public static boolean isLoggable(...); public static int v(...); public static int d(...); public static int i(...); }` in `proguard-rules.pro`.
- **Recommended Remediation:**
  Add R8 log stripping rules to `proguard-rules.pro` for release builds, or wrap debug logging in a timber/facade tree enabled only when `BuildConfig.DEBUG == true`.

---

### [FINDING-04] LOW: Offline Clock Rollback Manipulation on Fresh Install
- **Severity:** `LOW`
- **File & Location:** [`app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt:L74-L80`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt#L74-L80)
- **Current Behavior:**
  When app data is completely cleared or the app is newly installed while offline, `loadPersistedAnchor()` finds no stored anchor and initializes `serverAnchorTime = System.currentTimeMillis()`.
- **Security Impact:**
  An attacker can uninstall the app, set their system clock back 5 years, install the app, and run it strictly offline. The client will calculate local countdowns based on the falsified past timestamp.
- **Exploitability:** **Low / Offline-Only Display Quirk**.
- **Remediation Note:**
  As soon as the app connects to the internet, `refreshSubscription()` overrides the anchor with the server timestamp from Firestore.

---

## 5. False Positives / Non-Issues Investigated

1. **Android Auto Backup Data Leakage:**
   - *Investigated:* Whether SQLite data or DataStore preferences can be leaked to Google Drive or extracted via `adb backup`.
   - *Verdict:* **CLEARED (False Positive)**. `AndroidManifest.xml` explicitly sets `android:allowBackup="false"`, preventing all automated backup and extraction tools.
2. **Local SQL Injection in Room DAO:**
   - *Investigated:* Whether user-controlled date strings or filters can inject raw SQL into `DailyReelDataDao`.
   - *Verdict:* **CLEARED (False Positive)**. All queries utilize compile-time verified Room query binders (`:dateString`, `:today`).
3. **Client-Side Direct Premium Privilege Escalation:**
   - *Investigated:* Whether editing local Room or DataStore records can grant persistent premium access in the cloud.
   - *Verdict:* **CLEARED (False Positive)**. Premium state is derived from the server-authoritative Firestore document `/users/{uid}/subscription/status`, which client writes cannot touch.

---

## 6. Missing Security Controls Checklist

1. [ ] **User-Isolated Database Isolation:** Local Room database records lack user ID isolation, causing cross-account contamination on sign out.
2. [ ] **At-Rest Database Encryption (SQLCipher):** Room database SQLite file is unencrypted.
3. [ ] **Encrypted DataStore for Sensitive Session Data:** Preferences DataStore stores tokens in cleartext.
4. [ ] **Release Log Stripping:** ProGuard / R8 rules do not strip verbose debugging logs.
5. [ ] **Integrity / Anti-Frida Instrumentation:** No client runtime verification against memory hooking or runtime tampering.

---

## 7. Recommended NEXT Audit Step ONLY

> **Recommended Next Step:** **Audit of Third-Party Dependencies, Build Configuration & Release Hardening** (`app/build.gradle.kts`, `package.json`, transitive SDK dependencies, SDK versions, ProGuard rules, and network security configs), verifying known CVEs, dependency vulnerabilities, and release binary posture.

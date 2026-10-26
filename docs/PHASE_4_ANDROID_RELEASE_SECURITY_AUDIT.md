# Phase 4 Security Audit Report: Android Client & Release Security

**Date:** 2026-09-18  
**Scope:** VULN-09 (Release Signing, R8, Build, Logging), VULN-10 (Local Room Database Protection / At-Rest Encryption), Preferences/DataStore, Merged Manifest, Exported Components, Network Security Config, Supply Chain & Cross-Phase Regressions.  
**Overall Status:** **PASS WITH FINDINGS**  

---

## 1. Executive Summary

Phase 4 evaluates the Android client application and release artifacts against security hardening requirements, production deployment risks, and potential data-at-rest vulnerabilities.

### Summary of Audit Targets:
1. **VULN-09 (Release Signing, R8, Build & Logging Hardening):**
   - **Release Signing:** Currently unsigned (`signingConfigs` is not configured in `app/build.gradle.kts`). While this prevents accidental leakage of private keys in source control, a production keystore configuration mechanism (via environment variables / CI secrets) is required for Google Play release.
   - **R8 / ProGuard:** R8 minification (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) are active. However, R8 rules do not explicitly strip Android `Log.*` invocations (`-assumenosideeffects`), leaving debug logs compiled into release bytecode.
   - **Accessibility & Engine Logging:** Multiple `Log.d` / `Log.e` calls in tracking engines and receivers log internal state, reel transition metadata, and timing heuristics.
2. **VULN-10 (Local Room Database Protection / At-Rest Threat Model):**
   - **Data Stored:** Room table `daily_reel_data` stores daily scroll counts, time spent, goals, strict mode lock flags, session start/end timestamps, and sync status. It does **not** store auth credentials, OAuth tokens, payment cards, or user passwords.
   - **Threat Model & Device-Local Today Preservation:** Encrypting Room with SQLCipher or Android Keystore is feasible but introduces key lifecycle complexities (e.g. device lock state on boot / Direct Boot / accessibility services waking before unlock). Crucially, the Phase 0 guarantee requiring `dateString == today` to persist locally across account logout/switching must not be broken by user-keyed encryption.
3. **Preferences / DataStore:**
   - Proactive purge of legacy `auth_id_token` is intact in `PreferencesRepositoryImpl.kt`. No access tokens, refresh tokens, or secrets are persisted locally in DataStore.
4. **Manifest & Component Exposure:**
   - Merged release manifest confirms `android:allowBackup="false"`.
   - `MainActivity` is the sole exported launcher.
   - `ReelTrackerService` is properly guarded by `android.permission.BIND_ACCESSIBILITY_SERVICE`.
   - Debug broadcast receivers in `ReelTrackerService` are guarded by `BuildConfig.DEBUG` and registered dynamically with `RECEIVER_NOT_EXPORTED`.
5. **Network Security:**
   - `network_security_config.xml` strictly enforces `cleartextTrafficPermitted="false"` across all domains.
   - All backend calls use HTTPS to the authorized Cloudflare Worker with `AppCheckInterceptor`.

---

## 2. Findings Matrix

| Finding ID | Severity | Target Area | Title & Summary | Production Blocker? |
| :--- | :--- | :--- | :--- | :--- |
| **FINDING P4-01** | **HIGH** | Build / Signing | **Missing Production Release Signing Configuration**<br>Release build uses default unsigned output without a secure CI/environment keystore configuration. | **YES** (Must sign for Play Store) |
| **FINDING P4-02** | **MEDIUM** | Logging / R8 | **Debug Logging Retained in Release APK**<br>`Log.d` and `Log.e` calls in tracking engine and auth flows are not stripped by R8 `-assumenosideeffects`. | **NO** (Hardening required) |
| **FINDING P4-03** | **LOW** | Room / At-Rest | **Unencrypted Room Database (Threat Model Assessment)**<br>Room database is standard SQLite. No high-value credentials or PII are stored, but device extraction on rooted devices exposes reel history. | **NO** (Architectural decision) |
| **FINDING P4-04** | **LOW** | Network / Worker | **Inconsistent Base Worker URL in Repository Constants**<br>Android client points to `refocus-razorpay-worker.refocus-razorpay-worker.workers.dev` whereas test suite references `reeltrak-backend.pavandevyarakala.workers.dev`. | **YES** (Deployment alignment) |
| **FINDING P4-05** | **INFORMATIONAL** | Lint Config | **Release Lint Checking Disabled in `build.gradle.kts`**<br>`lint.checkReleaseBuilds = false` and `abortOnError = false` bypass Gradle lint safety gates. | **NO** (CI enhancement) |

---

## 3. Detailed Audit Findings

### 3.1 [FINDING P4-01] (HIGH) — Missing Production Release Signing Configuration
- **Severity:** HIGH (Production Release Blocker)
- **Location:** [`app/build.gradle.kts#L23-L32`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L23-L32)
- **Evidence:**
  ```kotlin
  buildTypes {
      release {
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(
              getDefaultProguardFile("proguard-android-optimize.txt"),
              "proguard-rules.pro"
          )
      }
  }
  ```
- **Why it matters:** The release build type does not define a `signingConfig`. Running `./gradlew assembleRelease` creates an unsigned APK (`app-release-unsigned.apk`). An unsigned APK cannot be installed on end-user devices or uploaded to the Google Play Console.
- **Exploitability / Risk:** If developers manually sign builds on local machines using ad-hoc keystores or fall back to debug keys in release, app upgrade paths and Play App Signing integrity are compromised.
- **Recommended Remediation:**
  - Configure `signingConfigs` in `app/build.gradle.kts` to read release keystore path, alias, and passwords securely from environment variables (e.g., `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) or Gradle properties (`~/.gradle/gradle.properties`).
  - Do NOT commit `.jks` / `.keystore` files or passwords to git.
  - Fall back gracefully if signing environment variables are absent during local testing.

---

### 3.2 [FINDING P4-02] (MEDIUM) — Debug Logging Retained in Release APK
- **Severity:** MEDIUM (Information Exposure & Hygiene)
- **Location:** [`app/proguard-rules.pro`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro) and tracking/auth classes across `app/src/main/java/`
- **Evidence:**
  - In `app/proguard-rules.pro`, there is no `-assumenosideeffects class android.util.Log` directive.
  - In `InstagramDetectorV2.kt#L124`:  
    `Log.d("InstagramDebug", "Analyze: creator='$creator', caption='$caption' (norm='$normalizedCaption'), song='$song', classification=$classification, nodes=$nodesVisited")`
  - In `ReelTrackerService.kt#L282-L378`:  
    `Log.e("PipelineAudit", "1. onAccessibilityEvent received")`, `Log.e("PipelineAudit", "5. rootNode acquired")`
  - In `GoogleAuthManager.kt#L82`:  
    `Log.d("GoogleAuth", "Initiating Google Sign-In with Web Client ID: ...")`
- **Why it matters:** When apps run on user devices, `adb logcat` can read logs emitted by debug statements if not stripped. While sensitive tokens (App Check, Firebase Auth, Razorpay secrets) are omitted from logging, captions and creators visited by the user in Instagram/YouTube are logged at `Log.d` level.
- **Exploitability:** Local applications with `READ_LOGS` (on older Android or rooted devices) or physical access via ADB can view accessibility heuristics and reel metadata.
- **Recommended Remediation:**
  - Add R8 stripping rules in `app/proguard-rules.pro`:
    ```proguard
    -assumenosideeffects class android.util.Log {
        public static boolean isLoggable(java.lang.String, int);
        public static int v(...);
        public static int d(...);
        public static int i(...);
        public static int w(...);
    }
    ```
  - Wrap high-frequency pipeline logging inside `if (BuildConfig.DEBUG)` checks.

---

### 3.3 [FINDING P4-03] (LOW) — Unencrypted Room Database (Threat Model Assessment)
- **Severity:** LOW (Architectural / Threat Model Decision)
- **Location:** [`app/src/main/java/com/reeltrak/app/core/di/DatabaseModule.kt#L43-L51`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/di/DatabaseModule.kt#L43-L51)
- **Evidence:**
  `Room.databaseBuilder(context, ReelTrakDatabase::class.java, "reeltrak_db")` creates standard unencrypted SQLite storage in `/data/data/com.reeltrak.app/databases/reeltrak_db`.
- **Why it matters:**
  - Android application sandbox isolates app databases from other apps on unrooted devices.
  - On rooted devices or forensic physical extraction, plaintext SQLite data can be inspected.
- **Sensitive Data Inventory in Room (`daily_reel_data`):**
  - `dateString` (e.g. `"2026-09-18"`)
  - `totalReelsCount` (Integer)
  - `totalTimeSpentMillis` (Long)
  - `dailyGoal` (Integer)
  - `isStrictModeOn`, `isStrictModeLocked`, `isLimitLocked` (Boolean)
  - `sessions` (List of session start/end timestamps and platform names)
  - `syncStatus` (Integer)
  - **Zero Credentials Stored:** No auth tokens, API keys, passwords, payment info, or identity tokens exist in Room.
- **Crucial Architectural Constraint (Phase 0 Preservation):**
  - Phase 0 mandates that today's tracking record (`dateString == today`) and strict mode enforcement must survive account switching, logout, login, and device restarts.
  - If user-derived encryption keys are used, logging out or switching accounts would destroy or lock the today enforcement state, causing severe security regressions.
- **Recommendation:**
  - If encrypted at rest, use an Android Keystore hardware-backed device master key (`EncryptedSharedPreferences` / SQLCipher device passphrase) rather than a user-derived password, ensuring the database remains accessible to `ReelTrackerService` and `DayManager` regardless of authentication state.

---

### 3.4 [FINDING P4-04] (LOW / CONFIGURATION) — Inconsistent Worker Base URL
- **Severity:** LOW / CONFIGURATION (Pre-Release Alignment)
- **Location:**
  - [`SubscriptionRepositoryImpl.kt#L44`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L44)
  - [`RazorpaySubscriptionPurchaseServiceImpl.kt#L52`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L52)
  - [`AccountRepositoryImpl.kt#L111`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt#L111)
  - [`Phase3BackendEdgeSecurityTest.kt#L327`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/test/java/com/reeltrak/app/security/Phase3BackendEdgeSecurityTest.kt#L327)
- **Evidence:**
  - Android repositories use:  
    `"https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev"`
  - Test suite / doc references use:  
    `"https://reeltrak-backend.pavandevyarakala.workers.dev"`
- **Why it matters:** If the live deployed Cloudflare Worker domain differs from the URL compiled into the Android client, API calls (`create-order`, `verify-payment`, `account/status`, `request-deletion`) will fail in production.
- **Recommended Remediation:**
  - Centralize the production Worker URL in `BuildConfig` or a single shared constant object (e.g. `NetworkConfig.WORKER_BASE_URL`), populated from `build.gradle.kts`.

---

## 4. Evaluation of Other Security Controls

### 4.1 DataStore & Local Preferences (Phase 0 Re-Verification)
- Checked `PreferencesRepositoryImpl.kt`:
  - Proactively removes legacy `auth_id_token` on every `saveAuthSession()` and `clearAuthSession()`.
  - Only stores non-sensitive user identity cache (`email`, `displayName`, `profilePictureUri`).
  - No auth tokens, refresh tokens, Google OAuth secrets, or Razorpay credentials exist in DataStore.

### 4.2 Backup & Restore Configuration
- `AndroidManifest.xml` has `android:allowBackup="false"`.
- Merged release manifest confirms `allowBackup="false"` is preserved across all library merges.
- Prevents ADB backup (`adb backup`) extraction and cloud auto-restore from contaminating account state across devices.

### 4.3 Exported Components & Permissions
- Launcher Activity (`MainActivity`) is the only exported UI component (`exported="true"`).
- `ReelTrackerService` is protected with `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- `RzpTokenReceiver` is exported by Razorpay SDK for device token sharing (standard Razorpay behavior).
- WorkManager internal services and receivers have proper non-exported flags or `BIND_JOB_SERVICE` permissions.

### 4.4 Network Security Config
- `network_security_config.xml` has `<base-config cleartextTrafficPermitted="false" />`.
- Zero cleartext / HTTP exemptions exist.
- All network traffic is strictly TLS/HTTPS.

### 4.5 Cross-Phase Security Guarantees Intact
- **Phase 0:** Device-local today state survives account switching; strict mode lock remains intact.
- **Phase 1:** Razorpay webhook reconciliation, server-authoritative ₹99 / 28-day model, and refund handling remain enforced.
- **Phase 2:** Server-authoritative account deletion state machine (`ACTIVE`, `PENDING_DELETION`, `DELETED`) and 7-day grace period remain enforced.
- **Phase 3:** App Check token attachment, sliding-window rate limiting, and CORS allowlist remain enforced.

---

## 5. Recommended Phase 4 Implementation Plan

When ready to implement Phase 4, execute in the following priority order:

1. **Phase 4.1 (VULN-09): Production Release Signing Setup**
   - Add secure environment-driven `signingConfigs.release` in `app/build.gradle.kts`.
   - Ensure clean local build fallback when signing env variables are not present.
2. **Phase 4.2 (VULN-09): R8 Log Stripping & Worker URL Centralization**
   - Configure R8 `-assumenosideeffects` in `proguard-rules.pro` to strip `Log.d`/`Log.v`/`Log.i`.
   - Centralize `WORKER_BASE_URL` in `BuildConfig` or a single `NetworkConstants` file.
3. **Phase 4.3 (VULN-10): Room Security & At-Rest Hardening**
   - Formally document the threat model: Room stores non-sensitive metrics only; device-local today state must remain accessible across accounts.
   - If SQLCipher is applied, back it with an Android Keystore hardware device key.
4. **Phase 4.4: Testing & Verification**
   - Add `Phase4ClientReleaseSecurityTest.kt` verifying signing config structure, R8 log stripping, backup configuration, and component export safety.
   - Run `./gradlew testDebugUnitTest` and `./gradlew assembleRelease`.

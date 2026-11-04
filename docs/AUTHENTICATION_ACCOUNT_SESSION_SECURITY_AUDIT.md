# STEP 7 — AUTHENTICATION, ACCOUNT LIFECYCLE & SESSION MANAGEMENT SECURITY AUDIT

**Target Application:** Re:Focus Android & Backend Services  
**Audit Scope:** Firebase Authentication, Google Identity / Credential Manager, Token Lifecycle, Session Persistence, Login/Logout Isolation, Account Switching, Account Deletion & Recovery Architecture, Background WorkManager Session Boundaries, and Firestore Rule Authorization.  
**Audit Date:** September 2026  
**Status:** Audit Only — No code, configuration, or database rules modified.

---

## 1. Executive Summary

This security assessment performed an in-depth, read-only audit of the authentication, session state machine, and account lifecycle implementation in Re:Focus.

### Key Positive Findings:
1. **Google Credential Manager Implementation:** Google Sign-In is built using Jetpack's modern `androidx.credentials.CredentialManager` with `GetGoogleIdOption` and explicit server Web Client ID (`524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com`).
2. **Authoritative Edge & Firestore Authorization:** Backend services verify Google RS256 JWKS signatures over Firebase ID tokens and map operations strictly to `token.payload.sub`. Firestore security rules prevent cross-user data reading (`isOwner(userId)`).
3. **Master Restriction Enforcement:** The app enforces a server-authoritative account lock via `/users/{uid}/account/status`. When marked `RESTRICTED`, `LoginViewModel` immediately terminates the local session and signs out of Firebase.

### Critical Vulnerabilities & Gaps Identified:
1. **High Cross-Account Data Contamination on Account Switching (`AUTH-01`):** During logout (`LoginViewModel.signOut()`), the local SQLite database preserves "Today's" tracking record (`clearHistoricalDataExcept(todayStr)`). When User B logs in on the same device, `SyncManager.restoreFromCloud()` executes `backupToCloud()` first, pushing User A's un-cleared tracking counts directly into User B's Firestore subcollection (`/users/{userB_uid}/daily_data/{todayStr}`).
2. **Plaintext ID Token Storage in DataStore (`AUTH-02`):** `PreferencesRepositoryImpl.kt` stores the active `auth_id_token` in unencrypted Proto DataStore (`settings.preferences_pb`), creating credential exposure on rooted devices or ADB backups.
3. **Missing Account Deletion Implementation (`AUTH-03`):** No account deletion mechanism (neither direct `FirebaseUser.delete()` nor backend orchestrated deletion) exists in the application.
4. **Missing Account Deletion Grace / Recovery Window (`AUTH-04`):** The intended "soft delete with recovery window" feature does not exist anywhere in the codebase.

---

## 2. Authentication Architecture

```
[ User UI ]
    │
    ├─► 1. Google Sign-In (CredentialManager) ──► Returns Google ID Token
    │         │
    │         ▼
    │   Firebase.auth.signInWithCredential() ──► Issues Firebase ID Token + Refresh Token
    │
    └─► 2. Email + Password (Firebase Auth)
              │
              ▼
        Firebase.auth.signInWithEmailAndPassword() ──► Issues Firebase ID Token
```

### Supported Providers:
1. **Google Sign-In:** Uses `androidx.credentials.CredentialManager` via `GoogleAuthManager.kt`.
2. **Email / Password:** Handled directly by `Firebase.auth.signInWithEmailAndPassword` via `LoginViewModel.signInWithEmailPassword()`.
3. **Anonymous / Phone Auth:** Not implemented or enabled.

---

## 3. Token Lifecycle & Session Management

| Lifecycle Stage | Implementation Mechanism | Security Evaluation |
| :--- | :--- | :--- |
| **Acquisition** | Obtained upon sign-in via `FirebaseUser.getIdToken(false)`. | **Standard**: Managed by Firebase C++ / Java native SDK. |
| **Local Storage** | Written to `DataStore` (`settings.preferences_pb`) under key `auth_id_token`. | **Vulnerable (`AUTH-02`)**: ID tokens should not be stored in unencrypted DataStore when `Firebase.auth.currentUser` is already available in memory and encrypted by Firebase SDK. |
| **Refresh** | Cloudflare Worker requests in `SubscriptionRepositoryImpl` call `user.getIdToken(false).await()`, allowing Firebase SDK to auto-refresh expired JWTs. | **Secure**: Leverages Firebase SDK's built-in refresh token cycle. |
| **Transmission** | Passed in `Authorization: Bearer <idToken>` headers over TLS HTTPS to Cloudflare Worker. | **Secure**: Never passed in URL query parameters. |
| **Revocation / Expiry** | Verified at edge by Cloudflare Worker validating `payload.exp < nowSec`. | **Secure**: Expired tokens are rejected with HTTP 401. |

---

## 4. Google OAuth & Sign-In Analysis (`GoogleAuthManager.kt`)

- **OAuth Client ID:** `524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com`
- **Dynamic Resource Lookup:** Resolved dynamically from `default_web_client_id` with fallback string.
- **Account Selection:** `setAutoSelectEnabled(false)` and `setFilterByAuthorizedAccounts(false)` ensure explicit user account selection.
- **Credential Parsing:** Validates `credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` and handles malformed credentials gracefully.

---

## 5. Account Switching & Cross-Account Contamination Flow (`AUTH-01`)

### The Contamination Trace:

```
Step 1: User A logs in (UID: userA)
        Tracks 150 reels today (2026-09-17) -> Room SQLite has `totalReelsCount = 150`

Step 2: User A clicks "Sign Out"
        `LoginViewModel.signOut()` executes:
        `database.dailyReelDataDao.clearHistoricalDataExcept(todayStr)`
        -> Today's row (150 reels) is INTENTIONALLY PRESERVED in Room SQLite.

Step 3: User B logs in (UID: userB) on the same device
        `LoginViewModel.signIn()` succeeds -> calls `syncManager.restoreFromCloud()`

Step 4: `SyncManager.restoreFromCloud()` executes `backupToCloud()` first:
        `allDailyData = dailyReelDataDao.getAllDailyDataSync()`
        -> Reads today's record (User A's 150 reels) from local Room DB!
        -> Writes `ref = firestore.collection("users").document("userB").collection("daily_data").document("2026-09-17")`
        -> User A's 150 reels are now PERMANENTLY SAVED under User B's cloud account!
```

### Impact:
- **Data Integrity Breach:** User B's historical statistics and streaks are contaminated with User A's activity.
- **Privacy Violation:** Activity data from the previous account leaks into the subsequent account.

---

## 6. Logout Security Boundary Analysis

| Component / State | Cleaned on Logout? | Mechanism / Reference | Status |
| :--- | :---: | :--- | :--- |
| **Firebase Auth Session** | **YES** | `Firebase.auth.signOut()` in `GoogleAuthManager.kt:L167` | Cleaned |
| **Credential Manager State** | **YES** | `credentialManager.clearCredentialState()` in `GoogleAuthManager.kt:L173` | Cleaned |
| **DataStore Preferences** | **YES** | `preferencesRepository.clearAllPreferences()` in `LoginViewModel.kt:L274` | Cleaned |
| **Room Database (Historical)**| **YES** | `clearHistoricalDataExcept(todayStr)` in `LoginViewModel.kt:L273` | Cleaned |
| **Room Database (Today)** | **NO** | Kept in SQLite to avoid losing unsaved progress | **Vulnerable (`AUTH-01`)** |
| **WorkManager Sync Jobs** | **PARTIAL** | WorkManager runs independently; checks `Firebase.auth.currentUser?.uid` | Fails gracefully if logged out |
| **Firestore Listeners** | **YES** | Disposed via `authStateFlow.flatMapLatest()` cancellation in `SubscriptionRepositoryImpl.kt` | Cleaned |

---

## 7. Account Deletion & Recovery Window Analysis

### A. Account Deletion Audit
- **Current State:** **COMPLETELY MISSING**.
- **Audit Finding:** There is no UI button, ViewModel method, or repository API in the entire project to delete an account or its associated Firestore/Room records.
- **Impact:** Non-compliance with Google Play Store User Data Policy (which mandates an in-app and web-based account deletion mechanism).

### B. Deletion Recovery Window Audit
- **Current State:** **COMPLETELY MISSING**.
- **Audit Finding:** The specification mentions a "soft deletion grace period" (several days recovery window). No database schema, Firestore field, Cloudflare Worker scheduled trigger, or Android logic currently exists to manage pending deletion states.

---

## 8. Background Worker Session Isolation (`SyncWorker.kt`)

- **Execution Safety:**
  ```kotlin
  val uid = Firebase.auth.currentUser?.uid
  if (uid == null) {
      Log.e("SyncWorker", "User not logged in, cannot sync.")
      return@withContext Result.failure()
  }
  ```
- **Finding:** `SyncWorker` verifies that a Firebase user is actively signed in before performing Firestore sync. If logout occurred while a worker was enqueued, the worker safely aborts without writing unauthenticated data.

---

## 9. Detailed Findings

### Finding ID: AUTH-01 — Cross-Account Data Contamination via Partial Database Wipe
- **Severity:** `HIGH`
- **Component:** `LoginViewModel.kt` & `SyncManager.kt`
- **File Paths:**
  - `app/src/main/java/com/reeltrak/app/features/auth/LoginViewModel.kt#L273`
  - `app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt#L142`
- **Current Behavior:** `LoginViewModel.signOut()` preserves today's tracking row in Room SQLite. When a new user logs in, `SyncManager.restoreFromCloud()` immediately executes `backupToCloud()`, uploading the previous user's local tracking row into the new user's Firestore document.
- **Security Impact:** Leaks tracking stats across accounts on shared or switched devices and corrupts cloud database integrity.
- **Realistic Attack Scenario:** User A logs out on a shared tablet; User B logs in with their personal Google account. User A's reel count is permanently written to User B's Firestore account.
- **Remediation:** In `LoginViewModel.signOut()`, execute a full database wipe (`database.dailyReelDataDao.clearAll()`). Any pending unsynced data for User A must be synced *before* sign-out or discarded upon explicit sign-out confirmation.

---

### Finding ID: AUTH-02 — Plaintext ID Token Persistence in DataStore
- **Severity:** `MEDIUM`
- **Component:** `PreferencesRepositoryImpl.kt`
- **File Path:** `app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt#L51, #L205-L225`
- **Current Behavior:** The Firebase ID Token string is written to unencrypted Proto DataStore under `auth_id_token`.
- **Security Impact:** ID tokens can be extracted from device file system backups or rooted environments.
- **Remediation:** Remove `AUTH_ID_TOKEN_KEY` from DataStore. Obtain live, auto-refreshed tokens on-demand via `Firebase.auth.currentUser?.getIdToken(false)`.

---

### Finding ID: AUTH-03 — Missing In-App Account Deletion Capability
- **Severity:** `MEDIUM`
- **Component:** Account Lifecycle / Compliance
- **File Path:** Project-wide
- **Current Behavior:** The app has no mechanism for users to delete their account and associated data.
- **Security Impact:** Violates GDPR / CCPA right-to-erasure and Google Play Developer Account Deletion Policy.
- **Remediation:** Implement an authenticated `deleteAccount()` flow that deletes `/users/{uid}` in Firestore, wipes local storage, and invokes `FirebaseUser.delete()`.

---

### Finding ID: AUTH-04 — Missing Account Deletion Grace / Recovery Window
- **Severity:** `INFORMATIONAL`
- **Component:** Account Lifecycle Specification
- **File Path:** Project-wide
- **Current Behavior:** Soft-delete with recovery window is documented in product specifications but not implemented in code.
- **Security Impact:** Product feature discrepancy.

---

## 10. Attack Scenarios Summary

| Attack Vector | Simulated Scenario | System Resistance & Behavior | Status |
| :--- | :--- | :--- | :--- |
| **Stolen Expired ID Token** | Attacker replays an intercepted token after 1 hour. | Worker validates `payload.exp < nowSec` and rejects with HTTP 401. | **Blocked** |
| **UID Forgery in Request** | Attacker tampers with request body UID. | Worker extracts UID exclusively from RS256-verified `payload.sub`. Body UIDs are ignored. | **Blocked** |
| **Cross-User Firestore Tampering** | Authenticated User A tries to read/write `/users/{userB_uid}/daily_data`. | Firestore security rules evaluate `request.auth.uid == userId` and reject with `PERMISSION_DENIED`. | **Blocked** |
| **Shared Device Account Switch** | User A logs out; User B logs in. | Today's local SQLite row from User A is uploaded to User B's account. | **Vulnerable (`AUTH-01`)** |
| **Credential Manager Hijacking** | Malicious app requests credentials for Re:Focus. | Android OS enforces package name and signing certificate validation. | **Blocked** |

---

## 11. Prioritized Remediation Order

1. **P1 — Fix Cross-Account Data Contamination (`AUTH-01`):** Completely clear the Room SQLite database during `signOut()` after ensuring pre-logout cloud sync completes.
2. **P2 — Remove ID Token Persistence in DataStore (`AUTH-02`):** Rely strictly on in-memory Firebase SDK token retrieval.
3. **P3 — Implement Account Deletion Flow (`AUTH-03`):** Add user-initiated account deletion satisfying Google Play policy.

---

RECOMMENDED NEXT AUDIT STEP:
STEP 8 — FIREBASE SECURITY RULES, CLOUD FUNCTIONS & BACKEND ATTACK-SURFACE AUDIT

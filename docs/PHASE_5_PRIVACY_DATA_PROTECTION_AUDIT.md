# PHASE 5 — PRIVACY & DATA PROTECTION AUDIT

**Target Application**: Re:Focus Android Application (`com.reeltrak.app`)  
**Audit Type**: Privacy, Telemetry, Third-Party SDK & Data Protection Audit  
**Scope**: Android Client, Cloudflare Edge Worker, Firebase/Firestore, Razorpay Payment Pipeline, Local Storage & Accessibility Service  
**Date**: September 18, 2026  
**Auditor**: Independent Privacy & Security Audit Agent  

---

## 1. Executive Summary

This comprehensive privacy and data-protection audit evaluated every data processing pipeline, local storage entity, network transaction, SDK integration, and permission in the Re:Focus Android application.

### Key Audit Conclusions
1. **Accessibility Data Isolation**: The `ReelTrackerService`, `InstagramDetectorV2`, and `YouTubeDetectorV2` extract ephemeral UI text (creator name, truncated caption snippet, audio title) strictly in-memory within an isolated processing loop. **Zero accessibility content, reel captions, user comments, or creator handles are persisted to disk or transmitted off-device.**
2. **Device-Local vs. Account-Owned Data**: Daily tracking metrics for "today" (reels count, usage seconds, strict mode status, daily limits) are maintained device-locally in Room. Only historical day records (prior to today) are synchronized to the authenticated user's Firestore path (`/users/{uid}/daily_data/{date}`).
3. **Authentication & Token Privacy**: No OAuth refresh tokens or Firebase ID tokens are stored in DataStore, Room, or disk. Tokens are fetched dynamically on demand. DataStore caches only non-sensitive display identity (`email`, `displayName`, `profilePictureUri`).
4. **Payment Data Boundaries**: The application receives and stores zero credit card numbers, CVVs, UPI PINs, or bank account credentials. Razorpay Standard Checkout operates as a managed layer. The Cloudflare Worker and Firestore store only order and payment identifiers (`order_id`, `payment_id`), plan type, and timestamp ranges.
5. **Third-Party Telemetry & Analytics**: The application contains **zero advertising SDKs, third-party analytics trackers (e.g. Google Analytics / Firebase Analytics are omitted), and third-party crash reporting SDKs (Crashlytics is omitted)**.
6. **Privacy Disclosure Gaps**: While technical data isolation is robust, user-facing privacy disclosures exhibit gaps (e.g., absence of an in-app privacy policy viewer, lack of explicit Accessibility Service privacy notice during onboarding).

---

## 2. Comprehensive Data Inventory

| Data Category | Specific Data Element | Source | Mandatory / Optional | Processing Location | Storage Location | Off-Device Transmission | Off-Device Destination | Retention Period | Deletion Mechanism | Shared with Third Parties | Stated Purpose |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **Reel Tracking Metrics** | Daily reel scroll count | `ReelTrackerService` (Accessibility) | Mandatory for Core Feature | Local Service Memory | Device Room DB (`daily_reel_data`) | Yes (Historical only) | Google Firestore (`users/{uid}/daily_data`) | Local: Indefinite unless cleared. Cloud: Until account deletion | Server-side purge on account deletion | No | Core reel tracking, goal progress & analytics |
| **Reel Tracking Metrics** | Session duration & start/end times | `ReelTrackerService` / `DayManager` | Mandatory | Local Service Memory | Device Room DB (`daily_reel_data.sessions`) | No (Stripped from sync DTO) | None (Device-local only) | Local: Indefinite | User clears app data | No | Local session history & live tracking card |
| **Reel Tracking Metrics** | Daily goal & strict-mode locks | User Input / Dashboard | Optional / User-set | UI / StateFlow | Room DB + DataStore (`settings`) | Yes (Goal & strict flags) | Google Firestore (`users/{uid}/daily_data`) | Local & Cloud: Until account deletion | Account deletion API | No | Goal enforcement & anti-bypass locks |
| **Account Identity** | Firebase UID | Firebase Auth | Mandatory for Cloud Sync | Memory / Auth Interceptor | Memory / Auth Session | Yes | Google Firebase & Cloudflare Worker | Account lifecycle | Permanent purge on account deletion | Google Firebase | Cloud sync isolation & backend authorization |
| **Account Identity** | Email address | Google Sign-In / Firebase Auth | Optional (App usable without login) | Memory / DataStore | DataStore (`settings`) | Yes | Google Firebase & Cloudflare Worker (prefill) | Until sign out / account deletion | `clearAuthSession()` / Deletion API | Razorpay (prefill for receipts) | Account identification & receipt dispatch |
| **Account Identity** | Display Name | Google Sign-In / Firebase Auth | Optional | Memory / DataStore | DataStore (`settings`) | Yes | Google Firebase & Razorpay (prefill) | Until sign out / account deletion | `clearAuthSession()` / Deletion API | Razorpay (prefill) | UI profile header & payment receipts |
| **Account Identity** | Profile Picture URI | Google Sign-In / Firebase Auth | Optional | UI / Coil | DataStore (`settings`) | No (Loaded directly from Google CDN) | None | Until sign out / account deletion | `clearAuthSession()` | Google CDN (image fetch) | UI avatar display |
| **Authentication Secrets** | Google OAuth Tokens & Firebase ID Tokens | Google Credential Manager & Firebase SDK | Mandatory when logged in | Memory (volatile) | **NOT STORED** (Fetched on demand) | Yes (In HTTP `Authorization` header) | Google Firebase & Cloudflare Worker | Memory lifetime / 1 hr token expiry | Automatic SDK token eviction | No | API request authorization |
| **Subscription & Orders** | Order ID (`order_...`) | Cloudflare Worker / Razorpay | Mandatory for Purchase | Memory / Worker | Firestore (`users/{uid}/subscription/status`) | Yes | Cloudflare Worker & Firestore | Active subscription period + 7 days | Purged on account deletion | Razorpay | Order tracking & verification |
| **Subscription & Orders** | Payment ID (`pay_...`) | Razorpay SDK | Mandatory for Purchase | Memory / Worker | Firestore (`users/{uid}/subscription/status`) | Yes | Cloudflare Worker & Firestore | Active subscription period + 7 days | Purged on account deletion | Razorpay | Entitlement grant & refund reconciliation |
| **Subscription & Orders** | Entitlement Start & End Timestamps | Cloudflare Worker | Mandatory for Purchase | Memory / Worker | Firestore (`users/{uid}/subscription/status`) | Yes | Cloudflare Worker & Firestore | Active period | Purged on account deletion | No | Feature gate / 28-day access enforcement |
| **Payment Credentials** | Card numbers, CVV, UPI PINs, NetBanking creds | User entry in Razorpay UI | Optional | **NOT ACCESSIBLE** (Razorpay webview/dialog) | **NOT STORED** | Transmitted by Razorpay SDK directly | Razorpay Payment Gateway | Managed by Razorpay PCI-DSS compliant infrastructure | Managed by Razorpay | Razorpay & issuing banks | Payment processing |
| **Accessibility Tree Content** | Creator names, raw captions, audio titles | Instagram / YouTube UI Nodes | Mandatory for Detection | `InstagramDetectorV2` / `YouTubeDetectorV2` (Memory) | **NOT STORED** (Recycled & discarded) | **NO** | **NONE** | Transient frame analysis duration (<50ms) | Instant garbage collection / Node recycling | No | Reel transition fingerprinting |
| **App Diagnostics & Telemetry** | Logcat debug messages | Android Logger (`Log.*`) | Development only | Local device logcat buffer | **NOT STORED** (Stripped by R8 in release) | **NO** | **NONE** | Logcat ring buffer | Cleared on device reboot / buffer wrap | No | Local developer debugging |

---

## 3. Accessibility Service Privacy

### Observed Technical Implementation
- **Service Configuration** (`accessibility_service_config.xml`): Scoped strictly to `com.instagram.android` and `com.google.android.youtube`. Does not inspect other apps, system keyboards, password managers, or messaging apps.
- **Node Traversal & Recycling**: In [`InstagramDetectorV2.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt) and [`YouTubeDetectorV2.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt), nodes are traversed using BFS up to a shallow limit (max 100–200 nodes). All visited nodes explicitly call `.recycle()` in `finally` blocks to release memory immediately.
- **Semantic Fingerprint Generation**: Extracted strings (creator, truncated caption, song) are combined into a transient `SemanticFingerprint` used solely by `TransitionLedger` to detect when the active reel changes.
- **Off-Device Transmission**: Audited all network models (`CloudDailyReelData`, `SyncWorker`, Worker endpoints). **Zero accessibility strings or fingerprints are ever uploaded or written to persistent storage.**
- **Release Logging Hardening**: ProGuard R8 rules (`-assumenosideeffects class android.util.Log`) strip all verbose, debug, and info logs from release builds, ensuring extracted metadata cannot leak to device system logs.

---

## 4. Local Storage Privacy

1. **Room Database (`daily_reel_data`)**:
   - Stores: `dateString`, `totalReelsCount`, `totalTimeSpentMillis`, `dailyGoal`, `isSkullDay`, `isStrictModeOn`, `isStrictModeLocked`, `isLimitLocked`, `sessions` (app name, start/end timestamps, reels scrolled), `updatedAt`, `syncStatus`.
   - Contains zero personal identifiers, account emails, Firebase tokens, or payment secrets.
2. **Jetpack Preferences DataStore (`settings`)**:
   - Stores: UI preferences, lock flags, milestone badges, and non-sensitive identity cache (`auth_email`, `auth_display_name`, `auth_profile_picture_uri`).
   - Legacy token key `auth_id_token` is actively purged upon session save and sign-out.
3. **Android Backup Configuration**:
   - `AndroidManifest.xml` explicitly defines `android:allowBackup="false"`. Application data is excluded from automated cloud backups and adb backup extraction.

---

## 5. Firebase & Cloud Data Mapping

```
Google Cloud Firestore
 └── /users/{uid}                               [User Root: email, createdAt]
      ├── /daily_data/{dateString}              [Historical metrics: totalReelsCount, dailyGoal, isStrictModeOn]
      ├── /subscription/status                  [Server-managed: planId, subscriptionStatus, currentPeriodStart, currentPeriodEnd, premium]
      ├── /account/status                       [Server-managed: accountStatus, scheduledDeletionTime]
      └── /onboarding/tour                      [Tour state: hasCompletedTour, completedAt]
```

- **Device-Local Isolation**: Today's active record is excluded from upload by `dailyReelDataDao.getPendingSyncData(todayStr)`. Only historical records are synchronized.
- **Access Authority**: Firestore rules strictly prohibit client writes to `/subscription/*` and `/account/*`. All subscription and account status modifications are executed by the Cloudflare Worker using service account credentials.

---

## 6. Authentication Privacy

- **Credential Manager Integration**: Uses modern `androidx.credentials` with `GoogleIdTokenCredential`. Raw Google account passwords or refresh tokens are never handled or stored by the app.
- **ID Token Handling**: Firebase ID tokens are generated on demand via `user.getIdToken(false)` and attached to HTTP headers in volatile memory.
- **Sign Out Cleanup**: Calling `signOut()` clears the local DataStore identity cache, revokes Firebase Auth state, and resets active subscription StateFlows in memory.

---

## 7. Razorpay & Payment Privacy

- **Client Boundary**: The app delegates checkout to `com.razorpay.Checkout`. No PCI-DSS sensitive data enters app memory.
- **Prefill Scope**: Only `user.email` and `user.displayName` are passed to the Razorpay checkout builder to generate invoices and payment receipts.
- **Server Record Minimization**:
  - Worker stores only `order_id`, `payment_id`, `firebaseUid`, and timestamp in `razorpay_events/{eventId}` for idempotent webhook processing.
  - No customer bank account numbers or card numbers are processed or retained on the Worker.

---

## 8. Third-Party SDK Audit

| Library / SDK | Version | Declared Purpose | Data Accessed | Off-Device Transmission | Tracking / Ad Network | Consent / Notice Status |
|---|---|---|---|---|---|---|
| **Google Firebase Auth** | 33.1.2 (BoM) | User Authentication | Email, UID, OAuth tokens | Google Firebase Auth API | No | Disclosed in login footer |
| **Google Cloud Firestore** | 33.1.2 (BoM) | Cloud Data Synchronization | Daily reel counts, goals, subscription status | Google Firestore REST/gRPC | No | Disclosed in login footer |
| **Firebase App Check** | 33.1.2 (BoM) | Backend Request Attestation | Device/app integrity signals | Google Play Integrity API | No | Required for API security |
| **Google Credential Manager** | 1.2.2 | Google One-Tap Sign In | Google account selector | Google Play Services | No | Google system UI |
| **Razorpay Checkout SDK** | 1.6.38 | Subscription Payment Gateway | Email, Name, Payment instrument | Razorpay Servers / UPI / Banks | No | Payment flow disclosure |
| **Coil Compose** | 2.6.0 | Profile Avatar Image Loading | Google profile picture URL | Google User Content CDN | No | Standard HTTP image load |
| **AndroidX WorkManager** | 2.9.0 | Background Sync Scheduling | Local database queries | None (Internal scheduling) | No | System service |

*Verification Note: The application includes ZERO dedicated tracking, attribution, or third-party ad SDKs (e.g. no Facebook SDK, AppsFlyer, Mixpanel, Firebase Analytics, or Google AdMob).*

---

## 9. Network Data Flows

| Endpoint URL | Method | Auth Header | Transmitted Payload | Response Data | Personal Data Transmitted |
|---|---|---|---|---|---|
| `https://refocus-razorpay-worker.../api/ensure-subscription` | POST | Bearer Firebase JWT | `{}` | `{ status, trialActive, currentPeriodEnd }` | Verified Firebase UID in JWT |
| `https://refocus-razorpay-worker.../api/create-order` | POST | Bearer Firebase JWT | `{ planId: "plan_monthly" }` | `{ order_id, amount, currency, key_id }` | Verified Firebase UID in JWT |
| `https://refocus-razorpay-worker.../api/verify-payment` | POST | Bearer Firebase JWT | `{ razorpay_order_id, razorpay_payment_id, razorpay_signature }` | `{ success, status, currentPeriodEnd }` | Payment ID, Order ID, UID in JWT |
| `https://refocus-razorpay-worker.../api/account/request-deletion` | POST | Bearer Firebase JWT | `{}` | `{ success, status: "PENDING_DELETION", scheduledDeletionTime }` | Verified Firebase UID in JWT |
| `https://refocus-razorpay-worker.../api/account/cancel-deletion` | POST | Bearer Firebase JWT | `{}` | `{ success, status: "ACTIVE" }` | Verified Firebase UID in JWT |
| `https://refocus-razorpay-worker.../api/account/status` | GET | Bearer Firebase JWT | None | `{ accountStatus, scheduledDeletionTime }` | Verified Firebase UID in JWT |
| `https://refocus-razorpay-worker.../api/razorpay-webhook` | POST | Razorpay HMAC Signature | Raw Razorpay Webhook JSON event | `{ received: true }` | Razorpay payment/refund IDs, UID |
| `https://firestore.googleapis.com/...` | REST / gRPC | Bearer Firebase JWT | `CloudDailyReelData` (dateString, totalReelsCount, dailyGoal, isStrictModeOn) | Document payloads | Daily metrics mapped to UID |

---

## 10. Logging & Telemetry

- **Release Build Hardening**: ProGuard configuration includes `-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); public static int i(...); }`.
- **Debug Logs**: In debug builds, logs print internal state machine messages. No passwords, auth tokens, or payment card details are logged in any build variant.

---

## 11. Data Retention Policy Matrix

| Storage Category | Data Items | Lifecycle / Retention Trigger | Automatic Expiry | Post-Deletion State |
|---|---|---|---|---|
| **Local Room Database** | Today's count, goal, strict mode | Overwritten daily or retained until app data is cleared | None (Rolling daily records) | Preserved on device to prevent anti-bypass loops |
| **Local Preferences DataStore** | User email, display name, UI flags | Retained while signed in | Cleared on Sign Out / Deletion | Cleared immediately (`clearAuthSession`) |
| **Firestore User Documents** | Historical daily data, profile, onboarding | Created on sync / login | None | Permanently purged via Worker delete API after 7 days |
| **Firestore Subscription Documents** | Active period start/end, subscription status | Created on purchase / trial | Set to `EXPIRED` / `CANCELLED` | Purged on permanent account deletion |
| **Firestore Webhook Event Cache** | Event ID, event type, processed timestamp | Created on webhook delivery | 30-day logical TTL | Retained for financial audit & reconciliation |
| **Google Identity Toolkit** | Firebase Auth account record | Created on registration | None | Deleted permanently via Admin API on account deletion |

---

## 12. Account Deletion & User Rights

1. **Deletion Trigger**: User initiates deletion via Settings (`Delete Account`).
2. **7-Day Soft-Delete Grace Period**: Account transitions to `PENDING_DELETION`. User is signed out. Logging in during this window prompts the user with an option to restore their account (`/api/account/cancel-deletion`).
3. **Permanent Purge**: After 7 days, any status query triggers `purgeAccountData(uid, env)`, deleting all Firestore documents under `/users/{uid}/*` and calling the Google Identity Toolkit API to delete the auth account.
4. **Device-Local Preservation**: Today's active limit enforcement remains on the physical device to prevent users from bypassing screen-time locks by rapidly deleting and recreating accounts.

---

## 13. Privacy Disclosure & Consent Gap Analysis

| Area | Current Implementation | Observed Gap | Recommendation |
|---|---|---|---|
| **Login Screen Policy Link** | Static text: *"By continuing, you agree to our Terms of Service and Privacy Policy."* | Text is not hyperlinked; clicking does not open a web URL or in-app dialog. | Make *"Terms of Service"* and *"Privacy Policy"* clickable links opening the hosted policy URLs. |
| **Accessibility Service Notice** | System accessibility settings prompt. | No dedicated in-app pre-permission dialog explaining that accessibility is used solely for reel counting and never reads personal messages or transmits data. | Add an in-app explanatory screen prior to launching system accessibility settings. |
| **In-App Privacy Policy Viewer** | Missing in Settings. | User cannot view the Privacy Policy after completing onboarding. | Add a "Privacy Policy" row in Settings linking to the public policy document. |
| **Account Deletion Warning** | Detailed confirmation dialog in Settings. | Accurately explains 7-day grace period, historical data purge, and local device enforcement retention. | **Compliant** — Clear and transparent. |

---

## 14. Android Permission Audit

| Permission | Protection Level | Purpose in App | Is Personal Data Collected? | Minimization Status |
|---|---|---|---|---|
| `android.permission.INTERNET` | Normal | Cloud sync, Worker APIs, Razorpay checkout | Yes (Sync metrics, tokens) | **REQUIRED** |
| `android.permission.SYSTEM_ALERT_WINDOW` | Signature / Special | Display floating reel counter overlay | No | **REQUIRED** for overlay feature |
| `android.permission.POST_NOTIFICATIONS` | Runtime (API 33+) | Daily usage alerts and limit warnings | No | **REQUIRED** for notifications |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | Signature / System | Real-time reel transition detection | Ephemeral text processed in-memory only | **REQUIRED** for core product function |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Normal | WorkManager reschedule on reboot | No | Merged by WorkManager |
| `android.permission.WAKE_LOCK` | Normal | WorkManager background execution | No | Merged by WorkManager |
| `android.permission.ACCESS_NETWORK_STATE` | Normal | WorkManager network constraint checks | No | Merged by WorkManager |

*Unnecessary Permissions Check: The app requests ZERO location, camera, microphone, contacts, storage, SMS, or phone state permissions.*

---

## 15. Data Minimization Review

| Data Element | Storage Location | Dependency Assessment | Classification | Action |
|---|---|---|---|---|
| `SessionData` (Per-session timestamps) | Local Room DB only | Used for local session history card on Dashboard | **KEEP** | Retain locally; confirm it is never uploaded. |
| `auth_profile_picture_uri` | DataStore (`settings`) | Used solely to display Google avatar in Settings | **KEEP** | Cached locally for UI performance; cleared on logout. |
| `CloudDailyReelData.dailyGoal` | Firestore | Syncs goal across devices | **KEEP** | Minimal payload. |
| `razorpay_events` Webhook Records | Firestore | Required for duplicate webhook prevention and financial reconciliation | **KEEP** | Contains only transaction IDs; zero cardholder data. |

---

## 16. Privacy Threat Scenario Evaluation

1. **Account A Logout → Account B Login**: Today's device enforcement state stays active. SyncWorker uploads only Account B's future historical data to Account B's path. Account A's local identity cache is purged on logout.
2. **Device Root Access / Local DB Inspection**: Attacker with root privileges can inspect Room DB to view reel counts and goal limits. **No passwords, authentication tokens, or payment card numbers exist in the database.**
3. **Network Interception (MITM)**: All communication uses HTTPS (TLS 1.3). Cleartext traffic is disabled globally via `network_security_config.xml`.
4. **Accessibility Leakage**: Extracted strings are processed in ephemeral memory and discarded immediately. Release R8 rules strip logging.

---

## 17. Audit Findings Table

| Finding ID | Severity | Category | File / Location | Observed Behavior | Privacy Impact | Recommended Remediation | Code Change Req? | Policy Req? |
|---|---|---|---|---|---|---|---|---|
| **P5-01** | **LOW** | Consent & Disclosure | [`LoginScreen.kt:423`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/auth/ui/LoginScreen.kt) | Privacy Policy and Terms text in login footer is static text without clickable URLs. | Users cannot easily inspect legal disclosures before authenticating. | Convert text to clickable `ClickableText` / `AnnotatedString` opening the hosted policy URLs. | Yes | Yes |
| **P5-02** | **LOW** | Consent & Disclosure | [`SettingsScreen.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt) | Settings screen lacks a dedicated "Privacy Policy" navigation row. | Authenticated users have no in-app access to review privacy terms. | Add a "Privacy Policy" and "Terms of Service" entry in the Settings menu. | Yes | Yes |
| **P5-03** | **INFORMATIONAL** | Transparency | [`OnboardingScreen.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/onboarding/ui/OnboardingScreen.kt) | App requests accessibility permission without a dedicated pre-permission privacy explanation. | Users may be uncertain about how Accessibility data is handled. | Provide an introductory card explaining that Accessibility data never leaves the device. | Yes | No |

---

## 18. Recommended Remediation Roadmap (Phase 5)

1. **UI Disclosures Hardening**:
   - Make *"Terms of Service"* and *"Privacy Policy"* on `LoginScreen.kt` clickable links.
   - Add a *"Privacy & Legal"* section in `SettingsScreen.kt` linking to hosted policy pages.
   - Add an introductory Accessibility Privacy disclosure during the onboarding setup flow.
2. **Hosted Policy Deployment**:
   - Host the public Privacy Policy and Terms of Service documents on the Firebase web hosting domain (`https://refocus-504119.web.app/privacy.html` or equivalent).
3. **Legal Review**:
   - Ensure the published Privacy Policy accurately reflects:
     - Zero collection of accessibility message/caption content off-device.
     - 7-day soft-delete grace period with server-authoritative permanent purge.
     - Razorpay third-party payment processing.
     - Device-local retention of today's enforcement counter to prevent limit bypass.

---

## 19. Final Audit Summary Statistics

- **Total Findings**: **3** (0 Critical, 0 High, 0 Medium, 2 Low, 1 Informational)
- **Confirmed Data Transmitted Off-Device**:
  - Historical daily reel counts, daily goals, strict-mode state (to Google Firestore).
  - Firebase Auth UID, email, display name (to Firebase Auth & Cloudflare Worker).
  - Razorpay Order ID, Payment ID, Signature (to Cloudflare Worker & Razorpay).
- **Confirmed Third-Party Data Recipients**:
  - Google Cloud Platform (Firebase Auth, Cloud Firestore, Play Integrity).
  - Razorpay Software Pvt. Ltd. (Payment processing).
  - Cloudflare, Inc. (Worker edge compute).
- **Confirmed Persisted Personal Data**: User email and display name (cached in DataStore; cleared on sign-out/deletion).
- **Unnecessary Data Collection**: **None** (All collected data directly serves core tracking, anti-bypass goal enforcement, or billing).
- **Phase 5 Implementation Recommendation**: Proceed with implementing the UI disclosure items (P5-01, P5-02, P5-03) and hosting the public Privacy Policy.

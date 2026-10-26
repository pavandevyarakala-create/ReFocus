# SECURITY RECONNAISSANCE REPORT
**Application:** Re:Focus (formerly ReelTrak)  
**Date:** September 17, 2026  
**Audit Type:** Initial Architecture & Vulnerability Reconnaissance (Non-destructive / Inspection Only)

---

## 1. Executive Summary & Architecture Overview

Re:Focus is an Android digital wellbeing application designed to track and limit short-form video consumption (Instagram Reels, YouTube Shorts) via an Android `AccessibilityService`. The ecosystem consists of:
1. **Android Client Application:** Kotlin, Jetpack Compose, AndroidX Credential Manager, Firebase Auth SDK, Firestore SDK, Room SQLite, DataStore Preferences, Razorpay Checkout SDK, and OkHttp.
2. **Backend Services:**
   - **Primary Serverless Edge Backend:** Cloudflare Worker (`worker/src/index.js`) deployed at `refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`.
   - **Legacy/Secondary Cloud Functions:** Firebase Cloud Functions (`functions/index.js`) in `us-central1`.
   - **Legacy Express Server:** Local/Node.js express backend (`server.js`).
3. **Database & Storage Layer:** Cloud Firestore, Firebase Auth, Local Room SQLite, and Android DataStore.
4. **Third-Party Payment Gateway:** Razorpay Standard Checkout & Orders API.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                CLIENT (UNTRUSTED)                                │
│                                                                                  │
│   ┌──────────────────────────────────────────────────────────────────────────┐   │
│   │                              Android App                                 │   │
│   │  • AccessibilityService (Reel tracking, UI tree BFS analysis)            │   │
│   │  • TrustedTimeManager (Anti-rollback monotonic clock anchor)             │   │
│   │  • SubscriptionManager (Access gating: TRIAL | ACTIVE | LOCKED)          │   │
│   │  • DataStore Preferences & Room SQLite DB (Local tracking data)          │   │
│   │  • Razorpay Android Checkout SDK (Opens Razorpay payment modal)          │   │
│   └──────────────────────┬───────────────────────────────┬───────────────────┘   │
└──────────────────────────┼───────────────────────────────┼───────────────────────┘
                           │ (Firebase ID Token via HTTPS) │ (Real-time Snapshot Listener)
                           ▼                               ▼
┌──────────────────────────────────────────────┐  ┌────────────────────────────────┐
│             BACKEND (TRUSTED)                │  │    DATABASE & AUTH (GCP)       │
│                                              │  │                                │
│  Cloudflare Worker / Firebase Functions      │  │  Firebase Authentication       │
│  • Edge Google JWKS Verification (RS256)     │  │  • Google ID Token & Email/Pwd │
│  • Google Service Account OAuth2 Generator   │  │                                │
│  • Razorpay HMAC-SHA256 Sig Verification     │  │  Cloud Firestore               │
│  • Direct Firestore REST API Writes          │  │  • /users/{uid}/subscription   │
│    (/users/{uid}/subscription/status)        │  │  • /users/{uid}/account/status │
│                                              │  │  • /users/{uid}/daily_data     │
│  Razorpay API (https://api.razorpay.com)     │  │  • Security Rules Enforcement  │
│  • Orders API (Fixed ₹99 / 9900 paise)       │  └────────────────────────────────┘
│  • Payments Fetch & Capture Validation       │
└──────────────────────────────────────────────┘
```

---

## 2. Security Boundaries & Trust Zones

| Component | Trust Level | Description & Boundary Controls |
|---|---|---|
| **Android Client** | **Untrusted** | Can be decompiled, debugged, patched, or run on rooted environments. Must never hold server secrets or authoritatively self-grant subscription/access privileges. |
| **DataStore / Room** | **Untrusted (Local)** | Plaintext SQLite and key-value preferences on device filesystem; subject to physical/root extraction or file modification. |
| **Firebase Auth** | **Trusted Platform** | Authenticates identity, generates signed RS256 JWT ID Tokens (`aud`, `iss`, `sub`, `exp`). |
| **Cloudflare Worker** | **Trusted Server** | Edge-runtime verifying Firebase ID tokens, managing Razorpay secrets, enforcing business logic, and updating Firestore via Google Service Account OAuth2. |
| **Cloud Firestore** | **Trusted Rules Engine** | Restricts client write operations to `/users/{uid}/daily_data` and safe `/users/{uid}` fields. Rejects client writes to `/subscription/*` and `/account/*`. |
| **Razorpay API** | **Trusted 3rd Party** | Authoritative payment status, payment capturing, and HMAC signature generator. |

---

## 3. Vulnerability Findings & Security Gaps Log

---

### [FINDING-01] CRITICAL: Hardcoded Razorpay Secret Key in Cloud Functions Code Fallback
- **Severity:** `CRITICAL`
- **File / Path:** [`functions/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js#L28)
- **Component / Function:** `getKeySecret()` (Line 28)
- **Current Implementation:**
  ```javascript
  function getKeySecret() {
      try {
          const val = razorpayKeySecret.value();
          if (val) return val;
      } catch (_) {}
      return process.env.RAZORPAY_KEY_SECRET || "MvcuJz39QJsQXGf0cPSvfgib";
  }
  ```
- **Security Impact & Risk:**
  The live/test Razorpay API secret key (`MvcuJz39QJsQXGf0cPSvfgib`) is hardcoded as a fallback literal directly in source code. Any party with source access (or if packaged/leaked in version control) can sign arbitrary payment payloads, issue fake payment verifications, manipulate Razorpay merchant settings, or trigger fraudulent refunds/captures.
- **Exploitability:** **Directly Exploitable** if this key is active on Razorpay or if repository is exposed.
- **Remediation Action Needed Later:**
  1. Revoke and rotate `RAZORPAY_KEY_SECRET` immediately in Razorpay Dashboard.
  2. Strip hardcoded fallback strings from `functions/index.js`.
  3. Require cloud secret injection via Google Secret Manager / Cloudflare Worker Secrets exclusively.

---

### [FINDING-02] HIGH: Cleartext Sensitive Secret Key Committed in Local Environment File
- **Severity:** `HIGH`
- **File / Path:** [`.env`](file:///c:/Users/pavan/OneDrive/Documents/REEL/.env#L2)
- **Component / Function:** Environment Configuration
- **Current Implementation:**
  ```env
  RAZORPAY_KEY_ID=rzp_test_Tb9rSP1OzJ3Tdi
  RAZORPAY_KEY_SECRET=MvcuJz39QJsQXGf0cPSvfgib
  ```
- **Security Impact & Risk:**
  The project directory contains a plaintext `.env` holding Razorpay secrets. If `.env` is accidentally tracked, staged, or included in build artifacts, production/test secrets leak to version control or packaging.
- **Exploitability:** **Potential Risk / Exploitable on Repo Leakage**.
- **Remediation Action Needed Later:**
  Verify `.gitignore` rules, ensure `.env` is purged from git history if ever committed, and use template `.env.example` with zero actual credentials.

---

### [FINDING-03] HIGH: Insecure Room Database & DataStore Without At-Rest Encryption (SQLCipher / EncryptedSharedPreferences)
- **Severity:** `HIGH`
- **File / Path:** 
  - [`app/src/main/java/com/reeltrak/app/data/local/database/ReelTrakDatabase.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/local/database/ReelTrakDatabase.kt)
  - [`app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt)
  - [`app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt)
- **Component / Function:** Database / Storage Layer
- **Current Implementation:**
  - Standard Room database instantiated without SQLCipher / passphrase encryption.
  - Plaintext Proto/Preferences DataStore (`settings`, `trusted_time_store`) storing user email, display name, OAuth ID token, lock timestamps, and monotonic time high-water marks.
- **Security Impact & Risk:**
  On rooted devices, or via physical device extraction / ADB backups if debuggable, an attacker or malicious app with root privileges can inspect the user's detailed app usage timestamps, session statistics, email addresses, or manipulate `trusted_time_store` data files to alter local subscription countdowns or reset daily limits.
- **Exploitability:** **Exploitable on Rooted / Compromised Devices**.
- **Remediation Action Needed Later:**
  Evaluate SQLCipher for Room SQLite and AndroidX EncryptedSharedPreferences / Crypto DataStore for sensitive authentication tokens and trusted time state.

---

### [FINDING-04] MEDIUM: Client-Side Trial Auto-Initialization Allowed Without Rate Limiting or CAPTCHA
- **Severity:** `MEDIUM`
- **File / Path:** 
  - [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L367-L395)
  - [`app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L140-L159)
- **Component / Function:** `POST /api/ensure-subscription`
- **Current Implementation:**
  When any authenticated Firebase user calls `/api/ensure-subscription`, if no Firestore document exists at `/users/{uid}/subscription/status`, the Cloudflare Worker creates a 7-day trial starting at `now` and ending `now + 7 days`.
- **Security Impact & Risk:**
  Because the client can register arbitrary free Firebase email/password or anonymous accounts without phone verification or device integrity attestations (Play Integrity API), an automated script or malicious user could endlessly create new Firebase accounts to obtain indefinite free 7-day trials.
- **Exploitability:** **Exploitable for Trial Abuse / Service Sybil Attack**.
- **Remediation Action Needed Later:**
  Integrate Google Play Integrity API / App Check tokens on the `/api/ensure-subscription` endpoint, or tie trials to unique verified identifiers (e.g. Play Billing account or Device ID with hardware key attestation).

---

### [FINDING-05] MEDIUM: ProGuard / R8 Obfuscation Rules Omit Anti-Tamper & Root Detection Mechanisms
- **Severity:** `MEDIUM`
- **File / Path:** 
  - [`app/build.gradle.kts`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L24-L32)
  - [`app/proguard-rules.pro`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro)
- **Component / Function:** Build Configuration & Client Protections
- **Current Implementation:**
  - `isMinifyEnabled = true` and `isShrinkResources = true` are active in release build type.
  - However, there are no root detection checks, emulator detection checks, signature verification checks, or Frida/Xposed hooking detection hooks in the application.
- **Security Impact & Risk:**
  Attackers can easily hook `SubscriptionManager.isAppUnlockedNow()` or `CloudSubscriptionDto.toDomain()` using Frida or Xposed on rooted devices to force `isUnlocked = true` and bypass client paywalls completely.
- **Exploitability:** **Exploitable on Client-Side Reversing Tools (Frida/Xposed)**.
- **Remediation Action Needed Later:**
  Implement client runtime integrity verifications, Play Integrity API attestation, and anti-hooking checks.

---

### [FINDING-06] LOW: Permissive CORS Configuration on Cloudflare Worker & Functions
- **Severity:** `LOW`
- **File / Path:** 
  - [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L26-L31)
  - [`functions/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js#L8)
- **Component / Function:** `CORS_HEADERS = { "Access-Control-Allow-Origin": "*" }`
- **Current Implementation:**
  The Cloudflare Worker and Firebase Functions return `Access-Control-Allow-Origin: *` on all endpoints (`/api/create-order`, `/api/verify-payment`, `/api/ensure-subscription`).
- **Security Impact & Risk:**
  While requests require a valid `Authorization: Bearer <Firebase_ID_Token>` header (mitigating unauthenticated browser cross-origin requests), wildcard CORS is unnecessary for an API exclusively consumed by a native Android mobile application. It exposes the endpoints to browser-based cross-origin invocations if an attacker tricks a user into authenticating in a browser session.
- **Exploitability:** **Low / Theoretical Risk**.
- **Remediation Action Needed Later:**
  Remove web CORS headers or restrict to authorized web origin domains if web clients are ever introduced.

---

### [FINDING-07] LOW: Accessibility Service Broadcast Receivers Registered Without Custom Signature Permissions
- **Severity:** `LOW`
- **File / Path:** [`app/src/main/java/com/reeltrak/app/services/ReelTrackerService.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/services/ReelTrackerService.kt#L258-L263)
- **Component / Function:** `debugReceiver` Registration
- **Current Implementation:**
  ```kotlin
  ContextCompat.registerReceiver(
      this,
      debugReceiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED
  )
  ```
  The debug receiver responds to actions like `ACTION_DEBUG_INCREMENT`, `ACTION_DEBUG_RESET`, `ACTION_DEBUG_SHOW_OVERLAY`. While registered with `RECEIVER_NOT_EXPORTED` on API 33+, on older Android versions or debug builds, internal broadcast intents could potentially be triggered by local processes.
- **Security Impact & Risk:**
  In non-debug releases, these debug actions are still compiled into `ReelTrackerService.kt` rather than being isolated behind build variant source trees (`src/debug` vs `src/release`).
- **Exploitability:** **Potential Risk in Debug / Low in Production**.
- **Remediation Action Needed Later:**
  Move debug broadcast receivers and mock controllers entirely to the `debug` source set or strip them during release builds using `BuildConfig.DEBUG` guards.

---

### [FINDING-08] INFORMATIONAL: Absence of Server-Side Webhook for Out-of-Band Razorpay Lifecycle Events (Refunds, Chargebacks, Auto-Cancellations)
- **Severity:** `INFORMATIONAL`
- **File / Path:** [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js)
- **Component / Function:** Razorpay Integration
- **Current Implementation:**
  Subscription activation is exclusively triggered by the Android client invoking `POST /api/verify-payment`. There is no Razorpay Webhook endpoint (`POST /api/razorpay-webhook`) handling backend-to-backend events such as `payment.failed`, `refund.created`, `subscription.charged`, or dispute updates.
- **Security Impact & Risk:**
  If a user disputes a charge or requests a refund directly through their bank or Razorpay, Firestore will retain `subscriptionStatus: "ACTIVE"` for the remainder of the 28 days because no webhook revokes the Firestore document.
- **Exploitability:** **Business Logic Gap / Non-Immediate Threat**.
- **Remediation Action Needed Later:**
  Implement a secure Razorpay Webhook endpoint in the Cloudflare Worker with webhook signature verification (`X-Razorpay-Signature`) to automatically handle refunds and cancellations.

---

## 4. Missing Security Controls Checklist

1. [ ] **Play Integrity API / App Check:** Client requests to Cloudflare Worker and Firebase are not validated for device tampering or APK signature spoofing.
2. [ ] **At-Rest Storage Encryption:** Room database and DataStore preferences are unencrypted.
3. [ ] **Certificate Pinning / HPKP:** OkHttp client relies on standard Android system CA trust store without certificate pinning for `workers.dev` or Razorpay APIs.
4. [ ] **Automated Secret Rotation & Vaulting:** Hardcoded fallbacks exist in legacy codebases.
5. [ ] **Razorpay Server Webhook Handling:** No webhook listener for chargebacks, refunds, and bank reversals.
6. [ ] **Root & Jailbreak Detection:** No runtime checks against Frida, Magisk, or substrate hooking frameworks.

---

## 5. Security Reconnaissance Conclusions

### Current Architecture & Security Boundaries
- **Client/Server Division:** Strong separation of subscription authority. The Firestore security rules correctly prohibit the Android client from writing directly to `/users/{userId}/subscription/status` and `/users/{userId}/account/status`. All paid subscription modifications are performed via the Cloudflare Worker using Google OAuth2 Service Account credentials.
- **Time Anti-Tampering:** The `TrustedTimeManager` provides client-side monotonicity and rollback protection anchored by server timestamps from Firestore.

### Critical Observations
- The primary critical vulnerability is the presence of hardcoded Razorpay secret literals in [`functions/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js) and [`.env`](file:///c:/Users/pavan/OneDrive/Documents/REEL/.env).
- The client app contains debug harness broadcast receivers in production code paths that should be restricted to debug builds.

### Components Requiring Deeper Auditing
1. **Cloudflare Worker Edge Verification:** [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js) (JWKS token verification & Firestore REST OAuth2 token exchange).
2. **Local Storage & Sync Security:** [`app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/sync/SyncManager.kt) & Room DAOs.
3. **Authentication Flows & Session Life Cycle:** [`app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt).

---

## 6. Recommended NEXT Audit Step

> **Recommended Next Step:** **Deep Audit of Backend & Cloudflare Worker Security Layer** (`worker/src/index.js`, Cloud Functions, and Firebase Security Rules), focusing on token validation correctness, secret management, replay attack resistance, and edge cryptographic implementations.

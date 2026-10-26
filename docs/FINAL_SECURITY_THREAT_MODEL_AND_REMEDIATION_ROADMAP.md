# FINAL SECURITY THREAT MODEL & MASTER REMEDIATION ROADMAP

**Target Application:** Re:Focus (Android Client & Edge/Cloud Backend Infrastructure)  
**Package Name:** `com.reeltrak.app`  
**Consolidation Scope:** All previous security audits (Steps 1–8) synthesized, independently verified, and organized into an authoritative master threat model, attack path map, and production remediation roadmap.  
**Audit Date:** September 2026  
**Status:** Read-Only Audit & Master Checklist — No code, configuration, or database rules modified.

---

## Master Vulnerability Inventory

Every finding identified across previous audits has been re-verified against the active codebase, deduplicated, and cataloged below with normalized severity ratings.

| Master Finding ID | Original Finding IDs | Normalized Severity | Category | Component & File Location | Current Verified Status |
| :--- | :--- | :---: | :--- | :--- | :---: |
| **VULN-01** | `AUTH-01`, `LOCAL-01` | **HIGH** | Account Isolation / Data Integrity | `LoginViewModel.kt:L273`<br>`SyncManager.kt:L142` | **VERIFIED ACTIVE**: `signOut()` keeps today's SQLite row via `clearHistoricalDataExcept(todayStr)`. Upon next login, `restoreFromCloud()` runs `backupToCloud()`, uploading User A's un-cleared tracking counts into User B's Firestore account. |
| **VULN-02** | `PAY-01`, `BE-FINDING-02` | **HIGH** | Payment Gateway / Entitlement Lifecycle | `worker/src/index.js`<br>Razorpay Gateway Integration | **VERIFIED ACTIVE**: No Razorpay Webhook endpoint (`POST /api/razorpay-webhook`) exists. Out-of-band refunds, chargebacks, disputes, and automatic cancellations do not revoke active Firestore entitlements (`subscriptionStatus: "ACTIVE"`). |
| **VULN-03** | `BE-01` | **MEDIUM** | Credential Security | `functions/index.js:L28, L36` | **VERIFIED ACTIVE**: `getKeySecret()` falls back to hardcoded string `"MvcuJz39QJsQXGf0cPSvfgib"`. |
| **VULN-04** | `AUTH-02`, `LOCAL-02` | **MEDIUM** | Credential Storage | `PreferencesRepositoryImpl.kt:L51, L204-L225` | **VERIFIED ACTIVE**: Firebase ID Token string is written to unencrypted Proto DataStore (`settings.preferences_pb`) under `auth_id_token`. |
| **VULN-05** | `AUTH-03` | **MEDIUM** | Lifecycle & Compliance | Project-Wide | **VERIFIED ACTIVE**: In-app account deletion mechanism is absent, conflicting with Google Play User Data policies. |
| **VULN-06** | `IPC-01` | **MEDIUM** | IPC / System Attack Surface | `ReelTrackerService.kt:L248-L264` | **VERIFIED ACTIVE**: Dynamically registered debug broadcast receiver (`DEBUG_INCREMENT`, `DEBUG_RESET`, `DEBUG_SHOW_OVERLAY`) is active in code and lacks `if (BuildConfig.DEBUG)` guard. |
| **VULN-07** | `PAY-02`, `BE-04` | **MEDIUM** | API Abuse / Rate Limiting | `worker/src/index.js:L398-L563` | **VERIFIED ACTIVE**: `/api/create-order` and `/api/verify-payment` lack per-IP/per-UID rate limits, allowing automated order creation spam. |
| **VULN-08** | `BE-03` | **MEDIUM** | Bot Protection & Attestation | Project-Wide / Firebase Console | **VERIFIED ACTIVE**: Firebase App Check (Play Integrity) is not configured. Direct REST/Firestore SDK calls can be scripted using public API keys. |
| **VULN-09** | `BUILD-01` | **MEDIUM** | Release Hardening | `app/build.gradle.kts:L35-L42` | **VERIFIED ACTIVE**: Release build block does not specify `signingConfig signingConfigs.getByName("release")` and lacks ProGuard rule optimization for log stripping. |
| **VULN-10** | `LOCAL-03` | **LOW** | At-Rest Data Encryption | `DailyReelDataDao.kt`<br>`ReelTrakDatabase.kt` | **VERIFIED ACTIVE**: Room SQLite database is unencrypted at rest (standard Android file sandbox, accessible on rooted devices). |
| **VULN-11** | `BE-02` | **LOW** | Architectural Redundancy | `worker/`, `functions/`, `server.js` | **VERIFIED ACTIVE**: Three parallel backend implementations exist, creating configuration drift risk. |
| **VULN-12** | `PAY-03` | **LOW** | Gateway Configuration | `worker/src/index.js:L413, L478` | **VERIFIED ACTIVE**: Fallback test Key ID string (`rzp_test_Tb9rSP1OzJ3Tdi`) hardcoded in Worker. |
| **VULN-13** | `BE-04b` | **LOW** | HTTP Hardening / CORS | `worker/src/index.js:L26` | **VERIFIED ACTIVE**: Wildcard `Access-Control-Allow-Origin: *` returned on all edge endpoints. |
| **VULN-14** | `AUTH-04` | **INFO** | Product Specification Gap | Project-Wide | **VERIFIED ACTIVE**: Soft-delete grace recovery window is documented in product specs but not implemented in code. |

---

## Threat Model

```
                                  ┌───────────────────────────┐
                                  │   Threat Actors & Vectors  │
                                  └─────────────┬─────────────┘
                                                │
         ┌───────────────────────┬──────────────┴──────────────┬───────────────────────┐
         ▼                       ▼                             ▼                       ▼
   [ Normal User ]      [ Shared Device User ]        [ Malicious Authenticated ]  [ Scripted Bot / Attacker ]
  • Accidental logout  • Exploits VULN-01            • Replays verification calls • Order spam (VULN-07)
  • Time tampering     • Cross-account sync          • Trial/subscription abuse   • Extracted APK endpoints
         │                       │                             │                       │
         └───────────────────────┼─────────────────────────────┼───────────────────────┘
                                 │
                                 ▼
                     ┌───────────────────────┐
                     │   Trust Boundaries    │
                     └───────────┬───────────┘
                                 │
     ┌───────────────────────────┼───────────────────────────┐
     ▼                           ▼                           ▼
[ Android Client ]      [ Cloudflare Worker ]     [ Google Firestore ]
• Plaintext DataStore    • RS256 JWKS JWT check    • isOwner(userId) rule
• Room SQLite sandbox    • HMAC-SHA256 signature   • allow write: if false; (Sub/Account)
• Accessibility BFS      • Razorpay REST status    • Default-deny wildcard
```

### Threat Actor Capabilities & Defenses:

1. **Malicious Authenticated User:**
   - *Can Attempt:* Replay `/api/verify-payment`, spoof subscription dates, forge payment amounts, or read other users' stats.
   - *Blocked By:* Edge JWT validation (`payload.sub`), constant-time HMAC signature checks, Razorpay direct amount validation (9900 paise INR), and Firestore `isOwner(userId)` rules.
   - *Remains Exploitable:* Retaining active access after a payment refund due to absent webhooks (**VULN-02**).

2. **Shared Device User:**
   - *Can Attempt:* Gain access to previous user's data on the same physical device.
   - *Blocked By:* Firebase logout terminating remote snapshot listeners.
   - *Remains Exploitable:* Cross-account tracking record upload (**VULN-01**).

3. **Scripted Bot / API Attacker:**
   - *Can Attempt:* Rapidly flood `/api/create-order` to exhaust gateway limits.
   - *Blocked By:* Requirement of a valid Firebase ID token header.
   - *Remains Exploitable:* Rate limit absence (**VULN-07**) and missing App Check (**VULN-08**).

4. **Rooted Device Attacker:**
   - *Can Attempt:* Read local SQLite files, inspect DataStore, or manipulate device system clock.
   - *Blocked By:* Server-anchored `TrustedTimeManager` for expiration checks.
   - *Remains Exploitable:* Plaintext token extraction from DataStore (**VULN-04**) and SQLite database inspection (**VULN-10**).

---

## Trust Boundary & Data Flow Map

```
Untrusted Domain: Third-Party Apps (Instagram / YouTube)
  │ (Accessibility Node Tree: text, view IDs, containers)
  ▼
[ Boundary 1: Accessibility BFS Traversal Engine ]
  │ Capped at MAX_EXTRACTION_NODES = 200 (Memory DoS Safe)
  ▼
Trusted Domain: Client Application Memory
  │
  ├─► [ Boundary 2: Local SQLite / DataStore Storage ] ──► Plaintext files in /data/data/
  │
  ├─► [ Boundary 3: Client ➔ Cloudflare Worker (REST TLS) ]
  │     • Untrusted client requests
  │     • Guard: RS256 JWT validation against Google JWKS (Derives UID)
  │
  └─► [ Boundary 4: Client ➔ Firestore SDK (TLS) ]
        • Direct client reads & writes
        • Guard: firestore.rules (isOwner, blacklisted sub keys, allow write: if false;)
```

---

## Detailed Attack Paths

### Path A: Cross-Account Contamination on Shared Device (VULN-01)
1. **Preconditions:** User A and User B share a single physical Android device.
2. **Execution:** User A tracks 200 reels today. User A signs out. `LoginViewModel.signOut()` executes `clearHistoricalDataExcept(todayStr)`, preserving today's 200-reel row in Room SQLite.
3. **Trigger:** User B logs in with their personal Google account. `SyncManager.restoreFromCloud()` runs `backupToCloud()` first, reading the local 200-reel record and uploading it to `/users/{userB_uid}/daily_data/{todayStr}`.
4. **Impact:** User B's historical metrics, streak calculations, and cloud backup are permanently corrupted with User A's data.

### Path B: Post-Refund Entitlement Persistence (VULN-02)
1. **Preconditions:** Authenticated user purchases a monthly subscription (₹99).
2. **Execution:** Subscription is verified and activated in Firestore (`subscriptionStatus: "ACTIVE"`). User requests a refund directly through Razorpay or issues a bank chargeback.
3. **Trigger:** Razorpay processes the refund. Because no server webhook (`POST /api/razorpay-webhook`) is listening, Firestore is never updated.
4. **Impact:** User retains full premium access for the entire 28-day validity period without paying.

### Path C: Token Extraction from Plaintext DataStore (VULN-04)
1. **Preconditions:** Physical access to a rooted device or ADB backup access.
2. **Execution:** Attacker pulls `/data/data/com.reeltrak.app/files/datastore/settings.preferences_pb`.
3. **Trigger:** Attacker reads `auth_id_token` string stored in plaintext and replays it against backend API endpoints.
4. **Impact:** Unauthorized API access under the victim's identity until token expiration (up to 1 hour).

---

## Production Security Gaps (Classification Matrix)

| Gap / Issue | Classification | Justification & Production Impact |
| :--- | :---: | :--- |
| **VULN-01: Cross-Account Data Contamination** | **MUST FIX** | Direct data integrity violation across user boundaries on shared devices. |
| **VULN-02: Missing Razorpay Webhook** | **MUST FIX** | Financial and entitlement leakage; unrevoked access on refunds/disputes. |
| **VULN-03: Hardcoded Fallback Secrets** | **MUST FIX** | Credential hygiene; elimination of repository test key exposure. |
| **VULN-04: Plaintext ID Token Storage** | **MUST FIX** | Credential security; redundant and unencrypted token persistence. |
| **VULN-05: In-App Account Deletion** | **MUST FIX** | Mandatory for Google Play Store compliance (User Data Policy). |
| **VULN-06: Debug Broadcast Receiver Active** | **MUST FIX** | External IPC tamper vector on pre-Android 13 devices. |
| **VULN-07: Missing Edge Rate Limiting** | **SHOULD FIX** | Gateway abuse prevention; protection against automated order spam. |
| **VULN-08: Missing Firebase App Check** | **SHOULD FIX** | Bot prevention; stops scripted API calls outside the genuine mobile app. |
| **VULN-09: Release Build Hardening & Signing** | **SHOULD FIX** | APK tampering defense and automated debug log stripping in release binaries. |
| **VULN-11: Backend Redundancy** | **SHOULD FIX** | Prevents architectural drift across parallel backend implementations. |
| **VULN-13: Permissive Wildcard CORS** | **SHOULD FIX** | Edge API hardening against browser-based proxy abuse. |
| **VULN-10: SQLCipher Database Encryption** | **OPTIONAL HARDENING**| Defense-in-depth on rooted devices (OS sandbox already protects non-rooted devices). |
| **VULN-14: Soft Deletion Grace Window** | **OPTIONAL HARDENING**| Product feature enhancement (can follow basic account deletion). |

---

## Master Remediation Roadmap (Phase-by-Phase)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ PHASE 0: Immediate Fixes (VULN-01, VULN-03, VULN-04, VULN-06)               │
│ • Full SQLite wipe on signOut() • Strip debug receiver                      │
│ • Purge hardcoded secrets       • Remove auth_id_token from DataStore       │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│ PHASE 1: Payment & Entitlement Lifecycle (VULN-02, VULN-12)                 │
│ • Deploy POST /api/razorpay-webhook with HMAC signature verification        │
│ • Handle refund.processed and dispute.created to revoke Firestore status    │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│ PHASE 2: Compliance & Account Lifecycle (VULN-05)                           │
│ • Implement in-app account deletion deleting Firestore /users/{uid} & Auth  │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│ PHASE 3: Backend & Edge Hardening (VULN-07, VULN-08, VULN-11, VULN-13)      │
│ • Add Cloudflare rate limiting • Restrict CORS • Deprecate legacy functions │
│ • Enable Firebase App Check with Google Play Integrity                      │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│ PHASE 4: Build, Release & Client Hardening (VULN-09, VULN-10)               │
│ • Configure release signing configs • Strip ProGuard debug logs              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Verification & Security Test Plan

| Category | Target Vulnerability | Test Action | Expected Result & Verified Property |
| :--- | :--- | :--- | :--- |
| **Account Switching** | `VULN-01` | User A tracks 50 reels today. Logs out. User B logs in on same device. | User B's local Room DB and Firestore subcollection contain 0 reels. No data contamination occurs. |
| **Webhook Handling** | `VULN-02` | Simulate Razorpay `refund.processed` webhook with valid HMAC signature. | Firestore `/users/{uid}/subscription/status` transitions to `CANCELLED`/`EXPIRED`. App locks premium features. |
| **Token Storage** | `VULN-04` | Inspect `settings.preferences_pb` in device sandbox after login. | No `auth_id_token` key is present. Live tokens are retrieved exclusively from `Firebase.auth.currentUser`. |
| **IPC Isolation** | `VULN-06` | External app sends `com.reeltrak.app.DEBUG_INCREMENT` on Android 12. | Intent is rejected or ignored. Receiver is not registered in release builds. |
| **Account Deletion** | `VULN-05` | Authenticated user triggers "Delete Account". | Local Room DB cleared, DataStore cleared, Firestore `/users/{uid}` deleted, and `FirebaseUser.delete()` succeeds. |
| **Rate Limiting** | `VULN-07` | Send 50 rapid `POST /api/create-order` requests from same IP within 10s. | Requests beyond rate threshold return HTTP 429 Too Many Requests. |

---

## Legal & Compliance Flags

> [!IMPORTANT]
> **LEGAL / COMPLIANCE REVIEW REQUIRED**
> 1. **Google Play Account Deletion Policy:** Google Play mandates that apps with account creation must provide an easily discoverable in-app account deletion mechanism AND a web-based deletion request URL. Currently missing (**VULN-05**).
> 2. **Indian Digital Personal Data Protection (DPDP) Act:** Applies to processing of digital personal data in India. Requires explicit consent, purpose limitation, and user right to erasure/correction.
> 3. **Google Play Accessibility Service Policy:** Apps using `AccessibilityService` must provide prominent in-app disclosure before requesting permission and must not use accessibility APIs for covert data collection or tapjacking. Current implementation is compliant (BFS traversal bounded, no sensitive text extraction).
> 4. **Razorpay Merchant Terms & Recurring Payments:** Clear disclosure of billing cycles (28 calendar days), refund policies, and customer dispute resolution must be displayed in the purchase flow UI.

---

## Final Security Status Overview

### 1. Verified Strong Controls Already Present:
- Edge RS256 JWKS JWT verification against Google certificates.
- Zero-trust Firestore security rules blocking all client writes to subscription and account control documents (`allow write: if false;`).
- Constant-time HMAC-SHA256 signature verification for payment transactions.
- Server-authoritative price validation querying Razorpay directly for captured amount (9900 paise INR).
- Memory DoS protection via BFS traversal depth caps (`MAX_EXTRACTION_NODES = 200`) in `ReelTrackerService`.
- Anti-clock-tampering engine via `TrustedTimeManager`.

### 2. Verified High-Risk Issues Requiring Priority Remediation:
- **`VULN-01`**: Cross-account data contamination during account switching on shared devices.
- **`VULN-02`**: Unrevoked subscription entitlement on refunds/chargebacks due to missing Razorpay server webhook.

---

FINAL RECOMMENDED NEXT STEP:
PHASE 0 EXECUTION — FIX CRITICAL ACCOUNT ISOLATION (VULN-01), REMOVE DATASTORE TOKEN PERSISTENCE (VULN-04), AND STRIP DEBUG RECEIVER (VULN-06)

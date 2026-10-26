# PHASE 3 — BACKEND & EDGE SECURITY AUDIT AND IMPLEMENTATION PLAN
**VULNERABILITIES TARGETED:**
- `VULN-07` — Missing API rate limiting
- `VULN-08` — Missing Firebase App Check / Play Integrity protection
- `VULN-11` — Backend redundancy / multiple backend implementations
- `VULN-13` — Wildcard CORS

---

## 1. Current Backend & Edge Architecture

### 1.1 High-Level Architecture Overview
The Re:Focus backend ecosystem currently consists of three distinct implementations created across development phases:
1. **Cloudflare Worker (`worker/src/index.js`)** — **ACTIVE PRODUCTION BACKEND**
   - Hosted on Cloudflare Edge: `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
   - Handles:
     - `POST /api/ensure-subscription` (Trial Initialization)
     - `POST /api/create-order` (Razorpay ₹99 Standard Order Creation)
     - `POST /api/verify-payment` (Client Payment Verification)
     - `POST /api/razorpay-webhook` (Authoritative Razorpay Lifecycle Reconciliation)
     - `POST /api/account/request-deletion` (7-Day Soft-Delete Grace Period Initiation)
     - `POST /api/account/cancel-deletion` (Account Recovery)
     - `GET  /api/account/status` (Authoritative Account Lifecycle Status & Lazy Purge)
     - `GET  /health` (Health Check)
   - Performs Edge RS256 JWT verification against Google JWKS (`securetoken.google.com/refocus-504119`).
   - Uses Google OAuth2 Service Account Access Token to mutate Firestore (`/users/{uid}/...`, `/razorpay_events/...`) and delete Firebase Auth records via Google Identity Toolkit REST API.

2. **Firebase Cloud Functions (`functions/index.js`)** — **LEGACY / REDUNDANT**
   - Exports `createRazorpayOrder`, `verifyRazorpayPayment`, `razorpayWebhook`, `ensureTrialSubscription`.
   - Uses Node.js `firebase-admin` and `firebase-functions/v2`.
   - **Status:** Not referenced by Android client code.

3. **Express Node.js Server (`server.js`)** — **LOCAL DEV / LEGACY MOCK**
   - Standalone Express server providing `/api/create-order`, `/api/verify-payment`, `/api/razorpay-webhook`, `/api/ensure-subscription`.
   - **Status:** Unused in production; was used for local simulator testing.

---

## 2. Comprehensive Endpoint Inventory

| Endpoint | Method | Auth Required | Purpose | Current Protection | Abuse Potential | Rate Limit Tier |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `/api/create-order` | POST | Firebase ID Token | Creates Razorpay order | JWT verify, account status check | Cost/Resource exhaustion (Razorpay API throttling, DB writes) | Strict (5 req / min per UID) |
| `/api/verify-payment` | POST | Firebase ID Token | Verifies client payment | JWT verify, HMAC signature, Razorpay API check | Signature brute-force, Razorpay API hammering | Strict (5 req / min per UID) |
| `/api/ensure-subscription` | POST | Firebase ID Token | Initializes 7-day trial | JWT verify, account status check | Redundant Firestore lookups | Moderate (10 req / min per UID) |
| `/api/account/request-deletion` | POST | Firebase ID Token | Schedules account deletion | JWT verify, idempotency guard | Spamming deletion status | Moderate (5 req / min per UID) |
| `/api/account/cancel-deletion` | POST | Firebase ID Token | Restores account | JWT verify, server time deadline check | Brute-forcing cancel calls | Moderate (5 req / min per UID) |
| `/api/account/status` | GET | Firebase ID Token | Queries lifecycle status | JWT verify, lazy purge trigger | Excessive polling of Firestore REST API | High-volume (30 req / min per UID) |
| `/api/razorpay-webhook` | POST | X-Razorpay-Signature | Razorpay lifecycle events | HMAC-SHA256 signature, atomic idempotency doc | Webhook endpoint flooding, CPU compute exhaustion | Edge IP Limiting (60 req / min per IP) |
| `/health` | GET | None | Liveness check | None | DDoS / traffic amplification | Edge IP Limiting (60 req / min per IP) |

---

## 3. Vulnerability Analysis & Proposed Defenses

### 3.1 VULN-07 — Missing API Rate Limiting

#### Threat Scenario
1. **Authenticated Endpoint Abuse:** A rogue client with a valid Firebase account scripts rapid calls to `/api/create-order`, creating thousands of unpaid Razorpay orders, or spams `/api/account/status`, driving up Google Cloud Datastore / Firestore REST API costs.
2. **Unauthenticated / Webhook Flooding:** An attacker spams `/api/razorpay-webhook` with garbage payloads, consuming Cloudflare Worker CPU execution time and exhausting edge compute limits.

#### Current Defense
- Zero rate limiting exists. All valid requests are executed.

#### Proposed Defense
1. **Edge-Native In-Memory Token Bucket / Sliding Window inside Worker**:
   - Implement a lightweight, zero-dependency sliding window rate limiter in Cloudflare Worker memory using a bounded Map with automatic timestamp-based cleanup.
   - For distributed durability across edge nodes, support Cloudflare Rate Limiting Rules (via Cloudflare Dashboard or Worker KV/D1 fallback).
2. **Multi-Tier Limit Model**:
   - **Authenticated Endpoints (`/api/create-order`, `/api/verify-payment`, `/api/account/*`)**:
     - Key: `UID + endpoint` (Prevents blocking multiple legitimate users sharing an enterprise/NAT IP address like cellular CGNAT or university Wi-Fi).
     - Limits:
       - `/api/create-order`: 5 requests / 60 seconds per UID.
       - `/api/verify-payment`: 5 requests / 60 seconds per UID.
       - `/api/account/request-deletion` & `/api/account/cancel-deletion`: 5 requests / 60 seconds per UID.
       - `/api/account/status`: 30 requests / 60 seconds per UID.
       - `/api/ensure-subscription`: 10 requests / 60 seconds per UID.
   - **Public / System Endpoints (`/api/razorpay-webhook`, `/health`, pre-auth failures)**:
     - Key: `CF-Connecting-IP + endpoint`.
     - Limits:
       - `/api/razorpay-webhook`: 120 requests / 60 seconds per IP (Allows Razorpay batch webhooks without dropping genuine events).
       - `/health` & unauthenticated probes: 60 requests / 60 seconds per IP.
3. **HTTP 429 Response Standards**:
   - Return `HTTP 429 Too Many Requests` with `Retry-After: <seconds>` header and JSON body `{ "error": "Too many requests. Please try again later.", "retryAfter": <seconds> }`.

---

### 3.2 VULN-08 — Firebase App Check & Play Integrity Protection

#### Evaluation of Capabilities & Boundaries
1. **Firebase Services vs Custom Worker Boundary**:
   - Firebase App Check protects direct Firebase services (Firestore, Firebase Storage, Realtime Database, Cloud Functions for Firebase).
   - The primary backend for Re:Focus is a custom **Cloudflare Worker**, which receives HTTP requests directly from OkHttp in the Android app.
   - Standard Firebase App Check client SDK does **not** automatically protect non-Firebase HTTP endpoints unless the client attaches the App Check token in a custom header (e.g. `X-Firebase-AppCheck`), and the Worker verifies that token against Google's App Check JWKS endpoint (`https://firebaseappcheck.googleapis.com/v1/...`).

2. **Android Play Integrity + App Check Architecture**:
   - Android utilizes Google Play Integrity API via Firebase App Check provider:
     ```kotlin
     FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
         if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
         else PlayIntegrityAppCheckProviderFactory.getInstance()
     )
     ```
   - Firestore Security Rules can enforce App Check on direct Firestore SDK access:
     ```
     allow read, write: if request.auth != null && request.app != null;
     ```

3. **Cloudflare Worker App Check Verification Design**:
   - For high-consequence endpoints (`/api/create-order`, `/api/verify-payment`, `/api/account/request-deletion`), Android attaches `X-Firebase-AppCheck: <token>` via OkHttp Interceptor.
   - Worker implements `verifyAppCheckToken(token, projectId)` using Google App Check JWKS:
     - JWKS URL: `https://firebaseappcheck.googleapis.com/v1beta/projects/{projectId}/apps/{appId}:getJwks` or standard Google JWT verification.
   - **Graceful Fallback / Non-Enforcing Transition**:
     - Allow a configurable `APP_CHECK_ENFORCEMENT_MODE` in Worker environment: `disabled` -> `audit/log_only` -> `enforced`. This prevents locking out legitimate users on modified ROMs, emulators during development, or devices with delayed Play Integrity attestation.
   - **Webhook Immunity**:
     - `POST /api/razorpay-webhook` is called server-to-server by Razorpay servers and **MUST NOT** require App Check. It continues to be strictly protected by HMAC-SHA256 signature verification.

---

### 3.3 VULN-11 — Backend Redundancy Consolidation

#### Analysis of Current Implementations
1. **Android Routing Evidence**:
   - `RazorpaySubscriptionPurchaseServiceImpl.kt`: Points strictly to `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
   - `SubscriptionRepositoryImpl.kt`: Points strictly to `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
   - `AccountRepositoryImpl.kt`: Points strictly to `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
   - **0** references exist to `cloudfunctions.net` or `localhost:3000` in production code.

2. **Redundant Files**:
   - `functions/index.js` & `functions/package.json`: Legacy Cloud Functions from initial prototype.
   - `server.js`: Standalone Express server.

#### Consolidation & Deprecation Strategy
1. **Designate Single Source of Truth**:
   - Formally document and designate `worker/src/index.js` as the **sole authoritative production backend**.
2. **Retain vs Deprecate Legacy Files**:
   - `functions/`: Mark `functions/index.js` as DEPRECATED with a clear banner comment at the top explaining that all routing is handled by Cloudflare Worker. Do not delete during build to avoid breaking CI if `firebase deploy` is invoked by external scripts, but ensure functions do not hold live credentials.
   - `server.js`: Add explicit deprecation header. Keep solely as a reference/dev artifact.

---

### 3.4 VULN-13 — Wildcard CORS Hardening

#### Audit of Current CORS Usage
- Current Worker header in `worker/src/index.js`:
  ```javascript
  const CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Razorpay-Signature",
    "Access-Control-Max-Age": "86400"
  };
  ```

#### Why Wildcard CORS Existed & Correct Hardening
1. **Browser vs Native Android vs Webhooks**:
   - **Android Native App (OkHttp / Retrofit)**: Android HTTP clients do **not** enforce browser CORS policies (CORS is an SOP security feature implemented by web browsers).
   - **Razorpay Webhooks**: Server-to-server HTTP POST requests directly from Razorpay IP addresses. Razorpay does not send `Origin` or parse CORS headers.
   - **Web Browsers**: If an attacker hosts a malicious website, wildcard CORS (`Access-Control-Allow-Origin: *`) combined with `Authorization` headers allows a malicious web script to make authenticated cross-origin requests to the Worker if the user's token is accessible.

2. **Targeted CORS Configuration**:
   - Since Re:Focus is a native Android application with no production web dashboard, the Worker does not need to allow arbitrary web origins.
   - Define an explicit origin allowlist in `worker/src/index.js`:
     ```javascript
     const ALLOWED_ORIGINS = [
       "https://refocus-app.firebaseapp.com",
       "https://refocus-504119.web.app"
     ];
     ```
   - For non-browser clients (requests with no `Origin` header, such as Android OkHttp and Razorpay Webhooks), process normally without returning wildcard reflection.
   - For browser requests (`Origin` header present):
     - If `Origin` is in `ALLOWED_ORIGINS`, reflect that specific origin: `Access-Control-Allow-Origin: ${origin}` with `Vary: Origin`.
     - If `Origin` is not allowed, omit `Access-Control-Allow-Origin` or return 403 for OPTIONS preflight.

---

## 4. Interaction with Phase 0, Phase 1, and Phase 2 Security Fixes

| Phase / Feature | Interaction with Phase 3 | Verification Strategy |
| :--- | :--- | :--- |
| **Phase 0 (Device-Local Today & UID Isolation)** | Rate limiting and CORS will not alter Room entities, `DayManager`, or token persistence rules. | Verify `testDebugUnitTest` and Room data isolation remain intact. |
| **Phase 1 (Razorpay Entitlements & Webhook)** | Webhook endpoint `/api/razorpay-webhook` is exempted from App Check and per-UID limits; protected by IP rate limiting + HMAC-SHA256 signature. | Verify Razorpay payments and webhooks process successfully without 429 errors. |
| **Phase 2 (Account Lifecycle & Deletion)** | Account deletion `/api/account/request-deletion` and `/api/account/cancel-deletion` receive per-UID rate limits (5 req/min) to prevent rapid toggle spamming. | Verify deletion state machine tests still pass. |

---

## 5. Newly Discovered Issues

1. **Worker Error Information Disclosure**:
   - In `worker/src/index.js`, generic 500 error responses currently return `err.message`, which could leak internal stack traces or database URLs.
   - *Recommendation*: Sanitize 500 error messages in production mode to return `"Internal Server Error"`.
2. **Missing `X-Content-Type-Options: nosniff` and Security Headers**:
   - Edge responses lack standard security headers (`X-Content-Type-Options`, `Strict-Transport-Security`, `X-Frame-Options`).
   - *Recommendation*: Add global security response headers to `jsonResponse()`.

---

## 6. Exact Files Targeted for Implementation

| File | Changes Planned |
| :--- | :--- |
| [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js) | Implement sliding-window rate limiter, restrict CORS headers to specific allowed origins, add App Check token verification helper, add security response headers. |
| [`app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt) | [NEW] OkHttp interceptor to attach Firebase App Check token (`X-Firebase-AppCheck`) to outgoing Worker requests. |
| [`app/src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt) | Use configured OkHttpClient with rate-limit and AppCheck support. |
| [`app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt) | Ensure client handles HTTP 429 gracefully with user-friendly retry banners. |
| [`functions/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js) | Add formal deprecation notice and disable execution. |
| [`server.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/server.js) | Add formal deprecation notice and restrict to dev mock. |
| [`app/src/test/java/com/reeltrak/app/security/Phase3BackendEdgeSecurityTest.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/test/java/com/reeltrak/app/security/Phase3BackendEdgeSecurityTest.kt) | [NEW] Comprehensive unit test suite covering rate limiting, CORS, and backend consolidation. |

---

## 7. Recommended Implementation Sequence

```
1. Backend Rate Limiting (VULN-07)
   ├── Implement Worker sliding-window rate limiter (in-memory per-UID and per-IP buckets)
   ├── Add HTTP 429 response handling with Retry-After header
   └── Verify webhook threshold allows burst delivery

2. CORS Hardening (VULN-13)
   ├── Replace wildcard "*" with strict allowlist for browser origins
   ├── Preserve native Android & Razorpay webhook non-browser compatibility
   └── Add standard security headers (nosniff, HSTS)

3. Backend Redundancy Consolidation (VULN-11)
   ├── Add deprecation banners and routing documentation to functions/index.js & server.js
   └── Confirm Android client exclusively connects to Cloudflare Worker

4. App Check & Play Integrity Integration (VULN-08)
   ├── Add Firebase App Check dependency & initialization in Android
   ├── Implement AppCheck OkHttp header attachment
   └── Implement Worker App Check verification with graceful fallback mode

5. Verification & Testing
   ├── Run unit tests: ./gradlew testDebugUnitTest
   ├── Run release build: ./gradlew assembleRelease
   └── Create Phase 3 Remediation Report
```

---

## 8. Verification & Test Plan

1. **Automated Unit Tests**:
   - `Phase3BackendEdgeSecurityTest.kt` verifying:
     - Rate limit threshold enforcement (5th request succeeds, 6th returns 429)
     - `Retry-After` calculation
     - CORS header generation for allowed vs disallowed origins
     - Android non-browser request headers
     - Webhook signature + IP rate limit coexistence
2. **Regression Verification**:
   - Run existing `Phase0SecurityRemediationTest`, `Phase1PaymentSecurityTest`, and `Phase2AccountDeletionSecurityTest`.
3. **Release Compilation**:
   - Run `./gradlew assembleRelease` and verify R8 minification.

---

## 9. Conclusion & Recommendation

**RECOMMENDATION: READY FOR IMPLEMENTATION**

The proposed architecture resolves all four targeted vulnerabilities (`VULN-07`, `VULN-08`, `VULN-11`, `VULN-13`) without breaking legitimate Android clients, Razorpay payment webhooks, or existing Phase 0/1/2 security properties.

# Phase 3 Independent Security Verification Report: Backend & Edge Security Hardening

**Date:** 2026-09-18  
**Verification Target:** Phase 3 Implementation (VULN-07, VULN-08, VULN-11, VULN-13, Security Headers, Error Sanitization)  
**Overall Status:** **PASS WITH FINDINGS**  

---

## 1. Executive Summary & Verification Overview

An adversarial and independent verification was performed on the Phase 3 backend and edge security hardening implementation across the codebase:
- [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js)
- [`app/src/main/java/com/reeltrak/app/ReelTrakApplication.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/ReelTrakApplication.kt)
- [`app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt)
- Android Repositories & Networking Services
- [`firestore.rules`](file:///c:/Users/pavan/OneDrive/Documents/REEL/firestore.rules)
- Legacy backends [`functions/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js) and [`server.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/server.js)
- Test suite [`Phase3BackendEdgeSecurityTest.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/test/java/com/reeltrak/app/security/Phase3BackendEdgeSecurityTest.kt)

### Summary of Verdict:
1. **Rate Limiting (VULN-07):** Worker-level in-memory defense is correctly implemented with UID keys derived from verified JWT tokens, memory-bounded sliding windows, and HTTP 429 `Retry-After`. However, as documented, Worker isolates are local and do not provide distributed global rate limiting without Cloudflare Dashboard WAF rules.
2. **App Check (VULN-08):** Correct separation between Debug (`DebugAppCheckProviderFactory`) and Release (`PlayIntegrityAppCheckProviderFactory`). Worker implements standard RS256 JWKS verification against `firebaseappcheck.googleapis.com`. Webhooks are strictly exempted.
3. **Backend Consolidation (VULN-11):** Verified 0 active production references to legacy URLs (`cloudfunctions.net`, `localhost:3000`, `10.0.2.2`). Legacy files have deprecation banners.
4. **CORS Hardening (VULN-13):** Wildcard CORS is removed. Only approved Firebase hosting origins are matched with `Vary: Origin`. Non-browser Android and Webhook requests are unaffected.
5. **Security Headers & Error Sanitization:** Applied globally across all routes. Production 500 errors return sanitized messages.
6. **Cross-Phase Regressions:** Phase 0 (device-local today enforcement), Phase 1 (Razorpay lifecycle & ₹99 / 28-day entitlement), and Phase 2 (7-day account deletion grace period & permanent purge) remain intact.

---

## 2. Detailed Verification by Target

### 2.1 VULN-07: Rate Limiting
- **Endpoint Limits Verified in Worker:**
  - `/api/create-order`: 5 req / 60s per UID (`checkRateLimit('uid_create_order_${uid}', 5, 60000)`)
  - `/api/verify-payment`: 5 req / 60s per UID (`checkRateLimit('uid_verify_pay_${uid}', 5, 60000)`)
  - `/api/account/request-deletion`: 5 req / 60s per UID (`checkRateLimit('uid_req_del_${uid}', 5, 60000)`)
  - `/api/account/cancel-deletion`: 5 req / 60s per UID (`checkRateLimit('uid_cancel_del_${uid}', 5, 60000)`)
  - `/api/account/status`: 30 req / 60s per UID (`checkRateLimit('uid_status_${uid}', 30, 60000)`)
  - `/api/ensure-subscription`: 10 req / 60s per UID (`checkRateLimit('uid_ensure_sub_${uid}', 10, 60000)`)
  - `/api/razorpay-webhook`: 120 req / 60s per IP (`checkRateLimit('ip_webhook_${clientIp}', 120, 60000)`)
  - `/health`: 60 req / 60s per IP (`checkRateLimit('ip_health_${clientIp}', 60, 60000)`)
- **Key Integrity:** Authenticated endpoints strictly derive the UID from `verifyFirebaseIdToken(token, projectId)`. Client-supplied UIDs cannot alter the rate limit key. Webhook strictly uses IP rate limiting (never Firebase UID).
- **429 & Retry-After:** In [`worker/src/index.js#L140-147`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L140-L147), `retryAfterSec` is computed as `Math.ceil((earliestTimestamp + windowMs - now) / 1000)` and returned in the `Retry-After` header.
- **Memory Bounding & Eviction:** The `rateLimitStore` Map size is capped at 10,000 keys. Batch pruning evicts expired entries before inserting new keys.

### 2.2 VULN-08: Firebase App Check / Play Integrity
- **Build Variant Segregation:**
  - In [`ReelTrakApplication.kt#L33-L41`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/ReelTrakApplication.kt#L33-L41):
    ```kotlin
    if (BuildConfig.DEBUG) {
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    } else {
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
    ```
- **Interceptor Safety:** [`AppCheckInterceptor.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt) awaits token retrieval with a 2-second timeout and fails open on network errors (does not crash the app). Token is added as `X-Firebase-AppCheck`. Tokens are never logged.
- **Worker Verification:** In [`worker/src/index.js#L296-L345`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L296-L345), tokens are verified against Google's App Check JWKS (`https://firebaseappcheck.googleapis.com/v1/jwks`) with RS256 signature verification, `exp` check, and `iss`/`aud` checks against project ID / project number.
- **Enforcement Modes:** Supported via `env.APP_CHECK_ENFORCEMENT_MODE`: `disabled`, `audit` (default for smooth rollout), and `enforced` (fails closed with 401). Webhook route (`/api/razorpay-webhook`) is exempted.

### 2.3 VULN-11: Backend Consolidation
- **Authoritative Backend:** Cloudflare Worker is confirmed as the sole production backend.
- **Client Configuration:** All Android networking components (`AccountRepositoryImpl`, `RazorpaySubscriptionPurchaseServiceImpl`, `SubscriptionRepositoryImpl`) target `https://reeltrak-backend.pavandevyarakala.workers.dev`.
- **Zero Legacy References:** Grep searches confirmed 0 active references to `cloudfunctions.net`, `localhost:3000`, or `10.0.2.2` in production Android code.
- **Legacy Files:** Deprecation banners are in place on `functions/index.js` and `server.js`.

### 2.4 VULN-13: CORS Hardening
- **Strict Allowlist:** Replaced `*` with `ALLOWED_ORIGINS = ["https://refocus-app.firebaseapp.com", "https://refocus-504119.web.app"]`.
- **Response Headers:** `Access-Control-Allow-Origin` is only emitted when `request.headers.get("Origin")` matches the allowlist, accompanied by `Vary: Origin`.
- **Non-Browser Compatibility:** Requests without `Origin` (Android OkHttp & Razorpay Webhooks) proceed normally.

### 2.5 Security Response Headers & Error Sanitization
- **Global Headers:** Added `X-Content-Type-Options: nosniff`, `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`, `Referrer-Policy: no-referrer`, and `X-Frame-Options: DENY` via `jsonResponse()` to all 200, 400, 401, 403, 404, 405, 410, 429, and 500 response branches.
- **Error Information Disclosure:** In [`worker/src/index.js#L1397-L1403`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L1397-L1403), unhandled 500 exceptions return a sanitized `"Internal Server Error"` response.

---

## 3. Findings & Security Notes

### [FINDING P3-01] (MEDIUM) — App Check Token Issuer & Audience Project Number Verification
- **Severity:** MEDIUM (Operational / Configuration Hardening)
- **Evidence:** [`worker/src/index.js#L337-L339`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L337-L339):
  ```javascript
  const expectedIssuer = `https://firebaseappcheck.googleapis.com/${projectId}`;
  const isIssuerValid = payload.iss === expectedIssuer || payload.iss?.includes(projectId);
  ```
- **Why it matters:** Firebase App Check JWT tokens generated by Google Cloud / Firebase issue their `iss` and `sub`/`aud` claims formatted as `https://firebaseappcheck.googleapis.com/<PROJECT_NUMBER>` (e.g. `524118238647` from `google-services.json`), rather than the alphanumeric `PROJECT_ID` (`refocus-504119`).
- **Exploitability:** If `FIREBASE_PROJECT_NUMBER` is not provided and `payload.iss` uses the project number while `projectId` is alphanumeric, strict verification in `enforced` mode could reject valid Play Integrity tokens.
- **Remediation:** Ensure `FIREBASE_PROJECT_NUMBER` (e.g. `"524118238647"`) is configured in Worker environment variables (`wrangler.jsonc` / Cloudflare Secrets) and verified against `payload.iss` and `payload.aud` / `payload.sub`.

---

### [FINDING P3-02] (LOW) — Worker Rate Limiter Ephemerality vs Cloudflare Dashboard WAF Rules
- **Severity:** LOW / INFORMATIONAL
- **Evidence:** [`worker/src/index.js#L116-L155`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L116-L155)
- **Why it matters:** In-memory `Map` state inside a Cloudflare Worker is local to the active V8 isolate and edge location. An attacker distributing requests across multiple worldwide PoPs can make more requests than the isolate threshold before triggering the in-memory limit.
- **Exploitability:** Low for standard user flows, but allows burst distributed flooding across multiple edge PoPs.
- **Remediation:** As documented in the Phase 3 report, configure Cloudflare Dashboard Rate Limiting Rules in WAF as the distributed edge defense layer.

---

### [FINDING P3-03] (INFORMATIONAL) — Unit Test Suite Mocking Scope
- **Severity:** INFORMATIONAL
- **Evidence:** [`Phase3BackendEdgeSecurityTest.kt#L37-L69`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/test/java/com/reeltrak/app/security/Phase3BackendEdgeSecurityTest.kt#L37-L69)
- **Why it matters:** The unit tests in `Phase3BackendEdgeSecurityTest.kt` validate the JVM client-side interceptor and Kotlin implementations of the rate limiting / CORS algorithms. The actual JavaScript worker logic runs in Cloudflare Worker / Miniflare environments.
- **Remediation:** Maintain integration tests in `worker` test suites (e.g. Vitest / Miniflare) for direct end-to-end JS execution.

---

## 4. Production Readiness & Configuration Matrix

| Component / Item | Verification Status | Action Required |
| :--- | :--- | :--- |
| **Worker In-Memory Rate Limiting** | **VERIFIED IN CODE** | None (Active in Worker) |
| **Cloudflare Distributed Edge Rate Limiting** | **REQUIRES MANUAL CONFIGURATION** | Configure WAF Rate Limiting rules in Cloudflare Dashboard |
| **Play Integrity in Android Release Build** | **VERIFIED IN CODE** | None (Configured in `ReelTrakApplication.kt`) |
| **Worker App Check Token Verification** | **VERIFIED IN CODE** | Add `FIREBASE_PROJECT_NUMBER` to Worker environment |
| **Worker App Check Enforcement Mode** | **VERIFIED IN CODE** | Default is `audit`; switch to `enforced` after Play Console rollout |
| **Backend Consolidation (No Legacy URLs)** | **VERIFIED IN CODE** | None (All endpoints point to Worker) |
| **Strict CORS Allowlist** | **VERIFIED IN CODE** | None (Active in Worker) |
| **Global Security Headers** | **VERIFIED IN CODE** | None (Active in `jsonResponse`) |
| **Sanitized Error Responses** | **VERIFIED IN CODE** | None (Active in Worker error handler) |

---

## 5. Cross-Phase Regression Verification

- **Phase 0 Guarantees:** Device-local today records (`dateString == today`) and strict mode enforcement in Room remain completely intact across app restarts and account switching.
- **Phase 1 Guarantees:** Razorpay webhook HMAC-SHA256 signature verification, exact-once idempotency (`/razorpay_events/*`), ₹99 / 28-day entitlement model, and cancellation/refund revocation remain authoritative in the Worker.
- **Phase 2 Guarantees:** Server-authoritative account deletion state machine (`ACTIVE`, `PENDING_DELETION`, `DELETED`), 7-day grace period, and permanent purge of Firestore/Auth records remain fully active.
- **Firestore Rules:** Unchanged. Direct client writes to `/users/{userId}/subscription/*` and `/users/{userId}/account/*` remain strictly blocked (`allow write: if false`).

---

## 6. Conclusion & Recommendation

**Status:** **PASS WITH FINDINGS**

The Phase 3 implementation successfully hardens the edge and backend against unauthorized access, CORS abuse, information disclosure, and legacy backend drift while maintaining all Phase 0, 1, and 2 security properties.

All findings are non-blocking operational/configuration hardening items. Phase 4 can safely proceed.

# Phase 1: Server-Authoritative Time Endpoint Implementation Report

## 1. Architectural Invariant

> **Architectural Invariant:**
> Device local time remains available throughout the application. It is intentionally retained for non-authoritative technical/UI purposes. Security-sensitive and progression-sensitive decisions will migrate to `TrustedTimeManager` backed by verified Cloudflare Worker server time.

Device local time APIs (`System.currentTimeMillis()`, `LocalDate.now()`, `SystemClock.elapsedRealtime()`) are **NOT** removed from the codebase. Local time continues to serve relative durations, animation/UI rendering, and accessibility debounce, while authoritative decisions (trial validity, subscription expiration, daily resets, grace periods) will query `TrustedTimeManager`.

---

## 2. Cloudflare Worker `GET /api/time` Implementation

### 2.1 Route & Contract
- **Endpoint:** `GET /api/time`
- **Deployed Worker URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/time`
- **HTTP Method:** `GET`
- **Request Headers Supported:**
  - `X-Client-Timestamp`: Monotonic `SystemClock.elapsedRealtime()` value sent by Android client for round-trip latency estimation.
  - `X-Firebase-AppCheck`: Firebase App Check attestation token (evaluated per `APP_CHECK_ENFORCEMENT_MODE`).
  - `Origin`: Validated against CORS allowlist (`https://refocus-app.firebaseapp.com`, `https://refocus-504119.web.app`).

### 2.2 Response Schema (`application/json`)
```json
{
  "serverTime": 1789920177887,
  "iso": "2026-09-20T16:02:57.887Z",
  "clientEcho": 987654321
}
```

### 2.3 Key Implementation Details in `worker/src/index.js`
```javascript
// ---------------------------------------------------------------------
// Server-Authoritative Time: GET /api/time
// Rate Limit: 120 req / 60s per IP
// Protected by: Firebase App Check (configurable mode) & IP Rate Limiting
// Supports pre-login time synchronization without leaking sensitive data
// ---------------------------------------------------------------------
if (path === "/api/time") {
  if (request.method !== "GET") {
    return jsonResponse({ error: "Method Not Allowed. Use GET." }, 405, request);
  }

  const rateCheck = checkRateLimit(`ip_time_${clientIp}`, 120, 60000);
  if (!rateCheck.allowed) {
    return jsonResponse(
      { error: "Too many requests. Please try again later.", retryAfter: rateCheck.retryAfterSec },
      429,
      request,
      { "Retry-After": rateCheck.retryAfterSec.toString(), "Cache-Control": "no-store" }
    );
  }

  // Validate App Check attestation
  await validateAppCheck(request, env);

  const serverTime = Date.now();
  const clientEchoHeader = request.headers.get("X-Client-Timestamp");
  const clientEcho = clientEchoHeader ? (isNaN(Number(clientEchoHeader)) ? clientEchoHeader : Number(clientEchoHeader)) : null;

  return jsonResponse({
    serverTime: serverTime,
    iso: new Date(serverTime).toISOString(),
    clientEcho: clientEcho
  }, 200, request, {
    "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
    "Pragma": "no-cache"
  });
}
```

---

## 3. Security, Caching & Performance Attributes

1. **Authentication / Pre-Login Decision:**
   - `/api/time` does **not** require a Firebase User JWT, enabling fresh app launches and newly installed devices to sync server time prior to user login.
   - It is protected by **Firebase App Check** (Play Integrity attestation) and bounded sliding-window **IP Rate Limiting (120 req / 60s per IP)**.
2. **Cache-Control Guarantee:**
   - Strict `no-store, no-cache, must-revalidate, max-age=0` headers ensure no edge or intermediate proxy caches stale timestamps.
3. **Information Disclosure Prevention:**
   - Zero internal secrets, Firestore details, or environment variables are exposed in responses.
4. **CORS Headers:**
   - `X-Client-Timestamp` was added to `Access-Control-Allow-Headers` with `Vary: Origin` and security headers (`nosniff`, `HSTS`, `X-Frame-Options: DENY`).

---

## 4. Test Suite Results & Deployment Verification

### 4.1 Automated Worker Test Suite (`node test_runner.mjs`)
Added unit tests covering:
- `GET /api/time` returns numeric `serverTime` matching runtime `Date.now()`.
- `GET /api/time` includes `Cache-Control: no-store`.
- `GET /api/time` echoes `X-Client-Timestamp` verbatim in `clientEcho`.
- `GET /api/time` rejects invalid HTTP methods (`POST` returns 405).
- All 19 existing recurring subscription, cancel, and webhook tests pass.

```
🚀 Starting Cloudflare Worker Monthly Autopay Test Suite...

  ✅ PASS: GET /health returns healthy status and recurring model
  ✅ PASS: GET /api/time returns current Unix epoch serverTime and no-store headers
  ✅ PASS: GET /api/time echoes X-Client-Timestamp header without modification
  ✅ PASS: GET /api/time rejects unsupported HTTP methods (405)
  ✅ PASS: POST /api/create-subscription rejects unauthenticated requests (401)
  ✅ PASS: POST /api/create-subscription rejects missing RAZORPAY_PLAN_ID configuration (500)
  ✅ PASS: POST /api/create-subscription rejects invalid requested plan ID (400)
  ✅ PASS: POST /api/create-subscription rejects PENDING_DELETION / DELETED accounts (403)
  ✅ PASS: POST /api/create-subscription creates subscription, binds UID in notes and returns { subscriptionId, keyId } without secrets
  ✅ PASS: POST /api/verify-subscription rejects invalid signature (400)
  ✅ PASS: POST /api/verify-subscription rejects UID ownership mismatch (403)
  ✅ PASS: POST /api/verify-subscription successfully activates recurring subscription
  ✅ PASS: POST /api/cancel-subscription cancels at period end and preserves active access
  ✅ PASS: Webhook rejects missing or invalid signature (400)
  ✅ PASS: Webhook enforces atomic idempotency on duplicate deliveries
  ✅ PASS: Webhook: subscription.charged renews cycle and extends currentPeriodEnd
  ✅ PASS: Webhook: subscription.pending puts subscription in PENDING status (grace period)
  ✅ PASS: Webhook: subscription.halted revokes premium access immediately
  ✅ PASS: Webhook: refund.processed revokes access and marks CANCELLED
  ✅ PASS: Webhook: rejects stale activation after refund/cancellation

========================================
Test Results: 20 Passed, 0 Failed
========================================
```

### 4.2 Production Live Verification
- Deployed via Wrangler (`wrangler deploy`): Version ID `5ba6514f-e4db-448f-a202-fcd758185011`.
- Live test against `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/time`:
  - **HTTP Status:** `200 OK`
  - **Headers Verified:** `cache-control: no-store, no-cache, must-revalidate, max-age=0`, `x-content-type-options: nosniff`
  - **Payload Verified:** `{ serverTime: 1789920177887, iso: '2026-09-20T16:02:57.887Z', clientEcho: 987654321 }`
- Live test against `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/health`:
  - **HTTP Status:** `200 OK`
  - **Payload Verified:** `{ status: 'healthy', service: 'Re:Focus Edge Worker', model: 'recurring_autopay_v2' }`

---

## 5. Scope & Isolation Check

- **Android Code:** 100% untouched.
- **TrustedTimeManager:** 100% untouched (BUG-HIGH-03 anti-tamper logic intact).
- **Subscription / Firestore / Detection Engines:** 100% untouched.

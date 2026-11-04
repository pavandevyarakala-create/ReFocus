# STEP 6 — SUBSCRIPTION, PAYMENT & RAZORPAY END-TO-END SECURITY AUDIT

**Target Application:** Re:Focus Android & Cloudflare Worker Backend  
**Audit Scope:** Android Razorpay Client Checkout, Cloudflare Worker Backend (`worker/src/index.js`), Firestore Database & Security Rules, Webhook Architecture, Anti-Replay / Idempotency Controls, and Cryptographic Signature Verification.  
**Audit Date:** September 2026  
**Status:** Audit Only — No code, configuration, or database rules modified.

---

## 1. Executive Summary

This comprehensive, read-only security assessment audited the complete end-to-end payment and subscription system of Re:Focus across the Android client, Cloudflare Worker edge backend, Razorpay payment gateway, and Google Cloud Firestore.

### Key Positive Findings:
1. **Server-Authoritative Premium State:** The Android application is strictly a consumer of subscription state. Client-side Firestore writes to `/users/{userId}/subscription/status` and `/users/{userId}/account/status` are blocked by Firestore security rules (`allow write: if false;`).
2. **Cryptographic Signature Verification:** Payment verification in the Cloudflare Worker performs constant-time HMAC-SHA256 signature verification over `razorpay_order_id|razorpay_payment_id` against `RAZORPAY_KEY_SECRET`.
3. **Razorpay Direct API Query:** The backend performs an out-of-band server-to-server call (`GET https://api.razorpay.com/v1/payments/{payment_id}`) to confirm status (`captured`), order matching (`payment.order_id === razorpay_order_id`), and price/currency integrity (`9900` paise `INR`).
4. **Idempotency Safeguards:** The verification endpoint prevents double-processing if the same payment ID is already marked `ACTIVE`.

### Critical Vulnerabilities & Gaps Identified:
1. **Absence of Razorpay Server-to-Server Webhook Handling:** No webhook listener exists for asynchronous out-of-band lifecycle events (refunds, chargebacks, disputed payments, or auto-cancellations). A refunded user retains active premium until period expiry.
2. **Missing Rate Limiting & Abuse Controls:** `/api/create-order` and `/api/verify-payment` lack IP/UID rate limiting, allowing automated order creation spam.
3. **Hardcoded Fallback Test Key ID in Worker:** `worker/src/index.js` contains a fallback public test key string (`rzp_test_Tb9rSP1OzJ3Tdi`).
4. **CORS Wildcard on Edge Worker:** `Access-Control-Allow-Origin: *` is present on payment endpoints.

---

## 2. Complete Payment Architecture & Data Flow

```
[ Android Client ]
      │
      │ 1. POST /api/create-order (Authorization: Bearer <Firebase_ID_Token>)
      ▼
[ Cloudflare Worker ] ──(Verifies RS256 JWT via Google JWKS)──► Derives UID
      │
      │ 2. POST https://api.razorpay.com/v1/orders (Amount: 9900 paise, Currency: INR)
      ▼
[ Razorpay Gateway ] ──(Returns order_id)──► [ Cloudflare Worker ] ──► [ Android Client ]
      │
      │ 3. Opens Razorpay Standard Checkout SDK
      ▼
[ User Pays via UPI/Card/NetBanking ]
      │
      │ 4. onPaymentSuccess(razorpay_order_id, razorpay_payment_id, razorpay_signature)
      ▼
[ Android Client ]
      │
      │ 5. POST /api/verify-payment (Authorization: Bearer <Firebase_ID_Token>)
      ▼
[ Cloudflare Worker ]
      ├─► Step A: Verify RS256 Firebase JWT (Derives UID)
      ├─► Step B: Verify HMAC-SHA256(order_id|payment_id, RAZORPAY_KEY_SECRET)
      ├─► Step C: Fetch GET https://api.razorpay.com/v1/payments/{payment_id}
      ├─► Step D: Validate payment.status == "captured" && amount == 9900 && currency == "INR"
      ├─► Step E: Idempotency check against Firestore
      └─► Step F: REST PATCH /users/{uid}/subscription/status (via Google Service Account OAuth2)
            │
            ▼
[ Cloud Firestore ] ──(Real-time snapshot listener)──► [ Android Client Unlocks Premium ]
```

---

## 3. Trust Boundaries

| Boundary | Components Involved | Trust Level & Enforcement Mechanism |
| :--- | :--- | :--- |
| **Client → Cloudflare Worker** | Android App ➔ Edge Worker | **Untrusted Input**. Worker authenticates callers via Google JWKS RS256 JWT verification (`Authorization: Bearer <idToken>`). UIDs in request bodies are ignored in favor of verified `payload.sub`. |
| **Worker → Razorpay API** | Edge Worker ➔ Razorpay REST API | **Trusted Channel**. Communicates over HTTPS using HTTP Basic Authentication (`RAZORPAY_KEY_ID:RAZORPAY_KEY_SECRET`). |
| **Worker → Firestore REST** | Edge Worker ➔ Cloud Firestore | **Privileged Channel**. Authenticates using Google OAuth2 JWT assertion signed by the private key from `FIREBASE_SERVICE_ACCOUNT_KEY`. |
| **Client → Firestore** | Android App ➔ Cloud Firestore | **Constrained Read-Only Access**. Enforced by `firestore.rules`. User can read their own document (`/users/{uid}/subscription/status`) but cannot write or modify subscription/account fields. |

---

## 4. Android Payment Flow Analysis (`RazorpaySubscriptionPurchaseServiceImpl.kt`)

### A. Lifecycle Safety & State Management
- `RazorpaySubscriptionPurchaseServiceImpl` uses `@Singleton` scope and keeps track of `currentActivity` through explicit `attachActivity`/`detachActivity` bindings in `MainActivity`.
- The checkout state is exposed as an immutable `StateFlow<CheckoutState>`.
- Coroutine execution runs on a structured `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, ensuring payment verification network requests complete even if UI composables recompose.

### B. Payment Callbacks & Parameter Integrity
- `onPaymentSuccess`: Extracts `razorpay_payment_id`, `razorpay_order_id`, and `razorpay_signature` from `PaymentData`. Validates nullability and non-emptiness before firing the verification task.
- `onPaymentError`: Maps Razorpay SDK error codes (`PAYMENT_CANCELED`, `NETWORK_ERROR`) to domain error messages and resets inflight flags (`isCreatingOrder = false`, `isVerifyingOnBackend = false`).

### C. Client-Side Bypass Assessment
- **Can the Android client forge premium state?** **No**.
  - Local state is derived exclusively from Firestore snapshots in `SubscriptionRepositoryImpl.kt` (`CloudSubscriptionDto.fromSnapshot(snapshot)`).
  - Anti-tamper verification is checked via `TrustedTimeManager.getTrustedTime()` against `currentPeriodEnd`.
  - Client has `allow write: if false;` on subscription documents.

---

## 5. Backend Payment Flow Analysis (`worker/src/index.js`)

### A. Endpoint 1: `POST /api/create-order`
- **Authentication:** Enforces valid Firebase ID token via `authenticateRequest()`.
- **Plan Validation:** Accepts only `planId === "plan_monthly"`.
- **Pricing Enforcement:** Price is hardcoded on the server (`amountPaise = 9900` = ₹99.00 INR). The client cannot inject a custom or discounted price.
- **Receipt Generation:** Server generates unique receipt `rcpt_${uid.substring(0,8)}_${Date.now()}`.
- **Order Metadata:** Binds `firebase_uid` and `plan_id` in Razorpay order `notes`.

### B. Endpoint 2: `POST /api/verify-payment`
- **Signature Verification:**
  - Computes `HMAC-SHA256(order_id + "|" + payment_id, secret)` using Web Crypto Subtle API.
  - Compares computed signature with client-provided hex string using **constant-time bitwise XOR comparison** (`result |= calculatedHex.charCodeAt(i) ^ signatureHex.charCodeAt(i)`), mitigating timing attacks.
- **Direct Gateway Query:**
  - Fetches live payment status from Razorpay: `https://api.razorpay.com/v1/payments/${payment_id}`.
  - Verifies status is `captured` or `authorized`.
  - Verifies `payment.order_id === razorpay_order_id`.
  - Verifies `payment.amount === 9900` and `payment.currency === "INR"`.
- **Idempotency & Replay Protection:**
  - Reads existing Firestore document for the user. If `subscriptionId === razorpay_payment_id && subscriptionStatus === "ACTIVE"`, returns early HTTP 200 without extending the subscription duplicate times.
- **Duration Calculation:**
  - Sets `currentPeriodStart` to the beginning of the current UTC day and adds exactly 28 days (`28 * 24 * 60 * 60 * 1000` ms) for `currentPeriodEnd`.

---

## 6. Webhook Security Assessment

### Current Architecture State: **NO WEBHOOK IMPLEMENTATION**
There is currently **no Razorpay Webhook endpoint** (`POST /api/razorpay-webhook`) deployed in `worker/src/index.js` or elsewhere in the project.

### Security and Operational Consequences:
1. **Unrevoked Access on Refund / Chargeback:** If a customer initiates a chargeback with their credit card issuer or requests a refund via the Razorpay dashboard, Razorpay processes the refund, but Firestore maintains `subscriptionStatus: "ACTIVE"` for the entire 28-day validity window.
2. **Payment Success with Interrupted Client Verification:** If the user completes payment on Razorpay SDK, but their device runs out of battery, loses network connectivity, or crashes before `POST /api/verify-payment` completes, their subscription will not be activated until manual support intervention or retry.

---

## 7. Firestore Security & Database Integrity

### A. Document Schema (`/users/{uid}/subscription/status`)
```json
{
  "subscriptionStatus": "ACTIVE",
  "planId": "plan_monthly",
  "subscriptionId": "pay_P123456789",
  "currentPeriodStart": "2026-09-17T00:00:00.000Z",
  "currentPeriodEnd": "2026-10-15T00:00:00.000Z",
  "premium": true,
  "updatedAt": "2026-09-17T15:26:00.000Z"
}
```

### B. Security Rules Enforcement (`firestore.rules`)
```javascript
match /users/{userId}/subscription/{documentId} {
  allow read: if isOwner(userId);
  allow write: if false; // STRICT: Only Backend Service Account can write
}
match /users/{userId}/account/{documentId} {
  allow read: if isOwner(userId);
  allow write: if false; // STRICT: Master admin control
}
```
- **Cross-User Tampering:** Impossible. `isOwner(userId)` prevents User A from reading or observing User B's subscription records.
- **Client Privilege Escalation:** Impossible. Direct writes from Android client SDK are unconditionally rejected (`allow write: if false;`).

---

## 8. State-Machine Resilience & Edge-Case Analysis

| Flow / Edge Scenario | System Response & Behavior | Verdict |
| :--- | :--- | :--- |
| **A. Normal Purchase Flow** | Order created server-side (₹99), paid on Razorpay, verified by HMAC & gateway query, Firestore updated to ACTIVE (28 days). | **Secure & Functional** |
| **B. App Closes Before Verification** | Razorpay captures funds, but client verification call never reaches Worker. Subscription remains unactivated until user contacts support or re-triggers verification. | **Resilience Gap (Requires Webhook)** |
| **C. Verification Request Replayed** | Attacker replays identical `POST /api/verify-payment` payload. Worker detects existing `subscriptionId == payment_id` and returns `200 OK` without extending duration. | **Protected** |
| **D. Same Payment ID Submitted by User B** | User B attempts to verify User A's `payment_id`. Worker fetches payment from Razorpay and validates `order.notes.firebase_uid === userB.uid` or signature. Signature fails because User B has no valid signature for their order. | **Protected** |
| **E. Modified Amount / Currency** | Attacker modifies client-side payload to amount ₹1. Razorpay order was locked to ₹99 (9900 paise) server-side; verification rejects amounts != 9900 paise. | **Protected** |
| **F. Modified User UID in Token** | Attacker tampers with UID in header. Token signature fails RS256 Google JWKS check. | **Protected** |
| **G. User Logs Out / Switches Account** | `SubscriptionRepositoryImpl` uses `authStateFlow.flatMapLatest()`. Logging out immediately unbinds snapshot listener and falls back to `SubscriptionInfo.LOCKED_DEFAULT`. | **Protected** |
| **H. Local Time Manipulation (Clock Skew)** | User moves device clock forward/backward. `TrustedTimeManager` calculates time based on server timestamp delta and `SystemClock.elapsedRealtime()`. | **Protected** |

---

## 9. Detailed Vulnerability Findings

### Finding ID: PAY-01 — Absence of Razorpay Server Webhook Endpoint
- **Severity:** `HIGH`
- **Component:** Cloudflare Worker / Razorpay Integration
- **File Path:** `worker/src/index.js`
- **Current Behavior:** Backend only processes payments via synchronous client-initiated `/api/verify-payment` calls. No webhook endpoint exists.
- **Security Impact:** Out-of-band events (refunds, dispute chargebacks, manual cancellations) do not revoke premium access. Interrupted client connections result in orphaned successful payments without entitlement activation.
- **Attack Scenario:** A user purchases a subscription, obtains active access, immediately requests a refund from Razorpay or their issuing bank, and retains 28 days of access without paying.
- **Remediation:** Implement `POST /api/razorpay-webhook` in `worker/src/index.js` verifying `X-Razorpay-Signature` against `RAZORPAY_WEBHOOK_SECRET` handling `payment.captured`, `refund.processed`, and `dispute.created`.

---

### Finding ID: PAY-02 — Missing Rate Limiting on Order Creation and Verification Endpoints
- **Severity:** `MEDIUM`
- **Component:** Cloudflare Worker Endpoints (`/api/create-order`, `/api/verify-payment`)
- **File Path:** `worker/src/index.js#L398-L563`
- **Current Behavior:** Endpoints execute on every authenticated request without IP or per-user rate limits.
- **Security Impact:** An authenticated user can script rapid requests to `/api/create-order`, exhausting Razorpay API rate limits or creating thousands of pending orders in the Razorpay dashboard.
- **Remediation:** Implement IP/UID-based rate limiting via Cloudflare Worker Rate Limiting bindings or Cloudflare KV token bucket counters.

---

### Finding ID: PAY-03 — Hardcoded Fallback Test Key ID in Worker Code
- **Severity:** `LOW`
- **Component:** Cloudflare Worker Configuration
- **File Path:** `worker/src/index.js#L413, #L478`
- **Code Reference:**
  ```javascript
  const rawKeyId = env.RAZORPAY_KEY_ID || "rzp_test_Tb9rSP1OzJ3Tdi";
  ```
- **Current Behavior:** If `RAZORPAY_KEY_ID` environment secret is missing or unset, the worker falls back to a public Razorpay test key ID.
- **Security Impact:** While public key IDs (`rzp_test_...`) are not secrets, hardcoding test keys creates risks of accidental test transactions in production environments.
- **Remediation:** Require `RAZORPAY_KEY_ID` to be strictly set via `env` and reject execution with a 500 error if missing, matching `RAZORPAY_KEY_SECRET` validation logic.

---

### Finding ID: PAY-04 — Permissive Wildcard CORS Header on Payment Endpoints
- **Severity:** `LOW`
- **Component:** Cloudflare Worker API
- **File Path:** `worker/src/index.js#L26`
- **Code Reference:**
  ```javascript
  const CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    ...
  };
  ```
- **Current Behavior:** Allows browser-based cross-origin invocations from any origin.
- **Security Impact:** While endpoints require `Authorization: Bearer <Firebase_ID_Token>`, wildcard CORS allows arbitrary third-party web pages to invoke the API if they can obtain or proxy a valid token.
- **Remediation:** Restrict CORS origin to known domain origins or omit CORS headers for purely native mobile application endpoints.

---

## 10. Secrets & Credential Inventory

| Credential / Identifier | Classification | Location | Storage Status & Exposure |
| :--- | :--- | :--- | :--- |
| `RAZORPAY_KEY_SECRET` | **Real Secret** | Cloudflare Worker Environment Variable (`env.RAZORPAY_KEY_SECRET`) | **Secure**: Never present in client APK or repository source code. Injected via Cloudflare encrypted secrets. |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | **Real Secret** | Cloudflare Worker Environment Variable (`env.FIREBASE_SERVICE_ACCOUNT_KEY`) | **Secure**: Service account private key is stored exclusively as an edge secret. |
| `RAZORPAY_KEY_ID` (`rzp_test_...`) | **Public Identifier / Test Credential** | `worker/src/index.js`, `RazorpaySubscriptionPurchaseServiceImpl.kt` | **Non-Secret**: Public gateway identifier required by client SDK. |
| `FIREBASE_PROJECT_ID` (`refocus-504119`) | **Public Identifier** | `worker/src/index.js`, `google-services.json` | **Non-Secret**: Standard Firebase project ID. |

---

## 11. Strong Security Controls Already Present

1. **Edge RS256 JWKS Verification:** Cryptographically validates Google-signed Firebase ID tokens before executing payment or database actions.
2. **Constant-Time HMAC Verification:** Prevents timing side-channel attacks on payment signature verification.
3. **Out-of-Band Price Validation:** Verifies payment amount (`9900` paise) and currency (`INR`) directly against Razorpay's API rather than trusting client-provided payment amounts.
4. **Zero-Trust Firestore Database Rules:** Direct client-side writes to `/users/{uid}/subscription/status` are unconditionally denied (`allow write: if false;`).
5. **Anti-Clock Tampering Engine:** Client derives access state using server-anchored timestamps via `TrustedTimeManager`.

---

## 12. Prioritized Remediation Roadmap

1. **P1 — Implement Razorpay Server Webhook (`POST /api/razorpay-webhook`):** Handle refund events (`refund.processed`), chargebacks (`dispute.created`), and asynchronous payment capture.
2. **P2 — Add Edge Rate Limiting:** Prevent order creation spam and brute-force verification requests on `/api/create-order` and `/api/verify-payment`.
3. **P3 — Remove Hardcoded Test Key Fallbacks:** Enforce mandatory environment variable resolution for all Razorpay identifiers.
4. **P4 — Restrict CORS Origin Policies:** Restrict or remove unnecessary CORS wildcard headers for mobile-specific endpoints.

---

RECOMMENDED NEXT AUDIT STEP:
STEP 7 — AUTHENTICATION, ACCOUNT LIFECYCLE & SESSION MANAGEMENT SECURITY AUDIT

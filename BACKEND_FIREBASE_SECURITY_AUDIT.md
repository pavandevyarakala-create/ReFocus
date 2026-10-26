# DEEP BACKEND & FIREBASE SECURITY AUDIT REPORT

**System:** Re:Focus (formerly ReelTrak)  
**Target:** Cloudflare Edge Worker, Firebase Authentication, Cloud Firestore Security Rules, Legacy Cloud Functions / Server, Google Service Account, and Razorpay Payment Integration  
**Date:** September 17, 2026  
**Status:** Inspection Only (No changes applied)

---

## 1. Executive Summary

This deep audit rigorously examined the entire backend attack surface:
1. Edge-native JWT validation in [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js).
2. Firestore access control rules in [`firestore.rules`](file:///c:/Users/pavan/OneDrive/Documents/REEL/firestore.rules).
3. Google Service Account assertion & OAuth2 REST API execution.
4. Razorpay payment creation, cryptographic HMAC-SHA256 signature verification, and race conditions.
5. Trial allocation logic and Sybil vulnerabilities.
6. Error handling, CORS leakage, and information disclosure.

---

## 2. Authentication & Payment Trust-Boundary Diagrams

### A. Authentication & Edge Validation Flow

```
[ Android Client ]
      │
      │ 1. Firebase Auth ID Token (RS256 JWT signed by Google SecureToken)
      ▼
[ Cloudflare Edge Worker ] (worker/src/index.js)
      │
      ├─► 2. Edge Google JWKS Key Discovery (kid matching via WebCrypto)
      ├─► 3. RS256 Crypto Verification (crypto.subtle.verify)
      ├─► 4. Claims Validation (iss: "https://securetoken.google.com/<projectId>", aud: "<projectId>", exp > now)
      │
      ▼ (Authenticated UID strictly extracted from JWT subject: payload.user_id || payload.sub)
[ Business Logic & Authorization Engine ]
```

### B. Payment Verification & Privilege Escalation Boundary

```
[ Client / Razorpay SDK ]
      │
      │ 1. Submits { razorpay_order_id, razorpay_payment_id, razorpay_signature } + Bearer Token
      ▼
[ Cloudflare Worker ]
      │
      ├─► 2. Authenticates Firebase ID Token (Yields trusted caller UID)
      ├─► 3. Verifies HMAC-SHA256 Signature (order_id|payment_id against RAZORPAY_KEY_SECRET)
      ├─► 4. Direct API Call to Razorpay (GET /v1/payments/{paymentId})
      │      - Validates: status == 'captured' || 'authorized'
      │      - Validates: payment.order_id == request.order_id
      │      - Validates: payment.amount == 9900 (₹99) & currency == 'INR'
      ├─► 5. Generates Google OAuth2 JWT assertion via Service Account Private Key (PKCS8)
      │
      ▼ (Authorized PATCH /databases/(default)/documents/users/{uid}/subscription/status)
[ Cloud Firestore REST API ] ──► Updates 'ACTIVE' 28-day subscription
```

---

## 3. Endpoint Security Matrix (Cloudflare Worker)

| Endpoint | Method | Auth Required | Identity Source | Authorization Check | User-Controlled Params | Sensitive Operations | Firestore Resources Accessed |
|---|---|---|---|---|---|---|---|
| `/api/ensure-subscription` | `POST` | Yes (`Bearer <Firebase_ID_Token>`) | Verified JWT `uid` | Caller matches token `uid` | None (empty body `{}`) | Initializes 7-day trial if document does not exist | `GET` & `PATCH` `/users/{uid}/subscription/status` |
| `/api/create-order` | `POST` | Yes (`Bearer <Firebase_ID_Token>`) | Verified JWT `uid` | Caller matches token `uid` | `planId` (must be `"plan_monthly"`) | Creates Razorpay order with server-enforced amount (9900 paise) | None (caches order in Razorpay metadata) |
| `/api/verify-payment` | `POST` | Yes (`Bearer <Firebase_ID_Token>`) | Verified JWT `uid` | Caller matches token `uid` | `razorpay_order_id`, `razorpay_payment_id`, `razorpay_signature` | Verifies HMAC, verifies Razorpay API capture, enforces 9900 paise, grants 28-day ACTIVE access | `GET` & `PATCH` `/users/{uid}/subscription/status` |
| `/` or `/health` | `GET` | No | None | Public | None | Health check | None |

---

## 4. Firestore Security Rules Permission Matrix

| Path | Read Permitted | Create Permitted | Update Permitted | Delete Permitted | Field-Level Constraints / Notes |
|---|---|---|---|---|---|
| `/users/{userId}` | `isOwner(userId)` | `isOwner(userId)` | `isOwner(userId)` | `false` | **Enforced:** `!request.resource.data.keys().hasAny(['premium', 'subscriptionStatus', 'subscriptionId', 'currentPeriodStart', 'currentPeriodEnd', 'planId'])` |
| `/users/{userId}/daily_data/{dateString}` | `isOwner(userId)` | `isOwner(userId)` | `isOwner(userId)` | `false` | Read/write allowed for tracking stats. Deletion forbidden. |
| `/users/{userId}/subscription/{documentId}` | `isOwner(userId)` | `false` (Denied) | `false` (Denied) | `false` (Denied) | **Fully Protected:** Client write is completely blocked. Backend uses Google Service Account to update via REST API. |
| `/users/{userId}/account/{documentId}` | `isOwner(userId)` | `false` (Denied) | `false` (Denied) | `false` (Denied) | **Fully Protected:** Master account status (ACTIVE/RESTRICTED) writable only via Admin / Console. |
| `/{document=**}` (Catch-all) | `false` | `false` | `false` | `false` | Default deny on all unspecified paths. |

### Conceptual Boundary Tests:
- **User A → User B's profile/data/subscription:** `request.auth.uid == userId` fails (`403 PERMISSION_DENIED`).
- **Unauthenticated → Any path:** `request.auth != null` fails (`403 PERMISSION_DENIED`).
- **Client → Self-granting premium at root `/users/{userId}`:** Rejected by `hasAny` whitelist check.
- **Client → Direct write to `/users/{userId}/subscription/status`:** Blocked by `allow write: if false;`.

---

## 5. Comprehensive Audit Findings Log

---

### [FINDING-01] CRITICAL: Hardcoded Razorpay Secret Key in Cloud Functions Fallback Code
- **Severity:** `CRITICAL`
- **File & Location:** [`functions/index.js:L28`](file:///c:/Users/pavan/OneDrive/Documents/REEL/functions/index.js#L28)
- **Current Behavior:**
  ```javascript
  function getKeySecret() {
      try {
          const val = razorpayKeySecret.value();
          if (val) return val;
      } catch (_) {}
      return process.env.RAZORPAY_KEY_SECRET || "MvcuJz39QJsQXGf0cPSvfgib";
  }
  ```
- **Security Impact:**
  The production/test secret key `MvcuJz39QJsQXGf0cPSvfgib` is embedded in plaintext in source code. Anyone with access to the repo or deployed artifact can generate valid HMAC-SHA256 signatures for arbitrary payment IDs, issue unauthorized refunds, or forge payment confirmations.
- **Realistic Attack Scenario:**
  An attacker reads the hardcoded secret from leaked source or repository history, generates a fake payment ID, computes `HMAC_SHA256("order_xxx|pay_fake", secret)`, and submits it to `/verifyRazorpayPayment` (or the Cloudflare Worker if using the same key).
- **Exploitability:** **Directly Exploitable**.
- **Evidence:** Literal string `"MvcuJz39QJsQXGf0cPSvfgib"` on Line 28 of `functions/index.js` and in `.env`.
- **Recommended Fix:**
  1. Rotate key immediately in Razorpay Dashboard.
  2. Remove fallback string completely.
  3. Ensure secret is injected solely via environment variables or Secret Manager (`defineSecret`).

---

### [FINDING-02] HIGH: Absence of Webhook for Out-of-Band Refunds / Chargebacks
- **Severity:** `HIGH`
- **File & Location:** [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js) (Entire backend)
- **Current Behavior:**
  Subscription activation occurs strictly when the mobile client calls `POST /api/verify-payment`. The backend has no webhook endpoint listening to Razorpay server-to-server events (`payment.failed`, `refund.created`, `refund.processed`, `dispute.created`).
- **Security Impact:**
  If a user completes a ₹99 purchase, receives 28 days of access in Firestore, and immediately requests a refund through Razorpay support or their issuing bank, the backend is never notified. Firestore continues to serve `subscriptionStatus: "ACTIVE"` until the 28-day expiration.
- **Realistic Attack Scenario:**
  User purchases monthly subscription, activates app, requests instant refund via UPI/bank dispute, gets their money back, but retains premium access for the full 28 days.
- **Exploitability:** **Currently Exploitable (Revenue Loss / Free Access via Refund Abuse)**.
- **Recommended Fix:**
  Add a dedicated `POST /api/razorpay-webhook` endpoint with `X-Razorpay-Signature` validation that downgrades Firestore status to `EXPIRED` / `CANCELLED` upon receiving `refund.processed`.

---

### [FINDING-03] MEDIUM: Lack of Sybil / Account-Recreation Resistance on Trial Initialization
- **Severity:** `MEDIUM`
- **File & Location:** [`worker/src/index.js:L367-L395`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L367-L395)
- **Current Behavior:**
  `/api/ensure-subscription` inspects whether `/users/{uid}/subscription/status` exists. If not found, it creates a 7-day trial.
- **Security Impact:**
  Firebase Authentication allows free account creation (via email/password or automated scripts). An attacker can delete their local session, create a new Firebase account, and receive a fresh 7-day trial indefinitely without ever paying.
- **Realistic Attack Scenario:**
  A script generates new email accounts on day 7, signs in, calls `/api/ensure-subscription`, and resets the trial cycle infinitely.
- **Exploitability:** **Exploitable for Infinite Free Access**.
- **Recommended Fix:**
  Enforce Google Play Integrity API or Firebase App Check verification on `/api/ensure-subscription` to verify genuine untampered devices, or bind trial status to a verified external account identifier.

---

### [FINDING-04] MEDIUM: Race Condition / Non-Atomic Check-Then-Set in `/api/ensure-subscription` and `/api/verify-payment`
- **Severity:** `MEDIUM`
- **File & Location:** [`worker/src/index.js:L375-L390`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L375-L390) & [`worker/src/index.js:L520-L554`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L520-L554)
- **Current Behavior:**
  The Worker performs a `getFirestoreSubscription(uid)` (HTTP GET), inspects fields in worker memory, and subsequently executes `updateFirestoreSubscription(uid)` (HTTP PATCH).
- **Security Impact:**
  Because this is not an atomic Firestore transaction or conditional write (using Firestore preconditions like `currentDocument.exists` or `updateTime`), concurrent requests sent in parallel can interleave.
  In `/api/ensure-subscription`, two simultaneous requests from a new user could both see `null` and perform redundant PATCH writes.
- **Realistic Attack Scenario:**
  Two concurrent `/api/verify-payment` calls for two different orders/payments could overwrite one another without proper transactional locking.
- **Exploitability:** **Theoretical / Low Real-World Impact on Single Users, but Violates Strict Concurrency Invariants**.
- **Recommended Fix:**
  Use Firestore REST API preconditions (`currentDocument.exists=false` for trial creation, or Firestore commit transactions).

---

### [FINDING-05] LOW: Permissive CORS Configuration (`*`) on API Endpoints
- **Severity:** `LOW`
- **File & Location:** [`worker/src/index.js:L26-L31`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L26-L31)
- **Current Behavior:**
  `Access-Control-Allow-Origin: *` is returned on all endpoints.
- **Security Impact:**
  While the endpoints require an `Authorization: Bearer <ID_Token>` header (meaning ambient browser credentials like cookies are not exploited), an arbitrary website visited by a user could make AJAX requests to the worker if the site managed to obtain a token.
- **Exploitability:** **Low / Non-Exploitable without token exfiltration**.
- **Recommended Fix:**
  Restrict or eliminate CORS headers since the API is solely consumed by a native Android application.

---

### [FINDING-06] LOW: Google Service Account Token Cached in Global Worker Scope Across Invocations
- **Severity:** `LOW`
- **File & Location:** [`worker/src/index.js:L21-L23`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L21-L23) & [`worker/src/index.js:L194-L251`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L194-L251)
- **Current Behavior:**
  `serviceAccountTokenCache` and `jwksCache` are stored in global in-memory variables.
- **Security Analysis:**
  Within a single Cloudflare Worker isolate, global memory is preserved across requests handled by that isolate. While standard practice for performance in serverless runtimes, if an isolate is reused across different environments (not applicable here, but good practice), token leakage could occur.
- **Exploitability:** **Informational / Best Practice Observation**.
- **Recommended Fix:**
  Ensure isolate boundaries and environment isolation are maintained.

---

## 6. Detailed Inspection Areas

### A. Firebase Authentication (JWT Validation)
- **Signature & Algorithm:** Correctly enforces `header.alg === "RS256"`. Rejects any algorithm switching (e.g. `none` or `HS256`).
- **Public Key Fetching:** Downloads Google JWKS from `https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com` and matches `header.kid`.
- **Claims Verification:**
  - `exp`: Validated (`payload.exp < nowSec` triggers expiration error).
  - `iss`: Validated (`https://securetoken.google.com/${projectId}`).
  - `aud`: Validated (`projectId`).
  - `uid`: Extracted from `payload.user_id || payload.sub || payload.uid`.
- **Clock Skew:** Currently checks `payload.exp < nowSec` with 0-second margin. A minor clock skew (e.g. client token generated with slight time difference) could cause premature rejection, but does not introduce security vulnerabilities.
- **Impersonation Assessment:** **Attacker CANNOT impersonate another user**. The JWT subject is verified with Google's RS256 public keys. Client cannot supply arbitrary UIDs.

### B. Authorization & IDOR/BOLA Assessment
- All worker endpoints extract the `uid` directly from the validated JWT payload.
- No user-supplied `uid`, `userId`, or path parameter is trusted for resource addressing.
- All Firestore accesses are strictly scoped to `/users/${uid}/...`.
- **IDOR / BOLA Vulnerability Status: NONE DETECTED**.

### C. Razorpay Cryptographic Verification
- **HMAC Verification:** Uses WebCrypto `crypto.subtle.sign("HMAC", ...)` with `SHA-256` over `${order_id}|${payment_id}`.
- **Timing Attacks:** Line 345 of [`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js#L345) uses constant-time XOR comparison:
  ```javascript
  let result = 0;
  for (let i = 0; i < calculatedHex.length; i++) {
    result |= calculatedHex.charCodeAt(i) ^ signatureHex.charCodeAt(i);
  }
  return result === 0;
  ```
- **Authoritative Validation:** Queries Razorpay API directly to verify `payment.status == 'captured'` and `payment.amount == 9900`. An attacker cannot forge an active status with a ₹1 payment.

---

## 7. Confirmed Vulnerabilities vs False Positives

### Confirmed Vulnerabilities
1. `CRITICAL`: Hardcoded fallback Razorpay Secret in `functions/index.js` and cleartext in `.env`.
2. `HIGH`: Missing Razorpay server webhook to invalidate subscriptions upon refund/chargeback.
3. `MEDIUM`: Sybil attack vulnerability allowing infinite trial generation via account recreation.
4. `MEDIUM`: Non-atomic check-then-set in Firestore REST API operations.

### False Positives / Non-Issues Investigated & Cleared
- **Client-side UID parameter injection:** Investigated and verified safe; backend never takes `uid` from request body or query params.
- **Firestore direct write privilege escalation:** Investigated and verified safe; `firestore.rules` strictly prohibits all client writes to `/subscription/*` and `/account/*`.
- **Timing attack on signature comparison:** Investigated and verified safe; constant-time byte/character comparison is implemented.
- **Amount tampering in Razorpay orders:** Investigated and verified safe; Worker enforces `amountPaise = 9900` during both order creation and payment verification against the Razorpay API.

---

## 8. Recommended NEXT Audit Step ONLY

> **Recommended Next Step:** **Audit of Android Local Storage, Data Protection & Cryptography Layer** (Room SQLite Database, DataStore Preferences, `TrustedTimeManager`, and `SyncManager`), focusing on at-rest encryption, offline replay vulnerabilities, and local time tampering.

# Legacy Payment Endpoint Removal & Architecture Elimination Report

**Document Version:** 1.0.0  
**Target Environment:** Cloudflare Edge Worker (`worker/src/index.js`, `worker/test_runner.mjs`)  
**Status:** COMPLETE & VERIFIED — ALL LEGACY ONE-TIME PAYMENT ROUTES REMOVED  
**Author:** Antigravity Agentic Security Engine  

---

## Executive Summary

As part of the production readiness hardening, the obsolete legacy Razorpay one-time payment architecture has been completely purged from the Cloudflare Worker backend. 

All legacy routes (`/api/create-order`, `/createRazorpayOrder`, `/api/verify-payment`, `/verifyRazorpayPayment`), the legacy 28-day entitlement logic (`reconcileLegacyEntitlementActivation`, `plan_monthly_legacy`), and legacy one-time webhook branches (`payment.captured`, `order.paid`) have been eliminated.

The only supported Premium payment architecture across the entire Re:Focus ecosystem is now the authoritative **Razorpay Recurring Subscription Autopay Engine** (₹79/month).

---

## 1. Discovered Legacy Endpoints & Callers

A complete repository-wide audit was conducted prior to modifying any code:

### 1.1 Discovered Legacy Endpoints & Functions
1. `POST /api/create-order` and `POST /createRazorpayOrder`: Legacy one-time order creation endpoint (₹99 / 9900 paise).
2. `POST /api/verify-payment` and `POST /verifyRazorpayPayment`: Legacy synchronous payment verification endpoint granting discrete 28-day access.
3. `reconcileLegacyEntitlementActivation()`: Function that wrote `plan_monthly_legacy` and 28-day timestamps directly to Firestore `users/{uid}/subscription/status`.
4. `payment.captured` and `order.paid`: Webhook cases in `POST /api/razorpay-webhook` that invoked `reconcileLegacyEntitlementActivation()`.

### 1.2 Identified Callers & Dependencies
* **Active Android Application Source (`app/src/main/java`):** **ZERO callers.**
  * The production Android app uses strictly:
    * `POST /api/create-subscription` ([`RazorpaySubscriptionPurchaseServiceImpl.kt:107`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L107))
    * `POST /api/verify-subscription` ([`RazorpaySubscriptionPurchaseServiceImpl.kt:245`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L245))
    * `POST /api/ensure-subscription` ([`SubscriptionRepositoryImpl.kt:148`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L148))
    * `POST /api/cancel-subscription` ([`SubscriptionRepositoryImpl.kt:245`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L245))
    * `GET /api/time` ([`TrustedTimeManager.kt:152`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/time/TrustedTimeManager.kt#L152))
    * `POST /api/account/request-deletion` & `POST /api/account/cancel-deletion` ([`AccountRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/AccountRepositoryImpl.kt))
* **Historical Files:** `server.js` (legacy local development express server), historical security audit documents.

---

## 2. Exact Code Removed from `worker/src/index.js`

### 2.1 Removed Docstring References (Lines 9–10)
```diff
- * - POST /api/create-order (LEGACY: Deprecated one-time order creation)
- * - POST /api/verify-payment (LEGACY: Deprecated one-time payment verification)
```

### 2.2 Removed Legacy 28-Day Entitlement Handler (Lines 1245–1305)
```javascript
// REMOVED:
async function reconcileLegacyEntitlementActivation(uid, paymentId, orderId, env) {
  // Wrote plan_monthly_legacy and 28-day window to Firestore
}
```

### 2.3 Removed One-Time Webhook Cases (Lines 1550–1561)
```diff
- case "payment.captured":
- case "order.paid": {
-   if (!firebaseUid) return jsonResponse({ status: "ignored_missing_uid" }, 200, request);
-   if (paymentEntity && (paymentEntity.amount !== 9900 || paymentEntity.currency !== "INR")) return jsonResponse({ status: "ignored_invalid_amount" }, 200, request);
-   const result = await reconcileLegacyEntitlementActivation(firebaseUid, paymentId, orderId, env);
-   return jsonResponse({ status: "processed_legacy", result }, 200, request);
- }
```
*(Now falls through cleanly to `default: { return jsonResponse({ status: "ignored_unhandled_type" }, 200, request); }` without modifying any user records).*

### 2.4 Removed Legacy Route Handlers in Router (Lines 2072–2221)
```javascript
// REMOVED:
if (path === "/api/create-order" || path === "/createRazorpayOrder") { ... }
if (path === "/api/verify-payment" || path === "/verifyRazorpayPayment") { ... }
```
*(Calls to these paths now fall directly through to `return jsonResponse({ error: "Not Found" }, 404, request);`).*

---

## 3. Supported Premium Payment Architecture

The only active payment architecture in the Worker is:

```
                  ┌────────────────────────────────────────────────────────┐
                  │                 ANDROID APPLICATION                    │
                  └───────────────┬────────────────────────┬───────────────┘
                                  │                        │
         1. Create Subscription   │                        │ 3. Verify Mandate
            (POST /api/create-    │                        │    (POST /api/verify-
             subscription)        │                        │     subscription)
                                  ▼                        ▼
                  ┌────────────────────────────────────────────────────────┐
                  │            CLOUDFLARE EDGE WORKER                      │
                  │  - Validates App Check (X-Firebase-AppCheck)           │
                  │  - Authenticates Firebase User JWT                     │
                  │  - Server-enforced planId ("plan_TdtgehXIhzQ8U9")      │
                  │  - Binds firebase_uid into subscription notes          │
                  │  - Verifies HMAC signature with Web Crypto             │
                  └───────────────┬────────────────────────▲───────────────┘
                                  │                        │
                 Server-to-Server │                        │ 4. Webhook Events
                 API Calls        │                        │    (subscription.activated,
                                  ▼                        │     subscription.charged,
                  ┌────────────────────────────────────────┴───────────────┐
                  │                 RAZORPAY GATEWAY                       │
                  │  - Monthly Recurring Autopay (₹79/month)               │
                  │  - Webhook delivery with HMAC-SHA256 signature         │
                  └────────────────────────────────────────────────────────┘
```

### Remaining Endpoints & Capabilities:
1. `GET /health` & `GET /`: Health check probes.
2. `GET /api/time`: Pre-login server-authoritative time synchronization (App Check device attestation protected).
3. `POST /api/ensure-subscription`: 7-day server trial initialization.
4. `POST /api/create-subscription`: Razorpay recurring subscription creation (120 cycles, plan `plan_TdtgehXIhzQ8U9`).
5. `POST /api/verify-subscription`: Server verification of signature and Razorpay subscription status.
6. `POST /api/cancel-subscription`: Authoritative cancellation at cycle end via Razorpay API.
7. `POST /api/razorpay-webhook`: Authoritative lifecycle synchronization (`subscription.authenticated`, `subscription.activated`, `subscription.charged`, `subscription.pending`, `subscription.halted`, `subscription.cancelled`, `subscription.resumed`, `subscription.completed`, `payment.failed`, `refund.processed`, `refund.created`).
8. `GET /api/account/status`, `POST /api/account/request-deletion`, `POST /api/account/cancel-deletion`: Account lifecycle management.

---

## 4. Verification & Test Suite Results

The Node.js Worker test suite ([`worker/test_runner.mjs`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/test_runner.mjs)) was updated with dedicated test cases validating the complete removal and security of legacy paths:

### 4.1 New Legacy Removal Test Cases
* `POST /api/create-order` &rarr; Returns `404 Not Found` (`{"error":"Not Found"}`).
* `POST /createRazorpayOrder` &rarr; Returns `404 Not Found`.
* `POST /api/verify-payment` &rarr; Returns `404 Not Found`, grants **zero** Firestore entitlement.
* `POST /verifyRazorpayPayment` &rarr; Returns `404 Not Found`.
* `Webhook: payment.captured` / `order.paid` &rarr; Returns `200 OK` with `status: "ignored_unhandled_type"`, grants **zero** Firestore entitlement.

### 4.2 Full Test Suite Execution
```text
========================================
Test Results: 49 Passed, 0 Failed
========================================
```
* **Authentication & Health:** 4/4 Passed
* **Legacy Route Elimination:** 5/5 Passed
* **Create Subscription:** 4/4 Passed
* **Verify Subscription:** 3/3 Passed
* **Cancel Subscription:** 1/1 Passed
* **Webhook Lifecycle & Security:** 32/32 Passed

---

## 5. Security Confirmation: Zero Unregulated Entitlement Paths

An attacker attempting to exploit legacy endpoints encounters the following:
* **Arbitrary Payment IDs / Signatures:** Attempting to POST to `/api/verify-payment` immediately receives **HTTP 404**. No database reads, signature verifications, or Firestore writes occur.
* **Legacy Webhooks:** Replaying old `payment.captured` or `order.paid` webhooks produces `ignored_unhandled_type` without touching `users/{uid}/subscription/status`.
* **Legacy 28-Day Plan:** `plan_monthly_legacy` has been completely purged from the codebase.
* **Subscription State Machine Integrity:** The only way to obtain `premium: true` in Firestore is through an authentic Razorpay subscription validated against `plan_TdtgehXIhzQ8U9` with cryptographic HMAC verification and UID ownership checks.

---

## 6. Conclusion

The legacy Razorpay one-time payment system has been **safely, cleanly, and completely eliminated**. The Worker codebase is now 100% focused on the recurring autopay subscription model, with zero dead legacy endpoints or backward-compatibility security holes.

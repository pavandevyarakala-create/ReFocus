# Razorpay TEST Environment & Plan Binding Status

**Document Version:** 1.0  
**Verification Date:** 2026-09-19  
**Status:** TEST PLAN BOUND & VERIFIED (READ-ONLY)

---

## 1. TEST Plan Configuration & Binding Details

| Parameter | Value / Status | Verification Notes |
| :--- | :--- | :--- |
| **TEST Plan ID** | `plan_TdtgehXIhzQ8U9` | Verified created in Razorpay Dashboard (Test Mode) |
| **TEST Plan Price** | `₹79.00 INR / month` (`7900` paise) | User-configured recurring monthly subscription |
| **Billing Frequency** | `monthly` (Interval: `1`) | Recurring billing every 1 month |
| **Total Cycles** | `120` (10 years) | Passed dynamically by Worker during subscription creation |
| **Environment Binding** | `env.RAZORPAY_PLAN_ID = "plan_TdtgehXIhzQ8U9"` | Configured in `worker/wrangler.jsonc` and deployed to Cloudflare Worker |

---

## 2. Cloudflare Worker Deployment & Health Status

- **Worker Canonical URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
- **Current Version ID:** `4cd6fa08-7f42-4fa9-be1a-e8936d008083`
- **Wrangler Version:** `4.135.0`
- **Deployed Triggers / Status:** `refocus-razorpay-worker (ACTIVE)`
- **Healthcheck Verification (`GET /health`):**
  ```json
  {
    "status": "healthy",
    "service": "Re:Focus Edge Worker",
    "model": "recurring_autopay_v2"
  }
  ```
  *(Verified live via HTTPS HTTP 200 response).*

---

## 3. Worker Automated Test Suite Results

The complete test runner (`worker/test_runner.mjs`) was executed against the newly bound Plan ID `plan_TdtgehXIhzQ8U9`:

```text
========================================
Test Results: 17 Passed, 0 Failed
========================================
```

**Verified Test Cases:**
1. `GET /health` returns healthy status and recurring model (PASS)
2. `POST /api/create-subscription` rejects unauthenticated requests with 401 (PASS)
3. `POST /api/create-subscription` rejects missing `RAZORPAY_PLAN_ID` configuration with 500 (PASS)
4. `POST /api/create-subscription` rejects invalid requested plan ID with 400 (PASS)
5. `POST /api/create-subscription` rejects `PENDING_DELETION` / `DELETED` accounts with 403 (PASS)
6. `POST /api/create-subscription` binds Firebase UID to notes and returns `{ subscriptionId, keyId }` without secrets (PASS)
7. `POST /api/verify-subscription` rejects invalid signature with 400 (PASS)
8. `POST /api/verify-subscription` rejects UID ownership mismatch with 403 (PASS)
9. `POST /api/verify-subscription` successfully activates recurring subscription with 200 (PASS)
10. `POST /api/cancel-subscription` cancels at period end and preserves active access (PASS)
11. Webhook rejects missing or invalid signature with 400 (PASS)
12. Webhook enforces atomic idempotency on duplicate deliveries (PASS)
13. Webhook `subscription.charged` renews cycle and extends `currentPeriodEnd` (PASS)
14. Webhook `subscription.pending` puts subscription in `PENDING` status (grace period) (PASS)
15. Webhook `subscription.halted` revokes premium access immediately (PASS)
16. Webhook `refund.processed` revokes access and marks `CANCELLED` (PASS)
17. Webhook rejects stale activation after refund/cancellation (PASS)

---

## 4. Security, Isolation & Architectural Guarantees

1. **Android Source Code Isolation**:
   - The Android client **does not contain any Plan ID** or Razorpay Secret.
   - Android requests `/api/create-subscription` via authenticated HTTPS and receives the dynamically generated `subscriptionId` and public `keyId`.
2. **Zero Secret Exposure**:
   - `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and `FIREBASE_SERVICE_ACCOUNT_KEY` are stored strictly in Cloudflare Worker encrypted secrets.
   - Zero secrets are printed in logs, exposed in API responses, or committed to Git.
3. **TEST vs. LIVE Strict Separation**:
   - All credentials and configurations in use are **TEST-only** (`rzp_test_...`).
   - No LIVE credentials, LIVE Plan IDs, or LIVE webhooks have been created, modified, or assumed.
   - LIVE payment processing is strictly disabled until explicit live configuration.
4. **Firebase Security Rules**:
   - Firestore security rules (`firestore.rules`) remain completely untouched. Client-side subscription document writes remain strictly blocked (`allow write: if false;`).
5. **DEBUG Mock Simulator**:
   - The mock subscription simulator in `DevMockSubscriptionPurchaseServiceImpl.kt` remains strictly isolated under `BuildConfig.DEBUG` and untouched.
6. **Webhook Endpoint**:
   - Webhook URL remains: `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`

---

## 5. Exact Remaining Steps Before First End-to-End TEST Payment

1. **Configure Webhook in Razorpay Dashboard (Test Mode):**
   - Ensure the webhook in Razorpay Dashboard (Test Mode) is pointing to `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`.
   - Ensure the 9 subscription events are checked:
     - `subscription.authenticated`
     - `subscription.activated`
     - `subscription.charged`
     - `subscription.pending`
     - `subscription.halted`
     - `subscription.cancelled`
     - `subscription.completed`
     - `payment.failed`
     - `refund.processed`
2. **Launch Android App in Test Environment:**
   - Sign in with Google.
   - Open Paywall / Subscription sheet and click **Subscribe**.
   - Complete mandate authorization using Razorpay Test UPI / Test Card.
3. **Verify End-to-End Flow:**
   - Confirm Worker `/api/verify-subscription` returns success.
   - Confirm Firestore `/users/{uid}/subscription/status` updates to `ACTIVE`.
   - Confirm the Android UI reactively unlocks.

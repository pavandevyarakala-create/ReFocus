# RE:FOCUS — Monthly Autopay Worker Implementation Status
**Document Version:** 1.0  
**Status:** BACKEND IMPLEMENTATION & TEST VERIFICATION COMPLETE  
**Target:** Cloudflare Worker Recurring Subscriptions & Webhook Reconciliation Engine

---

## 1. Implemented Endpoints

| Endpoint | Method | Purpose & Implementation Details |
|---|---|---|
| `/health` | `GET` | Service healthcheck returning `{ status: "healthy", service: "Re:Focus Edge Worker", model: "recurring_autopay_v2" }`. Rate-limited to 60 req/60s per IP. |
| `/api/ensure-subscription` | `POST` | Initializes 7-day server-side free trial for new users (`trialStart`, `trialEnd`, `trialStartDate`, `trialEndDate`). Rejects deleted or restricted accounts. |
| `/api/create-subscription` | `POST` | **NEW:** Creates recurring subscription on Razorpay (`POST /v1/subscriptions`, `total_count: 120`, `plan_id: RAZORPAY_PLAN_ID`). Binds authenticated Firebase UID to Razorpay notes and internal mapping. Returns `{ subscriptionId, keyId }`. |
| `/api/verify-subscription` | `POST` | **NEW:** Verifies Razorpay checkout subscription signature `HMAC-SHA256(payment_id + "|" + subscription_id, RAZORPAY_KEY_SECRET)`. Validates subscription ownership and plan matching. Writes authoritative `status: "ACTIVE"`, `subscriptionId`, and period timestamps to Firestore. |
| `/api/cancel-subscription` | `POST` | **NEW:** Authoritative cancellation endpoint. Calls Razorpay `POST /v1/subscriptions/{id}/cancel` with `cancel_at_cycle_end: 1`. Updates Firestore with `cancelAtPeriodEnd: true` while preserving active entitlement until the billing cycle expires. |
| `/api/create-order` | `POST` | **LEGACY (ISOLATED):** Maintained for temporary backward compatibility. Marked with `is_legacy: true` in notes. |
| `/api/verify-payment` | `LEGACY` | **LEGACY (ISOLATED):** Maintained for temporary backward compatibility. Activates discrete legacy 28-day window without recurring properties. |
| `/api/razorpay-webhook` | `POST` | Server-to-server webhook endpoint handling 9 subscription lifecycle events with raw-body HMAC-SHA256 signature verification and exact-once atomic idempotency. |
| `/api/account/request-deletion` | `POST` | Account soft-deletion with 7-day grace period. |
| `/api/account/cancel-deletion` | `POST` | Cancels pending deletion within grace period and restores account to `ACTIVE`. |
| `/api/account/status` | `GET` | Master account status and automated lazy purge of expired accounts. |

---

## 2. Webhook Events Implemented

| Webhook Event | Payload Handling & Entitlement Action |
|---|---|
| `subscription.authenticated` | Stores mandate authorization map (`subscriptionId` $\to$ `firebaseUid`). |
| `subscription.activated` | Activates initial paid subscription. Sets `subscriptionStatus = "ACTIVE"`, `premium = true`, and sets `currentPeriodStart`/`currentPeriodEnd` from Razorpay timestamps. |
| `subscription.charged` | Recurring monthly auto-debit renewal. Advances `currentPeriodEnd` and `nextBillingDate` from Razorpay authoritative cycle dates. |
| `subscription.pending` | Auto-debit failed; Razorpay retrying. Sets `subscriptionStatus = "PENDING"`, preserving premium access during the grace period. |
| `subscription.halted` | Retries exhausted. Sets `subscriptionStatus = "HALTED"`, `premium = false`, locking access immediately. |
| `subscription.cancelled` | User/Admin cancelled subscription. Sets `cancelAtPeriodEnd = true`. If period has ended, sets `subscriptionStatus = "CANCELLED"`, `premium = false`. |
| `subscription.completed` | Total cycle count reached. Sets `subscriptionStatus = "EXPIRED"`, `premium = false`. |
| `payment.failed` | Logs individual payment failure notification. |
| `refund.processed` / `refund.created` | Revokes entitlement immediately (`subscriptionStatus = "CANCELLED"`, `premium = false`, `refundedAt = now`), and cancels recurring billing on Razorpay. |

---

## 3. Firestore Data Model Updates

Path: `/users/{uid}/subscription/status`

| Field | Type | Description |
|---|---|---|
| `subscriptionStatus` | `stringValue` | `"TRIAL" \| "ACTIVE" \| "PENDING" \| "HALTED" \| "CANCELLED" \| "EXPIRED"` |
| `subscriptionId` | `stringValue` | Razorpay subscription ID (`sub_...`) |
| `planId` | `stringValue` | Razorpay plan ID (`plan_...`) |
| `latestPaymentId` | `stringValue` | Latest transaction ID (`pay_...`) |
| `trialStartDate` / `trialStart` | `timestampValue` | Start of 7-day free trial |
| `trialEndDate` / `trialEnd` | `timestampValue` | End of 7-day free trial |
| `currentPeriodStart` | `timestampValue` | Start of current paid billing cycle |
| `currentPeriodEnd` | `timestampValue` | End of current paid billing cycle |
| `cancelAtPeriodEnd` | `booleanValue` | `true` if cancelled but active until period end |
| `nextBillingDate` | `timestampValue` | Upcoming auto-debit timestamp |
| `premium` | `booleanValue` | Server boolean flag indicating active access |
| `lastWebhookEventId` | `stringValue` | Event ID tracking for audit & idempotency |
| `updatedAt` | `timestampValue` | Timestamp of last server update |

Path: `/subscription_map/{subscriptionId}`

| Field | Type | Description |
|---|---|---|
| `firebaseUid` | `stringValue` | Authoritative Firebase UID bound to this subscription |
| `planId` | `stringValue` | Plan ID associated with this subscription |
| `createdAt` | `timestampValue` | Timestamp when mapping was established |

---

## 4. Security Controls Preserved

1. **Server Authority:** All subscription entitlement state is written exclusively by the Cloudflare Worker via Google Service Account OAuth2 credentials. Direct client writes remain blocked.
2. **App Check & JWT Auth:** Enforces Firebase App Check verification and RS256 Firebase ID Token signature verification via Google JWKS.
3. **UID Binding & Ownership Protection:** Subscriptions created on Razorpay carry the Firebase UID in `notes.firebase_uid`. `verify-subscription` explicitly rejects attempts by one UID to activate a subscription belonging to another UID.
4. **Subscription Signature Verification:** Verified using `HMAC-SHA256(payment_id + "|" + subscription_id, RAZORPAY_KEY_SECRET)`.
5. **Webhook Replay Protection & Idempotency:** Webhooks verify `X-Razorpay-Signature` with `RAZORPAY_WEBHOOK_SECRET` and write atomic idempotency markers to Firestore `/razorpay_events/{eventId}` (`currentDocument.exists=false`).
6. **Anti-Resurrection Guard:** Webhook `subscription.charged` rejects reactivation if the subscription was previously marked as refunded and cancelled.
7. **Rate Limiting:** Sliding-window per-IP and per-UID rate limiters prevent API flooding and brute force attempts.

---

## 5. Tests Added & Execution Results

Test Suite: `worker/test_runner.mjs` (executed via `npm test`)

```text
🚀 Starting Cloudflare Worker Monthly Autopay Test Suite...

  ✅ PASS: GET /health returns healthy status and recurring model
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
Test Results: 17 Passed, 0 Failed
========================================
```

---

## 6. Configuration Still Required

1. **`RAZORPAY_PLAN_ID` Variable:** Currently defined as an empty placeholder `""` in `wrangler.jsonc`. Needs to be populated with the actual Razorpay Plan ID (e.g. `plan_xxxxxxxxxxxxxx`) once created in Razorpay Dashboard or Test Mode.
2. **Secrets Preserved:** No changes made to production secrets (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `FIREBASE_SERVICE_ACCOUNT_KEY`).

---

## 7. Known Limitations & Boundaries

- **Worker Only:** Android app source code, Firestore client rules, and Razorpay Dashboard have NOT been modified in this phase.
- **Pre-Deployment:** The worker has not been redeployed to production yet.

---

## 8. Exact Next Step

1. Update the Android Client (`RazorpaySubscriptionPurchaseServiceImpl.kt`, `CloudSubscriptionDto.kt`, and `SubscriptionBottomSheet.kt`) to interact with the new `/api/create-subscription`, `/api/verify-subscription`, and `/api/cancel-subscription` endpoints.
2. Update the DEBUG mock subscription simulator to support recurring cycle testing.
3. Verify Android unit and security test suites.

# Razorpay ₹99/Month Recurring Autopay Dashboard Readiness Audit

**Audit Date:** 2026-09-19  
**Status:** Pre-Configuration Audit Complete (READ-ONLY)  
**Target:** Configure ₹99/Month Recurring Subscription in Razorpay Dashboard

---

## 1. Exact Razorpay Plan Configuration Required

To support the Re:Focus commercial model, the Plan created in the Razorpay Dashboard (or via API) must have the exact parameters below:

| Parameter | Value | Details / Rationale |
| :--- | :--- | :--- |
| **Plan Name** | `Re:Focus Monthly Premium` | Customer-visible subscription title on bank statement & mandate auth |
| **Plan Description** | `Monthly recurring access to Re:Focus premium features` | Explains recurring service in Razorpay & bank notifications |
| **Billing Period / Frequency** | `monthly` (`daily` / `weekly` / `monthly` / `yearly`) | Recurring cycle is exactly 1 month |
| **Billing Interval** | `1` | Charged every 1 month |
| **Amount** | `₹99.00` (`9900` paise) | Fixed monthly price inclusive of applicable taxes |
| **Currency** | `INR` | Standard Indian Rupee |
| **Total Count (Cycles)** | `120` | Passed at subscription creation (`POST /v1/subscriptions`); represents up to 10 years of ongoing renewals until cancelled |
| **Cancellation Policy** | `cancel_at_cycle_end = 1` | User retains access until the active paid period ends (`currentPeriodEnd`) |

---

## 2. Trial Period Architecture: Worker/App vs Razorpay Plan

### Determination:
**The 7-day free trial is handled ENTIRELY by our application and Cloudflare Worker / Firebase logic, NOT by the Razorpay Plan.**

- **How Trial Works in Re:Focus:**
  1. Upon user registration, Android calls `POST /api/ensure-subscription`.
  2. The Worker initializes `/users/{uid}/subscription/status` with `subscriptionStatus = "TRIAL"`, `trialStart = now`, `trialEnd = now + 7 days`, and `premium = false`.
  3. The app unlocks all premium features because `trustedNow < trialEnd` evaluates to `AccessState.TRIAL`.
  4. The user can explore the full app for 7 days without entering payment details.
- **Why Razorpay Plan should NOT have trial days configured:**
  1. If trial days were configured in the Razorpay Plan, a user authorizing recurring autopay on day 7 would get an additional unearned trial period on Razorpay before being charged.
  2. Authorizing recurring autopay via `POST /api/create-subscription` triggers immediate initial mandate authentication and first ₹99 charge (`total_count: 120`), immediately rolling the user into `ACTIVE` paid recurring status.

---

## 3. Environment & Configuration Values Status

| Configuration Variable | Current Status | Location / Destination | Required Action |
| :--- | :--- | :--- | :--- |
| `RAZORPAY_PLAN_ID` | **MISSING** (empty string `""` in `wrangler.jsonc`) | Cloudflare Worker Environment Secret / Var | Must be populated with the Plan ID (e.g. `plan_xxxxxxxxxxxxxx`) after creating the Plan in Razorpay Dashboard |
| `RAZORPAY_KEY_ID` | **CONFIGURED (Worker Secret)** | Cloudflare Worker Secret | Currently set in Cloudflare Worker secrets |
| `RAZORPAY_KEY_SECRET` | **CONFIGURED (Worker Secret)** | Cloudflare Worker Secret | Currently set in Cloudflare Worker secrets |
| `RAZORPAY_WEBHOOK_SECRET` | **CONFIGURED (Worker Secret)** | Cloudflare Worker Secret | Currently set in Cloudflare Worker secrets |
| `FIREBASE_SERVICE_ACCOUNT_KEY`| **CONFIGURED (Worker Secret)** | Cloudflare Worker Secret | Manages server-authoritative Firestore writes |
| `FIREBASE_PROJECT_ID` | **CONFIGURED** (`refocus-504119`) | `wrangler.jsonc` | Target Firebase project |
| `FIREBASE_PROJECT_NUMBER` | **CONFIGURED** (`524118238647`) | `wrangler.jsonc` | Used for App Check RS256 token verification |
| `APP_CHECK_ENFORCEMENT_MODE` | **CONFIGURED** (`"audit"`) | `wrangler.jsonc` | Audit mode active during testing |

---

## 4. Required Razorpay Webhook Events Matrix

The Cloudflare Worker (`worker/src/index.js`) listens for and processes the following **9 webhook events**:

| Webhook Event | Purpose in Re:Focus | Worker Action |
| :--- | :--- | :--- |
| `subscription.authenticated` | Autopay mandate authenticated | Binds `subscription_id` to Firebase UID in `subscription_map` collection |
| `subscription.activated` | Initial payment successful; subscription live | Sets `subscriptionStatus = "ACTIVE"`, `currentPeriodEnd = current_end`, `premium = true` |
| `subscription.charged` | Monthly automatic renewal successful | Extends `currentPeriodEnd = current_end`, records new `latestPaymentId`, `premium = true` |
| `subscription.pending` | Auto-debit failed; retry in progress | Sets `subscriptionStatus = "PENDING"`, preserves access during 3-day grace period |
| `subscription.halted` | Retries exhausted | Sets `subscriptionStatus = "HALTED"`, `premium = false` (locks app) |
| `subscription.cancelled` | Subscription cancelled | Sets `cancelAtPeriodEnd = true`. Keeps active until `currentPeriodEnd`, then expires |
| `subscription.completed` | 120 cycles reached | Sets `subscriptionStatus = "EXPIRED"`, `premium = false` |
| `payment.failed` | Individual debit attempt failed | Logged and acknowledged |
| `refund.processed` / `refund.created` | Subscription refund issued | Cancels subscription on Razorpay, sets `subscriptionStatus = "CANCELLED"`, revokes access |

---

## 5. Webhook Endpoint Verification

- **Canonical Worker URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`
- **Exact Webhook URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`
- **Method:** `POST`
- **Authentication:** `X-Razorpay-Signature` HMAC-SHA256 verification using `RAZORPAY_WEBHOOK_SECRET`
- **Idempotency:** Exact-once processing enforced via atomic Firestore `razorpay_events/{eventId}` collection.

---

## 6. Razorpay Subscriptions KYC & Business Requirements

### In TEST Mode:
- **KYC Status:** Not required. Test Plan creation, test subscriptions, UPI Autopay simulation, and test webhook deliveries function immediately with Test API Keys (`rzp_test_...`).
- **Payment Methods:** Simulated bank authorization, UPI Autopay test handles, and Razorpay test cards.

### In LIVE Mode:
- **KYC & Business Activation:** Full business KYC and banking verification are **mandatory** on Razorpay before live recurring auto-debits can be executed.
- **Recurring Autopay Activation:**
  - **UPI Autopay** must be activated under *Settings > Payment Methods*.
  - **Cards with e-Mandate** must be activated (RBI e-mandate framework requires AFA registration and pre-debit notifications via SMS/email).
  - **Customer Notification:** Must be enabled (`customer_notify: 1` is sent by our Worker) so Razorpay dispatches required 24-hour pre-debit notifications to users.

---

## 7. Android Client Checkout Flow Compatibility

| Step | Android Client Flow | Compatibility Status | Notes |
| :--- | :--- | :--- | :--- |
| **1. Auth & Pre-check** | `RazorpaySubscriptionPurchaseServiceImpl.kt` checks Firebase auth & obtains ID token | **COMPATIBLE** | Rejects unauthenticated users cleanly |
| **2. Create Subscription** | Calls `POST /api/create-subscription` with Bearer token & App Check | **COMPATIBLE** | Receives `{ subscriptionId, keyId }` |
| **3. Razorpay Checkout** | Opens Razorpay Standard SDK with `subscription_id`, `amount: 9900`, `currency: "INR"`, user prefill | **COMPATIBLE** | Android SDK handles UPI Autopay / card mandate UI |
| **4. On Success Callback** | `onPaymentSuccess` extracts `razorpay_payment_id`, `razorpay_subscription_id`, `razorpay_signature` | **COMPATIBLE** | Matches Razorpay Android SDK mandate callback schema |
| **5. Server Verification** | Calls `POST /api/verify-subscription` with `signature`, `payment_id`, `subscription_id` | **COMPATIBLE** | Worker validates `HMAC_SHA256(paymentId + "\|" + subscriptionId)` |
| **6. Real-time UI Update** | Reads Firestore `/users/{uid}/subscription/status` | **COMPATIBLE** | `SubscriptionRepositoryImpl` & `SubscriptionManager` reactively unlock app |
| **7. In-App Cancellation** | Settings screen provides "Manage Subscription" & cancellation | **COMPATIBLE** | Calls `POST /api/cancel-subscription` |

---

## 8. Hardcoded Secrets & Real Plan ID Audit

- [x] **No real Razorpay Plan ID hardcoded**: `RAZORPAY_PLAN_ID` in `wrangler.jsonc` is `""`. Android code uses generic `SubscriptionPlan.MONTHLY` enum with internal `planId = "plan_monthly"`.
- [x] **No Razorpay secrets in Android codebase**: Searched entire Android source; zero secret keys or private tokens found.
- [x] **No hardcoded credentials in Worker git repo**: `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and `FIREBASE_SERVICE_ACCOUNT_KEY` are managed strictly as Cloudflare Worker Secrets.
- [x] **Mock Mode Isolation**: All dev simulation is strictly inside `BuildConfig.DEBUG` blocks.

---

## 9. State & Field Alignment Check

| Entity | Android Domain Model (`SubscriptionInfo`) | Worker Backend (`index.js`) | Firestore Status Doc (`CloudSubscriptionDto`) | Razorpay Webhook Event | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Trial Active** | `AccessState.TRIAL` | `subscriptionStatus: "TRIAL"` | `trialStart`, `trialEnd`, `premium: false` | N/A (App-managed) | **ALIGNED** |
| **Trial Expired**| `AccessState.LOCKED` | `subscriptionStatus: "NONE"` | `trialEnd < now`, `premium: false` | N/A | **ALIGNED** |
| **Paid Active** | `AccessState.ACTIVE` | `subscriptionStatus: "ACTIVE"` | `currentPeriodStart`, `currentPeriodEnd`, `premium: true` | `subscription.activated`, `subscription.charged` | **ALIGNED** |
| **Grace Period** | `AccessState.ACTIVE` + Warning | `subscriptionStatus: "PENDING"` | `subscriptionStatus: "PENDING"`, `currentPeriodEnd > now` | `subscription.pending` | **ALIGNED** |
| **Halted** | `AccessState.LOCKED` | `subscriptionStatus: "HALTED"` | `subscriptionStatus: "HALTED"`, `premium: false` | `subscription.halted` | **ALIGNED** |
| **Cancelled** | `AccessState.ACTIVE` (before end) $\to$ `LOCKED` (after end) | `subscriptionStatus: "ACTIVE"` $\to$ `"CANCELLED"` | `cancelAtPeriodEnd: true`, `currentPeriodEnd` | `subscription.cancelled` | **ALIGNED** |
| **Completed** | `AccessState.LOCKED` | `subscriptionStatus: "EXPIRED"` | `subscriptionStatus: "EXPIRED"`, `premium: false` | `subscription.completed` | **ALIGNED** |

---

## 10. READY FOR TEST PLAN CREATION

The codebase and backend architecture are **100% prepared** for Razorpay TEST Plan configuration:

1. Cloudflare Worker has all recurring endpoints (`/api/create-subscription`, `/api/verify-subscription`, `/api/cancel-subscription`, `/api/razorpay-webhook`).
2. 17/17 Worker unit & security tests pass.
3. Android client is fully migrated to `subscription_id` standard checkout and verification.
4. Unit tests pass (65/65 passed). Release lint passes (0 errors). Debug APK builds.
5. All 9 subscription webhook events are implemented with signature verification and exact-once idempotency.
6. Firestore security rules prevent all unauthorized client-side subscription modifications.

---

## 11. BLOCKERS / REQUIRED ACTIONS

Before running real end-to-end recurring payments in **Test Mode** on Razorpay:

1. **Create the Razorpay TEST Plan in Razorpay Dashboard:**
   - Log into [Razorpay Dashboard](https://dashboard.razorpay.com) in **Test Mode**.
   - Navigate to **Subscriptions > Plans > Create Plan**.
   - Set Name: `Re:Focus Monthly Premium`, Period: `Monthly`, Interval: `1`, Amount: `₹99.00`.
   - Copy the generated Plan ID (format: `plan_xxxxxxxxxxxxxx`).

2. **Configure `RAZORPAY_PLAN_ID` in Worker Environment:**
   - Set the `RAZORPAY_PLAN_ID` secret/var in Cloudflare Worker to the Plan ID created in Step 1.
   - Deploy/sync Worker configuration with `npx wrangler deploy`.

3. **Verify Webhook Subscription in Razorpay Dashboard:**
   - In **Settings > Webhooks**, ensure webhook points to: `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`.
   - Ensure the 9 subscription events are selected (`subscription.authenticated`, `subscription.activated`, `subscription.charged`, `subscription.pending`, `subscription.halted`, `subscription.cancelled`, `subscription.completed`, `payment.failed`, `refund.processed`).

*(Note: Live recurring autopay will subsequently require complete Razorpay KYC and banking approval, but Test Mode is unblocked once the Test Plan ID is created and bound to the Worker).*

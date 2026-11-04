# Razorpay Recurring Subscriptions (Test Mode) E2E Readiness Verification

**Date:** 2026-09-19  
**Status:** **READY FOR TEST PAYMENT**  
**Model:** Monthly Recurring Autopay (`recurring_autopay_v2`)  
**Configured Plan:** `plan_TdtgehXIhzQ8U9` (₹79/month)  

---

## 1. Architectural Verification & Security Checklist

### 1.1 Canonical Webhook URL
* **URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`
* **Protocol:** HTTPS (POST only, TLS 1.3 enforced by Cloudflare)
* **Access:** Public server-to-server endpoint protected by HMAC-SHA256 signature verification and sliding window rate-limiting (120 req/60s per IP).

### 1.2 Handled Webhook Events
The Cloudflare Worker (`worker/src/index.js`) processes the following **9 webhook events**:
1. `subscription.authenticated` — Mandate authenticated by customer.
2. `subscription.activated` — First recurring cycle debited and subscription active.
3. `subscription.charged` — Subsequent recurring cycle renewal debited.
4. `subscription.pending` — Renewal charge failure; retry pending (grace period entered).
5. `subscription.halted` — Max retries exhausted; subscription suspended.
6. `subscription.cancelled` — Cancelled at cycle end or immediately.
7. `subscription.completed` — Total billing cycles (120 months) completed.
8. `payment.failed` — Individual debit transaction failure notification.
9. `refund.processed` / `refund.created` — Reversal or refund handled.
*(Also retains backward-compatibility handlers for legacy `payment.captured` / `order.paid`).*

### 1.3 Webhook Secret & Cryptographic Verification
* The Worker expects **one common webhook secret** (`env.RAZORPAY_WEBHOOK_SECRET`) for all incoming webhook events.
* Signature verification checks the `X-Razorpay-Signature` header via HMAC-SHA256 against the raw incoming payload body.
* **Requirement:** The Webhook Secret configured under **Razorpay Dashboard (Test Mode) → Settings → Webhooks** MUST match the secret stored in Cloudflare Worker (`RAZORPAY_WEBHOOK_SECRET`).

### 1.4 TEST Plan Binding
* Worker environment variable `RAZORPAY_PLAN_ID` is set to `plan_TdtgehXIhzQ8U9`.
* `/api/create-subscription` passes `plan_TdtgehXIhzQ8U9` to Razorpay's Subscriptions API (`https://api.razorpay.com/v1/subscriptions`) with:
  * `total_count: 120` (10 years recurring)
  * `quantity: 1`
  * `customer_notify: 1`
  * `notes.firebase_uid: <authenticated_uid>`
* `/api/verify-subscription` validates that the client mandate matches `plan_TdtgehXIhzQ8U9`.

### 1.5 Environment Isolation (TEST vs LIVE)
* **API Keys:** Cloudflare Worker is bound to Razorpay **Test Mode** API Key ID (`rzp_test_...`) and Secret.
* **No Live Credentials:** No live production keys or live plans are present in Worker secrets or configuration.
* **Zero Client Secrets:** Neither the Key Secret, Webhook Secret, nor Plan ID are hardcoded in the Android source code.

### 1.6 Android Client Checkout Pipeline
The end-to-end client flow is fully integrated:
```
Android (SubscriptionBottomSheet)
  └──> RazorpaySubscriptionPurchaseServiceImpl.initiatePurchase(plan)
        └──> Worker POST /api/create-subscription (with Firebase ID Token + App Check)
              └──> Returns { subscriptionId, keyId }
                    └──> Razorpay Android SDK Checkout.open(activity, options)
                          └──> User authorizes mandate (Test Card / UPI Autopay)
                                └──> onPaymentSuccess(paymentId, paymentData)
                                      └──> Worker POST /api/verify-subscription
                                            └──> Validates HMAC & Mandate Status
                                            └──> Authoritative Firestore Write:
                                                  /users/{uid}/subscription/status -> ACTIVE
                                                        └──> Android UI auto-unlocks!
```

---

## 2. Razorpay Dashboard (TEST Mode) Configuration Checklist

Before initiating the test payment from your Android device, verify these settings in your [Razorpay Dashboard](https://dashboard.razorpay.com):

### Step 1: Switch to Test Mode
* Ensure the top/side toggle in Razorpay Dashboard is set to **Test Mode** (yellow/orange badge).

### Step 2: Verify Webhook Configuration
1. Navigate to **Account & Settings** → **Webhooks** (under Website & App Settings).
2. Look for the webhook pointing to:  
   `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`
3. Click **Edit** (or Add New Webhook if not present):
   * **Webhook URL:** `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook`
   * **Secret:** Enter your chosen webhook secret (must match the `RAZORPAY_WEBHOOK_SECRET` stored in Cloudflare Worker).
   * **Alert Email:** (Your operational email).
   * **Active Events Checkbox Selection:**
     - [x] `subscription.authenticated`
     - [x] `subscription.activated`
     - [x] `subscription.charged`
     - [x] `subscription.pending`
     - [x] `subscription.halted`
     - [x] `subscription.cancelled`
     - [x] `subscription.completed`
     - [x] `payment.failed`
     - [x] `refund.processed`
4. Click **Save Webhook**.

---

## 3. End-to-End Test Payment Flow on Android App

### Prerequisites
* Install the debuggable/test APK of Re:Focus on a physical device or emulator with Google Play Services.
* Ensure your Google Account is signed into the app.

### Execution Steps
1. **Launch App & Open Paywall:**
   * Open Re:Focus.
   * Open the Subscription Bottom Sheet / Paywall.
2. **Initiate Subscription:**
   * Tap the **Start Subscription** (or Unlock Premium) button.
   * The app calls Worker `/api/create-subscription` and launches the Razorpay Checkout SDK.
3. **Complete Test Mandate Authorization:**
   * **Payment Method 1: Test Cards (Recommended)**
     * Select **Card**.
     * Card Number: `4111 1111 1111 1111` (or Razorpay standard recurring test card).
     * Expiry: Any future date (e.g., `12/30`).
     * CVV: `123`.
     * OTP: `123456` or click **Success** on the Razorpay simulation screen.
   * **Payment Method 2: Test UPI Autopay**
     * Select **UPI** → Enter `success@razorpay` (or any valid test VPA).
     * Authorize the mandate in the simulator dialog by tapping **Success**.
4. **Observe App Activation:**
   * Razorpay SDK returns `onPaymentSuccess` with `razorpay_payment_id`, `razorpay_subscription_id`, and `razorpay_signature`.
   * App automatically calls `/api/verify-subscription`.
   * Bottom sheet displays **Recurring subscription activated!** and dismisses.
   * Premium features unlock immediately.
5. **Verify in Razorpay Dashboard:**
   * Open **Razorpay Dashboard (Test Mode)** → **Subscriptions**.
   * Verify the subscription shows status: **Active** under Plan `plan_TdtgehXIhzQ8U9`.
   * Open **Webhooks** → View Recent Logs to verify `subscription.authenticated` and `subscription.activated` were delivered with HTTP `200 OK`.

---

## 4. Final Verdict

**READY FOR TEST PAYMENT**

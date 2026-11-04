# Razorpay Test Webhook & Subscription Pipeline Verification

**Date:** 2026-09-19  
**Status:** **READY FOR FIRST TEST SUBSCRIPTION PAYMENT**  
**Model:** Monthly Recurring Autopay (`recurring_autopay_v2`)  
**Configured TEST Plan:** `plan_TdtgehXIhzQ8U9` (₹79/month)  
**Deployed Version ID:** `f9b81c57-ed17-4bf1-8000-48e341517e6f`  

---

## 1. Environment & Architecture Verification

| Item | Verified Value | Status |
|---|---|---|
| **Worker Health** | `{"status":"healthy","service":"Re:Focus Edge Worker","model":"recurring_autopay_v2"}` | ✅ PASS |
| **TEST Plan ID** | `plan_TdtgehXIhzQ8U9` (bound in `wrangler.jsonc` & Worker environment) | ✅ PASS |
| **Credentials Mode** | TEST Mode (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` in Worker secrets) | ✅ PASS |
| **Canonical Webhook URL** | `https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev/api/razorpay-webhook` | ✅ PASS |
| **Webhook Secret** | Synchronized in Cloudflare Worker secrets & validated via HMAC-SHA256 | ✅ PASS |
| **Handled Webhook Events** | 9 subscription/payment/refund events matching Razorpay Dashboard | ✅ PASS |
| **Worker Test Suite** | 17/17 tests passing (HMAC signature, idempotency, lifecycle states) | ✅ PASS |
| **Code & Config Integrity** | Zero unauthorized modifications, no secrets committed | ✅ PASS |

---

## 2. Handled Webhook Events & Alignment

The deployed Cloudflare Worker handles the following 9 events configured in the Razorpay Dashboard:
1. `subscription.authenticated`
2. `subscription.activated`
3. `subscription.charged`
4. `subscription.pending`
5. `subscription.halted`
6. `subscription.cancelled`
7. `subscription.completed`
8. `payment.failed`
9. `refund.processed` (and `refund.created`)

---

## 3. Step-by-Step Android Test Flow (First ₹79/Month Test Subscription)

### Step 1: Open the App
1. Launch the Re:Focus debug/test build on your physical Android device or emulator.
2. Confirm you are logged in with your Google account.

### Step 2: Trigger Paywall
1. Navigate to Settings or attempt to access a premium feature to open the **Subscription Bottom Sheet**.
2. Tap the **Unlock Premium** / **Start Subscription** button.

### Step 3: Authorize Mandate in Razorpay Checkout
The Razorpay Standard Checkout SDK modal will open for the ₹79/month subscription.

* **Option A: Test Card (Recommended)**
  * Select **Card**.
  * Card Number: `4111 1111 1111 1111` (or standard recurring test card).
  * Expiry: Any future date (e.g. `12/30`).
  * CVV: `123`.
  * In the OTP Simulation screen, tap **Success**.

* **Option B: Test UPI Autopay**
  * Select **UPI**.
  * Enter VPA: `success@razorpay`.
  * In the UPI Mandate simulator, tap **Success**.

### Step 4: Verification & Entitlement Unlock
1. The Razorpay SDK returns payment and subscription credentials to the app.
2. The app calls Worker `POST /api/verify-subscription`.
3. The Worker verifies the HMAC signature, confirms the `plan_TdtgehXIhzQ8U9` binding, and writes the `ACTIVE` entitlement directly to `/users/{uid}/subscription/status` in Firestore.
4. The bottom sheet will update to **"Recurring subscription activated!"** and dismiss automatically.
5. All app tracking and limit restrictions are now unlocked.

### Step 5: Dashboard Verification
1. Open **Razorpay Dashboard (Test Mode) → Subscriptions**:
   * Confirm a new subscription entry exists under Plan `plan_TdtgehXIhzQ8U9` with status **Active**.
2. Open **Razorpay Dashboard (Test Mode) → Webhooks → Logs**:
   * Confirm the `subscription.authenticated` and `subscription.activated` events were delivered with **200 OK** response from your Cloudflare Worker.

---

## 4. Final Verdict

**READY FOR FIRST TEST SUBSCRIPTION PAYMENT**

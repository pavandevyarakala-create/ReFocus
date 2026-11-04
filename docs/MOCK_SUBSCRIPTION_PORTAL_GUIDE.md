# ReelTrak — Development Mock Subscription Portal Guide

This guide explains how to access, use, and test the **Development-Only Mock Subscription Portal** implemented in ReelTrak.

---

## 1. How to Enable Development / Mock Mode

- **Automatic Activation**: Mock mode is enabled automatically whenever you run or build a **`debug`** build variant (e.g. `.\gradlew installDebug` or running the app from Android Studio in Debug mode).
- **Condition**: It is tied to `BuildConfig.DEBUG == true`. No manual toggle, build flag, or environment variable is required in debug builds.

---

## 2. Exact Navigation Path to Open the Mock Portal

1. Launch the app and log in with your Google account.
2. From the bottom navigation bar, tap the **Settings** tab (gear icon).
3. Scroll down to the **"DATA & SUPPORT"** section.
4. Tap **"Subscription & Dev Simulator"** (this row is rendered only in debug builds).
5. You are now inside the **Debug Dashboard**, which contains the **Subscription State Simulator (Dev Only)** at the top.

---

## 3. How to Simulate Each Subscription Scenario

Inside the **Subscription State Simulator** card on the Debug Dashboard:

### A. Simulate a Successful Subscription (Annual / Monthly)
- **Action**: Tap **"Simulate Active Pro (Annual)"** or **"Simulate Active Pro (Monthly)"**.
- **Result**: The app writes an active subscription document to Firestore with a 365-day or 30-day future epoch timestamp. The UI updates immediately: `isPremium Active: true`.
- **Alternative (UI Paywall Flow)**: You can also open the normal subscription card from Settings, select Annual/Monthly, and tap `"Start Annual Plan"`. In debug mode, it simulates gateway latency and activates the mock subscription automatically.

### B. Simulate Payment Failure
- **Action**: Tap the red **"Simulate Failed Payment"** button.
- **Result**: Simulates a payment gateway declination. The subscription is not activated, a failure banner is displayed, and the user remains on the free plan (`premium = false`).

### C. Simulate Subscription Cancellation
- **Action**: Tap **"Simulate Cancel"**.
- **Result**: Simulates a user cancelling recurring billing. Status becomes `CANCELLED`, but because paid days remain until `currentPeriodEnd`, `isPremium` remains `true` (grace period).

### D. Simulate Subscription Expiration
- **Action**: Tap **"Simulate Expire"**.
- **Result**: Simulates a subscription whose validity ended yesterday. Status becomes `EXPIRED`, `currentPeriodEnd` is set to `now - 1 day`, and `isPremium` immediately changes to `false`.

### E. Reset the User Back to Free
- **Action**: Tap **"Reset to Free"**.
- **Result**: Cleanses the Firestore subscription document back to `status = NONE`, `planId = null`, `premium = false`.

### F. Force Server Sync / Refresh
- **Action**: Tap **"Sync Server"**.
- **Result**: Forces an explicit fetch against Firestore's server cache to refresh the local snapshot listener.

---

## 4. Firestore Fields Modified After Each Action

All actions update the authenticated user's document at:
```
/users/{uid}/subscription/status
```

| Simulated Action | `subscriptionStatus` | `planId` | `premium` | `currentPeriodStart` | `currentPeriodEnd` |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Active Pro (Annual)** | `"ACTIVE"` | `"plan_annual"` | `true` | `now` | `now + 365 days` |
| **Active Pro (Monthly)** | `"ACTIVE"` | `"plan_monthly"` | `true` | `now` | `now + 30 days` |
| **Cancelled** | `"CANCELLED"` | `"plan_annual"` | `true` | `now - 10 days` | `now + 20 days` |
| **Expired** | `"EXPIRED"` | `"plan_monthly"` | `false` | `now - 60 days` | `now - 1 day` |
| **Reset to Free / Failure** | `"NONE"` | `null` | `false` | `null` | `null` |

---

## 5. Guarantee: Disabled in Release / Production Builds

Mock mode is **100% guaranteed to be disabled** in production/release builds through multiple redundant compiler and runtime guards:

1. **Service Guard (`DevMockSubscriptionPurchaseServiceImpl.kt`)**:
   - `initiatePurchase()` checks `if (!BuildConfig.DEBUG)` and immediately returns an error.
   - `simulateSubscriptionTransition()` checks `if (!BuildConfig.DEBUG)` and returns `Result.failure(IllegalStateException("Simulations are disabled in release builds"))`.
2. **UI Strip Guard (`SettingsScreen.kt`)**:
   - The navigation entry is wrapped in `if (BuildConfig.DEBUG) { SettingsRow("Subscription & Dev Simulator", ...) }`.
   - In release builds, `BuildConfig.DEBUG` is compiled as a constant `false`, allowing R8/ProGuard dead-code elimination to strip the row and debug navigation completely from the production APK.
3. **Firestore Security Rules**:
   - In production, clients cannot write to `/users/{userId}/subscription/{docId}` directly (`allow write: if false;`). Write authority belongs solely to the backend Cloud Functions / Firebase Admin SDK.

# DEBUG Recurring-Subscription Simulator Status & Specification

## 1. Executive Summary

This document details the implementation and verification of the **DEBUG mock recurring-subscription simulator** for **Re:Focus (₹99/Month Autopay)**.

The simulator allows full manual and programmatic end-to-end testing of every state in the monthly subscription lifecycle without interacting with Razorpay or incurring charges.

---

## 2. Security & DEBUG Isolation Guarantees

1. **Strictly DEBUG-Only**:
   - `MockSubscriptionController` and `DevMockSubscriptionPurchaseServiceImpl` are gated by `BuildConfig.DEBUG`.
   - Release builds use `RazorpaySubscriptionPurchaseServiceImpl` and cannot trigger any mock state transitions or write fake subscription data.
2. **Untouched Production Backends**:
   - Cloudflare Worker (`POST /api/create-subscription`, `/api/verify-subscription`, `/api/cancel-subscription`, `/api/ensure-subscription`) remains untouched.
   - Firestore security rules, Firebase authentication, and App Check remain strictly intact.
   - No fake Razorpay Plan ID is hardcoded in production paths.

---

## 3. Simulator States, Transitions & Expected Access Matrix

The simulator directly updates the Firestore `/users/{uid}/subscription/status` document consumed by `SubscriptionRepositoryImpl`, `SubscriptionManager`, and the Android UI.

| Simulator Action / Button | Simulated Cloud Fields | Invariant / Expected `AccessState` | App Locked / Unlocked | UI Indication |
| :--- | :--- | :--- | :--- | :--- |
| **Start 7-Day Trial** | `subscriptionStatus: "NONE"`<br>`trialStart: now`<br>`trialEnd: now + 7d`<br>`premium: false` | `AccessState.TRIAL`<br>`(now < trialEnd)` | **UNLOCKED** | Blue banner: "Free Trial (X days remaining)" |
| **Expire Trial** | `subscriptionStatus: "NONE"`<br>`trialStart: now - 8d`<br>`trialEnd: now - 1h`<br>`premium: false` | `AccessState.LOCKED`<br>`(now > trialEnd)` | **LOCKED** | Full Paywall: "Start 7-Day Free Trial / ₹99/month" |
| **Activate Monthly Autopay (₹99)** | `subscriptionStatus: "ACTIVE"`<br>`planId: "plan_monthly"`<br>`subscriptionId: "sub_mock_..."`<br>`latestPaymentId: "pay_mock_..."`<br>`currentPeriodStart: now`<br>`currentPeriodEnd: now + 30d`<br>`nextBillingDate: now + 30d`<br>`cancelAtPeriodEnd: false`<br>`premium: true` | `AccessState.ACTIVE`<br>`(now < currentPeriodEnd)` | **UNLOCKED** | Green/Premium: "Re:Focus Premium Active — ₹99/month recurring" |
| **Simulate Auto-Renewal (+30 Days)** | `subscriptionStatus: "ACTIVE"`<br>`currentPeriodStart: now`<br>`currentPeriodEnd: now + 30d`<br>`nextBillingDate: now + 30d`<br>`latestPaymentId: "pay_renew_..."` | `AccessState.ACTIVE` | **UNLOCKED** | Period extended by 30 days without interruption |
| **Payment Fail (PENDING Grace)** | `subscriptionStatus: "PENDING"`<br>`currentPeriodEnd: now + 3d`<br>`latestPaymentId: "pay_mock_failed_..."` | `AccessState.ACTIVE`<br>`(now < currentPeriodEnd)` | **UNLOCKED + WARNING** | Orange/Warning Banner: "Payment retry in progress. Access active for 3 days." |
| **Retries Exhausted (HALTED)** | `subscriptionStatus: "HALTED"`<br>`currentPeriodEnd: now - 1d`<br>`premium: false` | `AccessState.LOCKED`<br>`(now > currentPeriodEnd)` | **LOCKED** | Paywall: "Subscription halted due to payment failure. Renew to unlock." |
| **Cancel Autopay (Period Grace)** | `subscriptionStatus: "CANCELLED"`<br>`cancelAtPeriodEnd: true`<br>`currentPeriodEnd: now + 15d`<br>`nextBillingDate: null`<br>`premium: true` | `AccessState.ACTIVE`<br>`(now < currentPeriodEnd)` | **UNLOCKED** | Orange/Amber: "Autopay cancelled. Access active until [15 days remaining]." |
| **Expire Period (EXPIRED)** | `subscriptionStatus: "EXPIRED"`<br>`currentPeriodEnd: now - 1h`<br>`premium: false` | `AccessState.LOCKED`<br>`(now > currentPeriodEnd)` | **LOCKED** | Paywall: "Subscription expired. Re-subscribe for ₹99/month." |
| **Stale `premium=true` Exploit Test** | `subscriptionStatus: "EXPIRED"`<br>`currentPeriodEnd: now - 1h`<br>`premium: true` *(stale boolean)* | `AccessState.LOCKED`<br>*(Timestamp check rejects boolean)* | **LOCKED** | Paywall (Tamper proof) |
| **Reset Mock Subscription** | `subscriptionStatus: "NONE"`<br>`planId: "free"`<br>All timestamps: `null`<br>`premium: false` | `AccessState.LOCKED` | **LOCKED** | Clean initial state |

---

## 4. Debug UI Controls & Dashboard

The Debug Screen ([DebugOverlayScreen.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/debug/ui/DebugOverlayScreen.kt)) provides:

1. **Live State Readout**:
   - `Plan`: Free / Monthly ₹99
   - `Status`: NONE / TRIAL / ACTIVE / PENDING / HALTED / CANCELLED / EXPIRED
   - `AccessState`: TRIAL (Blue), ACTIVE (Green), LOCKED (Red)
   - `App Unlocked`: TRUE / FALSE
   - `Sub ID` & `Latest Pay ID`
   - `Cancel At Period End`: TRUE (Orange badge)
   - `Period Start` & `Period End` (Human-readable dates)
   - `Trial Started`, `Trial Ends`, and live `Trial Countdown`
2. **Simulation Action Buttons**:
   - **Section 1 (Trial)**: `Start 7-Day Trial`, `Expire Trial`
   - **Section 2 (Active Autopay)**: `Activate Monthly Autopay (₹99)`, `Simulate Auto-Renewal (+30 Days)`
   - **Section 3 (Payment Failure & Dunning)**: `Payment Fail (PENDING Grace)`, `Retries Exhausted (HALTED)`
   - **Section 4 (Cancellation & Expiry)**: `Cancel Autopay (Period Grace)`, `Expire Period (EXPIRED)`
   - **Section 5 (Reset & Sync)**: `Reset Mock Subscription`, `Sync Server`

---

## 5. Verification Results

All required verification suites were executed:

### 1. Unit Tests (`./gradlew test`)
- **Command**: `./gradlew test`
- **Result**: **BUILD SUCCESSFUL** (65 actionable tasks, 0 failures)
- **Key Test Cases Verified**:
  - `test active 7-day trial grants TRIAL access state and unlocks app` -> PASS
  - `test expired trial with no paid subscription results in LOCKED access state` -> PASS
  - `test clock rollback cannot extend expired trial when trustedNow is used` -> PASS
  - `test active paid subscription grants ACTIVE access state and unlocks app` -> PASS
  - `test expired paid subscription with expired trial results in LOCKED access state` -> PASS
  - `test cancelled subscription with future periodEnd retains ACTIVE access until expiration` -> PASS
  - `test cancelled subscription after periodEnd results in LOCKED access state` -> PASS
  - `test PENDING status within valid currentPeriodEnd results in ACTIVE accessState with warning` -> PASS
  - `test HALTED status results in LOCKED accessState` -> PASS
  - `test EXPIRED status results in LOCKED accessState` -> PASS
  - `test stale premium boolean cannot bypass expired period or expired trial` -> PASS
  - `test clean reset state results in NONE status and LOCKED accessState` -> PASS

### 2. Android Lint Release (`./gradlew lintRelease`)
- **Command**: `./gradlew lintRelease`
- **Result**: **BUILD SUCCESSFUL** (0 lint errors)

### 3. Debug Build Assembly (`./gradlew assembleDebug`)
- **Command**: `./gradlew assembleDebug`
- **Result**: **BUILD SUCCESSFUL** (APK generated successfully)

# RE:FOCUS — Android Monthly ₹99 Autopay Implementation Status
**Document Version:** 1.0  
**Status:** IMPLEMENTATION & VERIFICATION COMPLETE  
**Target:** Android Client Recurring ₹99/Month Autopay Migration

---

## 1. Files Changed

| Component | File Path | Key Changes |
|---|---|---|
| **Payment Service** | `app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt` | Replaced legacy one-time order creation (`/api/create-order`) with recurring subscription creation (`/api/create-subscription`). Passes `subscription_id` to Razorpay Checkout. Verifies signature via `/api/verify-subscription`. |
| **Domain Models** | `app/src/main/java/com/reeltrak/app/domain/models/SubscriptionInfo.kt` | Added `PENDING` and `HALTED` to `SubscriptionStatus`. Updated `SubscriptionPlan.MONTHLY` display name to `"₹99/Month"`. Extended `SubscriptionInfo` with `cancelAtPeriodEnd`, `latestPaymentId`, and `nextBillingDate`. |
| **Remote DTO** | `app/src/main/java/com/reeltrak/app/data/remote/dto/CloudSubscriptionDto.kt` | Added recurring properties (`cancelAtPeriodEnd`, `nextBillingDate`, `latestPaymentId`, `lastWebhookEventId`). Updated access evaluation (`toDomain`) to grant access for `PENDING` during grace period, enforce `HALTED`/`EXPIRED` locks, and keep `CANCELLED` unlocked until `currentPeriodEnd`. |
| **Repository Layer** | `app/src/main/java/com/reeltrak/app/domain/repository/SubscriptionRepository.kt`<br>`app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt` | Added `cancelSubscription()` suspending function that calls Cloudflare Worker `POST /api/cancel-subscription` to cancel recurring mandates on Razorpay at cycle end. |
| **Manager Layer** | `app/src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt` | Added `cancelSubscription()` pass-through for ViewModels. |
| **UI & Paywall** | `app/src/main/java/com/reeltrak/app/features/settings/ui/components/SubscriptionBottomSheet.kt` | Updated copy to `"₹99 / Month Autopay"`, added mandatory RBI recurring autopay mandate notices, added pending payment failure warning banner, and added "Cancel Autopay" action with confirmation dialog. |
| **Settings Screen** | `app/src/main/java/com/reeltrak/app/features/settings/ui/SettingsScreen.kt`<br>`app/src/main/java/com/reeltrak/app/features/settings/viewmodel/SettingsViewModel.kt` | Wired `cancelSubscription` and `isCancellingSubscription` state flows. |
| **Main Screen** | `app/src/main/java/com/reeltrak/app/features/main/ui/MainScreen.kt` | Wired `cancelSubscription` into the locked app paywall invocation. |
| **Dev Mock Service** | `app/src/main/java/com/reeltrak/app/data/services/DevMockSubscriptionPurchaseServiceImpl.kt` | Added exhaustive `PENDING` and `HALTED` branch handling in mock status simulation. |
| **Unit & Security Tests** | `app/src/test/java/com/reeltrak/app/security/Phase1PaymentSecurityTest.kt` | Updated test suite for recurring subscription activation, `PENDING` grace periods, `HALTED` locks, and `cancelAtPeriodEnd` expiration transitions. |

---

## 2. Old Payment Flow Replaced

| Dimension | Old One-Time Flow | New Recurring Autopay Flow |
|---|---|---|
| **API Endpoint** | `POST /api/create-order` | `POST /api/create-subscription` |
| **Gateway Request** | Orders API (`amount: 9900`) | Subscriptions API (`plan_id: RAZORPAY_PLAN_ID`, `total_count: 120`) |
| **Checkout ID** | `order_id: "order_xxx"` | `subscription_id: "sub_xxx"` |
| **Verification Endpoint** | `POST /api/verify-payment` | `POST /api/verify-subscription` |
| **Verification Signature** | `order_id + "\|" + payment_id` | `payment_id + "\|" + subscription_id` |
| **Terms Copy** | *"₹99 for 28 days / one-time payment"* | *"₹99 / Month • Auto-renews monthly • Cancel anytime"* |

---

## 3. Firebase Access-State Behavior

Access decision in `CloudSubscriptionDto.toDomain(trustedNow)`:

```kotlin
val hasValidPaidEnd = endMillis != null && endMillis > 0L && trustedNow < endMillis
val isPaidActive = (status == SubscriptionStatus.ACTIVE || 
                    status == SubscriptionStatus.PENDING || 
                    status == SubscriptionStatus.CANCELLED) && hasValidPaidEnd

val isTrialActive = trialEndMillis != null && trialEndMillis > 0L && trustedNow < trialEndMillis

val derivedAccessState = when {
    isPaidActive -> AccessState.ACTIVE
    isTrialActive && (status == SubscriptionStatus.NONE || status == SubscriptionStatus.TRIAL) -> AccessState.TRIAL
    else -> AccessState.LOCKED
}
```

- **TRIAL**: `UNLOCKED` while `trustedNow < trialEnd`; `LOCKED` once expired.
- **ACTIVE**: `UNLOCKED` while `trustedNow < currentPeriodEnd`.
- **PENDING**: `UNLOCKED` during grace period as long as `trustedNow < currentPeriodEnd`. Non-blocking warning banner displayed.
- **HALTED**: Immediately `LOCKED`.
- **CANCELLED**: `UNLOCKED` until `currentPeriodEnd` passes (end-of-cycle cancellation grace), then immediately `LOCKED`.
- **EXPIRED**: Immediately `LOCKED`.
- **Stale `premium == true` Safeguard**: Never grants access if trusted time has passed `currentPeriodEnd` or if status is `HALTED`/`EXPIRED`.

---

## 4. Cancellation Behavior

1. User taps **Cancel Autopay** in `SubscriptionBottomSheet`.
2. A confirmation dialog confirms that future renewals will cease while current paid cycle remains active.
3. App calls `SettingsViewModel.cancelSubscription()` $\to$ Worker `POST /api/cancel-subscription`.
4. Worker calls Razorpay `POST /v1/subscriptions/{id}/cancel` with `cancel_at_cycle_end: 1` and updates Firestore `cancelAtPeriodEnd: true`.
5. Android app reflects cancelled status immediately while preserving full premium access until `currentPeriodEnd`.

---

## 5. Payment Failure Behavior

- **`PENDING` State**: Displays an in-app amber alert banner: *"Your automatic monthly payment could not be completed. Please update your payment method to avoid losing access."* Access remains active during the backend-managed grace period.
- **`HALTED` State**: Retries exhausted. App transitions to `LOCKED` with an error banner prompt to resubscribe.

---

## 6. Build & Test Verification

| Command | Result | Notes |
|---|---|---|
| `./gradlew test` | **BUILD SUCCESSFUL** | All unit & security test suites pass (including `Phase1PaymentSecurityTest`, `SubscriptionModelTest`, `Phase3BackendEdgeSecurityTest`, `Phase7PerformanceRemediationTest`). |
| `./gradlew assembleDebug` | **BUILD SUCCESSFUL** | Clean compilation of all debug artifacts. |

---

## 7. Remaining Work (Next Steps)

1. **DEBUG Mock Recurring-Subscription Simulator**: Update the developer debug portal in `DevMockSubscriptionPurchaseServiceImpl.kt` and `SubscriptionDebugView.kt` to simulate recurring monthly cycle charges, grace periods, dunning halts, and end-of-cycle cancellations.
2. **Razorpay Dashboard & KYC Configuration**: Create the live/test Razorpay Plan (`₹99/month`) and configure the Subscriptions webhook events.

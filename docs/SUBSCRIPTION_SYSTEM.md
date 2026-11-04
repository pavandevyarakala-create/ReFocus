# ReelTrak — Complete Subscription System & Audit Documentation

This document provides a comprehensive technical overview of the subscription system architecture, data models, security rules, mock simulation workflows, and future Razorpay backend integration guidelines for ReelTrak.

---

## 1. System Architecture & Overview

The subscription subsystem is architected following Clean Architecture & MVVM principles, ensuring complete decoupling between payment gateways, state management, and business logic:

```
[UI Screens / ViewModels]
          │ (observes isPremium / subscriptionInfo)
          ▼
[SubscriptionManager] (@Singleton Single Source of Truth)
          │
          ▼
[SubscriptionRepository]
          │
          ▼
[SubscriptionRepositoryImpl]
          │
          ├─► Listens to FirebaseAuth (UID changes)
          │
          └─► Attaches Real-time SnapshotListener to Firestore
              Path: /users/{uid}/subscription/status
```

---

## 2. Firestore Structure & Data Model

### Document Path
```
/users/{uid}/subscription/status
```

### JSON Schema
```json
{
  "subscriptionStatus": "ACTIVE",
  "planId": "plan_annual",
  "subscriptionId": "sub_razorpay_998877",
  "currentPeriodStart": 1757000000000,
  "currentPeriodEnd": 1788536000000,
  "premium": true
}
```

### Supported Status Enums
- `NONE`: Default unsubscribed / free tier.
- `ACTIVE`: Active paid subscription with unexpired `currentPeriodEnd`.
- `CANCELLED`: Cancelled renewal; retains access until `currentPeriodEnd` passes.
- `EXPIRED`: Timestamp has expired; access strictly disabled.
- `PAST_DUE`: Payment issue; access disabled.

---

## 3. Firestore Security Rules

Defined in `firestore.rules`:
- **Read Access**: Authenticated users can only read their own subscription document:
  ```javascript
  match /users/{userId}/subscription/{documentId} {
    allow read: if request.auth != null && request.auth.uid == userId;
    allow write: if false; // Clients CANNOT write subscription data directly
  }
  ```
- **Write Access**: Disallowed for all Android client operations (`allow write: if false;`). Write authority is reserved exclusively for the trusted Firebase Admin SDK (Cloud Functions).
- **Profile Tamper Protection**: Users cannot write `premium` or `subscriptionStatus` fields on root `/users/{userId}`.

---

## 4. Key Classes & File Inventory

| Layer / Component | File Path | Description |
| :--- | :--- | :--- |
| **Domain Models** | `app/src/main/java/com/reeltrak/app/domain/models/SubscriptionInfo.kt` | Enums (`SubscriptionStatus`, `SubscriptionPlan`), `SubscriptionInfo`, and `SubscriptionState` (sealed interface). |
| **Domain Repository** | `app/src/main/java/com/reeltrak/app/domain/repository/SubscriptionRepository.kt` | Reactive repository interface (`isPremium`, `subscriptionInfo`, `refreshSubscription()`). |
| **Central Manager** | `app/src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt` | App-wide singleton exposing `StateFlow`s and synchronous helpers for ViewModels/Services. |
| **Purchase Contract** | `app/src/main/java/com/reeltrak/app/domain/services/SubscriptionPurchaseService.kt` | Interface defining checkout initiation and gateway state. |
| **Remote DTO** | `app/src/main/java/com/reeltrak/app/data/remote/dto/CloudSubscriptionDto.kt` | Firestore mapping and date validation logic (`toDomain()`). |
| **Data Repository** | `app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt` | Auth listener and Firestore real-time snapshot connection. |
| **Dev Mock Service** | `app/src/main/java/com/reeltrak/app/data/services/DevMockSubscriptionPurchaseServiceImpl.kt` | Development simulator (guarded by `BuildConfig.DEBUG`). |
| **Dependency Injection** | `app/src/main/java/com/reeltrak/app/core/di/PreferencesModule.kt` | Hilt DI module binding repositories and services. |
| **Settings UI & Paywall** | `app/src/main/java/com/reeltrak/app/features/settings/ui/components/SubscriptionBottomSheet.kt` | Professional paywall UI with monthly/annual selection and dynamic status cards. |
| **Security Rules** | `firestore.rules` & `firebase.json` | Server-side security and isolation enforcement. |

---

## 5. Development Simulator Guide (Debug Builds Only)

In `debug` builds, the **Subscription & Dev Simulator** is accessible from **Settings**:
- **Simulate Active Pro (Annual / Monthly)**: Writes an active subscription with valid 365/30-day epoch timestamps.
- **Simulate Failed Payment**: Emits an error without granting premium access.
- **Simulate Cancellation**: Marks status as `CANCELLED` while preserving access until `currentPeriodEnd`.
- **Simulate Expiration**: Sets `currentPeriodEnd` to the past and disables premium.
- **Reset to Free**: Reverts user to `FREE_DEFAULT`.

> [!NOTE]
> All simulation endpoints check `BuildConfig.DEBUG`. In `release` builds, all mock methods fail-safe and dev UI entries are stripped.

---

## 6. Future Razorpay Backend Integration Steps

When Razorpay KYC is finalized:
1. **Cloud Functions**:
   - Create `createRazorpaySubscription` callable function to return Order ID / Subscription ID.
   - Create Webhook Handler verifying Razorpay signature and writing to `/users/{uid}/subscription/status` via Firebase Admin SDK.
2. **Android Client**:
   - Implement `RazorpaySubscriptionPurchaseServiceImpl` implementing `SubscriptionPurchaseService`.
   - Update `PreferencesModule.kt` to bind the new implementation.
   - Zero changes required in UI screens or ViewModels.

---

## 7. Audit Checklist Summary

- [x] Every subscription is tied to the authenticated Firebase UID.
- [x] Premium status is centralized through `SubscriptionManager`.
- [x] Client-side direct writes to subscription fields are blocked by security rules.
- [x] State persists across app restarts and auth changes.
- [x] Multi-tenant cross-user access is blocked.
- [x] All lifecycle states (`FREE`, `ACTIVE`, `CANCELLED`, `EXPIRED`, `PAST_DUE`) are handled safely.
- [x] No payment secrets or API keys are stored in the client app.
- [x] Automated unit tests (`SubscriptionModelTest`) and release build (`assembleRelease`) passed.

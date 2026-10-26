# ReelTrak Subscription System Architecture & Specification

## 1. Executive Summary

This document provides the complete architectural and operational specification for the ReelTrak subscription and premium system. The architecture is designed to seamlessly integrate with **Firebase Authentication**, **Cloud Firestore**, and a future backend webhook orchestrator for **Razorpay** payments.

---

## 2. Core Architecture & Design Principles

```
[Android Client (ReelTrak)]
      │
      ├── 1. Reads & Observes Real-Time Status ──> [SubscriptionManager]
      │                                                   │
      │                                                   ▼
      │                                         [SubscriptionRepository]
      │                                                   │ (Firestore Snapshot Listener)
      │                                                   ▼
      │                                         [/users/{uid}/subscription/status]
      │                                                   ▲
      │                                                   │ (Read-only for Client)
      │                                                   │ (Written only by Backend / Webhook)
      │                                                   │
      └── 2. Initiates Checkout Flow ──> [SubscriptionPurchaseService]
                                                  │
                                                  ▼
                                      [Razorpay Android SDK (Future)]
                                                  │
                                                  ▼
                                          [Razorpay Server]
                                                  │
                                                  ▼ (Webhook: payment.captured / subscription.charged)
                                      [Firebase Cloud Function]
                                                  │ (Verifies HMAC SHA256 Signature)
                                                  ▼
                                      [Firestore /users/{uid}/subscription/status]
```

### Key Architectural Pillars:
1. **Single Source of Truth**: All app screens and services query [SubscriptionManager](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/domain/managers/SubscriptionManager.kt) rather than implementing ad-hoc premium checks.
2. **Zero Client Authority**: The Android app **never** grants itself premium privileges directly. All subscription writes in production are strictly forbidden by [firestore.rules](file:///c:/Users/pavan/OneDrive/Documents/REEL/firestore.rules).
3. **Multi-Tenant UID Isolation**: All subscription ownership is strictly bound to the authenticated user's Firebase UID (`/users/{uid}/subscription/status`).
4. **Resilient Local & Offline Behavior**: Offline caching via Firestore guarantees uninterrupted access for active members even when disconnected, while expired timestamp evaluation protects against offline tampering.

---

## 3. Data Models & Firestore Schema

### Firestore Document Path:
```
/users/{userId}/subscription/status
```

### Document Schema:
```json
{
  "subscriptionStatus": "ACTIVE",
  "planId": "plan_annual",
  "subscriptionId": "sub_123456789",
  "currentPeriodStart": 1757000000000,
  "currentPeriodEnd": 1788536000000,
  "premium": true
}
```

### Status Enum & Lifecycle State Machine:
- **`NONE`**: Default state for new or non-subscribed users (`isPremium = false`).
- **`ACTIVE`**: Paid subscription active (`isPremium = true` if `currentPeriodEnd > now`).
- **`CANCELLED`**: User cancelled auto-renewal. Premium access remains active until `currentPeriodEnd` passes (grace period).
- **`EXPIRED`**: Period ended without renewal (`isPremium = false`).
- **`PAST_DUE`**: Payment retry in progress (`isPremium = false`).

---

## 4. Firestore Security Rules

Located at [firestore.rules](file:///c:/Users/pavan/OneDrive/Documents/REEL/firestore.rules):

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function isAuthenticated() {
      return request.auth != null;
    }

    function isOwner(userId) {
      return isAuthenticated() && request.auth.uid == userId;
    }

    match /users/{userId} {
      allow read: if isOwner(userId);
      
      // Prevent client writes to root subscription keys
      allow create, update: if isOwner(userId) 
        && !request.resource.data.keys().hasAny([
          'premium', 'subscriptionStatus', 'subscriptionId', 
          'currentPeriodStart', 'currentPeriodEnd', 'planId'
        ]);
        
      allow delete: if false;

      match /daily_data/{dateString} {
        allow read, write: if isOwner(userId);
      }

      // Read-only for authenticated user; writes restricted to Server / Admin SDK
      match /subscription/{documentId} {
        allow read: if isOwner(userId);
        allow write: if false;
      }
    }

    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

---

## 5. Development & Mock Testing System

To enable end-to-end testing before Razorpay KYC and webhook backend deployment:

- **Mock Controller**: [MockSubscriptionController.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/domain/services/MockSubscriptionController.kt)
- **Implementation**: [DevMockSubscriptionPurchaseServiceImpl.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/DevMockSubscriptionPurchaseServiceImpl.kt)
- **Safety Guard**: All mock simulation writes and transitions are wrapped in `if (BuildConfig.DEBUG)`. In release builds (`assembleRelease`), simulations are strictly disabled and stripped.
- **Debug Simulator UI**: Accessible from Settings in debug builds to simulate:
  1. Active Pro (Annual & Monthly)
  2. Failed Payment
  3. Subscription Cancellation
  4. Expiration
  5. Reset to Free Tier
  6. Server Sync / Cache Refresh

---

## 6. How to Connect Future Features to Premium

### In Jetpack Compose Screens:
```kotlin
@Composable
fun FeatureScreen(viewModel: FeatureViewModel = hiltViewModel()) {
    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()

    if (isPremium) {
        PremiumFeatureContent()
    } else {
        LockedFeatureCard(
            title = "Pro Feature",
            onUpgradeClick = { /* Open SubscriptionBottomSheet */ }
        )
    }
}
```

### In ViewModels:
```kotlin
@HiltViewModel
class FeatureViewModel @Inject constructor(
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {
    val isPremium: StateFlow<Boolean> = subscriptionManager.isPremium
    val subscriptionInfo: StateFlow<SubscriptionInfo> = subscriptionManager.subscriptionInfo
}
```

---

## 7. Razorpay Integration Checklist (When Ready)

1. [ ] Deploy Firebase Cloud Function `createRazorpayOrder` to generate order IDs.
2. [ ] Deploy Cloud Function webhook handler with HMAC SHA256 signature verification.
3. [ ] Add Razorpay Android SDK dependency (`com.razorpay:checkout:1.6.38`).
4. [ ] Implement `RazorpaySubscriptionPurchaseServiceImpl` conforming to `SubscriptionPurchaseService`.
5. [ ] Update [PreferencesModule.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/di/PreferencesModule.kt) to bind `RazorpaySubscriptionPurchaseServiceImpl`.

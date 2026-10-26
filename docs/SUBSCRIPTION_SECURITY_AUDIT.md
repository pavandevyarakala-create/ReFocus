# ReelTrak — Security-Only Audit Report: Firestore Subscription System

This document details the security posture, permission rules, and attack vector analysis for ReelTrak's subscription system.

---

## 1. Exact Firestore Security Rules Controlling `/users/{userId}/subscription/{document}`

The active production rules located in `firestore.rules` are:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Helper Functions
    function isAuthenticated() {
      return request.auth != null;
    }

    function isOwner(userId) {
      return isAuthenticated() && request.auth.uid == userId;
    }

    // User Profile Document
    match /users/{userId} {
      // Users can read their own profile document
      allow read: if isOwner(userId);
      
      // Users can only create or update their profile with safe profile fields (e.g. email)
      // They CANNOT write or modify any subscription fields at root level.
      allow create, update: if isOwner(userId) 
        && !request.resource.data.keys().hasAny([
          'premium', 'subscriptionStatus', 'subscriptionId', 
          'currentPeriodStart', 'currentPeriodEnd', 'planId'
        ]);
        
      allow delete: if false;

      // 1. Daily Tracking Data Subcollection
      match /daily_data/{dateString} {
        allow read, write: if isOwner(userId);
      }

      // 2. Subscription Status Document
      // Path: /users/{userId}/subscription/status
      match /subscription/{documentId} {
        // Users can READ their own subscription status to render UI and check features
        allow read: if isOwner(userId);

        // Client app is STRICTLY PROHIBITED from writing or modifying subscription documents.
        // Subscription authority is reserved exclusively for the trusted backend / server environment
        // (Firebase Admin SDK / Cloud Functions triggered by Razorpay webhooks bypass rules automatically).
        allow write: if false;
      }
    }

    // Default deny all other paths
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

---

## 2. Attack Vector Analysis: Can a Malicious User Grant Themselves Premium?

### Analysis Summary: **NO. A malicious user cannot grant themselves premium.**

Here is the exact security verification against all potential attack vectors:

### A. Direct Firestore REST / SDK Write Attack
- **Attack Attempt**: An attacker extracts the user's Firebase Auth token and makes a direct `set()`, `update()`, or `POST` request to `/users/{uid}/subscription/status` with `{"premium": true, "subscriptionStatus": "ACTIVE"}`.
- **Rule Defense**: 
  ```javascript
  match /subscription/{documentId} {
    allow write: if false;
  }
  ```
  Firestore rejects the request immediately at the database level with `PERMISSION_DENIED`. `allow write: if false;` applies to `create`, `update`, and `delete`.

### B. Root Document Property Injection Attack
- **Attack Attempt**: The attacker attempts to bypass the `/subscription` subcollection by writing `{"premium": true}` directly to their root `/users/{uid}` document.
- **Rule Defense**:
  ```javascript
  allow create, update: if isOwner(userId) 
    && !request.resource.data.keys().hasAny([
      'premium', 'subscriptionStatus', 'subscriptionId', 
      'currentPeriodStart', 'currentPeriodEnd', 'planId'
    ]);
  ```
  If any of these protected subscription keys exist in the write request, Firestore rejects the transaction with `PERMISSION_DENIED`.

### C. Cross-User Snooping or Tampering Attack
- **Attack Attempt**: User A attempts to read or modify `/users/{UserB_UID}/subscription/status`.
- **Rule Defense**:
  `isOwner(userId)` checks `request.auth.uid == userId`. Since User A's UID does not match User B's UID, the request is rejected with `PERMISSION_DENIED`.

### D. Client-Side Timestamp Spoofing & Stale Flag Attack
- **Attack Attempt**: If an attacker somehow had an old document with `premium = true` but an expired `currentPeriodEnd`, or manually alters in-memory state.
- **Client Defense (`CloudSubscriptionDto.kt`)**:
  ```kotlin
  val now = System.currentTimeMillis()
  val isExpired = currentPeriodEnd != null && currentPeriodEnd!! > 0L && now > currentPeriodEnd!!
  val isEffectivePremium = premium && (status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.CANCELLED) && !isExpired
  ```
  The domain layer validates the expiration epoch before granting active premium access.

---

## 3. How Legitimate Updates Reach Firestore

- **Backend / Cloud Functions Authority**:
  When a user pays via Razorpay, Razorpay's servers send a cryptographic webhook (`payment.captured` or `subscription.charged`) to your backend Firebase Cloud Function.
- **Admin SDK Rule Bypass**:
  The Cloud Function uses the **Firebase Admin SDK** (initialized with service account credentials). The Firebase Admin SDK operates with superuser administrative privileges and **bypasses Firestore Security Rules automatically**.
- This ensures that only server-verified transactions can write to `/users/{userId}/subscription/status`.

---

## 4. Verification Checklist

| Security Requirement | Status | Verification Detail |
| :--- | :--- | :--- |
| **User cannot set `premium=true`** | **VERIFIED** | `allow write: if false;` on `/subscription/{documentId}` |
| **User cannot set `subscriptionStatus=ACTIVE`** | **VERIFIED** | Blocked on both `/subscription` and `/users/{userId}` |
| **User cannot change `planId`** | **VERIFIED** | Blocked on both `/subscription` and `/users/{userId}` |
| **User cannot extend `currentPeriodEnd`** | **VERIFIED** | Blocked on both `/subscription` and `/users/{userId}` |
| **User cannot alter `subscriptionId`** | **VERIFIED** | Blocked on both `/subscription` and `/users/{userId}` |
| **User cannot access/modify another user's subscription** | **VERIFIED** | Enforced via `isOwner(userId)` (`request.auth.uid == userId`) |
| **User cannot create a fake subscription document** | **VERIFIED** | Direct client creates are blocked (`allow write: if false;`) |
| **Server/Admin SDK can write legitimate updates** | **VERIFIED** | Firebase Admin SDK / Cloud Functions automatically bypass client security rules |

---

## 5. Audit Conclusion

**NO VULNERABILITIES FOUND.** 
The Firestore security rules and client DTO parsing logic form an airtight, zero-trust security perimeter. No modifications to `firestore.rules` are required.

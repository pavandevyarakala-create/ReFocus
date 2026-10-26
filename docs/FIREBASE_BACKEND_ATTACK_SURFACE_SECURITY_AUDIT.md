# STEP 8 — FIREBASE SECURITY RULES, CLOUD FUNCTIONS & BACKEND ATTACK-SURFACE AUDIT

**Target Application:** Re:Focus Firebase, Cloudflare Worker & Legacy Backend Infrastructure  
**Audit Scope:** `firestore.rules`, `firebase.json`, `functions/index.js`, `server.js`, `worker/src/index.js`, Firebase Storage, App Check, IAM Service Account Permissions, Input Validation, and Edge Attack Surface.  
**Audit Date:** September 2026  
**Status:** Audit Only — No code, configuration, or database rules modified.

---

## 1. Executive Summary

This comprehensive security assessment audited the complete backend and cloud surface of the Re:Focus ecosystem. The audit evaluated Firebase Firestore security rules, Firebase Cloud Functions (`functions/`), the Express mock/local server (`server.js`), the production Cloudflare Worker edge backend (`worker/src/index.js`), Firebase Storage usage, Service Account IAM roles, and bot protection.

### Key Positive Findings:
1. **Strict User Ownership in Firestore Rules:** `firestore.rules` enforces `request.auth.uid == userId` for all user-owned collections (`/users/{userId}`, `/users/{userId}/daily_data/{dateString}`). Default-deny (`allow read, write: if false;`) is applied to all other paths.
2. **Server-Exclusive Entitlement & Master Lock:** The client application is strictly prohibited from writing or mutating `/users/{userId}/subscription/{documentId}` and `/users/{userId}/account/{documentId}` (`allow write: if false;`).
3. **Edge Cryptographic Signature & JWT Verification:** Cloudflare Worker and Cloud Functions verify RS256 Firebase ID tokens directly against Google's public JWKS certificates and compute constant-time HMAC-SHA256 signatures for payment validation.

### Critical Vulnerabilities & Gaps Identified:
1. **Hardcoded Secrets in Legacy Server / Functions Files (`BE-01`):** `functions/index.js:L28` and `functions/index.js:L36` contain hardcoded Razorpay test secret keys (`MvcuJz39QJsQXGf0cPSvfgib`) and key IDs as fallbacks.
2. **Dual-Backend Codebase Ambiguity (`BE-02`):** The repository maintains three backend entry points: `worker/src/index.js` (Active Cloudflare Worker), `functions/index.js` (Firebase Cloud Functions), and `server.js` (Express backend). `SubscriptionRepositoryImpl.kt` targets the Cloudflare Worker URL (`https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`).
3. **Absence of Firebase App Check (`BE-03`):** No Firebase App Check (Play Integrity on Android) is configured, allowing direct, automated script calls against Firestore and Firebase Auth using public project API keys.
4. **Permissive CORS & Missing Rate Limiting on Edge Worker (`BE-04`):** All endpoints return `Access-Control-Allow-Origin: *` and lack per-IP/per-UID rate limits.

---

## 2. Backend Architecture Overview

```
                      ┌────────────────────────────────────────┐
                      │             Android Client             │
                      └───────────────────┬────────────────────┘
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  │                                               │
                  ▼ (Direct Firestore SDK)                        ▼ (HTTP REST API)
    ┌───────────────────────────┐                   ┌───────────────────────────┐
    │      Cloud Firestore      │                   │ Cloudflare Worker (Edge)  │
    │  (Guarded by Security     │                   │  (worker/src/index.js)    │
    │          Rules)           │                   └─────────────┬─────────────┘
    └─────────────▲─────────────┘                                 │
                  │                                               │
                  └────────(Service Account REST API)─────────────┘
                                  (Bearer OAuth2)
```

---

## 3. Firestore Security Rules Deep Inspection (`firestore.rules`)

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

    // User Profile Document
    match /users/{userId} {
      allow read: if isOwner(userId);
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
      match /subscription/{documentId} {
        allow read: if isOwner(userId);
        allow write: if false; // STRICT: Only Backend Service Account can write
      }

      // 3. Master Account Access Control Document
      match /account/{documentId} {
        allow read: if isOwner(userId);
        allow write: if false; // STRICT: Only Admin can write
      }
    }

    // Default deny all other paths
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

### Path-by-Path Security Matrix:

| Collection Path | Read Rule | Write / Update Rule | Delete Rule | Security Evaluation |
| :--- | :--- | :--- | :--- | :--- |
| `/users/{userId}` | `isOwner(userId)` | `isOwner(userId)` (Blacklists subscription keys) | `allow delete: if false;` | **Secure**: Prevents field-injection of subscription/entitlement fields. |
| `/users/{userId}/daily_data/{date}`| `isOwner(userId)` | `isOwner(userId)` | Not specified (Defaults to false) | **Secure**: Strict user-level data isolation. |
| `/users/{userId}/subscription/{doc}`| `isOwner(userId)`| `allow write: if false;` | `allow delete: if false;` | **Secure**: Client writes are completely prohibited. |
| `/users/{userId}/account/{doc}` | `isOwner(userId)` | `allow write: if false;` | `allow delete: if false;` | **Secure**: Client cannot modify account restrictions. |
| Wildcard `/{document=**}` | `false` | `false` | `false` | **Secure**: Default-deny protects undeclared collections. |

---

## 4. Firebase Storage Assessment

- **Usage Analysis:** Comprehensive grep searches across the repository confirmed that **Firebase Storage SDK is NOT utilized by the Android client or backend**.
- **`firebase.json` Configuration:** Does not declare a `storage` ruleset or target bucket.
- **Bucket in `google-services.json`:** `storage_bucket: "refocus-504119.firebasestorage.app"` is provisioned by default by Firebase Console, but unused.
- **Verdict:** **No Attack Surface**.

---

## 5. Cloud Functions & Legacy Backend Assessment

### A. Firebase Cloud Functions (`functions/index.js`)
- **Status:** Contains implementations of `createRazorpayOrder` and `verifyRazorpayPayment` using Firebase Functions v2 (`onRequest`).
- **Critical Vulnerability (`BE-01`):** `getKeySecret()` falls back to a plaintext test secret key string: `process.env.RAZORPAY_KEY_SECRET || "MvcuJz39QJsQXGf0cPSvfgib"`.
- **Deployment Status:** Inactive in current Android production flow. Android client targets Cloudflare Worker URL.

### B. Express Server (`server.js`)
- **Status:** Local Express backend designed for development/emulator testing.
- **Authentication:** Middleware `authenticateFirebaseUser` correctly verifies ID tokens using Firebase Admin SDK.

---

## 6. Cloudflare Worker Deep Endpoint Audit (`worker/src/index.js`)

| Endpoint | Method | Auth Required | Validation & Input Handling | External Calls | Threat / Risk Analysis |
| :--- | :---: | :---: | :--- | :--- | :--- |
| `POST /api/ensure-subscription` | `POST` | `Bearer <JWT>` | Checks if subscription document exists in Firestore; initializes 7-day trial if missing. | Firestore REST GET & PATCH | **Low Risk**: Idempotent. Only initializes if document is missing. |
| `POST /api/create-order` | `POST` | `Bearer <JWT>` | Validates `planId === "plan_monthly"`. Hardcodes price to 9900 paise INR. | Razorpay Orders API (`POST /v1/orders`) | **Medium Risk**: Lacks rate limiting; order creation spam possible. |
| `POST /api/verify-payment` | `POST` | `Bearer <JWT>` | Validates `order_id`, `payment_id`, `signature`. Constant-time HMAC check + status query (`captured`). | Razorpay Payments API + Firestore REST | **Medium Risk**: Lacks rate limiting. Webhook absence (`PAY-01`). |
| `GET /` / `GET /health` | `GET` | None | None | None | **Safe**: Public health check. |

---

## 7. Service Account & IAM Security

- **Credential Formulation:** The Cloudflare Worker uses `FIREBASE_SERVICE_ACCOUNT_KEY` environment secret containing the service account JSON.
- **Token Exchange:** Implements RFC 7523 JWT Bearer assertion signed via Web Crypto Subtle API (`RSASSA-PKCS1-v1_5` with SHA-256) targeting `https://oauth2.googleapis.com/token`.
- **Scope Requested:** `https://www.googleapis.com/auth/datastore`.
- **Access Scope Evaluation:** The service account has full read/write access to Firestore. Because all Worker database operations target `/users/${uid}/subscription/status` (where `uid` is derived from verified JWT `payload.sub`), path traversal or cross-user hijacking from request parameters is prevented.

---

## 8. App Check & Bot Protection Assessment

- **Current State:** **ABSENT**.
- **Impact:** While endpoints require valid Firebase ID tokens, an authenticated attacker can automate requests using headless scripts or curl without running the official Android app binary.
- **Recommended Control:** Enable Firebase App Check with **Play Integrity** attestation on Android and verify App Check tokens at the edge / Firestore rule layer.

---

## 9. HTTP Security Headers & Hardening

| Security Header | Status in Worker Responses | Assessment & Remediation |
| :--- | :---: | :--- |
| `Content-Type` | `application/json; charset=utf-8` | **Configured** |
| `Access-Control-Allow-Origin` | `*` (Wildcard) | **Permissive (`BE-04`)**: Restrict to specific origins or remove for mobile endpoints. |
| `X-Content-Type-Options` | Missing | Add `nosniff`. |
| `X-Frame-Options` | Missing | Add `DENY` (relevant for web hosting). |
| `Strict-Transport-Security` | Enforced by Cloudflare Edge | **Configured** |

---

## 10. Detailed Findings

### Finding ID: BE-01 — Hardcoded Test Secret in Legacy Functions Code
- **Severity:** `MEDIUM`
- **Component:** `functions/index.js`
- **File Path:** `functions/index.js#L28, #L36`
- **Current Behavior:** Code falls back to hardcoded string `"MvcuJz39QJsQXGf0cPSvfgib"` if secret resolution fails.
- **Security Impact:** Credential leakage in source repository.
- **Remediation:** Remove fallback string and require mandatory secret resolution via `defineSecret`.

---

### Finding ID: BE-02 — Multi-Backend Redundancy & Deployment Ambiguity
- **Severity:** `LOW`
- **Component:** Backend Infrastructure
- **File Paths:** `worker/src/index.js`, `functions/index.js`, `server.js`
- **Current Behavior:** Three independent backend codebases exist in the repo.
- **Security Impact:** Potential drift in security patches between Cloudflare Worker and Firebase Functions.
- **Remediation:** Archive or deprecate `functions/` and `server.js` if Cloudflare Worker is the sole production runtime.

---

### Finding ID: BE-03 — Missing Firebase App Check Attestation
- **Severity:** `MEDIUM`
- **Component:** Firebase Security & Abuse Prevention
- **File Path:** Project-wide
- **Current Behavior:** App Check is not enabled.
- **Security Impact:** Authenticated users can script requests to backend endpoints and Firestore directly.
- **Remediation:** Configure Firebase App Check with Google Play Integrity.

---

### Finding ID: BE-04 — Permissive Wildcard CORS & Missing Rate Limits
- **Severity:** `LOW`
- **Component:** Cloudflare Worker
- **File Path:** `worker/src/index.js#L26`
- **Current Behavior:** Returns `Access-Control-Allow-Origin: *` without request rate limiting.
- **Security Impact:** Automated traffic flooding against `/api/create-order`.
- **Remediation:** Implement IP/UID rate limiting and restrict CORS origins.

---

## 11. Prioritized Remediation Order

1. **P1 — Remove Hardcoded Test Credentials in Legacy Files (`BE-01`):** Purge hardcoded fallback secrets from `functions/index.js`.
2. **P2 — Implement Edge Rate Limiting & Restrict CORS (`BE-04`):** Protect payment and trial endpoints from automated spam.
3. **P3 — Enable Firebase App Check with Play Integrity (`BE-03`):** Prevent automated bot invocations against Firebase and backend APIs.
4. **P4 — Unify Backend Architecture (`BE-02`):** Formally deprecate legacy functions to avoid configuration drift.

---

RECOMMENDED NEXT AUDIT STEP:
STEP 9 — COMPREHENSIVE VULNERABILITY CONSOLIDATION, THREAT MODEL & FINAL REMEDIATION ROADMAP

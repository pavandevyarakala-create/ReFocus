# Phase 7: Production Deployment & Release Configuration Verification Report

## 1. Executive Summary

This report performs a comprehensive, read-only deployment-readiness audit of the **Re:Focus Android application, Cloudflare Worker backend, Razorpay payment gateway integration, Firebase authentication, and legal/privacy documentation**.

### Primary Deployment Findings:
1. **Core Payment Flow: 100% Plan-Driven (₹79/Month):** The production subscription flow is plan-driven via `RAZORPAY_PLAN_ID = "plan_TdtgehXIhzQ8U9"` on Razorpay Recurring Autopay. The active Android client UI ([SubscriptionBottomSheet.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/settings/ui/components/SubscriptionBottomSheet.kt) and [LockedAppScreen.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/subscription/ui/LockedAppScreen.kt)) correctly displays and charges **₹79/month**.
2. **Pricing Discrepancy in Marketing/Landing Page:** The hosted HTML files ([public/index.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/index.html), [public/terms.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/terms.html), [public/privacy.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/privacy.html)) still contain legacy placeholder text referencing `PREMIUM_PRICE: "₹99"`.
3. **App Check Status:** Configured in `"audit"` mode in [wrangler.jsonc](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/wrangler.jsonc). Safe for rollout, but must not be switched to `"enforced"` until production Play Integrity attestation is registered in the Firebase Console.
4. **Release Signing Security:** Release build signing in [build.gradle.kts](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts) is strictly guarded (`signingConfig = null` if release credentials are not provided via environment variables, completely preventing accidental debug-keystore releases).

---

## 2. Cloudflare Worker Deployment Configuration

| Component / Variable | Type / State | Current Value / Setting | Verification Notes & Required Actions |
| :--- | :--- | :--- | :--- |
| `name` | Metadata | `refocus-razorpay-worker` | Deployed name on Cloudflare Workers. |
| `compatibility_date` | Environment | `2026-03-01` (`nodejs_compat`) | Node.js crypto and subtle crypto compatibility enabled. |
| `FIREBASE_PROJECT_ID` | Production Var | `refocus-504119` | Matches [google-services.json](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json). |
| `FIREBASE_PROJECT_NUMBER`| Production Var | `524118238647` | Matches [google-services.json](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json). |
| `APP_CHECK_ENFORCEMENT_MODE` | Config Var | `"audit"` | **SAFE FOR DEPLOYMENT.** Allows gradual client attestation verification without breaking legacy or sideloaded installs. |
| `RAZORPAY_PLAN_ID` | Var / Plan | `plan_TdtgehXIhzQ8U9` | **TEST/SANDBOX PLAN.** Must be set to the live production Razorpay Plan ID when flipping to live Razorpay mode. |
| `RAZORPAY_KEY_ID` | Secret Binding | Secret (`rzp_test_...`) | Configured in Cloudflare Worker secrets. Must be updated to live Key ID (`rzp_live_...`) for production. |
| `RAZORPAY_KEY_SECRET` | Secret Binding | Secret (`***`) | Configured in Cloudflare Worker secrets. Must be updated to live Key Secret for production. |
| `RAZORPAY_WEBHOOK_SECRET` | Secret Binding | Secret (`***`) | Configured in Cloudflare Worker secrets. Webhook URL on Razorpay Dashboard must point to `https://refocus.workers.dev/api/razorpay-webhook`. |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | Secret Binding | Secret (JSON String) | Service account with Datastore & Identity Toolkit Admin roles. |
| `ALLOWED_ORIGINS` | CORS Security | `https://refocus-app.firebaseapp.com`<br>`https://refocus-504119.web.app` | Production Firebase hosting origins. |

---

## 3. Razorpay Subscription & Pricing Audit

### Price Verification Trace:
- **Android App UI:**
  - [SubscriptionBottomSheet.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/settings/ui/components/SubscriptionBottomSheet.kt#L285): `text = "₹79 / Month Autopay"`, `price = "₹79"`, `text = "Subscribe for ₹79/Month"`
  - [LockedAppScreen.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/features/subscription/ui/LockedAppScreen.kt#L200): `text = "₹79"`, `"₹79 / month recurring"`, `"Start for ₹79"`
  - [SubscriptionInfo.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/domain/models/SubscriptionInfo.kt#L29): `MONTHLY("plan_monthly", "₹79/Month")`
- **Cloudflare Worker Autopay Flow:**
  - `POST /api/create-subscription` passes `plan_id: env.RAZORPAY_PLAN_ID` (`plan_TdtgehXIhzQ8U9`, ₹79/month) directly to `https://api.razorpay.com/v1/subscriptions`.
  - Android SDK opens Checkout with `subscription_id` (amount is authoritatively dictated by Razorpay Plan, not client parameters).
- **Public Landing Page Discrepancy:**
  - `public/index.html`, `public/terms.html`, and `public/privacy.html` define `PREMIUM_PRICE: "₹99"`.
  - **Resolution Needed:** Update `CONFIG.PREMIUM_PRICE = "₹79"` in landing page assets prior to marketing launch.

---

## 4. App Check & Play Integrity Release Configuration

- **Android Client Configuration:**
  - [ReelTrakApplication.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/ReelTrakApplication.kt):
    - `BuildConfig.DEBUG == true` $\implies$ `DebugAppCheckProviderFactory.getInstance()`
    - `BuildConfig.DEBUG == false` $\implies$ `PlayIntegrityAppCheckProviderFactory.getInstance()`
  - [AppCheckInterceptor.kt](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/network/AppCheckInterceptor.kt): Safely fetches `getAppCheckToken(false)` with a 2000ms timeout.
- **Worker Configuration:**
  - `APP_CHECK_ENFORCEMENT_MODE` is currently `"audit"`.
  - **Rollout Plan:**
    1. Distribute signed APK/AAB via Google Play Internal/Closed Testing.
    2. Register Play Integrity SHA-256 and signing key in Firebase App Check console.
    3. Monitor audit logs in Cloudflare Worker (`validateAppCheck` passes without warnings).
    4. Set `APP_CHECK_ENFORCEMENT_MODE = "enforced"` via `wrangler secret put` / environment variables.

---

## 5. Firebase & Google Authentication Release Configuration

- **Google Services JSON:**
  - Package Name: `com.reeltrak.app`
  - OAuth Clients registered:
    - Client 1 (SHA-1: `cc37d1e6cb491cf6000835fb1df9ca288a6a7b4e`) — Debug Keystore
    - Client 2 (SHA-1: `42754aa07e4dbb7504059f2dfc762ac3ffca088e`) — Release / Upload Keystore
    - Client 3 (Web Client ID: `524118238647-fhocd7sf7t3nkmdbbanf3a33bgqcgj90.apps.googleusercontent.com`)
- **Release Login Flow:**
  - Google Credential Manager requests Google ID token using the Web Client ID.
  - **Release Requirement:** Ensure the SHA-1 and SHA-256 fingerprints from the **Google Play App Signing Key** (generated by Google Play Console if Play App Signing is enabled) are added to the Firebase Console Android App configuration. Otherwise, release builds downloaded from the Play Store will throw `16: Cannot find a matching credential`.

---

## 6. Release Signing & Build Hardening

- **Keystore Environment Variable Protection:**
  - [build.gradle.kts](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts) verifies `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
  - If unset, `signingConfig = null`. Gradle will fail fast rather than building an insecure debug-signed release APK.
- **R8 / Minification:**
  - `isMinifyEnabled = true`
  - `isShrinkResources = true`
  - `proguard-rules.pro` preserves Room DAOs, DataTransferObjects, Razorpay callback listeners, and Hilt injections.

---

## 7. Network & Manifest Security Configuration

- **Cleartext Disabled:** [network_security_config.xml](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/res/xml/network_security_config.xml) enforces `<base-config cleartextTrafficPermitted="false" />`.
- **AndroidManifest Declaration:**
  - `allowBackup="false"`
  - `ReelTrackerService` is protected with `android.permission.BIND_ACCESSIBILITY_SERVICE` and non-exported to third parties.
  - Debug broadcast receiver is guarded by `BuildConfig.DEBUG` and `RECEIVER_NOT_EXPORTED`.

---

## 8. Legal & Privacy Compliance

| Legal Requirement | Current Status | Alignment & Notes |
| :--- | :--- | :--- |
| **Privacy Policy URL** | Hosted in `public/privacy.html` | Accurately describes on-device data processing, no selling of data, and Firebase/Google authentication. Needs price alignment to ₹79. |
| **Terms of Service URL** | Hosted in `public/terms.html` | Mentions daily limits, Skull Days, discipline tiers, and monthly subscriptions. Mentions Google Play billing in Section 4 (should mention Razorpay subscription autopay). |
| **Account Deletion Disclosure** | Present in `public/terms.html` & in-app Settings | Fully functional via `POST /api/account/request-deletion` with 7-day soft-delete grace period and permanent purge. |
| **Accessibility Disclosure** | In-app Onboarding Screen | Explicitly displays why Accessibility is needed (detecting Reels/Shorts package activity) before requesting permission. |

---

## 9. Comprehensive Production Test Matrix

| Area | Component | Current Environment | Production Readiness | Required Action for Launch |
| :--- | :--- | :--- | :--- | :--- |
| **Google Sign-In** | Credential Manager | Firebase Test/Live | **READY** | Add Play App Signing SHA-1 to Firebase Console upon Play Store upload. |
| **Sign-Out & Wipe** | ApplicationScope Teardown | Device Local | **READY** | None. Fully verified (`BUG-CRIT-02`). |
| **Account Deletion** | Worker `/api/account/*` | Cloudflare Edge | **READY** | None. Soft-delete + purge fully tested. |
| **Reel Detection** | Accessibility Service | Device Local | **READY** | None. Zero network calls; V2 debounced detectors. |
| **Daily Limits & Streaks** | DayManager / Room | Device Local | **READY** | None. Monotonic progression verified. |
| **Server-Authoritative Time** | `GET /api/time` | Cloudflare Edge | **READY** | None. RTT/2 compensated, anti-tamper protected (`BUG-HIGH-03`). |
| **Cloud Sync & Merge** | SyncManager / Firestore | Firestore Production | **READY** | None. Non-corrupting $\max(\text{local}, \text{cloud})$ verified (`BUG-HIGH-02`). |
| **Free Trial (7-Day)** | `POST /api/ensure-subscription` | Cloudflare Edge | **READY** | None. Firestore rules block client writes. |
| **Subscription Purchase** | Razorpay Recurring Autopay | Razorpay TEST Mode | **READY FOR LIVE KEYS** | Swap `RAZORPAY_PLAN_ID`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` to Live. |
| **Webhook Processing** | `POST /api/razorpay-webhook` | Cloudflare Edge | **READY FOR LIVE WEBHOOK** | Configure Live Webhook URL & Secret in Razorpay Dashboard. |
| **App Check** | Firebase Play Integrity | Audit Mode | **READY FOR GRADUAL ENFORCEMENT** | Switch `APP_CHECK_ENFORCEMENT_MODE` to `enforced` after Play Store verification. |
| **Legal / Terms** | Firebase Hosting `public/` | Live Web Hosting | **MINOR UPDATE NEEDED** | Update `CONFIG.PREMIUM_PRICE = "₹79"` in HTML files. |

---

## 10. Final Action Classification

### A. MUST FIX BEFORE PUBLIC RELEASE
1. **Landing Page Pricing:** Update `PREMIUM_PRICE` from `"₹99"` to `"₹79"` in [public/index.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/index.html), [public/terms.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/terms.html), and [public/privacy.html](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/privacy.html).
2. **Terms Section 4 Billing Text:** Clarify in `public/terms.html` that subscription billing is managed securely via Razorpay recurring autopay.

### B. MUST CONFIGURE BEFORE PUBLIC RELEASE
1. **Razorpay Production Credentials:**
   - Create a ₹79/month recurring plan in Razorpay Live Dashboard.
   - Set `RAZORPAY_PLAN_ID` in `wrangler.jsonc` (or via Cloudflare secrets) to the Live Plan ID.
   - Update `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` with live production secrets.
2. **Razorpay Live Webhook Configuration:**
   - Add webhook endpoint `https://refocus.workers.dev/api/razorpay-webhook` in Razorpay Live Dashboard.
   - Select events: `subscription.authenticated`, `subscription.activated`, `subscription.charged`, `subscription.pending`, `subscription.halted`, `subscription.cancelled`, `subscription.completed`, `payment.failed`, `refund.processed`, `refund.created`.
3. **Firebase Play App Signing Fingerprints:**
   - Add the SHA-1 and SHA-256 certificate fingerprints from Google Play Console (App Signing) into Firebase Project Settings.

### C. REAL-DEVICE TEST REQUIRED
1. Perform one live end-to-end ₹79 subscription purchase with UPI Autopay on a release build.
2. Verify instant entitlement unlock upon webhook delivery.
3. Test cancellation via Settings BottomSheet.

### D. OPTIONAL POST-LAUNCH IMPROVEMENT
1. Switch `APP_CHECK_ENFORCEMENT_MODE` from `"audit"` to `"enforced"` once 100% of active client traffic is submitting valid Play Integrity attestations.

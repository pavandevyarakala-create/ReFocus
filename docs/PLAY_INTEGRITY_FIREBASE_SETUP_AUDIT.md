# Final Play Integrity & Firebase App Check Setup Audit

**Application**: Re:Focus Android Application  
**Package Name**: `com.reeltrak.app`  
**Firebase Project ID**: `refocus-504119`  
**Firebase Project Number**: `524118238647`  
**Audit Date**: September 22, 2026  

---

## 1. Firebase Project & Architecture Verification

- **Firebase Project ID**: `refocus-504119` (Confirmed in [`app/google-services.json:5`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json#L5) and [`worker/wrangler.jsonc:10`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/wrangler.jsonc#L10))
- **Firebase Project Number**: `524118238647` (Confirmed in [`app/google-services.json:3`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json#L3) and [`worker/wrangler.jsonc:11`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/wrangler.jsonc#L11))
- **Android Package Name**: `com.reeltrak.app` (Confirmed in [`app/google-services.json:13`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json#L13) and [`app/build.gradle.kts:11,15`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L11-L15))

---

## 2. App Check Providers & Worker Configuration

### Android App Check Initialization ([`ReelTrakApplication.kt:21-30`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/ReelTrakApplication.kt#L21-L30))
- **Debug Builds (`BuildConfig.DEBUG == true`)**: Installs `DebugAppCheckProviderFactory`. Debug tokens are printed to Logcat and used exclusively for local development.
- **Release Builds (`BuildConfig.DEBUG == false`)**: Installs `PlayIntegrityAppCheckProviderFactory`. Requests hardware-backed attestation from Google Play Services.

### Worker Enforcement & Route Protection ([`worker/src/index.js`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/src/index.js))
- **Current Enforcement Mode**: `APP_CHECK_ENFORCEMENT_MODE: "audit"` in [`worker/wrangler.jsonc:12`](file:///c:/Users/pavan/OneDrive/Documents/REEL/worker/wrangler.jsonc#L12).
- **Pre-Login Route (`GET /api/time`)**: Protected by `validateAppCheck` and IP rate limiting. Operates pre-login because `FirebaseAppCheck` initializes in `Application.onCreate()`, before user sign-in.
- **Authenticated Routes**:
  - `POST /api/ensure-subscription`
  - `POST /api/create-subscription`
  - `POST /api/verify-subscription`
  - `POST /api/cancel-subscription`
  - `GET  /api/account/status`
  - `POST /api/account/request-deletion`
  - `POST /api/account/cancel-deletion`
  *(All require valid App Check attestation + RS256 Firebase ID token)*.
- **Unauthenticated Server-to-Server Webhook**: `POST /api/razorpay-webhook` (bypasses App Check; authenticated via Razorpay HMAC-SHA256 signature).

---

## 3. SHA-256 Fingerprint Analysis & Classification

### Fingerprints Present in Repository Configuration ([`app/google-services.json:18-32`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/google-services.json#L18-L32))
1. `cc37d1e6cb491cf6000835fb1df9ca288a6a7b4e` (SHA-1 OAuth client hash)
2. `42754aa07e4dbb7504059f2dfc762ac3ffca088e` (SHA-1 OAuth client hash)

### Critical Fingerprint Distinction for Play Integrity
- **Upload Key SHA-256**: Derived from your local release keystore (`.jks`). This is used when you sign the `.aab` locally before uploading to Google Play Console.
- **Google Play App Signing Key SHA-256 (MANDATORY)**: Generated/managed by Google Play inside the Play Console. When Google distributes your app to end-users, it re-signs the APK with this App Signing Key.
- **Rule**: The **Google Play App Signing Key SHA-256** is the certificate that devices present during Play Integrity attestation. It **MUST** be added to the Firebase Console App Check settings.

---

## 4. Google Play Readiness Status

- **Status in Repository**: The repository contains client configurations for `com.reeltrak.app`, but Play App Signing certificate fingerprints are generated on Google Play Console and cannot exist in the repo prior to console setup.
- **Pre-requisite for Play Integrity Enforcement**: You must obtain the Play App Signing SHA-256 fingerprint from Google Play Console and paste it into Firebase App Check before switching `APP_CHECK_ENFORCEMENT_MODE` to `"enforced"`.

---

## 5. Audit Results Summary

### A. PASS Items
- ✅ **Package & Project Alignment**: `com.reeltrak.app` matches across `google-services.json`, `build.gradle.kts`, and `AndroidManifest.xml`.
- ✅ **Provider Separation**: `PlayIntegrityAppCheckProviderFactory` for Release, `DebugAppCheckProviderFactory` strictly for Debug.
- ✅ **Zero Premature Lockout**: `APP_CHECK_ENFORCEMENT_MODE="audit"` ensures legitimate users will not be rejected while testing on physical devices.
- ✅ **Pre-Login Attestation**: `/api/time` functions with App Check without requiring user login.
- ✅ **Dual-Layer Worker Verification**: Worker validates issuer (`https://firebaseappcheck.googleapis.com/524118238647`), audience, and JWKS signatures.

### B. BLOCKED Items (Prior to Switching Worker to "enforced")
- ⚠️ **Local Signing Variables**: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` must be set in your terminal to build the signed `.aab`.
- ⚠️ **Play Console App Signing Fingerprint Registration**: The App Signing SHA-256 fingerprint from Play Console must be registered in Firebase App Check.
- ⚠️ **Physical Device Telemetry Gathering**: App must be tested via Google Play Internal Testing for 48–72 hours in `"audit"` mode to verify 0 verification failures.

---

## 6. Manual Console Procedures

### C. EXACT Firebase Console Steps
1. Navigate to [Firebase Console](https://console.firebase.google.com/) -> Select project **`refocus-504119`**.
2. In the left navigation menu, go to **Build** -> **App Check**.
3. Under the **Apps** tab, find **`com.reeltrak.app`** and click **Register / Manage** (or **Play Integrity**).
4. Enter your **Google Play App Signing SHA-256 fingerprint** (and upload key SHA-256 if prompted).
5. Set token TTL (default is 1 hour).
6. Click **Save**.
7. *(Keep Firestore and Cloud Functions enforcement UNENFORCED in Firebase Console until Play Integrity telemetry verifies cleanly).*

### D. EXACT Google Play Console Steps
1. Log in to [Google Play Console](https://play.google.com/console).
2. Select your **Re:Focus** app.
3. In the left menu, navigate to **Release** -> **Setup** -> **App integrity**.
4. Under the **App signing** tab:
   - Copy the **App signing key certificate SHA-256 fingerprint**.
   - Copy the **Upload key certificate SHA-256 fingerprint** (for reference).
5. Go to **Testing** -> **Internal testing** -> Create a new release, upload the signed `app-release.aab`, and rollout to internal testers.
6. Install the app on a physical Android device via the internal testing link.

---

## 7. Exact Next Step

1. Export your release keystore variables in your local terminal:
   ```powershell
   $env:KEYSTORE_PATH="<path_to_keystore.jks>"
   $env:KEYSTORE_PASSWORD="<password>"
   $env:KEY_ALIAS="<alias>"
   $env:KEY_PASSWORD="<password>"
   ```
2. Build the production release bundle:
   ```powershell
   .\gradlew bundleRelease
   ```
3. Upload the generated `.aab` (`app/build/outputs/bundle/release/app-release.aab`) to the Google Play Console Internal Testing track.

# SECRET & CREDENTIAL EXPOSURE SECURITY AUDIT

**Date:** 2026-09-21  
**Scope:** Repository-wide scan across all Android source code, Gradle configurations, XML/JSON assets, Cloudflare Worker backend, Firebase functions, local scripts, and Git tracking.  
**Auditor:** Antigravity Security Audit Subsystem  

---

## 1. Executive Summary

| Metric | Count | Status |
|---|:---:|---|
| **Critical Exposed Secrets** | **0** | **CLEAN** |
| **High-Risk Credentials** | **0** | **CLEAN** |
| **Medium-Risk Findings / Configuration Hygiene** | **2** | **INFORMATIONAL** |
| **Public / Client-Visible Identifiers** | **5** | **VERIFIED SAFE** |
| **False Positives / Parsing Artifacts** | **3** | **VERIFIED NON-EXPOSURE** |

### Key Highlights:
1. **Zero Privileged Secrets in Android Client:** No Razorpay Key Secrets, Webhook Secrets, Cloudflare API Tokens, Firebase Admin Service Account Private Keys, or Keystore Passwords exist within the Android project files or generated APK build metadata.
2. **Zero Plaintext Secrets in Worker Repository:** Cloudflare Worker relies strictly on dynamic runtime secrets (`env.RAZORPAY_KEY_SECRET`, `env.RAZORPAY_WEBHOOK_SECRET`, `env.FIREBASE_SERVICE_ACCOUNT_KEY`) populated via Cloudflare Secret Store (`wrangler secret put`).
3. **Client-Visible Identifiers are Properly Scoped:** Firebase client configuration (`google-services.json`), Razorpay Test Key ID (`rzp_test_****Tdi`), and Worker public URLs are public identification tokens by design, protected by server-side rules, package name / SHA-1 restrictions, and App Check token binding.

---

## 2. Findings Table

| # | Location | Finding Type | Classification | Actually Exposed? | Risk | Recommendation |
|:---:|---|---|---|:---:|:---:|---|
| **1** | `app/google-services.json` | Firebase Client API Key (`AIzaSy****8MA`) | **A. Public Client Config** | Yes (Client Config) | **LOW / INFORMATIONAL** | Keep in APK; ensure Firebase Security Rules & API key app restrictions (Package Name + SHA-1 fingerprint) remain active in Google Cloud Console. |
| **2** | `app/google-services.json` | OAuth Client IDs (`524118238647-****.apps.googleusercontent.com`) | **A. Public Client Config** | Yes (Client Config) | **NONE** | Standard Google Sign-In client configuration. Safe in APK. |
| **3** | `worker/wrangler.jsonc` | Project IDs & Plan ID (`refocus-504119`, `plan_Tdtg****Q8U9`) | **B. Non-Secret Config** | Yes (Worker Config) | **NONE** | Non-privileged metadata required for worker routing and Razorpay plan mapping. Safe in repository. |
| **4** | `worker/src/index.js` | Razorpay Key ID fallback (`rzp_test_****Tdi`) | **A. Public Client Config** | Yes (Public Test ID) | **LOW** | Used as fallback for public key ID; ensure production deployment sets `RAZORPAY_KEY_ID` via Cloudflare environment variable. |
| **5** | `app/.../RazorpaySubscriptionPurchaseServiceImpl.kt` | Public Key ID placeholder / inject | **A. Public Client Config** | Injected at runtime | **NONE** | Android client receives public key ID or worker URL; never handles private key secret. |
| **6** | `functions/index.js` | `defineSecret("RAZORPAY_KEY_SECRET")` | **B. Secret Binding Ref** | No (Code Reference Only) | **LOW / HYGIENE** | Legacy Firebase Functions artifact. Cleanly references Secret Manager rather than hardcoding; recommend archiving or pruning legacy code. |
| **7** | `server.js` | `process.env.FIREBASE_SERVICE_ACCOUNT_KEY` | **B. Env Var Reference** | No (Code Reference Only) | **LOW / HYGIENE** | Local testing server script reading local environment variables. Safe from repository exposure. |
| **8** | `worker/src/index.js` | RegEx `/-----BEGIN[ A-Z0-9_-]+-----/g` | **E. False Positive** | No (Regex Pattern) | **NONE** | Regex parser for normalizing PEM service-account private key formatting at runtime. |
| **9** | `worker/test_runner.mjs` | `rzp_test_mockKey123` | **E. False Positive** | No (Mock String) | **NONE** | Mock credential used purely for local unit testing assertions. |
| **10** | `.gitignore` | `*.keystore`, `*.jks`, `.env*` | **E. False Positive** | No (Gitignore Rule) | **NONE** | Standard defensive ignore rules preventing accidental credential commits. |

---

## 3. Detailed Answers to Core Security Questions

### Finding 1 & 2: Firebase Client Configuration (`google-services.json`)
* **Redacted Value:** `AIzaSyBilmiuqvT56rMlShcdW1R7X39d1vY****`
1. **Is this value safe to ship inside an APK?**  
   **YES.** Firebase Client API keys identify the project to Google backend services. They are not server secrets and do not grant administrative access.
2. **Can an attacker extract it from the APK?**  
   **YES.** Anyone decompiling or unpacking the APK can read `google-services.json` and compiled string resources.
3. **If extracted, what could they do with it?**  
   Only invoke Firebase client endpoints enabled for the project. Access to data is strictly enforced by Firestore Security Rules, Firebase Auth, and App Check, not by the client API key.
4. **Does it need rotation?**  
   **NO.** Standard public identifier.
5. **Where should it live instead?**  
   In `app/google-services.json` as per official Android/Firebase architecture.

---

### Finding 3 & 4: Razorpay Public Key ID & Plan ID (`worker/wrangler.jsonc`, `worker/src/index.js`)
* **Redacted Values:** `rzp_test_Tb9rSP1OzJ****`, `plan_TdtgehXIhz****`
1. **Is this value safe to ship inside an APK / public Worker repo?**  
   **YES.** The Razorpay Key ID (`rzp_...`) is designed to be public and passed to Razorpay Checkout JS / Android SDK to open the payment modal. The Plan ID (`plan_...`) identifies the public recurring billing tier.
2. **Can an attacker extract it?**  
   **YES.** It is sent over the network to the client during checkout initialization.
3. **If extracted, what could they do with it?**  
   They can open a payment form for the ₹79/month plan. They **CANNOT** issue refunds, capture payments, forge signatures, read customer data, or bypass verification without the `RAZORPAY_KEY_SECRET`.
4. **Does it need rotation?**  
   **NO.** Only when transitioning from TEST (`rzp_test_...`) to LIVE (`rzp_live_...`) for production launch.
5. **Where should it live instead?**  
   Configured via `wrangler.jsonc` / Cloudflare Worker environment variables.

---

### Finding 6 & 7: Firebase Service Account & Razorpay Key Secret Bindings (`functions/`, `server.js`, `worker/src/`)
* **Redacted References:** `env.FIREBASE_SERVICE_ACCOUNT_KEY`, `env.RAZORPAY_KEY_SECRET`, `env.RAZORPAY_WEBHOOK_SECRET`
1. **Are these values safe to ship inside an APK?**  
   **ABSOLUTELY NOT.** If these were in the APK, it would be a **CRITICAL** vulnerability.
2. **Are they present in the APK or codebase?**  
   **NO.** Inspection of all APK configurations, BuildConfig generation, Room migrations, assets, and Gradle scripts confirms zero presence in Android artifacts. In the backend, they are strictly loaded from runtime environment bindings (`env.*`).
3. **If extracted from backend, what could an attacker do?**  
   - Razorpay Secret: Forge payment signatures, cancel subscriptions, initiate unauthorized transactions.
   - Service Account: Grant full admin read/write access to Firestore and Auth databases.
4. **Do they need rotation?**  
   **NO.** They have not been exposed or committed to source control.
5. **Where should they live?**  
   Encrypted in Cloudflare Secret Store (`wrangler secret put`).

---

## 4. Android Build Output & Artifact Analysis

1. **BuildConfig Fields:**  
   `app/build.gradle.kts` does not define sensitive `buildConfigField` entries. Only standard build variants are produced.
2. **Resource Packaging (`res/values`, `strings.xml`):**  
   Scanned all XML resource files. Zero secrets or API keys are hardcoded in application resources.
3. **Keystore & Signing Security:**  
   `app/build.gradle.kts` uses environment variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) for release builds and explicitly sets `signingConfig = null` if any variable is missing. No plaintext keystore passwords exist in `gradle.properties` or `local.properties`.
4. **R8 / ProGuard Log Stripping:**  
   `app/proguard-rules.pro` includes `-assumenosideeffects` rules stripping `Log.d`, `Log.v`, and `Log.i` in release builds, preventing accidental credential or PII leakage in system logcat.

---

## 5. Backend API & Endpoint Security Matrix

| Endpoint | Auth Required | App Check Binding | Firebase UID Binding | Server-Side Secret Required | Attack / Tampering Resilience |
|---|:---:|:---:|:---:|:---:|---|
| `GET /api/time` | No | Optional / Audit | N/A | No | High. Read-only server-authoritative timestamp. |
| `POST /api/ensure-subscription` | Yes (Bearer ID Token) | Yes | Yes (Token UID) | `FIREBASE_SERVICE_ACCOUNT_KEY` | High. Auto-provisions free trial with server-authoritative date. |
| `POST /api/create-subscription` | Yes (Bearer ID Token) | Yes | Yes (Token UID) | `RAZORPAY_KEY_SECRET`, `RAZORPAY_PLAN_ID` | High. Server creates subscription on Razorpay; customer notes bound to UID. |
| `POST /api/verify-subscription` | Yes (Bearer ID Token) | Yes | Yes (Token UID) | `RAZORPAY_KEY_SECRET`, `FIREBASE_SERVICE_ACCOUNT_KEY` | High. Validates HMAC SHA-256 signature; enforces plan ID match. |
| `POST /api/cancel-subscription` | Yes (Bearer ID Token) | Yes | Yes (Token UID) | `RAZORPAY_KEY_SECRET`, `FIREBASE_SERVICE_ACCOUNT_KEY` | High. Verifies Firestore ownership before issuing Razorpay cancel API. |
| `POST /api/razorpay-webhook` | No (HMAC Signature) | N/A | Bound via notes | `RAZORPAY_WEBHOOK_SECRET`, `FIREBASE_SERVICE_ACCOUNT_KEY` | High. Validates `x-razorpay-signature` against raw payload. Rejects invalid signatures. |

---

## 6. Git & Repository History Verification

* Scanned repository root, `.gitignore`, and commit structures.
* No `.env`, `.env.local`, `*.keystore`, `serviceAccountKey.json`, or `.pem` private key files are tracked by Git.
* Standard `.gitignore` properly excludes build directories (`app/build/`, `.gradle/`, `worker/dist/`) and local configuration files (`local.properties`).

---

## 7. Conclusion & Action Items for Production Launch

1. **Pre-Launch Configuration:**  
   Before flipping the application to Live mode, provision production secrets in Cloudflare Secret Store:
   - `wrangler secret put RAZORPAY_KEY_ID` (Live key: `rzp_live_...`)
   - `wrangler secret put RAZORPAY_KEY_SECRET` (Live secret)
   - `wrangler secret put RAZORPAY_WEBHOOK_SECRET` (Live webhook secret)
   - `wrangler secret put FIREBASE_SERVICE_ACCOUNT_KEY` (Prod service account JSON)
2. **Legacy Cleanup (Optional / Housekeeping):**  
   Prune deprecated `functions/` directory and `server.js` test harness if no longer utilized, reducing codebase surface area.
3. **Audit Verdict:**  
   **PASS — ZERO EXPOSED PRIVILEGED SECRETS.** The repository and build architecture comply with Google Play and OWASP secret management standards.

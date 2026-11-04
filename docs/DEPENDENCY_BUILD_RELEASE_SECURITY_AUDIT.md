# THIRD-PARTY DEPENDENCY, BUILD CONFIGURATION & RELEASE SECURITY AUDIT

**Target:** Android Client Application (`app/build.gradle.kts`), Node.js Backends (`package.json`, `functions/package.json`, `worker/package.json`), Gradle Build System, R8/ProGuard Rules, and Network Security Configuration  
**Date:** September 17, 2026  
**Status:** Inspection Only (No changes applied)

---

## 1. Executive Summary

This security audit conducted an in-depth inspection of the project's dependency tree, supply-chain risks, Gradle build files, release compilation flags, R8/ProGuard optimization rules, and network security policies.

### Summary of Audit Outcomes:
1. **Critical Build Configuration Gap:** [`app/build.gradle.kts`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L23-L32) defines `isMinifyEnabled = true` and `isShrinkResources = true` for the `release` build type, but **omits a dedicated release `signingConfig`**. Building a release APK via standard Gradle workflows falls back to debug signing or fails unless explicitly configured in an external CI pipeline.
2. **Lint Safety Suppression:** `app/build.gradle.kts` sets `checkReleaseBuilds = false` and `abortOnError = false` in the `lint {}` block, disabling release-gate checks for manifest errors, security warnings, and deprecated APIs.
3. **Hardcoded URLs & Client IDs:** Production Cloudflare Worker URLs (`https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev`) and Google Web Client OAuth IDs are hardcoded in Kotlin source code rather than driven via `BuildConfig` flavors.
4. **Supply Chain Integrity:** All Maven repositories are locked down to `google()` and `mavenCentral()` with `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` in `settings.gradle.kts`. No dynamic or untrusted package sources exist.
5. **Network Security:** Android cleartext traffic is globally forbidden (`cleartextTrafficPermitted="false"`).

---

## 2. Complete Dependency Inventory

### A. Android Client Application (`app/build.gradle.kts`)

| Group & Artifact | Version | Direct/Transitive | Purpose / Category | Maintenance Status | Security / Vulnerability Notes |
|---|---|---|---|---|---|
| `androidx.core:core-ktx` | `1.13.1` | Direct | Core Android Kotlin extensions | Active | Stable. No known direct CVEs. |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `2.7.0` | Direct | Lifecycle coroutine scopes | Active | Stable. |
| `androidx.lifecycle:lifecycle-runtime-compose` | `2.7.0` | Direct | Compose lifecycle state collection | Active | Stable. |
| `androidx.activity:activity-compose` | `1.9.0` | Direct | Compose activity integration | Active | Stable. |
| `androidx.navigation:navigation-compose` | `2.7.7` | Direct | Jetpack Compose navigation host | Active | Stable. |
| `androidx.compose:compose-bom` | `2024.05.00` | Direct (BOM) | Bill of Materials for Compose UI | Active | Stable (Includes Compose 1.6.7). |
| `androidx.compose.ui:ui` | BOM Managed | Direct | Compose UI rendering | Active | Stable. |
| `androidx.compose.material3:material3` | BOM Managed | Direct | Material 3 Design components | Active | Stable. |
| `androidx.compose.material:material-icons-extended` | BOM Managed | Direct | Extended iconography | Active | Stable. |
| `io.coil-kt:coil-compose` | `2.6.0` | Direct | Asynchronous image loading | Active | Stable. |
| `com.google.dagger:hilt-android` | `2.51.1` | Direct | Dependency Injection runtime | Active | Stable. |
| `com.google.dagger:hilt-android-compiler` | `2.51.1` | Direct (KSP) | Hilt code generation | Active | Stable. |
| `androidx.hilt:hilt-navigation-compose` | `1.2.0` | Direct | Hilt navigation integration | Active | Stable. |
| `androidx.datastore:datastore-preferences` | `1.1.1` | Direct | Key-value reactive local storage | Active | Stable. |
| `androidx.room:room-runtime` | `2.6.1` | Direct | SQLite ORM database runtime | Active | Stable. |
| `androidx.room:room-ktx` | `2.6.1` | Direct | Room Coroutines/Flow support | Active | Stable. |
| `androidx.room:room-compiler` | `2.6.1` | Direct (KSP) | Room query verification | Active | Stable. |
| `androidx.credentials:credentials` | `1.2.2` | Direct | Android Credential Manager | Active | Stable. |
| `androidx.credentials:credentials-play-services-auth` | `1.2.2` | Direct | Play Services Auth bridging | Active | Stable. |
| `com.google.android.libraries.identity.googleid:googleid` | `1.1.1` | Direct | Google ID Token Credential helper | Active | Stable. |
| `com.google.firebase:firebase-bom` | `33.1.2` | Direct (BOM) | Firebase BOM | Active | Stable. |
| `com.google.firebase:firebase-auth` | BOM Managed | Direct | Firebase User Authentication | Active | Stable. |
| `com.google.firebase:firebase-firestore` | BOM Managed | Direct | Cloud Firestore Client SDK | Active | Stable. |
| `com.google.code.gson:gson` | `2.10.1` | Direct | JSON serialization for Room | Active | Stable. |
| `androidx.work:work-runtime-ktx` | `2.9.0` | Direct | Background task manager | Active | Stable. |
| `androidx.hilt:hilt-work` | `1.2.0` | Direct | WorkManager Hilt integration | Active | Stable. |
| `com.razorpay:checkout` | `1.6.38` | Direct | Razorpay Standard Checkout SDK | Active | Verified proprietary payment SDK. ProGuard rules required. |
| `com.squareup.okhttp3:okhttp` | `4.12.0` | Direct | HTTP/REST network client | Active | Stable. |

---

### B. Backend & Worker Dependencies

| Environment / Package | Dependency | Version | Purpose | Security Notes |
|---|---|---|---|---|
| **Root (`package.json`)** | `express` | `^5.2.1` | Legacy Express server | Express 5.x. Active. |
| **Root (`package.json`)** | `firebase-admin` | `^14.4.0` | Admin SDK | Active. |
| **Root (`package.json`)** | `razorpay` | `^2.9.8` | Razorpay Node.js SDK | Active. |
| **Root (`package.json`)** | `cors` | `^2.8.6` | Express CORS middleware | Active. |
| **Root (`package.json`)** | `dotenv` | `^17.4.2` | Local environment loader | Active. |
| **Functions (`functions/package.json`)** | `firebase-functions` | `^6.3.2` | Cloud Functions v2 runtime | Active. |
| **Functions (`functions/package.json`)** | `firebase-admin` | `^13.1.0` | Admin SDK | Active. |
| **Worker (`worker/package.json`)** | `wrangler` | `^4.131.1` | Cloudflare CLI (Dev) | Active. Edge runtime uses native WebCrypto with zero runtime npm dependencies. |

---

## 3. Supply Chain Security Assessment

1. **Repository Lockdown:**
   [`settings.gradle.kts:L8-L14`](file:///c:/Users/pavan/OneDrive/Documents/REEL/settings.gradle.kts#L8-L14) enforces centralized repository management:
   ```kotlin
   dependencyResolutionManagement {
       repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
       repositories {
           google()
           mavenCentral()
       }
   }
   ```
   This strictly forbids individual subprojects from pulling packages from unapproved third-party or plain HTTP Maven endpoints.
2. **Version Pinning:**
   All direct Gradle dependencies in `app/build.gradle.kts` use exact version numbers (e.g. `2.6.1`, `1.6.38`, `4.12.0`) rather than dynamic wildcards (`+` or `1.0.+`).
3. **Zero Runtime Dependencies in Edge Worker:**
   The Cloudflare Worker in `worker/src/index.js` operates purely on Web Standards (WebCrypto, `fetch`, `Response`, `SubtleCrypto`) with **0 external npm dependencies** in production, completely eliminating npm supply chain injection vectors on the active edge backend.

---

## 4. Build Configuration & Release Hardening Findings

---

### [FINDING-01] HIGH: Missing Release Signing Configuration in Gradle Build
- **Severity:** `HIGH`
- **File & Location:** [`app/build.gradle.kts:L23-L32`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L23-L32)
- **Current Behavior:**
  ```kotlin
  buildTypes {
      release {
          isMinifyEnabled = true
          isShrinkResources = true
          proguardFiles(
              getDefaultProguardFile("proguard-android-optimize.txt"),
              "proguard-rules.pro"
          )
      }
  }
  ```
  The `release` block lacks a `signingConfig`.
- **Security Impact:**
  Release builds created via Gradle CLI (`./gradlew assembleRelease`) will produce unsigned APKs or fall back to default debug keystores if configured locally. If developers sign release binaries manually with ad-hoc keys, key provenance and reproducible builds are compromised.
- **Realistic Attack Scenario:**
  Unsigned APKs cannot be deployed directly to Google Play, risking accidental distribution of test-signed builds or developer signing mistakes in CI/CD.
- **Recommended Remediation:**
  Configure a structured `signingConfigs { create("release") { ... } }` referencing environment variables or secure keystore properties.

---

### [FINDING-02] MEDIUM: Lint Release Checks and Error Aborting Explicitly Disabled
- **Severity:** `MEDIUM`
- **File & Location:** [`app/build.gradle.kts:L46-L50`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/build.gradle.kts#L46-L50)
- **Current Behavior:**
  ```kotlin
  lint {
      disable += "RemoveWorkManagerInitializer"
      checkReleaseBuilds = false
      abortOnError = false
  }
  ```
- **Security Impact:**
  Disabling `checkReleaseBuilds` and setting `abortOnError = false` permits builds to succeed even when Android Lint detects high-severity security issues (e.g. exported components without permissions, insecure cryptographic algorithms, or missing network security constraints).
- **Realistic Attack Scenario:**
  A developer accidentally introduces an un-permissioned exported BroadcastReceiver or cleartext domain exception; the release build compiles without failing the pipeline.
- **Recommended Remediation:**
  Set `checkReleaseBuilds = true` and `abortOnError = true` for production release gating. Specific false-positive lint rules can be explicitly ignored using `disable += "..."`.

---

### [FINDING-03] MEDIUM: Missing ProGuard Rules for Logging Stripping in Production
- **Severity:** `MEDIUM`
- **File & Location:** [`app/proguard-rules.pro`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro)
- **Current Behavior:**
  While R8 minification and resource shrinking are enabled, `proguard-rules.pro` does not specify optimization directives to strip `android.util.Log` calls (`v`, `d`, `i`, `w`).
- **Security Impact:**
  Verbose diagnostic strings, active package names, and UI state dumps remain present in bytecode and execute on release devices, outputting internal state to system Logcat.
- **Recommended Remediation:**
  Add the standard R8 logging removal rule:
  ```proguard
  -assumenosideeffects class android.util.Log {
      public static boolean isLoggable(java.lang.String, int);
      public static int v(...);
      public static int d(...);
      public static int i(...);
  }
  ```

---

### [FINDING-04] LOW: Hardcoded Backend Endpoint URLs and Public OAuth Client IDs
- **Severity:** `LOW`
- **File & Location:** 
  - [`RazorpaySubscriptionPurchaseServiceImpl.kt:L51`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L51)
  - [`SubscriptionRepositoryImpl.kt:L42`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L42)
  - [`GoogleAuthManager.kt:L73`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt#L73)
- **Current Behavior:**
  `workerBaseUrl = "https://refocus-razorpay-worker.refocus-razorpay-worker.workers.dev"` and OAuth Client ID are hardcoded literals in Kotlin class bodies.
- **Security Impact:**
  Prevents environment-based isolation (e.g. dev, staging, prod) and forces code changes to point to different test backends. The OAuth Web Client ID and Worker URLs are public client identifiers (not private secrets), but hardcoding them inhibits zero-code backend URL switching or certificate rotation.
- **Recommended Remediation:**
  Inject `WORKER_BASE_URL` via Gradle `buildConfigField` based on the active build type or product flavor.

---

## 5. Network Security Configuration Assessment

- **Configuration File:** [`app/src/main/res/xml/network_security_config.xml`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/res/xml/network_security_config.xml)
- **Verification:**
  ```xml
  <network-security-config>
      <base-config cleartextTrafficPermitted="false" />
  </network-security-config>
  ```
- **Audit Finding:**
  - `cleartextTrafficPermitted="false"` is active globally. All HTTP requests are blocked; only HTTPS is permitted.
  - No insecure domain exceptions or debug trust anchors are declared.
  - **Status: SECURE (Base Config)**.

---

## 6. Secrets & Binary Exposure Assessment

| Asset / Identifier | Location | Classification | Security Risk & Assessment |
|---|---|---|---|
| **Razorpay Test Key ID (`rzp_test_...`)** | [`RazorpaySubscriptionPurchaseServiceImpl.kt:L127`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt#L127) | **Public Client Key** | Intended for public checkout initialization in client SDKs. Cannot capture payments or execute administrative actions without the Secret Key. |
| **Google Web Client ID (`524118...apps.googleusercontent.com`)** | [`GoogleAuthManager.kt:L73`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt#L73) | **Public OAuth Client ID** | Required by Android Credential Manager to request Google ID tokens. Safe for client embedding. |
| **Cloudflare Worker URL** | [`SubscriptionRepositoryImpl.kt:L42`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/SubscriptionRepositoryImpl.kt#L42) | **Public API Endpoint** | Standard public API route. All endpoints are protected by Bearer token authentication. |

---

## 7. False Positives & Non-Issues Investigated

1. **Gradle Build Cache & Wrapper Integrity:**
   - *Investigated:* [`gradle-wrapper.properties`](file:///c:/Users/pavan/OneDrive/Documents/REEL/gradle/wrapper/gradle-wrapper.properties) distribution URL.
   - *Verdict:* **CLEARED**. Points to official secure HTTPS endpoint `https://services.gradle.org/distributions/gradle-8.7-bin.zip`.
2. **Third-Party Payment SDK Integrity:**
   - *Investigated:* `com.razorpay:checkout:1.6.38`.
   - *Verdict:* **CLEARED**. Sourced from official Maven repository with ProGuard rules preserved in `proguard-rules.pro`.

---

## 8. Missing Security Controls Checklist

1. [ ] **Release Signing Config:** Release signing block is unconfigured in `build.gradle.kts`.
2. [ ] **Lint Release Gate Enforcement:** `checkReleaseBuilds = true` and `abortOnError = true` disabled.
3. [ ] **ProGuard Log Stripping:** No `-assumenosideeffects` rules to remove verbose diagnostic logging.
4. [ ] **BuildConfig Flavor Separation:** Endpoints are hardcoded rather than dynamically configured per build flavor.
5. [ ] **SSL Pinning / Network Security Certificate Pinning:** OkHttp client does not pin public certificates for Cloudflare Workers.

---

## 9. Recommended NEXT Audit Step ONLY

> **Recommended Next Step:** **Audit of Accessibility Service, IPC, Broadcast Receivers & Android System Attack Surface** (`app/src/main/AndroidManifest.xml`, `ReelTrackerService.kt`, `FloatingCounterManager.kt`, WindowManager overlays, and system broadcast receivers), focusing on overlay hijacking (tapjacking), IPC permission enforcement, and intent manipulation.

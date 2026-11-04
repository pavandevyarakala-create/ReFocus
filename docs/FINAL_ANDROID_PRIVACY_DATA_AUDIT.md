# Final Android Privacy & Data-Leak Audit

**Application**: Re:Focus Android Application  
**Package Name**: `com.reeltrak.app`  
**Audit Date**: September 22, 2026  
**Scope**: All Android source files, Room entities, DataStore preferences, Firestore sync DTOs, Worker payloads, and public privacy disclosures.

---

## 1. Privacy & Data-Leak Audit Findings

| Data Type / Subsystem | Classification | Storage / Transmission Mechanism | Audit Assessment & Guarantees |
| :--- | :---: | :--- | :--- |
| **Room Local Database** | **PASS** | Stored locally in `daily_reel_data` table ([`DailyReelData.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/local/entity/DailyReelData.kt)) | Stores aggregate metrics: `dateString`, `totalReelsCount`, `totalTimeSpentMillis`, `dailyGoal`, `isSkullDay`, `isStrictModeOn`, `isLimitLocked`, and brief session timestamps (`SessionData`: `appName`, `startTimeStamp`, `endTimeStamp`, `reelsScrolled`). Zero personal or sensitive data stored. |
| **DataStore Preferences** | **PASS** | Stored in `settings` & `trusted_time_store` ([`PreferencesRepositoryImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt)) | Stores non-sensitive user profile cache (`auth_email`, `auth_display_name`, `auth_profile_picture_uri`), goal configuration, badge unlock timestamps, and monotonic time anchors. Zero plaintext passwords or auth ID tokens are stored (legacy tokens proactively purged on save/logout). |
| **Firestore Cloud Sync** | **PASS** | Synced to `/users/{uid}/daily_data/{dateString}` via [`CloudDailyReelData.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/remote/dto/CloudDailyReelData.kt) | Strips all granular session arrays and transmits only minimal daily goal sync aggregates: `dateString`, `totalReelsCount`, `dailyGoal`, `isStrictModeOn`, `isStrictModeLocked`, `isLimitLocked`. No browsing history or media content is synced. |
| **Worker Transmission** | **PASS** | HTTPS REST requests to `/api/*` | Transmits only `planId` and `subscription_id`, `payment_id`, `signature` for verification. No banking info, credit cards, or passwords ever touch the app or Worker. |
| **Accessibility Node Info** | **PASS** | Scoped in [`accessibility_service_config.xml`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/res/xml/accessibility_service_config.xml) to `com.instagram.android` and `com.google.android.youtube` | Ephemeral in-memory node inspection solely to identify video reel boundaries (`InstagramDetectorV2`, `YouTubeDetectorV2`). Raw accessibility text is recycled immediately and **never** persisted to Room/DataStore or transmitted to network. |
| **Sensitive Permissions** | **PASS** | [`AndroidManifest.xml`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/AndroidManifest.xml) | Uses only `INTERNET`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`. No location, contacts, SMS, phone, camera, or microphone permissions requested. |
| **Authentication Tokens** | **PASS** | [`GoogleAuthManager.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/core/auth/GoogleAuthManager.kt) & [`RazorpaySubscriptionPurchaseServiceImpl.kt`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/java/com/reeltrak/app/data/services/RazorpaySubscriptionPurchaseServiceImpl.kt) | Google ID tokens and Firebase ID tokens are retrieved dynamically on-demand and exchanged in-memory. Never written to disk. |
| **Payment Credentials** | **PASS** | Razorpay Standard Android SDK | SDK handles mandate checkout directly. The app only receives `subscription_id`, `payment_id`, and `signature`. Zero credit card numbers or UPI PINs are processed or stored. |
| **Android Backup Behavior** | **PASS** | [`AndroidManifest.xml:12`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/AndroidManifest.xml#L12) | `android:allowBackup="false"` prevents unauthorized adb backups or cloud extraction of local databases/preferences. |
| **Network Cleartext Traffic** | **PASS** | [`network_security_config.xml:4`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/res/xml/network_security_config.xml#L4) | `<base-config cleartextTrafficPermitted="false" />` blocks all unencrypted HTTP traffic. |
| **Release Log Stripping** | **PASS** | [`proguard-rules.pro:49-54`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/proguard-rules.pro#L49-L54) | ProGuard strips `Log.d`, `Log.v`, `Log.i` at bytecode level in release builds. |
| **Component Exporting** | **PASS** | [`AndroidManifest.xml`](file:///c:/Users/pavan/OneDrive/Documents/REEL/app/src/main/AndroidManifest.xml) | Only `MainActivity` is exported. `ReelTrackerService` requires `BIND_ACCESSIBILITY_SERVICE`. Unexported WorkManager provider. |
| **Privacy Policy Alignment** | **PASS** | [`public/privacy.html`](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/privacy.html) & [`public/terms.html`](file:///c:/Users/pavan/OneDrive/Documents/REEL/public/terms.html) | Accurately discloses on-device processing, Razorpay payment processing, and account deletion rights. |

---

## 2. End-to-End Data Flow Summary

```
[ Accessibility Event (Instagram/YouTube) ]
                 │
                 ▼
[ Ephemeral In-Memory Detection (InstagramDetectorV2 / YouTubeDetectorV2) ]
                 │
                 ├──► (Raw Node Text is Immediately Recycled / Destroyed)
                 │
                 ▼
[ Local Room DB (DailyReelData) ] ── (Daily Aggregate Counts Only)
                 │
                 ▼
[ Cloud Sync (CloudDailyReelData) ] ──► [ Firestore: /users/{uid}/daily_data/{date} ]
                                            (Only dateString, count, goal, lock flags)

[ User Profile / Session ] ──► [ DataStore (auth_email, auth_display_name) ]
                                    (Zero tokens persisted to disk)

[ Payment Flow ] ──► [ Razorpay SDK ] ──► [ Worker /api/verify-subscription ]
                         (No card/UPI data)       (Only sub_id, pay_id, signature)
```

---

## 3. Findings & Required Code Changes

- **Overall Privacy Finding**: **PASS (Zero Leaks Detected)**
- **Required Code Changes**: **NONE**. The data structures, network security, and accessibility boundaries strictly adhere to minimal collection and privacy-by-design principles.

---

## 4. Final Readiness Assessment

The Re:Focus Android application privacy architecture is **100% READY FOR GOOGLE PLAY INTERNAL TESTING**.

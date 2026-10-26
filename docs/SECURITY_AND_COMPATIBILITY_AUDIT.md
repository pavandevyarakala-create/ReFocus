# ReelTrak — Security, Compatibility & Play Protect Full Audit Report

This report provides an in-depth security, compatibility, Google Play Protect, and banking/UPI app audit for ReelTrak.

---

## 1. Root Cause Summary: Why Play Protect & Banking Apps (Paytm) Flag the App

When ReelTrak is installed as a signed APK outside Google Play (sideloaded), automated security scanners (Google Play Protect, NPCI UPI security modules, Promon, ThreatMetrix, AppSealing) flag the application due to three specific high-risk triggers:

1. **Global Unfiltered Accessibility Service**:
   - `accessibility_service_config.xml` lacked `android:packageNames`.
   - The Android OS was delivering window inspection events for **every application** on the device, including Paytm, Google Pay, banking apps, SMS, and password managers.
   - Banking apps detect third-party accessibility services with global view access and automatically restrict transactions to prevent overlay attacks, keylogging, and OTP interception.

2. **OS Clear Storage Hijacking (`ManageSpaceActivity`)**:
   - `AndroidManifest.xml` registered `android:manageSpaceActivity=".ManageSpaceActivity"`.
   - When users clicked "Clear Storage" in Android Settings, `ManageSpaceActivity` intercepted the intent, displayed `"Action Denied: Storage cannot be cleared"`, and finished.
   - Anti-malware engines identify this as a ransomware/evasion pattern designed to prevent uninstallation or data clearance.

3. **Public Storage Hidden Dotfile Persistence (`.reeltrak_intent_backup.txt`)**:
   - `PreferencesRepositoryImpl.kt` wrote hidden files to `/sdcard/Documents/` to survive local data wipes.
   - This matches persistence/evasion heuristics used by trojans.

---

## 2. Detailed Findings & Mitigation Breakdown

| # | Component | Location | Risk / Heuristic Trigger | App Requirement | Remediation Action |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Clear Storage Hijack** | `AndroidManifest.xml`, `ManageSpaceActivity.kt` | Intercepts OS clear storage with "Action Denied". Flagged as trojan evasion. | **Not needed** | Remove `android:manageSpaceActivity` from Manifest and delete `ManageSpaceActivity.kt`. |
| **2** | **Hidden Dotfile Persistence** | `PreferencesRepositoryImpl.kt` | Writes `.reeltrak_intent_backup.txt` to public shared storage. | **Not needed** | Remove file writes. Rely on standard DataStore + Firestore. |
| **3** | **Global Accessibility Listener** | `accessibility_service_config.xml` | Missing `android:packageNames`. Receives events from banking/UPI apps. | **Not needed globally** | Scope strictly: `android:packageNames="com.instagram.android,com.google.android.youtube"`. |
| **4** | **Broad Accessibility Flags** | `accessibility_service_config.xml` | `flagIncludeNotImportantViews` and `flagRetrieveInteractiveWindows`. | **Not needed** | Restrict flags to `flagDefault\|flagReportViewIds`. |
| **5** | **Overlay Permission** | `AndroidManifest.xml` | `SYSTEM_ALERT_WINDOW` triggers heuristic alerts when combined with global accessibility. | **Required** for floating counter | Keep permission; scoping package names in accessibility config resolves the security heuristic. |

---

## 3. Permissions & Services Matrix

### Permissions Matrix
- **KEEP (Legitimate & Required)**:
  - `android.permission.INTERNET` (Cloud sync, Firebase Auth, billing APIs)
  - `android.permission.POST_NOTIFICATIONS` (Habit reminders, daily summaries)
  - `android.permission.SYSTEM_ALERT_WINDOW` (Floating reel counter widget over target apps)
  - `android.permission.BIND_ACCESSIBILITY_SERVICE` (Reel and scroll detection engine)
- **DO NOT ADD / FORBIDDEN**:
  - ❌ `RECEIVE_BOOT_COMPLETED` (Prevents unnecessary background wakeups)
  - ❌ `QUERY_ALL_PACKAGES` (Violates Google Play policies and flags banking shields)
  - ❌ `READ_SMS` / `RECEIVE_SMS` (Triggers financial fraud detection)
  - ❌ `READ_PHONE_STATE` (Triggers privacy/telephony alerts)

### Services Matrix
- **KEEP**:
  - `ReelTrackerService` (Accessibility Service strictly scoped to Instagram and YouTube)
- **REMOVE**:
  - ❌ `ManageSpaceActivity`

---

## 4. Exact Remediation Steps

1. **`app/src/main/AndroidManifest.xml`**:
   - Delete `android:manageSpaceActivity=".ManageSpaceActivity"` from `<application>`.
   - Delete `<activity android:name=".ManageSpaceActivity" ... />`.
2. **`app/src/main/java/com/reeltrak/app/ManageSpaceActivity.kt`**:
   - Delete this file from the project.
3. **`app/src/main/res/xml/accessibility_service_config.xml`**:
   - Add `android:packageNames="com.instagram.android,com.google.android.youtube"`.
   - Change flags to `flagDefault|flagReportViewIds`.
4. **`app/src/main/java/com/reeltrak/app/data/repository/PreferencesRepositoryImpl.kt`**:
   - Remove `backupFile` and public storage read/write logic.

---

## 5. Expected Results After Remediation

- **Google Play Protect**: Clears all heuristic alerts (no OS storage interception, no hidden public storage dotfiles).
- **Banking / UPI Apps (Paytm, Google Pay, PhonePe)**: Will not restrict functionality because the Android OS will never deliver banking accessibility events to ReelTrak.
- **Core Functionality**: Reel tracking, floating pill overlay, habit analytics, calendar streaks, cloud sync, and subscriptions continue to function seamlessly.

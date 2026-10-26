# BUG-HIGH-03 Remediation Report

## 1. Executive Summary & Root Cause

### Confirmed Bug
`TrustedTimeManager` previously allowed an unverified device clock (`System.currentTimeMillis()`) to mutate and advance the persistent `maxObservedTrustedTime` high-water mark whenever `getTrustedTime()` fell back to device wall-clock time (e.g. before the first server synchronization or upon device reboot).

### Attack / Failure Vector
1. User or automated time-zone shift moved the device wall clock +1 year into the future.
2. `getTrustedTime()` evaluated the forward timestamp and unconditionally executed `maxObservedTrustedTime = computedTime`, persisting the poisoned future timestamp into DataStore.
3. When the device clock returned to normal, `getTrustedTime()` continued returning the poisoned future timestamp (`maxObservedTrustedTime`).
4. Trial periods, subscription expiration calculations, and daily date calculations became permanently locked, expired, or corrupted.

---

## 2. Architectural Distinction: Verified vs. Unverified Time

| Domain | Source | Authority | Capable of Advancing `maxObservedTrustedTime` |
| :--- | :--- | :--- | :--- |
| **Verified Server Time** | Firestore snapshots / Cloud server response | Authoritative server clock | **YES** (Via `recordServerTime()`) |
| **Monotonic Time** | `serverAnchorTime + (SystemClock.elapsedRealtime() - elapsedRealtimeAnchor)` | High-precision hardware monotonic counter since verified anchor | **YES** (Continuously updates `maxObservedTrustedTime`) |
| **Unverified Device Wall Clock** | `System.currentTimeMillis()` | Untrusted local user space | **NO** (Only evaluated transiently via `Math.max(maxObservedTrustedTime, wallTime)`) |

---

## 3. Exact Before vs. After Behavior

### Before Remediation
```kotlin
// In getTrustedTime():
return if (s0 > 0L && e0 > 0L && currentElapsed >= e0) {
    val computedTime = s0 + (currentElapsed - e0)
    if (computedTime > maxObservedTrustedTime) {
        maxObservedTrustedTime = computedTime
    }
    computedTime
} else {
    val computedTime = System.currentTimeMillis()
    if (computedTime > maxObservedTrustedTime) {
        maxObservedTrustedTime = computedTime // <--- POISONED PERSISTENT STATE!
    }
    computedTime
}
```

### After Remediation
```kotlin
// In getTrustedTime():
return if (s0 > 0L && e0 > 0L && currentElapsed >= e0) {
    // VERIFIED MONOTONIC TIME PATH:
    val computedTime = s0 + (currentElapsed - e0)
    if (computedTime >= maxObservedTrustedTime) {
        maxObservedTrustedTime = computedTime
        computedTime
    } else {
        maxObservedTrustedTime
    }
} else {
    // UNVERIFIED DEVICE FALLBACK PATH:
    // Anti-rollback is preserved (never returns earlier than maxObservedTrustedTime),
    // but unverified wall-clock time NEVER mutates or advances maxObservedTrustedTime!
    val wallTime = System.currentTimeMillis()
    if (maxObservedTrustedTime > 0L) {
        Math.max(maxObservedTrustedTime, wallTime)
    } else {
        wallTime
    }
}
```

---

## 4. Key Security & Operational Scenarios

### A. Clock-Forward Manipulation Test
- **Action:** User moves device clock +1 year into the future.
- **Result:** `getTrustedTime()` temporarily returns the future time while the device is in the future. However, `maxObservedTrustedTime` remains at the last verified server baseline.
- **Clock Restored:** Once the device clock is restored to current time, `getTrustedTime()` immediately returns the true current time without any persistent poisoning.

### B. Clock-Rollback Manipulation Test
- **Action:** User moves device clock backward by 30 days to extend a trial/subscription.
- **Result:**
  - If a verified server anchor exists, monotonic `serverAnchor + elapsedRealtime` is returned, ignoring the wall clock entirely.
  - If operating in fallback mode, `Math.max(maxObservedTrustedTime, wallTime)` returns `maxObservedTrustedTime`, preventing time from regressing.

### C. Device Reboot Behavior
- **Action:** Device reboots (`SystemClock.elapsedRealtime()` resets to ~0).
- **Result:** `loadPersistedAnchor()` detects `currentElapsed < savedElapsed`. In-memory anchors `serverAnchorTime` and `elapsedRealtimeAnchor` are safely invalidated (reset to `0L`) while preserving `maxObservedTrustedTime` from DataStore. Monotonic calculation halts safely until the next verified server sync arrives.

### D. Offline Behavior
- **Action:** Device syncs with server, then operates offline for hours or days without rebooting.
- **Result:** Monotonic progression continues perfectly (`serverAnchorTime + deltaElapsed`), advancing `maxObservedTrustedTime` without needing network calls for every check.

---

## 5. Verification & Test Results

### Automated Unit Test Suite (`BugHigh03RemediationTest.kt`)
Added regression tests covering:
- **Requirement A:** Verified server timestamp advances high-water mark.
- **Requirement B, C, D:** Unverified device clock forward does not poison high-water mark; restoration works immediately.
- **Requirement E:** Device clock rollback is blocked by verified high-water mark.
- **Requirement F, G:** Offline monotonic progression after valid server sync.
- **Requirement H:** Reboot clears stale monotonic anchors while preserving anti-rollback high-water mark.
- **Requirement I:** Subscription expiration evaluation remains accurate.
- **Requirement J:** Daily date calculations remain consistent.

### Build Verification
- `./gradlew testDebugUnitTest` -> **BUILD SUCCESSFUL** (32 tasks, 0 failures)
- `./gradlew lintDebug` -> **BUILD SUCCESSFUL** (0 lint errors)
- `./gradlew compileDebugKotlin` -> **BUILD SUCCESSFUL**
- `./gradlew assembleDebug` -> **BUILD SUCCESSFUL**

---

## 6. Scope Confirmation & Isolation Check

- **SyncManager:** UNTOUCHED (BUG-HIGH-02 fix intact).
- **LoginViewModel:** UNTOUCHED (BUG-CRIT-02 fix intact).
- **Subscription / Billing Repository:** UNTOUCHED.
- **Reel Detection Engine & Floating Overlay:** UNTOUCHED.
- **File Changes:** Strictly confined to `TrustedTimeManager.kt`, `BugHigh03RemediationTest.kt`, and documentation in `docs/`.

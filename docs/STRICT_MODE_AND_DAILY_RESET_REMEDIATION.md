# Strict Mode and Daily Reset Remediation Report

## 1. Executive Summary

This remediation addresses two critical behavioral bugs identified in the application:
1. **Daily Reel Counter Reset Anomaly**: Spurious counter resets caused by transient `null` emissions in state flows and asynchronous re-initialization during lifecycle events or date evaluation races.
2. **Permanent Strict Mode Lock**: Once enabled, Strict Mode could not be disabled even on subsequent calendar days or after app restarts, violating the day-scoped enforcement contract.

Following a thorough root cause investigation across `DayManager`, Room database entities, UI layers (`DashboardScreen`, `StrictModeCard`), and the server-authoritative time framework (`TrustedTimeManager`), a unified, deterministic day-transition and state-ownership model was implemented and verified.

---

## 2. Root Cause Analysis

### A. Daily Reel Counter Reset Bug
1. **Transient State Flicker**: In `DayManager.loadOrCreateDayLocked()`, `_currentDayStats.value = null` was emitted before database queries finished. Any observer or collector reading `currentDayStats` during this non-atomic window received `null`, triggering default values (`0` count) in UI components and dependent flows.
2. **Day Rollover & DB Race**: When transitioning across midnight or evaluating dates, missing atomicity allowed asynchronous calls to load outdated or empty day records before database sync finished.

### B. Strict Mode Permanent Lock Bug
1. **UI State Lock Over-Constraint**: In `DashboardScreen.kt`, the `StrictModeCard` composable was invoked with:
   ```kotlin
   isLocked = isStrictModeLocked || isStrictMode == true || isStrictMode == null
   ```
   This meant that whenever `isStrictMode == true` (even when `isStrictModeLocked == false` on Day 2 following rollover), the UI switch was permanently disabled (`enabled = !isLocked`), completely preventing the user from toggling Strict Mode OFF on Day 2.
2. **Toggle Logic Flaw in DayManager**: In `DayManager.setStrictMode()`, the validation condition checked `if (currentStats.isStrictModeLocked)` before handling `enabled = false` vs `enabled = true`, rejecting disable actions even when a clean toggle was valid, and assigning `isStrictModeLocked = enabled` directly without cleanly separating the day's lock state from the persistent preference.

---

## 3. Implemented State Machine Architecture

### Formal State Model
- **`isStrictModeOn: Boolean`**: Persistent preference carrying over across calendar days.
- **`isStrictModeLocked: Boolean`**: Day-scoped lock indicating whether Strict Mode was activated during the current day.
- **`currentDateString: String`**: Formatted ISO calendar date string derived authoritatively from `TrustedTimeManager.getTrustedLocalDate()`.

### Transition Rules
1. **Turn ON on Day 1**:
   - `isStrictModeOn = true`
   - `isStrictModeLocked = true`
   - Disabled state: User **cannot** turn Strict Mode OFF for the remainder of Day 1. Process restarts preserve both flags.
2. **Midnight Rollover (Day 1 $\to$ Day 2)**:
   - Evaluated solely via `TrustedTimeManager.getTrustedLocalDate()`.
   - `isStrictModeOn` is inherited from Day 1 (`isStrictModeOn = true`).
   - `isStrictModeLocked` resets to `false`.
   - Previous day's count is finalized to Room DB with `syncStatus = 1`.
   - New day's `totalReelsCount` starts at `0`.
3. **Turn OFF on Day 2**:
   - Permitted because `isStrictModeLocked == false`.
   - `isStrictModeOn = false`
   - `isStrictModeLocked = false`
4. **Re-Enable on Day 2**:
   - If user turns Strict Mode ON again during Day 2, `isStrictModeOn = true` and `isStrictModeLocked = true` (immediately re-locked for the remainder of Day 2).

---

## 4. Modified Files & Classes

| File Path | Component | Changes Made |
| :--- | :--- | :--- |
| `app/src/main/java/com/reeltrak/app/services/tracking/DayManager.kt` | `DayManager` | Removed `_currentDayStats.value = null` emission to eliminate flicker; updated `setStrictMode` to check `!enabled && currentStats.isStrictModeLocked` and properly handle day-scoped locking; verified atomic day initialization and rollover. |
| `app/src/main/java/com/reeltrak/app/features/dashboard/ui/DashboardScreen.kt` | `DashboardScreen` | Corrected `StrictModeCard` lock binding from `isStrictModeLocked \|\| isStrictMode == true` to `isStrictModeLocked \|\| isStrictMode == null`, allowing user to disable carried-over Strict Mode on a new day. |
| `app/src/main/java/com/reeltrak/app/features/dashboard/ui/components/StrictModeCard.kt` | `StrictModeCard` | Updated subtitle hierarchy to distinguish between "Enforcement active (Locked for today)" and "Enforcement active (Can disable once today)". |
| `app/src/test/java/com/reeltrak/app/services/tracking/StrictModeAndDailyResetTest.kt` | Test Suite | Added full suite of unit tests covering test cases A–S, boundary transitions (23:59 $\to$ 00:00 $\to$ 00:01), and static source audits. |

---

## 5. Verification & Test Results

### Test Suite Execution
- Command: `.\gradlew testDebugUnitTest`
- **Total Tests Completed**: 164 unit tests (including all tests in `StrictModeAndDailyResetTest`)
- **Failed**: 0
- **Passed**: 164

### Specific Scenarios Verified
- **A. OFF $\to$ ON today**: Successfully locks Strict Mode for the day and rejects disabling.
- **B. ON today $\to$ Process restart**: Preserves `isStrictModeOn = true` and `isStrictModeLocked = true`.
- **C. ON today $\to$ Date changes (Midnight)**: Strict Mode carries over as `isStrictModeOn = true` while `isStrictModeLocked = false`.
- **D. Next day $\to$ OFF once**: Successfully turns OFF and stays OFF.
- **E. Next day $\to$ OFF $\to$ ON again**: Immediately re-locks for the remainder of the day.
- **F. Next day $\to$ ON $\to$ Process restart**: Preserves re-locked state after restart.
- **G. Next day $\to$ ON $\to$ Day 3 transition**: Unlocks again on Day 3.
- **H/I. Auth & Sync**: Account logout/login and Firebase sync do not delete or overwrite device-local today enforcement state.
- **J/K/L/M/N/O/P/Q/R/S. Daily Counter Lifecycle**: Multiple reads, app switching, service restarts, and fake clock manipulations do not trigger unwanted resets; trusted midnight transitions create exactly one new day starting at `0`.
- **Boundary Transitions**: 23:59 $\to$ 00:00 $\to$ 00:01 accurately transitions counts and lock states.

### Quality & Compilation Checks
- `.\gradlew compileDebugKotlin`: **SUCCESS**
- `.\gradlew lintDebug`: **SUCCESS** (0 errors)
- `.\gradlew assembleDebug`: **SUCCESS**

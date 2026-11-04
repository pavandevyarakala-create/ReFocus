# Server-Authoritative Time Global Consumer Migration (Phase 4)

## Overview & Invariant Architecture
Phase 4 completes the comprehensive migration of all security-sensitive and progression-sensitive date/time consumers across the ReelTrak Android application to server-authoritative trusted time.

### Architectural Invariants:
1. **Authoritative Server Time:** Cloudflare Worker endpoint `GET /api/time` provides the primary source of truth.
2. **Local Trusted Representation:** `TrustedTimeManager` translates server time + monotonic `SystemClock.elapsedRealtime()` into local $O(1)$ operations (`getTrustedTime()`, `getTrustedLocalDate()`).
3. **Anti-Tamper & High-Water Mark:** `maxObservedTrustedTime` (`BUG-HIGH-03`) persists verified anchors and prevents device wall-clock rollbacks or unverified future-clock poisoning.
4. **Device Local Time Retention:** Local time APIs (`System.currentTimeMillis()`, `LocalDate.now()`, `SystemClock.elapsedRealtime()`) remain intact for technical duration measurements, UI animations, touch debounce, and calendar browsing.
5. **No Per-Reel Network Calls:** Reel tracking, counter increments, detection pipelines, and UI recompositions perform zero network operations.

---

## Complete Project-Wide Time-Source Inventory & Classification

All date and time usages across the ReelTrak codebase have been audited and categorized using the following schema:
- **A: Security-Sensitive** (Trial/subscription validity, account deletion grace period, tampering guards)
- **B: Progression-Sensitive** (Daily limits, midnight auto-unlock, streak calculations, badge unlocks)
- **C: Account / Cloud Data Timestamp** (Cloud synchronization, Firestore records)
- **D: Analytics / History** (Historical rollups, habit trends)
- **E: Relative Duration / Technical** (Session duration calculations, debounce timeouts, monotonic anchors)
- **F: UI-Only** (Calendar grid rendering, axis label formatting, countdown tickers)

### Comprehensive Classification Table

| File | Occurrence / API | Classification | Status | Rationale / Safety Guarantee |
| :--- | :--- | :--- | :--- | :--- |
| `DashboardViewModel.kt` | `trustedTimeManager.getTrustedLocalDate()` | **B (Progression)** | **Migrated (Phase 4)** | Used for midnight daily-limit auto-unlock check and 90-day habit history window anchor. |
| `DashboardViewModel.kt` | `trustedTimeManager.getTrustedTime()` | **B (Progression)** | **Migrated (Phase 4)** | Used for baseline `firstRunDate` initialization and `checkAndPersistBadges()` unlock timestamp. |
| `AccountRepositoryImpl.kt` | `Instant.parse(scheduledForIso).toEpochMilli()` | **A / C (Security)** | **Migrated (Phase 4)** | Worker's deletion schedule is authoritative. Removed unverified local fallback calculation (`now + 7 days`). |
| `OnboardingRepositoryImpl.kt` | `FieldValue.serverTimestamp()` | **C (Cloud Sync)** | **Migrated (Phase 4)** | Cloud metadata `updatedAt` uses Firestore authoritative server timestamp instead of local clock. |
| `CloudSubscriptionDto.kt` | `toDomain(trustedNow: Long)` | **A (Security)** | **Migrated (Phase 4)** | Removed default `= System.currentTimeMillis()`. Requires explicit caller-provided trusted timestamp. |
| `DayManager.kt` | `trustedTimeManager.getTrustedLocalDate()` | **B (Progression)** | **Migrated (Phase 3)** | Authoritative date string generation for daily reel records, limits, and unlock dates. |
| `SyncManager.kt` | `trustedTimeManager.getTrustedTime()` | **C (Cloud Sync)** | **Migrated (Phase 3)** | Cloud reel synchronization and last-sync tracking. |
| `SyncWorker.kt` | `trustedTimeManager.getTrustedTime()` | **C (Cloud Sync)** | **Migrated (Phase 3)** | Sync throttling and periodic sync timestamp checks. |
| `LoginViewModel.kt` | `trustedTimeManager.getTrustedTime()` | **C (Account)** | **Migrated (Phase 3)** | Account registration and profile creation `createdAt` timestamps. |
| `PreferencesRepositoryImpl.kt` | `setFirstRunDate(trustedTime)` | **B (Progression)** | **Migrated (Phase 4)** | Persists milestone baseline timestamps generated from trusted time. |
| `SubscriptionManager.kt` | `trustedTimeManager.getTrustedTime()` | **A (Security)** | **Migrated (Phase 3/4)** | Authoritative trial/subscription expiration checks; UI countdown ticker calculates relative differences without network requests. |
| `TrustedTimeManager.kt` | `SystemClock.elapsedRealtime()` | **E (Technical)** | **Retained (Core)** | Monotonic progression anchor for calculating current time between server syncs. |
| `TrustedTimeManager.kt` | `System.currentTimeMillis()` | **E (Technical)** | **Retained (Core)** | Unsynchronized bootstrap fallback, strictly constrained by persistent high-water mark (`maxObservedTrustedTime`). |
| `SessionManager.kt` | `System.currentTimeMillis()` | **E (Technical)** | **Retained (Local)** | Reel scroll session duration calculation (`sessionEnd - sessionStart`). Relative math is local-time safe. |
| `ReelTrackerService.kt` | `System.currentTimeMillis()` | **E (Technical)** | **Retained (Local)** | Audio playback timeout debounce and detection cooldowns. |
| `FloatingCounterManager.kt` | `System.currentTimeMillis()` | **E (Technical)** | **Retained (Local)** | Touch/click debounce timing for floating bubble UI. |
| `LiveSessionCard.kt` | `System.currentTimeMillis()` | **F (UI-Only)** | **Retained (Local)** | Computes dynamic UI elapsed duration since live session start. |
| `GoalCalendar.kt` | `LocalDate.now()`, `YearMonth.now()` | **F (UI-Only)** | **Retained (Local)** | Default month navigation view in calendar picker UI. |
| `WeeklyActivityGraph.kt` | `LocalDate.now().toEpochDay()` | **F (UI-Only)** | **Retained (Local)** | Renders "Today" text label on weekly activity chart X-axis. |
| `DevMockSubscriptionPurchaseServiceImpl.kt` | `System.currentTimeMillis()` | **Dev / Test** | **Retained (Local)** | Development mock simulator for in-app purchases. |

---

## Key Consumer Migration Details

### 1. DashboardViewModel
- **Midnight Auto-Unlock:** Evaluates `trustedTimeManager.getTrustedLocalDate().toEpochDay() > lastUnlockDay` to prevent users from fast-forwarding device clocks to bypass daily limits.
- **Habit Tracker History:** Uses `trustedTimeManager.getTrustedLocalDate().toEpochDay()` to anchor the 90-day activity matrix.
- **Badge Milestones:** `checkAndPersistBadges()` stamps unlocks with `trustedTimeManager.getTrustedTime()`.
- **First-Run Timestamp:** Baseline initialized using `trustedTimeManager.getTrustedTime()`.

### 2. Account Deletion & Grace Period (`AccountRepositoryImpl`)
- **Authoritative Cloudflare Worker:** Cloudflare Worker handles `scheduledFor` calculation on the server.
- **Android Client:** Treats `scheduledFor` from server response as authoritative. Removed any local fallback calculation (`System.currentTimeMillis() + 7 days`) to prevent client-server state discrepancy.

### 3. Onboarding & Cloud Metadata (`OnboardingRepositoryImpl`)
- **Firestore Server Timestamps:** Migrated `updatedAt` in Firestore user onboarding documents to `FieldValue.serverTimestamp()`, ensuring cloud writes carry server-authoritative timestamps without relying on device clocks.

### 4. Subscription State Evaluation (`CloudSubscriptionDto`)
- **Strict Trusted Epoch Requirement:** Removed `trustedNow: Long = System.currentTimeMillis()` default parameter from `CloudSubscriptionDto.toDomain()`.
- **Caller Enforcement:** All consumers must explicitly provide `trustedTimeManager.getTrustedTime()`, preventing silent fallback to local system clocks during subscription validity evaluation.

---

## Offline & Reboot Resilience

1. **Offline Operation:**
   - When offline, `TrustedTimeManager` continues progressing accurately using `serverAnchorTime + (elapsedRealtime() - elapsedRealtimeAnchor)`.
   - `maxObservedTrustedTime` ensures time never moves backwards even if device time changes offline.
2. **Device Reboot:**
   - `elapsedRealtime()` resets to 0 on reboot.
   - `TrustedTimeManager` marks server time as unverified until the next successful sync with Cloudflare `/api/time`.
   - In the interim, time is clamped to `max(System.currentTimeMillis(), maxObservedTrustedTime)`, preventing clock rewinds from granting expired trials or rolling back progression.

---

## Verification & Test Results

### Unit & Security Test Suite
- `Phase4ServerTimeMigrationTest.kt` verifies:
  1. `deviceClockFastForward_doesNotUnlockNewDailyLimit()` — PASS
  2. `deviceClockRewind_doesNotAlterStreakOrExtendTrial()` — PASS
  3. `midnightRollover_triggeredByTrustedServerTime()` — PASS
  4. `offlineTrustedAnchor_continuesMonotonicProgression()` — PASS
  5. `deviceReboot_requiresResync_andClampsToMaxObservedTime()` — PASS
  6. `accountRepository_usesAuthoritativeServerDeletionSchedule()` — PASS
  7. `onboardingRepository_usesServerTimestampForCloudMetadata()` — PASS
  8. `cloudSubscriptionDto_requiresExplicitTrustedTimestamp()` — PASS
  9. `dashboardViewModel_badgesAndFirstRun_useTrustedTime()` — PASS

### Build Verification
- `.\gradlew compileDebugKotlin` → **SUCCESS**
- `.\gradlew testDebugUnitTest` → **SUCCESS (134 tests passed, 0 failures)**
- `.\gradlew lintDebug` → **SUCCESS**
- `.\gradlew assembleDebug` → **SUCCESS**

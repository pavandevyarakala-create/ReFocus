# Trusted Business Clock & Date Architecture

## 1. Overview & Purpose
Re:Focus enforces daily reel scrolling limits, strict mode locks, streak calculations, and calendar history. To prevent tampering (such as manually changing the phone's system time forward or backward in device settings) and to eliminate cold-start/startup race conditions between UI components and local database state, the application uses a **Server-Anchored Monotonic Business Clock**.

---

## 2. Anchor Model & Monotonic Progression

### Mathematical Formula
While within the same boot session, the trusted timestamp is computed as:
$$\text{trustedNow} = S_0 + (\text{SystemClock.elapsedRealtime}() - E_0)$$

Where:
- $S_0$: The server-authoritative epoch timestamp in milliseconds (compensated for network round-trip time).
- $E_0$: The local monotonic `SystemClock.elapsedRealtime()` measured at the exact arrival time of $S_0$.

### Monotonic Invariant
- Trusted business time advances at a rate of 1 millisecond per real-time millisecond via hardware tick count.
- Changes to the Android system wall-clock (`System.currentTimeMillis()`) via device Settings > Date & Time have **0% effect** on the internal business clock.
- The high-water mark `maxObservedTrustedTime` ensures that time **never moves backward**.

---

## 3. Reboot & Offline Behavior

### Reboot Detection
`SystemClock.elapsedRealtime()` resets to `0` whenever the device powers off or reboots.
On startup:
1. `TrustedTimeManager` reads the persisted state synchronously via `SharedPreferences`.
2. If $\text{currentElapsedRealtime} < E_0$, a **reboot is detected**.
3. Monotonic tracking anchors from `maxObservedTrustedTime` to the new boot session, and `isRebootPendingReanchor` is flagged `true`.

### Power-Off / Gap Monotonicity
- When the device is turned off for 3 or 30 days while offline, the system does **not fabricate activity or timestamps** for the offline period.
- Monotonic progress resumes from `maxObservedTrustedTime`.

### Lazy Re-Anchoring
- When network connectivity is established after a reboot or fresh install, `TrustedTimeManager.syncIfPendingReanchor()` issues **ONE single `/api/time` synchronization**.
- Once $S_0$ and $E_0$ are re-anchored to the new boot session, `isRebootPendingReanchor` is cleared.
- **No periodic timers, background polling workers, per-minute, or per-second requests** exist in the codebase. Normal operation requires **zero ongoing network calls**.

---

## 4. Single Source of Truth Across Layers

| Component | Time Source | Description |
|---|---|---|
| `TrustedTimeManager` | Monotonic Anchor $(S_0, E_0)$ | Computes anti-tamper epoch ms and `LocalDate` |
| `DayManager` | `trustedTimeManager.getTrustedLocalDate()` | Single date string $YYYY-MM-DD$ governing database rows & midnight transitions |
| `DashboardViewModel` | `trustedCurrentDate: StateFlow<LocalDate>` | Drives today's scrolled reels, habit grid, and midnight resets |
| `GoalCalendar` | `currentBusinessDate: LocalDate` | Renders the calendar month grid anchored to trusted date |
| `WeeklyActivityGraph` | `trustedBusinessDate: LocalDate` | Identifies "Today" bar and last 7 days exclusively via trusted date |
| `CalendarScreen` | `trustedCurrentDate` | Passes trusted date down to `GoalCalendar` and streak cards |

---

## 5. Technical-Only APIs Kept Monotonic

The following technical operations deliberately keep local hardware timers:
- Touch tap debouncing and floating overlay drag timeouts: `System.currentTimeMillis()` / `SystemClock.elapsedRealtime()`.
- Local UI session active duration: `sessionStartTime`.
- Room 500ms write coalescing delay: `delay(500L)`.

All business-authoritative decisions (daily limits, strict mode lock, streak tracking, calendar rendering, cloud sync) strictly use `TrustedTimeManager`.

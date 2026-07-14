# Performance Baseline

This document acts as a historical record of the app's performance characteristics and identifies critical bottlenecks that exist within the current architecture.

*(Note: These findings were recorded during the initial performance audit. No code modifications have been made to address them yet).*

## 1. Accessibility Event Throughput & Memory Allocations

- **Exact File**: `ReelDetectionEngine.kt`
- **Exact Function**: `calculateConfidence()`
- **Why it happens**: The BFS traversal analyzes thousands of `AccessibilityNodeInfo` objects per second during active scrolling. It queries child nodes using `current.getChild(i)` but never calls `recycle()` on them. `AccessibilityNodeInfo` objects are pooled by Android OS.
- **Estimated Impact**: **CRITICAL**. Exhausting the OS object pool forces massive GC (Garbage Collection) allocations, resulting in constant micro-stutters and frame drops in the host app (Instagram/YouTube) and significant battery drain.

## 2. Background Thread Work (Coroutine Efficiency)

- **Exact File**: `EventProcessor.kt`
- **Exact Function**: `onAccessibilityEvent()`
- **Why it happens**: For every raw accessibility event, a new coroutine is launched: `coroutineScope.launch(Dispatchers.Default)`. At 60-120 events per second, this completely floods the `Dispatchers.Default` thread pool, causing CPU starvation.
- **Estimated Impact**: **HIGH**. The app spends more CPU cycles scheduling coroutines than actually processing the events.

## 3. Main Thread Work (Flow Efficiency)

- **Exact File**: `DashboardViewModel.kt`
- **Exact Function**: Flow operators (`weeklyActivityData`, `goalCalendarData`)
- **Why it happens**: The `.map` operators iterate over the entire database history to perform complex Calendar math, date parsing, and time block clamping. Because they lack a `.flowOn(Dispatchers.Default)` declaration, they inherit the `Dispatchers.Main` context from the `collectAsState()` call in the UI.
- **Estimated Impact**: **HIGH**. A large database (e.g., thousands of sessions) will cause UI freezes and dropped frames whenever the database emits an update or the Dashboard is opened.

## 4. Widget Recompositions (Compose Efficiency)

- **Exact File**: `RecentSessionsTimeline.kt`
- **Exact Function**: `RecentSessionsTimeline()`
- **Why it happens**: The `.forEach` loop iterates over sessions but does not provide a stable `key`. Furthermore, `SessionEntity` lacks the `@Immutable` annotation.
- **Estimated Impact**: **MEDIUM**. Whenever the session list changes (e.g., when the live session counter updates), Compose destroys and rebuilds every node in the timeline instead of smartly skipping unchanged elements.

## 5. Database Efficiency

- **Exact File**: `SessionDao.kt`
- **Exact Function**: `getAllSessions()`
- **Why it happens**: Executes `SELECT * FROM scroll_sessions ORDER BY startTimeStamp DESC`. There is no `LIMIT` clause or pagination.
- **Estimated Impact**: **LOW (Currently) -> HIGH (Eventually)**. As the user accumulates months of data, loading the entire historical table into RAM will cause severe memory bloat and potential OutOfMemory (OOM) exceptions.

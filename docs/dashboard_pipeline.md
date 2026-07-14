# Dashboard Data Pipeline

The Dashboard is the central UI of the application. It displays real-time and historical analytics. This document traces exactly how a physical thumb swipe on Instagram eventually renders as a data point on the REEL Dashboard.

## 1. The Real-Time Pipeline

When the user is actively scrolling a Reel, the Dashboard must update immediately.

1. **Physical Swipe (AccessibilityEvent)**: The OS dispatches `TYPE_VIEW_SCROLLED`.
2. **Detection & Debounce**: `ReelTrackerService` checks scroll direction (ignores backward) and passes the event to `ReelDetectionEngine`.
3. **State Machine (`SessionManager`)**: 
   - A new unique signature triggers `incrementReel()`.
   - `SessionManager` updates its internal `reelsCount` and invokes the state change callback.
4. **State Dispatch (`OverlayStateManager`)**:
   - The service calls `overlayStateManager.updateState { it.copy(currentSessionReels = count) }`.
5. **ViewModel Blending (`DashboardViewModel`)**:
   - The ViewModel is actively observing `OverlayStateManager.state`.
   - It runs the `combine(allSessions, liveSessionState)` operator.
   - It intercepts the live session state. If `SessionState.ACTIVE`, it synthesizes a "Mock" `SessionEntity` representing the currently ongoing session.
   - It prepends this mock entity to the `historical.toMutableList()`.
6. **UI Recomposition (`DashboardScreen`)**:
   - The UI collects `blendedSessions`. 
   - Because the list just mutated, it passes the new list to `WeeklyActivityGraph` and `HeroProgressCard`, which immediately redraw with the live data point seamlessly integrated alongside historical data.

## 2. The Historical Pipeline

When the user exits Instagram and opens REEL later to view their stats.

1. **Session Finalization**:
   - The user switches apps or the 2000ms idle timeout fires.
   - `SessionManager` transitions to `IDLE`.
   - `ReelTrackerService` calls `saveSessionToDb()`.
2. **Database Write**:
   - A new `SessionEntity` is inserted into Room (`SessionDao`).
3. **Database Read (`SessionDao` Flow)**:
   - Room's architecture automatically observes the `scroll_sessions` table for mutations.
   - It queries `SELECT * FROM scroll_sessions ORDER BY startTimeStamp DESC`.
   - It emits a new `List<SessionEntity>` to the Flow.
4. **ViewModel Mapping (`DashboardViewModel`)**:
   - `DashboardViewModel` receives the new Flow.
   - Variables like `avgReelsPerDay`, `hoursScrolledThisWeek`, and `goalCalendarData` apply `.map` operators to loop through the historical data, group by date, and summarize the counts.
5. **UI Rendering**:
   - `GoalCalendar` receives a new list of 84 integers (intensities 0-3) and redraws its grid.
   - `RecentSessionsTimeline` takes the top 3 items and draws the text.

## 3. The Daily Progress Pipeline

1. **Increment Event**: During active scrolling, `checkDailyLimit(1)` is called.
2. **Atomic Write**: The service fires the `incrementCount` SQL `UPDATE` directly on `DailyStatisticsDao`.
3. **Flow Re-emission**: Room detects the mutation on the `daily_statistics` table and emits the new total.
4. **UI Update**: `HeroProgressCard` collects `reelsScrolledToday` and smoothly animates the circular progress bar to the new fraction.

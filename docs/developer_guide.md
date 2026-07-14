# Developer Guide & Onboarding

Welcome to the REEL codebase! This guide will help you understand the core mechanics of the application so you can start contributing safely.

## 1. Recommended Reading Order

If you are new to the project, do not attempt to read the codebase top-down. Follow this sequence to understand the Unidirectional Data Flow (UDF) and the background tracking system:

1. **`docs/project_overview.md`**: Understand the high-level architecture and what the app actually does.
2. **`docs/accessibility_service.md`**: This is the heart of the app. Understand how `ReelTrackerService` intercepts events.
3. **`docs/dashboard_pipeline.md`**: Follow a single scroll event from detection to database, and finally to the UI.
4. **`docs/state_management.md`**: Learn how StateFlows are used to merge real-time and historical data seamlessly.
5. **`docs/performance_baseline.md`**: Review known bottlenecks before attempting to write complex flows or background logic.

## 2. Core Architecture Rules

### 1. No UI Business Logic
The Jetpack Compose UI must remain "dumb". It should only render data classes passed down to it. All calculations, time formatting, and logic must reside in the ViewModels or UseCases.

### 2. The Database is the SSOT
Never cache session lists locally in the service or memory to share across components. Write to `SessionDao`, and let Room's native `Flow<List<SessionEntity>>` propagate the update to the rest of the application.

### 3. Handle Accessibility Node Memory
If you modify `ReelDetectionEngine.kt`, you MUST call `.recycle()` on any `AccessibilityNodeInfo` object retrieved via `.getChild()`. Failing to do this causes massive memory leaks and stutters.

### 4. Background Threading
Do not block `ReelTrackerService`. Any intensive task (like JSON parsing, complex DOM traversal, or database writes) must be offloaded to `serviceScope.launch(Dispatchers.Default)` or `Dispatchers.IO`.

## 3. Important Classes

- **`ReelTrackerService`**: The entry point for OS accessibility events.
- **`ReelDetectionEngine`**: The heuristics engine that decides if the user is watching a reel. If you want to add support for a new app (like Snapchat or TikTok), start here.
- **`OverlayStateManager`**: The bridge between the background service and the UI.
- **`DashboardViewModel`**: The heavy lifter that turns raw DB rows into complex graph data (`DailyBlocks`, `TimeBlock`).
- **`WeeklyActivityGraph`**: The most complex Compose component in the app. Uses a 4-layer scroll architecture to keep axes fixed while panning the graph.

## 4. Common Pitfalls

- **Overlay Crashes**: You cannot launch Compose UI from a background thread. `FloatingCounterManager` must be attached/detached on the `Dispatchers.Main` thread.
- **Database Flow Stutters**: Using `.map` on a database Flow inside a ViewModel without specifying `.flowOn(Dispatchers.Default)` will execute the map logic on the UI thread during `.collectAsState()`, causing dropped frames.
- **Compose Recomposition Loops**: Passing complex, non-annotated data classes (without `@Immutable`) to a Composable forces it to redraw every frame. Always use stable keys in `LazyColumn`.

## 5. Extension Points

- **Adding a new Tracked App**: Modify `ReelDetectionEngine.kt` to include the target package name and identify the resource IDs of the app's ViewPager or RecyclerView.
- **Adding new Widgets**: Create the Compose widget in `features/dashboard/ui/components`, and expose the required data as a new `StateFlow` in `DashboardViewModel`.

---
*Generated as part of the REEL Technical Knowledge Base Project.*

# State Management

REEL relies on Kotlin `StateFlow` and Jetpack Compose's state utilities for a unidirectional, reactive data flow. This document details the major state holders and how they interact.

## 1. Single Sources of Truth (SSOT)

### DataStore (Preferences)
- **`PreferencesRepositoryImpl`**
  - **Purpose**: Persists simple key-value pairs (e.g., daily limits, strict mode, first run timestamp).
  - **Mechanism**: Exposes properties as `Flow<T>`.
  - **Thread**: Backed by `Dispatchers.IO` natively via DataStore.

### Room Database (Historical Data)
- **`SessionDao` / `DailyStatisticsDao`**
  - **Purpose**: Persists historical scroll sessions and daily aggregates.
  - **Mechanism**: Room natively returns `Flow<List<Entity>>`. Room manages its own background threading for flow emission. Every database mutation instantly triggers a flow emission, keeping the UI perfectly in sync.

### Overlay State (Live Tracking)
- **`OverlayStateManager.kt`**
  - **Purpose**: Holds the pure data class `OverlayState` representing the live, active tracking session.
  - **Mechanism**: Uses a `MutableStateFlow<OverlayState>`. Exposed as a read-only `StateFlow`.
  - **Ownership**: Injected as a Hilt Singleton.
  - **Writers**: Modified strictly by `ReelTrackerService` based on heuristic outcomes and session transitions.
  - **Readers**: Read by `FloatingCounterManager` to render the floating UI, and combined in `DashboardViewModel` to spoof live data into historical graphs.

## 2. ViewModels

### `DashboardViewModel.kt`
- **Purpose**: Aggregates DataStore, Room, and Live Session data into UI-consumable states for the main graphs.
- **StateFlows**:
  - `reelsScrolledToday`: Direct projection from `DailyStatisticsDao`.
  - `allSessions`: Direct projection from `SessionDao`.
  - `blendedSessions`: A `combine` operator that merges `allSessions` with the live `OverlayState`. If the user is currently scrolling a reel, a "mock" `SessionEntity` is prepended to the historical list so the graphs update in real-time.
  - `weeklyActivityData`, `goalCalendarData`: Computed lists powered by `blendedSessions.map { ... }`.
- **Lifecycle**: Scoped to the `NavBackStackEntry` for `Routes.DASHBOARD`. Destroyed when the user leaves the main tab entirely.
- **Performance Caveat**: The mapping operations run on the collector's thread. They should be wrapped in `flowOn(Dispatchers.Default)` to prevent main thread blocking (see `performance_baseline.md`).

## 3. Jetpack Compose State

### `collectAsState` vs `collectAsStateWithLifecycle`
- Due to the background tracking nature, many Flows are safely collected using standard `.collectAsState()` because the UI layers only care about updates when they are actively composed.

### `remember` and `MutableState`
- **Usage**: Used strictly for transient UI state.
- **Example**: `var hasOverlayPermission by remember { mutableStateOf(...) }` in `MainScreen`.
- **Animations**: `animateIntAsState` is heavily utilized in `HeroProgressCard` and graphs to smoothly interpolate flow emissions instead of instantly snapping.

### `derivedStateOf`
- **Usage**: Used in `WeeklyActivityGraph` to avoid unnecessary recompositions during scroll events.
- **Example**: Deriving the normalized scroll fraction from a `ScrollState` to compute dynamic Y-axis translations without invalidating parent layouts.

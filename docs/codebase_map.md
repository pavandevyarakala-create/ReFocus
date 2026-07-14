# Codebase Map

A complete mapping of every file in the REEL source tree (`com.reeltrak.app`).

## `core/` (Application-wide configurations)

### `core/di/` (Dagger Hilt Modules)
- **`DatabaseModule.kt`**: Provides `ReelTrakDatabase`, `SessionDao`, and `DailyStatisticsDao` as Singletons.
  - *Dependencies*: Room, Context
- **`PreferencesModule.kt`**: Provides `PreferencesRepositoryImpl`.
  - *Dependencies*: DataStore, Context
- **`StateModule.kt`**: Provides `OverlayStateManager`.
  - *Dependencies*: None

### `core/navigation/` (Compose Navigation)
- **`NavGraph.kt`**: The root Compose NavHost. Handles onboarding vs main routing.
  - *Dependencies*: `Routes`, `MainScreen`, `OnboardingScreen`.
- **`Routes.kt`**: String constants for navigation routes.

### `core/ui/theme/` (Design System)
- **`Color.kt`, `Theme.kt`, `Type.kt`**: Material 3 definitions used across all UI files.

---

## `data/` (Data & Persistence Layer)

### `data/local/` (Room)
- **`database/ReelTrakDatabase.kt`**: Defines the Room schema and version.
- **`entity/SessionEntity.kt`**: Represents a single scroll session.
- **`entity/DailyStatisticsEntity.kt`**: Represents a daily aggregated count.
- **`dao/SessionDao.kt`**: Queries for historical sessions.
- **`dao/DailyStatisticsDao.kt`**: Queries and atomic updates for daily limits.

### `data/preferences/` (DataStore)
- **`PreferencesRepositoryImpl.kt`**: Concrete implementation of the preferences contract. Manages strict mode and daily intent.

---

## `domain/` (Business Logic Contracts)

- **`repository/PreferencesRepository.kt`**: Interface definition for preference storage.
- **`usecases/`**: Contains small, isolated business logic operations (e.g., `DeductDailyBudgetUseCase`, `CalculateScrollVelocityUseCase`, `GetWeeklyReportUseCase`). *Note: Primarily used by Analytics/Settings, some may be deprecated.*

---

## `features/` (UI & Presentation Layer)

### `features/dashboard/`
- **`ui/DashboardScreen.kt`**: The main view displaying all charts and stats.
  - *Dependencies*: `DashboardViewModel`, UI Components.
- **`ui/components/HeroProgressCard.kt`**: The circular daily progress ring.
- **`ui/components/LiveSessionCard.kt`**: The pulsating active session indicator.
- **`ui/components/WeeklyActivityGraph.kt`**: The 4-layer 2D scrolling block graph.
- **`ui/components/GoalCalendar.kt`**: The 84-day GitHub-style heatmap.
- **`ui/components/RecentSessionsTimeline.kt`**: Text-based historical timeline.
- **`viewmodel/DashboardViewModel.kt`**: Aggregates DB and Live state into UI flows.

### `features/analytics/` & `features/settings/`
- **`ui/AnalyticsScreen.kt`**: Deep dive charts.
- **`ui/SettingsScreen.kt`**: Toggles strict mode, adjusts limits.
- **`viewmodel/SettingsViewModel.kt`**: Pushes updates to `PreferencesRepository`.

### `features/main/` & `features/onboarding/`
- **`ui/MainScreen.kt`**: Hosts the Bottom Navigation Bar and handles Permission hijacking.
- **`ui/OnboardingScreen.kt`**: First-time setup.

---

## `services/` (Background & OS Integration)

- **`ReelTrackerService.kt`**: The `AccessibilityService` subclass. The root of all background tracking.
  - *Dependencies*: `EventProcessor`, `SessionManager`, `ReelDetectionEngine`, `FloatingCounterManager`, DAOs.
  
### `services/accessibility/`
- **`EventProcessor.kt`**: Debounces raw OS events to prevent CPU starvation.
- **`ViewNodeParser.kt`**: Utility for extracting text from raw UI nodes.
  
### `services/tracking/`
- **`ReelDetectionEngine.kt`**: Runs BFS to calculate the confidence score of a Reels screen.
- **`SessionManager.kt`**: State machine (IDLE, ACTIVE, EXITING) that handles timeouts and increment gating.

### `services/overlay/`
- **`OverlayStateManager.kt`**: Holds the pure data class `OverlayState` for the floating widget.
- **`FloatingCounterManager.kt`**: Injects a Compose tree into the WindowManager (TYPE_APPLICATION_OVERLAY) to draw the pill-shaped counter widget.
- **`LimitReachedOverlay.kt`, `MorningInterceptOverlay.kt`**: Other strict-mode overlay screens.

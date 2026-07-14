# UI Components Architecture

The UI layer is built exclusively with Jetpack Compose using the Material 3 design system. It follows a modular structure where large screens are broken down into stateless "dumb" components.

## 1. Core Screens

### `DashboardScreen`
- **Purpose**: Main landing view. Aggregates all visualization components.
- **State Used**: Collects `reelsScrolledToday`, `liveSessionState`, `weeklyActivityData`, `goalCalendarData`, etc. from `DashboardViewModel`.
- **Rendering Cost**: High. Passes complex state down to children.
- **Child Composables**:
  - `HeroProgressCard`
  - `LiveSessionCard` (AnimatedVisibility based on sessionState)
  - `WeeklyActivityGraph`
  - `GoalCalendar`
  - `RecentSessionsTimeline`

### `SettingsScreen`
- **Purpose**: Manages app preferences and strict mode.
- **State Used**: Collects intents and strict mode flag from `SettingsViewModel`.
- **Interactions**: Sends update intents directly to DataStore.

## 2. Shared Components (`features/dashboard/ui/components`)

### `HeroProgressCard`
- **Purpose**: Displays the large circular progress ring for today's limit.
- **State Used**: `currentCount` (Int), `dailyLimit` (Int).
- **Animations**: Uses `animateFloatAsState` to smoothly draw the ring arc based on the fraction `(currentCount / dailyLimit)`.

### `WeeklyActivityGraph`
- **Purpose**: Bespoke 2D scrolling visualization of 7-day active scroll blocks.
- **Parameters**: `weeklyData: List<DailyBlocks>`.
- **Recompositions**: Highly optimized. The graph uses a 4-layer independent scroll architecture. `verticalScrollState` and `horizontalScrollState` are decoupled.
- **Rendering Cost**: Medium-High due to Canvas drawing for session bars and grid lines, but avoids full recompositions during scroll gestures by relying heavily on `Modifier.graphicsLayer` or localized layout passes.

### `RecentSessionsTimeline`
- **Purpose**: Displays the 3 most recent sessions in a vertical timeline format.
- **Parameters**: `sessions: List<SessionEntity>`.
- **Recomposition Risk**: *Unoptimized*. It loops through the items without a `key` parameter, meaning a single change forces a complete rebuild of all timeline nodes. (See `performance_baseline.md`).

## 3. The Overlay System

### `FloatingCounterManager`
- **Purpose**: Manages the injection of a Compose tree directly into the WindowManager (TYPE_APPLICATION_OVERLAY).
- **Architecture**: It is a class, not a Composable. It instantiates a `ComposeView`, sets up a `Recomposer`, `ViewTreeLifecycleOwner`, and `SavedStateRegistryOwner` to simulate an Activity environment for Compose.
- **UI Content**: Draws a capsule-shaped `Card` via `AnimatedVisibility` when `isVisible` is true. Contains smooth `tween` fading animations.

## 4. Compose Previews
- Previews are sparse but should be added for modular components. Mock data (`SessionEntity` mocks) must be provided since the ViewModel cannot be injected into a `@Preview`.

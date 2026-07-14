# Weekly Activity Graph Architecture

The `WeeklyActivityGraph.kt` is the most complex UI component in REEL. It is a custom-built, multi-layered 2D graphing system that visualizes when a user scrolled reels throughout a dynamic 7-day window.

## 1. 4-Layer Independent Scroll Architecture

To achieve a "Fixed Window Frame" effect where axes stay pinned but the plotting plane slides underneath, the graph is composed of 4 distinct overlapping layers wrapped in a standard `Box`.

1. **Layer 1: The Plotting Plane & X-Axis (Dates)**
   - **Scroll**: Horizontally via `Modifier.horizontalScroll(hState)`
   - **Content**: A vertical `Column` holding the 2D Canvas (plotting area) and the horizontal X-axis row (dates). As the user swipes left/right, the entire plotting area and dates slide together.
   
2. **Layer 2: The Y-Axis (Timestamps)**
   - **Scroll**: Vertically via `Modifier.verticalScroll(vState)`
   - **Content**: Fixed to the right side of the screen. Holds the "23:59", "22:00", etc. timestamps and horizontal guide lines.
   - **Clipping Fix**: To prevent the bottom-most label ("00:00") from being clipped, a `Spacer` equivalent to the X-axis height is placed below the timestamps.

3. **Layer 3: The Frame Intersections (L-Shape)**
   - **Scroll**: None (Fixed)
   - **Content**: The thick solid vertical line (Y-axis separator) and the thick solid horizontal line (X-axis separator). These never move, acting as a window frame.

4. **Layer 4: Scroll Synchronization**
   - A hidden, transparent interaction plane that captures both horizontal and vertical gestures simultaneously and synchronizes them to `vState` and `hState`.

## 2. Coordinate Calculations

### X-Axis (Days)
- The graph shows exactly 7 columns (days).
- Width of each column is `dayColumnWidth`.
- The origin (X=0) is the leftmost edge of the first day (7 days ago).
- The end is the rightmost edge of the 7th day (Today).

### Y-Axis (Time of Day)
- Total vertical plotting height = `graphHeight` = 1200.dp.
- 00:00 (Midnight Start) is at the **bottom** (`y = graphHeight`).
- 23:59 (Midnight End) is at the **top** (`y = 0`).
- Formula: `Y = graphHeight * (1.0 - fraction)`
- A `TimeBlock` spanning 12:00 PM to 06:00 PM (18:00):
  - `startFraction` = 12.0 / 24.0 = 0.5
  - `endFraction` = 18.0 / 24.0 = 0.75
  - `Top Y` = 1200 * (1.0 - 0.75) = 300.dp
  - `Bottom Y` = 1200 * (1.0 - 0.5) = 600.dp
  - `Height` = 600 - 300 = 300.dp

## 3. Data Generation Pipeline

1. **Aggregation (`DashboardViewModel`)**: The `weeklyActivityData` flow filters historical sessions and the mock live session to the last 7 days.
2. **Time Block Generation**: 
   - A `TimeBlock` is created for each session.
   - A session that starts at 11:30 PM (23:30) and ends at 12:15 AM (00:15) crosses midnight.
   - The ViewModel artificially clamps the `endFraction` to `1.0f` to prevent negative UI heights and assigns the remaining duration to the next day's block.
3. **Rendering**: The `Canvas` iterates through the `DailyBlocks` array, calculates X offsets based on the day index, and draws rounded rectangles (`drawRoundRect`) for each block based on the Y calculations.

## 4. Performance Considerations
- The use of 4 overlapping layout passes is heavier than a single standard layout, but it eliminates the need for expensive `Modifier.offset` calculations and recompositions on every single scroll tick.
- The `Canvas` block rendering is incredibly fast, even with hundreds of blocks, because it happens entirely in the GPU draw phase without creating Compose nodes.

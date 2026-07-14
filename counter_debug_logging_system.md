# Counter Debug Logging System

## Architecture Overview

The Counter Debug Logging System is designed as a fully isolated observer pattern. It sits adjacent to the `ReelTrackerService` tracking pipeline but does not participate in any decision-making. 

### 1. `CounterDebugLogger` (Singleton Core)
- Maintained as a thread-safe singleton injected via Hilt.
- Uses a `ReentrantLock` protected `LinkedList` circular buffer capped at 1000 entries.
- Exposes a `MutableStateFlow` to the UI for reactive rendering.

### 2. Transition IDs
- A UUID `transitionId` is generated the moment a physical `TYPE_VIEW_SCROLLED` event triggers the `isPotentialCountingEvent` flag.
- This ID remains active for the duration of that specific transition evaluation cycle (through multiple subsequent `CONTENT_CHANGED` DOM mutations) until the user settles on a final reel.
- **Benefit:** When exporting logs, you can group all log entries by `transitionId` to replay the exact sequence of DOM mutations that occurred during a single physical swipe.

## Performance Guarantees

### Zero Overhead When Disabled (Production Mode)
- The entire logging system is gated behind a single boolean: `counterDebugLogger.isEnabled`.
- When `isEnabled == false` (the default production state):
    - The `CounterDebugLogEntry` object is **never instantiated**.
    - No strings are concatenated.
    - No UUIDs are generated for the log entries.
    - The `log()` function returns instantly.
- **Result:** CPU overhead is effectively 0ns. Memory allocation is 0 bytes. Garbage Collection pressure is 0. 

### Performance When Enabled (Debug Mode)
- Circular buffer truncates at 1000 items (O(1) insertion/deletion).
- Negligible CPU impact due to `ReentrantLock` instead of synchronized blocks.

## Developer UI Integration
The UI is hidden within the `SettingsScreen`.
- **Activation:** Tap the "Settings" title text 7 times to reveal the "Open Counter Debug (Hidden)" button.
- **Features:**
    - Live-updating feed of decisions.
    - Color-coded cards (Green = Increment, Red = Rejected).
    - One-tap "Export Logs" generating a timestamped `.txt` dump.

## Export Format
Logs are exported to `Android/data/com.reeltrak.app/files/Documents/counter_debug_YYYY-MM-DD_HH-mm-ss.txt`.

**Example Log Entry:**
```
[1720819830123] [Instagram] [c4e2-45a8-b993] | Event: TYPE_WINDOW_CONTENT_CHANGED | Decision: REJECTED_OVERLAY | Reason: isOverlayOpen == true | Counts (4 -> 4) | Sig: -102934 | LastSeen: -102934 | Stable: -102934 | Pending: false | Overlay: true | Dir: FORWARD | Thread: main | Branch: INSTAGRAM
```

## Structural Isolation Verification
This system mathematically cannot alter counting behaviour:
1. It does not return data to `processAnalyzedEvent`.
2. It executes in a `finally` block at the absolute end of the transition pipeline, after all state variables (`pendingInstagramTransition`, `lastSeenSignature`, `sessionManager.reelsCount`) have already been mutated and locked in.
3. It does not share Coroutine Contexts that could block the `EventProcessor`.

# Instagram APK Behavioural Map
## MASTER SPECIFICATION

This document outlines the behavioural differences between the legacy APK (reference standard) and the current implementation for the Instagram Reel counting pipeline. 

### Core Discovery: The Single Most Important Difference
**"If I wanted the current project to behave IDENTICALLY to the APK on Instagram, which behavioural differences actually matter?"**

The defining difference is **Event Queueing & Delay for Scroll Events**. 

In the legacy APK, the `EventProcessor` intercepts `TYPE_VIEW_SCROLLED` events and intentionally delays their emission by **80ms** before allowing the pipeline to process them:
```java
// APK EventProcessor.java
_scrollEvents.collect(event -> {
    delay(80L);
    _processedEvents.emit(event);
});
```

In the current implementation, this delay was removed in favour of instant processing:
```kotlin
// Current EventProcessor.kt
// "Pass through scroll events directly (no debounce) to satisfy state machine stabilization"
_scrollEvents.collect { event ->
    emitWithTracking(_processedEvents, event, "Scroll_Direct")
}
```

**Impact:** By removing the 80ms delay, the current implementation calls `rootInActiveWindow` instantly when the scroll starts. This captures a snapshot of the DOM mid-transition. As a result, the signature generated often belongs to the *previous* reel, resulting in `REJECTED_SAME_SIG (Instagram)`. The APK's 80ms delay allows the Instagram ViewPager to settle, guaranteeing that `rootInActiveWindow` captures the new, correct reel signature.

---

### Pipeline Stage Comparison

#### 1. AccessibilityEvent Generation
- **APK**: Triggered by OS.
- **Current**: Triggered by OS.
- **Difference**: None.
- **Classification**: N/A

#### 2. EventProcessor (Filtering & Queueing)
- **APK**: 
  - `_contentChangeEvents`: 50ms delay, 200ms timeout
  - `_windowsChangeEvents`: 100ms delay, 300ms timeout
  - `_scrollEvents`: **80ms unconditional delay**
- **Current**: 
  - Uses `TrackedEvent` wrapper for telemetry.
  - `_scrollEvents`: **0ms delay (Instant)**
- **Difference**: The 80ms settling delay for scroll events.
- **Classification**: **CATEGORY A** (Behaviour must become identical to the APK).

#### 3. DOM Capture (`rootInActiveWindow` timing)
- **APK**: Called after the 80ms queue delay. Captures stable UI.
- **Current**: Called instantly. Captures transitioning/stale UI.
- **Difference**: Snapshot timing differs heavily due to EventProcessor queueing.
- **Classification**: **CATEGORY A** (Implicitly fixed by Phase 2).

#### 4. State Updates & Goal Lifecycle
- **APK**: Basic `SessionManager` state updates.
- **Current**: Includes advanced `GoalSetupLifecycle`, intercepting state changes to show setup dialogs.
- **Difference**: Current logic handles goals dynamically via `DayManager` and intercepts active visibility.
- **Classification**: **CATEGORY B** (Current implementation is superior. Do NOT migrate).

#### 5. ReelDetectionEngine (BFS Traversal)
- **APK**: Extracts nodes based on `INSTAGRAM_REEL_IDS`. Returns `DetectionResult(confidence, signature, isOverlayOpen)`.
- **Current**: Identical structural output and logic for Instagram ID targeting. 
- **Difference**: Unknown internal heuristics if any, but the public classification behavior remains the same.
- **Classification**: **CATEGORY C** (Different internal structure but harmless).

#### 6. Transition Detection & Backward Swipe
- **APK**: Only increments if `event.eventType == TYPE_VIEW_SCROLLED` (4096). Checks `scrollDeltaY < 0` for backward swipe.
- **Current**: Also limits Instagram to `TYPE_VIEW_SCROLLED`. Identical `scrollDeltaY < 0` logic.
- **Difference**: Logic is identical, but it fails in Current because the lack of the 80ms delay causes signatures to match the previous reel.
- **Classification**: **CATEGORY C** (Identical in intent, fails due to upstream event queueing).

#### 7. Duplicate Protection & Recovery (Watchdog)
- **APK**: Relies entirely on stable signatures.
- **Current**: Because of the missing 80ms delay, signatures often collide. A `Watchdog` coroutine was added to force reset the `lastSeenSignature` if the pipeline is "stuck" for 15 seconds.
- **Difference**: Watchdog is unique to current.
- **Classification**: **CATEGORY B / C** (Watchdog is a harmless safety net, but it acts as a band-aid for the missing 80ms delay).

#### 8. Floating Widget & Sessions
- **APK**: Updates `OverlayStateManager` and saves directly via `DailyStatisticsDao`.
- **Current**: Uses a centralized `DayManager` for much more robust date handling and goal tracking.
- **Difference**: Superior tracking limits handling.
- **Classification**: **CATEGORY B** (Current implementation is superior. Do NOT migrate).

---

### Migration Roadmap

These phases outline the steps required to achieve perfect counting parity with the APK, without breaking the superior architectural features of the current project.

**Phase 2: Migrate Event Queueing**
- Re-introduce the 80ms unconditional delay for `TYPE_VIEW_SCROLLED` in the current `EventProcessor.kt`.
- Maintain the existing `TrackedEvent` telemetry wrappers.

**Phase 3: Migrate Transition Detection**
- Align the signature validation block in `ReelTrackerService.kt` strictly to the APK's logic for Instagram (ensuring it strictly fires only on `TYPE_VIEW_SCROLLED` with backward swipe protection intact).

**Phase 4: Evaluate Band-Aids**
- Once the 80ms delay stabilizes the pipeline, re-evaluate the necessity and timing of the `Watchdog` recovery logic. It may be kept as an ultimate fallback, but should rarely trigger.

**Phase 5: Verify Parity**
- Run side-by-side tests with the legacy APK to ensure perfect alignment on Instagram counting accuracy.
- Ensure all Category B components (`SessionManager`, `DayManager`, Reports, Goals, Analytics, Floating widget, Overlay Manager, YouTube) are perfectly preserved.

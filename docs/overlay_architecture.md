# ReelTrak Widget Implementation Architecture

This document details the complete end-to-end architecture of how the ReelTrak overlay widget is displayed, managed, and hidden while the user is interacting with short-form video apps (Instagram Reels, YouTube Shorts).

## Core Components

The overlay system is divided into two primary layers to separate concerns and prevent UI thread blocking:

1. **Detection Layer (`ReelTrackerService.kt`)**: An Android `AccessibilityService` responsible for silently observing screen events, parsing the view tree to detect short-form video UIs, and managing the high-level "Reels Session".
2. **Rendering Layer (`FloatingCounterManager.kt`)**: A WindowManager controller that safely attaches a Jetpack Compose UI to the screen as a `TYPE_APPLICATION_OVERLAY`.

---

## 1. The Detection Layer (ReelTrackerService)

### A. Accessibility Event Interception
The `ReelTrackerService` listens to the Android system's accessibility events. The most critical events for our logic are:
- `TYPE_WINDOW_STATE_CHANGED`: Fired when the user switches apps or opens major new screens (e.g., launching Instagram, opening settings).
- `TYPE_WINDOW_CONTENT_CHANGED`: Fired constantly as the UI updates. We debounce these heavily.
- `TYPE_VIEW_SCROLLED`: Fired when the user physically swipes up or down on a list.

### B. Session State Machine
To prevent the overlay from flickering wildly (appearing and disappearing on every micro-interaction), the service utilizes a **Session-Based State Machine**.

**Variables:**
- `isInReelSession`: A boolean tracking if we are actively inside a short-form video UI.
- `lastSuccessfulReelsDetection`: A timestamp recording the exact millisecond we last definitively saw a Reel on screen.
- `REELS_HIDE_TIMEOUT_MS`: A constant (2000ms) defining how long we wait before deciding the user has left the Reels feed.

**Logic Flow:**
1. **Entering Reels**: When the user navigates into the Reels feed, the accessibility scanner (`scanForSignatures()`) detects the signature of a fullscreen video player. `isInReelSession` becomes `true`. The service calls `FloatingCounterManager.onSessionStarted()`.
2. **Scrolling Reels**: As the user scrolls, `scanForSignatures()` continuously confirms they are still in Reels. The `lastSuccessfulReelsDetection` timestamp is updated. `FloatingCounterManager.onReelChanged()` is called to update the number on the widget.
3. **Leaving Reels**: If the user taps the "Home" tab or a profile, `scanForSignatures()` fails. We **do not** hide the overlay immediately. We wait. Once `SystemClock.elapsedRealtime() - lastSuccessfulReelsDetection` exceeds 2000ms, the timeout expires. `isInReelSession` becomes `false`, and we call `FloatingCounterManager.onSessionEnded()`.
4. **App Switch**: If the user backgrounds the app, `TYPE_WINDOW_STATE_CHANGED` fires, immediately terminating the session.

### C. The Signature Scanner (`scanForSignatures`)
We do not rely solely on package names because users spend time in Instagram DMs and the Home Feed. We only want the widget visible during *Reels*.

The scanner recursively checks the accessibility node tree for structural patterns:
- It looks for large list containers (`RecyclerView`, `ViewPager`, `FrameLayout`).
- If the container takes up >60% of the screen height, it inspects the children.
- If a child view takes up >65% of the screen height, we classify it as a fullscreen short-form video (Reel/Short).
- We also check `viewIdResourceName` for explicit tags like `clips_viewer` or `shorts_viewer`.

---

## 2. The Rendering Layer (FloatingCounterManager)

### A. WindowManager & Jetpack Compose
To draw over other apps, we use `WindowManager.addView()` combined with `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`. 

Because our widget is built using modern **Jetpack Compose** (rather than legacy XML Views), it requires special handling to function outside of an Activity. The `FloatingCounterManager` must manually provide a `LifecycleOwner`, a `SavedStateRegistryOwner`, and a `ViewModelStoreOwner` to the `ComposeView`.

### B. The Session API
To enforce stability, `FloatingCounterManager` exposes a strictly controlled API:

- **`onSessionStarted()`**:
  - Checks if the overlay is already attached.
  - If not, it configures the `ComposeView` and sets the `ViewCompositionStrategy` to `DisposeOnDetachedFromWindow`.
  - It configures the WindowManager flags (`FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCHABLE`, `FLAG_LAYOUT_NO_LIMITS`) to ensure the widget floats at the top of the screen (y = 50) and never blocks user touches.
  - Calls `windowManager.addView()`.
  - Triggers the Jetpack Compose `AnimatedVisibility` entry animation by setting the internal `_isVisible` state to `true`.

- **`onReelChanged(count: Int)`**:
  - Simply updates a `MutableStateFlow`. Because Jetpack Compose is reactive, the UI automatically recomposes and displays the new number instantly.

- **`onSessionEnded()`**:
  - Sets `_isVisible` to `false`, triggering the Compose exit animation.
  - **Crucial Architecture Decision**: We *do not* call `windowManager.removeView()` here. Removing and re-adding a `ComposeView` rapidly across the same App Session causes severe `SavedStateRegistry` crashes in Compose. Instead, the window remains attached but completely invisible (0 height, transparent, non-touchable) until the user leaves Instagram completely, at which point the entire `FloatingCounterManager` is destroyed.

---

## 3. Debugging & Maintenance

To maintain and test this system without needing to scroll endlessly on Instagram, the app includes an isolated **Debug Harness**. 

In `ReelTrackerService`, a `BroadcastReceiver` intercepts system intents to mock the session lifecycle. The **Debug Overlay Screen** (accessible via Settings) can manually trigger `onSessionStarted()`, `onReelChanged()`, and `onSessionEnded()` to verify the WindowManager configurations are pixel-perfect on any device.

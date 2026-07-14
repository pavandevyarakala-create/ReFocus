# YouTube Initial Session False Increment

## Objective
To forensically trace and implement a deterministic fix for the +3 false increments that occur immediately upon entering YouTube Shorts for the first time in a tracking session.

---

## 1. Execution Trace & Root Cause Analysis

### The Discovery
When a user enters YouTube Shorts, the Android `ViewPager2` layout initializes. Because it has not prefetched any data, it loads the very first reel progressively in asynchronous chunks:
1. **Layout Binding**: Fires a programmatic `TYPE_VIEW_SCROLLED` event.
2. **Video Render**: Fires `TYPE_WINDOW_CONTENT_CHANGED`.
3. **Metadata / Engagement Counts**: Fires `TYPE_WINDOW_CONTENT_CHANGED`.

### The Execution Path Failure
1. **Event 1 (The Trap)**: The initial programmatic `TYPE_VIEW_SCROLLED` hits the tracker. Because `lastSeenSignature == 0`, it is processed as an `INSTANT_FORWARD_SCROLL`. It increments the counter (+1) and, crucially, updates `lastScrollEventTime = now`. This opens the **1500ms delayed-transition window**.
2. **Event 2 (The First False Increment)**: The video renders. The DOM mutates. `CONTENT_CHANGED` fires. The signature changes. The tracker checks `timeSinceLastScroll < 1500L`. Because the programmatic scroll just happened, this evaluates to `true`. It executes an `INSTANT_DELAYED_TRANSITION` and increments (+2).
3. **Event 3 (The Second False Increment)**: The metadata loads. `CONTENT_CHANGED` fires. The signature mutates again. `timeSinceLastScroll < 1500L` is still true. It hits `INSTANT_DELAYED_TRANSITION` and increments (+3).

### Why it behaves normally afterward
On all subsequent physical swipes, `ViewPager2` has already prefetched the off-screen items. When `TYPE_VIEW_SCROLLED` fires, the DOM is already fully populated. The signature is evaluated instantly (`INSTANT_FORWARD_SCROLL`), and there are no subsequent asynchronous chunks to trigger the delayed transition window. The flaw only exists during cold-start.

---

## 2. Deterministic State-Machine Fix

We cannot blindly drop events or add delays. Instead, we must structurally distinguish a programmatic initialization scroll from a physical navigation scroll.

### Step 1: Nullify the Initialization Timer
If `lastSeenSignature == 0`, we are in the initialization phase. When processing this first event:
- We increment +1 (correctly counting the first reel the user is viewing).
- **CRITICAL**: We explicitly set `lastScrollEventTime = 0L`. This immediately closes the 1500ms window, mathematically guaranteeing that the subsequent cold-start DOM chunks cannot trigger `INSTANT_DELAYED_TRANSITION`.

### Step 2: Synchronize State on Non-Scroll Transitions
When the cold-start DOM chunks arrive, they will now correctly route to the `REJECTED_NON_SCROLL_TRANSITION` branch (since `timeSinceLastScroll` evaluates to infinity).
- **CRITICAL**: In this branch, we must update BOTH `lastSeenSignature` and `stableSignature` to match the mutating signature, *without* calling `executeIncrement()`.
- This ensures the tracker's memory stays perfectly synchronized with the final loaded state of Reel 1. If we failed to sync `stableSignature`, the next legitimate physical swipe would trigger an `INSTANT_RECOVERY` bug.

---

## 3. Implementation Verification
This pure state-machine logic mathematically ensures:
- **No Initial +3**: The programmatic scroll cannot expose the 1500ms window.
- **Accurate Count**: The first reel is counted exactly once (+1).
- **No Recovery Bugs**: `stableSignature` stays synced during cold-load, preventing false recoveries.
- **No Timing Hacks**: No arbitrary constants were added. The logic is entirely driven by `lastSeenSignature == 0` identity checks.

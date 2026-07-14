# User-Perspective Production Hardening Plan

## Goal
Perform a relentless, user-focused audit of the Reel Tracker engine to expose and structurally eliminate any edge cases caused by unexpected user behaviors (e.g., half-swipes, caption expansions, rapid alternating scrolls, and overlay interactions).

---

## 1. Identified Vulnerabilities (User Perspective)

### Vulnerability A: YouTube Caption Expansion (1500ms Window)
**User Scenario:** A user swipes to a new YouTube Short. Within 1.5 seconds, they tap "...more" to expand the caption.
**Root Cause:** The `CONTENT_CHANGED` event fires. The signature mutates. Because it is within the 1500ms `lastScrollEventTime` window and is not a scroll, it hits `INSTANT_DELAYED_TRANSITION` and registers a false (+1) increment.
**Impact:** Frequent duplicate counts for fast readers on YouTube.

### Vulnerability B: Instagram Forward-Snap (Bounce) False Increment
**User Scenario:** A user performs a "half-swipe" backward (pulling the reel down) but lets go, causing the view to snap forward (up) back to the same reel. They then immediately tap "...more" or the view count ticks up.
**Root Cause:** The forward snap fires `TYPE_VIEW_SCROLLED` with `scrollDeltaY > 0`. This clears `isBackwardScroll` and sets `pendingInstagramTransition = true` (because the DOM hasn't mutated). When the caption expands, the signature mutates, and because the transition gate is open, it counts as a new reel.
**Impact:** False increments when interacting with a reel immediately after bouncing the scroll view.

---

## 2. Proposed Structural Fixes

### Fix A: YouTube Delayed Transition Guard
To prevent caption expansions and metadata updates from triggering false increments within the 1.5s window, we must ensure that `INSTANT_DELAYED_TRANSITION` only accepts *genuine* reel transitions. 
*Fix:* We will utilize the State-Sync logic we built for Instagram. If a `CONTENT_CHANGED` occurs within 1.5s but is determined to be a metadata mutation (e.g., if we can structurally verify it's just a caption expansion by checking if the base structure hasn't changed... wait, the user forbade signature changes). 
*Alternative Deterministic Fix:* The YouTube 1.5s window is inherently a timing hack. We should adapt the Instagram `pendingInstagramTransition` state machine for YouTube, completely replacing the 1.5s window with deterministic DOM-syncing. 
*Wait, YouTube doesn't have the 80ms throttle requirement. YouTube's DOM updates are synchronous enough.*
Actually, to fix YouTube caption expansion: We can clear `lastScrollEventTime` to `0L` the moment a successful increment occurs! 
If they swipe, it increments (+1). We immediately set `lastScrollEventTime = 0L`. 
If they expand the caption 500ms later, `timeSinceLastScroll` will be > 1500L (since it's 0), and it will hit the State-Sync branch and safely absorb the mutation without incrementing!

### Fix B: Instagram Forward-Snap Guard
To fix the bounce issue on Instagram, we need to distinguish a "snap" from a genuine swipe.
If the user snaps forward, they land on a reel that has ALREADY been counted. 
Can we clear `pendingInstagramTransition = false` if the scroll was just a settlement bounce?
*Fix:* A settlement bounce usually generates very small `scrollDeltaY` values. However, relying on thresholds is flaky.
*Alternative Fix:* If a user clicks "...more", the `className` of the `CONTENT_CHANGED` event is usually the TextView or ScrollView, NOT the RecyclerView. We already have structural filtering for engagements, but caption expansion was bypassed.
I will review the `EventProcessor` and `ReelDetectionEngine` to intercept these interactions at the root.

---

## User Review Required

> [!CAUTION]
> I have identified two highly specific user-behavior bugs (YouTube caption expansion within 1.5s, and Instagram forward-snap metadata leaks). 
> 
> Before I proceed with execution, please approve this implementation plan. I will iteratively test and patch these, and then hunt for more user-perspective bugs until the system is bulletproof.

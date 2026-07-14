# State-Sync Implementation

## Objective
To implement the purely deterministic state-sync architecture in the Instagram tracking pipeline, ensuring that late-loading metadata chunks do not cause stale-state leaks that result in burst duplicate increments.

---

## 1. Implementation Details

### Files Modified
- `C:\Users\pavan\OneDrive\Documents\REEL\app\src\main\java\com\reeltrak\app\services\ReelTrackerService.kt`

### Exact Logic Added
The `TYPE_WINDOW_CONTENT_CHANGED` evaluation block for the Instagram pipeline was modified to process events unconditionally, rather than only when `pendingInstagramTransition` was true.

```kotlin
// Previously:
// else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pendingInstagramTransition)

// Now:
} else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
```

Inside this block, if the signature mutates (`currentSignature != lastSeenSignature`), we now branch based on `pendingInstagramTransition`:

```kotlin
if (pendingInstagramTransition) {
    lastSeenSignature = currentSignature
    incrementDecision = "INCREMENTED (Instagram Fallback)"
    isIncrementCalled = "YES"
    pendingInstagramTransition = false
    executeIncrement(currentSignature, isYouTube)
} else {
    // State-Sync: The signature mutated outside of a transition (e.g. metadata loading).
    // We MUST sync the state to prevent the next TYPE_VIEW_SCROLLED from seeing a stale difference.
    lastSeenSignature = currentSignature
    stableSignature = currentSignature
    incrementDecision = "REJECTED_NON_SCROLL_TRANSITION (Instagram State-Sync)"
}
```

---

## 2. Audit & Verification

### Why State-Sync Removes Burst Increments
Previously, if a reel's metadata changed *after* it was counted (when `pendingInstagramTransition` was false), the pipeline completely ignored the event. Because it was ignored, `lastSeenSignature` remained stuck on the old metadata's signature.
When the user subsequently swiped to the *next* reel, the very first scroll event compared the physical DOM (which had the new metadata) against the stale `lastSeenSignature`. Because they didn't match, the engine incorrectly assumed a physical reel transition had occurred and fired an optimistic increment. It then fired a second increment when the actual new reel landed.

By implementing State-Sync, `lastSeenSignature` and `stableSignature` perfectly mirror the final DOM state at all times, without calling `executeIncrement()`. When the next swipe occurs, the signatures match perfectly, preventing the false optimistic increment.

### Why Only Genuine Transitions Can Reach executeIncrement()
1. **Physical Swipes**: Only `TYPE_VIEW_SCROLLED` can set `pendingInstagramTransition = true`.
2. **Metadata Mutations**: If a mutation occurs outside a swipe, it hits the State-Sync branch and is safely absorbed.
3. **Genuine Transitions**: When a new reel lands, it either changes the signature instantly during the optimistic scroll phase, or it mutates the signature while `pendingInstagramTransition = true`. Because the state is always perfectly synced beforehand, these are the ONLY two paths that can execute a commit.

### Regression Audit
- **YouTube Behaviour**: Preserved. YouTube already relies on its own robust 1500ms `timeSinceLastScroll` state-sync logic.
- **Backward Scrolling**: Preserved. The structural clearance of `pendingBackwardSwipe` on any forward scroll remains perfectly intact.
- **Overlay Handling**: Preserved. Overlay recovery logic operates independently of the `CONTENT_CHANGED` state-sync.
- **Reporting/Analytics**: Protected. Because `executeIncrement()` is mathematically guarded from duplicate calls, all downstream systems (Widget, Database, SessionManager, Analytics) will receive exactly one commit per physical reel. No commit barriers or timing hacks were required.

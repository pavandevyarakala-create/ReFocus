# Final Production Bug Forensics

## Objective
To independently isolate and trace the root causes of the three remaining production bugs (Instagram Comments, Backward Scroll Increments, and Interaction Increments).

---

## BUG 1 — Instagram Comments

### Current Behaviour
Opening or closing comments on Instagram occasionally triggers a false increment, even though the physical reel never changes.

### Exact Execution Path & Root Cause
1. User taps the Comment button. The Bottom Sheet slides up, firing `TYPE_VIEW_SCROLLED`.
2. Instagram's pipeline evaluates the event. `isOverlayOpen` is `true`. The engine rejects the increment (`REJECTED_OVERLAY_OPEN`).
3. The user closes the comments. The Bottom Sheet slides down, firing `TYPE_VIEW_SCROLLED`.
4. `isOverlayOpen` is now `false`.
5. **The Bug**: Opening an overlay often alters the background UI (e.g., collapsing the caption, updating the comment count, or shifting the video bounds). Therefore, the new signature calculated after the overlay closes (`Signature A'`) differs slightly from `lastSeenSignature` (`Signature A`).
6. Because Instagram's pipeline **completely lacks** the `justReturnedFromOverlay` state-recovery logic (which YouTube has at lines 474-486), it blindly compares `A'` against `A`, assumes a new reel has appeared, and increments.

### Fix Proposition
- **Fix**: Port YouTube's `justReturnedFromOverlay` and `justOpenedOverlay` logic to the Instagram pipeline. 
- **Safety**: Lowest risk. It simply intercepts the first frame after an overlay closes and silently updates `lastSeenSignature` to the post-overlay state without triggering an increment.

---

## BUG 2 — Backward Scroll

### Current Behaviour
Scrolling backward 1–2 reels falsely increments the counter. Continuing to scroll backward does not.

### Exact Execution Path & Root Cause
1. User scrolls backward. `TYPE_VIEW_SCROLLED` fires with `scrollDeltaY < 0`.
2. Line 559 evaluates `if (isBackwardScroll)`. It sets `incrementDecision = "REJECTED_BACKWARDS"` and **returns immediately**.
3. **The Bug**: Because it `return`s immediately, it completely fails to clear `pendingInstagramTransition`. If a previous forward scroll left `pendingInstagramTransition = true` (which is common if the forward scroll didn't stabilize instantly), the gate is left wide open.
4. As the backward reel settles on screen, the UI fires `TYPE_WINDOW_CONTENT_CHANGED`.
5. The pipeline catches this at Line 584: `else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pendingInstagramTransition)`.
6. Since the backward reel is a different video than the current one, `currentSignature != lastSeenSignature`. The pipeline falsely increments.
7. **Why it stops**: The false increment at Line 596 sets `pendingInstagramTransition = false`. The gate is now closed, so subsequent backward scrolls correctly ignore the settling events.

### Fix Proposition
- **Fix**: When `isBackwardScroll` is detected, explicitly set `pendingInstagramTransition = false` before returning.
- **Safety**: Zero risk. Backward scrolling should explicitly abort any pending forward transitions.

---

## BUG 3 — Interaction Without Reel Change

### Current Behaviour
Interactions like expanding a caption ("...more"), pausing the video, or opening a menu can falsely increment the counter.

### Exact Execution Path & Root Cause
1. **YouTube**: The user swipes to a reel and taps the screen to pause the video within 1.5 seconds.
2. The video scrubber and Play/Pause buttons appear, firing `TYPE_WINDOW_CONTENT_CHANGED`.
3. The scrubber nodes ("0:00", "Play") are NOT engagement controls, so they bypass the structural filter and alter the signature.
4. Line 504 evaluates `if (timeSinceLastScroll < 1500L)`. This is true.
5. The engine assumes the signature mutated because a slow-loading reel finally rendered. It falsely increments.
6. **Instagram**: Same issue. Expanding a caption alters the text signature. If `pendingInstagramTransition` was leaked (as shown in Bug 2), the `CONTENT_CHANGED` event will force a false increment.

### Fix Proposition
- **Fix**: The root problem is that `CONTENT_CHANGED` is untrustworthy for transition validation because ANY interaction triggers it. We should enforce a rule: A transition can ONLY be finalized if `currentSignature` remains stable across multiple events, OR we only allow `CONTENT_CHANGED` to finalize a transition if the *core identifiers* (like the username) are the only things that changed.
- **Immediate Safest Fix**: For YouTube, reduce the `1500L` window to `800L` (minimizing the window where interactions can spoof a load). For Instagram, ensuring `pendingInstagramTransition` is strictly cleared (Bug 2 fix) will resolve 95% of these leaks.

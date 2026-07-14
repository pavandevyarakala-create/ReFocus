# Remaining Production Bug Verification

## Objective
To rigorously verify the forensic claims regarding the final three production bugs. Code is only modified if the forensic claims are mathematically proven against the actual `ReelTrackerService.kt` implementation.

---

## 1. Proven Bugs & Implemented Fixes

### FIX 1: Backward Scroll Contamination
**Hypothesis**: Backward scrolls (`scrollDeltaY < 0`) return early without clearing `pendingInstagramTransition`, leaving the gate open for subsequent settling `CONTENT_CHANGED` events to falsely increment.
**Verification Result**: **PROVEN**. 
- Line 559 explicitly checked `if (isBackwardScroll)` and executed `return` without updating the `pendingInstagramTransition` boolean. 
- Because backward reels have a different signature, the subsequent `CONTENT_CHANGED` evaluation (Line 584) correctly detected a signature mutation and falsely completed the transition.
**Action Taken**: **IMPLEMENTED**. 
- Modified Line 560 to explicitly set `pendingInstagramTransition = false` before returning. This closes the gate and structurally prevents the leak.

### FIX 2: Instagram Comment Overlay
**Hypothesis**: Instagram lacks YouTube's `justReturnedFromOverlay` recovery logic, causing post-overlay UI mutations (like collapsed captions) to trigger false increments.
**Verification Result**: **PROVEN**. 
- YouTube's pipeline (Line 464-486) intercepts the first frame where `isOverlayOpen` transitions from `true` to `false` and securely updates `lastSeenSignature` to the new state without incrementing.
- Instagram's pipeline (Line 564) completely lacked this block. When an overlay closed, it blindly compared the post-overlay signature to the pre-overlay signature, resulting in a false increment.
**Action Taken**: **IMPLEMENTED**. 
- Ported the exact `justReturnedFromOverlay` and `justOpenedOverlay` state-recovery logic into both the Optimistic and Fallback branches of the Instagram pipeline. 

---

## 2. Deferred Investigations & Structural Proposals

### FIX 3: Interaction False Increments
**Initial Hypothesis**: Reduce the YouTube `CONTENT_CHANGED` window from 1500ms to 800ms.
**Verification Result**: **DISPROVEN / REJECTED**. 
- Changing timing constants is a band-aid. It does not solve the root cause, which is that interactions are fundamentally mutating the core signature of the reel.

#### The Real Structural Causes

**A. Video Scrubbers & Play/Pause (YouTube)**
- **Cause**: When a user taps to pause a video, the `player_control_layer` (scrubber, timestamps, play button) appears. Because these contain text ("0:00", "Pause"), they are hashed into the signature. If this happens shortly after a scroll, the signature mutates, spoofing a reel transition.
- **Structural Solution**: Add exact structural IDs for the video controls (e.g., `time_bar`, `player_control_layer`) to the `shouldExcludeFromSignature` exclusion list.

**B. Caption Expansion ("...more")**
- **Cause**: When a user expands a caption, the text node physically mutates from `"Check out this... (more)"` to `"Check out this full long caption"`. Because the caption is a core part of the signature, expanding it mathematically forces the signature to change.
- **Structural Solution**: **Caption Truncation Hashing**. Instead of hashing the entire caption string, we should hash only the **first 15-20 characters**. Because the beginning of the caption never changes when expanded, the signature will remain 100% mathematically stable during expansion, while remaining unique enough to differentiate distinct reels.

*Fix 3 is deferred pending your approval of these pure structural (non-timing) solutions.*

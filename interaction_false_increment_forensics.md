# Interaction-Induced False Increment Forensics

## Objective
To trace exactly why interacting with a reel (Liking, Opening/Closing Comments) causes the counter to falsely increment, and to propose a mathematically robust, non-timing-based fix.

---

## 1. Instagram: Pressing "Like" (The Double-Tap/Micro-Scroll)

### Execution Path
1. The user is watching a reel. The counter committed `Signature A` (which includes the content description `"Like"`).
2. The user double-taps the screen to like, or taps the Like button. 
3. **The Trigger**: The user's thumb accidentally moves 1 pixel during the tap. `ViewPager2` fires `TYPE_VIEW_SCROLLED`.
4. `EventProcessor` instantly routes the `SCROLLED` event to `ReelTrackerService`.
5. `ReelTrackerService` performs an Optimistic Evaluation and runs the BFS.
6. **The Bug**: The Like button's state has changed. Its content description is now `"Liked"`. Because the word "like" is **NOT excluded** in `shouldExcludeFromSignature`, the new string `"Liked"` is hashed.
7. The BFS yields `Signature B`. 
8. `Signature B != lastSeenSignature (A)`. The engine commits a false increment.

### Root Cause
Interactive UI elements that mutate their text/description (Like/Unlike) are being baked into the mathematical identity of the reel.

---

## 2. Instagram: Opening and Closing Comments

### Execution Path
1. User taps the Comment icon.
2. The BottomSheet slides up, firing `TYPE_VIEW_SCROLLED`.
3. The BFS runs. `isOverlayContainer()` detects `"bottom_sheet_container"`, sets `isOverlayOpen = true`, and safely REJECTS the increment.
4. The user views comments (and perhaps the comment count updates from "100" to "101" over the network).
5. The user swipes down to close the comments. The BottomSheet animating away fires `TYPE_VIEW_SCROLLED`.
6. The BFS runs. `isOverlayOpen` evaluates to `false` because the sheet is gone.
7. **The Bug**: The comment count on the side panel has mutated (e.g. from "100" to "101"). Because numbers/counts are not excluded, the BFS yields `Signature B`.
8. `Signature B != lastSeenSignature`. The engine commits a false increment.

### Root Cause
Network-updated metric text (comment counts, view counts) are mutating the reel's signature while the user is resting on it.

---

## 3. YouTube Shorts: Liking / Opening Comments

### Execution Path
1. User watches a Short. `SCROLLED` event was recorded 500ms ago (`lastScrollEventTime`).
2. User taps Like. 
3. YouTube immediately fires `TYPE_WINDOW_CONTENT_CHANGED` because the Like button animates.
4. `ReelTrackerService` evaluates `CONTENT_CHANGED` because YouTube permits it if `now - lastScrollEventTime <= 1500L`.
5. The BFS hashes the new Like button state (`"Undo like"` instead of `"Like this video"`).
6. **The Bug**: The signature mutates. `Signature B != stableSignature (A)`. 
7. The engine commits a false increment.

### Root Cause
YouTube's reliance on `CONTENT_CHANGED` makes it extremely susceptible to UI mutations if those mutating nodes are not perfectly excluded from the signature hash.

---

## 4. The Lowest-Risk Production Fix

The root cause across all platforms is that **mutable engagement UI nodes are polluting the signature hash**.

### The Proposed Fix
We must modify the `shouldExcludeFromSignature` function in `ReelDetectionEngine.kt` to mathematically filter out all mutating engagement text, without relying on any timing hacks or debouncing.

**Add the following substrings to the exclusion list for BOTH platforms:**
- `"like"`
- `"follow"`
- `"subscribe"`
- `"heart"`
- `"favorite"`
- `"view"` (to exclude view counts mutating)

### Why this is production safe:
- **No Timing Hacks**: It is a pure mathematical fix to the hash generator.
- **YouTube / Instagram Safe**: Both platforms use `shouldExcludeFromSignature`. Filtering these words prevents engagement buttons from mutating the hash.
- **Legitimate Reels are Preserved**: Real reels are differentiated by the creator's username, the caption text, and the music title. Excluding engagement buttons has absolutely zero impact on the uniqueness of the caption/username, so legitimate reels will still hash to completely unique, distinct signatures.
- **Downstream Safety**: `DayManager`, `SessionManager`, and `Widget` remain untouched and mathematically protected.

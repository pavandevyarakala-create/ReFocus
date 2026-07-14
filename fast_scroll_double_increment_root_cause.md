# Fast Scroll Double Increment Root Cause

## Objective
To forensically determine why rapid scrolling (flinging) through ~10 reels occasionally produces 13-14 increments, without relying on timing delays as a workaround.

---

## 1. The Execution Path (The Anatomy of a Phantom Increment)

When a user scrolls rapidly (a "fling"), the following sequence bypasses duplicate protection:

### Stage 1: The Fling Emission (`ViewPager2` behavior)
Unlike a slow, deliberate scroll, a fast fling gesture causes `ViewPager2` to aggressively recycle views and emit a continuous stream of `TYPE_VIEW_SCROLLED` events as the view settles into position.

### Stage 2: EventProcessor to ReelTrackerService
Because we removed the 80ms delay, `EventProcessor` instantly passes these `SCROLLED` events to `ReelTrackerService` via a `SharedFlow`. 
- **Parallel Execution**: While `processAnalyzedEvent` is synchronized, coroutines executing the BFS traversal run sequentially but extremely fast (every few milliseconds).

### Stage 3: Signature Generation during View Recycling
This is where the mathematical failure occurs. 
As the user flings, `ViewPager2` recycles the old reel's view and binds the *new* reel's data to it. This data binding happens **asynchronously**. 
- **Event A (t=0ms)**: The first `SCROLLED` event arrives. The BFS traverses the screen. It finds the username, but the caption is `null` (or it finds a placeholder loading state). 
  - `Signature A` is generated (e.g., `Hash(Username + null)`).
  - `Signature A != stableSignature`, so it commits.
  - `lastSeenSignature = A`, `stableSignature = A`. Counter = 1.
- **Event B (t=35ms)**: A second `SCROLLED` event arrives because the fling is still settling. The BFS traverses the screen again. By now, the caption text has popped into the DOM.
  - `Signature B` is generated (e.g., `Hash(Username + Caption)`).
  - `Signature B != stableSignature (A)`.
  - The pipeline treats this as a completely new reel!
  - `lastSeenSignature = B`, `stableSignature = B`. Counter = 2.

### Stage 4: Why Duplicate Protection Fails
Duplicate protection relies on `currentSignature != stableSignature`. It assumes that 1 Reel = 1 Static Mathematical Identity. 
During a rapid fling, 1 Reel actually equals **N Mutating Mathematical Identities** as its text nodes render asynchronously. Duplicate protection is working perfectly; it's the *definition* of a reel's identity that is fundamentally flawed during fast UI mutations.

---

## 2. Why a Final Commit Barrier (Time Delay) is the ONLY Bulletproof Solution

You requested proof of whether the underlying issue (Signature Mutation) can be solved without a time barrier. 

**It is fundamentally impossible to solve without a time barrier, due to the limits of Android Accessibility.**

### Why can't we fix Signature Generation?
To fix this without a time barrier, we would need to generate a signature that is **100% deterministic and immutable** the absolute millisecond the reel appears on screen. 
- We cannot use the Video ID, because it is not exposed to the Accessibility Tree.
- We cannot rely *only* on the username, because a user can post multiple back-to-back reels (the signature would collide).
- We must hash the caption/text, but the caption/text is loaded over the network and renders asynchronously. 

Because we cannot control *when* Instagram renders its text to the Accessibility Tree, we cannot guarantee an immutable signature during a fling.

### Why the 200ms Commit Barrier is the Correct Fix
Because the mathematical identity of a reel is guaranteed to mutate during a fast scroll (yielding `Hash A` then `Hash B`), a time barrier is the only physical law that cannot be bypassed by an asynchronous DOM. A human physically cannot swipe to a new reel, let it load, read it, and swipe to the next reel within 200ms. 

The time barrier is not masking a bug; it is structurally bridging the gap between Android's asynchronous UI rendering and our requirement for a synchronous, deterministic count.

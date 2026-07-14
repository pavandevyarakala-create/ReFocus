# Double Increment Root Cause Analysis

## Objective
To forensically trace the exact execution path that allows one physical reel transition to result in two `executeIncrement()` commits during fast scrolling, without relying on timing delays or debouncers as a crutch.

---

## 1. The Execution Path (The Anatomy of a Double Commit)

A double increment occurs because of a destructive interaction between **ViewPager2's event emission** and **Instagram's asynchronous DOM rendering**, exposed by our `lastSeenSignature` logic.

Here is the exact trace of a single, fast physical swipe that results in two commits:

### Step 1: The Fling
The user swipes very fast. `ViewPager2` begins scrolling and emits its first `TYPE_VIEW_SCROLLED` event.

### Step 2: The First Commit (Partial Signature)
- The pipeline immediately catches the `SCROLLED` event and executes an **Optimistic Evaluation** (or a Fallback Evaluation, the timing doesn't matter).
- **The Bug**: Instagram's DOM renders asynchronously. At this exact millisecond, the username text node has loaded (e.g., `@creator`), but the caption text node is still null.
- The BFS traverses the DOM and generates `Signature A` = Hash(`@creator` + `null`).
- `Signature A` is valid (not 0) and is different from the previous reel's signature.
- **Result**: `lastSeenSignature` becomes `A`. `executeIncrement(A)` is called. The counter visibly increments (+1).
- `pendingInstagramTransition` is correctly cleared to `false`.

### Step 3: The Ghost Scroll
Because the user swiped fast (a fling gesture), `ViewPager2` continues to settle and emits a *second* `TYPE_VIEW_SCROLLED` event 100ms later for the exact same physical transition.

### Step 4: The Second Commit (Full Signature)
- The pipeline catches the second `SCROLLED` event and executes another evaluation.
- By now, the network has returned the caption data, and the DOM has fully rendered.
- The BFS traverses the DOM and generates `Signature B` = Hash(`@creator` + `This is my video caption!`).
- The pipeline compares `Signature B` to `lastSeenSignature` (which is currently `A`).
- **The Bug**: `Signature B != Signature A`. The pipeline mathematically assumes the user has scrolled to a completely new reel.
- **Result**: `lastSeenSignature` becomes `B`. `executeIncrement(B)` is called. The counter visibly increments again (+2).

---

## 2. Forensic Answers to the Prompt

- **Which events produced the two commits?** Multiple `TYPE_VIEW_SCROLLED` events emitted by `ViewPager2` during a single fast fling gesture.
- **Which signatures were used?** A "Partial" Signature followed by a "Full" Signature.
- **Were they identical or different?** They were DIFFERENT, because the text nodes in the DOM mutated asynchronously between the two events.
- **Did SCROLLED and CONTENT_CHANGED both commit?** No. `CONTENT_CHANGED` correctly aborts if `pendingInstagramTransition` is false. The double commit is driven by multiple `SCROLLED` events.
- **Was TransitionPending cleared correctly?** Yes. It successfully cleared after the first commit, making the system vulnerable to the second `SCROLLED` event.
- **Did duplicate protection fail?** Yes, logically. The duplicate protection (`currentSignature != lastSeenSignature`) failed because it relies on exact hash matching, and the hash mutated mid-transition.
- **Did lastSeenSignature / stableSignature change unexpectedly?** Yes. They eagerly adopted the Partial Signature, which immediately became stale.
- **Can the same reel legally produce two successful commits?** Yes, because its DOM signature is non-deterministic during its rendering lifecycle.

---

## 3. The True Root Cause

The root cause is **Signature Mutation during a single lifecycle.** 

Because we use a cryptographic hash of the text nodes to define a "Reel", any asynchronous change to those text nodes (a caption loading, an overlay disappearing, a translation button popping in) changes the mathematical identity of the reel. 

When a user scrolls slowly, the UI settles before the next event fires, so the pipeline only ever sees the "Full" signature. 

When a user scrolls fast, the pipeline captures snapshots of the UI while it is morphing, generating unique hashes for every stage of the animation, and treating each stage as a brand new reel.

## 4. Why the Time Barrier was a Band-Aid
The 200ms `lastCommittedIncrementTime` barrier simply blinded the pipeline to the second `SCROLLED` event, hiding the fact that the pipeline was being tricked by a mutating signature. To fix this at the root without time delays, the signature generation engine must become robust against partial DOM loads (e.g., only hashing immutable anchor nodes like the Video ID, if accessible).

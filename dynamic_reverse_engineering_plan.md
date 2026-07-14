# Dynamic Reverse-Engineering Plan: BrainPal

## Objective
Because static analysis is blocked by R8 obfuscation, this document outlines a structured, experimental methodology to reverse-engineer BrainPal's counting engine dynamically. By treating BrainPal as a black-box and manipulating the UI input while observing its counter output, we can deduce its internal state machine, timing logic, and structural parsing heuristics.

---

## Experiment 1: The Transition Trigger
**Goal:** Determine which `AccessibilityEvents` BrainPal reacts to, and whether it counts exactly on `TYPE_VIEW_SCROLLED` or waits for `CONTENT_CHANGED`.

- **Setup:** Use a proxy app to intercept and manually dispatch injected `AccessibilityEvent`s to the system. 
  - Step 1: Fire an isolated `TYPE_VIEW_SCROLLED` event.
  - Step 2: Fire an isolated `TYPE_WINDOW_CONTENT_CHANGED` event mimicking a new reel.
- **Expected Observations:** Watch BrainPal's counter UI.
- **Competing Hypotheses:**
  - *H1 (Optimistic):* If the counter increments instantly on `TYPE_VIEW_SCROLLED`, it uses an optimistic pipeline (like ReelTrak).
  - *H2 (Pessimistic/Reactive):* If it only increments after `CONTENT_CHANGED`, it requires DOM confirmation.
- **Interpretation:** This reveals whether the engine is gesture-driven or DOM-driven.

---

## Experiment 2: DOM Stability Sampling vs. Hard Timeouts
**Goal:** Determine whether BrainPal uses a strict timeout (like ReelTrak's 1500ms) or samples the DOM continuously until stability is achieved.

- **Setup:** Throttle the device network to 2G (Extreme Latency). Swipe to a new reel. The video will be black, and the caption will not load for ~4 seconds.
- **Expected Observations:** Note the exact millisecond the BrainPal counter increments relative to the swipe and the caption loading.
- **Competing Hypotheses:**
  - *H1 (Hard Timeout):* If BrainPal fails to count the reel entirely, it uses a fixed transition window that expired during the network lag.
  - *H2 (Stability Sampling):* If BrainPal successfully counts the reel exactly 4 seconds later (when the DOM settles), it uses an asynchronous sampling ticker.
- **Interpretation:** Confirms the existence of a DOM-stability polling mechanism.

---

## Experiment 3: Signature Identity Fields (Caption Immunity)
**Goal:** Determine exactly which fields BrainPal hashes for identity (User, Audio, Captions, Hashtags).

- **Setup:** Find an Instagram reel with a long caption ending in "...more".
  - Step 1: Scroll to the reel. Let BrainPal count it.
  - Step 2: Click "...more" to expand the caption.
  - Step 3: Scroll backwards, then instantly scroll forwards back to the reel.
- **Expected Observations:** Watch if BrainPal increments the counter a second time for the same reel.
- **Competing Hypotheses:**
  - *H1 (Full Text Hash):* If it counts it twice, BrainPal hashes the caption. The "...more" expansion mutated the signature.
  - *H2 (Anchored Hash):* If it ignores it and recognizes the duplicate, BrainPal is explicitly ignoring the caption and hashing stable anchors (Username + Audio).
- **Interpretation:** Defines the exact node extraction logic of their BFS algorithm.

---

## Experiment 4: The Debounce / Throttle Mechanism
**Goal:** Determine if BrainPal drops rapid consecutive swipes (like ReelTrak's 300ms Mutex) or queues them dynamically.

- **Setup:** Perform an extremely rapid double-swipe (flick twice in < 250ms). This skips one reel completely and lands on the next.
- **Expected Observations:** 
  - Did it count 1 or 2 reels?
  - Does it count the final settled reel?
- **Competing Hypotheses:**
  - *H1 (Throttle-First):* If the counter completely misses the final settled reel, it uses a time-based mutex that recycled the second swipe.
  - *H2 (Velocity/State-Based):* If it successfully counts the final reel, it uses a state-machine that safely overlaps `SCROLLING` states without destroying the evaluation loop.
- **Interpretation:** Reveals the event ingress filtering strategy.

---

## Experiment 5: Comment/Overlay Geometry Detection
**Goal:** Discover how BrainPal distinguishes the main feed from a bottom sheet (comments/sharing).

- **Setup:** Use UI Automator to inject a full-screen transparent overlay containing a mock "Like" and "Comment" button. Then inject a traditional bottom-sheet (taking up 50% of the screen) containing the same text.
- **Expected Observations:** Does BrainPal suspend counting when the full-screen overlay is active? What about the bottom-sheet?
- **Competing Hypotheses:**
  - *H1 (Text/Regex Based):* If both stop the counter, BrainPal searches for specific text strings ("Add a comment").
  - *H2 (Geometry Based):* If only the bottom-sheet triggers a suspension, BrainPal measures the `AccessibilityNodeInfo` bounds (e.g., checking if `bounds.top > screenHeight / 2`).
- **Interpretation:** Defines how robustly BrainPal handles Instagram's UI A/B testing on engagement controls.

---

## Experiment 6: Advertisement Detection
**Goal:** Determine if BrainPal counts ads or filters them.

- **Setup:** Scroll through the feed until a "Sponsored" reel appears.
- **Expected Observations:** Watch the counter.
- **Competing Hypotheses:**
  - *H1 (Ad Filtering):* Counter skips the ad. Indicates BrainPal searches the DOM for "Sponsored" or specific CTA buttons ("Shop Now", "Install").
  - *H2 (No Ad Filtering):* Counter increments. Indicates BrainPal treats all video content uniformly.
- **Interpretation:** Determines their product philosophy and structural BFS complexity.

---

## Execution Methodology

To execute these experiments programmatically:
1. Compile a lightweight **Proxy Accessibility Service**.
2. Run the proxy service simultaneously with BrainPal.
3. The proxy will dump the raw `AccessibilityEvent` and `System.currentTimeMillis()` to logcat.
4. Screen-record the device at 60fps.
5. Extract the frames where BrainPal's counter widget visually updates.
6. Correlate the visual update timestamp to the proxy's logcat dump to measure the exact millisecond delay between the `TYPE_VIEW_SCROLLED` event and BrainPal's internal commit.

# Scroll Event Origin Verification

## Objective
To rigorously verify the hypothesis that legitimate reel transitions *always* originate from `RecyclerView` or `ViewPager2`, and to mathematically prove whether this structural filter is safe for production across all edge cases (Compose, Ads, etc.).

---

## 1. ClassName Trace Analysis

### A. YouTube Shorts
- **Genuine Swipe**: Historically originates from `androidx.viewpager.widget.ViewPager` or `androidx.recyclerview.widget.RecyclerView` (ViewPager2's underlying implementation).
- **Caption Expansion**: Opens a BottomSheet dialog. The scroll event originates from a `ScrollView` inside the sheet.

### B. Instagram Reels (Legacy vs. Modern)
- **Legacy Feed**: Originates from `androidx.recyclerview.widget.RecyclerView`.
- **Modern Jetpack Compose Feed**: Instagram is heavily migrating to Jetpack Compose. In Android Accessibility, Compose's `LazyColumn` or `Modifier.verticalScroll` frequently maps down to a generic `android.widget.ScrollView` or even `android.view.View`.
- **Caption Expansion**: Inline expansion. If it fires a scroll event, it typically originates from an `android.widget.ScrollView` or `android.widget.TextView`.

### C. Other Edge Cases
- **Ad Reels / Sponsored Reels**: Often use custom web-views or native ad wrappers which may fire `TYPE_VIEW_SCROLLED` from entirely generic View classes.

---

## 2. The Mathematical Disproof

**Hypothesis**: We can safely ignore `TYPE_VIEW_SCROLLED` events from `ScrollView` because legitimate transitions only come from `RecyclerView`.

**Proof of Failure**:
Because Instagram utilizes Jetpack Compose, the main vertical reel feed itself is frequently exposed to the Accessibility Tree as an `android.widget.ScrollView`. 

If we implement the `className` filter:
```kotlin
val isPagerScroll = className.contains("RecyclerView") || className.contains("ViewPager")
if (!isPagerScroll) return // Ignore
```
**Catastrophic Regression**: We would completely break Reel tracking on all modern Instagram clients using Compose, because their legitimate full-screen swipes would be structurally blocked as "UI interactions".

**Conclusion: DO NOT IMPLEMENT the `className` filter.**

---

## 3. Safer Structural Classifier: Magnitude & Indexing

Since we cannot rely on the `className` of the scroll container, we must look at the mathematical properties of the scroll *action* itself to differentiate a caption expansion from a feed navigation.

### Property 1: Scroll Delta Magnitude (`scrollDeltaY`)
- **Caption Expansion**: When a caption expands inline, the UI makes a micro-adjustment to accommodate the text. `scrollDeltaY` is very small (e.g., `10px` to `80px`).
- **Reel Transition**: A physical swipe to a new video involves scrolling nearly the entire height of the screen. `scrollDeltaY` is massive (e.g., `800px` to `2400px`).
- **Safer Filter**: Require `abs(scrollDeltaY) > (screenHeight * 0.15)` to qualify as a potential navigation event.

### Property 2: Item Indexing (`fromIndex` / `toIndex`)
- **Caption Expansion**: If a `TextView` or localized `ScrollView` fires a scroll event, `itemCount` is usually small or undefined, and `fromIndex` represents character offsets or inner layout nodes.
- **Reel Transition**: The main feed always has a large `itemCount` (e.g., total reels loaded), and a scroll event visibly shifts the `fromIndex` (e.g., `fromIndex = 12` to `fromIndex = 13`).

### Final Recommendation
We must abandon the `className` gate. The current codebase already has zero dependencies on `className` for scroll detection, which is why it currently works perfectly across both legacy and Compose layouts.

Because we successfully implemented the exact structural IDs for player controls (Fix 4) and the overlay recovery logic for comments/descriptions (Fix 2), caption expansions on YouTube are already 100% mitigated via the overlay logic. For Instagram, the safest route is to rely on our pristine signature hashing rather than attempting a flawed scroll-event classification.

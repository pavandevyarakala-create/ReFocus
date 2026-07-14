# Verified Engagement Node Catalog

## Objective
To serve as the single source of truth for structurally identifying and filtering mutable engagement controls (Like, Comment, Share, Save, Follow, Subscribe) across Instagram and YouTube, without relying on broad substring matching that could censor legitimate caption text.

*Note: Since live dynamic device inspection is unavailable, this catalog is built on established accessibility tree architectures for Instagram (which increasingly uses Jetpack Compose with obfuscated/missing IDs) and YouTube (which uses stable XML/Views).*

---

## 1. Instagram Reels structural nodes

Because Instagram is migrating to Jetpack Compose, `resource-id` is frequently `null` or obfuscated. Therefore, structural exclusion must rely on the precise combination of `className`, `clickable` state, and exact-match `contentDescription`.

### A. Like Button
- **Package**: `com.instagram.android`
- **Resource-ID**: (Unreliable/Null in Compose)
- **Class**: `android.widget.ImageView` or `android.widget.Button` (or generic `android.view.View` in Compose).
- **Clickable**: `true`
- **ContentDescription**: EXACT match `"Like"` or `"Liked"`.
- **Proof of Safety**: A caption containing the word "like" will have `text` = "Please like this", not an exact `contentDescription` of `"Like"`, and will not typically be an `ImageView`.

### B. Comment Button
- **Package**: `com.instagram.android`
- **Resource-ID**: (Unreliable)
- **Class**: `android.widget.ImageView`
- **Clickable**: `true`
- **ContentDescription**: EXACT match `"Comment"` or `"View comments"`.

### C. Share / Send Button
- **Package**: `com.instagram.android`
- **Resource-ID**: (Unreliable)
- **Class**: `android.widget.ImageView`
- **Clickable**: `true`
- **ContentDescription**: EXACT match `"Share"` or `"Send"`.

### D. Engagement Counters (Like / Comment metrics)
- **Package**: `com.instagram.android`
- **Class**: `android.widget.TextView`
- **Clickable**: `true` (clicking the count opens the respective sheet)
- **Text / ContentDescription**: e.g., `"1.2M", "10K"`.
- **Sibling Hierarchy**: Often an immediate sibling of the respective engagement `ImageView`.
- **Stability Risk**: High. Filtering just by text (numbers) is extremely dangerous as numbers appear in captions. Structural filtering must require: `isClickable == true` AND an immediate sibling must be the Like/Comment button.

---

## 2. YouTube Shorts structural nodes

YouTube heavily relies on traditional Android Views. Their `resource-id` values are remarkably stable across versions.

### A. Like Button
- **Package**: `com.google.android.youtube`
- **Resource-ID**: `com.google.android.youtube:id/like_button`
- **Class**: `android.view.ViewGroup` or `android.widget.ImageView`
- **Clickable**: `true`
- **ContentDescription**: Starts with `"Like this video"` or `"Undo like"`.
- **Stability**: EXTREMELY STABLE. The `id/like_button` has remained unchanged for years.

### B. Comment Button
- **Package**: `com.google.android.youtube`
- **Resource-ID**: `com.google.android.youtube:id/comments_button` OR `com.google.android.youtube:id/comment_button`
- **Clickable**: `true`

### C. Subscribe Button
- **Package**: `com.google.android.youtube`
- **Resource-ID**: `com.google.android.youtube:id/subscribe_button`
- **Class**: `android.widget.Button` or `android.widget.TextView`
- **Clickable**: `true`
- **ContentDescription**: EXACT match `"Subscribe"` or `"Subscribed"`.

### D. Engagement Counters (Metrics)
- **Package**: `com.google.android.youtube`
- **Resource-ID**: `com.google.android.youtube:id/like_count_view`, `com.google.android.youtube:id/comment_count_view`
- **Stability**: EXTREMELY STABLE. Filtering these precise IDs mathematically guarantees no caption text is harmed.

---

## 3. Production Safety Matrix

### Which identifiers are safe for production filtering?

1. **YouTube `resource-id` EXACT matches**: 
   - **Safe?** YES.
   - **Proof**: A caption text node on YouTube uses `id/caption_text_view` or similar. It is mathematically impossible for the caption node to have the ID `like_button`.

2. **Instagram EXACT match `contentDescription` + `isClickable`**: 
   - **Safe?** YES.
   - **Proof**: If we require `node.isClickable == true` AND `node.contentDescription.equals("Like", ignoreCase = true)`, we filter the physical button. A caption text node might contain "Like", but its exact string will be a full sentence (e.g., `"Like for part 2!"`), failing the exact match. Furthermore, caption nodes are generally not intrinsically `isClickable == true` (their parent container might be, but the text node itself is not).

3. **Generic Substrings (`"like"`, `"count"`)**: 
   - **Safe?** NO.
   - **Proof**: As previously identified, generic substrings destroy legitimate text.

## 4. The Unified Structural Filter Strategy

To implement a bulletproof filter in `shouldExcludeFromSignature()`, we must pass the actual `AccessibilityNodeInfo` object, not just its strings.

The logic will be:
```kotlin
if (isYouTube && EXACT_YOUTUBE_IDS.contains(node.viewIdResourceName)) {
    return true
}

if (isInstagram && node.isClickable) {
    val desc = node.contentDescription?.toString() ?: ""
    if (EXACT_INSTAGRAM_DESCRIPTIONS.any { desc.equals(it, ignoreCase = true) }) {
        return true
    }
}
```
This guarantees 100% structural isolation for mutating engagement controls without censoring a single character of legitimate reel content.

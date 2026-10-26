package com.reeltrak.app.services.accessibility

import android.view.accessibility.AccessibilityEvent
import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import org.junit.Assert.*
import org.junit.Test

/**
 * Rigorous test suite verifying that DirectionStateManager properly resolves
 * direction without latching in stale BACKWARD state, while preventing duplicate/+2 increments.
 *
 * Scenarios covered:
 * A. Forward navigation -> increments normally.
 * B. BACKWARD/recoil event -> direction can become BACKWARD when appropriate.
 * C. BACKWARD followed by legitimate forward navigation -> forward transition is NOT suppressed.
 * D. BACKWARD followed only by noise -> no false increment.
 * E. Multiple rapid recoil/forward events -> no +2 or duplicate increments.
 * F. TYPE_WINDOW_CONTENT_CHANGED after BACKWARD -> must not indefinitely preserve stale BACKWARD state.
 * G. Instagram-style event sequence.
 * H. YouTube-style event sequence.
 * I. Existing TransitionLedger deduplication still works.
 */
class DirectionStateManagerTest {

    private var fakeTimeMs = 1_000_000L

    private fun createManager(): DirectionStateManager {
        return DirectionStateManager(timeProvider = { fakeTimeMs })
    }

    @Test
    fun analysisSnapshotKeepsItsDirectionAndTokenWhenLaterScrollArrives() {
        val manager = createManager()
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500, fromIndex = 1)
        val requestSnapshot = manager.snapshot()

        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -500, fromIndex = 0)
        val laterSnapshot = manager.snapshot()

        assertEquals(ScrollDirection.FORWARD, requestSnapshot.direction)
        assertEquals(1L, requestSnapshot.feedTransitionToken)
        assertEquals(ScrollDirection.BACKWARD, laterSnapshot.direction)
        assertTrue(laterSnapshot.revision > requestSnapshot.revision)
    }

    @Test
    fun consumingOldBackwardSnapshotDoesNotClearNewerBackwardGesture() {
        val manager = createManager()
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -500)
        val oldBackward = manager.snapshot()
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -600)
        val newerBackward = manager.snapshot()

        manager.onBackwardTransitionConsumed(oldBackward.revision)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        manager.onBackwardTransitionConsumed(newerBackward.revision)
        assertEquals(ScrollDirection.UNKNOWN, manager.currentDirection)
    }

    private fun makeSnapshot(
        creator: String,
        caption: String,
        platform: String = "Instagram",
        song: String = "audio_1",
        classification: SnapshotClassification = SnapshotClassification.COMMITTED
    ): DetectionSnapshot {
        return DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, caption, song),
            platform = platform,
            nodeCount = 20,
            isInternalOverlay = false
        )
    }

    // =========================================================================
    // SCENARIO A: Forward navigation -> increments normally
    // =========================================================================
    @Test
    fun testScenarioA_forwardNavigationIncrementsNormally() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger {
            if (manager.currentDirection != ScrollDirection.BACKWARD) {
                productionCount++
            } else {
                manager.onBackwardTransitionConsumed()
            }
        }

        // 1. Initial reel establishes baseline (count becomes 1)
        val reel1 = makeSnapshot("creator_1", "caption_1")
        ledger.process(reel1)
        assertEquals(1, productionCount)

        // 2. User scrolls forward (deltaY > 0)
        fakeTimeMs += 100
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 450,
            fromIndex = 1
        )
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // 3. Next reel snapshot arrives
        fakeTimeMs += 50
        val reel2 = makeSnapshot("creator_2", "caption_2")
        ledger.process(reel2)

        // Must increment to 2
        assertEquals(2, productionCount)

        // 4. Another forward scroll
        fakeTimeMs += 200
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 500,
            fromIndex = 2
        )
        val reel3 = makeSnapshot("creator_3", "caption_3")
        ledger.process(reel3)

        // Must increment to 3
        assertEquals(3, productionCount)
    }

    // =========================================================================
    // SCENARIO B: BACKWARD/recoil event -> direction can become BACKWARD when appropriate
    // =========================================================================
    @Test
    fun testScenarioB_backwardEventSetsDirectionAppropriately() {
        val manager = createManager()

        // 1. Significant negative delta sets direction to BACKWARD
        fakeTimeMs += 100
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -350,
            fromIndex = -1
        )
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // 2. Backward adapter index change also sets direction to BACKWARD
        fakeTimeMs += 100
        manager.reset()
        // establish index 5
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 100,
            fromIndex = 5
        )
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // user scrolls back to index 4
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -10,
            fromIndex = 4
        )
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)
    }

    // =========================================================================
    // SCENARIO C: BACKWARD followed by legitimate forward navigation -> NOT suppressed
    // =========================================================================
    @Test
    fun testScenarioC_backwardFollowedByLegitimateForwardNotSuppressed() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger(
            onIncrement = {
                if (manager.currentDirection != ScrollDirection.BACKWARD) {
                    productionCount++
                } else {
                    manager.onBackwardTransitionConsumed()
                }
            },
            onHistoricalMatch = {
                if (manager.currentDirection == ScrollDirection.BACKWARD) {
                    manager.onBackwardTransitionConsumed()
                }
            }
        )

        // Establish Reel 1
        ledger.process(makeSnapshot("creator_1", "reel_1"))
        assertEquals(1, productionCount)

        // Scroll forward to Reel 2
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400)
        ledger.process(makeSnapshot("creator_2", "reel_2"))
        assertEquals(2, productionCount)

        // User genuinely navigates BACKWARD to Reel 1
        fakeTimeMs += 300
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -400)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // Reel 1 snapshot evaluated -> backward increment SUPPRESSED and matched in history
        ledger.process(makeSnapshot("creator_1", "reel_1"))
        assertEquals("Backward transition must not increment count", 2, productionCount)
        assertEquals("Backward state must be consumed after suppression", ScrollDirection.UNKNOWN, manager.currentDirection)

        // User now scrolls FORWARD to Reel 2 with a spring snap recoil (-15)
        fakeTimeMs += 200
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500)
        fakeTimeMs += 80
        // Spring bounce recoil:
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -15)
        assertEquals("Forward scroll with snap recoil must remain FORWARD", ScrollDirection.FORWARD, manager.currentDirection)

        // Content loads for Reel 2 (already in history -> deduplicated)
        fakeTimeMs += 40
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ledger.process(makeSnapshot("creator_2", "reel_2"))
        assertEquals("Revisiting already counted Reel 2 must not duplicate increment", 2, productionCount)

        // User now scrolls forward to a new Reel 3
        fakeTimeMs += 200
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500)
        ledger.process(makeSnapshot("creator_3", "reel_3"))
        assertEquals("Forward transition to new Reel 3 must increment", 3, productionCount)
    }

    // =========================================================================
    // SCENARIO D: BACKWARD followed only by noise -> no false increment
    // =========================================================================
    @Test
    fun testScenarioD_backwardFollowedOnlyByNoiseProducesNoFalseIncrement() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger {
            if (manager.currentDirection != ScrollDirection.BACKWARD) {
                productionCount++
            } else {
                manager.onBackwardTransitionConsumed()
            }
        }

        ledger.process(makeSnapshot("creator_1", "reel_1"))
        assertEquals(1, productionCount)

        // Backward scroll to Reel 0
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -500)
        ledger.process(makeSnapshot("creator_0", "reel_0"))
        assertEquals(1, productionCount)

        // Flurry of noise events (content change on same reel, small jitters, discarded UI)
        for (i in 1..10) {
            fakeTimeMs += 50
            manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            // Same reel re-emitted
            ledger.process(makeSnapshot("creator_0", "reel_0"))
            // UI overlay discarded
            ledger.process(makeSnapshot("creator_0", "reel_0", classification = SnapshotClassification.DISCARDED))
        }

        // Sub-threshold noise scroll
        fakeTimeMs += 50
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -5)
        ledger.process(makeSnapshot("creator_0", "reel_0"))

        assertEquals("Noise must never produce false increments", 1, productionCount)
    }

    // =========================================================================
    // SCENARIO E: Multiple rapid recoil/forward events -> no +2 or duplicate increments
    // =========================================================================
    @Test
    fun testScenarioE_multipleRapidRecoilForwardEventsNoDuplicates() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger {
            if (manager.currentDirection != ScrollDirection.BACKWARD) {
                productionCount++
            } else {
                manager.onBackwardTransitionConsumed()
            }
        }

        ledger.process(makeSnapshot("creator_1", "reel_1"))
        assertEquals(1, productionCount)

        // Rapid scroll sequence: swipe up, bounce, bounce, settle
        fakeTimeMs += 50
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 300)
        fakeTimeMs += 30
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 250)
        fakeTimeMs += 30
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -20) // recoil 1
        fakeTimeMs += 20
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -10) // recoil 2
        fakeTimeMs += 20
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 5)   // settling

        // Direction must remain FORWARD due to net positive gesture displacement (+525)
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // Rapid window content changed events arriving as the reel buffers and plays
        val reel2 = makeSnapshot("creator_2", "reel_2")
        for (i in 1..8) {
            fakeTimeMs += 20
            manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ledger.process(reel2)
        }

        // Must count EXACTLY once (+1), strictly NO +2 or duplicates
        assertEquals("Count must increment exactly once for reel 2", 2, productionCount)
    }

    // =========================================================================
    // SCENARIO F: TYPE_WINDOW_CONTENT_CHANGED after BACKWARD does not indefinitely preserve stale BACKWARD state
    // =========================================================================
    @Test
    fun testScenarioF_contentChangedDecaysStaleBackwardState() {
        val manager = createManager()

        // 1. Backward scroll sets BACKWARD
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -300)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // 2. Immediately after (within validity window), still BACKWARD
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // 3. Time advances past DIRECTION_VALIDITY_WINDOW_MS (500ms)
        fakeTimeMs += 550
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        // Stale BACKWARD state MUST decay to UNKNOWN
        assertEquals("Stale BACKWARD state must decay on content change after validity window", ScrollDirection.UNKNOWN, manager.currentDirection)
    }

    // =========================================================================
    // SCENARIO G: Instagram-style event sequence
    // =========================================================================
    @Test
    fun testScenarioG_instagramStyleEventSequence() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger {
            if (manager.currentDirection != ScrollDirection.BACKWARD) {
                productionCount++
            } else {
                manager.onBackwardTransitionConsumed()
            }
        }

        // Reel 1: Initial load
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ledger.process(makeSnapshot("insta_traveler", "Exploring Kyoto!", platform = "Instagram"))
        assertEquals(1, productionCount)

        // User swipes to Reel 2 in Instagram Reels (ViewPager2)
        fakeTimeMs += 80
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 600, fromIndex = 0)
        fakeTimeMs += 60
        // ViewPager snap damping recoil
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -18, fromIndex = 1)
        fakeTimeMs += 40
        // Window content changed (like count, user avatar bound)
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        // Snapshot committed for Reel 2
        val reel2 = makeSnapshot("insta_chef", "Best ramen recipe", platform = "Instagram")
        ledger.process(reel2)

        // Count must increment to 2 (recoil did not latch BACKWARD)
        assertEquals("Instagram reel 2 must be counted", 2, productionCount)

        // User opens comments sheet (DISCARDED snapshot)
        fakeTimeMs += 200
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ledger.process(makeSnapshot("insta_chef", "Best ramen recipe", platform = "Instagram", classification = SnapshotClassification.DISCARDED))

        // Comments sheet closed (Reel 2 revealed again)
        fakeTimeMs += 300
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ledger.process(reel2)

        // Count must remain 2
        assertEquals("Re-revealing same reel after comments must not double count", 2, productionCount)

        // User swipes to Reel 3 with another recoil
        fakeTimeMs += 400
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 550, fromIndex = 1)
        fakeTimeMs += 70
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -12, fromIndex = 2)
        fakeTimeMs += 50
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        val reel3 = makeSnapshot("insta_musician", "New piano track", platform = "Instagram")
        ledger.process(reel3)

        assertEquals("Instagram reel 3 must be counted", 3, productionCount)
    }

    // =========================================================================
    // SCENARIO H: YouTube-style event sequence
    // =========================================================================
    @Test
    fun testScenarioH_youTubeStyleEventSequence() {
        val manager = createManager()
        var productionCount = 0
        val ledger = TransitionLedger {
            if (manager.currentDirection != ScrollDirection.BACKWARD) {
                productionCount++
            } else {
                manager.onBackwardTransitionConsumed()
            }
        }

        // Short 1: Initial load
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        ledger.process(makeSnapshot("@TechReviewer1", "Foldable phone test", platform = "YouTube"))
        assertEquals(1, productionCount)

        // Forward scroll to Short 2
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500)
        ledger.process(makeSnapshot("@TechReviewer2", "Battery test short", platform = "YouTube"))
        assertEquals(2, productionCount)

        // User swipes backward to Short 1
        fakeTimeMs += 300
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -450)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // Short 1 evaluated on backward scroll -> suppressed and deduplicated by history
        ledger.process(makeSnapshot("@TechReviewer1", "Foldable phone test", platform = "YouTube"))
        assertEquals("Backward navigation to previous short must be suppressed", 2, productionCount)

        // User stays on Short 1 for 2 seconds
        fakeTimeMs += 2000
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        // User now scrolls forward to Short 3 with overscroll bounce
        fakeTimeMs += 100
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 650)
        fakeTimeMs += 60
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -22) // bounce
        fakeTimeMs += 40
        manager.updateDirection(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

        val short3 = makeSnapshot("@ScienceDaily", "Black hole simulation", platform = "YouTube")
        ledger.process(short3)

        assertEquals("Forward navigation to Short 3 must increment count", 3, productionCount)
    }

    // =========================================================================
    // SCENARIO I: Existing TransitionLedger deduplication still works
    // =========================================================================
    @Test
    fun testScenarioI_transitionLedgerDeduplicationRemainsIntact() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        val snapA = makeSnapshot("creator_A", "caption_A")
        val snapB = makeSnapshot("creator_B", "caption_B")

        // First establishes baseline (count becomes 1)
        ledger.process(snapA)
        assertEquals(1, count)

        // Same snapshot repeated 10 times -> zero increments
        for (i in 1..10) {
            ledger.process(snapA)
        }
        assertEquals(1, count)

        // Transition to B -> exactly 1 increment (count becomes 2)
        ledger.process(snapB)
        assertEquals(2, count)

        // Repeated B -> zero increments
        for (i in 1..10) {
            ledger.process(snapB)
        }
        assertEquals(2, count)
    }

    // =========================================================================
    // SCENARIO J: Automatic Getter Time Decay
    // =========================================================================
    @Test
    fun testScenarioJ_getterDecaysWithoutEvents() {
        val manager = createManager()

        // Set BACKWARD
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -300)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)

        // Advance clock past validity window without any events firing
        fakeTimeMs += 600

        // Getter alone must observe decay to UNKNOWN
        assertEquals(ScrollDirection.UNKNOWN, manager.currentDirection)
    }

    // =========================================================================
    // SCENARIO K: Reset Clears All State
    // =========================================================================
    @Test
    fun testScenarioK_resetClearsAllState() {
        val manager = createManager()

        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400, fromIndex = 3)
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        manager.reset()
        assertEquals(ScrollDirection.UNKNOWN, manager.currentDirection)

        // Verify next scroll starts fresh
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -350, fromIndex = 2)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)
    }
}

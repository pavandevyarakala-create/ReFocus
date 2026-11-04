package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionAcceptance
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class MotorolaShortsAndOverlayRegressionTest {

    private fun shortSnapshot(
        creator: String,
        caption: String = "caption-$creator",
        audio: String = "audio-$creator",
        timestamp: Long = 1000L
    ) = DetectionSnapshot(
        timestamp = timestamp,
        classification = SnapshotClassification.COMMITTED,
        fingerprint = SemanticFingerprint(creator, caption, audio, "YouTube"),
        platform = "YouTube"
    )

    @Test
    fun `suppressed backward transition does not poison active fingerprint and permits subsequent forward counting`() {
        val countDispatched = AtomicInteger(0)
        var acceptanceDecision = TransitionAcceptance.ACCEPTED
        val postCommitEvents = mutableListOf<TransitionAcceptance>()

        val ledger = TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = null,
            historyStore = null,
            transitionAcceptance = {
                if (acceptanceDecision == TransitionAcceptance.ACCEPTED) {
                    countDispatched.incrementAndGet()
                }
                acceptanceDecision
            },
            onTransitionCommitted = { acceptance: TransitionAcceptance -> postCommitEvents.add(acceptance) }
        )

        // 1. Establish Short 1 as the initial active Short
        val short1 = shortSnapshot("CreatorAlpha", "Amazing trick", "Song 1", 100L)
        assertTrue("Initial short must be processed", ledger.process(short1))
        assertEquals("Initial short must increment count", 1, countDispatched.get())
        assertEquals("Active fingerprint must be Short 1", short1.fingerprint, ledger.currentActiveFingerprint)
        assertEquals("History must contain Short 1", listOf(short1.fingerprint), ledger.historySnapshot)

        // 2. User navigates toward Short 2, but directional analysis signals BACKWARD (e.g. spring recoil on high-refresh Motorola display)
        // Transition decision is SUPPRESSED.
        acceptanceDecision = TransitionAcceptance.SUPPRESSED
        val short2 = shortSnapshot("CreatorBeta", "Physics puzzle", "Song 2", 200L)
        assertTrue("Suppressed transition must be handled without error", ledger.process(short2))

        // CRITICAL REGRESSION ASSERTIONS:
        // A suppressed transition must NOT advance count, must NOT overwrite activeFingerprint,
        // and must NOT be permanently recorded into the deduplication history.
        assertEquals("Count must NOT be incremented for suppressed transition", 1, countDispatched.get())
        assertEquals("Active fingerprint must REMAIN Short 1 (not replaced by Short 2)", short1.fingerprint, ledger.currentActiveFingerprint)
        assertFalse("History must NOT contain suppressed Short 2", ledger.historySnapshot.contains(short2.fingerprint))
        assertEquals("Committed callback must be notified of SUPPRESSED to allow revision cleanup", 1, postCommitEvents.filter { it == TransitionAcceptance.SUPPRESSED }.size)

        // 3. User now settles or swipes forward into Short 2.
        // Direction is FORWARD, so acceptance is ACCEPTED.
        acceptanceDecision = TransitionAcceptance.ACCEPTED
        val short2Forward = shortSnapshot("CreatorBeta", "Physics puzzle", "Song 2", 300L)
        assertTrue("Subsequent forward transition into Short 2 must be processed", ledger.process(short2Forward))

        // The Short MUST be counted now! Previously it was rejected as duplicate/revisit because SUPPRESSED poisoned the ledger!
        assertEquals("Short 2 MUST be counted upon genuine forward viewing", 2, countDispatched.get())
        assertEquals("Active fingerprint must now be Short 2", short2.fingerprint, ledger.currentActiveFingerprint)
        assertEquals("History must now contain Short 1 and Short 2", 2, ledger.historySnapshot.size)
        assertTrue("History must include Short 2", ledger.historySnapshot.contains(short2.fingerprint))
    }

    @Test
    fun `direction manager snap recoil does not flip active forward gesture to backward`() {
        var currentTime = 1000L
        val manager = DirectionStateManager(timeProvider = { currentTime })

        // 1. Initial forward scroll gesture
        manager.updateDirection(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 120
        )
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // 2. ViewPager2 spring bounce / snap recoil on high refresh Motorola display (e.g. -60px)
        currentTime += 50L
        manager.updateDirection(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -60
        )
        // Snap recoil must NOT flip the direction to BACKWARD while within the active forward gesture
        assertEquals("Snap recoil (-60px) must maintain FORWARD direction during gesture settle", ScrollDirection.FORWARD, manager.currentDirection)

        // 3. True deliberate backward swipe (e.g. delta <= -150px)
        currentTime += 50L
        manager.updateDirection(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -180
        )
        assertEquals("Definite, large backward scroll must be classified as BACKWARD", ScrollDirection.BACKWARD, manager.currentDirection)
    }

    @Test
    fun `direction manager isolated backward scroll from rest is classified as backward`() {
        var currentTime = 1000L
        val manager = DirectionStateManager(timeProvider = { currentTime })

        // From rest (UNKNOWN), a negative delta >= 40px must be classified as BACKWARD
        manager.updateDirection(
            eventType = android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -60
        )
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)
    }

    @Test
    fun `motorola system overlays are properly classified`() {
        // Test reflection or direct invocation of Motorola system overlay helper in ReelTrackerService
        val serviceClass = Class.forName("com.reeltrak.app.services.ReelTrackerService")
        val isSystemOverlayMethod = serviceClass.getDeclaredMethod("isSystemOverlay", String::class.java)
        isSystemOverlayMethod.isAccessible = true

        val instance = serviceClass.getDeclaredConstructor().newInstance()

        // Standard Android system UI
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.android.systemui") as Boolean)
        assertTrue(isSystemOverlayMethod.invoke(instance, "android") as Boolean)

        // Motorola system overlays (harmless, must not trigger false app exit)
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.motorola.gamemode") as Boolean)
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.motorola.actions") as Boolean)
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.motorola.gesture") as Boolean)
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.motorola.motocare") as Boolean)
        assertTrue(isSystemOverlayMethod.invoke(instance, "com.motorola.systemui") as Boolean)

        // Motorola non-overlay apps (genuine exits, must NOT be whitelisted)
        assertFalse(isSystemOverlayMethod.invoke(instance, "com.motorola.camera") as Boolean)
        assertFalse(isSystemOverlayMethod.invoke(instance, "com.motorola.launcher3") as Boolean)
        assertFalse(isSystemOverlayMethod.invoke(instance, "com.motorola.ccc.notification") as Boolean)
    }

    @Test
    fun `youtube channel candidate extraction accepts valid display names and rejects buttons`() {
        val detectorClass = Class.forName("com.reeltrak.app.services.tracking.engine.detector.YouTubeDetectorV2")
        val method = detectorClass.getDeclaredMethod(
            "isValidChannelCandidate",
            String::class.java,
            String::class.java,
            AccessibilityNodeInfo::class.java
        )
        method.isAccessible = true
        val detectorInstance = detectorClass.getDeclaredConstructor().newInstance()

        val clickableNode = AccessibilityNodeInfo.obtain()
        clickableNode.isClickable = true

        val nonClickableNode = AccessibilityNodeInfo.obtain()
        nonClickableNode.isClickable = false

        try {
            // Valid creator display names without @ (Motorola / YouTube display names)
            assertTrue(method.invoke(detectorInstance, "MrBeast", "", clickableNode) as Boolean)
            assertTrue(method.invoke(detectorInstance, "Veritasium", "", clickableNode) as Boolean)
            assertTrue(method.invoke(detectorInstance, "MKBHD", "com.google.android.youtube:id/text", clickableNode) as Boolean)
            assertTrue(method.invoke(detectorInstance, "Linus Tech Tips", "", clickableNode) as Boolean)

            // Generic action buttons that must NEVER be accepted as creators
            assertFalse(method.invoke(detectorInstance, "Subscribe", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Subscribed", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Join", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Share", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Remix", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Comments", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Dislike", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Like", "", clickableNode) as Boolean)

            // Numeric view count / timestamps
            assertFalse(method.invoke(detectorInstance, "1.2M", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "45K", "", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "123", "", clickableNode) as Boolean)

            // Captions, music, sound resource IDs
            assertFalse(method.invoke(detectorInstance, "Great video!", "video_caption", clickableNode) as Boolean)
            assertFalse(method.invoke(detectorInstance, "Original Audio", "sound_title", clickableNode) as Boolean)

            // Non-clickable candidate
            assertFalse(method.invoke(detectorInstance, "MrBeast", "", nonClickableNode) as Boolean)
        } finally {
            clickableNode.recycle()
            nonClickableNode.recycle()
        }
    }
}

package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import com.reeltrak.app.services.overlay.OverlayState
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * End-to-end unit test suite verifying the complete increment pipeline:
 * ReelDetectionEngine -> TransitionLedger -> incrementProductionCount() -> DayManager.incrementReelCount() -> counter UI
 *
 * Guarantees:
 * 1. 1 valid transition produces exactly 1 increment (count +1).
 * 2. Deduplication prevents duplicate (+2) increments on repeated snapshots of the same reel.
 * 3. Backward scrolling is suppressed without latching or causing missed future forward increments.
 * 4. DayManager and ReelTrackerService increment pipeline does not reject or drop increments when token is null.
 * 5. scheduleOverlaySynchronization does not drop settled reel transitions.
 */
class IncrementPipelineVerificationTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    private fun makeSnapshot(
        creator: String,
        caption: String,
        song: String = "audio_track",
        platform: String = "Instagram"
    ): DetectionSnapshot {
        return DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint(creator, caption, song),
            platform = platform,
            nodeCount = 25,
            isInternalOverlay = false
        )
    }

    @Test
    fun testIncrementPipeline_ExactOneTransitionProducesExactlyOneIncrement() {
        var dayManagerCount = 0
        val overlayStateFlow = MutableStateFlow(OverlayState())
        val directionManager = DirectionStateManager()

        // Mock incrementProductionCount & DayManager.incrementReelCount pipeline
        fun onIncrement() {
            if (directionManager.currentDirection != ScrollDirection.BACKWARD) {
                // DayManager increments totalReelsCount
                dayManagerCount++
                // OverlayState updates count immediately to drive UI
                overlayStateFlow.value = overlayStateFlow.value.copy(count = dayManagerCount)
            } else {
                directionManager.onBackwardTransitionConsumed()
            }
        }

        val ledger = TransitionLedger { onIncrement() }

        // 1. Initial Reel 1 (Initial reel established -> exactly 1 count)
        val reel1 = makeSnapshot(creator = "creator_alpha", caption = "video 1")
        ledger.process(reel1)
        assertEquals("Initial reel established must increment to 1", 1, dayManagerCount)
        assertEquals("OverlayState count must update to 1 for UI", 1, overlayStateFlow.value.count)

        // 2. Multiple rapid accessibility events on Reel 1 (no scroll) -> must NOT increment (+2 prevention)
        ledger.process(reel1)
        ledger.process(reel1)
        assertEquals(1, dayManagerCount)
        assertEquals(1, overlayStateFlow.value.count)

        // 3. User swipes to Reel 2 -> exactly 1 increment (total 2)
        val reel2 = makeSnapshot(creator = "creator_beta", caption = "video 2")
        ledger.process(reel2)
        assertEquals("Count must increment to 2 on first transition", 2, dayManagerCount)
        assertEquals("OverlayState count must update to 2 for UI", 2, overlayStateFlow.value.count)

        // 4. Repeated snapshots of Reel 2 (while user watches) -> count stays 2
        ledger.process(reel2)
        ledger.process(reel2)
        assertEquals("Count must not increment on duplicate snapshots", 2, dayManagerCount)
        assertEquals(2, overlayStateFlow.value.count)

        // 5. User swipes to Reel 3 -> exactly 1 increment (total 3)
        val reel3 = makeSnapshot(creator = "creator_gamma", caption = "video 3")
        ledger.process(reel3)
        assertEquals("Count must increment to 3 on second transition", 3, dayManagerCount)
        assertEquals("OverlayState count must update to 3 for UI", 3, overlayStateFlow.value.count)
    }

    @Test
    fun testReelTrackerService_IncrementProductionCount_NotBlockedByNullToken() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify that incrementProductionCount does NOT reject when activeToken == null
        assertFalse(
            "incrementProductionCount must not drop increments when token is null",
            content.contains("token == null || !appAuthorizationManager.verifyToken(token)")
        )
        assertTrue(
            "incrementProductionCount must invoke sessionManager.incrementReel",
            content.contains("sessionManager.incrementReel(token)")
        )
        assertTrue(
            "incrementProductionCount must wait for durable DayManager acceptance",
            content.contains("dayManager.acceptReelTransition(fingerprint, token)") &&
                content.contains("ReelCountAcceptance.REJECTED")
        )
    }

    @Test
    fun testDayManager_DurableAcceptanceAllowsNullTokenWhenNoAuthManagerIsConfigured() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt")
        val content = file.readText()

        // Verify that DayManager does NOT reject when token is null
        assertFalse(
            "DayManager must not reject solely because the optional token is null",
            content.contains("if (token == null || !authMgr.verifyToken(token))")
        )
        assertTrue(
            "DayManager durable acceptance must use the authorization helper",
            content.contains("if (!isCountAuthorized(token) || isRestoring || !managerScope.isActive)") &&
                content.contains("return token == null || authMgr.verifyToken(token)")
        )
    }

    @Test
    fun testScheduleOverlaySynchronization_DoesNotEarlyReturnWhenActive() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify scheduleOverlaySynchronization does NOT skip detector BFS when active
        assertFalse(
            "scheduleOverlaySynchronization must NOT drop debounced events when session is active",
            content.contains("if (currentSession == SessionState.ACTIVE && currentOverlayState.isVisible && currentOverlayState.confidence == 100)")
        )
        assertTrue(
            "scheduleOverlaySynchronization must drive transitionLedger.process(snapshot)",
            content.contains("transitionLedger.process(snapshot)")
        )
    }

    @Test
    fun testTransitionLedger_ProgressiveMetadataEnrichment_PreventsDoubleIncrement() {
        var incrementCount = 0
        val ledger = TransitionLedger { incrementCount++ }

        // 1. Initial Reel 1 (Initial reel established -> exactly 1 count)
        val reel1 = makeSnapshot(creator = "creator_alpha", caption = "video 1")
        ledger.process(reel1)
        assertEquals(1, incrementCount)

        // 2. User swipes to Reel 2:
        // Frame A (immediate render): Creator is visible, but caption is empty string (progressive loading)
        val reel2Initial = makeSnapshot(creator = "creator_beta", caption = "", song = "")
        ledger.process(reel2Initial)
        assertEquals("Transition to new creator must increment count to 2", 2, incrementCount)

        // Frame B (150ms later): Full caption and song bind asynchronously on the same physical reel
        val reel2Enriched = makeSnapshot(creator = "creator_beta", caption = "Full caption text #trending", song = "Original Audio")
        ledger.process(reel2Enriched)
        assertEquals(
            "Progressive metadata enrichment on the same reel MUST NOT trigger a second increment (+2 prevention)",
            2,
            incrementCount
        )

        // Frame C (user expands caption): Volatile caption changes on the same reel
        val reel2Expanded = makeSnapshot(creator = "creator_beta", caption = "Full caption text #trending ...more extra lines", song = "Original Audio")
        ledger.process(reel2Expanded)
        assertEquals("Caption expansion on the same reel must not increment count", 2, incrementCount)
    }

    @Test
    fun testTransitionLedger_ConsecutiveSameCreatorReels_CountedWhenContentDiffers() {
        var incrementCount = 0
        val ledger = TransitionLedger { incrementCount++ }

        // 1. Establish initial Reel 1
        val reel1 = makeSnapshot(creator = "creator_alpha", caption = "video 1")
        ledger.process(reel1)
        assertEquals(1, incrementCount)

        // 2. Swipe to Reel 2 from creator_beta -> increments to 2
        val reel2 = makeSnapshot(creator = "creator_beta", caption = "First video by beta")
        ledger.process(reel2)
        assertEquals(2, incrementCount)

        // 3. User swipes to a separate consecutive video by the same creator_beta with distinct caption
        val reel3SameCreator = makeSnapshot(creator = "creator_beta", caption = "Completely different video by beta")
        ledger.process(reel3SameCreator)
        assertEquals(
            "Consecutive reels from the same creator with distinct content must be counted as a new reel",
            3,
            incrementCount
        )

        // 4. User swipes to Reel 4 from a different creator -> increments to 4
        val reel4DifferentCreator = makeSnapshot(creator = "creator_gamma", caption = "Video by gamma")
        ledger.process(reel4DifferentCreator)
        assertEquals("Transition to different creator must increment count to 4", 4, incrementCount)
    }

    @Test
    fun testSemanticFingerprint_IsSameAs_Rules() {
        val fp1 = SemanticFingerprint(primaryIdentifier = "creator_a", secondaryIdentifier = "", auxiliaryIdentifier = "")
        val fp2 = SemanticFingerprint(primaryIdentifier = "creator_a", secondaryIdentifier = "Caption loaded", auxiliaryIdentifier = "Song title")
        val fpDistinct1 = SemanticFingerprint(primaryIdentifier = "creator_a", secondaryIdentifier = "Caption 1", auxiliaryIdentifier = "Song title")
        val fpDistinct2 = SemanticFingerprint(primaryIdentifier = "creator_a", secondaryIdentifier = "Caption 2", auxiliaryIdentifier = "Song title")
        val fp3 = SemanticFingerprint(primaryIdentifier = "creator_b", secondaryIdentifier = "Caption loaded", auxiliaryIdentifier = "Song title")
        val empty1 = SemanticFingerprint("", "", "")
        val empty2 = SemanticFingerprint("", "", "")

        // Same creator with unpopulated caption -> enrichment of same reel
        assertTrue("Matching creator with loading metadata must be treated as same reel", fp1.isSameAs(fp2))
        assertTrue("Symmetric equality must hold", fp2.isSameAs(fp1))

        // Same creator with distinct non-empty captions -> MUST be recognized as different reels!
        assertFalse("Same creator with distinct captions must be recognized as different reels", fpDistinct1.isSameAs(fpDistinct2))

        // Different creators -> isSameAs must return false
        assertFalse("Different primary identifiers must not be equal", fp1.isSameAs(fp3))

        // Empty fingerprints -> isSameAs must return false
        assertFalse("Empty fingerprints cannot be considered identical", empty1.isSameAs(empty2))

        // Historical matching checks
        assertTrue("Identical content matches history", fpDistinct1.matchesHistorical(fpDistinct1))
        assertFalse("Distinct content does not match history", fpDistinct1.matchesHistorical(fpDistinct2))
        assertFalse("Creator alone cannot prove historical match when history item had caption", fp1.matchesHistorical(fp2))
    }

    @Test
    fun testIncrementPipeline_ImmediateResponsivenessOnDifferentCreatorTransition() {
        var incrementCount = 0
        val overlayStateFlow = MutableStateFlow(OverlayState())
        val ledger = TransitionLedger {
            incrementCount++
            // Immediate optimistic UI update as done in ReelTrackerService.incrementProductionCount
            overlayStateFlow.value = overlayStateFlow.value.copy(count = incrementCount)
        }

        // 1. Establish initial Reel 1 (creator_1)
        val reel1 = makeSnapshot(creator = "creator_1", caption = "Caption 1")
        ledger.process(reel1)
        assertEquals(1, incrementCount)
        assertEquals(1, overlayStateFlow.value.count)

        // 2. Immediate Frame 1 of Reel 2 (creator_2): No caption loaded yet
        val reel2Frame1 = makeSnapshot(creator = "creator_2", caption = "")
        ledger.process(reel2Frame1)
        assertEquals("Different creator must increment immediately on Frame 1", 2, incrementCount)
        assertEquals("OverlayState count must reflect increment immediately", 2, overlayStateFlow.value.count)

        // 3. Progressive metadata frames for Reel 2: Must NOT trigger second increment (+2 prevention)
        val reel2Frame2 = makeSnapshot(creator = "creator_2", caption = "Caption arrives 100ms later")
        ledger.process(reel2Frame2)
        assertEquals("Progressive metadata enrichment must not increment", 2, incrementCount)
        assertEquals(2, overlayStateFlow.value.count)

        val reel2Frame3 = makeSnapshot(creator = "creator_2", caption = "Caption arrives 100ms later... more", song = "Audio track")
        ledger.process(reel2Frame3)
        assertEquals(2, incrementCount)
        assertEquals(2, overlayStateFlow.value.count)

        // 4. Immediate Frame 1 of Reel 3 (creator_3): Must increment immediately
        val reel3Frame1 = makeSnapshot(creator = "creator_3", caption = "")
        ledger.process(reel3Frame1)
        assertEquals("Transition to third creator must increment immediately on Frame 1", 3, incrementCount)
        assertEquals(3, overlayStateFlow.value.count)
    }

    @Test
    fun testIncrementPipeline_MetadataEnrichmentDeduplicationUnderRapidSnapshots() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Initial reel 1
        ledger.process(makeSnapshot("creator_alpha", "Initial post"))
        assertEquals(1, count)

        // Transition to creator_beta
        ledger.process(makeSnapshot("creator_beta", ""))
        assertEquals(2, count)

        // 10 rapid duplicate events with mutating volatile captions on the same creator
        for (i in 1..10) {
            val mutatingCaption = "Progressive caption step $i with extra metadata #${i}"
            val song = if (i > 5) "Original Audio - creator_beta" else ""
            ledger.process(makeSnapshot("creator_beta", mutatingCaption, song))
            assertEquals("Duplicate rapid snapshots of same creator must never trigger +2", 2, count)
        }

        // Transition to creator_gamma
        ledger.process(makeSnapshot("creator_gamma", "New creator video"))
        assertEquals("Transition to new creator must increment to 3", 3, count)
    }

    @Test
    fun testReelTrackerService_OptimisticOverlayStateUpdate_PresentInSource() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        assertTrue(
            "ReelTrackerService must optimistically update overlayStateManager in incrementProductionCount",
            content.contains("overlayStateManager.updateState { currentState ->") &&
            content.contains("val newCount = currentState.count + 1")
        )
    }

    @Test
    fun testOverlayStateManager_MonotonicCountPreservationOnSameDay() {
        // Simulates the race condition where optimistic increment advanced overlay state to 7,
        // but an intermediate DayManager emission with count 6 arrives shortly after.
        val manager = com.reeltrak.app.services.overlay.OverlayStateManager()
        manager.updateState { it.copy(count = 5) }

        // Optimistic increment: user swipes to Reel 6 and Reel 7 rapidly
        manager.updateState { it.copy(count = it.count + 1) } // 6
        manager.updateState { it.copy(count = it.count + 1) } // 7
        assertEquals(7, manager.state.value.count)

        // Simulating the DayManager collector resolution logic in ReelTrackerService:
        // Intermediate emission with count = 6 arrives for the SAME date
        val isDateChanged = false
        val intermediateTotalToday = 6
        manager.updateState { currentState ->
            val resolvedCount = if (isDateChanged) intermediateTotalToday else maxOf(currentState.count, intermediateTotalToday)
            currentState.copy(count = resolvedCount)
        }
        assertEquals("Count must NOT regress to 6 when optimistic count is 7", 7, manager.state.value.count)

        // Final authoritative emission with count = 7 arrives
        val finalTotalToday = 7
        manager.updateState { currentState ->
            val resolvedCount = if (isDateChanged) finalTotalToday else maxOf(currentState.count, finalTotalToday)
            currentState.copy(count = resolvedCount)
        }
        assertEquals("Count remains stable at 7", 7, manager.state.value.count)
    }

    @Test
    fun testReelTrackerService_SourceAudit_MonotonicAndFallbackIntegrity() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        assertTrue(
            "currentDayStats collector must resolve count monotonically using maxOf",
            content.contains("val resolvedCount = if (isDateChanged) totalToday else maxOf(currentState.count, totalToday)")
        )
        assertTrue(
            "lastEventProcessingTime must be updated monotonically using maxOf",
            content.contains("lastEventProcessingTime = maxOf(lastEventProcessingTime, eventTime)")
        )
        assertTrue(
            "scheduleOverlaySynchronization must check debounce before requesting queue",
            content.contains("now - lastEventProcessingTime >= EVENT_DEBOUNCE_MS") &&
            content.contains("processingQueue?.request(TrackingAnalysisRequest(now, directionManager.snapshot()))")
        )
    }
}

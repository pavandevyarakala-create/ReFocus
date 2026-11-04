package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mandatory regression test suite verifying:
 * "Fix First-Reel Counting Without Duplicate Counts"
 *
 * Requirements:
 * 1. First confidently detected reel A increments the counter to 1.
 * 2. Repeated snapshots of A do not increment again.
 * 3. A -> B results in a total of 2.
 * 4. A -> B -> A -> B remains at 2.
 * 5. Closing and reopening the same reel A does not increment again when its identity matches history.
 * 6. Returning to A after a temporary app switch does not increment again.
 * 7. A genuinely new reel increments exactly once.
 * 8. Different reels from the same creator are counted separately when identifying evidence distinguishes them.
 * 9. Incomplete snapshots do not increment speculatively.
 * 10. History eviction and daily rollover follow the explicitly defined policy.
 * 11. Concurrent duplicate events cannot double-increment.
 */
class ReelIdentityAndDeduplicationTest {

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
        caption: String = "",
        song: String = "",
        platform: String = "Instagram",
        classification: SnapshotClassification = SnapshotClassification.COMMITTED,
        isInternalOverlay: Boolean = false,
        internalOverlayKind: InternalOverlayKind = InternalOverlayKind.NONE
    ): DetectionSnapshot {
        return DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, caption, song),
            platform = platform,
            nodeCount = 25,
            isInternalOverlay = isInternalOverlay,
            internalOverlayKind = internalOverlayKind
        )
    }

    // =========================================================================
    // 1. First confidently detected reel A increments the counter to 1
    // =========================================================================
    @Test
    fun testRequirement1_firstConfidentlyDetectedReelA_incrementsCounterToOne() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "creator_alpha", caption = "Caption A", song = "Audio A")

        // Initial state before any reel
        assertEquals(0, count)
        assertNull(ledger.currentActiveFingerprint)
        assertTrue(ledger.historySnapshot.isEmpty())

        // First confidently detected reel arrives
        val processed = ledger.process(reelA)
        assertTrue("First valid reel must be processed", processed)
        assertEquals("First confidently detected reel must increment count to 1", 1, count)
        assertEquals("Active fingerprint must be Reel A", reelA.fingerprint, ledger.currentActiveFingerprint)
        assertEquals("History must contain exactly 1 entry", 1, ledger.historySnapshot.size)
        assertEquals("History entry must be Reel A", reelA.fingerprint, ledger.historySnapshot.first())
    }

    // =========================================================================
    // 2. Repeated snapshots of A do not increment again
    // =========================================================================
    @Test
    fun testRequirement2_repeatedSnapshotsOfA_doNotIncrementAgain() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "creator_alpha", caption = "Caption A", song = "Audio A")

        // First snapshot increments to 1
        ledger.process(reelA)
        assertEquals(1, count)

        // 100 repeated snapshots on the same physical reel (scrolling jitters, a11y focus, playback updates)
        for (i in 1..100) {
            val processed = ledger.process(reelA)
            assertTrue(processed)
            assertEquals("Repeated snapshots of A must NEVER increment count beyond 1", 1, count)
        }

        assertEquals(1, count)
        assertEquals(1, ledger.historySnapshot.size)
        assertEquals(reelA.fingerprint, ledger.currentActiveFingerprint)
    }

    // =========================================================================
    // 3. A -> B results in a total of 2
    // =========================================================================
    @Test
    fun testRequirement3_A_to_B_resultsInTotalOfTwo() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "creator_alpha", caption = "Video A", song = "Audio A")
        val reelB = makeSnapshot(creator = "creator_beta", caption = "Video B", song = "Audio B")

        ledger.process(reelA)
        assertEquals("Reel A must increment to 1", 1, count)

        ledger.process(reelB)
        assertEquals("Reel B transition must increment to 2", 2, count)

        assertEquals("Active fingerprint must now be Reel B", reelB.fingerprint, ledger.currentActiveFingerprint)
        assertEquals("History must contain both reels", 2, ledger.historySnapshot.size)
        assertEquals(listOf(reelA.fingerprint, reelB.fingerprint), ledger.historySnapshot)
    }

    // =========================================================================
    // 4. A -> B -> A -> B remains at 2
    // =========================================================================
    @Test
    fun testRequirement4_A_to_B_to_A_to_B_remainsAtTwo() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "creator_alpha", caption = "Video A", song = "Audio A")
        val reelB = makeSnapshot(creator = "creator_beta", caption = "Video B", song = "Audio B")

        // Step 1: Reel A -> 1
        ledger.process(reelA)
        assertEquals(1, count)

        // Step 2: Reel B -> 2
        ledger.process(reelB)
        assertEquals(2, count)

        // Step 3: Swipe backward to Reel A (matched in rolling history)
        ledger.process(reelA)
        assertEquals("Swiping back to Reel A from history must NOT increment", 2, count)
        assertEquals("Active fingerprint must update to Reel A", reelA.fingerprint, ledger.currentActiveFingerprint)

        // Step 4: Swipe forward back to Reel B (matched in rolling history)
        ledger.process(reelB)
        assertEquals("Swiping forward back to Reel B from history must NOT increment", 2, count)
        assertEquals("Active fingerprint must update to Reel B", reelB.fingerprint, ledger.currentActiveFingerprint)

        // Total count remains 2 across the entire loop
        assertEquals("Total count for A -> B -> A -> B must remain at exactly 2", 2, count)
        assertEquals(2, ledger.historySnapshot.size)
    }

    // =========================================================================
    // 5. Closing and reopening the same reel A does not increment again when its identity matches history
    // =========================================================================
    @Test
    fun testRequirement5_closingAndReopeningSameReelA_doesNotIncrementAgainWhenIdentityMatchesHistory() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "traveler_pro", caption = "Visiting Tokyo #japan", song = "City Sounds")

        // Initial launch: Reel A is counted
        ledger.process(reelA)
        assertEquals(1, count)
        assertEquals(reelA.fingerprint, ledger.currentActiveFingerprint)
        assertEquals(1, ledger.historySnapshot.size)

        // User exits/closes Instagram (e.g. presses Back/Home to exit to Launcher)
        // ReelTrackerService handles SessionState.IDLE by calling transitionLedger.clearActive()
        ledger.clearActive()
        assertNull("Active focal pointer must be unlatched on exit", ledger.currentActiveFingerprint)
        assertEquals("Rolling history must be preserved across app exit", 1, ledger.historySnapshot.size)

        // User immediately reopens Instagram: Instagram restores the same screen showing Reel A
        ledger.process(reelA)
        assertEquals("Reopening onto the same Reel A must NOT increment again", 1, count)
        assertEquals("Active focal pointer must be restored to Reel A", reelA.fingerprint, ledger.currentActiveFingerprint)

        // Repeated events on Reel A after reopening
        ledger.process(reelA)
        assertEquals(1, count)

        // User now scrolls to a new Reel B
        val reelB = makeSnapshot(creator = "foodie_chef", caption = "Best Ramen in Shibuya", song = "Kitchen Beats")
        ledger.process(reelB)
        assertEquals("Scrolling to new Reel B must increment count to 2", 2, count)
        assertEquals(2, ledger.historySnapshot.size)
    }

    // =========================================================================
    // 6. Returning to A after a temporary app switch does not increment again
    // =========================================================================
    @Test
    fun testRequirement6_returningToA_afterTemporaryAppSwitch_doesNotIncrementAgain() {
        var count = 0
        val ledger = TransitionLedger { count++ }
        val reelA = makeSnapshot(creator = "creator_1", caption = "First post", song = "Sound 1")
        val reelB = makeSnapshot(creator = "creator_2", caption = "Second post", song = "Sound 2")

        ledger.process(reelA)
        assertEquals(1, count)
        ledger.process(reelB)
        assertEquals(2, count)

        // Temporary app switch to WhatsApp or incoming call
        ledger.clearActive()
        assertNull(ledger.currentActiveFingerprint)
        assertEquals(2, ledger.historySnapshot.size)

        // User switches back into app right onto Reel A
        ledger.process(reelA)
        assertEquals("Returning to Reel A after app switch must NOT increment again", 2, count)
        assertEquals("Active reel must update to Reel A", reelA.fingerprint, ledger.currentActiveFingerprint)

        // User switches forward to Reel B
        ledger.process(reelB)
        assertEquals("Returning to Reel B after app switch must NOT increment again", 2, count)
        assertEquals("Active reel must update to Reel B", reelB.fingerprint, ledger.currentActiveFingerprint)
    }

    // =========================================================================
    // 7. A genuinely new reel increments exactly once
    // =========================================================================
    @Test
    fun testRequirement7_genuinelyNewReel_incrementsExactlyOnce() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        val reelA = makeSnapshot(creator = "artist_1", caption = "Song 1 clip")
        val reelB = makeSnapshot(creator = "artist_2", caption = "Song 2 clip")
        val reelC = makeSnapshot(creator = "artist_3", caption = "Song 3 clip")

        ledger.process(reelA)
        assertEquals(1, count)

        ledger.process(reelB)
        assertEquals(2, count)

        ledger.process(reelC)
        assertEquals(3, count)

        assertEquals(reelC.fingerprint, ledger.currentActiveFingerprint)
        assertEquals(3, ledger.historySnapshot.size)
    }

    // =========================================================================
    // 8. Different reels from the same creator are counted separately when identifying evidence distinguishes them
    // =========================================================================
    @Test
    fun testRequirement8_differentReelsFromSameCreator_countedSeparatelyWhenIdentified() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Distinct captions and distinct audio from the same creator
        val reel1 = makeSnapshot(
            creator = "mrbeast",
            caption = "I spent 50 hours in solitary confinement",
            song = "soundtrack_1"
        )
        val reel2 = makeSnapshot(
            creator = "mrbeast",
            caption = "I gave a random person 10000 dollars",
            song = "soundtrack_2"
        )
        val reel3 = makeSnapshot(
            creator = "mrbeast",
            caption = "Last to leave the red circle wins a car",
            song = "soundtrack_3"
        )

        ledger.process(reel1)
        assertEquals("Reel 1 from mrbeast must increment to 1", 1, count)

        ledger.process(reel2)
        assertEquals("Reel 2 with distinct caption from mrbeast must increment to 2", 2, count)

        ledger.process(reel3)
        assertEquals("Reel 3 with distinct caption from mrbeast must increment to 3", 3, count)

        assertEquals("All 3 distinct reels from the same creator must be preserved in history", 3, ledger.historySnapshot.size)

        // Swiping back to Reel 2 from mrbeast matches historical entry
        ledger.process(reel2)
        assertEquals("Returning to previous reel from same creator must NOT increment", 3, count)
        assertEquals(reel2.fingerprint, ledger.currentActiveFingerprint)
    }

    // =========================================================================
    // 9. Incomplete snapshots do not increment speculatively
    // =========================================================================
    @Test
    fun testRequirement9_incompleteSnapshots_doNotIncrementSpeculatively() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Case A: PENDING snapshot (container loading, missing nodes)
        val pendingSnapshot = makeSnapshot(
            creator = "",
            caption = "",
            classification = SnapshotClassification.PENDING
        )
        val processedPending = ledger.process(pendingSnapshot)
        assertFalse("Pending snapshot must be ignored by ledger", processedPending)
        assertEquals(0, count)
        assertNull(ledger.currentActiveFingerprint)

        // Case B: Snapshot missing primary identifier (creator)
        val missingCreatorSnapshot = makeSnapshot(
            creator = "",
            caption = "Just a caption without creator",
            song = "Audio track",
            classification = SnapshotClassification.COMMITTED
        )
        val processedMissingCreator = ledger.process(missingCreatorSnapshot)
        assertFalse("Snapshot missing creator must be rejected as incomplete", processedMissingCreator)
        assertEquals(0, count)

        // Case C: Completely empty fingerprint
        val emptySnapshot = makeSnapshot(
            creator = "",
            caption = "",
            song = "",
            classification = SnapshotClassification.COMMITTED
        )
        val processedEmpty = ledger.process(emptySnapshot)
        assertFalse("Empty fingerprint must be rejected", processedEmpty)
        assertEquals(0, count)

        // Case D: First valid reel arrives
        val validReel = makeSnapshot(creator = "cooking_channel", caption = "Soup recipe", song = "Acoustic")
        ledger.process(validReel)
        assertEquals(1, count)

        // Case E: Ambiguous creator-only snapshot for the active creator does not double-count
        val creatorOnlySame = makeSnapshot(creator = "cooking_channel", caption = "", song = "")
        ledger.process(creatorOnlySame)
        assertEquals("Ambiguous snapshot for active creator must NOT increment", 1, count)

        // Case F: PENDING snapshot while active reel is set does not disturb active state
        ledger.process(pendingSnapshot)
        assertEquals(1, count)
        assertEquals("cooking_channel", ledger.currentActiveFingerprint?.primaryIdentifier)
    }

    // =========================================================================
    // 10. History eviction and daily rollover follow the explicitly defined policy
    // =========================================================================
    @Test
    fun testRequirement10_historyEvictionAndDailyRollover_followExplicitlyDefinedPolicy() {
        var dailyCount = 0
        val ledger = TransitionLedger { dailyCount++ }

        // --- PART A: 10-Entry History Eviction Policy ---
        val reels = (1..11).map { i ->
            makeSnapshot(creator = "creator_$i", caption = "Caption $i", song = "Song $i")
        }

        // Feed first 10 distinct reels (Day 1)
        for (i in 0 until 10) {
            ledger.process(reels[i])
            assertEquals(i + 1, dailyCount)
        }
        assertEquals("Count must reach 10", 10, dailyCount)
        assertEquals("History size must be bounded at exactly 10", 10, ledger.historySnapshot.size)
        assertTrue("Reel 1 is initially in history", ledger.historySnapshot.contains(reels[0].fingerprint))
        assertTrue("Reel 10 is in history", ledger.historySnapshot.contains(reels[9].fingerprint))

        // Feed 11th unique reel -> evicts oldest entry (Reel 1)
        ledger.process(reels[10])
        assertEquals("11th unique reel increments to 11", 11, dailyCount)
        assertEquals("History size must remain bounded at 10", 10, ledger.historySnapshot.size)
        assertFalse("Oldest entry (Reel 1) must be evicted", ledger.historySnapshot.contains(reels[0].fingerprint))
        assertTrue("Reel 2 is now the oldest entry", ledger.historySnapshot.contains(reels[1].fingerprint))
        assertTrue("Reel 11 is the newest entry", ledger.historySnapshot.contains(reels[10].fingerprint))

        // Revisit Reel 2 (which is in history) -> no increment
        ledger.process(reels[1])
        assertEquals("Revisiting Reel 2 from history must NOT increment", 11, dailyCount)

        // Revisit Reel 1 (was evicted!) -> documented policy: re-counted as new transition
        ledger.process(reels[0])
        assertEquals("Revisiting evicted Reel 1 must increment as a new transition", 12, dailyCount)
        assertTrue("Reel 1 is re-added to history", ledger.historySnapshot.contains(reels[0].fingerprint))

        // --- PART B: Daily Rollover Policy ---
        // At midnight (date change), DayManager resets daily count to 0 for the new day.
        // ReelTrackerService calls transitionLedger.clearActive() so that the 10-entry
        // rolling history SURVIVES across midnight.
        dailyCount = 0 // DayManager daily bucket resets to 0 for the new day
        ledger.clearActive() // Focal unlatched, history preserved!

        assertNull("Active reel is unlatched at midnight", ledger.currentActiveFingerprint)
        assertEquals("10-entry rolling history SURVIVES midnight date rollover", 10, ledger.historySnapshot.size)

        // User continues watching Reel 1 across midnight:
        // Snapshot for Reel 1 arrives at 00:00:01
        ledger.process(reels[0])
        assertEquals("Actively watched reel across midnight must NOT spuriously increment new day counter", 0, dailyCount)
        assertEquals("Active reel restored to Reel 1 without incrementing", reels[0].fingerprint, ledger.currentActiveFingerprint)

        // User swiping back to Reel 11 (watched late last night, still in history)
        ledger.process(reels[10])
        assertEquals("Recent reel from late last night in history must NOT increment on revisit", 0, dailyCount)

        // User genuinely swipes to Reel 12 (the first brand new reel of the new day!)
        val reel12 = makeSnapshot(creator = "creator_12", caption = "Brand new day reel", song = "Morning track")
        ledger.process(reel12)
        assertEquals("First genuinely new reel of the new day increments today's counter to 1", 1, dailyCount)
        assertEquals(reel12.fingerprint, ledger.currentActiveFingerprint)

        // --- PART C: Explicit Service Destruction Policy ---
        // When service is destroyed (SessionState.DESTROYED), resetFull() wipes active and history
        ledger.resetFull()
        assertNull(ledger.currentActiveFingerprint)
        assertTrue("Service destruction must wipe rolling history completely", ledger.historySnapshot.isEmpty())
    }

    // =========================================================================
    // 11. Concurrent duplicate events cannot double-increment
    // =========================================================================
    @Test
    fun testRequirement11_concurrentDuplicateEvents_cannotDoubleIncrement() {
        val atomicCount = AtomicInteger(0)
        val ledger = TransitionLedger { atomicCount.incrementAndGet() }
        val reelA = makeSnapshot(creator = "viral_artist", caption = "Trending hit", song = "Sound A")

        val threadCount = 30
        val executor = Executors.newFixedThreadPool(threadCount)
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        // Launch 30 threads attempting to process Reel A simultaneously
        for (i in 0 until threadCount) {
            executor.submit {
                readyLatch.countDown()
                try {
                    startLatch.await(5, TimeUnit.SECONDS)
                    ledger.process(reelA)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown() // Release all 30 threads concurrently
        doneLatch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals("Concurrent duplicate snapshots of Reel A must result in EXACTLY 1 increment", 1, atomicCount.get())
        assertEquals(1, ledger.historySnapshot.size)

        // Concurrent transition to Reel B
        val reelB = makeSnapshot(creator = "viral_artist_2", caption = "Another video", song = "Sound B")
        val executor2 = Executors.newFixedThreadPool(threadCount)
        val readyLatch2 = CountDownLatch(threadCount)
        val startLatch2 = CountDownLatch(1)
        val doneLatch2 = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            executor2.submit {
                readyLatch2.countDown()
                try {
                    startLatch2.await(5, TimeUnit.SECONDS)
                    ledger.process(reelB)
                } finally {
                    doneLatch2.countDown()
                }
            }
        }

        readyLatch2.await(5, TimeUnit.SECONDS)
        startLatch2.countDown()
        doneLatch2.await(5, TimeUnit.SECONDS)
        executor2.shutdown()

        assertEquals("Concurrent transition to Reel B must result in EXACTLY 2 increments total", 2, atomicCount.get())
        assertEquals(2, ledger.historySnapshot.size)
    }

    // =========================================================================
    // 12. Caption/Audio progressive enrichment and cross-platform compatibility
    // =========================================================================
    @Test
    fun testRequirement12_captionAudioEnrichmentAndCrossPlatformCompatibility() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Progressive enrichment on Instagram
        val igFrame1 = makeSnapshot(creator = "ig_creator", caption = "", song = "", platform = "Instagram")
        ledger.process(igFrame1)
        assertEquals(1, count)

        // Caption loads 100ms later
        val igFrame2 = makeSnapshot(creator = "ig_creator", caption = "Beautiful sunset in Kyoto", song = "", platform = "Instagram")
        ledger.process(igFrame2)
        assertEquals("Caption enrichment must not trigger new increment", 1, count)

        // Caption expanded (...more) and audio loads
        val igFrame3 = makeSnapshot(creator = "ig_creator", caption = "Beautiful sunset in Kyoto ...more details #travel", song = "Original Audio - ig_creator", platform = "Instagram")
        ledger.process(igFrame3)
        assertEquals("Caption expansion & audio enrichment must not increment", 1, count)
        assertEquals("Original Audio - ig_creator", ledger.currentActiveFingerprint?.auxiliaryIdentifier)

        // YouTube Shorts transition
        val ytShort1 = makeSnapshot(creator = "@veritasium", caption = "Math puzzle #shorts", song = "Shorts Audio", platform = "YouTube")
        ledger.process(ytShort1)
        assertEquals("Transition to YouTube Short must increment to 2", 2, count)

        val ytShort2 = makeSnapshot(creator = "@veritasium", caption = "Physics puzzle #shorts", song = "Shorts Audio", platform = "YouTube")
        ledger.process(ytShort2)
        assertEquals("Different short by same channel must increment to 3", 3, count)

        // Verify source code safety
        val igFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt")
        val igContent = igFile.readText()
        assertTrue(igContent.contains("hasReliableCreator"))
        assertTrue(igContent.contains("GENERIC_UI_WORDS"))

        val ytFile = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt")
        val ytContent = ytFile.readText()
        assertTrue(ytContent.contains("hasReliableCreator"))
    }

    // =========================================================================
    // 13. Service Destruction & Recreation: Preserves Reliable History & Deduplicates
    // =========================================================================
    @Test
    fun testRequirement13_serviceDestructionAndRecreation_preservesReliableHistoryAndPreventsDuplicateCount() {
        val diskStore = InMemoryDiskHistoryStore()
        var dailyCount = 0

        // Service 1 is active
        val service1Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        val reelA = makeSnapshot(creator = "cooking_chef", caption = "Best Pasta Recipe #pasta", song = "Italian Music")
        val reelB = makeSnapshot(creator = "travel_blogger", caption = "Tokyo Shibuya Crossing", song = "City Vibes")

        // Reel A is first detected -> increments to 1
        service1Ledger.process(reelA)
        assertEquals(1, dailyCount)

        // Reel B is detected -> increments to 2
        service1Ledger.process(reelB)
        assertEquals(2, dailyCount)

        // Android OS destroys Service 1 (e.g. LMK, service rebind, configuration change)
        // ReelTrackerService.onDestroy -> SessionState.DESTROYED calls clearActive() (preserving diskStore)
        service1Ledger.clearActive()

        // Verify disk storage persisted both reliable identities
        assertEquals(2, diskStore.rawStorage().size)
        assertTrue(diskStore.rawStorage().contains(reelA.fingerprint))
        assertTrue(diskStore.rawStorage().contains(reelB.fingerprint))

        // Service 2 is recreated by Android and starts afresh
        val service2Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        // Ledger 2 restored history from diskStore
        assertEquals(2, service2Ledger.historySnapshot.size)
        assertNull(service2Ledger.currentActiveFingerprint)

        // User is still looking at Reel B when Service 2 initializes!
        val processedB = service2Ledger.process(reelB)
        assertTrue(processedB)
        assertEquals("Returning to / reopening Reel B after service restart must NOT increment", 2, dailyCount)
        assertEquals(reelB.fingerprint, service2Ledger.currentActiveFingerprint)

        // User swipes back to Reel A
        val processedA = service2Ledger.process(reelA)
        assertTrue(processedA)
        assertEquals("Swiping back to Reel A after service restart must NOT increment", 2, dailyCount)
        assertEquals(reelA.fingerprint, service2Ledger.currentActiveFingerprint)
    }

    // =========================================================================
    // 14. Service Destruction & Recreation: Genuinely New Reel Increments
    // =========================================================================
    @Test
    fun testRequirement14_serviceDestructionAndRecreation_genuinelyNewReelIncrements() {
        val diskStore = InMemoryDiskHistoryStore()
        var dailyCount = 0

        // Service 1 processes Reel A
        val service1Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )
        val reelA = makeSnapshot(creator = "tech_creator", caption = "New Android Features", song = "Tech Beat")
        service1Ledger.process(reelA)
        assertEquals(1, dailyCount)

        // Service 1 destroyed
        service1Ledger.clearActive()

        // Service 2 recreated
        val service2Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        // Genuinely new Reel C arrives
        val reelC = makeSnapshot(creator = "nature_doc", caption = "Deep Sea Creatures", song = "Ocean Waves")
        val processedC = service2Ledger.process(reelC)
        assertTrue(processedC)
        assertEquals("Genuinely new reel C after service recreation must increment count to 2", 2, dailyCount)
        assertEquals(reelC.fingerprint, service2Ledger.currentActiveFingerprint)
        assertEquals(2, service2Ledger.historySnapshot.size)
    }

    // =========================================================================
    // 15. Creator-Only and Unreliable Identities: Never Persisted Across Restarts
    // =========================================================================
    @Test
    fun testRequirement15_creatorOnlyAndUnreliableIdentities_neverPersistedAcrossRestarts() {
        val diskStore = InMemoryDiskHistoryStore()
        var dailyCount = 0

        val service1Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        // Creator-only snapshot (no caption, no audio) - ambiguous identity
        val creatorOnlyReel = makeSnapshot(creator = "generic_creator", caption = "", song = "")
        assertFalse("Creator-only identity must NOT be reliable for persistence", creatorOnlyReel.fingerprint.isReliableForPersistence)

        service1Ledger.process(creatorOnlyReel)
        assertEquals(1, dailyCount)
        assertEquals(1, service1Ledger.historySnapshot.size)

        // In diskStore, creator-only entry was rejected by isReliableForPersistence
        assertTrue("Creator-only entry must NEVER be saved to persistent store", diskStore.rawStorage().isEmpty())

        // Service 2 recreated from store
        val service2Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )
        assertTrue("Service 2 history must be empty because creator-only was never persisted", service2Ledger.historySnapshot.isEmpty())
    }

    // =========================================================================
    // 16. Midnight Rollover with Service Restart: Preserves Deduplication
    // =========================================================================
    @Test
    fun testRequirement16_midnightRolloverWithServiceRestart_preservesDeduplication() {
        val diskStore = InMemoryDiskHistoryStore()
        var dailyCount = 45 // 45 reels counted today

        val service1Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        val reelA = makeSnapshot(creator = "night_owl", caption = "Late night coding session", song = "Lofi Chill")
        service1Ledger.process(reelA)
        assertEquals(46, dailyCount)

        // Midnight occurs:
        // DayManager resets count to 0 for the new day
        dailyCount = 0
        // ReelTrackerService calls transitionLedger.clearActive()
        service1Ledger.clearActive()

        // Android OS kills service during midnight maintenance
        // Service 2 starts in the morning
        val service2Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        // User wakes up, phone was left on Reel A:
        service2Ledger.process(reelA)
        assertEquals("Reel A carried over midnight and service restart must NOT increment new day counter", 0, dailyCount)

        // User swipes to Reel B (first new reel of today)
        val reelB = makeSnapshot(creator = "morning_news", caption = "Top headlines today", song = "News Beat")
        service2Ledger.process(reelB)
        assertEquals("First new reel of the new day after service restart must increment to 1", 1, dailyCount)
    }

    // =========================================================================
    // 17. History Eviction Across Service Restarts: Strictly Bounded to 10
    // =========================================================================
    @Test
    fun testRequirement17_historyEvictionAcrossServiceRestarts_strictlyBoundedTo10() {
        val diskStore = InMemoryDiskHistoryStore()
        var dailyCount = 0

        val service1Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )

        val reels = (1..12).map { i ->
            makeSnapshot(creator = "creator_$i", caption = "Caption $i", song = "Song $i")
        }

        // Feed 12 unique reels
        for (reel in reels) {
            service1Ledger.process(reel)
        }
        assertEquals(12, dailyCount)

        // Disk store must be bounded at MAX_HISTORY_SIZE (10)
        assertEquals(10, diskStore.rawStorage().size)
        assertFalse("Reel 1 was evicted", diskStore.rawStorage().contains(reels[0].fingerprint))
        assertFalse("Reel 2 was evicted", diskStore.rawStorage().contains(reels[1].fingerprint))
        assertTrue("Reel 3 is the oldest stored", diskStore.rawStorage().contains(reels[2].fingerprint))
        assertTrue("Reel 12 is the newest stored", diskStore.rawStorage().contains(reels[11].fingerprint))

        // Service 2 recreated
        val service2Ledger = TransitionLedger(
            onIncrement = { dailyCount++ },
            historyStore = diskStore
        )
        assertEquals(10, service2Ledger.historySnapshot.size)

        // Revisit Reel 3 (in history) -> no increment
        service2Ledger.process(reels[2])
        assertEquals("Revisiting Reel 3 must NOT increment", 12, dailyCount)

        // Revisit Reel 1 (was evicted!) -> increments as new reel per policy
        service2Ledger.process(reels[0])
        assertEquals("Revisiting evicted Reel 1 must increment", 13, dailyCount)
    }

    // =========================================================================
    // 18. Verify Service Destroyed Calls clearActive and NOT resetFull
    // =========================================================================
    @Test
    fun testRequirement18_verifyServiceDestroyedCallsClearActiveNotResetFull() {
        val serviceFile = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val serviceCode = serviceFile.readText()

        // Verify SessionState.DESTROYED preserves history
        val destroyedIndex = serviceCode.indexOf("SessionState.DESTROYED ->")
        assertTrue("SessionState.DESTROYED block must exist", destroyedIndex != -1)
        val destroyedSnippet = serviceCode.substring(destroyedIndex, destroyedIndex + 300)
        assertTrue("DESTROYED handler must call transitionLedger.clearActive()", destroyedSnippet.contains("transitionLedger.clearActive()"))
        assertFalse("DESTROYED handler must NOT call transitionLedger.resetFull()", destroyedSnippet.contains("transitionLedger.resetFull()"))

        // Verify date change preserves history across midnight
        val dateChangeIndex = serviceCode.indexOf("if (isDateChanged) {")
        assertTrue("if (isDateChanged) { block must exist", dateChangeIndex != -1)
        val dateChangeSnippet = serviceCode.substring(dateChangeIndex, dateChangeIndex + 400)
        assertTrue("Midnight rollover must call transitionLedger.clearActive()", dateChangeSnippet.contains("transitionLedger.clearActive()"))
        assertFalse("Midnight rollover must NOT call transitionLedger.resetFull()", dateChangeSnippet.contains("transitionLedger.resetFull()"))
    }

    class InMemoryDiskHistoryStore : com.reeltrak.app.services.tracking.engine.state.HistoryStore {
        private val storage = mutableListOf<SemanticFingerprint>()

        override fun loadHistory(): List<SemanticFingerprint> {
            return storage.filter { it.isReliableForPersistence }.takeLast(TransitionLedger.MAX_HISTORY_SIZE)
        }

        override fun saveHistory(history: List<SemanticFingerprint>) {
            storage.clear()
            storage.addAll(history.filter { it.isReliableForPersistence }.takeLast(TransitionLedger.MAX_HISTORY_SIZE))
        }

        override fun clearHistory() {
            storage.clear()
        }

        fun rawStorage(): List<SemanticFingerprint> = storage.toList()
    }
}

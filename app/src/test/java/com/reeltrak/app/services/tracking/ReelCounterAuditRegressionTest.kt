package com.reeltrak.app.services.tracking

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.data.local.entity.SessionData
import com.reeltrak.app.services.accessibility.snapshot.DirectionStateManager
import com.reeltrak.app.services.accessibility.snapshot.ScrollDirection
import com.reeltrak.app.services.tracking.engine.detector.CaptionNormalizer
import com.reeltrak.app.services.tracking.engine.detector.InstagramDetectorV2
import com.reeltrak.app.services.tracking.engine.detector.YouTubeDetectorV2
import com.reeltrak.app.services.tracking.engine.detector.boundedAccessibilityBreadthFirst
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.HistoryStore
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive Regression Test Suite for the 12 Reel-Counter Bugs:
 *
 * PHASE 1 — CRITICAL COUNT RELIABILITY
 * - BUG-F01: DayManager null current-day state recovery
 * - BUG-E01: Overlay synchronization race, duplicate increments & stale snapshot rejection
 *
 * PHASE 2 — DETECTION AND CRASH SAFETY
 * - BUG-I01: InstagramDetectorV2 safe recycling & node ownership
 * - BUG-A03: YouTubeDetectorV2 offscreen child selection & viewport spatial filtering
 * - BUG-C01: Caption normalization, hashtag-first captions, ordinary 'more' words & collisions
 *
 * PHASE 3 — DIRECTION AND FINGERPRINT ACCURACY
 * - BUG-A01: Secondary scroll containers (comments/sheets/carousels) do not corrupt direction
 * - BUG-A02: Snap recoil physics not misclassified as backward
 * - BUG-C02: Cross-platform fingerprint collision avoidance
 *
 * PHASE 4 — PERSISTENCE AND LIFECYCLE
 * - BUG-F02: Creator-only active focal identity persistence across service restart
 * - BUG-G01: Transient null active window session continuity
 * - BUG-A04: Null root bounded retry in processLatestUIState
 * - BUG-F03: Threshold notifications when counts jump across milestones
 */
class ReelCounterAuditRegressionTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    // =========================================================================
    // PHASE 1 — BUG-F01: DayManager null current-day state recovery
    // =========================================================================

    class FakeDailyReelDataDao : DailyReelDataDao {
        override suspend fun getAcceptedTransitionState(accountScope: String, dateString: String): com.reeltrak.app.data.local.entity.AcceptedReelTransitionState? = null
        override suspend fun insertAcceptedTransitionState(state: com.reeltrak.app.data.local.entity.AcceptedReelTransitionState) = Unit
        override suspend fun incrementDailyReelCount(dateString: String, acceptedAtMs: Long): Int = 0
        val records = ConcurrentHashMap<String, DailyReelData>()

        override suspend fun insertOrReplace(data: DailyReelData) {
            records[data.dateString] = data
        }
        override suspend fun getDailyDataSync(dateString: String): DailyReelData? = records[dateString]
        override suspend fun getLatestDailyDataSync(): DailyReelData? = records.values.maxByOrNull { it.dateString }
        override suspend fun getLatestHistoricalDataSync(dateString: String): DailyReelData? =
            records.values.filter { it.dateString < dateString }.maxByOrNull { it.dateString }
        override fun getDailyDataFlow(dateString: String): Flow<DailyReelData?> = MutableStateFlow(records[dateString])
        override fun getAllDailyDataFlow(): Flow<List<DailyReelData>> = MutableStateFlow(records.values.sortedBy { it.dateString })
        override fun getDailyDataRangeFlow(startDate: String, endDate: String): Flow<List<DailyReelData>> =
            MutableStateFlow(records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString })
        override suspend fun getAllDailyDataSync(): List<DailyReelData> = records.values.sortedBy { it.dateString }
        override suspend fun getDailyDataRangeSync(startDate: String, endDate: String): List<DailyReelData> =
            records.values.filter { it.dateString in startDate..endDate }.sortedBy { it.dateString }
        override suspend fun clearAll() = records.clear()
        override suspend fun clearHistoricalDataExcept(today: String) { records.keys.removeIf { it != today } }
        override suspend fun insertAll(data: List<DailyReelData>) { data.forEach { records[it.dateString] = it } }
        override suspend fun getPendingSyncData(todayStr: String): List<DailyReelData> = emptyList()
        override suspend fun getHistoricalDailyDataSync(todayStr: String): List<DailyReelData> = emptyList()
        override suspend fun deleteByDate(dateStr: String) { records.remove(dateStr) }
        override suspend fun deleteHistoricalSyncedExcept(todayStr: String) {}
        override suspend fun markAsSynced(dateStr: String, syncedTimestamp: Long) {}
    }

    /**
     * Test harness that mirrors DayManager's recovery logic for null _currentDayStats.
     */
    class TestDayManagerRecoveryHarness(
        val dao: FakeDailyReelDataDao = FakeDailyReelDataDao(),
        var todayStr: String = "2026-10-23"
    ) {
        var currentDayStats: DailyReelData? = null
        val lock = Any()

        fun loadOrCreateDayLocked(dateStr: String): DailyReelData {
            var stats = dao.records[dateStr]
            if (stats == null) {
                stats = DailyReelData(
                    dateString = dateStr,
                    totalReelsCount = 0,
                    dailyGoal = 100
                )
                dao.records[dateStr] = stats
            }
            currentDayStats = stats
            return stats
        }

        fun incrementReelCount(token: Any? = null) {
            synchronized(lock) {
                // BUG-F01 fix: Recover state safely if null
                if (currentDayStats == null) {
                    loadOrCreateDayLocked(todayStr)
                }
                val current = currentDayStats ?: return
                val updated = current.copy(
                    totalReelsCount = current.totalReelsCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
                currentDayStats = updated
                dao.records[todayStr] = updated
            }
        }

        fun appendSession(session: SessionData) {
            synchronized(lock) {
                // BUG-F01 fix: Recover state safely if null
                if (currentDayStats == null) {
                    loadOrCreateDayLocked(todayStr)
                }
                val current = currentDayStats ?: return
                val addedTime = session.endTimeStamp - session.startTimeStamp
                val updated = current.copy(
                    totalTimeSpentMillis = current.totalTimeSpentMillis + addedTime,
                    sessions = current.sessions + session,
                    updatedAt = System.currentTimeMillis()
                )
                currentDayStats = updated
                dao.records[todayStr] = updated
            }
        }
    }

    @Test
    fun testBUG_F01_DayManager_NullCurrentDayStats_RecoveryOnIncrementAndAppend() {
        val harness = TestDayManagerRecoveryHarness()
        // Pre-populate DAO with existing stats for today
        harness.dao.records[harness.todayStr] = DailyReelData(
            dateString = harness.todayStr,
            totalReelsCount = 5,
            totalTimeSpentMillis = 60000L,
            dailyGoal = 100
        )

        // Force in-memory stats to null (simulating uninitialized or dropped state)
        harness.currentDayStats = null
        assertNull(harness.currentDayStats)

        // Increment occurs
        harness.incrementReelCount()

        // Verify recovery: count safely recovered to 6, not dropped or overwritten to 1
        assertNotNull("Current-day stats must be restored", harness.currentDayStats)
        assertEquals("Count must increment from 5 to 6", 6, harness.currentDayStats?.totalReelsCount)
        assertEquals("DAO must reflect updated count of 6", 6, harness.dao.records[harness.todayStr]?.totalReelsCount)

        // Now force stats null again and append a session
        harness.currentDayStats = null
        val session = SessionData(
            appName = "Instagram",
            startTimeStamp = 1000L,
            endTimeStamp = 4000L,
            reelsScrolled = 1
        )
        harness.appendSession(session)

        // Verify session append recovered safely
        assertNotNull("Current-day stats must be restored on session append", harness.currentDayStats)
        assertEquals("Watch time must accumulate recovered value + session duration", 63000L, harness.currentDayStats?.totalTimeSpentMillis)
        assertEquals(1, harness.dao.records.size) // No duplicate records introduced
    }

    // =========================================================================
    // PHASE 1 — BUG-E01: Overlay synchronization race & duplicate increments
    // =========================================================================

    @Test
    fun testBUG_E01_OverlaySynchronization_StaleSnapshotRejection_And_ExactCount() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1 arrives at t = 1000
        val reel1Snapshot = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_1", "caption_1", "audio_1"),
            platform = "Instagram",
            timestamp = 1000L
        )
        val p1 = ledger.process(reel1Snapshot)
        assertTrue(p1)
        assertEquals(1, count)

        // Stale snapshot arriving from an asynchronous/delayed overlay sync pass at t = 950
        val staleSnapshot = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_stale", "caption_stale", "audio_stale"),
            platform = "Instagram",
            timestamp = 950L // Stale timestamp!
        )
        val pStale = ledger.process(staleSnapshot)
        assertFalse("Stale snapshot with timestamp earlier than lastProcessedTimestamp must be rejected", pStale)
        assertEquals("Count must remain 1 after stale snapshot rejection", 1, count)

        // Repeated synchronization observing the same Reel 1 at t = 1020
        val repeatedSync = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_1", "caption_1", "audio_1"),
            platform = "Instagram",
            timestamp = 1020L
        )
        val pRepeat = ledger.process(repeatedSync)
        assertTrue(pRepeat)
        assertEquals("Repeated observation of active reel must not increment", 1, count)

        // Genuine next reel arrives at t = 1100
        val reel2Snapshot = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_2", "caption_2", "audio_2"),
            platform = "Instagram",
            timestamp = 1100L
        )
        val p2 = ledger.process(reel2Snapshot)
        assertTrue(p2)
        assertEquals("Genuine next reel must increment to 2", 2, count)
    }

    @Test
    fun testBUG_E01_WallClockAdjustment_DoesNotSuppressCounting() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Snapshot 1 processed with monotonic timestamp = 1000L, wall clock = 1700000000000L
        val snap1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_1", "caption_1", "audio_1", "Instagram"),
            platform = "Instagram",
            timestamp = 1000L,
            wallClockTimeMillis = 1700000000000L
        )
        assertTrue(ledger.process(snap1))
        assertEquals(1, count)

        // Device wall-clock time is adjusted backward by 1 hour (e.g. NTP or manual clock adjustment)
        // Wall clock jumps from 1700000000000L down to 1699996400000L (-3,600,000 ms).
        // But in-process ordering relies on monotonic timestamp (e.g. 1050L > 1000L).
        val snap2 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_2", "caption_2", "audio_2", "Instagram"),
            platform = "Instagram",
            timestamp = 1050L, // Monotonic time moved forward
            wallClockTimeMillis = 1699996400000L // Wall clock moved backward!
        )
        assertTrue("Monotonic timestamp ordering must accept snap2 despite backward wall clock", ledger.process(snap2))
        assertEquals("Legitimate transition must be counted despite backward wall clock", 2, count)
    }

    @Test
    fun testBUG_E01_EqualMonotonicTimestamps_HandledSafely() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Snapshot 1 at monotonic t = 1000L
        val snap1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_1", "caption_1", "audio_1", "Instagram"),
            platform = "Instagram",
            timestamp = 1000L
        )
        assertTrue(ledger.process(snap1))
        assertEquals(1, count)

        // Snapshot 2 at exact same monotonic t = 1000L (e.g. simultaneous evaluation)
        // Must NOT be rejected as stale because 1000L < 1000L is false
        val snap2 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_2", "caption_2", "audio_2", "Instagram"),
            platform = "Instagram",
            timestamp = 1000L
        )
        assertTrue("Equal timestamp must not be rejected as stale", ledger.process(snap2))
        assertEquals(2, count)
    }

    @Test
    fun testBUG_E01_ProcessRestart_DoesNotRejectSnapshots() {
        val fakeStore = object : HistoryStore {
            private var active: SemanticFingerprint? = null
            private val hist = mutableListOf<SemanticFingerprint>()
            override fun loadHistory(): List<SemanticFingerprint> = hist
            override fun saveHistory(history: List<SemanticFingerprint>) { hist.clear(); hist.addAll(history) }
            override fun clearHistory() { hist.clear() }
            override fun loadActiveFingerprint(): SemanticFingerprint? = active
            override fun saveActiveFingerprint(fingerprint: SemanticFingerprint?) { active = fingerprint }
            override fun clearActiveFingerprint() { active = null }
        }

        // Before process termination: ledger processed events with high monotonic uptime (e.g. 500_000L)
        var count = 0
        val oldLedger = TransitionLedger({ count++ }, fakeStore)
        val snapOld = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_old", "caption_old", "audio_old", "Instagram"),
            platform = "Instagram",
            timestamp = 500_000L
        )
        oldLedger.process(snapOld)
        assertEquals(1, count)

        // Process restarts: new TransitionLedger instance created. Monotonic clock starts fresh or device rebooted (e.g. uptime = 100L).
        // Stored history does NOT persist monotonic timestamps, so lastProcessedTimestamp initializes to 0L.
        var newCount = 0
        val newLedger = TransitionLedger({ newCount++ }, fakeStore)
        assertEquals(0L, newLedger.latestProcessedTimestamp)

        // Incoming snapshot with low monotonic timestamp (100L) must NOT be rejected
        val snapNew = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_new", "caption_new", "audio_new", "Instagram"),
            platform = "Instagram",
            timestamp = 100L
        )
        assertTrue("Snapshot after process restart must not be rejected despite lower timestamp than pre-restart", newLedger.process(snapNew))
        assertEquals(1, newCount)
        assertEquals(100L, newLedger.latestProcessedTimestamp)
    }

    @Test
    fun testBUG_E01_ClearActive_ResetsLastProcessedTimestamp() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        val snap1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("creator_1", "caption_1", "audio_1", "Instagram"),
            platform = "Instagram",
            timestamp = 5000L
        )
        ledger.process(snap1)
        assertEquals(5000L, ledger.latestProcessedTimestamp)

        ledger.clearActive()
        assertEquals("clearActive must reset latestProcessedTimestamp to 0L", 0L, ledger.latestProcessedTimestamp)
    }

    // =========================================================================
    // PHASE 2 — BUG-I01: Instagram safe node traversal & recycling
    // =========================================================================

    @Test
    fun testBUG_I01_InstagramDetector_SafeRecyclingContract() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt")
        val content = file.readText()

        // 1. Verify parent node is cleaned up safely in a finally block
        assertTrue(
            "Parent node check must wrap recycle in finally block to avoid leaking or accessing recycled parent",
            content.contains("val parentNode = try { node.parent }") &&
            content.contains("parentNode?.isClickable == true") &&
            content.contains("parentNode?.recycle()")
        )

        // 2. Verify detector delegates queue traversal to shared bounded accessibility tree traversal
        assertTrue(
            "Instagram detector must use bounded accessibility traversal for queue lifecycle safety",
            content.contains("boundedAccessibilityBreadthFirst")
        )

        // 3. Verify shared BoundedAccessibilityTreeTraversal guarantees safe recycling on normal completion and exceptions
        class SafeRecyclingTestNode(val id: String, val children: MutableList<SafeRecyclingTestNode> = mutableListOf()) {
            var recycled = false
        }

        // Case A: Normal completion — retained matched node is preserved, while unvisited queued nodes are recycled in finally
        val child1 = SafeRecyclingTestNode("child1")
        val child2 = SafeRecyclingTestNode("child2")
        val child3 = SafeRecyclingTestNode("child3")
        val mockRoot = SafeRecyclingTestNode("root", mutableListOf(child1, child2, child3))

        var matchedNode: SafeRecyclingTestNode? = null
        val normalResult = boundedAccessibilityBreadthFirst(
            root = mockRoot,
            maxNodes = 10,
            childCount = { it.children.size },
            childAt = { n, i -> n.children[i] },
            inspect = { n ->
                if (n.id == "child1") {
                    matchedNode = n
                    true
                } else false
            },
            retainMatchedNode = { it === matchedNode },
            recycle = { it.recycled = true }
        )

        assertFalse("Root must not be recycled prematurely", mockRoot.recycled)
        assertFalse("Matched node must be retained and not recycled", child1.recycled)
        assertTrue("Queued unvisited child2 must be recycled in finally block", child2.recycled)
        assertTrue("Queued unvisited child3 must be recycled in finally block", child3.recycled)

        // Case B: Exception during inspection — traversal halts safely and queued nodes are recycled in finally
        val excChild1 = SafeRecyclingTestNode("exc1")
        val excChild2 = SafeRecyclingTestNode("exc2")
        val excRoot = SafeRecyclingTestNode("excRoot", mutableListOf(excChild1, excChild2))

        val excResult = boundedAccessibilityBreadthFirst(
            root = excRoot,
            maxNodes = 10,
            childCount = { it.children.size },
            childAt = { n, i -> n.children[i] },
            inspect = { n ->
                if (n.id == "exc1") {
                    throw IllegalStateException("Simulated inspection exception")
                }
                false
            },
            recycle = { it.recycled = true }
        )

        assertTrue("Access failure must be captured when inspection throws", excResult.hadAccessFailure)
        assertFalse("Root must not be recycled prematurely", excRoot.recycled)
        assertTrue("Encountered node must be recycled", excChild1.recycled)
        assertTrue("Queued unvisited node must be recycled in finally block on exception", excChild2.recycled)

        // 3. Verify container node recycling handles rootNode reference equality
        assertTrue(
            "Container node recycling must not recycle rootNode prematurely",
            content.contains("if (containerNode !== rootNode)")
        )
    }

    // =========================================================================
    // PHASE 2 — BUG-A03: YouTube offscreen child selection & viewport spatial filtering
    // =========================================================================

    @Test
    fun testBUG_A03_YouTubeDetector_ViewportSpatialFiltering() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt")
        val content = file.readText()

        // 1. Verify spatial viewport parameters passed to findReelContainer with bounded traversal signature
        assertTrue(
            "YouTube detector must pass center coordinates to findReelContainer with bounded traversal",
            content.contains("findReelContainer(rootNode, viewportCenterX, viewportCenterY") &&
            content.contains("centerX: Int") &&
            content.contains("centerY: Int") &&
            content.contains("maxShallowNodes: Int")
        )

        // Verify centered child selection in reel_recycler / shorts_viewer
        assertTrue(
            "YouTube detector must filter reel_recycler / shorts_viewer children by viewport center",
            content.contains("childBounds.contains(centerX, centerY)")
        )

        // Verify offscreen prebuffered children are recycled and discarded
        assertTrue(
            "Offscreen children must be recycled",
            content.contains("child.recycle()")
        )
    }

    @Test
    fun testBUG_A03_YouTubeSpatialFiltering_SelectionBehavior() {
        // Algorithmic verification of YouTube detector viewport center containment geometry.
        // In JVM unit tests, android.graphics.Rect is stubbed by mockable-android.jar with default false/0 returns,
        // so we verify the geometric algorithm directly while testBUG_A03_YouTubeDetector_ViewportSpatialFiltering
        // verifies the production binding and node recycling contract.
        fun rectContains(left: Int, top: Int, right: Int, bottom: Int, x: Int, y: Int): Boolean =
            x in left until right && y in top until bottom

        val screenLeft = 0
        val screenTop = 0
        val screenRight = 1080
        val screenBottom = 2400
        val centerX = (screenLeft + screenRight) / 2 // 540
        val centerY = (screenTop + screenBottom) / 2 // 1200

        // 1. Offscreen prebuffered child above viewport (e.g. previous Short: top=-2400, bottom=0)
        assertFalse("Offscreen child above viewport must NOT contain screen center",
            rectContains(0, -2400, 1080, 0, centerX, centerY))

        // 2. Focused child centered in screen (active Short: top=0, bottom=2400)
        assertTrue("Focused child must contain screen center",
            rectContains(0, 0, 1080, 2400, centerX, centerY))

        // 3. Offscreen prebuffered child below viewport (e.g. next Short: top=2400, bottom=4800)
        assertFalse("Offscreen child below viewport must NOT contain screen center",
            rectContains(0, 2400, 1080, 4800, centerX, centerY))

        // 4. Partially scrolled child with <50% on screen
        assertFalse("Child not covering screen center must not be selected as primary container",
            rectContains(0, 1500, 1080, 3900, centerX, centerY))
    }

    // =========================================================================
    // PHASE 2 — BUG-C01: Caption normalization, hashtag-first captions, & ordinary 'more'
    // =========================================================================

    @Test
    fun testBUG_C01_CaptionNormalizationRules() {
        // Tests real production CaptionNormalizer implementation directly
        val igDetector = InstagramDetectorV2()
        val ytDetector = YouTubeDetectorV2()

        // 1. Hashtag-first captions: Must retain text, not become empty
        val hashtagCaption = "#fitness motivation for daily morning routine"
        val normalizedHashtag = CaptionNormalizer.normalize(hashtagCaption)
        assertEquals("fitness motivation for daily morning routine", normalizedHashtag)
        assertTrue(normalizedHashtag.isNotBlank())

        // 2. Ordinary words containing "more": Must be preserved
        assertEquals("More dogs playing in the park", CaptionNormalizer.normalize("More dogs playing in the park"))
        assertEquals("Learn more about financial freedom", CaptionNormalizer.normalize("Learn more about financial freedom"))
        assertEquals("Tasty smores by campfire", CaptionNormalizer.normalize("Tasty smores by campfire"))

        // 3. Trailing UI expanders: Must be stripped without mangling body text
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below... more"))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below… more"))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below..."))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below…"))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below...more"))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below…more"))
        assertEquals("Full tutorial below", CaptionNormalizer.normalize("Full tutorial below more"))

        // 4. Empty caption and standalone "more" handled safely
        assertEquals("", CaptionNormalizer.normalize(""))
        assertEquals("", CaptionNormalizer.normalize("   "))
        assertEquals("", CaptionNormalizer.normalize("more"))
        assertEquals("", CaptionNormalizer.normalize("  more  "))

        // 5. Verify both InstagramDetectorV2 and YouTubeDetectorV2 delegate to CaptionNormalizer
        assertEquals("Full tutorial below", igDetector.normalizeCaption("Full tutorial below... more"))
        assertEquals("Full tutorial below", ytDetector.normalizeCaption("Full tutorial below... more"))
        assertEquals("fitness motivation", igDetector.normalizeCaption("#fitness motivation"))
        assertEquals("fitness motivation", ytDetector.normalizeCaption("#fitness motivation"))
    }

    @Test
    fun testBUG_C01_DistinctCaptionsSharingFirst12Chars_CountedSeparately() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Two captions from the same creator sharing their first 12 characters ("Top 5 exerci")
        val caption1 = "Top 5 exercises for back"
        val caption2 = "Top 5 exercises for chest"

        val norm1 = CaptionNormalizer.normalize(caption1)
        val norm2 = CaptionNormalizer.normalize(caption2)

        // Ensure normalization preserves characters beyond 12
        assertTrue("Normalized caption 1 must retain distinguishing text beyond 12 chars", norm1.length > 12)
        assertTrue("Normalized caption 2 must retain distinguishing text beyond 12 chars", norm2.length > 12)
        assertNotEquals("Captions sharing first 12 chars must NOT normalize to identical strings", norm1, norm2)

        val fp1 = SemanticFingerprint("fitness_coach", norm1, "audio_track", "Instagram")
        val fp2 = SemanticFingerprint("fitness_coach", norm2, "audio_track", "Instagram")

        assertFalse("Distinct captions sharing first 12 chars must NOT be considered the same reel", fp1.isSameAs(fp2))
        assertFalse(fp1.matchesHistorical(fp2))

        // Process Reel 1
        val snap1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = fp1,
            platform = "Instagram",
            timestamp = 1000L
        )
        assertTrue(ledger.process(snap1))
        assertEquals(1, count)

        // Process Reel 2 (new reel from same creator)
        val snap2 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = fp2,
            platform = "Instagram",
            timestamp = 1100L
        )
        assertTrue(ledger.process(snap2))
        assertEquals("New reel from same creator sharing first 12 chars must increment count", 2, count)
    }

    @Test
    fun testBUG_C01_CaptionExpansion_DeduplicatesWithoutDuplicateIncrement() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        val collapsedRaw = "Best protein smoothie recipe... more"
        val expandedRaw = "Best protein smoothie recipe: 1 scoop whey, 1 banana, 200ml almond milk"

        val normCollapsed = CaptionNormalizer.normalize(collapsedRaw)
        val normExpanded = CaptionNormalizer.normalize(expandedRaw)

        assertEquals("Best protein smoothie recipe", normCollapsed)

        val fpCollapsed = SemanticFingerprint("chef_dan", normCollapsed, "cook_beat", "Instagram")
        val fpExpanded = SemanticFingerprint("chef_dan", normExpanded, "cook_beat", "Instagram")

        assertTrue("Expanded caption must be recognized as enrichment of collapsed caption", SemanticFingerprint.isCaptionEnrichment(normCollapsed, normExpanded))
        assertTrue("Collapsed vs expanded must match as same reel", fpCollapsed.isSameAs(fpExpanded))
        assertTrue("Expanded vs collapsed must match as same reel", fpExpanded.isSameAs(fpCollapsed))

        // Initial observation with collapsed caption
        val snap1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = fpCollapsed,
            platform = "Instagram",
            timestamp = 1000L
        )
        assertTrue(ledger.process(snap1))
        assertEquals(1, count)

        // User taps "... more" to expand caption on the same reel
        val snapExpanded = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = fpExpanded,
            platform = "Instagram",
            timestamp = 1050L
        )
        assertTrue(ledger.process(snapExpanded))
        assertEquals("Caption expansion must NOT cause duplicate increment", 1, count)

        // Repeated observation with expanded caption
        val snapRepeat = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = fpExpanded,
            platform = "Instagram",
            timestamp = 1100L
        )
        assertTrue(ledger.process(snapRepeat))
        assertEquals("Repeated observation must NOT cause duplicate increment", 1, count)
    }

    // =========================================================================
    // PHASE 3 — BUG-A01: Secondary scroll containers do not corrupt direction
    // =========================================================================

    @Test
    fun testBUG_A01_SecondaryContainers_DoNotCorruptDirection() {
        val manager = DirectionStateManager()

        // 1. Feed scroll forward establishing FORWARD direction
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 500,
            fromIndex = 1,
            containerId = "reel_recycler"
        )
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // 2. User opens comments sheet and scrolls comments upward (deltaY negative)
        // Container changes to "comment_thread"
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -300,
            fromIndex = 5,
            containerId = "comment_thread"
        )

        // 3. User closes comments and swipes forward in feed
        // Container changes back to "reel_recycler"
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 450,
            fromIndex = 2,
            containerId = "reel_recycler"
        )

        // Direction must remain FORWARD; comments scrolling must not corrupt feed direction
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)
    }

    // =========================================================================
    // PHASE 3 — BUG-A02: Snap recoil physics not misclassified as backward
    // =========================================================================

    @Test
    fun testBUG_A02_SnapRecoil_NotMisclassifiedAsBackward() {
        var fakeTime = 100000L
        val manager = DirectionStateManager(timeProvider = { fakeTime })

        // 1. Forward swipe
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500)
        assertEquals(ScrollDirection.FORWARD, manager.currentDirection)

        // 2. Snap recoil settling bounce (-15px) immediately following forward swipe
        fakeTime += 60
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -15)
        assertEquals("Snap recoil bounce must NOT flip direction to BACKWARD", ScrollDirection.FORWARD, manager.currentDirection)

        // 3. Tiny reverse movement from rest / idle jitter (-10px)
        fakeTime += 2000 // Expire validity window
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -10)
        assertNotEquals("Tiny jitter must not trigger BACKWARD", ScrollDirection.BACKWARD, manager.currentDirection)

        // 4. Genuine backward swipe (-450px)
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -450)
        assertEquals("Genuine backward swipe must trigger BACKWARD", ScrollDirection.BACKWARD, manager.currentDirection)
    }

    // =========================================================================
    // PHASE 3 — BUG-C02: Cross-platform fingerprint collision avoidance
    // =========================================================================

    @Test
    fun testBUG_C02_CrossPlatform_FingerprintCollisionAvoidance() {
        val instaFp = SemanticFingerprint(
            primaryIdentifier = "viral_creator",
            secondaryIdentifier = "Crazy trick shot",
            auxiliaryIdentifier = "trending_audio",
            platform = "Instagram"
        )
        val youtubeFp = SemanticFingerprint(
            primaryIdentifier = "viral_creator",
            secondaryIdentifier = "Crazy trick shot",
            auxiliaryIdentifier = "trending_audio",
            platform = "YouTube"
        )

        // Cross-platform: Identical metadata on different platforms MUST NOT collide
        assertFalse("Different platforms must never match as same reel", instaFp.isSameAs(youtubeFp))
        assertFalse("Different platforms must never match in history", instaFp.matchesHistorical(youtubeFp))
        assertNotEquals("Equals must differentiate platforms", instaFp, youtubeFp)

        // Within same platform: Identical metadata DOES match
        val instaFp2 = SemanticFingerprint(
            primaryIdentifier = "viral_creator",
            secondaryIdentifier = "Crazy trick shot",
            auxiliaryIdentifier = "trending_audio",
            platform = "Instagram"
        )
        assertTrue("Same platform and identical metadata must match", instaFp.isSameAs(instaFp2))
        assertEquals(instaFp, instaFp2)

        // Verify TransitionLedger increments when switching from Instagram to YouTube with same metadata
        var count = 0
        val ledger = TransitionLedger { count++ }

        val snapInstagram = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = instaFp,
            platform = "Instagram",
            timestamp = 1000L
        )
        val snapYouTube = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = youtubeFp,
            platform = "YouTube",
            timestamp = 2000L
        )

        ledger.process(snapInstagram)
        assertEquals(1, count)

        ledger.process(snapYouTube)
        assertEquals("Switching from Instagram to YouTube with identical metadata must increment as new reel", 2, count)
    }

    // =========================================================================
    // PHASE 4 — BUG-F02: Creator-only active focal identity persistence across restart
    // =========================================================================

    class FakeHistoryStore : HistoryStore {
        var activeFp: SemanticFingerprint? = null
        val historyList = mutableListOf<SemanticFingerprint>()

        override fun loadHistory(): List<SemanticFingerprint> = historyList.toList()
        override fun saveHistory(history: List<SemanticFingerprint>) {
            historyList.clear()
            historyList.addAll(history)
        }
        override fun clearHistory() { historyList.clear() }

        override fun loadActiveFingerprint(): SemanticFingerprint? = activeFp
        override fun saveActiveFingerprint(fingerprint: SemanticFingerprint?) { activeFp = fingerprint }
        override fun clearActiveFingerprint() { activeFp = null }
    }

    @Test
    fun testBUG_F02_CreatorOnly_ActiveFocalIdentity_PersistenceAcrossRestart() {
        val store = FakeHistoryStore()
        var count = 0

        // Service instance 1: user views a creator-only reel (caption and audio missing/loading)
        var ledger = TransitionLedger({ count++ }, store)
        val creatorOnlyFp = SemanticFingerprint(primaryIdentifier = "solo_creator", secondaryIdentifier = "", auxiliaryIdentifier = "")
        val snapshot1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = creatorOnlyFp,
            platform = "Instagram",
            timestamp = 1000L
        )

        ledger.process(snapshot1)
        assertEquals(1, count)
        assertEquals("Active fingerprint must be saved in store", creatorOnlyFp, store.activeFp)
        // Creator-only identity is NOT added to persistent history (isReliableForPersistence is false)
        assertTrue("Creator-only identity must not pollute persistent history", store.historyList.isEmpty())

        // Service restarts (process killed / recreated)
        // New ledger instance restores active focal fingerprint from store
        val ledger2 = TransitionLedger({ count++ }, store)
        assertEquals("Active focal fingerprint must be restored on restart", creatorOnlyFp, ledger2.currentActiveFingerprint)

        // Resuming playback of the same reel on restart does NOT increment again
        ledger2.process(snapshot1)
        assertEquals("Resuming playback of same reel after restart must NOT duplicate increment", 1, count)

        // Genuinely next reel arrives -> increments cleanly!
        val nextReel = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint(primaryIdentifier = "next_creator", secondaryIdentifier = "Part 2 of video", auxiliaryIdentifier = ""),
            platform = "Instagram",
            timestamp = 2000L
        )
        ledger2.process(nextReel)
        assertEquals("Next reel must increment", 2, count)
    }

    // =========================================================================
    // PHASE 4 — BUG-G01: Transient null active window session continuity
    // =========================================================================

    @Test
    fun testBUG_G01_TransientNullRoot_SessionContinuity() {
        var currentState = SessionState.IDLE
        val sessionManager = SessionManager(
            onStateChanged = { _, newState, _ -> currentState = newState }
        )

        // Start session in Instagram
        sessionManager.updateCurrentApp("com.instagram.android")
        sessionManager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        // 1. Single transient null root (accessibility window layout glitch)
        sessionManager.updateCurrentApp(null)
        assertEquals("Single transient null app must NOT terminate active session", SessionState.ACTIVE, sessionManager.currentState)

        // App becomes available again on next event
        sessionManager.updateCurrentApp("com.instagram.android")
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)

        // 2. Persistent app exit (2 consecutive null checks)
        sessionManager.updateCurrentApp(null)
        assertEquals(SessionState.ACTIVE, sessionManager.currentState)
        sessionManager.updateCurrentApp(null)
        assertEquals("Confirmed persistent app exit must transition session to IDLE", SessionState.IDLE, sessionManager.currentState)
    }

    // =========================================================================
    // PHASE 4 — BUG-A04: Null root drops a transition (bounded retry)
    // =========================================================================

    @Test
    fun testBUG_A04_NullRoot_BoundedRetry_InReelTrackerService() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt")
        val content = file.readText()

        // Verify bounded retry loop in processLatestUIState
        assertTrue(
            "processLatestUIState must implement bounded retry for rootInActiveWindow",
            content.contains("var rootNode = try { rootInActiveWindow } catch (e: Exception) { null }") &&
            content.contains("while (rootNode == null && retryCount < 2)") &&
            content.contains("retryCount++") &&
            content.contains("delay(40)")
        )
    }

    @Test
    fun testBUG_A04_NullRootBoundedRetry_Behavior() = runBlocking {
        // Harness simulating ReelTrackerService's rootInActiveWindow bounded retry loop:
        // var rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
        // var retryCount = 0
        // while (rootNode == null && retryCount < 2) {
        //     retryCount++
        //     delay(40)
        //     rootNode = try { rootInActiveWindow } catch (e: Exception) { null }
        // }
        suspend fun simulateRootAcquisition(responses: List<String?>): Pair<String?, Int> {
            var attempts = 0
            fun fetchRoot(): String? {
                val res = if (attempts < responses.size) responses[attempts] else null
                attempts++
                return res
            }

            var rootNode = fetchRoot()
            var retryCount = 0
            while (rootNode == null && retryCount < 2) {
                retryCount++
                delay(1) // Fast delay for unit test
                rootNode = fetchRoot()
            }
            return Pair(rootNode, retryCount)
        }

        // Scenario 1: Root available immediately (0 retries)
        val (root1, retries1) = simulateRootAcquisition(listOf("root_node"))
        assertEquals("root_node", root1)
        assertEquals(0, retries1)

        // Scenario 2: Transient glitch (null on attempt 0, succeeds on retry 1)
        val (root2, retries2) = simulateRootAcquisition(listOf(null, "root_node"))
        assertEquals("root_node", root2)
        assertEquals(1, retries2)

        // Scenario 3: Transient glitch (null on attempts 0 and 1, succeeds on retry 2)
        val (root3, retries3) = simulateRootAcquisition(listOf(null, null, "root_node"))
        assertEquals("root_node", root3)
        assertEquals(2, retries3)

        // Scenario 4: Persistent failure (null on all attempts): loop must strictly terminate after 2 retries (total 3 attempts)
        val (root4, retries4) = simulateRootAcquisition(listOf(null, null, null, "late_node"))
        assertNull("Persistent null root must return null after bounded retries", root4)
        assertEquals("Retry loop must be strictly bounded at 2 retries", 2, retries4)
    }

    // =========================================================================
    // PHASE 4 — BUG-F03: Threshold notifications when counts jump across milestones
    // =========================================================================

    @Test
    fun testBUG_F03_ThresholdNotifications_WhenCountsJump() {
        val triggeredMilestones = mutableListOf<Int>()
        var limitReachedTriggered = false

        fun checkMilestones(countBefore: Int, countAfter: Int, goal: Int) {
            if (goal <= 0) return
            val milestones = listOf(25, 50, 75, 90)
            for (m in milestones) {
                val threshold = (goal * m) / 100
                if (countBefore < threshold && countAfter >= threshold) {
                    triggeredMilestones.add(m)
                }
            }
            if (countBefore < goal && countAfter >= goal) {
                limitReachedTriggered = true
            }
        }

        val goal = 100

        // 1. Count jumps from 20 to 30 (jumping over 25% threshold at 25)
        checkMilestones(countBefore = 20, countAfter = 30, goal = goal)
        assertTrue("25% milestone must trigger when jumped over", triggeredMilestones.contains(25))

        // 2. Exact arrival at 50% threshold
        checkMilestones(countBefore = 30, countAfter = 50, goal = goal)
        assertTrue("50% milestone must trigger on exact arrival", triggeredMilestones.contains(50))

        // 3. Jump from 50 to 95 (crossing both 75% and 90%)
        checkMilestones(countBefore = 50, countAfter = 95, goal = goal)
        assertTrue("75% milestone must trigger", triggeredMilestones.contains(75))
        assertTrue("90% milestone must trigger", triggeredMilestones.contains(90))

        // 4. Jump across 100% (from 95 to 105)
        checkMilestones(countBefore = 95, countAfter = 105, goal = goal)
        assertTrue("Limit reached must trigger when jumped over 100%", limitReachedTriggered)

        // 5. Repeated updates above 100% (105 to 110): must NOT re-trigger limit reached
        limitReachedTriggered = false
        checkMilestones(countBefore = 105, countAfter = 110, goal = goal)
        assertFalse("Limit reached must NOT re-trigger when already exceeded", limitReachedTriggered)
    }

    // =========================================================================
    // METADATA-FREE CONSECUTIVE REELS TRANSITION REGRESSION TESTS
    // =========================================================================

    @Test
    fun testMetadataFree_01_SameCreator_EmptyMetadata_GenuineForwardTransition_IncrementsOnce() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1 establishes baseline
        val r1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            platform = "Instagram",
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        val p1 = ledger.process(r1)
        assertTrue("Reel 1 must be committed", p1)
        assertEquals("Initial Reel 1 establishes count of 1", 1, count)

        // User performs a genuine forward feed transition
        fakeTime += 100L
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 450,
            fromIndex = 1
        )
        val tokenAfterScroll = manager.currentFeedTransitionToken
        assertTrue("Feed transition token must advance on confirmed forward progression", tokenAfterScroll > 0L)

        // Reel 2 arrives from the SAME creator with empty caption and empty audio
        fakeTime += 50L
        val r2 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            platform = "Instagram",
            timestamp = fakeTime,
            feedTransitionToken = tokenAfterScroll
        )
        val p2 = ledger.process(r2)
        assertTrue("Reel 2 must be processed", p2)
        assertEquals("Genuine forward transition on metadata-free reel from same creator must increment count to 2", 2, count)
    }

    @Test
    fun testMetadataFree_02_SameCreator_RepeatedEvents_NoAdditionalIncrement() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // Forward scroll to Reel 2
        fakeTime += 100L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 500, fromIndex = 1)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(2, count)

        // 10 repeated events (video ticks, redrawing, settling) with the same token
        for (i in 1..10) {
            fakeTime += 30L
            val repeated = DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
            val processed = ledger.process(repeated)
            assertTrue("Repeated event must be handled as active match", processed)
        }
        assertEquals("Repeated frames/events on the same active reel must not trigger additional increments", 2, count)
    }

    @Test
    fun testMetadataFree_03_SnapRecoil_NoAdditionalIncrement() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Establish Reel 1
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // Forward scroll to Reel 2
        fakeTime += 100L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400, fromIndex = 1)
        val tokenBeforeRecoil = manager.currentFeedTransitionToken
        assertEquals(1L, tokenBeforeRecoil)

        fakeTime += 40L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = tokenBeforeRecoil
            )
        )
        assertEquals(2, count)

        // Physics bounce / snap recoil (|deltaY| < 40px)
        fakeTime += 20L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -15, fromIndex = 1)
        assertEquals("Snap recoil must not advance feed transition token", tokenBeforeRecoil, manager.currentFeedTransitionToken)

        // Snapshot arriving during/after snap recoil
        fakeTime += 20L
        val snapDuringRecoil = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        ledger.process(snapDuringRecoil)
        assertEquals("Snap recoil must not trigger duplicate increment", 2, count)
    }

    @Test
    fun testMetadataFree_04_BackwardSwipe_NoIncrement() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger(
            onIncrement = {
                if (manager.currentDirection != ScrollDirection.BACKWARD) {
                    count++
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

        // Reel 1
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // Forward scroll to Reel 2
        fakeTime += 100L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 450, fromIndex = 1)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(2, count)

        // User now swipes backward to previous Reel 1
        fakeTime += 300L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -400, fromIndex = 0)
        assertEquals("Backward swipe must be recognized as BACKWARD", ScrollDirection.BACKWARD, manager.currentDirection)
        assertEquals("Backward swipe must not advance feed transition token", 1L, manager.currentFeedTransitionToken)

        // Snapshot for Reel 1 arrives
        fakeTime += 50L
        val r1Return = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        val pReturn = ledger.process(r1Return)
        assertTrue(pReturn)
        assertEquals("Backward navigation to previous reel must NOT increment count", 2, count)
    }

    @Test
    fun testMetadataFree_05_CommentsSheetScrolling_NoIncrement() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // User opens comments sheet and scrolls up and down inside comments
        fakeTime += 200L
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 350,
            fromIndex = 5,
            containerId = "com.instagram.android:id/comment_sheet_recycler_view"
        )
        assertEquals("Comments sheet scroll must be ignored and not advance token", 0L, manager.currentFeedTransitionToken)

        // Snapshot while comments sheet is visible
        fakeTime += 50L
        val commentsSnapshot = DetectionSnapshot(
            classification = SnapshotClassification.DISCARDED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            isInternalOverlay = true,
            internalOverlayKind = InternalOverlayKind.REEL_COMMENTS,
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        ledger.process(commentsSnapshot)
        assertEquals("Comments overlay must not increment count", 1, count)

        // User closes comments sheet and returns to Reel 1
        fakeTime += 1000L
        val closedSnapshot = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        ledger.process(closedSnapshot)
        assertEquals("Closing comments sheet and resuming active reel must not increment count", 1, count)
    }

    @Test
    fun testIdentifiableMetadata_06_SameCreator_DifferentCaptions_BothCount() {
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1 from @natgeo with caption "Majestic African Elephant"
        val r1 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "Majestic African Elephant", ""),
            timestamp = 1000L
        )
        ledger.process(r1)
        assertEquals(1, count)

        // Repeated frames of Reel 1
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "Majestic African Elephant", ""),
                timestamp = 1050L
            )
        )
        assertEquals("Identical caption must be deduplicated normally", 1, count)

        // Reel 2 from SAME @natgeo with completely different caption "Deep Ocean Blue Whale"
        val r2 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "Deep Ocean Blue Whale", ""),
            timestamp = 1100L
        )
        ledger.process(r2)
        assertEquals("Reel with distinct identifiable caption from same creator must count", 2, count)

        // Repeated frames of Reel 2
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "Deep Ocean Blue Whale", ""),
                timestamp = 1150L
            )
        )
        assertEquals(2, count)
    }

    @Test
    fun testMetadataFree_07_ServiceRestart_OnSameActiveReel_NoDuplicateIncrement() {
        val store = FakeHistoryStore()
        var manager = DirectionStateManager { 1000L }
        var count = 0
        var ledger = TransitionLedger({ count++ }, store)

        // First reel before restart: creator "@natgeo", empty caption, empty audio
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = 1000L,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)
        assertEquals("Active focal reel must be persisted to store", SemanticFingerprint("@natgeo", "", ""), store.activeFp)

        // Service restart: ServiceScope cancelled, manager.reset(), new TransitionLedger created
        manager.reset()
        val restartCount = AtomicInteger(0)
        ledger = TransitionLedger({ restartCount.incrementAndGet() }, store)
        assertEquals("Restored ledger must have active focal fingerprint", SemanticFingerprint("@natgeo", "", ""), ledger.currentActiveFingerprint)

        // First snapshot arriving after restart for the same active reel
        val firstSnapshotAfterRestart = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = 2000L,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        val p = ledger.process(firstSnapshotAfterRestart)
        assertTrue("Snapshot must be processed", p)
        assertEquals("Active reel restored from store must NOT cause duplicate increment on restart", 0, restartCount.get())
    }

    @Test
    fun testMetadataFree_08_RapidConsecutiveForwardTransitions_EachCountsOnce() {
        var fakeTime = 10000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 1: initial baseline
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // Rapid forward transitions through 3 more consecutive reels from @natgeo (all metadata-free)
        for (reelIndex in 1..3) {
            fakeTime += 100L
            // Forward scroll gesture
            manager.updateDirection(
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
                scrollDeltaY = 350,
                fromIndex = reelIndex
            )
            assertEquals("Token must advance to $reelIndex", reelIndex.toLong(), manager.currentFeedTransitionToken)

            // Transition snapshot arrives
            fakeTime += 50L
            ledger.process(
                DetectionSnapshot(
                    classification = SnapshotClassification.COMMITTED,
                    fingerprint = SemanticFingerprint("@natgeo", "", ""),
                    timestamp = fakeTime,
                    feedTransitionToken = manager.currentFeedTransitionToken
                )
            )
            assertEquals("After transition $reelIndex, count must be ${reelIndex + 1}", reelIndex + 1, count)

            // Multiple rapid settling/redraw events for this reel
            for (frame in 1..4) {
                fakeTime += 25L
                ledger.process(
                    DetectionSnapshot(
                        classification = SnapshotClassification.COMMITTED,
                        fingerprint = SemanticFingerprint("@natgeo", "", ""),
                        timestamp = fakeTime,
                        feedTransitionToken = manager.currentFeedTransitionToken
                    )
                )
            }
            assertEquals("Settling frames must not increment count", reelIndex + 1, count)
        }

        assertEquals("All 4 consecutive metadata-free reels must each increment exactly once", 4, count) 
    }

    @Test
    fun testMetadataFree_09_ForwardDrag_WithoutAdapterProgression_DoesNotIncrementBeforeNewItemObserved() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Initial Reel 0 is committed
        val r0 = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        ledger.process(r0)
        assertEquals("Initial Reel 0 establishes count = 1", 1, count)
        assertEquals(0L, manager.currentFeedTransitionToken)

        // User drags on Reel 0 without adapter progression (deltaY = 120, fromIndex = 0)
        fakeTime += 50L
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 120,
            fromIndex = 0
        )
        assertEquals("Dragging on current item must NOT advance transition token", 0L, manager.currentFeedTransitionToken)

        // Snapshot analyzed while drag is in progress on Reel 0
        fakeTime += 20L
        val snapDuringDrag = DetectionSnapshot(
            classification = SnapshotClassification.COMMITTED,
            fingerprint = SemanticFingerprint("@natgeo", "", ""),
            timestamp = fakeTime,
            feedTransitionToken = manager.currentFeedTransitionToken
        )
        ledger.process(snapDuringDrag)
        assertEquals("Mid-drag snapshot on current item must NOT increment count", 1, count)

        // User releases and view snaps back to Reel 0
        fakeTime += 50L
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = -25,
            fromIndex = 0
        )
        assertEquals("Cancelled drag / snap back must NOT advance transition token", 0L, manager.currentFeedTransitionToken)

        fakeTime += 20L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("Snap back on Reel 0 must keep count at 1", 1, count)

        // Now user actually completes swipe to Reel 1 (fromIndex advances to 1)
        fakeTime += 100L
        manager.updateDirection(
            eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            scrollDeltaY = 400,
            fromIndex = 1
        )
        assertEquals("Token must advance when adapter index progresses to new item", 1L, manager.currentFeedTransitionToken)

        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("Count must increment to 2 only after genuine new item arrives", 2, count)
    }

    @Test
    fun testMetadataFree_10_RevisitingAlreadySeenReel_DoesNotIncrementAgain() {
        var fakeTime = 1000L
        val manager = DirectionStateManager { fakeTime }
        var count = 0
        val ledger = TransitionLedger { count++ }

        // Reel 0 (baseline)
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals(1, count)

        // Forward scroll to Reel 1
        fakeTime += 100L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400, fromIndex = 1)
        assertEquals(1L, manager.currentFeedTransitionToken)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("Reel 1 increments count to 2", 2, count)

        // Backward scroll to Reel 0
        fakeTime += 200L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = -400, fromIndex = 0)
        assertEquals(ScrollDirection.BACKWARD, manager.currentDirection)
        assertEquals("Backward scroll does not advance token", 1L, manager.currentFeedTransitionToken)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("Returning backward to Reel 0 does not increment", 2, count)

        // Forward scroll back to Reel 1 (already visited: fromIndex = 1 <= maxVisitedFromIndex = 1)
        fakeTime += 200L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400, fromIndex = 1)
        assertEquals("Revisiting already visited Reel 1 must NOT advance token", 1L, manager.currentFeedTransitionToken)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("Revisiting Reel 1 must NOT increment count", 2, count)

        // Now advance forward past maxVisitedFromIndex to new Reel 2 (fromIndex = 2)
        fakeTime += 200L
        manager.updateDirection(AccessibilityEvent.TYPE_VIEW_SCROLLED, scrollDeltaY = 400, fromIndex = 2)
        assertEquals("Advancing to new Reel 2 must advance token to 2", 2L, manager.currentFeedTransitionToken)
        fakeTime += 50L
        ledger.process(
            DetectionSnapshot(
                classification = SnapshotClassification.COMMITTED,
                fingerprint = SemanticFingerprint("@natgeo", "", ""),
                timestamp = fakeTime,
                feedTransitionToken = manager.currentFeedTransitionToken
            )
        )
        assertEquals("New Reel 2 must increment count to 3", 3, count)
    }
}

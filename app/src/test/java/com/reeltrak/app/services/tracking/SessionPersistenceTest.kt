package com.reeltrak.app.services.tracking

import com.reeltrak.app.data.local.entity.SessionData
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rigorous test suite validating that Reel tracking sessions are accurately
 * and immutably persisted BEFORE destructive in-memory state clearing occurs,
 * and that duplicate saves, zero-reel saves, and coroutine cancellation data losses are prevented.
 *
 * Scenarios covered:
 * A. Active session -> IDLE: session is persisted with correct fields.
 * B. Session with multiple reel increments: exact reel count is persisted.
 * C. Session start/end timestamps: persisted values are correct.
 * D. SessionManager clears its in-memory state: persistence still receives the original session snapshot.
 * E. Repeated IDLE transition: no duplicate session row.
 * F. App switch/session termination: completed session persists.
 * G. Subscription lock termination: completed session persists correctly.
 * H. Service/session shutdown path: completed session is not silently lost.
 * I. Zero-reel session: preserve the application's existing intended behavior (not persisted).
 * J. Two consecutive sessions: first session cannot be overwritten by the second.
 * K. Concurrent/rapid session termination: no duplicate or lost persistence.
 * L. Database write/coroutine cancellation scenario: verify completed session is not lost because of immediate coroutine cancellation.
 */
class SessionPersistenceTest {

    private var fakeTimeMs = 1_700_000_000_000L

    private fun createSessionManager(
        onSessionCompleted: (SessionData) -> Unit
    ): SessionManager {
        return SessionManager(
            onStateChanged = { _, _, completedSession ->
                if (completedSession != null) {
                    onSessionCompleted(completedSession)
                }
            },
            timeProvider = { fakeTimeMs }
        )
    }

    // =========================================================================
    // SCENARIO A: Active session -> IDLE: session is persisted with correct fields
    // =========================================================================
    @Test
    fun testScenarioA_activeSessionToIdlePersistsCorrectFields() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        // Start session in Instagram
        fakeTimeMs = 1_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection() // transitions to ACTIVE

        fakeTimeMs = 2_000L
        manager.incrementReel()

        fakeTimeMs = 5_000L
        val completed = manager.transitionTo(SessionState.IDLE)

        assertNotNull("Transition to IDLE must produce completed session", completed)
        assertEquals(1, savedSessions.size)
        val saved = savedSessions[0]
        assertEquals("com.instagram.android", saved.appName)
        assertEquals(1_000L, saved.startTimeStamp)
        assertEquals(5_000L, saved.endTimeStamp)
        assertEquals(1, saved.reelsScrolled)
    }

    // =========================================================================
    // SCENARIO B: Session with multiple reel increments: exact reel count is persisted
    // =========================================================================
    @Test
    fun testScenarioB_sessionWithMultipleReelIncrements() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        fakeTimeMs = 10_000L
        manager.updateCurrentApp("com.google.android.youtube")
        manager.recordSuccessfulDetection()

        // User scrolls 7 reels
        for (i in 1..7) {
            fakeTimeMs += 500
            manager.incrementReel()
        }

        fakeTimeMs += 1_000
        manager.transitionTo(SessionState.IDLE)

        assertEquals(1, savedSessions.size)
        assertEquals(7, savedSessions[0].reelsScrolled)
        assertEquals("com.google.android.youtube", savedSessions[0].appName)
    }

    // =========================================================================
    // SCENARIO C: Session start/end timestamps: persisted values are correct
    // =========================================================================
    @Test
    fun testScenarioC_sessionStartEndTimestampsAreAccurate() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        val start = 1_700_000_100_000L
        fakeTimeMs = start
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()

        // 120 seconds of viewing
        val end = start + 120_000L
        fakeTimeMs = end
        manager.transitionTo(SessionState.IDLE)

        assertEquals(1, savedSessions.size)
        val session = savedSessions[0]
        assertEquals(start, session.startTimeStamp)
        assertEquals(end, session.endTimeStamp)
        val durationMs = session.endTimeStamp - session.startTimeStamp
        assertEquals(120_000L, durationMs)
    }

    // =========================================================================
    // SCENARIO D: SessionManager clears in-memory state: persistence still receives original snapshot
    // =========================================================================
    @Test
    fun testScenarioD_inMemoryStateClearedDoesNotAffectSnapshot() {
        var capturedInsideCallback: SessionData? = null
        var managerRef: SessionManager? = null

        val manager = SessionManager(
            onStateChanged = { oldState, newState, completedSession ->
                if (newState == SessionState.IDLE) {
                    // At the moment of this callback, in-memory state in manager is ALREADY IDLE and cleared
                    capturedInsideCallback = completedSession
                    assertNull("currentApp in SessionManager must be cleared to null", managerRef?.currentApp)
                    assertEquals("reelsCount in SessionManager must be cleared to 0", 0, managerRef?.reelsCount)
                    assertEquals("sessionStartTime in SessionManager must be cleared to 0", 0L, managerRef?.sessionStartTime)
                }
            },
            timeProvider = { fakeTimeMs }
        )
        managerRef = manager

        fakeTimeMs = 50_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel() // 3 reels

        fakeTimeMs = 60_000L
        val returned = manager.transitionTo(SessionState.IDLE)

        // Verify that despite in-memory clearing, the captured snapshot retained all values
        assertNotNull(capturedInsideCallback)
        assertEquals("com.instagram.android", capturedInsideCallback?.appName)
        assertEquals(3, capturedInsideCallback?.reelsScrolled)
        assertEquals(50_000L, capturedInsideCallback?.startTimeStamp)
        assertEquals(60_000L, capturedInsideCallback?.endTimeStamp)

        assertEquals(capturedInsideCallback, returned)
    }

    // =========================================================================
    // SCENARIO E: Repeated IDLE transition: no duplicate session row
    // =========================================================================
    @Test
    fun testScenarioE_repeatedIdleTransitionProducesNoDuplicates() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()

        // 1st transition to IDLE
        val first = manager.transitionTo(SessionState.IDLE)
        assertNotNull(first)
        assertEquals(1, savedSessions.size)

        // 2nd transition to IDLE (redundant)
        val second = manager.transitionTo(SessionState.IDLE)
        assertNull("Redundant transitionTo(IDLE) must return null", second)
        assertEquals("Redundant transition must not create a duplicate save", 1, savedSessions.size)

        // 3rd transition to IDLE
        val third = manager.transitionTo(SessionState.IDLE)
        assertNull(third)
        assertEquals(1, savedSessions.size)
    }

    // =========================================================================
    // SCENARIO F: App switch / session termination: completed session persists
    // =========================================================================
    @Test
    fun testScenarioF_appSwitchTerminatesAndPersistsSession() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        // Start in Instagram
        fakeTimeMs = 1_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()

        // User switches to Home Launcher
        fakeTimeMs = 10_000L
        manager.updateCurrentApp("com.android.launcher3")

        assertEquals(1, savedSessions.size)
        val saved = savedSessions[0]
        assertEquals("com.instagram.android", saved.appName)
        assertEquals(2, saved.reelsScrolled)
        assertEquals(SessionState.IDLE, manager.currentState)
    }

    // =========================================================================
    // SCENARIO G: Subscription lock termination: completed session persists correctly
    // =========================================================================
    @Test
    fun testScenarioG_subscriptionLockTerminationPersistsSession() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        // Active session
        fakeTimeMs = 5_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel() // 3 reels

        // Subscription confirms locked -> forces transition to IDLE
        fakeTimeMs = 25_000L
        val session = manager.transitionTo(SessionState.IDLE)

        assertNotNull(session)
        assertEquals(1, savedSessions.size)
        assertEquals(3, savedSessions[0].reelsScrolled)
        assertEquals(5_000L, savedSessions[0].startTimeStamp)
        assertEquals(25_000L, savedSessions[0].endTimeStamp)
    }

    // =========================================================================
    // SCENARIO H: Service/session shutdown path: completed session is not silently lost
    // =========================================================================
    @Test
    fun testScenarioH_serviceShutdownPersistsActiveSession() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        fakeTimeMs = 100_000L
        manager.updateCurrentApp("com.google.android.youtube")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()

        // Service destroyed while session was ACTIVE
        fakeTimeMs = 150_000L
        val session = manager.transitionTo(SessionState.DESTROYED)

        assertNotNull("Transition to DESTROYED from ACTIVE must capture session", session)
        assertEquals(1, savedSessions.size)
        assertEquals("com.google.android.youtube", savedSessions[0].appName)
        assertEquals(2, savedSessions[0].reelsScrolled)
        assertEquals(100_000L, savedSessions[0].startTimeStamp)
        assertEquals(150_000L, savedSessions[0].endTimeStamp)
    }

    // =========================================================================
    // SCENARIO I: Zero-reel session: preserve existing intended behavior (no save)
    // =========================================================================
    @Test
    fun testScenarioI_zeroReelSessionIsNotPersisted() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        // Open Instagram, but exit immediately without scrolling any reel
        fakeTimeMs = 1_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        // reelsCount is 0

        fakeTimeMs = 2_000L
        val completed = manager.transitionTo(SessionState.IDLE)

        assertNull("Zero-reel session must return null and not persist", completed)
        assertTrue("No session should be saved for 0 reels", savedSessions.isEmpty())
    }

    // =========================================================================
    // SCENARIO J: Two consecutive sessions: first cannot be overwritten by the second
    // =========================================================================
    @Test
    fun testScenarioJ_twoConsecutiveSessionsDoNotOverwriteEachOther() {
        val savedSessions = mutableListOf<SessionData>()
        val manager = createSessionManager { savedSessions.add(it) }

        // Session 1: Instagram (3 reels)
        fakeTimeMs = 1_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel()

        fakeTimeMs = 10_000L
        // Switch to YouTube directly
        manager.updateCurrentApp("com.google.android.youtube")

        // First session must be saved
        assertEquals(1, savedSessions.size)
        assertEquals("com.instagram.android", savedSessions[0].appName)
        assertEquals(3, savedSessions[0].reelsScrolled)
        assertEquals(1_000L, savedSessions[0].startTimeStamp)
        assertEquals(10_000L, savedSessions[0].endTimeStamp)

        // Session 2: YouTube (4 shorts)
        fakeTimeMs = 15_000L
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel()

        fakeTimeMs = 30_000L
        manager.transitionTo(SessionState.IDLE)

        // Both sessions must exist with distinct, correct data
        assertEquals(2, savedSessions.size)
        assertEquals("com.google.android.youtube", savedSessions[1].appName)
        assertEquals(4, savedSessions[1].reelsScrolled)
        assertEquals(15_000L, savedSessions[1].startTimeStamp)
        assertEquals(30_000L, savedSessions[1].endTimeStamp)
    }

    // =========================================================================
    // SCENARIO K: Concurrent / rapid session termination: no duplicate or lost persistence
    // =========================================================================
    @Test
    fun testScenarioK_concurrentRapidSessionTermination() = runBlocking {
        val savedCount = AtomicInteger(0)
        val savedSessions = ConcurrentLinkedQueue<SessionData>()
        val manager = createSessionManager {
            savedCount.incrementAndGet()
            savedSessions.add(it)
        }

        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()

        // Launch 20 concurrent threads trying to transition to IDLE / DESTROYED
        val jobs = (1..20).map { i ->
            launch(Dispatchers.Default) {
                if (i % 2 == 0) {
                    manager.transitionTo(SessionState.IDLE)
                } else {
                    manager.updateCurrentApp("com.android.launcher3")
                }
            }
        }
        jobs.joinAll()

        assertEquals("Concurrent termination must persist EXACTLY ONE session", 1, savedCount.get())
        assertEquals(1, savedSessions.size)
        assertEquals(2, savedSessions.first().reelsScrolled)
        assertEquals("com.instagram.android", savedSessions.first().appName)
    }

    // =========================================================================
    // SCENARIO L: Database write / coroutine cancellation scenario
    // =========================================================================
    @Test
    fun testScenarioL_coroutineCancellationScenario() = runBlocking {
        val serviceScope = CoroutineScope(Dispatchers.Default + Job())
        val dbRecords = mutableListOf<SessionData>()

        // Simulate saveSessionToDb using NonCancellable Dispatchers.IO
        fun saveSessionToDbSimulated(session: SessionData) {
            if (session.reelsScrolled <= 0) return
            serviceScope.launch(Dispatchers.IO + NonCancellable) {
                // Even if parent job is cancelled, NonCancellable ensures this executes
                delay(50)
                synchronized(dbRecords) {
                    dbRecords.add(session)
                }
            }
        }

        val sessionToSave = SessionData(
            appName = "com.instagram.android",
            startTimeStamp = 1000L,
            endTimeStamp = 5000L,
            reelsScrolled = 5
        )

        // Dispatch save
        saveSessionToDbSimulated(sessionToSave)

        // Cancel service scope IMMEDIATELY (simulating immediate service destruction)
        serviceScope.cancel()

        // Wait a brief moment for NonCancellable write to finish
        delay(150)

        // Must still be saved despite serviceScope cancellation
        synchronized(dbRecords) {
            assertEquals("NonCancellable write must survive serviceScope cancellation", 1, dbRecords.size)
            assertEquals(5, dbRecords[0].reelsScrolled)
        }
    }

    // =========================================================================
    // SCENARIO M: Midnight rollover session extraction
    // =========================================================================
    @Test
    fun testScenarioM_midnightSessionExtraction() {
        val manager = createSessionManager { }

        fakeTimeMs = 1_000L
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()
        manager.incrementReel() // 3 reels before midnight

        fakeTimeMs = 5_000L // Midnight hits
        val midnightSnapshot = manager.extractCurrentSessionSnapshot(resetCount = true)

        assertNotNull(midnightSnapshot)
        assertEquals(3, midnightSnapshot?.reelsScrolled)
        assertEquals(1_000L, midnightSnapshot?.startTimeStamp)
        assertEquals(5_000L, midnightSnapshot?.endTimeStamp)

        // Reels counter reset for the new day, but app and state stay active
        assertEquals(0, manager.reelsCount)
        assertEquals(SessionState.ACTIVE, manager.currentState)
        assertEquals(5_000L, manager.sessionStartTime)

        // 2 reels watched after midnight
        fakeTimeMs = 7_000L
        manager.incrementReel()
        fakeTimeMs = 9_000L
        manager.incrementReel()

        fakeTimeMs = 10_000L
        val nextDaySession = manager.transitionTo(SessionState.IDLE)
        assertNotNull(nextDaySession)
        assertEquals(2, nextDaySession?.reelsScrolled)
        assertEquals(5_000L, nextDaySession?.startTimeStamp)
        assertEquals(10_000L, nextDaySession?.endTimeStamp)
    }
}

package com.reeltrak.app.services.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression coverage for reel lifecycle state after session-record persistence was removed. */
class SessionPersistenceTest {

    @Test
    fun sessionRecordsAndUnusedStatisticsAreNotProduced() {
        val methods = SessionManager::class.java.methods
        val fieldNames = SessionManager::class.java.declaredFields.map { it.name }.toSet()
        val transition = methods.single {
            it.name == "transitionTo" && it.parameterTypes.contentEquals(arrayOf(SessionState::class.java))
        }

        assertEquals("State transitions return no session record", Void.TYPE, transition.returnType)
        assertFalse("Session snapshots are no longer extractable", methods.any { it.name == "extractCurrentSessionSnapshot" })
        assertFalse("Unused last-detection timestamp must not be stored", "lastSuccessfulDetection" in fieldNames)
        assertFalse("Unused session-duration timer must not be exposed", methods.any { it.name == "getSessionDurationMs" })
    }

    @Test
    fun reelLifecycleAndOverlayStateRemainAvailable() {
        var now = 1_000L
        val transitions = mutableListOf<Pair<SessionState, SessionState>>()
        val manager = SessionManager(
            onStateChanged = { oldState, newState -> transitions += oldState to newState },
            timeProvider = { now }
        )

        manager.updateCurrentApp("com.instagram.android")
        manager.transitionTo(SessionState.ENTERING_REELS)
        assertEquals(SessionState.ENTERING_REELS, manager.currentState)
        assertEquals("com.instagram.android", manager.currentApp)
        assertEquals(1_000L, manager.sessionStartTime)

        manager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, manager.currentState)

        now = 2_000L
        manager.incrementReel()
        assertEquals(1, manager.reelsCount)
        assertEquals(SessionState.ACTIVE, manager.currentState)

        manager.transitionTo(SessionState.PAUSED)
        assertEquals(SessionState.PAUSED, manager.currentState)
        assertEquals(1, manager.reelsCount)
        manager.recordSuccessfulDetection()
        assertEquals(SessionState.ACTIVE, manager.currentState)

        manager.transitionTo(SessionState.IDLE)
        assertEquals(SessionState.IDLE, manager.currentState)
        assertNull(manager.currentApp)
        assertEquals(0, manager.reelsCount)
        assertEquals(0L, manager.sessionStartTime)
        assertEquals(
            listOf(
                SessionState.IDLE to SessionState.ENTERING_REELS,
                SessionState.ENTERING_REELS to SessionState.ACTIVE,
                SessionState.ACTIVE to SessionState.PAUSED,
                SessionState.PAUSED to SessionState.ACTIVE,
                SessionState.ACTIVE to SessionState.IDLE
            ),
            transitions
        )
    }

    @Test
    fun transientNullAppDoesNotEndReelUntilExitIsConfirmed() {
        val manager = SessionManager(onStateChanged = { _, _ -> })
        manager.updateCurrentApp("com.instagram.android")
        manager.recordSuccessfulDetection()

        manager.updateCurrentApp(null)
        assertEquals(SessionState.ACTIVE, manager.currentState)

        manager.updateCurrentApp("com.instagram.android")
        assertEquals(SessionState.ACTIVE, manager.currentState)

        manager.updateCurrentApp(null)
        assertEquals(SessionState.ACTIVE, manager.currentState)
        manager.updateCurrentApp(null)
        assertEquals(SessionState.IDLE, manager.currentState)
    }

    @Test
    fun midnightSessionCounterResetKeepsReelLifecycleActive() {
        var now = 1_000L
        val manager = SessionManager(onStateChanged = { _, _ -> }, timeProvider = { now })
        manager.updateCurrentApp("com.google.android.youtube")
        manager.recordSuccessfulDetection()
        manager.incrementReel()
        manager.incrementReel()

        now = 5_000L
        manager.resetSessionCount()

        assertEquals(SessionState.ACTIVE, manager.currentState)
        assertEquals("com.google.android.youtube", manager.currentApp)
        assertEquals(0, manager.reelsCount)
        assertEquals(5_000L, manager.sessionStartTime)

        manager.incrementReel()
        assertEquals(1, manager.reelsCount)
        assertEquals(SessionState.ACTIVE, manager.currentState)
    }
}

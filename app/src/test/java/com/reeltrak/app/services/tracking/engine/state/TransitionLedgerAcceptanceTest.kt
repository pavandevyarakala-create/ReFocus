package com.reeltrak.app.services.tracking.engine.state

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class TransitionLedgerAcceptanceTest {
    private fun findSourceFile(relativePath: String): File = listOf(
        File(relativePath), File("app/$relativePath"), File("../app/$relativePath")
    ).firstOrNull { it.exists() } ?: error("Source file not found: $relativePath")

    private fun reel(name: String, timestamp: Long = 100L) = DetectionSnapshot(
        timestamp = timestamp,
        classification = SnapshotClassification.COMMITTED,
        fingerprint = SemanticFingerprint(name, "caption-$name", "audio-$name", "Instagram"),
        platform = "Instagram"
    )

    private fun ledger(
        acceptance: () -> TransitionAcceptance,
        committed: (TransitionAcceptance) -> Unit = {}
    ) = TransitionLedger(
        onIncrement = {},
        onHistoricalMatch = null,
        historyStore = null,
        transitionAcceptance = acceptance,
        onTransitionCommitted = committed
    )

    @Test
    fun `rejection leaves candidate uncommitted and same explicit snapshot can later be accepted`() {
        var gateOpen = false
        val dailyCount = AtomicInteger()
        val ledger = ledger(acceptance = {
            if (!gateOpen) TransitionAcceptance.REJECTED
            else {
                dailyCount.incrementAndGet()
                TransitionAcceptance.ACCEPTED
            }
        })
        val reel = reel("a")

        assertFalse(ledger.process(reel))
        assertNull(ledger.currentActiveFingerprint)
        assertTrue(ledger.historySnapshot.isEmpty())
        assertEquals(0L, ledger.latestProcessedTimestamp)
        assertEquals(0, dailyCount.get())

        gateOpen = true
        assertTrue(ledger.process(reel))
        assertEquals(reel.fingerprint, ledger.currentActiveFingerprint)
        assertEquals(1, ledger.historySnapshot.size)
        assertEquals(1, dailyCount.get())
    }

    @Test
    fun `duplicate accessibility snapshots dispatch an accepted increment once`() {
        val dailyCount = AtomicInteger()
        val ledger = ledger(acceptance = {
            dailyCount.incrementAndGet()
            TransitionAcceptance.ACCEPTED
        })
        val reel = reel("same")

        assertTrue(ledger.process(reel))
        assertTrue(ledger.process(reel.copy(timestamp = 101L)))
        assertEquals(1, dailyCount.get())
        assertEquals(1, ledger.historySnapshot.size)
    }

    @Test
    fun `exception or cancellation before acceptance leaves no partial ledger state`() {
        var shouldThrow = true
        val dailyCount = AtomicInteger()
        val ledger = ledger(acceptance = {
            if (shouldThrow) throw CancellationException("cancel before acceptance")
            dailyCount.incrementAndGet()
            TransitionAcceptance.ACCEPTED
        })
        val reel = reel("cancel-before")

        try {
            ledger.process(reel)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the callback ran before any focal identity/history mutation.
        }
        assertNull(ledger.currentActiveFingerprint)
        assertTrue(ledger.historySnapshot.isEmpty())
        assertEquals(0L, ledger.latestProcessedTimestamp)

        shouldThrow = false
        assertTrue(ledger.process(reel))
        assertEquals(1, dailyCount.get())
        assertEquals(reel.fingerprint, ledger.currentActiveFingerprint)
    }

    @Test
    fun `ordinary acceptance exception leaves the candidate available for an explicit retry`() {
        var shouldThrow = true
        val dailyCount = AtomicInteger()
        val ledger = ledger(acceptance = {
            if (shouldThrow) throw IllegalStateException("acceptance failed before enqueue")
            dailyCount.incrementAndGet()
            TransitionAcceptance.ACCEPTED
        })
        val reel = reel("exception-before")

        try {
            ledger.process(reel)
            throw AssertionError("Expected acceptance exception")
        } catch (_: IllegalStateException) {
            // The callback failed before ledger state was changed.
        }
        assertNull(ledger.currentActiveFingerprint)
        assertTrue(ledger.historySnapshot.isEmpty())
        assertEquals(0L, ledger.latestProcessedTimestamp)
        assertEquals(0, dailyCount.get())

        shouldThrow = false
        assertTrue(ledger.process(reel))
        assertEquals(1, dailyCount.get())
        assertEquals(reel.fingerprint, ledger.currentActiveFingerprint)
    }

    @Test
    fun `cancellation after ledger commitment does not reopen or duplicate the transition`() {
        val dailyCount = AtomicInteger()
        val postCommitCalls = AtomicInteger()
        val ledger = ledger(
            acceptance = {
                dailyCount.incrementAndGet()
                TransitionAcceptance.ACCEPTED
            },
            committed = {
                postCommitCalls.incrementAndGet()
                throw CancellationException("post-commit observer canceled")
            }
        )
        val reel = reel("cancel-after")

        assertTrue(ledger.process(reel))
        assertTrue(ledger.process(reel.copy(timestamp = 101L)))
        assertEquals(1, dailyCount.get())
        assertEquals(1, postCommitCalls.get())
        assertEquals(reel.fingerprint, ledger.currentActiveFingerprint)
    }

    @Test
    fun `suppressed backward transition commits without counting and historical revisit stays deduplicated`() {
        val dailyCount = AtomicInteger()
        var nextDecision = TransitionAcceptance.ACCEPTED
        val historicalMatches = AtomicInteger()
        val ledger = TransitionLedger(
            onIncrement = {},
            onHistoricalMatch = { historicalMatches.incrementAndGet() },
            historyStore = null,
            transitionAcceptance = {
                if (nextDecision == TransitionAcceptance.ACCEPTED) dailyCount.incrementAndGet()
                nextDecision
            }
        )
        val a = reel("a", 100L)
        val b = reel("b", 200L)

        assertTrue(ledger.process(a))
        assertTrue(ledger.process(b))
        assertEquals(2, dailyCount.get())

        // Model a backward transition to an unseen reel. It is consumed but not counted.
        nextDecision = TransitionAcceptance.SUPPRESSED
        val c = reel("c", 300L)
        assertTrue(ledger.process(c))
        assertEquals(2, dailyCount.get())

        // A historical return does not ask for acceptance or dispatch another count.
        nextDecision = TransitionAcceptance.REJECTED
        assertTrue(ledger.process(a.copy(timestamp = 400L)))
        assertEquals(2, dailyCount.get())
        assertEquals(1, historicalMatches.get())
        assertEquals(a.fingerprint, ledger.currentActiveFingerprint)
    }

    @Test
    fun `update locked and day manager rejection gates do not bypass ledger acceptance`() {
        var updateRequired = false
        var locked = false
        var dayManagerRejects = false
        val dailyCount = AtomicInteger()
        val ledger = ledger(acceptance = {
            if (updateRequired || locked || dayManagerRejects) TransitionAcceptance.REJECTED
            else {
                dailyCount.incrementAndGet()
                TransitionAcceptance.ACCEPTED
            }
        })
        val reel = reel("protected")

        updateRequired = true
        assertFalse(ledger.process(reel))
        updateRequired = false
        locked = true
        assertFalse(ledger.process(reel))
        locked = false
        dayManagerRejects = true
        assertFalse(ledger.process(reel))

        assertNull(ledger.currentActiveFingerprint)
        assertTrue(ledger.historySnapshot.isEmpty())
        assertEquals(0, dailyCount.get())

        dayManagerRejects = false
        assertTrue(ledger.process(reel))
        assertEquals(1, dailyCount.get())
        assertEquals(reel.fingerprint, ledger.currentActiveFingerprint)
    }

    @Test
    fun `production acceptance checks update authorization restore and day manager gates before commit`() {
        val service = findSourceFile("src/main/java/com/reeltrak/app/services/ReelTrackerService.kt").readText()
        val incrementStart = service.indexOf("private fun incrementProductionCount(): TransitionAcceptance")
        val committedStart = service.indexOf("private fun applyCommittedTransition", incrementStart)
        val incrementBody = service.substring(incrementStart, committedStart)
        val updateGate = incrementBody.indexOf("if (inAppUpdateManager.isUpdateRequired.value)")
        val lockedGate = incrementBody.indexOf("OperationalMode.LOCKED_OR_EXPIRED")
        val dayManagerCall = incrementBody.indexOf("dayManager.acceptReelTransition(fingerprint, token)")
        val rejectedDayManager = incrementBody.indexOf("durableAcceptance == com.reeltrak.app.services.tracking.ReelCountAcceptance.REJECTED")

        assertTrue(updateGate >= 0 && updateGate < dayManagerCall)
        assertTrue(lockedGate >= 0 && lockedGate < dayManagerCall)
        assertTrue(rejectedDayManager >= 0 && rejectedDayManager < incrementBody.indexOf("TransitionAcceptance.ACCEPTED"))

        val dayManager = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/DayManager.kt").readText()
        val dayIncrementStart = dayManager.indexOf("fun acceptReelTransition(")
        val dayIncrementEnd = dayManager.indexOf("private fun isCountAuthorized", dayIncrementStart)
        val dayIncrementBody = dayManager.substring(dayIncrementStart, dayIncrementEnd)
        assertTrue(dayIncrementBody.contains("isRestoring"))
        assertTrue(dayIncrementBody.contains("isCountAuthorized(token)"))
        assertTrue(dayIncrementBody.contains("managerScope.isActive"))
        assertTrue(dayIncrementBody.contains("dailyReelDataDao.acceptReelTransition("))
    }
}

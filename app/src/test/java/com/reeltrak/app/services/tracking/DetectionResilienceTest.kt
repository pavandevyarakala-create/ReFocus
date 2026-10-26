package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import com.reeltrak.app.services.tracking.engine.state.TransitionLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionResilienceTest {
    private fun snapshot(
        classification: SnapshotClassification,
        creator: String = "creator",
        caption: String = "reel caption",
        timestamp: Long = 100L
    ) = DetectionSnapshot(
        timestamp = timestamp,
        classification = classification,
        fingerprint = SemanticFingerprint(creator, caption, "audio", "Instagram"),
        platform = "Instagram"
    )

    @Test
    fun `missing reel anchor gets a bounded retry and a committed result is applied`() {
        val gate = InconclusiveSnapshotGate()
        val increments = mutableListOf<Int>()
        val ledger = TransitionLedger(onIncrement = { increments += 1 })
        val missingAnchor = snapshot(SnapshotClassification.INCONCLUSIVE)

        assertNull(gate.resolve(missingAnchor, 100L))
        val recovered = gate.resolve(snapshot(SnapshotClassification.COMMITTED), 100L)
        assertEquals(SnapshotClassification.COMMITTED, recovered?.classification)
        assertTrue(ledger.process(recovered!!))
        assertEquals(1, increments.size)
    }

    @Test
    fun `repeated missing anchor confirms departure without increment`() {
        val gate = InconclusiveSnapshotGate()
        val increments = mutableListOf<Int>()
        val ledger = TransitionLedger(onIncrement = { increments += 1 })
        val missingAnchor = snapshot(SnapshotClassification.INCONCLUSIVE)

        assertNull(gate.resolve(missingAnchor, 200L))
        val confirmedAway = gate.resolve(missingAnchor, 200L)
        assertEquals(SnapshotClassification.DISCARDED, confirmedAway?.classification)
        assertTrue(ledger.process(confirmedAway!!))
        assertTrue(increments.isEmpty())
    }

    @Test
    fun `missing creator metadata remains pending until a committed snapshot arrives`() {
        val gate = InconclusiveSnapshotGate()
        val increments = mutableListOf<Int>()
        val ledger = TransitionLedger(onIncrement = { increments += 1 })

        val pending = gate.resolve(
            snapshot(SnapshotClassification.PENDING, creator = ""),
            300L
        )
        assertEquals(SnapshotClassification.PENDING, pending?.classification)
        assertFalse(ledger.process(pending!!))
        assertTrue(increments.isEmpty())

        val committed = gate.resolve(snapshot(SnapshotClassification.COMMITTED, timestamp = 301L), 301L)
        assertTrue(ledger.process(committed!!))
        assertTrue(ledger.process(committed))
        assertEquals("Repeated committed analysis must not increment the same reel twice", 1, increments.size)
    }
}

package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification

/**
 * Gives a transiently incomplete tree one fresh analysis before treating a missing reel anchor
 * as a confirmed departure. The analysis queue bounds this to one delayed retry per trigger.
 */
internal class InconclusiveSnapshotGate {
    private val lock = Any()
    private var firstInconclusiveEventTime: Long? = null

    fun reset() = synchronized(lock) {
        firstInconclusiveEventTime = null
    }

    /** Returns null to request the queue's single retry; otherwise returns the snapshot to apply. */
    fun resolve(snapshot: DetectionSnapshot, eventTime: Long): DetectionSnapshot? = synchronized(lock) {
        if (!snapshot.isInconclusive) {
            firstInconclusiveEventTime = null
            return@synchronized snapshot
        }

        if (firstInconclusiveEventTime != eventTime) {
            firstInconclusiveEventTime = eventTime
            return@synchronized null
        }

        firstInconclusiveEventTime = null
        snapshot.copy(classification = SnapshotClassification.DISCARDED)
    }
}

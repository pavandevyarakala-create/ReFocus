package com.reeltrak.app.services.tracking

import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot

/**
 * Gives a transiently incomplete tree one fresh analysis without treating a repeated read of the
 * same trigger as proof that the user left the reel. The service owns the bounded fail-safe timer.
 */
internal class InconclusiveSnapshotGate {
    private val lock = Any()
    private var firstInconclusiveEventTime: Long? = null

    fun reset() = synchronized(lock) {
        firstInconclusiveEventTime = null
    }

    fun markInconclusive(eventTime: Long) = synchronized(lock) {
        if (firstInconclusiveEventTime == null) firstInconclusiveEventTime = eventTime
    }

    val recoveryDeadlineMs: Long?
        get() = synchronized(lock) { firstInconclusiveEventTime?.plus(MAX_RECOVERY_MS) }

    fun hasPendingRecovery(): Boolean = synchronized(lock) {
        firstInconclusiveEventTime != null
    }

    /** Returns null for an inconclusive read, both on its first pass and its bounded retry. */
    fun resolve(snapshot: DetectionSnapshot, eventTime: Long): DetectionSnapshot? = synchronized(lock) {
        if (!snapshot.isInconclusive) {
            firstInconclusiveEventTime = null
            return@synchronized snapshot
        }

        if (firstInconclusiveEventTime == null) firstInconclusiveEventTime = eventTime
        null
    }

    companion object {
        // The tree reader retries null roots twice at 40 ms and the queue retries once after
        // 25 ms. One second allows those IPC/layout passes plus several UI frames, then fails
        // safe if no valid reel or confirmed non-reel tree arrives.
        const val MAX_RECOVERY_MS = 1_000L
    }
}

package com.reeltrak.app.services.accessibility.snapshot

import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the generation lifecycle of snapshots.
 * Prevents blocked IPC threads from returning stale state and polluting the counter.
 */
class SnapshotGenerationManager {
    private val masterGenerationId = AtomicLong(0)

    /**
     * Increments and returns a new generation ID whenever the screen becomes "dirty" (e.g., a new swipe).
     */
    fun startNewGeneration(): Long {
        return masterGenerationId.incrementAndGet()
    }

    /**
     * Checks if a worker's generation ID is still valid (i.e., no newer generations have started).
     */
    fun isGenerationValid(workerGenerationId: Long): Boolean {
        return masterGenerationId.get() == workerGenerationId
    }
}

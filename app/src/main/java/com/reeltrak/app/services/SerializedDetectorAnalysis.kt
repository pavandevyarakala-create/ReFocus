package com.reeltrak.app.services

import kotlinx.coroutines.sync.Mutex

/** Serializes detector traversal shared by reel tracking and strict-mode checks. */
internal class SerializedDetectorAnalysis {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}

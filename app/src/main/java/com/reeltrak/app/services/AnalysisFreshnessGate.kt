package com.reeltrak.app.services

/** Prevents an older asynchronous tree read from applying after a newer accessibility event. */
internal class AnalysisFreshnessGate {
    @Volatile private var newestEventTimeMs: Long = 0L

    @Synchronized
    fun observeEvent(eventTimeMs: Long) {
        newestEventTimeMs = maxOf(newestEventTimeMs, eventTimeMs)
    }

    fun isCurrent(analysisEventTimeMs: Long): Boolean = analysisEventTimeMs >= newestEventTimeMs
}

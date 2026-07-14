package com.reeltrak.app.services.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue

data class TrackedEvent(
    val id: String,
    val event: AccessibilityEvent,
    val receivedTime: Long = SystemClock.elapsedRealtime()
) {
    fun recycle() {
        try {
            event.recycle()
        } catch (e: Exception) {
            // Safe recycle
        }
    }
}

object DiagnosticsManager {
    private const val TAG = "PerfTrace"
    private val nextEventId = AtomicLong(0)
    
    // rootInActiveWindow metrics
    private val rootIpcSamples = ConcurrentLinkedQueue<Long>()
    
    // Backlog metrics
    @Volatile var eventsReceivedSec = 0
    @Volatile var eventsProcessedSec = 0

    fun generateEventId(): String {
        return "EVT-${nextEventId.incrementAndGet()}"
    }

    fun recordRootIpcDuration(durationMs: Long, isNull: Boolean) {
        rootIpcSamples.add(durationMs)
        Log.d(TAG, "[IPC] rootInActiveWindow | duration: ${durationMs}ms | isNull: $isNull")
        
        if (rootIpcSamples.size % 100 == 0) {
            printIpcStats()
        }
    }

    private fun printIpcStats() {
        val samples = rootIpcSamples.toList().sorted()
        if (samples.isEmpty()) return
        
        val count = samples.size
        val avg = samples.average()
        val median = samples[count / 2]
        val p95 = samples[(count * 0.95).toInt()]
        val max = samples.last()
        
        Log.d(TAG, "--- [STATS] rootInActiveWindow ($count samples) ---")
        Log.d(TAG, "Avg: ${"%.2f".format(avg)}ms | Median: ${median}ms | 95th: ${p95}ms | Max: ${max}ms")
    }
}

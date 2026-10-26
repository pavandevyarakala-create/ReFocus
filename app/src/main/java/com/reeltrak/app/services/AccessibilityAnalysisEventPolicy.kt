package com.reeltrak.app.services

import android.view.accessibility.AccessibilityEvent

/** Event filtering and focus-event debounce, extracted so JVM tests can exercise the policy. */
internal class AccessibilityAnalysisEventPolicy(
    private val debounceMs: Long = 50L
) {
    private var lastFocusEventTime: Long? = null

    fun isRelevant(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
        else -> false
    }

    /** Returns true for a focus event in the debounce window; the caller schedules trailing sync. */
    fun shouldDeferFocusEvent(eventType: Int, nowMs: Long): Boolean {
        if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) return false
        val previous = lastFocusEventTime
        lastFocusEventTime = nowMs
        return previous != null && nowMs - previous < debounceMs
    }

    /**
     * Resolve the foreground package without letting a transient active-root failure abort the
     * event handler before it queues the analysis. A returned accessibility root is always freed.
     */
    fun <R : Any> resolveWindowStatePackage(
        eventPackage: String?,
        isTrackedPackage: (String) -> Boolean,
        rootProvider: () -> R?,
        packageFromRoot: (R) -> String?,
        releaseRoot: (R) -> Unit
    ): String? {
        if (eventPackage != null && isTrackedPackage(eventPackage)) return eventPackage
        val root = try {
            rootProvider()
        } catch (_: Exception) {
            null
        } ?: return eventPackage

        return try {
            packageFromRoot(root) ?: eventPackage
        } catch (_: Exception) {
            eventPackage
        } finally {
            try { releaseRoot(root) } catch (_: Exception) { }
        }
    }
}

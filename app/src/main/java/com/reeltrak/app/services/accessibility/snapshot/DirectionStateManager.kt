package com.reeltrak.app.services.accessibility.snapshot

import android.os.Build
import android.view.accessibility.AccessibilityEvent

enum class ScrollDirection {
    FORWARD, BACKWARD, UNKNOWN
}

data class DirectionStateSnapshot(
    val direction: ScrollDirection,
    val feedTransitionToken: Long,
    val revision: Long
)

/**
 * Synchronously intercepts AccessibilityEvents to extract scroll direction.
 * Accurately tracks active scroll gestures, filters out overscroll/snap recoils,
 * and ensures backward state decays so legitimate forward transitions are never suppressed.
 */
class DirectionStateManager(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()

    companion object {
        /**
         * Maximum duration (ms) that directional evidence from a scroll gesture remains valid.
         * Beyond this window, the scroll gesture has completely settled and direction decays to UNKNOWN.
         */
        const val DIRECTION_VALIDITY_WINDOW_MS = 500L

        /**
         * Minimum magnitude of negative delta required to classify an isolated scroll as BACKWARD
         * when no adapter index or prior scroll history exists.
         * Overscroll bounce / recoil physics typically produce tiny deltas (|delta| < 40px).
         */
        const val MIN_BACKWARD_DELTA_PX = 40

        /**
         * Minimum magnitude of positive delta required to classify an isolated scroll as FORWARD
         * and indicate a genuine forward feed transition when no adapter index is reported.
         */
        const val MIN_FORWARD_DELTA_PX = 40
    }

    private var _currentDirection: ScrollDirection = ScrollDirection.UNKNOWN
    private var lastScrollFromIndex = -1
    private var maxVisitedFromIndex = -1
    private var lastScrollTimestamp = 0L
    private var cumulativeScrollDelta = 0
    private var inForwardGesture = false
    private var _feedTransitionToken = 0L
    private var revision = 0L

    val currentDirection: ScrollDirection
        get() {
            synchronized(lock) {
                val now = timeProvider()
                // If BACKWARD direction has expired beyond the validity window, decay to UNKNOWN
                if (_currentDirection == ScrollDirection.BACKWARD) {
                    if (now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS) {
                        _currentDirection = ScrollDirection.UNKNOWN
                    }
                }
                if (inForwardGesture && now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS) {
                    inForwardGesture = false
                }
                return _currentDirection
            }
        }

    val currentFeedTransitionToken: Long
        get() {
            synchronized(lock) {
                return _feedTransitionToken
            }
        }

    fun updateDirection(event: AccessibilityEvent) {
        val scrollDeltaX = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaX
            } else {
                0
            }
        } catch (e: Throwable) {
            0
        }

        val scrollDeltaY = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaY
            } else {
                0
            }
        } catch (e: Throwable) {
            0
        }

        // BUG-A01: Ignore horizontal scrolls (nested carousels, sliders, tab navigation)
        if (scrollDeltaX != 0 && scrollDeltaY == 0) {
            return
        }

        val source = try { event.source } catch (_: Throwable) { null }
        val viewId = try { source?.viewIdResourceName ?: "" } catch (_: Throwable) { "" }
        val className = try { source?.className?.toString() ?: event.className?.toString() ?: "" } catch (_: Throwable) { "" }
        try { source?.recycle() } catch (_: Throwable) {}

        // BUG-A01: Filter out secondary overlay containers (comments, bottom sheets, engagement panels)
        if (isSecondaryContainer(viewId, className)) {
            return
        }

        val currentFromIndex = try {
            event.fromIndex
        } catch (e: Throwable) {
            -1
        }

        val containerIdentifier = viewId.ifBlank { className }.ifBlank { null }

        updateDirection(
            eventType = event.eventType,
            scrollDeltaY = scrollDeltaY,
            fromIndex = currentFromIndex,
            containerId = containerIdentifier
        )
    }

    /** Captures direction and transition token atomically for one analysis request. */
    fun snapshot(): DirectionStateSnapshot = synchronized(lock) {
        val now = timeProvider()
        if (_currentDirection == ScrollDirection.BACKWARD &&
            now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS) {
            _currentDirection = ScrollDirection.UNKNOWN
        }
        if (inForwardGesture && now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS) {
            inForwardGesture = false
        }
        DirectionStateSnapshot(_currentDirection, _feedTransitionToken, revision)
    }

    private fun isSecondaryContainer(viewId: String, className: String): Boolean {
        if (viewId.contains("comment", ignoreCase = true) ||
            viewId.contains("bottom_sheet", ignoreCase = true) ||
            viewId.contains("engagement_panel", ignoreCase = true) ||
            viewId.contains("tray", ignoreCase = true) ||
            viewId.contains("dialog", ignoreCase = true)) {
            return true
        }
        if (className.contains("Dialog", ignoreCase = true) ||
            className.contains("PopupWindow", ignoreCase = true)) {
            return true
        }
        return false
    }

    private var lastScrollContainerId: String? = null

    fun updateDirection(
        eventType: Int,
        scrollDeltaY: Int = 0,
        fromIndex: Int = -1,
        containerId: String? = null
    ) {
        // BUG-A01: Filter out secondary overlay containers if passed directly
        if (containerId != null && isSecondaryContainer(containerId, "")) {
            return
        }

        synchronized(lock) {
            val now = timeProvider()

            when (eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    handleScrollEvent(scrollDeltaY, fromIndex, now, containerId)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    // Content or window transitions indicate UI is settling or displaying content.
                    // If the last scroll was more than the validity window ago, decay stale state.
                    if (now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS) {
                        if (_currentDirection == ScrollDirection.BACKWARD) {
                            _currentDirection = ScrollDirection.UNKNOWN
                        }
                        inForwardGesture = false
                    }
                }
            }
        }
    }

    private fun handleScrollEvent(scrollDeltaY: Int, currentFromIndex: Int, now: Long, containerId: String?) {
        // BUG-A01: If container changed (e.g. between comments and feed), reset adapter index tracking
        if (containerId != null && lastScrollContainerId != null && containerId != lastScrollContainerId) {
            lastScrollFromIndex = -1
            maxVisitedFromIndex = -1
            cumulativeScrollDelta = 0
            inForwardGesture = false
        }
        if (containerId != null) {
            lastScrollContainerId = containerId
        }

        val isNewGestureByTime = now - lastScrollTimestamp > DIRECTION_VALIDITY_WINDOW_MS
        if (isNewGestureByTime) {
            cumulativeScrollDelta = 0
            inForwardGesture = false
        }
        lastScrollTimestamp = now

        var directionDecision = ScrollDirection.UNKNOWN
        var confirmedForwardTransition = false

        // Signal 1: Adapter index progression (Strongest signal if available)
        if (currentFromIndex != -1 && lastScrollFromIndex != -1) {
            if (currentFromIndex > lastScrollFromIndex) {
                // Explicit forward adapter progression
                directionDecision = ScrollDirection.FORWARD
                if (currentFromIndex > maxVisitedFromIndex) {
                    confirmedForwardTransition = true
                    maxVisitedFromIndex = currentFromIndex
                }
                inForwardGesture = true
            } else if (currentFromIndex < lastScrollFromIndex) {
                // Explicit backward adapter progression
                directionDecision = ScrollDirection.BACKWARD
                inForwardGesture = false
            }
        } else if (currentFromIndex != -1 && lastScrollFromIndex == -1) {
            if (currentFromIndex > 0 && scrollDeltaY >= MIN_FORWARD_DELTA_PX) {
                // First scroll event arrived with adapter index > 0 and positive forward delta,
                // indicating a forward transition from the initial reel (index 0) to currentFromIndex.
                directionDecision = ScrollDirection.FORWARD
                confirmedForwardTransition = true
                maxVisitedFromIndex = currentFromIndex
                inForwardGesture = true
            } else {
                maxVisitedFromIndex = maxOf(maxVisitedFromIndex, currentFromIndex)
            }
        }

        if (currentFromIndex != -1) {
            lastScrollFromIndex = currentFromIndex
        }

        // Signal 2: DeltaY & Recoil filtering (BUG-A02)
        if (directionDecision == ScrollDirection.UNKNOWN) {
            if (scrollDeltaY <= -MIN_BACKWARD_DELTA_PX) {
                // Definite, significant backward movement
                directionDecision = ScrollDirection.BACKWARD
                cumulativeScrollDelta = scrollDeltaY
                inForwardGesture = false
            } else if (scrollDeltaY >= MIN_FORWARD_DELTA_PX) {
                // Definite, significant forward movement
                directionDecision = ScrollDirection.FORWARD
                cumulativeScrollDelta = scrollDeltaY
            } else if (scrollDeltaY > 0) {
                // Forward movement below threshold
                directionDecision = ScrollDirection.FORWARD
                cumulativeScrollDelta += scrollDeltaY
            } else if (scrollDeltaY < 0) {
                // Small negative movement (|scrollDeltaY| < MIN_BACKWARD_DELTA_PX, e.g. -15px recoil)
                // Filter out snap recoil: if we were already FORWARD, maintain FORWARD
                directionDecision = if (_currentDirection == ScrollDirection.FORWARD) {
                    ScrollDirection.FORWARD
                } else {
                    ScrollDirection.UNKNOWN
                }
            } else {
                // scrollDeltaY == 0
                directionDecision = _currentDirection
            }
        }

        if (confirmedForwardTransition) {
            _feedTransitionToken++
        }

        _currentDirection = directionDecision
        revision++
    }

    /**
     * Called when a backward transition has been processed and suppressed by ReelTrackerService.
     * Consuming the backward state prevents it from persisting to suppress the next forward transition.
     */
    fun onBackwardTransitionConsumed() {
        synchronized(lock) {
            _currentDirection = ScrollDirection.UNKNOWN
            cumulativeScrollDelta = 0
            inForwardGesture = false
            revision++
        }
    }

    /** Consume only the backward evidence used by an analysis, preserving newer scroll input. */
    fun onBackwardTransitionConsumed(expectedRevision: Long) {
        synchronized(lock) {
            if (revision != expectedRevision || _currentDirection != ScrollDirection.BACKWARD) return
            _currentDirection = ScrollDirection.UNKNOWN
            cumulativeScrollDelta = 0
            inForwardGesture = false
            revision++
        }
    }

    fun reset() {
        synchronized(lock) {
            revision++
            _currentDirection = ScrollDirection.UNKNOWN
            lastScrollFromIndex = -1
            maxVisitedFromIndex = -1
            lastScrollTimestamp = 0L
            cumulativeScrollDelta = 0
            lastScrollContainerId = null
            inForwardGesture = false
            _feedTransitionToken = 0L
        }
    }
}

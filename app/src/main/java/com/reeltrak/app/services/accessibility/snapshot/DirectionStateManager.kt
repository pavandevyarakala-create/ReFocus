package com.reeltrak.app.services.accessibility.snapshot

import android.view.accessibility.AccessibilityEvent

enum class ScrollDirection {
    FORWARD, BACKWARD, UNKNOWN
}

/**
 * Synchronously intercepts AccessibilityEvents to extract scroll direction.
 * This guarantees we never lose directional awareness even if events are conflated.
 */
class DirectionStateManager {
    private val lock = Any()
    
    @Volatile
    var currentDirection: ScrollDirection = ScrollDirection.UNKNOWN
        private set
        
    private var lastScrollFromIndex = -1

    fun updateDirection(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        
        synchronized(lock) {
            var isBackward = false
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                if (event.scrollDeltaY < 0) {
                    isBackward = true
                }
            }
            
            val currentFromIndex = event.fromIndex
            if (currentFromIndex != -1 && lastScrollFromIndex != -1) {
                if (currentFromIndex < lastScrollFromIndex) {
                    isBackward = true
                }
            }
            if (currentFromIndex != -1) {
                lastScrollFromIndex = currentFromIndex
            }
            
            currentDirection = if (isBackward) ScrollDirection.BACKWARD else ScrollDirection.FORWARD
        }
    }
    
    fun reset() {
        synchronized(lock) {
            currentDirection = ScrollDirection.UNKNOWN
            lastScrollFromIndex = -1
        }
    }
}

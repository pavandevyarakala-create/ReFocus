package com.reeltrak.app.services.accessibility.snapshot

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Replaces the queue-based EventProcessor.
 * Uses a conflated channel to drop historical events and only trigger snapshots when the UI settles.
 */
class SnapshotCoordinator(
    private val coroutineScope: CoroutineScope,
    private val directionManager: DirectionStateManager,
    private val generationManager: SnapshotGenerationManager
) {
    // CONFLATED channel guarantees we only ever process the absolute newest dirty flag.
    // If 1,000 events fire, 999 are instantly dropped. Zero backpressure.
    private val dirtySignalChannel = Channel<Unit>(Channel.CONFLATED)
    
    // We emit the validated snapshot trigger here for the Shadow Pipeline (Phase 2)
    private val _snapshotTriggers = Channel<SnapshotTrigger>(Channel.BUFFERED)
    val snapshotTriggers = _snapshotTriggers.receiveAsFlow()

    init {
        coroutineScope.launch(Dispatchers.Default) {
            for (signal in dirtySignalChannel) {
                // Wait for the trailing edge of the flurry of events (e.g. scroll settling)
                delay(50)
                
                val currentDirection = directionManager.currentDirection
                
                // If it was a backward scroll, we drop it immediately and do not trigger a snapshot.
                if (currentDirection == ScrollDirection.BACKWARD) {
                    android.util.Log.d("SnapshotCoordinator", "Dropped Snapshot Trigger: Backward Scroll")
                    continue
                }

                // Generational ID locking
                val genId = generationManager.startNewGeneration()
                _snapshotTriggers.send(SnapshotTrigger(genId))
            }
        }
    }

    /**
     * Called synchronously from AccessibilityService.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // 1. Instantly extract directional state
        directionManager.updateDirection(event)
        
        // 2. Fire dirty flag. 
        // This instantly wakes up the conflated channel (if idle) or overrides the pending element (if busy).
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                dirtySignalChannel.trySend(Unit)
            }
        }
    }
}

data class SnapshotTrigger(val generationId: Long)

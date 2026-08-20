package com.reeltrak.app.services.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.reeltrak.app.services.tracking.SessionState

enum class OverlayLifecycleState {
    NOT_CREATED,
    ATTACHING,
    VISIBLE,
    HIDDEN,
    DESTROYED
}

/**
 * Pure data class representing the complete UI state of the floating overlay.
 * Everything is immutable and updated via copy().
 */
data class OverlayState(
    val lifecycleState: OverlayLifecycleState = OverlayLifecycleState.NOT_CREATED,
    val isVisible: Boolean = false, // Keep for Compose backward compatibility, but driven by lifecycleState
    val count: Int = 0,
    val confidence: Int = 0,
    val currentApp: String? = null,
    val isOverLimit: Boolean = false,
    val sessionDurationMs: Long = 0L,
    val opacity: Float = 1.0f,
    val xOffset: Int = 0,
    val yOffset: Int = 50,
    val lastUpdateTimestamp: Long = 0L,
    val currentDateEpochDay: Long = 0L,
    val screenClassification: String = "Unknown",
    // Live Session Sync fields
    val currentSessionReels: Int = 0,
    val sessionStartTime: Long = 0L,
    val sessionState: SessionState = SessionState.IDLE,
    val dailyGoal: Int = 0
)

/**
 * Single source of truth for the Overlay's StateFlow.
 * Completely independent of WindowManager or Accessibility logic.
 */
class OverlayStateManager {
    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private val lock = Any()
    private var batchDepth = 0
    private var batchedState: OverlayState? = null

    fun beginBatch() {
        synchronized(lock) {
            if (batchDepth == 0) batchedState = _state.value
            batchDepth++
        }
    }

    fun endBatch() {
        synchronized(lock) {
            batchDepth--
            if (batchDepth <= 0) {
                batchDepth = 0
                batchedState?.let { finalState ->
                    if (_state.value != finalState) {
                        _state.value = finalState
                    }
                }
                batchedState = null
            }
        }
    }

    fun updateState(updater: (OverlayState) -> OverlayState) {
        synchronized(lock) {
            if (batchDepth > 0) {
                batchedState = updater(batchedState!!)
            } else {
                val currentState = _state.value
                val newState = updater(currentState)
                if (currentState != newState) {
                    _state.value = newState
                }
            }
        }
    }
    
    /**
     * The SINGLE SOURCE OF TRUTH for determining visibility.
     * Evaluates all current state conditions and computes the final lifecycle state.
     */
    fun evaluateVisibility(isOnReels: Boolean, isSystemOverlay: Boolean, isScreenReady: Boolean = true) {
        synchronized(lock) {
            updateState { currentState ->
                // FAIL-SAFE: If the screen is not ready (locked/off), we MUST hide, 
                // regardless of what the detector thinks.
                if (!isScreenReady) {
                    return@updateState currentState.copy(
                        lifecycleState = OverlayLifecycleState.HIDDEN,
                        isVisible = false
                    )
                }

                // Normal Counter Logic
                val shouldShowCounter = isOnReels && !isSystemOverlay && 
                                       (currentState.sessionState == SessionState.ACTIVE || currentState.sessionState == SessionState.ENTERING_REELS || currentState.sessionState == SessionState.PAUSED)
                
                val newLifecycleState = if (shouldShowCounter) {
                    OverlayLifecycleState.VISIBLE
                } else {
                    OverlayLifecycleState.HIDDEN
                }
                
                currentState.copy(
                    lifecycleState = newLifecycleState,
                    isVisible = shouldShowCounter
                )
            }
        }
    }
    
    fun reset() {
        synchronized(lock) {
            _state.value = OverlayState()
        }
    }
}

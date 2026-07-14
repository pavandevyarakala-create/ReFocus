package com.reeltrak.app.services.tracking

import android.util.Log

enum class SessionState {
    IDLE,
    ENTERING_REELS,
    ACTIVE,
    PAUSED,
    EXITING,
    DESTROYED
}

/**
 * Finite State Machine managing the Reel session lifecycle.
 */
class SessionManager(
    private val onStateChanged: (SessionState, SessionState) -> Unit
) {
    var currentState: SessionState = SessionState.IDLE
        private set

    var currentApp: String? = null
        private set
        
    var sessionStartTime: Long = 0L
        private set
        
    var lastSuccessfulDetection: Long = 0L
        private set
        
    var reelsCount: Int = 0
        private set
        
    val sessionDurationMs: Long
        get() = if (currentState != SessionState.IDLE && sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else 0L

    fun transitionTo(newState: SessionState) {
        if (currentState == newState) return
        
        Log.d("SessionDebug", "[Session] Transition: $currentState -> $newState")
        val oldState = currentState
        currentState = newState
        
        when (newState) {
            SessionState.ENTERING_REELS -> {
                sessionStartTime = System.currentTimeMillis()
                lastSuccessfulDetection = System.currentTimeMillis()
                reelsCount = 0
            }
            SessionState.IDLE, SessionState.DESTROYED -> {
                currentApp = null
                reelsCount = 0
                sessionStartTime = 0L
                lastSuccessfulDetection = 0L
            }
            else -> {}
        }
        
        onStateChanged(oldState, newState)
    }

    fun updateCurrentApp(packageName: String?) {
        if (currentApp != packageName) {
            Log.d("SessionDebug", "[Session] App Changed: $currentApp -> $packageName")
            currentApp = packageName
            if (packageName == null || !isTrackedApp(packageName)) {
                transitionTo(SessionState.IDLE)
            }
        }
    }

    fun recordSuccessfulDetection() {
        lastSuccessfulDetection = System.currentTimeMillis()
        if (currentState == SessionState.ENTERING_REELS) {
            transitionTo(SessionState.ACTIVE)
        } else if (currentState == SessionState.IDLE) {
            // Cold start detection jump: initialize session timers
            sessionStartTime = System.currentTimeMillis()
            reelsCount = 0
            transitionTo(SessionState.ACTIVE)
        } else if (currentState == SessionState.PAUSED || currentState == SessionState.EXITING) {
            transitionTo(SessionState.ACTIVE)
        }
    }
    
    fun incrementReel() {
        reelsCount++
        recordSuccessfulDetection()
    }

    fun resetSessionCount() {
        Log.d("SessionDebug", "[Session] Daily Reset: reelsCount reset to 0")
        reelsCount = 0
        sessionStartTime = System.currentTimeMillis()
    }

    fun isTrackedApp(packageName: String?): Boolean {
        return packageName != null && packageName in listOf("com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically")
    }
}

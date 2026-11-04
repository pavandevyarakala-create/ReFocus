package com.reeltrak.app.services.tracking

import android.util.Log
import com.reeltrak.app.data.security.authorization.AuthorizationToken

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
    private val onStateChanged: (SessionState, SessionState) -> Unit,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()

    @Volatile
    var currentState: SessionState = SessionState.IDLE
        private set

    @Volatile
    var currentApp: String? = null
        private set
        
    @Volatile
    var sessionStartTime: Long = 0L
        private set
        
    @Volatile
    var reelsCount: Int = 0
        private set

    @Volatile
    var activeAuthorizationToken: AuthorizationToken? = null
        private set
        
    fun transitionTo(newState: SessionState) {
        synchronized(lock) {
            if (currentState == newState) return
            
            Log.d("SessionDebug", "[Session] Transition: $currentState -> $newState")
            val oldState = currentState
            val now = timeProvider()

            currentState = newState
            
            when (newState) {
                SessionState.ENTERING_REELS -> {
                    sessionStartTime = now
                    reelsCount = 0
                }
                SessionState.IDLE, SessionState.DESTROYED -> {
                    currentApp = null
                    reelsCount = 0
                    sessionStartTime = 0L
                }
                else -> {}
            }
            
            onStateChanged(oldState, newState)
        }
    }

    private var consecutiveNullAppCount = 0

    fun updateCurrentApp(packageName: String?) {
        synchronized(lock) {
            if (packageName == null) {
                consecutiveNullAppCount++
                // BUG-G01: Transient null active window protection
                // A temporary null root during window transitions must not immediately destroy an active session.
                // Distinguish transient unavailability from persistent exit.
                if (consecutiveNullAppCount >= 2 && currentState != SessionState.IDLE) {
                    Log.d("SessionDebug", "[Session] Confirmed app exit on persistent null window -> IDLE")
                    transitionTo(SessionState.IDLE)
                    currentApp = null
                }
                return
            }

            consecutiveNullAppCount = 0

            if (currentApp != packageName) {
                Log.d("SessionDebug", "[Session] App Changed: $currentApp -> $packageName")
                
                // If switching away from an active session, end and persist it BEFORE updating currentApp
                if (currentState == SessionState.ACTIVE || 
                    currentState == SessionState.ENTERING_REELS || 
                    currentState == SessionState.PAUSED ||
                    currentState == SessionState.EXITING) {
                    transitionTo(SessionState.IDLE)
                }

                currentApp = packageName
                if (!isTrackedApp(packageName)) {
                    if (currentState != SessionState.IDLE) {
                        transitionTo(SessionState.IDLE)
                    }
                }
            }
        }
    }

    fun recordSuccessfulDetection() {
        synchronized(lock) {
            val now = timeProvider()
            if (currentState == SessionState.ENTERING_REELS) {
                transitionTo(SessionState.ACTIVE)
            } else if (currentState == SessionState.IDLE) {
                // Cold start detection jump: initialize session timers
                sessionStartTime = now
                reelsCount = 0
                transitionTo(SessionState.ACTIVE)
            } else if (currentState == SessionState.PAUSED || currentState == SessionState.EXITING) {
                transitionTo(SessionState.ACTIVE)
            }
        }
    }
    
    fun updateAuthorization(token: AuthorizationToken?) {
        synchronized(lock) {
            activeAuthorizationToken = token
            if (token == null && currentState != SessionState.IDLE) {
                Log.d("SessionDebug", "[Session] Authorization revoked. Forcing IDLE.")
                transitionTo(SessionState.IDLE)
            }
        }
    }

    fun incrementReel(token: AuthorizationToken? = null) {
        synchronized(lock) {
            if (token != null) {
                activeAuthorizationToken = token
            }
            reelsCount++
            recordSuccessfulDetection()
        }
    }

    fun resetSessionCount() {
        synchronized(lock) {
            Log.d("SessionDebug", "[Session] Daily Reset: reelsCount reset to 0")
            reelsCount = 0
            sessionStartTime = timeProvider()
        }
    }

    fun isTrackedApp(packageName: String?): Boolean {
        return packageName != null && packageName in listOf("com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically")
    }
}

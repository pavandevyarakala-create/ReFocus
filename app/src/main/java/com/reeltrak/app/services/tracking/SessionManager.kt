package com.reeltrak.app.services.tracking

import android.util.Log
import com.reeltrak.app.data.local.entity.SessionData
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
    private val onStateChanged: (SessionState, SessionState, SessionData?) -> Unit,
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
    var lastSuccessfulDetection: Long = 0L
        private set
        
    @Volatile
    var reelsCount: Int = 0
        private set

    @Volatile
    var activeAuthorizationToken: AuthorizationToken? = null
        private set
        
    val sessionDurationMs: Long
        get() = synchronized(lock) {
            if (currentState != SessionState.IDLE && sessionStartTime > 0) {
                timeProvider() - sessionStartTime
            } else 0L
        }

    fun transitionTo(newState: SessionState): SessionData? {
        synchronized(lock) {
            if (currentState == newState) return null
            
            Log.d("SessionDebug", "[Session] Transition: $currentState -> $newState")
            val oldState = currentState
            val now = timeProvider()

            // Capture completed session snapshot BEFORE in-memory state is cleared
            val completedSession = if ((oldState == SessionState.ACTIVE || 
                                        oldState == SessionState.ENTERING_REELS || 
                                        oldState == SessionState.PAUSED ||
                                        oldState == SessionState.EXITING) &&
                                       (newState == SessionState.IDLE || newState == SessionState.DESTROYED)) {
                val app = currentApp
                val count = reelsCount
                val startTime = sessionStartTime
                if (app != null && count > 0 && startTime > 0) {
                    SessionData(
                        appName = app,
                        startTimeStamp = startTime,
                        endTimeStamp = maxOf(startTime, now),
                        reelsScrolled = count
                    )
                } else {
                    null
                }
            } else {
                null
            }

            currentState = newState
            
            when (newState) {
                SessionState.ENTERING_REELS -> {
                    sessionStartTime = now
                    lastSuccessfulDetection = now
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
            
            onStateChanged(oldState, newState, completedSession)
            return completedSession
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
            lastSuccessfulDetection = now
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

    /**
     * Extracts an immutable snapshot of the current active session.
     * Optionally resets the session counter and start time (used at midnight date rollover).
     */
    fun extractCurrentSessionSnapshot(resetCount: Boolean = false): SessionData? {
        synchronized(lock) {
            val app = currentApp
            val count = reelsCount
            val startTime = sessionStartTime
            val now = timeProvider()
            if (app != null && count > 0 && startTime > 0) {
                val snapshot = SessionData(
                    appName = app,
                    startTimeStamp = startTime,
                    endTimeStamp = maxOf(startTime, now),
                    reelsScrolled = count
                )
                if (resetCount) {
                    reelsCount = 0
                    sessionStartTime = now
                }
                return snapshot
            }
            return null
        }
    }

    fun isTrackedApp(packageName: String?): Boolean {
        return packageName != null && packageName in listOf("com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically")
    }
}
